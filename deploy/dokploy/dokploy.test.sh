#!/usr/bin/env bash
# Offline tests of the Dokploy kit. No network, no Docker, no real token. A stub curl and a stub
# openssl on PATH answer from canned files, record argv and report whether the key header arrived
# on stdin. Every secret in here is an obvious canary, and the suite asserts that no canary ever
# reaches stdout, stderr or argv.
#
# Usage: bash deploy/dokploy/dokploy.test.sh
# Prints `dokploy-test: ok N cases` and exits 0, or names the first failing case and exits 1.
# Bash 3.2, needs jq, perl and openssl (the real one, to make test certificates).

set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_PATH="$PATH"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

CANARY_TOKEN="CANARYTOKEN0000DOKPLOYXYZ1234"
CANARY_JWT="canaryjwtsecret0123456789abcdef0123456789"
CANARY_DB="CanaryDbPass12345"
CANARY_RPC_KEY="CANARYRPCKEY1234567890abcd"
CANARY_RPC="https://amoy-node.canary.test/v2/$CANARY_RPC_KEY"
LOANBOOK="0xCcB8aD15A4954138c987a77924ceAb468a843266"
HOST="api.latifi.test"

CASES=0
ALL="$T/all-output.log"
: > "$ALL"

fail() {
  echo "dokploy-test: FAIL case $((CASES + 1)): $*" >&2
  if [ -f "$T/out" ]; then
    echo "--- stdout ---" >&2
    head -c 1500 "$T/out" >&2
  fi
  if [ -f "$T/err" ]; then
    echo "--- stderr ---" >&2
    head -c 1500 "$T/err" >&2
  fi
  exit 1
}

ok() {
  CASES=$((CASES + 1))
}

# ---- the stub curl and the stub openssl -----------------------------------------------------------

mkdir -p "$T/bin"
cat > "$T/bin/curl" << 'STUB'
#!/usr/bin/env bash
# Stub curl. Answers from $STUB_DIR/routes/<key>.{body,code,redirect,verify,ctype,exit}.
D="$STUB_DIR"
mkdir -p "$D/routes"
printf '%s\n' "$*" >> "$D/argv.log"
method="GET"; out=""; fmt=""; url=""; data=""; cfg=""; failflag=0; use_cfg=0
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -w) fmt="$2"; shift 2 ;;
    -d | --data | --data-binary) data="$2"; shift 2 ;;
    -H | -m | --max-time | --connect-timeout | --resolve | --cacert | --url) shift 2 ;;
    -K) use_cfg=1; shift 2 ;;
    --fail-with-body | -f | --fail) failflag=1; shift ;;
    -I) method="HEAD"; shift ;;
    http://* | https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
if [ "$use_cfg" = 1 ]; then
  cfg="$(cat)"
  if printf '%s\n' "$cfg" | grep -q "^header = \"x-api-key: ${STUB_EXPECT_KEY:-NONE}\"\$"; then
    echo yes >> "$D/keyhdr.log"
  else
    echo no >> "$D/keyhdr.log"
  fi
  cu="$(printf '%s\n' "$cfg" | sed -n 's/^url = "\(.*\)"$/\1/p' | head -n 1)"
  if [ -z "$url" ] && [ -n "$cu" ]; then url="$cu"; fi
  cd_="$(printf '%s\n' "$cfg" | grep '^data = "' | head -n 1 | perl -pe 's/^data = "//; s/"\s*$//; s/\\(.)/$1/g')"
  if [ -n "$cd_" ]; then data="$cd_"; fi
fi
rkey="$(printf '%s_%s' "$method" "${url%%\?*}" | tr -c 'A-Za-z0-9' '_')"
m="$(printf '%s' "$data" | sed -n 's/.*"method":"\([A-Za-z_]*\)".*/\1/p')"
if [ -n "$m" ]; then rkey="RPC_$m"; fi
rel="${url#*/api/}"
if [ "$rel" = "$url" ]; then rel="$url"; fi
printf '%s\t%s\t%s\n' "$method" "$rel" "$data" >> "$D/calls.log"
R="$D/routes/$rkey"
if [ -f "$R.exit" ]; then exit "$(cat "$R.exit")"; fi
if [ -f "$R.body" ]; then
  body="$(cat "$R.body")"; code=200
  if [ -f "$R.code" ]; then code="$(cat "$R.code")"; fi
else
  body='{"message":"no route"}'; code=404
fi
redirect=""; if [ -f "$R.redirect" ]; then redirect="$(cat "$R.redirect")"; fi
verify=0; if [ -f "$R.verify" ]; then verify="$(cat "$R.verify")"; fi
ctype="application/json"; if [ -f "$R.ctype" ]; then ctype="$(cat "$R.ctype")"; fi
if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
if [ -n "$fmt" ]; then
  esc() { printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }
  printf '%s' "$fmt" | sed \
    -e "s|%{http_code}|$(esc "$code")|g" \
    -e "s|%{redirect_url}|$(esc "$redirect")|g" \
    -e "s|%{ssl_verify_result}|$(esc "$verify")|g" \
    -e "s|%{content_type}|$(esc "$ctype")|g" \
    -e "s|%{url_effective}|$(esc "$url")|g"
fi
if [ "$failflag" = 1 ] && [ "$code" -ge 400 ]; then exit 22; fi
exit 0
STUB
chmod +x "$T/bin/curl"

