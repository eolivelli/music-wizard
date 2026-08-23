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

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render} says about the parts it cannot produce, which is #82.
 *
 * <p>It used to announce {@code voice, piano, bass, chords}, write only the
 * chord files and exit 0, so a user had no way to tell whether three parts had
 * failed, been skipped, or never been implemented. The rule the command now
 * follows is the one it already followed for a missing LilyPond binary: emit
 * what you can, name what you cannot and why, and fail only when there was
 * nothing at all to emit.
 *
 * <p>Everything here runs with {@code --no-pdf}. The fast suite must not shell
 * out to LilyPond, and none of these assertions is about engraving.
 */
class RenderPartsTest {

    @TempDir
    Path directory;

    /** I-V-vi-IV, so a score has harmony to render without any analysis running. */
    private static ChordProgression fourChords() {
        return new ChordProgression(List.of(
                chord("C", NoteLetter.C, ChordQuality.MAJOR, 0, 2),
                chord("G", NoteLetter.G, ChordQuality.MAJOR, 2, 4),
                chord("Am", NoteLetter.A, ChordQuality.MINOR, 4, 6),
                chord("F", NoteLetter.F, ChordQuality.MAJOR, 6, 8)),
                Confidence.of(0.8));
    }

    private static Chord chord(String unused, NoteLetter letter, ChordQuality quality,
                               double from, double to) {
        return Chord.ofSeconds(new PitchSpelling(letter, Accidental.NATURAL, 4), quality,
                from, to, Confidence.of(0.8));
    }

    /** A workspace holding audio, with whatever score the test needs. */
    private Path audioWorkspace(String name, ChordProgression chords) {
        return workspaceWith(name, chords, List.of());
    }

