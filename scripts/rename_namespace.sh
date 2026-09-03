#!/usr/bin/env bash
#
# Replaces the placeholder company namespace throughout the monorepo.
#
# The repository ships with neutral identifiers so it can be read and reviewed before a company
# name is chosen. This script performs the one-time rename, including the directory moves that a
# find-and-replace on its own would miss.
#
# Usage:
#   scripts/rename_namespace.sh <short> <android-package> <ios-module> <flutter-package>
#
# Example:
#   scripts/rename_namespace.sh ary com.ary.push ARYPush ary_push
#
# Run it on a clean checkout, review the diff, and commit in one go.

set -euo pipefail

if [[ $# -ne 4 ]]; then
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

SHORT="$1"
ANDROID_PACKAGE="$2"
IOS_MODULE="$3"
FLUTTER_PACKAGE="$4"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ANDROID_PATH="${ANDROID_PACKAGE//.//}"
MAVEN_GROUP="${ANDROID_PACKAGE%.*}"

echo "Renaming in $ROOT"
echo "  com.company.push      -> $ANDROID_PACKAGE"
echo "  CompanyPush           -> $IOS_MODULE"
echo "  company_push          -> $FLUTTER_PACKAGE"
echo "  com.company           -> $MAVEN_GROUP (Maven group)"
echo "  company-push          -> $SHORT-push (Maven artifact)"
echo "  push-api.company.com  -> push-api.$SHORT.com (documentation host)"
echo

if [[ -d .git ]] && ! git diff --quiet; then
    echo "Working tree is dirty. Commit or stash first." >&2
    exit 1
fi

# ---------------------------------------------------------------- text

# Longest identifiers first, so a shorter one cannot corrupt a longer match.
mapfile -t FILES < <(
    find . -type f \
        \( -name '*.kt' -o -name '*.kts' -o -name '*.java' -o -name '*.swift' \
           -o -name '*.dart' -o -name '*.gradle' -o -name '*.xml' -o -name '*.yaml' \
           -o -name '*.yml' -o -name '*.podspec' -o -name '*.plist' -o -name '*.md' \
           -o -name '*.pro' -o -name '*.properties' -o -name '*.pbxproj' \
           -o -name '*.xcscheme' -o -name '*.xcconfig' -o -name '*.entitlements' \
           -o -name 'Podfile' -o -name '*.sh' -o -name '.gitignore' \) \
        -not -path './.git/*' -not -path '*/build/*' -not -path '*/.dart_tool/*' \
        -not -path '*/Pods/*' -not -path './scripts/rename_namespace.sh'
)

for file in "${FILES[@]}"; do
    sed -i \
        -e "s/com\.company\.push/$ANDROID_PACKAGE/g" \
        -e "s/CompanyPush/$IOS_MODULE/g" \
        -e "s/companyPush/${SHORT}Push/g" \
        -e "s/company_push/$FLUTTER_PACKAGE/g" \
        -e "s/com\.company\b/$MAVEN_GROUP/g" \
        -e "s/company-push/$SHORT-push/g" \
        -e "s/push-api\.company\.com/push-api.$SHORT.com/g" \
        -e "s/push-api-\([a-z]*\)\.company\.com/push-api-\1.$SHORT.com/g" \
        -e "s/companyMaven/${SHORT}Maven/g" \
        -e "s/COMPANY_MAVEN/$(echo "$SHORT" | tr '[:lower:]' '[:upper:]')_MAVEN/g" \
        "$file"
done
echo "Rewrote ${#FILES[@]} files."

# ---------------------------------------------------------------- directories

# Every Kotlin/Java package directory, wherever it lives: the SDK, its tests, the Flutter
# bridge, the Android samples and the generated Flutter example sources. Collected first so the
# moves cannot disturb the traversal.
mapfile -d '' PACKAGE_DIRS < <(
    find . -type d -path '*/com/company/push' \
        -not -path './.git/*' -not -path '*/build/*' -print0
)

for dir in "${PACKAGE_DIRS[@]:-}"; do
    [[ -n "$dir" && -d "$dir" ]] || continue
    parent="${dir%/com/company/push}"
    target="$parent/$ANDROID_PATH"
    [[ "$dir" != "$target" ]] || continue
    mkdir -p "$(dirname "$target")"
    if [[ -d .git ]]; then git mv "$dir" "$target"; else mv "$dir" "$target"; fi
    echo "Moved $dir -> $target"
    rmdir -p "$(dirname "$dir")" 2>/dev/null || true
done

move() {
    local from="$1" to="$2"
    [[ -e "$from" && "$from" != "$to" ]] || return 0
    mkdir -p "$(dirname "$to")"
    if [[ -d .git ]]; then git mv "$from" "$to"; else mv "$from" "$to"; fi
    echo "Moved $from -> $to"
}

move ios/Sources/CompanyPush "ios/Sources/$IOS_MODULE"
move ios/Tests/CompanyPushTests "ios/Tests/${IOS_MODULE}Tests"
move ios/CompanyPush.podspec "ios/$IOS_MODULE.podspec"
move flutter/ios/company_push.podspec "flutter/ios/$FLUTTER_PACKAGE.podspec"
move flutter/lib/company_push.dart "flutter/lib/$FLUTTER_PACKAGE.dart"
move flutter/lib/src/company_push.dart "flutter/lib/src/$FLUTTER_PACKAGE.dart"

echo
echo "Done. Review the diff, then:"
echo "  scripts/verify.sh"
echo
echo "Anything left over will show up here:"
grep -rIl --exclude-dir=.git --exclude-dir=build --exclude-dir=.dart_tool \
    -e 'CompanyPush' -e 'company_push' -e 'com\.company' . 2>/dev/null | sed 's/^/  /' || true
