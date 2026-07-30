#!/usr/bin/env bash
#
# Copyright 2026 Music Wizard contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# ---------------------------------------------------------------------------
# Self-test for tools/mutation-sweep.sh.
#
# A mutation sweep is a measuring instrument, and this project has been misled
# by one four separate ways, each time by a confident number nobody had
# measured (issue #177). So the harness is tested the way the instrument is
# supposed to be used: by reproducing each of those four failures and checking
# that it now refuses, reports or survives them.
#
# Everything except the last test runs against a throwaway git repository and a
# stub `mvn` placed first on PATH. The stub is not a mock of the answer -- it
# models the parts of Maven that caused the failures: it recompiles only when
# the source is newer than the class file, it runs its "tests" against the
# compiled snapshot rather than the source, and it writes real surefire XML.
# That is what lets a stale-class false kill be constructed on purpose.
#
# The last test runs the real harness with the real Maven over a clone of this
# repository, mutating TempoMap, so that the whole thing is known to work on
# something other than a fixture. It needs Maven and a populated local
# repository, so it is opt-in:
#
#   tools/mutation-sweep-test.sh              # fast, offline, no Maven
#   tools/mutation-sweep-test.sh --with-maven # adds the real-code test
# ---------------------------------------------------------------------------

set -uo pipefail   # deliberately not -e: most tests here expect a failure

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
HARNESS="$SCRIPT_DIR/mutation-sweep.sh"

WITH_MAVEN=0
ONLY=''
while [ $# -gt 0 ]; do
    case "$1" in
        --with-maven) WITH_MAVEN=1; shift ;;
        --only) ONLY=${2:?--only needs a test name}; shift 2 ;;
        -h|--help)
            printf 'Usage: %s [--with-maven] [--only <test name>]\n' "$0"; exit 0 ;;
        *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE 2>/dev/null || true

TESTS_RUN=0; TESTS_FAILED=0; TESTS_SKIPPED=0
FAILED_NAMES=()
CURRENT=''
CURRENT_FAILED=0

SANDBOX=$(mktemp -d "${TMPDIR:-/tmp}/mw-sweep-selftest.XXXXXX")
cleanup() { rm -rf "$SANDBOX"; }
trap cleanup EXIT

say()  { printf '%s\n' "$*"; }
fail() {
    CURRENT_FAILED=1
    printf '    FAIL: %s\n' "$*"
}

assert_status() {
    local want=$1 got=$2 what=$3
    if [ "$got" -ne "$want" ]; then
        fail "$what: expected exit $want, got $got"
    fi
}

assert_contains() {
    local haystack=$1 needle=$2 what=$3
    case "$haystack" in
        *"$needle"*) ;;
        *) fail "$what: expected output to contain '$needle'"; printf '%s\n' "$haystack" | sed 's/^/      | /' ;;
    esac
}

assert_not_contains() {
    local haystack=$1 needle=$2 what=$3
    case "$haystack" in
        *"$needle"*) fail "$what: output should not contain '$needle'"; printf '%s\n' "$haystack" | sed 's/^/      | /' ;;
    esac
}

assert_file_is() {
    local path=$1 want=$2 what=$3 got
    got=$(cat "$path" 2>/dev/null)
    if [ "$got" != "$want" ]; then
        fail "$what: $path contains '$got', expected '$want'"
    fi
}

run_test() {
    local name=$1
    if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then
        return 0
    fi
    CURRENT=$name
    CURRENT_FAILED=0
    TESTS_RUN=$((TESTS_RUN + 1))
    # Every test starts from the real environment. Leaking the previous test's
    # stub `mvn` into the next one would let a test pass against a fixture it
    # was never meant to touch -- which is the same class of mistake the
    # harness itself exists to prevent.
    export PATH=$PATH_ORIG
    unset FAKE_MVN_LOG FAKE_MVN_MODE FAKE_SRC FAKE_CLASS FAKE_REPORTS \
          FAKE_LETHAL FAKE_CLASS_SKEW FAKE_MVN_SLEEP
    cd "$SANDBOX" || exit 1
    say "==> $name"
    "test_$name"
    if [ "$CURRENT_FAILED" -ne 0 ]; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
        FAILED_NAMES+=("$name")
        say "    FAILED"
    else
        say "    ok"
    fi
}

