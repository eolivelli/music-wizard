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
            assertThat(withoutChordChanges.value()).isLessThan(0.6);
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

        /** A flat envelope, so that onset energy cannot break any tie. */
        private static OnsetEnvelope flatEnvelope(double seconds) {
            return new OnsetEnvelope(new double[(int) Math.round(seconds * 100)], 100);
        }

        private static OnsetEnvelope accentedEnvelope(List<Double> beatTimes, int phase) {
            double[] strength = new double[(int) Math.round(
                    (beatTimes.get(beatTimes.size() - 1) + 1) * 100)];
            for (int beat = 0; beat < beatTimes.size(); beat++) {
                strength[(int) Math.round(beatTimes.get(beat) * 100)] =
                        Math.floorMod(beat, 4) == phase ? 10 : 1;
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
                    beats, chroma, accentedEnvelope(beats, 2), 4);

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

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), analysis.estimate(4));

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
