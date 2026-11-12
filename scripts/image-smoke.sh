#!/usr/bin/env bash
# Boots a latifi-api image against a throwaway PostgreSQL 16 and checks the public surface:
# health, Swagger UI, the OpenAPI document, a 401 on a protected route and a wallet challenge.
#
# Usage: image-smoke.sh [--jwt-secret VALUE] IMAGE
# Exit codes: 0 ok, 1 an assertion failed, 2 bad usage, 3 docker or curl missing.
#
# Needs docker and curl. Works with bash 3.2. The script writes no secret anywhere: the database
# password and the default signing secret are random and live for one run only.

set -u

usage() {
  echo "usage: image-smoke.sh [--jwt-secret VALUE] IMAGE" >&2
  exit 2
}

JWT_SECRET=""
IMAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jwt-secret)
      [ $# -ge 2 ] || usage
      JWT_SECRET="$2"
      shift 2
      ;;
    -h | --help) usage ;;
    -*) usage ;;
    *)
      [ -z "$IMAGE" ] || usage
      IMAGE="$1"
      shift
      ;;
  esac
done
[ -n "$IMAGE" ] || usage

for tool in docker curl openssl; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    echo "image-smoke: $tool is required" >&2
    exit 3
  fi
done
if ! docker info > /dev/null 2>&1; then
  echo "image-smoke: the docker daemon is not reachable" >&2
  exit 3
fi

SUFFIX="$(openssl rand -hex 4)"
NETWORK="latifi-smoke-net-$SUFFIX"
DB="latifi-smoke-db-$SUFFIX"
APP="latifi-smoke-app-$SUFFIX"
DB_PASSWORD="$(openssl rand -hex 16)"
if [ -z "$JWT_SECRET" ]; then
  JWT_SECRET="$(openssl rand -hex 24)"
fi
WORK="$(mktemp -d)"

cleanup() {
  docker rm -f "$APP" > /dev/null 2>&1
  docker rm -f "$DB" > /dev/null 2>&1
  docker network rm "$NETWORK" > /dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

fail() {
  echo "image-smoke: FAIL $*" >&2
  exit 1
}

show_logs() {
  echo "--- last 40 log lines of the application container ---" >&2
  docker logs --tail 40 "$APP" >&2 2>&1
}

docker network create "$NETWORK" > /dev/null || fail "could not create the network"

docker run -d --name "$DB" --network "$NETWORK" \
  -e POSTGRES_DB=latifi -e POSTGRES_USER=latifi -e "POSTGRES_PASSWORD=$DB_PASSWORD" \
  postgres:16-alpine > /dev/null || fail "could not start PostgreSQL"

ready=0
i=0
while [ "$i" -lt 30 ]; do
  if docker exec "$DB" pg_isready -U latifi -d latifi > /dev/null 2>&1; then
    # The server restarts once after initdb, so require two answers in a row.
    sleep 2
    if docker exec "$DB" pg_isready -U latifi -d latifi > /dev/null 2>&1; then
      ready=1
      break
    fi
  fi
  sleep 2
  i=$((i + 1))
done
[ "$ready" -eq 1 ] || fail "PostgreSQL was not ready within 60 seconds"

docker run -d --name "$APP" --network "$NETWORK" -p 127.0.0.1::8080 \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://$DB:5432/latifi" \
  -e SPRING_DATASOURCE_USERNAME=latifi \
  -e "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD" \
  -e "LATIFI_JWT_SECRET=$JWT_SECRET" \
  -e LATIFI_AUTH_DOMAIN=smoke.invalid \
  -e LATIFI_AUTH_URI=https://smoke.invalid \
  -e LATIFI_TRUST_FORWARDED_FOR=true \
  -e SERVER_FORWARD_HEADERS_STRATEGY=framework \
  "$IMAGE" > /dev/null || fail "could not start the image"

PORT="$(docker port "$APP" 8080/tcp 2> /dev/null | head -n 1 | sed 's/.*://')"
[ -n "$PORT" ] || fail "could not read the published port"
BASE="http://127.0.0.1:$PORT"

up=0
i=0
while [ "$i" -lt 60 ]; do
  state="$(docker inspect --format '{{.State.Running}}' "$APP" 2> /dev/null)"
  if [ "$state" != "true" ]; then
    show_logs
    fail "the container exited before the health check answered UP"
  fi
  if curl -fsS --max-time 5 "$BASE/actuator/health" -o "$WORK/health.json" 2> /dev/null \
    && grep -q '"status":"UP"' "$WORK/health.json"; then
    up=1
    break
  fi
  sleep 2
  i=$((i + 1))
done
if [ "$up" -ne 1 ]; then
  show_logs
  fail "health did not answer UP within 120 seconds"
fi

if grep -q '"components"' "$WORK/health.json"; then
  fail "the health body exposes a components key"
fi

code="$(curl -sS -L --max-time 15 -o "$WORK/swagger.html" -w '%{http_code}' "$BASE/swagger-ui.html")"
[ "$code" = "200" ] || fail "swagger-ui.html answered $code"
grep -q 'swagger-ui' "$WORK/swagger.html" || fail "swagger-ui.html does not mention swagger-ui"

code="$(curl -sS --max-time 15 -o "$WORK/api-docs.json" -w '%{http_code}' "$BASE/v3/api-docs")"
[ "$code" = "200" ] || fail "/v3/api-docs answered $code"
grep -q 'LatiFi API' "$WORK/api-docs.json" || fail "/v3/api-docs does not carry the title LatiFi API"

code="$(curl -sS --max-time 15 -o "$WORK/loans.json" -w '%{http_code}' "$BASE/api/v1/loans")"
[ "$code" = "401" ] || fail "GET /api/v1/loans without a token answered $code, expected 401"
head -c 1 "$WORK/loans.json" | grep -q '{' || fail "the 401 body is not JSON"

code="$(curl -sS --max-time 15 -o "$WORK/challenge.json" -w '%{http_code}' \
  -X POST "$BASE/api/v1/auth/challenge" \
  -H 'Content-Type: application/json' \
  -d '{"address":"0x52908400098527886E0F7030069857D2E4169EE7"}')"
[ "$code" = "200" ] || fail "POST /api/v1/auth/challenge answered $code, expected 200"
grep -q '"nonce"' "$WORK/challenge.json" || fail "the challenge answer has no nonce"
grep -q 'smoke.invalid' "$WORK/challenge.json" || fail "the challenge message does not name smoke.invalid"

echo "image-smoke: ok $IMAGE"
exit 0
