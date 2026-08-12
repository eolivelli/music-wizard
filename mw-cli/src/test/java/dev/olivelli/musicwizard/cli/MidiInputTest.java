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

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
        @DisplayName("init, analyze and render is a chain that ends in a chord chart")
        void theWholeChainProducesAChart() {
            // #114's "done when", as a chain rather than as three separately
            // tested halves. Until #115 landed this could not pass; afterwards it
            // passed only by composition -- MidiChordChartIT drives the
            // transcriber and the emitter directly, and every render test with
            // harmony in it plants the score by hand -- so nothing at any tier
            // ran the three commands in order over a real MIDI file.
            //
            // --no-pdf because the fast suite must not shell out to LilyPond.
            // The engraving half is MidiChordChartIT's; what this covers is that
            // the commands hand off to each other.
            Path source = fixture(MidiFixtures.fourChordSong(), "chain.mid");
            Path workspace = workspaceOf("chain");

            CliRunner.Result init = CliRunner.run(
                    "init", source.toString(), "-w", workspace.toString());
            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(init.exitCode()).as(init.all()).isZero();
            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/chords.txt")).exists();
            assertThat(workspace.resolve("out/chords.ly")).exists();
            // Not the empty-chart placeholder, and not asserted chord by chord:
            // which symbols come out is SymbolicChordEstimator's accuracy, which
            // is its own module's to pin.
            assertThat(render.out())
                    .contains("Wrote")
                    .doesNotContain("(no chords were found)")
                    .doesNotContain("Nothing could be written.");
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
        @DisplayName("estimated harmony is reported outside the declared block, not inside it")
        void estimatedHarmonyIsNotReportedAsDeclared() {
            // #115 landed while this change was in review, so a MIDI import now
            // has chords -- estimated from its notes, over a tempo and meter the
            // file declares. That is the mixture the summary's shape exists for,
            // and it is the first input that actually tests it: the chord count
            // must sit outside the heading that says where the figures came
            // from, because nothing about it came from the file.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            Score score = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(score.chords().isEmpty())
                    .as("the fixture must now have harmony for this to discriminate")
                    .isFalse();
            assertThat(analyze.out()).contains("Chords  " + score.chords().size() + " spans");
            assertThat(summaryBlock(analyze.out()).lines().takeWhile(line -> !line.isBlank()))
                    .as("an estimate quoted under the heading that says nothing was estimated")
                    .noneSatisfy(line -> assertThat(line).contains("Chords"));
        }

        @Test
        @DisplayName("a score with no harmony says so without naming a cause it cannot know")
        void absentHarmonyNamesNoCause() {
            // A drum-only file: SymbolicChordEstimator excludes percussion, so
            // there are notes and no harmony. Before #115 this branch named #115
            // as the missing stage; there is no single true cause to name now,
            // since an estimator ran and found nothing.
            Sequence drumsOnly = MidiFixtures.sequence()
                    .name("Kit")
                    .tempo(120)
                    .timeSignature(4, 4)
                    .part("Drum Kit", MidiFixtures.DRUM_CHANNEL)
                    .note(1, 0.5, 42).note(2, 0.5, 38).note(3, 0.5, 42)
                    .build();
            Path workspace = imported(drumsOnly, "kit");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.out())
                    .contains("Chords  none, though it holds 1 part(s)")
                    .doesNotContain("0 spans")
                    .as("naming a stage that has since landed")
                    .doesNotContain("#115");
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
                    .contains("Chords  none, and no parts either")
                    .as("naming a cause this command cannot know")
                    .doesNotContain("(#115)");
            assertThat(render.out())
                    .as("the two commands disagree about the same score")
                    .contains("no chord progression, and no parts either");
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
                    .keySignature(0, Mode.MAJOR)
                    .keySignatureAt(4, 0, Mode.MAJOR)
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
        @DisplayName("an enabled advisor is announced as advising nothing yet")
        void anEnabledAdvisorSaysWhatItDoes() {
            // The default is disabled, so this branch needs the workspace's own
            // config layer to reach it. "Advisor enabled" on its own announces a
            // layer that does not exist (#11) -- the defect #82 was filed for,
            // one command over -- and it matters slightly more now that the flag
            // is a cache key component, since invalidating an entry is currently
            // the only thing it does.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null, null, null,
                    null, new MusicWizardConfig.LlmConfig(
                            true, null, null, null, null, null, null, null)));

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.out())
                    .contains("Advisor    enabled, but advises nothing yet (#11)");
        }

        @Test
        @DisplayName("a key declared part-way through is not reported as the key at the start")
        void aLateKeySignatureIsNotReportedAsTheOpeningKey() {
            // Unlike the tempo and meter rows, this one has no origin guarantee
            // behind it: TempoMap's constructor forces its first segment and
            // first meter change to bar 0, and nothing imposes that on the key
            // list -- MidiTranscriber emits exactly the events the file carries.
            //
            // Every key fixture in the repo declares at beat 0, so this row's
            // overstatement survived five rounds behind the origin trap
            // CLAUDE.md names. This file declares its first key four bars in and
            // says nothing about the bars before it.
            Sequence lateKey = MidiFixtures.sequence()
                    .name("Late key")
                    .tempo(120)
                    .timeSignature(4, 4)
                    .keySignatureAt(16, 1, Mode.MINOR)
                    .part("Melody", 0)
                    .note(1, 1, 60).note(17, 1, 64)
                    .build();
            Path workspace = imported(lateKey, "latekey");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());
            Score score = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(score.keys()).singleElement().satisfies(key ->
                    assertThat(key.startBeat().orElseThrow())
                            .as("the fixture must not declare at the origin to discriminate")
                            .isEqualTo(16.0));
            assertThat(summaryBlock(analyze.out()))
                    .contains("Key     not declared at the start; E minor from beat 16.000");

            // The contradiction this row cannot reach: MidiTranscriber's own
            // stage line still says the piece opens in a key the file declares
            // four bars in. Same shape as the #118 tripwire above, same reason --
            // it is emitted by another module. Asserted rather than left for
            // someone to notice, and this assertion is expected to fail when
            // #127 lands, which is the point.
            assertThat(analyze.out())
                    .as("#127 is fixed; delete this assertion and the note beside it")
                    .contains("read 1 key signature(s), opening in E minor");
        }

        @Test
        @DisplayName("a key carrying no beat position is not quoted in beats")
        void aKeyWithoutABeatAxisIsNotLabelledInBeats() {
            // Not reachable through the CLI: MidiTranscriber sets both axes on
            // every key. It is the branch written for a score some other
            // producer wrote, and it was wrong in its own terms -- it printed
            // the seconds under the word "beat", which at this score's 120 BPM
            // is off by a factor of two against the axis it named.
            Score score = Score.empty(TempoMap.constant(120), 30.0)
                    .withKeys(List.of(new Key(
                            new PitchSpelling(NoteLetter.E, Accidental.NATURAL, 4), Mode.MINOR,
                            12.5, 30.0, Optional.empty(), Optional.empty(), Confidence.CERTAIN)));

            assertThat(AnalyzeCommand.summary(SourceKind.MIDI, score))
                    .contains("  Key     not declared at the start; E minor from 12.500s")
                    .noneSatisfy(line -> assertThat(line).contains("from beat"));
        }

        @Test
        @DisplayName("two spans of one key differing only in an unprinted field are not a change")
        void anUnprintedFieldIsNotAChange() {
            // PitchSpelling carries an octave; Key documents it as ignored and
            // displayName never prints it. Counting changes on the tonic made
            // two spans of E minor a change -- the exact defect countChanges was
            // introduced to remove, surviving in its own discriminator.
            Score score = Score.empty(TempoMap.constant(120), 30.0)
                    .withKeys(List.of(
                            eMinorAtOctave(3, 0.0, 15.0),
                            eMinorAtOctave(5, 15.0, 30.0)));

            assertThat(score.keys()).extracting(Key::displayName)
                    .as("the two spans must print alike for this to discriminate")
                    .containsExactly("E minor", "E minor");
            assertThat(AnalyzeCommand.summary(SourceKind.MIDI, score))
                    .contains("  Key     E minor")
                    .noneSatisfy(line -> assertThat(line).contains("changed"));
        }

        private Key eMinorAtOctave(int octave, double from, double to) {
            return new Key(new PitchSpelling(NoteLetter.E, Accidental.NATURAL, octave),
                    Mode.MINOR, from, to,
                    Optional.of(from), Optional.of(to), Confidence.CERTAIN);
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
        @DisplayName("a transcription request is answered, because render advises it blind")
        void transcriptionRequestIsAnswered() {
            // render's no-lyrics message offers --lyrics-language without
            // naming a source kind, since it cannot know one. A MIDI user who
            // follows that advice must hear why nothing happened.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run(
                    "analyze", workspace.toString(), "--lyrics-language", "en");

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err())
                    .contains("--lyrics-language alone asks for transcription")
                    .contains("MIDI");
        }

        @Test
        @DisplayName("--skip-separation is answered for what it is: doing nothing in this run")
        void skipSeparationIsNotAMidiSpecificExclusion() {
            // Round 3 found it described as an audio option, ignored in silence
            // by an audio run, and reported to a MIDI user in words implying an
            // audio run would honour it. On a MIDI workspace it changes nothing
            // whatever else is asked for, so that is what it says -- and it must not be listed
            // among the overrides the *file* supersedes, which is a different
            // reason entirely.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run(
                    "analyze", workspace.toString(), "--skip-separation");

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err())
                    .contains("skipping separation changes nothing in this run")
                    .contains("#8");
            assertThat(analyze.err())
                    .as("listed among the options the file's own declarations supersede")
                    .doesNotContain("--skip-separation has no effect on a MIDI workspace");
        }

        @Test
        @DisplayName("and is answered from the config file too, because it applies to no run")
        void skipSeparationIsAnsweredFromTheConfigLayer() {
            // The rule render arrived at, applied here: an override that merely
            // does not apply to *this* run may be passed over in silence, and one
            // that applies to no run may not. The tempo and meter overrides are
            // the first kind and stay silent from a config file; this is the
            // second.
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null,
                    new MusicWizardConfig.AnalysisConfig(90.0, "3/4", null, Boolean.TRUE),
                    null, null, null, null));

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err())
                    .contains("skipping separation changes nothing in this run");
            // And the other half of the rule, which must stay silent: a tempo in
            // a config file does apply, just not on this path.
            assertThat(analyze.err())
                    .as("a preference that merely does not apply here was announced")
                    .doesNotContain("no effect on a MIDI workspace");
        }

        @Test
        @DisplayName("are not mentioned when none was typed")
        void areSilentWhenAbsent() {
            Path workspace = imported(MidiFixtures.fourChordSong(), "four");

            CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString());

            // The exit code anchors it. "The warning did not appear" is equally
            // satisfied by the command dying before it would have, and this test
            // passed against an analyze that threw on its first line.
            assertThat(analyze.exitCode()).as(analyze.all()).isZero();
            assertThat(analyze.err()).doesNotContain("no effect on a MIDI workspace");
        }
    }
}
