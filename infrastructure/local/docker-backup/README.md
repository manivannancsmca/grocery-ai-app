# Docker backup, cleanup and restore

Scripts that back up the app's whole Docker environment, remove only the app's Docker resources, and restore them.
The step-by-step guide, with every command and both checklists, is
[docs/operations/docker-backup-and-restore.md](../../../docs/operations/docker-backup-and-restore.md). Each backup also
carries a copy of the guide as `RESTORE-GUIDE.md`, and of these scripts under `scripts/`.

Run them from Git Bash on Windows, or bash on Linux and macOS, with Docker running.

| Script | What it does |
|---|---|
| `backup-all.sh` | The complete backup in one command: configuration, database, logs, volumes, information file, checksums, verification |
| `backup-database.sh` | Every service database into one SQL file (consistent, with routines, triggers and events) |
| `backup-volumes.sh` | Every app volume into a `.tar.gz` plus its metadata (stops the app's containers first) |
| `backup-config.sh` | Compose files, `.env`, Dockerfiles, service configuration, `infrastructure/local`, the source code |
| `verify-backup.sh` | Checks a backup: checksums, a test restore of the SQL file, a test start of the MySQL volume, readable archives. Writes `VERIFIED` only when everything passes |
| `cleanup-grocery-docker.sh` | Removes only the app's containers, volumes, networks and images, after a verified backup (dry run with `--dry-run`) |
| `restore-volumes.sh` | Recreates the volumes with their original names and labels, and puts the data back |
| `restore-database.sh` | Imports the SQL backup into the running MySQL (all databases or `--only` some) |
| `common.sh` | Shared settings and helpers (sourced by the others) |

The app's resources are found by the Docker Compose project label (`com.docker.compose.project=grocery`) and the
`grocery-*` cache volumes. Nothing belonging to another project is touched, and no global `prune` command is used.
Backups go to `../grocery-ai-app-backups/` next to the repository, unless you pass `--backup-root`.
