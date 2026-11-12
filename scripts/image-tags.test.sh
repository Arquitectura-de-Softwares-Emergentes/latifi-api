#!/usr/bin/env bash
# Offline table test for image-tags.sh. Exits 1 on the first mismatch.

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/image-tags.sh"
IMG="ghcr.io/arquitectura-de-softwares-emergentes/latifi-api"
SHA="0123456789abcdef0123456789abcdef01234567"
COUNT=0

# check NAME EXPECTED_EXIT EXPECTED_STDOUT IMAGE REF SHA
check() {
  name="$1"
  want_code="$2"
  want_out="$3"
  image="$4"
  ref="$5"
  sha="$6"
  out="$(IMAGE="$image" GITHUB_REF="$ref" GITHUB_SHA="$sha" bash "$SCRIPT" 2> /dev/null)"
  code=$?
  if [ "$code" -ne "$want_code" ] || [ "$out" != "$want_out" ]; then
    echo "image-tags-test: FAIL $name" >&2
    echo "  exit: got $code, want $want_code" >&2
    echo "  stdout: got [$out]" >&2
    echo "  stdout: want [$want_out]" >&2
    exit 1
  fi
  COUNT=$((COUNT + 1))
}

NL='
'

check "main" 0 "$IMG:sha-0123456${NL}$IMG:main" "$IMG" "refs/heads/main" "$SHA"
check "release tag" 0 "$IMG:sha-0123456${NL}$IMG:1.2.3${NL}$IMG:1.2${NL}$IMG:latest" "$IMG" "refs/tags/v1.2.3" "$SHA"
check "zero version tag" 0 "$IMG:sha-0123456${NL}$IMG:0.1.0${NL}$IMG:0.1${NL}$IMG:latest" "$IMG" "refs/tags/v0.1.0" "$SHA"
check "tag with a fourth part" 2 "" "$IMG" "refs/tags/v1.2.3.4" "$SHA"
check "tag with a suffix" 2 "" "$IMG" "refs/tags/v1.2.3-rc1" "$SHA"
check "tag without v" 2 "" "$IMG" "refs/tags/1.2.3" "$SHA"
check "feature branch" 2 "" "$IMG" "refs/heads/feature/deployment" "$SHA"
check "develop branch" 2 "" "$IMG" "refs/heads/develop" "$SHA"
check "pull request ref" 2 "" "$IMG" "refs/pull/7/merge" "$SHA"
check "empty ref" 2 "" "$IMG" "" "$SHA"
check "uppercase image" 2 "" "ghcr.io/Arquitectura/latifi-api" "refs/heads/main" "$SHA"
check "short sha" 2 "" "$IMG" "refs/heads/main" "abc"
check "uppercase sha" 2 "" "$IMG" "refs/heads/main" "0123456789ABCDEF0123456789ABCDEF01234567"
check "seven character sha" 0 "$IMG:sha-abcdef0${NL}$IMG:main" "$IMG" "refs/heads/main" "abcdef0"

echo "image-tags-test: ok $COUNT cases"
