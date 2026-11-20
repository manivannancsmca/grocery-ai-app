# ADR-0009: Local development fully in Docker

- **Status:** Accepted (product owner's change request)
- **Date:** 2026-11-10

## Context

The development machine runs Windows 11. Installing MySQL, Kafka, Elasticsearch, Redis or Ollama as Windows services
causes version drift, port conflicts and "works on my machine" problems. The full platform has 16 services plus
infrastructure, so memory must be managed.

## Decision

- **Everything runs as Docker containers** through `infrastructure/local/docker-compose.yml`. **Nothing is installed as a Windows service.**
- **Containers:**
  - **One MySQL 8.4.13 container** hosts all 13 service databases. Each service has its own database user. The container is
    tuned for low memory (`innodb_buffer_pool_size=256M`, `performance_schema=OFF`, ≈768 MB cap).
  - Kafka 4.3.2 (KRaft), Debezium Connect 3.7.1.Final, Elasticsearch 9.4.6, Redis 8.8.4, Kafka UI.
  - **Ollama 0.40.15 container** (profile `ai`, GPU passthrough if an NVIDIA GPU is present).
  - All 16 Spring Boot services and the Vite dev server.
- **Compose profiles** start only what a phase needs. Infrastructure is always on.
- **Multi-stage Dockerfiles:**
  - Stage 1: Maven on JDK 25, with a BuildKit cache for `~/.m2`. It installs `grocery-common` and packages the service.
  - Stage 2: JRE 25 runtime as a non-root user.
  - The build context is the repo root, with a `.dockerignore`.
- **Memory:** every container has a memory cap. JVM flags: `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`.
- **Spring profiles:** `docker` for container hostnames, `local` for running one service from the IDE against the
  containerized infrastructure. Debugging also works by attaching to a container's JDWP port.
- **Not covered here:** CI image pipelines, vulnerability scanning, ECR and AWS remain deferred until approved.

## Consequences

- ✅ Only Docker Desktop is required. Every developer gets identical versions.
- ✅ Each service is independently buildable with `docker compose up -d --build <service>`.
- ⚠️ With everything running, the platform uses ≈13–15 GB inside the WSL2 VM. A 32 GB machine is recommended, with `.wslconfig` set to `memory=20GB`.
- ⚠️ A Java change needs an image rebuild of that service (≈30–60 s with cached dependencies).
- ⚠️ On Windows, bind-mounted frontend sources need file-watch polling for hot reload.
- ⚠️ Without an NVIDIA GPU, Ollama runs on CPU. Use a small (3B–8B) quantized model.

## Alternatives considered

- **Infrastructure in Docker, services through `mvn spring-boot:run`:** faster Java feedback loop, but requires a local
  JDK and differs from how services run elsewhere. Kept only as an optional debugging mode (`local` profile).
- **Native Windows installs:** rejected by the product owner.