skip() {
    TESTS_SKIPPED=$((TESTS_SKIPPED + 1))
    say "    SKIPPED: $*"
}

# ---------------------------------------------------------------------------
# Fixture: a miniature repository plus a stub Maven that behaves like the real
# one in the two respects that have produced wrong numbers here.
# ---------------------------------------------------------------------------

# new_fixture <dir> -- prints nothing; sets up $1 as a git repo and returns.
new_fixture() {
    local dir=$1
    mkdir -p "$dir/fixmod/src/main/java" "$dir/notes"
    cat >"$dir/.gitignore" <<'EOF'
target/
EOF
    cat >"$dir/pom.xml" <<'EOF'
<project><modules><module>fixmod</module></modules></project>
EOF
    cat >"$dir/fixmod/pom.xml" <<'EOF'
<project><artifactId>fixmod</artifactId></project>
EOF
    cat >"$dir/fixmod/src/main/java/Widget.java" <<'EOF'
final class Widget {
    static final int LIMIT = 7;
    static final int SPARE = 3;
    static final int OTHER = 7;
}
EOF
    cat >"$dir/fixmod/src/main/java/Helper.java" <<'EOF'
final class Helper {
    static int twice(int n) { return n + n; }
}
EOF
    printf 'scratch\n' >"$dir/notes/scratch.txt"
    git -C "$dir" init -q
    git -C "$dir" add -A
    git -C "$dir" -c user.email=selftest@example.com -c user.name=selftest \
        commit -qm 'fixture'
}

# make_stub_mvn <bindir> -- writes a `mvn` that models incremental compilation.
#
# Environment it reads, all set per test:
#   FAKE_MVN_LOG     append one line per invocation, plus recompile decisions
#   FAKE_MVN_MODE    normal | buildfail | notests | slow
#   FAKE_MVN_SLEEP   seconds to sleep in `slow` mode
#   FAKE_SRC         the source file it "compiles"
#   FAKE_CLASS       where the compiled snapshot goes
#   FAKE_REPORTS     surefire report directory
#   FAKE_LETHAL      text which, present in the *compiled snapshot*, fails a test
#   FAKE_CLASS_SKEW  seconds to add to the class file's mtime after compiling
make_stub_mvn() {
    local bindir=$1
    mkdir -p "$bindir"
    cat >"$bindir/mvn" <<'STUB'
#!/usr/bin/env bash
set -u
printf 'ARGS %s\n' "$*" >>"$FAKE_MVN_LOG"

case "${FAKE_MVN_MODE:-normal}" in
    buildfail)
        # What a stale sibling, or a mutation that will not compile, looks like:
        # Maven fails and no test ever runs.
        printf '[ERROR] cannot find symbol: Sibling.method()\n'
        printf '[INFO] BUILD FAILURE\n'
        exit 1 ;;
    buildfail-on-mutant)
        # The realistic shape of failure mode 4: the baseline is green, and the
        # build only breaks once the mutant is in place -- because a sibling
        # went stale mid-sweep, or because the mutation does not compile.
        if grep -qF "${FAKE_LETHAL:-__none__}" "$FAKE_SRC"; then
            printf '[ERROR] cannot find symbol: Sibling.method()\n'
            printf '[INFO] BUILD FAILURE\n'
            exit 1
        fi ;;
    notests)
        printf '[INFO] BUILD SUCCESS\n'
        exit 0 ;;
    slow)
        sleep "${FAKE_MVN_SLEEP:-30}" ;;
esac

mkdir -p "$(dirname "$FAKE_CLASS")" "$FAKE_REPORTS"

