#!/usr/bin/env bash
# Prints the GHCR tags for the current ref, one full tag per line.
#
# Reads IMAGE (lowercase, default ghcr.io/arquitectura-de-softwares-emergentes/latifi-api),
# GITHUB_REF and GITHUB_SHA from the environment.
#
#   always                  IMAGE:sha-<first 7 characters of the sha>
#   refs/heads/main         IMAGE:main
#   refs/tags/vX.Y.Z        IMAGE:X.Y.Z, IMAGE:X.Y and IMAGE:latest
#
# Any other ref, an uppercase image name, a malformed tag or a short sha prints nothing on stdout,
# explains on stderr and exits 2, so nothing is ever published from a feature branch.

set -u
export LC_ALL=C

IMAGE="${IMAGE:-ghcr.io/arquitectura-de-softwares-emergentes/latifi-api}"
REF="${GITHUB_REF:-}"
SHA="${GITHUB_SHA:-}"

refuse() {
  echo "image-tags: $*" >&2
  exit 2
}

case "$IMAGE" in
  *[A-Z]*) refuse "the image name must be lowercase: $IMAGE" ;;
esac

if ! printf '%s' "$SHA" | grep -Eq '^[0-9a-f]{7,40}$'; then
  refuse "GITHUB_SHA must be 7 to 40 lowercase hex characters"
fi
SHORT="$(printf '%s' "$SHA" | cut -c1-7)"

TAGS="$IMAGE:sha-$SHORT"

case "$REF" in
  refs/heads/main)
    TAGS="$TAGS
$IMAGE:main"
    ;;
  refs/tags/v*)
    VERSION="${REF#refs/tags/v}"
    if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
      refuse "the tag must look like vMAJOR.MINOR.PATCH: $REF"
    fi
    MINOR="${VERSION%.*}"
    TAGS="$TAGS
$IMAGE:$VERSION
$IMAGE:$MINOR
$IMAGE:latest"
    ;;
  *)
    refuse "ref not allowed to publish: ${REF:-empty}"
    ;;
esac

printf '%s\n' "$TAGS"
