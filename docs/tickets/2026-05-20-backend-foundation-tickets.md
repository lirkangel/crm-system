# Backend Foundation Tickets

Scope: current Spring Boot backend in `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server`

Purpose: convert the current scaffold into a real Foundation v0 backend, in the order that unblocks the rest of the platform.

---

## Recommended order

1. T001-T004: fix auth contract
2. T005-T008: enforce RBAC and account security
3. T009-T010: build real audit behavior
4. T011-T012: add sync/change feed foundation
5. T013-T015: start plugin-host foundation

---

## T001 - Return a real auth response from login

- Status: `TODO`
- Priority: `P0`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/AuthController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/TokenService.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/Impl/TokenServiceImpl.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/DTO/`
- Problem:
  - `POST /api/v1/auth/login` currently returns only a refresh-token `jti`, not a usable access JWT.
- Deliver:
  - Add a dedicated login response DTO with:
    - `accessToken`
    - `accessTokenExpiresAt`
    - `refreshToken`
    - `refreshTokenExpiresAt`
    - `tokenType`
  - Update login endpoint to return the real auth payload.
- Acceptance:
  - Client can call a protected endpoint immediately after login using returned bearer token.
  - No controller returns raw `jti` as the primary login result.

## T002 - Add refresh-token rotation endpoint

- Status: `TODO`
- Priority: `P0`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/AuthController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/TokenService.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/Impl/TokenServiceImpl.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/RefreshToken.java`
- Problem:
  - Refresh tokens exist in schema, but there is no refresh flow.
- Deliver:
  - Add `POST /api/v1/auth/refresh`.
  - Accept refresh token input.
  - Mark previous token `used_at`.
  - Create replacement token and set `replaced_by`.
  - Return a fresh access token and refresh token.
- Acceptance:
  - Refresh token can only be used once.
  - Second reuse of the same token is rejected.
  - Rotation chain is persisted in DB.

## T003 - Add logout and token revocation semantics

- Status: `TODO`
- Priority: `P0`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/AuthController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/TokenService.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Repository/RefreshTokenRepository.java`
- Problem:
  - Current revoke path sets expiry only. There is no clear logout contract.
- Deliver:
  - Add logout endpoint for current refresh token.
  - Differentiate:
    - expired
    - revoked
    - already used
    - not found
  - Stop using expiry overwrite as the only revocation mechanism.
- Acceptance:
  - Logout makes future refresh attempts fail.
  - Service behavior is explicit and test-covered.

## T004 - Separate JwtService from refresh-token persistence

- Status: `TODO`
- Priority: `P1`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Component/JwtTokenProvider.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/TokenService.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/Impl/TokenServiceImpl.java`
- Problem:
  - Access-JWT creation and refresh-token lifecycle are not clearly separated.
- Deliver:
  - Keep one component responsible for signed JWT access tokens.
  - Keep one service responsible for refresh-token persistence and rotation rules.
  - Make naming match behavior.
- Acceptance:
  - Service boundaries are clear from class names and tests.
  - Auth controller orchestration is simple and explicit.

## T005 - Map roles and permissions into Spring Security authorities

- Status: `TODO`
- Priority: `P0`
- Area: `security`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Middleware/JwtAuthenticationFilter.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Config/WebSecurityConfig.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/User.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/Role.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/Permission.java`
- Problem:
  - Authenticated users currently receive empty authorities.
- Deliver:
  - Build `GrantedAuthority` list from role permissions.
  - Put those authorities into the authentication object.
  - Add a current-user endpoint if needed for inspection.
- Acceptance:
  - A user with permission `core.users.read` can access guarded user endpoints.
  - A user without that permission is rejected with 403.

## T006 - Add method-level authorization

- Status: `TODO`
- Priority: `P0`
- Area: `security`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Config/WebSecurityConfig.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/UserController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/RoleController.java`
- Problem:
  - The system currently checks only `authenticated()`, not permission-specific access.
- Deliver:
  - Enable method security.
  - Add `@PreAuthorize` on backend endpoints.
  - Start with core foundation permissions:
    - `core.users.read`
    - `core.users.write`
    - `core.roles.read`
    - `core.roles.write`
    - `core.audit.read`
    - `core.plugins.manage`
- Acceptance:
  - Protected routes enforce permissions, not just valid JWT presence.

## T007 - Implement account lockout and enabled checks

- Status: `TODO`
- Priority: `P0`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/Impl/UserServiceImpl.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/User.java`
- Problem:
  - `failed_logins`, `locked_until`, and `enabled` exist but are not enforced.
- Deliver:
  - Increment failed login count on bad credentials.
  - Lock account after configured threshold.
  - Block disabled users from login.
  - Reset failure count on successful login.
- Acceptance:
  - Repeated bad login attempts lock the account.
  - Locked users cannot log in until lock expires or is reset.

## T008 - Replace public self-register with admin-managed user creation

- Status: `TODO`
- Priority: `P1`
- Area: `identity`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/AuthController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/UserController.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Service/UserService.java`
- Problem:
  - Public registration does not fit the planned on-prem admin-controlled deployment model.
- Deliver:
  - Remove or disable public register endpoint.
  - Add admin-only user creation flow instead.
  - Separate login DTOs from user-create DTOs.
- Acceptance:
  - Unauthenticated users cannot create accounts.
  - Admins can create users via protected API.

## T009 - Build a real AuditService with hash chaining

