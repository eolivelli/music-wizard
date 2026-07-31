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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render} says about a PDF the engraver wrote and then called wrong,
 * which is #156.
 *
 * <p>A failed bar check is a <em>warning</em> to LilyPond: it draws the short
 * bar, says {@code Success: compilation successfully completed}, exits zero and
 * leaves a real PDF behind. Measured on 2.26.0 before any of this was written, a
 * 4/4 bar holding three quarters gave {@code warning: bar check failed at: 3/4},
 * status 0 and 28833 bytes of PDF. So {@code succeeded()} is true, and the only
 * thing separating a right page from a wrong one is what LilyPond said.
 *
 * <p>Driven by a stand-in engraver rather than by LilyPond, for the reason the
 * whole fast suite exists: {@code mvn verify} stays offline and binary-free. That
 * costs nothing here, because what is under test is what this command does with
 * the renderer's answer, not how the answer was arrived at — and a stand-in can
 * produce the awkward cases (ten failures, the pre-2.25.6 spelling) that no
 * fixture would reliably give.
 *
 * <p><b>What this does not prove, and what changed.</b> {@code render} writes
 * only the chord chart today. When this was written {@code
 * ChordChart.toLilyPond} emitted no {@code |} bar checks at all, so real
 * LilyPond could not reach this branch through {@code mw render}; #160 gave the
 * chart a {@code \time} and a check per bar, and {@code
 * ChordChartEngravingIT.aShortBarIsCaught} engraves a short chart bar with real
 * LilyPond and reads the warning back. What is still not proved <em>here</em> is
 * the wiring end to end under a real engraver, which is deliberate: this suite
 * stays offline, and a stand-in is what lets it produce ten failures and the
 * pre-2.25.6 spelling.
 */
class RenderDiagnosticsTest {

    @TempDir
    Path directory;

    /**
     * A workspace holding a four-chord score and an engraver of our choosing.
     *
     * <p>The score is planted rather than analysed: what is under test is what
     * {@code render} does with the engraver's output, and running a transcriber
     * to obtain a score would make this depend on a stage it is not about.
     */
    private Path workspaceEngravedBy(String name, String engraverScript) throws Exception {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();

        Path engraver = directory.resolve(name + "-engraver");
        Files.writeString(engraver, engraverScript);
        Files.setPosixFilePermissions(engraver, PosixFilePermissions.fromString("rwxr-xr-x"));

        Workspace workspace = Workspace.open(root);
        workspace.writeScore(Score.empty(TempoMap.constant(120), 8.0).withChords(fourChords()));
        Workspace.Descriptor descriptor = workspace.readDescriptor();
        workspace.writeDescriptor(new Workspace.Descriptor(
                descriptor.schemaVersion(), descriptor.sourceFileName(), descriptor.sourceSha256(),
                descriptor.createdAt(), descriptor.title(), descriptor.artist(),
                new MusicWizardConfig(null, null,
                        new MusicWizardConfig.NotationConfig(
                                engraver.toString(), null, null, null, null),
                        null, null, null)));
        return root;
    }

    /** I-V-vi-IV, so there is harmony to engrave without any analysis running. */
    private static ChordProgression fourChords() {
        return new ChordProgression(List.of(
                chord(NoteLetter.C, ChordQuality.MAJOR, 0, 2),
                chord(NoteLetter.G, ChordQuality.MAJOR, 2, 4),
                chord(NoteLetter.A, ChordQuality.MINOR, 4, 6),
                chord(NoteLetter.F, ChordQuality.MAJOR, 6, 8)),
                Confidence.of(0.8));
    }

    private static Chord chord(NoteLetter letter, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(new PitchSpelling(letter, Accidental.NATURAL, 4), quality,
                from, to, Confidence.of(0.8));
    }

    /**
     * An engraver that writes the PDF, complains about the bars, and exits zero.
     *
     * @param complaints lines to print before declaring success
     */
    private static String engraverThatComplains(String... complaints) {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        for (String complaint : complaints) {
            script.append("echo \"").append(complaint).append("\"\n");
        }
        // Named exactly as LilyPond names it: the command runs it as
        // "--pdf -o chords chords.ly" with the output directory as its cwd.
        return script.append("""
                echo "Success: compilation successfully completed"
                : > chords.pdf
                exit 0
                """).toString();
    }

