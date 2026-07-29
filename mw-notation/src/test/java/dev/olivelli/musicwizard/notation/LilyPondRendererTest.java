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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
                // Code points, not chars, and tabs to eight-column stops --
                // the same arithmetic LilyPond uses. Round 5 found this check
                // counting chars, which meant it would have certified an
                // astral-bearing fixture as real output while the parser
                // disagreed with the engraver about it.
                int printed = 0;
                for (int c = 0; c < beforeColumn.length();
                        c += Character.charCount(beforeColumn.codePointAt(c))) {
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
        @DisplayName("is not skipped when the first line is merely short enough, but only when it fits")
        void theWidthMustMatchExactlyRatherThanMerelyNotOverflow() {
            // Why the width is compared with = and not <=. Leniency here means
            // skipping *more*, and skipping is the only thing in this class that
            // can lose a diagnostic -- so the loose form is loose in the unsafe
            // direction, which is the opposite of how it reads.
            //
            // Synthetic, because LilyPond does not produce a short first echo
            // line: the point is what happens if it ever did. Here a real
            // diagnostic sits where the echo's first half would be, narrower
            // than the column calls for. Exact declines to skip and reports both;
            // <= treats it as an echo and swallows the second.
            //
            // Found by a sweep that loosened the comparison back and killed
            // nothing, which is how the tab handling had been unreachable too.
            assertThat(said("p.ly:1:51: warning: bar check failed at: 3/4\n"
                    + "p.ly:1:1: warning: bar check failed at: 9/9\n"
                    + " ".repeat(50) + "| rest\n").failedBarChecks())
                    .as("50 spaces of indent, but a 43-wide line where 50 was called for")
                    .containsExactly("3/4", "9/9");
        }

        @Test
        @DisplayName("is not skipped when the second line is indented further than the column")
        void theIndentMustMatchExactlyToo() {
            // The sibling of the width comparison, and round 5 found it pinned
            // by nothing: loosening this one to >= killed no test either. Same
            // argument, same direction -- a looser indent test skips more, and
            // skipping is the only thing here that can lose a diagnostic.
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4\n"
                    + "xxx\n"
                    + "      p.ly:9:9: warning: bar check failed at: 9/9\n").failedBarChecks())
                    .as("six spaces of indent where three were called for")
                    .containsExactly("3/4", "9/9");
        }

        @Test
        @DisplayName("is not skipped when the second line is padded with a tab rather than spaces")
        void theEchoPaddingIsSpacesAndNothingElse() {
            // LilyPond pads the second half of an echo with spaces whatever the
            // source held, so a tab there means this pair is not an echo.
            // Round 5 found the spaces-only rule unpinned: widening it to
            // Character.isWhitespace killed nothing, and it widens in the skip-
            // more direction.
            // Column 2, so one column of indent is called for and the line
            // offers exactly one tab. Counting whitespace generally would make
            // that a match and swallow the diagnostic; counting spaces does not.
            // The first version of this test used a column of 9 against a single
            // tab, where both rules say no -- it passed either way and pinned
            // nothing, which is the mistake it was written to catch.
            assertThat(said("p.ly:1:2: warning: bar check failed at: 3/4\n"
                    + "x\n"
                    + "\tp.ly:9:9: warning: bar check failed at: 9/9\n").failedBarChecks())
                    .as("a tab where one space of padding was called for is not padding")
                    .containsExactly("3/4", "9/9");
        }

        @Test
        @DisplayName("does not fall over when the output stops in the middle of an echo")
        void aTruncatedOutputIsReadRatherThanThrown() {
            // The bounds guard, which round 5 found load-bearing rather than
            // decorative: with it off by one this throws
            // ArrayIndexOutOfBoundsException, and a killed or timed-out engraver
            // is exactly what leaves a half-written echo behind. An exception
            // here is worse than either failure this class chooses between,
            // because it escapes render() after the files are on disk.
            // No trailing terminator, so the split really does stop one line
            // short of what the echo test wants to read. With a terminator the
            // split leaves an empty final element and the off-by-one is hidden,
            // which is how the first version of this test passed against the
            // mutant it was written for.
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4\nxxx")
                    .failedBarChecks())
                    .containsExactly("3/4");
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4\nxxx\n")
                    .failedBarChecks())
                    .containsExactly("3/4");
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4\n").failedBarChecks())
                    .containsExactly("3/4");
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4").failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("survives a column too large to be a number")
        void anUnparseableColumnIsNotAnException() {
            // Round 5 got a NumberFormatException out of the accessor with this,
            // which escapes render() after the .txt, .ly and .pdf are written --
            // neither of the two outcomes this class says it degrades between.
            // A column nothing can parse is a column no layout can be checked
            // against, so it counts as no column: no skip, and the echo is
            // over-reported.
            assertThat(said("x.ly:1:99999999999999: warning: bar check failed at: 3/4\n")
                    .failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("counts a column the way LilyPond does when the line holds an astral character")
        void aSupplementaryCharacterIsOneColumnAndNotTwo() {
            // Round 5, and not an exotic case for this project of all projects:
            // a \markup carrying a clef or a note glyph is outside the basic
            // plane, so it is two chars in Java and one column to LilyPond. Real
            // 2.26.0 output for `c4^"<treble clef>"` before the failing column --
            // 45 columns, 46 Java chars -- reported [3/4, 9/9] while the same
            // source with a same-width plain character reported [3/4].
            //
            // The fixture is the engraver's own, and engraverSaid checks the
            // layout with the same code-point arithmetic, which is the other
            // half of round 5's finding: the check could not have caught this
            // while it counted chars.
            assertThat(engraverSaid("""
                    astral.ly:2:46: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4^"𝄞" c4 c4\s
                                                                 | c1 | } } % a:1:2: warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("counts a combining mark as its own column, because LilyPond does")
        void aCombiningMarkIsNotZeroWidth() {
            // The half of the code-point decision that was asserted rather than
            // measured when it was written, and is measured now. Treating a
            // combining mark as zero width is the obvious "correct" thing to do
            // and would be wrong here: LilyPond gives it a column of its own, so
            // a code-point count is what agrees with the engraver and a grapheme
            // count would reintroduce the divergence in the other direction.
            //
            // Measured on 2.26.0, column against code points, for the whole
            // family that could have had its own layer:
            //
            //   plain ASCII          48 / 47      astral treble clef   46 / 45
            //   NFC e-acute          46 / 45      NFD e + combining    47 / 46
            //   zero-width joiner    48 / 47      variation selector   47 / 46
            //   bidi mark            48 / 47      CJK wide             46 / 45
            //
            // Column is code points + 1 in every one, so none of normalisation,
            // combining, joining, variation selection, bidi or East Asian width
            // is a further layer here. This fixture is the NFD case, which is
            // the one the claim was about, and the mark is written as a
            // backslash-u escape: a raw combining character in source is
            // invisible in a diff, which is the same reason CLAUDE.md bans
            // raw control characters outright.
            assertThat(engraverSaid("""
                    combining.ly:2:47: warning: bar check failed at: 3/4
                    \\score { \\new Staff { \\time 4/4 c4^"e\u0301" c4 c4\s
                                                                  | c1 | } } % a:1:2: warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("reads a moment followed by trailing whitespace, which the pattern allows on purpose")
        void trailingWhitespaceAfterTheMomentIsTolerated() {
            // Round 5 found the pattern's trailing \s* unpinned. It is there
            // because a capture path may pad or a terminal may not, and dropping
            // it would make this blind to a diagnostic that is otherwise perfect
            // -- the direction that matters.
            assertThat(said("p.ly:1:5: warning: bar check failed at: 3/4   \n").failedBarChecks())
                    .containsExactly("3/4");
            assertThat(said("p.ly:1:5: warning: bar check failed at: 3/4\t\n").failedBarChecks())
                    .containsExactly("3/4");
        }

        @Test
        @DisplayName("is lost in either skipped line when LilyPond emitted no echo there")
        void theShapesTheEchoSkipCanSwallow() {
            // The residual, stated by execution because three attempts to state
            // it by reasoning were each wrong -- and wrong the same way twice,
            // by describing one of the two skipped lines and calling it the
            // rule. They are tested differently: the first by its printed width
            // alone, the second by its indentation alone.
            //
            // First line. No indentation needed, no unusual file name, just a
            // width of C-1. The 9/9 diagnostic below is 43 columns wide and the
            // one above it reported column 44.
            String swallowed = "x.ly:1:2: warning: bar check failed at: 9/9";
            assertThat(said("p.ly:1:44: warning: bar check failed at: 3/4\n"
                    + swallowed + "\n"
                    + " ".repeat(43) + "| c4 c4 c4 | } }\n").failedBarChecks())
                    .as("round 6: the first skipped line needs only the width")
                    .containsExactly("3/4");
            // Second line. Here the indentation is what has to match, which
            // needs a file name that begins with whitespace, since LilyPond
            // prints it verbatim.
            //
            // Column 1 used to be the other way in, and is not any more: an
            // indent of zero is satisfied by *any* unindented line, so column 1
            // matched a blank line and the next real diagnostic and swallowed
            // it. A skip now needs a column of 2 or more. Round 7's generator
            // showed that essentially all the loss lived there -- with echoes
            // absent or malformed at random it failed within 500 trials, always
            // on this shape, and passes with the column floor in place. The cost
            // is that a genuine column-1 echo is no longer recognised and its
            // text may be over-reported, which is the direction this class
            // prefers, and a bar check at column 1 means the bar line is the
            // first thing on its source line -- which is not how anything here
            // emits one.
            assertThat(said("p.ly:1:1: warning: bar check failed at: 3/4\n"
                    + "\n"
                    + "p.ly:2:20: warning: bar check failed at: 7/8\n").failedBarChecks())
                    .as("column 1 no longer skips at all, so nothing is swallowed")
                    .containsExactly("3/4", "7/8");
            assertThat(said("p.ly:1:4: warning: bar check failed at: 3/4\n"
                    + "xxx\n"
                    + "   spaced.ly:9:9: warning: bar check failed at: 9/9\n").failedBarChecks())
                    .as("round 6: a file name beginning with whitespace, at any column")
                    .containsExactly("3/4");
            // And the one precondition all three share: LilyPond emitted no echo
            // where one was due. Give them their echoes and nothing is lost.
            assertThat(said("p.ly:1:44: warning: bar check failed at: 3/4\n"
                    + " ".repeat(43) + "\n"
                    + " ".repeat(43) + "| rest\n"
                    + swallowed + "\n").failedBarChecks())
                    .as("with a real echo in the way, the second diagnostic survives")
                    .containsExactly("3/4", "9/9");
        }

        @Test
        @DisplayName("over-reports a genuine column-1 echo, which is what the column floor costs")
        void aColumnOneEchoIsNoLongerRecognised() {
            // The price of the column floor, paid deliberately and measured
            // rather than assumed. At column 1 the echo's first half is an empty
            // line and its second is unindented, so "indented by column - 1" is
            // satisfied by any unindented line at all -- which is how a blank
            // line and the next real diagnostic came to be read as an echo and
            // swallowed. Refusing to skip below column 2 closes that, and the
            // cost is here: a real column-1 echo is no longer recognised, so
            // diagnostic-shaped text in it is reported.
            //
            // Real 2.26.0 output, and an over-report is the direction this class
            // prefers. Not reachable from what this project emits either: a bar
            // check at column 1 means the bar line is the first thing on its
            // source line, and StaffNotation writes it at the end of one.
            assertThat(engraverSaid("""
                    col1.ly:3:1: warning: bar check failed at: 3/4

                    | c1 | } } % a:1:2: warning: bar check failed at: 9/9
                    Success: compilation successfully completed
                    """).failedBarChecks())
                    .as("known over-report, and the reason the column floor is affordable")
                    .containsExactly("3/4", "9/9");
        }

        @Test
        @DisplayName("is not lost to an echo that was present but not recognised")
        void anUnrecognisedEchoCannotEatTheNextDiagnostic() {
            // Round 7, and the reason the residual's precondition had to become
            // "no echo *this parser recognises*" rather than "no echo". An echo
            // LilyPond truncates (#169) is present but fails the layout test, so
            // it is read as a diagnostic in its own right -- the accepted
            // over-report -- and its fabricated column then drove a skip that
            // swallowed the *next* real diagnostic. An over-report chaining into
            // a loss, which is the one thing this design says cannot happen.
            //
            // Closed by not letting a line inside an expected-but-missing echo
            // trigger a skip of its own. That can only ever skip less, so it
            // costs nothing: the fabricated moment is still reported.
            assertThat(said("chords.ly:1:20: warning: bar check failed at: 3/4\n"
                    + "xxxxx % a:1:1: warning: bar check failed at: 9/9\n"
                    + "\n"
                    + "chords.ly:2:30: warning: bar check failed at: 7/8\n").failedBarChecks())
                    .as("the 7/8 was lost and the fabricated 9/9 put in its place")
                    .containsExactly("3/4", "9/9", "7/8");
            // The same with a fabricated column that is not 1, so this cannot be
            // passing by refusing column 1 alone.
            assertThat(said("chords.ly:1:20: warning: bar check failed at: 3/4\n"
                    + "xxxxx % a:1:6: warning: bar check failed at: 9/9\n"
                    + "xxxxx\n"
                    + "     chords.ly:2:30: warning: bar check failed at: 7/8\n")
                    .failedBarChecks())
                    .containsExactly("3/4", "9/9", "7/8");
        }

        @Test
        @DisplayName("is never lost while every diagnostic still has its echo")
        void thePreconditionIsTheWholeOfTheResidual() {
            // The residual's completeness, checked by generation rather than by
            // reasoning -- because reasoning about it has now been wrong three
            // times, each time by describing one of the two skipped lines and
            // calling it the rule.
            //
            // Every located diagnostic here is followed by a well-formed echo,
            // and the shapes that beat earlier versions are mixed in on purpose:
            // file names that begin with spaces, which LilyPond prints verbatim,
            // and echo text that is itself diagnostic-shaped. If "the echo was
            // missing" is really the only precondition, nothing can be lost.
            //
            // Seeded, so a failure is reproducible rather than a rumour. The
            // same generator with echoes omitted at random loses 36 moments in
            // 20000 outputs, which is the residual doing what it is documented
            // to do; with the echoes present it loses none in 20000.
            Random random = new Random(20260729L);
            for (int trial = 0; trial < 2000; trial++) {
                StringBuilder output = new StringBuilder();
                List<String> expected = new ArrayList<>();
                for (int diagnostic = 1 + random.nextInt(4); diagnostic > 0; diagnostic--) {
                    for (int noise = random.nextInt(3); noise > 0; noise--) {
                        output.append(random.nextBoolean() ? "" : "Interpreting music...")
                                .append('\n');
                    }
                    int column = 1 + random.nextInt(60);
                    String moment = (1 + random.nextInt(9)) + "/" + (1 + random.nextInt(8));
                    String file = (random.nextInt(4) == 0 ? "  " : "") + "f.ly";
                    output.append(file).append(":1:").append(column)
                            .append(": warning: bar check failed at: ").append(moment).append('\n');
                    expected.add(moment);
                    String tail = random.nextInt(3) == 0
                            ? " % a:1:2: warning: bar check failed at: 9/9"
                            : " | c4 c4";
                    // The echo is well formed only most of the time. Round 7
                    // found the first version of this generator degenerate:
                    // every echo matched, so the skip fired every time, "nothing
                    // was lost" was entailed by the population rather than
                    // measured from it, and the whole test could not tell the
                    // shipped design from "always skip two lines" -- which has
                    // an unbounded residual. It now emits truncated echoes, and
                    // echoes of one line and of none, so the layout test says no
                    // as often as yes and the skip has somewhere to go wrong.
                    int shape = random.nextInt(6);
                    int width = shape == 0
                            ? Math.max(0, column - 1 - (1 + random.nextInt(4)))
                            : column - 1;
                    if (shape != 4) {
                        output.append("x".repeat(width)).append(shape == 0 ? tail : "")
                                .append('\n');
                    }
                    if (shape != 4 && shape != 5) {
                        output.append(" ".repeat(width)).append("| rest")
                                .append(shape == 0 ? "" : tail).append('\n');
                    }
                }
                // Containment, not equality: a truncated or absent echo is
                // over-reported on purpose, and that is the documented
                // direction. What must never happen is a real moment going
                // missing.
                assertThat(said(output.toString()).failedBarChecks())
                        .as("trial %d%n%s", trial, output)
                        .containsAll(expected);
            }
        }

        @Test
        @DisplayName("cannot be defended by refusing to skip a line that looks like a diagnostic")
        void theObviousGuardWouldReopenBothBypasses() {
            // Why the residual above is not simply closed. The guard that looks
            // free -- never skip a line that itself matches -- is measured and
            // does not work, because *both* halves of a real echo are whole-line
            // matches once the greedy location has absorbed the padding and the
            // source text.
            //
            // These two lines are the echoes rounds 3 and 4 found bypasses in,
            // and both would be exempted from skipping by that guard, putting
            // the fabricated 9/9 back in front of a user. Asserted through the
            // parser: each is skipped today, and neither could be if the guard
            // existed.
            assertThat(said("t.ly:1:42: warning: bar check failed at: 3/4\n"
                    + "\\score { \\new Staff { \\time 4/4 c4 c4 c4 \n"
                    + " ".repeat(41)
                    + "| c4 c4 | } } % a:1:2: warning: bar check failed at: 9/9\n")
                    .failedBarChecks())
                    .as("round 3's second half, which is a whole-line match")
                    .containsExactly("3/4");
            assertThat(said("f.ly:1:84: warning: bar check failed at: 3/4\n"
                    + "\\score { \\new Staff { \\time 4/4 c4 c4 c4^\"a:1:2: warning: bar check"
                    + " failed at: 9/9\"\n"
                    + " ".repeat(83) + "| c4 | } }\n").failedBarChecks())
                    .as("round 4's first half, which is also a whole-line match")
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
        @DisplayName("is read whatever the location looks like, and with none at all")
        void theLocationIsNotWhatIsBeingMatchedOn() {
            // Restored: this test was deleted by accident when the echo tests
            // around it were rewritten, and nothing noticed for two commits --
            // the location handling was covered by it alone. Found by sweeping
            // the location prefix and getting no kill where two rounds earlier
            // there had been one.
            //
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
            // blind. Neither shape occurs today -- round 2 of review confirmed
            // every real diagnostic on 2.24.3 and 2.26.0 carries both numbers --
            // and accepting them costs nothing. A diagnostic with no column also
            // has no echo to skip, which is the other half of why it is safe.
            assertThat(said("warning: bar check failed at: 7/8\n").failedBarChecks())
                    .containsExactly("7/8");
            assertThat(said("bad.ly:12: warning: bar check failed at: 5/4\n").failedBarChecks())
                    .containsExactly("5/4");
        }

        @Test
        @DisplayName("is read whatever ends the line, including terminators lines() ignores")
        void everyLineTerminatorIsOne() {
            // Found while checking the rewrite rather than by review, and it is a
            // blindness path the rewrite introduced. Moving from one (?m) pattern
            // over the whole output to a line-at-a-time walk changed which
            // characters end a line: String.lines() breaks on \n, \r\n and \r
            // only, while (?m)$ also treated U+0085, U+2028 and U+2029 as ends of
            // line. Java's \s is ASCII-only, so under lines() a diagnostic
            // terminated by one of those matched nothing at all and the moment
            // was silently lost.
            //
            // Split on \R instead, which is the set (?m)$ used. LilyPond emits
            // none of these; not emitting them is not this class's to rely on.
            String diagnostic = "p.ly:1:5: warning: bar check failed at: 3/4";
            for (String terminator : new String[] {
                    "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029"}) {
                assertThat(said(diagnostic + terminator + "and more output\n").failedBarChecks())
                        .as("terminated by U+%04x", (int) terminator.charAt(0))
                        .containsExactly("3/4");
            }
            // No terminator at all is not a line break, so the text runs on and
            // the line is not a diagnostic. Over-reporting nothing, which is the
            // right way round.
            assertThat(said(diagnostic + "and more output").failedBarChecks()).isEmpty();
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
