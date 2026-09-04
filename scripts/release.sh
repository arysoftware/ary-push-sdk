#!/usr/bin/env bash
#
# Cuts a release: one version, one tag, three artifacts.
#
# Android, iOS and Flutter share a version number so that host applications only ever reason
# about one SDK version. This script keeps them in step.
#
# Usage:
#   scripts/release.sh 1.1.0
#   scripts/release.sh 1.1.0 --dry-run
#
# It does NOT publish. Pushing the tag is what triggers CI to build, test and publish, so a
# release artifact can never exist without a green build behind it.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
DRY_RUN="${2:-}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: scripts/release.sh <MAJOR.MINOR.PATCH> [--dry-run]" >&2
    exit 1
fi

say() { printf '\033[1m%s\033[0m\n' "$*"; }

edit() {
    local file="$1" expression="$2"
    if [[ "$DRY_RUN" == "--dry-run" ]]; then
        printf '   would edit %s\n' "$file"
    else
        sed -i -E "$expression" "$file"
        printf '   %s\n' "$file"
    fi
}

# ---------------------------------------------------------------- preconditions

say "Checking the working tree"
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Working tree is dirty. Commit or stash first." >&2
    exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "Tag v$VERSION already exists. Versions are immutable." >&2
    exit 1
fi

if ! grep -q "## \[$VERSION\]" CHANGELOG.md; then
    echo "CHANGELOG.md has no '## [$VERSION]' section. Write the entry before releasing." >&2
    exit 1
fi

# ---------------------------------------------------------------- version bump

say "Setting version $VERSION"
edit android/gradle.properties "s/^aryPush\.version=.*/aryPush.version=$VERSION/"
edit ios/Sources/ARYPush/Internal/DeviceInfoProvider.swift \
    "s/(static let sdkVersion = )\"[0-9.]+\"/\1\"$VERSION\"/"
edit ios/ARYPush.podspec "s/(s\.version[[:space:]]*=[[:space:]]*)'[0-9.]+'/\1'$VERSION'/"
edit flutter/pubspec.yaml "s/^version: .*/version: $VERSION/"
edit flutter/ios/ary_push.podspec "s/(s\.version[[:space:]]*=[[:space:]]*)'[0-9.]+'/\1'$VERSION'/"
edit flutter/android/build.gradle "s/^version = '[0-9.]+'/version = '$VERSION'/"

# ---------------------------------------------------------------- verify

say "Verifying"
if [[ "$DRY_RUN" == "--dry-run" ]]; then
    printf '   would run scripts/verify.sh\n'
else
    scripts/verify.sh
fi

# ---------------------------------------------------------------- tag

if [[ "$DRY_RUN" == "--dry-run" ]]; then
    say "Dry run complete. Nothing was written or tagged."
    exit 0
fi

say "Committing and tagging"
git add -A
git commit -m "Release v$VERSION"
git tag -a "v$VERSION" -m "ARY Push SDK v$VERSION"

cat <<EOF

Ready. Push when you are:

    git push origin HEAD
    git push origin v$VERSION

Pushing the tag triggers CI to build, test and publish:

  * the Android AAR to the private Maven repository
  * the Swift package, consumed straight from the tag
  * the Flutter plugin, consumed straight from the tag

Applications update by changing one version number.
EOF
