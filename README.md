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
on a phone with the app below, shared through a cloud drive, and read by the
pipeline unaided.

## What it does today

- **Beats and tempo** tracked from the audio — the meter is assumed 4/4 unless
  `--time-signature` says otherwise — with the corrections that matter most
  exposed as flags (`--tempo`, `--first-downbeat`), because beat tracking is
  the least reliable stage and everything downstream hangs on it.
- **Key**, reported with separate confidences for the signature and for which
  of a relative pair is home, because those are decisions of very different
  reliability.
- **Chords** from the full mix — major and minor triads, dominant and minor
  sevenths — behind an NNLS chroma front end built for real recordings rather
  than clean synthetic ones.
- **Lyrics**, two ways: place a supplied [LRC][lrc] file under the chords —
  any language — or transcribe the singing from the recording itself;
  transcription and syllable splitting cover Italian and English.
- **Engraving**: a text chart, LilyPond source, and PDF via [LilyPond] — for
  the chord chart and the chords-and-lyrics sheet. `--transpose` moves the
  chords, the key and the spelling together.
- **Standard MIDI File input**, read symbolically, with its declared tempo and
  meter kept apart from anything estimated.
- **A workspace per song** (`song.mwz/`) with per-stage caching keyed on
  inputs, so re-running recomputes only what changed.

Honestly: the quality is that of a good automatic chord-recognition service,
plus notation — usable with light edits on most pop, and not a replacement for
a human transcriber. The corpus it is measured on lives in `samples/`, the
current readings in `tools/baselines/`, and the single most valuable thing you
can do by hand is correct the tempo or the first downbeat.

## From a phone in the room to a chart

MW's benchmark material is real playing, not fixtures, and the repo carries
the whole loop for collecting it:

1. **Record on the phone.** The [Android app](android/README.md) is a
   field-recording instrument — record a take, run the same harmony analysis
   on the device, read the chart as text. It can also take a YouTube link
   shared into it and fetch the audio as a take; those are marked as what they
   are, and never reach the committed corpus.
2. **Write down what was played**, in the app, while it is fresh — the note
   travels with the take.
3. **Share the bundle.** One zip, `<take>.mwz.zip`: the WAV, your note, an
   info file — and, when the take was analysed on the phone, its chart and
   analysis cache. Hand it to any app you trust — a cloud drive, typically —
   straight from the share sheet.
