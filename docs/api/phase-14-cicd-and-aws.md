# Phase 14 contract — CI/CD with Jenkins and AWS deployment

**Scope chosen by the product owner:** everything is built and **validated offline**. Nothing is applied to an AWS
account; the owner applies when ready.

**Production choices:**
- **Search:** Elastic Cloud on AWS.
- **Monitoring:** AWS managed services (ADOT → Amazon Managed Prometheus, CloudWatch Logs, X-Ray; Amazon Managed Grafana).
- **LLM:** decided later. The orchestrator keeps `AI_PROVIDER` / `OLLAMA_BASE_URL` as variables. The default deploys
  with no model endpoint, so the assistant answers `AI_UNAVAILABLE` gracefully until a provider is chosen.

Every microservice stays **independently buildable and deployable**, each with its own pipeline, image, ECS service
and Flyway migrations.

## 1. AWS target architecture

| Concern | AWS service | Notes |
|---|---|---|
| Web app | **S3 + CloudFront** (OAC, SPA fallback, security headers) | The production container image (nginx) also exists for other targets |
| API entry | **ALB** (HTTPS, ACM certificate) → `api-gateway` on ECS; CloudFront routes `/api/*` to the ALB | Optional AWS WAF managed rules |
| Services | **ECS Fargate**, one service per microservice, private subnets, **ECS Service Connect** for service-to-service names (`order-service:8110` …) | Linux/ARM64 or X86_64 (variable); autoscaling on CPU and request count |
| Images | **ECR**, one repository per service, scan on push, lifecycle (keep the last 30 tagged, untagged after 7 d) | Immutable tags |
| Libraries | **CodeArtifact** Maven repository for `grocery-common` and `grocery-event-schemas` | Services pin versions again (ADR-0011 refinement) |
| Databases | **RDS for MySQL 8.4**, Multi-AZ in prod, parameter group for Debezium (`binlog_format=ROW`, `binlog_row_image=FULL`, GTID on, binlog retention ≥ 72 h), one database and user per service | A one-off ECS task `db-init` applies the per-service database and user SQL from Secrets Manager |
| Kafka | **Amazon MSK** (KRaft, TLS); services SASL/SCRAM (secret per service), MSK Connect IAM auth | Topics created by a one-off `kafka-init` ECS task (same script as local) |
| Outbox relay | **MSK Connect** with the Debezium MySQL plugin (from S3), one connector per service (same JSON, env-specific values) | Worker config: `BinaryDataConverter`, `skip.unparseable.ddl` |
| Schemas | **Confluent Schema Registry** on ECS (backed by MSK), `schema-init` one-off task | Glue Schema Registry would change the wire format, so it is not used |
| Cache | **ElastiCache for Redis** (TLS, AUTH token), replication group, Multi-AZ in prod | |
| Search | **Elastic Cloud** deployment (Terraform `elastic/ec` provider), private connectivity (PrivateLink traffic filter), API key in Secrets Manager | |
| Secrets | **Secrets Manager**, `grocery/<env>/<service>/<name>` (DB password, Kafka SCRAM, JWT keys, approval signing key, Razorpay, Elastic API key, Redis token, the Anthropic API key of ai-orchestrator-service: placeholder `REPLACE_ME` until the operator sets it) | Injected through ECS task `secrets` |
| Config | **SSM Parameter Store** `/grocery/<env>/…` for non-secret settings; Spring profile `aws` (`application-aws.yml` per service) reads env vars | |
| Observability | **ADOT collector sidecar** per task → **AMP** (remote write), **X-Ray** (OTLP traces), **CloudWatch Logs** (awslogs driver, JSON); **AMG** workspace with the Phase 13 dashboards; AMP alert rules + CloudWatch alarms → **SNS** (email) | |
| Network | VPC across 3 AZs (2 in dev): public subnets (ALB, NAT), private subnets (tasks, RDS, MSK, Redis); VPC endpoints (ECR, S3, Secrets Manager, SSM, CloudWatch Logs, STS) | One NAT in dev, one per AZ in prod |
| CI | **Jenkins** (EC2 or ECS, out of scope to provision) with an IAM role to push to ECR and deploy to ECS | Local Jenkins in Docker for validation |