    @Test
    @DisplayName("names the bars that do not sum, beside the PDF it still wrote")
    void aFailedBarCheckIsReportedRatherThanPassedOverInSilence() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        Path workspace = workspaceEngravedBy("short-bar", engraverThatComplains(
                "chords.ly:5:17: warning: bar check failed at: 3/4"));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        // Zero, and the PDF listed: the .txt, .ly and .pdf were all produced,
        // and this command reserves non-zero for producing nothing at all. The
        // wrongness is in bars the user can see named, and a script that lost
        // all three files over a defect it cannot act on would be worse off
        // than one told plainly what is wrong.
        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(render.out()).contains("Wrote ", "chords.pdf");
        // The line #156 is about. Without it the run ends at "Wrote
        // .../chords.pdf" and says nothing more about a page whose music does
        // not match the transcription. Present tense on purpose: no real run
        // has produced one, for the reason the class javadoc gives.
        //
        // "check while" rather than "check": round 1 of review found that
        // contains("1 failed bar check") is satisfied by "1 failed bar checkS",
        // so the singular branch was pinned by nothing.
        assertThat(render.err())
                .contains("warning: LilyPond reported 1 failed bar check while")
                .contains("at 3/4.");
        // The file's name, not its path -- in *both* places the message names
        // it. Round 9 found `contains("chords.ly")` satisfied either way; round
        // 10 then found the assertion written for that keying on the first
        // interpolation only, so mutating the second survived. Two readers of
        // one value in a single statement, which is this project's recorded
        // failure mode inside the fix written for it.
        assertThat(render.err())
                .contains("engraving chords.ly,")
                .contains("report it with chords.ly attached")
                .doesNotContain("engraving " + workspace)
                .doesNotContain("with " + workspace);
        assertThat(render.err()).contains("does not add up to its time signature");
        // On stderr rather than stdout, because this command's last act is to
        // print the chart itself -- which is exactly the thing someone pipes to
        // a file, and the redirection must not take the warning with it.
        assertThat(render.out()).doesNotContain("failed bar check");
    }

    @Test
    @DisplayName("says it after the line saying the PDF was written, not before")
    void theWarningComesAfterTheFileItIsAbout() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The property the Emitted record exists for, and until round 1 of
        // review it was held by nothing: the two streams were captured into two
        // buffers, so moving the warning block back above the file list -- the
        // exact regression -- left every test green. A warning printed before
        // "Wrote" reads as the reason a file is missing rather than as a fact
        // about one that is there.
        Path workspace = workspaceEngravedBy("ordering", engraverThatComplains(
                "chords.ly:5:17: warning: bar check failed at: 3/4"));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        String transcript = render.transcript();
        // The last "Wrote " rather than the first mention of chords.pdf: the
        // claim is that the warning follows the whole file list, and keying on a
        // file name would also pass if a temp directory happened to be called
        // after one.
        int lastFileLine = transcript.lastIndexOf("Wrote ");
        int warning = transcript.indexOf("warning: LilyPond reported");
        assertThat(transcript).contains("chords.pdf");
        assertThat(lastFileLine).as("%s", transcript).isNotNegative();
        assertThat(warning).as("%s", transcript).isNotNegative();
        assertThat(warning).as("the warning must follow the files it is about%n%s", transcript)
                .isGreaterThan(lastFileLine);
        // And come before the chart this command prints last. That is the half
        // that proves the transcript is a real interleaving rather than stdout
        // followed by stderr: concatenating the two buffers would put the
        // warning after the chart, and round 2 of review found exactly that
        // mutant -- reorder the printing *and* degrade transcript() -- surviving.
        int chart = transcript.indexOf("Tempo  ");
        assertThat(chart).as("%s", transcript).isNotNegative();
        assertThat(warning).as("the warning must not be buried after the chart%n%s", transcript)
                .isLessThan(chart);
    }

    @Test
    @DisplayName("counts the bars in digits every machine reads the same way")
    void theCountIsNotLocalised() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // Round 1 of review: String.format without a Locale localises %d, so on
        // an ar_EG machine this sentence read "LilyPond reported ١٠ failed bar
        // checks" with every other word in English. The same class of defect as
        // LilyPondRenderer.speakEnglish, one process in, and the reason
        // StaffNotation's tempo mark already carries Locale.ROOT.
        String[] ten = new String[10];
        for (int bar = 0; bar < ten.length; bar++) {
            ten[bar] = "chords.ly:1:1: warning: bar check failed at: " + (bar + 1) + "/4";
        }
        Path workspace = workspaceEngravedBy("arabic-digits", engraverThatComplains(ten));

        Locale original = Locale.getDefault();
        CliRunner.Result render;
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            render = CliRunner.run("render", workspace.toString());
        } finally {
            Locale.setDefault(original);
        }

        assertThat(render.err()).contains("LilyPond reported 10 failed bar checks while");
    }

    @Test
    @DisplayName("recognises the wording of the LilyPond that CI installs, not only Homebrew's")
    void thePreTwentyFiveSixSpellingIsReadToo() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // #145: LilyPond renamed the message at 2.25.6. Ubuntu 24.04 packages
        // 2.24.3 and says "barcheck"; reading only the newer spelling would
        // leave every user on a distribution LilyPond exactly as uninformed as
        // before, and the tool would look fixed.
        Path workspace = workspaceEngravedBy("old-lilypond", engraverThatComplains(
                "chords.ly:5:17: warning: barcheck failed at: 7/8"));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(render.err()).contains("1 failed bar check while").contains("at 7/8.");
    }

    @Test
    @DisplayName("names several bars, and stops naming them before the line stops being read")
    void manyFailuresAreCountedInFullAndListedInPart() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // LilyPond 2.24 reports every failure in the part, so a score that goes
        // wrong early produces one per bar. The count stays exact; the list is
        // capped, because a warning long enough to scroll off the screen is a
        // warning nobody reads.
        String[] ten = new String[10];
        for (int bar = 0; bar < ten.length; bar++) {
            ten[bar] = "chords.ly:" + (bar + 1) + ":17: warning: bar check failed at: "
                    + (bar + 1) + "/4";
        }
        Path workspace = workspaceEngravedBy("many-short-bars", engraverThatComplains(ten));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(render.err())
                .contains("10 failed bar checks")
                .contains("at 1/4, 2/4, 3/4, 4/4, 5/4, 6/4, 7/4, 8/4, and 2 more.");
        assertThat(render.err()).doesNotContain("9/4");
    }

    @Test
    @DisplayName("names exactly eight bars without an \"and 0 more\"")
    void theCapDoesNotBiteAtTheBoundary() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The boundary the cap is written on, which round 7 found unvisited:
        // the tests either side of it use one moment and ten. Loosening the
        // comparison to >= is invisible except at exactly eight, where the user
        // would read "and 0 more".
        String[] eight = new String[8];
        for (int bar = 0; bar < eight.length; bar++) {
            eight[bar] = "chords.ly:1:1: warning: bar check failed at: " + (bar + 1) + "/4";
        }
        Path workspace = workspaceEngravedBy("exactly-eight", engraverThatComplains(eight));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        assertThat(render.err())
                .contains("at 1/4, 2/4, 3/4, 4/4, 5/4, 6/4, 7/4, 8/4.")
                .doesNotContain("more");
    }

    @Test
    @DisplayName("says nothing about bars when LilyPond did not complain about them")
    void aCleanEngravingIsNotWarnedAbout() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The control, and the half that decides whether the warning is worth
        // anything: a diagnostic that fires on correct output trains the user to
        // skip it. #136 is a tuplet-number placement complaint that LilyPond
        // words as a programming error, is reachable from ordinary 4/4 in about
        // one staff in eighty, and describes a page whose music is right.
        Path workspace = workspaceEngravedBy("clean", engraverThatComplains(
                "programming error: not enough space for tuplet number against beam",
                "continuing, cross fingers"));

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(render.out()).contains("chords.pdf");
        // "bar check" alone, which subsumes the plural: round 1 of review noted
        // the second clause could never fail independently.
        assertThat(render.err()).doesNotContain("bar check");
    }

    @Test
    @DisplayName("does not claim the bars are wrong when the engraver failed outright")
    void aFailedRunIsStillReportedAsAFailedRun() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // A non-zero engraver writes no PDF, so there is no page to be wrong
        // about. The pre-existing message is what should appear, unchanged, and
        // the new one must not join it -- "the PDF is wrong" and "there is no
        // PDF" are different things to tell a user.
        Path workspace = workspaceEngravedBy("broken", """
                #!/bin/sh
                echo "chords.ly:5:17: warning: bar check failed at: 3/4"
                echo "fatal error: failed files: \\"chords.ly\\""
                exit 1
                """);

        CliRunner.Result render = CliRunner.run("render", workspace.toString());

        assertThat(render.out()).contains("LilyPond could not engrave the chart:");
        assertThat(render.err()).doesNotContain("warning: LilyPond reported");
        // The sources survive the engraver, which is the posture a missing
        // binary already gets.
        assertThat(render.out()).contains("chords.txt").contains("chords.ly");
        assertThat(render.exitCode()).as(render.all()).isZero();
    }
}
