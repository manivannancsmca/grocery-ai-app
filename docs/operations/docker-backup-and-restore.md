# Runbook: back up, clean up and restore the app's Docker environment

This runbook covers three jobs on a developer machine:

- **Back up** everything the app keeps in Docker (databases, volumes, configuration, logs), plus the source code.
- **Clean up** only the app's Docker resources, after the backup has been verified.
- **Restore** the app with its data later, step by step, with the exact commands.

Run every command from **Git Bash** (Windows), or bash (Linux, macOS), in the repository folder (`D:\Drive E\dev\grocery-ai-app`),
unless a step says otherwise. Windows `.cmd` files run from `cmd` or PowerShell. The scripts are in
`infrastructure/local/docker-backup/`, and every backup carries its own copy of them under `scripts/`.

AWS backups (RDS point-in-time restore and so on) are a different subject: see `disaster-recovery.md`.

## What belongs to the app

The scripts never guess from names alone:

| Resource | How it is found | Today |
|---|---|---|
| Containers | Compose label `com.docker.compose.project=grocery` | `grocery-mysql-1`, `grocery-kafka-1`, … (14) |
| Named volumes | the same label | `grocery_mysql-data`, `grocery_kafka-data`, `grocery_es-data`, `grocery_redis-data` |
| Build-cache volumes | named `grocery-*`, without a Compose label | `grocery-m2` (Maven), `grocery-web-linux-node-modules` (frontend) |
| Network | the same label | `grocery_default` |
| Images | run by the app's containers and by no other project's container | `mysql:8.4.13`, `apache/kafka:4.3.2`, … |

Everything else on the machine is left alone: other Compose projects (for example `docker`, with `licensing-mysql`),
their volumes and networks, images that other projects use, and Docker's shared build cache. No global `prune`
command is used.

`grocery-m2` is part of the backup on purpose. It holds the app's own libraries (`grocery-event-schemas`,
`grocery-common`) and every downloaded dependency, so a restored machine builds without downloading everything again.
Every service pins the `grocery-event-schemas` version the source code builds (1.8.0), so `build-jars.cmd` can also
recreate it from the sources when the cache is lost.

## What a backup contains

```text
D:\Drive E\dev\grocery-ai-app-backups\
└── grocery-ai-app-backup-20261117-153000\
    ├── database\grocery-db-20261117-153000.sql            every service database (14), one consistent moment
    ├── volumes\
    │   ├── grocery_mysql-data-20261117-153000.tar.gz       + .json (name, driver, labels) for each volume
    │   ├── grocery_kafka-data-…tar.gz                      topics, Debezium offsets, Schema Registry schemas
    │   ├── grocery_es-data-…, grocery_redis-data-…         search index, carts
    │   └── grocery-m2-…, grocery-web-linux-node-modules-…  build caches
    ├── configuration\
    │   ├── compose\docker-compose.yml, docker-compose.gpu.yml
    │   ├── env\.env, .env.example                          .env holds the local credentials
    │   ├── dockerfiles\…                                   every Dockerfile, at its repository path
    │   ├── application-config\…                            application*.yml and Debezium connector JSON per service
    │   ├── infrastructure-local-…tar.gz                    infrastructure/local without backups/
    │   └── source-code-…tar.gz                             the repository without generated folders
    ├── logs\container-logs-….tar.gz                        `docker logs` of every container
    ├── scripts\                                            the backup and restore scripts
    ├── RESTORE-GUIDE.md                                    this runbook
    ├── backup-information-….txt                            what was backed up (no passwords)
    ├── SHA256SUMS                                          checksum of every file
    ├── verification-….txt                                  the verification report, with rows per table
    └── VERIFIED                                            written only when every check passed
```

**The backup holds personal data and the local credentials.** That covers names, phone numbers, addresses, orders
and `.env`. Keep the folder private: never commit it, and never put it on a shared drive.

---

## Part A: back up

1. **Stop the Spring Boot services that run on the host.** Press Ctrl+C in their tabs, or run
   `infrastructure\local\host-run\stop-all.cmd`. While they are stopped, nothing writes during the backup.
2. **Run the backup:**
   ```bash
   bash infrastructure/local/docker-backup/backup-all.sh
   # another place:              --backup-root "/e/backups"
   # start the containers again: --restart (without it they stay stopped for the cleanup)
   ```
   The script works in this order:
   1. The configuration and source code.
   2. The SQL dump of all databases, with MySQL running.
   3. The container logs.
   4. It stops the app's containers, then archives every volume, so the files are consistent.
   5. It writes the information file, copies the scripts and this guide into the backup, and records checksums.
   6. It runs the verification.

   It stops at the first error. A backup without a `VERIFIED` file is not complete.
3. **Read the result** in the last lines, `verification-<timestamp>.txt` and `backup-information-<timestamp>.txt`.

