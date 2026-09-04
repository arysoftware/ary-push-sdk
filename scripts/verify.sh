#!/usr/bin/env bash
#
# Runs every check CI runs, locally.
#
# Usage:
#   scripts/verify.sh              all platforms available on this machine
#   scripts/verify.sh android      one platform: android | ios | flutter | secrets

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-all}"
FAILURES=()

section() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
skip()    { printf '   skipped: %s\n' "$1"; }
run() {
    local label="$1"; shift
    printf '   %s ... ' "$label"
    if "$@" >/tmp/verify.$$.log 2>&1; then
        printf 'ok\n'
    else
        printf 'FAILED\n'
        sed 's/^/      /' /tmp/verify.$$.log | tail -30
        FAILURES+=("$label")
    fi
    rm -f /tmp/verify.$$.log
}

# ---------------------------------------------------------------- android

if [[ "$TARGET" == "all" || "$TARGET" == "android" ]]; then
    section "Android"
    if [[ -x android/gradlew ]]; then
        run "unit tests" ./android/gradlew -p android :sdk:test
        run "lint"       ./android/gradlew -p android :sdk:lint
        run "assemble"   ./android/gradlew -p android :sdk:assembleRelease
    elif command -v gradle >/dev/null; then
        run "unit tests" gradle -p android :sdk:test
        run "lint"       gradle -p android :sdk:lint
    else
        skip "no Gradle wrapper or gradle on PATH"
    fi
fi

# ---------------------------------------------------------------- ios

if [[ "$TARGET" == "all" || "$TARGET" == "ios" ]]; then
    section "iOS"
    if command -v swift >/dev/null; then
        run "build" swift build --package-path ios
        run "tests" swift test --package-path ios
    else
        skip "swift not available (macOS only)"
    fi
fi

# ---------------------------------------------------------------- flutter

if [[ "$TARGET" == "all" || "$TARGET" == "flutter" ]]; then
    section "Flutter"
    if command -v flutter >/dev/null; then
        run "pub get"        flutter pub get --directory flutter
        run "analyze"        flutter analyze --no-pub flutter
        run "tests"          flutter test --no-pub flutter
        run "example pub"    flutter pub get --directory flutter/example
        run "example analyze" flutter analyze --no-pub flutter/example
    else
        skip "flutter not on PATH"
    fi
fi

# ---------------------------------------------------------------- secrets

if [[ "$TARGET" == "all" || "$TARGET" == "secrets" ]]; then
    section "Secret scan"

    check_absent() {
        local label="$1"; shift
        printf '   %s ... ' "$label"
        local hits
        hits=$(find . -type f \( "$@" \) -not -path './.git/*' -not -path '*/build/*' 2>/dev/null)
        if [[ -z "$hits" ]]; then
            printf 'ok\n'
        else
            printf 'FAILED\n'
            echo "$hits" | sed 's/^/      /'
            FAILURES+=("$label")
        fi
    }

    check_absent "no Firebase configuration" \
        -name 'google-services.json' -o -name 'GoogleService-Info.plist'
    check_absent "no signing keys or certificates" \
        -name '*.p8' -o -name '*.p12' -o -name '*.cer' -o -name '*.mobileprovision'

    printf '   no TLS bypass ... '
    # An SDK that ships an SSL bypass ships it to every application that embeds it.
    if grep -rInE 'TrustAllCerts|ALLOW_ALL_HOSTNAME|checkServerTrusted\s*\([^)]*\)\s*\{\s*\}|NSAllowsArbitraryLoads' \
        --include='*.kt' --include='*.swift' --include='*.java' --include='*.plist' \
        android/sdk/src ios/Sources flutter 2>/dev/null; then
        printf 'FAILED\n'
        FAILURES+=("no TLS bypass")
    else
        printf 'ok\n'
    fi
fi

# ---------------------------------------------------------------- result

printf '\n'
if [[ ${#FAILURES[@]} -eq 0 ]]; then
    printf '\033[32mAll checks passed.\033[0m\n'
    exit 0
fi
printf '\033[31mFailed: %s\033[0m\n' "${FAILURES[*]}"
exit 1
