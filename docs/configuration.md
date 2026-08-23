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
  run/manifest.json  what the last analysis actually ran
  run/traces.json    the evidence its stages weighed
  out/               .txt, .ly and .pdf per part (staff parts: no .txt)
```

The cache is keyed on the recording's digest and the options that shaped
the listening — today one entry, the whole transcription — so a changed
source or option recomputes without being asked. Lyrics live outside it:
correcting a lyric file must not recompute the DSP.

## The run manifest

`run/manifest.json` is what one `analyze` recorded about itself: the build,
when it ran, the settings it acted on, and a line per stage saying whether the
stage ran, was served from the cache, did not run, or failed and was carried
past — with the reason it printed at the time and whatever facts it chose to
write down. It is a record and never an input: no stage reads it and no cache
key is computed from it.

A stage adds its line by writing one, and nothing enumerates the stages there
are, so the analysis report renders what it finds and states what it does not.
Lines computed under a cache key are stored beside the cached score, so a run
served that answer reports what those stages did rather than going blank.

A workspace analysed before there was a manifest has none. Everything that
reads one says so rather than failing, and re-analysing writes one.

## What the stages weighed

A line is one label to one value, which a candidate ranking or a per-frame
reading is not. `run/traces.json` holds those, under the stage's own name, in
whatever shape that stage records — the beat tracker's is its tempo candidates
per analysis window and how the bass register read the octave; the chroma front
end's is its tuning, the model it fitted with, and what each chord span was read
from. It travels with
the cached score under the same key the lines do, since a trace is a function
of that key exactly as the score is.

Same rules as the manifest: a record, never an input, and a trace this build
cannot parse costs that stage's picture and nothing else.

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

`render` attempts a default set of parts — the chord chart, the
chords-and-lyrics sheet, the lead sheet and the melody staff — and names the
ones it could not produce, with the reason. `--parts playable`
adds a second lead sheet whose melody has been reduced to what a player
reads; it is written only when named, being an arrangement of MW's estimate
rather than a reading of the recording. `--parts report` writes
`out/report.html`, one self-contained page showing what each analysis stage
took in, decided and produced — including, for the stages whose reasoning the
workspace does not keep, that it does not keep it. Ask for a part that is not
implemented (`--parts piano`) and it says so and why rather than listing it
and writing nothing.

PDF needs [LilyPond](https://lilypond.org) on the `PATH` (or
`notation.lilypondPath`); without it the tool still writes the `.ly` source
and says so. LilyPond is run with its message locale
pinned to `C`, because MW decides whether engraving went well by reading
LilyPond's output, and LilyPond translates that output.
