# crm-system CI/CD Design Spec

**Date:** 2026-05-31  
**Repo:** `github.com/lirkangel/crm-system` (primary) → mirrored to GitLab  
**Cluster:** Talos k8s `10.0.0.7`, local registry `10.0.0.7:30500`

---

## Goal

Two independent pipelines:
1. **Server** — GitLab CI (homelab runner) builds the Java Docker image and pushes to the local registry. Flux detects the new digest and rolls out to k8s automatically.
2. **Desktop** — GitHub Actions builds Tauri native binaries on version tags and publishes them as GitHub Releases.

---

## Architecture

### Repo mirroring

A GitHub Actions workflow (`mirror.yml`) runs on every push to `main` and mirrors the full repo to a new GitLab repo (`gitlab.com/lirkangel/crm-system`) via `git push --mirror`. This gives the homelab GitLab runner access to the code without changing the primary development workflow on GitHub.

**Secret required in GitHub:** `GITLAB_MIRROR_TOKEN` — a GitLab deploy token with `write_repository` scope on the new `crm-system` GitLab repo.

### Server pipeline (GitLab CI)

`.gitlab-ci.yml` in the repo root. Single `deploy` stage, `homelab` runner tag (same runner as all other homelab apps on docker-host `10.0.0.11`):

```
on push to main →
  docker build server/ -t 10.0.0.7:30500/crm-server:latest
  docker push 10.0.0.7:30500/crm-server:latest
```

Flux `ImageRepository` + `ImagePolicy` detect the new digest → `ImageUpdateAutomation` commits the updated image reference to `homelab-apps-deployment` → Flux `Kustomization` rolls out the new pod.

### Desktop pipeline (GitHub Actions)

`.github/workflows/release.yml`. Triggers on tags matching `v*.*.*`. Uses `tauri-apps/tauri-action` to build binaries for Linux (AppImage + deb), macOS (dmg), and Windows (msi + nsis). Uploads all artifacts as a GitHub Release.

No k8s involvement — desktop binaries are distributed directly via GitHub Releases.

---

## k8s Resources (in homelab-apps-deployment)

**New file:** `crm-server/manifest.yaml`

| Resource | Details |
|---|---|
| Namespace | `crm-server` |
| PVC | `crm-postgres-data` 5Gi, `local-path` |
| Deployment `postgres` | `postgres:16-alpine`, pre-existing `crm-db-secret` for credentials |
| Service `postgres` | ClusterIP, port 5432 |
| Deployment `crm-server` | `10.0.0.7:30500/crm-server:latest`, env from `crm-server-secret` (DB_URL, DB_USER, DB_PASS) |
| Service `crm-server` | NodePort **30082** → container port 8082 |

**Secrets (pre-created in cluster, not in Git):**
- `crm-db-secret` — `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`
- `crm-server-secret` — `DB_URL`, `DB_USER`, `DB_PASS`

**New file:** `clusters/homelab/apps/crm-server.yaml` — Flux `Kustomization` pointing at `./crm-server`

**Updated:** `clusters/homelab/kustomization.yaml` — add `- apps/crm-server.yaml`

**New resources in image-automation:**
- `ImageRepository` `crm-server` → `10.0.0.7:30500/crm-server`, `insecure: true`
- `ImagePolicy` `crm-server` → `filterTags: ^latest$`, `digestReflectionPolicy: Always`, `interval: 1m`

**imagepolicy marker on Deployment image line:**
```yaml
image: 10.0.0.7:30500/crm-server:latest # {"$imagepolicy": "flux-system:crm-server"}
```

---

## Flows

**Server deploy (after setup):**
```
git push origin main (GitHub)
  → mirror.yml mirrors to GitLab
  → GitLab CI builds + pushes crm-server:latest to 10.0.0.7:30500
  → Flux ImageRepository detects new digest (~1m)
  → ImageUpdateAutomation commits updated image ref to homelab-apps-deployment
  → Flux Kustomization rolls out new crm-server pod
```

**Desktop release:**
```
git tag v1.0.0 && git push origin v1.0.0
  → GitHub Actions builds Tauri binaries (Linux/macOS/Windows)
  → Creates GitHub Release with all binaries attached
```

---

## Out of Scope

- TLS / public domain for crm-server (NodePort 30082 is LAN-only for now)
- Desktop auto-update mechanism
- Database migrations (handled by the app at startup)
