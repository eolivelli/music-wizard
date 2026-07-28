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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MIDI entry point, driven through the CLI a user actually types.
 *
 * <p>The behaviour under test is a routing decision, so nothing here mocks the
 * transcribers: what makes the difference visible is that the two produce
 * incompatible answers from the same bytes. Before #114 the audio path read
 * these files -- FFmpeg synthesised them -- and reported a 120 BPM, 18 second,
 * two-part file as a 60.3 BPM, 22 second recording with nine chord spans and no
 * parts at all. Every assertion below that names a number is naming one the
 * audio path got wrong.
 */
class MidiInputTest {

    @TempDir
    Path directory;

    private Path fixture(Sequence sequence, String name) {
        return MidiFixtures.write(sequence, directory.resolve(name));
    }

    private Path workspaceOf(String name) {
        return directory.resolve(name + ".mwz");
    }

    /**
     * The summary block, from its heading to the end.
     *
     * <p>Separated from the stage lines above it because the two are written by
     * different modules and make their claims independently. A test that asserts
     * over all of stdout is asserting about both, which is how one of these
     * passed for a reason that had nothing to do with the code under review.
     */
    private static String summaryBlock(String out) {
        int start = out.indexOf("From the file,");
        assertThat(start).as("no summary block in:\n%s", out).isNotNegative();
        return out.substring(start);
    }

    /** Imports a fixture and returns its workspace directory. */
    private Path imported(Sequence sequence, String name) {
        Path source = fixture(sequence, name + ".mid");
        CliRunner.Result init = CliRunner.run(
                "init", source.toString(), "-w", workspaceOf(name).toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        return workspaceOf(name);
    }

    @Nested
    @DisplayName("reading a MIDI file")
    class Reading {

        @Test
        @DisplayName("init names the file for what it is and analyze reads it symbolically")
        void importsAndAnalyses() {
            Path source = fixture(MidiFixtures.fourChordSong(), "four.mid");

            CliRunner.Result init = CliRunner.run(
                    "init", source.toString(), "-w", workspaceOf("four").toString());
            assertThat(init.exitCode()).as(init.all()).isZero();
            assertThat(init.out()).contains("four.mid (Standard MIDI File)");

            CliRunner.Result analyze = CliRunner.run("analyze", workspaceOf("four").toString());
            assertThat(analyze.exitCode()).as(analyze.all()).isZero();

            Score score = Workspace.open(workspaceOf("four")).readScore().orElseThrow();
            // 8 bars of 4/4 after a one-bar lead-in, at a stated 120: 36 quarter
            // beats, 18 seconds. The audio path answered 22.0 for this file,
            // because it was measuring FFmpeg's rendering of it and not the file.
            assertThat(score.durationSeconds()).isCloseTo(18.0, within(1e-9));
            assertThat(score.tracks()).extracting(NoteTrack::name)
                    .containsExactly("Piano", "Bass");
            assertThat(score.keys()).singleElement()
                    .satisfies(key -> assertThat(key.displayName()).isEqualTo("C major"));
        }

        @Test
        @DisplayName("what the file declares is reported as declared, and not as found")
        void reportsProvenanceRatherThanDiscovery() {
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.out())
                    .contains("From the file, or the MIDI default where it declares nothing:")
                    .contains("Tempo   120.0 BPM")
                    .contains("Meter   4/4")
                    .contains("Key     C major")
                    .contains("Parts   Piano, Bass");
            // The audio path's vocabulary, which is the thing that must not leak
            // across: every one of these words claims something was measured.
            assertThat(analyze.out())
                    .doesNotContain("found ")
                    .doesNotContain("tracking beats")
                    .doesNotContain("estimating chords")
                    .doesNotContain("beats/min");
        }

        @Test
        @DisplayName("absent harmony is reported as absent, not as nothing found")
        void absentHarmonyIsNotZeroSpans() {
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            // "0 spans" would read as the result of looking, and nothing looked.
            assertThat(analyze.out())
                    .contains("Chords  none: it holds 2 part(s), and naming the harmony"
                            + " a set of notes spells is not implemented yet (#115)")
                    .doesNotContain("0 spans");
        }

        @Test
        @DisplayName("gives the same reason render gives, for a file with no notes at all")
        void agreesWithRenderAboutWhyThereAreNoChords() {
            // A conductor-track-only file. analyze used to name #115 here
            // unconditionally -- false for a file that states no notes, since
            // #115 is about naming the harmony notes spell -- and render, three
            // commands later, said something different about the same score.
            // Both now read the explanation from one place.
            Sequence conductorOnly = MidiFixtures.sequence()
                    .name("Conductor")
                    .tempo(120)
                    .tempoAt(4, 60)
                    .timeSignature(4, 4)
                    .build();
            Path workspace = imported(conductorOnly, "conductor");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(analyze.out())
                    .contains("Parts   none")
                    .contains("there are no parts in it either")
                    .as("naming a stage that could not produce chords for a file with no notes")
                    .doesNotContain("(#115)");
            assertThat(render.out())
                    .as("the two commands disagree about the same score")
                    .contains("there are no parts in it either");
        }

        @Test
        @DisplayName("a changing tempo is reported as changing, not averaged into one figure")
        void aChangingTempoIsNotAveraged() {
            // 100 BPM to bar 3, then 140, and 4/4 into 6/8. Score.estimatedTempo
            // falls back to a duration-weighted average here -- a number the file
            // never contains -- and printing that under a heading claiming it was
            // read from the file is precisely the confidence this path exists to
            // avoid overstating.
            Path workspace = imported(MidiFixtures.tempoAndMeterChange(), "changing");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            Score score = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(score.estimatedTempo())
                    .as("the average differs from the opening tempo, so this fixture discriminates")
                    .isNotCloseTo(100.0, within(1.0));
            assertThat(analyze.out())
                    .contains("Tempo   100.0 BPM at the start, changed 1 time later")
                    .contains("Meter   4/4 at the start, changed 1 time later");
        }

        @Test
        @DisplayName("a tempo the file never declared is not reported as declared")
        void aDefaultedTempoIsNotPresentedAsAStatement() {
            // No tempo event and no time signature. MidiTranscriber substitutes
            // 120 and 4/4 -- what the specification says such a file is played at
            // -- and the TempoMap that comes back cannot be told apart from one
            // built for a file that declared them (#119). So the heading must not
            // claim it was read from the file, which the first version of this
            // command did.
            Sequence silentAboutTempo = MidiFixtures.sequence()
                    .part("Melody", 0)
                    .note(1, 1, 60).note(2, 1, 62)
                    .build();
            Path workspace = imported(silentAboutTempo, "silent");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.out())
                    .contains("From the file, or the MIDI default where it declares nothing:")
                    .contains("Tempo   120.0 BPM")
                    .contains("Meter   4/4")
                    .contains("Key     not declared by the file");
            assertThat(analyze.out())
                    .as("a default presented as something the file said")
                    .doesNotContain("Read from the file");
        }

