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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /**
     * The same I-V-vi-IV signal with its first two and a half seconds cut off,
     * so that the bar lines no longer fall on the first tracked beat.
     *
     * <p>Every other fixture here starts on a downbeat, which makes phase 0 the
     * right answer everywhere and hides anything that ignores the phase or gets
     * its sign wrong. Trimming a whole number of beats leaves the beats where
     * they were and moves only the bars: the chord changes land at 1.5s, 3.5s
     * and so on, which is beat 3 of the tracked grid.
     */
    private static float[] chordsStartingMidBar(double seconds) {
        float[] full = chordsPerBar(seconds + 2.5);
        return java.util.Arrays.copyOfRange(full, (int) Math.round(2.5 * RATE), full.length);
    }

    /**
     * The same I-V-vi-IV signal with every chord change pushed ahead of the bar
     * line by {@code pushBeats} beats, which is what a pop anticipation does.
     *
     * <p>The clicks stay exactly where they were, so the beats and the bar lines
     * are untouched and only the harmony moves. At a push of one beat the chords
     * change on beats 4, 8, 12 of the recording minus one — beats 3, 7, 11 of the
     * tracked grid — while the bars still start at 0, 4, 8. That is the ground
     * truth this fixture asserts and nothing in the audio records: see
     * {@link Anticipation}.
     *
     * <p>At a push of zero it reproduces {@link #chordsPerBar} sample for sample,
     * which is asserted rather than assumed, so that the pushed fixture is known
     * to differ from the unpushed one in the push alone.
     */
    private static float[] pushedChords(double seconds, int pushBeats) {
        float[] out = SignalFactory.clickTrack(120, seconds, RATE);
        double[][] chords = {
                SignalFactory.majorTriad(60),   // C
                SignalFactory.majorTriad(67),   // G
                SignalFactory.minorTriad(69),   // Am
                SignalFactory.majorTriad(65),   // F
        };
        double interval = 60.0 / 120;
        int beat = 0;
        for (double t = 0; t < seconds; t += interval, beat++) {
            double[] frequencies = chords[Math.floorMod(
                    Math.floorDiv(beat + pushBeats, 4), chords.length)];
            int start = (int) Math.round(t * RATE);
            int noteLength = (int) Math.round(interval * RATE);
            for (double frequency : frequencies) {
                for (int i = 0; i < noteLength && start + i < out.length; i++) {
                    double envelope = 0.7 + 0.3 * Math.exp(-3.0 * i / noteLength);
                    out[start + i] += (float) (0.6 / frequencies.length * envelope
                            * Math.sin(2 * Math.PI * frequency * i / RATE));
                }
            }
        }
        return out;
    }

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

    /** Everything the estimator needs, analysed from one signal. */
    private record Analysis(BeatTracker.Result beats, Chroma chroma, OnsetEnvelope envelope) {
        static Analysis of(float[] samples) {
            AudioBuffer audio = new AudioBuffer(samples, RATE);
            OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);
            BeatTracker.Result beats = BeatTracker.track(envelope);
            Chroma chroma = Chroma.extract(audio).beatSynchronous(beats.beatTimes());
            return new Analysis(beats, chroma, envelope);
        }

        /**
         * The same, over the grid the fixture was built on rather than the one
         * the tracker recovers from it.
         *
         * <p>A tier-1 fixture knows its own beats exactly, and asking a question
         * about the bar lines is not a way to ask one about the beats. It matters
         * here rather than being tidiness: on {@code pushedChords(32, 1)} the
         * tracker inserts a spurious beat at 1.207s, which shifts every index
         * after it by one and lands the phase on 0 — the right answer, arrived at
         * by a beat-tracking error cancelling a downbeat one. See #71.
         */
        static Analysis onExactBeats(float[] samples, double seconds) {
            AudioBuffer audio = new AudioBuffer(samples, RATE);
            OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);
            List<Double> times = new ArrayList<>();
            for (double t = 0; t + 0.5 < seconds; t += 0.5) {
                times.add(t);
            }
            Chroma chroma = Chroma.extract(audio).beatSynchronous(times);
            return new Analysis(
                    new BeatTracker.Result(times, 120, Confidence.CERTAIN), chroma, envelope);
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

            // Against the ceiling harmony can reach rather than against 0.8,
            // which is where this sat until #48: what changed there is not how
            // well the harmony is measured but how much a measurement of harmony
            // is worth as a claim about bar lines, so every number harmony backs
            // moved down together and the contrast this test is about did not.
            assertThat(withChordChanges.value()).isGreaterThan(0.575);
            // The floor exactly for a phase nothing but the accent supports, so
            // it can never read as strongly as one harmony has backed. The
            // weakest evidence reporting the highest confidence is how the
            // original bug stayed invisible. Bounded at 0.45 until #48 took the
            // accent out of the confidence, which was 40% slack on a band 0.25
            // wide -- and 0.45 is not a number the code contains any more.
            assertThat(withoutChordChanges.value()).isEqualTo(0.35)
                    .isLessThan(withChordChanges.value());
        }
    }

    /**
     * The anticipated chord change of #48: a chord that arrives a beat before
     * the bar it belongs to, which is ordinary in pop and near-universal in some
     * Latin idioms.
     *
     * <p>These tests do not pin desirable behaviour. The phase is still the
     * anticipation and these record that it is, because the alternative would be
     * a fixture nobody notices has stopped covering anything. What #48 changed is
     * the second half: the answer is no longer reported as settled.
     */
    @Nested
    @DisplayName("anticipated chord changes")
    class Anticipation {

        @Test
        @DisplayName("differs from the unpushed fixture in the push and nothing else")
        void thePushIsTheOnlyDifference() {
            // The pushed fixture is only evidence about anticipation if it is the
            // unpushed one with the harmony moved. Asserting that at a push of
            // zero it reproduces chordsPerBar sample for sample says so, and
            // catches a generator that drifted away from the one every other test
            // here is calibrated against.
            assertThat(pushedChords(16, 0)).isEqualTo(chordsPerBar(16));
        }

        @Test
        @DisplayName("are still read as the downbeat, because the chroma cannot say otherwise")
        void theAnticipationIsStillTheAnswer() {
            // The bars start at beat 0 and the chords change at beat 3. The
            // estimator answers 3: it is measuring harmonic change, which really
            // is there, and calling it a bar line, which it is not.
            Analysis analysis = Analysis.onExactBeats(pushedChords(32, 1), 32);

            assertThat(analysis.estimate(4).phase()).isEqualTo(3);
        }

        @Test
        @DisplayName("look exactly like a piece whose bars start mid-grid")
        void anAnticipationLooksExactlyLikeAMidBarStart() {
            // Why no reweighing of this evidence can fix #48, rather than an
            // opinion that none can. chordsStartingMidBar is a recording whose
            // bars genuinely start at phase 3; pushedChords is a recording whose
            // bars start at phase 0 with the chords pushed onto phase 3. Ground
            // truth differs. Everything the estimator can see agrees -- same
            // phase, and confidences within a hundredth -- so any rule that moved
            // one onto phase 0 would move the other off the answer it gets right.
            //
            // That is the argument for reporting the phase less confidently
            // rather than differently: the evidence needed to choose is not being
            // weighed wrongly here, it is absent. Adding it is #42.
            DownbeatEstimator.Estimate pushed =
                    Analysis.onExactBeats(pushedChords(28, 1), 28).estimate(4);
            DownbeatEstimator.Estimate midBar =
                    Analysis.onExactBeats(chordsStartingMidBar(28), 28).estimate(4);

            assertThat(pushed.phase()).isEqualTo(midBar.phase()).isEqualTo(3);
            assertThat(pushed.confidence().value())
                    .isCloseTo(midBar.confidence().value(), within(0.01));
        }

        @Test
        @DisplayName("are not reported as settled, however unanimous the harmony is")
        void anAnticipationIsNotReportedAsSettled() {
            // The bug in #48 was not the phase, which cannot be recovered from
            // this evidence, but the 0.85 it came back with: a consumer reading
            // downbeatConfidence could not tell it from an answer that was right,
            // and 0.85 reads as "no need to check this".
            //
            // Sixteen chord changes that all agree, so every factor of the
            // harmonic agreement is satisfied in full and this is the most the
            // scoring can say. It is still not more than the assumption behind
            // the scoring is worth -- and that holds for the unpushed fixture
            // too, necessarily, since the estimator cannot tell them apart.
            Analysis anticipated = Analysis.onExactBeats(pushedChords(32, 1), 32);
            Analysis onTheBar = Analysis.onExactBeats(chordsPerBar(32), 32);

            assertThat(anticipated.estimate(4).confidence().value()).isLessThanOrEqualTo(0.6);
            assertThat(onTheBar.estimate(4).confidence().value()).isLessThanOrEqualTo(0.6);
        }

        @Test
        @DisplayName("read the same with a backbeat under them as without one")
        void theBackbeatUnderAPushDoesNotMoveTheNumber() {
            // The failure that survived capping the confidence, and the reason a
            // cap could not reach it. A push of one beat lands the chord on beat
            // 4, which carries a backbeat, which is the loudest phase this
            // envelope reports (#70) -- so the accent agreed with the anticipation
            // and not with the bar. The pushed reading collected an accent bonus
            // that the correct reading of the same music never could, because the
            // bar line carries a kick and the bonus was floored at zero.
            //
            // A cap on the total did not touch that: it binds only where the
            // harmonic agreement is already unanimous, and real chroma is not --
            // these fixtures measure 0.92 -- so under the cap the pushed reading
            // still came back at 0.600 against the bar line's 0.580.
            //
            // What is asserted is that the accent moves the number by nothing at
            // all, which is the mechanism. An earlier version asserted the
            // outcome instead -- that the pushed reading never exceeds the
            // unpushed one -- and that is not a property this code has, or should
            // claim: the two are indistinguishable, so which is the larger is
            // residual chroma leakage. It came out the right way at 32 seconds
            // and the wrong way at 12, 16, 20, 24, 28, 36 and 40, by between
            // 3e-4 and 5e-3.
            // Whether they are close is asserted, once, in
            // anAnticipationLooksExactlyLikeAMidBarStart; asserting an ordering
            // between them would pin noise and be a fixture length away from red.
            for (double seconds : new double[] {16, 28, 32}) {
                Analysis pushed = Analysis.onExactBeats(pushedChords(seconds, 1), seconds);
                Analysis onTheBar = Analysis.onExactBeats(chordsPerBar(seconds), seconds);
                List<Double> beats = pushed.beats().beatTimes();
                double flatPushed = pushed.estimate(4).confidence().value();
                double flatBarLine = onTheBar.estimate(4).confidence().value();

                for (double backbeat : new double[] {0, 0.01, 0.3, 1, 2, 10, 1000}) {
                    // On phase 3, which is beat 4: the backbeat the push lands on.
                    OnsetEnvelope envelope = envelopeOf(beats, new double[] {0, 0, 0, backbeat});
                    DownbeatEstimator.Estimate anticipated =
                            DownbeatEstimator.estimate(beats, pushed.chroma(), envelope, 4);
                    DownbeatEstimator.Estimate barLine =
                            DownbeatEstimator.estimate(beats, onTheBar.chroma(), envelope, 4);

                    assertThat(anticipated.phase())
                            .as("%ss, backbeat %s", seconds, backbeat).isEqualTo(3);
                    assertThat(anticipated.confidence().value())
                            .as("%ss, backbeat %s", seconds, backbeat)
                            .isCloseTo(flatPushed, within(1e-12));
                    // And the bar line's own reading is not moved by it either,
                    // so the two stay as indistinguishable as they started.
                    assertThat(barLine.confidence().value())
                            .as("%ss, backbeat %s", seconds, backbeat)
                            .isCloseTo(flatBarLine, within(1e-12))
                            .isCloseTo(flatPushed, within(0.01));
                }
            }
        }

        @Test
        @DisplayName("report the same way whatever phase they are pushed onto")
        void everyOffsetIsReportedTheSameWay() {
            // #48 was confirmed on chroma rather than on audio, at two offsets:
            // changes on beats 3, 7, 11 came back as phase 3 at 0.85 and changes
            // on 2, 6, 10 as phase 2 at 0.85. Reproduced here at every offset of
            // every meter the grid supports, because a ceiling that only held for
            // the one offset somebody happened to try would leave the issue open
            // for the rest.
            List<Double> beats = beatsEvery(0.5, 129);

            for (int beatsPerBar = 2; beatsPerBar <= 6; beatsPerBar++) {
                for (int offset = 0; offset < beatsPerBar; offset++) {
                    DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(beats,
                            stepwiseChroma(128, beatsPerBar, offset), flatEnvelope(70),
                            beatsPerBar);

                    assertThat(estimate.phase())
                            .as("%d/4 offset %d", beatsPerBar, offset).isEqualTo(offset);
                    assertThat(estimate.confidence().value())
                            .as("%d/4 offset %d", beatsPerBar, offset)
                            .isLessThanOrEqualTo(0.6);
                }
            }
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
            //
            // The floor exactly, rather than the 0.45 the accent could reach
            // until #48: how loud the loudest phase was is not evidence that it
            // is the bar line, since this envelope's loudest phase is the
            // backbeat (#70), so scaling the number with it said a pronounced
            // guess is a better guess than a faint one. An equality rather than a
            // bound, because the claim is now that the accent does not move the
            // number at all and a bound would not notice if it did.
            for (float[] signal : new float[][] {kickAndSnare(24), accentedOneChord(24),
                    chordsPerBar(32), SignalFactory.clickTrack(120, 20, RATE)}) {
                Analysis analysis = Analysis.of(signal);

                DownbeatEstimator.Estimate estimate = DownbeatEstimator.fromOnsets(
                        analysis.beats().beatTimes(), analysis.envelope(), 4);

                assertThat(estimate.confidence().value()).isEqualTo(0.35);
            }
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring {

        /**
         * The same, but with the two alternating vectors set to a chosen cosine
         * similarity, so a bar line's novelty can be made as faint as wanted.
         */
        private static Chroma faintlyStepwiseChroma(int spans, int perBar, double similarity) {
            return faintlyStepwiseChroma(spans, perBar, similarity, 0);
        }

        /** The same, with the faint changes moved onto a chosen phase. */
        private static Chroma faintlyStepwiseChroma(int spans, int perBar, double similarity,
                                                    int offset) {
            double[] held = new double[12];
            held[0] = 1;
            double[] moved = new double[12];
            moved[0] = similarity;
            moved[1] = Math.sqrt(1 - similarity * similarity);

            double[][] vectors = new double[spans][];
            for (int span = 0; span < spans; span++) {
                vectors[span] = (Math.floorDiv(span - offset, perBar) % 2 == 0 ? held : moved)
                        .clone();
            }
            return new Chroma(vectors, 0);
        }

        /**
         * Chroma whose cosine distance across each beat is exactly what is
         * asked for.
         *
         * <p>Every span is a unit vector in the plane of two pitch classes, at an
         * angle chosen so that consecutive spans sit exactly {@code acos(1 - n)}
         * apart. The direction of each step is whichever keeps the angle inside
         * the quarter turn where both components stay non-negative, since a
         * chroma vector with a negative bin is not a chroma vector.
         *
         * @param noveltyPerBeat the wanted novelty at each beat; index 0 and any
         *     index past the last span are ignored, having a span on one side only
         */
        private static Chroma chromaWithNovelty(double[] noveltyPerBeat, int spans) {
            double[][] vectors = new double[spans][12];
            double angle = Math.PI / 4;
            for (int span = 0; span < spans; span++) {
                if (span > 0 && span < noveltyPerBeat.length && noveltyPerBeat[span] > 0) {
                    double step = Math.acos(Math.clamp(1 - noveltyPerBeat[span], -1, 1));
                    angle += angle + step <= Math.PI / 2 ? step : -step;
                }
                vectors[span][0] = Math.cos(angle);
                vectors[span][5] = Math.sin(angle);
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
        @DisplayName("answers for the meter it was asked about")
        void reportsTheMeterItWasAsked() {
            // The Estimate carries the bar length so that a phase computed for
            // one meter cannot be applied to another, and toBeatGrid reads it
            // back. Nothing asserted that an estimator asked for one meter
            // answers for that meter, so widening the one-beat-bar guard by a
            // single beat -- which hands a caller asking for 2 an Estimate saying
            // 1, and a grid where every beat is a downbeat -- passed everything.
            List<Double> beats = beatsEvery(0.5, 33);
            Chroma chroma = stepwiseChroma(32, 4, 0);

            for (int beatsPerBar = 1; beatsPerBar <= 6; beatsPerBar++) {
                assertThat(DownbeatEstimator
                        .estimate(beats, chroma, flatEnvelope(20), beatsPerBar).beatsPerBar())
                        .isEqualTo(beatsPerBar);
                assertThat(DownbeatEstimator
                        .fromOnsets(beats, flatEnvelope(20), beatsPerBar).beatsPerBar())
                        .isEqualTo(beatsPerBar);
            }
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
        @DisplayName("the accent saturates at the scale it says it does")
        void theAccentSaturatesWhereItSaysItDoes() {
            // ONSET_FULL_SCALE sets how many deviations of accent it takes to
            // reach most of the onset term's bound, so changing it changes how
            // large an accent has to be to overturn a harmonic lead -- and hands
            // a phase to whichever of the two the change happens to favour.
            // Pinned through the phase, because the accent no longer touches the
            // confidence: confidenceIsTheProductOfItsFactors used to pin this
            // constant as a side effect of its worked example, and stopped when
            // #48 took the accent out of that arithmetic. That worked example
            // pinned it in both directions, so both are here.
            //
            // Both leads are set close to where the accent is worth exactly as
            // much as the harmony, so the pair brackets the constant to about a
            // fifth either way rather than to a factor of two. Brackets rather
            // than pins: the only thing this constant is now observable through
            // is which phase wins, which is a threshold, so a change of a few
            // per cent cannot be caught at all. It could be pinned exactly again
            // by putting the accent back into the confidence, and that is the
            // trade #48 made deliberately.
            //
            // Larger scale, accent shrinks. Harmony leads on phase 2 by 0.049,
            // inside the 0.1 an accent may move. An advantage of exactly one
            // deviation on phase 0 and -1/3 elsewhere separates them by
            // 0.05 * (tanh(1) + tanh(1/3)) = 0.0542 at full scale and by
            // 0.05 * (tanh(1/1.2) + tanh(1/3.6)) = 0.0477 at a fifth more --
            // one side of 0.049 and then the other, so the accent stops winning.
            List<Double> beats = beatsEvery(0.5, 34);

            assertThat(DownbeatEstimator.estimate(beats, leadingOnPhaseTwo(0.049),
                    envelopeOf(beats, new double[] {4.0 / 3, 0, 0, 0}), 4).phase()).isZero();

            // Smaller scale, accent grows, and the failure is the mirror image:
            // an accent that should not be enough becomes enough. A lead of
            // 0.028 against an advantage of 0.4 gives
            // 0.05 * (tanh(0.4) + tanh(2/15)) = 0.0256 at full scale and
            // 0.05 * (tanh(0.5) + tanh(1/6)) = 0.0314 at a fifth less, so the
            // accent starts taking a phase the harmony should have kept.
            assertThat(DownbeatEstimator.estimate(beats, leadingOnPhaseTwo(0.028),
                    envelopeOf(beats, new double[] {0.4 / 0.75, 0, 0, 0}), 4).phase())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the accent is measured over the beats the harmony was measured over")
        void theAccentIsScoredOverTheSameBeatsAsTheHarmony() {
            // Novelty is undefined at the first and last beat, so the harmony is
            // scored over the beats between them and the accent has to be scored
            // over exactly those too -- stated in estimate() and, like the scale
            // above, pinned until #48 only as a side effect of a worked example
            // that no longer involves the accent.
            //
            // One enormous onset on a beat no phase's harmony can see, and
            // nothing anywhere else, so that without it every advantage is zero
            // and the harmony decides alone. Reading that beat gives its phase a
            // mean far above the others and an accent that takes the answer.
            //
            // Both ends, and the winner is phase 2 rather than phase 0, which is
            // what makes the head of the range observable at all: beat 0 is a
            // phase-0 beat, so an outlier there lands inside phase 0's own mean.
            // A fixture whose winner is phase 0 is helped by reading it and
            // cannot tell that it was read -- which is how the first version of
            // this test passed while every range mutation survived.
            List<Double> beats = beatsEvery(0.5, 34);

            for (int loud : new int[] {0, 33}) {
                assertThat(DownbeatEstimator.estimate(beats, leadingOnPhaseTwo(0.04),
                        oneLoudBeat(beats, loud), 4).phase())
                        .as("outlier on beat %d", loud).isEqualTo(2);
            }
        }

        /**
         * A flat envelope with one beat struck a hundred deviations hard.
         *
         * <p>Every other beat sits at zero rather than at the fill below it, so
         * that widening the scored range is the only thing that can move an
         * advantage.
         */
        private static OnsetEnvelope oneLoudBeat(List<Double> beatTimes, int loudBeat) {
            double[] strength = new double[1800];
            java.util.Arrays.fill(strength, -0.2);
            for (double time : beatTimes) {
                strength[(int) Math.round(time * 100)] = 0;
            }
            strength[(int) Math.round(beatTimes.get(loudBeat) * 100)] = 100;
            return new OnsetEnvelope(strength, 100);
        }

        /** Chroma over 34 beats whose only novelty is a lead on every phase-2 beat. */
        private static Chroma leadingOnPhaseTwo(double lead) {
            double[] novelty = new double[34];
            for (int beat = 2; beat <= 30; beat += 4) {
                novelty[beat] = lead;
            }
            return chromaWithNovelty(novelty, 33);
        }

        @Test
        @DisplayName("harmony alone reaches its ceiling and stops there")
        void harmonyAloneStopsAtItsCeiling() {
            // The ceiling is the reliability of "harmony moves at the bar line",
            // not the weight of the harmony, so the best evidence the scoring can
            // be given has to land exactly on it -- not below, or the ceiling is
            // unreachable and means nothing, and not above, or #48 is back.
            //
            // Orthogonal chord changes on every bar line and nothing anywhere
            // else, over a flat envelope, satisfy all three factors in full.
            List<Double> beats = beatsEvery(0.5, 65);

            assertThat(DownbeatEstimator
                    .estimate(beats, stepwiseChroma(64, 4, 0), flatEnvelope(40), 4)
                    .confidence().value()).isCloseTo(0.6, within(1e-9));
        }

        @Test
        @DisplayName("nothing harmonic passes the ceiling, at any meter or phase")
        void nothingHarmonicPassesTheCeiling() {
            // The ceiling on one fixture is a number; the ceiling as a property
            // is what #48 needs, since the anticipation it is there for can land
            // on any phase of any meter. Swept over the meters the grid supports,
            // every phase within each, and harmonic rhythms from one change a bar
            // to one every four, with the accent pushed as hard onto the winning
            // phase as tanh will carry it.
            //
            // Half the sweep holds a chord that changes outright and half one that
            // barely moves, because an orthogonal change alone puts the harmonic
            // agreement at exactly 1, where the number is at the ceiling however
            // the accent is set -- so a sweep of only those cases would report a
            // ceiling that held and never test what the accent does under it,
            // which is where the #48 inversion actually lived.
            List<Double> beats = beatsEvery(0.5, 129);
            double worst = 0;
            double leastWithHeadroom = 1;
            for (int beatsPerBar = 2; beatsPerBar <= 6; beatsPerBar++) {
                for (int offset = 0; offset < beatsPerBar; offset++) {
                    for (int barsPerChord = 1; barsPerChord <= 4; barsPerChord++) {
                        for (double similarity : new double[] {0, 0.98}) {
                            int perBar = beatsPerBar * barsPerChord;
                            Chroma chroma = similarity == 0
                                    ? stepwiseChroma(128, perBar, offset)
                                    : faintlyStepwiseChroma(128, perBar, similarity, offset);
                            double[] levels = new double[beatsPerBar];
                            levels[offset] = 40;
                            double confidence = DownbeatEstimator
                                    .estimate(beats, chroma, envelopeOf(beats, levels),
                                            beatsPerBar)
                                    .confidence().value();
                            worst = Math.max(worst, confidence);
                            if (similarity != 0) {
                                leastWithHeadroom = Math.min(leastWithHeadroom, confidence);
                            }
                        }
                    }
                }
            }
            // Some case in the sweep really does sit below the ceiling, so the
            // bound below is a bound and not an identity.
            assertThat(leastWithHeadroom).isLessThan(0.55);

            // Exactly the ceiling and not a thousandth over it, at the hardest
            // input the sweep can build. The accent is saturated on the winning
            // phase throughout, so before #48 capped the total this came back at
            // 0.7 -- the onset bonus riding on top of a ceiling it was supposed to
            // sit under.
            assertThat(worst).isCloseTo(0.6, within(1e-9));
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
                    // Half way from the floor a phase nothing supports reports to
                    // the ceiling harmony can reach. It was 0.6 until #48 halved
                    // the span between those two; 0.475 is the same fraction of
                    // the new one, so the population being counted is unchanged.
                    if (confidence >= 0.475) {
                        overHalf++;
                    }
                }
            }

            // Below what the same measure reports for harmony that does line up,
            // asserted just below as the control. 0.575 rather than the 0.6 the
            // control reaches: 0.6 is the top of the band, so bounding by it would
            // assert only that a confidence is not at the theoretical maximum,
            // where the old 0.8 was 90% of the old band. 0.575 is that 90% of the
            // new one, and the measured worst is 0.533.
            assertThat(worst).isLessThan(0.575);
            // And rarely anywhere near it. The worst case alone is a weak claim:
            // the calibration that matters is that guesses are reported as
            // guesses in the aggregate, not that no single one slips through.
            assertThat(overHalf).as("%d of %d trials", overHalf, trials)
                    .isLessThan(trials / 100);
            assertThat(DownbeatEstimator
                    .estimate(beats, stepwiseChroma(64, 4, 0), flatEnvelope(40), 4)
                    .confidence().value()).isGreaterThanOrEqualTo(0.6);
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
            //
            //     0.35 + 0.25 * (0.25 * 5/9 * 2/3)                = 0.37314...
            //
            // The 0.25 in front of the harmonic agreement is the span from the
            // floor to the ceiling, which #48 halved from 0.5.
            //
            // The accent is still in the envelope, at the level that used to put
            // its tanh at exactly 0.5 and add 0.05 to this number. It is here to
            // be ignored: the accent is not in the confidence any more, so the
            // fixture that would show it if it were is the one worth keeping.
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
                    0.35 + 0.25 * (0.25 * (5.0 / 9) * (2.0 / 3)), within(1e-9));
            // And the same number without the accent, so that "the accent is not
            // in this" is asserted rather than implied by an equality that would
            // also hold if the constant behind it were zero for a different reason.
            assertThat(DownbeatEstimator
                    .estimate(beats, new Chroma(spans, 0), flatEnvelope(45), 4)
                    .confidence().value()).isEqualTo(estimate.confidence().value());
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
            // 0.6 until #48, which is now the ceiling itself and would leave this
            // asserting only that a confidence is a confidence. Half way from the
            // floor to that ceiling is the same claim the 0.6 used to make.
            assertThat(faint.confidence().value()).isLessThan(0.475);
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
            // 0.55 and 0.8 until #48 halved the span between the floor and the
            // ceiling; both moved by that halving and neither by anything else.
            assertThat(once.confidence().value()).isLessThan(0.45);
            assertThat(often.confidence().value()).isGreaterThanOrEqualTo(0.6);
        }

        @Test
        @DisplayName("no accent anywhere in the bar moves the confidence, either way")
        void theAccentIsNotInTheConfidence() {
            // Both halves of the same claim, and the second half is what #48
            // ended up needing. A backbeat is ordinary in this material, so an
            // accent on a beat harmony did not pick must not subtract -- that
            // would make every backbeat recording report less than the same music
            // played flat. And an accent on the beat harmony did pick must not
            // add, because it is the same observation twice over (the accent
            // already chose the phase, in the score) and because the phase it
            // sits on is the backbeat, which is where an anticipation lands.
            //
            // Only the first half was asserted before, so halving the harmonic
            // span and leaving the accent bonus alone was invisible here -- and
            // that bonus is what let the pushed reading of a fixture outrank the
            // bar line it was pushed off.
            //
            // Partial agreement deliberately: at unanimous agreement the number
            // is at the ceiling and a bonus could not show even if there were
            // one. A novelty floor of 0.1 under bar lines of 0.4 puts the share
            // factor well below one while leaving a margin of 0.3 -- three times
            // what an accent can move -- so the accent cannot take the phase and
            // the test stays about the confidence.
            List<Double> beats = beatsEvery(0.5, 65);
            double[] novelty = new double[65];
            for (int beat = 1; beat < 64; beat++) {
                novelty[beat] = beat % 4 == 0 ? 0.4 : 0.1;
            }
            Chroma weaklyAligned = chromaWithNovelty(novelty, 64);

            DownbeatEstimator.Estimate flat = DownbeatEstimator.estimate(
                    beats, weaklyAligned, flatEnvelope(40), 4);
            DownbeatEstimator.Estimate backbeat = DownbeatEstimator.estimate(
                    beats, weaklyAligned, envelopeOf(beats, new double[] {0, 0, 5, 0}), 4);
            DownbeatEstimator.Estimate onTheChosenPhase = DownbeatEstimator.estimate(
                    beats, weaklyAligned, envelopeOf(beats, new double[] {5, 0, 0, 0}), 4);

            assertThat(flat.confidence().value()).isStrictlyBetween(0.35, 0.6);
            assertThat(backbeat.phase()).isEqualTo(flat.phase()).isZero();
            assertThat(onTheChosenPhase.phase()).isZero();
            assertThat(backbeat.confidence().value())
                    .isCloseTo(flat.confidence().value(), within(1e-12));
            assertThat(onTheChosenPhase.confidence().value())
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
        @DisplayName("a ragged chroma cannot reach the novelty measure at all")
        void raggedChromaRowsAreNotNovel() {
            // This used to assert that harmonicNovelty scored a ragged chroma as
            // zero rather than indexing off the end of the shorter row. Since #77
            // the shape is a constructor invariant of Chroma, so the ragged case
            // is refused a layer earlier and the guard inside the novelty measure
            // is unreachable through it. The property is the same one -- nothing
            // reads past the end of a chroma vector -- asserted where it is now
            // enforced rather than where it used to be survived.
            double[][] spans = {new double[12], new double[6], new double[12]};

            assertThatThrownBy(() -> new Chroma(spans, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vectors[1]")
                    .hasMessageContaining("twelve pitch classes");
        }

        @Test
        @DisplayName("a phase with no beats does not win on evidence it never gave")
        void unobservedPhaseDoesNotWinOnOnsets() {
            // Three beats at 4/4 leave phase 3 unobserved. The onset envelope is
            // centred on zero, so scoring an unobserved phase at zero rather than
            // at the average makes it look like the loudest phase in the bar
            // whenever the observed beats happen to sit below zero -- and it then
            // wins, on nothing.
            double[] strength = new double[400];
            java.util.Arrays.fill(strength, -0.5);

            assertThat(DownbeatEstimator.fromOnsets(List.of(0.0, 0.5, 1.0),
                    new OnsetEnvelope(strength, 100), 4).phase()).isZero();
        }

        @Test
        @DisplayName("survives an envelope with no frames in it at all")
        void emptyEnvelopeIsNotAnIndexError() {
            // frameOf clamps to frame 0, which does not exist in an empty
            // envelope, so sampling it reads off the end of the array.
            assertThat(DownbeatEstimator.fromOnsets(List.of(0.0, 0.5, 1.0, 1.5),
                    new OnsetEnvelope(new double[0], 100), 4).confidence().value())
                    .isEqualTo(0.35);
        }

        @Test
        @DisplayName("a silent recording is uncertain, not certainly wrong")
        void silentChromaIsMerelyUncertain() {
            // Every span empty means no novelty anywhere, so the effective change
            // count divides zero by zero. Left as NaN it propagates through every
            // clamp and comes out as a confidence of zero -- which claims
            // certainty that the phase is wrong rather than admitting ignorance.
            List<Double> beats = beatsEvery(0.5, 17);

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(
                    beats, new Chroma(new double[16][12], 0), flatEnvelope(10), 4);

            assertThat(estimate.confidence().value()).isEqualTo(0.35);
            assertThat(estimate.phase()).isZero();
        }

        @Test
        @DisplayName("a one-beat bar has no phase to get wrong")
        void oneBeatBars() {
            // Degenerate but reachable through the meter, and the case the whole
            // scoring apparatus has no question to answer for.
            //
            // The novelty is deliberately faint: the phase is certain no matter
            // how weak the harmonic evidence for it is, since there is no other
            // phase for the evidence to be weighed against.
            List<Double> beats = beatsEvery(0.5, 17);
            double[] novelty = new double[17];
            for (int beat = 4; beat < 15; beat += 4) {
                novelty[beat] = 0.1;
            }

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(
                    beats, chromaWithNovelty(novelty, 16), flatEnvelope(10), 1);

            assertThat(estimate.phase()).isZero();
            assertThat(estimate.confidence()).isEqualTo(Confidence.CERTAIN);
        }

        @Test
        @DisplayName("a one-beat bar is certain whatever the harmony does")
        void oneBeatBarsAreCertainOfTheirPhase() {
            // The scoring apparatus is entirely about choosing between phases.
            // At a one-beat bar there is nothing to choose: every beat begins a
            // bar, phase 0 is the only phase there is, and it is right whatever
            // the recording contains. Deriving that through measures of how well
            // the evidence discriminates made it depend on how much harmonic
            // change a recording with no choice to make happened to have -- an
            // unchanging chord and silence came out half a point apart, on a
            // question neither of them bears on.
            //
            // Certain, not merely high: a phase that cannot be wrong must not
            // report less than a 4/4 phase that can, which is the one thing
            // about these numbers a caller should be able to rely on.
            List<Double> beats = beatsEvery(0.5, 17);

            for (Chroma chroma : new Chroma[] {
                    chromaWithNovelty(new double[17], 16),   // one chord held
                    new Chroma(new double[16][12], 0),       // silence
                    stepwiseChroma(16, 4, 0)}) {             // chords every bar
                assertThat(DownbeatEstimator.estimate(beats, chroma, flatEnvelope(10), 1)
                        .confidence()).isEqualTo(Confidence.CERTAIN);
            }
            // Answered before any evidence is gathered, so it holds for inputs
            // there is nothing to gather from, and on the onset path -- which
            // used to report the floor here, since the answer lived inside the
            // harmonic scoring that fromOnsets never runs.
            assertThat(DownbeatEstimator.estimate(List.of(0.0, 0.5),
                    new Chroma(new double[1][12], 0), flatEnvelope(2), 1).confidence())
                    .isEqualTo(Confidence.CERTAIN);
            assertThat(DownbeatEstimator.fromOnsets(beats, flatEnvelope(10), 1).confidence())
                    .isEqualTo(Confidence.CERTAIN);
        }

        @Test
        @DisplayName("a span that was observed and did not change beats one that was not observed")
        void unchangedBeatsUnobserved() {
            // Silence routes through the undefined-cosine branch to exactly zero.
            // A held chord next to itself lands a hair BELOW zero, because a
            // cosine of two numerically parallel vectors can round above one --
            // for a triad of unit components, exactly -2.22e-16. Unfloored, the
            // phase whose beats sit beside silence therefore outranks the phase
            // that was observed and did not change, and so does a phase with no
            // beats in range at all: absence of evidence outranks presence of it.
            //
            // This is the one place the floor is observable. It is not much of a
            // margin, but it decides the phase, and it is why the floor belongs
            // where the sign is decided rather than as defensive checks further
            // down that would each look unnecessary alone.
            double[] triad = new double[12];
            triad[0] = 1;
            triad[4] = 1;
            triad[7] = 1;
            double[][] spans = new double[20][];
            for (int span = 0; span < spans.length; span++) {
                spans[span] = span % 5 == 4 ? new double[12] : triad.clone();
            }
            List<Double> beats = beatsEvery(0.5, 21);

            for (int beatsPerBar : new int[] {2, 3, 4}) {
                assertThat(DownbeatEstimator
                        .estimate(beats, new Chroma(spans, 0), flatEnvelope(12), beatsPerBar)
                        .phase()).as("%d beats per bar", beatsPerBar).isZero();
            }
        }

        @Test
        @DisplayName("harmony that disagrees with the answer is no credit, not a penalty")
        void harmonyAgainstTheAnswerIsNotNegative() {
            // When an accent overrides the harmony, the winning phase's harmonic
            // margin is negative. That is worth nothing, and nothing is where it
            // stops: letting it count as a penalty would push a phase below the
            // floor that says "this is a guess", claiming the answer is worse
            // than a guess when what is true is that harmony did not pick it.
            //
            // Two fixtures whose harmony disagrees with the accent by different
            // amounts -- both inside the margin an accent can move, or harmony
            // would simply win. Both are decided by the accent, so both must
            // report the same confidence; a penalty would scale with the
            // disagreement and separate them.
            List<Double> beats = beatsEvery(0.5, 33);
            OnsetEnvelope accented = envelopeOf(beats, new double[] {0, 0, 8, 0});

            DownbeatEstimator.Estimate nearMiss = DownbeatEstimator.estimate(
                    beats, chromaWithNovelty(noveltyOn(0.24, 0.20), 32), accented, 4);
            DownbeatEstimator.Estimate wideMiss = DownbeatEstimator.estimate(
                    beats, chromaWithNovelty(noveltyOn(0.24, 0.16), 32), accented, 4);

            assertThat(nearMiss.phase()).isEqualTo(2);
            assertThat(wideMiss.phase()).isEqualTo(2);
            assertThat(nearMiss.confidence().value())
                    .isGreaterThanOrEqualTo(0.35)
                    .isEqualTo(wideMiss.confidence().value());
        }

        /** Novelty of one size on phase 0's beats and another on phase 2's. */
        private static double[] noveltyOn(double onPhaseZero, double onPhaseTwo) {
            double[] novelty = new double[33];
            for (int beat = 4; beat <= 28; beat += 4) {
                novelty[beat] = onPhaseZero;
            }
            for (int beat = 2; beat <= 30; beat += 4) {
                novelty[beat] = onPhaseTwo;
            }
            return novelty;
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
        @DisplayName("falls back rather than rejecting a chroma with no frames at all")
        void emptyChromaFallsBackToOnsets() {
            // The regression gate for a crash that reached the CLI twice: once
            // as Math.clamp with crossed bounds inside Chroma.beatSynchronous,
            // and again -- after that was guarded -- as this class rejecting the
            // empty chroma the guard now hands it.
            //
            // Beats without chroma is a state the pipeline can genuinely reach.
            // Since #3 the analysis window is 8192 samples, so a recording
            // shorter than about 0.37 s yields no frames while BeatTracker still
            // finds pulses in it. Three beats, because the too-few-beats
            // fallback above already catches two and it takes three to reach
            // this branch -- which is exactly what the first test written for
            // this bug got wrong, having been written from a stack trace whose
            // clip happened to yield two.
            //
            // Here rather than only in EndToEndIT because that test is an *IT
            // and runs under -Pintegration alone. A guard against a
            // CLI-reachable crash whose only cover is a job someone has to
            // remember to run is one refactor away from coming back, and on this
            // branch it has already come back once.
            List<Double> beats = List.of(0.0, 0.5, 1.0, 1.5);
            OnsetEnvelope envelope = new OnsetEnvelope(new double[300], 100);

            DownbeatEstimator.Estimate estimate = DownbeatEstimator.estimate(
                    beats, new Chroma(new double[0][], 100), envelope, 4);

            assertThat(estimate.beatsPerBar()).isEqualTo(4);
            assertThat(estimate.phase()).isBetween(0, 3);
            // The onset-only floor, unscaled: no harmony was seen, so none is
            // claimed. Identical to what fromOnsets gives for the same input,
            // which is the point -- the fallback is the existing answer for "no
            // harmonic evidence", not a new one invented for this case.
            assertThat(estimate.confidence().value())
                    .isEqualTo(DownbeatEstimator.fromOnsets(beats, envelope, 4)
                            .confidence().value());

            // And a beat-synchronous empty chroma, which is the other shape the
            // fold can produce, takes the same branch rather than the validator.
            assertThat(DownbeatEstimator.estimate(
                    beats, new Chroma(new double[0][], 0), envelope, 4).beatsPerBar())
                    .isEqualTo(4);
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
        @DisplayName("marks the phase it was given, not the phase it would prefer")
        void appliesANonZeroPhase() {
            // The one line that turns an estimated phase into bar positions,
            // tested where it can be seen. Every audio fixture that reaches a
            // grid estimates phase 0, and at phase 0 floorMod(i - phase, 4)
            // degenerates to i % 4 -- so discarding the phase, or getting its
            // sign wrong, left every grid test passing. A wrong sign here is
            // literally the symptom issue #27 reported.
            //
            // Phase 1 and phase 3 rather than 2: at phase 2 in 4/4 the sign
            // cannot be seen either, since -2 and +2 agree modulo 4.
            BeatTracker.Result result = new BeatTracker.Result(
                    List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5), 120, Confidence.CERTAIN);

            BeatGrid phaseOne = BeatTracker.toBeatGrid(result,
                    new DownbeatEstimator.Estimate(1, 4, Confidence.CERTAIN));
            BeatGrid phaseThree = BeatTracker.toBeatGrid(result,
                    new DownbeatEstimator.Estimate(3, 4, Confidence.CERTAIN));

            assertThat(phaseOne.beats()).extracting(BeatGrid.Beat::positionInBar)
                    .containsExactly(3, 0, 1, 2, 3, 0, 1, 2);
            assertThat(phaseOne.downbeatTimes()).containsExactly(0.5, 2.5);
            assertThat(phaseThree.beats()).extracting(BeatGrid.Beat::positionInBar)
                    .containsExactly(1, 2, 3, 0, 1, 2, 3, 0);
            assertThat(phaseThree.downbeatTimes()).containsExactly(1.5, 3.5);
        }

        @Test
        @DisplayName("bars are as long as the estimate says, not as long as 4/4")
        void usesTheMeterTheEstimateCarries() {
            // The bar length travels inside the Estimate so that a phase computed
            // for one meter cannot be applied to another. Nothing here infers a
            // meter -- 4/4 remains the prior, and this is the caller's meter
            // being honoured rather than guessed -- but hard-coding four in the
            // one place that reads it left the whole suite green.
            BeatTracker.Result result = new BeatTracker.Result(
                    List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5), 120, Confidence.CERTAIN);

            BeatGrid grid = BeatTracker.toBeatGrid(result,
                    new DownbeatEstimator.Estimate(0, 3, Confidence.CERTAIN));

            assertThat(grid.beats()).extracting(BeatGrid.Beat::positionInBar)
                    .containsExactly(0, 1, 2, 0, 1, 2, 0, 1);
            assertThat(grid.downbeatTimes()).containsExactly(0.0, 1.5, 3.0);
        }

        @Test
        @DisplayName("puts the bar lines mid-grid when that is where the chords change")
        void barLinesNeedNotStartAtTheFirstBeat() {
            // The same, reached through the whole stage rather than by handing
            // toBeatGrid a phase. The chords change at 1.5s, 3.5s and so on here,
            // which is beat 3, so a grid that ignores the phase cannot pass.
            Analysis analysis = Analysis.of(chordsStartingMidBar(28));

            BeatGrid grid = BeatTracker.toBeatGrid(analysis.beats(), analysis.estimate(4));

            assertThat(grid.beats().get(3).downbeat()).isTrue();
            assertThat(grid.beats().get(0).downbeat()).isFalse();
            for (double downbeat : grid.downbeatTimes()) {
                double nearestBarLine = 1.5 + Math.round((downbeat - 1.5) / 2.0) * 2.0;
                assertThat(Math.abs(downbeat - nearestBarLine)).isLessThan(0.06);
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
            OnsetEnvelope envelope = new OnsetEnvelope(new double[100], 100);

            assertThatIllegalArgumentException().isThrownBy(() -> BeatTracker.toBeatGrid(empty,
                    new DownbeatEstimator.Estimate(0, 4, Confidence.CERTAIN)));
            // Both overloads, and both saying what the caller asked for rather
            // than what a stage they did not call happens to complain about:
            // without its own check the onset overload reports a downbeat phase
            // it was never asked to estimate.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BeatTracker.toBeatGrid(empty, envelope, 4))
                    .withMessageContaining("beat grid");
        }
    }
}
