#!/usr/bin/env bash
# End-to-end smoke test of the guest stack (hotel check-in + dental pre-visit) on the homelab docker-host (this Mac
# has no Docker).
#
# Ships server/ and guest-web/ to a unique temp dir over ssh, builds both images with unique tags, runs
# postgres + crm-server + guest-web (nginx) on a unique docker network and checks, through the nginx port:
#   - the public check-in API works end to end on the hotel host (summary, passport submission, staff read-back),
#   - the dental pre-visit API works end to end on the dental host (appointment, link, summary, form, staff read),
#   - each host proxies only its own public API; the other host's API and any unknown Host are 404,
#   - nothing from the staff side is reachable (every other /api path is 404),
#   - security headers are on every response, caching rules hold,
#   - a forged CF-Connecting-IP / X-Client-IP cannot pick the rate-limit bucket (real_ip_only_from_tunnel).
# Hosts are reached through docker network aliases: checkin.<name> (hotel), nhakhoa.<name> (dental), and the bare
# <name> (an unknown Host). Everything (containers, network, images, temp dir) is removed on exit. Nothing is pushed to any registry.
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
  export SSHPASS="$HOMELAB_SSH_PASSPHRASE"
  ssh_cmd=(sshpass -P passphrase -e ssh "${SSH_OPTS[@]}")
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
CONTAINERS=(db server web webb webc webe echo tools1 tools2 tools3 tools4 tools5)
VOL_BEFORE=$(docker volume ls -q | wc -l)
c() { echo "$RUN_ID-$1"; }

teardown() {
  local rc=$?
  for n in "${CONTAINERS[@]}"; do docker rm -fv "$(c "$n")" >/dev/null 2>&1; done   # -v: postgres' anonymous volume
  docker network rm "$NET" >/dev/null 2>&1
  docker rmi -f "$SERVER_IMG" "$WEB_IMG" "$TOOLS_IMG" >/dev/null 2>&1
  cd / && rm -rf "$REMOTE_DIR"
  local after; after=$(docker volume ls -q | wc -l)
  echo "volumes before: $VOL_BEFORE, after teardown: $after"
  if [ "$after" -gt "$VOL_BEFORE" ]; then echo "RESULT: FAIL (volumes left behind)"; exit 1; fi
  exit "$rc"
}
trap teardown EXIT

rand() { head -c 256 /dev/urandom | tr -dc 'A-Za-z0-9' | head -c "$1"; }
set +o pipefail
export PG_PASSWORD="$(rand 24)" JWT_SECRET="$(rand 48)" ADMIN_PASSWORD="$(rand 24)"   # secret below: 40 chars of [A-Za-z0-9]
export CRM_PUBLIC_PROXY_SECRET="$(rand 40)" FIELD_ENC_KEY="$(head -c 32 /dev/urandom | base64)"
export DENTAL_FIELD_KEY="$(head -c 32 /dev/urandom | base64)"   # never printed
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
# usage: checks.sh functional | dental | burst | other-client     (env: SERVER WEB DENTAL UNKNOWN WEBB ADMIN_PASSWORD)
# WEB is the hotel host (checkin.*), DENTAL the dental host (nhakhoa.*), UNKNOWN the same nginx under a Host it
# does not serve.
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
DPUB=/api/v1/plugins/dental/public
DENT=/api/v1/plugins/dental
CSP="default-src 'self'; img-src 'self' blob: data:; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