cat > "$T/bin/openssl" << 'STUB'
#!/usr/bin/env bash
# Stub openssl. s_client prints the certificate chosen by the test, everything else is the real tool.
case "$*" in
  *s_client*)
    if [ -f "$STUB_DIR/cert.pem" ]; then
      cat "$STUB_DIR/cert.pem"
      exit 0
    fi
    exit 1
    ;;
esac
exec "$STUB_REAL_OPENSSL" "$@"
STUB
chmod +x "$T/bin/openssl"
STUB_REAL_OPENSSL="$(command -v openssl)"
export STUB_REAL_OPENSSL

# ---- fixtures -------------------------------------------------------------------------------------

default_for() {
  case "$1" in
    SPRING_DATASOURCE_URL) echo "jdbc:postgresql://shared-postgres:5432/latifi" ;;
    SPRING_DATASOURCE_USERNAME) echo "latifi" ;;
    SPRING_DATASOURCE_PASSWORD) echo "$CANARY_DB" ;;
    LATIFI_JWT_SECRET) echo "$CANARY_JWT" ;;
    LATIFI_AUTH_DOMAIN) echo "$HOST" ;;
    LATIFI_AUTH_URI) echo "https://$HOST" ;;
    LATIFI_TRUST_FORWARDED_FOR) echo "true" ;;
    SERVER_FORWARD_HEADERS_STRATEGY) echo "framework" ;;
    LATIFI_RPC_URL) echo "$CANARY_RPC" ;;
    LATIFI_LOANBOOK_ADDRESS) echo "$LOANBOOK" ;;
    LATIFI_INDEXER_START_BLOCK) echo "51700000" ;;
    LATIFI_SWAGGER_ENABLED) echo "true" ;;
  esac
}

KEYS="SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD LATIFI_JWT_SECRET LATIFI_AUTH_DOMAIN LATIFI_AUTH_URI LATIFI_TRUST_FORWARDED_FOR SERVER_FORWARD_HEADERS_STRATEGY LATIFI_RPC_URL LATIFI_LOANBOOK_ADDRESS LATIFI_INDEXER_START_BLOCK LATIFI_SWAGGER_ENABLED"

# write_app_env [KEY=VALUE | -KEY]... writes the runtime file with canary defaults and overrides.
write_app_env() {
  local k v a skip
  : > "$LATIFI_HOME/latifi-api.env"
  for k in $KEYS; do
    v="$(default_for "$k")"
    skip=0
    for a in "$@"; do
      case "$a" in
        "$k="*) v="${a#*=}" ;;
        "-$k") skip=1 ;;
      esac
    done
    if [ "$skip" -eq 0 ]; then
      printf '%s=%s\n' "$k" "$v" >> "$LATIFI_HOME/latifi-api.env"
    fi
  done
  chmod 600 "$LATIFI_HOME/latifi-api.env"
}

# reset starts a clean case: new home, stub directory and manifest.
reset() {
  rm -rf "${T:?}/home" "${T:?}/stub"
  mkdir -p "$T/home/.latifi" "$T/stub/routes"
  export LATIFI_HOME="$T/home/.latifi"
  export STUB_DIR="$T/stub"
  export STUB_EXPECT_KEY="$CANARY_TOKEN"
  export LATIFI_AMOY_MANIFEST="$T/amoy.json"
  export PATH="$T/bin:$REAL_PATH"
  printf '{"contracts":{"LoanBook":{"address":"0xccb8ad15a4954138c987a77924ceab468a843266"}}}\n' > "$T/amoy.json"
  printf 'DOKPLOY_URL=http://127.0.0.1:3000\nDOKPLOY_TOKEN=%s\n' "$CANARY_TOKEN" > "$LATIFI_HOME/dokploy.env"
  chmod 600 "$LATIFI_HOME/dokploy.env"
  write_app_env
}

# route METHOD URL BODY [CODE] teaches the stub curl one answer.
route() {
  local key
  key="$(printf '%s_%s' "$1" "$2" | tr -c 'A-Za-z0-9' '_')"
  printf '%s' "$3" > "$STUB_DIR/routes/$key.body"
  if [ -n "${4:-}" ]; then printf '%s' "$4" > "$STUB_DIR/routes/$key.code"; fi
}

PANEL="http://127.0.0.1:3000/api"

# run CMD... captures stdout, stderr and the exit status in RC, and keeps a copy for the leak scan.
run() {
  RC=0
  "$@" > "$T/out" 2> "$T/err" < /dev/null || RC=$?
  cat "$T/out" "$T/err" >> "$ALL"
}

expect_rc() {
  [ "$RC" -eq "$1" ] || fail "$2: expected exit $1, got $RC"
}

expect_err() {
  grep -q -- "$1" "$T/err" || fail "$2: stderr does not mention '$1'"
}

body_of() {
  # body_of PATH prints the JSON body recorded for the first call to that path.
  awk -F '\t' -v p="$1" '$2 == p { print $3; exit }' "$STUB_DIR/calls.log"
}

PROVISION="$HERE/provision.sh"
START_BLOCK="$HERE/start-block.sh"

# ---- task 1: provisioning ------------------------------------------------------------------------

# 1. the example file holds no value and all twelve keys.
if grep -Eq '^[A-Z_]+=.+' "$HERE/app.env.example"; then fail "app.env.example carries a value"; fi
n="$(grep -Ec '^[A-Z_]+=$' "$HERE/app.env.example")"
[ "$n" -eq 12 ] || fail "app.env.example has $n keys, expected 12"
reset
for k in $KEYS; do grep -q "^$k=\$" "$HERE/app.env.example" || fail "app.env.example lacks $k"; done
ok

