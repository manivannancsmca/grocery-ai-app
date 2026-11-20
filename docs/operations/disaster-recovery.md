# Runbook: backup and disaster recovery (AWS)

What protects each part of the platform in AWS, how much data and time a failure may cost (RPO/RTO), and what to do
in each failure. Releases that go wrong are covered by `docs/operations/deploy-and-rollback.md`. This runbook is
about losing infrastructure or data. On a developer machine (Docker Compose), a daily export of every database
protects the data instead: see "Database backups" in the README.

## In plain language

- **The databases are the truth.** Every service owns its MySQL database. Kafka carries changes between services but
  is not a backup, and Redis and Elasticsearch hold copies that can be rebuilt or are short-lived.
- **Most failures heal on their own.** A crashed task is replaced; a lost availability zone triggers automatic
  failover of the database, Redis and Kafka. Nobody has to act, only check.
- **Data mistakes are undone by restoring to a point in time**, next to the live database, and copying back only
  what was damaged. The other services keep running.
- **Losing a whole AWS Region** is the rare, slow case: the platform is rebuilt in another Region from Terraform and
  backup copies.

## 1. Targets (prod)

RPO = how much recent data may be lost. RTO = how long until the service works again. Staging aims for the same
mechanisms with relaxed times; dev has no targets (single-AZ, short backup retention).

| Failure | RPO | RTO | How |
|---|---|---|---|
| A task or a container crashes | 0 | < 2 min | ECS replaces it; ≥ 2 tasks per service keep serving |
| One availability zone is lost | 0 | < 5 min | Tasks in 3 AZs; RDS and ElastiCache Multi-AZ failover; MSK replicas in 3 AZs |
| Bad data written in one service (bug, operator error) | ≤ 5 min before the mistake | ≤ 4 h | RDS point-in-time restore to a side instance, repair in place (§3.2) |
| The RDS instance is unrecoverable | ≤ 5 min | ≤ 4 h | Point-in-time restore to a new instance (§3.3) |
| The MSK cluster is lost | 0 for business state; events in flight are re-published | ≤ 4 h | Recreate, re-publish from the outboxes (§3.4) |
| Redis is lost | carts: ≤ 24 h (last snapshot) | ≤ 1 h | Failover, or restore the latest snapshot (§3.5) |
| The Elasticsearch deployment is lost | 0 (rebuilt from the catalog) | ≤ 2 h | Snapshot restore or full reindex (§3.6) |
| Web app files deleted or broken | 0 | ≤ 30 min | S3 versioning, older release prefix, or redeploy (§3.7) |
| A Region is lost | ≤ 24 h (≤ minutes with cross-Region backup replication) | ≤ 24 h | Rebuild in a second Region (§3.9) |

These are the targets to confirm with the product owner before go-live. They assume the backup settings in §2.

## 2. What protects what

| Component | Built-in redundancy | Backups | Retention (prod) |
|---|---|---|---|
| **RDS MySQL** (all service databases) | Multi-AZ synchronous standby | Automated backups with **point-in-time restore** (transaction logs every 5 min); a manual snapshot before every contract migration and before a restore | 14 days (staging 7, dev 1); manual snapshots kept until deleted |
| **MSK** (domain topics, DLTs, Connect internal topics, `_schemas`) | 3 brokers in 3 AZs, replication factor 3, `min.insync.replicas=2` | **None in Kafka itself.** The outbox tables are the source for re-publishing | domain topics 7 d, `*.DLT` 30 d, rider locations 1 h (as `kafka-init` creates them) |
| **Outbox tables** (every producing service) | inside RDS | with RDS | rows kept 7 d after publishing (`GROCERY_OUTBOX_CLEANUP_RETENTION`) |
| **Schema Registry** | ≥ 2 tasks; data in the compacted `_schemas` topic (RF 3) | Schemas are in git (`libs/grocery-event-schemas`); **export the subjects with their ids daily to S3** (ids must survive: outbox rows and DLT records carry them) | 35 days of exports |
| **ElastiCache Redis** (carts, rate limits, SSE fan-out) | replication group, Multi-AZ automatic failover | daily automatic snapshot | 7 days |
| **Elastic Cloud** (search read model) | 2+ availability zones | automatic snapshots (Elastic Cloud default: every 30 min) | Elastic Cloud default policy |
| **S3 SPA bucket** | S3 durability | **versioning**; every release is a separate immutable `releases/<sha>/` prefix | noncurrent versions 90 days |
| **ECR** | regional service | immutable tags; last 30 tagged images per repository | 30 images; untagged 7 d |
| **Secrets Manager** | regional service | version stages (`AWSCURRENT`/`AWSPREVIOUS`); deletion has a recovery window | recovery window 30 days |
| **SSM parameters** | regional service | parameter history | 100 versions |
| **Terraform state** | S3 + DynamoDB lock | bucket **versioning** | all versions |
| **Source of truth for everything else** | git | the repository: Terraform, pipelines, connector JSON, topic scripts, schemas | |

