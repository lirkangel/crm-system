#!/usr/bin/env bash
# End-to-end smoke test of the guest check-in stack on the homelab docker-host (this Mac has no Docker).
#
# Ships server/ and guest-web/ to a unique temp dir over ssh, builds both images with unique tags, runs
# postgres + crm-server + guest-web (nginx) on a unique docker network and checks, through the nginx port:
#   - the public check-in API works end to end (summary, passport submission, staff read-back),
#   - nothing from the staff side is reachable (every other /api path is 404),
#   - security headers are on every response, caching rules hold,
#   - a forged CF-Connecting-IP / X-Client-IP cannot pick the rate-limit bucket (real_ip_only_from_tunnel).
# Everything (containers, network, images, temp dir) is removed on exit. Nothing is pushed to any registry.
# Secrets are random per run and never printed.
#
# Usage:  scripts/smoke-guest-checkin.sh
# Env:    DOCKER_TEST_HOST   ssh target (default root@10.0.0.11)
#         HOMELAB_SSH_PASSPHRASE  optional; when set, ssh goes through sshpass
# Exit code: 0 when every check passed, non-zero otherwise.
set -uo pipefail

HOST="${DOCKER_TEST_HOST:-root@10.0.0.11}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="smoke$(date +%s)$$"
REMOTE_DIR="/root/crm-$RUN_ID"

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=10)
if [[ -n "${HOMELAB_SSH_PASSPHRASE:-}" ]]; then
  ssh_cmd=(sshpass -P passphrase -p "$HOMELAB_SSH_PASSPHRASE" ssh "${SSH_OPTS[@]}")
else
  ssh_cmd=(ssh "${SSH_OPTS[@]}")
fi

if ! "${ssh_cmd[@]}" "$HOST" true; then
  echo "smoke: docker-host $HOST is unreachable" >&2
  exit 255
fi

# Ship the sources (no build output, no node_modules, no extended attributes).
export COPYFILE_DISABLE=1
if ! tar --no-xattrs --exclude='target' --exclude='node_modules' --exclude='dist' -C "$ROOT_DIR" -cf - server guest-web \
  | "${ssh_cmd[@]}" "$HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -xf - -C '$REMOTE_DIR'"; then
  echo "smoke: failed to upload sources to $HOST" >&2
  "${ssh_cmd[@]}" "$HOST" "rm -rf '$REMOTE_DIR'" >/dev/null 2>&1
  exit 255
fi

# The remote driver runs with RUN_ID/REMOTE_DIR in its environment; it tears everything down itself (trap).
"${ssh_cmd[@]}" "$HOST" "RUN_ID='$RUN_ID' REMOTE_DIR='$REMOTE_DIR' bash -s" <<'REMOTE_DRIVER'
set -uo pipefail
cd "$REMOTE_DIR" || exit 255

NET="net-$RUN_ID"
SERVER_IMG="crm-server-$RUN_ID"
WEB_IMG="crm-guest-web-$RUN_ID"
TOOLS_IMG="crm-smoke-tools-$RUN_ID"
CONTAINERS=(db server web webb tools1 tools2)
c() { echo "$RUN_ID-$1"; }

teardown() {
  for n in "${CONTAINERS[@]}"; do docker rm -f "$(c "$n")" >/dev/null 2>&1; done
  docker network rm "$NET" >/dev/null 2>&1
  docker rmi -f "$SERVER_IMG" "$WEB_IMG" "$TOOLS_IMG" >/dev/null 2>&1
  cd / && rm -rf "$REMOTE_DIR"
}
trap teardown EXIT

rand() { head -c 256 /dev/urandom | tr -dc 'A-Za-z0-9' | head -c "$1"; }
set +o pipefail
export PG_PASSWORD="$(rand 24)" JWT_SECRET="$(rand 48)" ADMIN_PASSWORD="$(rand 24)"
export CRM_PUBLIC_PROXY_SECRET="$(rand 40)" FIELD_ENC_KEY="$(head -c 32 /dev/urandom | base64)"
set -o pipefail

