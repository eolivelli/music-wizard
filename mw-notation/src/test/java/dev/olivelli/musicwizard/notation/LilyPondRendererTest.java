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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the renderer does to the process it starts, checked without LilyPond.
 *
 * <p>Engraving itself belongs in {@code mw-it} and does not run here: {@code mvn
 * verify} has to stay fast, offline and binary-free. What the subprocess is
 * handed is not engraving — it is arithmetic done before anything starts — so it
 * is checked here, against an environment poisoned on purpose rather than
 * against the machine's own.
 *
 * <p>That distinction is the finding of round 5 and is why this file was
 * rewritten: asserting on the ambient environment proved nothing, because the
 * build machine has no {@code LANGUAGE} set and every mutation of the code under
 * test passed.
 */
class LilyPondRendererTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("LilyPond is asked for English messages, whatever the machine says")
    void theSubprocessIsAskedForEnglishMessages() {
        // Poisoned first, so the assertion is about what the method does rather
        // than about what this machine happens not to have set.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_MESSAGES", "it_IT.UTF-8");
        environment.put("LANGUAGE", "it");
        environment.put("LC_ALL", "it_IT.UTF-8");
        environment.put("LANG", "it_IT.UTF-8");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).containsEntry("LC_MESSAGES", "C");
    }

    @Test
    @DisplayName("an LC_ALL that would override the message language is moved out of the way")
    void anAmbientLcAllIsNeutralisedRatherThanLeftToWin() {
        // Round 6 of review found the previous version of this a no-op for the
        // commonest way of all to set a locale: POSIX has LC_ALL override every
        // individual category, so writing LC_MESSAGES=C beside an inherited
        // LC_ALL=it_IT.UTF-8 changes nothing and LilyPond says "attenzione: bar
        // check failed" -- which is round 4's bug, reopened by round 5's fix.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "it_IT.UTF-8");
        environment.put("LANG", "it_IT.UTF-8");
        environment.put("LANGUAGE", "it");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).doesNotContainKey("LC_ALL").containsEntry("LC_MESSAGES", "C");
        // Left alone: gettext ignores LANGUAGE once the messages locale is C,
        // and LANG is outranked by LC_MESSAGES for messages.
        assertThat(environment)
                .containsEntry("LANGUAGE", "it")
                .containsEntry("LANG", "it_IT.UTF-8");
    }

    @Test
    @DisplayName("everything LC_ALL was covering is written out in its place, not just the ctype")
    void everyMaskedCategoryIsCarriedRatherThanUnMasked() {
        // Round 5 of review found round 4 setting a C ctype and breaking
        // canción.ly outright, so round 6 carried LC_ALL forward into LC_CTYPE.
        // Round 7 found that this is still the bug one category wide: LC_ALL
        // masks every category, and glibc's locale selection is all-or-nothing,
        // so one un-masked variable naming a locale that is not installed takes
        // the whole child down to C -- ctype included, and canción.ly with it.
        // An ambient "LC_ALL=it_IT.UTF-8 LC_TIME=de_DE.UTF-8" does exactly that,
        // and the second half of it is inert until the first is removed.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "es_ES.UTF-8");
        environment.put("LC_TIME", "de_DE.UTF-8");
        environment.put("LC_NUMERIC", "fr_FR.UTF-8");
        environment.remove("LC_CTYPE");

        LilyPondRenderer.speakEnglish(builder);

        // Every category POSIX and glibc define, bar the one being changed.
        assertThat(environment)
                .containsEntry("LC_CTYPE", "es_ES.UTF-8")
                .containsEntry("LC_NUMERIC", "es_ES.UTF-8")
                .containsEntry("LC_TIME", "es_ES.UTF-8")
                .containsEntry("LC_COLLATE", "es_ES.UTF-8")
                .containsEntry("LC_MONETARY", "es_ES.UTF-8")
                .containsEntry("LC_PAPER", "es_ES.UTF-8")
                .containsEntry("LC_NAME", "es_ES.UTF-8")
                .containsEntry("LC_ADDRESS", "es_ES.UTF-8")
                .containsEntry("LC_TELEPHONE", "es_ES.UTF-8")
                .containsEntry("LC_MEASUREMENT", "es_ES.UTF-8")
                .containsEntry("LC_IDENTIFICATION", "es_ES.UTF-8")
                .containsEntry("LC_MESSAGES", "C");
    }

    @Test
    @DisplayName("the character type that was in force is the one kept, not the one being ignored")
    void anOverriddenCharacterTypeIsReplacedByTheOneThatWasWinning() {
        // LC_CTYPE set and LC_ALL set: the effective ctype is LC_ALL's, because
        // it outranks. Keeping the LC_CTYPE that was being ignored would change
        // how bytes are decoded, which is the thing this method must not do --
        // so the move is unconditional rather than putIfAbsent.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_CTYPE", "POSIX");
        environment.put("LC_ALL", "ja_JP.UTF-8");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).containsEntry("LC_CTYPE", "ja_JP.UTF-8");
    }

    @Test
    @DisplayName("a machine with no LC_ALL keeps the character type it already had")
    void nothingIsInventedWhenThereIsNoLcAllToMove() {
        // The ordinary case, and the one that must not acquire a stray LC_CTYPE:
        // an empty LC_ALL is not a setting either, since POSIX has it fall
        // through to the individual categories.
        for (String lcAll : new String[] {null, ""}) {
            ProcessBuilder builder = new ProcessBuilder("true");
            Map<String, String> environment = builder.environment();
            environment.remove("LC_CTYPE");
            if (lcAll == null) {
                environment.remove("LC_ALL");
            } else {
                environment.put("LC_ALL", lcAll);
            }
            environment.put("LANG", "de_DE.UTF-8");

            LilyPondRenderer.speakEnglish(builder);

            assertThat(environment)
                    .as("LC_ALL was %s", lcAll == null ? "unset" : "empty")
                    .doesNotContainKey("LC_CTYPE")
                    .doesNotContainKey("LC_TIME")
                    .containsEntry("LANG", "de_DE.UTF-8")
                    .containsEntry("LC_MESSAGES", "C");
        }
    }

    @Test
    @DisplayName("engraving actually goes through it, rather than the method merely existing")
    void theRendererAppliesItToTheProcessItStarts() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // Round 6 of review found that deleting both call sites left every test
        // green: the seam was tested and the wiring was not. A stand-in binary
        // that reports the locale it was started with is the only thing that can
        // see the difference without LilyPond.
        //
        // Round 8 then found this version of it inert on a machine whose own
        // LC_MESSAGES is already C, because the child then reports C whether or
        // not the code under test ran. The module's surefire configuration gives
        // this JVM a non-C LC_MESSAGES for exactly that reason. Checked rather
        // than assumed, because a build file losing that element would leave the
        // assertion below true for the wrong reason on such a machine -- and
        // true for the right reason everywhere else, which is how it would
        // survive a review.
        assertThat(System.getenv("LC_MESSAGES"))
                .as("mw-notation/pom.xml sets this for the test JVM; without it this test can"
                        + " still pass on a machine whose own LC_MESSAGES is C")
                .isNotEqualTo("C");
        Path script = tempDirectory.resolve("reports-locale");
        Files.writeString(script, """
                #!/bin/sh
                echo "LC_MESSAGES=[${LC_MESSAGES-unset}]"
                exit 1
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        LilyPondRenderer renderer = new LilyPondRenderer(script);

        assertThat(renderer.render(source).output()).contains("LC_MESSAGES=[C]");
        // And --version, which parses output too. Round 7 found the previous
        // assertion here -- that a version was present at all -- satisfied by
        // any output whatsoever, so deleting the call in version() was killed by
        // nothing.
        assertThat(renderer.version()).contains("LC_MESSAGES=[C]");
    }

    @Test
    @DisplayName("a binary that cannot engrave is reported, not thrown")
    void aFailedRunIsAResultRatherThanAnException() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The renderer's own contract, and the reason a failed engraving does
        // not lose the analysis that produced it. Uses a stand-in binary rather
        // than LilyPond, so it stays in the offline suite.
        Path script = tempDirectory.resolve("not-lilypond");
        Files.writeString(script, "#!/bin/sh\necho 'it went wrong'\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        LilyPondRenderer.Result result = new LilyPondRenderer(script).render(source);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.pdf()).isEmpty();
        assertThat(result.output()).contains("it went wrong");
    }

    @Nested
    @DisplayName("a bar that does not fill its meter")
    class FailedBarChecks {

        /** A result carrying nothing but the output, which is all the parse reads. */
        private LilyPondRenderer.Result said(String output) {
            return new LilyPondRenderer.Result(true, Optional.empty(), output);
        }

        @Test
        @DisplayName("is reported even though LilyPond called the run a success")
        void isVisibleOnAResultThatSucceeded() throws Exception {
            assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

            // #156, end to end through the renderer and with no LilyPond: a
            // failed bar check is a *warning*, so the engraver draws the short
            // bar, writes a real PDF and exits zero. Before this, that fact
            // lived in output() and nothing could ask for it, so RenderCommand
            // -- which branches on succeeded() alone -- printed "Wrote
            // chords.pdf" for a chart whose bars do not sum.
            //
            // The stand-in reproduces all three halves of that: the message,
            // the PDF, and the zero. Measured against real LilyPond 2.26.0
            // first, which said "bad.ly:2:42: warning: bar check failed at:
            // 3/4", then "Success: compilation successfully completed", then
            // exited 0 having written 28833 bytes of PDF.
            Path script = tempDirectory.resolve("engraves-a-short-bar");
            Files.writeString(script, """
                    #!/bin/sh
                    echo "part.ly:5:17: warning: bar check failed at: 3/4"
                    echo "Success: compilation successfully completed"
                    : > part.pdf
                    exit 0
                    """);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            Path source = tempDirectory.resolve("part.ly");
            Files.writeString(source, "% a bar short\n");

            LilyPondRenderer.Result result = new LilyPondRenderer(script).render(source);

            assertThat(result.succeeded())
                    .as("the PDF is real; succeeded() answers 'was one produced', not 'is it right'")
                    .isTrue();
            assertThat(result.pdf()).isPresent();
            assertThat(result.failedBarChecks()).containsExactly("3/4");
        }

        @Test
        @DisplayName("is recognised in both of LilyPond's spellings of it")
        void bothSpellingsAreRead() {
            // #145: the message is one string in LilyPond's source and it was
            // rewritten at 2.25.6. CI installs Ubuntu's 2.24.3 and says
            // "barcheck"; Homebrew ships 2.26 and says "bar check". Reading one
            // is being blind to the other, and blind looks exactly like correct.
            assertThat(said("x.ly:5:17: warning: barcheck failed at: 3/4").failedBarChecks())
                    .as("2.24.3, which is what the integration job installs")
                    .containsExactly("3/4");
            assertThat(said("x.ly:5:17: warning: bar check failed at: 3/4").failedBarChecks())
                    .as("2.26.0, which is what Homebrew ships")
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is every moment LilyPond named, in order and with repeats kept")
        void everyMomentIsCarried() {
            // 2.24.3 reports each failure; 2.25.6 onwards reports only the first
            // per context. Neither count is asserted anywhere -- what is carried
            // is whatever LilyPond said, so a caller can name the bars.
            assertThat(said("""
                    part.ly:5:17: warning: bar check failed at: 3/4
                    part.ly:9:17: warning: barcheck failed at: 7/8
                    part.ly:13:17: warning: bar check failed at: 3/4
                    """).failedBarChecks())
                    .containsExactly("3/4", "7/8", "3/4");
        }

        @Test
        @DisplayName("is not read out of the source line LilyPond echoes back")
        void anEchoedSourceLineIsNotASecondComplaint() {
            // Round 1 of review found the previous version of this claiming more
            // than it tested, and the claim was false. LilyPond echoes the
            // offending line after each diagnostic *verbatim and with no prefix
            // of its own*, so requiring "warning:" does not hold an echo out --
            // an echo of a line containing the phrase carries the prefix along
            // with it. Measured on 2.26.0: one real failure, two moments
            // reported.
            //
            // This fixture is that output, byte for byte. The line start is what
            // separates the two: a diagnostic begins with a location or with
            // "warning:", an echo begins with the source's own text.
            assertThat(said("""
                    echo.ly:2:83: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4 c4 c4 %{ warning: bar check failed at: 99/9 %}
                                                                                     | c1 | }
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is not read out of the half of an echo that starts at column 0")
        void anEchoIsSplitAtTheFailingColumnAndItsFirstHalfIsNotIndented() {
            // Round 2 of review found round 1's reasoning false, and this is the
            // fixture that shows it. LilyPond does not print the echo as one
            // indented block: it splits the source line *at the failing column*,
            // prints everything before that column starting at column 0, and
            // indents only the remainder. So an echo does begin at a line start,
            // and anchoring there proves nothing on its own.
            //
            // Byte-exact 2.26.0 output for a one-line source whose title says the
            // phrase. The previous version of this file asserted on an indented
            // continuation instead and claimed a title "is escaped and quoted,
            // never a line start, so it cannot pose as a diagnostic" -- both
            // halves wrong, and it passed on its two leading spaces.
            assertThat(said("""
                    titled.ly:2:105: warning: bar check failed at: 3/4
                    \\header { title = "a:1:2: warning: bar check failed at: 9/9" } \\score { \\new Staff { \\time 4/4 c4 c4 c4
                                                                                                            | c1 | } }
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .as("the unanchored form gives [3/4, 9/9\"], and the line-start anchor alone"
                            + " does not change that")
                    .containsExactly("3/4");
            // What does separate them is the end of the line: a diagnostic is a
            // format string whose last token is the moment, so nothing follows
            // it, while an echo fragment carries the rest of the source line.
            assertThat(said("part.ly:1:1: warning: bar check failed at: 1/2 % and more source\n")
                    .failedBarChecks())
                    .as("text after the moment means this was never a diagnostic line")
                    .isEmpty();
        }

        @Test
        @DisplayName("is read whatever the location looks like, and with none at all")
        void theLocationIsNotWhatIsBeingMatchedOn() {
            // A file name may contain a space -- 2.26.0 prints "my song.ly:2:42:
            // warning: bar check failed at: 3/4", measured -- so a pattern that
            // read the location as a run of non-space would go silent on exactly
            // the input it was written for.
            assertThat(said("my song.ly:2:42: warning: bar check failed at: 3/4").failedBarChecks())
                    .containsExactly("3/4");
            assertThat(said("canción.ly:2:42: warning: bar check failed at: 5/8").failedBarChecks())
                    .containsExactly("5/8");
            // And with no location, and with a line but no column, so that a
            // LilyPond dropping either makes this over-report rather than go
            // blind. Blind is the failure this whole class exists to avoid: it
            // looks exactly like correct output. Neither shape occurs today --
            // round 2 of review confirmed every real diagnostic on 2.24.3 and
            // 2.26.0 carries both numbers -- and accepting them costs nothing.
            assertThat(said("warning: bar check failed at: 7/8\n").failedBarChecks())
                    .containsExactly("7/8");
            assertThat(said("bad.ly:12: warning: bar check failed at: 5/4\n").failedBarChecks())
                    .containsExactly("5/4");
        }

        @Test
        @DisplayName("is not read out of a file merely named after the phrase")
        void aNameThatContainsThePhraseIsNotAComplaint() {
            assertThat(said("""
                    Processing `bar check failed at: 3/4.ly'
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .isEmpty();
        }

        @Test
        @DisplayName("is empty for a clean engraving, and for a run that never got that far")
        void nothingIsInventedWhenLilyPondDidNotSayIt() {
            assertThat(said("Success: compilation successfully completed\n").failedBarChecks())
                    .isEmpty();
            // The timeout and interrupt paths synthesise their own output.
            // "LilyPond did not complain" and "LilyPond never ran" are both
            // already reported by succeeded() == false; neither is a bar-check
            // finding, and inventing one would be worse than saying nothing.
            assertThat(said("LilyPond did not finish within 120 seconds").failedBarChecks())
                    .isEmpty();
        }

        @Test
        @DisplayName("does not go missing when LilyPond words the diagnostic as an error")
        void aBenignDiagnosticIsNotPromotedToAWrongPage() {
            // The other half of the decision, and the reason this is not "did
            // LilyPond say anything". #136 is a tuplet-number placement
            // complaint that LilyPond words as a programming error, is reachable
            // from ordinary 4/4 in roughly one staff in eighty, and describes a
            // page whose music is correct. #92 found benign diagnostics carrying
            // "error:" too. Warning on those teaches a user to skip the line
            // that matters.
            assertThat(said("""
                    programming error: not enough space for tuplet number against beam
                    continuing, cross fingers
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .isEmpty();
        }
    }
}
