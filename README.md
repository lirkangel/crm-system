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
- Owns its own Postgres schema (e.g., `hotel`, `dental`); its JPA entities may map only that schema. The manifest `schema` must match `^[a-z][a-z0-9_]{1,62}$` and may not be `public`, Foundation's default schema, `information_schema` or `pg_*`; a schema already used by another plugin (earlier in file-name order, or in `plugin_registry`) fails the later plugin before it is migrated
- Gives its JPA entities plugin-specific names (`@Entity(name = "HotelRoom")`): entity names are global to the shared persistence unit, so a name already taken by Foundation or an earlier plugin makes the plugin `LOAD_FAILED`
- Runs its own Flyway migrations on install
- Declares permissions (and, optionally, i18n bundles and custom JSON form metadata)

How it behaves at runtime:
- **URL prefix:** a plugin's controllers are served under `/api/v1/plugins/{route}/…`. `route` is an optional `plugin.yaml` field; it defaults to the last segment of the plugin id (`com.crm.hotel` → `hotel`). Routes of a plugin that is disabled or failed to load return 404.
- **Deny by default:** every plugin handler method must carry `@PreAuthorize` (on the method or its class). A plugin with an unguarded handler method gets none of its routes and is `LOAD_FAILED`.
- **Install:** restart-required, no hot reload. Plugin ZIPs are read from the directory in `foundation.plugins.directory` (env `PLUGINS_DIR`, default `plugins`). The hotel ZIP ships inside the server image at `/app/plugins`, so a normal deploy installs it with no manual step.
- **Permissions:** on load the plugin's `permissions-declared` keys are written to `permissions` and granted to the `admin` role when first inserted (a later removal of a grant survives restarts). Permissions reach a user through the JWT at login, so **an admin must log in again after a plugin is first installed, and after an upgrade that adds permissions** (hotel 0.2.0 adds six, below).
- **Failure isolation:** a ZIP whose package or manifest is invalid (zip-slip entries, missing or malformed `plugin.yaml`, an invalid or reserved `schema`) is rejected with a warning in the server log and is not registered, so there is no `plugin_registry` row for it. Once the manifest parses, a failure in the migration, entity mapping (invalid, out-of-schema or clashing entities), classloader or entry point, child context, routes or permission registration marks the plugin `LOAD_FAILED` with the reason in `plugin_registry.error_message`. The one exception is a plugin whose schema another plugin already uses: `plugin_registry.schema_name` is unique, so it gets no row and its failure is only in the server log. Either way the server still starts with the other plugins.
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

> **Active development** — Foundation platform and plugin runtime complete. Hotel PMS Tier 1 on the server: the booking slice (rooms, guests, availability, reservations) and the stay slice (check-in, folio, check-out, front-desk lists; plugin 0.2.0) and the CCCD scan (plugin 0.3.0) are done; the desktop screens are next.

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
| Hotel plugin 0.2.0 (server) | Booking source (walk-in, phone, OTA with channel and reference); check-in with one identity record per adult and same-type room swap; same-day undo and identity corrections; folio with extra charges, payments (cash, card, transfer, OTA prepayment) and voids, balance never below zero; check-out only at zero balance; arrivals, in-house and departures lists with late/overdue flags; a checked-in room stays blocked until check-out |
| Tests | 86 frontend (Vitest + RTL); backend via Testcontainers (see "Run server tests") |

**Hotel 0.2.0 permissions** (new keys, granted to `admin` on first load; there is no receptionist role yet): `hotel.stays.checkin` (check-in, undo check-in, identity corrections), `hotel.stays.checkout`, `hotel.stays.read` (the identity records, which are personal data), `hotel.folio.read`, `hotel.folio.charge` (add and void charges), `hotel.folio.pay` (add and void payments). The front-desk lists use `hotel.reservations.read`. **Admins must log in again after upgrading to receive them.** Upgrading means replacing `hotel-0.1.0-plugin.zip` with `hotel-0.2.0-plugin.zip` (the server image already does); the V2 migration runs at startup and leaves slice-1 reservations as they were.

### CCCD scan (hotel 0.3.0)

At check-in, staff upload a photo of the guest's CCCD. The server decodes the QR code, returns the identity fields to pre-fill (`idType` CCCD, `nationality` VN), stores the photo in Postgres (table `hotel.id_scan`, JPEG, long edge at most 1600 px) and links it to the guest's identity record.

- **Retention:** a photo is deleted 30 days after upload (hourly job, on the hour, Asia/Ho_Chi_Minh). A stay longer than 30 days loses the photo but keeps the confirmed identity fields. Undo check-in deletes the linked photos at once.
- **Backups:** if Postgres backups are added, they must keep 30 days or less, or deleted photos live on in them.
- **Purge availability:** the purge runs only while the hotel plugin is active; if it is disabled or fails to load, photos are not deleted until it runs again.
- **Privacy:** photos, QR text and decoded values are never written to the audit log, the logs or the change feed (audit holds only the size, and the purge count).
- **Endpoints and permissions:** `POST /api/v1/plugins/hotel/reservations/{id}/id-scans` (multipart `image`; `hotel.stays.checkin`; 201 new, 200 duplicate); `GET .../reservations/{id}/stay-guests/{stayGuestId}/id-scan` (`hotel.stays.read`); `StayGuestRequest.idScanId` (optional) links a scan on check-in or correction (`hotel.stays.checkin`). No new permission keys.
- **Clients** should check that a photo is 10 MB or less before uploading; larger uploads are refused with 413.
- **Plugin ZIPs** may now bundle non-Spring third-party libraries in `lib/` (the hotel ZIP carries `zxing-core` and `zxing-javase`).

## License

Private — all rights reserved.
