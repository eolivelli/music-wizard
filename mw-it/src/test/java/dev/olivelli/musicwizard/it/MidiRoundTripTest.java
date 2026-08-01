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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.MidiExport;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier-3 loop closure over the symbolic path: {@code Score -> MIDI -> Score}.
 *
 * <p>The cheapest real test in the project. It needs no audio, no model and no
 * external binary, so it runs in the ordinary {@code mvn verify} rather than
 * behind {@code -Pintegration} — and it exercises the one thing neither
 * {@code MidiExportTest} nor {@code MidiImportTest} can, which is that the two
 * halves agree. Each of those checks its own side against the format; only this
 * checks that a file one wrote is the file the other expected.
 *
 * <p>It lives here rather than in {@code mw-notation} because the exporter and
 * the importer are in different modules and neither may depend on the other:
 * the notation layer must not see the audio and DSP stack {@code mw-transcribe}
 * is built on, and {@code mw-transcribe} has no business emitting notation.
 * {@code mw-it} is the module that already depends on both.
 *
 * <p><b>Tempo does not round-trip exactly, and that is the format.</b> A tempo
 * event holds whole microseconds per quarter note, so 140 BPM is stored as
 * 428571 and reads back as 140.00014. {@link MidiFixtures#storedTempo} computes
 * what a tempo becomes, and it is used here rather than a widened tolerance —
 * the tolerance would have to be about one part in ten thousand, which would
 * hide a real error in every other assertion it touched.
 */
class MidiRoundTripTest {

    /**
     * How far a beat position may move on the way out and back.
     *
     * <p>Zero, in effect. The export writes 768 ticks to the quarter and the
     * import divides by the same number, so every position the notation layer
     * can produce survives the trip bit for bit; this exists only so that a
     * comparison of doubles reads as an approximation rather than as a claim
     * about IEEE rounding that would be true by accident.
     */
    private static final double BEAT_TOLERANCE = 1e-9;

    private static Note note(double onsetBeat, double beats, int midiPitch) {
        // Seconds that a 120 BPM reading would give. Deliberately not what the
        // export reads: if it ever read them, this test would still pass at 120
        // and fail at every other tempo.
        return Note.ofSeconds(onsetBeat / 2, beats / 2, midiPitch, Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats);
    }

    /** A score with everything MIDI can carry, and one thing it cannot. */
    private static Score fullScore() {
        NoteTrack voice = new NoteTrack(PartRole.OTHER, "Voice", List.of(
                // A spelling, which MIDI has no event for: the round trip must
                // lose it rather than invent one.
                note(0, 1, 60).spelledAs(PitchSpelling.parse("C4")),
                note(1, 0.5, 62), note(1.5, 0.5, 64),
                note(2, 2, 65), note(4, 4, 67)), Confidence.CERTAIN);
        NoteTrack bass = new NoteTrack(PartRole.BASS, "Bass", List.of(
                note(0, 2, 36), note(2, 2, 43), note(4, 4, 36)), Confidence.CERTAIN);
        NoteTrack drums = new NoteTrack(PartRole.DRUMS, "Drums", List.of(
                note(0, 0.5, 36), note(1, 0.5, 38), note(2, 0.5, 36),
                note(3, 0.5, 38)), Confidence.CERTAIN);

        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4)
                .withTrack(voice)
                .withTrack(bass)
                .withTrack(drums)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("Eb3"), Mode.MAJOR,
                        0, 4, Confidence.CERTAIN)))
                .withMetadata("Loop Closure", null);
    }

    private static Score roundTrip(Score score, Path directory, String name) {
        Path file = MidiExport.write(score, directory.resolve(name + ".mid"));
        return new MidiTranscriber().transcribe(file);
    }

    @Test
    @DisplayName("every note comes back at the beat and for the length it was written at")
    void notesSurvive(@TempDir Path directory) {
        Score original = fullScore();
        Score reimported = roundTrip(original, directory, "notes");

        assertThat(reimported.tracks()).hasSameSizeAs(original.tracks());
        for (int i = 0; i < original.tracks().size(); i++) {
            NoteTrack before = original.tracks().get(i);
            NoteTrack after = reimported.tracks().get(i);
            assertThat(after.name()).isEqualTo(before.name());
            assertThat(after.notes()).as("%s", before.name()).hasSameSizeAs(before.notes());
            for (int n = 0; n < before.notes().size(); n++) {
                Note was = before.notes().get(n);
                Note is = after.notes().get(n);
                assertThat(is.midiPitch()).as("%s note %d pitch", before.name(), n)
                        .isEqualTo(was.midiPitch());
                assertThat(is.velocity()).as("%s note %d velocity", before.name(), n)
                        .isEqualTo(was.velocity());
                assertThat(is.onsetBeat()).as("%s note %d onset", before.name(), n)
                        .hasValueSatisfying(beat -> assertThat(beat)
                                .isCloseTo(was.onsetBeat().orElseThrow(),
                                        within(BEAT_TOLERANCE)));
                assertThat(is.durationBeats()).as("%s note %d length", before.name(), n)
                        .hasValueSatisfying(beats -> assertThat(beats)
                                .isCloseTo(was.durationBeats().orElseThrow(),
                                        within(BEAT_TOLERANCE)));
            }
        }
    }

    @Test
    @DisplayName("a triplet survives the trip, because the resolution divides by three")
    void tripletsSurvive(@TempDir Path directory) {
        NoteTrack voice = new NoteTrack(PartRole.OTHER, "Voice", List.of(
                note(0, 1.0 / 3, 60), note(1.0 / 3, 1.0 / 3, 62), note(2.0 / 3, 1.0 / 3, 64),
                note(1, 1.0 / 6, 65), note(1 + 1.0 / 6, 5.0 / 6, 67),
                note(2, 2, 69)), Confidence.CERTAIN);
        Score original = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 2)
                .withTrack(voice);

        List<Note> after = roundTrip(original, directory, "triplets").tracks()
                .getFirst().notes();
        assertThat(after).hasSize(6);
        for (int i = 0; i < after.size(); i++) {
            // A third of a beat is not a representable double, so this is the
            // case the tick resolution was chosen for. Anything short of exact
            // here means a quantized triplet comes back off the grid and the
            // next stage has to decide what was meant.
            assertThat(after.get(i).onsetBeat()).hasValueSatisfying(beat ->
                    assertThat(beat).isNotNegative());
            assertThat(after.get(i).onsetBeat().orElseThrow())
                    .isCloseTo(voice.notes().get(i).onsetBeat().orElseThrow(),
                            within(BEAT_TOLERANCE));
            assertThat(after.get(i).durationBeats().orElseThrow())
                    .isCloseTo(voice.notes().get(i).durationBeats().orElseThrow(),
                            within(BEAT_TOLERANCE));
        }
    }

    @Test
    @DisplayName("the tempo comes back as the file can hold it, not as it was asked for")
    void tempoSurvivesAsFarAsTheFormatAllows(@TempDir Path directory) {
        for (double bpm : new double[] {60, 100, 120, 150}) {
            Score original = Score.empty(TempoMap.constant(bpm, TimeSignature.FOUR_FOUR), 4)
                    .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                            List.of(note(0, 4, 60)), Confidence.CERTAIN));
            Score reimported = roundTrip(original, directory, "tempo-" + (int) bpm);
            // These four are exact: 60,000,000 divides by each of them evenly.
            assertThat(MidiFixtures.storedTempo(bpm)).isEqualTo(bpm);
            assertThat(reimported.tempoMap().initialTempo()).isEqualTo(bpm);
        }

        // And one that is not. 140 BPM is 428571.43 microseconds a quarter; the
        // file holds 428571 and the score comes back at 140.00014 BPM. Asserting
        // the stored value rather than widening a tolerance is the point: a
        // tolerance loose enough to swallow this would swallow a real error in
        // every tempo above.
        Score odd = Score.empty(TempoMap.constant(140, TimeSignature.FOUR_FOUR), 4)
                .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                        List.of(note(0, 4, 60)), Confidence.CERTAIN));
        Score reimported = roundTrip(odd, directory, "tempo-140");
        assertThat(MidiFixtures.storedTempo(140)).isNotEqualTo(140.0);
        assertThat(reimported.tempoMap().initialTempo())
                .isEqualTo(MidiFixtures.storedTempo(140));
    }

    @Test
    @DisplayName("a tempo change comes back on the beat it was written on")
    void tempoChangesSurvive(@TempDir Path directory) {
        TempoMap tempoMap = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120),
                        new TempoMap.TempoSegment(4, 2, 60)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        Score original = Score.empty(tempoMap, 6)
                .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                        List.of(note(0, 4, 60), note(4, 4, 62)), Confidence.CERTAIN));

        TempoMap after = roundTrip(original, directory, "tempo-change").tempoMap();
        assertThat(after.segments()).hasSize(2);
        assertThat(after.segments().get(0).startBeat()).isEqualTo(0);
        assertThat(after.segments().get(0).beatsPerMinute()).isEqualTo(120);
        assertThat(after.segments().get(1).startBeat()).isCloseTo(4, within(BEAT_TOLERANCE));
        assertThat(after.segments().get(1).beatsPerMinute()).isEqualTo(60);
    }

    @Test
    @DisplayName("the meter comes back, including a change part-way through")
    void meterSurvives(@TempDir Path directory) {
        Score original = fullScore();
        original = original.withTempoMap(
                original.tempoMap().withMeterChange(1, TimeSignature.THREE_FOUR));

        List<TempoMap.MeterChange> after =
                roundTrip(original, directory, "meter").tempoMap().meterChanges();
        assertThat(after).containsExactly(
                new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR));
    }

    @Test
    @DisplayName("a compound meter comes back as 6/8 rather than as three quarter beats")
    void compoundMeterSurvives(@TempDir Path directory) {
        Score original = Score.empty(TempoMap.constant(180, TimeSignature.SIX_EIGHT), 4)
                .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                        List.of(note(0, 3, 60)), Confidence.CERTAIN));

        assertThat(roundTrip(original, directory, "six-eight").tempoMap().initialTimeSignature())
                .isEqualTo(TimeSignature.SIX_EIGHT);
    }

    @Test
    @DisplayName("the key signature comes back, which the pitch spelling does not")
    void keysSurviveAndSpellingsDoNot(@TempDir Path directory) {
        Score original = fullScore();
        Score reimported = roundTrip(original, directory, "keys");

        assertThat(reimported.keys()).hasSize(1);
        Key key = reimported.keys().getFirst();
        // A signed count of sharps and a mode flag name exactly one key, so this
        // is a bijection rather than an inference -- unlike a spelling.
        assertThat(key.tonic().displayName()).isEqualTo("Eb4");
        assertThat(key.mode()).isEqualTo(Mode.MAJOR);
        assertThat(key.keySignatureAccidentals()).isEqualTo(-3);

        // And the loss, asserted rather than left implicit. MIDI 60 is C, B
        // sharp or D double flat and the file says only 60; a round trip that
        // came back with a spelling would have invented it.
        assertThat(original.tracks().getFirst().notes().getFirst().spelling())
                .isNotEmpty();
        assertThat(reimported.tracks().getFirst().notes().getFirst().spelling())
                .isEmpty();
    }

    @Test
    @DisplayName("the title comes back, and so do the part names")
    void namesSurvive(@TempDir Path directory) {
        Score reimported = roundTrip(fullScore(), directory, "names");

        assertThat(reimported.title()).contains("Loop Closure");
        assertThat(reimported.tracks()).extracting(NoteTrack::name)
                .containsExactly("Voice", "Bass", "Drums");
    }

    @Test
    @DisplayName("percussion and a named bass keep their roles; nothing else does")
    void rolesSurviveOnlyWhereTheFormatSaysSo(@TempDir Path directory) {
        Score original = fullScore();
        Score reimported = roundTrip(original, directory, "roles");

        assertThat(reimported.tracks()).extracting(NoteTrack::role)
                // "Voice" was LEAD_VOCAL-shaped and comes back OTHER: nothing in
                // a MIDI file says a part is the lead vocal. Percussion survives
                // on channel 10 and the bass on its name, which are the only two
                // signals the importer trusts. #96.
                .containsExactly(PartRole.OTHER, PartRole.BASS, PartRole.DRUMS);
    }

    @Test
    @DisplayName("a lead vocal part comes back as OTHER, so the loss is where it is claimed")
    void leadVocalRoleIsLost(@TempDir Path directory) {
        Score original = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4)
                .withTrack(new NoteTrack(PartRole.LEAD_VOCAL, "Lead",
                        List.of(note(0, 4, 60)), Confidence.CERTAIN));

        assertThat(roundTrip(original, directory, "vocal").tracks().getFirst().role())
                .isEqualTo(PartRole.OTHER);
    }

    @Test
    @DisplayName("a score with no key comes back with no key, rather than with C major")
    void anAbsentKeyStaysAbsent(@TempDir Path directory) {
        Score original = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4)
                .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                        List.of(note(0, 4, 60)), Confidence.CERTAIN));

        assertThat(roundTrip(original, directory, "no-key").keys()).isEmpty();
    }

    @Test
    @DisplayName("a score with no title comes back with none")
    void anAbsentTitleStaysAbsent(@TempDir Path directory) {
        Score original = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4)
                .withTrack(new NoteTrack(PartRole.OTHER, "Voice",
                        List.of(note(0, 4, 60)), Confidence.CERTAIN));

        assertThat(roundTrip(original, directory, "no-title").title()).isEqualTo(Optional.empty());
    }
}
