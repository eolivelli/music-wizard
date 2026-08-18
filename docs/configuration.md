# Configuration and the workspace

## The workspace

A **workspace** is a directory holding the recording and everything derived
from it:

```
song.mwz/
  workspace.yaml     schema version, source identity, preferences
  source/            the untouched recording
  cache/             analysis results, keyed by their inputs
  score/score.json   the transcription
  out/               .txt, .ly and .pdf per part (staff parts: no .txt)
```

The cache is keyed on the recording's digest and the options that shaped
the listening — today one entry, the whole transcription — so a changed
source or option recomputes without being asked. Lyrics live outside it:
correcting a lyric file must not recompute the DSP.

## Configuration layers

Settings layer, weakest first: built-in defaults, your global config at
`~/.config/music-wizard/config.yaml`, the workspace's own `workspace.yaml`,
then command-line flags. Each layer states only what it changes.

```yaml
notation:
  transposeSemitones: -2
ml:
  sherpaNativePath: /path/to/sherpa-onnx/build/lib
```

What reaches the pipeline today:

- `analysis` — `tempoOverride`, `timeSignatureOverride`,
  `firstDownbeatSecondsOverride`, `skipSeparation`. The first three are the
  corrections a workspace keeps; see
  [tempo-detection.md](tempo-detection.md) for what each does.
- `ml` — provider and model selection for separation, transcription and
  alignment. **Providers configure themselves from the global file only**
  (#383): `ml.asrModelDirectory` and `ml.alignmentModelDirectory` set in a
  workspace never reach them, and `analyze` says so when one tries.
- `notation` — `lilypondPath`, `transposeSemitones`, `beatMarks`,
  `repeatTags`. An explicit `lilypondPath` is used exactly as written; a
  non-executable one is an error, not a hint.

Several notation keys that reach nothing draw a warning rather than
silence. Not all inert keys do: `arrangement` warns nothing until the piano
work lands (#144), and the `llm` block beyond `enabled` reaches nothing
until the advisor exists (#11).

[local-setup.md](local-setup.md) is what a machine needs for the stages that
reach outside the repo — the sherpa native, the alignment models — and what
degrades in silence without each.

## Rendering

`render` defaults to the parts that are implemented — the chord chart, the
chords-and-lyrics sheet, and, when the score was analysed with `--melody`,
the lead sheet and the melody staff. `--parts playable` adds a second lead
sheet whose melody has been reduced to what a player reads; it is written
only when named, being an arrangement of MW's estimate rather than a reading
of the recording. Ask for a part that is not implemented (`--parts piano`),
or for a melody the score does not hold, and it says so and why rather than
listing it and writing nothing.

PDF needs [LilyPond](https://lilypond.org) on the `PATH` (or
`notation.lilypondPath`); without it the tool still writes the `.ly` source
and says so. LilyPond is run with its message locale
pinned to `C`, because MW decides whether engraving went well by reading
LilyPond's output, and LilyPond translates that output.
