# ADR-0012: Deploy to AWS on ECS Fargate with blue/green, Jenkins pipelines and Terraform

- **Status:** Accepted (product owner's Phase 14 choices). Built and validated offline; nothing is applied to an AWS
  account until the product owner does it.
- **Date:** 2026-11-13

## Context

The platform is 17 Spring Boot services (the API gateway and 16 business services), Confluent Schema Registry and a
React SPA. It depends on MySQL with binlog-based change data capture (Debezium, ADR-0002), Kafka with Avro
(ADR-0011), Redis (SSE fan-out, ADR-0005) and Elasticsearch. Everything already runs in Docker (ADR-0009).

Production needs:
- each service built, deployed and rolled back on its own, without a platform-wide release;
- zero-downtime releases, with automatic rollback when a release misbehaves;
- managed data services, so the team does not operate MySQL, Kafka or Redis clusters;
- repeatable `dev`, `staging` and `prod` environments;
- a small team: as little infrastructure to patch and upgrade as possible.

## Decision

**Compute: Amazon ECS on Fargate.**
- One ECS service per deployable unit (the list and sizes live in
  `infrastructure/aws/terraform/services.auto.tfvars.json`), in private subnets across 3 AZs (2 in dev).
- **ECS Service Connect** gives services the same names and ports as locally (`order-service:8110` …), so the
  `aws` Spring profile only swaps hosts for environment variables.
- Every task has the app container and an **ADOT collector sidecar** (ADR-0013). Autoscaling on CPU and ALB request
  count; at least 2 tasks per service in prod.
- **The edge:** CloudFront serves the SPA from S3 and routes `/api/*` to an ALB with an ACM certificate in front of
  `api-gateway`. One origin for the browser, so no CORS, and the refresh cookie stays first-party.

**Managed data services**, the same topology as locally:
- **RDS for MySQL 8.4** (Multi-AZ in prod), one database and user per service, with a parameter group for Debezium
  (`binlog_format=ROW`, `binlog_row_image=FULL`, GTID, binlog retention ≥ 72 h).
- **Amazon MSK** (KRaft, TLS, SASL/SCRAM per service) and **MSK Connect** running the same Debezium connector JSON.
- **ElastiCache for Redis** (TLS, AUTH). **Elastic Cloud** for search, over PrivateLink.
- **Confluent Schema Registry** on ECS (Glue Schema Registry would change the wire format).
- Secrets in **Secrets Manager** (`grocery/<env>/<service>/<name>`), settings in **SSM** (`/grocery/<env>/…`).

**Releases: native ECS blue/green.**
- The new task definition starts next to the old one (green next to blue). Test traffic goes to the gateway's test
  listener, then production traffic is shifted (Service Connect shifts internal services).
- Bake time 5 min in staging and prod. **Automatic rollback** on CloudWatch alarms (5xx rate, unhealthy targets, task
  failures) and on the deployment circuit breaker. Optional **canary** per service: 10 % for 5 min, then 100 %.
- CodeDeploy is used only if the Terraform provider lacks the native strategy for a case.
- **Flyway at start-up, expand/contract only.** Old and new versions share the schema during the bake, so a migration
  must not break the previous release. The Phase 13 migration lint enforces this, and migrations are never rolled back.
- **Manual rollback:** `infrastructure/ci/scripts/rollback.sh <env> <service> [<revision>]` stops an in-progress
  deployment with `ROLLBACK`, or redeploys an earlier task-definition revision (itself a blue/green deployment).

**The web app: immutable releases on S3.**
- Each build goes to `s3://<bucket>/releases/<sha>/`: hashed assets cached for a year, `index.html` `no-cache`.
- `infrastructure/ci/scripts/deploy-spa.sh` switches the CloudFront S3 origin path to the new prefix and invalidates
  `/*`. Rollback re-points the origin path to an earlier prefix. No rebuild, no upload.
- `frontend/grocery-web/Dockerfile.prod` (unprivileged nginx) serves the same build for targets without CloudFront.

**Pipelines: Jenkins with a shared library.**
- `groceryServicePipeline`, `groceryFrontendPipeline` and `groceryLibraryPipeline`. Each service has its own short
  Jenkinsfile.
- **Service pipeline:** build and tests → Pact → Trivy dependency scan → image (`Dockerfile.service`, runtime only,
  non-root, OCI labels) and SBOM → Trivy image gate → ECR → schema registration → dev → smoke tests → approval →
  staging (API tests, E2E, ZAP) → approval → prod (bake + alarms) → post-deploy smoke.
- **One image per commit,** tagged `<semver>-<shortSha>` and `sha-<fullSha>`, promoted by re-tagging and never rebuilt.
  ECR repositories are immutable, scan on push and expire old images.
- Shared libraries are published to **CodeArtifact**, so services can pin library versions again (refining ADR-0011).

**Infrastructure as code: Terraform** (≥ 1.9, `hashicorp/aws` ~> 6.x, `elastic/ec`).
- Reusable modules (`network`, `ecs-service`, `rds-mysql`, `msk`, `spa-hosting` …); `envs/dev|staging|prod` compose
  them with sizes from tfvars. State in a versioned, encrypted S3 bucket with a DynamoDB lock table.
- Validated offline: `fmt`, `validate`, tflint (AWS ruleset) and Trivy config. Accepted findings are documented.

## Consequences

- ✅ No servers, AMIs or Kubernetes control plane to patch. Every service scales, deploys and rolls back alone.
- ✅ The release path is safe by default: traffic moves only after health checks; alarms and the circuit breaker
  bring the old version back within seconds during the bake; a manual rollback is one command.
- ✅ The same containers, profiles and init scripts as locally. Only addresses and credentials change.
- ✅ Managed data services bring backups, point-in-time recovery, Multi-AZ failover and patching
  (`docs/operations/disaster-recovery.md`).
- ⚠️ Cost: Fargate costs more per vCPU than EC2 and has no bin-packing; about 19 ECS services × 2 tasks in prod plus NAT
  gateways, MSK and RDS Multi-AZ have a noticeable baseline. Dev runs 1 task per service on one NAT gateway.
- ⚠️ Blue/green doubles the service's tasks during each bake. Every deployment to staging and prod takes at least
  5 minutes.
- ⚠️ Expand/contract discipline is mandatory: a careless migration breaks the version still serving traffic. The
  migration lint is a pipeline gate, not a guideline.
- ⚠️ Long-lived SSE streams cross CloudFront and the ALB: their timeouts must exceed the longest silence on a stream,
  and blue/green drains connections, so clients reconnect (which they already do, with `Last-Event-ID`).
- ⚠️ Jenkins itself is not provisioned by this repository: it needs an IAM role that can push to ECR, register task
  definitions, update the ECS services, write the SPA bucket and update the CloudFront distribution.
- ⚠️ Schema Registry on ECS is one more stateful-ish component to run (its data lives in the `_schemas` topic on MSK).

## Alternatives considered

- **Amazon EKS (Kubernetes):** more flexible (service mesh, custom autoscaling, bin-packing) but a cluster to
  operate and upgrade, for about 19 services and one team. Kept as a later path: ADR-0014.
- **ECS on EC2:** cheaper at steady load with bin-packing, but brings back instances, AMIs and capacity planning.
  Can be adopted later per service through capacity providers without changing the pipelines.
- **AWS App Runner / Elastic Beanstalk:** simpler, but too limited for Service Connect, sidecars, blue/green with
  alarms and private networking to MSK and RDS.
- **Rolling updates instead of blue/green:** fewer tasks during a release, but no bake period with instant traffic
  return, and old and new versions mixed behind the ALB.
- **CodePipeline/CodeBuild instead of Jenkins:** fully managed, but the product owner chose Jenkins. It is also
  testable entirely offline with a local Jenkins in Docker.
- **Amazon OpenSearch instead of Elastic Cloud:** rejected by the product owner. The search code targets
  Elasticsearch 9 APIs.
- **Self-managed Kafka Connect on ECS instead of MSK Connect:** more control over plugins and upgrades, but another
  cluster to run. MSK Connect runs the same Debezium plugin and connector JSON.