# Maven's incremental compiler rebuilds only when the source is strictly newer
# than the output. Model exactly that, and nothing else.
if [ ! -f "$FAKE_CLASS" ] || [ "$FAKE_SRC" -nt "$FAKE_CLASS" ]; then
    cp "$FAKE_SRC" "$FAKE_CLASS"
    if [ -n "${FAKE_CLASS_SKEW:-}" ]; then
        perl -e 'my $t = time() + $ARGV[1]; utime($t, $t, $ARGV[0]) or die $!;' \
            "$FAKE_CLASS" "$FAKE_CLASS_SKEW"
    fi
    printf 'RECOMPILED yes\n' >>"$FAKE_MVN_LOG"
else
    printf 'RECOMPILED no\n' >>"$FAKE_MVN_LOG"
fi

# The tests run against the compiled snapshot, never against the source. This
# is the whole point: a build that skipped compiling tests the previous bytes.
if grep -qF "${FAKE_LETHAL:-__none__}" "$FAKE_CLASS"; then
    cat >"$FAKE_REPORTS/TEST-fixture.WidgetTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fixture.WidgetTest" tests="2" failures="1">
  <testcase name="limitIsSeven" classname="fixture.WidgetTest">
    <failure message="expected 7"/>
    <system-out>an error occurred somewhere, which must not be read as one</system-out>
  </testcase>
  <testcase name="spareIsPositive" classname="fixture.WidgetTest"/>
</testsuite>
XML
    printf '[INFO] BUILD FAILURE\n'
    exit 1
fi

cat >"$FAKE_REPORTS/TEST-fixture.WidgetTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fixture.WidgetTest" tests="2" failures="0">
  <testcase name="limitIsSeven" classname="fixture.WidgetTest"/>
  <testcase name="spareIsPositive" classname="fixture.WidgetTest"/>
</testsuite>
XML
printf '[INFO] BUILD SUCCESS\n'
exit 0
STUB
    chmod +x "$bindir/mvn"
}

# setup_case <name> -- creates $REPO, $BIN, exports the stub's environment and
# leaves the caller in $REPO. Prints nothing.
setup_case() {
    CASE_DIR="$SANDBOX/$1"
    REPO="$CASE_DIR/repo"
    BIN="$CASE_DIR/bin"
    mkdir -p "$CASE_DIR"
    new_fixture "$REPO"
    make_stub_mvn "$BIN"
    export PATH="$BIN:$PATH_ORIG"
    export FAKE_MVN_LOG="$CASE_DIR/mvn.log"
    export FAKE_MVN_MODE=normal
    export FAKE_SRC="$REPO/fixmod/src/main/java/Widget.java"
    export FAKE_CLASS="$REPO/fixmod/target/classes/Widget.class"
    export FAKE_REPORTS="$REPO/fixmod/target/surefire-reports"
    export FAKE_LETHAL="LIMIT = 99"
    export FAKE_CLASS_SKEW=''
    export FAKE_MVN_SLEEP=30
    : >"$FAKE_MVN_LOG"
    cd "$REPO" || exit 1
}

PATH_ORIG=$PATH

# sweep <args...> -- runs the harness in $REPO, captures combined output in
# $OUT and the status in $STATUS.
sweep() {
    OUT=$( cd "$REPO" && "$HARNESS" --maven-repo "$CASE_DIR/m2" "$@" 2>&1 )
    STATUS=$?
}

lethal_mutant_args=(--id lethal --file fixmod/src/main/java/Widget.java
                    --find 'LIMIT = 7' --replace 'LIMIT = 99')

# ---------------------------------------------------------------------------
# Failure mode 1 -- the revert that ate the author's fix
# ---------------------------------------------------------------------------

