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

        @ParameterizedTest(name = "{0}{1}{2} is LilyPond {3}")
        @CsvSource({
            "C, NATURAL, 4, c",
            "C, SHARP, 4, cis",
            "B, FLAT, 3, bes",
            "F, DOUBLE_SHARP, 5, fisis",
            "E, DOUBLE_FLAT, 2, eeses"})
        void lilyPondNames(NoteLetter letter, Accidental accidental, int octave, String expected) {
            assertThat(new PitchSpelling(letter, accidental, octave).lilyPondName())
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}{1}{2} is LilyPond {3} in absolute octaves")
        @CsvSource({
            "C, NATURAL, 3, c",
            "C, NATURAL, 4, c'",
            "C, NATURAL, 6, c'''",
            "C, NATURAL, 2, 'c,'",
            "A, FLAT, 1, 'aes,,'"})
        void lilyPondAbsoluteNames(NoteLetter letter, Accidental accidental, int octave,
                                   String expected) {
            // LilyPond's unmarked octave is the one below middle C, so getting
            // this off by one puts the whole staff an octave out.
            assertThat(new PitchSpelling(letter, accidental, octave).lilyPondAbsoluteName())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("every spelling parses back from its display name")
        void parseInvertsDisplayName() {
            for (int octave = -1; octave <= 9; octave++) {
                for (NoteLetter letter : NoteLetter.values()) {
                    for (Accidental accidental : Accidental.values()) {
                        PitchSpelling original = new PitchSpelling(letter, accidental, octave);
                        assertThat(PitchSpelling.parse(original.displayName()))
                                .as("round trip of %s", original.displayName())
                                .isEqualTo(original);
                    }
                }
            }
        }

        @Test
        @DisplayName("parsing accepts a lower-case letter and surrounding space")
        void parseIsForgivingWhereItCanBe() {
            assertThat(PitchSpelling.parse("  bb3  "))
                    .isEqualTo(new PitchSpelling(NoteLetter.B, Accidental.FLAT, 3));
            assertThat(PitchSpelling.parse("f#4"))
                    .isEqualTo(new PitchSpelling(NoteLetter.F, Accidental.SHARP, 4));
        }

        @ParameterizedTest(name = "\"{0}\" is not a pitch")
        @CsvSource({"''", "H4", "C", "C#", "Cx4", "C4.5", "4", "Cb", "C--1", "C4x"})
        void parseRejectsRubbish(String text) {
            assertThatThrownBy(() -> PitchSpelling.parse(text))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parsing rejects an octave that is not an ASCII number")
        void parseRejectsNonAsciiDigits() {
            // Integer.parseInt accepts every Unicode decimal digit, so an
            // Arabic-Indic six would otherwise parse to an octave nobody typed.
            assertThatThrownBy(() -> PitchSpelling.parse("C\u0664"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PitchSpelling.parse("C\u06664"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parsing rejects an octave outside scientific pitch notation")
        void parseRejectsAbsurdOctaves() {
            // Unbounded, the octave overflows midiPitch silently and makes
            // lilyPondAbsoluteName allocate one octave mark per octave. parse is
            // where untrusted text arrives, so it is where that is stopped.
            assertThatThrownBy(() -> PitchSpelling.parse("C200000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("octave must be within");
            assertThatThrownBy(() -> PitchSpelling.parse("C-2"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PitchSpelling.parse("C10"))
                    .isInstanceOf(IllegalArgumentException.class);

            // The ends of the accepted range still parse.
            assertThat(PitchSpelling.parse("C-1").octave()).isEqualTo(-1);
            assertThat(PitchSpelling.parse("G9").midiPitch()).isEqualTo(127);
        }
    }

    @Nested
    @DisplayName("key signatures")
    class KeySignatures {

        private Key major(NoteLetter letter, Accidental accidental) {
            return Key.ofSeconds(new PitchSpelling(letter, accidental, 4), Mode.MAJOR, 0, 1,
                    Confidence.CERTAIN);
        }

        private Key minor(NoteLetter letter, Accidental accidental) {
            return Key.ofSeconds(new PitchSpelling(letter, accidental, 4), Mode.MINOR, 0, 1,
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
            LyricWord word = LyricWord.ofSeconds(text, 0, 0.5, Confidence.CERTAIN);
            assertThat(word.syllableEstimate()).isEqualTo(expected);
        }

        @Test
        @DisplayName("always reports at least one syllable")
        void neverReportsZero() {
            LyricWord word = LyricWord.ofSeconds("shh", 0, 0.5, Confidence.CERTAIN);
            assertThat(word.syllableEstimate()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a plain word carries no engraving marks")
        void plainWordHasNoMarks() {
            LyricWord word = LyricWord.ofSeconds("love", 0, 0.5, Confidence.CERTAIN);

            assertThat(word.hyphenatedToNext()).isFalse();
            assertThat(word.melisma()).isFalse();
        }

        @Test
        @DisplayName("engraving marks survive the copying operations")
        void marksSurviveCopies() {
            // snappedTo and withText run after the marks are decided, so dropping
            // them there would lose every hyphen the moment lyrics were snapped
            // to the beat grid.
            LyricWord word = LyricWord.ofSeconds("hal", 0, 0.5, Confidence.CERTAIN)
                    .withHyphenToNext(true)
                    .withMelisma(true);

            assertThat(word.snappedTo(4.0).hyphenatedToNext()).isTrue();
            assertThat(word.snappedTo(4.0).melisma()).isTrue();
            assertThat(word.withText("hall").hyphenatedToNext()).isTrue();
            assertThat(word.withText("hall").melisma()).isTrue();
            assertThat(word.withHyphenToNext(false).melisma()).isTrue();
            assertThat(word.withMelisma(false).hyphenatedToNext()).isTrue();
        }

        @Test
        @DisplayName("hyphenated syllables print as one word")
        void hyphenatedSyllablesRejoin() {
            LyricLine line = new LyricLine(List.of(
                    LyricWord.ofSeconds("hal", 0.0, 0.2, Confidence.CERTAIN).withHyphenToNext(true),
                    LyricWord.ofSeconds("le", 0.2, 0.4, Confidence.CERTAIN).withHyphenToNext(true),
                    LyricWord.ofSeconds("lu", 0.4, 0.6, Confidence.CERTAIN).withHyphenToNext(true),
                    LyricWord.ofSeconds("jah", 0.6, 0.8, Confidence.CERTAIN).withMelisma(true),
                    LyricWord.ofSeconds("now", 1.0, 1.2, Confidence.CERTAIN)),
                    Confidence.CERTAIN);

            assertThat(line.text()).isEqualTo("hallelujah now");
        }

        @Test
        @DisplayName("a melisma's extent is recoverable from the snapped beats")
        void melismaExtentComesFromTheBeats() {
            // Why melisma is a flag and not a length: the span a held syllable
            // covers is already there, by value, as the gap to the next
            // syllable's snapped beat. Storing it again would be a second source
            // of truth that can disagree with the first -- the same argument this
            // change makes for putting beats on Key and Section rather than bars.
            LyricLine line = new LyricLine(List.of(
                    LyricWord.ofSeconds("glo", 0.0, 0.5, Confidence.CERTAIN)
                            .snappedTo(0.0).withHyphenToNext(true),
                    LyricWord.ofSeconds("ri", 0.5, 1.0, Confidence.CERTAIN)
                            .snappedTo(1.0).withHyphenToNext(true).withMelisma(true),
                    LyricWord.ofSeconds("a", 2.5, 3.0, Confidence.CERTAIN).snappedTo(5.0)),
                    Confidence.CERTAIN);

            List<LyricWord> words = line.words();
            double melismaStart = words.get(1).startBeat().orElseThrow();
            double melismaEnd = words.get(2).startBeat().orElseThrow();

            assertThat(words.get(1).melisma()).isTrue();
            assertThat(melismaEnd - melismaStart).isEqualTo(4.0);
        }

        @Test
        @DisplayName("an unhyphenated line still prints with spaces")
        void plainLineIsUnchanged() {
            LyricLine line = new LyricLine(List.of(
                    LyricWord.ofSeconds("hello", 0.0, 0.4, Confidence.CERTAIN),
                    LyricWord.ofSeconds("world", 0.5, 0.9, Confidence.CERTAIN)),
                    Confidence.CERTAIN);

            assertThat(line.text()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("a hyphen on the last syllable of a line does not leave a trailing space")
        void trailingHyphenDoesNotPad() {
            // A word split across a line break leaves the last syllable hyphenated
            // with nothing after it. Two words, not one, so the loop actually
            // reaches the final element with the flag set rather than
            // short-circuiting on the line having a single word.
            LyricLine line = new LyricLine(List.of(
                    LyricWord.ofSeconds("I", 0.0, 0.2, Confidence.CERTAIN),
                    LyricWord.ofSeconds("won", 0.4, 0.6, Confidence.CERTAIN)
                            .withHyphenToNext(true)),
                    Confidence.CERTAIN);

            assertThat(line.text()).isEqualTo("I won");
            assertThat(line.text()).doesNotEndWith(" ").doesNotEndWith("-");
        }
    }
}
