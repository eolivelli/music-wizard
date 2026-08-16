# Music Wizard

**Point it at a recording; get sheet music back.** MW listens to an MP3 or WAV
and works out the beat grid, the tempo, the key and the chords — then engraves
a chord chart as a PDF, with the words under the chords when you supply lyrics
or let it transcribe the singing. It runs on your machine, on
open models, with no account and no server: plain Java, Apache-2.0.

```
$ mw init song.mp3 && mw analyze song.mwz && mw render song.mwz

Tempo  116 BPM
Meter  4/4
Key    G major (92% confidence)

| N.C. G      | %           | %           | D           |
| %           | G           | C           | G           |
```

That chart is real output: *Happy Birthday* sung at a kitchen piano, recorded
on a phone with the [Android app](android/README.md), shared through a cloud
drive, and read by the pipeline unaided.

## What it does today

- **Beats and tempo** tracked from the audio, with the corrections that
  matter most exposed as flags (`--tempo`, `--first-downbeat`) — beat
  tracking is the least reliable stage and everything downstream hangs on it.
- **Key**, reported with separate confidences for the signature and for which
  of a relative pair is home.
- **Chords** from the full mix — triads, dominant and minor sevenths — behind
  a chroma front end built for real recordings rather than clean synthetic
  ones.
- **Lyrics**, two ways: place a supplied [LRC][lrc] file under the chords, or
  transcribe the singing from the recording itself; transcription and
  syllable splitting cover Italian and English.
- **Melody**, from a recording whose melody is the only thing sounding:
  `analyze --melody` reads the sung line and `render --parts lead` engraves a
  lead sheet. Off unless asked for, because the tracker is monophonic — on a
  full mix it confidently returns the loudest line, usually the bass.
- **Engraving**: a text chart, LilyPond source, and PDF via [LilyPond] — with
  `--transpose`, `--beat-marks` and `--repeat-tags`.
- **Standard MIDI File input**, read symbolically, with its declared tempo
  and meter kept apart from anything estimated.
- **A workspace per song** with per-stage caching, so re-running recomputes
  only what changed.

Honestly: the quality is that of a good automatic chord-recognition service,
plus notation — usable with light edits on most pop, and not a replacement
for a human transcriber. The single most valuable thing you can do by hand is
correct the tempo or the first downbeat.

## How it works

[docs/pipeline.md](docs/pipeline.md) is the map, and each stage has a page of
its own: [tempo and beats](docs/tempo-detection.md),
[harmony](docs/harmony-detection.md), [melody](docs/melody-detection.md),
[lyrics](docs/lyrics-detection.md). The corpus MW is measured on lives in
`samples/`, its current readings in `tools/baselines/`, and the loop that
collects real recordings — phone app, share sheet, importer agent — is
[docs/android-app.md](docs/android-app.md) and
[docs/phone-to-corpus.md](docs/phone-to-corpus.md). A
[music-teacher agent](docs/music-teacher.md) grows a synthetic corpus with
exact ground truth beside the real one.

## Installing

Building requires **JDK 25**; the jar is Java 21 bytecode and runs on JDK 21
or newer. For PDF output you also need [LilyPond] on your `PATH`:

```sh
brew install lilypond      # macOS, or Homebrew on Linux
apt install lilypond       # Debian or Ubuntu
```

Without LilyPond everything still runs — you get the `.ly` source and engrave
it elsewhere. Then:

```sh
mvn package -DskipTests    # produces mw-cli/target/mw.jar
./mw doctor                # the wrapper rebuilds when sources change
```

No model weights ship in the repo or the jar; stages that need one download
it on first use into a local cache, checksummed, with its provenance beside
it.

## Using it

```sh
mw init song.mp3 --title "Song" --artist "Artist"   # create a workspace
mw analyze song.mwz                                  # work out what is played
mw render song.mwz                                   # engrave what can be engraved
mw info song.mwz                                     # what has been computed
```

Hearing the words:

```sh
mw analyze song.mwz --lyrics song.lrc --lyrics-language it
mw analyze song.mwz --lyrics-language it   # no file: transcribe the singing
mw render song.mwz                         # adds the chords-lyrics sheet
```

[docs/configuration.md](docs/configuration.md) covers the workspace layout
and the config layers; [docs/local-setup.md](docs/local-setup.md) is what a
machine needs for the stages that reach outside the repo — including the
sherpa-onnx native that lyric transcription builds from a source submodule —
and what degrades in silence without each. `mw doctor` reports what this
machine can do.

## Roadmap

Where this is going, in rough order of pull:

- **Melody out of a mix, and the piano sheet** — the sung line read through
  separation, and a playable two-hand reduction.
- **Drums detection, and drum sheets.**
- **Sharper lyric hearing** — better sung-speech transcription and word
  timing.
- **Harmony, always** — a richer chord vocabulary and bar lines that follow
  a recording that does not hold one constant tempo.
- **More languages** than English and Italian; **more genres** than the pop
  and blues the corpus leans today.

Not built yet, named so nothing has to be discovered by trying it: the
**bass** and **piano** parts (`render` refuses them by name and says why),
**drums**, **MusicXML and MIDI export** as finished routes, and a **web UI**.
The CLI is the product today.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

No model weights are shipped. See NOTICE for the models chosen so far and
their licences; models and datasets under non-commercial terms are
deliberately avoided — see [CONTRIBUTING.md](CONTRIBUTING.md) for the policy.

[lrc]: https://en.wikipedia.org/wiki/LRC_(file_format)
[LilyPond]: https://lilypond.org
