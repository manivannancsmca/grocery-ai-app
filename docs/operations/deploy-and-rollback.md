# Runbook: deploy and roll back

How a change reaches `dev`, `staging` and `prod` on AWS, how to tell whether a release is healthy, and how to undo it.
Background: `docs/architecture/10-deployment-architecture.md`, ADR-0012, contract `docs/api/phase-14-cicd-and-aws.md`.

## In plain language

- **Deploying is the pipeline's job.** Every merge builds one image, tests and scans it, and deploys it to dev. A
  person approves staging, then prod. Nobody builds or pushes images by hand.
- **A release never replaces the old version in place.** The new version starts next to the old one, receives the
  traffic, and the old one stays ready for 5 minutes. If an alarm fires in that time, ECS moves the traffic back on
  its own.
- **Rolling back means running the previous version again**: the previous task definition (same image that already
  ran) or, for the website, the previous release folder. It is one command, and it does not touch the database.
- **The database only moves forward.** Migrations add, never remove, so the previous version still works on the new
  schema. A bad migration is fixed with a new migration, not undone.

## Who can do what

| Action | Who | How |
|---|---|---|
| Deploy to dev | the pipeline, on every build of the main branch | automatic |
| Promote to staging / prod | release approver (not the change's author for prod) | "approve" in the Jenkins job |
| Manual rollback | on-call engineer | `infrastructure/ci/scripts/rollback.sh`, with the Jenkins/ops IAM role |
| Disaster recovery | on-call + a second engineer | `docs/operations/disaster-recovery.md` |

Tools for manual steps: bash, AWS CLI v2, and `jq` or `python3`. Every script accepts `DRY_RUN=1`, which prints the
exact `aws` calls without making any: use it first when in doubt.

## 1. Deploying a service

The service pipeline (`infrastructure/ci/jenkins/shared-library/vars/groceryServicePipeline.groovy`) runs:

1. build and tests → Pact → Trivy dependency scan → image (`infrastructure/ci/docker/Dockerfile.service`) + SBOM →
   Trivy image gate → push to ECR (`<semver>-<shortSha>`, `sha-<fullSha>`);
2. **schema registration** for producers (`BACKWARD_TRANSITIVE`) - before any producer of the new schema runs;
3. **dev:** register a task definition with the new image, `update-service`, wait for the blue/green deployment →
   API smoke tests (`tests/api-tests`, tag `smoke`);
4. **approval → staging:** the same image; API tests, E2E (Playwright) and ZAP baseline;
5. **approval → prod:** blue/green with 5 min bake and alarms → post-deploy smoke tests;
6. a failure after a deploy stage → automatic rollback + notification.

**Before approving prod**, check on the staging run:
- [ ] API, E2E and ZAP stages green; Trivy gates passed (or findings accepted in `.trivyignore` with a reason and expiry);
- [ ] migration lint passed: the release contains only expand changes, or the contract step of an earlier release;
- [ ] schemas registered without a compatibility error;
- [ ] Grafana "Service detail" for the service in staging: no new errors or latency regression since the deploy;
- [ ] for releases that change an API or an event used by others: the consumers are already deployed (or tolerant).

**While prod bakes (5 min)**, watch:
- ECS console → the service → *Deployments*: the deployment moves to `COMPLETED` (or `ROLLBACK_*`);
- Grafana (Amazon Managed Grafana) → *Platform overview* and *Service detail* (`service` = the one being deployed):
  5xx share, p95, Kafka consumer lag, dead letters;
- CloudWatch alarms of the service (5xx rate, unhealthy targets, task failures): any `ALARM` rolls back automatically.

## 2. Deploying the web app

The frontend pipeline builds and tests, then runs:

```bash
infrastructure/ci/scripts/deploy-spa.sh <env> <git-sha> [frontend/grocery-web/dist]
```

1. uploads the build to `s3://<bucket>/releases/<sha>/` (`assets/*`: `public, max-age=31536000, immutable`;
   `index.html` and other root files: `no-cache`);
2. points the CloudFront S3 origin path at `/releases/<sha>`, waits until the distribution is deployed;
3. invalidates `/*` and waits;
4. appends `deploy <sha>` to `s3://<bucket>/meta/release-history.tsv`;
5. the pipeline then runs the E2E suite against the environment.

The bucket and distribution come from `SPA_BUCKET` / `SPA_DISTRIBUTION_ID`, or the SSM parameters
`/grocery/<env>/frontend/bucket-name` and `/grocery/<env>/frontend/distribution-id`. Users with the old page open keep
working (their hashed assets are still in the old release, and the old release stays in S3); they get the new version
on their next navigation or reload.

## 3. Automatic rollback

ECS rolls a deployment back by itself when, during the deployment or the bake time:
- a CloudWatch alarm attached to the service goes to `ALARM` (5xx rate, unhealthy ALB targets, task failures), or
- the deployment circuit breaker trips (new tasks keep failing to start or to pass health checks).

What you see: the ECS deployment ends `ROLLBACK_SUCCESSFUL`, the Jenkins stage fails and notifies, the service runs
its previous revision. Then:
1. Check the green tasks' logs (CloudWatch Logs, the service's log group, filter by the deployment time) and the
   stopped tasks' *stopped reason* in the ECS console.
