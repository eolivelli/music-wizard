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

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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
 * @param overlapTolerance  how far past a whole number of grid steps a note may
 *                          sound before the allowance decides it must have been
 *                          written longer. Suppresses the allowance for a note
 *                          held <em>through</em> its written end rather than
 *                          short of it. 0.0 applies the allowance to everything
 * @param detectSwing       whether to look for a shuffle and write it straight
 * @param grids             the subdivisions a bar may be written on. The costs
 *                          above choose among these; a resolution left out
 *                          cannot be chosen however well it fits
 */
public record QuantizationSettings(
        double levelPenalty,
        double tupletPenalty,
        double gridChangePenalty,
        double articulationRatio,
        double overlapTolerance,
        boolean detectSwing,
        Set<GridResolution> grids) {

    /**
     * The calibrated defaults.
     *
     * <p>Fitted against the measured deviation of each of the five canonical
     * materials on each of the six grids, at the 25 ms onset spread of a decent
     * human player, by maximising the smallest margin by which the right answer
     * wins. That smallest margin is sixteenth-note triplets, at 0.011 beats per
     * note -- they are the hardest thing here to tell from plain sixteenths, and
     * they stay the first thing to break if these numbers move.
     *
     * <p>Fitted <em>at 120 BPM</em>, and that qualifier is load-bearing:
     * deviation is in beats while a player's spread is fixed in seconds, so
     * the effective spread grows with the tempo and recovery falls off above
     * the fitted one — the sweep is in {@code QuantizerCalibrationTest}.
     * Scaling these constants with the tempo was tried and makes it worse.
     */
    public static final QuantizationSettings DEFAULT =
            new QuantizationSettings(0.035, 0.015, 0.25, 0.9, 0.02, true,
                    EnumSet.allOf(GridResolution.class));

    /**
     * The defaults, restricted to the subdivisions a part written to be
     * <em>read</em> is written in: the meter's own, down to two levels below
     * the counted beat.
     *
     * <p>For {@link PlayableMelody}'s reduction rather than for a
     * transcription, and the difference is what evidence there is (#594). The
     * reduction prints one note-head per sung syllable, so a bar of it holds a
     * handful of onsets — few enough that some subdivision always fits, and
     * the one that wins is fitting the segmenter's spread rather than the
     * singing. Where that lands a triplet in a song in straight time, the
     * reader is being told something the recording never said.
     * {@code tools/PlayablePartCheck.java} sweeps the vocabularies and holds
     * each against an arranger's own reading of the same recording.
     */
    public static final QuantizationSettings READING = DEFAULT.withGrids(
            EnumSet.of(GridResolution.BEAT, GridResolution.HALF_BEAT,
                    GridResolution.QUARTER_BEAT));

    /** The narrowest articulation allowance that is not absurd. */
    private static final double MIN_ARTICULATION_RATIO = 0.25;

    public QuantizationSettings {
        requireNonNegative(levelPenalty, "levelPenalty");
        requireNonNegative(tupletPenalty, "tupletPenalty");
        requireNonNegative(gridChangePenalty, "gridChangePenalty");
        requireNonNegative(overlapTolerance, "overlapTolerance");
        if (!(articulationRatio >= MIN_ARTICULATION_RATIO && articulationRatio <= 1.0)) {
            throw new IllegalArgumentException(
                    "articulationRatio must be within " + MIN_ARTICULATION_RATIO
                            + "..1.0, got: " + articulationRatio);
        }
        grids = copyOf(grids);
    }

    /**
     * An unmodifiable copy iterating in {@link GridResolution} declaration
     * order, which is what makes the quantizer's tie-break — the simplest grid
     * — depend on the enum rather than on how a caller happened to build the
     * set.
     */
    private static Set<GridResolution> copyOf(Collection<GridResolution> grids) {
        Objects.requireNonNull(grids, "grids");
        if (grids.isEmpty()) {
            throw new IllegalArgumentException("a bar has to be written on some grid");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(grids));
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
                articulationRatio, overlapTolerance, false, grids);
    }

    /** Returns a copy with a different per-section grid-change cost. */
    public QuantizationSettings withGridChangePenalty(double penalty) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, penalty,
                articulationRatio, overlapTolerance, detectSwing, grids);
    }

    /** Returns a copy with a different articulation allowance. */
    public QuantizationSettings withArticulationRatio(double ratio) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, gridChangePenalty,
                ratio, overlapTolerance, detectSwing, grids);
    }

    /** Returns a copy written on the given subdivisions and no others. */
    public QuantizationSettings withGrids(Collection<GridResolution> allowed) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, gridChangePenalty,
                articulationRatio, overlapTolerance, detectSwing, copyOf(allowed));
    }

    /**
     * Returns a copy with a different overlap tolerance.
     *
     * <p>Exists so that the tolerance can be measured rather than asserted:
     * while it was a private constant, the sentence justifying its value
     * stayed wrong precisely because nothing could move it -- while its
     * neighbour, which had an accessor, was measured and corrected.
     */
    public QuantizationSettings withOverlapTolerance(double tolerance) {
        return new QuantizationSettings(levelPenalty, tupletPenalty, gridChangePenalty,
                articulationRatio, tolerance, detectSwing, grids);
    }
}