- Status: `TODO`
- Priority: `P0`
- Area: `audit`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/AuditEvent.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Repository/AuditEventRepository.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Audit/`
- Problem:
  - Audit schema exists, but no service writes chained audit rows.
- Deliver:
  - Add `AuditService`.
  - Compute `prev_hash` and `hash`.
  - Persist actor, source IP, entity type, entity id, op, before/after JSON, severity.
- Acceptance:
  - Create/update/delete operations can write audit records.
  - Hash chain is deterministic and verifiable in tests.

## T010 - Narrow AuditListener to timestamps/defaults only, move real audit writes to services

- Status: `TODO`
- Priority: `P1`
- Area: `audit`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Audit/AuditListener.java`
- Problem:
  - Listener currently acts only as a timestamp/default helper, but its name suggests full auditing.
- Deliver:
  - Keep listener limited to safe entity defaulting.
  - Do not try to generate `audit_event` rows in entity lifecycle callbacks.
  - Rename or document the class if needed so intent is clear.
- Acceptance:
  - Audit row persistence happens in explicit services or handlers, not hidden listener logic.

## T011 - Start change-event writing on mutating backend operations

- Status: `TODO`
- Priority: `P1`
- Area: `sync`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/ChangeEvent.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Repository/ChangeEventRepository.java`
  - new sync service classes under `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/`
- Problem:
  - `change_event` exists as a table/entity, but nothing writes to it.
- Deliver:
  - Add a service to emit change events after successful writes.
  - Capture plugin id, entity type, entity id, version, op, occurred_at.
- Acceptance:
  - User/role/permission changes create `change_event` rows.
  - Events are written only after successful persistence.

## T012 - Add delta feed endpoint for sync

- Status: `TODO`
- Priority: `P1`
- Area: `sync`
- Files:
  - new sync controller/service classes under `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/`
- Problem:
  - Desktop sync has no HTTP feed to consume.
- Deliver:
  - Add `GET /api/v1/sync/changes?since=...`.
  - Return ordered change events.
  - Define response DTOs explicitly.
- Acceptance:
  - Client can pull changes incrementally using timestamp or cursor.
  - Endpoint is secured and permissioned appropriately.

## T013 - Add plugin manifest model and registry service

- Status: `TODO`
- Priority: `P0`
- Area: `plugin`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Domain/PluginRegistry.java`
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Repository/PluginRegistryRepository.java`
  - new plugin classes under `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/`
- Problem:
  - Plugin registry exists in persistence only; there is no domain service for plugin state.
- Deliver:
  - Define plugin manifest DTO/model.
  - Add registry service for:
    - register
    - activate
    - disable
    - mark load failed
    - mark uninstall pending
- Acceptance:
  - Plugin state changes are explicit and test-covered.

## T014 - Build ZIP plugin discovery and validation

- Status: `TODO`
- Priority: `P0`
- Area: `plugin`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/plugins/`
  - new plugin loader classes under `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/`
- Problem:
  - Backend does not yet discover or validate plugin ZIP packages.
- Deliver:
  - Scan plugin directory on startup.
  - Validate ZIP contents and traversal safety.
  - Read `plugin.yaml`.
  - Validate required fields before load.
- Acceptance:
  - Invalid plugin packages are rejected safely.
  - Broken plugin does not crash whole application startup flow.

## T015 - Add classloader and schema-per-plugin load pipeline

- Status: `TODO`
- Priority: `P0`
- Area: `plugin`
- Files:
  - new plugin loader/migration classes under `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/`
- Problem:
  - The core differentiator of the platform, plugin loading with isolated schemas, is not implemented yet.
- Deliver:
  - Create URLClassLoader-per-plugin.
  - Resolve plugin entrypoint.
  - Run plugin Flyway migrations in the plugin schema.
  - Persist plugin state into `plugin_registry`.
- Acceptance:
  - A minimal demo plugin can be discovered, registered, and migrated into its own schema.

## T016 - Replace ad-hoc error handling with API-grade error responses

- Status: `TODO`
- Priority: `P1`
- Area: `api`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/main/java/com/crm/foundation/Controller/ErrorHandler.java`
- Problem:
  - Current error handler only maps `IllegalArgumentException` to bare 400 with no structured payload.
- Deliver:
  - Return structured error responses for:
    - validation
    - auth failure
    - forbidden
    - not found
    - conflict
    - plugin load failure
  - Prefer `ProblemDetail` or equivalent stable contract.
- Acceptance:
  - API clients receive consistent machine-readable error payloads.

## T017 - Expand backend integration coverage

- Status: `TODO`
- Priority: `P1`
- Area: `testing`
- Files:
  - `/Users/lirkangel/ProjectsStuff/OwnProjects/crm-system/server/src/test/java/com/crm/foundation/`
- Problem:
  - Test suite passes, but most coverage is unit/mock based. Critical runtime paths are still unproven.
- Deliver:
  - Add integration tests for:
    - login flow
    - refresh rotation
    - protected endpoint access
    - permission denial
    - audit write
    - change-event write
  - Keep Postgres-backed coverage via Testcontainers.
- Acceptance:
  - Core auth/security behavior is proven end-to-end, not only by mocks.

---

## Suggested milestone split

### Milestone A - usable auth base
- T001
- T002
- T003
- T004

### Milestone B - real security
- T005
- T006
- T007
- T008

### Milestone C - observability and sync base
- T009
- T010
- T011
- T012
- T016
- T017

### Milestone D - plugin foundation
- T013
- T014
- T015

---

## Notes

- Do not start Hotel plugin work before at least Milestone A and B are done.
- Do not let the desktop client drive backend design yet. The server contract is still the blocking path.
- The backend currently looks like a scaffold with partial JWT work. The next value is not more CRUD. The next value is finishing the platform contracts.
