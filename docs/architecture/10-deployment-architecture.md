# 10. Deployment Architecture

## Now: local development fully in Docker Compose

```mermaid
flowchart TB
  DEV["Developer browser<br/>localhost"] --> FE["frontend container<br/>Vite dev server :6420"]
  DEV --> KUI["kafka-ui :8070"]
  FE -->|"/api"| GW["api-gateway :8080"]

  subgraph APPS["Service containers - started by Compose profiles"]
    S1["identity :8101"]
    S2["customer :8102"]
    S3["vendor :8103"]
    S4["restaurant :8104"]
    S5["catalog-command :8105"]
    S6["catalog-query :8106"]
    S7["inventory :8107"]
    S8["pricing :8108"]
    S9["cart :8109"]
    S10["order :8110"]
    S11["payment :8111"]
    S12["delivery :8112"]
    S13["notification :8113"]
    S14["review :8114"]
    S15["ai-orchestrator :8115"]
  end
  GW --> APPS

  subgraph INFRA["Infrastructure containers - always on"]
    MY[("mysql 8.4.13 :3306<br/>13 databases, 1 container")]
    KF[["kafka 4.3.2 KRaft<br/>kafka:29092 / localhost:9092"]]
    KC["kafka-connect<br/>Debezium 3.7.1 :8083"]
    ES[("elasticsearch 9.4.6 :9200")]
    RD[("redis 8.8.4 :6379")]
  end
  OL["ollama 0.40.15 :11434<br/>profile ai"]

  APPS --> MY & KF & RD
  S6 --> ES
  S15 --> OL
  MY --> KC --> KF

  subgraph INIT["One-shot init containers"]
    I1["create topics"]
    I2["register connectors"]
    I3["pull Ollama model"]
  end
  I1 --> KF
  I2 --> KC
  I3 --> OL
```

### Compose profiles

| Profile | Starts |
|---|---|
| none (default) | mysql, kafka, kafka-connect, elasticsearch, redis, kafka-ui, init containers |
| `ai` | ollama + model pull |
| `identity`, `catalog`, `inventory`, `cart`, `orders`, `realtime`, `fulfillment`, `delivery`, `review` | The services for that phase, plus api-gateway |
| `frontend` | React dev server |
| `all` | Everything |

Example: `docker compose -f infrastructure/local/docker-compose.yml --profile identity --profile frontend up -d --build`

### Memory budget

| Group | Approximate memory |
|---|---|
| Infrastructure (MySQL 768 MB, Kafka 768 MB, Connect 768 MB, ES 1 GB, Redis 128 MB, Kafka UI 256 MB) | ≈ 3.5 GB |
| 16 service containers at 320–384 MB each | ≈ 5.5 GB |
| Ollama with a 3B–8B quantized model | 3–6 GB |
| Frontend dev server | ≈ 0.3 GB |
| **Everything at once** | **≈ 13–15 GB** inside the WSL2 VM |

## Production target: AWS (Phase 14)

Built and validated **offline**: Terraform, pipelines and images are checked locally, and nothing is applied to an AWS
account until the product owner does it. Contract: `docs/api/phase-14-cicd-and-aws.md`. Decisions: ADR-0012
(ECS Fargate), ADR-0013 (OpenTelemetry → AWS managed observability) and ADR-0014 (the path to Kubernetes).

### AWS deployment