# ---- the checks that run inside the docker network (own image: curl + jq) --------------------------------------
mkdir tools
cat > tools/Dockerfile <<'EOF'
FROM alpine:3.20
RUN apk add --no-cache curl jq
COPY checks.sh /checks.sh
EOF
cat > tools/checks.sh <<'EOF'
#!/bin/sh
# usage: checks.sh functional | burst | other-client      (env: SERVER WEB WEBB ADMIN_PASSWORD)
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1  ($2)"; }
expect() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi
}
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
hdr()  { curl -s -D - -o /dev/null "$@" | tr -d '\r'; }
has_header() { # name url-args...  : prints header value (lowercased compare of name)
  n="$1"; shift
  hdr "$@" | grep -i "^$n:" | head -1 | sed 's/^[^:]*: *//'
}
PUB=/api/v1/plugins/hotel/public
HOTEL=/api/v1/plugins/hotel
CSP="default-src 'self'; img-src 'self' blob: data:; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

summary() { echo; echo "checks ($1): $PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]; }

case "$1" in
functional)
  # wait for the server
  i=0; until [ "$(code $SERVER/actuator/health)" = 200 ]; do
    i=$((i+1)); [ $i -gt 90 ] && { echo "FAIL  server did not become healthy"; exit 1; }; sleep 2; done
  ok "server healthy"

  # ---- staff side over the server port ----
  for try in 1 2 3 4 5 6 7 8 9 10; do   # the bootstrap admin is created by a startup runner
    login=$(curl -s -X POST $SERVER/api/v1/auth/login -H 'Content-Type: application/json' \
      -d "{\"username\":\"smokeadmin\",\"password\":\"$ADMIN_PASSWORD\"}")
    TOKEN=$(echo "$login" | jq -r '.data.accessToken // empty')
    [ -n "$TOKEN" ] && break; sleep 3
  done
  [ -n "$TOKEN" ] && ok "admin login" || { bad "admin login" "$(echo "$login" | jq -r '[.code,.message,.detail,.title]|map(select(.!=null))|join(" ")' 2>/dev/null | head -c 200)"; summary functional; exit 1; }
  auth="Authorization: Bearer $TOKEN"
  js='Content-Type: application/json'
  suffix=$(date +%s)
  rt=$(curl -s -X POST $SERVER$HOTEL/room-types -H "$auth" -H "$js" \
    -d "{\"code\":\"SM$suffix\",\"nameI18n\":{\"vi\":\"Phòng đôi\",\"en\":\"Double\"},\"maxGuests\":3,\"nightlyRateGrossVnd\":1000000,\"active\":true}")
  RT=$(echo "$rt" | jq -r '.data.id // empty'); [ -n "$RT" ] && ok "room type created" || bad "room type created" "$(echo "$rt" | head -c 200)"
  rm_=$(curl -s -X POST $SERVER$HOTEL/rooms -H "$auth" -H "$js" \
    -d "{\"number\":\"S$suffix\",\"roomTypeId\":\"$RT\",\"floor\":1,\"inService\":true}")
  ROOM=$(echo "$rm_" | jq -r '.data.id // empty'); [ -n "$ROOM" ] && ok "room created" || bad "room created" "$(echo "$rm_" | head -c 200)"
  now=$(date +%s)
  cin=$(date -u -d "@$((now + 3600 + 86400))" +%F); cout=$(date -u -d "@$((now + 3600 + 3*86400))" +%F)
  rs=$(curl -s -X POST $SERVER$HOTEL/reservations -H "$auth" -H "$js" \
    -d "{\"guestId\":null,\"newGuest\":{\"fullName\":\"Smoke Booker\",\"phone\":\"0912345678\",\"email\":null},\"roomId\":\"$ROOM\",\"checkIn\":\"$cin\",\"checkOut\":\"$cout\",\"adults\":1,\"notes\":null}")
  RES=$(echo "$rs" | jq -r '.data.id // empty'); [ -n "$RES" ] && ok "reservation booked" || { bad "reservation booked" "$(echo "$rs" | head -c 300)"; summary functional; exit 1; }
  lk=$(curl -s -X POST $SERVER$HOTEL/reservations/$RES/guest-link -H "$auth")
  URL=$(echo "$lk" | jq -r '.data.url // empty'); LINK_TOKEN="${URL#*#}"
  [ -n "$URL" ] && [ "$LINK_TOKEN" != "$URL" ] && [ -n "$LINK_TOKEN" ] && ok "guest link created" || { bad "guest link created" "no url"; summary functional; exit 1; }
  tok="X-Checkin-Token: $LINK_TOKEN"

  # ---- guest side through nginx ----
  sum=$(curl -s -w '\n%{http_code}' $WEB$PUB/checkin -H "$tok")
  expect "nginx: GET summary 200" 200 "$(echo "$sum" | tail -1)"
  expect "summary: maxGuests 3" 3 "$(echo "$sum" | head -n -1 | jq -r '.data.maxGuests')"
  expect "summary: open" true "$(echo "$sum" | head -n -1 | jq -r '.data.open')"
  expect "summary: no-store from the server passes" "no-store" "$(has_header cache-control $WEB$PUB/checkin -H "$tok")"
  expect "nginx: made-up token -> 404" 404 "$(code $WEB$PUB/checkin -H 'X-Checkin-Token: nope-nope')"
  expect "nginx: no token -> 404" 404 "$(code $WEB$PUB/checkin)"
  expect "nginx: staff path /reservations -> 404" 404 "$(code $WEB$HOTEL/reservations)"
  expect "nginx: staff path with a staff token -> 404" 404 "$(code $WEB$HOTEL/reservations -H "$auth")"
  expect "nginx: staff guest-link -> 404" 404 "$(code -X POST $WEB$HOTEL/reservations/$RES/guest-link -H "$auth")"
  expect "nginx: /api/v1/auth/login -> 404" 404 "$(code -X POST $WEB/api/v1/auth/login -H "$js" -d '{}')"
  expect "nginx: /api alone -> 404" 404 "$(code $WEB/api)"
  expect "nginx: /api/ -> 404" 404 "$(code $WEB/api/)"
  expect "nginx: public without trailing slash -> 404" 404 "$(code $WEB$HOTEL/public)"
  expect "nginx: dot-dot out of public -> 404" 404 "$(code --path-as-is "$WEB$PUB/../reservations" -H "$auth")"
  expect "nginx: encoded dot-dot out of public -> 404" 404 "$(code --path-as-is "$WEB$PUB/%2e%2e/reservations" -H "$auth")"
  expect "nginx: PUT on public -> not proxied (405/403)" 403 "$(code -X PUT $WEB$PUB/checkin -H "$tok")"

  body='{"identity":{"idType":"PASSPORT","idNumber":"C1234567","fullName":"John Smith","dateOfBirth":"1990-05-17","nationality":"GB","address":null,"visaNumber":null,"entryDate":null,"idScanId":null},"consent":true}'
  sub=$(curl -s -w '\n%{http_code}' -X POST $WEB$PUB/checkin/guests -H "$tok" -H "$js" -d "$body")
  expect "nginx: passport submission 201" 201 "$(echo "$sub" | tail -1)"
  GID=$(echo "$sub" | head -n -1 | jq -r '.data.id // empty')
  expect "nginx: same passport again 200" 200 "$(code -X POST $WEB$PUB/checkin/guests -H "$tok" -H "$js" -d "$body")"
  echo "$sub" | head -n -1 | grep -q 'C1234567' && bad "public response hides the document number" "number echoed" || ok "public response hides the document number"
  expect "summary lists the guest" 1 "$(curl -s $WEB$PUB/checkin -H "$tok" | jq '.data.guests | length')"

  # staff read-back over the server port
  rb=$(curl -s $SERVER$HOTEL/reservations/$RES/pre-arrival-guests -H "$auth")
  echo "$rb" | grep -q 'C1234567' && ok "staff read-back shows the submission" || bad "staff read-back shows the submission" "$(echo "$rb" | head -c 200)"

  # ---- headers and caching of the static side ----
  for path in / /index.html /nope /assets/missing.js $PUB/checkin /api/v1/x; do
    h=$(hdr $WEB$path -H "$tok")
    echo "$h" | grep -qi '^x-robots-tag: noindex' && ok "X-Robots-Tag on $path" || bad "X-Robots-Tag on $path" "missing"
    echo "$h" | grep -qi '^referrer-policy: no-referrer' && ok "Referrer-Policy on $path" || bad "Referrer-Policy on $path" "missing"
    echo "$h" | grep -qi '^x-content-type-options: nosniff' && ok "nosniff on $path" || bad "nosniff on $path" "missing"
    echo "$h" | grep -qi '^permissions-policy: camera=(self), geolocation=(), microphone=()' && ok "Permissions-Policy on $path" || bad "Permissions-Policy on $path" "missing"
    got=$(echo "$h" | grep -i '^content-security-policy:' | sed 's/^[^:]*: *//')
    expect "CSP on $path (exactly one, as specified)" "$CSP" "$got"
  done
  expect "GET / is the SPA" 200 "$(code $WEB/)"
  expect "SPA fallback for a client route" 200 "$(code $WEB/some/client/route)"
  expect "index.html: no-store" "no-store" "$(has_header cache-control $WEB/)"
  asset=$(curl -s $WEB/ | grep -o '/assets/[A-Za-z0-9._-]*\.js' | head -1)
  [ -n "$asset" ] && ok "index.html references a hashed asset" || bad "index.html references a hashed asset" "none"
  expect "asset 200" 200 "$(code $WEB$asset)"
  case "$(has_header cache-control $WEB$asset)" in *immutable*) ok "hashed asset: long cache";; *) bad "hashed asset: long cache" "no immutable";; esac
  expect "missing asset is 404, not the SPA" 404 "$(code $WEB/assets/missing.js)"
  expect "no Set-Cookie from public API" "" "$(has_header set-cookie $WEB$PUB/checkin -H "$tok")"
  expect "server tokens hidden" "nginx" "$(has_header server $WEB/)"

  # DELETE reaches the server through nginx (an unknown submission id is the server's own 404 HOTEL_NOT_FOUND;
  # deleting the real no-scan submission is NOT checked here, see task-6-report.md concern about findScanRef)
  del=$(curl -s -w '\n%{http_code}' -X DELETE $WEB$PUB/checkin/guests/00000000-0000-0000-0000-000000000000 -H "$tok")
  expect "nginx: DELETE proxied (server answers 404)" 404 "$(echo "$del" | tail -1)"
  expect "nginx: DELETE answer is the server's HOTEL_NOT_FOUND" HOTEL_NOT_FOUND "$(echo "$del" | head -n -1 | jq -r '.code')"
  summary functional
  ;;