# 2. the unfilled example is refused with exit 2 and names the first key.
reset
cp "$HERE/app.env.example" "$LATIFI_HOME/latifi-api.env"
chmod 600 "$LATIFI_HOME/latifi-api.env"
run bash "$PROVISION" --host "$HOST"
expect_rc 2 "unfilled example"
expect_err SPRING_DATASOURCE_URL "unfilled example"
ok

# 3. the dry run makes zero curl calls, needs no token file and prints no secret.
reset
rm -f "$LATIFI_HOME/dokploy.env"
run bash "$PROVISION" --host "$HOST"
expect_rc 0 "dry run"
[ ! -f "$STUB_DIR/calls.log" ] || fail "dry run called curl"
[ ! -f "$STUB_DIR/argv.log" ] || fail "dry run called curl"
grep -q 'provision: dry run ok' "$T/out" || fail "dry run did not finish"
grep -q 'SPRING_DATASOURCE_PASSWORD=\*\*\*' "$T/out" || fail "dry run does not mask the password"
grep -q 'LATIFI_JWT_SECRET=\*\*\*' "$T/out" || fail "dry run does not mask the signing secret"
grep -q 'https://amoy-node.canary.test/\*\*\*' "$T/out" || fail "dry run does not show the node host only"
ok

# 4 to 21: every invalid value exits 2 and names the key.
check_invalid() {
  # check_invalid KEY_EXPECTED OVERRIDE...
  local expect="$1"
  shift
  reset
  write_app_env "$@"
  run bash "$PROVISION" --host "$HOST"
  expect_rc 2 "invalid $expect"
  expect_err "$expect" "invalid $expect"
  [ ! -f "$STUB_DIR/calls.log" ] || fail "invalid $expect reached the network"
  ok
}
check_invalid LATIFI_RPC_URL -LATIFI_RPC_URL
check_invalid LATIFI_JWT_SECRET "LATIFI_JWT_SECRET=short31charsecret0123456789abcd"
check_invalid LATIFI_AUTH_DOMAIN "LATIFI_AUTH_DOMAIN=other.latifi.test"
check_invalid LATIFI_AUTH_URI "LATIFI_AUTH_URI=http://$HOST"
check_invalid LATIFI_RPC_URL "LATIFI_RPC_URL=http://amoy-node.canary.test/v2/$CANARY_RPC_KEY"
check_invalid LATIFI_RPC_URL "LATIFI_RPC_URL=https://polygon-mainnet.canary.test/v2/$CANARY_RPC_KEY"
check_invalid LATIFI_RPC_URL "LATIFI_RPC_URL=https://rpc.canary.test/polygon/$CANARY_RPC_KEY"
check_invalid LATIFI_LOANBOOK_ADDRESS "LATIFI_LOANBOOK_ADDRESS=0x1234"
check_invalid LATIFI_LOANBOOK_ADDRESS "LATIFI_LOANBOOK_ADDRESS=0x1111111111111111111111111111111111111111"
check_invalid LATIFI_INDEXER_START_BLOCK "LATIFI_INDEXER_START_BLOCK=51675535"
check_invalid LATIFI_INDEXER_START_BLOCK "LATIFI_INDEXER_START_BLOCK=latest"
check_invalid LATIFI_TRUST_FORWARDED_FOR "LATIFI_TRUST_FORWARDED_FOR=false"
check_invalid SERVER_FORWARD_HEADERS_STRATEGY "SERVER_FORWARD_HEADERS_STRATEGY=native"
check_invalid LATIFI_SWAGGER_ENABLED "LATIFI_SWAGGER_ENABLED=false"
check_invalid SPRING_DATASOURCE_USERNAME 'SPRING_DATASOURCE_USERNAME=la"tifi'
check_invalid SPRING_DATASOURCE_USERNAME "SPRING_DATASOURCE_USERNAME=la tifi"
check_invalid SPRING_DATASOURCE_PASSWORD "SPRING_DATASOURCE_PASSWORD=Canary/Pass12345"
check_invalid SPRING_DATASOURCE_URL "SPRING_DATASOURCE_URL=jdbc:postgresql://shared-postgres:5432/latifi?password=x"

# 22. a loose mode on the runtime file is refused.
reset
chmod 644 "$LATIFI_HOME/latifi-api.env"
run bash "$PROVISION" --host "$HOST"
expect_rc 2 "loose runtime file mode"
expect_err "mode" "loose runtime file mode"
ok

# 23. a loose mode on the token file is refused under --apply, with no call.
reset
chmod 644 "$LATIFI_HOME/dokploy.env"
run bash "$PROVISION" --host "$HOST" --apply
expect_rc 2 "loose token file mode"
[ ! -f "$STUB_DIR/calls.log" ] || fail "loose token file reached the network"
ok

# 24. a plain http panel URL that is not loopback is refused under --apply, with no call.
reset
printf 'DOKPLOY_URL=http://panel.canary.test:3000\nDOKPLOY_TOKEN=%s\n' "$CANARY_TOKEN" > "$LATIFI_HOME/dokploy.env"
run bash "$PROVISION" --host "$HOST" --apply
expect_rc 2 "plain http panel"
expect_err "clear text" "plain http panel"
[ ! -f "$STUB_DIR/calls.log" ] || fail "plain http panel reached the network"
ok

