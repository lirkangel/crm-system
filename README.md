# CRM System

A flexible, plugin-driven on-premises CRM platform — think 1C/Misa-style architecture built on modern Java.

## Architecture

```
crm-system/
├── server/                 # Maven reactor — the "Server Pack" installed on company server
│   ├── plugin-api/         #   types plugins compile against (PluginActivator, recorders, PluginProblem)
│   ├── plugins/hotel/      #   the hotel vertical; builds hotel-<version>-plugin.zip
│   ├── foundation/         #   the Spring Boot 3.4.1 app (the server itself)
│   └── scripts/            #   remote-verify.sh (build + test on the homelab docker-host)
└── desktop/                # Tauri 2 + React 18 — thin desktop client installed on workstations
```

**Server Pack** runs on a dedicated on-premises machine and is shared across all workstations. **Desktop Client** connects to the server, caches data locally in SQLite, and syncs via WebSocket with HTTP polling fallback.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.4.1, Java 21, PostgreSQL 15+ |
| ORM / Migrations | Hibernate/JPA, Flyway |
| Security | Spring Security, JWT (JJWT 0.12.6), BCrypt |
| Desktop Shell | Tauri 2 (Rust), React 18, TypeScript, Tailwind CSS |
| Export | Apache POI 5.3.0 (Excel), DocxStamper (Word) |
| Tests | JUnit 5, Testcontainers (real Postgres), REST Assured |

## Plugin Model

Each plugin is a ZIP package that:
- Loads via a dedicated `URLClassLoader` (parent-last isolation) and its own Spring child context
- Owns its own Postgres schema (e.g., `hotel`, `dental`); its JPA entities may map only that schema
- Gives its JPA entities plugin-specific names (`@Entity(name = "HotelRoom")`): entity names are global to the shared persistence unit, so a name already taken by Foundation or an earlier plugin makes the plugin `LOAD_FAILED`
- Runs its own Flyway migrations on install
- Declares permissions (and, optionally, i18n bundles and custom JSON form metadata)

How it behaves at runtime:
- **URL prefix:** a plugin's controllers are served under `/api/v1/plugins/{route}/…`. `route` is an optional `plugin.yaml` field; it defaults to the last segment of the plugin id (`com.crm.hotel` → `hotel`). Routes of a plugin that is disabled or failed to load return 404.
- **Deny by default:** every plugin handler method must carry `@PreAuthorize` (on the method or its class). A plugin with an unguarded handler method gets none of its routes and is `LOAD_FAILED`.
- **Install:** restart-required, no hot reload. Plugin ZIPs are read from the directory in `foundation.plugins.directory` (env `PLUGINS_DIR`, default `plugins`). The hotel ZIP ships inside the server image at `/app/plugins`, so a normal deploy installs it with no manual step.
- **Permissions:** on load the plugin's `permissions-declared` keys are written to `permissions` and granted to the `admin` role when first inserted (a later removal of a grant survives restarts). Permissions reach a user through the JWT at login, so **an admin must log in again after a plugin is first installed**.
- **Failure isolation:** a ZIP whose package or manifest is invalid (zip-slip entries, missing or malformed `plugin.yaml`) is rejected with a warning in the server log and is not registered, so there is no `plugin_registry` row for it. Once the manifest parses, a failure in the migration, entity mapping (invalid, out-of-schema or clashing entities), classloader or entry point, child context, routes or permission registration marks the plugin `LOAD_FAILED` with the reason in `plugin_registry.error_message`. Either way the server still starts with the other plugins.
- **Upgrades:** if two ZIPs declare the same plugin id, only the first in file-name order is loaded. Remove old versions when upgrading (`hotel-0.9.0.zip` sorts before `hotel-1.0.0.zip`, so the old one would win).

## MVP — Hotel PMS

**Tier 1 (Front-desk):** Guest profiles · Room management · Reservations · Check-in / Check-out · Folio with VAT + service charge · Police reporting (Khai báo lưu trú) · Excel/Word export

**Tier 2 (Mobile):** Pre-arrival QR check-in/out via emailed link · Self-service guest flow

## Security

