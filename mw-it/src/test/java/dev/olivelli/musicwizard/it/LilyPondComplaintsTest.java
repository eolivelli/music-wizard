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

import static dev.olivelli.musicwizard.it.LilyPondComplaints.failedBarChecksIn;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link LilyPondComplaints} does and does not count as a failed bar check.
 *
 * <p>No LilyPond here on purpose. The engraving tests can only show the matcher
 * against whichever version is installed on the machine running them, which is
 * precisely the blind spot #145 lived in — green under 2.26 and red under 2.24,
 * with nothing anywhere saying the two differ. The fixtures below are verbatim
 * output from both versions, so one run says the matcher handles both.
 *
 * <p>The other half matters at least as much. A matcher that is too generous
 * fails a clean engraving, which is loud; a matcher keyed on a substring that
 * turns up in ordinary output would pass everything, which is silent, and
 * silent is the failure this suite keeps finding. So every line LilyPond emits
 * around a bar check is here as a case that must <em>not</em> match.
 *
 * <p><b>The one {@code Test} in a module of {@code IT}s</b>, and deliberately.
 * Everything else here is slow or wants a binary, so it belongs to failsafe and
 * to {@code -Pintegration}; this needs neither and takes a tenth of a second.
 * Round 1 of review pointed out what leaving it under that profile would mean:
 * the guard against the matcher rotting would live inside the job whose being
 * unread for weeks is the whole subject of #145. It runs in {@code mvn verify}
 * instead, which is the check people actually look at.
 */
class LilyPondComplaintsTest {

    /** Verbatim from LilyPond 2.24.3, the version the {@code integration} job installs. */
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

    /** Verbatim from LilyPond 2.26.0, which is what Homebrew installs. */
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
        // Why the matcher insists on the "warning:" prefix and on the moment
        // rather than looking for the phrase. LilyPond echoes file names and the
        // offending line of source back at you, so the phrase can appear in
        // output that contains no failed bar check at all -- and a test that
        // matched it would pass on a file named after the bug it was written
        // for.
        assertThat(failedBarChecksIn("Processing `bar check failed.ly'")).isEmpty();
        assertThat(failedBarChecksIn("Converting to `barcheck failed at 3-4.pdf'...")).isEmpty();
        assertThat(failedBarChecksIn("  % barcheck failed at: 3/4 -- fixed in the next bar"))
                .isEmpty();
        // And the phrase run together with no space at all, which is neither
        // spelling and should not be treated as one.
        assertThat(failedBarChecksIn("bar.ly:5:17: warning: barcheckfailed at: 3/4")).isEmpty();
    }
}
