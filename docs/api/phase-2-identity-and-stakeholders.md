# Phase 2 API contract — Identity & stakeholder management

Single source of truth for the Phase 2 backend and the React app. All paths go through the API gateway
(`http://localhost:8080`; the React dev server proxies relative `/api/...` calls to it).

## 1. Conventions

| Topic | Rule |
|---|---|
| Auth header | `Authorization: Bearer <accessToken>` on every non-public call |
| Content type | `application/json`; errors are `application/problem+json` |
| IDs | UUID strings (UUIDv7). **customerId = userId** and **delivery partnerId = userId**; vendorId / restaurantId are separate (businesses) |
| Timestamps | ISO-8601 UTC strings, e.g. `2026-11-11T10:15:30.123Z` |
| Pagination | query `page` (0-based, default 0), `size` (default 20, max 100) → `PageResponse` |
| Correlation | send `X-Correlation-Id` (8–64 chars `[A-Za-z0-9._-]`); every response echoes one |

```jsonc
// PageResponse<T>
{ "content": [ /* T */ ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }

// Error (RFC 9457 ProblemDetail)
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "Email is already registered",
  "errorCode": "EMAIL_ALREADY_REGISTERED", "correlationId": "…",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ] }   // only for VALIDATION_FAILED
```

### 401 handling (frontend)
| errorCode | Meaning | Client action |
|---|---|---|
| `TOKEN_EXPIRED` | access token expired | refresh once, retry the request |
| `TOKEN_REVOKED` | issued before a role/status change (gateway) | refresh once, retry (new token has new claims) |
| `INVALID_TOKEN`, `UNAUTHENTICATED` | bad/missing token | refresh once; if that fails → login page |
| `REFRESH_TOKEN_INVALID` (from `/api/auth/refresh`) | session over | clear session → login page |

`403 ACCOUNT_SUSPENDED` (login/refresh) → show "account suspended" screen. `403 FORBIDDEN` → "not allowed".

## 2. Roles and JWT

Roles: `CUSTOMER`, `VENDOR`, `RESTAURANT_OWNER`, `DELIVERY_PARTNER` (self-registration) and admin roles
`SUPER_ADMIN`, `OPS_ADMIN`, `FINANCE_ADMIN`, `SUPPORT`, `DELIVERY_MANAGER` (created by a SUPER_ADMIN; a
bootstrap SUPER_ADMIN is created on first start: `admin@grocery.local` / `Admin@12345`, local only).
A user is an **admin** if they hold any admin role.

Access token (RS256, 15 min, verify with `GET /.well-known/jwks.json`):
```jsonc
{ "iss": "https://identity.grocery.local", "aud": "grocery-api", "sub": "<userId>",
  "iat": 1794335467, "iat_ms": 1794335467342, "exp": 1794336367, "jti": "<uuid>",
  "email": "a@b.com", "name": "Asha Rao", "roles": ["VENDOR"],
  "vendor_id": "<uuid>",        // only while the vendor is APPROVED (active)
  "restaurant_id": "<uuid>" }   // only while the restaurant is APPROVED
```
Refresh token: opaque, **httpOnly cookie** `grocery_rt` (`SameSite=Strict`, `Path=/api/auth`, 14 days), rotated on
every refresh. Reusing an old refresh token after a 10 s grace window revokes the whole session family.
Role/status changes (approval, suspension) mark older access tokens as revoked → the client refreshes and
receives a token with the new claims.

## 3. Identity service

| Method & path | Auth | Body → Response |
|---|---|---|
| `POST /api/auth/register` | public | `RegisterRequest` → `201 AuthResponse` + `Set-Cookie` |
| `POST /api/auth/login` | public | `LoginRequest` → `200 AuthResponse` + `Set-Cookie` |
| `POST /api/auth/refresh` | cookie + header `X-Requested-With: XMLHttpRequest` | → `200 AuthResponse` + new cookie |
| `POST /api/auth/logout` | cookie + header `X-Requested-With: XMLHttpRequest` | → `204`, cookie cleared |
| `GET /api/auth/me` | any user | → `UserView` |
| `POST /api/auth/password/change` | any user (the refresh cookie is sent along) | `ChangePasswordRequest` → `204` |
| `POST /api/auth/password/forgot` | public | `{ "email" }` → `202` (always, no body) |
| `POST /api/auth/password/reset` | public | `ResetPasswordRequest` → `204` (signs nobody in) |
| `GET /.well-known/jwks.json` | public | JWK set |
| `GET /api/admin/users?query=&role=&status=&page=&size=` | SUPER_ADMIN, OPS_ADMIN, SUPPORT | → `PageResponse<AdminUserView>` |
| `POST /api/admin/users` | SUPER_ADMIN | `CreateAdminRequest` → `201 AdminUserView` |
| `POST /api/admin/users/{id}/suspend` | SUPER_ADMIN, OPS_ADMIN | `{ "reason": "…" }` → `AdminUserView` |
| `POST /api/admin/users/{id}/reactivate` | SUPER_ADMIN, OPS_ADMIN | → `AdminUserView` |

