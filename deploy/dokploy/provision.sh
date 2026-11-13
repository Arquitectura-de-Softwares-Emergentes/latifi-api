#!/usr/bin/env bash
# Idempotent Dokploy setup of latifi-api: project, application, Git source, Dockerfile build type,
# runtime environment, domain with Let's Encrypt and, on request, a deploy.
#
# Usage: provision.sh --host DNS_NAME [options] [--apply] [--deploy]
#   --host NAME          public DNS name of the API (required)
#   --env-file FILE      DOKPLOY_URL and DOKPLOY_TOKEN (default ~/.latifi/dokploy.env), --apply only
#   --app-env-file FILE  runtime variables, built from app.env.example (default ~/.latifi/latifi-api.env)
#   --branch NAME        branch or tag to build (default main)
#   --repo-url URL       Git URL (default the latifi-api repository of the organization)
#   --project NAME       Dokploy project (default latifi)
#   --app NAME           Dokploy application (default latifi-api)
#   --github-id ID       use the Dokploy GitHub App provider with push triggers instead of a Git URL
#   --apply              execute. Without it the script validates and prints the plan, with no network call
#   --deploy             queue a deploy at the end of --apply
#
# Exit codes: 0 ok, 2 bad usage or invalid config, 3 prerequisite missing, 4 Dokploy API error,
# 5 an operation the API does not support.
#
# Bash 3.2, needs curl and jq. No secret is ever printed: values show as *** and the node URL as its host.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

DEPLOYMENT_BLOCK=51675536
ENV_FILE="$LATIFI_HOME/dokploy.env"
APP_ENV_FILE="$LATIFI_HOME/latifi-api.env"
HOST=""
BRANCH="main"
REPO_URL="https://github.com/Arquitectura-de-Softwares-Emergentes/latifi-api.git"
PROJECT="latifi"
APP="latifi-api"
GITHUB_ID=""
APPLY=0
DEPLOY=0
AMOY_MANIFEST="${LATIFI_AMOY_MANIFEST:-$REPO_ROOT/../latifi-contracts/deployments/amoy.json}"

KEYS="SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD LATIFI_JWT_SECRET LATIFI_AUTH_DOMAIN LATIFI_AUTH_URI LATIFI_TRUST_FORWARDED_FOR SERVER_FORWARD_HEADERS_STRATEGY LATIFI_RPC_URL LATIFI_LOANBOOK_ADDRESS LATIFI_INDEXER_START_BLOCK LATIFI_SWAGGER_ENABLED"

# Optional keys: sent only when the file gives a value. A free-tier Alchemy node allows 10 blocks per
# eth_getLogs call, so set LATIFI_INDEXER_BATCH_SIZE=10 there. The default of the API is 500.
OPTIONAL_KEYS="LATIFI_INDEXER_BATCH_SIZE"

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file | --app-env-file | --host | --branch | --repo-url | --project | --app | --github-id)
      [ $# -ge 2 ] || usage
      case "$1" in
        --env-file) ENV_FILE="$2" ;;
        --app-env-file) APP_ENV_FILE="$2" ;;
        --host) HOST="$2" ;;
        --branch) BRANCH="$2" ;;
        --repo-url) REPO_URL="$2" ;;
        --project) PROJECT="$2" ;;
        --app) APP="$2" ;;
        --github-id) GITHUB_ID="$2" ;;
      esac
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --deploy)
      DEPLOY=1
      shift
      ;;
    -h | --help) usage ;;
    *) usage ;;
  esac
done

[ -n "$HOST" ] || dk_die 2 "--host is required"
dk_need_tools curl jq

# Argument checks. Messages name the flag, never a value.
printf '%s' "$HOST" | grep -Eq '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' \
  || dk_die 2 "--host must be a lowercase DNS name such as api.example.com"