# 25. an https panel is accepted (the stub answers it) and loopback http too (every other case).
reset
printf 'DOKPLOY_URL=https://panel.canary.test\nDOKPLOY_TOKEN=%s\n' "$CANARY_TOKEN" > "$LATIFI_HOME/dokploy.env"
route GET "https://panel.canary.test/api/project.all" '[]'
run bash "$PROVISION" --host "$HOST" --apply
[ -f "$STUB_DIR/calls.log" ] || fail "https panel was not called"
ok

# 26. apply on an empty Dokploy: creates, in order, with the expected fields.
empty_dokploy() {
  route GET "$PANEL/project.all" '[]'
  route POST "$PANEL/project.create" '{"project":{"projectId":"p1"},"environment":{"environmentId":"e1"}}'
  route POST "$PANEL/application.create" '{"applicationId":"a1","appName":"app-latifi-xyz"}'
  route POST "$PANEL/application.saveGitProvider" 'true'
  route POST "$PANEL/application.saveGithubProvider" 'true'
  route POST "$PANEL/application.saveBuildType" 'true'
  route POST "$PANEL/application.saveEnvironment" 'true'
  route GET "$PANEL/domain.byApplicationId" '[]'
  route POST "$PANEL/domain.create" '{"domainId":"d1"}'
  route POST "$PANEL/application.deploy" 'true'
}
reset
empty_dokploy
run bash "$PROVISION" --host "$HOST" --apply
expect_rc 0 "apply on empty"
grep -q '^provision: applied$' "$T/out" || fail "apply did not finish"
posts="$(awk -F '\t' '$1 == "POST" { print $2 }' "$STUB_DIR/calls.log" | tr '\n' ' ')"
want="project.create application.create application.saveGitProvider application.saveBuildType application.saveEnvironment domain.create "
[ "$posts" = "$want" ] || fail "mutation order was '$posts'"
b="$(body_of project.create)"
[ "$(printf '%s' "$b" | jq -r .name)" = "latifi" ] || fail "project.create name"
b="$(body_of application.create)"
[ "$(printf '%s' "$b" | jq -r '.name + "/" + .environmentId')" = "latifi-api/e1" ] || fail "application.create fields"
b="$(body_of application.saveGitProvider)"
printf '%s' "$b" | jq -e '.applicationId == "a1" and .customGitBranch == "main" and .customGitBuildPath == "/" and .enableSubmodules == false and .watchPaths == [] and (.customGitUrl | endswith("/latifi-api.git"))' > /dev/null || fail "saveGitProvider fields: $b"
b="$(body_of application.saveBuildType)"
printf '%s' "$b" | jq -e '.buildType == "dockerfile" and .dockerfile == "Dockerfile" and .dockerContextPath == "." and .dockerBuildStage == null and .herokuVersion == "24" and .railpackVersion == "0.15.4" and .publishDirectory == null and .isStaticSpa == false' > /dev/null || fail "saveBuildType fields: $b"
b="$(body_of application.saveEnvironment)"
printf '%s' "$b" | jq -e '.applicationId == "a1" and .buildArgs == "" and .buildSecrets == "" and .createEnvFile == true and (.env | split("\n") | length == 12) and (.env | contains("LATIFI_AUTH_DOMAIN=api.latifi.test")) and (.env | contains("LATIFI_JWT_SECRET='"$CANARY_JWT"'"))' > /dev/null || fail "saveEnvironment fields"
b="$(body_of domain.create)"
printf '%s' "$b" | jq -e '.host == "api.latifi.test" and .path == "/" and .port == 8080 and .https == true and .certificateType == "letsencrypt" and .domainType == "application" and .stripPath == false and .applicationId == "a1"' > /dev/null || fail "domain.create fields: $b"
grep -q 'application.deploy' "$STUB_DIR/calls.log" && fail "deploy was queued without --deploy"
[ "$(sort -u "$STUB_DIR/keyhdr.log")" = "yes" ] || fail "a call went out without the key header on stdin"
[ "$(jq -r .applicationId "$LATIFI_HOME/dokploy-state.json")" = "a1" ] || fail "state file lacks the application id"
mode="$(stat -c '%a' "$LATIFI_HOME/dokploy-state.json" 2> /dev/null || stat -f '%Lp' "$LATIFI_HOME/dokploy-state.json")"
[ "$mode" = "600" ] || fail "state file mode is $mode"
ok

# 27. no canary reached argv, and the state file holds ids only.
for c in "$CANARY_TOKEN" "$CANARY_JWT" "$CANARY_DB" "$CANARY_RPC_KEY"; do
  if grep -q -- "$c" "$STUB_DIR/argv.log"; then fail "a canary reached curl argv"; fi
  if grep -q -- "$c" "$LATIFI_HOME/dokploy-state.json"; then fail "a canary reached the state file"; fi
done
ok

# 28. --deploy queues the deploy last.
reset
empty_dokploy
run bash "$PROVISION" --host "$HOST" --apply --deploy
expect_rc 0 "apply with deploy"
last="$(awk -F '\t' '$1 == "POST" { p = $2 } END { print p }' "$STUB_DIR/calls.log")"
[ "$last" = "application.deploy" ] || fail "last mutation was $last"
ok

