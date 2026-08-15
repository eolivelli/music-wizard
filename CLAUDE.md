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

`docs/local-setup.md` is what a machine needs beyond that — the global config
keys, the sherpa native, the alignment models — written around what each one
degrades in silence. A stage that cannot reach its model says so in one line
and carries on, so a harness row can measure something other than it appears
to, in either direction.

`./mw` is a developer wrapper that rebuilds when sources change; the real
artifact is the shaded `mw-cli/target/mw.jar`. It is ~88 MB, almost entirely
ONNX Runtime, FFmpeg natives and the Anthropic SDK.

## The primary goal

**Real commercial recordings, not synthetic fixtures.** Everything is judged
against whether it moves that. The first tier-2 run — an actual 3:43 track —
produced seven chord spans, five of them `N.C.`, one covering 169 consecutive
seconds (#185). Measured over every frame, the flat no-chord template scores
0.859 against the best possible triad's 0.713; on the synthetic fixture the same
measurement gives +0.356 the other way. **The sign flips between synthetic and
real.**

That was read as meaning no constant in `ChordEstimator` could fix it, and #3
showed otherwise. On the G blues that was the reference recording then — a
different recording, with exactly known changes — the estimator's three changes
together take *plain* chroma from 0.0% to 58.9% of bars correct, before any
front end. The flat no-chord template scores highest exactly when a frame looks
least like music, so it wins on a real mix whatever the chroma is.

No single constant does it, though, and the first draft of this paragraph
claimed one did — three changes reach 58.9% and the largest of them alone
reaches 17.5%. `ChordEstimator` carries the decomposition and is the only place
it is measured; do not restate it here, because this figure has already gone
stale in four separate files. The lesson is not "a constant can fix it" but
"the emission model was wrong in a way the front end could not compensate for".

So: work that makes real audio work outranks work that polishes what already
works on synthetic audio. NNLS chroma (#3) was the top item and has landed —
every benchmark with known ground truth went from 0% of bars correct to between
14% and 89%, and from one `N.C.` span per recording to none. The beat drift that
exposed is fixed too (#196): the tracker's spacing penalty was a forty-eighth of
the published one, so it left the grid for any loud offbeat, and the benchmarks
now score between 15% and 99% of bars correct on the tracker's own downbeats.

The chart's bar *rate* is fixed too (#200): it was the median tracked interval,
which is not a rate and is quantised to the analysis hop, and it is now the mean
of the intervals the tracker held steadily. Each of the five benchmarks that
existed then improved or held **on the root column**, the reference recording by
fifteen points and one other by twelve. Not on `root+quality`, which fell a point
or two on two of them — at the time that column was dominated by #208, whose
small movements did not mean much either way, which is exactly why the two are
quoted separately.

The chart's bar axis no longer hangs on one downbeat and one constant rate.
Where the grid's downbeats are every one of them a plausible bar, they *are* the
bar lines (#187); where they are not, the chart is one bar length hung on the
offset the downbeats agree on, keeping the first downbeat where they agree on no
offset within the beat (#233). So what the chart's bar lines are wrong by is now
what the grid is wrong by, and nothing else — on the benchmarks whose tracked
downbeats sit further from the music than one constant bar length did, the chart
sits further too, which is #424 rather than the chart's.
`tools/baselines/score-chart.txt` carries the readings.

What is now top of the bar axis is which grids to believe: the veto that decides
it catches a tracker that lost the beat and says nothing about one that is
merely jittery (#429).

Then: the vocabulary still has no major seventh,
sixth, minor sixth or half-diminished, each of which was measured and costs more
than it buys until four-note candidates can be ranked on something better than
which extra note is louder (#287, #274).

Dominant sevenths are found now (#208) — they were found on two benchmarks and
called plain triads on three others whose roots were read nearly perfectly. The
root is still decided from both registers and the quality now from the treble,
once per chord rather than per beat, which is two changes rather than one
because different benchmarks needed different halves. A large net gain that
closed nothing and cost a couple of points on the two benchmarks whose sevenths
were already being found: `ChordEstimator` carries the mechanism and
`tools/baselines/score-samples.txt` the current reading.

Minor sevenths are found too (#272), and it took two things. **The decoder's
vocabulary and the quality decision's are not the same one**: a quality the
decoder may choose competes across roots, and `Am7` is a `C` triad with an A in
it, so in the decoder it moves roots wherever the sixth degree sounds. And
`C7` and `Cm7` differ in nothing but the third, so a minor-third candidate is
scored on its notes' mass less whatever major third the root's own fifth
partial cannot account for — subtract all of the major third instead and a
blues third or a strongly voiced root turns minor chords major, which is how a
B minor blues came to be named B major.

**The corpus has a plain-triad benchmark now**, `pop-c-g-am-f-120.mp3`, every
root right on the uploader's stated grid. It is what decided the size of that
correction, and before it nothing in the scored set could tell a quality that is
found from one that is reported because nothing said not to (#273).

**The decoder reads the bass register too, as a prior over roots** (#448). Both
registers added is still a fold to pitch classes, and a fold cannot say which of
a chord's own notes is its root — which is the whole difference between a chord
and its relative minor, since a sixth added to the one gives exactly the other's
four notes. So a boogie shuffle's root-and-sixth comping reads as the relative
minor, and goes on reading that way however wide the span it is decided over;
nothing about the window fixes it. The bass says it instead. It has to be
read over about a bar rather than beat by beat, because a walking bass passes
through the third and the sixth and asserting a root at every passing note
splits a chord's run in two — and a run split in two has its quality decided
twice from half the evidence each time, which is a different defect wearing the
same clothes. `ChordEstimator` carries both constants and the sweeps;
`tools/baselines/` carries what it was worth, which was most of the corpus and
not only the shuffle.

Judge a change by what it does to a real recording. If that cannot be measured,
say so rather than quoting the synthetic figure.

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
depend on `mw-ml`. `mw-cli` is the only module that wires everything together.**
(As of #247 the stronger statement is also true — `mw-cli` is the *only* module
that depends on `mw-ml` — but that is current state, not the rule: when melody
lands, `mw-transcribe` will need a provider SPI again. The rule above survives
that; the fact does not.)
This is what lets the symbolic and audio tracks be built in parallel without
colliding — M1a owns `mw-notation`/`mw-arrange`, M1b owns `mw-audio`/`mw-dsp`,
and changes to `mw-core` go through a separate serialized PR.

One edge between non-core modules is new and worth naming: **`mw-notation`
depends on `mw-arrange`**, for `QuantizedScore` and the per-bar `BarGrid`. It
joins the ones the pipeline already had — `mw-dsp` on `mw-audio`, and
`mw-transcribe` on both `mw-audio` and `mw-dsp`. (`mw-transcribe` declared
`mw-ml` too and never wrote a line against it, which put ONNX Runtime's desktop
natives in the Android app's compile closure for nothing; #247 moved that
declaration to `mw-cli`, at runtime scope, where the wiring belongs.) The notation
layer needs the quantizer's tuplet decision and cannot re-derive it — three
onsets a third of a beat apart and three a half beat apart are both legal on the
sixth-of-a-beat grid — so the fact is carried rather than inferred (#92). Both
modules are purely symbolic, so this pulls no audio and no models into notation.

## Licensing

Apache-2.0. The `maven-enforcer-plugin` denies a list of named artifacts, and CI
has a job for it — a list, not a licence check, so it catches what someone has
already thought of and nothing else.

- **TarsosDSP cannot be used**, though it is the obvious Java DSP library for
  this domain. GPL-3.0, *and* not actually on Maven Central — what is there are
  stale unofficial forks. We implement the DSP on JTransforms (BSD-2).
- **madmom's pretrained models, Open-Unmix `umxl`, and the MedleyDB / MAESTRO /
  MusicNet / Isophonics corpora are CC BY-NC-SA** and unusable here. madmom's
  *code* is BSD; its weights are not. Clean alternatives: Spleeter (MIT for the
  code *and* the pretrained models, stated in Deezer's own paper), CREPE ONNX
  (MIT), basic-pitch (Apache-2.0), OpenScore Lieder (CC0).
- **Code and weights carry separate licences.** Demucs is the case to remember:
  MIT code, weights its author says are for scientific purposes only. Read a
  weights licence from the author, per checkpoint; `CONTRIBUTING.md` has the
  rule.
- LilyPond is GPL-3.0 and that is fine: it is invoked as a separate process,
  never linked or redistributed, and the tool works without it.
- No model weights ship in the repo. Stages that need one download it on first
  use into a local cache, checksummed, provenance beside it; `NOTICE` lists
  what has been chosen so far.
- The sherpa-onnx native (ASR) is built from a source submodule with TTS off,
  because the default build statically links a GPL-3.0 espeak fork.
  `tools/check-sherpa-native.sh` asserts the built library against that, in CI
  too; the flags live in `tools/build-sherpa-native.sh`, nowhere else.

**Lyrics and reference charts are input, not output, and are governed
separately.** Everything above is about what MW *ships* — code it links, weights
it would download, audio it commits. A lyric sheet or a chord chart used to
judge whether MW read a recording correctly is none of those. MW analyses
recordings; it does not perform music, does not publish anyone's words, and does
not generate content from them.

So, by Enrico's decision and under his responsibility:

- **Any lyric text may be fetched and used locally** — as analysis input, as
  ground truth, and as harness input. No licence gate, and a source that states
  no licence is not thereby excluded.
- **The full text stays local.** Lyric files live under `uncommitted/`, which is
  gitignored, and are never committed and never listed in `NOTICE`. Using a work
  to measure a program is not publishing it; putting it in a public repository
  is, and that is the line, not the intent behind it.
- **Short excerpts may be committed** where a test fixture or a baseline
  genuinely needs one — a line or two, no more than the test needs to fail for
  the right reason.
- Unchanged: **audio still gates on licence**, because `samples/` is committed
  and therefore redistributed. A commercial recording stays in `uncommitted/`
  with its fetch command, as it always has.

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

**One PR in flight per Claude session**, not one across the repository. A
session carries one change from triage to merge and does not start a second;
running several sessions is how several PRs run at once. The rules live in
`.claude/agents/pr-worker.md` and `pr-reviewer.md`; the incidents that shaped
them are in `docs/history.md`. The short version:

- **Two-stage gate.** Locally, `tools/premerge.sh` (branch merged with
  current `origin/main`) — its irreplaceable part is the harness diff against
  `tools/baselines/`, which CI cannot run in full because the local-only
  benchmark files never leave this machine. Any movement fails; intended
  improvements regenerate the baseline in the same PR. It leaves the test
  suites to CI, which runs them on the merge preview anyway; `--full` runs
  them locally too. **Finally, CI on the pull request is the quality gate**:
  full matrix against the merge preview; merge only on reviewer approval plus
  every check green on the approved head.
- **Round 1 is a full adversarial review; later rounds are scoped to the
  delta.** Loop until a round finds nothing new, or only prose
  (`APPROVE_WITH_CORRECTIONS` → delta pass on the changed text → merge).
  Findings are `CONFIRMED` by execution or honestly `PLAUSIBLE`.
- **The one mechanical check every round: enumerate every reader of the value
  that changed.** It is the project's dominant defect class and reasoning
  about it has repeatedly failed where running it succeeded.
- **Prose discipline:** no number outside a test or committed harness; when a
  fact is corrected, grep for its every other statement before replying.
- Revert-the-fix verification and mutation sweeps are **not** used; both were
  tried and withdrawn (`docs/history.md`).

## Conventions

- **Push at every milestone**, not at the end.
- **Anything not being fixed now becomes a GitHub issue**, never a `TODO`. A
  review that finds ten things produces fixes for the serious ones and issues
  for the rest; findings that are neither fixed nor filed are lost.
- Labels: `epic` + `milestone:MX`, `priority:medium|low`, `review-finding`,
  `design-gap`, `module:*`.
- Commit messages explain **why**. If a change fixes something subtle, say what
  would have gone wrong without it.
- **Keep prose short.** Comments, javadoc, commit messages, issue and PR bodies:
  write what a future reader strictly needs and stop. Reviewing prose is the
  most expensive thing this project does per unit of value, and every sentence
  is a claim someone has to check.
  - **Prefer the qualitative fact to the figure.** A number invites
    verification, dates as soon as anything moves, and has to be restated
    everywhere it appears. Give one only where it decides something.
  - **No superlative that is a ranking of the current corpus** — *worst*,
    *furthest*, *the only one* — since it dates the moment a benchmark is added.
    Point at the committed baseline instead. A superlative that follows from a
    mechanism is fine and often the clearest thing to write, because growth
    cannot falsify it. On #200 four successive drafts of one paragraph each
    claimed a ranking the data did not hold.
  - **Do not narrate the review.** "An earlier draft said", "round 3 found",
    "corrected in review" is process history; it belongs in the commit message
    or the PR, once, not in the source. Fix the sentence and move on.
  - When a fact changes, grep for every statement of it before editing one. That
    is the cheapest way to stop the next round.
- **One git worktree per task, one local Maven repository per worktree**
  (`-Dmaven.repo.local` via `MAVEN_ARGS`, `-am` on every build). Its first
  build downloads the dependency closure; that is the price of the isolation.
  Never `git checkout` in the shared clone, and never move `refs/heads/main`
  from a worktree: git refuses most of the ways but not all of them, and one
  that goes through moves the ref for the clone too — whose files then read as
  deletions against a HEAD that moved without them. A worktree that must build
  from `main` detaches. The incidents behind each half are in
  `docs/history.md`.
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
tracking, tuning-corrected chroma, chord recognition, key naming, chord chart,
LilyPond). Four review rounds on `mw-core`.

Key detection (#275) reads the estimated chords, not chroma, and reports two
confidences because it makes two decisions of very different reliability: the
key signature, and which of a relative pair is home. The second is what fails —
a loop that neither begins nor ends on its tonic gives it nothing to work with,
and it answers at the coin-flip floor rather than pretending. `KeyEstimator`
carries the rules and `tools/baselines/score-samples.txt` carries the scores.

The lyrics chain (#9) runs end to end: vocal separation (#312), forced
alignment of supplied LRC lyrics (#313), and transcription from the audio
itself (#314, Qwen3-ASR through a sherpa-onnx source submodule, built by
`tools/build-sherpa-native.sh` and present only when that has run). The
transcriber knows words but not their times — sherpa's Qwen3 emits none — so
words are spread across their sung stretch and the aligner measures onsets
where it speaks the language, which today is English only.
**What the melody stage is worth is known now, on real singing.** `vocadito`
— 40 clips of solo voice annotated note by note by two trained musicians, CC BY
4.0, fetched into `uncommitted/` — is scored by `tools/score-melody.py --source
vocadito`, and it carries its own ceiling: each row prints one annotator scored
against the other by the same rule. That ceiling is nowhere near 100%, because
where a sung note begins is genuinely ambiguous — the two annotators do not
even agree how many notes a clip holds. MW sits close under it. Read the
baseline rather than a figure quoted here.

Two things that measurement overturned, both of which had been believed on the
strength of how a page looked:

- **Real sung notes are short**, most of them under a quarter of a second. A
  melody stage returning notes that length is not fragmenting, and a rule that
  absorbed short notes would destroy real music.
- **On a mix the melody stage's accuracy is a statement about separation, not
  about the melody stage.** `tools/measure-separation-cost.py` scores the same
  annotated voices three ways — clean, through the separator with no band, and
  through it with a band mixed in. The middle row costs almost nothing, so the
  separator does not spoil a voice by itself; the whole loss appears once a
  band is there. What that gap is made of the tool cannot say, and the two
  candidates want opposite fixes: band the mask failed to remove, or voice the
  mask removed with it (#503). How far the voice sits above the band is the
  variable that tool now states rather than inherits, and it can sweep it, so
  ask it for the curve rather than a figure. Absolute level is a second axis and
  is not controlled — the separator is not level-invariant even at a fixed ratio
  (#515) — so its rows carry each clip's own level beside them. None of it is
  baselined.

**Melody is read from a signal that holds nothing else (#494).**
pYIN in `mw-dsp`, segmented into notes, engraved as a lead sheet — melody
staff, chord symbols, lyrics. The stage is off unless `analyze --melody` asks
for it, and that is the whole shape of the thing: a monophonic tracker does not
fail on a full mix, it confidently returns the loudest periodic line, which on
a band is the bass. The corpus carries both cases and
`tools/baselines/score-melody.txt` scores them side by side, the accompanied
packages as controls rather than targets. What is not solved is *when* a note
starts (#497) and two notes of one pitch abutting (#495); both want the onset
envelope, which this stage deliberately does not read yet.

Still missing: melody from a real mix, which is separation's problem rather
than the tracker's, piano
(#10), advisor (#11). The symbolic track (#1) is four-fifths landed and parked.
NNLS chroma (#3) and the Ellis-penalty correction (#196) have landed;
`tools/score-samples.py` and `tools/score-chart.py` are the standing
measurement of what they are worth, with baselines under `tools/baselines/`.

`mw-core` passed round 4 once its three blockers landed, but see the open
`design-gap` issues before treating it as frozen — especially #4 (no beat unit,
so compound meters mis-bar) and #5 (notation-facing gaps).


## Sample files

`samples/` is the corpus MW is measured on, and the reference test set for
whether the output is any good. `samples/list.txt` says what is in each
recording — changes confirmed by ear, which is what makes them ground truth
rather than description — and where it came from. Committed files are CC BY and
attributed in `NOTICE`; anything not redistributable is gitignored with the
fetch command beside it, so a fresh clone is short of benchmarks rather than
short of a licence (#204).

`synthetic_samples/` is the corpus's synthetic sibling: packages
(spec, MIDI, rendered MP3) generated by the music-teacher agent
(`.claude/agents/music-teacher.md`) to teach MW the common harmonic patterns of
mainstream genres. The spec is ground truth by construction — see its README.
Rendering uses FluidSynth and a cached, checksummed soundbank that is never
committed (`tools/music-teacher/`). Coverage is tracked as one
`synthetic-sample` issue per package; `tools/score-synthetic.py` scores every
package against its own spec, sequence-aligned, and both premerge and CI diff
it against `tools/baselines/score-synthetic.txt` — CI can run this harness in
full because every package is committed (#447). These sit between tiers 1 and
2 and are never quoted as product accuracy.

Some packages are for the melody stage instead: a `melody-level` on a 1-to-4
difficulty ramp, and an `accompaniment` of `pad` or `none`. A package with no
accompaniment carries no evidence for its own chord grid, so the chord harness
skips it and says so rather than scoring noise; `tools/score-melody.py` is what
scores those, against each package's own MIDI melody track, in seconds. A pair
of packages differing in nothing but the pad is what measures what harmony
under a melody costs, which is why a graded melody draws from its own random
stream rather than the arrangement's.

`tools/score-samples.py` scores every grid written down there and
`BluesLoopIT` gates one recording in CI. Lyric ground truth is gated on one
thing only, and it is not the words' licence (see Licensing above): a sung entry
names its **language**, because the hyphenation patterns cover Italian and
English and nothing else, so dialect material would be scored against wrong
syllable counts. `docs/phone-to-corpus.md` is the route
a recording made with the phone app takes into `uncommitted/` or `samples/`,
and what to write down beside it. The app can also fetch a shared YouTube link
as a take, and those have only one of those two destinations: the bundle's
`<take>.info.txt` carries a `source:` line, and `source: youtube` means
commercial audio whatever it sounds like.