## 2. Environments

- `dev`, `staging` and `prod` share the same Terraform modules, with sizes from tfvars:
  - dev: smallest sizes, single-AZ, 1 task per service;
  - staging: like prod, smaller;
  - prod: Multi-AZ, at least 2 tasks per service.
- Names follow `grocery-<env>-<component>`. Tags: `Project=grocery`, `Environment=<env>`, `Service=<service>`,
  `ManagedBy=terraform`.

## 3. Terraform (`infrastructure/aws/terraform`)

```
bootstrap/                 S3 state bucket (versioned, encrypted) + DynamoDB lock table (applied once per account)
modules/
  network/  ecr/  codeartifact/  ecs-cluster/  ecs-service/  alb/  rds-mysql/  msk/  msk-connect/
  elasticache/  elastic-cloud/  spa-hosting/  secrets/  observability/  one-off-task/
envs/
  dev/  staging/  prod/    main.tf (composes the modules), variables.tf, <env>.tfvars, backend.tf (S3 backend)
services.auto.tfvars.json  the 19 deployable units with port, cpu, memory, health path, desired count, env, secrets,
                           whether they need DB / Kafka / Redis / Elastic
```

- **Pinned versions:** Terraform ≥ 1.9; providers `hashicorp/aws` ~> 6.x and `elastic/ec`.
- **The `ecs-service` module** is the reusable unit: task definition (app + ADOT sidecar), service, Service Connect,
  autoscaling, log group, task and execution IAM roles (least privilege), secrets and **blue/green deployment** (§5).
- **Offline validation** (Docker): `terraform fmt -check -recursive`, `terraform init -backend=false` and
  `terraform validate` for every env, **tflint** (AWS ruleset) and **Trivy config** (misconfiguration scan; accepted
  findings documented with a reason).

## 4. Images and scanning

- **Service images:** the existing multi-stage Dockerfiles stay for local use. CI uses
  `infrastructure/ci/docker/Dockerfile.service`, a runtime-only image taking the jar built by Maven in the pipeline:
  - `eclipse-temurin` JRE, non-root, layered jar, OCI labels (`org.opencontainers.image.*`, revision = git SHA).
- **Frontend:** `frontend/grocery-web/Dockerfile.prod` (build stage + `nginxinc/nginx-unprivileged`, SPA fallback,
  security headers, gzip).
- **Tags:** `<semver>-<shortSha>` and `sha-<fullSha>`; a promotion re-tags (`dev`, `staging`, `prod`) without
  rebuilding.
- **SBOM** (CycloneDX, Trivy) archived per build.
- **Gates (Trivy):**
  - the dependency scan and the image scan **fail on CRITICAL or HIGH with a fix available** (allow-list
    `.trivyignore` with reasons and expiry);
  - secret scan and IaC config scan;
  - ECR scan on push as a second opinion.

## 5. Deployment, migrations, rollback

- **ECS blue/green** (native ECS deployment strategy; CodeDeploy only if the provider lacks it):
  - two target groups and a test listener for the gateway;
  - Service Connect traffic shifting for internal services;
  - bake time 5 min in staging and prod;
  - **automatic rollback** on CloudWatch alarms (5xx rate, unhealthy targets, task failures) plus the deployment
    circuit breaker.
- **Canary** (optional per service, a variable): shift 10 % for 5 min, then 100 %.
- **Database migrations:** Flyway runs at service start-up and must be **backward compatible (expand/contract)**:
  the old and new versions run side by side during blue/green. This is enforced by the Phase 13 migration lint, and
  destructive changes need a later "contract" release.
- **Event schemas:** registered by the pipeline's `schema-register` stage before the producer deploys
  (BACKWARD_TRANSITIVE).
- **Rollback:**
  - automatic, as above;
  - manual: `infrastructure/ci/scripts/rollback.sh <env> <service> [<task-def-revision>]` redeploys the previous task
    definition;
  - migrations are never rolled back (expand/contract makes that unnecessary);
  - frontend: re-point CloudFront to the previous S3 prefix (`releases/<sha>/`).

