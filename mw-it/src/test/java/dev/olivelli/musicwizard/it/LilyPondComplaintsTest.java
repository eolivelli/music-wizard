/*
 * Copyright 2026 Music Wizard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.olivelli.musicwizard.it;

import static dev.olivelli.musicwizard.it.LilyPondComplaints.TOLERATED_COMPLAINT;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.complaintsIn;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.failedBarChecksIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link LilyPondComplaints} does and does not count as a complaint, and as
 * a failed bar check.
 *
 * <p>No LilyPond here on purpose. The engraving tests can only show the matcher
 * against whichever version is installed on the machine running them, which is
 * precisely the blind spot #145 lived in — green under 2.26 and red under 2.24,
 * with nothing anywhere saying the two differ. The fixtures below are real
 * output from both versions, so one run says the matcher handles both.
 *
 * <p>The other half matters at least as much. A matcher that is too generous
 * fails a clean engraving, which is loud; a matcher keyed on a substring that
 * turns up in ordinary output would pass everything, which is silent, and
 * silent is the failure this suite keeps finding. So every line LilyPond emits
 * around a bar check is here as a case that must <em>not</em> match.
 *
 * <p><b>No {@code IT} in the name</b>, and deliberately. Everything an engraving
 * test can say about the tolerance is said behind {@code -Pintegration}; this
 * needs no binary and takes a tenth of a second. Leaving it under that
 * profile would mean: the guard against
 * the matcher rotting would live inside the job whose being unread for weeks is
 * the whole subject of #145. Named this way it runs on every {@code mvn verify}
 * and in every CI job; named {@code *IT} it would run only where a reviewer had
 * remembered {@code -Pintegration}. #155 moved
 * {@link #theToleranceIsNarrowerThanTheWordItContains} here for the same reason
 * one class over.
 *
 * <p>What that does <em>not</em> buy is noticing a test that stops being run
 * at all. Renaming {@link StaffNotationOverloadTest} to {@code *IT} watches
 * {@code mvn verify} go green one test quieter than before: surefire's
 * {@code failIfNoTests} is not configured anywhere in this build, so a
 * {@code *Test} that disappears is exactly as silent as an {@code *IT} that
 * does. The argument for the name is which job runs it, not what happens when
 * nothing does. (The count is deliberately not written down here — one was
 * recorded once and went stale in the same commit.)
 */
class LilyPondComplaintsTest {

    /**
     * LilyPond 2.24.3, the version the {@code integration} job installs. Every
     * line is byte-exact, the trailing space included; the four progress lines
     * between preprocessing and success are cut, because they say nothing
     * about bar checks — which is what "verbatim" here means.
     */
    private static final String OUTPUT_2_24 = """
            Processing `bar.ly'
            Parsing...
            Interpreting music...
            bar.ly:5:17: warning: barcheck failed at: 3/4
                c'4 d'4 e'4\s
                            |
            Preprocessing graphical objects...
            Success: compilation successfully completed
            """;

    /** The same run under LilyPond 2.26.0, which is what Homebrew installs, abridged alike. */
    private static final String OUTPUT_2_26 = """
            Processing `bar.ly'
            Parsing...
            Interpreting music...
            bar.ly:5:17: warning: bar check failed at: 3/4
                c'4 d'4 e'4\s
                            |
            Preprocessing graphical objects...
            Success: compilation successfully completed
            """;

    @Test
    @DisplayName("both of LilyPond's spellings are the same failed bar check")
    void eitherSpellingIsFound() {
        // The whole of #145 in two lines: these two outputs describe the same
        // bar, three quarters long where four were due, and any assertion that
        // tells them apart is asserting on LilyPond's prose rather than on the
        // music. 2.25.6 moved bar checks into Timing_translator and reworded the
        // message on the way.
        assertThat(failedBarChecksIn(OUTPUT_2_24)).containsExactly("3/4");
        assertThat(failedBarChecksIn(OUTPUT_2_26)).containsExactly("3/4");
    }

    @Test
    @DisplayName("the moment is read out, not just the fact")
    void theReportedMomentIsCaptured() {
        // What makes this stronger than the substring match it replaces: a
        // matcher that returned "yes, something" could not tell a bar short by a
        // beat from one short by a triplet sixteenth, and the second is what
        // TupletEngravingIT is actually about.
        assertThat(failedBarChecksIn(
                "bar.ly:5:34: warning: bar check failed at: 11/12")).containsExactly("11/12");
        assertThat(failedBarChecksIn(
                "bar.ly:5:34: warning: barcheck failed at: 11/12")).containsExactly("11/12");
        // Compound moments print with a grace part attached. Not produced by
        // anything this project emits, but the matcher must not truncate one
        // into a different moment if it ever is.
        assertThat(failedBarChecksIn(
                "bar.ly:5:34: warning: bar check failed at: 3/4;-1/8"))
                .containsExactly("3/4;-1/8");
    }

