#!/usr/bin/env bash
# Checks the public HTTPS surface of a deployed latifi-api. No token and no secret are needed.
#
# Usage: smoke-live.sh https://HOST [--evidence FILE]
#   --evidence FILE  also write a JSON record with the host, the UTC time, every status code and the
#                    certificate notAfter. The file holds no secret.
#
# Checks, in order, each failing with exit 1 and one line that names the check:
#   redirect         plain http answers a redirect whose Location starts with the https origin
#   certificate      the chain verifies and has at least 14 days left
#   health           /actuator/health answers 200, JSON, "status":"UP", and has no components key
#   swagger-ui       /swagger-ui.html (redirects followed) answers 200 and is the Swagger UI
#   openapi          /v3/api-docs answers 200, title LatiFi API, servers[0].url is the https origin
#   protected-route  GET /api/v1/loans without a token answers 401 with a JSON body
#   actuator-env     /actuator/env answers no 2xx
#   actuator-beans   /actuator/beans answers no 2xx
#   challenge        a wallet challenge answers 200 with a 32 hex nonce and a message that names the host
# The last line is `smoke-live: ok URL`.
# Exit codes: 0 ok, 1 a check failed, 2 bad usage, 3 curl, openssl or jq missing.
# Needs curl, openssl and jq. Bash 3.2.

set -u

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

URL=""
EVIDENCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --evidence)
      [ $# -ge 2 ] || usage
      EVIDENCE="$2"
      shift 2
      ;;
    -h | --help) usage ;;
    -*) usage ;;
    *)
      [ -z "$URL" ] || usage
      URL="$1"
      shift
      ;;
  esac
done
[ -n "$URL" ] || usage
URL="${URL%/}"
printf '%s' "$URL" | grep -Eq '^https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?$' \
  || {
    echo "smoke-live: the URL must be an https origin such as https://api.example.com" >&2
    exit 2
  }
HOST="${URL#https://}"

for tool in curl openssl jq; do
  command -v "$tool" > /dev/null 2>&1 || {
    echo "smoke-live: $tool is required" >&2
    exit 3
  }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "smoke-live: FAIL $1: $2" >&2
  exit 1
}

# fetch NAME PATH [curl args...] saves the body in $WORK/NAME.body and the status in CODE.
CODE=""
fetch() {
  local name="$1" path="$2"
  shift 2
  CODE="$(curl -sS --max-time 20 -o "$WORK/$name.body" -w '%{http_code}' "$@" "$URL$path")" \
    || fail "$name" "curl could not complete the request to $path (exit $?)"
}

# ---- redirect ---------------------------------------------------------------------------------

out="$(curl -sS --max-time 20 -o /dev/null -w '%{http_code} %{redirect_url}' "http://$HOST/actuator/health")" \
  || fail redirect "curl could not reach http://$HOST (exit $?)"
CODE_REDIRECT="${out%% *}"
LOCATION="${out#* }"
case "$CODE_REDIRECT" in
  301 | 302 | 307 | 308) ;;
  *) fail redirect "plain http answered $CODE_REDIRECT, expected a redirect to https" ;;
esac
case "$LOCATION" in
  "$URL"*) ;;
  *) fail redirect "the redirect goes to '$LOCATION', expected a URL under $URL" ;;
esac

# ---- certificate ------------------------------------------------------------------------------

out="$(curl -sS --max-time 20 -o /dev/null -w '%{ssl_verify_result}' "$URL/actuator/health")" \
  || fail certificate "the TLS handshake with $HOST failed (curl exit $?), the certificate is not trusted"
[ "$out" = "0" ] || fail certificate "the chain does not verify (ssl_verify_result $out)"

cert="$(echo | openssl s_client -servername "$HOST" -connect "$HOST:443" 2> /dev/null | openssl x509 2> /dev/null)"
[ -n "$cert" ] || fail certificate-expiry "could not read the certificate of $HOST"
NOT_AFTER="$(printf '%s\n' "$cert" | openssl x509 -noout -enddate 2> /dev/null | sed 's/^notAfter=//')"
printf '%s\n' "$cert" | openssl x509 -noout -checkend 1209600 > /dev/null 2>&1 \
  || fail certificate-expiry "the certificate expires within 14 days (notAfter $NOT_AFTER)"

# ---- health -----------------------------------------------------------------------------------

out="$(curl -sS --max-time 20 -o "$WORK/health.body" -w '%{http_code} %{content_type}' "$URL/actuator/health")" \
  || fail health "curl could not complete the request (exit $?)"
CODE_HEALTH="${out%% *}"
[ "$CODE_HEALTH" = "200" ] || fail health "answered $CODE_HEALTH, expected 200"
case "$out" in
  *json*) ;;
  *) fail health "the answer is not JSON ($out)" ;;
