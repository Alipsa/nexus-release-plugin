#!/usr/bin/env bash
set -euo pipefail
source_script=$1
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/bin"
cp "$source_script" "$fixture/publish.sh"
cat > "$fixture/bin/git" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
cat > "$fixture/bin/java" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
cat > "$fixture/bin/jdk17" <<'STUB'
# The external helper is allowed to read an unset variable.
helper_value="$PUBLISH_HARNESS_UNSET"
JAVA_HOME="$HARNESS_FIXTURE"
STUB
cat > "$fixture/gradlew" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$HARNESS_LOG"
if [[ "$*" == "${HARNESS_FAIL:-}" ]]; then exit 17; fi
STUB
chmod +x "$fixture/bin/git" "$fixture/bin/java" "$fixture/gradlew"
expected=('clean build' 'publishPlugins --validate-only' 'release' 'publishPlugins')
for failure in '' "${expected[@]}"; do
  export HARNESS_FIXTURE="$fixture" HARNESS_LOG="$fixture/log" HARNESS_FAIL="$failure"
  : > "$HARNESS_LOG"
  result=0
  (cd "$fixture"; PATH="$fixture/bin:$PATH" bash publish.sh) || result=$?
  count=4
  if [[ -n "$failure" ]]; then
    [[ "$result" == 17 ]]
    for i in "${!expected[@]}"; do
      if [[ "${expected[$i]}" == "$failure" ]]; then count=$((i + 1)); fi
    done
  else
    [[ "$result" == 0 ]]
  fi
  printf '%s\n' "${expected[@]:0:count}" > "$fixture/expected"
  diff -u "$fixture/expected" "$HARNESS_LOG"
done