summary() { echo; echo "checks ($1): $PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]; }

login_admin() { # sets TOKEN (empty on failure) and login
  for try in 1 2 3 4 5 6 7 8 9 10; do   # the bootstrap admin is created by a startup runner
    login=$(curl -s -X POST $SERVER/api/v1/auth/login -H 'Content-Type: application/json' \
      -d "{\"username\":\"smokeadmin\",\"password\":\"$ADMIN_PASSWORD\"}")
    TOKEN=$(echo "$login" | jq -r '.data.accessToken // empty')
    [ -n "$TOKEN" ] && break; sleep 3
  done
}

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

  # a guest removes the passport submission (no photo): 204, and it is gone from the summary
  del=$(curl -s -w '\n%{http_code}' -X DELETE $WEB$PUB/checkin/guests/$GID -H "$tok")
  expect "nginx: DELETE passport submission 204" 204 "$(echo "$del" | tail -1)"
  expect "summary: guest gone after DELETE" 0 "$(curl -s $WEB$PUB/checkin -H "$tok" | jq '.data.guests | length')"
  expect "nginx: second DELETE 404 HOTEL_NOT_FOUND" HOTEL_NOT_FOUND "$(curl -s -X DELETE $WEB$PUB/checkin/guests/$GID -H "$tok" | jq -r '.code')"

  # ---- traversal table: staff token in hand, every variant must be refused by nginx ----
  for p in "$PUB/..;/auth/login" "$PUB/%2e%2e%2f.." "$PUB/..%2freservations" "$PUB/%2E%2E/" \
           "/$HOTEL/reservations" "$PUB//../reservations" "$PUB/..\\reservations" "$PUB/..%5creservations" \
           "$PUB/%2e%2e/%2e%2e/auth/login" "$PUB/x%3b/../../reservations"; do
    c=$(curl -s -g -o /dev/null -w '%{http_code}' --path-as-is "$WEB$p" -H "$auth")
    case "$c" in 404|400) ok "traversal refused ($c): $p";; *) bad "traversal refused: $p" "got $c";; esac
  done
  summary functional
  ;;
