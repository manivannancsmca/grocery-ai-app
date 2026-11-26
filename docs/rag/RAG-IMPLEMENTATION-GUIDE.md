# Knowledge base (RAG) — implementation guide

A tour of the code for developers new to it: where each piece lives, how they fit, and how to make common changes.
Start with [RAG-ARCHITECTURE.md](RAG-ARCHITECTURE.md) for the picture.

## 1. Packages (`services/ai-orchestrator-service/src/main/java/com/grocery/ai/`)

```
knowledge/
  KnowledgeProperties        grocery.ai.knowledge.* (all settings, validated at startup)
  KnowledgeCategory          POLICIES, APP_GUIDE, PRODUCT_GUIDE, VENDOR, DELIVERY, SUPPORT
  KnowledgeAudience          CUSTOMER, VENDOR, RESTAURANT_OWNER, DELIVERY_PARTNER, STAFF  (+ of(roles))
  KnowledgeSource            {documentId, title, category}: what the chat shows under an answer
  KnowledgeException         the API's error codes
  KnowledgeMetrics           grocery.ai.knowledge.* meters
  document/                  MySQL side and the admin API
    KnowledgeDocument (+ Text, Chunk)  entities;  repositories
    KnowledgeDocumentService           upload / replace / reindex / delete / list / status
    KnowledgeAdminController           /api/admin/ai/knowledge/**
    DocumentView                       the API's document
    KnowledgeSeeder                    starter articles into an empty knowledge base
  ingest/
    DocumentValidator   TextExtractor   TextCleaner   TextChunker
    IngestionWorker                    the background pipeline
  index/
    KnowledgeEmbeddings                Spring AI EmbeddingModel: Ollama or Bedrock
    KnowledgeIndex                     the Elasticsearch index (create, write, kNN, delete)
  search/
    KnowledgeSearch                    question → passages (with the MySQL check)
    KnowledgeQuestionDetector          deterministic routing
chat/
  KnowledgeAssist                      search before the model, the note, the controlled reply
  ChatService                          (changed) uses KnowledgeAssist, sends and stores sources
tool/
  KnowledgeTools                       searchHelpCenter, getStoreRules
```

Resources: `elasticsearch/knowledge.json` (index mapping), `db/migration/V9__knowledge_base.sql`,
`V10__ai_message_sources.sql`, `knowledge/seed/*.md` (starter articles).

## 2. How a chat turn uses it

In `ChatService.run(...)`, after the shopping commands and before the model:

```java
modelUse = modelActivity.begin();
KnowledgeAssist.Lookup lookup = knowledge.lookUp(agent, user, text);   // 1. rules decide, search if needed
toolTurn.addKnowledgeSources(lookup.sources());                        // 2. remember the sources of the turn
if (lookup.answerWithoutModel()) {                                     // 3. policy question, no article:
    ... answer KnowledgeAssist.NO_VERIFIED_ANSWER, store, done ...     //    the controlled reply, no model
    return;
}
String knowledgeNote = lookup.note();                                  // 4. the articles as an app note
List<Message> messages = prompt(agent, fitted, contextLine, knowledgeNote, text);
stream(messages, tools, toolTurn, sink, answer, finishReason);         // 5. the model (tools as before)
...
sink.sources(sources);                                                 // 6. "From the help center: …"
conversations.finish(turn, ..., unverified, sources);                  // 7. stored with the answer
```

`notBacked(...)` (the skipped-tool / invented-figures check) now counts a lookup as a lookup and adds the articles' text
to what the model was given.

The tool path: `KnowledgeTools.searchHelpCenter` returns a `ToolResult.Ok` with `sources`; `ToolGuard` adds them to the
turn (`ToolTurn.addKnowledgeSources`), so they are shown and stored the same way.

## 3. Where security happens

| Concern | Code |
|---|---|
| Who may read a document | `KnowledgeAudience.of(roles)` from the token → Elasticsearch `terms` filter (`KnowledgeIndex.search`) → checked again in MySQL (`KnowledgeSearch.current`) |
| Who may change documents | `@PreAuthorize(Roles.OPERATIONS_ADMIN)` on the controller's write methods |
| Uploaded files | `DocumentValidator`, `TextExtractor` (safe XML, no macros, size limits) |
| Articles are data | `KnowledgeAssist.Lookup.note()` wording; the note is a user-role app message, never a system message |
| Live amounts | `getStoreRules` → pricing-service; the rule `Agents.KNOWLEDGE` |

## 4. Common changes

**Add a category.** Add it to `KnowledgeCategory` (Java) and `KNOWLEDGE_CATEGORIES` / `CATEGORY_LABELS`
(`frontend/grocery-web/src/features/knowledge/api.ts`, and the JavaScript copy in `grocery-web-ui`). The column is a
`VARCHAR(20)`: no migration for a short name.

**Add an audience.** Add it to `KnowledgeAudience` and its `of(roles)` mapping, and to the frontend lists. Existing
documents keep their audiences.

**Recognise more policy questions.** Add words to `TOPIC` (or `LIVE` / `STORE_RULES`) in `KnowledgeQuestionDetector`
and a line to `KnowledgeQuestionDetectorTest`. Keep `KNOWLEDGE` for questions that are *only* about rules: those get the
controlled reply when nothing matches.

**Change chunk size, top-K or threshold.** Environment variables (see the architecture's configuration table). Run the
evaluation before and after ([RAG-TESTING-GUIDE.md](RAG-TESTING-GUIDE.md)); reindex after a chunk change.

**Use another embedding provider.** Add a branch in `KnowledgeEmbeddings`'s constructor returning a Spring AI
`EmbeddingModel` (e.g. `OpenAiEmbeddingModel`), set its dimensions, a new index version, and reindex.

**Add another file format.** Add it to `DocumentFormat` (extensions), a content check in `DocumentValidator` and an
extraction in `TextExtractor`, with a unit test in `DocumentValidationAndExtractionTest`.

## 5. Tests to look at

| Test | Shows |
|---|---|
| `knowledge/TextChunkerTest`, `TextCleanerTest`, `DocumentValidationAndExtractionTest`, `KnowledgeAudienceTest`, `KnowledgeQuestionDetectorTest` | the building blocks, without Spring |
| `KnowledgeIngestionIntegrationTest` | upload → index → versions → delete → failures, against real Elasticsearch |
| `KnowledgeSearchIntegrationTest` | audiences, threshold, the MySQL check |
| `KnowledgeChatIntegrationTest` | the chat: note, sources, controlled reply, mixed questions, both tools |
| `KnowledgeSeederIntegrationTest` | the starter articles |
| `KnowledgeEvaluationTest` | quality with the real models (not in the normal build) |

`AbstractKnowledgeIntegrationTest` starts Elasticsearch 9.4.6 in a container and uses `FakeOllama`'s bag-of-words
embeddings, so similar texts really are similar in tests.
