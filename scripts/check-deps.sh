#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOWLIST="$REPO_ROOT/scripts/deps-allowlist.txt"
ALLOWED_PACKAGES_FILE="$REPO_ROOT/scripts/allowed-packages.txt"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

ERRORS=0

# Fail closed on missing configuration files
if [ ! -f "$ALLOWLIST" ]; then
    echo "ERROR: Allowlist file not found at $ALLOWLIST" >&2
    exit 1
fi

if [ ! -f "$ALLOWED_PACKAGES_FILE" ]; then
    echo "ERROR: Allowed packages file not found at $ALLOWED_PACKAGES_FILE" >&2
    exit 1
fi

# -----------------------------------------------------------------------------
# 1. Quick text scan of build.gradle.kts declarations
# -----------------------------------------------------------------------------
# Parse allowlist sections
RUNTIME_ALLOWED=()
TEST_ALLOWED=()
current_section=""
while IFS= read -r line || [ -n "$line" ]; do
    line="$(echo "$line" | sed 's/#.*//' | tr -d ' \r\t')"
    [ -z "$line" ] && continue
    if [ "$line" = "[runtime]" ]; then
        current_section="runtime"
    elif [ "$line" = "[test]" ]; then
        current_section="test"
    elif [ "$current_section" = "runtime" ]; then
        RUNTIME_ALLOWED+=("$line")
    elif [ "$current_section" = "test" ]; then
        TEST_ALLOWED+=("$line")
    fi
done < "$ALLOWLIST"