dental)
  login_admin
  [ -n "$TOKEN" ] && ok "admin login" || { bad "admin login" "no token"; summary dental; exit 1; }
  auth="Authorization: Bearer $TOKEN"
  js='Content-Type: application/json'
  now=$(date +%s)
  starts=$(date -u -d "@$((now + 86400))" +%Y-%m-%dT%H:%M:%SZ)
  bk=$(curl -s -w '\n%{http_code}' -X POST $SERVER$DENT/appointments -H "$auth" -H "$js" \
    -d "{\"newPatient\":{\"fullName\":\"Smoke Patient\",\"phone\":\"0912345678\",\"dateOfBirth\":\"1990-05-17\"},\"startsAt\":\"$starts\",\"durationMinutes\":30,\"dentist\":\"Dr Smoke\",\"reason\":\"checkup\"}")
  expect "staff: appointment booked 201" 201 "$(echo "$bk" | tail -1)"
  APPT=$(echo "$bk" | head -n -1 | jq -r '.data.id // empty')
  [ -n "$APPT" ] || { bad "appointment id" "none"; summary dental; exit 1; }
  lk=$(curl -s -w '\n%{http_code}' -X POST $SERVER$DENT/appointments/$APPT/visit-link -H "$auth")
  expect "staff: visit link created 201" 201 "$(echo "$lk" | tail -1)"
  URL=$(echo "$lk" | head -n -1 | jq -r '.data.url // empty'); VTOKEN="${URL#*#d/}"
  [ -n "$URL" ] && [ "$VTOKEN" != "$URL" ] && [ -n "$VTOKEN" ] && ok "visit link has a #d/ token" || { bad "visit link has a #d/ token" "no url"; summary dental; exit 1; }
  case "$URL" in https://nhakhoa.smoke.invalid/#d/*) ok "link uses dental.guest-base-url";; *) bad "link uses dental.guest-base-url" "unexpected prefix";; esac
  vt="X-Visit-Token: $VTOKEN"

  # ---- patient side through nginx on the dental host ----
  sum=$(curl -s -w '\n%{http_code}' $DENTAL$DPUB/visit -H "$vt")
  expect "dental host: GET summary 200" 200 "$(echo "$sum" | tail -1)"
  expect "summary: open" true "$(echo "$sum" | head -n -1 | jq -r '.data.open')"
  expect "summary: adult" false "$(echo "$sum" | head -n -1 | jq -r '.data.child')"
  expect "summary: not yet submitted" false "$(echo "$sum" | head -n -1 | jq -r '.data.submitted')"
  expect "summary: no-store passes" "no-store" "$(has_header cache-control $DENTAL$DPUB/visit -H "$vt")"
  expect "dental host: made-up token -> 404" 404 "$(code $DENTAL$DPUB/visit -H 'X-Visit-Token: nope-nope')"
  expect "dental host: no token -> 404" 404 "$(code $DENTAL$DPUB/visit)"
  body='{"identity":{"idType":"PASSPORT","idNumber":"C7654321","fullName":"Jane Smith","dateOfBirth":"1990-05-17","nationality":"GB","address":null},"phone":"0912345678","allergies":{"none":true,"items":[],"otherText":null},"medicines":{"none":true,"text":null},"reason":{"code":"CHECKUP","text":null},"consent":true,"idScanId":null}'
  sub=$(curl -s -w '\n%{http_code}' -X POST $DENTAL$DPUB/visit/form -H "$vt" -H "$js" -d "$body")
  expect "dental host: passport form 201" 201 "$(echo "$sub" | tail -1)"
  echo "$sub" | head -n -1 | grep -q 'C7654321' && bad "public response hides the document number" "echoed" || ok "public response hides the document number"
  expect "summary: submitted after the form" true "$(curl -s $DENTAL$DPUB/visit -H "$vt" | jq -r '.data.submitted')"
  blank='{"identity":{"idType":"PASSPORT","idNumber":"C7654321","fullName":"Jane Smith","dateOfBirth":"1990-05-17","nationality":"GB","address":null},"phone":"0912345678","medicines":{"none":true,"text":null},"reason":{"code":"CHECKUP","text":null},"consent":true,"idScanId":null}'
  expect "dental host: untouched allergy question -> 400" 400 "$(code -X POST $DENTAL$DPUB/visit/form -H "$vt" -H "$js" -d "$blank")"

  # staff read-back over the server port
  pv=$(curl -s -w '\n%{http_code}' $SERVER$DENT/appointments/$APPT/previsit -H "$auth")
  expect "staff: previsit read 200" 200 "$(echo "$pv" | tail -1)"
  pj=$(echo "$pv" | head -n -1)
  expect "staff read-back: document number" C7654321 "$(echo "$pj" | jq -r '.data.identity.idNumber')"
  expect "staff read-back: allergies none" true "$(echo "$pj" | jq -r '.data.allergies.none')"
  expect "staff read-back: medicines none" true "$(echo "$pj" | jq -r '.data.medicines.none')"
  expect "staff read-back: reason" CHECKUP "$(echo "$pj" | jq -r '.data.reason.code')"
  expect "staff read-back: consent recorded" true "$(echo "$pj" | jq -r '.data.consentAt != null')"

  # ---- the hosts stay apart ----
  expect "dental host: hotel public path -> 404" 404 "$(code $DENTAL$PUB/checkin -H 'X-Checkin-Token: nope')"
  expect "hotel host: dental public path -> 404" 404 "$(code $WEB$DPUB/visit -H "$vt")"
  expect "hotel host: dental public path without token -> 404" 404 "$(code $WEB$DPUB/visit)"
  expect "unknown host: / -> 404" 404 "$(code $UNKNOWN/)"
  expect "unknown host: /index.html -> 404" 404 "$(code $UNKNOWN/index.html)"
  expect "unknown host: hotel public path -> 404" 404 "$(code $UNKNOWN$PUB/checkin -H 'X-Checkin-Token: nope')"
  expect "unknown host: dental public path -> 404" 404 "$(code $UNKNOWN$DPUB/visit -H "$vt")"
  expect "forged Host on the hotel port: / -> 404" 404 "$(code $WEB/ -H 'Host: evil.example')"
  expect "forged Host on the hotel port: dental path -> 404" 404 "$(code $WEB$DPUB/visit -H 'Host: evil.example' -H "$vt")"
  expect "forged dental Host reaches the dental API" 200 "$(code $WEB$DPUB/visit -H 'Host: nhakhoa.forged.example' -H "$vt")"
  expect "dental host: staff path -> 404" 404 "$(code $DENTAL$DENT/appointments -H "$auth")"
  expect "dental host: staff visit-link -> 404" 404 "$(code -X POST $DENTAL$DENT/appointments/$APPT/visit-link -H "$auth")"
  expect "dental host: staff previsit -> 404" 404 "$(code $DENTAL$DENT/appointments/$APPT/previsit -H "$auth")"
  expect "dental host: /api/v1/auth/login -> 404" 404 "$(code -X POST $DENTAL/api/v1/auth/login -H "$js" -d '{}')"
  expect "dental host: /api -> 404" 404 "$(code $DENTAL/api)"
  expect "dental host: /api/ -> 404" 404 "$(code $DENTAL/api/)"
  expect "dental host: public without trailing slash -> 404" 404 "$(code $DENTAL$DENT/public)"
  expect "dental host: PUT on public -> 403" 403 "$(code -X PUT $DENTAL$DPUB/visit -H "$vt")"
  expect "dental host: DELETE on public -> 403" 403 "$(code -X DELETE $DENTAL$DPUB/visit -H "$vt")"
  expect "hotel host: GET / is the SPA" 200 "$(code $WEB/)"
  expect "dental host: GET / is the SPA" 200 "$(code $DENTAL/)"
  expect "dental host: SPA fallback for a client route" 200 "$(code $DENTAL/some/route)"
  expect "dental host: missing asset is 404" 404 "$(code $DENTAL/assets/missing.js)"
  expect "dental host: index.html no-store" "no-store" "$(has_header cache-control $DENTAL/)"
  expect "dental host: no Set-Cookie from the public API" "" "$(has_header set-cookie $DENTAL$DPUB/visit -H "$vt")"

  # security headers on every kind of response on every host (the unknown host included)
  for base in $DENTAL $UNKNOWN; do
    for path in / /nope /assets/missing.js $DPUB/visit /api/v1/x; do
      h=$(hdr $base$path -H "$vt")
      echo "$h" | grep -qi '^x-robots-tag: noindex' && ok "X-Robots-Tag on $base$path" || bad "X-Robots-Tag on $base$path" "missing"
      echo "$h" | grep -qi '^referrer-policy: no-referrer' && ok "Referrer-Policy on $base$path" || bad "Referrer-Policy on $base$path" "missing"
      echo "$h" | grep -qi '^x-content-type-options: nosniff' && ok "nosniff on $base$path" || bad "nosniff on $base$path" "missing"
      echo "$h" | grep -qi '^permissions-policy: camera=(self), geolocation=(), microphone=()' && ok "Permissions-Policy on $base$path" || bad "Permissions-Policy on $base$path" "missing"
      got=$(echo "$h" | grep -i '^content-security-policy:' | sed 's/^[^:]*: *//')
      expect "CSP on $base$path (exactly one, as specified)" "$CSP" "$got"
    done
  done

  # traversal table on the dental host
  for p in "$DPUB/..;/auth/login" "$DPUB/%2e%2e%2f.." "$DPUB/..%2fappointments" "$DPUB/%2E%2E/" \
           "$DPUB//../appointments" "$DPUB/..\\appointments" "$DPUB/..%5cappointments" \
           "$DPUB/%2e%2e/%2e%2e/auth/login" "$DPUB/x%3b/../../appointments" "$DPUB/../../hotel/public/checkin"; do
    c=$(curl -s -g -o /dev/null -w '%{http_code}' --path-as-is "$DENTAL$p" -H "$auth")
    case "$c" in 404|400) ok "traversal refused ($c): $p";; *) bad "traversal refused: $p" "got $c";; esac
  done
  summary dental
  ;;