```mermaid
flowchart TB
  USER["Browser"]

  subgraph EDGE["Edge - global"]
    CF["CloudFront<br/>security headers policy, optional WAF"]
  end

  subgraph ACCOUNT["AWS account, one Region - per environment dev / staging / prod"]
    S3[("S3 SPA bucket<br/>versioned, private, OAC only<br/>releases/SHA/")]
    subgraph VPC["VPC - 3 AZs, 2 in dev"]
      subgraph PUB["Public subnets"]
        ALB["ALB - HTTPS 443, ACM<br/>prod listener + test listener"]
        NAT["NAT gateway<br/>1 in dev, 1 per AZ in prod"]
      end
      subgraph PRIV["Private subnets"]
        GW["api-gateway<br/>ECS Fargate, blue/green"]
        SVC["16 business services + Schema Registry<br/>ECS Fargate, 1 service each<br/>Service Connect: order-service:8110 ..."]
        ADOT["ADOT collector<br/>sidecar in every task"]
        RDS[("RDS MySQL 8.4<br/>Multi-AZ in prod<br/>1 database + user per service")]
        MSK[["Amazon MSK<br/>KRaft, TLS, SASL/SCRAM per service"]]
        MSKC["MSK Connect<br/>Debezium, 1 connector per service"]
        REDIS[("ElastiCache Redis<br/>TLS + AUTH, Multi-AZ in prod")]
        VPCE["VPC endpoints<br/>ECR, S3, Secrets Manager, SSM, Logs, STS"]
      end
    end
    ECR["ECR<br/>1 repository per service, scan on push"]
    SEC["Secrets Manager<br/>grocery/ENV/SERVICE/NAME"]
    SSM["SSM Parameter Store<br/>/grocery/ENV/..."]
    MON["AMP, X-Ray, CloudWatch Logs<br/>Managed Grafana, SNS"]
  end

  ES[("Elastic Cloud<br/>search, PrivateLink")]

  USER --> CF
  CF -->|"default behaviour<br/>origin path /releases/SHA"| S3
  CF -->|"/api/* - not cached,<br/>SSE streamed"| ALB
  ALB --> GW --> SVC
  SVC --> RDS
  SVC --> MSK
  SVC --> REDIS
  SVC --> ES
  RDS -->|"binlog, outbox tables"| MSKC --> MSK
  ECR -.->|"image pull"| SVC
  SEC -.->|"task secrets"| SVC
  SSM -.->|"settings"| SVC
  ADOT --> MON
```

| Concern | Service | Notes |
|---|---|---|
| Web app | S3 + CloudFront | Origin Access Control; the S3 origin path selects one release (`releases/<sha>/`); SPA fallback; response headers policy |
| API entry | CloudFront `/api/*` → ALB → `api-gateway` | Same origin as the SPA, so no CORS. ALB idle timeout and CloudFront origin timeout sized for SSE |
| Services | ECS Fargate, one service per deployable unit | Private subnets, Service Connect names, autoscaling on CPU and request count, ≥ 2 tasks in prod |
| Data | RDS MySQL, MSK + MSK Connect (Debezium), ElastiCache, Elastic Cloud | The same topology as locally: database per service, outbox → Debezium → Kafka, Avro + Schema Registry |
| Secrets / config | Secrets Manager, SSM, Spring profile `aws` | `application-aws.yml` reads environment variables only |
| One-off tasks | `db-init`, `kafka-init`, `schema-init` | The same scripts as the local init containers |

### Environments

| | dev | staging | prod |
|---|---|---|---|
| Availability zones | 2 | 3 | 3 |
| Tasks per service | 1 | like prod, smaller | ≥ 2 |
| RDS / Redis | single-AZ | Multi-AZ | Multi-AZ |
| NAT gateways | 1 | 1 per AZ | 1 per AZ |
| Blue/green bake time | none | 5 min | 5 min |
| How a deployment arrives | automatically after the build | manual approval | manual approval |

All three are composed from the same Terraform modules (`infrastructure/aws/terraform/envs/<env>`) with sizes from
tfvars. Names follow `grocery-<env>-<component>`; tags `Project`, `Environment`, `Service`, `ManagedBy=terraform`.

### CI/CD flow

```mermaid
flowchart LR
  subgraph SVCPIPE["Service pipeline - one Jenkinsfile per service, shared library"]
    direction TB
    A1["checkout"] --> A2["build + unit/integration tests<br/>Maven in Docker, Testcontainers"]
    A2 --> A3["contract tests - Pact"]
    A3 --> A4["Trivy dependency scan"]
    A4 --> A5["image: Dockerfile.service<br/>SBOM CycloneDX"]
    A5 --> A6["Trivy image scan<br/>gate: CRITICAL/HIGH with a fix"]
    A6 --> A7["push to ECR<br/>semver-shortSha, sha-fullSha"]
    A7 --> A8["register Avro schemas<br/>producers only"]
    A8 --> A9["deploy dev<br/>blue/green, wait stable"]
    A9 --> A10["API smoke tests"]
    A10 --> A11{"approve<br/>staging?"}
    A11 --> A12["deploy staging, same image<br/>API + E2E + ZAP baseline"]
    A12 --> A13{"approve<br/>prod?"}
    A13 --> A14["deploy prod<br/>blue/green, bake, alarms"]
    A14 --> A15["post-deploy smoke"]
  end

  subgraph WEBPIPE["Frontend pipeline"]
    direction TB
    F1["npm ci, tests, lint,<br/>type check, build"] --> F2["Trivy fs scan"]
    F2 --> F3["deploy-spa.sh ENV SHA<br/>upload releases/SHA/"]
    F3 --> F4["switch CloudFront origin path<br/>invalidate /*"]
    F4 --> F5["E2E against the environment"]
  end

  A9 -.->|"failure after a deploy"| RB["automatic rollback<br/>+ notification"]
  A14 -.-> RB
```