```jsonc
// RegisterRequest — role is one of the 4 self-registration roles
{ "email": "a@b.com", "password": "Secret@123", "fullName": "Asha Rao", "phone": "+919876543210", "role": "VENDOR" }
// password: 8–64 chars, at least one letter and one digit. phone optional: ^\+?[0-9]{10,15}$
// LoginRequest
{ "email": "a@b.com", "password": "Secret@123" }
// AuthResponse
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900, "user": UserView }
// UserView
{ "id": "…", "email": "a@b.com", "fullName": "Asha Rao", "phone": "+91…", "status": "ACTIVE",
  "roles": ["VENDOR"], "vendorId": null, "restaurantId": null,
  "businesses": [ { "type": "VENDOR", "businessId": "…", "status": "PENDING" } ] }   // PENDING|ACTIVE|REJECTED|SUSPENDED
// AdminUserView
{ "id", "email", "fullName", "phone", "roles": [], "status": "ACTIVE|SUSPENDED", "createdAt", "lastLoginAt" }
// CreateAdminRequest — roles ⊆ admin roles
{ "email", "fullName", "password", "phone?", "roles": ["OPS_ADMIN"] }
// ChangePasswordRequest — newPassword: the registration rule (8–64 chars, a letter and a digit), not the current one
{ "currentPassword": "Secret@123", "newPassword": "Fresh2026x" }
// ResetPasswordRequest — token: from the emailed link /reset-password?token=…; newPassword: the registration rule
{ "token": "q3V…", "newPassword": "Fresh2026x" }
```
Errors: `409 EMAIL_ALREADY_REGISTERED`, `401 INVALID_CREDENTIALS` (also used while temporarily locked after 5
failures), `403 ACCOUNT_SUSPENDED`, `401 REFRESH_TOKEN_INVALID`, `409 REFRESH_RACE` (concurrent refresh — retry once),
`403 CSRF_HEADER_MISSING`, `422 CANNOT_SUSPEND_SELF`, `404 USER_NOT_FOUND`, `400 CURRENT_PASSWORD_INCORRECT` (also
while the account is locked), `400 PASSWORD_UNCHANGED`, `400 RESET_TOKEN_INVALID` (unknown, expired, used or replaced
link, or the account is no longer active), `400 VALIDATION_FAILED` (e.g. a new password that breaks the rule).

**Passwords.**
- **Change** (signed in): a wrong current password counts towards the lockout like a failed sign-in. On success the
  password is replaced, every *other* session is signed out (their refresh-token families are revoked; the caller's
  family, from the `grocery_rt` cookie, stays), and the "revoked-before" marker ends every access token issued so far:
  the caller's client gets `401 TOKEN_REVOKED` on its next call and refreshes (the web app refreshes right away).
  Unused reset links of the account stop working. Event `PasswordChanged` (method `CHANGE`).
- **Forgot**: the same `202` for every address, and every call takes at least 250 ms
  (`grocery.identity.password-reset.min-response-time`), so neither the answer nor its timing tells whether an account
  exists. For an ACTIVE account it creates a reset link: 32 random bytes (URL-safe base64), valid for 30 minutes
  (`grocery.identity.password-reset.ttl`), only its SHA-256 hash stored (`password_reset_token`, like refresh tokens).
  A new link ends the account's older unused ones. At most 3 links per account per hour; more requests answer `202`
  and do nothing. Event `PasswordResetRequested`, which carries the raw token: notification-service emails the link
  `${APP_BASE_URL}/reset-password?token=…`.
- **Reset**: the link works once. It sets the password, ends a lockout, signs out every session and writes the
  revoked-before marker. Nobody is signed in: the user signs in with the new password. Event `PasswordChanged` (method
  `RESET`).
- **Why the token is in an event:** the email is sent by notification-service, which needs the link. The trade-off is
  kept small: the link expires after 30 minutes and works once; identity_db keeps only the hash; notification-service
  keeps the link only in the email's `email_outbox` row (never in the inbox, the admin email log or its export, and
  never logged); the production Referrer-Policy keeps the query string from leaving the site.
- The gateway routes `/api/auth/**` to identity under the per-IP auth rate limit (as login and registration); forgot
  and reset are public paths.