burst|rotate|fixed)
  # burst URL : every request has a different forged CF-Connecting-IP (+ forged X-Client-IP and secret)
  # fixed URL : every request has the same CF-Connecting-IP
  # rotate URL: like burst, against a proxy that DOES trust this client's CF-Connecting-IP (positive control)
  url="$2"; n=0; first=0
  while [ $n -lt $((LIMIT + 1)) ]; do
    n=$((n+1))
    case "$1" in fixed) cf=198.51.100.77;; *) cf="203.0.113.$n";; esac
    c=$(code $url$PUB/checkin -H 'X-Checkin-Token: nope' -H "CF-Connecting-IP: $cf" \
        -H "X-Client-IP: 198.51.100.$n" -H 'X-Guest-Proxy-Secret: forged')
    [ "$c" = 429 ] && [ $first -eq 0 ] && first=$n
  done
  case "$1" in
    rotate) expect "trusted proxy, rotating CF-Connecting-IP: never 429 ($n requests)" 0 "$first";;
    *) if [ "$first" -ge "$LIMIT" ] && [ "$first" -le $((LIMIT + 1)) ]; then
         ok "$3: one bucket, first 429 at request $first (limit $LIMIT)"
       else bad "$3: one bucket" "first 429 at $first, expected $LIMIT or $((LIMIT + 1))"; fi;;
  esac
  summary "$1"
  ;;
