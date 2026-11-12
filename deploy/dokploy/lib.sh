#!/usr/bin/env bash
# Shared helpers of the Dokploy kit. Source this file, do not execute it.
# Compatible with bash 3.2. Needs curl and jq.
#
# Rules enforced here (T-07-07, T-07-09):
#   - env files are parsed line by line and never sourced, so nothing in them is executed;
#   - a file with a mode looser than 600 is refused;
#   - the Dokploy token and every request body travel to curl through a config read from stdin,
#     so none of them appears in argv, in `ps` or in a process listing;
#   - a plain http panel URL is refused unless the host is loopback, because the token would cross
#     the network in clear text;
#   - every text that can reach a terminal goes through dk_redact first.
#
# Exit codes used by the scripts of the kit: 0 ok, 2 bad usage or invalid config, 3 prerequisite
# missing, 4 Dokploy API error, 5 an operation the API does not support.

LATIFI_HOME="${LATIFI_HOME:-$HOME/.latifi}"
DK_STATE_FILE="${DK_STATE_FILE:-$LATIFI_HOME/dokploy-state.json}"
DK_SECRETS=""
DK_CODE=""
DK_BODY=""
DOKPLOY_URL=""
DOKPLOY_TOKEN=""

dk_die() {
  local code="$1"
  shift
  echo "error: $*" >&2
  exit "$code"
}

dk_need_tools() {
  local t
  for t in "$@"; do
    command -v "$t" > /dev/null 2>&1 || dk_die 3 "$t is required"
  done
}

dk_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

# Prints only the host of a URL, without userinfo, port, path or query. IPv6 literals keep brackets.
dk_url_host() {
  local auth
  auth="$(printf '%s' "$1" | sed -E \
    -e 's#^[A-Za-z][A-Za-z0-9+.-]*://##' \
    -e 's#^[^@/]*@##' \
    -e 's#[/?\#].*$##')"
  case "$auth" in
    \[*) printf '%s' "$auth" | sed -E 's#^(\[[^]]*\]).*#\1#' ;;
    *) printf '%s' "$auth" | sed -E 's#:.*$##' ;;
  esac
}

# Octal mode of a file, portable between GNU and BSD stat.
dk_file_mode() {
  stat -c '%a' "$1" 2> /dev/null || stat -f '%Lp' "$1" 2> /dev/null
}

# Refuses an env file that group or others can read. Exit 2.
dk_require_private_file() {
  local file="$1" mode
  [ -f "$file" ] || dk_die 3 "file not found: $file"
  mode="$(dk_file_mode "$file")"
  case "$mode" in
    *00) ;;
    *) dk_die 2 "$file has mode $mode, run: chmod 600 $file" ;;
  esac
}

# Prints the value of KEY from an env file. The file is parsed, never sourced. The last definition
# wins and a value keeps everything after the first equals sign.
dk_env_get() {
  local file="$1" key="$2"
  grep -E "^${key}=" "$file" | tail -n 1 | cut -d= -f2-
}

# Adds a value to the list of texts that dk_redact removes. Ignores short values that would
# redact ordinary words.
dk_add_secret() {
  [ "${#1}" -ge 6 ] || return 0
  if [ -z "$DK_SECRETS" ]; then
    DK_SECRETS="$1"
  else
    DK_SECRETS="$DK_SECRETS
$1"
  fi
}

# Replaces every known secret with *** and masks the key of a node URL of the form /v2/KEY.
# Reads stdin, writes stdout.
dk_redact() {
  DK_SECRETS_ENV="$DK_SECRETS" awk '
    BEGIN {
      n = split(ENVIRON["DK_SECRETS_ENV"], list, "\n")
    }
    {
      line = $0
      for (i = 1; i <= n; i++) {
        s = list[i]
        if (s == "") continue
        while ((p = index(line, s)) > 0) {
          line = substr(line, 1, p - 1) "***" substr(line, p + length(s))
        }
      }
      print line
    }' | sed -E 's#(/v[0-9]+/)[A-Za-z0-9_-]{16,}#\1***#g; s#([Xx]-[Aa][Pp][Ii]-[Kk][Ee][Yy]: *)[A-Za-z0-9._-]+#\1***#g'
}

