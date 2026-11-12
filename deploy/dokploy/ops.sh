#!/usr/bin/env bash
# Day-two operations of the latifi-api application through the Dokploy API.
#
# Usage: ops.sh [--env-file FILE] [--app-env-file FILE] COMMAND
#   status              build status, and the real container state and health
#   logs [--tail N]     runtime log of the application, N lines (default 200, at most 10000), redacted
#   stop                stop the application (a graceful stop)
#   start               start it again
#   redeploy            rebuild from the Git source and replace the container
#   kill                kill the container as a crash would, so the orchestrator restarts it. This is
#                       what the indexer kill and recover rehearsal uses
#
# --env-file      DOKPLOY_URL and DOKPLOY_TOKEN (default ~/.latifi/dokploy.env)
# --app-env-file  runtime variables (default ~/.latifi/latifi-api.env), read only to know which values
#                 to hide in logs. It is optional.
# The application id comes from ~/.latifi/dokploy-state.json, written by provision.sh --apply.
# Every command prints the UTC time it ran, which the rehearsal records.
#
# Exit codes: 0 ok, 2 bad usage or invalid config, 3 prerequisite missing, 4 Dokploy API error,
# 5 the API does not allow the operation (kill prints the docker kill line for an SSH session).
# Bash 3.2, needs curl and jq.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

ENV_FILE="$LATIFI_HOME/dokploy.env"
APP_ENV_FILE="$LATIFI_HOME/latifi-api.env"
CMD=""
TAIL=200

usage() {
  sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file)
      [ $# -ge 2 ] || usage
      ENV_FILE="$2"
      shift 2
      ;;
    --app-env-file)
      [ $# -ge 2 ] || usage
      APP_ENV_FILE="$2"
      shift 2
      ;;
    --tail)
      [ $# -ge 2 ] || usage
      TAIL="$2"
      shift 2
      ;;
    status | logs | stop | start | redeploy | kill)
      [ -z "$CMD" ] || usage
      CMD="$1"
      shift
      ;;
    -h | --help) usage ;;
    *) usage ;;
  esac
done
[ -n "$CMD" ] || usage
printf '%s' "$TAIL" | grep -Eq '^[0-9]{1,5}$' && [ "$TAIL" -ge 1 ] && [ "$TAIL" -le 10000 ] \
  || dk_die 2 "--tail must be a number from 1 to 10000"

dk_need_tools curl jq
dk_load_env "$ENV_FILE"

# The runtime values are only used to hide them. A missing file is fine, a loose one is not.
if [ -f "$APP_ENV_FILE" ]; then
  dk_require_private_file "$APP_ENV_FILE"
  for key in SPRING_DATASOURCE_PASSWORD LATIFI_JWT_SECRET LATIFI_RPC_URL; do
    dk_add_secret "$(dk_env_get "$APP_ENV_FILE" "$key")"
  done
fi

APP_ID="$(dk_state_get applicationId)"
[ -n "$APP_ID" ] || dk_die 3 "no application id in $DK_STATE_FILE, run provision.sh --apply first"
printf '%s' "$APP_ID" | grep -Eq '^[A-Za-z0-9_-]+$' || dk_die 2 "the application id in the state file has unexpected characters"

# app_name prints the generated container name prefix of the application. Dokploy assigns a slug, and
# applicationStatus alone only says that the build finished.
app_name() {
  local one name
  one="$(dk_api GET "application.one?applicationId=$APP_ID")" || return 4
  name="$(printf '%s' "$one" | jq -r '.appName // empty' 2> /dev/null)"
  [ -n "$name" ] || {
    echo "dokploy: application.one answered without an appName" >&2
    return 4
  }
  printf '%s' "$name" | grep -Eq '^[A-Za-z0-9._-]+$' || {
    echo "dokploy: the appName has unexpected characters" >&2
    return 4
  }
  printf '%s' "$name"
}