        @Test
        @DisplayName("restating a value is not reported as changing it")
        void aRestatedValueIsNotAChange() {
            // Sequencer exports commonly rewrite the tempo and key signature at
            // every section boundary. MidiTranscriber drops a restated *meter*
            // and keeps restated tempi and keys (#118), so counting entries
            // rather than transitions reported a change in two of the three lines
            // and not the third -- three claims about one file that disagreed.
            Sequence restated = MidiFixtures.sequence()
                    .name("Restated")
                    .tempo(120).tempoAt(4, 120)
                    .timeSignature(4, 4).timeSignatureAt(4, 4, 4)
                    .keySignature(0, dev.olivelli.musicwizard.core.model.Mode.MAJOR)
                    .keySignatureAt(4, 0, dev.olivelli.musicwizard.core.model.Mode.MAJOR)
                    .part("Melody", 0)
                    .note(1, 1, 60).note(5, 1, 62)
                    .build();
            Path workspace = imported(restated, "restated");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            Score score = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(score.tempoMap().segments())
                    .as("the entries really are duplicated, so this fixture discriminates")
                    .hasSize(2);
            assertThat(score.keys()).hasSize(2);

            // Asserted over the summary block alone, not over the whole of
            // stdout. An earlier version of this test asserted the latter and
            // passed only because the importer's wording happens to be "changes"
            // where this command's is "changed" -- it read as "no line here
            // reports a change" and proved something much narrower.
            String block = summaryBlock(analyze.out());
            assertThat(block)
                    .as("a restatement reported as a change")
                    .doesNotContain("changed")
                    .contains("Tempo   120.0 BPM")
                    .contains("Meter   4/4")
                    .contains("Key     C major");

            // And the contradiction that remains, recorded rather than left to
            // be discovered: the importer's own stage line still counts entries.
            // This assertion is expected to fail when #118 lands, which is the
            // point -- it is the reminder to delete it.
            assertThat(analyze.out())
                    .as("#118 is fixed; delete this assertion and the note beside it")
                    .contains("the tempo changes 1 time(s) during the piece");
        }

