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
# A benchmark this machine cannot measure is skipped rather than failed, but a
# skip and a pass are not the same claim (#464): the verdict names every
# skipped row, and where the machine has declared which skips it expects, an
# undeclared one fails the gate. See tools/premerge-skips.py and
# docs/local-setup.md.
set -u
cd "$(dirname "$0")/.."
REPO_ARGS="${MAVEN_ARGS:-}"
fail=0
full=0
skipped=0
steps=0
drift=""
records=$(mktemp "${TMPDIR:-/tmp}/premerge-records.XXXXXX") \
  || { echo "FAIL: cannot record what this run compares"; exit 1; }
trap 'rm -f "$records"' EXIT

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
  local since="baseline file(s) changed on main since the branch point"
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
  # --no-renames, because rename detection names only the destination: a
  # baseline renamed and re-measured in one commit would reach the classifier
  # as a path that has no older self, which is its quiet arm.
  moved=$(git diff --no-renames --name-only "$base" "$tip" -- tools/baselines/ 2>/dev/null)

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
  # about a column added to every row, or about rows that did not exist to be
  # quoted, is how a prompt teaches people to skip it -- so the verdict line,
  # which is what gets pasted into a PR, carries the kind as well as the count.
  # The quiet arms are keyed on statuses python cannot produce by accident --
  # it exits 2 itself when it cannot open the script -- so a classifier that
  # never ran reads as one that found movement, never as a clean one. Nothing
  # may come between the call and the case: any command, a `local` included,
  # replaces the status being read.
  case "$?" in
    0) echo "No row in them changed."
       drift="$n $since, no row changed" ;;
    3) echo "No figure the rows still share moved. Check anything you quoted from"
       echo "a column listed above."
       drift="$n $since, no figure moved" ;;
    4) echo "Rows were only added. Nothing quoted earlier was measured against them."
       drift="$n $since, rows added only" ;;
    *) echo "A figure this branch quoted earlier may have been measured against the"
       echo "older baseline. Re-take it, or do not quote it."
       drift="$n $since, a figure may be stale" ;;
  esac
  return 0
}
baseline_drift

# One Maven invocation either way. -Pintegration only adds failsafe
# executions, so it runs everything plain `verify` runs and the ITs as well;
# running both would run the unit suite twice for nothing.
if [ "$full" -eq 1 ]; then
  step "1/10 full suite (unit + integration)"
  mvn -B -q -T 1C $REPO_ARGS -Pintegration verify \
    || { echo "FAIL: mvn -Pintegration verify"; fail=1; }
else
  step "1/10 build (suites left to CI; --full runs them here)"
  mvn -B -q -T 1C $REPO_ARGS -DskipTests package \
    || { echo "FAIL: mvn package"; fail=1; }
fi

compare() { # $1 harness  $2 baseline  $3... harness args
  local harness="$1" baseline="$2"; shift 2
  steps=$((steps + 1))
  local out rc
  out=$(python3 "tools/$harness" ${1+"$@"} 2>&1); rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -ne 0 ]; then
    # A dead harness must not read as a clean one: every row it never got
    # to print would otherwise become a silent skip.
    echo "FAIL: $harness exited $rc"
    return 1
  fi
  # The comparison lives in tools/premerge-diff.py, where CI's harness-rule
  # tests can reach it. Its status is what fails the step, not a grep for DIFF:
  # a comparison that died on its way printed no DIFF either. `local diffs` is
  # declared above rather than here, because a `local` between the call and the
  # read replaces the status being read.
  local diffs
  diffs=$(printf '%s\n' "$out" | python3 tools/premerge-diff.py "$baseline" "$records"); rc=$?
  printf '%s\n' "$diffs"
  skipped=$((skipped + $(grep -c '^SKIP' <<<"$diffs")))
  # Every run of the comparison ends by saying how many rows it compared. Its
  # absence means the step vouched for nothing, whatever the status says.
  grep -q '^compared ' <<<"$diffs" \
    || { echo "FAIL: nothing says what $baseline compared"; return 1; }
  [ "$rc" -eq 0 ] && return 0
  [ "$rc" -eq 3 ] || echo "FAIL: premerge-diff.py exited $rc"
  return 1
}

