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
# Mutation sweep harness.
#
# For each mutant: substitute a literal string in a source file, run the
# module's tests, record whether the suite noticed, put the file back.
#
# The point of the harness is not convenience -- a sweep is four shell commands
# -- it is that every one of those four commands has silently produced a wrong
# number on this project. Issue #177 lists them; each is prevented here by a
# mechanism rather than by a warning:
#
#   1. `git checkout -- <file>` reverts the mutation *and* whatever the author
#      had uncommitted in that file. Six occurrences, four PRs, one day.
#      Here: no mutating git command is ever run, the revert is a byte-exact
#      copy taken before the edit, and a file that is already dirty is refused
#      outright -- so there is nothing of the author's to lose.
#
#   2. A source file whose mtime is not newer than its `.class` makes Maven
#      skip recompiling, so the tests run against the previous mutant's bytes.
#      Fifteen false kills on #163. Here: before every build the source mtime
#      is forced past every timestamp under the module's target/.
#
#   3. A substitution that matches nothing looks exactly like a surviving
#      mutant, i.e. like a coverage gap that does not exist. Three on #163.
#      Here: the match count is asserted before the build and after the
#      restore, and a miss is an error, never a survivor.
#
#   4. A stale sibling artifact makes the mutant fail to *build*, which a
#      summary that greps for BUILD FAILURE counts as a kill. Ten of
#      twenty-five on #63. Here: Maven always runs with `-am` and an explicit
#      non-shared `-Dmaven.repo.local`, and a kill is only a kill when a named
#      test failed in a surefire report written by that build.
# ---------------------------------------------------------------------------

set -euo pipefail