    @Test
    @DisplayName("more than one failing bar is more than one moment")
    void everyReportedFailureIsReturned() {
        assertThat(failedBarChecksIn("""
                part.ly:5:17: warning: barcheck failed at: 3/4
                part.ly:9:12: warning: barcheck failed at: 1/2
                """)).containsExactly("3/4", "1/2");
    }

    @Test
    @DisplayName("a clean engraving reports nothing, so the empty case means something")
    void aCleanRunHasNoFailedBarChecks() {
        assertThat(failedBarChecksIn("""
                Processing `part.ly'
                Parsing...
                Interpreting music...
                Preprocessing graphical objects...
                Finding the ideal number of pages...
                Fitting music on 1 page...
                Drawing systems...
                Converting to `part.pdf'...
                Success: compilation successfully completed
                """)).isEmpty();
    }

    @Test
    @DisplayName("other complaints are not failed bar checks")
    void unrelatedDiagnosticsDoNotMatch() {
        // The trap a reviewer named on #132: LilyPond writes benign diagnostics
        // and fatal ones with the same vocabulary, so a matcher keyed on a
        // common word reports every one of them as the thing being watched for.
        // Each of these is a real line from this suite's own runs.
        assertThat(failedBarChecksIn(
                "programming error: not enough space for tuplet number against beam")).isEmpty();
        assertThat(failedBarChecksIn(
                "part.ly:5:20: error: syntax error, unexpected '}'")).isEmpty();
        assertThat(failedBarChecksIn(
                "warning: skipping zero-duration score")).isEmpty();
        assertThat(failedBarChecksIn(
                "fatal error: failed files: \"canci??n.ly\"")).isEmpty();
    }

    @Test
    @DisplayName("the words alone are not the diagnostic")
    void theWordsWithoutTheDiagnosticDoNotMatch() {
        // Why the matcher asks for the whole shape rather than for the phrase.
        // LilyPond echoes file names and the offending line of source back at
        // you, so the phrase turns up in output containing no failed bar check
        // at all, and a test that matched it would pass on a file named after
        // the bug it was written for. Which part of the pattern does the
        // work differs by case, and that is the argument for writing it
        // down: the first two
        // are rejected for reporting no moment; the third -- a commented-out
        // line of source, where everything else about the text is right -- is
        // the only one that needs the "warning:" prefix; and the fourth is
        // rejected by neither, but by the spelling, because only the space
        // between "bar" and "check" is optional and the one before "failed" is
        // not.
        assertThat(failedBarChecksIn("Processing `bar check failed.ly'")).isEmpty();
        assertThat(failedBarChecksIn("Converting to `barcheck failed at 3-4.pdf'...")).isEmpty();
        assertThat(failedBarChecksIn("  % barcheck failed at: 3/4 -- fixed in the next bar"))
                .isEmpty();
        // And the phrase run together with no space at all, which is neither
        // spelling and should not be treated as one.
        assertThat(failedBarChecksIn("bar.ly:5:17: warning: barcheckfailed at: 3/4")).isEmpty();
    }

    // ------------------------------------------------- what counts as a complaint

    @Test
    @DisplayName("a complaint is any line LilyPond wrote the word warning or error on")
    void everyDiagnosticLineIsAComplaint() {
        // Both spellings of the bar check, an error, and LilyPond's own
        // internal grumbles -- every one of them has to be a complaint, because
        // the engraving tests assert that the list is empty and each of these
        // is a real line from a run that produced a page nobody wanted.
        assertThat(complaintsIn(OUTPUT_2_24))
                .containsExactly("bar.ly:5:17: warning: barcheck failed at: 3/4");
        assertThat(complaintsIn(OUTPUT_2_26))
                .containsExactly("bar.ly:5:17: warning: bar check failed at: 3/4");
        assertThat(complaintsIn("""
                x.ly:9:3: warning: skipping zero-duration score
                x.ly:11:21: error: syntax error, unexpected SYMBOL
                programming error: system with empty extent
                fatal error: failed files: "x.ly"
                """)).hasSize(4);
        // Case, because LilyPond writes "Warning" nowhere today and a matcher
        // that only handled the lower-case spelling would be one release away
        // from reading nothing.
        assertThat(complaintsIn("part.ly:5:20: WARNING: bar check failed at: 3/4")).hasSize(1);
    }

    @Test
    @DisplayName("a clean run has nothing to complain about, so the empty list means something")
    void aCleanRunHasNoComplaints() {
        // Byte-exact from a real chord-chart run, both versions -- this is what
        // EndToEndIT asserts the flagship pipeline produces, and if any of these
        // ordinary progress lines counted the assertion could never pass.
        assertThat(complaintsIn("""
                Processing `chords.ly'
                Parsing...
                Interpreting music...[8][16]
                Preprocessing graphical objects...
                Finding the ideal number of pages...
                Fitting music on 1 page...
                Drawing systems...
                Converting to `chords.pdf'...
                Success: compilation successfully completed
                """)).isEmpty();
    }