printf '%s' "$BRANCH" | grep -Eq '^[A-Za-z0-9._/-]+$' || dk_die 2 "--branch has characters that a branch name cannot hold"
printf '%s' "$PROJECT" | grep -Eq '^[A-Za-z0-9._-]+$' || dk_die 2 "--project must use letters, digits, dot, underscore or hyphen"
printf '%s' "$APP" | grep -Eq '^[A-Za-z0-9._-]+$' || dk_die 2 "--app must use letters, digits, dot, underscore or hyphen"
printf '%s' "$REPO_URL" | grep -Eq '^https://[^[:space:]"'"'"']+$' || dk_die 2 "--repo-url must be an https URL"
if [ -n "$GITHUB_ID" ]; then
  printf '%s' "$GITHUB_ID" | grep -Eq '^[A-Za-z0-9_-]+$' || dk_die 2 "--github-id has unexpected characters"
  printf '%s' "$REPO_URL" | grep -Eq '^https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$' \
    || dk_die 2 "--github-id needs a github.com repository URL in --repo-url"
fi

invalid() {
  echo "provision: invalid $1: $2" >&2
  exit 2
}

# ---- validate the runtime environment ---------------------------------------------------------

dk_require_private_file "$APP_ENV_FILE"

val() {
  dk_env_get "$APP_ENV_FILE" "$1"
}

missing=""
for k in $KEYS; do
  v="$(val "$k")"
  [ -n "$v" ] || missing="$missing $k"
done
if [ -n "$missing" ]; then
  echo "provision: missing or empty keys in the runtime environment file:$missing" >&2
  exit 2
fi

for k in $KEYS; do
  v="$(val "$k")"
  case "$v" in
    *[[:space:]]* | *\"* | *\'*) invalid "$k" "the value has a quote, a space or a line break" ;;
  esac
done

# Names of keys in the file that the kit does not send. Names only.
is_managed() {
  case " $KEYS $OPTIONAL_KEYS " in
    *" $1 "*) return 0 ;;
  esac
  return 1
}
extra=""
for name in $(grep -E '^[A-Z][A-Z0-9_]*=' "$APP_ENV_FILE" | cut -d= -f1 | sort -u); do
  is_managed "$name" || extra="$extra$name "
done
[ -z "$extra" ] || echo "provision: ignoring keys that the kit does not manage: $extra" >&2

DB_URL="$(val SPRING_DATASOURCE_URL)"
DB_USER="$(val SPRING_DATASOURCE_USERNAME)"
DB_PASSWORD="$(val SPRING_DATASOURCE_PASSWORD)"
JWT_SECRET="$(val LATIFI_JWT_SECRET)"
AUTH_DOMAIN="$(val LATIFI_AUTH_DOMAIN)"
AUTH_URI="$(val LATIFI_AUTH_URI)"
TRUST_XFF="$(val LATIFI_TRUST_FORWARDED_FOR)"
FWD_STRATEGY="$(val SERVER_FORWARD_HEADERS_STRATEGY)"
RPC_URL="$(val LATIFI_RPC_URL)"
LOANBOOK="$(val LATIFI_LOANBOOK_ADDRESS)"
START_BLOCK="$(val LATIFI_INDEXER_START_BLOCK)"
SWAGGER="$(val LATIFI_SWAGGER_ENABLED)"
BATCH_SIZE="$(val LATIFI_INDEXER_BATCH_SIZE)"

dk_add_secret "$DB_PASSWORD"
dk_add_secret "$JWT_SECRET"
dk_add_secret "$RPC_URL"

printf '%s' "$DB_URL" | grep -Eq '^jdbc:postgresql://[A-Za-z0-9._-]+(:[0-9]+)?/[A-Za-z0-9_]+$' \
  || invalid SPRING_DATASOURCE_URL "expected jdbc:postgresql://HOST:PORT/DATABASE with no user or password inside"
printf '%s' "$DB_PASSWORD" | grep -Eq '^[A-Za-z0-9]+$' \
  || invalid SPRING_DATASOURCE_PASSWORD "letters and digits only, because other characters break connection strings (use openssl rand -hex 24)"