## 6. Jenkins

- **Shared library** `infrastructure/ci/jenkins/shared-library` (`vars/groceryServicePipeline.groovy`,
  `groceryFrontendPipeline.groovy`, `groceryLibraryPipeline.groovy`). Each service has a short `Jenkinsfile` calling
  it with its name, so each builds and deploys alone.
- **Service pipeline stages:**
  1. checkout
  2. build and unit/integration tests (Maven in a Docker agent, Testcontainers through the Docker socket; JUnit and
     Jacoco reports)
  3. contract tests (Pact verify / publish files)
  4. Trivy filesystem (dependency) scan
  5. package the jar and build the image (`Dockerfile.service`), SBOM
  6. Trivy image scan
  7. push to ECR
  8. register schemas (producers)
  9. deploy **dev** (ECS blue/green, wait for stable)
  10. API smoke tests (`tests/api-tests`, tag `smoke`) against dev
  11. **manual approval** → staging (same image), with API tests, E2E and ZAP baseline
  12. **manual approval** → prod (blue/green with bake and alarms), then post-deploy smoke
  13. on failure after deploy: automatic rollback + notification
- **Frontend pipeline:** `npm ci`, tests, lint, type check, build, Trivy filesystem scan, upload to
  `s3://…/releases/<sha>/`, switch CloudFront (origin path) and invalidate, then E2E against the environment.
- **Library pipelines** (`libs/*`): build and test, compatibility gate (schemas), publish to CodeArtifact.
- **Local validation** (`infrastructure/ci/jenkins/local/`): Jenkins (JCasC, plugins pinned), a local Docker registry
  standing in for ECR, jobs for every Jenkinsfile.
  - Every Jenkinsfile passes Jenkins' declarative linter.
  - At least one service pipeline runs end to end locally (build → tests → scans → image → push to the local
    registry), with the AWS deploy stages in `DRY_RUN` mode: they render the task definition and print the
    `aws ecs` calls.
  - The repository is not a git repository yet, so local jobs read the workspace from a mounted path.

## 7. Configuration and secrets

- **Profile:** every service has an `application-aws.yml` that reads only environment variables (no secrets in files):
  - `DB_HOST`, `DB_PORT`;
  - `KAFKA_BOOTSTRAP_SERVERS` + SCRAM (`KAFKA_SASL_USERNAME`/`PASSWORD` from Secrets Manager), `SCHEMA_REGISTRY_URL`;
  - `REDIS_HOST`/`TLS`/`AUTH`, `ELASTICSEARCH_URIS` + API key;
  - `IDENTITY_JWKS_URI`, `APPROVAL_JWKS_URI`;
  - `OTEL_EXPORTER_OTLP_ENDPOINT` (sidecar `http://localhost:4318`), `GROCERY_ENV`.
- **Local stays as-is** (`docker` profile). `.env.example` never holds real secrets; the existing Razorpay values
  there should move to `.env`.

## 8. Kubernetes path (ADR)

ECS Fargate is the start: no cluster to operate, and it fits 19 services. An ADR records:
- **When to move to EKS:** many more services or teams, need for service-mesh features, custom autoscaling or
  cost/bin-packing at scale.
- **How:** the same images; Helm charts generated from `services.auto.tfvars.json`; Service Connect → Kubernetes
  Services; Secrets Manager via External Secrets; ADOT via the EKS add-on.

## 9. Acceptance (offline)

- **Terraform:** `fmt`, `validate` (dev/staging/prod), tflint and Trivy config are clean or documented.
- **Jenkins:** every Jenkinsfile lints, and a local Jenkins run of one service pipeline (and the frontend pipeline)
  passes build, tests, scans and image, and pushes to the local registry. The deploy stages print the exact AWS
  calls.
- **Images:** the production images build. Trivy image scans pass the gate, and the SBOMs are produced.
- **Profiles:** every service starts with the `aws` profile when given env vars (proved with a local "aws-like" run
  of one service against the local infrastructure).
- **Docs:** the deployment architecture doc, a deploy/rollback runbook, ADRs (deployment, observability, Kubernetes
  path) and the README CI/CD and AWS sections.
