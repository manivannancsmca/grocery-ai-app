# Knowledge base (RAG) — testing and evaluation

## 1. Test layers

| Layer | Tests | Needs |
|---|---|---|
| Unit | `knowledge/TextChunkerTest`, `TextCleanerTest`, `DocumentValidationAndExtractionTest` (real PDF / DOCX / HTML), `KnowledgeAudienceTest`, `KnowledgeQuestionDetectorTest` (the brief's example questions, the wordings the evaluation found, store-rule questions), `KnowledgeEmbeddingsTest` (provider switch, Bedrock setup) | nothing |
| Integration (ai-orchestrator-service) | `KnowledgeIngestionIntegrationTest` (upload → index → new version → reindex → delete → failures → roles), `KnowledgeSearchIntegrationTest` (audiences, threshold, the MySQL check, outage), `KnowledgeChatIntegrationTest` (note, sources, controlled reply, mixed question, `searchHelpCenter`, store rules read before the model and the fallback to `getStoreRules`, unrecognised messages: no articles for shopping, none searched when short, articles when clearly about one), `KnowledgeSeederIntegrationTest`, `KnowledgeDisabledIntegrationTest` | Docker (MySQL 8.4.13 and Elasticsearch 9.4.6 in Testcontainers); `FakeOllama` answers `/api/embed` with bag-of-words vectors |
| Regression | the whole `ai-orchestrator-service` suite (chat, tools, MCP pilot, approvals, payments, returns, voice, warm-up…), pricing-service `PlatformSettingsIntegrationTest`, api-gateway `GatewayRoutingIntegrationTest`, the frontends' suites | Docker |
| Frontend | `grocery-web`: `chatStream.test.ts` (the `sources` event), `AssistantVoiceAndHistory.test.tsx` (sources live and reopened), `KnowledgeBase.test.tsx` (the admin page), `RoleNav.test.tsx`; the same in `grocery-web-ui` | Node 24 |
| Monitoring | `infrastructure/local/observability/prometheus/tests/alerts.test.yml` (KnowledgeIngestionFailing, KnowledgeRetrievalErrors) | Docker (promtool) |
| Terraform | `infrastructure/aws/validate.sh` (fmt, validate, mock-provider plan tests, tflint, trivy) | Docker |
| **Evaluation** | `KnowledgeEvaluationTest` with `src/test/resources/knowledge-eval/questions.json` | the real models (below) |

`FakeOllama`'s embeddings count words, so texts sharing words really are similar and retrieval tests are meaningful —
but its similarity scores are not a real model's. Retrieval **quality** is measured by the evaluation.

## 2. Running the tests

Maven runs in the project's Maven + JDK 25 container (no JDK or Maven needed on the host):

```bash
cd grocery-ai-app
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble \
  mvn -B -f services/ai-orchestrator-service/pom.xml test                           # everything
#  ... test -Dtest='Knowledge*'                                                    # only the knowledge base
```

(The first run installs `libs/grocery-common` and `libs/grocery-event-schemas`:
`mvn -f libs/<lib>/pom.xml install -DskipTests` in the same container, as `host-run/build-jars.cmd` does.)

Frontend (Node in Docker, its own `node_modules` volume):

```bash
MSYS_NO_PATHCONV=1 docker run --rm -m 3g -v "$PWD/frontend/grocery-web:/app" \
  -v grocery_frontend-node-modules:/app/node_modules -w /app node:24.22.2-alpine3.23 \
  sh -c "npm ci && npx tsc -b && npx eslint . && npx vitest run"
```

## 3. The evaluation

`KnowledgeEvaluationTest` (tag `rag-eval`, **not in the normal build**) loads the 11 starter articles into a fresh
Elasticsearch, indexes them with the **real** embedding model, then runs **44 messages**
([questions.json](../../services/ai-orchestrator-service/src/test/resources/knowledge-eval/questions.json)):

- **27 answerable** from the articles (customers, a vendor, a delivery partner), each with the article(s) that answer it
  and phrases a correct answer contains (or must not contain);
- **2 live** (the minimum order, free delivery): answered from the store rules in force;
- **7 the knowledge base cannot answer** (loyalty points, catering, alcohol, subscriptions, holiday hours, senior
  discounts, GST): the assistant must not invent an answer;
- **8 ordinary shopping messages** ("Add 2 kg organic carrots to my cart", "Did my last payment go through?", "hello"):
  retrieval and routing only; they must **not** get articles.

For each message it measures the retrieval and the routing (what the rules made of it, and whether the chat would give
the articles to the model), records the best similarity, and — unless `-Drag.eval.answers=false` — asks each question
(not the shopping messages) **through the real chat endpoint** with the real chat model, exactly as a customer would.

| Metric | Meaning |
|---|---|
| Retrieval relevance (hit@K) | an expected article is among the passages retrieved |
| Context precision | the share of retrieved passages that come from an expected article |
| Context recall | the share of expected articles retrieved |
| No-context handling | unanswerable questions for which retrieval found nothing |
| Before the model | answerable questions whose articles the chat gives the model before it answers; shopping messages that would get articles (should be none) |
| Best similarity | the lowest best match of an answerable question, the highest of an unanswerable question and of a shopping message: the data the thresholds are set from |
| Answer correctness | the answer contains an expected phrase and no forbidden one |
| Refusals | unanswerable questions answered "couldn't find / contact support" instead of an invented answer |
| Faithfulness | no figure (2+ digits) that is not in the question, the passages or the store rules; no forbidden claim |
| Latency | retrieval and full-answer times |

Run it (needs Ollama with `qwen3:4b-instruct` and `nomic-embed-text`; 15–40 minutes on a CPU):

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble \
  mvn -B -f services/ai-orchestrator-service/pom.xml test -Dtest=KnowledgeEvaluationTest -DexcludedGroups= \
      -Drag.eval.ollama=http://host.docker.internal:11435
# options: -Drag.eval.answers=false (retrieval only, ~1 minute)
#          -Drag.eval.chunkTokens=800 -Drag.eval.overlapTokens=150 -Drag.eval.topK=4 -Drag.eval.threshold=0.64
#          -Drag.eval.unrecognisedThreshold=0.70
#          calibrating thresholds: -Drag.eval.answers=false -Drag.eval.threshold=0 (every best match is reported)
#          re-checking a few questions after a change: -Drag.eval.only=Q03,Q08 (~4 minutes)
```

The report is written to `services/ai-orchestrator-service/target/rag-eval/report.md` (a summary and one row per
question). Run it again after changing chunking, top-K, the threshold, the embedding model, the articles or the
assistants' rules, and compare.

## 4. Results

Measured on 2026-11-25 on a Windows 11 laptop, everything on the CPU: Ollama in Docker with `qwen3:4b-instruct`
(chat) and `nomic-embed-text` (embeddings), the 11 starter articles (20 chunks of ~350 tokens, indexed in ~12 s),
top-K 4. The full report of the final run, one row per message:
[evaluation/report-2026-11-25.md](evaluation/report-2026-11-25.md).

### 4.1 From the first run to the final one

| Metric | Run 1: first version | Run 2: calibrated | Run 3 | Run 4: final |
|---|---|---|---|---|
| Messages | 36 (no shopping messages yet) | 44 | 44 | 44 |
| Retrieval relevance (hit@K) | 29 / 29 (100 %) | 29 / 29 (100 %) | 29 / 29 (100 %) | **29 / 29 (100 %)** |
| Context precision | 0.59 | 0.75 | 0.76 | **0.76** |
| Context recall | 1.00 | 1.00 | 1.00 | **1.00** |
| No-context handling (unanswerable: retrieval found nothing) | 0 / 7 | 4 / 7 | 4 / 7 | **4 / 7** |
| Answerable questions given the articles before the model | – | 28 / 29 | 28 / 29 | **28 / 29** |
| Shopping messages given articles (should be none) | – | 0 / 8 | 0 / 8 | **0 / 8** |
| Answer correctness | 25 / 29 (86 %)¹ | 27 / 29 (93 %) | 28 / 29 (97 %) | **29 / 29 (100 %)** |
| Refusals (unanswerable: nothing invented) | 6 / 7 (86 %) | 7 / 7 (100 %) | 7 / 7 (100 %) | **7 / 7 (100 %)** |
| Faithfulness (no figures from nowhere, no forbidden claims) | 36 / 36¹ | 36 / 36 | 36 / 36 | **36 / 36** |
| Answers showing a help-center source | 22 / 29 (76 %) | 28 / 29 (97 %) | 28 / 29 (97 %) | **28 / 29 (97 %)** |
| Retrieval latency p50 / p95 | 65 / 399 ms | 56 / 89 ms | 58 / 102 ms | **55 / 117 ms** |
| Answer latency p50 / p95 (CPU) | 26.3 / 110.2 s | 17.3 / 50.2 s | 15.2 / 44.6 s | **15.0 / 41.9 s** |

¹ Run 1's checks were more lenient: they counted Q13 (the answer said there is no live tracking) and Q08 (it invented
"send the items back") as correct and faithful. With today's checks run 1 scores 23 / 29 correct and 34 / 36 faithful.

"No-context handling" stays at 4 / 7 on purpose: the three other unanswerable questions (subscriptions, senior-citizen
refunds, GST for alcohol) share words with real articles and score above the threshold (0.65–0.71). The articles reach
the model, and it still answers "I couldn't find verified information" (refusals 7 / 7). The controlled reply without
the model covers the other four.

The one answer without a source (Q18, "Can one order have items from two different stores?") is correct: the shopping
assistant's cart tool already tells the model that a cart holds one store, and the question scored 0.685, just under the
0.70 needed for an unrecognised message.

### 4.2 Calibrating the thresholds

Run 1 used a guessed threshold of 0.6. To set it from data, the evaluation was run without answers and without a
threshold (`-Drag.eval.answers=false -Drag.eval.threshold=0`), recording each message's best similarity:

| Kind | Best similarities (nomic-embed-text) |
|---|---|
| Answerable questions (29) | 0.646 (lowest) … 0.821 |
| Questions the help center cannot answer (7) | 0.613 … 0.711 (highest) |
| Ordinary shopping messages (8) | 0.514 … 0.663 (highest) |

The ranges overlap, so no single threshold separates them, and the routing rules decide the rest:

- **0.64 for recognised policy / how-to questions** (`similarity-threshold`). Every answerable question passes; 4 of the
  7 unanswerable ones get the controlled reply without the model. The other 3 reach the model with the help-center
  rule.
- **0.70 for every other message** (`unrecognised-threshold`), which rules cannot recognise. That stays above every
  shopping message (0.663 at most), and still brings articles to unrecognised help questions such as "Can I track my
  delivery partner live on a map?" (0.728) and "Do you pick up returned items from my home?" (0.722).

The margins are small (the lowest answerable question, Q24, scores 0.646). After changing articles, the embedding model
or the thresholds, run the evaluation again.

### 4.3 What the runs found and what was changed

| Run 1 problem | Cause | Change |
|---|---|---|
| "Do you deliver alcohol?" got an invented policy | The threshold 0.6 let an unrelated passage through (0.635) | Calibrated threshold 0.64 → controlled reply |
| The minimum order (Q27) was answered without the amount | The model did not call `getStoreRules` | The app reads the store rules in force **before** the model for questions about them |
| "What do the order statuses mean?" and "How many addresses can I save?" got "couldn't find" | The rules did not recognise the wording (nor "Above what amount is delivery free?") | New how-to and store-rule wordings; any other message is searched with the 0.70 bar |
| "Can I track my delivery partner live on a map?" was answered "no live tracking" (wrong) | Not recognised ("track" looks like an order question), so the article was not given to the model up front | Unrecognised messages are searched (0.728 ≥ 0.70) |
| "Do you pick up returned items?" (Q08) got "send them back to the store" (invented) | The article only said "no pickup" | Article: the return request does not ask you to send the items back; raise a complaint if unsure (run 3) |
| "Do you accept cash on delivery?" (Q03) got "couldn't find" | The ordering and delivery articles rank above the payment article for "… on delivery", and the model missed the sentence | A "No cash on delivery" section in the payment article and a mention in the ordering article (run 4) |

The article changes are the normal way to improve the knowledge base: the evaluation shows which wording questions use,
and admins write the articles to match. The rules and thresholds are code and configuration, tested by the unit and
integration tests above.

### 4.4 Limits of these results

- **Small and hand-made:** 44 messages over 11 articles, checked with phrases, not by people. They show the design
  works and catch regressions; they are not a quality guarantee for every wording customers use.
- **One run each:** the chat model's temperature is 0.2, so a run can differ by a question or two.
- **Embedding-specific:** the thresholds are calibrated for `nomic-embed-text`. In AWS (Titan V2) they must be calibrated
  again ([RAG-LOCAL-SETUP.md §6](RAG-LOCAL-SETUP.md#6-aws)).
- **Latency** is for a 4B model on a laptop CPU. Retrieval itself (embedding + Elasticsearch + MySQL check) takes
  ~55 ms at the median; the rest is the chat model.
