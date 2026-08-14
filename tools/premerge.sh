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
drift=0

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
  local tip base first moved guard= origin=fetched
  git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null || return 0
  # Read origin/main rather than a memory of it: a tracking ref last updated at
  # the branch point reports exactly the zero drift this exists to catch. Being
  # offline is not an error -- the local ref is used and said to be old.
  command -v timeout >/dev/null && guard="timeout 30"
  $guard git fetch --quiet origin main 2>/dev/null || origin="local ref, origin unreachable"
  tip=$(git rev-parse --verify --quiet refs/remotes/origin/main) || return 0
  # The branch point is where this branch STARTED, not merge-base(HEAD, main).
  # Once the branch has merged current main -- which is how this script is
  # meant to be run -- that merge-base is main itself and the answer is always
  # zero. The oldest commit on HEAD and not on main pins the start instead; a
  # feature branch merged in makes that window wider, never narrower.
  first=$(git rev-list --topo-order --reverse HEAD --not "$tip" 2>/dev/null | head -1)
  [ -n "$first" ] || return 0   # detached at main, or on main, or behind it
  base=$(git merge-base "$first" "$tip" 2>/dev/null)
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
  drift=$(grep -c . <<<"$moved")
  python3 - "$base" "$tip" <<'PY' $moved
import re, subprocess, sys

base, tip, paths = sys.argv[1], sys.argv[2], sys.argv[3:]

def rows(rev, path):
    """Baseline rows as name -> {field shape: field}, or None if absent."""
    p = subprocess.run(["git", "show", f"{rev}:{path}"],
                       capture_output=True, text=True)
    if p.returncode != 0:
        return None
    out = {}
    for line in p.stdout.splitlines():
        if not line[:1].isspace() or ":" not in line:
            continue                      # a header line, not a row
        name, _, body = line.partition(":")
        fields = {}
        for f in re.split(r"\s{2,}", body.strip()):
            # A field is keyed by its shape -- itself with every number masked
            # -- so a column added or renamed reads as a shape change while a
            # measurement that moved reads as a figure change. Without that,
            # adding a column rewrites every row and the prompt cries wolf.
            k = re.sub(r"\d+(?:\.\d+)?", "#", f)
            fields[k] = fields.get(k, "") + f
        out[name.strip()] = fields
    return out

remeasured = False
for path in paths:
    old, new = rows(base, path), rows(tip, path)
    name = path.rsplit("/", 1)[-1]
    if old is None or new is None:
        remeasured = True
        print(f"  {name}: {'added' if old is None else 'removed'} on main")
        continue
    common = sorted(set(old) & set(new))
    figures = [r for r in common
               if any(k in new[r] and old[r][k] != new[r][k] for k in old[r])]
    shape = [r for r in common if set(old[r]) != set(new[r])]
    bits = []
    if set(old) != set(new):
        remeasured = True
        bits.append(f"{len(set(new) - set(old))} rows added, "
                    f"{len(set(old) - set(new))} removed")
    if figures:
        remeasured = True
        bits.append(f"figures moved in {len(figures)} of {len(common)} rows")
    if shape:
        bits.append(f"columns changed in {len(shape)} of {len(common)} rows"
                    + ("" if figures else ", no shared figure moved"))
    print(f"  {name}: " + ("; ".join(bits) if bits else "rows unchanged"))

sys.exit(1 if remeasured else 0)
PY
  # Only a figure that moved can invalidate a quoted one. Saying the same thing
  # about a column added to every row is how a prompt teaches people to skip it.
  if [ "$?" -eq 0 ]; then
    echo "No figure moved there -- the rows were rewritten, not re-measured."
  else
    echo "A figure this branch quoted earlier may have been measured against the"
    echo "older baseline. Re-take it, or do not quote it."
  fi
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
[ "$drift" -gt 0 ] && scope="$scope; $drift baseline file(s) moved on main since the branch point"
[ "$fail" -eq 0 ] && echo "PREMERGE: PASS ($scope)" || echo "PREMERGE: FAIL ($scope)"
exit "$fail"
