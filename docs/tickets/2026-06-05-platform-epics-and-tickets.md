# Platform Epics & Tickets — Foundation v0

**Date:** 2026-06-05
**Scope:** Whole platform — `server/` (Spring Boot), `desktop/src/` (React frontend), `desktop/src-tauri/` (Tauri Rust shell).
**Spec:** [`docs/superpowers/specs/2026-05-02-foundation-hotel-pms-mvp-design.md`](../superpowers/specs/2026-05-02-foundation-hotel-pms-mvp-design.md)
**Plan:** [`docs/superpowers/plans/2026-05-02-foundation-v0.md`](../superpowers/plans/2026-05-02-foundation-v0.md)
**Supersedes ticket numbering in:** [`2026-05-20-backend-foundation-tickets.md`](./2026-05-20-backend-foundation-tickets.md) (backend T-IDs preserved below).

> **Working model:** the user writes code, Claude reviews. Each ticket carries **Expected** (the deliverable) and **Acceptance** (how we know it's done). Tickets are sized for one focused sitting.

---

## Status legend
`DONE` shipped & verified · `PARTIAL` started, gaps remain · `TODO` not started · `BLOCKED` waiting on a dependency

## Critical path (build order)
```
B-EPIC-1 (auth) ─► B-EPIC-2 (RBAC) ─► B-EPIC-3/4 (audit/sync) ─► B-EPIC-5 (plugin host)
        │                                       │
        ▼                                       ▼
F-EPIC-1 (scaffold) ─► F-EPIC-2 (auth UI) ─► F-EPIC-3 (data) ─► F-EPIC-4/5/6
                                              │
                                              ▼
                          D-EPIC-2 (cache) ─► D-EPIC-3 (sync engine)
```
**Do not start Hotel plugin work until Backend EPIC-1 + EPIC-2 are green.** The server contract is the blocking path. _(Both are green; the hotel booking slice — B-EPIC-7 + H-EPIC-1 below — was built 2026-10-22/23.)_

---

# BACKEND

> **State (2026-10-23):** B-EPIC-1 to B-EPIC-7 are done. The plugin host now runs real plugins (own classloader, Spring child context, REST routes, permissions, per-plugin failure isolation) and the first one, the hotel booking slice (H-EPIC-1), is proven end to end. Full gate on the homelab docker-host: 340 tests (322 foundation + 18 hotel unit), 0 failures, 0 skipped.

> **State (2026-10-25):** the hotel check-in/folio/check-out slice (H-EPIC-2, plugin 0.2.0) is done on the server. Full gate on the homelab docker-host: 460 tests (428 foundation + 32 hotel unit), 0 failures, 0 skipped.

> **State (2026-10-26):** the business date (H208) and undo check-out (H209) close the two front-desk gaps the slice 2 review found. Full gate: 477 tests (436 foundation + 41 hotel unit), 0 failures, 0 skipped.

> **State (2026-11-07):** the CCCD scan (H-EPIC-3, plugin 0.3.0) is done on the server. Full gate: 538 tests (467 foundation + 71 hotel unit), 0 failures, 0 skipped.

> **State (2026-11-14):** guest online check-in (H-EPIC-4, plugin 0.4.0 and `guest-web/`) is done and passes the gate; going live (manifests, secret, Cloudflare hostname) is a separate step. Full gate: 610 tests (525 foundation + 85 hotel unit), 0 failures, 0 skipped; guest-web 36 tests; docker-host smoke 93 checks.

> **State (2026-11-15):** the dental pre-visit slice (D-EPIC-7, plugin `dental` 0.1.0 and the dental flow in `guest-web/`) is done and passes the gate; it ships switched off (`dental.public.enabled=false`) and going live is a separate step. Full gate: 732 tests (601 foundation + 45 dental, 46 hotel and 40 cccd-scan unit), 0 failures, 0 skipped; guest-web 104 tests; docker-host smoke 212 checks.

## B-EPIC-1 — Identity & Auth
*Goal: a usable, rotation-safe token contract.*

### T001 — Real auth response from login · `DONE` · P0
**Expected:** `POST /api/v1/auth/login` returns `accessToken`, `accessTokenExpiresAt`, `refreshToken`, `refreshTokenExpiresAt`, `tokenType` (not a bare jti).
**Acceptance:** client calls a protected endpoint immediately after login using the returned bearer token; no controller returns raw jti as the primary result; covered by `AuthControllerTest`.

### T002 — Refresh-token rotation endpoint · `DONE` · P0
**Expected:** `POST /api/v1/auth/refresh` marks the old token `used_at`, creates a replacement, sets `replaced_by`, returns fresh access + refresh tokens.
**Acceptance:** a refresh token works exactly once; reuse is rejected; rotation chain persisted; covered by `TokenServiceImplTest`.

### T003 — Logout + revocation semantics · `DONE` · P0
**Expected:** logout endpoint that distinguishes `EXPIRED` / `REVOKED` / `ALREADY_USED` / `NOT_FOUND`; `revoked_at` column (V2 migration); stop using expiry-overwrite as the only revocation path.
**Acceptance:** logout makes future refresh attempts fail; states are explicit and test-covered.

### T004 — Separate JWT signing from refresh-token persistence · `DONE` · P1
**Expected:** one component owns signed access JWTs (`JwtTokenProvider`); one service owns refresh-token persistence + rotation (`TokenService`). Names match behavior; remove dead `updateToken` if unused.
**Acceptance:** service boundaries are obvious from class names and tests; `AuthController` orchestration reads simply.
**Done:** `JwtTokenProvider` solely issues/validates access JWTs (`issueAccessToken`); `TokenServiceImpl` solely owns refresh-token persistence + rotation. Dropped dead `JwtTokenProvider.generateToken`, `TokenService.updateToken`, `RefreshTokenRepository.findByUser`; renamed `jwtExpirationMillis`→`refreshTtlSeconds`. Verified 2026-06-08 (commit 9f6a9b2).

---

## B-EPIC-2 — RBAC & Account Security
*Goal: real permission enforcement, not just "is authenticated".* — **highest value next**

### T005 — Map roles→permissions into authorities · `DONE` · P0 · (needs T004)
**Expected:** build a `GrantedAuthority` list from the user's role permissions and put it on the `Authentication` in `JwtAuthenticationFilter`.
**Acceptance:** an authenticated user's authorities reflect their permissions (verified in `JwtAuthenticationFilterTest`); no more empty authority list.
**Done:** `RoleRepository.findPermissionKeysForUser` (native join `roles ⋈ role_permissions ⋈ permissions ⋈ user_roles`) backs `RoleService.permissionKeysForUser`; `JwtAuthenticationFilter` maps the resolved keys to `SimpleGrantedAuthority` and grants them on the `Authentication`, replacing the hard-coded `Collections.emptyList()`. Covered by `RoleServiceImplTest#permissionKeysForUser_delegatesToRepository` and `JwtAuthenticationFilterTest#validToken_userFound_authoritiesReflectResolvedPermissions`. Verified 2026-06-09 (commit cc91afc).

### T005a — Embed permission claims in the access JWT · `DONE` · P1 · (needs T005)
**Expected:** access JWT carries permission claims so the filter doesn't hit the DB on every request.
**Acceptance:** a request authorizes from JWT claims alone; no per-request user/role query in the hot path.
**Done:** `JwtTokenProvider.issueAccessToken(User, Set<String>)` stores a `"perms"` claim (list of permission keys); `getPermissionsFromJWT(String)` extracts it, returning empty set for legacy tokens without the claim. `TokenService.createAccessToken(User, Set<String>)` passes through. `AuthController.login` and `.refresh` call `roleService.permissionKeysForUser` once at issuance time and embed the result. `JwtAuthenticationFilter` now reads authorities from JWT claims — `RoleService` dependency removed from the filter, eliminating the per-request DB join. `JwtPermissionsClaimTest` covers round-trip + empty + legacy-token cases. 83/83 green. Verified 2026-06-09 (commit 8895a51).

### T006 — Method-level authorization · `DONE` · P0 · (needs T005)
**Expected:** enable method security; add `@PreAuthorize` to User/Role controller endpoints using core permissions.
**Acceptance:** a user with `core.users.read` reaches the guarded user endpoint; a user without it gets 403 (not just 401).
**Done:** `@EnableMethodSecurity(prePostEnabled = true)` added to `WebSecurityConfig`; `UserController.getUserById` gated with `@PreAuthorize("hasAuthority('core.users.read')")` using `CorePermissions` constant. Added `spring-security-test` dep + `-Dnet.bytebuddy.experimental=true` Surefire arg (Byte Buddy 1.15.x + JDK 26 support). `UserControllerSecurityTest` (`@WebMvcTest` + `@WithMockUser`) confirms 200 with authority, 403 without. `RoleController` is currently empty; `@PreAuthorize` will be added once endpoints exist in T008/T009. 65/65 unit tests green. Verified 2026-06-09 (commit 33ec4d7).

### T006a — Core permission constants · `DONE` · P0
**Expected:** define `core.users.read/write`, `core.roles.read/write`, `core.audit.read`, `core.plugins.manage` as constants and seed them.
**Acceptance:** constants referenced by `@PreAuthorize`; seeded permissions exist in DB; admin role maps to all.
**Done:** `CorePermissions` (`Security/`) holds the 7 built-in permission key constants (incl. `core.users.delete`, already seeded) + `all()`. `V3__seed_admin_role_permissions.sql` maps the `admin` role to every built-in permission. `CorePermissionsTest` (unit) + `AdminRolePermissionSeedIntegrationTest` (Testcontainers, tagged `integration`) cover it; migration verified directly against a throwaway Postgres container (admin → all 7 perms). `@PreAuthorize` wiring itself lands with **T006** once method security is enabled by **T005**. Verified 2026-06-09.

### T007 — Account lockout + enabled checks · `DONE` · P0
**Expected:** increment `failed_logins` on bad credentials; lock after 5 fails within 15 min via `locked_until`; block disabled users; reset count on success.
**Acceptance:** 5 bad attempts lock the account; locked/disabled users cannot log in until lock expires; covered by tests.
**Done:** `LoginResult` sealed interface (`Success(User)` / `Failure(FailureReason)`) with `BAD_CREDENTIALS`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` reasons. `UserService.attemptLogin()` — transactional, checks `enabled` then active `locked_until`, then BCrypt; increments `failed_logins` on failure and sets `locked_until = now + 15 min` at the 5th; resets both fields on success. `AuthController.login` now uses `attemptLogin` exclusively, returning distinct 401 codes per failure reason. No migration needed (`failed_logins`, `locked_until`, `enabled` already in V1). `LoginRequest` gained `@AllArgsConstructor`. 9 new unit tests in `UserServiceLockoutTest`; 74/74 green. Verified 2026-06-09 (commit 1020870).

### T008 — Admin-managed user creation (remove public register) · `DONE` · P1 · (needs T006)
**Expected:** remove/disable public `/register`; add admin-only user creation; separate login DTOs from user-create DTOs.
**Acceptance:** unauthenticated users cannot create accounts; an admin can create a user via a protected endpoint.
**Done:** `CreateUserRequest` record (`username`, `email`, `password` — all `@NotBlank`/`@Email`). `UserService.createUser(CreateUserRequest)` replaces `register(LoginRequest)` — stores BCrypt hash, sets real `email` field (no fake `@users.local` generation). `POST /api/v1/auth/register` removed. `POST /api/v1/users` added to `UserController`, guarded by `@PreAuthorize("hasAuthority('core.users.write')")`, returns 201. `WebSecurityConfig.permitAll` tightened to explicit paths (login/refresh/logout/revoke/**); generic `/auth/**` wildcard removed. `UserControllerCreateTest` confirms 201 with write authority, 403 without. 80/80 green. Verified 2026-06-09 (commit 041bf09).

### T008a — `GET /auth/me` current-user endpoint · `DONE` · P1 · (needs T005)
**Expected:** return the authenticated user's id, username, roles, and effective permissions.
**Acceptance:** frontend can fetch identity to drive permission-aware nav; returns 401 when unauthenticated.
**Done:** `MeResponse` record (`id`, `username`, `email`, `permissions`). `GET /api/v1/auth/me` added to `AuthController`; takes `Authentication` param (no `SecurityContextHolder` coupling); looks up user by UUID subject, fetches permissions via `RoleService.permissionKeysForUser`. `WebSecurityConfig` gains `authenticationEntryPoint` returning 401 for unauthenticated requests (was 403). `AuthControllerMeTest` (`@WebMvcTest`) confirms 200 + payload when authenticated, 401 without. 80/80 green. Verified 2026-06-09 (commit 041bf09).

### T008b — Verify BCrypt password hashing end-to-end · `DONE` · P0
**Expected:** confirm register stores a BCrypt hash and login verifies against it (not plaintext / not a placeholder).
**Acceptance:** stored password column is a BCrypt hash; wrong password fails; a test asserts the hash format.
**Done:** `UserServiceCreateTest.createUser_storesBcryptHashedPassword` asserts the saved password starts with `$2` (BCrypt prefix) and is not equal to the plaintext input. Wrong-password path already covered by `UserServiceLockoutTest`. 80/80 green. Verified 2026-06-09 (commit 041bf09).

### T008c — Bootstrap the first admin on an empty server · `DONE` · P0 · (needs T008)
**Expected:** a fresh deployment (0 rows in `users`, no public register since T008) can get its first admin without hand-written SQL: on startup, if no user holds the Flyway-seeded `admin` role and `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` are all set, create that user with the `admin` role.
**Acceptance:** the bootstrapped admin can log in and holds every core permission; restarting with the vars still set never creates a second admin or overwrites the first; the creation is in the audit chain.
**Done:** `AdminBootstrapRunner` (`Component/`, an `ApplicationRunner`, `@Transactional`) reads `foundation.bootstrap-admin.*` (mapped to the env vars in `application.yml`). It locks the `admin` role row (`RoleRepository.lockByCode`, `SELECT … FOR UPDATE`) so concurrent starts serialize, then does nothing if `UserRepository.existsByRoles_Code("admin")`. Otherwise it creates the user through `UserService.createUser` (same static BCrypt, change event and `USER_CREATE` audit), grants `admin`, and records an `ADMIN_BOOTSTRAP` audit event (severity `WARN`, system actor). It never promotes or overwrites an existing account: if the username or email is already taken it logs and skips. Partial vars → warn and skip; no vars and no admin → warn that nobody can administer the server. Every outcome with vars set logs a warning to remove them; the password is never logged. `AdminBootstrapRunnerTest` (6 unit cases) + `AdminBootstrapIT` (Testcontainers: fresh DB → one admin, HTTP login + `/auth/me` shows all core permissions, `ADMIN_BOOTSTRAP` audited, re-run leaves the admin and its hash unchanged). 199/199 green with `mvn -B -Pintegration clean verify` on the homelab docker-host (JDK 21). Verified 2026-10-19. **Operator note:** to seed the live cluster, add the three vars to the `crm-server` Deployment (e.g. from `crm-server-secret`), roll it once, log in, then remove them.

---

## B-EPIC-3 — Audit Log (hash-chained)
*Goal: append-only, tamper-evident audit trail.*

### T009 — `AuditService` with hash chaining · `DONE` · P0
**Expected:** `AuditService.record(...)` computes `prev_hash` and `hash = SHA-256(prev_hash || canonical_json(payload))`; first event uses 32 zero bytes.
**Acceptance:** create/update/delete writes a chained row; recomputing the chain in a test validates integrity; tampering a past row breaks all later links.
**Done:** `HashChainComputer` computes `SHA-256(prevHash || canonicalJson(payload))` with a 32-zero-byte genesis; `AuditServiceImpl`/`AuditWriter` persist chained rows via `AuditEventRepository`. Verified 2026-06-09 (commits a423b61, 1dee5c8, 8e9deee).

### T009a — Single-writer chain serialization · `DONE` · P0 · (needs T009)
**Expected:** serialize audit writes (single dedicated writer or `SELECT … FOR UPDATE` on latest) so concurrent writes can't fork the chain.
**Acceptance:** concurrent writes produce a single unbroken chain in a test.
**Done:** `AuditWriter` is the single dedicated writer bean; a `ReentrantLock` serializes read-latest/compute/persist. `AuditServiceIT` fires 100 concurrent writes via `ExecutorService` and walks the resulting chain to confirm it's unbroken. Verified 2026-06-09 (commit 8e9deee).

### T009b — Full audit payload capture · `DONE` · P0 · (needs T009)
**Expected:** persist actor, source IP, entity type/id, op, before/after JSON, severity.
**Acceptance:** an audited operation records all fields; sensitive reads (e.g. guest IDs) and auth events are auditable.
**Done:** `AuditPayload`/`AuditWriter` already captured all fields (T009). Wired call sites: `AuthController.login` records `AUTH_LOGIN_SUCCESS` (INFO, actor=userId, entityType=User, entityId=userId) and `AUTH_LOGIN_FAILURE` (WARN, actor=null) on every attempt, capturing `sourceIp` from `HttpServletRequest`. `UserController.createUser` records `USER_CREATE` (INFO, actor=adminId, entityType=User, entityId=newUserId). `AuthControllerAuditTest` + `UserControllerAuditTest` verify all payload fields via `ArgumentCaptor`. 86/86 green. Verified 2026-06-09 (commit 3e74c71).

### T009c — Weekly chain-verification job · `DONE` · P2 · (needs T009)
**Expected:** scheduled task recomputes the chain; on break, writes a `CRITICAL` event + admin alert.
**Acceptance:** a deliberately broken chain triggers a CRITICAL event in a test.
**Done:** `ChainVerifier` runs weekly (`@Scheduled(cron = "0 0 3 * * SUN")`), recomputes the chain end-to-end, and on the first divergence records a `CHAIN_BREAK_DETECTED`/`CRITICAL` audit event. `ChainVerifierIT` tampers rows via raw JDBC and asserts detection + correct break index + recorded event. Verified 2026-06-09 (commit afa72b6).

### T010 — Narrow `AuditListener` to defaults only · `DONE` · P1 · (needs T009)
**Expected:** keep the JPA listener limited to timestamp/defaulting; move all `audit_event` writes into explicit services.
**Acceptance:** no audit rows are produced inside entity lifecycle callbacks; intent is clear from the class name/docs.
**Done:** renamed `Audit.AuditListener` → `Component.TimestampingListener` (it only fills `createdAt`/`updatedAt`); updated `@EntityListeners` references on `User`, `Role`, `Permission`, `Attachment`, `PasswordResetToken`, `PluginRegistry`. The `Audit` package/class names are now free for the real hash-chain audit log (T009). Verified 2026-06-09 (commit 8c66422).

---

## B-EPIC-4 — Sync / Change Feed (server side)
*Goal: clients can pull deltas.*

### T011 — Emit `change_event` on mutating writes · `DONE` · P1
**Expected:** after a successful write, emit a change event (plugin id, entity type/id, version, op, occurred_at).
**Acceptance:** user/role/permission changes create `change_event` rows; events only appear after successful persistence.
**Done:** `ChangeEventService` interface + `ChangeEventServiceImpl` (saves to `change_event` via `ChangeEventRepository`). Wired into `UserServiceImpl.createUser` — emits op=CREATE, pluginId=core, entityType=User, version=1. `ChangeEventServiceTest` + `UserServiceCreateChangeEventTest` verify fields via `ArgumentCaptor`. 88/88 green. Verified 2026-06-09 (commit 5efa7eb).

### T011a — Same-transaction change events · `DONE` · P1 · (needs T011)
**Expected:** write the change_event inside the same transaction as the data write.
**Acceptance:** a rolled-back data write produces no change_event (verified in a test).
**Done:** `ChangeEventServiceImpl.record` uses `@Transactional(REQUIRED)`, so it joins the caller's transaction. `ChangeEventSameTransactionIT` uses `TransactionTemplate` to verify: rolled-back write produces no rows; committed write produces exactly one row. Verified 2026-06-09 (commit 5efa7eb).

### T012 — Delta feed endpoint · `DONE` · P1 · (needs T011)
**Expected:** `GET /api/v1/sync/changes?since=...` returns ordered change events; secured + permissioned; explicit response DTO.
**Acceptance:** client pulls changes incrementally by timestamp/cursor; unauthorized access is rejected.
**Done:** `SyncController` exposes `GET /api/v1/sync/changes?since=<ISO instant>`, gated by new `core.sync.read` permission (`@PreAuthorize`); V4 migration seeds the permission and grants it to admin. `ChangeEventService.changesSince` → `findByOccurredAtAfterOrderByOccurredAtAsc` returns events oldest-first; explicit `ChangeEventResponse` DTO decouples the wire shape from the entity. `SyncControllerTest` covers 200+ordering+payload, 400 missing `since`, 403 without permission, 401 unauthenticated. 106/106 green. Verified 2026-06-10 (commit 2a1c668).

### T012a — WebSocket change push · `DONE` · P2 · (needs T012)
**Expected:** push change notifications over WebSocket as a real-time complement to polling.
**Acceptance:** a connected client receives a notification on a write without polling.
**Done (TDD):** `Sync.SyncWebSocketHandler` at `/ws/sync` fans out committed change events as JSON (`ChangeEventResponse` shape, same as the delta feed); per-session send isolation (one broken pipe never blocks others). `ChangeEventServiceImpl.record` publishes `ChangeEventRecorded`; `SyncChangeNotifier` listens `AFTER_COMMIT` (rolled-back writes never pushed — extends T011a's guarantee) and never propagates push failures (polling is the fallback). Handshake auth via `SyncHandshakeInterceptor` (`?token=` access JWT — browsers can't set headers on WS upgrade); `/ws/sync` permitted in `WebSecurityConfig` since the interceptor owns auth. Also fixed a latent flaky test (`validateToken_returnsFalseForTamperedToken` tampered the signature's final base64url char, which only carries 2 significant bits — now tampers mid-payload). 9 new tests; 172/172 green. Verified 2026-06-11.

---

## B-EPIC-5 — Plugin Host
*Goal: the platform's core differentiator — isolated plugin loading.*

### T013 — Plugin manifest model + registry service · `DONE` · P0
**Expected:** manifest DTO/model + `PluginRegistryService` with register / activate / disable / mark-load-failed / mark-uninstall-pending.
**Acceptance:** plugin state transitions are explicit and test-covered; state persists in `plugin_registry`.
**Done:** `Plugin.PluginManifest` record models the parsed `plugin.yaml` (id, version, display-name, depends-on, permissions-declared, foundation-services-used, schema, entry — per spec); `Plugin.PluginState` enum documents the lifecycle (REGISTERED → ACTIVE ⇄ DISABLED, any → LOAD_FAILED with reason, any → UNINSTALL_PENDING terminal). `PluginRegistryServiceImpl` enforces transitions: duplicate register → 400, activate/disable on UNINSTALL_PENDING → 400, unknown plugin → 404; activate stamps `lastLoadedAt` and clears `errorMessage`; manifest persisted to `manifest_json` via Jackson. 11 transition tests in `PluginRegistryServiceTest`; 117/117 green. Verified 2026-06-10 (commit f860caf).

### T014 — ZIP discovery + validation · `DONE` · P0 · (needs T013)
**Expected:** scan the plugin dir on startup; validate ZIP contents are traversal-safe; parse `plugin.yaml`; validate required fields before load.
**Acceptance:** invalid/malicious packages are rejected with a clear reason; a valid manifest parses.
**Done (TDD):** `Plugin.PluginZipValidator` rejects Zip-Slip entries (absolute paths, `..` segments incl. backslash variants) and packages without a root-level `plugin.yaml`; `Plugin.PluginManifestParser` (SnakeYAML `SafeConstructor` — no arbitrary type instantiation from untrusted manifests) parses kebab-case keys into `PluginManifest` and requires `id`/`version`/`schema`/`entry`; `Plugin.PluginDiscovery` scans `foundation.plugins.directory` for `*.zip` and returns per-package `PluginDiscoveryResult` (parsed manifest or rejection reason) so one bad package never aborts the scan — the seam T014a builds on. Startup wiring (registry register/mark-load-failed) lands with **T014a**. 20 tests across `PluginZipValidatorTest`/`PluginManifestParserTest`/`PluginDiscoveryTest`; 137/137 green. Verified 2026-06-11.

### T014a — Broken plugin does not crash startup · `DONE` · P0 · (needs T014)
**Expected:** a plugin failing validation/migration is marked `load_failed` with reason; other plugins still load.
**Acceptance:** with one broken and one valid plugin, the app starts and the valid one loads.
**Done (TDD):** `Plugin.PluginHost` (`ApplicationRunner`) orchestrates startup: scans via `PluginDiscovery`, registers new plugins, activates loadable ones, marks per-plugin failures `LOAD_FAILED` with reason — each plugin isolated in its own try/catch so neither a rejected package, a throwing load, nor even a failing `markLoadFailed` aborts the host or the app. `DISABLED`/`UNINSTALL_PENDING` are skipped; already-registered plugins re-activate. Classloading (T015) and per-schema migration (T015a) slot into `loadPlugin`. 7 tests in `PluginHostTest` (incl. broken+valid side-by-side); 144/144 green. Verified 2026-06-11.

### T015 — Classloader + entrypoint resolution · `DONE` · P0 · (needs T014)
**Expected:** `URLClassLoader`-per-plugin (parent-last); resolve and instantiate the declared entrypoint.
**Acceptance:** plugin classes load isolated; entrypoint `onLoad` is invoked.
**Done (TDD):** `Plugin.PluginActivator` is the entrypoint contract (`onLoad()`); `Plugin.ParentLastClassLoader` (child-first `URLClassLoader`, parallel-capable) delegates `java./javax./jdk./com.crm.foundation.` to the parent so API types stay castable while plugin jars win otherwise; `Plugin.PluginLoader` extracts `lib/*.jar` to a temp work dir, builds the loader, and instantiates the declared entry (clear rejections: missing class / not an activator / no no-arg ctor / no jars). `PluginHost.loadPlugin` now loads the entrypoint and invokes `onLoad()` before `activate`; load failure → `LOAD_FAILED`. `PluginLoaderTest` compiles a real plugin jar at test runtime (javax.tools) and proves isolation (plugin class in its own loader, castable to parent-loaded `PluginActivator`, `onLoad` observed); 4 loader + 8 host tests; 149/149 green. Verified 2026-06-11.

### T015a — Flyway-per-plugin-schema · `DONE` · P0 · (needs T015)
**Expected:** run the plugin's Flyway migrations against its own Postgres schema.
**Acceptance:** plugin tables are created in the plugin schema, not `public`.
**Done (TDD):** `Plugin.PluginMigrator` extracts `db/*.sql` from the validated package and runs Flyway with the manifest's `schema` as `defaultSchema`+`schemas` (`createSchemas`), so plugin tables and `flyway_schema_history` land in the plugin schema, never `public`; packages without `db/` skip cleanly. `PluginHost` migrates between register and entrypoint load; migration failure → `LOAD_FAILED`. `PluginMigratorTest` (3, DB-free extraction/skip) + `PluginMigratorIT` (Testcontainers: `demo_thing` + history in `plugin_demo`, absent from `public`) + host wiring test; 153/153 unit green (IT Docker-gated). Verified 2026-06-11.

### T015b — Minimal demo plugin loads end-to-end · `DONE` · P0 · (needs T015a)
**Expected:** a minimal demo plugin is discovered, registered, and migrated into its own schema.
**Acceptance:** after startup the demo plugin is `active` in `plugin_registry` with its schema present.
**Done:** `DemoPluginEndToEndIT` builds a complete demo package at test runtime (manifest + runtime-compiled entrypoint jar + `db/V1__init.sql`), drops it into a temp plugin dir via `@DynamicPropertySource`, boots the full Spring context, and asserts: registry row `ACTIVE` with `lastLoadedAt`, `demo_e2e_item` table present in `plugin_demo_e2e` schema, `onLoad()` observed. All pipeline stages individually unit-proven (T014–T015a); **run `mvn test -Pintegration` on a Docker-capable machine to confirm e2e** — this sandbox's Docker API is filtered so the IT skips. 2026-06-11.
**Verified e2e 2026-10-17:** `mvn -Pintegration clean verify` on the homelab docker-host (Temurin 21 + Docker 29) — `DemoPluginEndToEndIT` green.

---

## B-EPIC-6 — API Quality & Testing
*Goal: consistent error contract + proven runtime paths.*

### T016 — RFC 7807 ProblemDetail responses · `DONE` · P1
**Expected:** structured `application/problem+json` for validation / auth / forbidden / not-found / conflict / plugin-load, each with a stable `type` and a `trace_id`.
**Acceptance:** clients receive consistent machine-readable error payloads; validation errors include field details.
**Done (TDD):** `ErrorHandler` rewritten on Spring's `ProblemDetail`: stable `urn:problem-type:*` types for validation (400, per-field `errors` list), unauthorized (401, keeps `AuthException` code), forbidden (403, `AccessDeniedException`), not-found (404), bad-request (400), conflict (409, `OptimisticLockingFailureException`), plugin-load (422, `InvalidPluginPackageException`). Every problem carries a unique `trace_id` (logged for correlation) and a `code` extension so the existing FE client's `codeOf()` keeps working (FE reads status + optional `code`; `detail` replaces `message` — fallback to statusText is graceful). 9 tests in `ErrorHandlerTest`; 162/162 green. Verified 2026-06-11.

### T017 — Integration tests (Testcontainers) · `DONE` · P1 · (needs epics 2–4)
**Expected:** Postgres-backed tests for login, refresh rotation, protected access, 403 denial, audit write, change-event write.
**Acceptance:** core auth/security/audit/sync proven end-to-end, not only by mocks.
**Done:** `Auth.AuthFlowIT` (RANDOM_PORT + `TestRestTemplate`, real HTTP against Testcontainers Postgres): login returns usable bearer; `/auth/me` 200 with token, 401 without; permissionless user gets **403 problem+json** on `/users/{id}` while admin gets 200; refresh rotates and the old token is single-use (replay → 401); login writes an `AUTH_LOGIN_SUCCESS` audit row. Change-event write already covered by `ChangeEventSameTransactionIT`; audit chaining by `AuditServiceIT`/`ChainVerifierIT`. **Run `mvn test -Pintegration` on a Docker-capable machine** — sandbox Docker API filtered. 2026-06-11.
**Verified 2026-10-17 (homelab docker-host, 190/190 green, 0 skipped).** The first real run showed the ITs had never executed, and surfaced production bugs, all fixed:
- *Harness:* surefire skipped `*IT.java`; Testcontainers 1.20.x (Docker API 1.32) was rejected by Docker 29 → 1.21.4; `src/test/resources/application.yml` shadowed the main config → moved to `config/`.
- *Audit (prod):* `source_ip` String bound to `inet` failed every login/user-create audit insert → `CAST(? AS inet)`. Hashes were computed over values Postgres then normalized (`jsonb` formatting, `inet`, sub-µs timestamps), so the verifier would flag untouched events as tampered → `AuditWriter` now hashes the DB-normalized form. The in-JVM lock released before commit (two writers could link to the same predecessor) and chain order used a pre-lock caller timestamp → Postgres `pg_advisory_xact_lock` held to commit + strictly increasing µs `occurred_at` assigned under the lock.
- *Auth (prod):* `/auth/me` and `POST /users` parsed `authentication.getName()` as a UUID, but the principal is the `User` entity → 400 for every real user → `@AuthenticationPrincipal User`.
- *Tests:* AuthFlowIT used a nonexistent `PasswordEncoder` bean; admin-seed test read a lazy collection outside a session; ChainVerifierIT tampered via JDBC but verified through stale JPA cache.

---

## B-EPIC-7 — Plugin runtime
*Goal: a plugin can be a real vertical — persist through Foundation's transaction, expose endpoints, register permissions — without ever taking the server down.*
*Spec: `2026-10-22-hotel-booking-slice-design.md` §6.1–6.4 — design doc kept locally (docs/superpowers is git-ignored). Built on branch `claude/hotel-booking-slice`.*

### T018 — Two-phase plugin host · `DONE` · P0 · (needs T015b)
**Expected:** plugin JPA entities live in Foundation's own `EntityManagerFactory` (so one transaction covers plugin data, `change_event` and audit); an early phase prepares plugins before Hibernate starts, a late phase brings them up after the context is ready; `server/` becomes a Maven reactor with a `plugin-api` module for plugin-facing types.
**Acceptance:** a plugin entity persists through Foundation's EMF and rolls back together with its `change_event`; a plugin with a broken or out-of-schema mapping is `LOAD_FAILED` while the app and the other plugins start; `DemoPluginEndToEndIT` stays green.
**Done:** (7c795c1) `server/` split into `plugin-api` (`PluginActivator`, `CommonResponse`, recorder interfaces — same FQNs), `plugins/hotel` and `foundation` (the Spring Boot app, `foundation/src`). (2a0269d) Early phase: `PluginBootstrap` runs before the EMF is built — discover, build the parent-last classloader, run the plugin's Flyway migration into its schema (after Foundation's own), scan its `@Entity` classes (own jars only, so a shared package prefix never pulls in Foundation entities) and report READY/FAILED/SKIPPED (DISABLED and UNINSTALL_PENDING contribute nothing); `PluginJpaContributor` adds the READY entities to the persistence unit and hands the plugin classloaders to Hibernate. (087e6c8, e19236d, 51e34e0) Isolation: `PluginEntityValidator` rejects any entity that maps a table outside the manifest `schema` (`@Table`, secondary/join/collection tables) and trial-maps each plugin's entities in a throwaway EMF with `validate`, so a mapping error fails only that plugin; it also rejects entity names clashing with Foundation's and checks isolation against Hibernate's own built metadata. Late phase: see T019. `PluginJpaIT` (commit/rollback with `change_event` atomic), `PluginIsolationIT` (good + bad-mapping + schema-escape plugins in one directory, app starts, only the good one maps), `PluginBootstrapTest`, `PluginEntityValidatorTest`. Verified 2026-10-23 (homelab docker-host, `-Pintegration clean verify`, 340/340 green).

### T019 — Plugin child context + routes · `DONE` · P0 · (needs T018)
**Expected:** each plugin gets its own Spring child context (services, Spring Data repositories on Foundation's EMF/transaction manager, controllers); its controllers are served under `/api/v1/plugins/{route}/…` with method security; plugins get a small Foundation API (change events, audit, current user, problem errors).
**Acceptance:** a plugin endpoint is reachable at its prefix, 401 without a token, 403 without the permission, 404 once the plugin is disabled/failed; a plugin write and its `change_event` commit or roll back together.
**Done:** (12b7657) `plugin-api` gains `ChangeRecorder`, `AuditRecorder`, `CurrentUser`, `ChangeOp`, `PluginProblem` (status + stable code → problem+json via `ErrorHandler`), implemented in Foundation over `ChangeEventService`/`AuditService`/the security context. (6bf9e04) Optional manifest field `route` (default: last dot-segment of the id, validated `^[a-z][a-z0-9-]{1,31}$`). (d9684d6) `PluginContextFactory` builds the child context on the bootstrap classloader (`PluginChildConfiguration`: method security + transaction management; component scan of the entry package restricted to the plugin's own jars; repositories registered against Foundation's EMF); `PluginRouteRegistry` maps `@RestController` beans under the prefix (duplicate route rejected, roll-back on partial failure, unregistered on failure/shutdown); `PluginHost` rewritten as the late phase (register → start → `onLoad()` → routes → permissions → activate; any failure → routes removed, child closed, `LOAD_FAILED`). Unmatched URLs now return a 404 problem instead of 401. (993edc0, 3efaa7c) Error contract hardened: `sendError` statuses and `ResponseStatusException`/`@ResponseStatus` keep their status for authenticated callers; any unexpected exception becomes a generic 500 problem (`INTERNAL_ERROR`, `trace_id`, no message leak). `PluginRuntimeIT` (15: prefix, 403 problem+json, 401, unknown route 404, atomic write + rollback, child config stays out of the parent context, error shapes), `PluginRouteRegistryTest`, `PluginRecordersTest`, `ErrorHandlerTest`. Verified 2026-10-23 (340/340 green).

### T020 — Plugin permissions · `DONE` · P0 · (needs T019)
**Expected:** a plugin's `permissions-declared` keys are registered in `permissions` (with `plugin_id`) and granted to the `admin` role; plugin endpoints enforce them like Foundation endpoints.
**Acceptance:** after install the keys exist with `plugin_id` and `admin` holds them; a restart does not re-grant a permission an admin removed; Foundation permissions are untouched.
**Done:** (c9c8573) `PluginPermissionRegistrar` upserts every declared key (deduped) with `plugin_id` and grants **only newly inserted** keys to `admin`; it runs after the routes are registered and before `activate`, inside the host's failure handling. Permissions reach users through the JWT, so an admin must log in again after a plugin is first installed (documented in the README, not engineered around). `PluginPermissionRegistrarIT` (4: inserted with `plugin_id`, granted to admin, re-grant only on first insert, Foundation permissions untouched), `PluginHostTest`. Verified 2026-10-23 (340/340 green).

---

# HOTEL PLUGIN (`server/plugins/hotel/`)

## H-EPIC-1 — Hotel booking slice
*Goal: a receptionist can find free rooms for a date range and book one for a guest, and a booking can never collide with another one. Server side only; the desktop screens come with F301/F401.*
*Spec: `2026-10-22-hotel-booking-slice-design.md` — design doc kept locally (docs/superpowers is git-ignored). The plugin ID is `com.crm.hotel`, schema `hotel`, route `hotel`; permissions `hotel.rooms.*`, `hotel.guests.*`, `hotel.reservations.{read,create,cancel}` (admin only for now — no role management yet).*

### H101 — Room catalog · `DONE` · P0 · (needs T020)
**Expected:** the hotel plugin module and its schema; room types (`{vi,en}` names, max guests, one gross nightly rate in whole VND) and rooms (number, type, floor, in service) with list/create/update endpoints.
**Acceptance:** the hotel ZIP installs at startup under `/api/v1/plugins/hotel` with its 7 permissions on `admin`; reading needs `hotel.rooms.read`, writing `hotel.rooms.manage`; updates use `If-Match` with the entity version and a stale version is 409; duplicates are 409 `HOTEL_DUPLICATE`.
**Done:** (35e5e57) Maven module `com.crm.plugins:hotel` builds `hotel-0.1.0-plugin.zip` (`plugin.yaml`, `db/V1__hotel_booking.sql`, one jar of `com.crm.hotel.**`); the migration creates the `hotel` schema (room_type, room, guest, reservation, the reservation-code sequence and the `reservation_no_overlap` exclusion constraint on `btree_gist`); `server/Dockerfile` builds it and ships the ZIP in the image at `/app/plugins`; `foundation` test-depends on the ZIP, so focused test runs must use `verify`. (a72f2bc) `RoomCatalogController`/`RoomCatalogService`: `If-Match` required on PUT (missing → 428 `HOTEL_VERSION_REQUIRED`, stale → 409), unique violations → 409 `HOTEL_DUPLICATE`, unknown ids → 404 `HOTEL_NOT_FOUND`, Bean Validation → 400 `VALIDATION_ERROR`; every write records a `change_event` in the same transaction. `HotelPluginInstallIT` (5), `RoomCatalogIT` (9), `HotelErrorsTest`. `docker build server/` on the homelab docker-host succeeds and the image holds `/app/plugins/hotel-0.1.0-plugin.zip` (image removed afterwards). Verified 2026-10-23 (340/340 green).

### H102 — Guests · `DONE` · P0 · (needs H101)
**Expected:** create guests and search them by name or phone ignoring Vietnamese accents and case.
**Acceptance:** "Nguyen" finds "Nguyễn"; a phone fragment finds the guest; `%` and `_` in the query are literal; the same phone may belong to several guests.
**Done:** (fe56bb1) `GuestSearchKey` normalizes (NFD, strip combining marks, `đ` → `d`, lower case) name + phone into a stored `search_key`, searched with an escaped `LIKE` (results ordered by name, capped at 50); blank or normalizes-to-empty query → 400 `HOTEL_INVALID_QUERY`. Permissions `hotel.guests.read` / `hotel.guests.manage`; create records a `Guest` change event. `GuestSearchKeyTest` (5), `GuestIT` (8). ID documents and nationality are deliberately not here — they arrive with check-in and the police export. Verified 2026-10-23 (340/340 green).

### H103 — Availability search and stay rules · `DONE` · P0 · (needs H101)
**Expected:** `GET /availability?checkIn=&checkOut=&adults=` returns the free rooms grouped by room type with the nightly rate and the stay total; the stay rules and gross pricing used by booking.
**Acceptance:** rooms with a CONFIRMED overlapping reservation, out-of-service rooms, inactive types and types too small for `adults` are absent; cancelled reservations free the room; back-to-back stays do not collide.
**Done:** (e173e3c) `StayPolicy` (pure): `check_out > check_in`, 1–60 nights, check-in not before today in Asia/Ho_Chi_Minh (injectable `hotelClock`), adults ≥ 1 and ≤ `max_guests`, total = gross nightly rate × nights with overflow → 400 `HOTEL_INVALID_STAY`; `AvailabilityService` is one JPQL query (`NOT EXISTS` over CONFIRMED overlapping `[check_in, check_out)` ranges, no N+1); missing or malformed parameters → 400 `HOTEL_INVALID_QUERY`; permission `hotel.reservations.read`. `StayPolicyTest` (9), `AvailabilityIT` (8). Verified 2026-10-23 (340/340 green).

### H104 — Reservations: create, view, list, cancel · `DONE` · P0 · (needs H102, H103)
**Expected:** create a reservation for an existing guest or an inline new guest, get one, list by date-range overlap, cancel; the booking can never double-book a room, even under concurrency.
**Acceptance:** two overlapping bookings of one room → one 201 and one 409 `HOTEL_ROOM_UNAVAILABLE`, enforced by the database; cancelling frees the room at once; create and cancel write `change_event` and audit rows in the same transaction.
**Done:** (d0185f6) `ReservationService.create` (one transaction): exactly one of `guestId`/`newGuest`; locks the room row (`FOR NO KEY UPDATE`, so bookings of one room queue instead of deadlocking while catalog edits wait only for an in-flight booking); stay rules; room in service and type active (else 409 `HOTEL_ROOM_NOT_BOOKABLE`); overlap pre-check for a friendly 409; rate snapshotted with the gross total; code `R-<year>-<6-digit sequence>`; the Postgres exclusion constraint `reservation_no_overlap` stays the guarantee — a violation (SQLState 23P01 naming it) maps to 409 `HOTEL_ROOM_UNAVAILABLE` and a losing inline guest rolls back with it. Cancel locks the reservation, `CONFIRMED → CANCELLED` only (else 409 `HOTEL_ALREADY_CANCELLED`), `Reservation` change events plus audit `RESERVATION_CREATE` / `RESERVATION_CANCEL` with before/after JSON. Permissions `hotel.reservations.{read,create,cancel}`. `ReservationFlowIT` (14: book, view, list, availability disappears and returns, cancel, rules, 403 for a read-only user), `ReservationConcurrencyIT` (8 parallel bookings → exactly one 201, seven 409, one row; deterministic exclusion-constraint path), `ReservationServiceTest`. Verified 2026-10-23 (340/340 green).

**Not in this slice:** check-in/out, folio and payments, rate plans, a receptionist role (needs role management), the generic desktop write endpoint (D302 forward contract), hotel screens in the desktop app.

## H-EPIC-2 — Check-in, folio, check-out
*Goal: a receptionist can run a whole stay on the server: see who arrives, check the guests in with their identity records, charge and take payment (including OTA prepayment), and check them out at a zero balance, with a checked-in room staying blocked until then. Server side only; the desktop screens come with F301/F401.*
*Spec: `2026-10-24-hotel-checkin-checkout-design.md` — design doc kept locally (docs/superpowers is git-ignored). Plugin version 0.2.0 (migration `V2__hotel_stay.sql`); new permissions `hotel.stays.{checkin,checkout,read}` and `hotel.folio.{read,charge,pay}` (admin only for now — admins log in again after the upgrade to receive them).*

### H201 — Stay lifecycle and occupancy · `DONE` · P0 · (needs H104)
**Expected:** reservations gain the statuses `CHECKED_IN` and `CHECKED_OUT`; a room with a checked-in guest stays blocked until check-out, even past the booked check-out date.
**Acceptance:** blocking statuses are exactly `CONFIRMED` and `CHECKED_IN` in the exclusion constraint and in every availability/overlap query; a slice-1 database migrates to V2 and still works.
**Done:** (fcf3e5d) `V2__hotel_stay.sql` extends `hotel.reservation` (status check of four values, `source`, `ota_channel`, `ota_reference`, `checked_in_at`, `checked_out_at`), recreates `reservation_no_overlap` as `WHERE (status IN ('CONFIRMED','CHECKED_IN'))` and adds `stay_guest`, `folio_charge`, `folio_payment` (all in schema `hotel`, no implicit join tables); `RoomRepository.findAvailable` and the overlap count (`countBlockingOverlaps`) use the same two statuses; the plugin and the Maven property are 0.2.0 with the six new permission keys and nine new error factories (`HOTEL_ROOM_OCCUPIED`, `HOTEL_PAYMENTS_ON_FOLIO`, `HOTEL_INVALID_STATE`, `HOTEL_NOT_CHECKIN_DAY`, `HOTEL_BALANCE_DUE`, `HOTEL_OVERPAYMENT`, `HOTEL_INVALID_ID_DOC`, `HOTEL_INVALID_PAYMENT`, `HOTEL_INVALID_SOURCE`). `OccupancyIT` (4: a checked-in room is absent from availability, booking over it is 409, the constraint itself rejects an overlapping insert with SQLState 23P01, checked-out and cancelled stays do not block), `HotelUpgradeIT` (1: Flyway V1 → data → V2 on a scratch schema), `HotelErrorsTest`, `HotelPluginInstallIT` updated. Verified 2026-10-25.

### H202 — Booking source and OTA reference · `DONE` · P1 · (needs H201)
**Expected:** a reservation records where it came from (`WALK_IN` default, `PHONE`, `OTA`, `OTHER`), the OTA channel and an optional external reference.
**Acceptance:** `source: OTA` requires an `otaChannel` and any channel requires `source: OTA`, else 400 `HOTEL_INVALID_SOURCE` before anything is written; unknown enum values are 400; the fields come back on every reservation view.
**Done:** (d2ca5e5) `CreateReservationRequest` and `ReservationView` carry `source`, `otaChannel`, `otaReference` (and the view `checkedInAt`/`checkedOutAt`); `ReservationService.create` resolves null → `WALK_IN` and validates before the room lock and the inline-guest insert, so a refused booking leaves no rows; the DB CHECK `(source = 'OTA') = (ota_channel IS NOT NULL)` is only the backstop. `BookingSourceIT` (6). Verified 2026-10-25.

### H203 — Check-in with identity records · `DONE` · P0 · (needs H201, H202)
**Expected:** `POST /reservations/{id}/check-in` with one identity record per adult (ID type CCCD/PASSPORT/OTHER, number, name, date of birth, nationality, address, visa and entry date for foreigners), optionally moving the guest to another room of the same type; `GET /reservations/{id}/stay-guests` reads the records.
**Acceptance:** only a CONFIRMED reservation whose check-in date has come (hotel-local) can be checked in, else 409 `HOTEL_NOT_CHECKIN_DAY` / `HOTEL_INVALID_STATE`; adults ≤ the room type's maximum; a swap needs a bookable room of the same type, an already occupied room is 409 `HOTEL_ROOM_OCCUPIED`; Vietnamese citizens need an address (400 `HOTEL_INVALID_ID_DOC`); reading the records needs `hotel.stays.read`; audit rows never hold identity data.
**Done:** (acb898b) `CheckInService.checkIn` validates the identity rules (`CheckInPolicy`, pure) before taking locks, then locks the reservation and the old and new rooms (`FOR NO KEY UPDATE`, ordered by id), checks occupancy under the room lock, sets room/status/`checkedInAt`/adults and flushes through the shared `OverlapGuard` so a swap into a room booked later in the stay surfaces as 409 `HOTEL_ROOM_UNAVAILABLE`; `Reservation` UPDATE and `StayGuest` CREATE change events; audit `RESERVATION_CHECK_IN` holds the status change, the adult count and the stay-guest ids only. `CheckInPolicyTest` (8), `CheckInIT` (13), `OverlapGuardTest` (2, the extracted mapping). Verified 2026-10-25.

### H204 — Undo check-in and identity corrections · `DONE` · P1 · (needs H203)
**Expected:** a mis-click on check-in can be undone on the same day; a typo in an identity record can be corrected without losing the audit trail.
**Acceptance:** `POST /reservations/{id}/undo-check-in` works only on the hotel-local check-in day while no extra charge (active or voided) exists, returns the stay to `CONFIRMED`, deletes the identity records and keeps payments; `PUT /reservations/{id}/stay-guests/{stayGuestId}` (If-Match) revalidates the record and audits the names of the changed fields, never their values.
**Done:** (ba6e606) `CheckInService.undo` (reservation lock, then a native count on `hotel.folio_charge`, so it is serialized with charge adds) records `RESERVATION_CHECK_IN_UNDO` and `StayGuest` DELETE change events; a room swap is not reverted; `correct` records `STAY_GUEST_CORRECT` against the reservation (changed field names only) and makes no write when nothing changed. Both need `hotel.stays.checkin`. (64083ef) the `If-Match` version check is now shared by the room catalog and the stay services. `UndoAndCorrectIT` (13). Verified 2026-10-25.

### H205 — Folio: charges, payments, voids · `DONE` · P0 · (needs H203)
**Expected:** each stay has a folio: the room total plus extra charges (minibar and the like) minus payments (cash, card, transfer, or OTA prepayment with its channel); lines are voided, never deleted.
**Acceptance:** all amounts are positive whole VND gross; the balance (`totalGrossVnd` + active charges − active payments) can never go below 0, so an overpayment (or voiding a charge that would cause one) is 409 `HOTEL_OVERPAYMENT`; `method: OTA` needs a channel and no other method may have one (400 `HOTEL_INVALID_PAYMENT`); charges only while CHECKED_IN, payments also while CONFIRMED (OTA prepayment), else 409 `HOTEL_INVALID_STATE`; the folio stays readable afterwards; a CONFIRMED booking with an active payment cannot be cancelled (409 `HOTEL_PAYMENTS_ON_FOLIO`); permissions `hotel.folio.{read,charge,pay}`.
**Done:** (b788fc0) `FolioService` takes the reservation's `PESSIMISTIC_WRITE` lock before every write, so concurrent payments cannot overpay (`concurrent_payments_cannot_overpay`: one 201, one 409); `FolioMath` holds the balance arithmetic; add endpoints return 201 and voids 200 with the whole `FolioView`; `FolioCharge`/`FolioPayment` change events and audit `FOLIO_CHARGE_ADD/VOID`, `FOLIO_PAYMENT_ADD/VOID` carry line views only. `FolioMathTest` (3), `FolioIT` (15). Verified 2026-10-25.

### H206 — Check-out at zero balance · `DONE` · P0 · (needs H205)
**Expected:** `POST /reservations/{id}/check-out` finishes a stay and frees the room at once, also before the booked date.
**Acceptance:** only a CHECKED_IN stay (else 409 `HOTEL_INVALID_STATE`); a non-zero balance is 409 `HOTEL_BALANCE_DUE` with the amount in the detail and nothing changes; afterwards the folio is read-only but complete; permission `hotel.stays.checkout`.
**Done:** (0bc22e0) `CheckOutService.checkOut` locks the reservation, checks the balance through `FolioService.balance`, sets `CHECKED_OUT` and `checkedOutAt`, records the `Reservation` UPDATE change event and audit `RESERVATION_CHECK_OUT` (before/after views, no identity data); early check-out frees the remaining nights for new bookings. `CheckOutIT` (6). Verified 2026-10-25.

### H207 — Front-desk lists · `DONE` · P1 · (needs H203)
**Expected:** `GET /front-desk/arrivals`, `/in-house` and `/departures` (optional `?date=`, default hotel-local today), each row a reservation view plus flags, so late arrivals and overstayers do not vanish.
**Acceptance:** arrivals are CONFIRMED with check-in ≤ date < check-out (`late` when check-in is before the date); in-house are CHECKED_IN (`overdue` when check-out is before today); departures are CHECKED_IN with check-out ≤ date (`overdue` when before it); ordered by room number; a malformed date is 400 `HOTEL_INVALID_QUERY`; permission `hotel.reservations.read`.
**Done:** (552d5c6) `FrontDeskService` runs one JPQL query per list joining guest and room (no N+1); `QueryDates.optional` treats a blank date as absent. `FrontDeskIT` (7). **End-to-end:** `FullStayIT` (1) chains the whole flow over HTTP: OTA booking (Agoda) → arrivals → check-in of two adults → in-house → OTA prepayment for the room total → minibar charge 85,000 (check-out refused with `HOTEL_BALANCE_DUE`) → cash 85,000 → departures on the check-out date → check-out → the same room is bookable again for the same dates. Full gate on the homelab docker-host: 460 tests (32 hotel unit + 428 foundation), 0 failures, 0 skipped; `docker build server/` ships only `hotel-0.2.0-plugin.zip` in `/app/plugins`. Verified 2026-10-25.

### H208 — Hotel business date · `DONE` · P0 · (needs H203, H207)
**Expected:** the front desk's "today" is the business date — the Asia/Ho_Chi_Minh date of (now − cutoff), cutoff `hotel.business-day-cutoff` (env `HOTEL_BUSINESS_DAY_CUTOFF`, default 06:00) — so a guest arriving at 00:30 on the night they booked can still be booked and checked in, stays on the default arrivals list, and a 23:55 check-in can be undone at 00:05 (spec §6.2b, decided by Mathisterben 2026-10-25).
**Acceptance:** the check-in day rule, undo check-in's same-day check, the front-desk default date and in-house `overdue` flag, and the earliest bookable check-in (booking and availability) use the business date; the identity entry-date check and the reservation-code year keep the calendar date; fixed-clock unit tests at 00:30 (still the previous business date) and 06:30 (the new one).
**Done:** `BusinessDay` (bean built in `HotelConfig` from the `hotelClock` and the parsed cutoff; a malformed cutoff fails the plugin's start) has `today()`, `dateOf(Instant)` and `calendarToday()`; it replaces `CheckInPolicy.today(Clock)` in `CheckInService` (check-in rule, undo same-day check; identity checks keep the calendar date), `FrontDeskService` (default date, `overdue`), `ReservationService` and `AvailabilityService` (`StayPolicy`'s today). The ITs' `hotelToday()` is the business date too, so they pass between midnight and 06:00. `BusinessDayTest` (7: 00:30, 06:30, the cutoff itself, 00:00 cutoff, a UTC clock, 23:55 → 00:05 same day, cutoff parsing), `CheckInPolicyTest` (10: midnight arrival on the check-out date checks in, 06:30 is too late, 05:30 is too early). Verified 2026-10-26.

### H209 — Undo check-out · `DONE` · P1 · (needs H206, H208)
**Expected:** a check-out clicked by mistake can be taken back on the same business date: `POST /reservations/{id}/undo-check-out`, `CHECKED_OUT → CHECKED_IN`, permission `hotel.stays.checkout` (spec §6.5a).
**Acceptance:** only a CHECKED_OUT stay on the business date of `checkedOutAt` (else 409 `HOTEL_INVALID_STATE`); locks the reservation, then the room; 409 `HOTEL_ROOM_OCCUPIED` when another stay is checked in to the room; 409 `HOTEL_ROOM_UNAVAILABLE` when the nights an early check-out freed were rebooked (through `OverlapGuard`); clears `checkedOutAt`, records the `Reservation` UPDATE change event and audit `RESERVATION_CHECK_OUT_UNDO` without identity data; the folio is writable again.
**Done:** `CheckOutService.undo` (reservation lock, business-date check, room lock, occupancy check, save through `OverlapGuard`), `StayController` endpoint. `CheckOutIT` (+6: same-day undo with change event, audit, identity records kept, folio writable and the room blocked again; earlier business date refused; only `CHECKED_OUT`; another stay checked in to the room → `HOTEL_ROOM_OCCUPIED`; freed nights rebooked → `HOTEL_ROOM_UNAVAILABLE`; 403/401). Full gate on the homelab docker-host: 477 tests (41 hotel unit + 436 foundation), 0 failures, 0 skipped. Verified 2026-10-26.

**Not in this slice:** the police stay-registration export (Khai báo lưu trú), tax invoices, rate plans and extra-guest pricing, housekeeping status, a receptionist role (needs role management), hotel screens in the desktop app.

## H-EPIC-3 — CCCD scan
*Goal: at check-in a receptionist uploads a photo of the guest's CCCD; the server reads the QR code, pre-fills the identity fields, keeps the photo against the identity record and deletes it 30 days after upload. Server side only; the desktop upload screen comes with F301/F401.*
*Spec: `2026-11-07-hotel-cccd-scan-design.md` — design doc kept locally (docs/superpowers is git-ignored). Plugin version 0.3.0 (migration `V3__hotel_id_scan.sql`, table `hotel.id_scan`); no new permission keys (`hotel.stays.checkin` uploads and links, `hotel.stays.read` views the image). The hotel ZIP's `lib/` now also holds `zxing-core` and `zxing-javase` (3.5.3).*

### H301 — Plugin scheduling and large uploads · `DONE` · P0 · (needs B-EPIC-7)
**Expected:** a plugin's Spring child context can run `@Scheduled` jobs, and the server accepts a 10 MB image upload.
**Acceptance:** multipart limits 10 MB per file / 11 MB per request, `server.tomcat.max-swallow-size` 12 MB, `spring.task.scheduling.pool.size` 2; an upload over the limit is 413 problem code `PAYLOAD_TOO_LARGE`.
**Done:** (86aa419) Foundation settings above and scheduling support in plugin child contexts. Verified 2026-11-07.

### H302 — CCCD QR parsing and image decoding · `DONE` · P0 · (needs H301)
**Expected:** a JPEG or PNG is decoded for its QR code and the text is parsed into identity fields.
**Acceptance:** the QR text has exactly 7 pipe-separated fields `idNumber|oldIdNumber|fullName|dateOfBirth(ddMMyyyy)|sex|address|issueDate(ddMMyyyy)`, valid only if `idNumber` is 12 digits, both dates parse and are not in the future, `fullName` is non-blank; the result carries `idType = CCCD`, `nationality = VN`; image decode memory and time are bounded and each upload is decoded once.
**Done:** (f7c2c44, d5b0b33, 8d1a977) `CccdQrParser`, `QrDecoder` and `ImageNormalizer` (stored as JPEG, long edge at most 1600 px) behind `ScanPipeline`, with the bundled zxing jars. `CccdQrParserTest`, `QrDecoderTest`, `ImageNormalizerTest`, `ScanPipelineTest`. Verified 2026-11-07.

### H303 — Scan upload · `DONE` · P0 · (needs H302)
**Expected:** `POST /reservations/{id}/id-scans` (multipart `image`, `hotel.stays.checkin`) stores the photo and returns the parsed fields.
**Acceptance:** 201 for a new scan, 200 for the same image bytes (sha256) on the same reservation; JPEG/PNG by magic bytes, at most 10 MB; at most 20 scans per reservation; errors `HOTEL_SCAN_UNREADABLE` 400, `HOTEL_SCAN_UNSUPPORTED` 415 (type), `HOTEL_SCAN_LIMIT` 409, `HOTEL_SCAN_BUSY` 503; an upload over 10 MB over HTTP is refused by Foundation first as 413 `PAYLOAD_TOO_LARGE` (the plugin keeps its own `HOTEL_SCAN_UNSUPPORTED` 413 size check as a second line); unreadable and unsupported uploads are not stored; the response carries `Cache-Control: no-store`; audit `ID_SCAN_UPLOAD` holds the size only, never the image, the QR text or a decoded value.
**Done:** (2c4a035) `IdScanService` under the reservation lock, `HotelIdScan` entity, `V3__hotel_id_scan.sql`. `IdScanServiceTest`, `IdScanUploadIT`. Verified 2026-11-07.

### H304 — Link to identity records and view · `DONE` · P0 · (needs H303, H203)
**Expected:** `StayGuestRequest.idScanId` links a scan to a stay guest; `GET /reservations/{id}/stay-guests/{stayGuestId}/id-scan` returns the image (`hotel.stays.read`).
**Acceptance:** `idScanId` is optional; an unknown scan, a scan of another reservation, one already linked to another guest, or one scan given to two adults is 409 `HOTEL_SCAN_MISMATCH`; replacing a stay guest's scan deletes the old one in the same transaction; undo check-in deletes the linked scans at once; the image response carries `Cache-Control: no-store`; the image never appears in audit, logs or the change feed.
**Done:** (01449b9) linking in `CheckInService`/`StayController`, image view endpoint. `IdScanLinkIT`. Verified 2026-11-07.

### H305 — 30-day purge · `DONE` · P1 · (needs H303)
**Expected:** a scan is deleted 30 days after upload, whether or not it is linked; a stay guest keeps the confirmed fields.
**Acceptance:** rows with `uploaded_at < now - 30 days` are deleted by a job that runs hourly on the hour (`0 0 * * * *`, zone Asia/Ho_Chi_Minh); audit `ID_SCAN_PURGE` (entity id null, `{"count": n}`) is written only when n > 0.
**Done:** (8ff4c30) `IdScanPurgeJob` on the plugin scheduler. `IdScanPurgeJobTest`, `IdScanPurgeIT`. Verified 2026-11-07.

**Not in this slice:** passport/MRZ scanning, OCR of the front of the card, the desktop upload screen, per-guest retention settings.

## H-EPIC-4 — Guest online check-in
*Goal: before arrival a guest opens a personal link on a phone, scans each adult's CCCD (or types a passport), consents and submits; staff see the details on the reservation and the data is deleted at check-in, at cancel and after 30 days. Hotel plugin 0.4.0 plus the new `guest-web/` page.*
*Spec: `2026-11-14-hotel-guest-checkin-web-design.md` — design doc kept locally (docs/superpowers is git-ignored). Migration `V4__hotel_guest_checkin.sql` (tables `hotel.guest_link`, `hotel.pre_arrival_guest`, column `hotel.id_scan.qr_digest`); no new permission keys (`hotel.stays.checkin` manages links, `hotel.stays.read` views submissions). Public host `checkin.mathisterben.org` through the Cloudflare Tunnel.*

### H401 — Public plugin routes and rate limit · `DONE` · P0 · (needs B-EPIC-5)
**Expected:** a plugin can expose a small unauthenticated surface, safely.
**Acceptance:** `@PublicEndpoint` methods must map under `/public/` and carry no `@PreAuthorize`, otherwise the whole plugin is refused (LOAD_FAILED); a `/public` mapping without the annotation is refused; public paths are matched after decoding and an encoded `public` segment is 400; per-client rate limit `CRM_PUBLIC_RATE_LIMIT_PER_MINUTE` (default 60, below 1 fails startup) answers 429 `RATE_LIMITED` with `Retry-After`; the client address is `X-Client-IP` only when `X-Guest-Proxy-Secret` equals `CRM_PUBLIC_PROXY_SECRET` (constant-time), IPv6 keyed by /64, never resolved; every public response carries `Cache-Control: no-store` and `X-Robots-Tag: noindex`.
**Done:** (63731ac, 52b5b72, 48ae863) `PublicEndpoint`, `PluginRouteRegistry` checks, `PublicRateLimitFilter`, `PublicPaths`. `PluginPublicRouteIT`, `PublicRateLimitFilterTest`. Verified 2026-11-14.

### H402 — Guest links · `DONE` · P0 · (needs H401, H203)
**Expected:** staff create, replace, revoke and view a personal check-in link for a reservation.
**Acceptance:** `POST|DELETE|GET /reservations/{id}/guest-link` (`hotel.stays.checkin`; `GET` `hotel.reservations.read`); POST returns the URL once (201, `no-store`), prefix `hotel.guest-base-url` (default `https://checkin.mathisterben.org`); only a CONFIRMED reservation whose expiry (06:00 hotel time on the day after check-out) is still ahead gets one, otherwise 409 `HOTEL_CHECKIN_CLOSED`; posting again replaces the token; only the SHA-256 of the token is stored; the token and URL are never audited.
**Done:** (757e8b6) `GuestLinkService`, `GuestLinkController`, `GuestSite` (`hotel.guest-base-url`, `hotel.display-name`), V4 migration, plugin version 0.4.0. `GuestLinkIT`, `BusinessDayTest`, `HotelPluginInstallIT`. Verified 2026-11-14.

### H403 — Public check-in API · `DONE` · P0 · (needs H402, H303)
**Expected:** a guest with the link reads the stay summary, uploads a CCCD photo and submits or removes each adult's details.
**Acceptance:** `GET /public/checkin`, `POST /public/checkin/id-scans`, `POST /public/checkin/guests`, `DELETE /public/checkin/guests/{id}` under `/api/v1/plugins/hotel/public`, token in header `X-Checkin-Token`; a missing, unknown or revoked token is 404 `HOTEL_NOT_FOUND`; the summary shows masked names only; submission needs `consent: true` and is idempotent per `(idType, idNumber)`; capacity `maxGuests` holds under parallel requests (`HOTEL_TOO_MANY_GUESTS`); a scan must belong to the reservation (`HOTEL_SCAN_MISMATCH`); fields the guest edited after the scan are recorded; removing a submission works with or without a photo; audit holds no identity data or token.
**Done:** (d354091, 00cc055) `PublicCheckinController`, `PreArrivalService`, `PreArrivalStore`, `MaskedName`, `QrDigest`. `PublicCheckinIT`, `MaskedNameTest`, `QrDigestTest`. Verified 2026-11-14.

### H404 — Staff view and clean-up · `DONE` · P1 · (needs H403)
**Expected:** staff read what guests submitted, and the data does not outlive its purpose.
**Acceptance:** `GET /reservations/{id}/pre-arrival-guests` and `GET .../pre-arrival-guests/{guestId}/id-scan` (`hotel.stays.read`, `no-store`); the arrivals list carries `preArrivalAdults`; submissions are deleted at check-in, at cancel (with their unlinked photos; the link row stays, so the guest's page answers `open:false`, and the purge removes links expired more than 30 days) and 30 days after submission by the existing hourly purge (audit `ID_SCAN_PURGE` holds `count` and `preArrival`); the `IdScan` entity is never loaded.
**Done:** (af041aa) `PreArrivalController`, `FrontDeskService`, `CheckInService`, `ReservationService`, `IdScanService.purgeOlderThan`. `PreArrivalStaffIT`, `IdScanPurgeIT`. Verified 2026-11-14.

### H405 — Guest web page and deployment · `DONE` · P0 · (needs H403)
**Expected:** a mobile page and a container that exposes only the public API.
**Acceptance:** `guest-web/` (React, Tailwind 4, Vietnamese default and English) reads the token from the URL fragment into memory only and removes it from the address bar; consent text is marked `TODO(legal)` for Mathisterben's review before real guests use it; the image is nginx-unprivileged on 8080 and proxies only `/api/v1/plugins/hotel/public/`, every other `/api` path is 404; env `CRM_SERVER_URL`, `CRM_PUBLIC_PROXY_SECRET`, `REAL_IP_FROM` (default `127.0.0.1/32`, trusts no proxy; production uses the tunnel connector's exact /32), refused at start if malformed; security headers and a strict CSP on every response; CI job `build_guest_web` pushes `10.0.0.7:30500/crm-guest-web:latest`.
**Done:** (7dcb41f, f287166, 21949e6, cb2270a, this commit) `guest-web/`, `nginx/`, `Dockerfile`, `scripts/smoke-guest-checkin.sh` (93 checks on the docker-host), `.gitlab-ci.yml`. 36 Vitest tests. Go-live (manifests, secret, Cloudflare hostname) is a separate step.

**Not in this slice:** desktop screens for links and submissions, sending the link by SMS or email, the real consent text (legal review), a browser run of the CSP on a real device.


## D-EPIC-7 — Dental pre-visit
*Goal: a dental clinic books appointments, gives each patient a personal link, and the patient fills in identity (CCCD photo or passport), allergies, medicines and the reason for the visit before arriving; staff read the answers and apply them to the patient record. Plugin `dental` 0.1.0 plus the dental flow in `guest-web/`, served at `nhakhoa.mathisterben.org`. Ships with the public route off.*
*Ticket ids are DN1xx so they do not clash with the desktop D-tickets (D101-D602). Spec: `2026-11-14-dental-previsit-design.md`, kept locally (docs/superpowers is git-ignored). Migration `V1__dental_init.sql` (schema `dental`); permissions `dental.patients.read|write`, `dental.appointments.read|write`, `dental.previsit.read`.*

### DN101 — Plugin skeleton, field encryption and the CCCD library · `DONE` · P0 · (needs H-EPIC-3)
**Expected:** a `dental` plugin that loads beside hotel, with AES-GCM field encryption and the CCCD reader shared as a library.
**Acceptance:** manifest id `com.crm.dental`, route and schema `dental`; `DENTAL_FIELD_KEY` (base64 of 32 bytes) validated when present and required when `dental.public.enabled=true` (otherwise LOAD_FAILED); the CCCD scan code lives in `libs/cccd-scan` and hotel uses it unchanged.
**Done:** `DentalPlugin`, `DentalConfig`, `FieldCipher`, `libs/cccd-scan`. `FieldCipherTest`, `DentalPluginInstallIT`, `DentalPublicWithoutKeyIT`.

### DN102 — Patients and bookings · `DONE` · P0 · (needs DN101)
**Expected:** staff register patients and book, reschedule, cancel and mark appointments.
**Acceptance:** `POST|PUT /patients`, `GET /patients?phone=|q=` (accent-insensitive; shared phones return every match; `+` sent as `%2B`); `POST /appointments` (existing or inline new patient; date of birth required), `GET /appointments?date=`, `PATCH` reschedule with `If-Match`, `cancel`, `arrive`, `no-show`; only `BOOKED` can change; errors are problem+json `DENTAL_*`.
**Done:** `PatientController`, `AppointmentController` and services. `PatientAppointmentIT`.

### DN103 — Visit links and the public switch · `DONE` · P0 · (needs DN102)
**Expected:** staff give a patient a personal link, behind a switch that ships off.
**Acceptance:** `POST|DELETE|GET /appointments/{id}/visit-link`; token of 32 random bytes, only its SHA-256 stored, shown once, expiry 23:59 clinic time on the appointment date; `dental.public.enabled=false` makes every dental public endpoint 404 and link creation `409 DENTAL_PUBLIC_DISABLED`.
**Done:** `VisitLinkService`, `VisitLinkController`, `DentalSite`. `VisitLinkIT`, `VisitLinkDisabledIT`.

### DN104 — Public pre-visit API · `DONE` · P0 · (needs DN103)
**Expected:** a patient with the link reads the summary, uploads a CCCD photo, submits the form and checks earlier answers.
**Acceptance:** `GET /public/visit`, `POST /public/visit/id-scans`, `/form`, `/previous` under `/api/v1/plugins/dental/public`, token in `X-Visit-Token`; summary shows a masked name only; a blank allergy or medicine answer is 400, never "none"; `consent: true` required; a child under 14 needs a parent's name and phone; allergies, medicines and reason stored AES-GCM encrypted; 5 wrong birth dates lock the previous-answers check; no identity or health value in audit, logs, change feed or errors.
**Done:** `PublicVisitController`, `PrevisitService`, `PrevisitRules`, `PrevisitStore`. `PublicVisitIT`.

### DN105 — Staff view of the form · `DONE` · P1 · (needs DN104)
**Expected:** staff holding `dental.previsit.read` read and apply what the patient sent.
**Acceptance:** `GET /appointments/{id}/previsit` and `.../previsit/id-scan` (`no-store`, audited `PREVISIT_VIEW` and `PREVISIT_PHOTO_VIEW`); applying the reviewed form to the patient record is bound to the form version; the day list carries a `previsit` flag; cancelling deletes the form and photo; CCCD photos are purged 30 days after upload.
**Done:** `PrevisitController`, `PrevisitStaffService`, `DentalIdScanPurgeJob`. `PrevisitStaffIT`.

### DN106 — Dental flow in guest-web · `DONE` · P0 · (needs DN104)
**Expected:** a mobile page for the dental link at `/#d/<token>`.
**Acceptance:** token read from the fragment into memory only; adult and child paths; CCCD photo or passport; allergy, medicine and reason questions that must be answered; consent text marked `TODO(legal)`; the answers are cleared after sending; Vietnamese default and English.
**Done:** `guest-web/src/dental/`. `DentalApp.test.tsx` and the shared component tests.

### DN107 — Host-routed guest pages, smoke and docs · `DONE` · P0 · (needs DN105, DN106)
**Expected:** one container serves both hosts without letting either reach the other's API, and the whole path is proven on the docker-host.
**Acceptance:** nginx has one `server` per host (`HOTEL_HOST`, default `checkin.mathisterben.org`, proxies only the hotel public API; `DENTAL_HOST`, default `nhakhoa.mathisterben.org`, only the dental one; any other Host is 404 for everything); both keep the hardening (normalised-path proxy, `;`/`%3b`/`%5c` guard, explicit secret and client address with forwarding headers, `Authorization` and `Cookie` blanked, realip from `REAL_IP_FROM` only, 11 MB body limit, no access log on the API, security headers and CSP on every response); `05-check-env.sh` validates both hosts as DNS names and that they differ; the smoke books an appointment, makes a link, reads the summary, submits a passport form with `none: true` allergies and reads it back, and checks the cross-host and unknown-host 404s; README "Dental pre-visit (dental 0.1.0)" holds the switch-on checklist.
**Done:** `guest-web/nginx/` (`default.conf.template`, `site-common.conf.template`, `proxy-common.conf.template`, `05-check-env.sh`), `Dockerfile`, `scripts/smoke-guest-checkin.sh` (212 checks), README, CLAUDE.md. Go-live (flag, key, Dentist role, Cloudflare and NPM hostnames) is a separate step.

**Not in this slice:** desktop screens for dental, sending the link by SMS or email, the real consent text and retention decision (legal sign-off), key rotation for `DENTAL_FIELD_KEY`.

---

# FRONTEND (React — `desktop/src/`)

> **State: greenfield.** Empty `auth/ data/ forms/ routes/ sync/` dirs; `package.json` is a stub with no React/Vite/TS. Everything below is new.

## F-EPIC-1 — Project scaffold

### F101 — Vite + React 18 + TypeScript baseline · `DONE` · P0
**Expected:** working Vite app with `index.html`, `main.tsx`, `App.tsx`.
**Acceptance:** `pnpm dev` serves the app; `pnpm build` produces a bundle.
**Done:** Vite 8 + React 18 + TS strict (3-file tsconfig, `@/*` alias). `pnpm dev` serves `:1420` (HTTP 200); `pnpm build` → `dist/`. Verified 2026-06-08.

### F102 — Tailwind + shadcn/ui + Lucide · `DONE` · P0 · (needs F101)
**Expected:** Tailwind configured; shadcn/ui component patterns; Lucide icons available.
**Acceptance:** a styled sample component renders with a Lucide icon.
**Done:** Tailwind v4 (`@tailwindcss/vite`) + shadcn (new-york/neutral, `components.json`, `cn`, Button) + lucide-react. `LanguageToggle` renders a Button with a Lucide icon. Verified 2026-06-08.

### F103 — Vitest + RTL smoke test · `DONE` · P0 · (needs F101)
**Expected:** Vitest + React Testing Library wired with one passing test.
**Acceptance:** `pnpm test` is green.
**Done:** Vitest 4 + RTL + jsdom (`src/test/setup.ts`). `App.test.tsx` (title render + language toggle, written test-first) — `pnpm test` 2/2 green. Verified 2026-06-08.

### F104 — Lint/format/strict TS · `DONE` · P1 · (needs F101)
**Expected:** ESLint + Prettier + `tsconfig` strict mode.
**Acceptance:** `pnpm lint` passes; strict type errors surface.
**Done:** ESLint 10 flat config + Prettier; TS strict. `pnpm lint` exit 0 (one benign shadcn `buttonVariants` co-export warning). Verified 2026-06-08.

### F105 — i18n (VI default, EN toggle) · `DONE` · P1 · (needs F101)
**Expected:** react-i18next loading bundles from `/api/i18n/<lang>`, Vietnamese default.
**Acceptance:** toggling locale swaps all visible strings; VI is the boot default.
**Done:** react-i18next, VI default + fallback, EN toggle. **Deviation:** bundles are local `vi/en.json` for now — the `/api/i18n/<lang>` endpoint is B-EPIC-7; structured so `i18next-http-backend` drops in later untouched. Verified 2026-06-08.

## F-EPIC-2 — Auth UI

### F201 — Login screen · `DONE` · P0 · (needs F101, T001)
**Expected:** username/password form with error states, bilingual labels.
**Acceptance:** valid login navigates in; invalid shows a clear error; VI/EN labels correct.
**Done:** Functional layer (TDD): `src/auth/` types, `authClient` (typed wrappers over Tauri `login`/`logout`/`refresh`/`auth_status`), `validateLoginForm` (i18n error keys), headless `useLoginForm` hook, `LoginPage` container (translates keys→strings, slots `LanguageToggle`). Visual `LoginScreen` from claude.ai/design (shadcn card/input/label/button, fixed oklch backdrop). Navigation-in now wired via F204 (`LoginRoute` adopts session + redirects to dashboard). VI/EN labels + invalid-credential error + navigate-in all test-covered. Verified 2026-06-08.
**Note:** secure token storage split is **F202**; refined error taxonomy (bad-creds vs network) lands with **F203**.

### F202 — Token storage · `TODO` · P0 · (needs F201, D-EPIC-5)
**Expected:** access token in memory; refresh token via Tauri secure store.
**Acceptance:** refresh token never lives in localStorage/plaintext; access token cleared on logout.

### F203 — API client + 401→refresh interceptor · `DONE` · P0 · (needs F202, T002)
**Expected:** client attaches bearer header; on 401 it refreshes once and retries.
**Acceptance:** an expired access token transparently refreshes; a failed refresh logs the user out.
**Done (TDD):** `src/api/client.ts` — `createApiClient` (fetch-based, injectable deps) attaches `Bearer` from auth context, on 401 does a **single-flight** refresh (concurrent 401s share one call) → retries once with the new token → on refresh failure or repeat 401 calls `onAuthFailure` (clears session → guard → `/login`); unwraps `CommonResponse` and throws typed `ApiError` on non-2xx / `success:false`. `backendConfig.ts` resolves base URL from the shell `backend_config` (fallback default). `ApiProvider` + `useApi` wire it to `AuthContext` (refresh via the `refresh` Tauri command; `clearSession` added). 42 FE tests green (8 client incl. single-flight). Verified 2026-06-08.
**Note:** built on the current in-memory token model; **F202** (refresh token in OS keychain, Rust side) is still separate. Error taxonomy is status-based; bad-creds-vs-network refinement can come later.

### F204 — Auth context + route guards · `DONE` · P0 · (needs F203)
**Expected:** auth context; guarded routes; logout wired to `/auth/logout`.
**Acceptance:** unauthenticated users are redirected to login; logout clears session + calls the endpoint.
**Done:** react-router v7 (component routes). `AuthContext` (hydrates from shell `auth_status` on mount, holds access token in memory, `adoptStatus`/`logout`), `RequireAuth` guard (redirects unauth→`/login`, holds render while hydrating), `LoginRoute` (adopts session + navigates), `routes.tsx`, placeholder `Dashboard` (greeting + logout). App = `AuthProvider` + `BrowserRouter` + routes. Logout calls the shell `logout` command (local logout + revoke). 29 FE tests green incl. integration (unauth→login redirect; login→dashboard). Verified 2026-06-08.
**Note:** built ahead of F203 (its dependency) — F203's 401→refresh interceptor layers onto this context next; the `refresh` Tauri command already exists.

### F205 — VI formatting helpers · `DONE` · P1 · (needs F101)
**Expected:** helpers for Vietnamese diacritics, VND (`1.500.000 ₫`), DD/MM/YYYY dates, with tests.
**Acceptance:** currency/date render in VN format; diacritics never break; tests cover edge cases.
**Done (TDD):** `src/lib/format/` — `currency.ts` (`formatVnd` → `1.500.000 ₫`, rounds to whole đồng, handles 0/negative/non-finite), `date.ts` (`formatDateVn` DD/MM/YYYY + `formatDateTimeVn` DD/MM/YYYY HH:mm, local time, invalid→""), `text.ts` (`removeVietnameseTones` via NFD + đ→d, `normalizeForSearch` for diacritic/case-insensitive search). 16 tests covering edge cases. Verified 2026-06-08.

## F-EPIC-3 — Data layer (online/offline aware)

### F301 — `useEntity` / `useList` hooks · `TODO` · P0 · (needs F203, D-EPIC-2)
**Expected:** server-first reads; on network error fall through to Tauri cache.
**Acceptance:** reads succeed online via server and offline via cache.

### F302 — Write-through cache · `TODO` · P1 · (needs F301)
**Expected:** after a successful server read, write the result into the cache.
**Acceptance:** a subsequent offline read returns the cached value.

### F303 — Optimistic write + pending badge · `TODO` · P1 · (needs F301, D-EPIC-3)
**Expected:** offline writes update cache optimistically and show a pending-sync badge.
**Acceptance:** an offline edit appears immediately and is flagged pending until synced.

## F-EPIC-4 — Generic form renderer

### F401 — Render form from JSON metadata · `TODO` · P0 · (needs B-EPIC-5, F101)
**Expected:** fetch `/api/forms/<plugin>/<form>` and render groups/fields generically.
**Acceptance:** a plugin-shipped form renders without form-specific code.

### F402 — Core field types · `TODO` · P0 · (needs F401)
**Expected:** text, multiline, int, decimal, money(VND), date, datetime, boolean, enum, ref.
**Acceptance:** each type renders, validates, and submits the correct value shape.

### F403 — Unknown-type graceful fallback · `TODO` · P1 · (needs F402)
**Expected:** unknown field types fall back to `text` instead of erroring.
**Acceptance:** a form with an unsupported type still renders and submits.

### F404 — Server-side validation surfaced · `TODO` · P1 · (needs F401, B-EPIC-6)
**Expected:** map server validation/expression errors to field-level messages.
**Acceptance:** a server rejection shows inline under the offending field.

## F-EPIC-5 — Sync status & conflict UI

### F501 — Sync status pill · `TODO` · P1 · (needs D-EPIC-3)
**Expected:** always-visible pill: `Synced ✓ / Syncing… / Offline — N pending / Conflict — N`.
**Acceptance:** the pill reflects live sync state from the Tauri engine.

### F502 — Conflict resolution dialog · `TODO` · P1 · (needs F501)
**Expected:** dialog offering Keep mine / Use theirs / View diff on a 409.
**Acceptance:** user resolves a conflict explicitly; no silent last-writer-wins.

## F-EPIC-6 — Admin UI

### F601 — User CRUD · `PARTIAL` · P1 · (needs T008)
**Expected:** admin-only create/edit/disable users.
**Acceptance:** admin manages users; non-admins can't see the screen.
**Done (read list):** `useUsers` hook fetches `GET /api/v1/users`, returns `{ users, loading, error }` with graceful error handling. `UsersPage` renders a table (username/email/status columns) with loading spinner, error alert, and empty state. Route protected by `PermissionRoute permission="core.users.read"` and nav item gated by `hasPermission`. 8 new tests (86 total). Verified 2026-06-09.
**Remaining:** `GET /api/v1/users` backend list endpoint (T008b, list not yet exposed); create/edit/disable actions (F601b, depends on full user CRUD API).

### F602 — Role composer · `TODO` · P1 · (needs B-EPIC-2)
**Expected:** compose roles by checking permissions from the built-in + plugin union.
**Acceptance:** saving a role updates its permission set server-side.

### F603 — Audit log viewer · `TODO` · P2 · (needs B-EPIC-3)
**Expected:** read-only audit list filterable by entity and date.
**Acceptance:** entries display who/when/what; filters work.

### F604 — Plugin management · `TODO` · P2 · (needs B-EPIC-5)
**Expected:** list plugins; enable/disable; retry load; show `load_failed` reason.
**Acceptance:** a failed plugin shows its error and offers retry/disable.

## F-EPIC-7 — App shell & routing

### F701 — Router + layout · `DONE` · P0 · (needs F101)
**Expected:** router with a layout (nav, header containing sync pill + user menu).
**Acceptance:** routes navigate within a persistent shell.
**Done:** `AppShell` (`components/layout/`) — header with brand, nav links (`NavLink`, active-state styling), `SyncStatusPill` (static "Synced" placeholder — F501 wires it to live engine state), `LanguageToggle`, and sign-out, wrapping an `<Outlet/>`. Wired into `routes.tsx` as a layout route (`RequireAuth` → `AppShell` → nested `Dashboard`); Dashboard's standalone logout button removed (now lives in the shell header). TDD: `AppShell.test.tsx` covers brand/nav rendering, routed page content via Outlet, sync pill + language toggle, and logout dispatch through the auth context. Verified 2026-06-09 (commit 81123a1).

### F702 — Error boundaries + toasts · `DONE` · P1 · (needs F701)
**Expected:** per-route error boundaries; toast notifications for transient errors.
**Acceptance:** a thrown render error is contained to its route; transient errors toast.
**Done:** `RouteErrorBoundary` (class component + localized `RouteErrorFallback`) wraps every routed page in `routes.tsx` — a render error shows a "Something went wrong / Try again" fallback for that page only, leaving `AppShell` (and `/login`) intact; retry resets and re-renders. `sonner`'s `<Toaster/>` mounted app-wide in `App`; `AuthContext.logout` now toasts `auth.logoutFailedLocal` when the server-side revoke fails (session still clears locally, but the user is told why). TDD: `RouteErrorBoundary.test.tsx` covers pass-through, fallback, and recovery-on-retry. Verified 2026-06-09 (commit 79673e3).

### F703 — Permission-aware nav · `DONE` · P1 · (needs F204, B-EPIC-2)
**Expected:** hide nav items the user lacks permission for.
**Acceptance:** nav reflects the user's permissions from `/auth/me`.
**Done:** `CurrentUser` type (mirrors `/auth/me` DTO); `hasPermission(user, perm)` pure utility; `useCurrentUser` hook fetches `/api/v1/auth/me`, returns null gracefully when unauthenticated or on network error. `AppShell` nav items carry an optional `permission` field and are filtered at render time — admin Users (`core.users.read`) and Roles (`core.roles.read`) items are hidden from users without those permissions; Dashboard shows always. 10 new tests (4 permission util + 3 hook + 3 AppShell). i18n keys added for admin nav items (VI + EN). Verified 2026-06-09 (commit 2cc6c75).
**Note:** uses fail-closed model (items hidden while loading/null). Wires to the real `/auth/me` endpoint once T008a ships.

---

# DESKTOP (Tauri Rust — `desktop/src-tauri/`)

> **State (2026-10-23):** unchanged since 2026-10-17 — shell, SQLite cache (D201–D203), sync engine (D301–D306) and cache IPC (D401) and OS-keychain refresh token (D501) are done; `cargo test` 67/67 green. Remaining: auth/config close-out (D102, D103), sync events to the webview + pull scheduling (D402), first-run config (D502), packaging (D601, D602).

## D-EPIC-1 — Shell scaffold

### D101 — Tauri 2.x project + window · `DONE` · P0
**Expected:** Tauri app with config and a window.
**Acceptance:** app builds and launches.

### D102 — Auth client to server · `PARTIAL` · P0
**Expected:** `auth_client.rs` calls `/auth/*`; align with the final token contract from T004.
**Acceptance:** login/refresh/logout round-trip to the server with correct DTOs.

### D103 — Config + state management · `PARTIAL` · P0
**Expected:** `config.rs` (server URL etc.) + `state.rs` app state reviewed and stable.
**Acceptance:** config persists; shared state is accessed safely.

### D104 — Rust test harness · `DONE` · P0
**Expected:** `#[test]` harness with one passing test.
**Acceptance:** `cargo test` runs green.
**Done:** harness landed incidentally with the auth-slice work — `#[cfg(test)]` modules in `auth.rs`, `commands.rs`, `config.rs`, `state.rs`; `cargo test` 21/21 green. Verified 2026-06-11.

## D-EPIC-2 — Local SQLite cache

### D201 — SQLite setup + schema · `DONE` · P0 · (needs D101)
**Expected:** `rusqlite` wired; cache tables + `pending_changes` schema created on first run.
**Acceptance:** DB file initializes; schema present.
**Done (TDD):** `rusqlite` 0.32 (bundled) added; `cache::CacheDb::open` creates the DB file, enables WAL + foreign keys, and idempotently creates `cache_entities` (PK entity_type+entity_id, version, payload_json, cached_at) and `pending_changes` (op, entity_type, entity_id, payload_json, base_version, queued_at + drain-order index) on first run. 3 tests (first-run create, idempotent reopen, queue columns); cargo 24/24 green. Verified 2026-06-11.

### D202 — Cache read/write-through queries · `DONE` · P0 · (needs D201)
**Expected:** functions to read cached entities and write-through on server reads.
**Acceptance:** a cached entity round-trips through SQLite.
**Done (TDD):** `cache::entities` — `put_entity` (upsert, freshest server read wins), `get_entity` (`Option`), `list_entities` (per type, ordered). 4 tests incl. round-trip and stale-copy replacement; cargo 28/28 green. Verified 2026-06-11.

### D203 — Pending-changes queue · `DONE` · P0 · (needs D201)
**Expected:** `pending_changes(op, entity_type, entity_id, payload_json, base_version, queued_at)`.
**Acceptance:** an offline write persists in the queue and survives restart.
**Done (TDD):** `cache::queue` — `enqueue_change` (returns queue id), `pending_changes` (drain order: queued_at then id; carries `base_version` for D302's `If-Match`), `remove_change` (post-sync dequeue). 3 tests incl. restart survival against a real DB file; cargo 31/31 green. Verified 2026-06-11.

## D-EPIC-3 — Sync engine

### D301 — Sync state machine · `DONE` · P0 · (needs D203)
**Expected:** states for online/offline/syncing/conflict.
**Acceptance:** transitions are deterministic and unit-tested.
**Done (TDD):** `sync::machine` — `SyncState` (Offline/Online/Syncing/Conflict) × `SyncEvent` (connectivity up/down, drain started/completed, conflict detected/resolved, transient failure) with a total, deterministic `next()`: connectivity loss wins from every state; conflicts pause sync until resolved; transient failures return Online (backoff schedule is D303); unexpected events are no-ops. 6 tests incl. full-matrix determinism sweep; cargo 37/37 green. Verified 2026-06-11.

### D302 — Drain queue with optimistic lock · `DONE` · P0 · (needs D301, B-EPIC-4)
**Expected:** PUT/POST each pending change with `If-Match: <base_version>`.
**Acceptance:** queued changes flush in order when online.
**Done (TDD):** `sync::drain` — pushes queued changes oldest-first via the `ChangePusher` trait; each accepted change is dequeued before the next is sent. `HttpChangePusher` PUTs `{base}/api/v1/entities/{type}/{id}` with `If-Match: <base_version>` + bearer (mockito-verified headers/body/URL). Note: the generic entity-write endpoint is a forward contract — server side lands with the first plugin entity API. cargo 47/47 green. Verified 2026-06-11.

### D303 — Response handling · `DONE` · P0 · (needs D302)
**Expected:** 200 → mark synced; 409 → mark conflict; 5xx/timeout → exponential backoff (1→60s).
**Acceptance:** each response class drives the correct state, proven in tests.
**Done (TDD):** `DrainOutcome` — 2xx dequeues + continues; 409 stops with `Conflict` (change stays queued for the resolution dialog); 5xx/transport stops with `Retry`; other 4xx stops with `Fatal` (never retried). `sync::Backoff` doubles 1→2→4→…→60s cap, `reset()` on success. Tests cover every class incl. mid-queue failure leaving the remainder queued. Verified 2026-06-11.

### D304 — Typed error categories · `DONE` · P1 · (needs D303)
**Expected:** `Network / Conflict / ServerError / AuthExpired / PluginUnknown / Malformed`.
**Acceptance:** errors route to the right UX path; retry only on transient categories.
**Done (TDD):** `sync::SyncErrorCategory` with `from_status` mapping (401 AuthExpired, 409 Conflict, 404/410 PluginUnknown, other 4xx Malformed, 5xx ServerError; transport errors are Network at the call site) and `is_transient()` true only for Network/ServerError. Verified 2026-06-11.

### D305 — Pull deltas · `DONE (fetch layer)` · P1 · (needs D302, T012)
**Expected:** pull `/sync/changes` on reconnect, on WS notify, and on a 30s safety poll.
**Acceptance:** client converges to server state after reconnect.
**Done (TDD):** `sync::pull::pull_changes` — GET `/api/v1/sync/changes?since=<RFC3339>` with bearer, parses the `CommonResponse<ChangeEventResponse[]>` feed into `PulledChange` (caller refetches + writes through / evicts per op); errors map to `SyncErrorCategory` (401→AuthExpired, transport→Network). mockito tests: ordered pull, 401, unreachable server. The reconnect/WS-notify/30s-poll **scheduling** lands with the app-loop wiring (D402). cargo 50/50 green. Verified 2026-06-11.

### D306 — State-machine + backoff tests · `DONE` · P0 · (needs D301)
**Expected:** `tokio-test` + `mockito` tests for queue ordering, conflict, retry/backoff.
**Acceptance:** the state machine is proven under simulated network conditions.
**Done:** landed with D301–D304's TDD suites — `#[tokio::test]` drain tests with a scripted pusher (queue ordering, conflict stop, mid-queue 503, transport failure) + mockito HTTP-level test; full-matrix determinism sweep on the state machine; backoff schedule tests. cargo 47/47 green. Verified 2026-06-11.

## D-EPIC-4 — IPC bridge

### D401 — Cache IPC commands · `DONE` · P0 · (needs D202)
**Expected:** Tauri commands exposing cache reads/writes to React (`commands.rs`).
**Acceptance:** React reads/writes entities through IPC.
**Done (TDD):** `ipc::CacheState` (Mutex-wrapped `CacheDb`, opened in the app-data dir at setup) + commands `cache_get_entity` / `cache_list_entities` / `cache_put_entity` / `cache_enqueue_change` / `cache_pending_changes`, registered in `lib.rs`. Pure logic functions unit-tested (round-trip, missing→None, offline queue); thin `#[tauri::command]` wrappers per the codebase pattern. cargo 53/53 green. Verified 2026-06-11.

### D402 — Sync events to frontend · `TODO` · P1 · (needs D301)
**Expected:** push sync status + conflict events to the webview.
**Acceptance:** the frontend sync pill updates from emitted events.

## D-EPIC-5 — Secure credential storage

### D501 — Refresh token in OS keychain · `DONE` · P0 · (needs D101)
**Expected:** store the refresh token in the OS keychain, never plaintext.
**Acceptance:** no plaintext token on disk; token survives restart and is retrievable.
**Done (TDD):** `credentials` module — `CredentialStore` trait with `KeyringStore` (`keyring` 3: macOS Keychain / Windows Credential Manager / Secret Service, service `com.crm.desktop`, account `refresh-token`) and `InMemoryStore` (fallback + tests). `ShellState` persists `{refreshToken, refreshTokenExpiresAt}` on every `set_auth_session` (login + rotation) and clears it on `logout_locally` / `clear_session`; keychain I/O runs outside the state lock and is best-effort (a locked keychain never blocks login). New `restore_session` command exchanges the stored token via `/auth/refresh` on startup — rejected → keychain cleared, transient failure → kept for retry; a malformed entry is discarded. Race-safe: a session epoch (bumped on login/logout/clear) is captured before every awaited refresh/restore; a result that arrives after a login or logout is discarded and its rotated refresh token revoked, and a stale 401 cannot wipe a newer session. Keychain writes happen under the state lock so they follow the in-memory order. 19 new tests; cargo 72/72 green + the `#[ignore]` real-keychain round-trip passes on macOS. Verified 2026-10-17.
**Follow-ups:** the pending-logout-revoke marker is still memory-only, and a failed keychain delete on logout is only logged — so an *offline* logout whose keychain delete also fails, followed by quitting, could let the next `restore_session` reuse that un-revoked token (online logouts are safe: the server revokes it, so restore gets 401 and clears the keychain). Fix by persisting the pending-revoke marker / a logged-out tombstone; `POST /auth/revoke/{refreshToken}` puts the token in the URL path, so it can land in server/proxy access logs — move it to the request body (server + `config.rs`; client-side error messages already strip URLs); a failed revoke of a discarded (raced) rotated token is only logged, not retried — it never touches disk and expires naturally; `AuthStatus` still sends the full session (incl. refresh token) to the webview — F202 should narrow that and call `restore_session` at startup.

### D502 — First-run config · `TODO` · P1 · (needs D102)
**Expected:** prompt for server URL; validate via `/auth/login`.
**Acceptance:** a fresh install connects to a server after first-run setup.

## D-EPIC-6 — Packaging

### D601 — Build installers · `TODO` · P2 · (needs all desktop epics)
**Expected:** `.msi` (Windows) + `.dmg` (macOS) artifacts.
**Acceptance:** each artifact installs and launches on its OS.

### D602 — First-run trust docs · `TODO` · P2 · (needs D601)
**Expected:** documented unsigned-app workaround (signing deferred to v2).
**Acceptance:** a new user can install and open following the docs.

---

## Recommended next 5 tickets (in order)
_Updated 2026-10-23. The backend epics (B-EPIC-1 to B-EPIC-7) and the hotel booking slice (H-EPIC-1) are done on the server, so the critical path runs through desktop auth and the frontend data layer; the hotel screens (F301/F401) follow; the check-in/check-out slice (H-EPIC-2) was built 2026-10-25 on the server._
1. ~~**D501** — refresh token in the OS keychain (unblocks F202).~~ `DONE`
2. **F202** — frontend token storage on top of D501: call `restore_session` at startup, stop exposing the refresh token to the webview. ← next (in progress on another branch)
3. **D102 + D103** — finish the auth client and config/state so login/refresh/logout round-trips cleanly.
4. **F301** — `useEntity` / `useList` hooks over the D401 cache IPC.
5. **F401** — render forms from plugin JSON metadata.

ITs for **T015b** / **T017** verified on the homelab docker-host 2026-10-17 (190/190). Note: the live `crm-server` DB has **0 users** — there is no bootstrap admin, so nobody can log in yet; needs a first-admin seed/CLI ticket.

## Notes
- Hotel plugin work was gated on Backend EPIC-1 + EPIC-2 being green; both are, and the booking slice (H-EPIC-1) is built. The check-in/check-out slice (H-EPIC-2) is built too. Next hotel work: a receptionist role (needs role management, F602/B-EPIC-2 follow-up; the six `hotel.stays.*`/`hotel.folio.*` permissions are admin-only until then), the police stay-registration export, and the hotel screens.
- Plugin admins must log in again after a plugin is first installed or upgraded with new permissions (permissions reach the JWT at login; hotel 0.2.0 adds six). If two ZIPs share a plugin id only the first in file-name order loads, so remove old versions when upgrading.
- Leftovers from the slice: `docker-compose.yml`'s `server-test` service still runs `mvn -B test`, which cannot resolve the hotel ZIP in the reactor — it needs `verify`.
- The next backend value is finishing platform *contracts*, not more CRUD.
- Frontend and desktop can proceed in parallel once the auth contract (EPIC-1) is stable.
