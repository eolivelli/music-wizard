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
# Benchmarks missing locally are reported and skipped, never failed, and the
# verdict says how many rows were skipped: the gate can only vouch for what
# this machine could measure.
set -u
cd "$(dirname "$0")/.."
REPO_ARGS="${MAVEN_ARGS:-}"
fail=0
full=0
skipped=0
drift=""

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

# A prompt, never a gate (#428). The harness diff below compares this tree
# against the committed baselines, so it cannot see a figure quoted in a PR
# body, an issue comment or a javadoc earlier in this branch's life and
# measured against a baseline main has regenerated since. This says whether
# that could have happened. It never sets fail, and it says nothing at all
# whenever git cannot answer.
baseline_drift() {
  local tip base cut moved n t guard= origin=fetched
  git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null || return 0
  # Read origin/main rather than a memory of it: a tracking ref last updated at
  # the branch point reports exactly the zero drift this exists to catch. Not
  # reaching it is not an error -- the local ref is used and said to be old.
  # An unbounded fetch would hang a script that has printed nothing yet, so it
  # runs only under a timeout, and never asks for a credential it cannot be
  # given. The explicit refspec is what makes "(fetched)" true: with
  # remote.origin.fetch unset, a plain fetch succeeds and moves no ref.
  for t in timeout gtimeout; do
    command -v "$t" >/dev/null && { guard="$t 30"; break; }
  done
  if [ -z "$guard" ]; then
    origin="local ref, no timeout command to bound a fetch"
  elif ! $guard env GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -oBatchMode=yes' \
       git fetch --quiet origin "+refs/heads/main:refs/remotes/origin/main" 2>/dev/null; then
    origin="local ref, origin unreachable"
  fi
  tip=$(git rev-parse --verify --quiet refs/remotes/origin/main) || return 0
  # The branch point is where this branch STARTED, not merge-base(HEAD, main).
  # Once the branch has merged current main -- which is how this script is
  # meant to be run -- that merge-base is main itself and the answer is always
  # zero. What pins the start is where the commits that are HEAD's alone hang
  # off: their boundary. Taking the earliest of those means a feature branch
  # merged in widens the window rather than hiding the part before it.
  cut=$(git rev-list --boundary HEAD --not "$tip" 2>/dev/null | sed -n 's/^-//p')
  [ -n "$cut" ] || return 0     # detached at main, or on main, or behind it
  base=$(git merge-base --octopus $cut "$tip" 2>/dev/null)
  [ -n "$base" ] || return 0    # shallow clone, or no common history
  moved=$(git diff --name-only "$base" "$tip" -- tools/baselines/ 2>/dev/null)

  step "baseline drift since the branch point (prompt, not a gate)"
  printf 'branch point %s; origin/main since then: +%s commits, %s not merged here (%s).\n' \
    "$(git rev-parse --short "$base")" \
    "$(git rev-list --count "$base..$tip" 2>/dev/null)" \
    "$(git rev-list --count "HEAD..$tip" 2>/dev/null)" "$origin"
  if [ -z "$moved" ]; then
    echo "no file under tools/baselines/ changed on main since then."
    return 0
  fi
  n=$(grep -c . <<<"$moved")
  # The classification lives in tools/baseline-drift.py, where CI's harness-rule
  # tests can reach it: a rule about not crying wolf is worth a test.
  python3 tools/baseline-drift.py "$base" "$tip" $moved
  # Only a figure that moved can invalidate a quoted one. Saying the same thing
  # about a column added to every row is how a prompt teaches people to skip it
  # -- so the verdict line, which is what gets pasted into a PR, says which of
  # the three it was rather than counting files. An exit status that is none of
  # these is the harness itself failing, and takes the cautious branch.
  case "$?" in
    0) echo "No row in them changed."
       drift="$n baseline file(s) touched on main since the branch point, no row changed" ;;
    2) echo "No figure the rows still share moved. Check anything you quoted from"
       echo "a column listed above."
       drift="$n baseline file(s) reshaped on main since the branch point, no figure moved" ;;
    *) echo "A figure this branch quoted earlier may have been measured against the"
       echo "older baseline. Re-take it, or do not quote it."
       drift="$n baseline file(s) moved on main since the branch point" ;;
  esac
  return 0
}
baseline_drift

# One Maven invocation either way. -Pintegration only adds failsafe
# executions, so it runs everything plain `verify` runs and the ITs as well;
# running both would run the unit suite twice for nothing.
if [ "$full" -eq 1 ]; then
  step "1/6 full suite (unit + integration)"
  mvn -B -q -T 1C $REPO_ARGS -Pintegration verify \
    || { echo "FAIL: mvn -Pintegration verify"; fail=1; }