burst)
  # Every request carries a different forged CF-Connecting-IP (and a forged secret and X-Client-IP): nginx must
  # trust none of them (REAL_IP_FROM=127.0.0.1/32 does not cover this container), so all fall into one bucket.
  n=0; first429=0
  while [ $n -lt $((LIMIT + 1)) ]; do
    n=$((n+1))
    c=$(code $WEBB$PUB/checkin -H 'X-Checkin-Token: nope' -H "CF-Connecting-IP: 203.0.113.$n" \
        -H "X-Client-IP: 198.51.100.$n" -H 'X-Guest-Proxy-Secret: forged')
    [ "$c" = 429 ] && [ $first429 -eq 0 ] && first429=$n
  done
  expect "real_ip_only_from_tunnel: first 429 is request $((LIMIT + 1)) despite forged CF-Connecting-IP" $((LIMIT + 1)) "$first429"
  summary burst
  ;;
other-client)
  # A different client (different container address) still has its own bucket: proves nginx sends the real
  # X-Client-IP together with the right secret, and that the forged bursts did not poison everybody.
  expect "another client is not rate limited" 404 "$(code $WEBB$PUB/checkin -H 'X-Checkin-Token: nope')"
  summary other-client
  ;;
esac
EOF

fail=0
step() { echo "== $*"; }

