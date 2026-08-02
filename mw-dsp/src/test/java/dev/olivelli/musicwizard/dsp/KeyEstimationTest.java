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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What {@link KeyEstimator} reads out of a chord sequence.
 *
 * <p>Symbolic throughout: these fixtures are the changes a musician would write
 * down, not audio, so a failure here is the key rule and never the front end.
 * What the same rules do to real recordings is {@code tools/score-samples.py}
 * and its committed baseline.
 */
class KeyEstimationTest {

    /** Seconds each chord of a fixture lasts, so a bar is a bar. */
    private static final double BAR = 2.0;

    /** A progression of one-bar chords, given as lead-sheet symbols. */
    private static ChordProgression bars(String... symbols) {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < symbols.length; i++) {
            chords.add(chord(symbols[i], i * BAR, (i + 1) * BAR));
        }
        return new ChordProgression(chords, Confidence.of(0.8));
    }

    private static Chord chord(String symbol, double start, double end) {
        if (symbol.equals("N.C.")) {
            return Chord.noChord(start, end, Confidence.of(0.8));
        }
        int split = symbol.length() > 1 && (symbol.charAt(1) == '#' || symbol.charAt(1) == 'b')
                ? 2 : 1;
        PitchSpelling root = PitchSpelling.parse(symbol.substring(0, split) + "4");
        String suffix = symbol.substring(split);
        ChordQuality quality = null;
        for (ChordQuality candidate : ChordQuality.values()) {
            if (candidate != ChordQuality.NONE && candidate.symbol().equals(suffix)) {
                quality = candidate;
            }
        }
        if (quality == null) {
            throw new IllegalArgumentException("no quality for suffix \"" + suffix + "\"");
        }
        return Chord.ofSeconds(root, quality, start, end, Confidence.of(0.8));
    }

    private static String keyOf(ChordProgression progression) {
        return estimate(progression).key().displayName();
    }

    private static KeyEstimator.Estimate estimate(ChordProgression progression) {
        Optional<KeyEstimator.Estimate> estimate =
                KeyEstimator.estimate(progression, 0, 60);
        assertThat(estimate).as("an estimate for %s", progression.chords()).isPresent();
        return estimate.get();
    }

    @Nested
    @DisplayName("the diatonic set")
    class DiatonicSet {

        @Test
        @DisplayName("a twelve-bar blues is in its own key, not in its subdominant")
        void bluesIsNotReadAsItsSubdominant() {
            // The quick-change form, whose second bar is the IV: it holds fewer
            // bars of the tonic than the plain form, so nothing but the harmony
            // itself is left to pull the answer to G. Every seventh here lies
            // outside G major and inside C major, which is the pull the triad
            // rule removes.
            assertThat(keyOf(bars(
                    "G7", "C7", "G7", "G7",
                    "C7", "C7", "G7", "G7",
                    "D7", "C7", "G7", "D7"))).isEqualTo("G major");
        }

        @Test
        @DisplayName("the same shape transposed follows the transposition")
        void transposingTheBluesTransposesTheKey() {
            assertThat(keyOf(bars(
                    "E7", "E7", "E7", "E7",
                    "A7", "A7", "E7", "E7",
                    "B7", "A7", "E7", "B7"))).isEqualTo("E major");
        }

        @Test
        @DisplayName("a plain pop loop names the key its chords are diatonic to")
        void popLoopNamesItsKey() {
            assertThat(keyOf(bars("C", "G", "Am", "F", "C", "G", "Am", "F")))
                    .isEqualTo("C major");
        }

        @Test
        @DisplayName("the tonic is weighed by how long it sounds, not by how often")
        void durationDecidesRatherThanCount() {
            // One long G against three short ones elsewhere: counting chords
            // would answer C, and the piece is in G.
            List<Chord> chords = List.of(
                    chord("G", 0, 24),
                    chord("C", 24, 26),
                    chord("Am", 26, 28),
                    chord("D", 28, 30));
            assertThat(keyOf(new ChordProgression(chords, Confidence.of(0.8))))
                    .isEqualTo("G major");
        }
    }

    @Nested
    @DisplayName("minor keys")
    class MinorKeys {

        @Test
        @DisplayName("a minor tonic with its dominant stays minor")
        void aMinorWithItsDominantStaysMinor() {
            // Am and E7 share a diatonic set with C major, and E7's G sharp is
            // in neither C major nor the natural minor -- it is A minor's raised
            // seventh, and that is the whole of the evidence.
            assertThat(keyOf(bars("Am", "E7", "Am", "E7", "Am", "E7", "Am", "E7")))
                    .isEqualTo("A minor");
        }

        @Test
        @DisplayName("a minor blues stays minor")
        void aMinorBluesStaysMinor() {
            assertThat(keyOf(bars("Bm", "Bm", "Bm", "Bm", "G7", "F#7", "Bm", "Bm")))
                    .isEqualTo("B minor");
        }

        @Test
        @DisplayName("a minor key with a flat-side vocabulary stays minor")
        void bossaStaysMinor() {
            assertThat(keyOf(bars(
                    "Cm7", "Cm7", "Fm6", "Fm6",
                    "Dm7b5", "G7", "Cm6", "Cm6",
                    "Ebm7", "Ab7", "Dbmaj7", "Dbmaj7",
                    "Dm7b5", "G7", "Cm6", "Cm6"))).isEqualTo("C minor");
        }

        @Test
        @DisplayName("a one-chord minor vamp is in that chord's key")
        void minorVampNamesItsOwnChord() {
            assertThat(keyOf(bars("Fm7", "Fm7", "Fm7", "Fm7"))).isEqualTo("F minor");
        }

        @Test
        @DisplayName("a one-chord dominant vamp is major")
        void dominantVampIsMajor() {
            assertThat(keyOf(bars("Eb7", "Eb7", "Eb7", "Eb7"))).isEqualTo("Eb major");
        }

        @Test
        @DisplayName("the dominant is credited to the minor only on its own fifth degree")
        void aMajorChordElsewhereIsNotADominant() {
            // The same E major triad, now a whole tone below a D minor tonic
            // rather than a fifth above an A minor one, so it is chromatic
            // rather than the harmonic-minor dominant and cannot make D minor
            // the answer.
            assertThat(keyOf(bars("Dm", "E", "Dm", "E"))).isNotEqualTo("D minor");
        }
    }

    @Nested
    @DisplayName("the tonic within a diatonic set")
    class RelativePair {

        @Test
        @DisplayName("a loop and its rotation are the same evidence, and get the same answer")
        void aRotationOfALoopIsNotEvidence() {
            // The limit of what chords alone can say, stated as a test rather
            // than left to be rediscovered. C-G-Am-F and Am-F-C-G hold the same
            // chords for the same time; only where the recording starts and
            // stops differs, and the estimator does not read that (see the class
            // javadoc). Both come back with the same key and a tonic confidence
            // that says so.
            KeyEstimator.Estimate major = estimate(bars("C", "G", "Am", "F"));
            KeyEstimator.Estimate minor = estimate(bars("Am", "F", "C", "G"));
            assertThat(minor.key().displayName()).isEqualTo(major.key().displayName());
            assertThat(major.tonicConfidence().value())
                    .as("a tonic no chord separates from its relative")
                    .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
        }

        @Test
        @DisplayName("the raised seventh separates the pair, and says so in the confidence")
        void theDominantRaisesTheTonicConfidence() {
            KeyEstimator.Estimate withDominant = estimate(bars("Am", "E7", "Am", "E7"));
            KeyEstimator.Estimate without = estimate(bars("Am", "Em", "Am", "Em"));
            assertThat(withDominant.key().displayName()).isEqualTo("A minor");
            assertThat(withDominant.tonicConfidence().value())
                    .isGreaterThan(without.tonicConfidence().value());
        }

        @Test
        @DisplayName("the two decisions are reported apart and multiply into the key's own")
        void theKeyCarriesTheProductOfTheTwo() {
            KeyEstimator.Estimate estimate = estimate(bars("Am", "E7", "Dm", "Am"));
            assertThat(estimate.key().confidence().value())
                    .isCloseTo(estimate.signatureConfidence().value()
                                    * estimate.tonicConfidence().value(),
                            org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("what it refuses to answer")
    class Refusals {

        @Test
        @DisplayName("nothing sounding names no key")
        void silenceNamesNoKey() {
            assertThat(KeyEstimator.estimate(ChordProgression.empty(), 0, 60)).isEmpty();
            assertThat(KeyEstimator.estimate(bars("N.C.", "N.C."), 0, 60)).isEmpty();
        }

        @Test
        @DisplayName("no-chord spans are passed over rather than scored")
        void noChordSpansAreIgnored() {
            // A lead-in of silence in front of the same four bars must not move
            // the answer, and must not dilute the confidence either: it is
            // absence of evidence, not evidence.
            KeyEstimator.Estimate plain = estimate(bars("Am", "E7", "Am", "E7"));
            KeyEstimator.Estimate withLeadIn =
                    estimate(bars("N.C.", "N.C.", "Am", "E7", "Am", "E7"));
            assertThat(withLeadIn.key().displayName()).isEqualTo(plain.key().displayName());
            assertThat(withLeadIn.key().confidence().value())
                    .isCloseTo(plain.key().confidence().value(),
                            org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("how the tonic is written")
    class Spelling {

        @Test
        @DisplayName("a flat key is written flat")
        void flatKeysAreWrittenFlat() {
            // Not "D# major", which is the same pitch class spelled from a
            // signature of nine sharps. ChordEstimator hands every black key up
            // as a sharp, so nothing upstream of this decides it.
            assertThat(keyOf(bars("Eb", "Ab", "Bb", "Eb"))).isEqualTo("Eb major");
            assertThat(keyOf(bars("Cm", "Fm", "G7", "Cm"))).isEqualTo("C minor");
        }

        @Test
        @DisplayName("a sharp minor key is written sharp")
        void sharpMinorKeysAreWrittenSharp() {
            // F sharp minor needs three sharps and G flat minor nine flats.
            assertThat(keyOf(bars("F#m", "Bm", "C#7", "F#m"))).isEqualTo("F# minor");
        }

        @Test
        @DisplayName("the key spans exactly the range it was asked for")
        void theKeySpansWhatWasAsked() {
            KeyEstimator.Estimate estimate =
                    KeyEstimator.estimate(bars("C", "G", "Am", "F"), 0, 123.5).orElseThrow();
            assertThat(estimate.key().startSeconds()).isEqualTo(0);
            assertThat(estimate.key().endSeconds()).isEqualTo(123.5);
            assertThat(estimate.key().isQuantized())
                    .as("estimation works in seconds; the quantizer places it")
                    .isFalse();
        }
    }
}
