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
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        @DisplayName("a twelve-bar blues is in the key of its tonic chord")
        void aBluesIsInTheKeyOfItsTonicChord() {
            // The property, on the canonical form, with no claim about which
            // rule reaches it.
            assertThat(keyOf(bars(
                    "G7", "G7", "G7", "G7",
                    "C7", "C7", "G7", "G7",
                    "D7", "C7", "G7", "D7"))).isEqualTo("G major");
        }

        @Test
        @DisplayName("a blues is scored on its triads, not on its whole chord spellings")
        void bluesSeventhsAreNotScored() {
            // Widen score()'s triadTones from three to the whole interval set
            // and this fixture answers C major; the canonical form above answers
            // G major either way, so it cannot stand in for this. Widened, the
            // two keys score identically and C major wins on the same-mode
            // tie-break, so what this pins is the outcome rather than a margin
            // -- #278 could change how that tie falls.
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
            // Deliberately without the vi. A loop holding both C and Am scores
            // C major and A minor identically -- see RelativePair -- so it would
            // pass on the tie-break rather than on the diatonic fit this names.
            assertThat(keyOf(bars("C", "F", "G", "C", "F", "G", "C", "G")))
                    .isEqualTo("C major");
        }

        @Test
        @DisplayName("a turnaround with a secondary dominant is still in its own key")
        void aSecondaryDominantDoesNotMoveTheKey() {
            // I VI7 ii V in C. A7's C sharp is chromatic in C major and is D
            // minor's raised seventh, so the dominant rule hands D minor a
            // perfect fit for it and the two keys score identically -- the
            // secondary dominant is indistinguishable from a real one on this
            // scoring. What decides is the major prior, and the low confidence
            // that comes back is the honest reading of a genuine tie.
            assertThat(keyOf(bars("C", "A7", "Dm", "G7", "C", "A7", "Dm", "G7")))
                    .isEqualTo("C major");
        }

        @Test
        @DisplayName("the tonic is weighed by how long it sounds, not by how often")
        void durationDecidesRatherThanCount() {
            // One long E against a short C-F-G, which is three chords of C major
            // including its tonic. Counted a chord at a time the piece is in C;
            // weighed by the time each chord sounds it is in E, and it is E that
            // a listener hears.
            List<Chord> chords = List.of(
                    chord("E", 0, 24),
                    chord("C", 24, 26),
                    chord("F", 26, 28),
                    chord("G", 28, 30));
            assertThat(keyOf(new ChordProgression(chords, Confidence.of(0.8))))
                    .isEqualTo("E major");
        }
    }

    @Nested
    @DisplayName("minor keys")
    class MinorKeys {

        @Test
        @DisplayName("a minor tonic with its dominant stays minor")
        void aMinorWithItsDominantStaysMinor() {
            // No key explains every note here: A minor leaves out E7's G sharp
            // and E major leaves out Am's C natural, one tone each. Without the
            // raised-seventh rule the two therefore score identically and the
            // major prior takes it, so this fixture answers E major. The rule
            // makes E7 fully diatonic to A minor and the tie becomes a margin.
            assertThat(keyOf(bars("Am", "E7", "Am", "E7", "Am", "E7", "Am", "E7")))
                    .isEqualTo("A minor");
        }

        @Test
        @DisplayName("two keys of one mode that nothing separates take the simpler signature")
        void anUndecidableSameModePairTakesTheSimplerSignature() {
            // i-v repeated: both chords sit in both keys and each key owns one
            // of them, so nothing separates A minor from E minor and the piece
            // is written with the signature that asks for less. An editorial
            // preference and not a reading -- transposed, this fixture answers
            // the v as readily as the i, which it cannot avoid: C-G and C-F are
            // one shape with opposite right answers (#278).
            assertThat(keyOf(bars("Am", "Em", "Am", "Em"))).isEqualTo("A minor");
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

        @ParameterizedTest(name = "the I-V-vi-IV loop in {0}")
        @CsvSource({
            // The expected column is spelled from the conventional signature,
            // which is why the black keys read flat: D flat major needs five
            // flats where C sharp major needs seven sharps.
            "C, C major", "C#, Db major", "D, D major", "D#, Eb major",
            "E, E major", "F, F major", "F#, Gb major", "G, G major",
            "G#, Ab major", "A, A major", "A#, Bb major", "B, B major",
        })
        @DisplayName("the answer to an undecidable relative pair transposes with the music")
        void theRelativeTieBreakIsTranspositionInvariant(String tonic, String expected) {
            // A relative pair nothing separates has to be broken by something,
            // and that something must not be an array index: deciding by pitch
            // class made this loop major in three keys and minor in the other
            // nine, so the answer moved when the music was transposed and the
            // corpus score was a function of what key the benchmarks happened to
            // be in. Major is the stated prior; the point of the sweep is that
            // it is the same answer twelve times. Only the relative branch has
            // this property -- see beats, and #278.
            assertThat(keyOf(transposed(tonic, 0, 7, 9, 5)))
                    .as("the shared prior, whatever the music is transposed to")
                    .isEqualTo(expected);
            assertThat(estimate(transposed(tonic, 0, 7, 9, 5)).tonicConfidence().value())
                    .as("and it is a prior, so it reports no evidence")
                    .isEqualTo(0.5);
        }

        /** The I-V-vi-IV loop built on a tonic, as semitone offsets from it. */
        private ChordProgression transposed(String tonic, int... offsets) {
            int root = PitchSpelling.parse(tonic + "4").pitchClass();
            List<Chord> chords = new ArrayList<>();
            for (int i = 0; i < offsets.length; i++) {
                int pitchClass = Math.floorMod(root + offsets[i], 12);
                // The vi is the minor one; the sharp spelling is what
                // ChordEstimator hands up and carries no intent.
                chords.add(new Chord(
                        PitchSpelling.ofMidiPitchSharp(60 + pitchClass),
                        offsets[i] == 9 ? ChordQuality.MINOR : ChordQuality.MAJOR,
                        Optional.empty(), i * BAR, (i + 1) * BAR,
                        Optional.empty(), Optional.empty(), Confidence.of(0.8)));
            }
            return new ChordProgression(chords, Confidence.of(0.8));
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
    @DisplayName("what it writes down about the two decisions (#678)")
    class WhatItWeighed {

        @Test
        @DisplayName("every key it scored is recorded, with the key it named among them")
        void everyCandidateIsRecorded() {
            KeyTrace trace = estimate(bars("Am", "E7", "Dm", "Am")).trace();

            assertThat(trace.source()).isEqualTo(KeyTrace.FROM_CHORDS);
            // Twelve tonics in two modes: a shorter list is a candidate that
            // went unrecorded rather than one that went unscored.
            assertThat(trace.candidates()).hasSize(24);
            assertThat(trace.tonic().winner()).isEqualTo("A minor");
            assertThat(trace.tonic().runnerUp()).isEqualTo("C major");
            assertThat(trace.signature().winner()).isEqualTo(trace.tonic().winner());
        }

        @Test
        @DisplayName("the two things that can separate a relative pair are the two recorded")
        void theEvidenceThatSeparatedThePairIsRecorded() {
            KeyTrace trace = estimate(bars("Am", "E7", "Am", "E7")).trace();

            KeyTrace.Candidate home = candidate(trace, "A minor");
            KeyTrace.Candidate relative = candidate(trace, "C major");
            // The E7 is the chord on A minor's fifth degree whose third is that
            // key's raised seventh, and it is what makes this loop minor.
            assertThat(home.raisedSeventhSeconds()).isEqualTo(2 * BAR);
            assertThat(home.raisedSeventhSpans()).isEqualTo(2);
            assertThat(relative.raisedSeventhSpans()).isZero();
            assertThat(home.tonicChordSeconds()).isEqualTo(2 * BAR);
            assertThat(relative.tonicChordSpans()).isZero();
        }

        @Test
        @DisplayName("a pair nothing separated is recorded as a tie, not as a narrow win")
        void theFloorIsRecordedAsAState() {
            // The failure mode the class is designed around: neither of the two
            // rules says anything, the stated preference for the major decides,
            // and the tonic confidence comes back at its floor. A reader has to
            // be able to tell that from a margin that was merely small.
            KeyTrace trace = estimate(bars("C", "G", "Am", "F")).trace();

            assertThat(trace.tonic().read()).isEqualTo("tied");
            assertThat(trace.tonic().margin()).isZero();
            assertThat(candidate(trace, "C major").raisedSeventhSpans()).isZero();
            assertThat(candidate(trace, "A minor").raisedSeventhSpans()).isZero();
            assertThat(candidate(trace, "C major").tonicChordSeconds())
                    .isEqualTo(candidate(trace, "A minor").tonicChordSeconds());
            assertThat(trace.signature().read())
                    .as("and the other decision was not at its floor")
                    .isEqualTo("separated");
        }

        @Test
        @DisplayName("the margins are the differences between the scores that are recorded")
        void theMarginsAreTheRecordedScores() {
            // Two numbers a reader will subtract for themselves, so a page that
            // showed a margin the candidate table denies would be showing two
            // measurements.
            KeyTrace trace = estimate(bars("Am", "E7", "Dm", "Am")).trace();

            assertThat(candidate(trace, trace.tonic().winner()).score()
                    - candidate(trace, trace.tonic().runnerUp()).score())
                    .isEqualTo(trace.tonic().margin());
            assertThat(candidate(trace, trace.signature().winner()).score()
                    - candidate(trace, trace.signature().runnerUp()).score())
                    .isEqualTo(trace.signature().margin());
        }

        @Test
        @DisplayName("how much of the span carried a chord is recorded, silence and all")
        void whatWasWeighedIsRecorded() {
            // The factor both confidences are scaled by, which a margin says
            // nothing about: these four bars sound inside a minute.
            KeyTrace trace = KeyEstimator.estimate(bars("Am", "E7", "Dm", "Am"), 0, 60)
                    .orElseThrow().trace();

            assertThat(trace.soundingSeconds()).isEqualTo(4 * BAR);
            assertThat(trace.spanSeconds()).isEqualTo(60);
            assertThat(trace.weighed()).isEqualTo(4 * BAR / 60);
        }

        private KeyTrace.Candidate candidate(KeyTrace trace, String key) {
            return trace.candidates().stream()
                    .filter(entry -> entry.key().equals(key))
                    .findFirst().orElseThrow();
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
        @DisplayName("no-chord spans decide nothing, so the key does not move")
        void noChordSpansAreNotScored() {
            // Silence is not evidence for any key, so a lead-in in front of the
            // same four bars cannot change which key is named.
            KeyEstimator.Estimate plain = estimate(bars("Am", "E7", "Am", "E7"));
            KeyEstimator.Estimate withLeadIn =
                    estimate(bars("N.C.", "N.C.", "Am", "E7", "Am", "E7"));

            assertThat(withLeadIn.key().displayName()).isEqualTo(plain.key().displayName());
        }

        @Test
        @DisplayName("a margin over almost no music is not a confident answer")
        void confidenceFallsWithHowLittleWasWeighed() {
            // The score is an average over the sounding time, so half a second
            // of one chord averages as perfectly as five minutes of it and
            // produces the same margin. Without a term for how much was weighed,
            // a chart headed by four minutes of silence claimed a certain key.
            ChordProgression sparse = new ChordProgression(
                    List.of(chord("Fm7", 0, 0.5)), Confidence.of(0.8));
            ChordProgression solid = new ChordProgression(
                    List.of(chord("Fm7", 0, 240)), Confidence.of(0.8));

            KeyEstimator.Estimate thin = KeyEstimator.estimate(sparse, 0, 240).orElseThrow();
            KeyEstimator.Estimate full = KeyEstimator.estimate(solid, 0, 240).orElseThrow();

            assertThat(thin.key().displayName())
                    .as("the same key, from the same one chord")
                    .isEqualTo(full.key().displayName());
            assertThat(full.key().confidence().value()).isGreaterThan(0.9);
            assertThat(thin.key().confidence().value())
                    .as("half a second inside four minutes")
                    .isLessThan(0.1);
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