The parts can also run on their own: `backup-database.sh <folder>`, `backup-volumes.sh <folder> [--restart]`,
`backup-config.sh <folder>`, then `verify-backup.sh <folder>`.

### Backup verification checklist

`verify-backup.sh` checks these automatically. It writes `VERIFIED` only when all of them pass, and the cleanup
refuses to run without that file.

- [ ] `database/`, `volumes/`, `configuration/`, `logs/` and the information file exist.
- [ ] Every file matches `SHA256SUMS`.
- [ ] The SQL backup is not empty, ends with `-- Dump completed`, and names every service database.
- [ ] The SQL backup imports into a throwaway MySQL 8.4 without errors, and that copy has every database.
- [ ] Dumping that copy again gives the same file (an exact round trip).
- [ ] Every volume has a readable `.tar.gz` and its `.json` metadata.
- [ ] The MySQL volume archive starts a throwaway MySQL that holds every database.
- [ ] `docker-compose.yml`, an env file, the Dockerfiles, the service configuration, the source archive, the
      `infrastructure/local` archive and the logs archive are present and readable.
- [ ] The backup lies in a host folder, outside every Docker volume.

Check these yourself:

- [ ] The backup's size is plausible: about 1.5 GB today, mostly Kafka (`du -sh <backup folder>`).
- [ ] `backup-information-*.txt` lists the 4 app volumes, the 2 cache volumes and the 14 databases.
- [ ] There is room for a second copy on another disk, if the data matters to you.

---

## Part B: clean up only the app's Docker resources

1. **Look first.** This removes nothing:
   ```bash
   bash infrastructure/local/docker-backup/cleanup-grocery-docker.sh "<backup folder>" --dry-run
   ```
   It lists every container, volume, network and image it would remove, and the other projects' containers it keeps.
2. **Remove.** You confirm by typing the project name, `grocery`:
   ```bash
   bash infrastructure/local/docker-backup/cleanup-grocery-docker.sh "<backup folder>"
   ```
   Options:
   - `--include-tooling-images` also removes the app's build tools (`maven:3.9.16-eclipse-temurin-25-noble`,
     `node:24.22.2-alpine3.23`).
   - `--extra-volume <name>` also removes a leftover volume, for example one from a test. It is refused if another
     project uses it.
   - `--yes` skips the question, for scripted use.
   - `--accept-older-backup` removes even when an app container ran after the backup. Whatever changed since then
     is lost.
3. The script refuses, and removes nothing, in any of these cases:
   - the backup has no `VERIFIED` file;
   - a backup file changed since it was verified;
   - a volume about to be removed is not in the backup;
   - an app container is running now, or started after the backup, so its data may be newer. Make a fresh backup
     in that case: `backup-all.sh` stops the containers itself.

   At the end it checks that nothing of the app is left, and that the other projects' containers are untouched.

The source code, `infrastructure/local/.env` and the daily exports in `infrastructure/local/backups/` stay on disk:
they are files, not Docker resources.

---

## Part C: restore

### Which path

| | **Path 1: volumes (recommended)** | **Path 2: SQL** |
|---|---|---|
| Use when | the volume archives verify, and you want everything back exactly | a volume archive is damaged, or only the database should come back, or you move to another MySQL |
| Brings back | MySQL files, Kafka topics with Debezium offsets and schemas, search index, carts, build caches | the 14 databases only (Kafka, search and carts start empty; search is rebuilt) |
| Steps, in order | 1, 2, 6, 9, 4, 5, 7, 8, 10, 11, 12 | 1, 2, 6, 9, 7, 8, 3, 10, 11, 12 |

Below, `B` stands for the backup folder, for example:

```bash
B="/d/Drive E/dev/grocery-ai-app-backups/grocery-ai-app-backup-20261117-153000"
```

### 1. Prerequisites

- **Docker Desktop**, running (`docker version`; Compose v2: `docker compose version`).
- **Git Bash**, for these scripts. `bash`, `tar`, `sha256sum` and `awk` come with it.
- **Disk space:** about 5 GB free for the volumes, plus the images (about 9 GB pulled again).
- **Internet access** to pull the images, unless they are still on the machine.
- **For the Spring Boot services on the host** (`start-all.cmd`): JDK 25 on the host. The jars are built in Docker
  by `build-jars.cmd`.
- **Node 18 or newer**, for `rebuild-search.mjs` and the seed scripts.
- **Free ports:** 3307 (MySQL), 9092 (Kafka), 8081 (Schema Registry), 6379 (Redis), 9200 (Elasticsearch), 8070
  (Kafka UI), 8025 (Mailpit), 8080 (gateway; use `GATEWAY_PORT=8090` when another project holds 8080), 6420 (app).

### 2. Identify and verify the backup