4. **Import it here.** A [Claude Code](https://claude.com/claude-code) agent
   definition ships in-repo (`.claude/agents/take-importer.md`): it sweeps the
   drive with `rclone`, verifies each bundle, runs the full pipeline on the
   WAV, transcribes the singing when your note names the language, renames the
   drive copy `*.imported` so it stays behind as a backup, and reports the
   desktop chart against the phone's and against your own account of the take.
5. **Promote what deserves it.** [docs/phone-to-corpus.md](docs/phone-to-corpus.md)
   is the path from a staged take into `samples/` — by a person, by ear.

## Hearing the words

Supplying lyrics is the reliable path: point `analyze` at an LRC file and the
words land under the chords, timed from the file's tags where it has them.

```sh
mw analyze song.mwz --lyrics song.lrc --lyrics-language it
mw analyze song.mwz --lyrics-language it   # no file: transcribe the singing
mw render song.mwz                         # adds the chords-lyrics sheet
```

Naming a language without a file asks MW to *hear* the words:

- The vocal is separated from the mix with **Spleeter** (ONNX, MIT — code and
  weights both).
- Transcription is **Qwen3-ASR** through [sherpa-onnx], built from a source
  submodule by `tools/build-sherpa-native.sh` — currently
  [Enrico's fork][sherpa-fork], which carries the Qwen3-ASR feature-alignment
  fix ([k2-fsa/sherpa-onnx#3873][sherpa-pr]) ahead of upstream review, and
  points back at upstream once it lands. The build keeps TTS off so no GPL
  code reaches the link; `tools/check-sherpa-native.sh` asserts that against
  the built artifact, in CI too.
- Another Qwen3-ASR export can be substituted via `ml.asrModelDirectory` in
  the global config — only one size is published ready-made, so a different
  one means exporting the model yourself.
- Word-level timing comes from a **wav2vec2** forced aligner. English has a
  published export and always aligns; Italian has none, so it aligns when you
  produce one and name it in `ml.alignmentModelDirectory`
  ([docs/italian-alignment-model.md](docs/italian-alignment-model.md)). A
  language with no model gets its words spread across the sung stretch
  instead, and `mw doctor` lists which ones this machine can align.

`--lyrics-language` also splits words into the syllables they are sung on —
*a-mo-re*, not *amore* — with hyphenation patterns for Italian and English;
on any other language words deliberately stay whole, because splitting on the
wrong rules is worse than not splitting. Sung speech recognition is modest:
expect to correct the transcription, not to trust it. Supplied lyrics survive
later `analyze` runs, so correcting the tempo does not throw them away.

## Installing

Building requires **JDK 25**; the jar is Java 21 bytecode and runs on JDK 21
or newer — which is what lets the Android app link the same modules. For PDF
output you also need [LilyPond] on your `PATH`:

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

No model weights ship in the repo or the jar; stages that need one download it
on first use into a local cache, checksummed, with its provenance beside it.
The jar is large regardless; slimming it is its own line of work.

## Using it

```sh
mw init song.mp3 --title "Song" --artist "Artist"   # create a workspace
mw analyze song.mwz                                  # work out what is played
mw render song.mwz                                   # engrave what can be engraved
mw info song.mwz                                     # what has been computed
```

`render` defaults to the parts that are implemented — the chord chart and the
chords-and-lyrics sheet. Ask for one that is not (`--parts voice`) and it says
so and why, rather than listing it and writing nothing.

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

### Configuration

Settings layer, weakest first: built-in defaults, your global config at
`~/.config/music-wizard/config.yaml`, the workspace's own `workspace.yaml`,
then command-line flags. Each layer states only what it changes.

```yaml
notation:
  transposeSemitones: -2
ml:
  sherpaNativePath: /path/to/sherpa-onnx/build/lib
```

What reaches the pipeline is `analysis`, `ml` — provider and model selection
for separation, transcription and alignment — plus `notation.lilypondPath`
and `notation.transposeSemitones`. Providers configure themselves from the
global file ([#383][i383]), so `ml.asrModelDirectory` and
`ml.alignmentModelDirectory` are read from there only,
and `analyze` says so when a workspace tries to override it. Several notation
keys that reach nothing draw a warning rather than silence; `arrangement`
reaches nothing *and* warns nothing — entirely inert until the piano work
lands ([#144][i144]).

## Roadmap

Where this is going, in rough order of pull:

- **Melody detection, and the piano sheet** — the sung line as a voice part,
  and a playable two-hand reduction built from everything MW knows about the
  song.
- **Drums detection, and drum sheets** — the kit written the way drummers
  read.
- **Sharper lyric hearing** — better sung-speech transcription and word
  timing, on more of a mix than the clean separated vocal.
- **Harmony, always** — a richer chord vocabulary (major sevenths, sixths,
  half-diminished) once four-note candidates can be ranked on more than which
  extra note is loudest, and bar lines that follow a recording that does not
  hold one constant tempo.
- **More languages** than English and Italian, for both transcription and
  syllable splitting.
- **More genres** — the corpus and the estimators lean pop and blues today;
  jazz voicings, swing feel and denser harmony are the next frontier.

## Not built yet

Named so nothing has to be discovered by trying it: the **voice**, **bass**
and **piano** parts (`render` refuses them by name and says why), **drums**,
**MusicXML and MIDI export**, and a **web UI**. The CLI is the product today.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

No model weights are shipped. See NOTICE for the models chosen so far and
their licences. Models and datasets under non-commercial terms are
deliberately avoided — code and weights carry separate licences, and both are
read — see [CONTRIBUTING.md](CONTRIBUTING.md) for the policy.

[lrc]: https://en.wikipedia.org/wiki/LRC_(file_format)
[LilyPond]: https://lilypond.org
[sherpa-onnx]: https://github.com/k2-fsa/sherpa-onnx
[sherpa-fork]: https://github.com/eolivelli/sherpa-onnx/tree/qwen3-asr-stft-center-alignment
[sherpa-pr]: https://github.com/k2-fsa/sherpa-onnx/pull/3873
[i383]: https://github.com/eolivelli/music-wizard/issues/383
[i144]: https://github.com/eolivelli/music-wizard/issues/144