**A customer's name** (since `CustomerProfileUpdated`): the name and phone a customer saves on their profile
(`PUT /api/customers/me`) reach their account within seconds (`UserView`, the JWT `name` claim, the admin user list).
identity then writes the revoked-before marker, so the client's next call refreshes into a token with the new name.
The web app shows the new name in the header at once.

## 4. Customer service

| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /api/customers/me` | CUSTOMER | → `CustomerProfile` (`404 CUSTOMER_PROFILE_NOT_FOUND` for ~1 s after registration — retry) |
| `PUT /api/customers/me` | CUSTOMER | `{ "fullName", "phone?" }` → `CustomerProfile` (a changed name or phone publishes `CustomerProfileUpdated`) |
| `GET /api/customers/me/addresses` | CUSTOMER | → `Address[]` (default first) |
| `POST /api/customers/me/addresses` | CUSTOMER | `AddressRequest` → `201 Address` (`422 ADDRESS_LIMIT_REACHED`, max 10) |
| `PUT /api/customers/me/addresses/{id}` | CUSTOMER | `AddressRequest` → `Address` |
| `DELETE /api/customers/me/addresses/{id}` | CUSTOMER | → `204` |
| `POST /api/customers/me/addresses/{id}/default` | CUSTOMER | → `Address` |
| `GET /api/admin/customers?query=&page=&size=` | any admin role | → `PageResponse<CustomerProfile>` |

```jsonc
// CustomerProfile
{ "id": "<userId>", "email", "fullName", "phone", "createdAt", "updatedAt" }
// AddressRequest  (country defaults to "IN"; postalCode ^[1-9][0-9]{5}$; lat -90..90, lng -180..180)
{ "label": "Home", "recipientName": "Asha Rao", "phone": "+919876543210", "line1": "12 MG Road", "line2": null,
  "landmark": null, "city": "Bengaluru", "state": "Karnataka", "postalCode": "560001", "country": "IN",
  "latitude": 12.9716, "longitude": 77.5946, "isDefault": true }
// Address = AddressRequest + { "id", "isDefault", "createdAt", "updatedAt" }
```
The first address is always default; deleting the default promotes the most recent remaining address.

## 5. Stakeholder onboarding (vendor, restaurant, delivery partner)

Shared lifecycle (each service owns its own copy):
```
DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
  ▲                      │ reject
  └──── edit ◀── REJECTED ◀┘          (edit allowed only in DRAFT and REJECTED; submit again after edit)
```
The profile is created automatically (status `DRAFT`) when the user registers with that role
(`UserRegistered` event, ~1 s). `GET …/me` may return `404 …_NOT_FOUND` briefly — retry.

### Vendor service
| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /api/vendors/me` | VENDOR | → `VendorView` |
| `PUT /api/vendors/me` | VENDOR | `VendorUpdateRequest` → `VendorView` (`409 PROFILE_LOCKED` unless DRAFT/REJECTED) |
| `POST /api/vendors/me/submit` | VENDOR | → `VendorView` (`422 PROFILE_INCOMPLETE` with `errors[]`) |
| `GET /api/admin/vendors?status=&query=&page=&size=` | any admin role | → `PageResponse<VendorView>` |
| `GET /api/admin/vendors/{id}` | any admin role | → `VendorView` |
| `GET /api/admin/vendors/{id}/history` | any admin role | → `StatusChange[]` (newest first) |
| `POST /api/admin/vendors/{id}/approve` | SUPER_ADMIN, OPS_ADMIN | → `VendorView` |
| `POST /api/admin/vendors/{id}/reject` | SUPER_ADMIN, OPS_ADMIN | `{ "reason" }` → `VendorView` |
| `POST /api/admin/vendors/{id}/suspend` | SUPER_ADMIN, OPS_ADMIN | `{ "reason" }` → `VendorView` |
| `POST /api/admin/vendors/{id}/reinstate` | SUPER_ADMIN, OPS_ADMIN | → `VendorView` |

