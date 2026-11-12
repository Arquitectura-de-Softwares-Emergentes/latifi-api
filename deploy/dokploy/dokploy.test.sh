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

calls() {
  [ -f "$STUB_DIR/calls.log" ] && cut -f2 "$STUB_DIR/calls.log" | sed 's/?.*//' | tr '\n' ' '
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

# @@SMOKE_AND_OPS@@

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