step "2/10 model harness vs baseline"
compare score-samples.py tools/baselines/score-samples.txt || { echo "FAIL: score-samples moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "3/10 chart harness vs baseline"
compare score-chart.py tools/baselines/score-chart.txt || { echo "FAIL: score-chart moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "4/10 lyric harness vs baseline"
compare score-lyrics.py tools/baselines/score-lyrics.txt || { echo "FAIL: score-lyrics moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The transcription loop (#391): the same recordings through the ASR with no
# lyrics file, scored against the same truth. Costs about a minute per row,
# which is why it runs unconditionally like its siblings; it needs the sherpa
# native, and a machine without one reports every row skipped rather than
# failing (the harness prints the build command per row).
step "5/10 transcription harness vs baseline"
compare score-lyrics.py tools/baselines/score-asr.txt --source asr || { echo "FAIL: score-lyrics --source asr moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The synthetic corpus (#447): every package committed, so CI runs this same
# diff on the pull request; here it costs one analysis per package.
step "6/10 synthetic harness vs baseline"
compare score-synthetic.py tools/baselines/score-synthetic.txt || { echo "FAIL: score-synthetic moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The melody stage (#494), scored against each package's own MIDI melody track
# rather than against its grid, and in seconds rather than on the beat axis --
# see the harness's docstring for why those are the same decision. Committed
# corpus, so CI runs this diff too.
step "7/10 melody harness vs baseline"
compare score-melody.py tools/baselines/score-melody.txt || { echo "FAIL: score-melody moved — if intended, regenerate the baseline and commit it"; fail=1; }

# Real singing, annotated note by note (#502). Local-only: 69 MB of CC BY audio
# the repository does not carry, so a machine without it reports every clip
# skipped rather than failing. It measures the stage on a voice rather than on
# a rendered instrument, and carries its own ceiling -- see the harness
# docstring.
step "8/10 melody harness vs baseline, on real singing"
compare score-melody.py tools/baselines/score-melody-vocadito.txt --source vocadito || { echo "FAIL: score-melody --source vocadito moved — if intended, regenerate the baseline and commit it"; fail=1; }

# The same two corpora read the way analyze --melody reads a recording since
# #559: through the separated vocal. Their pair with the two steps above is
# what separation is worth to this stage, and it is not one number -- a band
# under the melody and a melody alone move opposite ways. Local-only, and CI
# cannot run them: they need a separation model this machine has downloaded,
# and a machine without one skips every row.
step "9/10 melody harness vs baseline, through the separated vocal"
compare score-melody.py tools/baselines/score-melody-separated.txt --separated || { echo "FAIL: score-melody --separated moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "10/10 melody harness vs baseline, real singing through the separated vocal"
compare score-melody.py tools/baselines/score-melody-vocadito-separated.txt --source vocadito --separated || { echo "FAIL: score-melody --source vocadito --separated moved — if intended, regenerate the baseline and commit it"; fail=1; }

step "what this run certified"
# Named rather than counted (#464), and checked against what this machine says
# it cannot measure. The quiet arms are keyed on statuses python cannot produce
# by accident -- it exits 1 on an uncaught exception and 2 of its own accord
# when it cannot open the script -- so an account that never ran fails the gate
# rather than reading as an expected skip. Nothing may come between the call
# and the read: any command, a `local` included, replaces the status.
account=$(python3 tools/premerge-skips.py "$records" "$skipped" "$steps"); rc=$?
printf '%s\n' "$account"
case "$rc" in
  0) ;;   # every skipped row was expected on this machine, or none skipped
  3) ;;   # this machine declares nothing, so the skips are named, not gated
  *) fail=1 ;;
esac

step "verdict"
# Say which of the two it was, so a pasted verdict cannot be read as covering
# suites that were never run here.
[ "$full" -eq 1 ] && scope="build + suites + harnesses" || scope="build + harnesses"
# The one line most likely to be pasted on its own, so what the run could not
# certify travels with it rather than staying in the block above.
summary=$(sed -n 's/^SUMMARY: //p' <<<"$account")
[ -n "$summary" ] && scope="$scope; $summary"
# The drift prompt is named here for the same reason -- a reader of the pasted
# line is exactly who needs to know that a figure in the body around it may
# predate a baseline main regenerated.
[ -n "$drift" ] && scope="$scope; $drift"
if [ "$fail" -ne 0 ]; then
  echo "PREMERGE: FAIL ($scope)"
elif [ -n "$summary" ]; then
  echo "PREMERGE: PASS-WITH-SKIPS ($scope)"
else
  echo "PREMERGE: PASS ($scope)"
fi
exit "$fail"
