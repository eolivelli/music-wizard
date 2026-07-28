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
            // is why the mean alone would be fooled. Their spread is twice what
            // any shuffle's is, and that is what rules them out.
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