        @Test
        @DisplayName("a tempo the format cannot hold exactly is not printed to a precision it lacks")
        void tempoIsPrintedToThePrecisionTheFormatHas() {
            // A MIDI tempo event carries whole microseconds per quarter note, so
            // 140 BPM is stored as 428571 and reads back as 140.00014.
            Sequence sequence = MidiFixtures.sequence()
                    .name("Not quite 140")
                    .tempo(140)
                    .timeSignature(4, 4)
                    .part("Melody", 0)
                    .note(1, 1, 60).note(2, 1, 62)
                    .build();
            Path workspace = imported(sequence, "precise");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            Score score = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(score.estimatedTempo())
                    .as("the file really does not hold exactly 140, so this test discriminates")
                    .isNotEqualTo(140.0)
                    .isEqualTo(MidiFixtures.storedTempo(140));
            // One decimal place, so the encoding's residue does not reach the
            // user as though it were something about the music.
            assertThat(analyze.out()).contains("Tempo   140.0 BPM");
            assertThat(analyze.out()).doesNotContain("140.00014");
        }
    }

    @Nested
    @DisplayName("a file the importer refuses")
    class Refused {

        /**
         * The reason and nothing else. Every case here reaches the user through
         * the same handler, so what is really being checked is that the CLI never
         * lets one of these out as a stack trace.
         */
        private void assertRefused(Path source, String name, String reason) {
            CliRunner.Result init = CliRunner.run(
                    "init", source.toString(), "-w", workspaceOf(name).toString());

            assertThat(init.exitCode()).as(init.all()).isNotZero();
            assertThat(init.err()).contains(reason);
            assertThat(init.all())
                    .as("a stack trace instead of a reason")
                    .doesNotContain("\tat ")
                    .doesNotContain("Exception");
            assertThat(workspaceOf(name))
                    .as("a workspace left behind for a file that was refused")
                    .doesNotExist();
        }

        @Test
        @DisplayName("a type 2 file is refused with the reason")
        void typeTwo() throws Exception {
            // The JDK will not write type 2, so the format word in the header --
            // bytes 8 and 9 -- is patched on a type 1 file instead.
            Sequence sequence = new Sequence(Sequence.PPQ, MidiFixtures.TICKS_PER_QUARTER);
            for (int t = 0; t < 2; t++) {
                Track track = sequence.createTrack();
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 80), 0));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MidiSystem.write(sequence, 1, bytes);
            byte[] patched = bytes.toByteArray();
            patched[8] = 0;
            patched[9] = 2;
            Path source = Files.write(directory.resolve("type2.mid"), patched);

            assertRefused(source, "type2", "type 2 MIDI files hold independent patterns");
        }

        @Test
        @DisplayName("an SMPTE-timed file is refused with the reason")
        void smpteTimed() throws Exception {
            Sequence sequence = new Sequence(Sequence.SMPTE_25, 40);
            Track track = sequence.createTrack();
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 80), 0));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 400));
            Path source = directory.resolve("smpte.mid");
            MidiSystem.write(sequence, 0, source.toFile());

            assertRefused(source, "smpte", "timed in SMPTE frames");
        }

        @Test
        @DisplayName("a file past the size cap is refused with the reason")
        void tooLarge() throws Exception {
            // Only the header has to be real: the size check runs before the
            // bytes are read, which is the whole point of a cap on bytes.
            byte[] oversized = new byte[4 * 1024 * 1024 + 1];
            byte[] real = Files.readAllBytes(
                    MidiFixtures.write(MidiFixtures.cMajorScale(), directory.resolve("real.mid")));
            System.arraycopy(real, 0, oversized, 0, real.length);
            Path source = Files.write(directory.resolve("huge.mid"), oversized);

            assertRefused(source, "huge", "refusing to read more than");
        }

        @Test
        @DisplayName("a file claiming to be MIDI and holding nothing of the sort is refused")
        void brokenHeader() throws Exception {
            byte[] junk = new byte[64];
            System.arraycopy("MThd".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0,
                    junk, 0, 4);
            Path source = Files.write(directory.resolve("broken.mid"), junk);

            assertRefused(source, "broken", "not a readable Standard MIDI File");
        }
    }

    @Nested
    @DisplayName("options that only the audio path acts on")
    class AudioOnlyOptions {

        @Test
        @DisplayName("are named rather than silently dropped")
        void areReported() {
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run(
                    "analyze", workspace.toString(), "--tempo", "90", "--time-signature", "3/4");

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err())
                    .contains("--tempo, --time-signature")
                    .contains("no effect on a MIDI workspace");
            // And genuinely not acted on: the file's own figures survive.
            assertThat(analyze.out())
                    .contains("Tempo   120.0 BPM")
                    .contains("Meter   4/4");
        }

        @Test
        @DisplayName("--skip-separation is answered for what it is: not implemented anywhere")
        void skipSeparationIsNotAMidiSpecificExclusion() {
            // Round 3 found it described as an audio option, ignored in silence
            // by an audio run, and reported to a MIDI user in words implying an
            // audio run would honour it. Nothing separates stems on either path
            // until #8, so that is what it says -- and it must not be listed
            // among the overrides the *file* supersedes, which is a different
            // reason entirely.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run(
                    "analyze", workspace.toString(), "--skip-separation");

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err())
                    .contains("--skip-separation has no effect yet on any input")
                    .contains("#8");
            assertThat(analyze.err())
                    .as("listed among the options the file's own declarations supersede")
                    .doesNotContain("--skip-separation has no effect on a MIDI workspace");
        }

        @Test
        @DisplayName("are not mentioned when none was typed")
        void areSilentWhenAbsent() {
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.err()).doesNotContain("no effect on a MIDI workspace");
        }
    }
}
