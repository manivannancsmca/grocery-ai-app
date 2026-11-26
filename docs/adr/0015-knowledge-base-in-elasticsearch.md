# ADR-0015: The assistants' knowledge base (RAG) in ai-orchestrator-service's own Elasticsearch index

- **Status:** Accepted (2026-11-25)
- **Date:** 2026-11-25

## Context

The assistants answered only from live data (the context line and tool calls through ToolGuard and MCP). Questions
about the app's rules and how it works ("What is your refund policy?", "How do I become a vendor?") had no source: the
model either refused or, worse, answered from its general training. We want retrieval-augmented generation (RAG): help
center articles that admins upload are searched by meaning, and the assistant answers from the passages found, with
the source shown. Live facts (stock, prices, carts, orders, payments, and the amounts admins set in platform settings)
keep coming only from the business APIs.

This needs:
- a **vector store** for the chunks of the articles and their embeddings, with exact filters (who may read a document);
- an **embedding model** locally and in AWS (production's chat model, Claude, has no embeddings API);
- a place in the architecture that does not break data ownership (ADR-0001).

## Decision

**1. Where:** inside ai-orchestrator-service, package `com.grocery.ai.knowledge`. It is the only consumer; a new
microservice would need its own pipeline, Terraform, database and routes for no gain today. The package boundary keeps
it easy to move out later.

**2. Vector store: an index of ai-orchestrator-service's own in the existing Elasticsearch** (9.4.6 locally, Elastic
Cloud 9.4.6 in AWS), not a new database:
- alias `grocery-knowledge` over `grocery-knowledge_v<n>`, `dense_vector` with `hnsw` (full precision: a few thousand
  chunks need little memory) and `cosine` similarity, keyword fields for exact filters (document, generation, category,
  audiences), `dynamic: strict`;
- **ownership (ADR-0001):** the cluster is shared like the local MySQL server is shared; the data is not.
  ai-orchestrator-service owns only `grocery-knowledge*`. In AWS it gets its **own Elastic Cloud API key limited to
  those indexes** (secret `grocery/<env>/ai-orchestrator-service/elasticsearch`), so it cannot read catalog-query's
  indexes and catalog-query cannot read its index;
- MySQL (`ai_db`) keeps the documents, their extracted text and the chunks' bookkeeping; vectors live only in the index.

**3. Written with the Elasticsearch Java client, not Spring AI's `ElasticsearchVectorStore`.** Spring AI is used for
the embedding models. The vector store class was rejected because it:
- checks its index when the application starts and throws if Elasticsearch cannot be reached (an Elasticsearch outage
  would stop the chat from starting);
- asks for only 1.5 × top-K kNN candidates (poor recall with filters);
- filters with a query-string text, where the audience filter must be exact (security).

`KnowledgeIndex` creates the index without ever failing startup (like catalog-query-service's `IndexManager`).

**4. Embedding models:** `nomic-embed-text` (768 dimensions) in the local Ollama; **Amazon Titan Text Embeddings V2**
(1024 dimensions) through **Amazon Bedrock** in AWS, with the task role (`bedrock:InvokeModel` on that model only). The
provider is configuration (`AI_EMBEDDING_PROVIDER`), like `AI_PROVIDER` for the chat model. Each environment builds
its own index with its own model.

## Consequences

- No new data store to run, back up or secure. The knowledge base is optional: off by default
  (`AI_KNOWLEDGE_ENABLED`), and when Elasticsearch or the embedding model is down the chat keeps working (the assistant
  says it cannot check policies right now); the service's health never depends on it.
- A different embedding model (other dimensions) needs a new index version (`grocery.ai.knowledge.index-version`) and
  a reindex of every document.
- In AWS the operator creates the limited API key and enables the Titan model in Bedrock before switching the
  knowledge base on (docs/rag/RAG-LOCAL-SETUP.md, "AWS").
- Retrieval quality locally and in AWS differs slightly (different embedding models); the evaluation
  (docs/rag/RAG-TESTING-GUIDE.md) is run per environment.

## Alternatives considered

- **PostgreSQL + pgvector:** a new engine (container, RDS instance, backups, disaster recovery, DB lint, Terraform) for
  a few thousand vectors. Rejected.
- **Redis vector search:** Redis holds the carts in memory with eviction; not a durable home for the knowledge base.
- **MySQL:** 8.4 has no vector type.
- **Elasticsearch's own inference (`semantic_text`, ELSER):** needs ML nodes and a higher subscription than the basic
  license used locally.
- **A separate knowledge microservice:** possible later; today it would only add deployment and network hops.