# 29. a second apply against a Dokploy that has everything issues no create call.
reset
route GET "$PANEL/project.all" '[{"projectId":"p1","name":"latifi","environments":[{"environmentId":"e1","isDefault":true,"name":"production","applications":[{"applicationId":"a1","name":"latifi-api","applicationStatus":"done"}]}]}]'
route GET "$PANEL/application.one" '{"applicationId":"a1","appName":"app-latifi-xyz","applicationStatus":"done"}'
route POST "$PANEL/application.saveGitProvider" 'true'
route POST "$PANEL/application.saveBuildType" 'true'
route POST "$PANEL/application.saveEnvironment" 'true'
route GET "$PANEL/domain.byApplicationId" "[{\"domainId\":\"d1\",\"host\":\"$HOST\"}]"
run bash "$PROVISION" --host "$HOST" --apply
expect_rc 0 "second apply"
for forbidden in project.create application.create domain.create application.deploy; do
  if awk -F '\t' '$1 == "POST" { print $2 }' "$STUB_DIR/calls.log" | grep -qx "$forbidden"; then
    fail "second apply called $forbidden"
  fi
done
grep -q '^provision: domain api.latifi.test exists$' "$T/out" || fail "second apply did not see the domain"
ok

# 30. --github-id uses the GitHub provider with a push trigger.
reset
empty_dokploy
run bash "$PROVISION" --host "$HOST" --apply --github-id gh123 --branch release/1
expect_rc 0 "github provider"
b="$(body_of application.saveGithubProvider)"
printf '%s' "$b" | jq -e '.owner == "Arquitectura-de-Softwares-Emergentes" and .repository == "latifi-api" and .githubId == "gh123" and .triggerType == "push" and .branch == "release/1" and .watchPaths == [] and .buildPath == "/"' > /dev/null || fail "saveGithubProvider fields: $b"
grep -q 'saveGitProvider' "$STUB_DIR/calls.log" && fail "both providers were saved"
ok

# 31. an API error exits 4 with the status and no secret, even when the answer echoes one.
reset
empty_dokploy
route POST "$PANEL/application.saveEnvironment" "{\"message\":\"bad env $CANARY_JWT and $CANARY_DB\"}" 400
run bash "$PROVISION" --host "$HOST" --apply
expect_rc 4 "api error"
expect_err 'HTTP 400' "api error"
ok

# ---- task 1: start block --------------------------------------------------------------------------

rpc_setup() {
  # rpc_setup CHAIN_HEX HEAD_DECIMAL
  reset
  printf 'AMOY_RPC_URL=%s\n' "$CANARY_RPC" > "$LATIFI_HOME/rpc.env"
  chmod 600 "$LATIFI_HOME/rpc.env"
  printf '%s' "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"$1\"}" > "$STUB_DIR/routes/RPC_eth_chainId.body"
  printf '%s' "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"$(printf '0x%x' "$2")\"}" > "$STUB_DIR/routes/RPC_eth_blockNumber.body"
}

# 32. head minus the default margin.
rpc_setup 0x13882 51676000
run bash "$START_BLOCK"
expect_rc 0 "start block"
[ "$(head -n 1 "$T/out")" = "LATIFI_INDEXER_START_BLOCK=51675950" ] || fail "start block answer: $(cat "$T/out")"
grep -q 'amoy-node.canary.test' "$T/err" || fail "start block does not name the node host"
if grep -q -- "$CANARY_RPC_KEY" "$STUB_DIR/argv.log"; then fail "the node key reached curl argv"; fi
ok

# 33. never below the deployment block.
rpc_setup 0x13882 51675560
run bash "$START_BLOCK"
expect_rc 0 "start block clamp"
[ "$(head -n 1 "$T/out")" = "LATIFI_INDEXER_START_BLOCK=51675536" ] || fail "clamp answer: $(cat "$T/out")"
ok

# 34. a chain other than 80002 exits 3.
rpc_setup 0x1 51676000
run bash "$START_BLOCK"
expect_rc 3 "wrong chain"
expect_err "wrong network" "wrong chain"
ok

# 35. a node behind the deployment block exits 3.
rpc_setup 0x13882 1000
run bash "$START_BLOCK"
expect_rc 3 "behind node"
ok

# 36. --first-event-block prints that value with no network call, and clamps a value below the deployment block.
rpc_setup 0x13882 51676000
run bash "$START_BLOCK" --first-event-block 51680123
expect_rc 0 "first event block"
[ "$(head -n 1 "$T/out")" = "LATIFI_INDEXER_START_BLOCK=51680123" ] || fail "first event answer"
[ ! -f "$STUB_DIR/calls.log" ] || fail "first event block called the node"
run bash "$START_BLOCK" --first-event-block 100
[ "$(head -n 1 "$T/out")" = "LATIFI_INDEXER_START_BLOCK=51675536" ] || fail "first event clamp"
ok

# 37. a missing node URL exits 3.
reset
run bash "$START_BLOCK"
expect_rc 3 "no node url"
ok

# ---- task 2: live smoke check ---------------------------------------------------------------------

SMOKE="$HERE/smoke-live.sh"
OPS="$HERE/ops.sh"
INDEXER="$HERE/indexer-status.sh"
ORIGIN="https://$HOST"

mkdir -p "$T/certs"
"$STUB_REAL_OPENSSL" req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "$T/certs/key.pem" -out "$T/certs/good.pem" -days 90 -subj "/CN=$HOST" > /dev/null 2>&1 \
  || { echo "dokploy-test: could not make a test certificate" >&2; exit 1; }
"$STUB_REAL_OPENSSL" req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "$T/certs/key2.pem" -out "$T/certs/short.pem" -days 3 -subj "/CN=$HOST" > /dev/null 2>&1 \
  || { echo "dokploy-test: could not make a test certificate" >&2; exit 1; }