2. Open the failing requests' traces (X-Ray / Grafana *Order trace explorer*) to find the cause.
3. Fix forward with a new commit. Re-running the same build would deploy the same broken image again.

## 4. Manual rollback

```bash
# A service: stop an in-progress deployment, or go back to the previous ACTIVE task-definition revision
infrastructure/ci/scripts/rollback.sh <env> <service>
# A specific revision (number, family:revision or task-definition ARN)
infrastructure/ci/scripts/rollback.sh prod order-service 41
# The web app: the release deployed before the current one, or a given sha
infrastructure/ci/scripts/rollback.sh prod frontend
infrastructure/ci/scripts/rollback.sh prod frontend 2b7c9e1f0a4d4b8e9c3f5a6d7e8f9a0b1c2d3e4f
# Always possible first: print what would happen
DRY_RUN=1 infrastructure/ci/scripts/rollback.sh prod order-service
```

What `rollback.sh` does for a service (cluster `grocery-<env>-cluster`, service and task family
`grocery-<env>-<service>`; override with `ECS_CLUSTER`, `ECS_SERVICE`, `TASK_FAMILY`):
1. reads the task definition the service runs;
2. **a deployment still in progress** (blue/green bake) is stopped with `aws ecs stop-service-deployment --stop-type
   ROLLBACK`: traffic returns to blue at once. Without an explicit revision, that is the whole rollback;
3. otherwise `aws ecs update-service --task-definition <previous>`: the previous image is deployed as a normal
   blue/green deployment (bake time and alarms included, about 5-10 min in prod);
4. waits for the service to be stable (`WAIT_TIMEOUT`, default 30 min) and checks it runs the chosen revision. If ECS
   rolled *that* deployment back too, the script fails and says so.

For the web app, `rollback.sh <env> frontend` runs `deploy-spa.sh --rollback`: it reads the current origin path,
picks the release deployed before it from `meta/release-history.tsv` (or the given sha), checks
`releases/<sha>/index.html` exists, switches, invalidates and records `rollback <sha>`. It takes the time of one
distribution update (a few minutes).

**Which revision is which?** The image tag carries the commit:

```bash
aws ecs list-task-definitions --family-prefix grocery-prod-order-service --sort DESC --max-items 5
aws ecs describe-task-definition --task-definition grocery-prod-order-service:41 \
  --query 'taskDefinition.containerDefinitions[].[name,image]' --output text
```

## 5. What to do in which situation

