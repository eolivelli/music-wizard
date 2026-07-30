# Music Wizard — working notes

Sheet music from a recording. MP3 in; melody, bass line, chords, lyrics and a
simplified piano arrangement out, engraved as one PDF per part.

Java 25, Maven, Apache-2.0. CLI now, web UI much later.
Repo: https://github.com/eolivelli/music-wizard

## Build and run

```sh
mvn verify                 # fast, offline; no models, no LilyPond needed
mvn verify -Pintegration   # adds the ground-truth loop and real PDF rendering
mvn -pl mw-core test       # the module that matters most
```

`./mw` is a developer wrapper that rebuilds when sources change; the real
artifact is the shaded `mw-cli/target/mw.jar`. It is ~88 MB, almost entirely
ONNX Runtime, FFmpeg natives and the Anthropic SDK.

## Rules that are not obvious

Break either of these and the output is subtly wrong rather than broken, which
is the expensive kind of wrong.

**1. Chords are estimated from the full mix, never from separated stems.**
Separation artifacts destroy the partial structure that chroma estimation
depends on. Separation exists to feed melody, bass and lyrics only.

**2. Nothing downstream of the beat grid works in seconds.** Once beats are
known, every time value is in quarter-note beats. `TempoMap` is the only
sanctioned conversion. This is what makes quantization, chord alignment, lyric
placement and arrangement mutually consistent for free. `Note` and `Chord` carry
*optional* musical timing precisely so an un-quantized value cannot be mistaken
for a quantized one.

Two more, less dramatic but easy to trip over:

- **Beats are always quarter-note beats**, whatever the meter. A 6/8 bar holds
  three quarter beats, not six.
- **Pitch is carried twice on purpose.** A MIDI number cannot be engraved: 61 is
  both C# and Db, and picking wrongly gives a score that is arithmetically
  correct and visibly wrong to a musician. `PitchSpelling` rides alongside the
  sounding pitch all the way to the notation layer.

## Module layout and the dependency rule

```
mw-core       domain model, workspace, config — depends on nothing but serialization
mw-audio      decoding, resampling, framing, STFT
mw-dsp        onsets, beats, chroma, chords, key, pitch tracking
mw-ml         provider SPI, ONNX implementations, model cache
mw-transcribe stage orchestration: audio or MIDI to Score
mw-arrange    piano reduction, part extraction (purely symbolic, no audio)
mw-notation   Score to LilyPond, MusicXML, MIDI, PDF
mw-llm        Claude advisor layer
mw-cli        picocli entry point
mw-testkit    MIDI fixtures, synthesis, evaluation metrics
mw-it         slow integration tests
```

**`mw-core` is the only module everything may depend on. `mw-notation` must not
depend on `mw-ml`. `mw-cli` is the only module that wires everything together.**
This is what lets the symbolic and audio tracks be built in parallel without
colliding — M1a owns `mw-notation`/`mw-arrange`, M1b owns `mw-audio`/`mw-dsp`,
and changes to `mw-core` go through a separate serialized PR.