- **One image, promoted.** The image built from a commit is the image that reaches prod: promotion re-tags it
  (`dev`, `staging`, `prod`) and never rebuilds.
- **Each service alone.** Every service has its own job, image, ECR repository, ECS service and Flyway history, so one
  can be built, deployed and rolled back without touching the others.
- **Libraries** (`libs/*`) have their own pipeline: build, schema compatibility gate, publish to CodeArtifact.
- **Locally**, Jenkins runs in Docker (`infrastructure/ci/jenkins/local/`) with a local registry standing in for ECR;
  the AWS stages run in `DRY_RUN` mode and print the exact `aws` calls.

### Blue/green deployment of one service

```mermaid
sequenceDiagram
  participant J as Jenkins
  participant ECS as ECS service - blue/green
  participant G as Green tasks - new revision
  participant B as Blue tasks - current revision
  participant ALB as ALB - prod + test listener
  participant CW as CloudWatch alarms

  J->>ECS: register task definition (new image tag), update-service
  ECS->>G: start green tasks next to blue
  Note over G: Flyway applies expand-only migrations at start-up<br/>blue keeps working on the same schema
  G-->>ECS: container health checks pass
  ECS->>ALB: green behind the test listener / test traffic
  Note over ALB,G: optional canary: 10 % of production traffic for 5 min
  ECS->>ALB: shift production traffic to green
  Note over ECS,CW: bake time 5 min in staging and prod - blue stays up
  alt alarms quiet (5xx rate, unhealthy targets, task failures)
    ECS->>B: stop blue tasks
    ECS-->>J: deployment COMPLETED, services-stable
  else an alarm fires, or the circuit breaker trips
    ECS->>ALB: traffic back to blue at once
    ECS->>G: stop green tasks
    ECS-->>J: deployment ROLLED BACK
    J->>J: mark the build failed, notify
  end
```

Internal services are shifted the same way through Service Connect. Manual rollback, during or after a deployment:
`infrastructure/ci/scripts/rollback.sh <env> <service> [<revision>]` (runbook: `docs/operations/deploy-and-rollback.md`).

### Web app releases on S3 + CloudFront

```mermaid
flowchart LR
  B["Vite build<br/>dist/"] -->|"deploy-spa.sh ENV SHA"| R2["s3://bucket/releases/SHA2/<br/>assets: max-age 1 year, immutable<br/>index.html: no-cache"]
  R1["s3://bucket/releases/SHA1/<br/>previous release, kept"]
  CF["CloudFront<br/>S3 origin path"] -->|"after the switch"| R2
  CF -.->|"rollback = point back"| R1
  H["meta/release-history.tsv<br/>deploy / rollback log"]
```

A release is immutable and complete before anyone sees it: files are uploaded under a new prefix, then the origin
path is switched in one distribution update and everything is invalidated. Rolling back is the same switch to an older
prefix, with no rebuild and no upload. The production nginx image (`frontend/grocery-web/Dockerfile.prod`) serves the
same build with the same headers for targets without CloudFront.

### Observability: local vs AWS

```mermaid
flowchart LR
  subgraph APP["Every Spring service - unchanged code"]
    M["Micrometer metrics<br/>/actuator/prometheus"]
    T["OTel traces<br/>OTLP http :4318"]
    L["JSON logs on stdout<br/>traceId, correlationId"]
  end

  subgraph LOCAL["Local - compose profile observability"]
    P["Prometheus<br/>scrape 15 s, alert rules"] --> AM["Alertmanager<br/>email to Mailpit"]
    OC["otel-collector"] --> TE["Tempo"]
    AL["Alloy"] --> LO["Loki"]
    GL["Grafana<br/>6 dashboards"]
  end

  subgraph AWS["AWS - managed"]
    AD["ADOT sidecar<br/>per task"]
    AMP["Amazon Managed<br/>Prometheus"]
    XR["X-Ray"]
    CWL["CloudWatch Logs<br/>awslogs driver"]
    AMG["Amazon Managed Grafana<br/>same dashboards"]
    SNS["SNS email"]
  end

  M --> P
  T --> OC
  L --> AL
  P --> GL
  TE --> GL
  LO --> GL

  M --> AD
  T --> AD
  AD -->|"remote write"| AMP
  AD -->|"OTLP"| XR
  L --> CWL
  AMP --> AMG
  XR --> AMG
  CWL --> AMG
  AMP -->|"same alert rules"| SNS
  CWL -->|"CloudWatch alarms"| SNS
```

