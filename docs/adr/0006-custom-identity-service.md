# ADR-0006: Custom identity-service with JWT and refresh-token rotation

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

Five stakeholder types register and log in to one first-party SPA. Downstream services need the user's roles
and stakeholder ownership (`vendor_id`, `restaurant_id`, `partner_id`) to authorize requests. Stakeholder
activation depends on admin approval in other services. The team prefers simple, understandable components.

## Decision

- **identity-service** (Spring Security, Spring Boot 4.1.2) handles:
  - registration and login, with BCrypt/Argon2 password hashing, login rate limiting and temporary lockout
  - **access tokens:** JWT RS256 with 15-minute expiry. Claims: `sub`, `roles`, `vendor_id` / `restaurant_id` /
    `partner_id`, `jti`. Public keys are published through JWKS.
  - **refresh tokens:** opaque 256-bit random values, stored hashed and **rotated on every use**. Reusing an old token revokes the
    whole token family. They are sent as an httpOnly, Secure, SameSite=Strict cookie scoped to `/api/auth`.
  - **service tokens:** client-credentials tokens for background service-to-service calls, with scopes and audience.
  - **stakeholder claims:** kept in sync by consuming `*Approved` / `*Suspended` events.
    A suspension also revokes refresh tokens and adds the user to a Redis denylist checked by the gateway.
- The gateway **and** every service validate JWTs through JWKS (zero trust).
- The SPA keeps the access token **in memory only**.

## Consequences

- ✅ Small, understandable component built on well-known libraries, with domain-specific onboarding.
- ✅ Stateless authorization everywhere. Immediate revocation through the denylist.
- ⚠️ Security-sensitive code is ours to maintain. Scope is kept narrow and covered by tests.
- ⚠️ No SSO, social login or MFA out of the box.

## Alternatives considered

- **Keycloak:** OIDC, MFA, social login and an admin UI out of the box, but heavy (memory, operations) and another
  technology to master. Reconsider if SSO, social login or MFA becomes a requirement.
- **Spring Authorization Server:** a full OAuth2 server, more than a first-party SPA needs today.
- **Tokens in localStorage:** vulnerable to XSS. Rejected.