test_refuses_when_the_file_to_mutate_is_dirty() {
    setup_case dirty-target
    # The exact situation from #141, #151, #163 and #172: an uncommitted fix
    # sitting in the file the sweep is about to edit and revert.
    printf 'final class Widget {\n    static final int LIMIT = 7; // the fix\n    static final int SPARE = 3;\n    static final int OTHER = 7;\n}\n' \
        >"$FAKE_SRC"

    sweep "${lethal_mutant_args[@]}"

    assert_status 2 "$STATUS" "dirty target file"
    assert_contains "$OUT" "has uncommitted changes" "dirty target file"
    assert_contains "$OUT" "Refusing" "dirty target file"
    if ! grep -qF '// the fix' "$FAKE_SRC"; then
        fail "the uncommitted fix was destroyed by a run that was supposed to refuse"
    fi
    if [ -s "$FAKE_MVN_LOG" ]; then
        fail "maven was invoked despite the refusal"
    fi
}

test_uncommitted_work_elsewhere_survives_a_sweep() {
    setup_case other-dirty
    # Uncommitted work that is *not* in a mutated file must come through
    # untouched -- this is what `git checkout --`, `git stash` and `git clean`
    # each get wrong in their own way, and why the harness runs none of them.
    #
    # One of the three is inside the module being swept and next to the file
    # being mutated. A harness that reverts by `git checkout -- <module>`, or
    # by stashing, passes the other two and destroys this one.
    printf 'scratch\nan uncommitted line\n' >"$REPO/notes/scratch.txt"
    printf 'untracked\n' >"$REPO/notes/brand-new.txt"
    printf 'final class Helper {\n    static int twice(int n) { return n * 2; } // uncommitted fix\n}\n' \
        >"$REPO/fixmod/src/main/java/Helper.java"

    cat >"$CASE_DIR/mutants" <<'EOF'
# two mutants in one file: one the suite catches, one it does not
mutant:  lethal
file:    fixmod/src/main/java/Widget.java
find:    LIMIT = 7
replace: LIMIT = 99

mutant:  harmless
file:    fixmod/src/main/java/Widget.java
find:    SPARE = 3
replace: SPARE = 4
EOF

    sweep --mutants "$CASE_DIR/mutants"

    assert_status 1 "$STATUS" "one survivor"
    assert_contains "$OUT" "KILLED" "sweep result"
    assert_contains "$OUT" "SURVIVED" "sweep result"
    assert_file_is "$REPO/notes/scratch.txt" "$(printf 'scratch\nan uncommitted line')" \
        "uncommitted edit elsewhere"
    assert_file_is "$REPO/notes/brand-new.txt" "untracked" "untracked file elsewhere"
    if ! grep -qF '// uncommitted fix' "$REPO/fixmod/src/main/java/Helper.java"; then
        fail "an uncommitted fix beside the mutated file, in the same module, was destroyed"
    fi
    local status_out
    status_out=$(git -C "$REPO" status --porcelain -- fixmod/src/main/java/Widget.java)
    if [ -n "$status_out" ]; then
        fail "the mutated file was left dirty: $status_out"
    fi
    assert_contains "$OUT" "other uncommitted changes" "warning about the rest of the tree"
}

# ---------------------------------------------------------------------------
# Failure mode 2 -- the stale .class
# ---------------------------------------------------------------------------

test_a_stale_class_cannot_produce_a_kill() {
    setup_case stale-class
    # Construct the hazard deliberately: every compile leaves the class file
    # timestamped an hour in the future. That is what a clock-skewed network
    # mount does, and it is the extreme of the same effect a one-second
    # granularity filesystem produces when two edits land in one second.
    #
    # Without the harness forcing the source past it, the second mutant would
    # never be compiled, the suite would re-run the *first* mutant's bytes, and
    # a harmless mutation would be reported killed -- the fifteen false kills
    # on #163, in miniature.
    export FAKE_CLASS_SKEW=3600

    cat >"$CASE_DIR/mutants" <<'EOF'
mutant:  lethal
file:    fixmod/src/main/java/Widget.java
find:    LIMIT = 7
replace: LIMIT = 99

mutant:  harmless
file:    fixmod/src/main/java/Widget.java
find:    SPARE = 3
replace: SPARE = 4
EOF

    sweep --mutants "$CASE_DIR/mutants"

    assert_status 1 "$STATUS" "one killed, one survivor"
    if ! printf '%s\n' "$OUT" | grep -q '^lethal .*KILLED'; then
        fail "the lethal mutant should have been killed"
        printf '%s\n' "$OUT" | sed 's/^/      | /'
    fi
    if ! printf '%s\n' "$OUT" | grep -q '^harmless .*SURVIVED'; then
        fail "the harmless mutant was not reported as a survivor -- a stale class masked it"
        printf '%s\n' "$OUT" | sed 's/^/      | /'
    fi
    # And the mechanism, not just the outcome: every build recompiled.
    if grep -q '^RECOMPILED no' "$FAKE_MVN_LOG"; then
        fail "a build skipped recompilation, so it tested the previous mutant's bytes"
        sed 's/^/      | /' "$FAKE_MVN_LOG"
    fi
}

