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

/**
 * The knobs the quantizer trades readability against literalness with.
 *
 * <p>The three costs below are in quarter-note beats, so that each can be added
 * directly to the deviation it is competing against. That is what makes the
 * numbers arguable rather than magic: a {@code levelPenalty} of 0.035 says
 * "halving the note value has to buy at least 0.035 beats of accuracy on every
 * note it applies to, or it is not worth reading".
 *
 * <p>The defaults were calibrated against bars of quarters, eighths, triplet
 * eighths, sixteenths and triplet sixteenths, each perturbed with the timing
 * spread of a decent human player, and each has to win its own case by a clear
 * margin. {@code QuantizerCalibrationTest} is that calibration written down and
 * is the thing to re-run before changing any of them.
 *
 * @param levelPenalty      cost per note per beam of the note value. Charges
 *                          shorter values for being harder to read
 * @param tupletPenalty     additional cost per note for a division the meter
 *                          does not subdivide by. Keeps a little rushing from
 *                          being read as a triplet
 * @param gridChangePenalty cost of changing grid between adjacent bars within a
 *                          section. This is the prior that makes the output
 *                          readable at all: without it the subdivision changes
 *                          every bar and no reader can follow it
 * @param articulationRatio the fraction of its written length a note is assumed
 *                          to be held for. Players release early, and snapping
 *                          the released offset to the nearest grid position
 *                          turns every detached quarter into a dotted eighth;
 *                          dividing the played length by this before snapping
 *                          is what stops that. 1.0 disables the allowance
 * @param detectSwing       whether to look for a shuffle and write it straight
 */
public record QuantizationSettings(
        double levelPenalty,
        double tupletPenalty,
        double gridChangePenalty,
        double articulationRatio,
        boolean detectSwing) {

    /**
     * The calibrated defaults.
     *
     * <p>Fitted against the measured deviation of each of the five canonical
     * materials on each of the six grids, at the 25 ms onset spread of a decent
     * human player, by maximising the smallest margin by which the right answer
     * wins. That smallest margin is sixteenth-note triplets, at 0.011 beats per
     * note -- they are the hardest thing here to tell from plain sixteenths, and
     * they stay the first thing to break if these numbers move.
     */
    public static final QuantizationSettings DEFAULT =
            new QuantizationSettings(0.035, 0.015, 0.25, 0.9, true);

    /** The narrowest articulation allowance that is not absurd. */
    private static final double MIN_ARTICULATION_RATIO = 0.25;

    public QuantizationSettings {
        requireNonNegative(levelPenalty, "levelPenalty");
        requireNonNegative(tupletPenalty, "tupletPenalty");
        requireNonNegative(gridChangePenalty, "gridChangePenalty");
        if (!(articulationRatio >= MIN_ARTICULATION_RATIO && articulationRatio <= 1.0)) {
            throw new IllegalArgumentException(
                    "articulationRatio must be within " + MIN_ARTICULATION_RATIO
                            + "..1.0, got: " + articulationRatio);
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative, got: " + value);
        }
    }

    /** Returns a copy with swing detection turned off. */
    public QuantizationSettings withoutSwingDetection() {
        return new QuantizationSettings(levelPenalty, tupletPenalty, gridChangePenalty,
                articulationRatio, false);
    }

    /** Returns a copy with a different per-section grid-change cost. */
    public QuantizationSettings withGridChangePenalty(double penalty) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, penalty,
                articulationRatio, detectSwing);
    }

    /** Returns a copy with a different articulation allowance. */
    public QuantizationSettings withArticulationRatio(double ratio) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, gridChangePenalty,
                ratio, detectSwing);
    }
}
