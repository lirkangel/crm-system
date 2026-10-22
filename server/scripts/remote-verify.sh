#!/usr/bin/env bash
# Run Maven for server/ on the homelab docker-host (JDK 21 + Docker for Testcontainers).
# The Mac's own Maven/JDK cannot compile Lombok, so all builds and tests go through here.
#
# Usage:  server/scripts/remote-verify.sh -Pintegration clean verify
#         server/scripts/remote-verify.sh -Pintegration clean verify -Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false
#         server/scripts/remote-verify.sh -pl plugins/hotel -am test      # hotel-module unit tests only
# Focused runs must go through `verify`: foundation test-depends on the hotel plugin ZIP, which
# only exists after the hotel module is packaged, so `-pl foundation -am test` cannot resolve it.
#
# Env:    DOCKER_TEST_HOST         ssh target (default root@10.0.0.11)
#         HOMELAB_SSH_PASSPHRASE   optional; when set, ssh goes through sshpass.
#                                  Otherwise rely on ssh-agent / an unencrypted key.
# Exit code is Maven's exit code (ssh propagates the remote command's status).
set -uo pipefail

HOST="${DOCKER_TEST_HOST:-root@10.0.0.11}"
REMOTE_DIR="/root/crm-verify-${USER:-$(id -un)}"
IMAGE="maven:3.9.9-eclipse-temurin-21-alpine"
SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SSH_OPTS=(-o StrictHostKeyChecking=no)
if [[ -n "${HOMELAB_SSH_PASSPHRASE:-}" ]]; then
  ssh_cmd=(sshpass -P passphrase -p "$HOMELAB_SSH_PASSPHRASE" ssh "${SSH_OPTS[@]}")
else
  ssh_cmd=(ssh "${SSH_OPTS[@]}")
fi

# Shell-quote the maven args for the remote shell.
quoted_args=""
for a in "$@"; do quoted_args+=" $(printf '%q' "$a")"; done

cleanup() { "${ssh_cmd[@]}" "$HOST" "rm -rf '$REMOTE_DIR'" >/dev/null 2>&1; }
trap cleanup EXIT

# Fresh copy of the sources every run (no stale files from earlier trees).
export COPYFILE_DISABLE=1
if ! tar --no-xattrs --exclude='target' -C "$SERVER_DIR" -cf - . \
  | "${ssh_cmd[@]}" "$HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -xf - -C '$REMOTE_DIR'"; then
  echo "remote-verify: failed to upload sources to $HOST" >&2
  exit 255
fi

"${ssh_cmd[@]}" "$HOST" "docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v '$REMOTE_DIR':/ws -v crm-m2:/root/.m2 -w /ws \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  $IMAGE mvn -B$quoted_args"
rc=$?
exit "$rc"
