# CRM System

A flexible, plugin-driven on-premises CRM platform — think 1C/Misa-style architecture built on modern Java.

## Architecture

```
crm-system/
├── server/          # Spring Boot 3.4.1 — the "Server Pack" installed on company server
├── desktop/         # Tauri 2 + React 18 — thin desktop client installed on workstations
└── plugins/         # ZIP-packaged verticals (hotel, dental, …)
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
- Loads via a dedicated `URLClassLoader` (parent-last isolation)
- Owns its own Postgres schema (e.g., `hotel`, `dental`)
- Runs its own Flyway migrations on install
- Declares permissions, i18n bundles, and custom JSON form metadata

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

```bash
cd server
cp src/main/resources/application.yml src/main/resources/application-local.yml
# edit application-local.yml with your DB credentials
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

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

### Run server integration tests

```bash
cd server
mvn verify -Pintegration
```

## Vietnamese Locale

The platform is Vietnamese-first (`vi-VN`): DD/MM/YYYY dates, VND currency (dot-separators, ₫ suffix), and mandatory police reporting for hotel deployments.

## Status

> **Active development** — Foundation platform complete. Hotel PMS Tier 1 in progress.

### What's working

| Area | Status |
|---|---|
| Backend auth | JWT (15 min access + 7 day rotating refresh), BCrypt, Spring Security |
| RBAC | Role/permission model, `@PreAuthorize` on endpoints, Flyway-seeded defaults |
| Audit log | Hash-chained event log (SHA-256, tamper-detectable) |
| Desktop shell | Tauri 2, login UI, auth context + route guards, token refresh interceptor |
| Admin UI | Permission-aware nav, Users list page (requires `core.users.read`) |
| i18n | Vietnamese default, English toggle (VI/EN string bundles) |
| Tests | 86 frontend (Vitest + RTL); backend via Testcontainers |

## License

Private — all rights reserved.
