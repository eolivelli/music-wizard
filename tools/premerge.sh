#!/usr/bin/env bash
# The local half of the merge gate, run by the author before requesting merge
# and re-run once by the reviewer at merge time, on the branch MERGED WITH
# CURRENT origin/main. By default it runs only what CI cannot:
#
#   1. build the shaded jar   mvn -B -T 1C -DskipTests package
#   2. the sample harnesses, diffed against the committed baselines
#
# CI runs the fast suite, the integration suite, the licensing check and the
# corpus report against the PR's merge preview, and a green PR is the gate
# that decides a merge. What CI cannot run is the harness diff: the local-only
# benchmark files never leave this machine. So that is what this script is for,
# and --full adds the suites back for anyone who wants them before pushing.
#
# Any harness diff fails the gate — including an improvement. An improvement
# is real evidence and belongs in the PR: regenerate the baseline
# (tools/score-*.py > tools/baselines/...) and commit it with the change, so
# the diff is reviewed rather than silently absorbed.
#
# Benchmarks missing locally (the licensing-bound ones; see samples/list.txt)
# are reported and skipped, never failed: CI can only gate the samples that are
# committed, and this script is honest about which lines it checked.
set -u
cd "$(dirname "$0")/.."
REPO_ARGS="${MAVEN_ARGS:-}"
fail=0
full=0

usage() {
  cat <<'EOF'
usage: tools/premerge.sh [--full]

  (default)  build the shaded jar, then diff the sample harnesses against
             tools/baselines/ — the part CI cannot run.
  --full     run the unit and integration suites here too, instead of
             waiting for CI to run them on the pull request.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --full) full=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

step() { printf '\n=== %s ===\n' "$1"; }

# One Maven invocation either way. -Pintegration only adds failsafe
# executions, so it runs everything plain `verify` runs and the ITs as well;
# running both would run the unit suite twice for nothing.
if [ "$full" -eq 1 ]; then
  step "1/4 full suite (unit + integration)"
  mvn -B -q -T 1C $REPO_ARGS -Pintegration verify \
    || { echo "FAIL: mvn -Pintegration verify"; fail=1; }
else
  step "1/4 build (suites left to CI; --full runs them here)"
  mvn -B -q -T 1C $REPO_ARGS -DskipTests package \
    || { echo "FAIL: mvn package"; fail=1; }
fi

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
    # A row the harness itself reports as absent is a skip, not a movement:
    # the diff can only compare what this machine can measure, and the skip
    # is printed so a run over a subset cannot read as a run over the corpus.
    # Only the CURRENT side may say so -- a committed baseline that certifies
    # absence is a defect, and falls through to DIFF where it will fail.
    if name not in current or ": not present (local-only" in current[name]:
        print(f"SKIP {name}: not measurable here"
              f" (fetch commands: samples/list.txt or uncommitted/list.txt)")
    elif current[name] != base:
        print(f"DIFF {name}\n  baseline: {base}\n  current:  {current[name]}")
PY
)
  printf '%s\n' "$diffs"
  grep -q '^DIFF' <<<"$diffs" && return 1 || return 0
}

step "2/4 model harness vs baseline"
compare score-samples.py tools/baselines/score-samples.txt || { echo "FAIL: score-samples moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "3/4 chart harness vs baseline"
compare score-chart.py tools/baselines/score-chart.txt || { echo "FAIL: score-chart moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "4/4 lyric harness vs baseline"
compare score-lyrics.py tools/baselines/score-lyrics.txt || { echo "FAIL: score-lyrics moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "verdict"
# Say which of the two it was, so a pasted verdict cannot be read as covering
# suites that were never run here.
[ "$full" -eq 1 ] && scope="build + suites + harnesses" || scope="build + harnesses"
[ "$fail" -eq 0 ] && echo "PREMERGE: PASS ($scope)" || echo "PREMERGE: FAIL ($scope)"
exit "$fail"