route_key() {
  printf '%s_%s' "$1" "$2" | tr -c 'A-Za-z0-9' '_'
}

# attr METHOD URL NAME VALUE sets a side file of a route (redirect, verify, ctype, exit, code).
attr() {
  printf '%s' "$4" > "$STUB_DIR/routes/$(route_key "$1" "$2").$3"
}

CHALLENGE_OK="$(jq -nc --arg msg "$HOST wants you to sign in with your Ethereum account:
0x52908400098527886E0F7030069857D2E4169EE7

Sign in to LatiFi. This request does not trigger a blockchain transaction or cost any fee.

URI: $ORIGIN
Version: 1
Chain ID: 80002" '{challengeId: "3f2b8c1e-5d4a-4c7b-9a10-2e6f7d8c9b01", nonce: "9c1d4f7a2b8e4d3f5a6b7c8d9e0f1a2b", message: $msg}')"

smoke_setup() {
  reset
  cp "$T/certs/good.pem" "$STUB_DIR/cert.pem"
  route GET "http://$HOST/actuator/health" '' 301
  attr GET "http://$HOST/actuator/health" redirect "$ORIGIN/actuator/health"
  route GET "$ORIGIN/actuator/health" '{"status":"UP"}'
  route GET "$ORIGIN/swagger-ui.html" '<html><title>Swagger UI</title><div id="swagger-ui"></div></html>'
  attr GET "$ORIGIN/swagger-ui.html" ctype "text/html"
  route GET "$ORIGIN/v3/api-docs" "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"LatiFi API\"},\"servers\":[{\"url\":\"$ORIGIN\"}]}"
  route GET "$ORIGIN/api/v1/loans" '{"code":"UNAUTHENTICATED"}' 401
  route GET "$ORIGIN/actuator/env" '{"status":404}' 404
  route GET "$ORIGIN/actuator/beans" '{"status":404}' 404
  route POST "$ORIGIN/api/v1/auth/challenge" "$CHALLENGE_OK"
}

smoke_fail() {
  # smoke_fail CHECK: the smoke run must exit 1 and name the check.
  run bash "$SMOKE" "$ORIGIN"
  expect_rc 1 "smoke $1"
  expect_err "FAIL $1" "smoke $1"
  if grep -q '^smoke-live: ok' "$T/out"; then fail "smoke $1 printed ok"; fi
  ok
}

# healthy origin
smoke_setup
run bash "$SMOKE" "$ORIGIN" --evidence "$T/evidence.json"
expect_rc 0 "healthy smoke"
[ "$(tail -n 1 "$T/out")" = "smoke-live: ok $ORIGIN" ] || fail "the last line is not the ok line"
jq -e --arg h "$HOST" '.host == $h and .status.health == "200" and .status.protectedRoute == "401" and .status.httpRedirect == "301" and (.certificateNotAfter | length > 0) and (.checkedAtUtc | endswith("Z"))' "$T/evidence.json" > /dev/null || fail "evidence content"
ok

# bad usage
smoke_setup
run bash "$SMOKE" "http://$HOST"
expect_rc 2 "http origin"
ok

smoke_setup
route GET "http://$HOST/actuator/health" '{"status":"UP"}' 200
smoke_fail redirect

smoke_setup
attr GET "http://$HOST/actuator/health" redirect "https://other.latifi.test/actuator/health"
smoke_fail redirect

smoke_setup
attr GET "$ORIGIN/actuator/health" verify 19
smoke_fail certificate

smoke_setup
attr GET "$ORIGIN/actuator/health" exit 60
smoke_fail certificate

smoke_setup
cp "$T/certs/short.pem" "$STUB_DIR/cert.pem"
smoke_fail certificate-expiry

smoke_setup
route GET "$ORIGIN/actuator/health" '{"status":"DOWN"}' 503
smoke_fail health

smoke_setup
route GET "$ORIGIN/actuator/health" '{"status":"DOWN"}'
smoke_fail health

smoke_setup
route GET "$ORIGIN/actuator/health" '{"status":"UP","components":{"db":{"status":"UP"}}}'
smoke_fail health

smoke_setup
attr GET "$ORIGIN/actuator/health" ctype "text/html"
smoke_fail health

smoke_setup
route GET "$ORIGIN/swagger-ui.html" 'not found' 404
smoke_fail swagger-ui

smoke_setup
route GET "$ORIGIN/swagger-ui.html" '<html>a page without the viewer</html>'
smoke_fail swagger-ui

smoke_setup
route GET "$ORIGIN/v3/api-docs" 'gone' 404
smoke_fail openapi

smoke_setup
route GET "$ORIGIN/v3/api-docs" "{\"info\":{\"title\":\"Other API\"},\"servers\":[{\"url\":\"$ORIGIN\"}]}"
smoke_fail openapi

smoke_setup
route GET "$ORIGIN/v3/api-docs" '{"info":{"title":"LatiFi API"},"servers":[{"url":"http://localhost:8080"}]}'
smoke_fail openapi

smoke_setup
route GET "$ORIGIN/api/v1/loans" '{"items":[]}' 200
smoke_fail protected-route

smoke_setup
route GET "$ORIGIN/api/v1/loans" 'Unauthorized' 401
smoke_fail protected-route

smoke_setup
route GET "$ORIGIN/actuator/env" '{"propertySources":[]}' 200
smoke_fail actuator-env

