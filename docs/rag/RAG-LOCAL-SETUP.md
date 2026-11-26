# Knowledge base (RAG) — setup

The knowledge base needs three things: **Elasticsearch** (the stack's own, profile `search`), the **embedding model**
(`nomic-embed-text` in the same Ollama as the chat model) and `AI_KNOWLEDGE_ENABLED=true`. Without them the assistants
work exactly as before.

## 1. Docker Compose (everything in containers)

```bash
cd infrastructure/local
docker compose --profile all up -d          # or at least: --profile search --profile ai --profile assistant
```

- `ollama-init` pulls **both** models: `qwen3:4b-instruct` (chat) and `nomic-embed-text` (embeddings, ~0.3 GB). To skip
  the embedding model set `OLLAMA_EMBEDDING_MODEL=` (empty) in `infrastructure/local/.env`.
- `ai-orchestrator-service` starts with `AI_KNOWLEDGE_ENABLED=true`, `ELASTICSEARCH_URIS=http://elasticsearch:9200`
  and `AI_KNOWLEDGE_SEED=true`: the **11 starter articles** (`src/main/resources/knowledge/seed/`) are loaded into the
  empty knowledge base and indexed in the background (a minute or two on a CPU).

Pull the embedding model by hand (an existing Ollama volume):

```bash
docker exec grocery-ollama-1 ollama pull nomic-embed-text
docker exec grocery-ollama-1 ollama list          # qwen3:4b-instruct and nomic-embed-text
```

## 2. Services on the Windows host (`infrastructure/local/host-run`)

`run-service.cmd ai-orchestrator-service` switches the knowledge base on by itself when Elasticsearch answers on
`http://localhost:9200` and Ollama lists `nomic-embed-text`; it prints what it chose:

```
 AI   : ollama - qwen3:4b-instruct at http://localhost:11435
 Voice: browser - the browser's own speech features. ...
 Help : knowledge base true - Elasticsearch http://localhost:9200, embeddings nomic-embed-text at http://localhost:11435
```

Choose yourself with `set AI_KNOWLEDGE_ENABLED=true` / `false` before running it. Rebuild the jar after code changes:
`build-jars.cmd ai-orchestrator-service`.

## 3. From the IDE

Profile `local`, plus the environment:

```
AI_KNOWLEDGE_ENABLED=true
AI_KNOWLEDGE_SEED=true
OLLAMA_BASE_URL=http://localhost:11435      # the port your Ollama container publishes
ELASTICSEARCH_URIS=http://localhost:9200
```

## 4. Check that it works

```bash
# 1. a token of the bootstrap admin
TOKEN=$(curl -s http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@grocery.local","password":"Admin@12345"}' | jq -r .accessToken)

# 2. on, reachable, documents per status
curl -s http://localhost:8080/api/admin/ai/knowledge/status -H "Authorization: Bearer $TOKEN"
# {"enabled":true,"elasticsearchReachable":true,"embeddingModel":"nomic-embed-text","index":"grocery-knowledge",
#  "documents":{"COMPLETED":11}}

# 3. what a customer would get
curl -s http://localhost:8080/api/admin/ai/knowledge/search -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"question":"Do you have cash on delivery?","audience":"CUSTOMER"}'

# 4. the index itself
curl -s 'http://localhost:9200/grocery-knowledge/_count'
```

In the app: **Admin → Help center** (`http://localhost:6420/admin/ai/knowledge`) lists the articles, uploads new ones
and has **Test the search**. As a customer, ask the assistant "What is your refund policy?": the answer shows **From the
help center: Cancellations and refunds**.

## 5. Changing settings

| Change | Then |
|---|---|
| Chunk size / overlap (`AI_KNOWLEDGE_CHUNK_TOKENS`, `AI_KNOWLEDGE_OVERLAP_TOKENS`) | Restart, then **Reindex** each document (Admin → Help center) |
| Top-K / thresholds (`AI_KNOWLEDGE_TOP_K`, `AI_KNOWLEDGE_SIMILARITY_THRESHOLD`, `AI_KNOWLEDGE_UNRECOGNISED_THRESHOLD`) | Restart; nothing to reindex |
| Another embedding model | Set `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS` and a new `grocery.ai.knowledge.index-version`, restart, reindex every document |
| Start the knowledge base over | Delete the documents in the admin page (or the `knowledge_*` rows and the index), restart with `AI_KNOWLEDGE_SEED=true` |

## 6. AWS

Production's chat model is Claude, which has no embeddings, so AWS uses **Amazon Titan Text Embeddings V2 through
Amazon Bedrock** and the knowledge base gets its **own index in the Elastic Cloud deployment**
([ADR-0015](../adr/0015-knowledge-base-in-elasticsearch.md)). Terraform already gives the task what it needs:
`ELASTICSEARCH_URIS`, the secret `grocery/<env>/ai-orchestrator-service/elasticsearch` (placeholder `api_key`), the
environment `AI_EMBEDDING_PROVIDER=bedrock`, `AI_EMBEDDING_MODEL=amazon.titan-embed-text-v2:0`,
`AI_EMBEDDING_DIMENSIONS=1024`, and `bedrock:InvokeModel` on that model only for the task role. It stays **off**
(`ai_knowledge_enabled = false` in `envs/<env>/<env>.tfvars`) until these one-time steps are done:

1. **Bedrock model access.** In the AWS console (the deployment's region): Amazon Bedrock → Model access → enable
   *Titan Text Embeddings V2*. Check that the model is offered in that region.
2. **An Elasticsearch API key limited to the knowledge index.** In Kibana Dev Tools of the Elastic Cloud deployment
   (or with the superuser secret):

   ```json
   POST /_security/api_key
   {
     "name": "ai-orchestrator-service-knowledge",
     "role_descriptors": {
       "knowledge": {
         "cluster": ["monitor"],
         "indices": [{
           "names": ["grocery-knowledge*"],
           "privileges": ["create_index", "manage", "read", "write", "view_index_metadata"]
         }]
       }
     }
   }
   ```

   Put the response's `encoded` value as `api_key` into the secret `grocery/<env>/ai-orchestrator-service/elasticsearch`.
   This key cannot read catalog-query's indexes.
3. **Switch it on:** `ai_knowledge_enabled = true` in `envs/<env>/<env>.tfvars`, apply, and deploy.
4. **Upload the articles** in Admin → Help center (the starter articles are not loaded automatically in AWS; upload
   the files of `services/ai-orchestrator-service/src/main/resources/knowledge/seed/` after removing their `---` header,
   or your own).
5. **Calibrate the thresholds for Titan.** `AI_KNOWLEDGE_SIMILARITY_THRESHOLD` (0.64) and
   `AI_KNOWLEDGE_UNRECOGNISED_THRESHOLD` (0.70) were calibrated for nomic-embed-text; Titan's scores are usually lower
   (then the assistant says "couldn't find" more often; it does not invent more). In staging, send the evaluation's
   messages ([questions.json](../../services/ai-orchestrator-service/src/test/resources/knowledge-eval/questions.json))
   to the admin test search with `"minSimilarity": 0` (`POST /api/admin/ai/knowledge/search`,
   [RAG-API-REFERENCE.md](RAG-API-REFERENCE.md)), note the best similarity of each kind as in
   [RAG-TESTING-GUIDE.md §4](RAG-TESTING-GUIDE.md#4-results), and set both values in the AI service's `env`
   (`services.auto.tfvars.json`).