# containers APPNAME prints the JSON array of containers whose name starts with the appName.
containers() {
  dk_api GET "docker.getContainersByAppNameMatch?appName=$1"
}

# text turns a readLogs answer into plain text. Dokploy answers a JSON string, and raw control
# characters inside it make a strict parser fail, so those are replaced by spaces and tried again.
text() {
  local raw="$1" out
  if out="$(printf '%s' "$raw" | jq -r 'if type == "string" then . else tojson end' 2> /dev/null)"; then
    printf '%s\n' "$out"
    return 0
  fi
  if out="$(printf '%s' "$raw" | LC_ALL=C tr '\000-\037' ' ' | jq -r 'if type == "string" then . else tojson end' 2> /dev/null)"; then
    printf '%s\n' "$out"
    return 0
  fi
  printf '%s\n' "$raw"
}

case "$CMD" in
  status)
    echo "ops: status at $(dk_now)"
    one="$(dk_api GET "application.one?applicationId=$APP_ID")" || exit 4
    name="$(printf '%s' "$one" | jq -r '.appName // empty' 2> /dev/null)"
    echo "application: ${name:-unknown}"
    echo "build status: $(printf '%s' "$one" | jq -r '.applicationStatus // "unknown"' 2> /dev/null) (done only means the build finished)"
    [ -n "$name" ] || exit 4
    list="$(containers "$name")" || exit 4
    count="$(printf '%s' "$list" | jq 'length' 2> /dev/null || echo 0)"
    if [ "$count" -eq 0 ]; then
      echo "container: none found, the application is not running"
    else
      printf '%s' "$list" | jq -r '.[] | "container: \(.name) state=\(.state) status=\(.status)"' | dk_redact
    fi
    ;;
  logs)
    echo "ops: logs at $(dk_now)" >&2
    dk_call GET "application.readLogs?applicationId=$APP_ID&tail=$TAIL&since=all" || {
      dk_explain_failure GET application.readLogs
      exit 4
    }
    text "$DK_BODY" | dk_redact
    ;;
  stop | start)
    body="$(jq -nc --arg a "$APP_ID" '{applicationId: $a}')"
    dk_api POST "application.$CMD" "$body" > /dev/null || exit 4
    echo "ops: $CMD requested at $(dk_now)"
    ;;
  redeploy)
    body="$(jq -nc --arg a "$APP_ID" '{applicationId: $a, title: "Redeploy from ops.sh"}')"
    dk_api POST application.redeploy "$body" > /dev/null || exit 4
    echo "ops: redeploy queued at $(dk_now)"
    ;;
  kill)
    name="$(app_name)" || exit 4
    list="$(containers "$name")" || exit 4
    cid="$(printf '%s' "$list" | jq -r '[.[] | select(.state == "running")][0].containerId // empty' 2> /dev/null)"
    cname="$(printf '%s' "$list" | jq -r '[.[] | select(.state == "running")][0].name // empty' 2> /dev/null)"
    [ -n "$cid" ] || dk_die 3 "no running container of $name, nothing to kill"
    printf '%s' "$cid" | grep -Eq '^[A-Za-z0-9._-]+$' || dk_die 4 "the container id has unexpected characters"
    body="$(jq -nc --arg c "$cid" '{containerId: $c}')"
    if dk_call POST docker.killContainer "$body"; then
      echo "ops: kill sent at $(dk_now) to container $cname"
      echo "ops: the orchestrator restarts it, watch with ops.sh status and ops.sh logs"
      exit 0
    fi
    case "$DK_CODE" in
      404 | 403 | 405)
        echo "ops: the Dokploy API refused or does not offer docker.killContainer (HTTP $DK_CODE) at $(dk_now)" >&2
        echo "ops: run this over SSH on the VPS instead, it does the same:" >&2
        echo "  docker kill $cname" >&2
        exit 5
        ;;
    esac
    dk_explain_failure POST docker.killContainer
    exit 4
    ;;
esac
exit 0