**Gaps to close before go-live** (not automated by this repository yet; decisions for the product owner):
- the daily Schema Registry export to S3 and its restore procedure (§3.4);
- cross-Region copies for the Region-loss target: RDS cross-Region automated backup replication (or AWS Backup
  copies), ECR replication, a copy of the Terraform state bucket;
- a quarterly DR drill in staging (§4).

## 3. What to do in which failure

Every procedure starts the same way: open an incident, name one person to lead it, note the time and what is known,
and check the alarms and Grafana *Platform overview* to see what is actually affected.

### 3.1 A task, a service or an availability zone fails

Usually nothing to do. ECS replaces tasks; RDS fails over to its standby in 60-120 s (connections drop, Hikari
reconnects); ElastiCache promotes a replica; MSK clients reconnect to the remaining brokers.
- Check that every ECS service is back at its desired count and the alarms are `OK`.
- Kafka consumer lag catches up by itself. Watch *Kafka & Debezium* for a connector task that stayed `FAILED` and
  restart it (MSK Connect: update or recreate the connector).
- dev is single-AZ: wait for the AZ, or recreate it with `terraform apply`.

### 3.2 Bad data in one service's database

Example: a bug or a manual statement damaged rows in `order_db`; a contract migration dropped data still in use.
1. **Stop more damage:** roll back the release that causes it (`rollback.sh`), or, if needed, scale the service to 0
   (`aws ecs update-service --desired-count 0`; calls to its API fail at the gateway meanwhile).
2. **Find the time T** just before the damage: logs (CloudWatch Logs Insights by service), the admin order timeline,
   the Flyway history (`flyway_schema_history.installed_on`).
3. **Take a manual snapshot** of the live instance (`aws rds create-db-snapshot`), so the restore itself is reversible.
4. **Restore to a side instance:**
   `aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier grocery-prod-mysql
   --target-db-instance-identifier grocery-prod-mysql-pitr-<date> --restore-time <T in UTC> ...` (same subnet group,
   parameter group and security group; not Multi-AZ). Wait for `available`.
