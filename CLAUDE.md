# Music Wizard — working notes

Sheet music from a recording. MP3 in; melody, bass line, chords, lyrics and a
simplified piano arrangement out, engraved as one PDF per part.

Built on JDK 25, compiled to Java 21 bytecode — Android's D8 reads no newer,
and the field-recording app (#236) links the shared modules. Maven, Apache-2.0.
CLI now, web UI much later.
Repo: https://github.com/eolivelli/music-wizard

## Build and run

```sh
mvn verify                 # fast, offline; no models, no LilyPond needed
mvn verify -Pintegration   # adds the ground-truth loop and real PDF rendering
mvn -pl mw-core test       # the module that matters most
```

`docs/local-setup.md` is what a machine needs beyond that — config keys, the
sherpa native, the models — written around what each one degrades in silence:
a stage that cannot reach its model says so in one line and carries on, so a
harness row can measure something other than it appears to. `./mw` is a
developer wrapper that rebuilds when sources change; the real artifact is the
shaded `mw-cli/target/mw.jar`.

## The primary goal

**Real commercial recordings, not synthetic fixtures.** Judge a change by what
it does to a real recording, measured by the harnesses in `tools/` against
`tools/baselines/`. If that cannot be measured, say so rather than quoting the
synthetic figure. Work that makes real audio work outranks work that polishes
what already works on synthetic audio.

How the chord, beat, key and melody stages got to where they are — what each
fix was worth and the defects each left standing — is **`docs/state.md`**;
read it before touching `ChordEstimator`, the beat grid or the melody chain.
`tools/ChordSweep.java` re-derives the chord-constant decompositions; do not
restate its figures anywhere — they have gone stale in four separate files
before.

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
mw-teacher    spec → ground-truth MIDI for synthetic_samples; nothing links it
mw-it         slow integration tests
```

**`mw-core` is the only module everything may depend on. `mw-notation` must not
depend on `mw-ml`. `mw-cli` is the only module that wires everything
together.** This is what lets the symbolic and audio tracks be built in
parallel without colliding: M1a owns `mw-notation`/`mw-arrange`, M1b owns
`mw-audio`/`mw-dsp`, and changes to `mw-core` go through a separate serialized
PR.

The edges between non-core modules: `mw-dsp` on `mw-audio`; `mw-transcribe` on
both; and **`mw-notation` on `mw-arrange`**, for `QuantizedScore` and the
per-bar `BarGrid` — the notation layer needs the quantizer's tuplet decision
and cannot re-derive it, so the fact is carried rather than inferred (#92).
Both are purely symbolic, so this pulls no audio and no models into notation.

## Licensing

Apache-2.0. The `maven-enforcer-plugin` denies a list of named artifacts, and
CI has a job for it — a list, not a licence check, so it catches what someone
has already thought of and nothing else.

- **TarsosDSP cannot be used** (GPL-3.0, and not actually on Maven Central);
  the DSP is implemented on JTransforms (BSD-2).
- **madmom's pretrained models, Open-Unmix `umxl`, and the MedleyDB / MAESTRO /
  MusicNet / Isophonics corpora are CC BY-NC-SA** and unusable here. Clean:
  Spleeter (MIT, code and models), CREPE ONNX (MIT), basic-pitch (Apache-2.0),
  OpenScore Lieder (CC0).
- **Code and weights carry separate licences** — Demucs is MIT code with
  research-only weights. Read a weights licence from the author, per
  checkpoint; `CONTRIBUTING.md` has the rule.
- LilyPond is GPL-3.0 and that is fine: invoked as a separate process, never
  linked or redistributed, and the tool works without it.
- No model weights ship in the repo. Stages download on first use into a local
  cache, checksummed, provenance beside it; `NOTICE` lists what has been chosen.
- The sherpa-onnx native (ASR) is built from a source submodule with TTS off
  (the default build statically links a GPL-3.0 espeak fork);
  `tools/check-sherpa-native.sh` asserts that, in CI too, and the flags live
  in `tools/build-sherpa-native.sh`, nowhere else.

**Lyrics and reference charts are input, not output, and are governed
separately.** Everything above is about what MW *ships*. A lyric sheet or chord
chart used to judge whether MW read a recording correctly is none of those. So,
by Enrico's decision and under his responsibility:

- **Any lyric text may be fetched and used locally** — as analysis input,
  ground truth, and harness input. No licence gate.
- **The full text stays local**, under gitignored `uncommitted/`, never
  committed and never in `NOTICE`. Using a work to measure a program is not
  publishing it; putting it in a public repository is — that is the line.
- **Short excerpts may be committed** where a fixture genuinely needs one — a
  line or two, no more than the test needs to fail for the right reason.
- Unchanged: **audio still gates on licence**, because `samples/` is committed
  and therefore redistributed. A commercial recording stays in `uncommitted/`
  with its fetch command.

## Rendering

LilyPond source is emitted **directly from the domain model**, not via
`musicxml2ly`, which is lossy. MusicXML is a parallel export, not the route to
PDF, and is not yet wired to the CLI. Without the LilyPond binary the tool
still writes the `.ly` source and says so rather than failing.

Discovery checks, in order: the `notation.lilypondPath` config key, `$PATH`,
then — on POSIX only — Homebrew and `/usr/local` prefixes. **LilyPond runs
with its message locale pinned to `C`**: engraving success is judged by
reading LilyPond's output, and LilyPond translates it. Pinning only the
message language and nothing else is delicate — see
`LilyPondRenderer.speakEnglish`, whose javadoc records the three wrong answers.

Three rules that are easy to get wrong:

- An explicit `notation.lilypondPath` is used **exactly as written**, extension
  included, and a non-executable one is an error, not a hint — silently falling
  back would ignore an explicit instruction.
- **Relative `PATH` entries are skipped**: we would be running `./lilypond`
  only because the user happened to `cd` somewhere.
- On Windows, discovery looks for `lilypond.exe` and friends and is `PATH`-only.
  **Nothing has ever been run on Windows** (#33).

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

**One PR in flight per Claude session**, not one across the repository — a
session carries one change from triage to merge; several sessions is how
several PRs run at once. The canonical process is
`.claude/agents/pr-worker.md` and `.claude/agents/pr-reviewer.md`; the
incidents that shaped it are in `docs/history.md`. Its spine: locally,
`tools/premerge.sh` on the branch merged with current `origin/main` — its
irreplaceable part is the harness diff against `tools/baselines/`, which CI
cannot run in full; then CI on the pull request as the quality gate, with
merge only on reviewer approval plus every check green on the approved head.
Round 1 is a full adversarial review; later rounds are scoped to the delta.
The project's dominant defect class is the fix that stops at the layer the bug
was noticed: **enumerate every reader of the value that changed** — reasoning
about it has repeatedly failed where running the enumeration succeeded.

## Conventions

- **Push at every milestone**, not at the end.
- **Anything not being fixed now becomes a GitHub issue**, never a `TODO`.
  Findings that are neither fixed nor filed are lost.
- Labels: `epic` + `milestone:MX`, `priority:medium|low`, `review-finding`,
  `design-gap`, `module:*`.
- Commit messages explain **why**. If a change fixes something subtle, say what
  would have gone wrong without it.
- **Good code does not need much commentary.** Javadoc and comments only where
  really necessary — a contract a caller could get wrong, a why that guards a
  known defect — and then one or two sentences pointing at the issue or the
  committed baseline, never retelling them. A member whose name and signature
  already say it gets nothing, and absent commentary is never a review finding.
  Reviewing prose is the most expensive thing this project does per unit of
  value; every sentence is a claim someone has to check.
  - **No numbers in comments or javadoc, ever.** A figure in source is stale
    before it is read. Numbers live in tests and in `tools/baselines/`; a
    comment that needs one points there.
  - **Keep the rest of the prose short too** — commit messages, issue and PR
    bodies: what a future reader strictly needs, then stop. A number is allowed
    there only if a test asserts it or a committed harness reproduces it, and
    prefer the qualitative fact even then.
  - **No superlative that is a ranking of the current corpus** — *worst*,
    *furthest*, *the only one* — since it dates the moment a benchmark is
    added. Point at the committed baseline instead. A superlative that follows
    from a mechanism is fine, because growth cannot falsify it.
  - **Do not narrate the review.** "An earlier draft said", "round 3 found" is
    process history; it belongs in the commit message or the PR, once, not in
    the source.
  - When a fact changes, grep for every statement of it before editing one.
    That is the cheapest way to stop the next round.
- **One git worktree per task, one local Maven repository per worktree**
  (`-Dmaven.repo.local` via `MAVEN_ARGS`, `-am` on every build). Never
  `git checkout` in the shared clone, and never move `refs/heads/main` from a
  worktree — one of the ways git does not refuse moves the ref for the clone
  too. A worktree that must build from `main` detaches. The incidents are in
  `docs/history.md`.
- **No raw control characters in source files** — git treats the file as
  binary, so no diff, no blame. Write escape sequences instead: in Java, a
  backslash followed by u0000, never the byte itself.

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

**The pipeline runs end to end**: a real MP3 in; beats, tempo, chords, key,
lyrics and (opt-in) melody out; chord chart and lead sheet engraved to PDF.
`docs/state.md` is the full account — stage by stage, what landed, what it was
worth on real recordings, and what is still missing (melody-on-a-mix #575,
piano #10, advisor #11). Baselines under `tools/baselines/` are the current
readings; for melody movement read premerge's output rather than CI, which
runs only one melody row.

## Sample files

`samples/` is the corpus MW is measured on. `samples/list.txt` says what is in
each recording — changes confirmed by ear, which is what makes them ground
truth rather than description — and where it came from. Committed files are
CC BY and attributed in `NOTICE`; anything not redistributable is gitignored
with the fetch command beside it (#204). `tools/score-samples.py` scores every
grid written down there and `BluesLoopIT` gates one recording in CI.

`synthetic_samples/` is the synthetic sibling: packages (spec, MIDI, rendered
MP3) generated by the music-teacher agent; the spec is ground truth by
construction — its README carries the format and rules, `docs/music-teacher.md`
the toolchain. `tools/score-synthetic.py` scores every package against its own
spec, diffed against its baseline by premerge and CI both (#447). These sit
between tiers 1 and 2 and are never quoted as product accuracy. Melody-only
packages carry no evidence for their own chord grid, so the chord harness
skips them and says so; `tools/score-melody.py` scores those.

Lyric ground truth is gated on one thing only, and it is not the words'
licence (see Licensing): a sung entry names its **language** — the hyphenation
patterns cover Italian and English and nothing else, so dialect material would
be scored against wrong syllable counts.

`docs/phone-to-corpus.md` is the route a recording takes into `uncommitted/`
or `samples/`, and what to write down beside it — including fetching a
commercial song from a link. A phone take whose `<take>.info.txt` says
`source: youtube` is commercial audio whatever it sounds like: `uncommitted/`
only, never `samples/`.
