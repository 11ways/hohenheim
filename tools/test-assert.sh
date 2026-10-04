# test-assert.sh -- the assertions the host-tool shell tests share; sourced, never run.
#
# ok/no count a labelled verdict, expect asserts that a captured output contains (or, with
# "no" as its fourth argument, does not contain) a fixed string, and report prints the
# totals and exits non-zero when anything failed.

PASSED=0
FAILED=0

ok() { PASSED=$((PASSED + 1)); printf 'ok   %s\n' "$1"; }
no() { FAILED=$((FAILED + 1)); printf 'FAIL %s\n' "$1"; }

expect() {
    local label="$1" haystack="$2" needle="$3" mode="${4:-yes}"
    if printf '%s' "$haystack" | /usr/bin/grep -qF -- "$needle"; then
        [ "$mode" = "yes" ] && ok "$label" || no "$label (unexpected: $needle)"
    else
        [ "$mode" = "yes" ] && no "$label (missing: $needle)" || ok "$label"
    fi
}

report() {
    printf '\n%s passed, %s failed\n' "$PASSED" "$FAILED"
    [ "$FAILED" -eq 0 ]
}
