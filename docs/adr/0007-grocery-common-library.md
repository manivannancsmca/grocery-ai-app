# ADR-0007: `grocery-common` as a separately versioned library

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

All 16 services need the same technical plumbing: outbox writing, event envelope and headers, idempotent consumers,
`Idempotency-Key` handling, JWT resource-server setup, ProblemDetail errors, correlation-ID propagation, and later
approval-token validation and DLT replay. Copying this into 16 services causes drift and bugs.
The blueprint forbids a Maven multi-module build.

## Decision

- Create **`libs/grocery-common`**: its own `pom.xml`, its own version, and Spring Boot auto-configuration.
  It is installed with `mvn install`. Docker builds install it in the first build stage. Later it is published to a
  private registry (AWS CodeArtifact or GitHub Packages).
- It contains **technical code only**: `outbox`, `event`, `idempotency`, `security`, `web`, `correlation`.
- It contains **no domain classes and no event payload classes.** Each consumer defines its own DTO with only the fields it needs.
- Each service pins the library version and upgrades when it chooses.
- Services use `spring-boot-starter-parent` directly. There is no shared parent POM.

## Consequences

- ✅ One tested implementation of reliability and security plumbing.
- ✅ Services stay independent Maven projects. This is not multi-module.
- ⚠️ A shared library is a coupling point. Changes must stay backward compatible, use semantic versions, and be adopted
  service by service.
- ⚠️ The Docker build context must include `libs/` (repo-root context with a `.dockerignore`).

## Alternatives considered

- **Copy-paste per service:** fully independent, but 16 copies drift. Rejected.
- **Maven multi-module / shared parent:** forbidden by the blueprint, and it couples builds and releases. Rejected.
- **Sharing domain/event classes:** creates a distributed monolith. Rejected.
