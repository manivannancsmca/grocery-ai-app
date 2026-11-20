# CI/CD with Jenkins (Phase 14)

Contract: [docs/api/phase-14-cicd-and-aws.md](../../docs/api/phase-14-cicd-and-aws.md) §4–§7. Every deployable unit
builds, tests, scans and deploys **on its own**: one short `Jenkinsfile` per unit calls a pipeline of the shared
library. Nothing here touches an AWS account unless a pipeline runs with `DRY_RUN=false` on a Jenkins that has AWS
credentials; the local validation Jenkins always runs `DRY_RUN`.

```text
infrastructure/ci/
  docker/Dockerfile.service          runtime image of a Spring service from the Maven-built jar (CI only)
  docker/Dockerfile.schema-init      the schema-init one-off task image (Avro schemas + register-schemas.sh); its
                                     context is the repo root, filtered by Dockerfile.schema-init.dockerignore
  docker/.trivyignore                accepted Trivy findings of the CI images (merged with tests/security/.trivyignore)
  scripts/render-task-definition.py  register-task-definition input: live (describe + new image) or DRY_RUN template
  scripts/deploy-spa.sh, rollback.sh SPA publish / rollback, manual + automatic rollback (owned by the AWS work)
  jenkins/shared-library/            the Jenkins shared library "grocery" (vars/, src/, resources/)
  jenkins/pipelines/debezium-plugin/ Jenkinsfile of the MSK Connect plugin ZIP
  jenkins/local/                     local validation Jenkins: compose, JCasC, plugins, jobs, lint.sh, run-pipeline.sh
services/<svc>/Jenkinsfile, frontend/grocery-web/Jenkinsfile, libs/<lib>/Jenkinsfile
services/<svc>/src/main/resources/application-aws.yml
```

## Pipelines

A Jenkinsfile is a few lines, e.g. `services/pricing-service/Jenkinsfile`:

```groovy
@Library('grocery') _
groceryServicePipeline(service: 'pricing-service', port: 8108, producesEvents: true, hasContracts: false, testcontainers: true)
```

Unknown keys, wrong types and a port that differs from `infrastructure/aws/terraform/services.auto.tfvars.json` fail
the run at once. Keys and defaults: `shared-library/src/com/grocery/ci/Config.groovy` (`agentLabel` `docker`,
`environments` `[dev, staging, prod]`, `approvers` (input submitters), `notify` (e-mail), `platform` `linux/amd64`,
`mavenImage`, `mavenMemory` `3g`, `smokeGroups` / `stagingGroups` (api-tests tags) …).

Parameters of every pipeline: **`DRY_RUN`** (print every AWS call instead of making it) and **`VALIDATE_ONLY`**
(load the Jenkinsfile and the library, validate the configuration, run no stage).

### Service pipeline (`groceryServicePipeline`)

| # | Stage | What |
|---|---|---|
| 1 | Checkout | SCM checkout (locally: copy of the mounted repo); build facts in `.ci/build-info.json`: revision, version, tags |
| 2 | Build and unit/integration tests | `mvn verify` in `maven:3.9.16-eclipse-temurin-25-noble` (3 GB), Testcontainers through the Docker socket, JaCoCo agent + report added on the command line; JUnit + JaCoCo published |
| 3 | Contract tests | `hasContracts`: Pact files (`target/pacts`, consumer) archived as the file-based "publish", provider verifications required |
| 4 | Dependency, secret and config scan | Trivy `fs` (Maven deps from the CI repository, `--offline-scan`; secrets) + `config` (Dockerfiles). **Gate:** HIGH/CRITICAL with a fix → fail |
| 5 | Image and SBOM | `Dockerfile.service`: pinned `eclipse-temurin:25.0.5_8-jre-alpine`, layered jar, uid 10001, OCI labels (revision = SHA) |
| 6 | Image scan | Trivy `image` (OS + jars) with the same gate; CycloneDX SBOM `sbom-<svc>-<tag>.cdx.json` archived |
| 7 | Push | ECR `grocery/<svc>`: `<semver>-<shortSha>` and `sha-<fullSha>` (immutable); `DRY_RUN`: local registry, ECR calls printed |
| 8 | Register schemas (dev) | producers: runs the `grocery-dev-schema-init` task with `grocery/schema-init:<grocery-event-schemas version from the pom>` |
| 9 | Deploy dev | describe-task-definition → swap the `app` image → register → update-service (native ECS blue/green) → wait stable → check the new revision runs → `/grocery/dev/<svc>/image-tag`; promotion tag `dev` |
| 10 | Smoke tests (dev) | `tests/api-tests -Dgroups=smoke` against `/grocery/dev/shared/app-url` |
| 11 | **Approve staging** → Staging | same image: register schemas, deploy, API tests, E2E (Playwright), ZAP baseline; tag `staging` |
| 12 | **Approve production** → Production | register schemas, deploy (blue/green with bake + alarms, up to 60 min), post-deploy smoke; tag `prod` |
| 13 | post failure | **rollback** of the environment being deployed (`rollback.sh <env> <svc> <revision that ran before>`), never of an environment already verified; notification |