if [ -z "${BASH_VERSINFO:-}" ] || [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
    printf 'mutation-sweep.sh needs bash 4 or newer (found %s).\n' "${BASH_VERSION:-unknown}" >&2
    printf 'On macOS the system bash is 3.2; "brew install bash" provides a current one.\n' >&2
    exit 2
fi

readonly SENTINEL_NAME=".mutation-sweep-active"

# Exit codes are distinct on purpose: a caller, human or agent, has to be able
# to tell "your tests missed a mutant" from "the harness measured nothing".
readonly EXIT_OK=0            # every mutant applied and was killed
readonly EXIT_SURVIVORS=1     # everything measured; some mutants survived
readonly EXIT_REFUSED=2       # refused to start (dirty file, bad usage, ...)
readonly EXIT_HARNESS=3       # ran, but produced no trustworthy measurement
readonly EXIT_UNRESTORED=4    # could not put the tree back -- read the message

log()  { printf '%s\n' "$*" >&2; }
note() { printf '[sweep] %s\n' "$*" >&2; }
warn() { printf '[sweep] WARNING: %s\n' "$*" >&2; }
die()  { local code=$1; shift; printf '[sweep] ERROR: %s\n' "$*" >&2; exit "$code"; }

usage() {
    cat <<'EOF'
Usage:
  tools/mutation-sweep.sh --mutants <file> [options]
  tools/mutation-sweep.sh --file <path> --find <text> --replace <text> [options]
  tools/mutation-sweep.sh --recover

Mutants come from a file, from the command line, or both.

Mutant file format. Blank lines and lines whose first non-blank character is
'#' are ignored; a record starts at a `mutant:` line and runs to the next one:

  mutant:  tempo-min-beats
  file:    mw-core/src/main/java/dev/olivelli/musicwizard/core/model/TempoMap.java
  find:    beatSeconds.size() < 2
  replace: beatSeconds.size() < 1
  count:   1      # optional; how many occurrences must match, default 1

`find` and `replace` are literal text, never regular expressions, and are
single-line. Spaces between the colon and the value are stripped; trailing
whitespace is kept, so do not let an editor trim it. A `find` that matches a
number of times other than `count` is an error, not a mutant: pick text that
identifies the occurrence you mean.

Options:
  --mutants FILE      read mutant records from FILE
  --file PATH         source file for a command-line mutant (repo-root relative)
  --find TEXT         literal text to replace
  --replace TEXT      literal replacement
  --id NAME           name for the command-line mutant
  --count N           expected occurrences of --find (default 1)
  --maven-repo DIR    local Maven repository (default: <repo root>/.m2). A
                      relative path resolves against the current directory, not
                      the repository root. ~/.m2 is refused.
  --pl MODULES        Maven -pl list; default is the module owning each mutant
  --test PATTERN      restrict surefire to matching tests (-Dtest=PATTERN)
  --mvn-arg ARG       extra Maven option, repeatable; options only, never goals
  --report FILE       write a tab-separated report
  --no-baseline       skip the pre-sweep green-suite check (you will be warned)
  --recover           restore files left behind by an interrupted sweep, then exit
  -h, --help          this text

Exit codes: 0 all mutants killed, 1 some survived, 2 refused to start,
3 harness could not measure, 4 the tree was left modified (read the message).
EOF
}

# ---------------------------------------------------------------------------
# Text helpers, over perl.
#
# perl rather than sed/awk because every value here is literal text arriving
# from a file: sed would read `.`, `/` and `[` as syntax, and `size() < 2` is
# exactly the kind of string that turns into a quoting bug which then reports
# as a surviving mutant.
# ---------------------------------------------------------------------------

# count_occurrences <file> <needle> -- literal, prints a count.
count_occurrences() {
    MW_NEEDLE="$2" perl -0777 -ne '
        my $n = 0;
        $n++ while /\Q$ENV{MW_NEEDLE}\E/g;
        print "$n\n";
    ' "$1"
}

# substitute <file> <find> <replace> -- prints the number of substitutions.
# Writes through the existing inode, so permissions and hard links survive.
substitute() {
    local file=$1 tmp n
    tmp=$(mktemp "${TMPDIR:-/tmp}/mw-sweep.XXXXXX")
    n=$(MW_FIND="$2" MW_REPL="$3" perl -0777 -e '
        my ($in, $out) = @ARGV;
        open(my $i, "<:raw", $in) or die "read $in: $!";
        local $/;
        my $text = <$i>;
        close $i;
        my $n = ($text =~ s/\Q$ENV{MW_FIND}\E/$ENV{MW_REPL}/g);
        open(my $o, ">:raw", $out) or die "write $out: $!";
        print $o $text;
        close $o or die "close $out: $!";
        print "$n\n";
    ' "$file" "$tmp")
    cat "$tmp" >"$file"
    rm -f "$tmp"
    printf '%s\n' "$n"
}

# force_recompile <file> [target-dir]
#
# Failure mode 2. Maven's incremental compiler rebuilds a module only when a
# source file is *strictly newer* than the outputs, compared at the
# filesystem's timestamp granularity. A plain `touch` is not enough: on a
# one-second-granularity filesystem, or one whose clock runs ahead of ours --
# any network mount -- a freshly written `.class` can carry a timestamp at or
# after "now", and the edit is then invisible. So take the newest timestamp
# anywhere under target/ and put the source two seconds past it, which is
# strictly newer under any granularity we could plausibly meet.
force_recompile() {
    perl -e '
        use File::Find;
        my ($file, $dir) = @ARGV;
        my $max = 0;
        if (defined $dir && length $dir && -d $dir) {
            find({ no_chdir => 1, wanted => sub {
                my @st = stat($_) or return;
                $max = $st[9] if $st[9] > $max;
            }}, $dir);
        }
        my $now = time();
        my $stamp = $now > $max ? $now : $max + 2;
        utime($stamp, $stamp, $file) or die "utime $file: $!";
    ' "$1" "${2:-}"
}

# ---------------------------------------------------------------------------
# Repository state
# ---------------------------------------------------------------------------

git_is_dirty() {
    # Empty porcelain output for the pathspec means the file matches HEAD and
    # the index -- exactly the precondition that makes a revert provable.
    local out
    out=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all -- "$1")
    [ -n "$out" ]
}

# module_of <repo-relative-path> -- prints the owning Maven module, or nothing.
module_of() {
    local rel=$1 first=${1%%/*}
    if [ "$first" != "$rel" ] && [ -f "$REPO_ROOT/$first/pom.xml" ]; then
        printf '%s\n' "$first"
    fi
}

target_dir_of() {
    local mod
    mod=$(module_of "$1")
    if [ -n "$mod" ]; then
        printf '%s\n' "$REPO_ROOT/$mod/target"
    fi
}

# ---------------------------------------------------------------------------
# Sentinel and restore.
#
# The sentinel is a plain file at the repo root, deliberately *not* gitignored,
# so a leftover one shows up in `git status` and in anyone else's build. It
# records where the backups are, which is what makes a sweep killed by SIGKILL
# -- where no trap can run -- recoverable rather than merely detectable.
# ---------------------------------------------------------------------------

# Records are typed -- `file<TAB>path<TAB>backup` -- rather than distinguished
# from the header by a leading '#'. The first column is a filesystem path, and a
# path may legitimately begin with '#', in which case a comment-based format
# reads a live record as a header and silently forgets a mutated file.
sentinel_add() { printf 'file\t%s\t%s\n' "$1" "$2" >>"$SENTINEL"; }

sentinel_records() {
    if [ ! -f "$SENTINEL" ]; then
        return 0
    fi
    grep '^file	' "$SENTINEL" || true
}

sentinel_drop() {
    local rel=$1 tmp
    if [ ! -f "$SENTINEL" ]; then
        return 0
    fi
    tmp=$(mktemp "${TMPDIR:-/tmp}/mw-sweep-sentinel.XXXXXX")
    MW_REL="$rel" perl -ne '
        my ($kind, $f) = split(/\t/, $_, 3);
        print unless (defined $kind && $kind eq "file" && defined $f && $f eq $ENV{MW_REL});
    ' "$SENTINEL" >"$tmp"
    cat "$tmp" >"$SENTINEL"
    rm -f "$tmp"
}

sentinel_has_entries() {
    [ -n "$(sentinel_records)" ]
}

# Only ever remove a sentinel this run created. Another sweep's sentinel is the
# only record that its files are mutated, and deleting it strands them.
sentinel_release() {
    if [ ! -f "$SENTINEL" ]; then
        return 0
    fi
    if grep -q "^run	$RUN_ID	" "$SENTINEL"; then
        rm -f "$SENTINEL"
    fi
}

# restore_file <repo-relative-path> <backup-path> -- byte-exact, verified twice.
restore_file() {
    local rel=$1 backup=$2 abs="$REPO_ROOT/$1"
    if [ ! -f "$backup" ]; then
        log "[sweep] ERROR: the backup for $rel is missing: $backup"
        return 1
    fi
    # Skip the write when the file already matches: a mutation that could not be
    # written leaves nothing to undo, and writing anyway would turn a harmless
    # "the source is read-only" into "the tree is still mutated".
    if ! cmp -s "$backup" "$abs"; then
        if ! cat "$backup" >"$abs"; then
            log "[sweep] ERROR: could not write $rel"
            return 1
        fi
    fi
    if ! cmp -s "$backup" "$abs"; then
        log "[sweep] ERROR: restoring $rel did not reproduce the backup byte for byte"
        return 1
    fi
    force_recompile "$abs" "$(target_dir_of "$rel")"
    # Cross-check against git, not only against our own copy: the backup could
    # itself have been taken from an already-mutated file if the harness were
    # wrong somewhere upstream, and git has an independent record.
    if git_is_dirty "$rel"; then
        log "[sweep] ERROR: $rel still differs from the index after being restored"
        return 1
    fi
    return 0
}

# Restores everything the sentinel still lists. Reads the sentinel rather than
# shell state because after a SIGKILL the shell state is gone and the sentinel
# is all there is.
restore_all_from_sentinel() {
    local failed=0 rel backup
    if [ ! -f "$SENTINEL" ]; then
        return 0
    fi
    local kind
    while IFS=$'\t' read -r kind rel backup; do
        if [ "$kind" != "file" ] || [ -z "$rel" ]; then
            continue
        fi
        if restore_file "$rel" "$backup"; then
            note "restored $rel"
        else
            failed=1
        fi
    done < <(sentinel_records)
    if [ "$failed" -eq 0 ]; then
        rm -f "$SENTINEL"
        return 0
    fi
    return 1
}

# The pid that owns the sentinel, if it is still alive. A sweep holds the
# sentinel for its whole run, so recovering under a live one would revert files
# it is in the middle of measuring.
sentinel_live_owner() {
    local pid
    if [ ! -f "$SENTINEL" ]; then
        return 0
    fi
    pid=$(perl -ne 'print $2 if /^run\t([^\t]*)\t(\d+)/' "$SENTINEL")
    if [ -n "$pid" ] && [ "$pid" != "$$" ] && kill -0 "$pid" 2>/dev/null; then
        printf '%s\n' "$pid"
    fi
}

on_exit() {
    local status=$?
    trap - EXIT INT TERM HUP
    if ! sentinel_has_entries; then
        sentinel_release
        exit "$status"
    fi
    log ""
    log "[sweep] interrupted with mutations still applied; restoring"
    if restore_all_from_sentinel; then
        log "[sweep] tree restored; nothing left behind"
        if [ "$status" -eq 0 ]; then
            status=$EXIT_HARNESS
        fi
        exit "$status"
    fi
    log ""
    log "[sweep] ################################################################"
    log "[sweep] # THE WORKING TREE IS STILL MUTATED. Do not commit it, and do"
    log "[sweep] # not run 'mvn clean' -- that deletes the backups. Recover with"
    log "[sweep] #     tools/mutation-sweep.sh --recover"
    log "[sweep] # Backups: $BACKUP_DIR"
    log "[sweep] # Sentinel: $SENTINEL"
    log "[sweep] ################################################################"
    exit "$EXIT_UNRESTORED"
}

# ---------------------------------------------------------------------------
# Maven
# ---------------------------------------------------------------------------

# Every surefire report is deleted before each build, so anything found
# afterwards was written by that build. Comparing timestamps instead would be
# one clock-granularity mistake away from attributing the previous mutant's
# failure to this one -- the same family of bug as failure mode 2.
clear_surefire_reports() {
    # Scoped to `*/target/surefire-reports`, exactly like the reader below.
    # `-name surefire-reports` alone would also match a directory of that name
    # in src/test/resources, which is somebody's fixture and not ours to delete.
    find "$REPO_ROOT" -type d -path '*/target/surefire-reports' -prune \
        -exec rm -rf {} + 2>/dev/null || true
}

# run_maven <modules> <log-file> -- exit status is Maven's.
#
# `-am` is not optional and not configurable, and neither is an explicit
# non-shared local repository. Failure mode 4 is a sibling resolved from a
# repository instead of built from source; a sweep whose numbers came from a
# build without both of these has not measured this source tree.
run_maven() {
    local modules=$1 logfile=$2
    local -a cmd=(mvn -B --no-transfer-progress
                  "-Dmaven.repo.local=$MAVEN_REPO"
                  -pl "$modules" -am)
    if [ -n "$TEST_PATTERN" ]; then
        cmd+=("-Dtest=$TEST_PATTERN" -Dsurefire.failIfNoSpecifiedTests=false)
    fi
    if [ "${#EXTRA_MVN_ARGS[@]}" -gt 0 ]; then
        cmd+=("${EXTRA_MVN_ARGS[@]}")
    fi
    cmd+=(test)

    clear_surefire_reports
    printf '[sweep] %s\n' "${cmd[*]}" >"$logfile"
    ( cd "$REPO_ROOT" && "${cmd[@]}" ) >>"$logfile" 2>&1
}

surefire_reports() {
    find "$REPO_ROOT" -path '*/target/surefire-reports/TEST-*.xml' 2>/dev/null
}

# failing_tests -- names of failing test cases, one per line, deduplicated.
failing_tests() {
    local -a reports=()
    local r
    while IFS= read -r r; do
        reports+=("$r")
    done < <(surefire_reports)
    if [ "${#reports[@]}" -eq 0 ]; then
        return 0
    fi
    perl -0777 -ne '
        # The class comes from the report filename, not from the classname
        # attribute: under JUnit 5 that attribute holds the @DisplayName, so a
        # kill would be reported as "construction from tracked beats.rejects..."
        # -- true, but not something anyone can pass back to -Dtest=.
        my $cls = $ARGV;
        $cls =~ s{.*/}{};
        $cls =~ s{^TEST-}{};
        $cls =~ s{\.xml$}{};
        $cls =~ s{.*\.}{};
        while (m{<testcase\b([^>]*?)(?:/>|>(.*?)</testcase>)}gs) {
            my ($attrs, $body) = ($1, $2);
            next unless defined $body;
            # Captured stdout/stderr lives in the body and routinely contains
            # the word "error"; only the report elements themselves count.
            $body =~ s{<system-(out|err)\b.*?</system-\1>}{}gs;
            next unless $body =~ m{<(?:failure|error)[\s>/]};
            my ($name) = $attrs =~ /\bname="([^"]*)"/;
            $name = defined $name ? $name : "?";
            print "$cls.$name\n";
        }
    ' "${reports[@]}" | sort -u
}

# tests_run -- how many test cases the build executed. Zero means the build
# produced no evidence either way, whatever its exit status said.
tests_run() {
    local -a reports=()
    local r
    while IFS= read -r r; do
        reports+=("$r")
    done < <(surefire_reports)
    if [ "${#reports[@]}" -eq 0 ]; then
        printf '0\n'
        return 0
    fi
    perl -0777 -ne '
        our $total;
        $total = 0 unless defined $total;
        $total++ while /<testcase\b/g;
        END { print(($total || 0) . "\n") }
    ' "${reports[@]}"
}

# ---------------------------------------------------------------------------
# Mutant records
# ---------------------------------------------------------------------------

MUT_IDS=(); MUT_FILES=(); MUT_FINDS=(); MUT_REPLS=(); MUT_COUNTS=()

add_mutant() {
    local id=$1 file=$2 find=$3 repl=$4 count=$5
    if [ -z "$id" ]; then
        die "$EXIT_REFUSED" "a mutant record has an empty name"
    fi
    if [ -z "$file" ]; then
        die "$EXIT_REFUSED" "mutant '$id' has no file"
    fi
    if [ -z "$find" ]; then
        die "$EXIT_REFUSED" "mutant '$id' has no find text"
    fi
    if [ "$find" = "$repl" ]; then
        die "$EXIT_REFUSED" "mutant '$id' replaces text with itself, which cannot mutate anything"
    fi
    case "$count" in
        ''|*[!0-9]*) die "$EXIT_REFUSED" "mutant '$id' has a non-numeric count: '$count'" ;;
        0) die "$EXIT_REFUSED" "mutant '$id' expects zero occurrences, which is not a mutation" ;;
    esac
    MUT_IDS+=("$id"); MUT_FILES+=("$file"); MUT_FINDS+=("$find")
    MUT_REPLS+=("$repl"); MUT_COUNTS+=("$count")
}

parse_mutants_file() {
    local path=$1
    if [ ! -f "$path" ]; then
        die "$EXIT_REFUSED" "no such mutants file: $path"
    fi
    local id='' file='' find='' repl='' count=1 line key value have=0 lineno=0
    flush_record() {
        if [ "$have" -eq 1 ]; then
            add_mutant "$id" "$file" "$find" "$repl" "$count"
        fi
        id=''; file=''; find=''; repl=''; count=1; have=0
    }
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        # A CRLF file would otherwise fail with "no such file: ...Widget.java^M",
        # which names the wrong problem convincingly enough to waste an hour.
        line=${line%$'\r'}
        case "${line#"${line%%[![:space:]]*}"}" in
            ''|'#'*) continue ;;
        esac
        case "$line" in
            *:*) key=${line%%:*}; value=${line#*:} ;;
            *) die "$EXIT_REFUSED" "$path:$lineno: expected 'key: value', got: $line" ;;
        esac
        key=$(printf '%s' "$key" | tr -d '[:space:]')
        value=${value#"${value%%[![:space:]]*}"}
        case "$key" in
            mutant)  flush_record; id=$value; have=1 ;;
            file|find|replace|count)
                if [ "$have" -ne 1 ]; then
                    die "$EXIT_REFUSED" "$path:$lineno: '$key' appears before any 'mutant:' line"
                fi
                case "$key" in
                    file) file=$value ;;
                    find) find=$value ;;
                    replace) repl=$value ;;
                    count) count=$value ;;
                esac
                ;;
            *) die "$EXIT_REFUSED" "$path:$lineno: unknown key '$key'" ;;
        esac
    done <"$path"
    flush_record
}

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------

MUTANTS_FILE=''
CLI_FILE=''; CLI_FIND=''; CLI_REPL=''; CLI_ID=''; CLI_COUNT=1; CLI_ANY=0
MAVEN_REPO=''
PL_OVERRIDE=''
TEST_PATTERN=''
REPORT_FILE=''
RUN_BASELINE=1
RECOVER=0
EXTRA_MVN_ARGS=()
BACKUP_DIR='(none)'
RUN_ID='(none)'

while [ $# -gt 0 ]; do
    case "$1" in
        --mutants)    MUTANTS_FILE=${2:?--mutants needs a path}; shift 2 ;;
        --file)       CLI_FILE=${2:?--file needs a path}; CLI_ANY=1; shift 2 ;;
        --find)       CLI_FIND=${2?--find needs text}; CLI_ANY=1; shift 2 ;;
        --replace)    CLI_REPL=${2?--replace needs text}; CLI_ANY=1; shift 2 ;;
        --id)         CLI_ID=${2:?--id needs a name}; CLI_ANY=1; shift 2 ;;
        --count)      CLI_COUNT=${2:?--count needs a number}; CLI_ANY=1; shift 2 ;;
        --maven-repo) MAVEN_REPO=${2:?--maven-repo needs a path}; shift 2 ;;
        --pl)         PL_OVERRIDE=${2:?--pl needs a module list}; shift 2 ;;
        --test)       TEST_PATTERN=${2:?--test needs a pattern}; shift 2 ;;
        --mvn-arg)    EXTRA_MVN_ARGS+=("${2:?--mvn-arg needs a value}"); shift 2 ;;
        --report)     REPORT_FILE=${2:?--report needs a path}; shift 2 ;;
        --no-baseline) RUN_BASELINE=0; shift ;;
        --recover)    RECOVER=1; shift ;;
        -h|--help)    usage; exit 0 ;;
        *)            usage >&2; die "$EXIT_REFUSED" "unknown argument: $1" ;;
    esac
done

if [ "${#EXTRA_MVN_ARGS[@]}" -gt 0 ]; then
    for arg in "${EXTRA_MVN_ARGS[@]}"; do
        case "$arg" in
            -*) ;;
            *) die "$EXIT_REFUSED" "--mvn-arg takes options, not goals: '$arg'. The harness picks the goal (test) deliberately; an 'install' mid-sweep is how a stale sibling gets into the local repository in the first place." ;;
        esac
    done
fi

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) \
    || die "$EXIT_REFUSED" "not inside a git working tree"
readonly REPO_ROOT
readonly SENTINEL="$REPO_ROOT/$SENTINEL_NAME"

if [ -z "$MAVEN_REPO" ]; then
    MAVEN_REPO="$REPO_ROOT/.m2"
fi
case "$MAVEN_REPO" in
    /*) ;;
    *) MAVEN_REPO="$(pwd)/$MAVEN_REPO" ;;
esac
# Failure mode 4, the half a worktree does not isolate. Refusing rather than
# warning: a sweep that resolves siblings from the shared repository may be
# measuring another agent's uncommitted work, and it looks exactly like a result.
case "${MAVEN_REPO%/}" in
    "${HOME:-/nonexistent}/.m2"|"${HOME:-/nonexistent}/.m2/repository")
        die "$EXIT_REFUSED" "refusing to sweep against the shared local repository $MAVEN_REPO; use one per worktree: --maven-repo <worktree>/.m2" ;;
esac
readonly MAVEN_REPO

# ---------------------------------------------------------------------------
# --recover
# ---------------------------------------------------------------------------

if [ "$RECOVER" -eq 1 ]; then
    if [ ! -f "$SENTINEL" ]; then
        note "no sweep in progress; nothing to recover"
        exit "$EXIT_OK"
    fi
    owner=$(sentinel_live_owner)
    if [ -n "$owner" ]; then
        die "$EXIT_REFUSED" "a sweep is still running in this worktree (pid $owner). Recovering now would revert a file it is measuring. Wait for it, or stop it first."
    fi
    note "recovering from $SENTINEL"
    if restore_all_from_sentinel; then
        note "tree restored"
        exit "$EXIT_OK"
    fi
    log "[sweep] ERROR: recovery is incomplete. Every mutated file was clean before the"
    log "[sweep]        sweep started, so its original bytes are still in the git index:"
    log "[sweep]        'git checkout --' on the files listed in $SENTINEL will restore"
    log "[sweep]        them. Check first that you have no other uncommitted work in them."
    exit "$EXIT_UNRESTORED"
fi

# ---------------------------------------------------------------------------
# Preflight. Everything checkable without touching a file is checked here, so a
# sweep either starts or does not; refusing halfway would leave mutants applied.
# ---------------------------------------------------------------------------

if [ -z "$MUTANTS_FILE" ] && [ "$CLI_ANY" -eq 0 ]; then
    usage >&2
    die "$EXIT_REFUSED" "no mutants given"
fi

if [ -f "$SENTINEL" ]; then
    owner=$(sentinel_live_owner)
    if [ -n "$owner" ]; then
        die "$EXIT_REFUSED" "another sweep is already running in this worktree (pid $owner). Two sweeps in one tree mutate each other's files and read each other's surefire reports; run the second one in its own worktree."
    fi
    log "[sweep] ERROR: a previous sweep did not finish. It left behind:"
    sed 's/^/[sweep]   /' "$SENTINEL" >&2
    die "$EXIT_REFUSED" "run 'tools/mutation-sweep.sh --recover' first"
fi

if [ -n "$MUTANTS_FILE" ]; then
    parse_mutants_file "$MUTANTS_FILE"
fi
if [ "$CLI_ANY" -eq 1 ]; then
    if [ -z "$CLI_ID" ]; then
        CLI_ID="$(basename "${CLI_FILE:-mutant}" .java)-cli"
    fi
    add_mutant "$CLI_ID" "$CLI_FILE" "$CLI_FIND" "$CLI_REPL" "$CLI_COUNT"
fi
if [ "${#MUT_IDS[@]}" -eq 0 ]; then
    die "$EXIT_REFUSED" "no mutants given"
fi

command -v mvn >/dev/null 2>&1 || die "$EXIT_REFUSED" "mvn is not on PATH"
command -v perl >/dev/null 2>&1 || die "$EXIT_REFUSED" "perl is not on PATH"

MUT_MODULES=()
seen_ids=''
all_modules=''
for i in "${!MUT_IDS[@]}"; do
    id=${MUT_IDS[$i]}
    rel=${MUT_FILES[$i]}
    case "|$seen_ids|" in
        *"|$id|"*) die "$EXIT_REFUSED" "duplicate mutant name: $id" ;;
    esac
    seen_ids="${seen_ids}|${id}"

    case "$rel" in
        "$REPO_ROOT"/*) rel=${rel#"$REPO_ROOT/"} ;;
    esac
    case "$rel" in
        /*) die "$EXIT_REFUSED" "mutant '$id': $rel is outside the repository" ;;
        *..*) die "$EXIT_REFUSED" "mutant '$id': the path must not contain '..': $rel" ;;
    esac
    MUT_FILES[i]=$rel

    if [ ! -f "$REPO_ROOT/$rel" ]; then
        die "$EXIT_REFUSED" "mutant '$id': no such file: $rel"
    fi
    if ! git -C "$REPO_ROOT" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
        die "$EXIT_REFUSED" "mutant '$id': $rel is not tracked by git, so there is no clean state to compare it against"
    fi
    if [ ! -w "$REPO_ROOT/$rel" ]; then
        die "$EXIT_REFUSED" "mutant '$id': $rel is not writable, so it can be mutated but not put back"
    fi

    # Failure mode 1, and the refusal the whole harness is built around: if the
    # file already differs from HEAD then the author has work in it, and no
    # revert -- ours included -- can be proven to have put that work back.
    if git_is_dirty "$rel"; then
        log "[sweep] ERROR: $rel has uncommitted changes."
        log "[sweep]        A sweep edits this file and reverts it. Refusing, so that your"
        log "[sweep]        work cannot be reverted along with the mutation. Commit or stash"
        log "[sweep]        it yourself -- deliberately -- and run the sweep again."
        git -C "$REPO_ROOT" status --short --untracked-files=all -- "$rel" | sed 's/^/[sweep]   /' >&2
        exit "$EXIT_REFUSED"
    fi

    if [ -n "$PL_OVERRIDE" ]; then
        mod=$PL_OVERRIDE
    else
        mod=$(module_of "$rel")
        if [ -z "$mod" ]; then
            die "$EXIT_REFUSED" "mutant '$id': $rel is not inside a Maven module; pass --pl"
        fi
    fi
    MUT_MODULES+=("$mod")
    case ",$all_modules," in
        *",$mod,"*) ;;
        *) all_modules="${all_modules:+$all_modules,}$mod" ;;
    esac
done

# Uncommitted work elsewhere is safe -- nothing but the mutant files is ever
# touched -- but it is part of what is being measured, so say so.
other_dirty=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal 2>/dev/null || true)
if [ -n "$other_dirty" ]; then
    warn "the working tree has other uncommitted changes. They will not be touched,"
    warn "but the suite runs against them, so these results describe your tree, not HEAD:"
    printf '%s\n' "$other_dirty" | sed 's/^/[sweep]   /' >&2
fi

# ---------------------------------------------------------------------------
# Set up
# ---------------------------------------------------------------------------

RUN_ID=$(date +%Y%m%d-%H%M%S)-$$
BACKUP_DIR="$REPO_ROOT/target/mutation-sweep/$RUN_ID"
readonly BACKUP_DIR
mkdir -p "$BACKUP_DIR"

# The sentinel doubles as the worktree lock, and it is created with noclobber so
# that creating it *is* the check. The `[ -f ]` test above gives the good
# message; on its own it is a check-then-act race, and two sweeps that both got
# past it would mutate one tree, read each other's surefire reports -- a
# survivor becomes a kill, complete with a named test -- and the second to
# finish would delete the sentinel out from under the first.
trap on_exit EXIT INT TERM HUP
if ! (set -o noclobber; : >"$SENTINEL") 2>/dev/null; then
    trap - EXIT INT TERM HUP
    die "$EXIT_REFUSED" "another sweep took $SENTINEL between the check and now. One sweep per worktree."
fi
{
    printf '# mutation sweep %s started %s\n' "$RUN_ID" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf '# restore with tools/mutation-sweep.sh --recover\n'
    printf 'run\t%s\t%s\n' "$RUN_ID" "$$"
} >"$SENTINEL"

note "repo:       $REPO_ROOT"
note "maven repo: $MAVEN_REPO"
note "modules:    $all_modules"
note "mutants:    ${#MUT_IDS[@]}"
note "backups:    $BACKUP_DIR"

# ---------------------------------------------------------------------------
# Baseline. A sweep against a red suite measures nothing: every mutant is
# "killed" by a test that was already failing. It also warms compilation, so
# the first mutant does not pay for a cold target/.
# ---------------------------------------------------------------------------

if [ "$RUN_BASELINE" -eq 1 ]; then
    note "baseline: $all_modules, unmutated"
    baseline_log="$BACKUP_DIR/baseline.log"
    if ! run_maven "$all_modules" "$baseline_log"; then
        log "[sweep] ERROR: the baseline build failed. Every mutant would then report as"
        log "[sweep]        killed by a test that was already broken. Log: $baseline_log"
        tail -n 30 "$baseline_log" | sed 's/^/[sweep]   /' >&2
        exit "$EXIT_HARNESS"
    fi
    baseline_tests=$(tests_run)
    if [ "$baseline_tests" -eq 0 ]; then
        log "[sweep] ERROR: the baseline build ran no tests at all, so nothing could ever"
        log "[sweep]        kill a mutant. Check --test and --pl. Log: $baseline_log"
        exit "$EXIT_HARNESS"
    fi
    note "baseline green: $baseline_tests tests"
else
    warn "baseline skipped: a mutant 'killed' by an already-failing test is indistinguishable from a real kill"
fi

# ---------------------------------------------------------------------------
# Sweep
# ---------------------------------------------------------------------------

RES_STATUS=(); RES_APPLIED=(); RES_KILLER=()
killed=0; survived=0; errored=0

for i in "${!MUT_IDS[@]}"; do
    id=${MUT_IDS[$i]}
    rel=${MUT_FILES[$i]}
    find_text=${MUT_FINDS[$i]}
    repl_text=${MUT_REPLS[$i]}
    want=${MUT_COUNTS[$i]}
    module=${MUT_MODULES[$i]}
    abs="$REPO_ROOT/$rel"

    RES_APPLIED+=("no"); RES_STATUS+=("ERROR"); RES_KILLER+=("did not run")

    log ""
    note "mutant $id: $rel"
    note "  - $find_text"
    note "  + $repl_text"

    # Re-checked immediately before editing: an editor, or a previous mutant
    # that went wrong, could have dirtied this file since preflight.
    if git_is_dirty "$rel"; then
        RES_KILLER[i]="became dirty after preflight; not mutated"
        errored=$((errored + 1))
        warn "  $rel became dirty since preflight; skipping it rather than reverting your work"
        continue
    fi

    before=$(count_occurrences "$abs" "$find_text")
    if [ "$before" -ne "$want" ]; then
        # Failure mode 3. Zero matches is the dangerous one: the build is green
        # because nothing changed, and a summary that only reads the exit status
        # calls that a survivor -- a coverage gap that does not exist.
        RES_KILLER[i]="found $before occurrences of the search text, expected $want"
        errored=$((errored + 1))
        if [ "$before" -eq 0 ]; then
            warn "  the search text does not occur in $rel. This is NOT a surviving mutant:"
            warn "  the substitution never happened. Fix the text, or the file, and re-run."
        else
            warn "  the search text occurs $before times, expected $want. Which one did you mean?"
            warn "  Narrow the text, or set 'count:' if mutating all of them is intended."
        fi
        continue
    fi

    backup="$BACKUP_DIR/$(printf '%s' "$rel" | tr '/' '_').$i.orig"
    cp "$abs" "$backup"
    if ! cmp -s "$abs" "$backup"; then
        RES_KILLER[i]="the backup copy did not match the source"
        errored=$((errored + 1))
        warn "  could not take a verified backup of $rel; not mutating it"
        continue
    fi
    # Recorded before the edit, never after: a crash between the two must leave
    # a sentinel that points at a backup, not a mutated file nobody knows about.
    sentinel_add "$rel" "$backup"

    n=$(substitute "$abs" "$find_text" "$repl_text")
    if [ "$n" -ne "$want" ] || cmp -s "$abs" "$backup"; then
        RES_KILLER[i]="substitution made $n changes, expected $want"
        errored=$((errored + 1))
        warn "  the substitution did not take effect as expected; restoring"
        if restore_file "$rel" "$backup"; then
            sentinel_drop "$rel"
        else
            die "$EXIT_UNRESTORED" "could not restore $rel from $backup"
        fi
        continue
    fi
    # git as an independent witness that the file we edited is the tracked one,
    # and not a symlink target or a path that resolves somewhere else.
    if ! git_is_dirty "$rel"; then
        RES_KILLER[i]="git does not see the mutation; the file edited is not the tracked one"
        errored=$((errored + 1))
        warn "  git reports $rel as unmodified after mutating it; refusing to trust this run"
        if restore_file "$rel" "$backup"; then
            sentinel_drop "$rel"
        fi
        continue
    fi
    RES_APPLIED[i]="yes"
    note "  applied, $n substitution(s)"

    force_recompile "$abs" "$(target_dir_of "$rel")"

    logfile="$BACKUP_DIR/mutant-$i-$(printf '%s' "$id" | tr -c 'A-Za-z0-9._-' '_').log"
    mvn_status=0
    run_maven "$module" "$logfile" || mvn_status=$?

    ran=$(tests_run)
    failures=()
    while IFS= read -r line; do
        failures+=("$line")
    done < <(failing_tests)

    if [ "${#failures[@]}" -gt 0 ]; then
        RES_STATUS[i]="KILLED"
        killer=${failures[0]}
        if [ "${#failures[@]}" -gt 1 ]; then
            killer="$killer (+$(( ${#failures[@]} - 1 )) more)"
        fi
        RES_KILLER[i]=$killer
        killed=$((killed + 1))
        note "  KILLED by $killer"
    elif [ "$mvn_status" -ne 0 ]; then
        # Failure mode 4. Maven failed but no test did: a compile error, a
        # missing sibling, a plugin blowing up. Counting that as a kill is how
        # ten of twenty-five mutants on #63 turned into coverage nobody had.
        RES_STATUS[i]="BUILD-ERROR"
        RES_KILLER[i]="maven exited $mvn_status with no failing test"
        errored=$((errored + 1))
        warn "  the build FAILED without any test failing. This is not a kill."
        warn "  Log: $logfile"
        tail -n 20 "$logfile" | sed 's/^/[sweep]   /' >&2
    elif [ "$ran" -eq 0 ]; then
        RES_STATUS[i]="ERROR"
        RES_KILLER[i]="the build ran no tests"
        errored=$((errored + 1))
        warn "  the build succeeded but ran no tests, so nothing was measured. Log: $logfile"
    else
        RES_STATUS[i]="SURVIVED"
        RES_KILLER[i]=""
        survived=$((survived + 1))
        note "  SURVIVED, $ran tests"
    fi

    # Restore: byte-exact, verified against the backup and against git, then
    # pushed past every timestamp under target/ so the next build cannot reuse
    # this mutant's classes (failure mode 2, in the direction that produces
    # false kills for the *following* mutant).
    if ! restore_file "$rel" "$backup"; then
        die "$EXIT_UNRESTORED" "could not restore $rel from $backup"
    fi
    sentinel_drop "$rel"
    after=$(count_occurrences "$abs" "$find_text")
    if [ "$after" -ne "$want" ]; then
        die "$EXIT_UNRESTORED" "after restoring $rel the search text occurs $after times, expected $want"
    fi
    note "  restored and verified clean"
done

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

log ""
log "=== mutation sweep: ${#MUT_IDS[@]} mutants ==="
printf '%-26s %-14s %-8s %-12s %s\n' "MUTANT" "MODULE" "APPLIED" "RESULT" "KILLED BY" >&2
for i in "${!MUT_IDS[@]}"; do
    printf '%-26s %-14s %-8s %-12s %s\n' \
        "${MUT_IDS[$i]}" "${MUT_MODULES[$i]}" "${RES_APPLIED[$i]}" "${RES_STATUS[$i]}" "${RES_KILLER[$i]}" >&2
done
log ""
log "killed: $killed   survived: $survived   not measured: $errored"

if [ -n "$REPORT_FILE" ]; then
    {
        printf 'mutant\tmodule\tfile\tapplied\tresult\tkilled_by\n'
        for i in "${!MUT_IDS[@]}"; do
            printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
                "${MUT_IDS[$i]}" "${MUT_MODULES[$i]}" "${MUT_FILES[$i]}" \
                "${RES_APPLIED[$i]}" "${RES_STATUS[$i]}" "${RES_KILLER[$i]}"
        done
    } >"$REPORT_FILE"
    note "report written to $REPORT_FILE"
fi

sentinel_release
trap - EXIT INT TERM HUP

if [ "$errored" -gt 0 ]; then
    log "[sweep] $errored mutant(s) produced no usable measurement. Do not quote a"
    log "[sweep] coverage figure from this run until they are resolved."
    exit "$EXIT_HARNESS"
fi
if [ "$survived" -gt 0 ]; then
    exit "$EXIT_SURVIVORS"
fi
exit "$EXIT_OK"