```bash
ls -1 "/d/Drive E/dev/grocery-ai-app-backups/"            # one folder per backup, newest last
cat "$B/VERIFIED" "$B/backup-information-"*.txt           # when it was made and what it holds
(cd "$B" && sha256sum --quiet -c SHA256SUMS && echo "all files intact")
bash "$B/scripts/verify-backup.sh" "$B" --quick           # structure and archives (add nothing for the full check)
```

The full check (without `--quick`) also test-restores the SQL file and test-starts the MySQL volume. It takes a few
minutes and needs the `mysql:8.4.13` image.

### 3. Restore the database from the SQL backup (path 2 only)

MySQL must run with an empty or old database (step 8 started it). The services must be stopped.

```bash
bash "$B/scripts/restore-database.sh" "$B"                      # asks, then imports all 14 databases
bash "$B/scripts/restore-database.sh" "$B" --only order_db,payment_db    # or some of them
```

The script does four things:
1. It refuses while services are connected.
2. It replaces each chosen database completely.
3. It imports with the binary log off, so Debezium does not publish old outbox rows again.
4. It compares the rows of every table with the verification report.

The MySQL users are not in the SQL file. `mysql-init` creates them from `infrastructure/local/mysql/init` with the
passwords of `.env`.

By hand, without the script:
```bash
docker exec -i grocery-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' \
  < <(echo "SET SESSION sql_log_bin = 0;"; cat "$B/database/grocery-db-"*.sql)
```

### 4. Recreate the Docker volumes (path 1)

`restore-volumes.sh` (step 5) creates each volume with its original name, driver and labels, read from
`volumes/<volume>-<timestamp>.json`. The labels include the Compose project, volume name and config hash, so Compose
adopts the volumes as its own. Check first that none of them exists:

```bash
docker volume ls --filter label=com.docker.compose.project=grocery   # empty after a cleanup
docker volume ls | grep grocery-                                      # the cache volumes
```

By hand, for one volume (labels from its `.json` file):
```bash
docker volume create --label com.docker.compose.project=grocery --label com.docker.compose.volume=mysql-data \
  --label com.docker.compose.version=5.7.0 --label com.docker.compose.config-hash=<from the .json> grocery_mysql-data
```

### 5. Restore the volume data (path 1)

```bash
bash "$B/scripts/restore-volumes.sh" "$B"                              # every volume
bash "$B/scripts/restore-volumes.sh" "$B" --volume grocery_mysql-data  # or one
# a volume that exists already: --replace (its current contents are lost; no container may use it)
```

By hand, for one volume:
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v grocery_mysql-data:/target -v "$(cygpath -m "$B/volumes"):/backup:ro" \
  alpine:3 tar -xzf /backup/grocery_mysql-data-<timestamp>.tar.gz --numeric-owner -C /target
```

### 6. Restore the application configuration

**If the repository folder still exists**, only check that `.env` is there:
```bash
cmp "$B/configuration/env/.env" infrastructure/local/.env && echo ".env unchanged"
cp "$B/configuration/env/.env" infrastructure/local/.env              # if it is missing or wrong
```

**If the repository is gone**, unpack the source code and put `.env` back:
```bash
mkdir -p "/d/Drive E/dev/grocery-ai-app" && cd "/d/Drive E/dev/grocery-ai-app"
tar -xzf "$B/configuration/source-code-"*.tar.gz
cp "$B/configuration/env/.env" infrastructure/local/.env
```

`configuration/compose`, `dockerfiles` and `application-config` hold the same files for reading or comparing:
`diff -r "$B/configuration/compose" infrastructure/local` shows what changed since the backup.

### 7. Rebuild or pull the images

The infrastructure images are pinned by tag in `docker-compose.yml`, so Compose pulls them in step 8. To pull them
beforehand, or to check what is missing:
```bash
cd infrastructure/local
docker compose --profile all pull mysql kafka schema-registry redis kafka-connect elasticsearch mailpit kafka-ui
```

The exact digests used at backup time are listed in `backup-information-*.txt`, for example
`docker pull mysql@sha256:…`. Then build the app itself:
- **Services on the host:** `infrastructure\local\host-run\build-jars.cmd`. It uses the Maven image and the
  `grocery-m2` volume.
- **Everything in Docker:** `docker compose --profile all build`.

### 8. Recreate the containers

**Path 1 (volumes restored).** Start the infrastructure; Compose creates the containers and the network and uses
the restored volumes:
```bash
cd infrastructure/local
docker compose up -d mysql mysql-init db-backup kafka kafka-init schema-registry schema-init redis \
  kafka-connect connect-init connect-watchdog elasticsearch mailpit kafka-ui
