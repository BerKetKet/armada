#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOWLIST="$REPO_ROOT/scripts/deps-allowlist.txt"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$ALLOWLIST" ]; then
    echo "ERROR: Allowlist file not found at $ALLOWLIST" >&2
    exit 1
fi

ERRORS=0

# 1. Scan every build.gradle.kts for dependency declarations
while IFS= read -r build_file; do
    # Match dependency configurations with string literals
    while IFS=: read -r line_num line_content; do
        # Extract configuration and coordinate
        # Example line: implementation("androidx.core:core-ktx:1.13.1")
        if [[ "$line_content" =~ (implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|androidTestImplementation)[[:space:]]*\([[:space:]]*[\"\']([^\"\']+)[\"\'] ]]; then
            config="${BASH_REMATCH[1]}"
            coord="${BASH_REMATCH[2]}"

            # Check against allowlist
            if ! grep -Fxq "$coord" "$ALLOWLIST"; then
                echo "ERROR: Disallowed dependency '$coord' found in $build_file:$line_num" >&2
                ERRORS=$((ERRORS + 1))
            fi

            # Check test-only constraints (e.g. JUnit must only be in test configurations)
            if [[ "$coord" =~ junit ]] && [[ ! "$config" =~ ^test ]]; then
                echo "ERROR: Test-only dependency '$coord' declared in non-test configuration '$config' in $build_file:$line_num" >&2
                ERRORS=$((ERRORS + 1))
            fi
        fi
    done < <(grep -n -E '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|androidTestImplementation)[[:space:]]*\([[:space:]]*["'\''][^"'\'']+["'\'']' "$build_file" || true)
done < <(find "$REPO_ROOT" -name "build.gradle.kts" -not -path "*/build/*")

# 2. Check built debug APK for androidx classes
if [ -f "$APK" ]; then
    # Look for androidx class descriptors in all dex files within the APK
    androidx_found="$(unzip -p "$APK" "*.dex" 2>/dev/null | strings | grep -E '^L?androidx/' | head -n 10 || true)"
    if [ -n "$androidx_found" ]; then
        echo "ERROR: Found androidx classes in $APK:" >&2
        echo "$androidx_found" >&2
        ERRORS=$((ERRORS + 1))
    fi
else
    echo "ERROR: Debug APK not found at $APK. Run './gradlew assembleDebug' before checking dependencies." >&2
    ERRORS=$((ERRORS + 1))
fi

if [ "$ERRORS" -gt 0 ]; then
    echo "FAILED: Dependency check found $ERRORS violation(s)." >&2
    exit 1
fi

echo "OK: Dependencies allowed and zero androidx classes in debug APK."
