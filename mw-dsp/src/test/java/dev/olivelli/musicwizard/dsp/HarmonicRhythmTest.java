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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-0 harmonic rhythm: chroma whose bar length is exact by construction, so
 * that which pulses can bar it is a fact rather than an estimate.
 *
 * <p>The recording that motivated the class is real audio and local-only, so
 * the corpus baselines carry its numbers; what is pinned here is the mechanism
 * on fixtures, including the one property no real recording can assert exactly
 * — that the factor is neutral inside a duple family and hostile outside it.
 */
class HarmonicRhythmTest {

    private static final double FRAME_RATE = 20.0;

    /**
     * Chroma holding one pitch-class profile per bar, cycling through four, at
     * {@code barSeconds} a bar. The harmonic rhythm is the bar by construction.
     */
    private static Chroma barPeriodicChroma(double barSeconds, double totalSeconds) {
        int frames = (int) Math.round(totalSeconds * FRAME_RATE);
        double[][] vectors = new double[frames][12];
        int[] roots = {0, 5, 9, 7};   // C F Am G
        for (int frame = 0; frame < frames; frame++) {
            int bar = (int) (frame / (barSeconds * FRAME_RATE));
            int root = roots[bar % roots.length];
            vectors[frame][root] = 1;
            vectors[frame][(root + 4) % 12] = 0.8;
            vectors[frame][(root + 7) % 12] = 0.9;
        }
        return new Chroma(vectors, FRAME_RATE);
    }

    /** Chroma that never changes: no harmonic rhythm at all. */
    private static Chroma flatChroma(double totalSeconds) {
        int frames = (int) Math.round(totalSeconds * FRAME_RATE);
        double[][] vectors = new double[frames][12];
        for (int frame = 0; frame < frames; frame++) {
            vectors[frame][0] = 1;
            vectors[frame][4] = 0.8;
            vectors[frame][7] = 0.9;
        }
        return new Chroma(vectors, FRAME_RATE);
    }

    @Test
    @DisplayName("a pulse that cannot bar the harmony is floored; the duple family is not")
    void killsThePulseThatCannotBarTheHarmony() {
        // A harmonic rhythm of 3.2s -- chords lasting two 1.6s bars, which is
        // what bossa-cm.mp3's grid mostly does. The quarter at 0.4 spans it in
        // eight, the half at 0.8 in four. Three eighths of the bar is 0.6, and
        // no whole number of 0.6s pulses reaches 3.2s or any multiple of it
        // inside the lags this reads -- their first meeting is at 9.6s. A
        // strictly one-bar harmony would not do here: 0.6 genuinely bars
        // 1.6s-periodic chords, eight pulses to three bars at 4.8s, and the
        // first draft of this fixture proved it by passing the false pulse at
        // 0.95 (#231).
        HarmonicRhythm rhythm = HarmonicRhythm.of(barPeriodicChroma(3.2, 64));

        double quarter = rhythm.supportFor(0.4);
        double half = rhythm.supportFor(0.8);
        double falsePulse = rhythm.supportFor(0.6);

        assertThat(quarter).isGreaterThan(0.7);
        assertThat(half).isGreaterThan(0.7);
        // Neutral within the family: the factor must not choose between a beat
        // and its half. That choice stays with the envelope and the prior.
        assertThat(Math.abs(quarter - half)).isLessThan(0.15);
        assertThat(falsePulse)
                .as("three eighths of the bar, which no whole pulse count bars")
                .isLessThan(0.35)
                .isLessThan(quarter - 0.4);
    }

