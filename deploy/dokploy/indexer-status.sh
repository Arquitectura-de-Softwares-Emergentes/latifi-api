#!/usr/bin/env bash
# Reads the runtime log of latifi-api through ops.sh and says whether the embedded indexer works.
#
# Usage: indexer-status.sh [--env-file FILE] [--app-env-file FILE] [--tail N]
#   --tail N  log lines to read (default 5000, at most 10000)
#
# Prints the indexer lines of the current container start (the startup report, mirrored events, skipped
# and failed cycles) and then one verdict line:
#   indexer: running   a startup line exists and the last cycle line after it is not a failure
#   indexer: off       the startup line says the RPC URL is empty, so the indexer stays off
#   indexer: failing   no startup line was found, or the last cycle line is a failed cycle
# The application logs the node host only, never the URL, and the text goes through the redactor anyway.
# Exit codes: 0 running, 1 off or failing, and the exit code of ops.sh when it cannot read the log.
# Bash 3.2.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=()
TAIL=5000

usage() {
  sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file | --app-env-file)
      [ $# -ge 2 ] || usage
      PASS+=("$1" "$2")
      shift 2
      ;;
    --tail)
      [ $# -ge 2 ] || usage
      TAIL="$2"
      shift 2
      ;;
    *) usage ;;
  esac
done

LOG_FILE="$(mktemp)"
trap 'rm -f "$LOG_FILE"' EXIT

bash "$SCRIPT_DIR/ops.sh" ${PASS[@]+"${PASS[@]}"} logs --tail "$TAIL" > "$LOG_FILE" 2> /dev/null
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "indexer-status: could not read the log (ops.sh exit $rc)" >&2
  exit "$rc"
fi

# Lines of the indexer, in order. The patterns are the exact messages of IndexerStartupReport and
# IndexerScheduler.
LINES_FILE="$LOG_FILE.indexer"
grep -E 'The indexer starts against node host|The indexer is enabled but latifi.chain.rpc-url is empty|Indexer cycle skipped|Indexer mirrored [0-9]+ new events|Indexer cycle failed' "$LOG_FILE" > "$LINES_FILE"
trap 'rm -f "$LOG_FILE" "$LINES_FILE"' EXIT

# Keep only what follows the last startup line.
last_start="$(grep -nE 'The indexer starts against node host|The indexer is enabled but latifi.chain.rpc-url is empty' "$LINES_FILE" | tail -n 1 | cut -d: -f1)"
if [ -z "$last_start" ]; then
  echo "indexer: failing"
  echo "indexer-status: no startup line of the indexer in the last $TAIL log lines" >&2
  exit 1
fi
tail -n +"$last_start" "$LINES_FILE" | cut -c1-300
startup="$(sed -n "${last_start}p" "$LINES_FILE")"
case "$startup" in
  *"rpc-url is empty"*)
    echo "indexer: off"
    exit 1
    ;;
esac

last_cycle="$(tail -n +"$((last_start + 1))" "$LINES_FILE" | grep -E 'Indexer cycle skipped|Indexer mirrored|Indexer cycle failed' | tail -n 1)"
case "$last_cycle" in
  *"Indexer cycle failed"*)
    echo "indexer: failing"
    exit 1
    ;;
esac
echo "indexer: running"
exit 0