test_the_hazard_is_real_without_the_harness() {
    # A control for the test above. If a plain `touch` were enough, the stale
    # class test would prove nothing about the harness. Reproduce the naive
    # revert by hand -- copy back, touch, rebuild -- and show the stub reports
    # a build that did not recompile.
    setup_case stale-control
    export FAKE_CLASS_SKEW=3600
    cp "$FAKE_SRC" "$CASE_DIR/orig"

    perl -0777 -i -pe 's/LIMIT = 7/LIMIT = 99/' "$FAKE_SRC"
    touch "$FAKE_SRC"
    ( cd "$REPO" && mvn test >/dev/null 2>&1 )

    cp "$CASE_DIR/orig" "$FAKE_SRC"
    perl -0777 -i -pe 's/SPARE = 3/SPARE = 4/' "$FAKE_SRC"
    touch "$FAKE_SRC"
    local out status
    out=$( cd "$REPO" && mvn test 2>&1 ); status=$?

    if [ "$status" -eq 0 ]; then
        fail "control: expected the naive sequence to report a false failure, but it passed"
    fi
    if ! grep -q '^RECOMPILED no' "$FAKE_MVN_LOG"; then
        fail "control: the naive sequence recompiled, so this fixture cannot demonstrate the hazard"
        sed 's/^/      | /' "$FAKE_MVN_LOG"
    fi
}

# ---------------------------------------------------------------------------
# Failure mode 3 -- the substitution that never happened
# ---------------------------------------------------------------------------

test_a_substitution_that_matches_nothing_is_an_error_not_a_survivor() {
    setup_case no-match
    sweep --id typo --file fixmod/src/main/java/Widget.java \
          --find 'LIMIT = 8' --replace 'LIMIT = 99'

    assert_status 3 "$STATUS" "substitution matched nothing"
    assert_contains "$OUT" "does not occur" "substitution matched nothing"
    assert_contains "$OUT" "NOT a surviving mutant" "substitution matched nothing"
    assert_not_contains "$OUT" "SURVIVED" "substitution matched nothing"
    if ! printf '%s\n' "$OUT" | grep -q '^typo .*no *ERROR'; then
        fail "the summary should record applied=no and result=ERROR"
        printf '%s\n' "$OUT" | sed 's/^/      | /'
    fi
    assert_contains "$OUT" "Do not quote a" "the run should disown its own numbers"
}

test_an_ambiguous_substitution_is_an_error() {
    setup_case ambiguous
    # `= 7` occurs on two lines. Replacing both silently would mutate more than
    # the author named, and mutating "the first one" is not a decision a harness
    # gets to make on its own.
    sweep --id ambiguous --file fixmod/src/main/java/Widget.java \
          --find '= 7' --replace '= 99'

    assert_status 3 "$STATUS" "ambiguous substitution"
    assert_contains "$OUT" "occurs 2 times" "ambiguous substitution"
    assert_not_contains "$OUT" "SURVIVED" "ambiguous substitution"

    # ... and is accepted when the author says so explicitly.
    setup_case ambiguous-declared
    sweep --id both --file fixmod/src/main/java/Widget.java \
          --find '= 7' --replace '= 99' --count 2
    assert_status 0 "$STATUS" "declared multi-occurrence substitution"
    assert_contains "$OUT" "KILLED" "declared multi-occurrence substitution"
}

