# Process history — why the rules are what they are

The working rules live in `CLAUDE.md` and `.claude/agents/`. This file holds
the incidents behind them, so the rules stay short and the evidence stays
findable. Nothing here is normative; when this file and a rule disagree, the
rule wins.

## Fixes stop at the layer where the bug was noticed

The project's dominant defect class. Observed instances:

- A tempo-map lead-in fix silently misaligned the map by up to half a beat;
  every existing test started at `t=0.0`, where all derivations agree, so none
  could see it.
- A path-traversal fix normalised the path but never resolved symlinks
  (a shared workspace could still read `~/.ssh/id_rsa`); the symlink fix was
  then itself bypassable twice more (rounds 3–4 on `mw-core`).
- A hash-collision fix added a byte-length prefix; UTF-8 is lossy for unpaired
  surrogates, so the collision survived.
- One night, four PRs each shipped a first fix reaching one caller and missing
  another: `--tempo` taught one of two transcriber paths; the follow-up
  reached the CLI but not the chart (tool said 120 BPM, chart said 180); the
  next reached the chart's header but not its bars (PR #63). A lyric ordering
  was fixed at the accessor rather than the collection; a NaN guard was added
  at the consumer while `AudioBuffer` kept admitting NaN (#61).

Hence the reviewer's one mechanical check: enumerate every reader of the
changed value. And when a fix needs the same edit in a third place, make the
structural change that removes the choice — that is where
`Score.estimatedTempo()` came from.

## Revert-the-fix and mutation sweeps: tried, withdrawn

Reviewers were briefly required to re-run each new test against the unfixed
code (one author's suite had passed 17 of 19 that way). Withdrawn (3f6b766):
reading the test finds the same defects, and the revert destroyed uncommitted
work six times across four PRs in one day — `git checkout -- <file>` discards
everything, and the build goes green afterwards because green is what the
missing fix was meant to produce. Twice an author committed a message
describing a fix the commit did not contain.

Mutation sweeps were never required — agents generalised the revert check on
their own — and produced more false numbers than findings, in both directions:

- stale sibling artifacts from a shared `~/.m2` counted 10 of 25 mutants as
  "killed" when they had failed to *build* (PR #63);
- the same trap inside an agent's own isolated repo after an `install`
  mid-sweep (`mvn -pl` without `-am`, PR #164);
- a restored file's mtime older than its `.class` made Maven skip recompiling
  — fifteen "kills" ran against unmutated bytes (PR #163);
- three reported "survivors" were substitutions that silently never applied
  (PR #163).

Both practices are now reserved for questions reading cannot settle.

## Review rounds: from fixed minimum to two tiers

Measured over the long reviews (#91: 18 rounds, #163: ~11, #198: 14),
executable defects stopped by round three-to-five — including two
CLI-reachable crashes no suite caught (#198) — and every later round corrected
prose: results measured at one point written up as general, figures wrong by
3x/5x/16x, corrections that fixed the sentence pointed at and not the sentence
beside it resting on the same fact (four consecutive rounds of that, twice).

Hence the two-tier verdicts (d097678): executable findings force a full fresh
round; prose-only findings are fixed and confirmed in a delta pass
(`APPROVE_WITH_CORRECTIONS`). And the two writing rules: grep for every other
statement of a corrected fact; no number in prose unless a test asserts it or
a committed harness reproduces it. First PR under the new rules closed in six
rounds where its predecessor took fourteen (#215 vs #198); #234 ran two full
rounds plus seven cheap delta passes.

## Isolation: worktrees and Maven repositories

A `git checkout` in the shared clone moved HEAD under a concurrent agent and
its commit landed on another agent's branch, silently. Hence one worktree per
task. A shared `~/.m2` then carried one agent's `install`ed artifacts into
another's build — six rounds of one PR's greens were resolved against a
sibling another agent was actively rewriting (PR #81's report). Hence one
local repository per worktree, `-am` on every build, seeded from
`~/.m2-pristine` to avoid re-downloading.

## Refuted-by-measurement PRs, kept as precedent

- **PR #207**: ten rounds, fully green, refused and closed by its own author —
  scored against the music instead of the beat grid, its change was a 21-point
  regression. Refuting your own approach with numbers is respected here.
- **PR #220**: authored in a session that could not spawn an independent
  reviewer; handed over unmerged for exactly that reason. The independent
  round then found five confirmed defects self-review had missed, including a
  semantic (not textual) conflict with a PR that landed after the branch was
  cut — both changes correct alone, wrong composed.

## Assorted scar tissue

- The `t=0.0` fixture trap caught three separate changes on three issues,
  including one written by an author who had read the warning.
- A "comment-only" post-approval commit was once merged unreviewed; hence
  approval covers one commit, and `-g:none` + class-file diff proves
  "non-executable" mechanically.
- A test file once contained literal NUL bytes; git treated it as binary — no
  diff, no blame. Hence no raw control characters in sources.
- LilyPond's message locale took four attempts to pin correctly
  (`LilyPondRenderer.speakEnglish` records the three wrong answers), and its
  bar-check wording changed between 2.25.5 and 2.25.6 (#145) — the version
  split that once left CI red while local runs were green.
- `#185` → `#3`: the flat no-chord template outscores every triad on real
  audio (margin −0.15 to −0.19) while losing by +0.36 on synthetic — the sign
  flip that made real recordings the primary goal. NNLS alone did not fix it;
  the estimator's emission model needed three changes the issue never named.
- `#196`: the beat tracker's Ellis spacing penalty was transcribed in log₂ at
  weight 1 where the reference is natural-log at weight 100 — a factor of 48.
  Two lines fixed what months of downstream tuning could not reach.
