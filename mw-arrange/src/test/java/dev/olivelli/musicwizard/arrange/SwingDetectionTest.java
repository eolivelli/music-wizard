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

package dev.olivelli.musicwizard.arrange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Swing is detected from where onsets sit inside the beat, and then removed
 * before snapping so the score reads straight.
 *
 * <p>The fixtures below play the same eight bars with the off-beat in four
 * different places -- halfway, two thirds of the way, evenly divided in three,
 * and evenly divided in four -- because those four are exactly what the
 * detector has to keep apart.
 */
class SwingDetectionTest {

    private static final double BPM = 120;
    private static final int BARS = 8;

    /** The classic triplet shuffle: the off-beat two thirds through the beat. */
    private static final double SHUFFLE = 2.0 / 3;

    @Nested
    @DisplayName("what counts as a shuffle")
    class Detection {

        @Test
        @DisplayName("a shuffle is found, and its ratio is measured rather than assumed")
        void shuffleIsDetected() {
            SwingFeel swing = Quantizer.quantize(pairs(SHUFFLE, 1)).swing();

            assertThat(swing.swung()).isTrue();
            assertThat(swing.ratio()).isCloseTo(SHUFFLE, within(0.03));
            assertThat(swing.confidence().value()).isGreaterThan(0.3);
            assertThat(swing.displayName()).isEqualTo("swing");
        }

        @Test
        @DisplayName("a lighter shuffle is found too, and measured as lighter")
        void aLightShuffleIsStillASwing() {
            SwingFeel swing = Quantizer.quantize(pairs(0.60, 2)).swing();

            assertThat(swing.swung()).isTrue();
            assertThat(swing.ratio()).isCloseTo(0.60, within(0.03)).isLessThan(SHUFFLE);
        }

        @Test
        @DisplayName("the description follows the measured ratio")
        void feelIsNamedByItsRatio() {
            assertThat(new SwingFeel(true, 0.55, Confidence.CERTAIN).displayName())
                    .isEqualTo("light swing");
            assertThat(new SwingFeel(true, SHUFFLE, Confidence.CERTAIN).displayName())
                    .isEqualTo("swing");
            assertThat(new SwingFeel(true, 0.75, Confidence.CERTAIN).displayName())
                    .isEqualTo("hard swing");
            assertThat(new SwingFeel(true, SHUFFLE, Confidence.CERTAIN).toString())
                    .isEqualTo("swing (67%)");
        }

        @Test
        @DisplayName("straight eighths are not a shuffle")
        void straightEighthsAreStraight() {
            assertThat(Quantizer.quantize(pairs(0.5, 3)).swing()).isEqualTo(SwingFeel.STRAIGHT);
        }

        @Test
        @DisplayName("a run of sixteenths is not a shuffle, however late its mean sits")
        void sixteenthsAreNotASwing() {
            // Onsets at 0.25, 0.5 and 0.75 of the beat average above 0.5, which
            // is why the mean alone would be fooled. Their spread is several
            // times a shuffle's, and that is what rules them out; the figures
            // are on MAX_SPREAD and deliberately nowhere else.
            SwingFeel swing = Quantizer.quantize(divisions(4, 4)).swing();
            assertThat(swing.swung()).isFalse();
        }

