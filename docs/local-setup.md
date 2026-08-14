# Setting up a machine

`mvn verify` and `mw analyze` on a committed sample need none of this. What
follows is for the stages that reach outside the repo — separation, lyric
transcription, forced alignment — and for running the merge gate on everything
it can measure rather than on what happens to be reachable.

Each item says **what silently degrades without it**, because that is the
failure mode all of these share: MW does not stop, it does less and says so in
a line that is easy to read past.

## The global config

`~/.config/music-wizard/config.yaml`. Providers read their model settings from
this layer *only* (#383) — a workspace's `workspace.yaml` cannot supply them,
and `analyze` warns when one tries.

```yaml
ml:
  # Where tools/build-sherpa-native.sh put the sherpa-onnx JNI native.
  sherpaNativePath: /path/to/music-wizard/third_party/sherpa-onnx/build/lib
  # Locally exported forced-alignment models, one subdirectory per language:
  # <dir>/<language>/model.onnx
  alignmentModelDirectory: /home/you/.cache/music-wizard/alignment
```

**Without `sherpaNativePath`:** lyric transcription reports that it cannot load
the native, and `tools/score-lyrics.py --source asr` skips its row.

**Without `alignmentModelDirectory`:** only the languages with a *published*
export align — today English alone. Italian lyrics keep the times the lyric
file was parsed into, spread across each line by syllable count, and `analyze`
prints `lyrics not aligned: onnx-wav2vec2 speaks [en]`. That sentence is true
and points at the provider, so the missing key is easy to miss; the engraved
sheet then shows placement nothing measured (#482).

## The sherpa-onnx native

```sh
git submodule update --init --recursive third_party/sherpa-onnx
tools/build-sherpa-native.sh
```

Built from the source submodule with TTS off, because the default build
statically links a GPL-3.0 espeak fork. `tools/check-sherpa-native.sh` asserts
that, in CI too.

## Alignment models

English downloads itself. Italian is a local export because no trusted ONNX one
is published — `docs/italian-alignment-model.md` is the recipe. Put the result
at `<alignmentModelDirectory>/it/model.onnx`.

## LilyPond

Needed for PDFs only; without it MW writes `.ly`, `.musicxml` and `.midi` and
says so. `brew install lilypond` or `apt install lilypond`, or set
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
each one. A machine short of them still passes the gate — `tools/premerge.sh`
reports those rows as skipped rather than failing them.

**Read the skip count in the verdict.** A skipped row and a passing row look
alike at a glance, and a gate that measured nothing still prints `PASS`. Adding
a column to `tools/score-lyrics.py` once broke a second baseline whose row was
skipping for want of the sherpa native, and the gate said `PASS` throughout
(#464).

## Working in a git worktree

The project uses one worktree per task. Two things there are not obvious.

**A worktree does not check out submodules.** So `third_party/sherpa-onnx` is
empty, the `sherpa` Maven profile does not activate, the ASR provider is absent
from the build, and the transcription harness row skips. Symlinking the
submodule and its build from the main clone is enough to run the gate in full:

```sh
ln -s ~/dev/music-wizard/third_party/sherpa-onnx third_party/sherpa-onnx
```

Local-only samples can be linked the same way. Neither shows up in `git status`
— `samples/` entries are gitignored by name and the submodule is a gitlink —
but check before committing, since replacing the submodule directory with a
symlink does show as a type change.

**No worktree may share `main` with the clone.** `main` is one ref: checking it
out in a worktree and resetting there moves it for every worktree at once,
including the shared clone, whose files then read as a hundred deletions
against a HEAD that moved without them. The rule written elsewhere is "never
`git checkout` in the shared clone", and it is possible to obey that and still
cause exactly what it prevents. If a worktree needs to build from `main`, give
it a detached HEAD:

```sh
git -C <worktree> checkout --detach origin/main
```