Approval stages hold no executor (`agent none` at the top; each environment allocates its own agent and unstashes
the scripts/tests it needs). Migrations run at start-up (Flyway, expand/contract) and are never rolled back.

### Frontend pipeline (`groceryFrontendPipeline`)

`npm ci` → lint → type check (`tsc -b`) → unit tests (Vitest, JUnit) → build (Vite; `dist` archived) → Trivy fs
(package-lock, secrets) + config (Dockerfile.prod) → image (`Dockerfile.prod`, nginx-unprivileged; ECR
`grocery/grocery-web` for non-AWS targets) + SBOM + image scan + push → deploy dev (`deploy-spa.sh`: S3
`releases/<sha>/`, CloudFront origin path, invalidation) → E2E (dev) → approve → staging + E2E + ZAP → approve →
prod + post-deploy check. On failure after CloudFront switched: `rollback.sh <env> frontend`.

Two optional keys: `typeCheck: false` skips `tsc` (JavaScript app), `publish: false` stops after the image scan (no
push, no deploy, no approvals). `frontend/grocery-web-ui/Jenkinsfile`, the JavaScript copy of the web app, uses both,
so it is built and checked but never replaces the deployed `grocery-web` SPA.

### Library pipelines (`groceryLibraryPipeline`)

Build and tests (JaCoCo) → **compatibility gate** (`grocery-event-schemas`: `BaselineCompatibilityTest`,
`CompatibilityGateRulesTest` – Confluent's BACKWARD_TRANSITIVE checker) → Trivy → **publish to CodeArtifact**
(`grocery` / `grocery-maven`; an existing release is never republished; SNAPSHOTs are refused). For
`grocery-event-schemas` also the **schema-init image** `grocery/schema-init:<version>` (+ scan, SBOM, push) that the
producers' "Register schemas" stages run. `DRY_RUN`: installed into the CI Maven repository (the local stand-in for
CodeArtifact), CodeArtifact calls printed. Services resolve the libraries from CodeArtifact
(`resources/com/grocery/ci/maven-settings-codeartifact.xml`); locally they are installed from the checkout.

### Debezium plugin pipeline (`groceryDebeziumPluginPipeline`, job `platform/debezium-plugin`)

