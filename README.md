# Music Wizard

Generate sheet music from a recording. Give it an MP3 and it works out the
melody, the bass line, the chords and the lyrics, then engraves a PDF for each
part — plus a simplified two-hand piano arrangement.

Java 25, Maven, Apache-2.0. Command line for now; a web UI later.

> **Status: early, but it runs.** Give it an MP3 or WAV and it will find the
> beats, estimate the chords, and engrave a chord chart as a PDF. Melody, bass,
> lyrics and the piano reduction are not built yet — see
> [Milestones](#milestones).

```
$ mw init song.mp3 && mw analyze song.mwz && mw render song.mwz

| C           | G           | Am          | F           |
| C           | G           | Am          | F           |
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
tempo or the first downbeat by hand. Beat tracking is the least reliable stage
and everything downstream depends on it, so one right number fixes a lot:

```
mw analyze mysong.mwz --tempo 128 --time-signature 4/4 --first-downbeat 0.42
```

## Installing

Requires **JDK 25**. For PDF output you also need
[LilyPond](https://lilypond.org) on your `PATH`:

```sh
brew install lilypond      # macOS, or Homebrew on Linux
apt install lilypond       # Debian or Ubuntu
```

Without LilyPond everything still runs — you get `.ly`, `.musicxml` and `.midi`
files that you can engrave elsewhere.

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
mw render song.mwz --parts voice,piano,bass,chords   # engrave the parts
mw info song.mwz                                     # what has been computed
```

A **workspace** is a directory holding the recording and everything derived
from it:

```
song.mwz/
  workspace.yaml     schema version, source identity, preferences
  source/            the untouched recording
  cache/             per-stage results, keyed by their inputs
  score/score.json   the transcription
  out/               .ly, .musicxml, .midi and .pdf per part
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

## The Claude advisor (optional)

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
- **M1b — Audio track.** Decode → beats → chroma → chords. *Chords and chart
  working; key detection and NNLS chroma still to come.*
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