The services only know `OTEL_EXPORTER_OTLP_ENDPOINT` and their Prometheus endpoint, so the code is identical in both
places: only the collector behind them changes (ADR-0013). Trace sampling is 1.0 locally and 0.1 in AWS.

## In plain language

- On a laptop, **everything runs in Docker**. Nothing is installed as a Windows service. You only need Docker Desktop.
- All 13 databases share **one MySQL container** to save memory. They remain isolated by separate database users.
- **Profiles** let you start only what you are working on. Infrastructure always runs, and you add the profile for the
  current phase.
- Each Java service is built **inside Docker** with a multi-stage Dockerfile. You don't need Java or Maven installed
  to build or run the platform.
- **In AWS, the same containers run on ECS Fargate**: AWS runs them, with no servers or cluster to patch. MySQL,
  Kafka, Redis and search become managed services, so backups, failover and patching are AWS's (or Elastic's) job.
- **The website is just files** on S3, delivered by CloudFront close to the user. `/api` goes from the same address to
  the API gateway, so the browser sees one site.
- **A new version never replaces the old one in place.** ECS starts the new version next to the old one, moves the
  traffic, watches the alarms for five minutes, and only then stops the old one. If anything looks wrong, traffic goes
  back automatically. The website works the same way with release folders.
- **The pipeline is the only way into production.** It builds one image per commit, scans it, tests it in dev and
  staging, and a person approves each step towards prod.

## Key rules

- Every service is independently buildable (`docker compose up -d --build <service>`), deployable and rollbackable:
  its own pipeline, image, ECR repository, ECS service and Flyway migrations.
- **Spring profiles:** `docker` for container hostnames, `local` for running one service from the IDE against the
  containerized infrastructure, `aws` for ECS (environment variables only, no secrets in files).
- Containers have memory limits. JVMs use `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`
  locally; ECS task sizes come from `services.auto.tfvars.json`.
- Elasticsearch security is off **only locally**. Elastic Cloud uses an API key from Secrets Manager over PrivateLink.
- Secrets are in a git-ignored `.env` locally (with a committed `.env.example` that never holds real values), and in
  AWS Secrets Manager (`grocery/<env>/<service>/<name>`), injected as ECS task secrets.
- **Migrations are expand/contract.** Flyway runs at start-up, and during blue/green the old and the new version share
  the schema, so a migration must never break the previous release. Destructive changes ship in a later "contract"
  release. Migrations are never rolled back.
- **Schemas before producers:** the pipeline registers Avro schemas (`BACKWARD_TRANSITIVE`) before a producer deploys.
- **Images are immutable and promoted, never rebuilt;** tags are `<semver>-<shortSha>` and `sha-<fullSha>`.
- **Streams through every hop:** CloudFront does not cache or compress `/api/*`; the ALB idle timeout and the
  CloudFront origin response timeout must exceed the longest silence on an SSE stream (the notification stream sends a
  heartbeat every 20 s; an AI chat can wait for the model, so use ≥ 60 s at CloudFront and ≥ 120 s at the ALB).
- **The gateway sees two proxies in AWS** (CloudFront, then the ALB): the client IP for rate limiting is the
  second-to-last `X-Forwarded-For` entry there, not the last one as behind a single proxy.
- **The public origin must be allowed by the gateway's CORS check** (`grocery.gateway.allowed-origins`, env
  `GROCERY_GATEWAY_ALLOWED_ORIGINS`, default `http://localhost:6420`). The browser sends `Origin` on every POST. When
  the `Host` the gateway receives differs from that origin (CloudFront → ALB rewrites it), Spring treats the call as
  cross-origin and answers 403. Set it to `https://<site domain>` per environment. The nginx image avoids this by
  forwarding the browser's `Host`.
- **Security headers are defined twice and must match:** the CloudFront response headers policy and the nginx image
  (`frontend/grocery-web/nginx/templates/security-headers.inc.template`). CloudFront also adds
  `upgrade-insecure-requests` and HSTS.
