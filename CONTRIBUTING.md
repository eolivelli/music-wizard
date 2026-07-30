# Contributing

## Licensing policy

The project is Apache-2.0. Dependencies are constrained accordingly, and the
rule is enforced mechanically by the `maven-enforcer-plugin` rather than by
convention:

| Licence | Allowed? |
|---|---|
| Apache-2.0, MIT, BSD | Yes |
| LGPL | Yes, as an **unmodified library dependency** only |
| GPL, AGPL | **No** |
| CC BY-NC-SA (models and datasets) | **No** |

Two consequences worth knowing before you reach for the obvious thing:

- **TarsosDSP is not usable here.** It is the natural Java DSP library for this
  domain, but it is GPL-3.0, and it is not on Maven Central either — the
  artifacts that are there are stale unofficial forks. We implement the DSP we
  need on top of JTransforms (BSD-2).
- **Several standard MIR resources are non-commercial.** madmom's *pretrained
  models* (the code is BSD, the weights are not), the Open-Unmix `umxl`
  weights, and the MedleyDB, MAESTRO, MusicNet and Isophonics corpora are all
  CC BY-NC-SA and cannot be used or vendored. Clean alternatives exist for every
  one: Demucs ONNX (MIT), CREPE ONNX (MIT), basic-pitch (Apache-2.0), and the
  OpenScore Lieder corpus (CC0).

LilyPond is GPL-3.0, which is fine because it is invoked as a separate process,
never linked or redistributed, and the program works without it.

## Module structure

```
mw-core         domain model, workspace, config. Depends on nothing but serialization.
mw-audio        decoding, resampling, framing, STFT
mw-dsp          onsets, beats, chroma, chords, key, pitch tracking
mw-ml           provider SPI, ONNX implementations, model cache
mw-transcribe   stage orchestration: audio or MIDI to Score
mw-arrange      piano reduction and part extraction (purely symbolic)
mw-notation     Score to LilyPond, MusicXML, MIDI, PDF
mw-llm          Claude advisor layer
mw-cli          picocli entry point
mw-testkit      MIDI fixtures, synthesis, evaluation metrics
mw-it           slow integration tests
```

**The dependency rule:** `mw-core` is the only module everything may depend on.
`mw-notation` must not depend on `mw-ml`. `mw-cli` is the only module that wires
everything together. This is what lets the symbolic and audio tracks be built
in parallel without colliding.

## Two rules that govern the pipeline

These are not style preferences; violating either produces output that is
subtly and confusingly wrong.

1. **Chords are estimated from the full mix, never from separated stems.**
   Separation artifacts destroy the partial structure that chroma estimation
   depends on. Separation exists to feed melody, bass and lyrics only.

2. **Nothing downstream of the beat grid works in seconds.** Once beats are
   known, every time value is expressed in quarter-note beats. `TempoMap` is the
   only sanctioned conversion between the two. This is what makes quantization,
   chord alignment, lyric placement and arrangement mutually consistent for
   free. Note and `Chord` carry *optional* musical timing precisely so that an
   un-quantized value cannot be mistaken for a quantized one.

## Testing

Four tiers, in increasing order of realism and decreasing order of trust:

- **Tier 0** — synthetic MIDI rendered through the JDK's built-in synth. Must be
  near-perfect; any regression here is a real bug. A 120 BPM click must yield
  120 BPM.
- **Tier 1** — synthetic multi-track mixes. Ground truth for stems, notes,
  chords and an *exact* beat grid comes free from the source MIDI.
- **Tier 2** — real audio, permissively licensed only.
- **Tier 3** — loop closure: score → LilyPond → MIDI → re-analyse → compare.

**Synthetic audio is systematically easier than real audio.** Tiers 0 and 1 are
regression gates whose thresholds must never drop; tier 2 is the number we
actually believe. Never quote a synthetic result as the product's accuracy.

Notation is tested by golden files over the generated LilyPond and MusicXML
*text*, which is diffable and stable. PDFs are checked only for existence and
page count.

`mvn verify` must stay fast, offline, and free of any external binary. Anything
that downloads a model or shells out to LilyPond belongs in `mw-it` behind
`-Pintegration`.

## Mutation sweeps

To find out whether a test suite would actually notice a change, break the code
on purpose and see if anything goes red. Do it through the harness:

```sh
tools/mutation-sweep.sh --maven-repo "$PWD/.m2" \
  --file mw-core/src/main/java/dev/olivelli/musicwizard/core/model/TempoMap.java \
  --find 'beatSeconds.size() < 2' --replace 'beatSeconds.size() < 1'
```

or, for more than one, a mutants file:

```
mutant:  tempo-min-beats
file:    mw-core/src/main/java/dev/olivelli/musicwizard/core/model/TempoMap.java
find:    beatSeconds.size() < 2
replace: beatSeconds.size() < 1

mutant:  tempo-pulse-lower-bound
file:    mw-core/src/main/java/dev/olivelli/musicwizard/core/model/TempoMap.java
find:    pulseQuarters < 1.0 / 1024
replace: pulseQuarters < 0
```

