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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The operations the notation and arrangement stages need from the model.
 *
 * <p>Each of these exists because the alternative is that every dependant
 * reimplements it — and the two that already had been reimplemented, the
 * bass octave shift and the LilyPond note name, are exactly the ones that
 * would have drifted apart first.
 */
class NotationSupportTest {

    @Nested
    @DisplayName("musical time is reachable without going back through seconds")
    class MusicalTimeAccessors {

        @Test
        @DisplayName("a quantized note reports its offset in beats")
        void noteOffsetBeat() {
            Note note = Note.ofSeconds(0.5, 0.5, 64, Confidence.CERTAIN).quantizedTo(1.0, 1.5);

            assertThat(note.offsetBeat()).contains(2.5);
        }

        @Test
        @DisplayName("an un-quantized note has no offset in beats")
        void unquantizedNoteHasNoOffsetBeat() {
            // Returning 0, or the seconds value, would let an un-quantized note
            // be laid out as though it had been quantized -- the one confusion
            // the optional musical fields exist to prevent.
            assertThat(Note.ofSeconds(0.5, 0.5, 64, Confidence.CERTAIN).offsetBeat()).isEmpty();
        }

        @Test
        @DisplayName("a quantized chord reports its duration in beats")
        void chordDurationBeats() {
            PitchSpelling c4 = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            Chord chord = Chord.ofSeconds(c4, ChordQuality.MAJOR, 0.0, 2.0, Confidence.CERTAIN)
                    .quantizedTo(4.0, 8.0);

            assertThat(chord.durationBeats()).contains(4.0);
            assertThat(Chord.ofSeconds(c4, ChordQuality.MAJOR, 0.0, 2.0, Confidence.CERTAIN)
                    .durationBeats()).isEmpty();
        }

        @Test
        @DisplayName("a progression is quantized only when every chord is")
        void progressionIsQuantized() {
            PitchSpelling c4 = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            Chord first = Chord.ofSeconds(c4, ChordQuality.MAJOR, 0.0, 2.0, Confidence.CERTAIN)
                    .quantizedTo(0.0, 4.0);
            Chord second = Chord.ofSeconds(c4, ChordQuality.MINOR, 2.0, 4.0, Confidence.CERTAIN);

            assertThat(new ChordProgression(List.of(first), Confidence.CERTAIN).isQuantized())
                    .isTrue();
            assertThat(new ChordProgression(List.of(first, second), Confidence.CERTAIN)
                    .isQuantized()).isFalse();
            assertThat(ChordProgression.empty().isQuantized()).isTrue();
        }

        @Test
        @DisplayName("a section reports its duration in beats once quantized")
        void sectionDurationBeats() {
            Section section = Section.unlabelled(0, 8, "A", Confidence.CERTAIN);

            assertThat(section.durationBeats()).isEmpty();
            assertThat(section.quantizedTo(0, 16).durationBeats()).contains(16.0);
            assertThat(section.quantizedTo(0, 16).isQuantized()).isTrue();
        }

        @Test
        @DisplayName("quantizing a key keeps everything else")
        void quantizingKeyKeepsTheRest() {
            PitchSpelling c4 = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            Key key = Key.ofSeconds(c4, Mode.MINOR, 1.0, 9.0, Confidence.of(0.4));

            Key quantized = key.quantizedTo(2.0, 18.0);

            assertThat(quantized.tonic()).isEqualTo(c4);
            assertThat(quantized.mode()).isEqualTo(Mode.MINOR);
            assertThat(quantized.startSeconds()).isEqualTo(1.0);
            assertThat(quantized.endSeconds()).isEqualTo(9.0);
            assertThat(quantized.confidence()).isEqualTo(Confidence.of(0.4));
            assertThat(quantized.startBeat()).contains(2.0);
        }

        @Test
        @DisplayName("quantizing rejects a span that ends before it starts")
        void quantizingRejectsInvertedSpans() {
            assertThatThrownBy(() -> Section.unlabelled(0, 8, null, Confidence.CERTAIN)
                    .quantizedTo(9, 4))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Key.ofSeconds(
                    new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4),
                    Mode.MAJOR, 0, 8, Confidence.CERTAIN).quantizedTo(9, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("note track queries")
    class TrackQueries {

        private NoteTrack track() {
            return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                    Note.ofSeconds(0.0, 1.0, 60, Confidence.CERTAIN),
                    Note.ofSeconds(2.0, 1.0, 67, Confidence.CERTAIN),
                    Note.ofSeconds(4.0, 1.0, 55, Confidence.CERTAIN)),
                    Confidence.CERTAIN);
        }

        @Test
        @DisplayName("notesBetween returns every note overlapping the span")
        void notesBetweenOverlaps() {
            // Same overlap rule as chordsBetween: a note that starts before the
            // span and is still sounding inside it belongs to the span, or a bar
            // would print without the note that is tied into it.
            assertThat(track().notesBetween(0.5, 2.5)).extracting(Note::midiPitch)
                    .containsExactly(60, 67);
            assertThat(track().notesBetween(1.0, 2.0)).isEmpty();
            assertThat(track().notesBetween(0.0, 10.0)).hasSize(3);
        }

