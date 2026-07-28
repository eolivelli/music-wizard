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

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the articulation allowance is worth, measured rather than asserted.
 *
 * <p>The allowance exists because players release early, so a note snapped
 * straight to the nearest grid position loses a step. It is bounded to one step
 * and suppressed when the plain rounding already explains the length. Both of
 * those are trade-offs, and a number in a javadoc describing a trade-off goes
 * stale silently -- an earlier set of figures here did.
 *
 * <p>So the comparison runs. Notes of one to sixteen grid steps, on a sixteenth
 * grid at 120 BPM, released according to three articulation distributions, and
 * a duration counts only when it comes back exactly right.
 *
 * <p>What it says, at the time of writing: 0.643 with the allowance against
 * 0.561 without, averaged over the three styles. The gain is all in detached
 * playing, 0.661 against 0.454; a player who holds through instead pays about
 * half a point, 0.760 against 0.766. That is the trade, and it is a trade --
 * not a free improvement, and not the only reading of the data.
 */
class ArticulationAllowanceTest {

    private static final double BPM = 120;
    private static final double STEP = 0.25;
    private static final int[] LENGTHS = {1, 2, 3, 4, 6, 8, 12, 16};
    private static final int NOTES_PER_CASE = 400;

    /** How much of its written length a note is held for, in three playing styles. */
    private enum Articulation {
        DETACHED(0.80, 0.98),
        MOSTLY_LEGATO(0.88, 1.05),
        WIDE(0.70, 1.10);

        final double low;
        final double high;

        Articulation(double low, double high) {
            this.low = low;
            this.high = high;
        }
    }

    @Test
    @DisplayName("the allowance recovers durations that snapping alone loses")
    void theAllowanceIsWorthHaving() {
        double with = recovery(QuantizationSettings.DEFAULT);
        double without = recovery(QuantizationSettings.DEFAULT.withArticulationRatio(1.0));

        // Measured 0.643 against 0.561 across the three styles. Gated below
        // that so a regression trips it rather than a tweak.
        assertThat(with)
                .describedAs("with the allowance %.4f, without %.4f", with, without)
                .isGreaterThan(without)
                .isGreaterThan(0.60);
        assertThat(with - without).isGreaterThan(0.05);
    }

    @Test
    @DisplayName("what the allowance costs the players it was not written for")
    void whatItCostsALegatoPlayer() {
        // The allowance is aimed at a player who releases early. One who holds
        // through instead is the case it can only get wrong, and this is what
        // that costs: measured 0.760 with against 0.766 without, so about half a
        // point, against twenty points gained on a detached player. Asserted as
        // a bound rather than a direction, because the sign is not the point --
        // the point is that it stays small.
        double legatoWith = recovery(QuantizationSettings.DEFAULT, Articulation.MOSTLY_LEGATO);
        double legatoWithout = recovery(
                QuantizationSettings.DEFAULT.withArticulationRatio(1.0),
                Articulation.MOSTLY_LEGATO);
        assertThat(legatoWithout - legatoWith)
                .describedAs("legato cost: %.4f with, %.4f without", legatoWith, legatoWithout)
                .isLessThan(0.02);

        // Measured 0.661 against 0.454. This is what pays for it.
        double detachedWith = recovery(QuantizationSettings.DEFAULT, Articulation.DETACHED);
        double detachedWithout = recovery(
                QuantizationSettings.DEFAULT.withArticulationRatio(1.0), Articulation.DETACHED);
        assertThat(detachedWith - detachedWithout)
                .describedAs("detached gain: %.4f with, %.4f without",
                        detachedWith, detachedWithout)
                .isGreaterThan(0.15);
    }

    private static double recovery(QuantizationSettings settings) {
        double total = 0;
        for (Articulation style : Articulation.values()) {
            total += recovery(settings, style);
        }
        return total / Articulation.values().length;
    }

    /** Fraction of notes whose written duration comes back exactly right. */
    private static double recovery(QuantizationSettings settings, Articulation style) {
        int correct = 0;
        int counted = 0;
        for (int lengthSteps : LENGTHS) {
            Random random = new Random(lengthSteps * 31L + style.ordinal());
            List<Note> notes = new ArrayList<>();
            List<Double> expected = new ArrayList<>();
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
            double at = 0;
            for (int i = 0; i < NOTES_PER_CASE; i++) {
                // A sixteenth on either side, so the bar is unambiguously on the
                // sixteenth grid whatever the note under test does.
                notes.add(exact(tempoMap, at, STEP));
                at += STEP;
                double written = lengthSteps * STEP;
                double held = written * (style.low + random.nextDouble() * (style.high - style.low));
                notes.add(exact(tempoMap, at, held));
                expected.add(written);
                at += Math.max(written, held) + STEP;
                at = Math.ceil(at / STEP) * STEP;
            }
            Score score = QuantizerTest.scoreOfForTest(tempoMap, notes);
            List<Note> quantized = Quantizer.quantize(score, settings).score()
                    .tracks().get(0).notes();
            int index = 0;
            for (Note note : quantized) {
                if (note.durationSeconds() <= tempoMap.beatsToSeconds(STEP) + 1e-9) {
                    continue;
                }
                if (index < expected.size()
                        && Math.abs(note.durationBeats().orElseThrow() - expected.get(index)) < 1e-9) {
                    correct++;
                }
                index++;
                counted++;
            }
        }
        return counted == 0 ? 0 : correct / (double) counted;
    }

    private static Note exact(TempoMap tempoMap, double beat, double lengthBeats) {
        double onset = tempoMap.beatsToSeconds(beat);
        return Note.ofSeconds(onset, tempoMap.beatsToSeconds(beat + lengthBeats) - onset, 60,
                Confidence.CERTAIN);
    }
}
