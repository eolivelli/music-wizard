#!/usr/bin/env bash
# The merge gate. One command, run by the author before requesting merge and
# re-run once by the reviewer at merge time, on the branch MERGED WITH CURRENT
# origin/main. Everything the project requires to call a change safe:
#
#   1. fast suite            mvn -B verify
#   2. integration suite     mvn -B -Pintegration verify
#   3. both sample harnesses, diffed against the committed baselines
#
# Any harness diff fails the gate — including an improvement. An improvement
# is real evidence and belongs in the PR: regenerate the baseline
# (tools/score-*.py > tools/baselines/...) and commit it with the change, so
# the diff is reviewed rather than silently absorbed.
#
# Benchmarks missing locally (the licensing-bound ones; see samples/list.txt)
# are reported and skipped, never failed: the committed CI can only gate the
# committed sample, and this script is honest about which lines it checked.
set -u
cd "$(dirname "$0")/.."
REPO_ARGS="${MAVEN_ARGS:-}"
fail=0

step() { printf '\n=== %s ===\n' "$1"; }

step "1/4 fast suite"
mvn -B -q $REPO_ARGS verify || { echo "FAIL: mvn verify"; fail=1; }

step "2/4 integration suite"
mvn -B -q $REPO_ARGS -Pintegration verify || { echo "FAIL: mvn -Pintegration verify"; fail=1; }

compare() { # $1 harness  $2 baseline
  local out; out=$(python3 "tools/$1" 2>&1)
  printf '%s\n' "$out"
  local diffs
  diffs=$(python3 - "$2" <<'PY' "$out"
import sys
baseline = {l.split(":")[0].strip(): l.rstrip() for l in open(sys.argv[1])
            if ".mp3:" in l}
current  = {l.split(":")[0].strip(): l.rstrip() for l in sys.argv[2].splitlines()
            if ".mp3:" in l}
for name, base in sorted(baseline.items()):
    if name not in current:
        print(f"SKIP {name}: not present locally (see samples/list.txt)")
    elif current[name] != base:
        print(f"DIFF {name}\n  baseline: {base}\n  current:  {current[name]}")
PY
)
  printf '%s\n' "$diffs"
  grep -q '^DIFF' <<<"$diffs" && return 1 || return 0
}

step "3/4 model harness vs baseline"
compare score-samples.py tools/baselines/score-samples.txt || { echo "FAIL: score-samples moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "4/4 chart harness vs baseline"
compare score-chart.py tools/baselines/score-chart.txt || { echo "FAIL: score-chart moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "verdict"
[ "$fail" -eq 0 ] && echo "PREMERGE: PASS" || echo "PREMERGE: FAIL"
exit "$fail"
