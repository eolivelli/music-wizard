#!/usr/bin/env bash
# Analyzes every committed sample and writes a markdown status report.
#
# The report is written to a file and then published three ways, because a
# report that exists in only one place is a report nobody reads: it is echoed
# to stdout (so it is in the job log), appended to $GITHUB_STEP_SUMMARY (so it
# is on the run's summary page) and left on disk for the workflow to upload as
# an artifact. The first CI version wrote to $GITHUB_STEP_SUMMARY alone, and
# when that did not surface there was nothing in the log to say why — not even
# whether the analyses had run.
#
# Two sections: a per-file analysis summary for EVERY committed mp3 (tempo,
# meter, chord spans — works even for files whose ground truth is not yet
# confirmed), then the two scored harnesses for the files with registered
# ground truth. Local-only samples are absent in CI by design; the harnesses
# say so per line rather than failing.
set -u
cd "$(dirname "$0")/.."
JAR=mw-cli/target/mw.jar
REPORT="${SAMPLES_REPORT:-target/samples-report.md}"
[ -f "$JAR" ] || { echo "build first: mvn -B -DskipTests package" >&2; exit 1; }
mkdir -p "$(dirname "$REPORT")"
: > "$REPORT"

# Progress goes to stderr so it interleaves with the runner's own log while the
# report itself accumulates in $REPORT. Without it the step looks hung for the
# many minutes the analyses take.
say() { echo "$*" >&2; }

rows=0
{
  echo "## Sample corpus status"
  echo
  echo "| file | duration | tempo | meter | chord spans |"
  echo "|---|---|---|---|---|"
} >> "$REPORT"

for mp3 in samples/*.mp3; do
  [ -e "$mp3" ] || { say "no samples found under samples/"; break; }
  say "analyzing $mp3"
  tmp=$(mktemp -d)
  ws="$tmp/w.mwz"
  log="$tmp/mw.log"
  if java -jar "$JAR" init "$mp3" --workspace "$ws" >"$log" 2>&1; then
    out=$(java -jar "$JAR" analyze "$ws" 2>"$log")
    tempo=$(sed -n 's/^Tempo *//p'  <<<"$out")
    meter=$(sed -n 's/^Meter *//p'  <<<"$out")
    spans=$(sed -n 's/^Chords *//p' <<<"$out")
    dur=$(python3 -c "import json;print(f\"{json.load(open('$ws/score/score.json'))['durationSeconds']:.0f}s\")" 2>/dev/null || echo "?")
    if [ -n "$tempo" ]; then
      echo "| $(basename "$mp3") | $dur | $tempo | $meter | $spans |" >> "$REPORT"
      rows=$((rows + 1))
    else
      echo "| $(basename "$mp3") | analyze FAILED | | | |" >> "$REPORT"
      say "analyze failed for $mp3:"; tail -20 "$log" >&2
    fi
  else
    echo "| $(basename "$mp3") | init FAILED | | | |" >> "$REPORT"
    say "init failed for $mp3:"; tail -20 "$log" >&2
  fi
  rm -rf "$tmp"
done

say "running the scored harnesses"
{
  echo
  echo "### Scored benchmarks (registered ground truth)"
  echo '```'
  python3 tools/score-samples.py 2>&1
  echo
  python3 tools/score-chart.py 2>&1
  echo '```'
} >> "$REPORT"

# Publish. `cat` first: if the summary append fails, the numbers are still in
# the log rather than lost with it.
cat "$REPORT"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  if cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"; then
    say "report appended to the job summary ($(wc -c < "$REPORT") bytes)"
  else
    say "could not write \$GITHUB_STEP_SUMMARY ($GITHUB_STEP_SUMMARY)"
  fi
fi

# A green job with an empty table means the corpus silently went missing or
# every analysis failed, which is exactly the failure this job exists to catch.
[ "$rows" -gt 0 ] || { say "no sample was analyzed successfully"; exit 1; }
say "reported on $rows sample(s)"
