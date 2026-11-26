# Knowledge base (RAG) — troubleshooting

First look: **Admin → Help center** (each document's status and failure reason), `GET /api/admin/ai/knowledge/status`,
the Grafana dashboard **Grocery — Knowledge base (help center)** (`grocery-knowledge-base`), and the service log
(`com.grocery.ai.knowledge`, the chat's correlation id in every line).

## Metrics

| Prometheus name | Tags | Meaning |
|---|---|---|
| `grocery_ai_knowledge_documents` | `status` | Documents per status (refreshed by every ingestion run) |
| `grocery_ai_knowledge_ingestions_total` | `outcome` = COMPLETED, FAILED, RETRY, STALE | Indexing runs |
| `grocery_ai_knowledge_ingestion_latency_seconds` | `outcome` | Time to index one document |
| `grocery_ai_knowledge_embedding_latency_seconds` | `operation` = document, question | Embedding calls |
| `grocery_ai_knowledge_search_latency_seconds` | | Vector searches in Elasticsearch |
| `grocery_ai_knowledge_retrievals_total` | `agent`, `source` = before (a recognised question) / unrecognised (any other message; NO_MATCH is normal there) / tool, `outcome` = HIT, NO_MATCH, ERROR | Retrievals for the assistants |
| `grocery_ai_knowledge_retrieved_chunks` | `agent` | Passages given to the model per retrieval |
| `grocery_ai_model_latency_seconds_count{outcome="KNOWLEDGE_NO_MATCH"}` | `agent` | Controlled replies given without the model |

A high **NO_MATCH** share means customers ask about things the help center does not cover: candidates for new
articles. **ERROR** means the knowledge base could not be reached.

## KnowledgeIngestionFailing

*At least one help-center document failed to index in the last 30 minutes.* Open Admin → Help center, filter Status =
Failed, read the reason:

| Reason | Fix |
|---|---|
| The embedding model is not installed (local: ollama pull &lt;model&gt;). | `docker exec grocery-ollama-1 ollama pull nomic-embed-text` (AWS: enable Titan Text Embeddings V2 in Bedrock → Model access), then **Reindex** |
| The embedding model could not be reached. | Local: is the `ollama` container up (`docker compose --profile ai up -d`)? AWS: Bedrock in this region, task role allowed (`bedrock:InvokeModel`)? |
| Elasticsearch could not be reached or refused the chunks. | Local: `--profile search`, `curl localhost:9200/_cluster/health`. AWS: the API key in `grocery/<env>/ai-orchestrator-service/elasticsearch` exists and has the privileges of [RAG-LOCAL-SETUP.md §6](RAG-LOCAL-SETUP.md#6-aws) |
| … returns vectors of N numbers, but the index expects M | The model and `AI_EMBEDDING_DIMENSIONS` disagree: set the right dimensions and a new `index-version`, restart, reindex |
| Indexing was interrupted too often. | The service restarted repeatedly while indexing (memory? see the ECS task's stop reason), then Reindex |

A failed **new version** does not remove the old one: the assistants keep using the previous version until a new one
is indexed.

## KnowledgeRetrievalErrors

*At least 5 help-center searches failed in 15 minutes.* The assistants answer policy questions with "I cannot check
that right now". Check Elasticsearch and the embedding model as above; `GET /api/admin/ai/knowledge/status` shows
`elasticsearchReachable`. Nothing else is affected: chat, tools and orders keep working.

## Common problems

| Symptom | Cause | Fix |
|---|---|---|
| Admin page says "The help center is switched off" (API: 503 `KNOWLEDGE_BASE_DISABLED`) | `AI_KNOWLEDGE_ENABLED` is not `true` | Docker: it is on by default; host-run prints "Help : knowledge base false" with the reason; AWS: `ai_knowledge_enabled` |
| Documents stay **Waiting** | The worker is off or cannot reach the model | Check the log for "Knowledge base ingestion worker started"; the reason appears on the document after the first try |
| Upload: 413 `DOCUMENT_TOO_LARGE` | Above 5 MB | Split the document; or raise `AI_KNOWLEDGE_MAX_FILE_SIZE` (and the nginx `client_max_body_size` of the web app's image) |
| Upload: 400 `EMPTY_DOCUMENT` for a PDF | A scanned PDF (images, no text) | Run OCR, or upload the text as `.docx` / `.md` |
| Upload: 400 `INVALID_DOCUMENT` | Renamed file, damaged, password-protected, macros | Save it again as a normal PDF / DOCX / UTF-8 text |
| Upload answers 200 and nothing changes | The same file is already in the knowledge base | The response is that document; use **New version** on it to change it |
| The assistant does not use an article that exists | Similarity below the threshold, wrong audience, or a question worded very differently (a message the rules do not recognise needs `AI_KNOWLEDGE_UNRECOGNISED_THRESHOLD`, 0.70) | **Test the search** as that audience; improve the article's wording and title; tune the thresholds with the evaluation |
| Answers say "I couldn't find verified information" too often | Missing articles, or the threshold is too high | Add articles for the NO_MATCH questions (dashboard); lower the threshold a little and run the evaluation |
| An article's amount differs from checkout | The article states an admin-set number | Remove the number from the article; the assistant reads the amounts in force from pricing-service |
| A customer sees a staff article | Its audiences include `CUSTOMER` | Change the audiences (applies at once, before the reindex finishes) |
| Chat got slower | Long articles in the prompt (large chunks / top-K) or indexing on the same CPU Ollama | Keep chunk 350 / top-K 4; indexing already waits for chats; on a GPU this disappears |
| Service does not start: "Unknown embedding provider" | `AI_EMBEDDING_PROVIDER` other than `ollama` or `bedrock` | Fix the value |

## Useful commands

```bash
# what is in the index
curl -s 'http://localhost:9200/grocery-knowledge/_search?size=3&_source_excludes=embedding' | jq '.hits.hits[]._source'
# documents per status in MySQL
docker exec grocery-mysql-1 mysql -uai_user -pai_pass ai_db \
  -e "SELECT status, COUNT(*) FROM knowledge_document GROUP BY status"
# the embedding model answers
curl -s http://localhost:11435/api/embed -d '{"model":"nomic-embed-text","input":["search_query: test"]}' | head -c 100
```