echo)
  # The throwaway upstream echoes what nginx forwards: only nginx-set values may arrive.
  got=$(curl -s $WEBE$PUB/checkin -H 'X-Client-IP: 1.1.1.1' -H 'x-client-ip: 2.2.2.2' \
    -H 'X-Guest-Proxy-Secret: forged1' -H 'x-GUEST-proxy-secret: forged2' -H 'Forwarded: for=9.9.9.9' \
    -H 'X-Forwarded-For: 8.8.8.8' -H 'X-Forwarded-Host: evil.example' -H 'X-Forwarded-Proto: http' \
    -H 'X-Forwarded-Port: 1' -H 'X-Real-IP: 7.7.7.7' -H 'CF-Connecting-IP: 6.6.6.6' \
    -H 'Authorization: Bearer forged' -H 'Cookie: sid=forged')
  want="$(hostname -i | tr -d ' ')|$CRM_PUBLIC_PROXY_SECRET||||||||/api/v1/plugins/hotel/public/checkin"
  if [ "$got" = "$want" ]; then ok "header echo: exactly the nginx-set values, forwarding headers, Authorization and Cookie empty, normalised path"
  else bad "header echo" "upstream saw: $(echo "$got" | sed "s|$CRM_PUBLIC_PROXY_SECRET|<secret>|")"; fi
  gotd=$(curl -s $WEBDE$DPUB/visit -H 'X-Client-IP: 1.1.1.1' -H 'X-Guest-Proxy-Secret: forged1' -H 'Forwarded: for=9.9.9.9' \
    -H 'X-Forwarded-For: 8.8.8.8' -H 'X-Forwarded-Host: evil.example' -H 'X-Forwarded-Proto: http' \
    -H 'X-Forwarded-Port: 1' -H 'X-Real-IP: 7.7.7.7' -H 'CF-Connecting-IP: 6.6.6.6' \
    -H 'Authorization: Bearer forged' -H 'Cookie: sid=forged')
  wantd="$(hostname -i | tr -d ' ')|$CRM_PUBLIC_PROXY_SECRET||||||||/api/v1/plugins/dental/public/visit"
  if [ "$gotd" = "$wantd" ]; then ok "dental header echo: exactly the nginx-set values, normalised path"
  else bad "dental header echo" "upstream saw: $(echo "$gotd" | sed "s|$CRM_PUBLIC_PROXY_SECRET|<secret>|")"; fi
  summary echo
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
LIMIT=30
docker run -d --name "$(c server)" --network "$NET" --network-alias server \
  -e DB_URL=jdbc:postgresql://db:5432/foundation -e DB_USER=foundation -e DB_PASS="$PG_PASSWORD" \
  -e JWT_SECRET -e FIELD_ENC_KEY -e CRM_PUBLIC_PROXY_SECRET -e DENTAL_FIELD_KEY \
  -e BOOTSTRAP_ADMIN_USERNAME=smokeadmin -e BOOTSTRAP_ADMIN_EMAIL=smokeadmin@example.invalid -e BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e CRM_PUBLIC_RATE_LIMIT_PER_MINUTE=$LIMIT \
  -e HOTEL_GUEST_BASE_URL=https://checkin.smoke.invalid -e HOTEL_DISPLAY_NAME=SmokeHotel \
  -e DENTAL_PUBLIC_ENABLED=true -e DENTAL_GUEST_BASE_URL=https://nhakhoa.smoke.invalid -e DENTAL_DISPLAY_NAME=SmokeClinic \
  "$SERVER_IMG" >/dev/null || exit 1
