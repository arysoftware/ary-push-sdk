#!/usr/bin/env bash
#
# Publishes the Android SDK to a local Maven repository inside the monorepo.
#
# OPTIONAL. The Flutter examples do not need this: their settings.gradle.kts includes
# android/sdk as a Gradle project and builds it from source.
#
# This exists to exercise the other resolution path, the one a real consuming application will
# use once the SDK is published to ARY's private Maven repository. Run it when you want to test
# against a built artifact rather than against source:
#
#     scripts/dev_publish_local.sh
#
# The artifact lands in android/build/local-maven, which the Flutter plugin picks up
# automatically when no local SDK project is included.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"

VERSION="$(sed -n 's/^aryPush\.version=//p' gradle.properties)"

echo "Publishing com.ary:ary-push:$VERSION to android/build/local-maven"
./gradlew :sdk:publishReleasePublicationToLocalStagingRepository

echo
echo "Published:"
find build/local-maven -name '*.aar' -o -name '*.pom' | sed 's/^/  /'