# ---------------------------------------------------------------------------
# Failure mode 4 -- the build failure counted as a kill
# ---------------------------------------------------------------------------

test_a_build_failure_is_not_a_kill() {
    setup_case build-failure
    export FAKE_MVN_MODE=buildfail-on-mutant

    sweep "${lethal_mutant_args[@]}"

    assert_contains "$OUT" "baseline green" "the baseline must pass, so only the mutant build fails"
    assert_status 3 "$STATUS" "build failure"
    assert_contains "$OUT" "BUILD-ERROR" "build failure"
    assert_contains "$OUT" "This is not a kill" "build failure"
    assert_contains "$OUT" "killed: 0" "build failure"
    assert_not_contains "$OUT" "KILLED by" "build failure"
    if [ -n "$(git -C "$REPO" status --porcelain -- fixmod)" ]; then
        fail "the tree was left dirty after a build failure"
    fi
}

test_a_kill_always_names_the_failing_test() {
    setup_case named-killer
    sweep "${lethal_mutant_args[@]}" --report "$CASE_DIR/report.tsv"

    assert_status 0 "$STATUS" "killed mutant"
    assert_contains "$OUT" "KILLED by WidgetTest.limitIsSeven" "killed mutant"
    assert_contains "$(cat "$CASE_DIR/report.tsv")" "WidgetTest.limitIsSeven" "report file"
    # The stub plants the word "error" in captured stdout; a report reader that
    # greps the whole body would invent a second failing test out of it.
    local killers
    killers=$(grep -c 'limitIsSeven' "$CASE_DIR/report.tsv")
    if [ "$killers" -ne 1 ]; then
        fail "expected exactly one killing test in the report, got $killers"
    fi
    assert_not_contains "$OUT" "+1 more" "system-out text must not be read as a failure"
}

test_maven_runs_with_am_and_a_private_local_repository() {
    setup_case maven-args
    sweep "${lethal_mutant_args[@]}"
    assert_status 0 "$STATUS" "kill with default invocation"

    local args
    args=$(grep '^ARGS ' "$FAKE_MVN_LOG" | head -1)
    assert_contains "$args" " -am" "maven invocation"
    assert_contains "$args" "-Dmaven.repo.local=$CASE_DIR/m2" "maven invocation"
    assert_contains "$args" "-pl fixmod" "maven invocation"
    assert_not_contains "$args" "install" "maven invocation"

    # And the shared repository -- the channel a worktree does not isolate -- is
    # refused rather than warned about.
    sweep "${lethal_mutant_args[@]}" --maven-repo "$HOME/.m2"
    assert_status 2 "$STATUS" "shared local repository"
    assert_contains "$OUT" "refusing to sweep against the shared local repository" "shared local repository"

    # An `install` mid-sweep is how a stale sibling gets into the repository in
    # the first place, so goals cannot be passed through at all.
    sweep "${lethal_mutant_args[@]}" --mvn-arg install
    assert_status 2 "$STATUS" "goal passed as an extra argument"
    assert_contains "$OUT" "takes options, not goals" "goal passed as an extra argument"
}

test_a_green_build_that_ran_no_tests_is_not_a_survivor() {
    setup_case no-tests
    export FAKE_MVN_MODE=notests
    sweep "${lethal_mutant_args[@]}" --no-baseline

    assert_status 3 "$STATUS" "build ran no tests"
    assert_contains "$OUT" "ran no tests" "build ran no tests"
    assert_not_contains "$OUT" "SURVIVED" "build ran no tests"
}