Downloads the Debezium MySQL connector plugin (Maven Central, SHA-1 pinned) and `msk-config-providers` (SHA-256
pinned), assembles the MSK Connect custom-plugin ZIP, scans it (Trivy rootfs + SBOM), archives it and uploads it per
environment (approvals for staging and prod) to `s3://<SSM /grocery/<env>/shared/msk-connect-plugin-bucket>/
debezium-mysql/debezium-connector-mysql-3.7.1.Final-with-msk-config-providers.zip` (Terraform's `plugin_object_key`).

## Images, tags and promotion

- Repositories (Terraform `envs/shared`): `grocery/<unit>`, `grocery/grocery-web`, `grocery/schema-init`.
- Build tags: `<semver>-<shortSha>` (what the task definition references) and `sha-<fullSha>`; `-SNAPSHOT` is dropped
  from the Maven version. Both are immutable in ECR.
- **Promotion re-tags without rebuilding:** after a successful deployment the mutable tag `dev` / `staging` / `prod`
  is pointed at the same manifest (`aws ecr batch-get-image` + `put-image`). Staging and prod deploy the image that
  passed dev.
- Trivy (`aquasec/trivy:0.76.2`) gates the dependencies, secrets, IaC config and the image; ECR scan-on-push is the
  second opinion. Accepted findings: `tests/security/.trivyignore`, `infrastructure/ci/docker/.trivyignore` and an
  optional `<module>/.trivyignore`, each id with a reason and `exp:YYYY-MM-DD`. Fixable dependency vulnerabilities are
  fixed in the poms, not accepted.

## What a real Jenkins needs

**Agents** labelled `docker` (Linux) with: Docker (+ buildx) and access to its socket, `bash`, `curl`, `python3`,
`tar`, `sha1sum`/`sha256sum`, **AWS CLI v2**. Steps run in containers (Maven, Node, Trivy), so no JDK/Node on the
agent. The E2E and ZAP stages bind-mount the workspace, so the agent's workspace must be visible to its Docker daemon
(a normal agent, not a controller-in-Docker).

**Plugins:** see `jenkins/local/plugins.txt` (Pipeline, Declarative, Groovy Libraries, Pipeline Utility Steps,
JUnit, JaCoCo, Credentials Binding, Timestamper, Mailer, Folders, Stage View; Git for the library).

**Global library** `grocery`: Git SCM of this repository, library path `infrastructure/ci/jenkins/shared-library/`
(trusted: it runs outside the sandbox), default version `main`.

**Global properties:** `AWS_REGION` (ap-south-1), `AWS_ACCOUNT_ID`, optionally `GROCERY_ECR_REGISTRY` and
`GROCERY_AWS_CREDENTIAL_SOURCE` (`Ec2InstanceMetadata` default, `EcsContainer`, `Environment`).

**IAM** (Terraform; pass the Jenkins host role in `ci_principal_arns` of `envs/shared` and every env):

| Role | Assumed for | Used by |
|---|---|---|
| `grocery-ci` (envs/shared) | ECR push/describe/put-image (promotion), CodeArtifact token + publish | Push, promote, library publish, Maven settings |
| `grocery-<env>-deployer` (envs/<env>) | ECS register/update/describe/wait, service deployments (rollback), run-task (schema-init), iam:PassRole `grocery-<env>-*`, SSM `/grocery/<env>/*`, the SPA bucket `releases/*`, `meta/*`, CloudFront switch + invalidation | Deploy, schemas, SPA, rollback |

The library never handles AWS keys: it writes an AWS CLI profile per role (`role_arn` + `credential_source`) and the
CLI assumes the role itself.

**Credentials (Jenkins):** `grocery-<env>-admin` (username/password: the environment's bootstrap SUPER_ADMIN, used by
the API tests). E-mail notifications: set `notify:` in a Jenkinsfile and SMTP in *Manage Jenkins → System*;
otherwise notifications are log lines (`NOTIFY …`).

## Local validation Jenkins (`jenkins/local`)

Docker only; its own compose project **`grocery-ci`**:

| Container | Image | Port (127.0.0.1) |
|---|---|---|
| `grocery-ci-jenkins` | `grocery-ci/jenkins:2.580.2-lts-jdk21` (pinned `jenkins/jenkins:2.580.2-lts-jdk21` + `plugins.txt` + Docker CLI 29.9.2 + python3) | 8190 |
| `grocery-ci-registry` | `registry:2.8.3` – stands in for ECR | 5000 |

Volumes: `grocery-ci-jenkins-home`, `grocery-ci-registry-data`, `grocery-ci-m2` (Maven), `grocery-ci-npm`,
`grocery-ci-trivy-cache`.

```bash
cd infrastructure/ci/jenkins/local
export JENKINS_ADMIN_PASSWORD='choose-one'          # or .env (see .env.example)
docker compose up -d --build                         # first build ≈ 3 min (plugins)
# optional, faster first build and no Maven Central 429s: seed the CI caches from the developer caches (read-only)
MSYS_NO_PATHCONV=1 docker run --rm -v grocery-m2:/from:ro -v grocery-ci-m2:/to alpine:3.24.3 sh -c 'cp -a /from/. /to/ && chown -R 1000:1000 /to'
MSYS_NO_PATHCONV=1 docker run --rm -v grocery-trivy-cache:/from:ro -v grocery-ci-trivy-cache:/to alpine:3.24.3 sh -c 'cp -a /from/. /to/ && chown -R 1000:1000 /to'
```

- **Configuration as code** (`casc/jenkins.yaml`): admin user from `JENKINS_ADMIN_ID` / `JENKINS_ADMIN_PASSWORD`,
  3 executors on the controller (label `docker`), global properties (`GROCERY_FORCE_DRY_RUN=true`, local registry,
  dry-run account `000000000000`, region `ap-south-1`), the library `grocery`, and the jobs.
- **The repository is not a git repository:** it is mounted read-only at `/repo`. Jobs (`jobs/jobs.groovy`, one per
  Jenkinsfile: `services/*`, `libs/*`, `frontend/*`, `platform/*`, plus `seed-jobs`) hold a copy of their Jenkinsfile;
  each run copies `/repo` into its workspace (without `target`, `node_modules`, `dist`, reports) and uses a content
  hash of the sources as the revision. The library is served from a git repository inside Jenkins home, refreshed
  from `/repo` at start and by `docker exec grocery-ci-jenkins sync-shared-library.sh` (lint.sh / run-pipeline.sh do
  it). After editing a Jenkinsfile run the `seed-jobs` job.
- **Docker:** step containers are siblings started through the host socket with `--volumes-from grocery-ci-jenkins`
  (same workspace path) and run as the Jenkins user. Mounting the Docker socket gives the controller root-equivalent
  access to the host's Docker: acceptable for this local, single-user validation setup only.
- Pushes go from the Docker daemon to `localhost:5000`; promotions re-tag there (`dev`, `staging`, `prod`).

### Lint

```bash
JENKINS_ADMIN_PASSWORD=… bash infrastructure/ci/jenkins/local/lint.sh          # declarative linter
JENKINS_ADMIN_PASSWORD=… bash infrastructure/ci/jenkins/local/lint.sh --load   # + every job loaded with VALIDATE_ONLY
```

It POSTs to `/pipeline-model-converter/validate`: every library entry point (`vars/grocery*Pipeline.groovy`) and
every Jenkinsfile **together with the entry point it calls** (a Jenkinsfile only calls the library, so on its own it
has no `pipeline {}` block for the declarative linter). `--load` then runs every job with `VALIDATE_ONLY=true`: the
real Jenkins loads the Jenkinsfile and the library and validates the configuration.

### A dry-run pipeline

```bash
JENKINS_ADMIN_PASSWORD=… OUT_DIR=/tmp/ci bash infrastructure/ci/jenkins/local/run-pipeline.sh services/api-gateway --approve
JENKINS_ADMIN_PASSWORD=… bash infrastructure/ci/jenkins/local/run-pipeline.sh frontend/grocery-web --approve
```

`--approve` answers the staging and prod approval gates through the REST API. Real: checkout, Maven build and tests
(Testcontainers), Trivy gates, image, SBOM, push to the local registry, promotion tags. Printed (`DRY-RUN: aws …`):
every ECR/ECS/SSM/S3/CloudFront call, with the task definition rendered from the Terraform catalog; the API/E2E/ZAP
commands of each environment. Artifacts of a run: `.ci/reports/` (Trivy JSON/tables, gate results, SBOMs, image
labels, `aws-calls.txt` = the deploy calls in order) and `.ci/deploy/<env>/taskdef-<unit>.json`.

Stop: `docker compose down` (keeps volumes) or `docker compose down -v` (removes Jenkins home, registry and caches).

## `application-aws.yml` (contract §7)

Every Spring service has `src/main/resources/application-aws.yml`; the ECS task definition sets
`SPRING_PROFILES_ACTIVE=aws` (alone, not with `docker`). It only reads environment variables; secrets come from
Secrets Manager through the task's `secrets`. Infrastructure endpoints have no default, so a missing one stops the
service at start-up. Each file's header lists the variables it reads; in short:

| Variable | Default | Services |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_MODE` | –, 3306, –, –, `REQUIRED` | with a database |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | – | with Kafka |
| `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM` | `SASL_SSL`, `SCRAM-SHA-512` | with Kafka (JAAS built from the two secrets) |
| `SCHEMA_REGISTRY_URL` / `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` | `http://schema-registry:8081` | producers/consumers of Avro events |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_TLS`, `REDIS_AUTH` | –, 6379, `true`, empty | gateway, identity, pricing, cart, delivery, notification |
| `ELASTICSEARCH_URIS`, `ELASTICSEARCH_API_KEY` | – | catalog-query |
| `JWT_ISSUER`, `IDENTITY_JWKS_URI` | –, `http://identity-service:8101/.well-known/jwks.json` | all |
| `APPROVAL_JWKS_URI` | the orchestrator's approval JWKS | vendor, restaurant, inventory, pricing, order, payment, delivery |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACING_SAMPLING_PROBABILITY`, `GROCERY_ENV` | `http://localhost:4318` (ADOT sidecar), 0.1, – | all |
| `<UNIT>_SERVICE_URL` | Service Connect names (`http://pricing-service:8108` …) | cart, order (defaults), gateway (set by Terraform) |
| `SPRING_MAIL_*`, `APP_BASE_URL`, `GROCERY_MAIL_ENABLED` | port 587, STARTTLS required | notification |
| `AI_APPROVAL_SIGNING_KEY` (required), `OLLAMA_BASE_URL` (unset → `AI_UNAVAILABLE`), `GROCERY_AI_GATEWAY_URL` | | ai-orchestrator |

Logs are ECS JSON on stdout; `/actuator/health/{liveness,readiness}` and `/actuator/prometheus` are exposed (the
gateway: on its internal management port 9080 only; its ALB check is `GET /api/health/gateway`).

**Local "aws-like" run** (the docker stack has no TLS, SASL or Redis AUTH; everything else is the aws profile as is):

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network grocery_default --read-only --tmpfs /tmp --memory 512m \
  -e SPRING_PROFILES_ACTIVE=aws -e GROCERY_ENV=local-aws \
  -e DB_HOST=mysql -e DB_USERNAME=pricing_user -e DB_PASSWORD=pricing_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:29092 -e KAFKA_SECURITY_PROTOCOL=PLAINTEXT \
  -e KAFKA_SASL_USERNAME=unused -e KAFKA_SASL_PASSWORD=unused \
  -e REDIS_HOST=redis -e REDIS_TLS=false \
  -e SCHEMA_REGISTRY_URL=http://schema-registry:8081 -e JWT_ISSUER=https://identity.grocery.local \
  -e IDENTITY_JWKS_URI=http://identity-service:8101/.well-known/jwks.json \
  -e APPROVAL_JWKS_URI=http://ai-orchestrator-service:8115/.well-known/approval-jwks.json \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
  <image built by the pipeline, or grocery/pricing-service:dev with -v <dir with application-aws.yml>:/config:ro
   -e SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/>
```

`DB_SSL_MODE` stays `REQUIRED`: the local MySQL 8.4 container serves TLS with its generated certificates.

## Known limitations

- The admin connector routes of the gateway (`KAFKA_CONNECT_URL`) have no AWS counterpart (MSK Connect has no
  Connect REST API).
- Real runs need the Jenkins host role in `ci_principal_arns`; the Debezium plugin upload needs `s3:PutObject` /
  `s3:GetObject` on the plugin bucket for `grocery-<env>-deployer` (see the report of this phase).
- E2E and ZAP against AWS environments need a normal Linux agent (they bind-mount their folder).