docker compose ps        # wait until every service is "healthy" or "exited (0)" (the one-shot *-init ones)
```

**Path 2 (SQL).** Start only MySQL first (an empty volume; `mysql-init` creates the databases and users), restore
the SQL (step 3), then start the rest with the command above:
```bash
cd infrastructure/local && docker compose up -d mysql mysql-init
```

`infrastructure\local\host-run\start-all.cmd` does the same infrastructure start, then opens one tab per Spring
Boot service: use it in step 10.

### 9. Networks and environment variables

- **Network:** `grocery_default` is created by `docker compose up`; the containers reach each other by service name
  (`mysql`, `kafka`, …). Nothing to do by hand. Check it with `docker network inspect grocery_default`.
- **Environment:** `infrastructure/local/.env` (step 6) is read by Compose and by the host-run scripts. It holds
  this machine's settings:
  - `SERVICES_RUN_ON=host`;
  - `OLLAMA_HOST_PORT=11435`;
  - the database passwords;
  - the ports.

  `.env.example` lists every setting with its default.
- **Ports another project holds:** set them before starting, for example `set GATEWAY_PORT=8090` before
  `start-all.cmd`. See README → Troubleshooting.

### 10. Start the application

**Services on the host (this machine's usual setup):**
```bat
infrastructure\local\host-run\start-all.cmd
```
It starts the infrastructure (as in step 8), registers the event schemas and the Debezium connectors, and opens a
tab per service. The React app runs on http://localhost:6420.

**Everything in Docker:**
```bash
cd infrastructure/local && docker compose --profile all up -d
```

**Path 2 only:** once the services are up, rebuild the search index from the restored databases:
```bash
node infrastructure/local/scripts/rebuild-search.mjs
```

### 11. Verify database connectivity and health

```bash
cd infrastructure/local
docker compose ps                                                            # all healthy
docker exec grocery-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SHOW DATABASES"'   # 14 *_db
docker exec grocery-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SELECT user FROM mysql.user"'
curl -s localhost:8081/subjects | head -c 300; echo                          # Schema Registry: the event schemas
curl -s localhost:8083/connectors 2>/dev/null || docker exec grocery-kafka-connect-1 curl -s localhost:8083/connectors
curl -s "localhost:9200/_cat/indices?v"                                      # products, categories, restaurants
curl -s localhost:8080/api/health/gateway; echo                              # gateway UP (or :8090)
```

### 12. Verify that the data is back

```bash
# Rows of every table against the backup (prints only differences):
bash "$B/scripts/restore-database.sh" "$B" --compare
```

Then in the app, at http://localhost:6420:
- sign in with a development account (`dev.customer@grocery.local` / `Dev@12345`): the profile, addresses and past
  orders are there;
- as the admin: Orders, Users and Reports show the same figures as before;
- search finds products with prices and photos;
- Kafka UI (http://localhost:8070) shows the topics; the connectors are RUNNING.

### Post-restoration validation checklist

- [ ] `docker compose ps`: every service is healthy, and every one-shot `*-init` exited with code 0.
- [ ] MySQL has the 14 `*_db` databases, and its users exist.
- [ ] `restore-database.sh … --compare` reports no table with different rows. Path 1 is checked against the SQL
      backup taken a few minutes earlier, so a few rows may differ if something wrote in between.
- [ ] The Schema Registry lists the subjects, and the Debezium connectors are RUNNING
      (`bash infrastructure/local/scripts/check-connectors.sh`).
- [ ] Elasticsearch has the three indexes with documents. If they are empty or old, run `rebuild-search.mjs`.
- [ ] The gateway health is UP, and http://localhost:6420 loads.
- [ ] Sign-in works for the development accounts, and their data (orders, addresses, store products) is there.
- [ ] A new action works end to end: place a test order, or change a product's stock. The other screens update
      live, so events flow through Kafka again.
- [ ] The daily database export runs again (`docker compose logs db-backup`: "Daily database export at …").

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `volume … already exists but was not created by Docker Compose` | It was created without its labels. Remove it and run `restore-volumes.sh` again, which recreates the labels. |
| `restore-volumes.sh`: "already exists" | Add `--replace`, after `docker compose down`, if its current contents may go. |
| MySQL does not start on the restored volume | Check `docker compose logs mysql`. Restore it again from the archive, or take path 2. |
| Connectors FAILED after path 2 | Kafka is new, so register the connectors again: `docker compose up connect-init`. The connectors start at the current binlog position. |
| Search empty or old | `node infrastructure/local/scripts/rebuild-search.mjs` (services running). |
| `build-jars.cmd` cannot find a `grocery-event-schemas` version | A service pins a version the sources no longer build: move its pin to the library's current version (`libs/grocery-event-schemas/pom.xml`), or restore the `grocery-m2` volume (step 5). |
| Port 8080 taken (another project) | `set GATEWAY_PORT=8090` before `start-all.cmd`. |
| A script says "Docker is not running" | Start Docker Desktop and wait until it is ready. |