        @Test
        @DisplayName("notesBetween excludes a note that ends exactly at the span start")
        void notesBetweenIsHalfOpen() {
            assertThat(track().notesBetween(1.0, 1.5)).isEmpty();
        }

        @Test
        @DisplayName("pitchRange reports a comparable pair")
        void pitchRangeIsAValue() {
            assertThat(track().pitchRange()).contains(new PitchRange(55, 67));
            assertThat(NoteTrack.empty(PartRole.BASS, "Bass").pitchRange()).isEmpty();
        }

        @Test
        @DisplayName("two equal ranges compare equal")
        void pitchRangeHasValueEquality() {
            // The concrete reason for the record: Optional<int[]> never satisfies
            // this, so no dependant could compare, cache or store a range.
            assertThat(new PitchRange(40, 64)).isEqualTo(new PitchRange(40, 64));
            assertThat(new PitchRange(40, 64).spanSemitones()).isEqualTo(24);
            assertThat(new PitchRange(40, 64).contains(40)).isTrue();
            assertThat(new PitchRange(40, 64).contains(64)).isTrue();
            assertThat(new PitchRange(40, 64).contains(65)).isFalse();
            assertThat(new PitchRange(40, 50).union(new PitchRange(45, 70)))
                    .isEqualTo(new PitchRange(40, 70));
        }

        @Test
        @DisplayName("a pitch range cannot be inverted or out of MIDI range")
        void pitchRangeIsValidated() {
            assertThatThrownBy(() -> new PitchRange(64, 40))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("below lowest");
            assertThatThrownBy(() -> new PitchRange(-1, 40))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PitchRange(40, 128))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("bass parts are written an octave above where they sound")
    class BassTransposition {

        @Test
        @DisplayName("only the bass role transposes")
        void onlyBassTransposes() {
            assertThat(PartRole.BASS.writtenTranspositionSemitones()).isEqualTo(12);
            for (PartRole role : PartRole.values()) {
                if (role != PartRole.BASS) {
                    assertThat(role.writtenTranspositionSemitones())
                            .as("%s is non-transposing", role)
                            .isZero();
                }
            }
        }

        @Test
        @DisplayName("the written pitch is an octave above the sounding one")
        void writtenPitchIsAnOctaveUp() {
            Note sounding = Note.ofSeconds(0, 1, 40, Confidence.CERTAIN);

            Note written = sounding.transposedBy(PartRole.BASS.writtenTranspositionSemitones());

            assertThat(written.midiPitch()).isEqualTo(52);
            // And the inverse is exactly a negation, so a round trip is lossless.
            assertThat(written.transposedBy(-PartRole.BASS.writtenTranspositionSemitones()))
                    .isEqualTo(sounding);
        }

        @Test
        @DisplayName("an octave shift keeps the spelling that was chosen")
        void octaveShiftKeepsSpelling() {
            // Ab2 sounding. Dropping the spelling would fall back to the
            // sharp-preferring default and print the bass line in G sharps
            // against a flat key signature.
            Note sounding = Note.ofSeconds(0, 1, 44, Confidence.CERTAIN)
                    .spelledAs(new PitchSpelling(NoteLetter.A, Accidental.FLAT, 2));

            Note written = sounding.transposedBy(12);

            assertThat(written.spelling()).contains(new PitchSpelling(NoteLetter.A, Accidental.FLAT, 3));
            assertThat(written.spelling().orElseThrow().midiPitch()).isEqualTo(written.midiPitch());
        }

        @Test
        @DisplayName("a shift of no semitones keeps the spelling")
        void zeroShiftKeepsSpelling() {
            Note note = Note.ofSeconds(0, 1, 61, Confidence.CERTAIN)
                    .spelledAs(new PitchSpelling(NoteLetter.D, Accidental.FLAT, 4));

            assertThat(note.transposedBy(0)).isEqualTo(note);
        }

        @Test
        @DisplayName("a non-octave shift still drops the spelling")
        void otherIntervalsDropSpelling() {
            // C#4 up a semitone is D in one key and C double sharp in another,
            // and a Note cannot tell which, so guessing would be worse than
            // leaving the decision to the speller.
            Note note = Note.ofSeconds(0, 1, 61, Confidence.CERTAIN)
                    .spelledAs(new PitchSpelling(NoteLetter.C, Accidental.SHARP, 4));

            assertThat(note.transposedBy(1).spelling()).isEmpty();
            assertThat(note.transposedBy(-7).spelling()).isEmpty();
        }

        @Test
        @DisplayName("an octave shift on an unspelled note stays unspelled")
        void octaveShiftOfUnspelledNote() {
            assertThat(Note.ofSeconds(0, 1, 40, Confidence.CERTAIN).transposedBy(12).spelling())
                    .isEmpty();
        }

        @Test
        @DisplayName("transposing out of MIDI range is still rejected")
        void rejectsOutOfRange() {
            assertThatThrownBy(() -> Note.ofSeconds(0, 1, 120, Confidence.CERTAIN).transposedBy(12))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of MIDI range");
        }
    }
}