# Loads DOKPLOY_URL and DOKPLOY_TOKEN from FILE. Exit 2 or 3 with a message that never holds a value.
dk_load_env() {
  local file="$1" host
  dk_require_private_file "$file"
  DOKPLOY_URL="$(dk_env_get "$file" DOKPLOY_URL)"
  DOKPLOY_TOKEN="$(dk_env_get "$file" DOKPLOY_TOKEN)"
  [ -n "$DOKPLOY_URL" ] || dk_die 2 "DOKPLOY_URL is missing in $file"
  [ -n "$DOKPLOY_TOKEN" ] || dk_die 2 "DOKPLOY_TOKEN is missing in $file"
  printf '%s' "$DOKPLOY_TOKEN" | grep -Eq '^[A-Za-z0-9._-]+$' \
    || dk_die 2 "DOKPLOY_TOKEN has characters outside letters, digits, dot, underscore and hyphen"
  printf '%s' "$DOKPLOY_URL" | grep -Eq '^https?://[^[:space:]"\\]+$' \
    || dk_die 2 "DOKPLOY_URL must be an http or https URL without spaces or quotes"
  DOKPLOY_URL="${DOKPLOY_URL%/}"
  dk_add_secret "$DOKPLOY_TOKEN"
  case "$DOKPLOY_URL" in
    https://*) ;;
    *)
      host="$(dk_url_host "$DOKPLOY_URL")"
      if ! printf '%s' "$host" | grep -Eq '^(localhost|127(\.[0-9]{1,3}){3}|\[::1\])$' \
        && [ "${DOKPLOY_ALLOW_HTTP:-}" != "1" ]; then
        dk_die 2 "DOKPLOY_URL is plain http to $host, so the token would travel in clear text. Use an SSH tunnel to http://localhost:3000, an https panel domain, or set DOKPLOY_ALLOW_HTTP=1"
      fi
      ;;
  esac
}

# One API call in the current shell (never call it inside $( ) when you need the status).
# Sets DK_CODE and DK_BODY. Returns 0 on a 2xx answer and 4 otherwise. Prints nothing.
#   dk_call METHOD PATH [JSON]
# Queries use GET with the query string inside PATH, mutations use POST with a JSON body.
# The token and the body reach curl through a config on stdin.
dk_call() {
  local method="$1" path="$2" body="${3:-}" cfg esc out rc
  cfg="header = \"x-api-key: $DOKPLOY_TOKEN\""
  if [ -n "$body" ]; then
    # One line only: a line break would end the data line of the curl config.
    body="$(printf '%s' "$body" | jq -c . 2> /dev/null)" || return 4
    esc="$(printf '%s' "$body" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
    cfg="$cfg
data = \"$esc\""
  fi
  out="$(printf '%s\n' "$cfg" | curl -sS --fail-with-body --max-time 30 -K - \
    -X "$method" -H 'Content-Type: application/json' \
    -w $'\n__HTTP__%{http_code}' "$DOKPLOY_URL/api/$path" 2> /dev/null)"
  rc=$?
  case "$out" in
    *__HTTP__*)
      DK_CODE="${out##*__HTTP__}"
      DK_BODY="${out%$'\n'__HTTP__*}"
      ;;
    *)
      DK_CODE="000"
      DK_BODY=""
      ;;
  esac
  if [ "$rc" -ne 0 ]; then
    return 4
  fi
  case "$DK_CODE" in
    2??) return 0 ;;
    *) return 4 ;;
  esac
}

# Prints a one line failure for the last dk_call. Status, path and the first 200 characters of the
# body, redacted. Never prints the request.
dk_explain_failure() {
  local method="$1" path="$2" shown
  shown="$(printf '%s' "$DK_BODY" | head -c 200 | tr '\r\n' '  ' | dk_redact)"
  echo "dokploy: HTTP $DK_CODE on $method ${path%%\?*}: $shown" >&2
}

# Same call for the common case. Prints the body on success, explains and returns 4 on failure.
#   body="$(dk_api GET project.all)" || exit 4
dk_api() {
  if dk_call "$@"; then
    printf '%s' "$DK_BODY"
    return 0
  fi
  dk_explain_failure "$1" "$2"
  return 4
}

# State file: ids and the application name only, never a secret. Mode 600.
dk_state_get() {
  [ -f "$DK_STATE_FILE" ] || return 0
  jq -r --arg k "$1" '.[$k] // empty' "$DK_STATE_FILE" 2> /dev/null
}

dk_state_put() {
  local key="$1" value="$2" tmp current='{}'
  mkdir -p "$(dirname "$DK_STATE_FILE")"
  if [ -f "$DK_STATE_FILE" ] && jq -e . "$DK_STATE_FILE" > /dev/null 2>&1; then
    current="$(cat "$DK_STATE_FILE")"
  fi
  tmp="$(mktemp "$(dirname "$DK_STATE_FILE")/.state.XXXXXX")" || return 3
  printf '%s' "$current" | jq --arg k "$key" --arg v "$value" '.[$k] = $v' > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$DK_STATE_FILE"
}
