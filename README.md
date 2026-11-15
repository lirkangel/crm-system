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

> **Active development** — Foundation platform and plugin runtime complete. Hotel PMS Tier 1 on the server: the booking slice (rooms, guests, availability, reservations) and the stay slice (check-in, folio, check-out, front-desk lists; plugin 0.2.0) the CCCD scan (plugin 0.3.0) and guest online check-in (plugin 0.4.0, `guest-web/`) are done; the desktop screens are next.

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

### Guest online check-in (hotel 0.4.0)

Before arrival, a guest opens a personal link on a phone, photographs each adult's CCCD (or types a passport), confirms the details and consents. Staff then see the submitted details on the reservation and use them at check-in. The page is `guest-web/` (React, served by nginx-unprivileged on port 8080) at `https://checkin.mathisterben.org` through the Cloudflare Tunnel.

- **Create a link** (staff, `hotel.stays.checkin`; `GET` needs `hotel.reservations.read`): `POST /api/v1/plugins/hotel/reservations/{id}/guest-link` returns the URL once (the token is stored hashed and never shown again; posting again replaces it). `DELETE` revokes it, `GET` shows its status. Only CONFIRMED reservations get a link; it expires at the business-day cutoff (06:00 hotel time) on the day after check-out.

  ```bash
  CRM=http://10.0.0.7:30082
  read -s -p 'Admin password: ' PW; echo
  TOKEN=$(curl -s "$CRM/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$PW\"}" | jq -r '.data.accessToken')
  curl -s -X POST "$CRM/api/v1/plugins/hotel/reservations/<reservation-id>/guest-link" \
    -H "Authorization: Bearer $TOKEN" | jq
  ```

