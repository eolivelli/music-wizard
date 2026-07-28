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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tier-0 downbeats: fixtures whose bar lines are exact by construction, so the
 * phase can be compared against truth rather than against another estimate.
 *
 * <p>The case that matters is the one that motivated the estimator: a click
 * track accents every beat identically, so onset energy cannot tell which one
 * starts the bar, while the chords change exactly on the bar lines and say so
 * unambiguously.
 */
class DownbeatEstimationTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /** A I-V-vi-IV signal, one chord per bar of four beats at 120 BPM. */
    private static float[] chordsPerBar(double seconds) {
        return SignalFactory.clickTrackWithChords(120, new double[][] {
                SignalFactory.majorTriad(60),   // C
                SignalFactory.majorTriad(67),   // G
                SignalFactory.minorTriad(69),   // Am
                SignalFactory.majorTriad(65),   // F
        }, 4, seconds, RATE);
    }

    /**
     * A click track holding one chord throughout, whose bars are marked by
     * dynamics alone: the first beat of each is struck hard, the rest softly.
     *
     * <p>The complement of {@link #chordsPerBar}: here the harmony says nothing
     * about the bar lines and the onsets say everything.
     */
    private static float[] accentedOneChord(double seconds) {
        int length = (int) Math.round(seconds * RATE);
        float[] out = new float[length];
        double interval = 60.0 / 120;
        int clickLength = Math.max(1, RATE / 100);
        int beat = 0;
        for (double t = 0; t < seconds; t += interval, beat++) {
            int start = (int) Math.round(t * RATE);
            double amplitude = beat % 4 == 0 ? 0.8 : 0.2;
            for (int i = 0; i < clickLength && start + i < length; i++) {
                double decay = Math.exp(-8.0 * i / clickLength);
                out[start + i] += (float) (amplitude * decay
                        * Math.sin(2 * Math.PI * 1000 * i / RATE));
            }
        }
        for (double frequency : SignalFactory.majorTriad(60)) {
            for (int i = 0; i < length; i++) {
                out[i] += (float) (0.5 / 3 * Math.sin(2 * Math.PI * frequency * i / RATE));
            }
        }
        return out;
    }

    /**
     * A drum pattern over one unchanging chord: a low kick on beat 1, a noise
     * snare on beat 3, and almost nothing on 2 and 4.
     *
     * <p>Unlike {@link #accentedOneChord} the beats differ in timbre as well as
     * in level, which spreads the onset strengths far enough apart to catch a
     * confidence model that scales with how loud the accent is.
     */
    private static float[] kickAndSnare(double seconds) {
        int length = (int) Math.round(seconds * RATE);
        float[] out = new float[length];
        // Fixed seed: the snare needs a broadband spectrum, and a fixture whose
        // answer changes from run to run is not a tier-0 fixture.
        Random noise = new Random(7);
        int beat = 0;
        for (double t = 0; t < seconds; t += 0.5, beat++) {
            int start = (int) Math.round(t * RATE);
            int strike = Math.max(1, RATE / 20);
            for (int i = 0; i < strike && start + i < length; i++) {
                double decay = Math.exp(-8.0 * i / strike);
                out[start + i] += (float) switch (beat % 4) {
                    case 0 -> 0.9 * decay * Math.sin(2 * Math.PI * 60 * i / RATE);
                    case 2 -> 0.6 * decay * (noise.nextDouble() * 2 - 1);
                    default -> 0.05 * decay * Math.sin(2 * Math.PI * 1000 * i / RATE);
                };
            }
        }
        for (double frequency : SignalFactory.majorTriad(60)) {
            for (int i = 0; i < length; i++) {
                out[i] += (float) (0.5 / 3 * Math.sin(2 * Math.PI * frequency * i / RATE));
            }
        }
        return out;
    }

    /** Everything the estimator needs, analysed from one signal. */
    private record Analysis(BeatTracker.Result beats, Chroma chroma, OnsetEnvelope envelope) {
        static Analysis of(float[] samples) {
            AudioBuffer audio = new AudioBuffer(samples, RATE);
            OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);
            BeatTracker.Result beats = BeatTracker.track(envelope);
            Chroma chroma = Chroma.extract(audio).beatSynchronous(beats.beatTimes());
            return new Analysis(beats, chroma, envelope);
        }

        DownbeatEstimator.Estimate estimate(int beatsPerBar) {
            return DownbeatEstimator.estimate(beats.beatTimes(), chroma, envelope, beatsPerBar);
        }
    }

    @Nested
    @DisplayName("phase from harmony")
    class FromHarmony {

        @Test
        @DisplayName("puts the bar lines where the chords change")
        void barLinesFollowChordChanges() {
            // Bars are two seconds long and the chords change on every one of
            // them. Before this estimator existed the grid came back a beat out
            // of phase -- downbeats at 1.46s and 3.46s against chord changes at
            // 1.96s and 3.96s -- consistent with the beats and wrong about the
            // bars, which is exactly what issue #27 reported.
            Analysis analysis = Analysis.of(chordsPerBar(32));

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), analysis.estimate(4));

            List<Double> downbeats = grid.downbeatTimes();
            List<Double> changes = ChordEstimator
                    .estimate(analysis.chroma(), analysis.beats().beatTimes())
                    .chords().stream().map(Chord::startSeconds).toList();

            assertThat(downbeats).isNotEmpty();
            // Every chord change is a bar line on this fixture, so each one must
            // coincide with a downbeat -- which is the agreement the issue found
            // missing. Compared against the chord changes rather than against the
            // nominal bar lines because the first tracked beat sits 0.15s late:
            // the onset envelope has no history before the first click, and that
            // is a beat-tracking artifact no downbeat phase can correct.
            for (double change : changes) {
                assertThat(downbeats).anySatisfy(downbeat ->
                        assertThat(downbeat).isCloseTo(change, within(0.06)));
            }
            // Sixteen bars in 32 seconds, allowing for the windowing at the edges.
            assertThat(downbeats).hasSizeBetween(14, 17);
        }

        @Test
        @DisplayName("onset energy alone gets this signal wrong")
        void onsetEnergyAloneIsWrongHere() {
            // Not pinning desirable behaviour: recording why the harmonic term is
            // needed at all. Every click on this track is identical, so onset
            // energy has nothing to go on and picks a phase that is not the one
            // the chords change on. Delete the harmonic term and the test above
            // starts failing again.
            Analysis analysis = Analysis.of(chordsPerBar(32));

            int fromOnsets = DownbeatEstimator
                    .fromOnsets(analysis.beats().beatTimes(), analysis.envelope(), 4).phase();

            assertThat(analysis.estimate(4).phase()).isZero();
            assertThat(fromOnsets).isNotZero();
        }

        @Test
        @DisplayName("says it is confident when the chords back the phase up")
        void confidenceReflectsTheEvidence() {
            // Chord changes on the bar line are strong evidence; an unchanging
            // harmony is not, and the two must not report the same confidence.
            Confidence withChordChanges = Analysis.of(chordsPerBar(32)).estimate(4).confidence();
            Confidence withoutChordChanges =
                    Analysis.of(accentedOneChord(24)).estimate(4).confidence();

            assertThat(withChordChanges.value()).isGreaterThan(0.8);
            // Capped at 0.45 for a phase nothing but the accent supports, so it
            // can never read as strongly as one harmony has backed. The weakest
            // evidence reporting the highest confidence is how the original bug
            // stayed invisible.
            assertThat(withoutChordChanges.value()).isLessThanOrEqualTo(0.45);
        }
    }

    @Nested
    @DisplayName("phase from onsets")
    class FromOnsets {

        @Test
        @DisplayName("keeps the accented phase when the harmony never changes")
        void accentDecidesWhenHarmonyIsStatic() {
            // The regression risk of scoring harmony: drowning out the cases
            // where onset energy genuinely does mark the downbeat. Here the
            // chroma is the same in every bar, so its per-phase differences are
            // noise and the accent has to win.
            Analysis analysis = Analysis.of(accentedOneChord(24));

            assertThat(analysis.estimate(4).phase()).isZero();
        }

        @Test
        @DisplayName("is what the grid falls back to without chroma")
        void gridWithoutChromaUsesOnsets() {
            Analysis analysis = Analysis.of(accentedOneChord(24));

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), analysis.envelope(), 4);

            assertThat(grid.beats().get(0).downbeat()).isTrue();
        }

        @Test
        @DisplayName("never sounds sure of itself, however pronounced the accent")
        void onsetsAreAlwaysHeldInDoubt() {
            // This is the path that produced the bug, so it must not be able to
            // report a confidence that harmony-backed answers cannot beat -- a
            // consumer choosing between two grids on confidence alone would
            // otherwise prefer the weaker evidence.
            // kickAndSnare is the fixture that matters: a kick, a noise snare and
            // two near-silent beats make the phases differ by enough that the
            // original confidence model reported 0.53 here, above what it gave
            // some harmony-backed answers. The three cheap fixtures alongside it
            // all stayed under the cap on their own.
            for (float[] signal : new float[][] {kickAndSnare(24), accentedOneChord(24),
                    chordsPerBar(32), SignalFactory.clickTrack(120, 20, RATE)}) {
                Analysis analysis = Analysis.of(signal);

                DownbeatEstimator.Estimate estimate = DownbeatEstimator.fromOnsets(
                        analysis.beats().beatTimes(), analysis.envelope(), 4);

                assertThat(estimate.confidence().value()).isLessThanOrEqualTo(0.45);
            }
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring {

        /** Chroma that holds one pitch class for {@code perBar} spans, then moves on. */
        private static Chroma stepwiseChroma(int spans, int perBar, int offset) {
            double[][] vectors = new double[spans][12];
            for (int span = 0; span < spans; span++) {
                int step = Math.floorDiv(span - offset, perBar);
                vectors[span][Math.floorMod(step * 5, 12)] = 1;
            }
            return new Chroma(vectors, 0);
        }

        /**
         * The same, but with the two alternating vectors set to a chosen cosine
         * similarity, so a bar line's novelty can be made as faint as wanted.
         */
        private static Chroma faintlyStepwiseChroma(int spans, int perBar, double similarity) {
            double[] held = new double[12];
            held[0] = 1;
            double[] moved = new double[12];
            moved[0] = similarity;
            moved[1] = Math.sqrt(1 - similarity * similarity);

            double[][] vectors = new double[spans][];
            for (int span = 0; span < spans; span++) {
                vectors[span] = (Math.floorDiv(span, perBar) % 2 == 0 ? held : moved).clone();
            }
            return new Chroma(vectors, 0);
        }

        /** Chroma with exactly one change, at the given span boundary. */
        private static Chroma oneChangeChroma(int spans, int changeAt) {
            double[][] vectors = new double[spans][12];
            for (int span = 0; span < spans; span++) {
                vectors[span][span < changeAt ? 0 : 5] = 1;
            }
            return new Chroma(vectors, 0);
        }

        /** A flat envelope, so that onset energy cannot break any tie. */
        private static OnsetEnvelope flatEnvelope(double seconds) {
            return new OnsetEnvelope(new double[(int) Math.round(seconds * 100)], 100);
        }

        /**
         * An envelope with a chosen strength at each phase's beats.
         *
         * <p>{@link OnsetEnvelope} is normalised to zero mean and unit variance,
         * so the levels at the beats are free to be negative, and the tests below
         * use negative ones deliberately: testing only with positive values would
         * miss anything that treats the envelope as a magnitude, which is what
         * let the first version of this scoring through. The fill between the
         * beats is scenery — only the beat frames are ever sampled — and is set
         * below zero so that reading this fixture does not suggest otherwise.
         *
         * @param levelsPerPhase strength at the beats of each phase, in deviations
         */
        private static OnsetEnvelope envelopeOf(List<Double> beatTimes, double[] levelsPerPhase) {
            double[] strength = new double[(int) Math.round(
                    (beatTimes.get(beatTimes.size() - 1) + 1) * 100)];
            java.util.Arrays.fill(strength, -0.2);
            for (int beat = 0; beat < beatTimes.size(); beat++) {
                strength[(int) Math.round(beatTimes.get(beat) * 100)] =
                        levelsPerPhase[Math.floorMod(beat, levelsPerPhase.length)];
            }
            return new OnsetEnvelope(strength, 100);
        }

        private static List<Double> beatsEvery(double interval, int count) {
            List<Double> times = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                times.add(i * interval);
            }
            return times;
        }

        @Test
        @DisplayName("novelty marks the beats where the chroma changes")
        void noveltyMarksChanges() {
            double[] novelty = DownbeatEstimator.harmonicNovelty(stepwiseChroma(16, 4, 0));

            // Orthogonal pitch classes either side of a change: cosine distance 1.
            assertThat(novelty[4]).isCloseTo(1.0, within(1e-9));
            assertThat(novelty[8]).isCloseTo(1.0, within(1e-9));
            // Within a bar nothing changes, and the ends have a span on one side.
            assertThat(novelty[5]).isZero();
            assertThat(novelty[0]).isZero();
            assertThat(novelty[novelty.length - 1]).isZero();
        }

        @Test
        @DisplayName("finds a phase that is not zero")
        void findsAnOffsetPhase() {
            // Guards against an estimator that happens to return 0 for every
            // input, which every fixture starting on a downbeat would hide.
            List<Double> beats = beatsEvery(0.5, 33);
            Chroma chroma = stepwiseChroma(32, 4, 2);

            assertThat(DownbeatEstimator.estimate(beats, chroma, flatEnvelope(20), 4).phase())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("harmony outvotes an onset accent on a different phase")
        void harmonyBeatsAConflictingAccent() {
            // The backbeat case: the loudest beat of the bar is not its first.
            // Harmony has to win, or the estimator is the old heuristic with
            // extra steps.
            List<Double> beats = beatsEvery(0.5, 33);
            Chroma chroma = stepwiseChroma(32, 4, 0);

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(
                    beats, chroma, envelopeOf(beats, new double[] {1, 1, 10, 1}), 4);

            assertThat(estimate.phase()).isZero();
        }

        @Test
        @DisplayName("falls back to onsets when there are too few beats to score")
        void tooFewBeatsForNovelty() {
            List<Double> beats = List.of(0.0, 0.5);
            Chroma chroma = new Chroma(new double[1][12], 0);

            // One span means no beat has chroma on both sides.
            assertThat(DownbeatEstimator.estimate(beats, chroma, flatEnvelope(2), 4).phase())
                    .isZero();
        }

        @Test
        @DisplayName("falls back rather than throwing when a single beat was tracked")
        void oneBeatFallsBackInsteadOfThrowing() {
            // Chroma.beatSynchronous cannot make a beat-synchronous chroma out of
            // one beat, so it returns the fixed-grid chroma unchanged. Validating
            // that before checking whether there is anything to score turned a
            // very short recording -- which the pipeline transcribed happily --
            // into an IllegalArgumentException.
            List<Double> oneBeat = List.of(0.1);
            Chroma fixedGrid = new Chroma(new double[8][12], 100);

            assertThat(DownbeatEstimator.estimate(oneBeat, fixedGrid, flatEnvelope(1), 4).phase())
                    .isZero();
        }

        @Test
        @DisplayName("a faint accent cannot outvote harmony however the envelope is centred")
        void aFaintAccentCannotOutvoteHarmony() {
            // The envelope is zero-mean by construction, so a phase mean can sit
            // anywhere around zero. Scoring the accent as a ratio to that mean
            // made a faint accent unbounded -- and near enough to the mean
            // crossing zero, discontinuous -- so it could overturn a cosine
            // distance of 1.0. The accent is bounded now, so it cannot.
            List<Double> beats = beatsEvery(0.5, 33);
            Chroma chroma = stepwiseChroma(32, 4, 0);

            // The first row is the one that matters: a faint peak against the
            // envelope's own negative floor, which is where dividing by the mean
            // put the term at +3.95 against harmony's ceiling of 1.0. The rows
            // after it walk the divisor through zero, where the old scaling was
            // also discontinuous.
            for (double[] levels : new double[][] {
                    {0.62, -0.2}, {0.62, 0.59}, {0.01, -0.01}, {-0.5, -0.6}, {100, 1}}) {
                double[] perPhase = {levels[1], levels[1], levels[0], levels[1]};
                DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(beats, chroma,
                        envelopeOf(beats, perPhase), 4);

                assertThat(estimate.phase()).as("accent %s", java.util.Arrays.toString(levels))
                        .isZero();
            }
        }

        @Test
        @DisplayName("without chroma, the first and last beats still count")
        void onsetsScoreEveryBeat() {
            // estimate() scores only the beats with harmony on both sides, and
            // has to: novelty is undefined at either end. fromOnsets has no such
            // constraint and must not inherit the restriction, because on a short
            // recording those two beats are a real share of the evidence. Here
            // the whole accent is on the first beat, and dropping it moves the
            // answer to a beat that is merely the loudest of what remains.
            List<Double> beats = beatsEvery(0.5, 9);
            double[] strength = new double[500];
            strength[0] = 10;
            strength[(int) Math.round(beats.get(3) * 100)] = 1;

            assertThat(DownbeatEstimator
                    .fromOnsets(beats, new OnsetEnvelope(strength, 100), 4).phase()).isZero();
        }

        @Test
        @DisplayName("bounding the accent does not cost it its ranking")
        void loudAccentsAreStillRanked() {
            // Bounding the onset term by clamping it flattened every accent past
            // the bound onto one score, and the resulting tie went to the earlier
            // phase -- so a signal whose loudest beat is the second of the bar
            // came back phased on the first, decided by arithmetic rather than by
            // evidence. tanh bounds without ordering loss; these levels all
            // saturate a clamp and must still rank the second beat highest.
            List<Double> beats = beatsEvery(0.5, 33);

            // Held over an unchanging harmony, so the accent is all there is to
            // rank and the full estimator has to rank it too -- not only
            // fromOnsets, which never applies the bound in the first place.
            Chroma unchanging = stepwiseChroma(32, 32, 0);

            for (double[] levels : new double[][] {
                    {6, 9, 2, 2}, {8, 10, 2, 2}, {5, 6, 1, 1}, {4, 5, 1, 1}}) {
                OnsetEnvelope envelope = envelopeOf(beats, levels);

                assertThat(DownbeatEstimator.fromOnsets(beats, envelope, 4).phase())
                        .as("fromOnsets, levels %s", java.util.Arrays.toString(levels))
                        .isEqualTo(1);
                assertThat(DownbeatEstimator.estimate(beats, unchanging, envelope, 4).phase())
                        .as("estimate, levels %s", java.util.Arrays.toString(levels))
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("harmony that changes at no consistent phase is not confident")
        void unalignedHarmonyIsNotConfident() {
            // A wide margin over the runner-up is easy to come by on material
            // that changes chord often at no fixed phase: the margin is real and
            // the phase is still a guess. Scoring confidence from the margin
            // alone reported the ceiling on a quarter of these.
            List<Double> beats = beatsEvery(0.5, 65);
            double worst = 0;
            int overHalf = 0;
            int trials = 0;
            for (int seed = 1; seed <= 8; seed++) {
                Random random = new Random(seed);
                for (int trial = 0; trial < 200; trial++) {
                    double[][] spans = new double[64][12];
                    int pitchClass = 0;
                    for (int span = 0; span < spans.length; span++) {
                        // A change every fourth span on average, landing wherever
                        // it lands rather than on a bar line.
                        if (random.nextInt(4) == 0) {
                            pitchClass = random.nextInt(12);
                        }
                        spans[span][pitchClass] = 1;
                    }
                    double confidence = DownbeatEstimator
                            .estimate(beats, new Chroma(spans, 0), flatEnvelope(40), 4)
                            .confidence().value();
                    worst = Math.max(worst, confidence);
                    trials++;
                    if (confidence >= 0.6) {
                        overHalf++;
                    }
                }
            }

            // Below what the same measure reports for harmony that does line up,
            // asserted just below as the control.
            assertThat(worst).isLessThan(0.8);
            // And rarely anywhere near it. The worst case alone is a weak claim:
            // the calibration that matters is that guesses are reported as
            // guesses in the aggregate, not that no single one slips through.
            assertThat(overHalf).as("%d of %d trials", overHalf, trials)
                    .isLessThan(trials / 100);
            assertThat(DownbeatEstimator
                    .estimate(beats, stepwiseChroma(64, 4, 0), flatEnvelope(40), 4)
                    .confidence().value()).isGreaterThanOrEqualTo(0.8);
        }

        @Test
        @DisplayName("confidence is exactly the product of its factors")
        void confidenceIsTheProductOfItsFactors() {
            // A worked example, pinning the whole model at once rather than one
            // mechanism at a time. Every quantity below is a different fraction,
            // so no two of them can be confused for each other and no constant
            // can be changed without moving the answer.
            //
            // 82 beats score beats 1 to 80, which is exactly twenty of each
            // phase. Two phase-0 beats and one phase-1 beat carry a change, each
            // of novelty 0.5, and nothing else changes at all:
            //
            //     harmony   = [0.050, 0.025, 0, 0]
            //
            //     decided   = margin 0.025 over CONFIDENT_MARGIN 0.1   = 0.250
            //     preferred = share 2/3, against 1/4 by chance         = 5/9
            //     observed  = two equal changes over three             = 2/3
            //     accent    = a level chosen to put tanh exactly on    = 0.500
            //
            //     0.35 + 0.5 * (0.25 * 5/9 * 2/3) + 0.1 * 0.5     = 0.44629...
            //
            // Novelty of 0.5 rather than 1 on purpose: at 1 the sum, the count
            // and the effective count of a phase's changes all coincide, and the
            // effective count is the thing being pinned. Beat 0 is phase 0 and
            // beat 81 is phase 1, so widening the scored range at either end
            // dilutes one of the two phases that matter and moves the answer.
            List<Double> beats = beatsEvery(0.5, 82);
            // Two directions sixty degrees apart, so each change between them is
            // a cosine distance of exactly 0.5, and both are non-negative as a
            // chroma vector has to be.
            double[] held = new double[12];
            held[0] = 1;
            double[] moved = new double[12];
            moved[0] = 0.5;
            moved[5] = Math.sqrt(3) / 2;

            double[][] spans = new double[81][];
            boolean atHeld = true;
            for (int span = 0; span < spans.length; span++) {
                if (span == 4 || span == 8 || span == 13) {
                    atHeld = !atHeld;
                }
                spans[span] = (atHeld ? held : moved).clone();
            }
            // Twenty of the eighty scored beats carry the accent, so a level of
            // a on those and zero elsewhere gives phase 0 an advantage of 3a/4.
            double loud = 2 * Math.log(3) / 3;

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(beats,
                    new Chroma(spans, 0), envelopeOf(beats, new double[] {loud, 0, 0, 0}), 4);

            assertThat(estimate.phase()).isZero();
            assertThat(estimate.confidence().value()).isCloseTo(
                    0.35 + 0.5 * (0.25 * (5.0 / 9) * (2.0 / 3)) + 0.1 * 0.5, within(1e-9));
        }

        @Test
        @DisplayName("faint harmonic evidence is not confident evidence")
        void faintNoveltyIsNotConfident() {
            // Guards the first confidence factor. The bar lines here carry all of
            // the harmonic change there is -- so the phase is unambiguous and the
            // second factor is satisfied in full -- but the change itself is a
            // fiftieth of a cosine distance, well inside what an accent could have
            // moved. Without the margin factor this reports 0.85, the same as a
            // fixture whose chords change outright.
            List<Double> beats = beatsEvery(0.5, 33);

            DownbeatEstimator.Estimate faint = DownbeatEstimator.estimate(
                    beats, faintlyStepwiseChroma(32, 4, 0.98), flatEnvelope(20), 4);

            assertThat(faint.phase()).isZero();
            assertThat(faint.confidence().value()).isLessThan(0.6);
        }

        @Test
        @DisplayName("one chord change is not a pattern, however long the recording")
        void oneChangeIsNotAPattern() {
            // Guards the third confidence factor, and guards it against counting
            // the wrong thing: a single change lands on some phase by necessity,
            // and a long recording holding one chord either side of it is not more
            // evidence than a short one. Counting bars rather than changes gave
            // this 0.85 -- the same as sixteen chord changes that all agree.
            List<Double> beats = beatsEvery(0.5, 65);

            DownbeatEstimator.Estimate once = DownbeatEstimator.estimate(
                    beats, oneChangeChroma(64, 12), flatEnvelope(40), 4);
            DownbeatEstimator.Estimate often = DownbeatEstimator.estimate(
                    beats, stepwiseChroma(64, 4, 0), flatEnvelope(40), 4);

            assertThat(once.phase()).isZero();
            assertThat(once.confidence().value()).isLessThan(0.55);
            assertThat(often.confidence().value()).isGreaterThan(0.8);
        }

        @Test
        @DisplayName("an accent elsewhere in the bar does not undermine the harmony")
        void aConflictingAccentDoesNotReduceConfidence() {
            // A backbeat is ordinary in this material. Once harmony has decided
            // the phase, the fact that the loudest beat is a different one is not
            // evidence against the decision, and letting it subtract would make
            // every backbeat recording report less confidence than the same music
            // played flat.
            List<Double> beats = beatsEvery(0.5, 33);
            Chroma chroma = stepwiseChroma(32, 4, 0);

            DownbeatEstimator.Estimate flat = DownbeatEstimator.estimate(
                    beats, chroma, flatEnvelope(20), 4);
            DownbeatEstimator.Estimate backbeat = DownbeatEstimator.estimate(
                    beats, chroma, envelopeOf(beats, new double[] {0, 0, 5, 0}), 4);

            assertThat(backbeat.phase()).isEqualTo(flat.phase()).isZero();
            assertThat(backbeat.confidence().value())
                    .isCloseTo(flat.confidence().value(), within(1e-12));
        }

        @Test
        @DisplayName("a harmonic lead of exactly the bound still beats a maximal accent")
        void harmonyWinsAtTheBoundary() {
            // tanh reaches exactly 1 in double arithmetic, so at a harmonic lead
            // of exactly 2 * ONSET_WEIGHT the two scores are equal rather than
            // ordered. Taking the earlier phase there would hand an accent a
            // decision the harmony had already made, so ties go to harmony.
            // Ten scorable beats of phase 2, one of them carrying an orthogonal
            // change, put the lead at exactly 0.1.
            List<Double> beats = beatsEvery(0.5, 40);

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(beats,
                    oneChangeChroma(39, 2), envelopeOf(beats, new double[] {1000, -1000, -1000, -1000}), 4);

            assertThat(estimate.phase()).isEqualTo(2);
        }

        @Test
        @DisplayName("an unobserved phase is not credited with the average")
        void unobservedPhasesScoreNoNovelty() {
            // Only one beat has chroma on both sides here, and it is phase 1.
            // Handing the three unobserved phases the overall mean made all four
            // equal and gave the answer to phase 0 -- discarding the only evidence
            // the recording had. No change observed is zero, not average.
            List<Double> beats = List.of(0.0, 0.5, 1.0);
            double[][] spans = new double[2][12];
            spans[0][0] = 1;
            spans[1][5] = 1;

            assertThat(DownbeatEstimator
                    .estimate(beats, new Chroma(spans, 0), flatEnvelope(2), 4).phase())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a ragged chroma is refused evidence rather than read off the end")
        void raggedChromaRowsAreNotNovel() {
            // Chroma does not validate its row shapes, so comparing a twelve-bin
            // vector with a shorter one indexed past the end of the shorter.
            double[][] spans = {new double[12], new double[6], new double[12]};
            spans[0][0] = 1;
            spans[1][0] = 1;
            spans[2][0] = 1;

            double[] novelty = DownbeatEstimator.harmonicNovelty(new Chroma(spans, 0));

            for (double value : novelty) {
                assertThat(value).isZero();
            }
        }

        @Test
        @DisplayName("silence is not a chord change")
        void silentSpansAreNotNovel() {
            // Cosine against a zero vector has no answer. Reading it as zero --
            // orthogonal -- made two silent spans score novelty 1.0, above any
            // real chord change, so a silent passage would decide the phase.
            double[][] spans = new double[8][12];
            for (int span = 0; span < 4; span++) {
                spans[span][0] = 1;
            }

            double[] novelty = DownbeatEstimator.harmonicNovelty(new Chroma(spans, 0));

            for (double value : novelty) {
                assertThat(value).isZero();
            }
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects chroma that does not line up with the beats")
        void rejectsMismatchedChroma() {
            List<Double> beats = List.of(0.0, 0.5, 1.0, 1.5, 2.0);
            OnsetEnvelope envelope = new OnsetEnvelope(new double[300], 100);

            // Silently scoring the wrong spans against the wrong beats would
            // reintroduce an arbitrary phase with no symptom to notice.
            assertThatIllegalArgumentException().isThrownBy(() ->
                    DownbeatEstimator.estimate(beats, new Chroma(new double[7][12], 0),
                            envelope, 4));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    DownbeatEstimator.estimate(beats, new Chroma(new double[4][12], 100),
                            envelope, 4));
        }

        @Test
        @DisplayName("refuses to phase bars with no beats or no meter")
        void rejectsEmptyInput() {
            OnsetEnvelope envelope = new OnsetEnvelope(new double[300], 100);

            assertThatIllegalArgumentException().isThrownBy(() ->
                    DownbeatEstimator.fromOnsets(List.of(), envelope, 4));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    DownbeatEstimator.fromOnsets(List.of(0.0, 0.5), envelope, 0));
        }

        @Test
        @DisplayName("refuses a phase that cannot exist in the bar it names")
        void rejectsImpossiblePhase() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new DownbeatEstimator.Estimate(4, 4, Confidence.CERTAIN));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new DownbeatEstimator.Estimate(-1, 4, Confidence.CERTAIN));
        }
    }

    @Nested
    @DisplayName("beat grid")
    class Grid {

        @Test
        @DisplayName("marks exactly the beats of the estimated phase as downbeats")
        void appliesThePhase() {
            Analysis analysis = Analysis.of(chordsPerBar(16));
            DownbeatEstimator.Estimate estimate = analysis.estimate(4);

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), estimate);

            List<BeatGrid.Beat> beats = grid.beats();
            for (int i = 0; i < beats.size(); i++) {
                int expected = Math.floorMod(i - estimate.phase(), 4);
                assertThat(beats.get(i).positionInBar()).isEqualTo(expected);
                // The model enforces this too, but it is the invariant the whole
                // phase question exists to satisfy.
                assertThat(beats.get(i).downbeat()).isEqualTo(expected == 0);
            }
        }

        @Test
        @DisplayName("never claims more confidence in the bars than in the beats")
        void downbeatConfidenceIsNotInflated() {
            Analysis analysis = Analysis.of(chordsPerBar(16));
            DownbeatEstimator.Estimate estimate = analysis.estimate(4);

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), estimate);

            // The two doubts multiply. A constant factor here -- which is what
            // this was -- reports the same confidence for a phase sixteen chord
            // changes agree on as for one picked out of noise, and cannot fall
            // when the phase evidence does.
            assertThat(grid.downbeatConfidence().value()).isEqualTo(
                    analysis.beats().confidence().value() * estimate.confidence().value());
            assertThat(grid.downbeatConfidence().value())
                    .isLessThan(grid.beatConfidence().value() + 1e-9);
            assertThat(grid.beatConfidence()).isEqualTo(analysis.beats().confidence());
        }

        @Test
        @DisplayName("refuses to build a grid with no beats")
        void rejectsEmptyResult() {
            BeatTracker.Result empty =
                    new BeatTracker.Result(List.of(), 120, Confidence.UNKNOWN);

            assertThatIllegalArgumentException().isThrownBy(() -> BeatTracker.toBeatGrid(empty,
                    new DownbeatEstimator.Estimate(0, 4, Confidence.CERTAIN)));
        }
    }
}