smoke_setup
route GET "$ORIGIN/actuator/beans" '{"contexts":{}}' 200
smoke_fail actuator-beans

smoke_setup
route POST "$ORIGIN/api/v1/auth/challenge" "$(printf '%s' "$CHALLENGE_OK" | sed "s/$HOST wants/localhost wants/")"
smoke_fail challenge

smoke_setup
route POST "$ORIGIN/api/v1/auth/challenge" "$(printf '%s' "$CHALLENGE_OK" | sed "s|URI: $ORIGIN|URI: http://localhost:8080|")"
smoke_fail challenge

smoke_setup
route POST "$ORIGIN/api/v1/auth/challenge" "$(printf '%s' "$CHALLENGE_OK" | sed 's/9c1d4f7a2b8e4d3f5a6b7c8d9e0f1a2b/short/')"
smoke_fail challenge

smoke_setup
route POST "$ORIGIN/api/v1/auth/challenge" '{"code":"INTERNAL"}' 500
smoke_fail challenge

# ---- task 2: operations wrapper -------------------------------------------------------------------

ops_setup() {
  reset
  jq -n '{applicationId: "a1", appName: "app-latifi-xyz"}' > "$LATIFI_HOME/dokploy-state.json"
  chmod 600 "$LATIFI_HOME/dokploy-state.json"
  route GET "$PANEL/application.one" '{"applicationId":"a1","appName":"app-latifi-xyz","applicationStatus":"done"}'
  route GET "$PANEL/docker.getContainersByAppNameMatch" '[{"containerId":"c0ffee1234","name":"app-latifi-xyz.1.abcdef","state":"running","status":"Up 2 minutes (healthy)"}]'
  route POST "$PANEL/application.stop" '{}'
  route POST "$PANEL/application.start" '{}'
  route POST "$PANEL/application.redeploy" 'true'
  route POST "$PANEL/docker.killContainer" 'true'
  route GET "$PANEL/application.readLogs" "$(jq -n --arg t "line one
node $CANARY_RPC key
secret $CANARY_JWT and $CANARY_DB
line four" '$t')"
}

ops_posts() {
  awk -F '\t' '$1 == "POST" { print $2 }' "$STUB_DIR/calls.log" | tr '\n' ' '
}

# status
ops_setup
run bash "$OPS" status
expect_rc 0 "ops status"
grep -q 'build status: done' "$T/out" || fail "status lacks the build status"
grep -q 'container: app-latifi-xyz.1.abcdef state=running status=Up 2 minutes (healthy)' "$T/out" || fail "status lacks the container line"
grep -Eq '^ops: status at 20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$' "$T/out" || fail "status lacks the UTC time"
grep -q 'appName=app-latifi-xyz' "$STUB_DIR/calls.log" || fail "status did not ask for the containers of the appName"
ok

# status with no container
ops_setup
route GET "$PANEL/docker.getContainersByAppNameMatch" '[]'
run bash "$OPS" status
expect_rc 0 "ops status without container"
grep -q 'none found' "$T/out" || fail "status does not say there is no container"
ok

# logs: tail, since, redaction through the runtime file and through the key pattern
ops_setup
run bash "$OPS" logs --tail 50
expect_rc 0 "ops logs"
grep -q 'tail=50&since=all' "$STUB_DIR/calls.log" || fail "logs did not send tail and since"
grep -q 'line one' "$T/out" && grep -q 'line four' "$T/out" || fail "logs lost lines"
grep -q 'ops: logs at 20' "$T/err" || fail "logs lack the UTC time"
for c in "$CANARY_JWT" "$CANARY_DB" "$CANARY_RPC_KEY"; do
  if grep -q -- "$c" "$T/out" "$T/err"; then fail "logs leaked a canary"; fi
done
ok

ops_setup
rm -f "$LATIFI_HOME/latifi-api.env"
route GET "$PANEL/application.readLogs" "$(jq -n --arg t "node $CANARY_RPC answered 429" '$t')"
run bash "$OPS" logs
expect_rc 0 "ops logs without the runtime file"
if grep -q -- "$CANARY_RPC_KEY" "$T/out"; then fail "the key pattern did not hide the node key"; fi
ok

# logs with a raw control character still read
ops_setup
printf '"before\001after"' > "$STUB_DIR/routes/$(route_key GET "$PANEL/application.readLogs").body"
run bash "$OPS" logs
expect_rc 0 "ops logs with a control character"
grep -q 'before' "$T/out" || fail "the control character broke the log reader"
ok

# stop, start, redeploy
ops_setup
for sub in stop start redeploy; do
  run bash "$OPS" "$sub"
  expect_rc 0 "ops $sub"
  grep -Eq "^ops: $sub (requested|queued) at 20" "$T/out" || fail "ops $sub lacks the UTC time"
done
[ "$(ops_posts)" = "application.stop application.start application.redeploy " ] || fail "ops posted $(ops_posts)"
for sub in stop start redeploy; do
  [ "$(awk -F '\t' -v p="application.$sub" '$2 == p { print $3; exit }' "$STUB_DIR/calls.log" | jq -r .applicationId)" = "a1" ] || fail "ops $sub body"
done
ok

