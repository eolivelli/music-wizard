## Why

`KEYS` got its `where` in #729 and the two files that issue named. Two more recordings in `uncommitted/` carry a key an ear has settled and reach no harness row:

- **`hanno-ucciso-luomo-ragno.mp3`** — its `list.txt` entry states A minor, and no A major occurs in it (#527).
- **`generale.mp3`** — `generale-reference.txt` records Enrico confirming the recording is in A, beside the fetched chart, which is written in A major.

On the unwritten standard #751 asks about: there is no piano requirement. `METERS` takes an ear-confirmed 6/8 on two recordings with no instrument named, so the piano was how two rows happened to be settled, not a bar. The `KEYS` comment now says what admits a row — a key an ear has settled and the file's `list.txt` entry writes down — and says that how it was settled does not rank one row below another.

## What the rows print

Both name the key their truth states, so both land OK and the change is a baseline regeneration with no estimator work:

```
  key hanno-ucciso-luomo-ragno.mp3: A minor at 18%  want A minor  OK
  key generale.mp3: A major at 44%  want A major  OK
```

Taken on this branch's jar, not inherited from the issue. No other row in `tools/baselines/score-samples.txt` moves; the diff against the committed baseline is exactly those two added lines.

## generale's list.txt entry

`CorpusTables` holds every row to its corpus's `list.txt`, and `generale.mp3` had no entry at all — the gap #639 is open on — so the entry is part of the fix rather than a rider.

The fetch command turned out to be recoverable. It was written in commit 47703d1b on `origin/test-generale-sample`, a branch that never merged, which is why `list.txt` never got it. Checked the way #538's was: `yt-dlp --skip-download --print` on that URL returns *Francesco De Gregori — Generale (Official Audio)* at a duration matching the local file to the second. The entry records the command, where it came from, the ear's key with its provenance, and that the reference chart itself is not ground truth — which is what that file already says of itself.

## Also checked for the same gap

`tools/MeterSweep.java` mentions neither key nor `Key`. `ChordSweep.java` prints how long each chord was held and expects no key. `TempoOctave.java`, `GlideSweep.java`, `OctaveSweep.java`, `ScoreBeats.java`, `SeparationSplit.java`, `PlayablePartCheck.java`, `score-chart.py`, `measure-tempo.py` and `score-synthetic.py` carry no table of expected keys either — `score-synthetic.py` reads each package's own spec, per package.

## Not fixed here

#752 (the `where` defaults in `run_for`/`doc_for`/`missing_line`) is untouched: every loop passes `where`, both new rows are local and reach their files, and that issue asks to be done with #750, which has to touch the same two loops.

Closes #751
Closes #639