    @Test
    @DisplayName("the tolerance is exactly one line, and everything either side of it still fails")
    void theToleranceIsNarrowerThanTheWordItContains() {
        // Nothing pinned the width: the filter could be widened to "any
        // line containing the word error" and the whole
        // suite stayed green, because the only negative fixture was a *warning*
        // and the carve-out sits on the error side. So the width is asserted
        // here, against the helper directly rather than through LilyPond -- a
        // synthetic Result is the only way to say "a different programming
        // error" without asking LilyPond to have one.
        assertThatNoException().isThrownBy(() -> assertEngravedCleanly(
                "only the tolerated line", engraved(TOLERATED_COMPLAINT), TOLERATED_COMPLAINT));
        assertThatNoException().isThrownBy(() ->
                assertEngravedCleanly("nothing at all", engraved("Processing `part.ly'")));

        // A different complaint of the same class. This is the one that matters:
        // "programming error" is not the tolerated thing, the rest of the line
        // is, and a filter keyed on the prefix would let every internal
        // complaint LilyPond has through.
        assertThatThrownBy(() -> assertEngravedCleanly("another programming error",
                engraved("programming error: cyclic dependency: chain of aligned objects"),
                TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
        // The tolerated text with anything else on the line, which is how a
        // substring match would have been fooled.
        assertThatThrownBy(() -> assertEngravedCleanly("more on the line",
                engraved(TOLERATED_COMPLAINT + " (and something else went wrong)"),
                TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
        // And the thing the ban exists for, beside the tolerated line rather
        // than instead of it -- in both of LilyPond's spellings (#145).
        //
        // The filter cannot read the bar-check wording at all: it selects on
        // "warning" or "error" and excludes exact strings, and javap confirms
        // no bar-check text in the method or its lambdas. A mutant adding
        // "&& !line.contains(\"barcheck\")" to the tolerance does make the
        // two spellings differ, and a real binary kills it: under 2.24
        // TupletEngravingIT.bracketsAreEngravedWithTheDurationTheyClaim reaches
        // this filter in the older spelling already.
        //
        // On the other version that is worth nothing: the mutant is killed
        // by integration tests under 2.24.3 and by *nothing at all* under
        // 2.26.0 -- so a developer on Homebrew LilyPond could widen
        // the tolerance to swallow bar checks and see a green suite. One line
        // here closes that, in the job everyone runs, on every machine.
        //
        // The pair below is about width in the sense this test is named for --
        // that neither spelling is special to the tolerance -- rather than
        // about the matcher, which is eitherSpellingIsFound's subject; they
        // are different API surfaces and the mutant that widens this filter
        // is invisible to that test.
        assertThatThrownBy(() -> assertEngravedCleanly("a bar check beside it",
                engraved(TOLERATED_COMPLAINT, "part.ly:5:20: warning: bar check failed at: 3/4"),
                TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertEngravedCleanly("the older spelling beside it",
                engraved(TOLERATED_COMPLAINT, "part.ly:5:20: warning: barcheck failed at: 3/4"),
                TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertEngravedCleanly("a real error",
                engraved("part.ly:5:20: error: syntax error, unexpected '}'"),
                TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
        // A run that produced no page at all is not clean however quiet it was.
        // LilyPond exits zero on a zero-duration score and writes nothing, so
        // this is the half of the helper that catches an empty chart.
        assertThatThrownBy(() -> assertEngravedCleanly("no page",
                new LilyPondRenderer.Result(false, Optional.empty(), ""), TOLERATED_COMPLAINT))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a caller that asks for no tolerance gets none, including for the tolerated line")
    void theToleranceIsNotGrantedUnlessItIsAskedFor() {
        // The hazard this pins (#164): while the tolerance was baked into
        // the helper, the obviously-named assertion
        // carried a carve-out for a tuplet-number spacing complaint to callers
        // engraving chord charts, which have neither beams nor tuplet numbers.
        // Every call site that engraves but one now passes nothing, and this is
        // what says that passing nothing means nothing -- a helper that kept the
        // old default while accepting the argument would pass every other case
        // in this class.
        assertThatThrownBy(() -> assertEngravedCleanly(
                "the tuplet carve-out, unasked-for", engraved(TOLERATED_COMPLAINT)))
                .isInstanceOf(AssertionError.class);
        // And more than one may be named, since the signature admits it.
        assertThatNoException().isThrownBy(() -> assertEngravedCleanly("two named",
                engraved(TOLERATED_COMPLAINT, "programming error: system with empty extent"),
                TOLERATED_COMPLAINT, "programming error: system with empty extent"));
    }

    /** A successful engraving that said exactly these lines. */
    private static LilyPondRenderer.Result engraved(String... output) {
        return new LilyPondRenderer.Result(true, Optional.of(Path.of("part.pdf")),
                String.join("\n", output) + "\n");
    }
}