    @Test
    @DisplayName("a harmony that never changes abstains rather than guessing")
    void flatHarmonyAbstains() {
        HarmonicRhythm rhythm = HarmonicRhythm.of(flatChroma(64));

        // Exactly one, for every candidate: the factor multiplies a score, and
        // abstention has to be the multiplicative identity or it would still be
        // an opinion.
        assertThat(rhythm.supportFor(0.4)).isEqualTo(1.0);
        assertThat(rhythm.supportFor(0.6)).isEqualTo(1.0);
        assertThat(rhythm.supportFor(0.8)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("none() supports every candidate equally")
    void noneIsNeutral() {
        assertThat(HarmonicRhythm.none().supportFor(0.4)).isEqualTo(1.0);
        assertThat(HarmonicRhythm.none().supportFor(123.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("beat-synchronous chroma is refused, because the beats do not exist yet")
    void beatSynchronousChromaIsRefused() {
        Chroma beatSynchronous = barPeriodicChroma(1.6, 64)
                .beatSynchronous(beatsEvery(0.5, 120));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> HarmonicRhythm.of(beatSynchronous))
                .withMessageContaining("beat-synchronous");
    }

    @Test
    @DisplayName("an estimate without a rhythm is exactly the estimate with none()")
    void estimateWithoutRhythmIsEstimateWithNone() {
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(
                new dev.olivelli.musicwizard.audio.AudioBuffer(
                        dev.olivelli.musicwizard.testkit.SignalFactory.clickTrack(
                                120, 20, dev.olivelli.musicwizard.testkit.SignalFactory
                                        .DEFAULT_SAMPLE_RATE),
                        dev.olivelli.musicwizard.testkit.SignalFactory.DEFAULT_SAMPLE_RATE));

        TempoEstimator.Estimate plain = TempoEstimator.estimate(envelope);
        TempoEstimator.Estimate withNone =
                TempoEstimator.estimate(envelope, HarmonicRhythm.none());

        assertThat(plain.beatsPerMinute()).isEqualTo(withNone.beatsPerMinute());
        assertThat(plain.periodicity()).isEqualTo(withNone.periodicity());
        assertThat(plain.peakiness()).isEqualTo(withNone.peakiness());
    }

    @Test
    @DisplayName("the clave's pulse loses to the family that can bar the harmony")
    void theClavePulseLosesToTheFamilyThatBarsTheHarmony() {
        // The #231 mechanism on a synthetic clave. Accents three, three, four,
        // three, three eighths apart -- the strongest periodicity in the
        // envelope is three eighths of the bar, and without the harmonic
        // rhythm the estimator locks onto it, exactly as it did on the real
        // recording. With it, the winner must come from the family that bars
        // the 3.2s harmonic cycle: the quarter at 150 or its half at 75. Which
        // of those two wins is the prior's business and deliberately not
        // pinned.
        double eighth = 0.2;
        List<double[]> clicks = new ArrayList<>();
        for (double barStart = 0; barStart < 64; barStart += 16 * eighth) {
            for (int accent : new int[] {0, 3, 6, 10, 13}) {
                clicks.add(new double[] {barStart + accent * eighth, 0.8});
            }
        }
        float[] audio = clicksWithGains(clicks, 64);
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(
                new dev.olivelli.musicwizard.audio.AudioBuffer(
                        audio, dev.olivelli.musicwizard.testkit.SignalFactory.DEFAULT_SAMPLE_RATE));
        HarmonicRhythm rhythm = HarmonicRhythm.of(barPeriodicChroma(3.2, 64));

        double without = TempoEstimator.estimate(envelope).beatsPerMinute();
        double with = TempoEstimator.estimate(envelope, rhythm).beatsPerMinute();

        assertThat(without)
                .as("without harmony the clave pulse wins, which is the defect")
                .isBetween(95.0, 105.0);
        assertThat(with)
                .as("with harmony the winner must bar the 1.6s cycle")
                .satisfiesAnyOf(
                        bpm -> assertThat(bpm).isBetween(72.0, 78.0),
                        bpm -> assertThat(bpm).isBetween(145.0, 155.0));
    }

    private static List<Double> beatsEvery(double interval, int count) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(i * interval);
        }
        return times;
    }

    /** The click synthesis BeatTrackingTest uses, at given times and gains. */
    private static float[] clicksWithGains(List<double[]> timesAndGains, double seconds) {
        int rate = dev.olivelli.musicwizard.testkit.SignalFactory.DEFAULT_SAMPLE_RATE;
        float[] out = new float[(int) Math.round(seconds * rate)];
        int clickLength = Math.max(1, rate / 100);
        for (double[] click : timesAndGains) {
            int start = (int) Math.round(click[0] * rate);
            for (int i = 0; i < clickLength && start + i < out.length; i++) {
                double decay = Math.exp(-8.0 * i / clickLength);
                out[start + i] +=
                        (float) (click[1] * decay * Math.sin(2 * Math.PI * 1000 * i / rate));
            }
        }
        return out;
    }
}