if [ ${#RUNTIME_ALLOWED[@]} -eq 0 ]; then
    echo "ERROR: Failed closed: [runtime] allowlist is empty in $ALLOWLIST" >&2
    exit 1
fi

while IFS= read -r build_file; do
    while IFS=: read -r line_num line_content; do
        if [[ "$line_content" =~ (implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|androidTestImplementation)[[:space:]]*\([[:space:]]*[\"\']([^\"\']+)[\"\'] ]]; then
            config="${BASH_REMATCH[1]}"
            coord="${BASH_REMATCH[2]}"

            in_runtime=0
            for a in "${RUNTIME_ALLOWED[@]}"; do
                if [ "$a" = "$coord" ]; then in_runtime=1; break; fi
            done

            in_test=0
            for a in "${TEST_ALLOWED[@]}"; do
                if [ "$a" = "$coord" ]; then in_test=1; break; fi
            done

            if [[ "$config" =~ ^test ]]; then
                if [ "$in_test" -eq 0 ] && [ "$in_runtime" -eq 0 ]; then
                    echo "ERROR [text scan]: Disallowed test dependency '$coord' found in $build_file:$line_num" >&2
                    ERRORS=$((ERRORS + 1))
                fi
            else
                if [ "$in_runtime" -eq 0 ]; then
                    echo "ERROR [text scan]: Disallowed runtime dependency '$coord' found in $build_file:$line_num" >&2
                    ERRORS=$((ERRORS + 1))
                fi
            fi
        fi
    done < <(grep -n -E '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|androidTestImplementation)[[:space:]]*\([[:space:]]*["'\''][^"'\'']+["'\'']' "$build_file" || true)
done < <(find "$REPO_ROOT" -name "build.gradle.kts" -not -path "*/build/*")

# -----------------------------------------------------------------------------
# 2. Resolved dependency graph check (authority)
# -----------------------------------------------------------------------------
check_graph() {
    local target="$1"
    local config="$2"
    local is_test="$3"

    local raw_output
    raw_output="$("$REPO_ROOT/gradlew" -q "$target:dependencies" --configuration "$config" 2>/dev/null || true)"
    if [ -z "$raw_output" ]; then
        echo "ERROR [graph check]: Failed closed: Gradle dependency report for $target ($config) produced no output" >&2
        ERRORS=$((ERRORS + 1))
        return
    fi

    local parsed_coords
    parsed_coords="$(echo "$raw_output" | awk '
        /--- [a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+/ {
            line = $0
            sub(/^[| \\+---]+/, "", line)
            sub(/[ \t]*\(\*\).*/, "", line)
            sub(/[ \t]*\([a-z]\).*/, "", line)
            if (line ~ / -> /) {
                split(line, parts, " -> ")
                split(parts[1], coord, ":")
                print coord[1] ":" coord[2] ":" parts[2]
            } else {
                split(line, parts, " ")
                print parts[1]
            }
        }
    ' | sort -u)"

    if [ -z "$parsed_coords" ]; then
        echo "ERROR [graph check]: Failed closed: parsed zero dependency coordinates for $target ($config)" >&2
        ERRORS=$((ERRORS + 1))
        return
    fi

    while IFS= read -r coord; do
        [ -z "$coord" ] && continue
        local allowed=0
        if [ "$is_test" -eq 1 ]; then
            for a in "${TEST_ALLOWED[@]}" "${RUNTIME_ALLOWED[@]}"; do
                if [ "$a" = "$coord" ]; then allowed=1; break; fi
            done
            if [ "$allowed" -eq 0 ]; then
                echo "ERROR [graph check]: Disallowed test graph coordinate '$coord' in $target ($config)" >&2
                ERRORS=$((ERRORS + 1))
            fi
        else
            for a in "${RUNTIME_ALLOWED[@]}"; do
                if [ "$a" = "$coord" ]; then allowed=1; break; fi
            done
            if [ "$allowed" -eq 0 ]; then
                echo "ERROR [graph check]: Disallowed runtime graph coordinate '$coord' in $target ($config)" >&2
                ERRORS=$((ERRORS + 1))
            fi
        fi
    done <<< "$parsed_coords"
}

check_graph ":app" "debugRuntimeClasspath" 0
check_graph ":daemon" "debugRuntimeClasspath" 0
check_graph ":cli" "debugRuntimeClasspath" 0
check_graph ":proto" "runtimeClasspath" 0
check_graph ":proto" "testRuntimeClasspath" 1

# -----------------------------------------------------------------------------
# 3. APK class & package scan via SDK dexdump
# -----------------------------------------------------------------------------
if [ ! -f "$APK" ]; then
    echo "ERROR [apk scan]: Debug APK not found at $APK. Run './gradlew assembleDebug' first." >&2
    exit 1
fi

SDK_DIR=""
if [ -f "$REPO_ROOT/local.properties" ]; then
    SDK_DIR="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$REPO_ROOT/local.properties" | cut -d= -f2- | tr -d ' \r\t')"
fi
if [ -z "$SDK_DIR" ] && [ -n "${ANDROID_HOME:-}" ]; then
    SDK_DIR="$ANDROID_HOME"
fi
if [ -z "$SDK_DIR" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    SDK_DIR="$ANDROID_SDK_ROOT"
fi

if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then
    echo "ERROR [apk scan]: Android SDK directory not found in local.properties or ANDROID_HOME." >&2
    exit 1
fi

DEXDUMP="$(find "$SDK_DIR/build-tools" -name dexdump -type f 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$DEXDUMP" ] || [ ! -x "$DEXDUMP" ]; then
    echo "ERROR [apk scan]: dexdump executable not found in $SDK_DIR/build-tools." >&2
    exit 1
fi

ALLOWED_PACKAGES=()
while IFS= read -r line || [ -n "$line" ]; do
    line="$(echo "$line" | sed 's/#.*//' | tr -d ' \r\t')"
    [ -n "$line" ] && ALLOWED_PACKAGES+=("$line")
done < "$ALLOWED_PACKAGES_FILE"

if [ ${#ALLOWED_PACKAGES[@]} -eq 0 ]; then
    echo "ERROR [apk scan]: Failed closed: $ALLOWED_PACKAGES_FILE is empty" >&2
    exit 1
fi

apk_class_count=0
while read -r class_desc flags; do
    [ -z "$class_desc" ] && continue
    apk_class_count=$((apk_class_count + 1))

    matched=0
    for allowed in "${ALLOWED_PACKAGES[@]}"; do
        if [[ "$class_desc" == "$allowed"/* || "$class_desc" == "$allowed" ]]; then
            matched=1
            break
        fi
    done

    if [ "$matched" -eq 0 ]; then
        # Skip compiler-generated synthetic platform stubs (D8 API modeling)
        if [[ "$flags" =~ SYNTHETIC ]] && [[ "$class_desc" =~ ^(android|dalvik|com/android/tools/r8)/ ]]; then
            continue
        fi
        echo "ERROR [apk scan]: Disallowed class package in APK: $class_desc ($flags)" >&2
        ERRORS=$((ERRORS + 1))
    fi
done < <("$DEXDUMP" "$APK" | awk '
    /Class descriptor/ {
        match($0, /L([^;]+);/, arr)
        desc = arr[1]
    }
    /Access flags/ {
        flags = $0
        sub(/^[ \t]*Access flags[ \t]*:[ \t]*/, "", flags)
        print desc, flags
    }
')

if [ "$apk_class_count" -eq 0 ]; then
    echo "ERROR [apk scan]: Failed closed: dexdump found 0 classes in $APK" >&2
    exit 1
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------
if [ "$ERRORS" -gt 0 ]; then
    echo "FAILED: Dependency check found $ERRORS violation(s)." >&2
    exit 1
fi

echo "OK: Dependencies allowed and zero unauthorized packages in debug APK."
