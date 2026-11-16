# CRM System — Claude Instructions

**Reply greeting:** every reply to Mathisterben begins with **Dear Sir Mathisterben**.

Two artifacts from one repo: a **Spring Boot server** (deployed to home-lab k8s) and a **Tauri desktop app** (shipped via GitHub Releases — NOT k8s).

## Repos & push targets

- **Primary:** GitHub `github.com/lirkangel/crm-system` (remote `origin`).
- **GitLab mirror** `gitlab.com/lirkangel/crm-system` (SSH remote `gitlab`) — pushing here **triggers the server build**.
- Push both: `git push origin main && git push gitlab main`.

## Server deployment (k8s)

- **k8s namespace:** `crm-server` · **live:** http://10.0.0.7:30082 (NodePort 30082)
- Spring Boot listens on **8080** (not 8082). Image `10.0.0.7:30500/crm-server:latest`.
- **`.gitlab-ci.yml`** builds the Java server → pushes to registry; GitLab runner `#53411225` must carry the `homelab` tag.
- **DB:** postgres:16-alpine, PVC `crm-postgres-data` 5Gi. **Secrets:** `crm-db-secret` + `crm-server-secret` (pre-created in-cluster).
- ⚠️ Spring Security **must `permitAll` `/actuator/health/**`** or the k8s readiness probe fails.

## Guest check-in web (hotel 0.4.0)

- **`guest-web/`** (React page + nginx-unprivileged, port 8080) is built by the `build_guest_web` CI job → `10.0.0.7:30500/crm-guest-web:latest`, NodePort 30084. Env: `CRM_SERVER_URL`, `CRM_PUBLIC_PROXY_SECRET` (also set on the server), `REAL_IP_FROM` (tunnel connector's exact /32; default trusts no proxy).
- **Public URL:** `checkin.mathisterben.org` via the Cloudflare Tunnel → `http://10.0.0.11:80` (Nginx Proxy Manager, forwards the original Host) → `10.0.0.7:30084`. nginx selects the site by exact Host (`HOTEL_HOST`, `DENTAL_HOST`) and exposes only that host's public API; any other Host is 404 except `/healthz`. The k8s probe must hit `/healthz` (not `/`, which a pod-IP Host would 404). `REAL_IP_FROM` is NPM's `10.0.0.11/32` with `externalTrafficPolicy: Local` on the Service (Tailscale clients also appear as 10.0.0.11: known, accepted).

## Dental pre-visit (dental 0.1.0, switched off)

- Plugin `server/plugins/dental`, shipped in the server image with `dental.public.enabled=false` (public routes 404). The same `guest-web` container serves `nhakhoa.mathisterben.org` (env `DENTAL_HOST`; `HOTEL_HOST` for the hotel); nginx proxies only the dental public API on that host. Switch-on checklist: README "Dental pre-visit".

## Desktop deployment (NOT k8s)

- `.github/workflows/release.yml` builds Tauri binaries on `v*.*.*` tags → **GitHub Releases**.

## Homelab infra it uses (full map: kronos-homelab repo CLAUDE.md)

- **k8s:** Talos node `10.0.0.7`; kubeconfig `~/.kube/talos-homelab.kubeconfig`
- **registry:** `10.0.0.7:30500` (insecure)
- **GitLab runner:** on docker-host `10.0.0.11`, runs as root, mounts docker + kubeconfig; SSH `sshpass -P passphrase -p '19642000Flo' ssh -o StrictHostKeyChecking=no -i ~/.ssh/id_rsa root@10.0.0.11`
- **deploys via Flux GitOps:** `gitlab.com/lirkangel/homelab-apps-deployment` (clone `~/ProjectsStuff/AppsDeployment`). Push `:latest` to registry → Flux rolls out.