# Hosts: checkin.<name> is the hotel host, nhakhoa.<name> the dental host (both match the image's default
# HOTEL_HOST/DENTAL_HOST through the checkin.* / nhakhoa.* wildcards), the bare <name> is a Host nobody serves.
# web : image default (REAL_IP_FROM unset -> 127.0.0.1/32, trusts no proxy)
# webb: REAL_IP_FROM=127.0.0.1/32 set explicitly
# webc: REAL_IP_FROM = this network's subnet (positive control: CF-Connecting-IP IS trusted from here)
# webe: like web but its upstream is a header-echo server instead of the CRM
SUBNET=$(docker network inspect "$NET" -f '{{(index .IPAM.Config 0).Subnet}}')
docker run -d --name "$(c web)" --network "$NET" --network-alias web --network-alias checkin.web --network-alias nhakhoa.web \
  -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET "$WEB_IMG" >/dev/null || exit 1
docker run -d --name "$(c webb)" --network "$NET" --network-alias webb --network-alias checkin.webb \
  -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e REAL_IP_FROM=127.0.0.1/32 "$WEB_IMG" >/dev/null || exit 1
docker run -d --name "$(c webc)" --network "$NET" --network-alias webc --network-alias checkin.webc \
  -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e REAL_IP_FROM="$SUBNET" "$WEB_IMG" >/dev/null || exit 1
cat > echo.conf <<'ECHOEOF'
server {
    listen 8080;
    location / {
        default_type text/plain;
        return 200 "$http_x_client_ip|$http_x_guest_proxy_secret|$http_forwarded|$http_x_forwarded_for|$http_x_forwarded_host|$http_x_forwarded_proto|$http_x_forwarded_port|$http_authorization|$http_cookie|$uri";
    }
}
ECHOEOF
docker run -d --name "$(c echo)" --network "$NET" --network-alias echo \
  -v "$PWD/echo.conf":/etc/nginx/conf.d/default.conf:ro nginxinc/nginx-unprivileged:1.27-alpine >/dev/null || exit 1
docker run -d --name "$(c webe)" --network "$NET" --network-alias webe --network-alias checkin.webe --network-alias nhakhoa.webe \
  -e CRM_SERVER_URL=http://echo:8080 -e CRM_PUBLIC_PROXY_SECRET "$WEB_IMG" >/dev/null || exit 1
for n in tools1 tools2 tools3 tools4 tools5; do
  docker run -d --name "$(c $n)" --network "$NET" -e ADMIN_PASSWORD -e LIMIT -e CRM_PUBLIC_PROXY_SECRET \
    -e SERVER=http://server:8080 -e WEB=http://checkin.web:8080 -e DENTAL=http://nhakhoa.web:8080 -e UNKNOWN=http://web:8080 \
    -e WEBB=http://checkin.webb:8080 -e WEBE=http://checkin.webe:8080 -e WEBDE=http://nhakhoa.webe:8080 \
    -e DENTAL_FIELD_KEY \
    --entrypoint sleep "$TOOLS_IMG" 3600 >/dev/null || exit 1
done

# Admin permissions of the hotel and dental plugins are granted when it starts; wait for that before anyone logs in.
for i in $(seq 1 120); do docker logs "$(c server)" 2>&1 | grep -q 'Plugin com.crm.hotel .* active under' && \
    docker logs "$(c server)" 2>&1 | grep -q 'Plugin com.crm.dental .* active under' && break; sleep 2; done

run_checks() { local who=$1; shift; docker exec -e LIMIT=$LIMIT "$(c "$who")" sh /checks.sh "$@"; }
record() { tee "out-$1.txt"; return "${PIPESTATUS[0]}"; }   # keep the check's exit status through tee