- Single-tenant per Server Pack installation
- Local authentication + short-lived JWT (15 min access / 7 day refresh with rotation)
- RBAC with fine-grained permission keys (e.g., `hotel.checkin.perform`)
- Hash-chained audit log (SHA-256, tamper-detectable)
- App-level AES-GCM field encryption for sensitive data (passport numbers, ID numbers)

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL 15+ (running locally or via Docker)
- Docker (for integration tests via Testcontainers)
- Node 20+ + pnpm (for the desktop client)
- Rust 1.75+ + Tauri CLI (for the Tauri shell)

### Run the server

`server/` is a Maven reactor (`plugin-api`, `plugins/hotel`, `foundation`); the application is the `foundation` module. Build everything once, put the hotel ZIP in the plugin directory, then run from `server/foundation`:

```bash
cd server
mvn -B -DskipTests install                      # plugin-api, the hotel ZIP, foundation
cp plugins/hotel/target/hotel-*-plugin.zip foundation/plugins/
cd foundation
cp src/main/resources/application.yml src/main/resources/application-local.yml
# edit application-local.yml with your DB credentials
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The plugin directory is relative to the working directory (`foundation/plugins` here) unless `PLUGINS_DIR` is set. The Docker image build (`server/Dockerfile`) does all of this itself and bakes the hotel ZIP into `/app/plugins`.

> The old `cd server` + `mvn spring-boot:run` no longer works: the parent `pom.xml` is not an application.

### Run the desktop client (dev mode)

```bash
cd desktop
pnpm install
pnpm dev          # React UI on http://localhost:1420
# or:
pnpm tauri dev    # full Tauri window (requires Rust toolchain)
```

### Run desktop tests

```bash
cd desktop
pnpm test         # Vitest + React Testing Library (86 tests)
pnpm lint         # ESLint
```

### Run server tests

Lombok does not compile on every JDK (JDK 27 fails), so the project's tests run on the homelab docker-host (JDK 21 + Docker for Testcontainers) through `server/scripts/remote-verify.sh`. It ships `server/` there, runs Maven in `maven:3.9.9-eclipse-temurin-21-alpine` and exits with Maven's exit code. Run one at a time (it uses a fixed remote directory). Set `DOCKER_TEST_HOST` to change the target (default `root@10.0.0.11`); the script uses ssh-agent unless `HOMELAB_SSH_PASSPHRASE` is set.

```bash
# full gate: unit + integration tests of every module
server/scripts/remote-verify.sh -Pintegration clean verify

# focused run (build the ZIP first, so go through `verify`)
server/scripts/remote-verify.sh -Pintegration clean verify \
  -Dtest='ReservationFlowIT,StayPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false

# hotel-module unit tests only
server/scripts/remote-verify.sh -pl plugins/hotel -am test
```

`-pl foundation -am test` does not work: `foundation` test-depends on the hotel plugin ZIP, which exists only after the hotel module is packaged. Integration tests are tagged `integration` and run only with `-Pintegration`; on a machine with a suitable JDK and Docker, `mvn verify -Pintegration` from `server/` is equivalent.

## Vietnamese Locale

The platform is Vietnamese-first (`vi-VN`): DD/MM/YYYY dates, VND currency (dot-separators, ₫ suffix), and mandatory police reporting for hotel deployments.

## Status

> **Active development** — Foundation platform and plugin runtime complete. Hotel PMS Tier 1: the booking slice (rooms, guests, availability, reservations) is done on the server; check-in/out, folio and the desktop screens are next.

### What's working

| Area | Status |
|---|---|
| Backend auth | JWT (15 min access + 7 day rotating refresh), BCrypt, Spring Security |
| RBAC | Role/permission model, `@PreAuthorize` on endpoints, Flyway-seeded defaults |
| Audit log | Hash-chained event log (SHA-256, tamper-detectable) |
| Desktop shell | Tauri 2, login UI, auth context + route guards, token refresh interceptor |
| Admin UI | Permission-aware nav, Users list page (requires `core.users.read`) |
| i18n | Vietnamese default, English toggle (VI/EN string bundles) |
| Plugin runtime | Per-plugin classloader + Spring child context, routes under `/api/v1/plugins/{route}`, plugin permissions, per-plugin failure isolation |
| Hotel plugin (server) | Room types and rooms, guests with accent-insensitive search, availability search, create/view/list/cancel reservations; double booking impossible (Postgres exclusion constraint) |
| Tests | 86 frontend (Vitest + RTL); backend via Testcontainers (see "Run server tests") |

## License

Private — all rights reserved.