| Symptom after a release | Action |
|---|---|
| Errors or latency rise during the bake | Nothing, or `rollback.sh <env> <service>` to stop it at once: ECS returns traffic to blue |
| Problem found after the deployment completed | `rollback.sh <env> <service>` (previous revision), then fix forward |
| New version fails to start (crash loop, health checks) | The circuit breaker rolls back by itself; read the stopped-task reason and the logs |
| A Flyway migration fails at start-up | The new tasks never become healthy, so blue keeps serving. Fix the migration in a new release. Never edit an applied migration |
| A migration succeeded but the new code is wrong | Roll back the service. The schema stays (expand-only), and the old code runs on it |
| A destructive ("contract") migration broke something | Not a rollback case: restore the data (disaster-recovery runbook, RDS PITR) or fix forward |
| A consumer mishandles a new event field or type | Roll back the consumer. Events stay in Kafka; failed ones are in `<topic>.DLT`; replay from the Operations page after the fix |
| A producer emits bad events | Roll back the producer. Clean up consumers' effects case by case (order timeline, DLT) |
| The web app is broken (blank page, JS errors) | `rollback.sh <env> frontend` |
| Web app and API changed together | Roll back the web app first, then the service, so the old UI never meets an API it does not know |
| Several services released for one feature | Roll back in reverse order of deployment: callers/consumers before the providers they started depending on |
| A configuration value (SSM) is wrong | Put back the previous value (SSM keeps parameter history), then `aws ecs update-service --force-new-deployment` |
| A secret is wrong | Restore the previous secret version (`AWSPREVIOUS` stage), then `--force-new-deployment` |
| A Debezium connector change broke the outbox relay | Update the MSK Connect connector back to the previous configuration. Rows wait in the outbox meanwhile: nothing is lost |

## 6. Rules that make rollback safe

- **Expand/contract migrations.** Release N adds (tables, nullable or defaulted columns, indexes). Code stops using
  the old structure in N or N+1. Only a later release removes it ("contract"), once no running or rollback-able
  version needs it. The migration lint enforces this in the pipeline.
- **Migrations are never rolled back** and never edited after they ran anywhere.
- **Events are backward compatible.** Schemas are `BACKWARD_TRANSITIVE` and only gain optional fields; a rolled-back
  producer simply writes an older schema version. Never delete schema versions from the registry.
- **Consumers are tolerant and idempotent**: unknown fields are ignored, duplicates are recorded as `DUPLICATE`, so
  a rollback and a later re-deploy can overlap safely.
- **Images are immutable** and kept in ECR (the last 30 tagged images per repository). A rollback further back than
  that needs a rebuild from the commit through the pipeline.
- **Old web releases stay in S3** under `releases/<sha>/`; do not delete them while they may be rolled back to.

## 7. After any rollback

1. Post in the incident channel: environment, service, from → to, the reason, the time.
2. Confirm health: post-deploy smoke tests (`tests/api-tests`, tag `smoke`), Grafana *Platform overview*, the
   service's alarms back to `OK`, dead letters not growing.
3. Stop the bad commit from being promoted again (mark the Jenkins build, revert or fix on the main branch).
4. If events were affected: check the order timeline and the Operations page (dead letters), replay after the fix.
5. Write the follow-up: cause, why staging did not catch it, which test or alarm is added.

## 8. Troubleshooting the scripts

| Message | Meaning |
|---|---|
| `service ... not found in cluster ...` | Wrong environment or names; set `ECS_CLUSTER` / `ECS_SERVICE` |
| `no ACTIVE revision of ... older than N` | Earlier revisions were deregistered; pass a revision explicitly or redeploy an older image through the pipeline |
| `... is not stable after 1800s` | The deployment is still baking or stuck: check the ECS deployment events; raise `WAIT_TIMEOUT` if it is only slow |
| `... runs X, not Y - the deployment was probably rolled back automatically` | The older revision failed its own health checks or alarms: investigate before trying again |
| `no earlier release in .../meta/release-history.tsv` | Pass the sha: list releases with `aws s3 ls s3://<bucket>/releases/` |
| `PreconditionFailed` from `update-distribution` | Someone changed the distribution meanwhile (Terraform?); run the script again |
| `expected exactly one S3 origin` | Set `SPA_ORIGIN_ID` to the CloudFront origin id of the bucket |