test_a_red_baseline_aborts_the_sweep() {
    setup_case red-baseline
    # The suite is already failing before anything is mutated. Every mutant
    # would then be "killed" by a test that was broken to begin with.
    export FAKE_LETHAL="LIMIT = 7"
    sweep "${lethal_mutant_args[@]}"

    assert_status 3 "$STATUS" "red baseline"
    assert_contains "$OUT" "baseline build failed" "red baseline"
    assert_not_contains "$OUT" "KILLED" "red baseline"
    if [ -n "$(git -C "$REPO" status --porcelain -- fixmod)" ]; then
        fail "the tree was left dirty after a refused sweep"
    fi
}

# ---------------------------------------------------------------------------
# Interruption
# ---------------------------------------------------------------------------

start_slow_sweep() {
    # Run the harness in its own process group so the test can signal the
    # harness and its Maven child together, which is what Ctrl-C does.
    setsid "$HARNESS" --maven-repo "$CASE_DIR/m2" --no-baseline \
        "${lethal_mutant_args[@]}" >"$CASE_DIR/sweep.out" 2>&1 &
    SWEEP_PID=$!
    local waited=0
    while [ "$waited" -lt 100 ]; do
        if grep -qF 'LIMIT = 99' "$FAKE_SRC" 2>/dev/null; then
            return 0
        fi
        sleep 0.1
        waited=$((waited + 1))
    done
    fail "the sweep never applied its mutation"
    return 1
}

test_sigterm_leaves_the_tree_clean() {
    if ! command -v setsid >/dev/null 2>&1; then
        setup_case sigterm >/dev/null 2>&1 || true
        skip "setsid is not available, so the process group cannot be signalled"
        return 0
    fi
    setup_case sigterm
    export FAKE_MVN_MODE=slow FAKE_MVN_SLEEP=30

    start_slow_sweep || return 0
    kill -TERM -"$SWEEP_PID" 2>/dev/null
    wait "$SWEEP_PID" 2>/dev/null
    local status=$?

    if [ "$status" -eq 0 ]; then
        fail "an interrupted sweep must not exit 0"
    fi
    if [ -n "$(git -C "$REPO" status --porcelain -- fixmod)" ]; then
        fail "SIGTERM left the tree modified"
    fi
    if [ -e "$REPO/.mutation-sweep-active" ]; then
        fail "SIGTERM left the sentinel behind"
    fi
    assert_contains "$(cat "$CASE_DIR/sweep.out")" "tree restored" "interrupted sweep"
}

test_sigkill_is_loud_and_recoverable() {
    if ! command -v setsid >/dev/null 2>&1; then
        skip "setsid is not available, so the process group cannot be signalled"
        return 0
    fi
    setup_case sigkill
    export FAKE_MVN_MODE=slow FAKE_MVN_SLEEP=30

    start_slow_sweep || return 0
    # SIGKILL: no trap can run, so the tree is left mutated on purpose. What
    # the harness owes here is that the state is visible and reversible.
    kill -KILL -"$SWEEP_PID" 2>/dev/null
    wait "$SWEEP_PID" 2>/dev/null
    export FAKE_MVN_MODE=normal   # the rest of this test does not need a slow build

    if ! grep -qF 'LIMIT = 99' "$FAKE_SRC"; then
        fail "expected the mutation to be left applied after SIGKILL"
    fi
    if [ ! -f "$REPO/.mutation-sweep-active" ]; then
        fail "SIGKILL left no sentinel, so the mutated tree is invisible"
        return 0
    fi
    # An untracked file at the repo root: it shows up in `git status`, which is
    # where somebody would look before committing.
    assert_contains "$(git -C "$REPO" status --porcelain)" ".mutation-sweep-active" \
        "sentinel visibility"

    # A second sweep must refuse rather than back up an already-mutated file.
    sweep "${lethal_mutant_args[@]}"
    assert_status 2 "$STATUS" "sweep after SIGKILL"
    assert_contains "$OUT" "did not finish" "sweep after SIGKILL"
    assert_contains "$OUT" "--recover" "sweep after SIGKILL"

    OUT=$( cd "$REPO" && "$HARNESS" --recover 2>&1 ); STATUS=$?
    assert_status 0 "$STATUS" "recovery"
    if [ -n "$(git -C "$REPO" status --porcelain -- fixmod)" ]; then
        fail "recovery did not restore the tree"
    fi
    if [ -e "$REPO/.mutation-sweep-active" ]; then
        fail "recovery left the sentinel behind"
    fi

    # And now a normal sweep runs again.
    sweep "${lethal_mutant_args[@]}"
    assert_status 0 "$STATUS" "sweep after recovery"
}