step "build images"
docker build -q -t "$SERVER_IMG" server >/dev/null 2>build-server.log || { echo "BUILD FAILED: server"; tail -30 build-server.log; exit 1; }
docker build -q -t "$WEB_IMG" guest-web >/dev/null 2>build-web.log || { echo "BUILD FAILED: guest-web"; tail -30 build-web.log; exit 1; }
docker build -q -t "$TOOLS_IMG" tools >/dev/null 2>build-tools.log || { echo "BUILD FAILED: tools"; tail -30 build-tools.log; exit 1; }
echo "images built"

step "start stack"
docker network create "$NET" >/dev/null || exit 1
docker run -d --name "$(c db)" --network "$NET" --network-alias db -e POSTGRES_USER=foundation -e POSTGRES_DB=foundation \
  -e PG_PASSWORD -e POSTGRES_PASSWORD="$PG_PASSWORD" postgres:16-alpine >/dev/null || exit 1
for i in $(seq 1 30); do docker exec "$(c db)" pg_isready -U foundation >/dev/null 2>&1 && break; sleep 1; done
LIMIT=20
docker run -d --name "$(c server)" --network "$NET" --network-alias server \
  -e DB_URL=jdbc:postgresql://db:5432/foundation -e DB_USER=foundation -e DB_PASS="$PG_PASSWORD" \
  -e JWT_SECRET -e FIELD_ENC_KEY -e CRM_PUBLIC_PROXY_SECRET \
  -e BOOTSTRAP_ADMIN_USERNAME=smokeadmin -e BOOTSTRAP_ADMIN_EMAIL=smokeadmin@example.invalid -e BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e CRM_PUBLIC_RATE_LIMIT_PER_MINUTE=$LIMIT -e CRM_PUBLIC_RATELIMITPERMINUTE=$LIMIT \
  -e HOTEL_GUEST_BASE_URL=https://checkin.smoke.invalid -e HOTEL_DISPLAY_NAME=SmokeHotel \
  "$SERVER_IMG" >/dev/null || exit 1