        @Test
        @DisplayName("a triplet passage is not a shuffle, and stays triplets")
        void tripletsAreNotASwing() {
            QuantizedScore quantized = Quantizer.quantize(divisions(3, 5));

            assertThat(quantized.swing().swung()).isFalse();
            assertThat(quantized.grids())
                    .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.THIRD_BEAT));
        }

        @Test
        @DisplayName("confidence falls with a thinner sample and with a looser cluster")
        void confidenceReflectsSupportAndTightness() {
            // Three factors decide it and only one of them was pinned. Isolating
            // the other two needs the cluster held still while the sample size
            // changes and vice versa, so the wobble here is a fixed alternating
            // pattern rather than noise: the spread is identical whatever the
            // length, and only the count differs.
            SwingFeel plenty = Quantizer.quantize(wobbled(8, 0.02)).swing();
            SwingFeel few = Quantizer.quantize(wobbled(2, 0.02)).swing();
            SwingFeel loose = Quantizer.quantize(wobbled(8, 0.06)).swing();

            assertThat(plenty.swung()).isTrue();
            assertThat(few.swung()).isTrue();
            assertThat(loose.swung()).isTrue();
            // Eight off-beats of the twenty-four that count as full support, so
            // exactly a third of the same reading seen four times over.
            assertThat(few.confidence().value())
                    .describedAs("thin sample %s against full %s", few, plenty)
                    .isCloseTo(plenty.confidence().value() / 3, within(1e-9));
            assertThat(loose.confidence().value())
                    .describedAs("loose cluster %s against tight %s", loose, plenty)
                    .isLessThan(plenty.confidence().value() * 0.75);
        }

        @Test
        @DisplayName("an onset early in the beat is not part of the off-beat cluster")
        void theWindowStartsLateEnoughToExcludeASixteenth() {
            // A shuffle whose long note is subdivided -- a very ordinary jazz
            // figure -- puts extra onsets a quarter of the way through the beat.
            // Those are not off-beat eighths, and counting them drags the mean
            // below the threshold and loses the feel for the whole piece.
            // Played to the tick: what is under test is where the window's
            // lower edge sits, and human spread would blur the very onsets the
            // edge is there to exclude.
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            Performance performance = new Performance(tempoMap, 22);
            for (int beat = 0; beat < BARS * 4; beat++) {
                performance.exact(60, beat, 0.25);
                performance.exact(60, beat + 0.25, SHUFFLE - 0.25);
                performance.exact(60, beat + SHUFFLE, 1 - SHUFFLE);
            }
            SwingFeel swing = Quantizer.quantize(performance.score()).swing();

            assertThat(swing.swung()).isTrue();
            assertThat(swing.ratio()).isCloseTo(SHUFFLE, within(0.04));
        }

        @Test
        @DisplayName("too little off-beat material to be sure means straight")
        void tooFewOffBeatsToDecide() {
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            Performance performance = new Performance(tempoMap, 6);
            performance.run(60, 1.0, 0, 1, 2, 3, 4, 5, 6, 7);
            // Three shuffled off-beats in eight bars is not evidence of a feel.
            performance.note(60, SHUFFLE, 0.3);
            performance.note(60, 4 + SHUFFLE, 0.3);
            performance.note(60, 6 + SHUFFLE, 0.3);

            assertThat(Quantizer.quantize(performance.score()).swing())
                    .isEqualTo(SwingFeel.STRAIGHT);
        }

        @Test
        @DisplayName("compound time is never asked, because a shuffle is compound time already")
        void compoundMetersAreNotLookedAt() {
            // The commonest rhythm in 6/8 -- a quarter and an eighth to the
            // dotted-quarter beat -- puts its off-beat at exactly two thirds,
            // which is the shuffle signature, with a tighter cluster than any
            // human shuffle. Measured against a straight-time expectation it
            // reads as a 66% swing, and the bar then comes out as duplets under
            // a swing direction. Both are wrong; the meter already says it.
            TempoMap tempoMap = TempoMap.constantPulse(80, TimeSignature.SIX_EIGHT);
            Performance performance = new Performance(tempoMap, 20);
            for (int beat = 0; beat < BARS * 2; beat++) {
                performance.note(60, beat * 1.5, 1.0);
                performance.note(60, beat * 1.5 + 1.0, 0.5);
            }
            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.swing()).isEqualTo(SwingFeel.STRAIGHT);
            assertThat(quantized.grids()).allSatisfy(g -> {
                assertThat(g.resolution()).isEqualTo(GridResolution.THIRD_BEAT);
                assertThat(g.tuplet()).isEmpty();
            });
        }

        @Test
        @DisplayName("a very hard shuffle is straightened, not left to collapse onto the beat")
        void theWidestShuffleTheWindowSeesIsStillStraightened() {
            // Everything the detector's window can see has to be something the
            // correction map can straighten, because refusing one of these does
            // not leave the notes alone -- it leaves them at 0.85 of the beat
            // with nothing to move them, and the grid vote then puts every one
            // on the next downbeat, in unison with the note already there.
            for (double phase : new double[] {0.80, 0.82, 0.85}) {
                QuantizedScore quantized = Quantizer.quantize(pairs(phase, 4));

                assertThat(quantized.swing().swung())
                        .describedAs("off-beat at %s", phase).isTrue();
                // The collapse signature is every bar on the beat grid with the
                // off-beats folded onto the downbeats. No bar may do that, and
                // most come out as plain eighths; a shuffle this wide leaves
                // enough spread that the odd bar reads as triplets instead,
                // which is a reading rather than a collapse.
                assertThat(quantized.grids()).describedAs("off-beat at %s", phase)
                        .noneMatch(g -> g.resolution() == GridResolution.BEAT);
                assertThat(quantized.grids().stream()
                        .filter(g -> g.resolution() == GridResolution.HALF_BEAT).count())
                        .describedAs("off-beat at %s: eighth-grid bars", phase)
                        .isGreaterThanOrEqualTo(quantized.grids().size() - 1);

                // Refusing this material instead put all sixty-four notes on
                // thirty-three downbeats. A few of the widest off-beats still
                // collide -- see #110 -- but the alternation survives.
                List<Double> onsets = quantized.score().tracks().get(0).notes().stream()
                        .map(n -> n.onsetBeat().orElseThrow())
                        .toList();
                assertThat(onsets.stream().distinct().count())
                        .describedAs("off-beat at %s: distinct onsets", phase)
                        .isGreaterThan((long) (onsets.size() * 0.9));
            }
        }

        @Test
        @DisplayName("a swing found in the 4/4 bars is not then applied to the 6/8 ones")
        void compoundBarsAreLeftAloneByASwingFoundElsewhere() {
            // Excluding compound bars from the measurement is only half of it.
            // One verdict is reached for the whole piece, so a score that swings
            // its 4/4 verses and then goes to 6/8 will hand that verdict to the
            // compound bars -- and de-swinging their plain eighths puts them on
            // a duplet grid, which is the defect the exclusion was for.
            TempoMap tempoMap = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                    .withMeterChange(8, TimeSignature.SIX_EIGHT);
            Performance performance = new Performance(tempoMap, 30);
            for (int beat = 0; beat < 32; beat++) {
                performance.note(60, beat, SHUFFLE);
                performance.note(60, beat + SHUFFLE, 1 - SHUFFLE);
            }
            for (int beat = 0; beat < 16; beat++) {
                performance.note(60, 32 + beat * 1.5, 1.0);
                performance.note(60, 32 + beat * 1.5 + 1.0, 0.5);
            }
            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.swing().swung()).isTrue();
            assertThat(quantized.gridAtBar(0).orElseThrow().resolution())
                    .isEqualTo(GridResolution.HALF_BEAT);
            assertThat(quantized.gridAtBar(8).orElseThrow().resolution())
                    .isEqualTo(GridResolution.THIRD_BEAT);
            assertThat(quantized.gridAtBar(8).orElseThrow().tuplet()).isEmpty();
            assertThat(quantized.score().tracks().get(0).notes().stream()
                    .map(n -> n.onsetBeat().orElseThrow())
                    .filter(b -> b >= 32 && b < 35)
                    .distinct())
                    .containsExactly(32.0, 33.0, 33.5, 34.5);
        }

        @Test
        @DisplayName("off-beats with no on-beat cluster are not bimodal, so not a shuffle")
        void aShuffleNeedsBothClusters() {
            // Every onset late in its beat and nothing on the beat at all is a
            // displaced pulse, not a swung one, and reading it as swing would
            // move every note in the piece.
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            Performance performance = new Performance(tempoMap, 31);
            for (int beat = 0; beat < BARS * 4; beat++) {
                performance.note(60, beat + SHUFFLE, 1 - SHUFFLE);
            }
            assertThat(Quantizer.quantize(performance.score()).swing())
                    .isEqualTo(SwingFeel.STRAIGHT);
        }

        @Test
        @DisplayName("an off-beat past the window collapses onto the downbeat, and that is known")
        void pastTheWindowTheFeelIsLostAndTheOffBeatsFold() {
            // Pinned, not endorsed -- see #111. Outside the window no feel is
            // found, and nothing then straightens the material: each off-beat
            // goes to the next downbeat, in unison with the note already there.
            //
            // Played to the tick here so the collapse is exact and the assertion
            // can be sharp, not because a human cannot reach it. A human can,
            // and often: at 0.92 of the beat this project's own 25 ms fixture
            // loses the feel in 46 seeds of 50 at 90 BPM. What binds is the
            // eight-onset minimum rather than the spread, and the figures are on
            // SwingFeel.
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            Performance performance = new Performance(tempoMap, 12);
            for (int beat = 0; beat < BARS * 4; beat++) {
                performance.exact(60, beat, 0.90);
                performance.exact(60, beat + 0.90, 0.10);
            }
            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.swing()).isEqualTo(SwingFeel.STRAIGHT);
            List<Double> onsets = quantized.score().tracks().get(0).notes().stream()
                    .map(n -> n.onsetBeat().orElseThrow())
                    .toList();
            assertThat(onsets.stream().distinct().count()).isLessThan(onsets.size());
        }

        @Test
        @DisplayName("a dotted eighth and sixteenth is read as a hard shuffle, and written straight")
        void threeToOneIsReadAsAHardShuffle() {
            // Pinned rather than endorsed. 3:1 is a notatable figure that the
            // sixteenth grid could have printed exactly, and it comes out as
            // straight eighths under a hard-swing direction instead. For a lead
            // sheet that is the conventional reading -- a hard shuffle is
            // routinely written either way -- but it is a real choice and it is
            // not the literal one.
            QuantizedScore quantized = Quantizer.quantize(pairs(0.75, 5));

            assertThat(quantized.swing().swung()).isTrue();
            assertThat(quantized.swing().displayName()).isEqualTo("hard swing");
            assertThat(quantized.grids())
                    .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.HALF_BEAT));
        }

        @Test
        @DisplayName("confidence falls off towards the threshold, not just with spread")
        void confidenceReflectsHowShuffleLikeTheMaterialIs() {
            SwingFeel marginal = Quantizer.quantize(pairs(0.59, 10)).swing();
            SwingFeel unequivocal = Quantizer.quantize(pairs(2.0 / 3, 10)).swing();

            assertThat(marginal.swung()).isTrue();
            assertThat(unequivocal.swung()).isTrue();
            assertThat(marginal.confidence().value())
                    .isLessThan(unequivocal.confidence().value() / 2);
        }

        @Test
        @DisplayName("detection can be turned off, and then the shuffle is taken literally")
        void detectionCanBeDisabled() {
            QuantizedScore literal = Quantizer.quantize(pairs(SHUFFLE, 7),
                    QuantizationSettings.DEFAULT.withoutSwingDetection());

            assertThat(literal.swing()).isEqualTo(SwingFeel.STRAIGHT);
            assertThat(literal.grids())
                    .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.THIRD_BEAT));
            assertThat(offBeatOnsets(literal))
                    .allSatisfy(b -> assertThat(b % 1.0).isCloseTo(SHUFFLE, within(1e-9)));
        }
    }

    @Nested
    @DisplayName("what is done about it")
    class Deswinging {

        @Test
        @DisplayName("a shuffle is written as straight eighths, not as triplets")
        void shuffledEighthsPrintStraight() {
            QuantizedScore quantized = Quantizer.quantize(pairs(SHUFFLE, 8));

            assertThat(quantized.grids())
                    .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.HALF_BEAT));
            assertThat(offBeatOnsets(quantized))
                    .allSatisfy(b -> assertThat(b % 1.0).isCloseTo(0.5, within(1e-9)));
        }

        @Test
        @DisplayName("a swung note's written length is straight too, not just its onset")
        void offsetsAreDeswungAsWellAsOnsets() {
            // A hard shuffle, played legato: each long note runs almost to the
            // short one that follows it. Straightened, both are eighths.
            // De-swinging only the onset leaves the long note ending at a
            // written 0.8 of the beat, which rounds to a whole beat -- a quarter
            // note where an eighth belongs, overlapping the note after it, and
            // then lengthened again by any reader who obeys the swing direction.
            //
            // Deliberately played to the tick rather than jittered: what is
            // under test is the transform applied to the offset, and the
            // detector that feeds it is exercised by the jittered fixtures above.
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            Performance performance = new Performance(tempoMap, 11);
            for (int beat = 0; beat < BARS * 4; beat++) {
                performance.exact(60, beat, 0.72);
                performance.exact(60, beat + 0.75, 0.20);
            }

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.swing().ratio()).isCloseTo(0.75, within(1e-9));
            assertThat(quantized.score().tracks().get(0).notes())
                    .allSatisfy(n -> assertThat(n.durationBeats()).contains(0.5));
        }

        @Test
        @DisplayName("the performance itself is untouched, so nothing is lost")
        void theSecondsStillSwing() {
            Score before = pairs(SHUFFLE, 9);
            Score after = Quantizer.quantize(before).score();

            List<Note> was = before.tracks().get(0).notes();
            List<Note> now = after.tracks().get(0).notes();
            for (int i = 0; i < was.size(); i++) {
                assertThat(now.get(i).onsetSeconds()).isEqualTo(was.get(i).onsetSeconds());
            }
        }

        @Test
        @DisplayName("the de-swing map is continuous and fixes the ends of the beat")
        void theMapIsWellBehaved() {
            SwingFeel swing = new SwingFeel(true, SHUFFLE, Confidence.CERTAIN);

            assertThat(swing.toWrittenPhase(0.0)).isZero();
            assertThat(swing.toWrittenPhase(SHUFFLE)).isCloseTo(0.5, within(1e-12));
            assertThat(swing.toWrittenPhase(1.0)).isCloseTo(1.0, within(1e-12));
            // Monotone in between, so it cannot reorder two onsets.
            double previous = -1;
            for (double phase = 0; phase <= 1.0; phase += 0.01) {
                double written = swing.toWrittenPhase(phase);
                assertThat(written).isGreaterThan(previous);
                previous = written;
            }
        }

        @Test
        void aStraightFeelIsTheIdentity() {
            assertThat(SwingFeel.STRAIGHT.toWrittenPhase(0.37)).isEqualTo(0.37);
            assertThat(SwingFeel.STRAIGHT.displayName()).isEqualTo("straight");
            assertThat(SwingFeel.STRAIGHT.toString()).isEqualTo("straight");
        }

        @Test
        void rejectsAnImpossibleFeel() {
            assertThatThrownBy(() -> new SwingFeel(true, 0.95, Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SwingFeel(true, 0.4, Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SwingFeel(false, 0.667, Confidence.CERTAIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("straight feel");
            assertThatThrownBy(() -> new SwingFeel(true, 0.667, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Shuffled pairs whose off-beats alternate a fixed distance either side of
     * the shuffle point, so the cluster's spread is exactly {@code wobble}
     * however many bars are played.
     */
    private static Score wobbled(int bars, double wobble) {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, 20);
        for (int beat = 0; beat < bars * 4; beat++) {
            double offBeat = SHUFFLE + (beat % 2 == 0 ? wobble : -wobble);
            performance.exact(60, beat, offBeat);
            performance.exact(60, beat + offBeat, 1 - offBeat);
        }
        return performance.score();
    }

    /** Eight bars of two notes per beat, the second at {@code phase} of the beat. */
    private static Score pairs(double phase, long seed) {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, seed);
        for (int beat = 0; beat < BARS * 4; beat++) {
            performance.note(60, beat, phase);
            performance.note(60, beat + phase, 1 - phase);
        }
        return performance.score();
    }

    /** Eight bars evenly divided {@code n} ways per beat. */
    private static Score divisions(int n, long seed) {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, seed);
        for (int beat = 0; beat < BARS * 4; beat++) {
            for (int i = 0; i < n; i++) {
                performance.note(60, beat + i / (double) n, 1.0 / n);
            }
        }
        return performance.score();
    }

    /** Quantized onsets that are not on a whole beat. */
    private static List<Double> offBeatOnsets(QuantizedScore quantized) {
        return quantized.score().tracks().get(0).notes().stream()
                .map(n -> n.onsetBeat().orElseThrow())
                .filter(b -> b % 1.0 != 0.0)
                .toList();
    }
}
