#!/usr/bin/env bash
# Analyzes every committed sample and writes a markdown status report.
# In GitHub Actions the report lands on the run's summary page via
# $GITHUB_STEP_SUMMARY; locally it prints to stdout.
#
# Two sections: a per-file analysis summary for EVERY committed mp3 (tempo,
# meter, chord spans — works even for files whose ground truth is not yet
# confirmed), then the two scored harnesses for the files with registered
# ground truth. Local-only samples are absent in CI by design; the harnesses
# say so per line rather than failing.
set -u
cd "$(dirname "$0")/.."
OUT="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
JAR=mw-cli/target/mw.jar
[ -f "$JAR" ] || { echo "build first: mvn -B -DskipTests package" >&2; exit 1; }

{
  echo "## Sample corpus status"
  echo
  echo "| file | duration | tempo | meter | chord spans |"
  echo "|---|---|---|---|---|"
} >> "$OUT"

for mp3 in samples/*.mp3; do
  ws=$(mktemp -d)/w.mwz
  if java -jar "$JAR" init "$mp3" --workspace "$ws" >/dev/null 2>&1; then
    out=$(java -jar "$JAR" analyze "$ws" 2>/dev/null)
    tempo=$(sed -n 's/^Tempo *//p'  <<<"$out")
    meter=$(sed -n 's/^Meter *//p'  <<<"$out")
    spans=$(sed -n 's/^Chords *//p' <<<"$out")
    dur=$(python3 -c "import json;print(f\"{json.load(open('$ws/score/score.json'))['durationSeconds']:.0f}s\")" 2>/dev/null || echo "?")
    if [ -n "$tempo" ]; then
      echo "| $(basename "$mp3") | $dur | $tempo | $meter | $spans |" >> "$OUT"
    else
      echo "| $(basename "$mp3") | analyze FAILED | | | |" >> "$OUT"
    fi
  else
    echo "| $(basename "$mp3") | init FAILED | | | |" >> "$OUT"
  fi
  rm -rf "$(dirname "$ws")"
done

{
  echo
  echo "### Scored benchmarks (registered ground truth)"
  echo '```'
  python3 tools/score-samples.py
  echo
  python3 tools/score-chart.py
  echo '```'
} >> "$OUT"
