#!/usr/bin/env bash
#
# Copyright 2026 Music Wizard contributors
# Licensed under the Apache License, Version 2.0.
#
# Builds one synthetic-sample package from its spec:
#
#   tools/music-teacher/generate.sh <name>.spec.txt [outdir]
#
# spec -> MIDI (mw-teacher, deterministic) -> WAV (FluidSynth + the cached
# soundbank) -> MP3 (ffmpeg, loudness-normalized). Outputs land beside the
# spec unless outdir says otherwise. The WAV is an intermediate and removed.
#
# Requires fluidsynth and ffmpeg on PATH; the spec-to-MIDI step alone needs
# neither and can be run directly via mvn -pl mw-teacher exec:java.

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <name>.spec.txt [outdir]" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
spec="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
outdir="$(cd "${2:-$(dirname "$spec")}" && pwd)"

base="$(basename "$spec")"
base="${base%.spec.txt}"
base="${base%.txt}"
mid="$outdir/$base.mid"
mp3="$outdir/$base.mp3"
wav="$(mktemp --suffix=.wav)"
trap 'rm -f "$wav"' EXIT

# Install with -am so siblings come from this source tree into the local
# repository; then run without -am, so exec:java targets mw-teacher alone
# rather than every module in the reactor.
(cd "$root" && mvn -q -pl mw-teacher -am -DskipTests install \
  && mvn -q -pl mw-teacher -DskipTests exec:java -Dexec.args="$spec --out $outdir")

"$root/tools/music-teacher/fetch-soundfont.sh"
sf2="${MW_SOUNDFONT:-$root/.mw-cache/soundfonts/GeneralUser-GS.sf2}"

fluidsynth -ni -r 44100 -o synth.gain=0.4 -F "$wav" "$sf2" "$mid" >/dev/null

ffmpeg -loglevel error -y -i "$wav" \
  -af loudnorm=I=-14:TP=-1.5:LRA=11 -ar 44100 \
  -codec:a libmp3lame -b:a 192k "$mp3"

echo "$mid"
echo "$mp3"