esac
grep -q '"status":"UP"' "$WORK/health.body" || fail health 'the body does not hold "status":"UP"'
if grep -q '"components"' "$WORK/health.body"; then
  fail health "the body exposes a components key"
fi

# ---- swagger ui and the openapi document ------------------------------------------------------

fetch swagger-ui /swagger-ui.html -L
CODE_SWAGGER="$CODE"
[ "$CODE_SWAGGER" = "200" ] || fail swagger-ui "/swagger-ui.html answered $CODE_SWAGGER, expected 200"
grep -q 'swagger-ui' "$WORK/swagger-ui.body" || fail swagger-ui "the page does not mention swagger-ui"

fetch openapi /v3/api-docs
CODE_OPENAPI="$CODE"
[ "$CODE_OPENAPI" = "200" ] || fail openapi "/v3/api-docs answered $CODE_OPENAPI, expected 200"
title="$(jq -r '.info.title // empty' "$WORK/openapi.body" 2> /dev/null)"
[ "$title" = "LatiFi API" ] || fail openapi "the title is '$title', expected LatiFi API"
server="$(jq -r '.servers[0].url // empty' "$WORK/openapi.body" 2> /dev/null)"
case "$server" in
  "$URL"*) ;;
  *) fail openapi "servers[0].url is '$server', expected the origin $URL (forwarded headers are not trusted)" ;;
esac

# ---- protected and hidden routes --------------------------------------------------------------

fetch protected-route /api/v1/loans
CODE_PROTECTED="$CODE"
[ "$CODE_PROTECTED" = "401" ] || fail protected-route "GET /api/v1/loans without a token answered $CODE_PROTECTED, expected 401"
head -c 1 "$WORK/protected-route.body" | grep -q '{' || fail protected-route "the 401 body is not JSON"

fetch actuator-env /actuator/env
CODE_ENV="$CODE"
case "$CODE_ENV" in
  2??) fail actuator-env "/actuator/env answered $CODE_ENV, it must not be exposed" ;;
esac
fetch actuator-beans /actuator/beans
CODE_BEANS="$CODE"
case "$CODE_BEANS" in
  2??) fail actuator-beans "/actuator/beans answered $CODE_BEANS, it must not be exposed" ;;
esac

# ---- challenge --------------------------------------------------------------------------------

fetch challenge /api/v1/auth/challenge -X POST -H 'Content-Type: application/json' \
  -d '{"address":"0x52908400098527886E0F7030069857D2E4169EE7"}'
CODE_CHALLENGE="$CODE"
[ "$CODE_CHALLENGE" = "200" ] || fail challenge "POST /api/v1/auth/challenge answered $CODE_CHALLENGE, expected 200"
nonce="$(jq -r '.nonce // empty' "$WORK/challenge.body" 2> /dev/null)"
printf '%s' "$nonce" | grep -Eq '^[0-9a-f]{32}$' || fail challenge "the nonce is not 32 hex characters"
message="$(jq -r '.message // empty' "$WORK/challenge.body" 2> /dev/null)"
first="$(printf '%s\n' "$message" | head -n 1)"
case "$first" in
  "$HOST wants you to sign in"*) ;;
  *) fail challenge "the first line of the message does not name $HOST: it names '${first%% *}'" ;;
esac
printf '%s\n' "$message" | grep -Fxq "URI: $URL" || fail challenge "the message does not carry the line URI: $URL"

# ---- evidence ---------------------------------------------------------------------------------

if [ -n "$EVIDENCE" ]; then
  jq -n --arg host "$HOST" --arg time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg notAfter "$NOT_AFTER" \
    --arg redirect "$CODE_REDIRECT" --arg health "$CODE_HEALTH" --arg swagger "$CODE_SWAGGER" \
    --arg openapi "$CODE_OPENAPI" --arg protected "$CODE_PROTECTED" --arg env "$CODE_ENV" \
    --arg beans "$CODE_BEANS" --arg challenge "$CODE_CHALLENGE" \
    '{host: $host, checkedAtUtc: $time, certificateNotAfter: $notAfter,
      status: {httpRedirect: $redirect, health: $health, swaggerUi: $swagger, openapi: $openapi,
               protectedRoute: $protected, actuatorEnv: $env, actuatorBeans: $beans, challenge: $challenge}}' \
    > "$EVIDENCE" || {
    echo "smoke-live: could not write the evidence file" >&2
    exit 2
  }
  echo "smoke-live: evidence written to $EVIDENCE"
fi

echo "smoke-live: ok $URL"
exit 0