```sh
tools/mutation-sweep.sh --maven-repo "$PWD/.m2" --mutants mutants.txt --report sweep.tsv
```

Each mutant is reported as `KILLED` and named with the test that killed it,
`SURVIVED`, or — and this is the distinction that matters — `ERROR` /
`BUILD-ERROR`, meaning the run measured nothing. Exit codes: 0 all killed,
1 some survived, 2 refused to start, 3 nothing trustworthy measured, 4 the tree
was left modified.

**Do not run a sweep by hand.** Not as a matter of taste: the obvious four-line
shell loop has produced six destroyed fixes and four distinct kinds of wrong
number on this project, every one of them looking exactly like a result.

| What went wrong | What the harness does |
|---|---|
| `git checkout --` reverted the mutation *and* the author's uncommitted fix, six times in one day | Runs no mutating git command at all. Refuses to mutate a file that is dirty; reverts from a byte-exact copy, verified against both the copy and git. Uncommitted work in every other file is untouched |
| A restored file older than its `.class` made Maven skip recompiling, so fifteen "kills" ran against the previous mutant's bytes | Forces the source mtime past every timestamp under the module's `target/` before each build |
| A substitution that matched nothing was reported as a surviving mutant | Counts occurrences before mutating and after restoring; a miss, or an ambiguous match, is an error |
| A stale sibling made the mutant fail to *build*, and the summary counted it as killed | Always `-am` and an explicit non-shared `-Dmaven.repo.local`; a kill requires a named failing test in a surefire report from that build |

Two more things it does that are easy to skip by hand: it runs the suite
unmutated first, because a sweep against an already-red suite reports every
mutant as killed; and it restores on `Ctrl-C`. A `kill -9` leaves an untracked
`.mutation-sweep-active` file at the repository root naming the backups — the
next sweep refuses to start until `tools/mutation-sweep.sh --recover` has undone
it.

The harness has its own tests, in `tools/mutation-sweep-test.sh`, which
reproduce each of the four failures above against a throwaway git repository and
a stub Maven and check that the harness now refuses or reports them. They run in
`mvn verify` — about two seconds, offline. `--with-maven` adds a slower test
that sweeps a real mutant in `TempoMap` with the real Maven, over a clone of the
repository.

## Review process

Every patch is reviewed for a **minimum of three rounds**:

1. **Correctness and design** — arithmetic, logic, validation gaps, API shape.
2. **Tests and edge cases** — coverage, boundaries, failure modes.
3. **Adversarial verification** — confirm the earlier findings were genuinely
   fixed and nothing regressed.

Reviewers should confirm suspected bugs by execution before reporting them as
confirmed, and should say explicitly what they checked and found correct so the
next round need not redo it.

## Issue tracking

Anything that is not being fixed right now goes in a GitHub issue rather than a
`TODO` comment or somebody's memory. In particular, a review round that finds
ten things should produce fixes for the serious ones and *issues* for the rest —
findings that are neither fixed nor filed are simply lost.

Labels:

| Label | Meaning |
|---|---|
| `epic` + `milestone:MX` | A milestone's umbrella issue |
| `priority:medium` | Should land before its milestone closes |
| `priority:low` | Real, but not blocking anything |
| `review-finding` | Raised by a review round |
| `design-gap` | A model or API gap to close before dependants hit it |
| `module:*` | Which module owns it |

## Working alongside other agents

Use a dedicated `git worktree` per task rather than the shared checkout. A
`git checkout` moves HEAD for every process using that clone, and a commit made
while HEAD is moving lands on somebody else's branch — silently, with a clean
test run either side of it.

```sh
git worktree add /tmp/wt-issue-42 -b issue-42-fix origin/main
cd /tmp/wt-issue-42
export MAVEN_ARGS="-Dmaven.repo.local=/tmp/wt-issue-42/.m2"
```

Give each worktree its own local Maven repository as well. The worktree isolates
the source tree; `~/.m2` is the channel it does not isolate — an artifact one
task installs becomes another task's dependency, so a reactor build can resolve
a sibling module from somebody else's uncommitted work instead of from your own
source. Build with `-am` for the same reason, so siblings are built rather than
resolved.

The failure this prevents is quiet rather than loud: a mutation sweep run as
`mvn -pl mw-dsp` against a stale `mw-audio` produced ten mutants that failed to
compile, which the summary reported as killed — full coverage, from a build that
never ran the tests. Expect the first build in a fresh repository to re-download
the dependency set.

## Pushing

Push to `origin/main` at every milestone, not at the end. The work is long
running and the repository is the shared record of it; a week of unpushed
commits is a week nobody else can see, review, or build on.

## Commit messages

Explain *why*, not *what* — the diff already shows what changed. If a change
fixes something subtle, say what would have gone wrong without it.