5. **Copy back only what was damaged** (the service's affected tables or rows), from the side instance into the live
   database, with `mysqldump`/`mysql` from a one-off ECS task in the VPC. The rest of the platform keeps running.
6. **Events:** data copied back by SQL does not go through the outbox, so other services are not told. If other
   services hold a view of this data (search, reports, notifications), re-emit through the service's admin actions
   or reindex (catalog search: admin *Search index* page).
7. Scale the service back, run the smoke tests, delete the side instance when the incident is closed.

### 3.3 The RDS instance is lost (or a full restore is the only way)

1. Restore the latest restorable time (`aws rds describe-db-instances --query ...LatestRestorableTime`) to a new
   instance with the production settings (Multi-AZ, parameter group with `binlog_format=ROW`, GTID on).
2. Point the services at it: update the DB host in SSM/Terraform (`DB_HOST`), then
   `aws ecs update-service --force-new-deployment` for every service with a database. Database users and passwords
   are part of the restored instance.
3. **Debezium (MSK Connect) must start fresh:** the stored binlog/GTID offsets belong to the old instance. Recreate
   each connector under a new name (fresh offsets; `snapshot.mode=no_data` starts at the current binlog position)
   **before** the services start writing, so no outbox row is missed.
4. **Reconcile the gap:** events published between the restore point and the failure are in Kafka (7 days) and
   other services already acted on them, while this database no longer knows them. Use the Kafka topics, the order
   timeline and the reports to find the affected orders. Payments are reconciled with Razorpay by payment-service's
   reconciliation job.
5. Smoke tests, then an incident report with the measured RPO/RTO.

### 3.4 The MSK cluster is lost

Business state is safe in the databases; what is lost is the events not yet consumed and the consumer offsets.
1. Recreate the cluster (`terraform apply`), then run the one-off tasks: `kafka-init` (topics), `schema-init`.
2. **Restore the Schema Registry with its ids** from the latest S3 export (registry in `IMPORT` mode), *before* any
   producer or consumer starts: outbox rows waiting to be published and DLT records carry schema ids in their bytes.
   Re-registering from git alone would assign new ids and make those bytes undecodable.
3. Recreate the MSK Connect connectors (fresh offsets) with the service SCRAM secrets.
4. **Re-publish the events written to the outboxes since the last moment the old cluster was healthy** (outbox rows
   are kept 7 days): an incremental snapshot of each `outbox` table filtered by `created_at`, through Debezium's
   signal mechanism, or a one-off re-publishing task. Consumers are idempotent, so duplicates are skipped
   (`DUPLICATE`). Rehearse this in staging (§4).
5. Consumer groups start empty on the new topics. Watch lag, the dead letters and the stuck-orders panel until
   they settle.

**Kafka replay limits** (also for smaller incidents, such as replaying one consumer group after a fix):
- A replay reaches back only as far as the topic retention: 7 days for domain topics, 30 days for `*.DLT`, 1 hour
  for rider locations. Older events exist only as outbox rows (7 days) or not at all. MSK has no point-in-time
  restore.
- Resetting offsets (`kafka-consumer-groups.sh --reset-offsets --to-datetime ... --execute`, from a one-off task with
  admin SCRAM credentials) needs the consumer group to be inactive: scale the consuming service to 0 first.
- Replays are safe because consumers record processed event ids. After a database restore, the guard table is restored
  too, so events after the restore point are applied again, which is what you want.
- notification-service ignores events older than 15 minutes for notifying people, so replays do not spam users.
- Every replayed record needs its schema id in the registry. Never delete schema versions.
- Single failed records are replayed from the Operations page (dead letters), not by moving offsets.

### 3.5 Redis is lost

- A node or AZ: automatic failover; check that the replication group is `available`.
- The whole replication group: create a new one from the latest daily snapshot (`aws elasticache
  create-replication-group --snapshot-name ...`), update the endpoint in SSM/Terraform, force new deployments of
  cart-service, api-gateway and notification-service.
- Carts live only in Redis: carts changed since the snapshot are lost (users add the items again). Rate-limit
  counters restart. SSE clients reconnect and catch up from the notification inbox (MySQL) by `Last-Event-ID`.

### 3.6 The Elasticsearch deployment is lost or corrupt

- The search index is a read model of the catalog. Either restore the latest Elastic Cloud snapshot (fast, a few
  minutes old), or create a new deployment (Terraform `elastic/ec`), update the endpoint and API key in Secrets
  Manager / SSM, and rebuild: admin → Catalog → *Search index* (full reindex from catalog-command's snapshot, ADR-0003).
- During the outage catalog-query (search and product pages, which read Elasticsearch only) fails, and so does
  everything that looks products up through it (adding to the cart, checkout). Orders already placed, payments,
  deliveries and notifications keep working.

### 3.7 The web app is broken or its files are gone

- A bad release: `rollback.sh prod frontend` (deploy-and-rollback runbook).
- Objects deleted from the bucket: restore the previous object versions (S3 versioning), or redeploy the current
  commit through the frontend pipeline, which rebuilds and uploads `releases/<sha>/` again.
- The distribution or bucket deleted: `terraform apply` for the environment, then `deploy-spa.sh <env> <sha>`.

### 3.8 A secret was deleted or leaked

- Deleted: `aws secretsmanager restore-secret --secret-id grocery/<env>/<service>/<name>` (within 30 days).
- Wrong value: move `AWSCURRENT` back to the previous version, force a new deployment of the service.
- Leaked: rotate it at the source (database user password, Kafka SCRAM, Razorpay key, Elastic API key, JWT signing
  key), store the new value, force new deployments. For a leaked JWT signing key: publish the new key in the JWKS
  first, so tokens signed with it validate everywhere, then remove the leaked key. Every token signed with the old
  key stops working, so users sign in again.

### 3.9 A Region is lost

1. Decide (product owner + lead) to fail over rather than wait. AWS Region outages usually last hours, and a
   failover takes about a day.
2. In the second Region: `terraform apply` the environment with a DR tfvars (same modules, other CIDRs and names),
   ECR images from replication (or rebuild through the pipelines), the RDS instance from the cross-Region backup or
   snapshot copy, Redis empty, Elasticsearch rebuilt by reindex, MSK new (§3.4), Schema Registry from its export,
   secrets re-created from the copies or rotated.
3. Point DNS at the new CloudFront distribution and redeploy the web app.
4. RPO is the age of the last cross-Region copy. Reconcile as in §3.3.

### 3.10 The Terraform state is damaged

Restore the previous object version of the state file in the state bucket (versioning). Clear a stuck lock in the
DynamoDB table only when no `terraform` run is active. Never edit state by hand; use `terraform state` commands.

## 4. Drills (staging, quarterly)

| Drill | Pass when |
|---|---|
| PITR of the RDS instance to a side instance, copy one table back | done within the RTO, data matches the chosen time |
| Recreate one Debezium connector with fresh offsets | events flow again, no outbox row missed |
| Re-publish one hour of outbox rows to a topic | consumers record the duplicates, no side effects |
| Schema Registry export → empty registry → import | schema ids identical, consumers decode old DLT records |
| Restore Redis from a snapshot | carts present, cart-service healthy |
| Elasticsearch full reindex | search results complete, `catalog-query` healthy |
| Web app rollback and redeploy | `rollback.sh staging frontend`, E2E green |
| Stop one AZ's tasks (ECS) / reboot RDS with failover | no failed requests beyond retries, alarms recover |

Record the measured RPO/RTO of each drill and update §1 if a target is not met.
