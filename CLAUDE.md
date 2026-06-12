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

## Desktop deployment (NOT k8s)

- `.github/workflows/release.yml` builds Tauri binaries on `v*.*.*` tags → **GitHub Releases**.

## Homelab infra it uses (full map: kronos-homelab repo CLAUDE.md)

- **k8s:** Talos node `10.0.0.7`; kubeconfig `~/.kube/talos-homelab.kubeconfig`
- **registry:** `10.0.0.7:30500` (insecure)
- **GitLab runner:** on docker-host `10.0.0.11`, runs as root, mounts docker + kubeconfig; SSH `sshpass -P passphrase -p '19642000Flo' ssh -o StrictHostKeyChecking=no -i ~/.ssh/id_rsa root@10.0.0.11`
- **deploys via Flux GitOps:** `gitlab.com/lirkangel/homelab-apps-deployment` (clone `~/ProjectsStuff/AppsDeployment`). Push `:latest` to registry → Flux rolls out.
