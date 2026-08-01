# From a phone take to a corpus entry

The phone app (`android/README.md`) records; this note is what to do with a take
once it is off the phone. The loop is the point: the same recording read on the
phone and on the desktop, with what was played written down beside it.

## 1. Share the WAV out

Long-press the take in the library and choose **Share WAV** — that is the
corpus-export path. A take is a PCM 16-bit mono 44100 Hz WAV in app-private
storage, named `yyyy-MM-dd_HH-mm-ss.wav` until the library's **Rename** says
otherwise, and the app offers it under whatever that name is. Rename it first,
to something that says what it is.

## 2. Which directory depends on whether the changes are known

`tools/score-samples.py` looks for every benchmark under `samples/`, so a take
that could be scored has to live there: an entry in `samples/list.txt` giving
the changes bar by bar, and — to be scored rather than merely present — those
changes in that script's `BENCHMARKS` table (`score-chart.py` imports it), with
the baselines under `tools/baselines/` regenerated in the same PR.

A take whose changes are not known that way goes in `uncommitted/`, whose
`list.txt` header says what that directory is for: looking at MW's output, never
scoring it.

Either way, commit the audio only if it may be redistributed. `samples/` already
holds files that may not — they are gitignored by name, and their `list.txt`
entry says where to get them instead.

## 3. Write the `list.txt` entry

Follow the entries already in whichever of the two files it is: file name, then
a paragraph. A phone take has no fetch command, so its provenance goes in that
place instead — who played, on what, when — and then what was played, marked as
known or as remembered.

## 4. Run the desktop CLI on the same file

```sh
./mw init uncommitted/<slug>.wav
./mw analyze uncommitted/<slug>.mwz
./mw render uncommitted/<slug>.mwz
```

`render` prints the chart and writes `<slug>.mwz/out/chords.txt`. That file and
what the result screen's **Share chart** sends are both `ChordChart.toText`
unaltered, so the two compare line for line — as long as `init` is given no
`--title`/`--artist`, which add a header the phone's chart has nothing to match.

Append what came out to the `list.txt` entry, as the existing entries do. That
record is what lets a re-run after the next change say whether MW got better on
this recording.