- **Staff view** (`hotel.stays.read`): `GET .../reservations/{id}/pre-arrival-guests` lists the submissions (with the fields the guest edited after the scan); `GET .../pre-arrival-guests/{guestId}/id-scan` returns the photo. The arrivals list shows `preArrivalAdults` per reservation.
- **Retention:** submissions are deleted at check-in, at cancel, and 30 days after submission. Photos follow the existing 30-day rule (see CCCD scan above); photos of submissions that were never checked in go with them. The guest link row itself is kept at check-in and cancel, so a guest who opens the link afterwards sees a "closed" page instead of "invalid"; the hourly purge removes links that expired more than 30 days ago.
- **Known limit:** photos a guest uploaded but never submitted still count toward the 20-scan cap of the reservation until the 30-day purge clears them. Staff hit the cap only after a guest has uploaded 20 photos.
- **Consent:** the guest-web consent text (`guest-web/src/components/ConsentNotice.tsx`, vi/en in `src/i18n`) is marked `TODO(legal)` and must be reviewed by Mathisterben before real guests use it.
- **Public surface:** only `/api/v1/plugins/hotel/public/**` is reachable from the internet, through nginx, which answers 404 for every other `/api` path. Public routes are opt-in per endpoint (`@PublicEndpoint`), rate limited per client (`CRM_PUBLIC_RATE_LIMIT_PER_MINUTE`, default 60), and answer `Cache-Control: no-store` and `X-Robots-Tag: noindex`. The token travels in the `X-Checkin-Token` header (and in the link's `#fragment`, which never reaches a server log), never in a URL path or query.
- **Configuration:**

  | Where | Setting | Meaning |
  |---|---|---|
  | server | `CRM_PUBLIC_PROXY_SECRET` | shared secret (`[A-Za-z0-9_-]{32,}`, make it with `openssl rand -hex 32`) that lets the server trust the client address nginx forwards; empty trusts nothing |
  | server | `CRM_PUBLIC_RATE_LIMIT_PER_MINUTE` | public requests per client per minute (default 60) |
  | hotel plugin | `HOTEL_GUEST_BASE_URL` (property `hotel.guest-base-url`) | link prefix (default `https://checkin.mathisterben.org`) |
  | hotel plugin | `HOTEL_DISPLAY_NAME` (property `hotel.display-name`) | hotel name shown to guests (default `Khách sạn`) |
  | guest-web | `CRM_SERVER_URL` | `scheme://host:port` of the server, no path |
  | guest-web | `CRM_PUBLIC_PROXY_SECRET` | same value as the server's |
  | guest-web | `REAL_IP_FROM` | the only address trusted to send `CF-Connecting-IP`; default `127.0.0.1/32` trusts no proxy, so in production set the exact /32 of the source address nginx sees for tunnel traffic: measure it from the guest-web access log for `/` (it is the address after the tunnel and kube-proxy, not necessarily the connector pod's), or set `externalTrafficPolicy: Local` on the guest-web Service. Never a broad range |

  The server logs one INFO line at start saying whether `CRM_PUBLIC_PROXY_SECRET` is configured (never its value) and, at most once a minute, a WARN when a request carries a non-matching `X-Guest-Proxy-Secret` (the two secrets differ). guest-web refuses to start on a malformed `CRM_SERVER_URL`, a weak secret or a bad `REAL_IP_FROM`. CI builds the image as `10.0.0.7:30500/crm-guest-web:latest`; `scripts/smoke-guest-checkin.sh` runs the whole stack on the docker-host.

### Dental pre-visit (dental 0.1.0)

A clinic books appointments for patients (name, phone and date of birth are required) and hands each patient a personal link. Before the visit the patient opens it on a phone, photographs a CCCD (or types a passport or other document), answers the allergy, medicine and reason questions, and consents. A child under 14 on the visit date is filled in by a parent (name and phone) instead. Staff read the form on the appointment and can apply the corrected details to the patient record. The page is the same `guest-web/` container as hotel check-in, served at `https://nhakhoa.mathisterben.org`.

**It ships switched off.** `dental.public.enabled` defaults to `false`: every dental public endpoint answers 404 and staff link creation answers `409 DENTAL_PUBLIC_DISABLED`. Patients, appointments and the staff screens' API work while it is off.

**Switch-on checklist** (do all of it before the first real patient):

1. A clinic exists (name for `DENTAL_DISPLAY_NAME`, link base for `DENTAL_GUEST_BASE_URL`, default `https://nhakhoa.mathisterben.org`).
2. Legal sign-off from Mathisterben on the consent text (`guest-web/src/dental`, vi/en, marked `TODO(legal)`) and on record retention (forms are kept; see below).
3. `DENTAL_FIELD_KEY` set on the server: `openssl rand -base64 32` (base64 of 32 bytes). The plugin refuses to start with the flag on and no key. Back the key up with the database: without it stored answers cannot be read, and rotating it needs a re-encryption step that does not exist yet.
4. A Dentist role carrying `dental.previsit.read` (and the appointment permissions) assigned to the staff who see health answers; admins get all five keys on first load and must log in again.
5. `DENTAL_PUBLIC_ENABLED=true` on the server (property `dental.public.enabled`).
6. The guest-web container needs `HOTEL_HOST` (default `checkin.mathisterben.org`) and `DENTAL_HOST` (default `nhakhoa.mathisterben.org`); any other Host gets 404 for everything.
7. Cloudflare Tunnel public hostname `nhakhoa.mathisterben.org` to `http://10.0.0.11:80`, and an NPM proxy host for it to `10.0.0.7:30084` (the guest-web NodePort).
8. Cloudflare: a cache bypass rule for `/api/*` on that hostname, and Rocket Loader, Email Obfuscation and Web Analytics turned off (they rewrite or inject scripts and break the page's CSP).

Check afterwards that `https://nhakhoa.mathisterben.org/api/v1/plugins/hotel/public/checkin` is 404 and that a link made for a booked appointment opens.

**Staff endpoints** (all under `/api/v1/plugins/dental`):

| Endpoint | Permission |
|---|---|
| `GET /patients?phone=...` or `?q=...`, `POST /patients`, `PUT /patients/{id}` (`If-Match`) | `dental.patients.read` (search), `dental.patients.write` |
| `POST /appointments` (an existing patient or an inline `newPatient`), `PATCH /appointments/{id}` (reschedule, `If-Match`), `POST .../cancel`, `.../arrive`, `.../no-show` | `dental.appointments.write` |
| `GET /appointments?date=YYYY-MM-DD`, `GET /appointments/{id}` | `dental.appointments.read` |
| `POST|DELETE /appointments/{id}/visit-link` (the URL is shown once) | `dental.appointments.write` |
| `GET /appointments/{id}/visit-link` (status) | `dental.appointments.read` |
| `GET /appointments/{id}/previsit`, `GET .../previsit/id-scan`, apply the form to the patient | `dental.previsit.read` |

The `+` in a phone query must be sent as `%2B` (`?phone=%2B84912345678`): a raw `+` is decoded as a space. Only a `BOOKED` appointment can be rescheduled, cancelled, marked arrived, given a link or receive a form. A link expires at 23:59 clinic time (`Asia/Ho_Chi_Minh`) on the appointment date, the token travels in the `X-Visit-Token` header and in the `#d/<token>` fragment, and every bad token gets the same 404.

**Stored data and audit:**

- Encrypted columns (AES-GCM, `IV || ciphertext`, key `DENTAL_FIELD_KEY`): `dental.previsit_form.allergies_enc`, `medicines_enc` and `reason_enc`. Identity details and the CCCD photo are stored as in the hotel plugin (photo downscaled, purged 30 days after upload, hourly).
- Retention: submitted forms are kept; cancelling an appointment deletes its form and photo.
- Audit events: `PATIENT_CREATE`, `PATIENT_UPDATE`, `APPOINTMENT_BOOK`, `APPOINTMENT_RESCHEDULE`, `APPOINTMENT_CANCEL`, `APPOINTMENT_ARRIVE`, `APPOINTMENT_NO_SHOW`, `VISIT_LINK_CREATE`, `VISIT_LINK_REVOKE`, `PREVISIT_SUBMIT`, `PREVISIT_VIEW` (every read of a form), `PREVISIT_PHOTO_VIEW`, `ID_SCAN_UPLOAD`, `ID_SCAN_PURGE`. No identity or health value reaches the audit log, the logs, the change feed or an error message.
- Previous answers: a returning patient can reuse earlier answers after confirming their date of birth against the patient record; 5 wrong attempts per link lock that check.

**Host routing in guest-web:** one nginx `server` per host. `HOTEL_HOST` (and `checkin.*`) proxies only `/api/v1/plugins/hotel/public/`; `DENTAL_HOST` (and `nhakhoa.*`) proxies only `/api/v1/plugins/dental/public/`; any other Host is a 404 even for `/`. Both share the same hardening (normalised-path proxy, `;`/`%3b`/`%5c` guard, explicit `X-Guest-Proxy-Secret` and `X-Client-IP`, 11 MB body limit, security headers on every response). `scripts/smoke-guest-checkin.sh` covers both paths and the cross-host 404s.

## License

Private — all rights reserved.
