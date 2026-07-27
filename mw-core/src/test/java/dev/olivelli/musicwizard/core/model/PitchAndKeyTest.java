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

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PitchAndKeyTest {

    @Nested
    @DisplayName("pitch spelling")
    class Spelling {

        @Test
        @DisplayName("middle C is MIDI 60")
        void middleC() {
            PitchSpelling c4 = new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4);
            assertThat(c4.midiPitch()).isEqualTo(60);
            assertThat(c4.displayName()).isEqualTo("C4");
        }

        @Test
        @DisplayName("A440 is MIDI 69")
        void concertA() {
            assertThat(new PitchSpelling(NoteLetter.A, Accidental.NATURAL, 4).midiPitch())
                    .isEqualTo(69);
        }

        @Test
        @DisplayName("enharmonics sound the same but occupy different staff positions")
        void enharmonicsDifferOnTheStaff() {
            PitchSpelling cSharp = new PitchSpelling(NoteLetter.C, Accidental.SHARP, 4);
            PitchSpelling dFlat = new PitchSpelling(NoteLetter.D, Accidental.FLAT, 4);

            assertThat(cSharp.midiPitch()).isEqualTo(dFlat.midiPitch()).isEqualTo(61);
            // The whole reason spelling is modelled separately from MIDI pitch:
            // these two print on different lines.
            assertThat(cSharp.diatonicPosition()).isNotEqualTo(dFlat.diatonicPosition());
        }

        @ParameterizedTest(name = "MIDI {0} spells as {1} preferring sharps")
        @CsvSource({"60, C4", "61, C#4", "62, D4", "63, D#4", "66, F#4", "70, A#4", "71, B4"})
        void defaultSharpSpelling(int midi, String expected) {
            assertThat(PitchSpelling.ofMidiPitchSharp(midi).displayName()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "MIDI {0} spells as {1} preferring flats")
        @CsvSource({"61, Db4", "63, Eb4", "66, Gb4", "68, Ab4", "70, Bb4"})
        void defaultFlatSpelling(int midi, String expected) {
            assertThat(PitchSpelling.ofMidiPitchFlat(midi).displayName()).isEqualTo(expected);
        }

        @Test
        @DisplayName("default spellings round-trip back to their MIDI pitch")
        void defaultSpellingRoundTrips() {
            for (int midi = 12; midi <= 120; midi++) {
                assertThat(PitchSpelling.ofMidiPitchSharp(midi).midiPitch()).isEqualTo(midi);
                assertThat(PitchSpelling.ofMidiPitchFlat(midi).midiPitch()).isEqualTo(midi);
            }
        }
    }

    @Nested
    @DisplayName("key signatures")
    class KeySignatures {

        private Key major(NoteLetter letter, Accidental accidental) {
            return new Key(new PitchSpelling(letter, accidental, 4), Mode.MAJOR, 0, 1,
                    Confidence.CERTAIN);
        }

        private Key minor(NoteLetter letter, Accidental accidental) {
            return new Key(new PitchSpelling(letter, accidental, 4), Mode.MINOR, 0, 1,
                    Confidence.CERTAIN);
        }

        @Test
        @DisplayName("major keys sit at the expected place on the circle of fifths")
        void majorKeys() {
            assertThat(major(NoteLetter.C, Accidental.NATURAL).keySignatureAccidentals()).isZero();
            assertThat(major(NoteLetter.G, Accidental.NATURAL).keySignatureAccidentals()).isEqualTo(1);
            assertThat(major(NoteLetter.D, Accidental.NATURAL).keySignatureAccidentals()).isEqualTo(2);
            assertThat(major(NoteLetter.F, Accidental.NATURAL).keySignatureAccidentals()).isEqualTo(-1);
            assertThat(major(NoteLetter.B, Accidental.FLAT).keySignatureAccidentals()).isEqualTo(-2);
            assertThat(major(NoteLetter.E, Accidental.FLAT).keySignatureAccidentals()).isEqualTo(-3);
            assertThat(major(NoteLetter.F, Accidental.SHARP).keySignatureAccidentals()).isEqualTo(6);
        }

        @Test
        @DisplayName("a minor key shares the signature of its relative major")
        void relativeMinorsShareSignatures() {
            assertThat(minor(NoteLetter.A, Accidental.NATURAL).keySignatureAccidentals())
                    .isEqualTo(major(NoteLetter.C, Accidental.NATURAL).keySignatureAccidentals());
            assertThat(minor(NoteLetter.E, Accidental.NATURAL).keySignatureAccidentals())
                    .isEqualTo(major(NoteLetter.G, Accidental.NATURAL).keySignatureAccidentals());
            assertThat(minor(NoteLetter.D, Accidental.NATURAL).keySignatureAccidentals())
                    .isEqualTo(major(NoteLetter.F, Accidental.NATURAL).keySignatureAccidentals());
        }

        @Test
        @DisplayName("flat keys are identified as such")
        void identifiesFlatKeys() {
            assertThat(major(NoteLetter.B, Accidental.FLAT).isFlatKey()).isTrue();
            assertThat(major(NoteLetter.D, Accidental.NATURAL).isFlatKey()).isFalse();
        }
    }

    @Nested
    @DisplayName("chords")
    class Chords {

        private PitchSpelling root(NoteLetter letter, Accidental accidental) {
            return new PitchSpelling(letter, accidental, 4);
        }

        @Test
        @DisplayName("prints conventional chart symbols")
        void printsSymbols() {
            assertThat(Chord.ofSeconds(root(NoteLetter.C, Accidental.NATURAL),
                    ChordQuality.MAJOR, 0, 1, Confidence.CERTAIN).symbol()).isEqualTo("C");
            assertThat(Chord.ofSeconds(root(NoteLetter.A, Accidental.NATURAL),
                    ChordQuality.MINOR_SEVENTH, 0, 1, Confidence.CERTAIN).symbol()).isEqualTo("Am7");
            assertThat(Chord.ofSeconds(root(NoteLetter.B, Accidental.FLAT),
                    ChordQuality.DOMINANT_SEVENTH, 0, 1, Confidence.CERTAIN).symbol()).isEqualTo("Bb7");
        }

        @Test
        @DisplayName("prints a slash chord when the bass is not the root")
        void printsSlashChords() {
            Chord cOverE = Chord.ofSeconds(root(NoteLetter.C, Accidental.NATURAL),
                            ChordQuality.MAJOR, 0, 1, Confidence.CERTAIN)
                    .withBass(new PitchSpelling(NoteLetter.E, Accidental.NATURAL, 3));

            assertThat(cOverE.isSlashChord()).isTrue();
            assertThat(cOverE.symbol()).isEqualTo("C/E");
        }

        @Test
        @DisplayName("a bass equal to the root is not a slash chord")
        void rootPositionIsNotASlashChord() {
            Chord c = Chord.ofSeconds(root(NoteLetter.C, Accidental.NATURAL),
                            ChordQuality.MAJOR, 0, 1, Confidence.CERTAIN)
                    .withBass(new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 2));

            assertThat(c.isSlashChord()).isFalse();
            assertThat(c.symbol()).isEqualTo("C");
        }

        @Test
        @DisplayName("reports the pitch classes it sounds")
        void reportsPitchClasses() {
            Chord cMajor = Chord.ofSeconds(root(NoteLetter.C, Accidental.NATURAL),
                    ChordQuality.MAJOR, 0, 1, Confidence.CERTAIN);
            assertThat(cMajor.pitchClasses()).containsExactly(0, 4, 7);

            Chord aMinor = Chord.ofSeconds(root(NoteLetter.A, Accidental.NATURAL),
                    ChordQuality.MINOR, 0, 1, Confidence.CERTAIN);
            assertThat(aMinor.pitchClasses()).containsExactly(9, 0, 4);
        }
    }

    @Nested
    @DisplayName("confidence")
    class Confidences {

        @Test
        @DisplayName("combines multiplicatively so derived values are never more certain")
        void combinesMultiplicatively() {
            Confidence combined = Confidence.of(0.8).and(Confidence.of(0.5));

            assertThat(combined.value()).isEqualTo(0.4);
            assertThat(combined.compareTo(Confidence.of(0.8))).isNegative();
        }

        @Test
        @DisplayName("clamps rather than rejecting out-of-range input")
        void clamps() {
            assertThat(Confidence.clamped(1.7)).isEqualTo(Confidence.CERTAIN);
            assertThat(Confidence.clamped(-3.0)).isEqualTo(Confidence.UNKNOWN);
            assertThat(Confidence.clamped(Double.NaN)).isEqualTo(Confidence.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("lyric words")
    class LyricWords {

        @ParameterizedTest(name = "\"{0}\" counts as {1} syllable(s)")
        @CsvSource({"love, 1", "baby, 2", "hello, 2", "beautiful, 3", "the, 1", "sky, 1", "away, 2"})
        void estimatesSyllables(String text, int expected) {
            LyricWord word = new LyricWord(text, 0, 0.5, Optional.empty(), Confidence.CERTAIN);
            assertThat(word.syllableEstimate()).isEqualTo(expected);
        }

        @Test
        @DisplayName("always reports at least one syllable")
        void neverReportsZero() {
            LyricWord word = new LyricWord("shh", 0, 0.5, Optional.empty(), Confidence.CERTAIN);
            assertThat(word.syllableEstimate()).isGreaterThanOrEqualTo(1);
        }
    }
}
