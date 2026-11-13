#!/usr/bin/env bash
# Claude Code Stop hook: when Claude finishes a turn and Kotlin/Gradle files have changed
# (uncommitted, or committed but not yet pushed), run `./gradlew check`: ktlint, the compiler
# with warnings as errors, and the tests.
# On failure it exits with code 2, which makes Claude Code show the output to Claude and let it
# continue to fix the problem. If the check fails again on that follow-up stop, the hook only
# warns the user, so Claude can never be stuck in a loop.
set -u
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
input=$(cat)

changed=$( { git status --porcelain | cut -c4-; git diff --name-only '@{upstream}...HEAD' 2>/dev/null; } |
    grep -E '\.(kt|kts)$|^src/|^gradle/|^\.editorconfig$' )
[ -z "$changed" ] && exit 0

if output=$(./gradlew check -q --console=plain 2>&1); then
    exit 0
fi
summary=$(printf '%s\n' "$output" | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tail -n 60)

if printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
    msg="./gradlew check still fails after Claude's fix attempt; see ./gradlew check output."
    printf '{"systemMessage": "%s"}\n' "$msg"
    exit 0
fi
printf './gradlew check failed (ktlint, compiler warnings, or tests). Fix it; `./gradlew ktlintFormat` fixes most style findings.\n\n%s\n' "$summary" >&2
exit 2
