# Setting up a machine

`mvn verify` and `mw analyze` on a committed sample need none of this. What
follows is for the stages that reach outside the repo — separation, lyric
transcription, forced alignment — and for running the merge gate on everything
it can measure rather than on what happens to be reachable.

Each item says **what silently degrades without it**, because that is the
failure mode all of these share: MW does not stop, it does less and says so in
a line that is easy to read past.

## The global config

`~/.config/music-wizard/config.yaml`. Providers configure themselves from this
layer (#383), so `ml.modelCacheDirectory`, `ml.offline` and the two model
directories — `ml.alignmentModelDirectory`, `ml.asrModelDirectory` — take effect
here and nowhere else. `analyze` warns when a workspace sets one of the two
model directories, and says nothing about the other two (#493). Provider ids and
`ml.sherpaNativePath` do reach the provider from a workspace layer.

```yaml
ml:
  # Where tools/build-sherpa-native.sh put the sherpa-onnx JNI native.
  sherpaNativePath: /path/to/music-wizard/third_party/sherpa-onnx/build/lib
  # Locally exported forced-alignment models, one subdirectory per language:
  # <dir>/<language>/model.onnx
  alignmentModelDirectory: /home/you/.cache/music-wizard/alignment
```

**Without `sherpaNativePath`:** `mw analyze --lyrics-language` reports that it
cannot load the native, and transcription does not run. It does not affect
`tools/score-lyrics.py --source asr`, which pins the repo's own build rather
than reading the key — that row skips on the built file being absent.

**Without `alignmentModelDirectory`:** only the languages with a *published*
export align — today English alone. Italian lyrics keep the times the lyric
file was parsed into, spread across each line by syllable count, and `analyze`
prints `lyrics not aligned: onnx-wav2vec2 speaks [en]…`. That is true, and it
points at the provider rather than at a key, so the engraved sheet ends up
showing placement nothing measured (#482).

**`mw doctor` answers this in a second** and is the first thing to run on a new
machine: it prints the alignment provider's languages, so a model that is
present but unreachable shows as a missing language rather than as a puzzle.

## The sherpa-onnx native

```sh
git submodule update --init --recursive third_party/sherpa-onnx
tools/build-sherpa-native.sh
```

Built from the source submodule with TTS off; `CLAUDE.md` says why, and
`tools/check-sherpa-native.sh` holds it, in CI too.

## Alignment models

English downloads itself. Italian is a local export because no trusted ONNX one
is published: [`italian-alignment-model.md`](italian-alignment-model.md) is the
recipe and the layout.

## The separation model

Downloads itself on first use into `ml.modelCacheDirectory`, so there is
nothing to set up — but there is something to know, because two stages now
read the vocal stem: lyric transcription and, since #559, `--melody`.

**Offline, or with the download failing:** both say so and fall back to the
mix, and `--melody` on a band recording then returns the loudest periodic
line instead of the voice. The two `--separated` melody steps in
`tools/premerge.sh` skip every row rather than failing.

## LilyPond

Needed for PDFs only; without it MW still writes the `.ly` source and says
so. `brew install lilypond` or `apt install lilypond`, or set
`notation.lilypondPath` to the binary.

## What the committed baselines assume

`tools/baselines/` records what this project measured on a machine set up as
above. That makes a baseline a claim about the configuration as well as about
MW: with the Italian alignment model unreachable, the lyric harness's onset
column reads zero because nothing aligned, and reads a real error once the key
is in place. Both are correct readings of different machines, and only the
second is the one to commit.

So a `DIFF` on a harness row is worth reading twice. It means MW's output
moved, or it means this machine can measure something the baseline's machine
could not — or the reverse.

## Benchmarks that do not travel

`samples/` holds what is redistributable; the rest of the corpus is listed in
`samples/list.txt` and `uncommitted/list.txt` with the command that fetches
each one. A machine short of them still runs the gate — `tools/premerge.sh`
reports those rows as skipped rather than failing them.

A skipped row and a passing row used to look alike, and a gate that measured
nothing still printed `PASS`: adding a column to `tools/score-lyrics.py` once
broke a second baseline whose only row was skipping for want of the sherpa
native, and the gate said `PASS` throughout (#464). It now ends with what it
could not certify — each skipped row named with the cause its harness printed,
each step whose every row skipped called out as having certified nothing — and
says `PASS-WITH-SKIPS` rather than `PASS`.

## Declaring what this machine cannot measure

Which skips are legitimate is a fact about the machine, not about the branch:
the same skip means "never fetched here" on one machine and "this worktree was
provisioned without it" on another, and only something outside the worktree
tells them apart. So the machine declares its own, in
`$XDG_CONFIG_HOME/music-wizard/premerge-skips.txt` (`~/.config/...` by
default), one glob per line matched against `<baseline> <row>`:

```
score-asr.txt *          # no sherpa native built here
score-samples.txt gli-anni.mp3
```

With that file present, a row that skips without being covered fails the gate,
and premerge prints the line to add if the skip is real. Without it, skips are
named but not gated — which is what a fresh clone gets, and what a machine
lacking an optional model can stay on.

## Working in a git worktree

The project uses one worktree per task. Two things there are not obvious.

**A worktree does not check out submodules.** `third_party/sherpa-onnx` is left
as an empty directory, so the `sherpa` Maven profile does not activate, the ASR
provider is absent from the build, and the transcription harness row skips.
Linking the clone's copy in is enough to run the gate in full — **remove the
empty directory first**, or the link lands *inside* it, the profile still does
not activate, and nothing says so:

```sh
rmdir third_party/sherpa-onnx
ln -s ~/dev/music-wizard/third_party/sherpa-onnx third_party/sherpa-onnx
```

`rmdir` rather than `rm -rf`: run one directory up by mistake, it refuses
instead of destroying a populated submodule and a native build.

That shows as a type change in `git status`; restore it with `git checkout --
third_party/sherpa-onnx` before committing. Local-only samples can be linked
the same way and do not show, being gitignored by name.

**Nothing outside the clone may move `refs/heads/main`.** Git refuses most of
the ways — checking `main` out in a second worktree, or in the clone while a
worktree holds it — but its refusals are not complete: `checkout -B` and
`update-ref` go through. So a `git checkout main || git checkout -B main
origin/main` fallback is how this actually happened; the first half was
refused, the second was not. The clone's files then read as a hundred deletions
against a HEAD that moved without them.

If a worktree needs to build from `main`, detach instead:

```sh
git -C <worktree> checkout --detach origin/main
```