One edge between non-core modules is new and worth naming: **`mw-notation`
depends on `mw-arrange`**, for `QuantizedScore` and the per-bar `BarGrid`. It
joins the ones the pipeline already had — `mw-dsp` on `mw-audio`, and
`mw-transcribe` on all three of `mw-audio`, `mw-dsp` and `mw-ml`. The notation
layer needs the quantizer's tuplet decision and cannot re-derive it — three
onsets a third of a beat apart and three a half beat apart are both legal on the
sixth-of-a-beat grid — so the fact is carried rather than inferred (#92). Both
modules are purely symbolic, so this pulls no audio and no models into notation.

## Licensing — enforced, not aspirational

Apache-2.0. The `maven-enforcer-plugin` bans GPL/AGPL artifacts outright, and CI
has a job for it.

- **TarsosDSP cannot be used**, though it is the obvious Java DSP library for
  this domain. GPL-3.0, *and* not actually on Maven Central — what is there are
  stale unofficial forks. We implement the DSP on JTransforms (BSD-2).
- **madmom's pretrained models, Open-Unmix `umxl`, and the MedleyDB / MAESTRO /
  MusicNet / Isophonics corpora are CC BY-NC-SA** and unusable here. madmom's
  *code* is BSD; its weights are not. Clean alternatives: Demucs ONNX (MIT),
  CREPE ONNX (MIT), basic-pitch (Apache-2.0), OpenScore Lieder (CC0).
- LilyPond is GPL-3.0 and that is fine: it is invoked as a separate process,
  never linked or redistributed, and the tool works without it.
- No model weights ship in the repo. They download on first use.

## Rendering

LilyPond source is emitted **directly from the domain model**, not via
`musicxml2ly`, which is lossy. MusicXML is a parallel export, not the route to
PDF. PDF requires the LilyPond binary; without it the tool still writes `.ly`,
`.musicxml` and `.midi` and says so rather than failing.

Discovery checks, in order: the `notation.lilypondPath` config key, `$PATH`,
then — **on POSIX only** — Homebrew and `/usr/local` prefixes, because Homebrew
installs outside a non-login shell's `PATH`, which is exactly how someone ends
up with LilyPond installed and the tool unable to find it.

**LilyPond is run with its message locale pinned to `C`.** The tool decides
whether engraving went well by reading LilyPond's output, and LilyPond
translates that output — a failed bar check is `attenzione: bar check failed` on
an Italian machine, so a check reading it for "warning" stops reading it at all,
silently. Pinning it costs a non-English user LilyPond's own complaints in their
language. It took four attempts to pin only the message language and nothing
else: `LC_ALL` masks eleven other categories, and setting or clearing them
wrongly stops a file whose name is not ASCII from engraving at all. See
`LilyPondRenderer.speakEnglish`, whose javadoc records all three wrong answers.

Three rules that are easy to get wrong:

- An explicit `notation.lilypondPath` is used **exactly as written**, extension
  included, and a non-executable one is an error rather than a hint. Silently
  falling back would ignore an explicit instruction.
- **Relative `PATH` entries are skipped.** A shell runs `./lilypond` because the
  user typed the command; we would run it only because the user happened to `cd`
  somewhere, which is not the same thing.
- On Windows, discovery looks for `lilypond.exe` and friends and is `PATH`-only.
  **Nothing has ever been run on Windows** (#33) — a user on an older installer
  must set `notation.lilypondPath`.

## Testing

Four tiers, decreasing trust:

- **Tier 0** synthetic MIDI through the JDK's built-in synth. Must be
  near-perfect; a regression here is a real bug.
- **Tier 1** synthetic multi-track mixes. Ground truth for stems, notes, chords
  and an *exact* beat grid comes free from the source MIDI.
- **Tier 2** real audio, permissively licensed only.
- **Tier 3** loop closure: score → LilyPond → MIDI → re-analyse → compare.

**Synthetic audio is systematically easier than real audio.** Tiers 0–1 are
regression gates whose thresholds must never drop; tier 2 is the number to
believe. Never quote a synthetic figure as product accuracy.

Notation is tested by golden files over the generated LilyPond and MusicXML
*text*. `mvn verify` must stay fast, offline and binary-free; anything that
downloads a model or shells out to LilyPond belongs in `mw-it`.

## Review process

Every patch gets **at least three review rounds** (see `.claude/agents/`), and
the reason is empirical rather than ceremonial: on this project, round two has
caught a round-one *fix* that was worse than the bug it replaced, and round
three caught two round-two fixes that were bypassable. Specifically:

- A lead-in fix silently misaligned the whole tempo map by up to half a beat —
  and every existing test started at `t=0.0`, so none exercised the branch the
  fix was written for.
- A path-traversal fix normalised the path but never resolved symlinks, so a
  shared workspace could still read `~/.ssh/id_rsa`.
- A hash-collision fix added a byte-length prefix, but UTF-8 encoding is itself
  lossy for unpaired surrogates, so the collision survived.

The pattern worth remembering: **fixes tend to stop at the layer where the bug
was noticed, not the layer where it lives.** Reviewers confirm findings by
execution and label them `CONFIRMED` or `PLAUSIBLE`.

Writing that pattern down here has not reduced how often it happens — four
changes in a single night each shipped a first fix that reached one caller and
missed another. So one thing is now a **mechanical check the reviewer runs
rather than a judgement call**: enumerate every reader of the value that
changed. See `.claude/agents/pr-reviewer.md`.

A second check was tried and withdrawn: re-running every new test against the
code without its fix. It was correct in principle — a fixture starting at
`t=0.0` tests nothing about tempo, because every derivation agrees at the origin
— but it cost far more than it returned. Reading the test finds the same thing,
most reverts only confirmed what the reviewer already believed, and the revert
itself (`git checkout -- <file>`, which discards *all* uncommitted changes to
that file) destroyed half-written fixes repeatedly. It is now reserved for
troubleshooting something genuinely hard to reproduce. Mutation sweeps were
never required at all; they were a generalisation of that check, and they
produced more false numbers than findings.

Two further observations from the same night. When a fix needs the same edit in
a *third* place, the structural change that removes the choice is cheaper than
the third edit — that is where `Score.estimatedTempo()` came from. And once late
rounds stop finding defects in executable code, what they find instead is claims
that outrun their evidence: a result measured at one point written up as
general, a javadoc describing the design that was replaced. On a tool whose
output is estimates users act on, an overstated confidence is a defect.

## Conventions

- **Push at every milestone**, not at the end.
- **Anything not being fixed now becomes a GitHub issue**, never a `TODO`. A
  review that finds ten things produces fixes for the serious ones and issues
  for the rest; findings that are neither fixed nor filed are lost.
- Labels: `epic` + `milestone:MX`, `priority:medium|low`, `review-finding`,
  `design-gap`, `module:*`.
- Commit messages explain **why**. If a change fixes something subtle, say what
  would have gone wrong without it.
- **One git worktree per concurrent task**, never the shared checkout. A
  `git checkout` moves HEAD for every process in that clone; a commit made
  during the move lands on another branch, silently.
- **One local Maven repository per worktree**, passed as
  `-Dmaven.repo.local=<worktree>/.m2` on every invocation. The worktree isolates
  the source; `~/.m2` is the channel it does not isolate. Whatever one agent
  installs becomes another's dependency, so a build can silently resolve a
  sibling module from somebody else's uncommitted work. It has already produced
  a false result here: a `mvn -pl mw-dsp` mutation sweep picked up a stale
  `mw-audio`, ten mutants failed to *build*, and the summary counted them as
  killed. Build with `-am` too, so siblings come from the source tree.
- **No raw control characters in source files.** A test file once contained
  literal NUL bytes, so git treated it as binary — no diff, no blame,
  unreviewable. Write them as escape sequences instead: in Java, a backslash followed by u0000, never the byte itself.

## Honest quality expectations

Roughly a good automatic chord-recognition service, plus notation. Not a
replacement for a human transcriber, and the README says so.

| Output | Realistic |
|---|---|
| Chord chart with lyrics | Strongest output; usable with light edits on most pop |
| Bass line | Good — best-separated stem, 80–88% note F1 |
| Lead vocal melody | Pitch mostly right, rhythm approximate, 65–75% note F1 |
| Piano reduction | Plausible but generic; an arrangement of our own estimates |

The highest-value user action is correcting the tempo or first downbeat by hand.
Beat tracking is the least reliable stage and everything depends on it.

## State

**The pipeline runs end to end.** A real MP3 goes in; beats, tempo and chords
come out; a chord chart is engraved to PDF via LilyPond. Verified on a
synthesised I-V-vi-IV signal and on an actual MP3 encoded from it.

Done: M0 (reactor, domain model, workspace with content-addressed caching,
layered config, CLI) and the harmony half of M1b (decode, onsets, Ellis beat
tracking, tuning-corrected chroma, chord recognition, chord chart, LilyPond).
Four review rounds on `mw-core`.

Still missing: key detection, NNLS chroma (#3), the whole symbolic/notation
track (#1), separation and melody (#8), lyrics (#9), piano (#10), advisor (#11).

`mw-core` passed round 4 once its three blockers landed, but see the open
`design-gap` issues before treating it as frozen — especially #4 (no beat unit,
so compound meters mis-bar) and #5 (notation-facing gaps).
