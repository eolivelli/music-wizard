# Music Wizard

Generate sheet music from a recording. Give it an MP3 and it works out the
melody, the bass line, the chords and the lyrics, then engraves a PDF for each
part — plus a simplified two-hand piano arrangement.

Built on JDK 25, targeting Java 21 bytecode; Maven, Apache-2.0. Command line
for now; a web UI later.

> **Status: early, but it runs.** Give it an MP3 or WAV and it will find the
> beats, estimate the chords, and engrave a chord chart as a PDF. Melody, bass,
> lyrics and the piano reduction are not built yet — see
> [Milestones](#milestones).

```
$ mw init song.mp3 && mw analyze song.mwz && mw render song.mwz

Tags   [A] marks lines that print identically

| C           | G           | Am          | F           |  [A]
| C           | G           | Am          | F           |  [A]
```

## What to expect

Automatic transcription is genuinely hard, and it is worth being honest about
where the quality lands rather than discovering it later:

| Output | Realistic quality |
|---|---|
| **Chord chart with lyrics** | The strongest output. Usable with light edits on most pop. |
| **Bass line** | Good. The bass is the best-separated, most reliably tracked part. |
| **Lead vocal melody** | Pitch mostly right, rhythm approximate. Expect to fix a few notes per phrase. |
| **Piano reduction** | Plausible but generic. It is an arrangement built from our own estimates, not a transcription of the actual piano part. |

This is roughly the tier of a good automatic chord-recognition service, plus
notation. It is not a replacement for a human transcriber.

The single highest-value thing you can do to improve output is correct the
tempo by hand. Beat tracking is the least reliable stage and everything
downstream depends on it, so one right number fixes a lot:

```
mw analyze mysong.mwz --tempo 128 --time-signature 4/4
```

`--tempo` is in the beat you count, so in 6/8 it is dotted quarters rather than
quarters. It replaces the tracked *rate* only. The tracked beats survive, and
the map is anchored so that the first of them still falls on a whole counted
beat rather than part-way through one — but they keep the spacing they were
tracked at, so halving the tempo leaves a grid whose beats are now eighth notes.
Correcting a half-or-double reading fixes the chart; it does not re-track the
beats, and it does not line the map's bar lines up with them ([#84][i84]).

`--first-downbeat` is in seconds from the start of the recording. It is snapped
to the nearest tracked beat — it says which beat begins a bar, not that a beat
was missed — and it overrides the downbeat detector outright rather than being
weighed against it.

**It moves the bar lines on the engraved page** ([#83][i83]). The chart's first
bar line is the downbeat at or before the first chord, so nominating a different
beat re-bars the PDF: the chords move within their bars, and a chord change
landing mid-bar shows up as one.

The text chart moves less, and it is worth knowing why before you compare two
runs. It prints chord *names*, not lengths, so it can tell you the harmony
starts part-way into the first bar — an `N.C.` appears — but not by how much
([#186][i186]). Two different wrong downbeats can give the same `.txt` and
different pages.

Bar *spacing* still comes from the tempo, not from the rest of the grid: only
the first bar line is read off a downbeat, and the ones after it are laid out at
the tempo the chart prints ([#187][i187]). **On real audio this is the chart's
largest remaining error.** How large is no longer known: the figure that used to
sit here — on an eleven minute twelve-bar blues, the first twenty-five changes in
the right bar and every one after that in the wrong one — was measured before
both [#196][i196] and [#200][i200], and it has not been re-taken. Between them
those two cut a bar line's drift against the recording by roughly an order of
magnitude — the figures, and a caveat on the earliest of them, are below. They
did not do it in the same place, which is why the twenty-five wants re-measuring
rather than adjusting: at the point that figure comes from, a hundred beats in,
#196 left the error where it was and #200 halved it. What *is* current is the
score of the printed chart, below.

That twenty-five is the bar arithmetic measured on real *timing* with the chords
supplied, not a figure for what the tool recognises. Chord recognition on that
recording used to return nothing but `N.C.`; since [#3][i3] it returns hundreds
of spans and no `N.C.` at all, most of them in the right bar with the right root
since [#196][i196] stopped the beat grid drifting. The chords were supplied
anyway, because a layout measurement wants a
progression known to be right rather than one that is half right.

Several things cause that drift and they are not the same size on every
recording. Two have been fixed: the beat tracker was leaving the grid for loud
offbeats ([#196][i196]), and the bar lines were spaced at the median tracked
interval rather than at a rate ([#200][i200]). Two are open, and neither is a
leftover of the other:

- the whole bar axis hangs on the grid's *first* downbeat, which on a recording
  with a lead-in is the least reliable beat in it ([#233][i233]);
- a recording does not hold one bar length anyway, and one constant spacing
  cannot follow one that does not ([#187][i187]). On the blues above, a bar line
  placed by index still ends about a beat and a half from the recording's own
  beats by the end of the eleven minutes — down from about seven once
  [#196][i196] had landed and before [#200][i200]. The figure before either was
  about seventeen, but that one is inherited rather than re-measured, and it is
  not on the same axis: #196 moved the tracked beat times themselves, so the
  seventeen is against the beats that tracker read and the one and a half is
  against today's.

So correcting the downbeat by hand is now worth more than correcting `--tempo`,
which is the other way round from how it used to be.

Those two fixes do show up in what the tool actually prints. Scoring the emitted
chart against the known changes — `tools/score-chart.py`, which reads the
engraved source rather than the model behind it — the share of bars carrying the
right chord on that recording went from 67% to 82% at [#200][i200]. All five
benchmarks that existed at the time improved or held; the next largest was 80% to
93%. Two more have since been added to the corpus and one of them scores lower
under this change — see [#242][i242], which measures why that particular cell is
not a measurement.

[i83]: https://github.com/eolivelli/music-wizard/issues/83
[i84]: https://github.com/eolivelli/music-wizard/issues/84
[i186]: https://github.com/eolivelli/music-wizard/issues/186
[i187]: https://github.com/eolivelli/music-wizard/issues/187
[i3]: https://github.com/eolivelli/music-wizard/issues/3
[i200]: https://github.com/eolivelli/music-wizard/issues/200
[i233]: https://github.com/eolivelli/music-wizard/issues/233
[i242]: https://github.com/eolivelli/music-wizard/issues/242
[i196]: https://github.com/eolivelli/music-wizard/issues/196

## Installing

Building requires **JDK 25**; the jar it produces is Java 21 bytecode and runs
on **JDK 21 or newer**, which is what lets an Android build link the same
modules. For PDF output you also need
[LilyPond](https://lilypond.org) on your `PATH`:

```sh
brew install lilypond      # macOS, or Homebrew on Linux
apt install lilypond       # Debian or Ubuntu
```

Without LilyPond everything still runs — you get the `.ly` source, which you can
engrave elsewhere. MusicXML and MIDI export are planned and not written yet.

Build and check your setup:

```sh
mvn package -DskipTests    # produces mw-cli/target/mw.jar
./mw doctor                # the wrapper rebuilds when sources change
```

The jar is large (~88 MB) because it bundles ONNX Runtime, FFmpeg natives and
the Anthropic SDK. Slimming it — most obviously by making the ML stack an
optional download rather than a bundled dependency — is tracked separately.

## Using it

```sh
mw init song.mp3 --title "Song" --artist "Artist"   # create a workspace
mw analyze song.mwz                                  # work out what is played
mw render song.mwz                                   # engrave what can be engraved
mw info song.mwz                                     # what has been computed
```

`render` defaults to the parts that are implemented, which today are the chord
chart and the chords-and-lyrics sheet. Ask for one that is not — `--parts
voice` — and it says so and why, rather than listing it and writing nothing.

### Lyrics

Nothing transcribes lyrics from a recording yet ([#9][i9]). What works today is
supplying them: point `analyze` at an [LRC][lrc] file and they are placed under
the chords.

```sh
mw analyze song.mwz --lyrics song.lrc
mw render song.mwz                   # writes out/chords-lyrics.txt as well
```

Word timings are read from the file where it has them — the `<mm:ss.xx>` tags of
"enhanced" LRC — and estimated within each line where it does not. A file that
cannot be read or that carries no timestamps is a warning, never a failed
analysis: the listening is the expensive part and a mistyped path must not cost
it. Lyrics are also kept out of the transcription cache key, so correcting the
lyric file re-reads it without re-analysing the recording.

**Two files, not one.** `out/chords.txt` is unchanged — a bar grid, which is
what to read when there is nothing to sing. `out/chords-lyrics.txt` puts each
chord symbol over the word it arrives on, which is what to read when there is.
Neither is the other with rows added; they answer different questions, and the
grid is what the measurement harness parses.

[i9]: https://github.com/eolivelli/music-wizard/issues/9
[lrc]: https://en.wikipedia.org/wiki/LRC_(file_format)

**`init` also takes a Standard MIDI File.** The input is identified by its
header rather than its extension, and a MIDI file is read symbolically rather
than measured. `analyze` reports its tempo, meter and key under a heading saying
where they came from — *from the file, or the MIDI default where it declares
nothing*, because a file that declares no tempo is played at 120 in 4/4 by the
specification and the import cannot tell the two apart ([#119][i119]).

Its chords are the one thing that is *not* declared: a MIDI file states which
notes sound, not what chord they spell, so the harmony is estimated from the
notes. That is why the chord count is printed outside that heading and not
under it.

[i119]: https://github.com/eolivelli/music-wizard/issues/119

```sh
mw init song.mid && mw analyze song.mwz
```

A **workspace** is a directory holding the recording and everything derived
from it:

```
song.mwz/
  workspace.yaml     schema version, source identity, preferences
  source/            the untouched recording
  cache/             per-stage results, keyed by their inputs
  score/score.json   the transcription
  out/               .txt, .ly and .pdf per part
```

Results are cached per stage and keyed by the stage's inputs and parameters, so
re-running only recomputes what actually changed. Separating stems takes
minutes; adjusting the piano arrangement afterwards should not pay that again.

## Configuration

Settings layer, weakest first: built-in defaults, your global config at
`~/.config/music-wizard/config.yaml`, the workspace's own `workspace.yaml`, then
command-line flags. Each layer only states what it changes.

```yaml
notation:
  paperSize: letter
  transposeSemitones: -2
arrangement:
  pianoDifficulty: 0.3      # 0 easiest, 1 most faithful
llm:
  enabled: true
```

Much of this is read, layered, and then read by nothing. What reaches the
pipeline is `analysis`, apart from `skipSeparation`, plus
`notation.lilypondPath` and `notation.transposeSemitones` — the last moves the
chords, the key and the spelling together, exactly as `render --transpose` does.

The keys that do nothing divide in two. `analyze` and `render` **warn** about
`analysis.skipSeparation`, `notation.paperSize` ([#180][i180]) and
`notation.capo` and `notation.accidentalPreference` ([#181][i181]) — from a flag
or from this file — rather than producing the default output in silence. All of
`arrangement` and `ml` is equally inert, and nothing warns about those: they have
no flags, and no command to warn from yet ([#144][i144]). The advisor is the
section below.

[i144]: https://github.com/eolivelli/music-wizard/issues/144

[i180]: https://github.com/eolivelli/music-wizard/issues/180

[i181]: https://github.com/eolivelli/music-wizard/issues/181


## The Claude advisor (optional, and not built yet)

**None of this section is implemented.** `mw-llm` holds no code; the config keys
below are read and layered but reach nothing, and `analyze` and `doctor` both say
so. It is recorded here as the design ([#11][i11]) rather than as behaviour — in
particular the safety property below, that a suggestion is re-scored against the
audio before being applied, is a guarantee about a mechanism that does not exist.

[i11]: https://github.com/eolivelli/music-wizard/issues/11


The pipeline is fully functional offline with no API key. If one is present and
you enable it, Claude post-processes the *symbolic* results — it never sees
audio. It is used only where musical convention matters more than signal:

- repairing speech-recognition errors in lyrics, voting across repeated choruses
- naming sections (deciding which repeated block is "the chorus")
- picking the key signature and enharmonic spelling (F♯ versus G♭)
- choosing a piano accompaniment style
- proposing corrections to implausible chord progressions

That last one is *proposer only*. Every suggested chord change is re-scored
against the actual audio evidence and applied only if the evidence still
supports it, so a confident wrong answer cannot overwrite a correct one.

Responses are cached in the workspace, so re-runs cost nothing and stay
reproducible.

## Building

```sh
mvn verify                 # fast, offline; no models, no LilyPond needed
mvn verify -Pintegration   # adds the ground-truth loop and real PDF rendering
```

## Milestones

- **M0 — Foundation.** Reactor, domain model, workspace, config, CLI. *Done.*
- **M1b — Audio track.** Decode → beats → chroma → chords → key. *Chords, key
  and chart working.*
- **M1a — Symbolic track.** MIDI in, MusicXML and MIDI out, staff notation.
- **M2 — Separation, bass and melody.**
- **M3 — Lyrics.**
- **M4 — Piano reduction.**
- **M5 — Claude advisor.**
- **M6 — Quality pass.** Metrics and tuning.

M1a and M1b run in parallel and meet at the `Score` type, which is why the
domain model was settled first.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

No model weights are shipped; they are downloaded on first use and each keeps
its own licence. Models and datasets under non-commercial terms are
deliberately avoided — see [CONTRIBUTING.md](CONTRIBUTING.md) for the policy.