```jsonc
// VendorUpdateRequest (all optional while editing; required to submit: businessName, legalName, gstin,
// contactEmail, contactPhone, address incl. latitude/longitude)
{ "businessName": "FreshMart", "legalName": "FreshMart Pvt Ltd", "gstin": "29ABCDE1234F1Z5",
  "fssaiLicense": "12345678901234", "contactEmail": "ops@freshmart.in", "contactPhone": "+919876543210",
  "address": { "line1", "line2?", "city", "state", "postalCode", "country": "IN", "latitude", "longitude" } }
// VendorView
{ "id", "ownerUserId", "status": "DRAFT|PENDING_APPROVAL|APPROVED|REJECTED|SUSPENDED",
  "businessName", "legalName", "gstin", "fssaiLicense", "contactEmail", "contactPhone", "address": {…},
  "statusReason": "…",   // latest rejection/suspension reason, null otherwise
  "submittedAt", "approvedAt", "createdAt", "updatedAt" }
// StatusChange
{ "fromStatus", "toStatus", "reason", "changedBy": "<userId>", "changedAt" }
```
Validation: GSTIN `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$`, FSSAI `^[0-9]{14}$`, PIN `^[1-9][0-9]{5}$`.
Transition errors: `409 INVALID_STATUS_TRANSITION`; not found: `404 VENDOR_NOT_FOUND`.
The public store directory of approved stores (`GET /api/stores/nearby`, `GET /api/stores/{id}`, no token) is in
[Phase 3 §9](phase-3-catalog-and-search.md#9-store-directory-and-search-across-nearby-stores).

### Restaurant service — same pattern
Paths `/api/restaurants/me`, `/api/restaurants/me/submit`, `/api/admin/restaurants/…` (same verbs/roles as vendor).
```jsonc
// RestaurantUpdateRequest (required to submit: name, cuisines(≥1), fssaiLicense, contactEmail, contactPhone,
// address incl. lat/lng, openingHours(≥1))
{ "name": "Spice Route", "description": "North Indian kitchen", "cuisines": ["NORTH_INDIAN", "BIRYANI"],
  "fssaiLicense": "12345678901234", "gstin": null, "contactEmail", "contactPhone", "address": {…},
  "openingHours": [ { "dayOfWeek": "MONDAY", "opensAt": "09:00", "closesAt": "23:00" } ] }
// RestaurantView = request fields + { "id", "ownerUserId", "status", "statusReason", "submittedAt", "approvedAt", "createdAt", "updatedAt" }
```
Errors use `RESTAURANT_NOT_FOUND`. Cuisines are free uppercase tokens `^[A-Z_]{2,30}$` (max 8).

### Delivery service (partner onboarding only in Phase 2)
Paths `/api/delivery/partners/me`, `/api/delivery/partners/me/submit`, `/api/admin/delivery-partners/…`.
Approve/reject/suspend/reinstate roles: SUPER_ADMIN, OPS_ADMIN **and DELIVERY_MANAGER** (delivery managers run the fleet).
```jsonc
// PartnerUpdateRequest (required to submit: fullName, phone, city, vehicleType; vehicleNumber and
// drivingLicenseNumber required unless vehicleType = BICYCLE)
{ "fullName", "phone", "city": "Bengaluru", "vehicleType": "BICYCLE|SCOOTER|MOTORCYCLE|CAR",
  "vehicleNumber": "KA01AB1234", "drivingLicenseNumber": "KA0120201234567" }
// PartnerView = request fields + { "id": "<userId>", "email", "status", "statusReason", "submittedAt", "approvedAt", "createdAt", "updatedAt" }
```
Errors use `PARTNER_NOT_FOUND`.

## 6. Health (public, through the gateway)
`GET /api/health/{service}` for `gateway`, `identity`, `customer`, `vendor`, `restaurant`, `delivery` →
`{ "status": "UP" | "DOWN" }` (503 when DOWN). Used by the React connectivity card and the admin System Status page.

## 7. Events introduced in Phase 2 (see docs/events/event-catalog.md)
| Topic | Events (v1) | Consumers |
|---|---|---|
| `identity.events` | UserRegistered `{userId,email,fullName,phone,role,registeredAt}`, UserSuspended `{userId,reason,suspendedBy}`, UserReactivated `{userId,reactivatedBy}`; later: PasswordResetRequested `{userId,email,fullName,token,expiresAt}`, PasswordChanged `{userId,email,fullName,method,changedAt}` | customer, vendor, restaurant, delivery (UserRegistered for their role); notification (the password emails) |
| `customer.events` | CustomerProfileUpdated `{customerId,fullName,phone,updatedAt}` (only when the name or phone changed) | identity (the account's name), notification (email greetings) |
| `vendor.events` | VendorRegistered, VendorSubmitted, VendorApproved, VendorRejected, VendorSuspended, VendorReinstated — `{vendorId, ownerUserId, businessName, reason?, actorUserId?}` | identity (claims) |
| `restaurant.events` | Restaurant{Registered,Submitted,Approved,Rejected,Suspended,Reinstated} — `{restaurantId, ownerUserId, name, reason?, actorUserId?}` | identity (claims) |
| `delivery.events` | DeliveryPartner{Registered,Submitted,Approved,Rejected,Suspended,Reinstated} — `{partnerId, fullName, reason?, actorUserId?}` | notification (Phase 7) |
