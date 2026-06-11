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
**Do not start Hotel plugin work until Backend EPIC-1 + EPIC-2 are green.** The server contract is the blocking path.

---

# BACKEND

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

### T012a — WebSocket change push · `TODO` · P2 · (needs T012)
**Expected:** push change notifications over WebSocket as a real-time complement to polling.
**Acceptance:** a connected client receives a notification on a write without polling.

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

### T015b — Minimal demo plugin loads end-to-end · `DONE (IT pending Docker run)` · P0 · (needs T015a)
**Expected:** a minimal demo plugin is discovered, registered, and migrated into its own schema.
**Acceptance:** after startup the demo plugin is `active` in `plugin_registry` with its schema present.
**Done:** `DemoPluginEndToEndIT` builds a complete demo package at test runtime (manifest + runtime-compiled entrypoint jar + `db/V1__init.sql`), drops it into a temp plugin dir via `@DynamicPropertySource`, boots the full Spring context, and asserts: registry row `ACTIVE` with `lastLoadedAt`, `demo_e2e_item` table present in `plugin_demo_e2e` schema, `onLoad()` observed. All pipeline stages individually unit-proven (T014–T015a); **run `mvn test -Pintegration` on a Docker-capable machine to confirm e2e** — this sandbox's Docker API is filtered so the IT skips. 2026-06-11.

---

## B-EPIC-6 — API Quality & Testing
*Goal: consistent error contract + proven runtime paths.*

### T016 — RFC 7807 ProblemDetail responses · `DONE` · P1
**Expected:** structured `application/problem+json` for validation / auth / forbidden / not-found / conflict / plugin-load, each with a stable `type` and a `trace_id`.
**Acceptance:** clients receive consistent machine-readable error payloads; validation errors include field details.
**Done (TDD):** `ErrorHandler` rewritten on Spring's `ProblemDetail`: stable `urn:problem-type:*` types for validation (400, per-field `errors` list), unauthorized (401, keeps `AuthException` code), forbidden (403, `AccessDeniedException`), not-found (404), bad-request (400), conflict (409, `OptimisticLockingFailureException`), plugin-load (422, `InvalidPluginPackageException`). Every problem carries a unique `trace_id` (logged for correlation) and a `code` extension so the existing FE client's `codeOf()` keeps working (FE reads status + optional `code`; `detail` replaces `message` — fallback to statusText is graceful). 9 tests in `ErrorHandlerTest`; 162/162 green. Verified 2026-06-11.

### T017 — Integration tests (Testcontainers) · `DONE (IT pending Docker run)` · P1 · (needs epics 2–4)
**Expected:** Postgres-backed tests for login, refresh rotation, protected access, 403 denial, audit write, change-event write.
**Acceptance:** core auth/security/audit/sync proven end-to-end, not only by mocks.
**Done:** `Auth.AuthFlowIT` (RANDOM_PORT + `TestRestTemplate`, real HTTP against Testcontainers Postgres): login returns usable bearer; `/auth/me` 200 with token, 401 without; permissionless user gets **403 problem+json** on `/users/{id}` while admin gets 200; refresh rotates and the old token is single-use (replay → 401); login writes an `AUTH_LOGIN_SUCCESS` audit row. Change-event write already covered by `ChangeEventSameTransactionIT`; audit chaining by `AuditServiceIT`/`ChainVerifierIT`. **Run `mvn test -Pintegration` on a Docker-capable machine** — sandbox Docker API filtered. 2026-06-11.

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

> **State:** shell scaffolded — `auth.rs`, `auth_client.rs`, `commands.rs`, `config.rs`, `error.rs`, `state.rs`, `lib.rs`, `main.rs` (~627 lines). No SQLite cache, no sync engine, no tests.

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

### D104 — Rust test harness · `TODO` · P0
**Expected:** `#[test]` harness with one passing test.
**Acceptance:** `cargo test` runs green.

## D-EPIC-2 — Local SQLite cache

### D201 — SQLite setup + schema · `TODO` · P0 · (needs D101)
**Expected:** `rusqlite` wired; cache tables + `pending_changes` schema created on first run.
**Acceptance:** DB file initializes; schema present.

### D202 — Cache read/write-through queries · `TODO` · P0 · (needs D201)
**Expected:** functions to read cached entities and write-through on server reads.
**Acceptance:** a cached entity round-trips through SQLite.

### D203 — Pending-changes queue · `TODO` · P0 · (needs D201)
**Expected:** `pending_changes(op, entity_type, entity_id, payload_json, base_version, queued_at)`.
**Acceptance:** an offline write persists in the queue and survives restart.

## D-EPIC-3 — Sync engine

### D301 — Sync state machine · `TODO` · P0 · (needs D203)
**Expected:** states for online/offline/syncing/conflict.
**Acceptance:** transitions are deterministic and unit-tested.

### D302 — Drain queue with optimistic lock · `TODO` · P0 · (needs D301, B-EPIC-4)
**Expected:** PUT/POST each pending change with `If-Match: <base_version>`.
**Acceptance:** queued changes flush in order when online.

### D303 — Response handling · `TODO` · P0 · (needs D302)
**Expected:** 200 → mark synced; 409 → mark conflict; 5xx/timeout → exponential backoff (1→60s).
**Acceptance:** each response class drives the correct state, proven in tests.

### D304 — Typed error categories · `TODO` · P1 · (needs D303)
**Expected:** `Network / Conflict / ServerError / AuthExpired / PluginUnknown / Malformed`.
**Acceptance:** errors route to the right UX path; retry only on transient categories.

### D305 — Pull deltas · `TODO` · P1 · (needs D302, T012)
**Expected:** pull `/sync/changes` on reconnect, on WS notify, and on a 30s safety poll.
**Acceptance:** client converges to server state after reconnect.

### D306 — State-machine + backoff tests · `TODO` · P0 · (needs D301)
**Expected:** `tokio-test` + `mockito` tests for queue ordering, conflict, retry/backoff.
**Acceptance:** the state machine is proven under simulated network conditions.

## D-EPIC-4 — IPC bridge

### D401 — Cache IPC commands · `PARTIAL` · P0 · (needs D202)
**Expected:** Tauri commands exposing cache reads/writes to React (`commands.rs`).
**Acceptance:** React reads/writes entities through IPC.

### D402 — Sync events to frontend · `TODO` · P1 · (needs D301)
**Expected:** push sync status + conflict events to the webview.
**Acceptance:** the frontend sync pill updates from emitted events.

## D-EPIC-5 — Secure credential storage

### D501 — Refresh token in OS keychain · `TODO` · P0 · (needs D101)
**Expected:** store the refresh token in the OS keychain, never plaintext.
**Acceptance:** no plaintext token on disk; token survives restart and is retrievable.

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
1. ~~**T004** — tidy JWT/refresh boundaries (unblocks RBAC).~~ `DONE`
2. ~~**T006a + T005** — permission constants, then map authorities.~~ `DONE`
3. **T006** — `@PreAuthorize` enforcement. ← next
4. **T007** — account lockout.
5. **F101–F103** — stand up the React app. `DONE`

## Notes
- Hotel plugin work stays out until Backend EPIC-1 + EPIC-2 are green.
- The next backend value is finishing platform *contracts*, not more CRUD.
- Frontend and desktop can proceed in parallel once the auth contract (EPIC-1) is stable.