else
  step "1/6 build (suites left to CI; --full runs them here)"
  mvn -B -q -T 1C $REPO_ARGS -DskipTests package \
    || { echo "FAIL: mvn package"; fail=1; }
fi

compare() { # $1 harness  $2 baseline  $3... harness args
  local harness="$1" baseline="$2"; shift 2
  local out rc
  out=$(python3 "tools/$harness" ${1+"$@"} 2>&1); rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -ne 0 ]; then
    # A dead harness must not read as a clean one: every row it never got
    # to print would otherwise become a silent skip.
    echo "FAIL: $harness exited $rc"
    return 1
  fi
  local diffs
  diffs=$(python3 - "$baseline" <<'PY' "$out"
import sys
baseline = {l.split(":")[0].strip(): l.rstrip() for l in open(sys.argv[1])
            if ".mp3:" in l}
current  = {l.split(":")[0].strip(): l.rstrip() for l in sys.argv[2].splitlines()
            if ".mp3:" in l}
for name, base in sorted(baseline.items()):
    if name not in current:
        # The harness ran to completion and still printed nothing for this
        # baselined name: harness and baseline disagree about the corpus --
        # a benchmark retired without regenerating the baseline. Fetching a
        # file cannot fix that, so it fails rather than skips.
        print(f"DIFF {name}\n  baseline: {base}\n  current:  (no row printed)")
    elif ": not present (local-only" in current[name]:
        # The diff can only compare what this machine can measure, and each
        # skip is printed -- and counted in the verdict -- so a subset run
        # cannot read as a corpus run. Only the CURRENT side may say so: a
        # committed baseline that certifies absence is a defect, and where
        # this machine can measure the file it falls through to DIFF below.
        print(f"SKIP {name}: not measurable here (the row above says how)")
    elif current[name] != base:
        print(f"DIFF {name}\n  baseline: {base}\n  current:  {current[name]}")
for name, row in sorted(current.items()):
    if name not in baseline:
        # The reverse disagreement: the harness printed a row the baseline
        # never recorded -- a benchmark added without regenerating the
        # baseline. The synthetic corpus grows a package per music-teacher
        # run, so this direction is the common one there.
        print(f"DIFF {name}\n  baseline: (no row recorded)\n  current:  {row}")
PY
)
  printf '%s\n' "$diffs"
  skipped=$((skipped + $(grep -c '^SKIP' <<<"$diffs")))
  grep -q '^DIFF' <<<"$diffs" && return 1 || return 0
}

step "2/6 model harness vs baseline"
compare score-samples.py tools/baselines/score-samples.txt || { echo "FAIL: score-samples moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "3/6 chart harness vs baseline"
compare score-chart.py tools/baselines/score-chart.txt || { echo "FAIL: score-chart moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "4/6 lyric harness vs baseline"
compare score-lyrics.py tools/baselines/score-lyrics.txt || { echo "FAIL: score-lyrics moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The transcription loop (#391): the same recordings through the ASR with no
# lyrics file, scored against the same truth. Costs about a minute per row,
# which is why it runs unconditionally like its siblings; it needs the sherpa
# native, and a machine without one reports every row skipped rather than
# failing (the harness prints the build command per row).
step "5/6 transcription harness vs baseline"
compare score-lyrics.py tools/baselines/score-asr.txt --source asr || { echo "FAIL: score-lyrics --source asr moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The synthetic corpus (#447): every package committed, so CI runs this same
# diff on the pull request; here it costs one analysis per package.
step "6/6 synthetic harness vs baseline"
compare score-synthetic.py tools/baselines/score-synthetic.txt || { echo "FAIL: score-synthetic moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "verdict"
# Say which of the two it was, so a pasted verdict cannot be read as covering
# suites that were never run here.
[ "$full" -eq 1 ] && scope="build + suites + harnesses" || scope="build + harnesses"
[ "$skipped" -gt 0 ] && scope="$scope; $skipped harness rows skipped"
# The verdict is the line that gets pasted into a PR, so the drift prompt is
# named here too -- a reader of the pasted line is exactly who needs to know
# that a figure in the body around it may predate a baseline main regenerated.
[ -n "$drift" ] && scope="$scope; $drift"
[ "$fail" -eq 0 ] && echo "PREMERGE: PASS ($scope)" || echo "PREMERGE: FAIL ($scope)"
exit "$fail"
