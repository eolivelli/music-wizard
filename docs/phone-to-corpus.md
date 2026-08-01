# From a phone take to a corpus entry

The phone app (`android/README.md`) records; this note is what to do with a take
once it is off the phone. The loop is the point: the same recording read on the
phone and on the desktop, with what was actually played written down beside it.

## 1. Share the WAV out

The library screen's **share** action on a take is the corpus-export path. A
take is a PCM 16-bit mono 44100 Hz WAV in app-private storage, named
`yyyy-MM-dd_HH-mm-ss.wav` until the library's rename says otherwise, and that
name is what the app offers it under. Rename it first, kebab-case, like the
entries already in `list.txt`.

## 2. Put it in `uncommitted/`

`uncommitted/` is gitignored except for its `list.txt`, and a take of a
copyrighted song belongs there however it was recorded. `samples/` is for audio
the project may ship: your own playing of your own or public-domain material.

## 3. Write the `list.txt` entry

Follow the entries already in the file: file name, then a paragraph. A phone
take has no fetch command, so put its provenance in that place instead — who
played, on what, when — and then what was played. Say whether the chords and
tempo are known to the player or remembered; a take whose player knows what he
played is ground truth, which is the one thing a downloaded recording can never
be.

## 4. Run the desktop CLI on the same file

```sh
./mw init uncommitted/<slug>.wav --title '<Title>' --artist '<Player>'
./mw analyze uncommitted/<slug>.mwz
./mw render uncommitted/<slug>.mwz
```

`render` prints the chart and writes `<slug>.mwz/out/chords.txt`. Both that file
and the phone's "share as text" are `ChordChart.toText` unaltered, so the two
charts compare line for line. Append what came out to the `list.txt` entry, as
the existing entries do — that record is what lets a re-run after the next
change say whether MW got better on this recording.

Below Android 15 the app cannot cache `score.json` (#254), so it analyses a take
again each time it is opened and the screen says so. That costs time on the
phone and changes neither the WAV nor the chart.

## If the take should be scored

Only committable audio can be. The harness reads its ground truth from the
`BENCHMARKS` table in `tools/score-samples.py` (`score-chart.py` imports it),
not from `samples/list.txt`, so a new benchmark is an entry in both plus
regenerated baselines under `tools/baselines/` in the same PR.
