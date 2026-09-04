#!/usr/bin/env bash
#
# Makes every example in this repository build before a release tag exists.
#
# WHY THIS EXISTS
#
# The examples deliberately consume the SDK the way a real application does: the Android samples
# resolve com.github.arysoftware:ary-push-sdk from JitPack, and the Flutter examples resolve the
# ary_push package from git. Neither needs a credential — the repository is public — but both
# need a release tag that JitPack has built.
#
# Until that tag exists, this points the examples at the working tree instead, explicitly and
# reversibly.
#
#     scripts/dev_offline_examples.sh            # point the examples at this working tree
#     scripts/dev_offline_examples.sh --undo     # put them back on the published SDK
#
# Everything this writes is git-ignored, so it can never end up in a commit.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

FLUTTER_EXAMPLES=(
    "$ROOT/flutter/example"
    "$ROOT/flutter/example-firebase"
)

undo() {
    for example in "${FLUTTER_EXAMPLES[@]}"; do
        if [ -f "$example/pubspec_overrides.yaml" ]; then
            rm -f "$example/pubspec_overrides.yaml"
            echo "  removed $(basename "$example")/pubspec_overrides.yaml"
        fi
    done

    if [ -d "$ROOT/android/build/local-maven" ]; then
        rm -rf "$ROOT/android/build/local-maven"
        echo "  removed android/build/local-maven"
    fi

    echo
    echo "The examples are back on the published SDK. They will build once a release"
    echo "tag exists and JitPack has built it; no credentials are involved."
}

if [ "${1:-}" = "--undo" ]; then
    echo "Restoring the examples to the published SDK"
    undo
    exit 0
fi

# ---------------------------------------------------------------------------
# Android: publish a real artifact to a local Maven repository.
#
# A local repository rather than a project(":sdk") dependency on purpose: substituting the
# project would skip POM generation and AAR packaging, so the samples would stop exercising the
# thing an application actually consumes. android/settings.gradle.kts picks this directory up
# automatically once it exists, and always prefers a genuinely published artifact over it.
# ---------------------------------------------------------------------------
echo "Publishing the Android SDK to android/build/local-maven"
(
    cd "$ROOT/android"
    ./gradlew --quiet :sdk:publishReleasePublicationToLocalStagingRepository
)

GROUP="$(sed -n 's/^aryPush\.group=//p' "$ROOT/android/gradle.properties")"
ARTIFACT="$(sed -n 's/^aryPush\.artifact=//p' "$ROOT/android/gradle.properties")"
VERSION="$(sed -n 's/^aryPush\.version=//p' "$ROOT/android/gradle.properties")"
echo "  published ${GROUP}:${ARTIFACT}:${VERSION}"

# ---------------------------------------------------------------------------
# Flutter: override the git dependency with a path to this working tree.
#
# pubspec_overrides.yaml is the mechanism pub provides for exactly this. It takes precedence
# over dependencies declared in pubspec.yaml, and it is git-ignored, so pubspec.yaml keeps
# describing how a real application consumes the SDK.
# ---------------------------------------------------------------------------
for example in "${FLUTTER_EXAMPLES[@]}"; do
    cat > "$example/pubspec_overrides.yaml" <<'OVERRIDE'
# Written by scripts/dev_offline_examples.sh. Git-ignored; never part of a release.
#
# Points this example at the plugin in the working tree instead of the published package, so it
# builds with no GitHub access. Remove it, or run `scripts/dev_offline_examples.sh --undo`, to go
# back to resolving the published SDK.
dependency_overrides:
  ary_push:
    path: ../
OVERRIDE
    echo "  wrote $(basename "$example")/pubspec_overrides.yaml"
done

echo
echo "Done. The examples now build from this working tree:"
echo
echo "  cd android && ./gradlew :sample-basic:assembleDebug"
echo "  cd flutter/example && flutter pub get && flutter run"
echo
echo "Run 'scripts/dev_offline_examples.sh --undo' to put them back on the published SDK."
