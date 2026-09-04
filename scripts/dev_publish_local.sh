#!/usr/bin/env bash
#
# Publishes the Android SDK to a local Maven repository inside the monorepo.
#
# The artifact lands in android/build/local-maven, which both the Android samples
# (android/settings.gradle.kts) and the Flutter plugin bridge (flutter/android/build.gradle) pick
# up automatically once the directory exists — and which both prefer a genuinely published
# artifact over, so this can never mask a real "not published yet" failure.
#
# Use it to build the Android samples against a real AAR with no credentials:
#
#     scripts/dev_publish_local.sh
#     cd android && ./gradlew :sample-basic:assembleDebug
#
# To do the same for the Flutter examples as well, use scripts/dev_offline_examples.sh, which
# runs this and then points the examples at the working tree.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"

VERSION="$(sed -n 's/^aryPush\.version=//p' gradle.properties)"

echo "Publishing com.ary:ary-push:$VERSION to android/build/local-maven"
./gradlew :sdk:publishReleasePublicationToLocalStagingRepository

echo
echo "Published:"
find build/local-maven -name '*.aar' -o -name '*.pom' | sed 's/^/  /'
