#!/usr/bin/env bash
#
# Points the whole repository at its real GitHub home.
#
# The SDK ships with a placeholder owner and repository name. Everything that has to name the
# repository - podspecs, Package.swift documentation, Gradle defaults, the integration guide,
# the examples - is rewritten here in one pass, so the URL is never half-updated.
#
# Usage:
#   scripts/set_repository.sh <owner> [repo]
#
# Example:
#   scripts/set_repository.sh arysoftware ary-push-sdk
#
# Run it once, review the diff, commit.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

OWNER="$1"
REPO="${2:-ary-push-sdk}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The current home is read from the iOS podspec, which is the one file that always spells it out
# in full. Deriving it beats hardcoding a "previous" value that goes stale after the first run.
CURRENT="$(sed -n "s|.*github\.com/\([A-Za-z0-9_.-]*\)/\([A-Za-z0-9_.-]*\)'.*|\1/\2|p" \
    ios/ARYPush.podspec | head -1)"
OLD_OWNER="${CURRENT%%/*}"
OLD_REPO="${CURRENT##*/}"
OLD_OWNER="${OLD_OWNER:-ary}"
OLD_REPO="${OLD_REPO:-ary-push-sdk}"

if [[ "$OLD_OWNER/$OLD_REPO" == "$OWNER/$REPO" ]]; then
    echo "Already pointed at $OWNER/$REPO. Nothing to do."
    exit 0
fi

echo "Repository home"
echo "  owner: $OLD_OWNER -> $OWNER"
echo "  repo:  $OLD_REPO -> $REPO"
echo

if [[ -d .git ]] && ! git diff --quiet; then
    echo "Working tree is dirty. Commit or stash first." >&2
    exit 1
fi

mapfile -t FILES < <(
    find . -type f \
        \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' -o -name '*.dart' \
           -o -name '*.gradle' -o -name '*.yaml' -o -name '*.yml' -o -name '*.podspec' \
           -o -name '*.md' -o -name '*.properties' -o -name 'Podfile' \) \
        -not -path './.git/*' -not -path '*/build/*' -not -path '*/.dart_tool/*' \
        -not -path './scripts/set_repository.sh'
)

for file in "${FILES[@]}"; do
    sed -i \
        -e "s|github\.com/$OLD_OWNER/$OLD_REPO|github.com/$OWNER/$REPO|g" \
        -e "s|github\.com:$OLD_OWNER/$OLD_REPO|github.com:$OWNER/$REPO|g" \
        -e "s|maven\.pkg\.github\.com/$OLD_OWNER/$OLD_REPO|maven.pkg.github.com/$OWNER/$REPO|g" \
        -e "s|getOrElse(\"$OLD_OWNER\")|getOrElse(\"$OWNER\")|g" \
        -e "s|getOrElse(\"$OLD_REPO\")|getOrElse(\"$REPO\")|g" \
        "$file"
done

echo "Rewrote ${#FILES[@]} files."
echo
echo "Still naming the old home (should be empty):"
grep -rIn --exclude-dir=.git --exclude-dir=build --exclude-dir=.dart_tool \
    --exclude=set_repository.sh "$OLD_OWNER/$OLD_REPO" . 2>/dev/null | sed 's/^/  /' || true

if [[ -d .git ]]; then
    echo
    echo "Updating the git remote:"
    git remote set-url origin "https://github.com/$OWNER/$REPO.git" 2>/dev/null \
        || git remote add origin "https://github.com/$OWNER/$REPO.git"
    git remote -v | sed 's/^/  /'
fi

echo
echo "Review with 'git diff', then commit."
