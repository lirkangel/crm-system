#!/bin/sh
# Runs before the image's envsubst step; refuses to start on values that would make a bad nginx config.
# Whole values are matched with case patterns (not grep, which works line by line and would let a value with an
# embedded or trailing newline through).
set -eu
fail() { echo "guest-web: $1" >&2; exit 1; }

# CRM_SERVER_URL: scheme://host[:port], no path, no trailing slash
server_url=${CRM_SERVER_URL:-}
msg="CRM_SERVER_URL must be scheme://host[:port] with no path and no trailing slash"
case "$server_url" in
  http://*|https://*) ;;
  *) fail "$msg" ;;
esac
rest=${server_url#*://}
case "$rest" in
  *:*:*) fail "$msg" ;;
  *:*)
    port=${rest#*:}
    host=${rest%:*}
    case "$port" in ''|*[!0-9]*) fail "$msg" ;; esac
    ;;
  *) host=$rest ;;
esac
case "$host" in ''|*[!A-Za-z0-9.-]*) fail "$msg" ;; esac

# CRM_PUBLIC_PROXY_SECRET: at least 32 of [A-Za-z0-9_-]
secret=${CRM_PUBLIC_PROXY_SECRET:-}
case "$secret" in *[!A-Za-z0-9_-]*) fail "CRM_PUBLIC_PROXY_SECRET must match [A-Za-z0-9_-]{32,}" ;; esac
[ "${#secret}" -ge 32 ] || fail "CRM_PUBLIC_PROXY_SECRET must match [A-Za-z0-9_-]{32,}"

# REAL_IP_FROM: one IP or CIDR
real_ip_from=${REAL_IP_FROM:-}
msg="REAL_IP_FROM must be one IP or CIDR"
case "$real_ip_from" in
  */*/*) fail "$msg" ;;
  */*)
    mask=${real_ip_from#*/}
    case "$mask" in ''|*[!0-9]*) fail "$msg" ;; esac
    [ "${#mask}" -le 3 ] || fail "$msg"
    ;;
esac
addr=${real_ip_from%%/*}
case "$addr" in ''|*[!0-9A-Fa-f:.]*) fail "$msg" ;; esac

echo "guest-web: trusting CF-Connecting-IP only from ${REAL_IP_FROM}"
