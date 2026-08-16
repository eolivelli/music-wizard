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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement #537 is fixed on, on signals whose notes are known exactly:
 * a pitch class the fit turned on to cover the root's own fifth partial, and
 * one that is being played, priced against the root that generates the first.
 */
class NnlsAblationTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /** C=0, C#=1, E=4, A=9. */
    private static final int C_SHARP = 1;
    private static final int A = 9;

    /**
     * Notes with the harmonic series the dictionary models: twelve partials
     * rolling off at {@link NoteDictionary#PARTIAL_ROLL_OFF}.
     *
     * <p>The roll-off is the dictionary's own on purpose. The phantom below is
     * not a signal the model was never told about — source and model agree by
     * construction, and the fit still turns on a note that is not playing.
     */
    private static AudioBuffer notes(int... midiPitches) {
        int length = (int) Math.round(3.0 * RATE);
        float[] out = new float[length];
        for (int midi : midiPitches) {
            double f0 = SignalFactory.midiToHz(midi);
            for (int partial = 1; partial <= 12; partial++) {
                double frequency = f0 * partial;
                if (frequency >= RATE / 2.0) {
                    break;
                }
                double amplitude = 0.3 * Math.pow(NoteDictionary.PARTIAL_ROLL_OFF, partial - 1);
                for (int i = 0; i < length; i++) {
                    out[i] += (float) (amplitude
                            * Math.sin(2 * Math.PI * frequency * i / RATE + 0.37 * partial));
                }
            }
        }
        return new AudioBuffer(out, RATE);
    }

    private static double[] significanceOf(AudioBuffer audio) {
        NnlsAblation ablation = NnlsAblation.extract(NnlsChroma.transform(audio), 0);
        return ablation.significanceOver(0, ablation.spanCount());
    }

    private static double[] meanTreble(AudioBuffer audio) {
        double[] total = new double[12];
        double sum = 0;
        for (double[] frame : NnlsChroma.extract(audio).treble().vectors()) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                total[pitchClass] += frame[pitchClass];
                sum += frame[pitchClass];
            }
        }
        for (int pitchClass = 0; pitchClass < 12 && sum > 0; pitchClass++) {
            total[pitchClass] /= sum;
        }
        return total;
    }

    @Test
    @DisplayName("prices a third the fit invented far below the root that invented it")
    void aPhantomThirdCostsAlmostNothingToDelete() {
        // A1 and A2 and nothing else. Partial 5 of a note is its major third
        // and the only partial below the eighth whose pitch class is outside
        // the triad, so a low root strongly voiced manufactures a C sharp --
        // and the chroma has no way to report that it did.
        AudioBuffer rootOnly = notes(33, 45);

        assertThat(meanTreble(rootOnly)[C_SHARP]).isGreaterThan(0.10);

        // The residual says what the chroma cannot: deleting that pitch class
        // costs a small fraction of what deleting the root costs, because the
        // partial's own note is still there to cover it.
        double[] significance = significanceOf(rootOnly);
        assertThat(significance[C_SHARP]).isLessThan(0.2 * significance[A]);
    }

    @Test
    @DisplayName("prices a third that is being played far above one it invented")
    void aPlayedThirdCostsRealResidual() {
        // The same two roots with a C sharp and an E voiced above them: an A
        // major triad, where the third is now carrying the spectrum rather than
        // standing in for a partial.
        double[] significance = significanceOf(notes(33, 45, 61, 64));

        assertThat(significance[C_SHARP]).isGreaterThan(0.2 * significance[A]);
        assertThat(significance[C_SHARP])
                .isGreaterThan(4 * significanceOf(notes(33, 45))[C_SHARP]);
    }

    @Test
    @DisplayName("says nothing about spans that hold nothing")
    void silenceHasNoSignificantPitchClass() {
        // A perfect fit leaves no residual for a deletion to increase, so the
        // ratio is 0/0. Answered as "no pitch class is carrying anything"
        // rather than as an infinity the quality decision would read as
        // evidence.
        assertThat(significanceOf(new AudioBuffer(SignalFactory.silence(2, RATE), RATE)))
                .containsOnly(0.0);
    }

    @Test
    @DisplayName("folds to the same spans the chroma folds to")
    void beatSynchronousSpansMatchTheChroma() {
        AudioBuffer audio = notes(33, 45, 61, 64);
        List<Double> beats = List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5);
        Spectrogram transform = NnlsChroma.transform(audio);

        NnlsAblation folded = NnlsAblation.extract(transform, 0).beatSynchronous(beats);

        assertThat(folded.spanCount())
                .isEqualTo(NnlsChroma.extract(transform, 0).treble()
                        .beatSynchronous(beats).frameCount())
                .isEqualTo(beats.size() - 1);
        // The whole recording read span by span still finds the played third.
        assertThat(folded.significanceOver(0, folded.spanCount())[C_SHARP])
                .isGreaterThan(0.2 * folded.significanceOver(0, folded.spanCount())[A]);
    }

    @Test
    @DisplayName("refuses a span range it does not cover")
    void rejectsARangeOutsideItself() {
        NnlsAblation folded = NnlsAblation.extract(NnlsChroma.transform(notes(45)), 0)
                .beatSynchronous(List.of(0.0, 0.5, 1.0));

        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> folded.significanceOver(0, 3));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> folded.significanceOver(1, 1));
    }

    @Test
    @DisplayName("refuses a tuning it cannot build a grid at")
    void rejectsANonFiniteTuning() {
        Spectrogram transform = NnlsChroma.transform(notes(45));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> NnlsAblation.extract(transform, Double.NaN))
                .withMessageContaining("tuningOffsetSemitones");
    }
}