step "startup guards (driver side)"
{
  line=$(docker logs "$(c web)" 2>&1 | grep -F 'guest-web: trusting CF-Connecting-IP only from' | head -1)
  [ "$line" = "guest-web: trusting CF-Connecting-IP only from 127.0.0.1/32" ] \
    && echo "PASS  default REAL_IP_FROM is 127.0.0.1/32 and is logged once at start" || echo "FAIL  startup log (got: $line)"
  [ "$(docker logs "$(c web)" 2>&1 | grep -c 'trusting CF-Connecting-IP only from')" = 1 ] \
    && echo "PASS  startup message logged exactly once" || echo "FAIL  startup message count"
  docker logs "$(c web)" 2>&1 | grep -qF "$CRM_PUBLIC_PROXY_SECRET" \
    && echo "FAIL  the secret appears in the container log" || echo "PASS  the secret is not logged"
  guard() { # name rc-must-be-1 env...
    local name=$1; shift
    timeout 20 docker run --rm --network "$NET" "$@" "$WEB_IMG" >/dev/null 2>&1; rc=$?
    [ $rc -eq 1 ] && echo "PASS  start refused: $name" || echo "FAIL  start refused: $name (rc=$rc)"
  }
  guard "CRM_SERVER_URL with a path" -e CRM_SERVER_URL=http://server:8080/x -e CRM_PUBLIC_PROXY_SECRET
  guard "CRM_SERVER_URL with a trailing slash" -e CRM_SERVER_URL=http://server:8080/ -e CRM_PUBLIC_PROXY_SECRET
  guard "secret too short" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET=short
  guard "secret with a quote" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"x'
  guard "CRM_SERVER_URL with a trailing newline" -e "CRM_SERVER_URL=http://server:8080"$'\n' -e CRM_PUBLIC_PROXY_SECRET
  guard "secret with an embedded newline" -e CRM_SERVER_URL=http://server:8080 -e "CRM_PUBLIC_PROXY_SECRET=${CRM_PUBLIC_PROXY_SECRET}"$'\nx'
  guard "REAL_IP_FROM with a trailing newline" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e "REAL_IP_FROM=127.0.0.1/32"$'\n'
  guard "HOTEL_HOST with a path" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e HOTEL_HOST=checkin.example/x
  guard "HOTEL_HOST with a space" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e "HOTEL_HOST=checkin.example x"
  guard "HOTEL_HOST with a trailing newline" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e "HOTEL_HOST=checkin.example"$'\n'
  guard "HOTEL_HOST empty" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e HOTEL_HOST=
  guard "DENTAL_HOST with a semicolon" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e 'DENTAL_HOST=nhakhoa.example; x'
  guard "DENTAL_HOST with a leading hyphen label" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e DENTAL_HOST=-nhakhoa.example
  guard "DENTAL_HOST with an empty label" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e DENTAL_HOST=nhakhoa..example
  guard "both hosts the same" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e HOTEL_HOST=same.example -e DENTAL_HOST=same.example
  guard "REAL_IP_FROM not an address" -e CRM_SERVER_URL=http://server:8080 -e CRM_PUBLIC_PROXY_SECRET -e 'REAL_IP_FROM=0.0.0.0/0; x'
} | tee out-guards.txt

step "functional checks (client: tools1)"
run_checks tools1 functional | record functional || fail=1
step "dental pre-visit path and host separation (client: tools1)"
run_checks tools1 dental | record dental || fail=1
step "forged CF-Connecting-IP, image default (no REAL_IP_FROM) (client: tools2, limit $LIMIT/min)"
run_checks tools2 burst http://checkin.web:8080 "default image" | record burst-default || fail=1
step "forged CF-Connecting-IP, REAL_IP_FROM=127.0.0.1/32 (client: tools3)"
run_checks tools3 burst http://checkin.webb:8080 "REAL_IP_FROM=127.0.0.1/32" | record burst-explicit || fail=1
step "positive control: proxy trusting this network, rotating CF-Connecting-IP (client: tools4)"
run_checks tools4 rotate http://checkin.webc:8080 | record rotate || fail=1
step "positive control: proxy trusting this network, one CF-Connecting-IP (client: tools5)"
run_checks tools5 fixed http://checkin.webc:8080 "trusted proxy, fixed CF-Connecting-IP" | record fixed || fail=1
step "header echo through a throwaway upstream (client: tools1)"
run_checks tools1 echo | record echo || fail=1
step "other client keeps its own bucket (client: tools1)"
run_checks tools1 other-client | record other || fail=1

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
