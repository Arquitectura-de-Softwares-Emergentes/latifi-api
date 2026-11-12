#!/usr/bin/env bash
# Prints the value for LATIFI_INDEXER_START_BLOCK: the current head of Polygon Amoy minus a margin,
# never below the LoanBook deployment block 51675536.
#
# Usage: start-block.sh [--margin N] [--first-event-block N]
#   --margin N             blocks to step back from the head (default 50)
#   --first-event-block N  print this block instead, for the case where loans already exist on chain.
#                          Makes no network call. Still never below the deployment block.
#
# The node URL comes from AMOY_RPC_URL or from ~/.latifi/rpc.env. It reaches curl through stdin, so it
# is not in argv, and it is never printed: the script names the host only.
# Exit codes: 0 ok, 2 bad usage, 3 the node is missing, unreachable or not Polygon Amoy (chain id 80002).

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

DEPLOYMENT_BLOCK=51675536
MARGIN=50
FIRST_EVENT=""

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --margin)
      [ $# -ge 2 ] || usage
      MARGIN="$2"
      shift 2
      ;;
    --first-event-block)
      [ $# -ge 2 ] || usage
      FIRST_EVENT="$2"
      shift 2
      ;;
    *) usage ;;
  esac
done

printf '%s' "$MARGIN" | grep -Eq '^[0-9]{1,6}$' || dk_die 2 "--margin must be a whole number up to 999999"
if [ -n "$FIRST_EVENT" ]; then
  printf '%s' "$FIRST_EVENT" | grep -Eq '^[0-9]{1,15}$' || dk_die 2 "--first-event-block must be a block number"
  if [ "$FIRST_EVENT" -lt "$DEPLOYMENT_BLOCK" ]; then
    echo "start-block: $FIRST_EVENT is below the deployment block, using $DEPLOYMENT_BLOCK" >&2
    FIRST_EVENT="$DEPLOYMENT_BLOCK"
  fi
  echo "LATIFI_INDEXER_START_BLOCK=$FIRST_EVENT"
  exit 0
fi

dk_need_tools curl jq

if [ -z "${AMOY_RPC_URL:-}" ] && [ -f "$LATIFI_HOME/rpc.env" ]; then
  dk_require_private_file "$LATIFI_HOME/rpc.env"
  AMOY_RPC_URL="$(dk_env_get "$LATIFI_HOME/rpc.env" AMOY_RPC_URL)"
fi
[ -n "${AMOY_RPC_URL:-}" ] || dk_die 3 "AMOY_RPC_URL is not set and $LATIFI_HOME/rpc.env does not define it"
printf '%s' "$AMOY_RPC_URL" | grep -Eq '^https://[^[:space:]"\\]+$' || dk_die 3 "AMOY_RPC_URL must be an https URL without spaces or quotes"
dk_add_secret "$AMOY_RPC_URL"
NODE_HOST="$(dk_url_host "$AMOY_RPC_URL")"

# rpc METHOD prints the result field of a JSON-RPC answer.
rpc() {
  local out
  out="$(printf 'url = "%s"\n' "$AMOY_RPC_URL" | curl -sS --max-time 20 -K - \
    -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":[]}" 2> /dev/null)" || return 1
  printf '%s' "$out" | jq -r '.result // empty' 2> /dev/null
}

chain="$(rpc eth_chainId)" || chain=""
[ -n "$chain" ] || dk_die 3 "could not read the chain id from $NODE_HOST"
if [ "$chain" != "0x13882" ]; then
  dk_die 3 "wrong network: $NODE_HOST reports chain id $chain, expected 0x13882 (80002, Polygon Amoy)"
fi

head_hex="$(rpc eth_blockNumber)" || head_hex=""
printf '%s' "$head_hex" | grep -Eq '^0x[0-9a-fA-F]{1,15}$' || dk_die 3 "could not read the block number from $NODE_HOST"
head=$((16#${head_hex#0x}))
if [ "$head" -lt "$DEPLOYMENT_BLOCK" ]; then
  dk_die 3 "the node at $NODE_HOST is at block $head, below the deployment block $DEPLOYMENT_BLOCK: it is behind or is not Amoy"
fi

start=$((head - MARGIN))
if [ "$start" -lt "$DEPLOYMENT_BLOCK" ]; then
  start="$DEPLOYMENT_BLOCK"
fi
echo "start-block: node $NODE_HOST, chain 80002, head $head, margin $MARGIN" >&2
echo "LATIFI_INDEXER_START_BLOCK=$start"
exit 0