[ "${#JWT_SECRET}" -ge 32 ] || invalid LATIFI_JWT_SECRET "shorter than 32 characters (use openssl rand -hex 32)"
[ "$AUTH_DOMAIN" = "$HOST" ] || invalid LATIFI_AUTH_DOMAIN "must equal --host"
[ "$AUTH_URI" = "https://$HOST" ] || invalid LATIFI_AUTH_URI "must be https:// followed by --host"

case "$RPC_URL" in
  https://*) ;;
  *) invalid LATIFI_RPC_URL "must start with https://" ;;
esac
rpc_rest="$(printf '%s' "${RPC_URL#https://}" | tr 'A-Z' 'a-z')"
rpc_host="$(dk_url_host "$RPC_URL" | tr 'A-Z' 'a-z')"
case "$rpc_host" in
  *mainnet* | polygon-rpc.com) invalid LATIFI_RPC_URL "names a mainnet host, this project runs on Polygon Amoy only" ;;
esac
case "${rpc_rest#*/}" in
  polygon | polygon/*) invalid LATIFI_RPC_URL "points at the Polygon mainnet path of its provider, this project runs on Amoy only" ;;
esac

printf '%s' "$LOANBOOK" | grep -Eq '^0x[0-9a-fA-F]{40}$' || invalid LATIFI_LOANBOOK_ADDRESS "expected 0x followed by 40 hex digits"
if [ -f "$AMOY_MANIFEST" ]; then
  expected="$(jq -r '.contracts.LoanBook.address // empty' "$AMOY_MANIFEST" 2> /dev/null | tr 'A-F' 'a-f')"
  if [ -n "$expected" ] && [ "$expected" != "$(printf '%s' "$LOANBOOK" | tr 'A-F' 'a-f')" ]; then
    invalid LATIFI_LOANBOOK_ADDRESS "differs from the LoanBook address of the Amoy deployment manifest"
  fi
fi

printf '%s' "$START_BLOCK" | grep -Eq '^[0-9]+$' || invalid LATIFI_INDEXER_START_BLOCK "digits only"
[ "${#START_BLOCK}" -le 15 ] || invalid LATIFI_INDEXER_START_BLOCK "too long to be a block number"
[ "$START_BLOCK" -ge "$DEPLOYMENT_BLOCK" ] || invalid LATIFI_INDEXER_START_BLOCK "below the deployment block $DEPLOYMENT_BLOCK"
if [ -n "$BATCH_SIZE" ]; then
  printf '%s' "$BATCH_SIZE" | grep -Eq '^[0-9]{1,3}$' || invalid LATIFI_INDEXER_BATCH_SIZE "digits only, 1 to 500"
  { [ "$BATCH_SIZE" -ge 1 ] && [ "$BATCH_SIZE" -le 500 ]; } || invalid LATIFI_INDEXER_BATCH_SIZE "must be between 1 and 500"
fi

[ "$TRUST_XFF" = "true" ] || invalid LATIFI_TRUST_FORWARDED_FOR "must be true behind Traefik"
[ "$FWD_STRATEGY" = "framework" ] || invalid SERVER_FORWARD_HEADERS_STRATEGY "must be framework"
[ "$SWAGGER" = "true" ] || invalid LATIFI_SWAGGER_ENABLED "must be true, Swagger UI is part of the public surface"

# ---- the plan ---------------------------------------------------------------------------------

print_plan() {
  echo "  project:      $PROJECT"
  echo "  application: $APP"
  if [ -n "$GITHUB_ID" ]; then
    echo "  source:      GitHub App provider $GITHUB_ID, $REPO_URL, branch $BRANCH, trigger push"
  else
    echo "  source:      Git URL $REPO_URL, branch $BRANCH, no push webhook (redeploy by hand)"
  fi
  echo "  build:       dockerfile Dockerfile, context ."
  echo "  domain:      $HOST -> container port 8080, https, letsencrypt"
  echo "  environment (12 keys):"
  echo "    SPRING_DATASOURCE_URL=$DB_URL"
  echo "    SPRING_DATASOURCE_USERNAME=$DB_USER"
  echo "    SPRING_DATASOURCE_PASSWORD=***"
  echo "    LATIFI_JWT_SECRET=***"
  echo "    LATIFI_AUTH_DOMAIN=$AUTH_DOMAIN"
  echo "    LATIFI_AUTH_URI=$AUTH_URI"
  echo "    LATIFI_TRUST_FORWARDED_FOR=$TRUST_XFF"
  echo "    SERVER_FORWARD_HEADERS_STRATEGY=$FWD_STRATEGY"
  echo "    LATIFI_RPC_URL=https://$(dk_url_host "$RPC_URL")/***"
  echo "    LATIFI_LOANBOOK_ADDRESS=$LOANBOOK"
  echo "    LATIFI_INDEXER_START_BLOCK=$START_BLOCK"
  echo "    LATIFI_SWAGGER_ENABLED=$SWAGGER"
  [ -z "$BATCH_SIZE" ] || echo "    LATIFI_INDEXER_BATCH_SIZE=$BATCH_SIZE"
  if [ "$DEPLOY" -eq 1 ]; then
    echo "  deploy:      queued at the end"
  else
    echo "  deploy:      not queued (add --deploy)"
  fi
}

if [ "$APPLY" -ne 1 ]; then
  echo "provision: dry run, no network call and no token read. Add --apply to execute."
  print_plan
  echo "provision: dry run ok"
  exit 0
fi

# ---- apply ------------------------------------------------------------------------------------

dk_load_env "$ENV_FILE"
echo "provision: applying to the panel at $(dk_url_host "$DOKPLOY_URL")"

ENV_TEXT=""
for k in $KEYS; do
  ENV_TEXT="$ENV_TEXT$k=$(val "$k")
"
done
[ -z "$BATCH_SIZE" ] || ENV_TEXT="${ENV_TEXT}LATIFI_INDEXER_BATCH_SIZE=$BATCH_SIZE
"
ENV_TEXT="${ENV_TEXT%$'\n'}"

projects="$(dk_api GET project.all)" || exit 4
PROJECT_ID="$(printf '%s' "$projects" | jq -r --arg n "$PROJECT" '[.[] | select(.name == $n)][0].projectId // empty' 2> /dev/null)"
ENV_ID=""
if [ -n "$PROJECT_ID" ]; then
  ENV_ID="$(printf '%s' "$projects" | jq -r --arg p "$PROJECT_ID" '
    [.[] | select(.projectId == $p)][0].environments // []
    | (map(select(.isDefault == true))[0] // map(select(.name == "production"))[0] // .[0]).environmentId // empty' 2> /dev/null)"
  [ -n "$ENV_ID" ] || dk_die 4 "project $PROJECT exists but has no environment"
  echo "provision: project $PROJECT exists"
else
  body="$(jq -nc --arg n "$PROJECT" '{name: $n, description: "LatiFi API and event indexer"}')"
  created="$(dk_api POST project.create "$body")" || exit 4
  PROJECT_ID="$(printf '%s' "$created" | jq -r '.project.projectId // empty' 2> /dev/null)"
  ENV_ID="$(printf '%s' "$created" | jq -r '.environment.environmentId // empty' 2> /dev/null)"
  [ -n "$PROJECT_ID" ] && [ -n "$ENV_ID" ] || dk_die 4 "project.create answered without a project id or an environment id"
  echo "provision: project $PROJECT created"
fi

APP_ID="$(printf '%s' "$projects" | jq -r --arg e "$ENV_ID" --arg n "$APP" '
  [.[] | .environments[]? | select(.environmentId == $e) | .applications[]? | select(.name == $n)][0].applicationId // empty' 2> /dev/null)"
if [ -n "$APP_ID" ]; then
  echo "provision: application $APP exists"
  one="$(dk_api GET "application.one?applicationId=$APP_ID")" || exit 4
  APP_NAME="$(printf '%s' "$one" | jq -r '.appName // empty' 2> /dev/null)"
else
  body="$(jq -nc --arg n "$APP" --arg e "$ENV_ID" '{name: $n, description: "Spring Boot API with the embedded indexer", environmentId: $e}')"
  created="$(dk_api POST application.create "$body")" || exit 4
  APP_ID="$(printf '%s' "$created" | jq -r '.applicationId // empty' 2> /dev/null)"
  APP_NAME="$(printf '%s' "$created" | jq -r '.appName // empty' 2> /dev/null)"
  [ -n "$APP_ID" ] || dk_die 4 "application.create answered without an application id"
  echo "provision: application $APP created"
fi

# The source. Both calls are updates, so running them again applies a changed branch or URL.
if [ -n "$GITHUB_ID" ]; then
  owner="$(printf '%s' "$REPO_URL" | sed -E 's#^https://github\.com/([^/]+)/([^/]+)$#\1#')"
  repo="$(printf '%s' "$REPO_URL" | sed -E 's#^https://github\.com/([^/]+)/([^/]+)$#\2#; s#\.git$##')"
  body="$(jq -nc --arg a "$APP_ID" --arg o "$owner" --arg r "$repo" --arg g "$GITHUB_ID" --arg b "$BRANCH" \
    '{applicationId: $a, repository: $r, owner: $o, buildPath: "/", githubId: $g, branch: $b, triggerType: "push", enableSubmodules: false, watchPaths: []}')"
  dk_api POST application.saveGithubProvider "$body" > /dev/null || exit 4
  echo "provision: source set to the GitHub App provider"
else
  body="$(jq -nc --arg a "$APP_ID" --arg u "$REPO_URL" --arg b "$BRANCH" \
    '{applicationId: $a, customGitUrl: $u, customGitBranch: $b, customGitBuildPath: "/", enableSubmodules: false, watchPaths: []}')"
  dk_api POST application.saveGitProvider "$body" > /dev/null || exit 4
  echo "provision: source set to the Git URL"
fi

body="$(jq -nc --arg a "$APP_ID" \
  '{applicationId: $a, buildType: "dockerfile", dockerfile: "Dockerfile", dockerContextPath: ".", dockerBuildStage: null, herokuVersion: "24", railpackVersion: "0.15.4", publishDirectory: null, isStaticSpa: false}')"
dk_api POST application.saveBuildType "$body" > /dev/null || exit 4
echo "provision: build type set to dockerfile"

# The environment goes through stdin so no value shows up in argv.
body="$(printf '%s' "$ENV_TEXT" | jq -Rsc --arg a "$APP_ID" '{applicationId: $a, env: ., buildArgs: "", buildSecrets: "", createEnvFile: true}')"
dk_api POST application.saveEnvironment "$body" > /dev/null || exit 4
echo "provision: runtime environment set (12 keys)"

domains="$(dk_api GET "domain.byApplicationId?applicationId=$APP_ID")" || exit 4
if printf '%s' "$domains" | jq -e --arg h "$HOST" 'map(select(.host == $h)) | length > 0' > /dev/null 2>&1; then
  echo "provision: domain $HOST exists"
else
  body="$(jq -nc --arg a "$APP_ID" --arg h "$HOST" \
    '{host: $h, path: "/", port: 8080, https: true, applicationId: $a, certificateType: "letsencrypt", domainType: "application", stripPath: false}')"
  dk_api POST domain.create "$body" > /dev/null || exit 4
  echo "provision: domain $HOST created"
fi

if [ "$DEPLOY" -eq 1 ]; then
  body="$(jq -nc --arg a "$APP_ID" '{applicationId: $a, title: "Deploy from provision.sh"}')"
  dk_api POST application.deploy "$body" > /dev/null || exit 4
  echo "provision: deploy queued at $(dk_now)"
fi

dk_state_put projectId "$PROJECT_ID"
dk_state_put environmentId "$ENV_ID"
dk_state_put applicationId "$APP_ID"
dk_state_put appName "$APP_NAME"
dk_state_put host "$HOST"
echo "provision: state written to $DK_STATE_FILE"
echo "provision: applied"
exit 0