# ---------------------------------------------------------------------------
# The real thing: real Maven, real code, real test.
# ---------------------------------------------------------------------------

test_a_real_mutant_in_real_code_is_killed() {
    if [ "$WITH_MAVEN" -ne 1 ]; then
        skip "needs Maven; pass --with-maven"
        return 0
    fi
    # Guard against a stub `mvn` left on PATH by an earlier test: this is the
    # one test whose value comes entirely from Maven being the real one.
    if ! mvn -v 2>/dev/null | grep -q 'Apache Maven'; then
        fail "the mvn on PATH is not a real Maven; this test would prove nothing"
        return 0
    fi
    local origin clone repo_local
    origin=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
    clone="$SANDBOX/real/clone"
    repo_local=${MW_SWEEP_TEST_MAVEN_REPO:-$origin/.m2}
    mkdir -p "$SANDBOX/real"
    # A clone, not the developer's own tree: this test is allowed to leave a
    # mess behind if the harness is broken, and it must not be theirs.
    if ! git clone -q --no-hardlinks "$origin" "$clone" 2>"$SANDBOX/real/clone.log"; then
        fail "could not clone $origin"
        sed 's/^/      | /' "$SANDBOX/real/clone.log"
        return 0
    fi

    local out status
    out=$( cd "$clone" && "$HARNESS" \
        --maven-repo "$repo_local" \
        --id tempo-min-beats \
        --file mw-core/src/main/java/dev/olivelli/musicwizard/core/model/TempoMap.java \
        --find 'beatSeconds.size() < 2' \
        --replace 'beatSeconds.size() < 1' \
        --report "$SANDBOX/real/report.tsv" 2>&1 )
    status=$?

    assert_status 0 "$status" "real mutant"
    # Named precisely enough to re-run: -Dtest=TempoMapTest#rejectsTooFewBeats.
    assert_contains "$out" "KILLED by TempoMapTest.rejectsTooFewBeats" "real mutant"
    assert_contains "$out" "baseline green" "real mutant"
    if [ -n "$(git -C "$clone" status --porcelain)" ]; then
        fail "the real sweep left the clone dirty"
        git -C "$clone" status --short | sed 's/^/      | /'
    fi
    if [ "$CURRENT_FAILED" -ne 0 ]; then
        printf '%s\n' "$out" | sed 's/^/      | /'
    fi
}

# ---------------------------------------------------------------------------

say "mutation-sweep self-test"
say "harness: $HARNESS"
say ""

run_test refuses_when_the_file_to_mutate_is_dirty
run_test uncommitted_work_elsewhere_survives_a_sweep
run_test a_stale_class_cannot_produce_a_kill
run_test the_hazard_is_real_without_the_harness
run_test a_substitution_that_matches_nothing_is_an_error_not_a_survivor
run_test an_ambiguous_substitution_is_an_error
run_test a_build_failure_is_not_a_kill
run_test a_kill_always_names_the_failing_test
run_test maven_runs_with_am_and_a_private_local_repository
run_test a_green_build_that_ran_no_tests_is_not_a_survivor
run_test a_red_baseline_aborts_the_sweep
run_test sigterm_leaves_the_tree_clean
run_test sigkill_is_loud_and_recoverable
run_test a_real_mutant_in_real_code_is_killed

say ""
say "ran $TESTS_RUN, failed $TESTS_FAILED, skipped $TESTS_SKIPPED"
if [ "$TESTS_FAILED" -ne 0 ]; then
    say "failed: ${FAILED_NAMES[*]}"
    exit 1
fi
exit 0