# web: default tunnel CIDR (a docker address is not in it); webb: only 127.0.0.1 is trusted for CF-Connecting-IP
docker run -d --name "$(c web)" --network "$NET" --network-alias web \
  -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e REAL_IP_FROM=10.0.0.0/8 "$WEB_IMG" >/dev/null || exit 1
docker run -d --name "$(c webb)" --network "$NET" --network-alias webb \
  -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e REAL_IP_FROM=127.0.0.1/32 "$WEB_IMG" >/dev/null || exit 1
for n in tools1 tools2; do
  docker run -d --name "$(c $n)" --network "$NET" -e ADMIN_PASSWORD -e LIMIT \
    -e SERVER=http://server:8080 -e WEB=http://web:8080 -e WEBB=http://webb:8080 \
    --entrypoint sleep "$TOOLS_IMG" 3600 >/dev/null || exit 1
done

# Admin permissions of the hotel plugin are granted when it starts; wait for that before anyone logs in.
for i in $(seq 1 120); do docker logs "$(c server)" 2>&1 | grep -q 'Plugin com.crm.hotel .* active under' && break; sleep 2; done

run_checks() { docker exec -e LIMIT=$LIMIT "$(c "$1")" sh /checks.sh "$2"; }

step "functional checks (client: tools1)"
run_checks tools1 functional | tee out-functional.txt; [ "${PIPESTATUS[0]}" -eq 0 ] || fail=1
step "forged CF-Connecting-IP burst (client: tools2, limit $LIMIT/min)"
run_checks tools2 burst | tee out-burst.txt; [ "${PIPESTATUS[0]}" -eq 0 ] || fail=1
step "other client keeps its own bucket (client: tools1)"
run_checks tools1 other-client | tee out-other.txt; [ "${PIPESTATUS[0]}" -eq 0 ] || fail=1

total_pass=$(cat out-*.txt | grep -c '^PASS')
total_fail=$(cat out-*.txt | grep -c '^FAIL')
echo
echo "================ SMOKE SUMMARY ================"
echo "passed: $total_pass   failed: $total_fail"
if [ "$fail" -ne 0 ] || [ "$total_fail" -ne 0 ]; then
  echo "RESULT: FAIL"
  echo "--- server log tail ---"; docker logs "$(c server)" 2>&1 | grep -i 'bootstrap\|WARN\|ERROR' | grep -iv 'password\|secret' | tail -15
  exit 1
fi
echo "RESULT: PASS"
REMOTE_DRIVER