    /**
     * Imports a source and writes a score straight into the workspace.
     *
     * <p>The score is planted rather than analysed on purpose: what is under test
     * is what {@code render} does with a score, and running a transcriber to
     * obtain one would make these tests depend on the accuracy of a stage they
     * are not about.
     *
     * <p>The source is always audio, and never varied to change what
     * {@code render} says — the point being that what
     * {@code render} can produce is a property of the score, and it used to
     * re-sniff the file on disk to decide -- which failed when the file was gone
     * and lied when it had been replaced.
     */
    private Path workspaceWith(String name, ChordProgression chords, List<NoteTrack> tracks) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace workspace = Workspace.open(root);
        Score score = Score.empty(TempoMap.constant(120), 8.0).withChords(chords);
        for (NoteTrack track : tracks) {
            score = score.withTrack(track);
        }
        workspace.writeScore(score);
        return root;
    }

    /**
     * A workspace whose score holds a sung melody: two syllables, and a scoop
     * into each of them for the reduction to have something to take out.
     */
    private Path sungWorkspace(String name) {
        List<Note> notes = List.of(
                Note.ofSeconds(0.0, 0.25, 64, Confidence.CERTAIN),
                Note.ofSeconds(0.25, 0.25, 65, Confidence.CERTAIN),
                Note.ofSeconds(0.5, 0.5, 67, Confidence.CERTAIN),
                Note.ofSeconds(1.0, 0.25, 69, Confidence.CERTAIN),
                Note.ofSeconds(1.25, 0.75, 72, Confidence.CERTAIN));
        List<dev.olivelli.musicwizard.core.model.LyricWord> words = List.of(
                dev.olivelli.musicwizard.core.model.LyricWord.ofSeconds(
                        "one", 0.0, 1.0, Confidence.CERTAIN),
                dev.olivelli.musicwizard.core.model.LyricWord.ofSeconds(
                        "two", 1.0, 2.0, Confidence.CERTAIN));
        Path root = workspaceWith(name, fourChords(), List.of(
                new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN)));
        Workspace workspace = Workspace.open(root);
        workspace.writeScore(workspace.readScore().orElseThrow().withLyrics(
                new dev.olivelli.musicwizard.core.model.Lyrics(
                        List.of(new dev.olivelli.musicwizard.core.model.LyricLine(
                                words, Confidence.CERTAIN)), "en", Confidence.CERTAIN)));
        return root;
    }

    /** One part's worth of notes, so a score can hold notes and no harmony. */
    private static NoteTrack aPart(String name) {
        return new NoteTrack(PartRole.OTHER, name,
                List.of(new Note(0.0, 1.0, 60, 80, Optional.empty(),
                        Optional.of(0.0), Optional.of(1.0), Confidence.CERTAIN)),
                Confidence.CERTAIN);
    }

    @Nested
    @DisplayName("the parts line")
    class PartsLine {

        @Test
        @DisplayName("names the parts it will attempt, not the ones the epic plans")
        void defaultsToTheImplementedParts() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Parts      chords");
            // The four-name line #82 was filed about.
            assertThat(render.out()).doesNotContain("voice, piano, bass, chords");
        }

        @Test
        @DisplayName("a list of separators and nothing else is a usage error, not an empty run")
        void rejectsAListOfSeparators() {
            // picocli requires one *argument*, not one value: "," splits into
            // none. The guard for this was once removed on the strength of an
            // argument rather than a run, and the command then printed an empty
            // parts line and exited 1 -- the code it reserves for "the score had
            // nothing to engrave" -- with no message at all.
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", ",", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.USAGE);
            assertThat(render.err()).contains("--parts was given no part names");
        }

        @Test
        @DisplayName("an unknown part is a usage error naming the ones that exist")
        void rejectsAnUnknownPart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "drums", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.USAGE);
            assertThat(render.err())
                    .contains("unknown part 'drums'")
                    .contains("chords");
        }
    }

    @Nested
    @DisplayName("options nothing reads")
    class InertOptions {

        @Test
        @DisplayName("--paper is named rather than silently discarded")
        void areReported() {
            // The worst variant of #82 and the one it did not name: --parts voice
            // writes nothing and says why, while a discarded notation setting
            // writes every file and exits 0. --transpose was the worst of these
            // and is honoured now (#129); the paper size is #180.
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--transpose", "-2", "--paper", "letter", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err())
                    .contains("the paper size (#180)")
                    .contains("no effect yet")
                    .doesNotContain("the transposition");
        }

        @Test
        @DisplayName("every notation key but lilypondPath is covered, not only the two with flags")
        void coverTheKeysWithNoFlagOfTheirOwn() {
            // capo and the accidental preference have no command-line flag, so
            // the config file is the only way to ask for them -- and they
            // are as inert as the two that do. They were widened into the
            // warning with no test in either direction, which is how two of
            // the four newly warned keys went unexercised.
            Path workspace = audioWorkspace("song", fourChords());
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, null, 3,
                            MusicWizardConfig.AccidentalPreference.SHARPS, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err())
                    .contains("the capo (#181)")
                    .contains("the accidental preference (#181)");
        }

        @Test
        @DisplayName("asking for the default is not asking for anything")
        void theDefaultsAreNotReported() {
            // Every notation key has a built-in default, so the effective config
            // always carries a value: a non-null test would warn on every run in
            // the tool. --paper a4 is the default paper size, so honouring it
            // would produce the same chart and there is nothing to warn about.
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--paper", "a4", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err()).doesNotContain("no effect yet");
        }

        @Test
        @DisplayName("are not mentioned when neither was typed")
        void areSilentWhenAbsent() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            // The exit code as well, because "the warning did not appear" is
            // equally satisfied by the command dying before it would have -- a
            // shape this file has held twice.
            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err()).doesNotContain("no effect yet");
        }

        @Test
        @DisplayName("warn from the workspace config too, not only from the command line")
        void areReportedFromTheConfigLayerAsWell() {
            // A paper size is a persistent preference, so workspace.yaml is
            // exactly where it belongs -- and it reached nothing, in silence,
            // while the flag warned. analyze is right to pass over a config value
            // that merely does not apply to this run; these apply to no run.
            Path workspace = audioWorkspace("song", fourChords());
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, "letter", null, null, null, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err())
                    .contains("the paper size (#180)")
                    .contains("whether set on the command line or in the workspace config");
        }

        @Test
        @DisplayName("and the chart really is unchanged by them, which is why they warn")
        void reallyDoNothing() throws Exception {
            // The warning is only honest if the claim behind it is true. If a
            // paper size starts changing the chart, the warning is what to
            // delete.
            Path plain = audioWorkspace("plain", fourChords());
            Path lettered = audioWorkspace("lettered", fourChords());

            CliRunner.run("render", plain.toString(), "--no-pdf");
            CliRunner.run("render", lettered.toString(), "--paper", "letter", "--no-pdf");

            assertThat(java.nio.file.Files.readString(lettered.resolve("out/chords.ly")))
                    .as("#180 has landed; delete the warning and this test")
                    .isEqualTo(java.nio.file.Files.readString(plain.resolve("out/chords.ly")));
        }
    }

    @Nested
    @DisplayName("a part that cannot be produced")
    class Unavailable {

        @Test
        @DisplayName("is named with the reason when it was asked for explicitly")
        void namesTheReason() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "chords,lead,voice,bass,piano", "--no-pdf");

            // Two reasons of different kinds, which is the distinction worth
            // holding: bass and piano have no code behind them, while lead and
            // voice have code and no melody in this score to run it on.
            assertThat(render.out())
                    .contains("Not written:")
                    .contains("lead     this score holds no melody part;"
                            + " see --melody on analyze")
                    .contains("voice    this score holds no melody part;"
                            + " see --melody on analyze")
                    .contains("bass     bass transcription is not implemented yet (#8)")
                    .contains("piano    the piano reduction is not implemented yet (#10)");
            // Chords were produced, so this run is a partial success.
            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("chords.txt").contains("chords.ly");
        }

        @Test
        @DisplayName("makes the run fail only when nothing at all was written")
        void failsOnlyWhenNothingWasWritten() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "voice,piano", "--no-pdf");

            assertThat(render.exitCode())
                    .as("a run that produced nothing exited 0")
                    .isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out()).contains("Nothing could be written.");
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("the playable part")
    class Playable {

        @Test
        @DisplayName("is not written unless it is asked for")
        void optInOnly() {
            Path workspace = sungWorkspace("opt-in");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).doesNotContain("playable");
            assertThat(workspace.resolve("out/lead.ly")).exists();
            assertThat(workspace.resolve("out/lead-playable.ly")).doesNotExist();
        }

        @Test
        @DisplayName("is a second page beside the estimate's, not a replacement for it")
        void besideTheEstimate() {
            Path workspace = sungWorkspace("beside");

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "lead,playable", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(noteHeads(workspace.resolve("out/lead-playable.ly")))
                    .as("the reduced page prints fewer note-heads than the estimate's")
                    .isLessThan(noteHeads(workspace.resolve("out/lead.ly")));
            // The saved transcription is the estimate and stays the estimate:
            // every melody baseline scores that track.
            assertThat(Workspace.open(workspace).readScore().orElseThrow()
                    .track(PartRole.LEAD_VOCAL).orElseThrow().size()).isEqualTo(5);
        }

        @Test
        @DisplayName("needs a melody for the same reason the other two do")
        void needsAMelody() {
            Path workspace = audioWorkspace("silent", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "chords,playable", "--no-pdf");

            assertThat(render.out()).contains("playable this score holds no melody part;"
                    + " see --melody on analyze");
        }

        /** Note-heads, counted as the pitch letters the staff block writes. */
        private long noteHeads(Path lilyPond) {
            String staff;
            try {
                String source = java.nio.file.Files.readString(lilyPond);
                int from = source.indexOf("\\new Staff");
                staff = source.substring(from, source.indexOf("\\new Lyrics", from));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return java.util.regex.Pattern.compile("(?<![a-z\\\\])[a-g][',]*[0-9]")
                    .matcher(staff).results().count();
        }
    }

    @Nested
    @DisplayName("the analysis report")
    class Report {

        @Test
        @DisplayName("is not written unless it is asked for")
        void optInOnly() {
            Path workspace = audioWorkspace("no-report", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/report.html")).doesNotExist();
        }

        @Test
        @DisplayName("is one self-contained file, written without an engraver")
        void writesOneFile() throws java.io.IOException {
            Path workspace = audioWorkspace("report", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "report", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            Path page = workspace.resolve("out/report.html");
            assertThat(page).exists();
            String html = java.nio.file.Files.readString(page);
            assertThat(html).startsWith("<!DOCTYPE html>").endsWith("</html>\n");
            assertThat(html).contains("<style>", "<script>");
        }

        @Test
        @DisplayName("is written for a workspace where most stages never ran")
        void needsNothingInParticular() {
            // Every other part names a reason and writes nothing. This one is
            // the page that explains the absences, so refusing to write it for a
            // score that has them would withhold it from its own audience.
            Path workspace = audioWorkspace("thin", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "report", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/report.html")).exists();
        }

        @Test
        @DisplayName("shows what MW read, not what --transpose moved the chart to")
        void ignoresTheTransposition() throws java.io.IOException {
            Path workspace = audioWorkspace("shifted", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "chords,report", "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            // The chart moves; the page about the recording does not, and says so.
            assertThat(java.nio.file.Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Eb").doesNotContain("| C ");
            assertThat(java.nio.file.Files.readString(workspace.resolve("out/report.html")))
                    .contains("<td class=\"symbol\">C</td>")
                    .doesNotContain("<td class=\"symbol\">Eb</td>")
                    .contains("moves the engraved parts and not this page");
        }

        @Test
        @DisplayName("a shift that reaches nothing written is named rather than left in the header")
        void saysWhenTheTranspositionMovedNothing() {
            Path workspace = audioWorkspace("only-report", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "report", "--transpose", "5", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Transpose  +5 semitones");
            assertThat(render.err())
                    .contains("nothing that was written moves with a transposition");
        }

        @Test
        @DisplayName("a shift the chart honours is not reported as reaching nothing")
        void staysQuietWhenSomethingMoved() {
            Path workspace = audioWorkspace("chart-and-report", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "chords,report", "--transpose", "5", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.err())
                    .doesNotContain("nothing that was written moves with a transposition");
        }

        @Test
        @DisplayName("names the recording the workspace was made from")
        void carriesTheWorkspaceIdentity() throws java.io.IOException {
            Path workspace = audioWorkspace("named", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "report", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(java.nio.file.Files.readString(workspace.resolve("out/report.html")))
                    .contains("named.wav");
        }
    }


    @Nested
    @DisplayName("a score with no harmony")
    class NoHarmony {

        @Test
        @DisplayName("but with parts says what it holds, and names no cause")
        void notesWithoutHarmonyAreDescribedNotExplained() {
            Path workspace = workspaceWith("four", ChordProgression.empty(),
                    List.of(aPart("Piano"), aPart("Bass")));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("no chord progression, though it holds 2 part(s)")
                    .as("naming a cause the score cannot support")
                    .doesNotContain("(#115)");
            // The empty chart is not written at all: a file saying "(no chords
            // were found)" is a file a user has to open to learn nothing.
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }

        @Test
        @DisplayName("and no parts is an empty score, not a missing feature")
        void nothingAtAllIsAnEmptyScore() {
            Path workspace = audioWorkspace("quiet", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("no chord progression, and no parts either")
                    .doesNotContain("#115");
        }

        @Test
        @DisplayName("says nothing about where the score came from, because it cannot know")
        void doesNotGuessAtProvenance() {
            // A MIDI file holding only a conductor track -- a plain tempo-map
            // export -- imports to a score with no parts, and so takes the same
            // branch as an audio analysis that found nothing. A source-file
            // sniff was once replaced with a proxy, and the proxy
            // was wrong here, on a real file, with the source present and
            // untouched. So neither message may name a source kind at all.
            Path source = MidiFixtures.write(MidiFixtures.sequence()
                    .name("Conductor")
                    .tempo(120)
                    .tempoAt(4, 60)
                    .timeSignature(4, 4)
                    .build(), directory.resolve("conductor.mid"));
            Path root = directory.resolve("conductor.mwz");
            assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                    .isZero();
            assertThat(CliRunner.run("analyze", root.toString()).exitCode()).isZero();
            assertThat(Workspace.open(root).readScore().orElseThrow().tracks())
                    .as("the fixture must take the no-parts branch to discriminate")
                    .isEmpty();

            CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

            assertThat(render.out())
                    .contains("no chord progression, and no parts either")
                    // Every wording this sentence has had that reached past what
                    // it can know, pinned by name. Two of them named a source
                    // kind; the third claimed a mechanism that is false on the
                    // audio path. The bug each time was a specific wrong
                    // sentence, not a stray adjective.
                    .doesNotContain("the analysis that produced it found no harmony")
                    .doesNotContain("nothing for harmony to be derived from")
                    .doesNotContain("nothing in it to engrave")
                    .doesNotContain("not implemented yet (#115)")
                    .doesNotContain("recording")
                    .doesNotContain("audio")
                    .doesNotContain("MIDI file");
        }

        @Test
        @DisplayName("does not announce an output directory or an engraver")
        void announcesNothingItWillNotUse() {
            Path workspace = audioWorkspace("quiet", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run("render", workspace.toString());

            // Not --no-pdf: the engraver is looked up on this path in real use,
            // and announcing it before discovering there is nothing to engrave is
            // the same defect as the parts line.
            //
            // Anchored on what the command DID print, not on its exit code. The
            // first attempt at this asserted SOFTWARE -- and a render that throws
            // on its first line exits SOFTWARE too, because that is what the
            // exception handler returns, so the assertion was satisfied by the
            // very failure it was added to exclude. An absence needs a positive
            // companion, and here the companion has to be output rather than a
            // status the failure path shares.
            assertThat(render.out())
                    .as("the command did not reach the point where it would announce")
                    .contains("Parts      chords")
                    .contains("Not written:");
            assertThat(render.out())
                    .doesNotContain("Output ")
                    .doesNotContain("Engraver");
            // The directory itself is created by init, so what must be true is
            // that nothing was put in it.
            assertThat(workspace.resolve("out")).isEmptyDirectory();
        }
    }

    @Nested
    @DisplayName("a workspace whose recording is gone")
    class WithoutItsSource {

        @Test
        @DisplayName("still renders, because the score holds what an engraver needs")
        void rendersFromTheScoreAlone() throws Exception {
            Path workspace = audioWorkspace("song", fourChords());
            // A workspace duplicates the recording, so deleting it to reclaim
            // space -- or archiving one without it -- is ordinary. render used to
            // re-sniff this file to word one message, and refused the whole run
            // when it was missing.
            Path source = Workspace.open(workspace).sourceFile();
            java.nio.file.Files.delete(source);

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/chords.txt")).exists();
        }
    }

    @Nested
    @DisplayName("a score with harmony")
    class WithHarmony {

        @Test
        @DisplayName("is written, and the chart is echoed")
        void writesTheChart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/chords.txt")).exists();
            assertThat(workspace.resolve("out/chords.ly")).exists();
            assertThat(render.out()).contains("| C").contains("| G").contains("| Am");
        }

        @Test
        @DisplayName("is written once even when the part is named twice")
        void doesNotRepeatARequestedPart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "chords,chords", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out().split("chords\\.txt", -1))
                    .as("the chart was written twice")
                    .hasSize(2);
        }
    }
}