# kill
ops_setup
run bash "$OPS" kill
expect_rc 0 "ops kill"
[ "$(ops_posts)" = "docker.killContainer " ] || fail "kill posted $(ops_posts)"
[ "$(body_of docker.killContainer | jq -r .containerId)" = "c0ffee1234" ] || fail "kill body"
grep -Eq '^ops: kill sent at 20' "$T/out" || fail "kill lacks the UTC time"
[ "$(sort -u "$STUB_DIR/keyhdr.log")" = "yes" ] || fail "an ops call went out without the key header on stdin"
if grep -q -- "$CANARY_TOKEN" "$STUB_DIR/argv.log"; then fail "the token reached curl argv in ops"; fi
ok

# kill degrades to the docker kill line when the API refuses
for refused in 404 403; do
  ops_setup
  route POST "$PANEL/docker.killContainer" '{"message":"not found"}' "$refused"
  run bash "$OPS" kill
  expect_rc 5 "ops kill refused $refused"
  grep -q 'docker kill app-latifi-xyz.1.abcdef' "$T/err" || fail "kill fallback lacks the docker kill line"
  ok
done

# kill with another error is an API error
ops_setup
route POST "$PANEL/docker.killContainer" '{"message":"boom"}' 500
run bash "$OPS" kill
expect_rc 4 "ops kill 500"
ok

# kill with nothing running
ops_setup
route GET "$PANEL/docker.getContainersByAppNameMatch" '[{"containerId":"dead","name":"app-latifi-xyz.1.old","state":"exited","status":"Exited (1)"}]'
run bash "$OPS" kill
expect_rc 3 "ops kill nothing running"
[ "$(ops_posts)" = "" ] || fail "kill posted with nothing running"
ok

# no state file
ops_setup
rm -f "$LATIFI_HOME/dokploy-state.json"
run bash "$OPS" status
expect_rc 3 "ops without state"
ok

# bad usage
ops_setup
run bash "$OPS" explode
expect_rc 2 "ops bad command"
run bash "$OPS" logs --tail 0
expect_rc 2 "ops bad tail"
ok

# ---- task 2: indexer status -----------------------------------------------------------------------

L_START="2026-11-12T10:00:00.000Z  INFO 1 --- [main] c.l.a.l.i.IndexerStartupReport : The indexer starts against node host amoy-node.canary.test, every PT5S, from block 51700000"
L_OFF="2026-11-12T10:00:00.000Z  WARN 1 --- [main] c.l.a.l.i.IndexerStartupReport : The indexer is enabled but latifi.chain.rpc-url is empty, so it stays off"
L_MIRROR="2026-11-12T10:01:00.000Z  INFO 1 --- [scheduling-1] c.l.a.l.i.IndexerScheduler : Indexer mirrored 3 new events up to block 51700123"
L_SKIP="2026-11-12T10:02:00.000Z  INFO 1 --- [scheduling-1] c.l.a.l.i.IndexerScheduler : Indexer cycle skipped: another instance holds the lock"
L_FAIL="2026-11-12T10:03:00.000Z  WARN 1 --- [scheduling-1] c.l.a.l.i.IndexerScheduler : Indexer cycle failed, the next one retries: rate limited by node amoy-node.canary.test"
L_NOISE="2026-11-12T10:00:30.000Z  INFO 1 --- [http-nio-8080-exec-1] c.l.a.SomeController : GET /api/v1/loans 200"

verdict() {
  # verdict EXPECTED_RC EXPECTED_WORD LINES...
  local want_rc="$1" word="$2" text
  shift 2
  ops_setup
  text="$(printf '%s\n' "$@")"
  route GET "$PANEL/application.readLogs" "$(jq -n --arg t "$text" '$t')"
  run bash "$INDEXER"
  expect_rc "$want_rc" "indexer verdict $word"
  [ "$(tail -n 1 "$T/out")" = "indexer: $word" ] || fail "verdict was '$(tail -n 1 "$T/out")', expected 'indexer: $word'"
  ok
}

verdict 0 running "$L_NOISE" "$L_START" "$L_MIRROR" "$L_SKIP"
verdict 0 running "$L_START"
verdict 0 running "$L_START" "$L_FAIL" "$L_MIRROR"
verdict 1 failing "$L_START" "$L_MIRROR" "$L_FAIL"
verdict 1 failing "$L_START" "$L_FAIL" "$L_SKIP" "$L_FAIL"
verdict 0 running "$L_START" "$L_FAIL" "$L_START"
verdict 1 off "$L_OFF"
verdict 1 off "$L_START" "$L_FAIL" "$L_OFF"
verdict 1 failing "$L_NOISE" "$L_MIRROR"
verdict 1 failing "$L_NOISE"

# the node key never shows in the verdict output
ops_setup
route GET "$PANEL/application.readLogs" "$(jq -n --arg t "$L_START
failed against $CANARY_RPC" '$t')"
run bash "$INDEXER"
if grep -q -- "$CANARY_RPC_KEY" "$T/out" "$T/err"; then fail "indexer-status leaked the node key"; fi
ok

# a log read that fails is passed on
ops_setup
route GET "$PANEL/application.readLogs" '{"message":"boom"}' 500
run bash "$INDEXER"
expect_rc 4 "indexer-status with a failing read"
ok


# ---- final leak scan ------------------------------------------------------------------------------

for c in "$CANARY_TOKEN" "$CANARY_JWT" "$CANARY_DB" "$CANARY_RPC_KEY"; do
  if grep -q -- "$c" "$ALL"; then
    echo "dokploy-test: FAIL a canary secret reached the output of a case" >&2
    exit 1
  fi
done
ok
echo "dokploy-test: ok $CASES cases"
exit 0
