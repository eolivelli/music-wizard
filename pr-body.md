Closes #478. Closes #477. Both are round-1 findings on #476, in code that landed the same day.

## #478 — the drift prompt cried wolf over rows that did not exist to be quoted

`baseline-drift.py` classified `set(old) != set(new)` as `moved`, so a branch
that adds a benchmark landed in `premerge.sh`s loudest arm: *"A figure this
branch quoted earlier may have been measured against the older baseline."*
Nothing quoted earlier can have been measured against a row that did not exist.
That is exactly the cry-wolf #428 was written to prevent, in the tool written to
prevent it.

Rows gained alone now exit 4, with their own quiet verdict — reported, not
warned about. What stays loud is unchanged: rows removed, rows gained beside a
figure that moved, rows gained beside a column that changed (`reshaped` outranks
`added`, because "check what you quoted from these columns" still applies to the
rows that were already there), and a file whose rows do not parse.

**One other arm overclaimed the same way**, and is fixed with it: a baseline
*file* that appeared on main took the loud arm too. Same argument, file scale; a
file that vanished stays loud.

That arm turned out to rest on a false premise about renames, found in round 1
and fixed here: `git diff --name-only` pairs a rename and names only its
destination, so a baseline renamed *and* re-measured in one commit would have
reached the classifier as a path with no older self — the new quiet arm. It is
`premerge.sh` that hands over the path list, so that is where it is fixed
(`--no-renames`), and the rename now arrives as the two halves it is. Round 1
also confirmed that `show` read "git could not answer" as "the commit does not
carry this path", which the same quiet arm then trusted; a rev git cannot
resolve is now loud and says so.

The issue is reproduced by the reading below, and PR #480 is a live instance:
it adds one synthetic row and nothing else.

```
$ python3 tools/baseline-drift.py f7a0c91^1 f7a0c91^2 tools/baselines/score-samples.txt
  score-samples.txt: 14 rows added, 0 removed
exit=4      # on main: exit=1
$ python3 tools/baseline-drift.py origin/main 56781d9 tools/baselines/score-synthetic.txt   # PR #480
  score-synthetic.txt: 1 rows added, 0 removed
exit=4      # on main: exit=1
```

## #477 — the phase row named the wrong absence

`score_phase` printed `no bar phase to read` both for a phase it could not read
and for beats carrying no confidence to divide the recorded product by. The
second is false about the phase: it is there and regular. Now its own branch and
its own sentence, `no phase confidence to read`.

## Neither fix moves a figure

`score-samples.py`s arithmetic is untouched; only which sentence an unreachable
path prints. Every phase row in `tools/baselines/score-samples.txt` is scored,
so no corpus recording reaches either branch. `baseline-drift.py` reports, it
does not measure.

## Tests — `tools/test-harness-rules.py`, which CI runs

Verified failing before the fix by binding the tests to the pre-fix modules
(`score-chart.py` re-inserts the real `tools/` on `sys.path`, so a copied tree
is not enough on its own).

Fail before, pass after:

- `test_the_paths_premerge_hands_over_keep_a_rename_in_two_halves` — runs the
  flags `premerge.sh` actually passes over a real rename in a scratch repo, so
  the option and the rule cannot drift apart.
- `test_a_rev_git_could_not_read_is_not_a_file_that_did_not_exist`.

- `test_a_row_that_only_appeared_is_reported_rather_than_warned_about` — kind
  `added`, replacing the test that asserted the defect.
- `test_the_quiet_statuses_...` — gains the status-4 case beside the existing
  three.
- `test_a_baseline_that_appeared_on_main_is_the_same_case_at_file_scale` — the
  file-scale arm, both directions.
- `test_rows_added_beside_a_column_that_changed_keep_the_column_warning`.
- `test_an_unreadable_confidence_is_not_reported_as_an_unreadable_phase` (#477)
  — asserts `bar_phase` reads the grid, so the old message was false rather than
  merely terse.

Pass before and after, pinning what must not go quiet:
`test_a_row_that_vanished_is_loud`,
`test_rows_added_beside_a_figure_that_moved_stay_loud`.

New: `test_premerge_answers_every_status_the_classifier_can_return`, which reads
`premerge.sh` and asserts every quiet status the classifier can return has an arm
of its own. An unhandled status falls into the loud default — safe, but saying
the wrong thing, which is this PR's own defect one level up.

## Not done

The row for a readable phase with no readable confidence prints no scores, as
before. `phase_roots` would still compute, but the column exists to say what the
estimator vouched for and there is nothing to vouch with; a second row format
for an unreachable path is more than the fix needs.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01QpphoazV2ATwgLePHyWeEj

