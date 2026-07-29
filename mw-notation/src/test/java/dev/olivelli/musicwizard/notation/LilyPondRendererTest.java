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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        /**
         * The same, for a fixture that is supposed to be real engraver output —
         * and which checks that it still is.
         *
         * <p>Round 4 of review found three fixtures in this file claiming to be
         * byte-exact 2.26.0 output while being off by as much as 17 columns, and
         * columns are precisely what the echo is now recognised by. One of them
         * had lost a single trailing space to a Java text block, which was enough
         * that a working fix for the over-report still left this file green.
         *
         * <p>So the layout is asserted rather than trusted: after a diagnostic
         * naming column C, the next line must print C - 1 wide and the one after
         * must be indented by C - 1 spaces. A fixture that drifts fails here,
         * saying so, instead of quietly testing nothing.
         */
        private LilyPondRenderer.Result engraverSaid(String output) {
            List<String> lines = output.lines().toList();
            Pattern located = Pattern.compile(
                    "^.*:(\\d+):(\\d+): warning: bar ?check failed at: \\S+\\s*$");
            int pairs = 0;
            for (int i = 0; i + 2 < lines.size(); i++) {
                Matcher diagnostic = located.matcher(lines.get(i));
                if (!diagnostic.matches()) {
                    continue;
                }
                int column = Integer.parseInt(diagnostic.group(2));
                String beforeColumn = lines.get(i + 1);
                String fromColumn = lines.get(i + 2);
                int indent = 0;
                while (indent < fromColumn.length() && fromColumn.charAt(indent) == ' ') {
                    indent++;
                }
                int printed = 0;
                for (int c = 0; c < beforeColumn.length(); c++) {
                    printed = beforeColumn.charAt(c) == '\t' ? (printed / 8 + 1) * 8 : printed + 1;
                }
                assertThat(printed)
                        .as("fixture is not real output: line after a column-%d diagnostic"
                                + " should print %d wide%n[%s]", column, column - 1, beforeColumn)
                        .isEqualTo(column - 1);
                assertThat(indent)
                        .as("fixture is not real output: the second echo line after a column-%d"
                                + " diagnostic should be indented %d%n[%s]",
                                column, column - 1, fromColumn)
                        .isEqualTo(column - 1);
                pairs++;
                // Past the pair just verified, so the phrase *inside* the echo
                // is not mistaken for a second diagnostic to check the layout
                // of -- which is the very confusion the parser exists to avoid,
                // and which this check walked straight into on its first run.
                i += 2;
            }
            assertThat(pairs).as("no diagnostic-and-echo pair found in this fixture%n%s", output)
                    .isPositive();
            return said(output);
        }

        @Test
        @DisplayName("is reported even though LilyPond called the run a success")
        void isVisibleOnAResultThatSucceeded() throws Exception {
            assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

            // #156, end to end through the renderer and with no LilyPond: a
            // failed bar check is a *warning*, so the engraver draws the short
            // bar, writes a real PDF and exits zero. The fact lived in output()
            // and nothing could ask for it, so a caller branching on succeeded()
            // alone -- which is what RenderCommand did -- had nothing to print
            // about a page whose music does not match the score. No user had
            // been handed such a chart, because the chord chart emits no bar
            // checks; StaffNotation's output does, and only mw-it engraves it.
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
            // LilyPond quotes the offending line back after each diagnostic,
            // verbatim and with no prefix of its own, so an echo of a line
            // containing the phrase carries the "warning:" along with it. Round 1
            // of review measured that against 2.26.0 -- one real failure, two
            // moments reported by the pattern this replaced.
            //
            // Every fixture in this class is now the engraver's own output,
            // captured and pasted rather than typed. Round 4 found three of them
            // claiming to be byte-exact while being off by as much as 17 columns,
            // and columns are exactly what the echo is recognised by, so a
            // hand-typed fixture cannot test the thing this file is about. The
            // \s escapes are load-bearing: a text block strips trailing spaces,
            // and the first echo line ends in one.
            assertThat(engraverSaid("""
                    echoComment.ly:2:83: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4 c4 c4 %{ warning: bar check failed at: 99/9 %}\s
                                                                                                      | c1 | } \\layout {} }
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is not read out of either half of the echo, wherever the check failed")
        void bothHalvesOfTheEchoAreRecognisedAndSkipped() {
            // The two shapes rounds 2 and 3 each found the previous fix missing,
            // and the reason this class stopped trying to tell a diagnostic from
            // an echo by what the line looks like. LilyPond splits the echo at
            // the failing column: the part before it is printed at column 0 and
            // the part from it is indented to line up.
            //
            // Round 3's shape -- the *second* half, which ends at the end of the
            // source line whatever the column was. Real 2.26.0 output, check
            // failing at column 42, phrase in a trailing comment.
            assertThat(engraverSaid("""
                    tail.ly:2:42: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4 c4 c4\s
                                                             | c4 c4 c4 c4 | } } % a:1:2: warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .as("was [3/4, 9/9] until the echo was recognised by its layout")
                    .containsExactly("3/4");
            // Round 4's shape -- the *first* half, with the phrase inside a ^"..."
            // markup, which is ordinary LilyPond and is what engraved lyrics will
            // emit (#9). Note the fabricated moment carried a stray quote: the
            // user would have been shown `at 3/4, 9/9"`.
            assertThat(engraverSaid("""
                    firstHalf.ly:2:84: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4 c4 c4^"a:1:2: warning: bar check failed at: 9/9"
                                                                                                       | c4 c4 c4 c4 | } }
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .as("was [3/4, 9/9\"] until the echo was recognised by its layout")
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is recognised when the source line holds tabs, which LilyPond counts as columns")
        void aTabbedSourceLineIsStillRecognisedAsAnEcho() {
            // LilyPond reports a *column*, not a character offset, and it counts
            // a tab as advancing to the next eight-column stop. Real 2.26.0
            // output: the line before the column is 41 characters holding three
            // tabs, and the diagnostic says column 52 -- so a reader comparing
            // characters would see 41 against 51, decline to recognise the echo,
            // and report the phrase in the trailing comment as a second failure.
            //
            // Round 4's sweep found this untested: with the width comparison
            // loose, counting a tab as one column changed no result. It is exact
            // now, so the expansion is load-bearing and this fixture proves it.
            assertThat(engraverSaid("""
                    tabs.ly:2:52: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4\tc4\tc4\tc4\s
                                                                       | c4 c4 c4 c4 | } } % a:1:2: warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is still read when the diagnostics come one after another with their echoes")
        void everyDiagnosticIsStillReadWhenEachHasAnEcho() {
            // The other half of the echo skip, and the one that would make it a
            // fix worse than the bug: skipping two lines after every diagnostic
            // must not eat the next diagnostic. This is the 2.24.3 shape, which
            // reports every failure rather than one per context, so the pairs
            // come back to back. Widths are exactly what LilyPond produces --
            // first line `column - 1` wide, second indented by the same.
            assertThat(said("""
                    p.ly:1:10: warning: bar check failed at: 2/4
                    xxxxxxxxx
                             | rest
                    p.ly:1:20: warning: bar check failed at: 5/4
                    xxxxxxxxxxxxxxxxxxx
                                       | rest
                    p.ly:1:30: warning: bar check failed at: 9/4
                    xxxxxxxxxxxxxxxxxxxxxxxxxxxxx
                                                 | rest
                    """).failedBarChecks())
                    .containsExactly("2/4", "5/4", "9/4");
        }

        @Test
        @DisplayName("is still read when there is no echo at all, or only half of one")
        void aMissingOrMalformedEchoCostsNothing() {
            // The property the whole design rests on: the layout test can only
            // suppress a match, never admit one, so a LilyPond that changed how
            // it echoes lands on this class's previous behaviour -- over-reporting
            // -- and never on silence.
            //
            // No echo at all.
            assertThat(said("""
                    p.ly:1:10: warning: bar check failed at: 3/4
                    p.ly:2:20: warning: bar check failed at: 7/8
                    p.ly:3:30: warning: bar check failed at: 1/2
                    """).failedBarChecks())
                    .containsExactly("3/4", "7/8", "1/2");
            // One echo line where there should be two, with a diagnostic-shaped
            // line in the gap: the pair is not recognised, so the echo is
            // over-reported -- and the real diagnostic after it is still read.
            assertThat(said("""
                    p.ly:1:10: warning: bar check failed at: 3/4
                    p.ly:9:9: warning: bar check failed at: 9/9
                    p.ly:2:20: warning: bar check failed at: 7/8
                    """).failedBarChecks())
                    .as("degrades to over-reporting, which is the safe direction")
                    .containsExactly("3/4", "9/9", "7/8");
        }

        @Test
        @DisplayName("is lost only where a column-1 diagnostic is followed by a blank line")
        void theOneShapeTheEchoSkipCanSwallow() {
            // The single under-report this design admits, recorded rather than
            // left to be discovered. To lose a diagnostic it must be indented by
            // exactly `column - 1` spaces -- and a diagnostic line begins with
            // its own location, never a space, so the only column that can match
            // is 1, which in turn needs the line between them to be blank.
            //
            // That is a compound of behaviours never observed together: at column
            // 1 the real echo *is* an empty line followed by the source line, so
            // reaching this needs LilyPond to have stopped echoing while still
            // emitting the blank. Written down because "cannot happen" is the
            // claim this file has had to retract three times.
            assertThat(said("""
                    p.ly:1:1: warning: bar check failed at: 3/4

                    p.ly:2:20: warning: bar check failed at: 7/8
                    """).failedBarChecks())
                    .as("known under-report; the blank line is read as a column-1 echo")
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is not read out of a phrase sitting mid-line, which is the start anchor's job")
        void theStartAnchorIsLoadBearingToo() {
            // Round 3 of review found the leading anchor killed by nothing: every
            // echo fixture happened to be rejected by the closing anchor instead,
            // so the pattern could have been simplified with a green build and a
            // false positive reintroduced. This is the shape only whole-line
            // matching rejects -- an echoed tail with no location in front of it,
            // so the optional location group cannot absorb the text before it.
            //
            // Real 2.26.0 output. Belt as well as braces now that the echo is
            // skipped by layout: this is what happens if the layout test ever
            // declines, which is the case above.
            assertThat(engraverSaid("""
                    noCaret.ly:2:42: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4 c4 c4\s
                                                             | c4 | } } % see warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
            // And text after the moment means the line was never a diagnostic.
            assertThat(said("part.ly:1:1: warning: bar check failed at: 1/2 % and more source\n")
                    .failedBarChecks())
                    .isEmpty();
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
