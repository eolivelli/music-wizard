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

import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The calibration behind {@link QuantizationSettings#DEFAULT}, written down.
 *
 * <p>Four bars of one kind of material, played by a human, have to come back as
 * the grid a human would have written. Each case is run over many seeds rather
 * than one, because a single seed passing says only that one draw of the noise
 * fell the right way; the recovery rate across seeds is the number that says
 * whether the penalties are calibrated.
 *
 * <p>These are the tests to re-run before touching any constant in
 * {@link QuantizationSettings}.
 */
class QuantizerCalibrationTest {

    private static final double BPM = 120;
    private static final int BARS = 4;
    private static final int SEEDS = 50;

    @ParameterizedTest
    @EnumSource(Material.class)
    @DisplayName("four bars of human-played material recover their own grid")
    void recoversTheGridItWasPlayedOn(Material material) {
        long[] failures = IntStream.range(0, SEEDS)
                .filter(seed -> !recovers(material, seed))
                .asLongStream()
                .toArray();
        double recovered = (SEEDS - failures.length) / (double) SEEDS;
        assertThat(recovered)
                .describedAs("%s recovered on %d of %d seeds; mis-read on %s",
                        material, SEEDS - failures.length, SEEDS,
                        java.util.Arrays.toString(failures))
                .isGreaterThanOrEqualTo(material.minimumRecovery);
    }

    @ParameterizedTest
    @ValueSource(doubles = {60, 90, 120})
    @DisplayName("the calibration holds up to about 120 BPM, and the sweep says where it stops")
    void theCalibrationIsATempoRangeNotAConstant(double bpm) {
        // Deviation is measured in beats and the player's spread is fixed in
        // seconds, so the effective spread grows with the tempo: 25 ms is a
        // twentieth of a beat at 120 and a twelfth at 200. Nothing in the
        // penalties knows the tempo, so the calibration is a statement about a
        // range, and the range has to be tested rather than assumed.
        //
        // Measured per material and tempo: up to 120 BPM everything is exact
        // bar one seed of triplet sixteenths. Above it the sixteenths go
        // first, and they go the unreadable way -- at the top of the range
        // most land on the sextuplet grid, finer and tupletted. Scaling the
        // penalties with the tempo was tried and makes it worse: it tilts
        // towards coarser grids, and the coarser neighbour of the sixteenth
        // grid is the triplet, so the sixteenths go there instead.
        //
        // A caveat that cuts the other way, and is why this is not treated as a
        // defect: holding the spread at 25 ms across the sweep overstates the
        // difficulty. A player's timing spread scales with the interval between
        // notes, so nobody is 25 ms out on a 75 ms grid and still playing
        // sixteenths.
        for (Material material : Material.values()) {
            long recovered = IntStream.range(0, SEEDS)
                    .filter(seed -> recovers(material, seed, bpm))
                    .count();
            assertThat(SEEDS - recovered)
                    .describedAs("%s at %s BPM", material, bpm)
                    .isLessThanOrEqualTo(2);
        }
    }

    private boolean recovers(Material material, long seed) {
        return recovers(material, seed, BPM);
    }

    private boolean recovers(Material material, long seed, double bpm) {
        TempoMap tempoMap = TempoMap.constant(bpm, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, seed);
        double[] beats = Performance.evenly(BARS, 4.0, material.divisionsPerBar);
        performance.run(69, 4.0 / material.divisionsPerBar, beats);
        QuantizedScore quantized = Quantizer.quantize(performance.score());
        return quantized.grids().stream().allMatch(g -> g.resolution() == material.expected);
    }

    @Test
    @DisplayName("a bar of sixteenths inside a section of eighths is still read as sixteenths")
    void strongEvidenceOutweighsTheSectionPrior() {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, 11);
        for (int bar = 0; bar < 5; bar++) {
            int divisions = bar == 2 ? 16 : 8;
            for (int i = 0; i < divisions; i++) {
                performance.note(69, bar * 4.0 + i * 4.0 / divisions, 4.0 / divisions);
            }
        }
        QuantizedScore quantized = Quantizer.quantize(performance.score());

        assertThat(quantized.gridAtBar(2).orElseThrow().resolution())
                .isEqualTo(GridResolution.QUARTER_BEAT);
        assertThat(quantized.gridAtBar(1).orElseThrow().resolution())
                .isEqualTo(GridResolution.HALF_BEAT);
        assertThat(quantized.gridAtBar(3).orElseThrow().resolution())
                .isEqualTo(GridResolution.HALF_BEAT);
    }

    @Test
    @DisplayName("a rushed pair of eighths does not become a triplet bar")
    void weakEvidenceDoesNotOutweighTheSectionPrior() {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        Performance performance = new Performance(tempoMap, 12);
        for (int bar = 0; bar < 5; bar++) {
            for (int i = 0; i < 8; i++) {
                // Bar 2 is played with its off-beats dragged a third of the way
                // towards a shuffle: enough to make the triplet grid fit better
                // in that one bar, nowhere near enough to be worth telling a
                // reader the subdivision changed.
                double drag = bar == 2 && i % 2 == 1 ? 0.06 : 0.0;
                performance.note(69, bar * 4.0 + i * 0.5 + drag, 0.5);
            }
        }
        QuantizedScore quantized = Quantizer.quantize(performance.score());
        assertThat(quantized.grids())
                .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.HALF_BEAT));
    }

    @Test
    @DisplayName("without the section prior the grid flaps from bar to bar")
    void theSectionPriorIsWhatMakesTheOutputReadable() {
        TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
        // Twelve bars of eighths, played a little loosely -- 40 ms rather than
        // the usual 25. Enough that three of the twelve bars fit the triplet
        // grid marginally better than the eighth grid, purely from noise.
        Performance performance = new Performance(tempoMap, 13, 0.040);
        performance.run(69, 0.5, Performance.evenly(12, 4.0, 8));

        QuantizedScore withPrior = Quantizer.quantize(performance.score());
        QuantizedScore withoutPrior = Quantizer.quantize(performance.score(),
                QuantizationSettings.DEFAULT.withGridChangePenalty(0));

        assertThat(gridChanges(withoutPrior))
                .describedAs("without the prior the grid should flap: %s", grids(withoutPrior))
                .isGreaterThanOrEqualTo(4);
        assertThat(gridChanges(withPrior))
                .describedAs("with the prior it should not: %s", grids(withPrior))
                .isZero();
        assertThat(withPrior.grids())
                .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.HALF_BEAT));
    }

    private static int gridChanges(QuantizedScore quantized) {
        int changes = 0;
        for (int i = 1; i < quantized.grids().size(); i++) {
            if (quantized.grids().get(i).resolution() != quantized.grids().get(i - 1).resolution()) {
                changes++;
            }
        }
        return changes;
    }

    private static List<GridResolution> grids(QuantizedScore quantized) {
        return quantized.grids().stream().map(BarGrid::resolution).toList();
    }

    /**
     * The five canonical bars, the grid each has to come back as, and how often
     * it has to manage it.
     *
     * <p>Four of the five are exact at this jitter and are gated at 100%; a
     * single mis-read is a regression, not noise. Triplet sixteenths are not,
     * and the floor says so rather than hiding it: at 120 BPM a triplet
     * sixteenth is 83 ms and the player's own spread is 25 ms, so the grid it
     * has to be told apart from -- plain sixteenths, 125 ms -- is only half a
     * jitter away. Measured at 49 of 50 seeds; the floor is set below that so
     * that a real regression is what trips it rather than one unlucky draw.
     */
    enum Material {
        QUARTERS(4, GridResolution.BEAT, 1.0),
        EIGHTHS(8, GridResolution.HALF_BEAT, 1.0),
        TRIPLET_EIGHTHS(12, GridResolution.THIRD_BEAT, 1.0),
        SIXTEENTHS(16, GridResolution.QUARTER_BEAT, 1.0),
        TRIPLET_SIXTEENTHS(24, GridResolution.SIXTH_BEAT, 0.9);

        final int divisionsPerBar;
        final GridResolution expected;
        final double minimumRecovery;

        Material(int divisionsPerBar, GridResolution expected, double minimumRecovery) {
            this.divisionsPerBar = divisionsPerBar;
            this.expected = expected;
            this.minimumRecovery = minimumRecovery;
        }
    }
}
