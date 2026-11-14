#!/bin/sh
# Runs before the image's envsubst step; refuses to start on values that would make a bad nginx config.
set -eu
fail() { echo "guest-web: $1" >&2; exit 1; }

printf '%s' "${CRM_SERVER_URL:-}" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]+)?$' \
  || fail "CRM_SERVER_URL must be scheme://host[:port] with no path and no trailing slash"
printf '%s' "${CRM_PUBLIC_PROXY_SECRET:-}" | grep -Eq '^[A-Za-z0-9_-]{32,}$' \
  || fail "CRM_PUBLIC_PROXY_SECRET must match [A-Za-z0-9_-]{32,}"
printf '%s' "${REAL_IP_FROM:-}" | grep -Eq '^[0-9A-Fa-f:.]+(/[0-9]{1,3})?$' \
  || fail "REAL_IP_FROM must be one IP or CIDR"

echo "guest-web: trusting CF-Connecting-IP only from ${REAL_IP_FROM}"
