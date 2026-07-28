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

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GridResolutionTest {

    private static final TimeSignature TWELVE_EIGHT = new TimeSignature(12, 8);
    private static final TimeSignature SEVEN_EIGHT = new TimeSignature(7, 8);
    private static final TimeSignature CUT_COMMON = new TimeSignature(2, 2);

    @Nested
    @DisplayName("in simple time the grid is the familiar set of quarter-note fractions")
    class SimpleTime {

        @Test
        void stepsAreFractionsOfTheQuarter() {
            assertThat(GridResolution.BEAT.stepQuarters(TimeSignature.FOUR_FOUR)).isEqualTo(1.0);
            assertThat(GridResolution.HALF_BEAT.stepQuarters(TimeSignature.FOUR_FOUR)).isEqualTo(0.5);
            assertThat(GridResolution.QUARTER_BEAT.stepQuarters(TimeSignature.FOUR_FOUR)).isEqualTo(0.25);
            assertThat(GridResolution.EIGHTH_BEAT.stepQuarters(TimeSignature.FOUR_FOUR)).isEqualTo(0.125);
            assertThat(GridResolution.THIRD_BEAT.stepQuarters(TimeSignature.FOUR_FOUR))
                    .isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-12));
        }

        @Test
        void thirdsAndSixthsAreTheTuplets() {
            assertThat(GridResolution.THIRD_BEAT.tupletIn(TimeSignature.FOUR_FOUR))
                    .contains(new GridResolution.Tuplet(3, 2));
            assertThat(GridResolution.SIXTH_BEAT.tupletIn(TimeSignature.FOUR_FOUR))
                    .contains(new GridResolution.Tuplet(3, 2));
            assertThat(GridResolution.HALF_BEAT.tupletIn(TimeSignature.FOUR_FOUR)).isEmpty();
            assertThat(GridResolution.QUARTER_BEAT.tupletIn(TimeSignature.FOUR_FOUR)).isEmpty();
        }

        @Test
        void depthCountsBeams() {
            assertThat(GridResolution.BEAT.depthIn(TimeSignature.FOUR_FOUR)).isZero();
            assertThat(GridResolution.HALF_BEAT.depthIn(TimeSignature.FOUR_FOUR)).isEqualTo(1);
            assertThat(GridResolution.THIRD_BEAT.depthIn(TimeSignature.FOUR_FOUR)).isEqualTo(1);
            assertThat(GridResolution.QUARTER_BEAT.depthIn(TimeSignature.FOUR_FOUR)).isEqualTo(2);
            assertThat(GridResolution.SIXTH_BEAT.depthIn(TimeSignature.FOUR_FOUR)).isEqualTo(2);
            assertThat(GridResolution.EIGHTH_BEAT.depthIn(TimeSignature.FOUR_FOUR)).isEqualTo(3);
        }

        @Test
        @DisplayName("3/4 counts three beats, so an eighth is still a half-beat")
        void threeFourIsNotCompound() {
            assertThat(GridResolution.HALF_BEAT.stepQuarters(TimeSignature.THREE_FOUR)).isEqualTo(0.5);
            assertThat(GridResolution.HALF_BEAT.divisionsPerBar(TimeSignature.THREE_FOUR)).isEqualTo(6);
            assertThat(GridResolution.HALF_BEAT.isTupletIn(TimeSignature.THREE_FOUR)).isFalse();
        }

        @Test
        @DisplayName("in cut common the counted beat is a half note")
        void cutCommonCountsHalves() {
            assertThat(GridResolution.BEAT.stepQuarters(CUT_COMMON)).isEqualTo(2.0);
            assertThat(GridResolution.HALF_BEAT.stepQuarters(CUT_COMMON)).isEqualTo(1.0);
            assertThat(GridResolution.BEAT.divisionsPerBar(CUT_COMMON)).isEqualTo(2);
        }

        @Test
        @DisplayName("7/8 is felt in eighths, not in dotted quarters")
        void sevenEightIsIrregularRatherThanCompound() {
            assertThat(SEVEN_EIGHT.isCompound()).isFalse();
            assertThat(GridResolution.BEAT.stepQuarters(SEVEN_EIGHT)).isEqualTo(0.5);
            assertThat(GridResolution.BEAT.divisionsPerBar(SEVEN_EIGHT)).isEqualTo(7);
            assertThat(GridResolution.THIRD_BEAT.isTupletIn(SEVEN_EIGHT)).isTrue();
        }
    }

    @Nested
    @DisplayName("in compound time the ladder is thirds, and it is the halves that are tuplets")
    class CompoundTime {

        @Test
        @DisplayName("a third of a 6/8 beat is the plain eighth, not a triplet")
        void thirdsAreTheNaturalSubdivision() {
            assertThat(GridResolution.THIRD_BEAT.stepQuarters(TimeSignature.SIX_EIGHT)).isEqualTo(0.5);
            assertThat(GridResolution.THIRD_BEAT.isTupletIn(TimeSignature.SIX_EIGHT)).isFalse();
            assertThat(GridResolution.THIRD_BEAT.tupletIn(TimeSignature.SIX_EIGHT)).isEmpty();
            assertThat(GridResolution.THIRD_BEAT.depthIn(TimeSignature.SIX_EIGHT)).isEqualTo(1);
            assertThat(GridResolution.THIRD_BEAT.divisionsPerBar(TimeSignature.SIX_EIGHT)).isEqualTo(6);
        }

        @Test
        @DisplayName("half a 6/8 beat is a duplet, printed two in the time of three")
        void halvesAreTheTuplets() {
            assertThat(GridResolution.HALF_BEAT.stepQuarters(TimeSignature.SIX_EIGHT)).isEqualTo(0.75);
            assertThat(GridResolution.HALF_BEAT.isTupletIn(TimeSignature.SIX_EIGHT)).isTrue();
            assertThat(GridResolution.HALF_BEAT.tupletIn(TimeSignature.SIX_EIGHT))
                    .contains(new GridResolution.Tuplet(2, 3));
            assertThat(GridResolution.QUARTER_BEAT.tupletIn(TimeSignature.SIX_EIGHT))
                    .contains(new GridResolution.Tuplet(2, 3));
        }

        @Test
        @DisplayName("the sixth of a 6/8 beat is the plain sixteenth")
        void sixthsAreTheNextRungDown() {
            assertThat(GridResolution.SIXTH_BEAT.stepQuarters(TimeSignature.SIX_EIGHT)).isEqualTo(0.25);
            assertThat(GridResolution.SIXTH_BEAT.isTupletIn(TimeSignature.SIX_EIGHT)).isFalse();
            assertThat(GridResolution.SIXTH_BEAT.depthIn(TimeSignature.SIX_EIGHT)).isEqualTo(2);
        }

        @Test
        @DisplayName("12/8 counts four dotted quarters, so a bar holds twelve eighths")
        void twelveEight() {
            assertThat(TWELVE_EIGHT.beatsPerBar()).isEqualTo(4);
            assertThat(GridResolution.THIRD_BEAT.divisionsPerBar(TWELVE_EIGHT)).isEqualTo(12);
            assertThat(GridResolution.BEAT.stepQuarters(TWELVE_EIGHT)).isEqualTo(1.5);
        }

        @Test
        @DisplayName("every depth in compound time still counts beams from the dotted quarter")
        void depthCountsBeams() {
            assertThat(GridResolution.BEAT.depthIn(TimeSignature.SIX_EIGHT)).isZero();
            assertThat(GridResolution.HALF_BEAT.depthIn(TimeSignature.SIX_EIGHT)).isEqualTo(1);
            assertThat(GridResolution.QUARTER_BEAT.depthIn(TimeSignature.SIX_EIGHT)).isEqualTo(2);
            assertThat(GridResolution.EIGHTH_BEAT.depthIn(TimeSignature.SIX_EIGHT)).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    @DisplayName("a grid always divides the beat exactly, in every meter it is asked about")
    void stepsTileTheBar(GridResolution resolution) {
        for (TimeSignature meter : new TimeSignature[] {
                TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                TWELVE_EIGHT, SEVEN_EIGHT, CUT_COMMON}) {
            double step = resolution.stepQuarters(meter);
            int divisions = resolution.divisionsPerBar(meter);
            assertThat(step * divisions)
                    .describedAs("%s in %s", resolution, meter)
                    .isCloseTo(meter.quarterBeatsPerBar(), org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    @DisplayName("a tuplet ratio is present exactly when the division is a tuplet")
    void tupletAgreesWithIsTuplet(GridResolution resolution) {
        for (TimeSignature meter : new TimeSignature[] {
                TimeSignature.FOUR_FOUR, TimeSignature.SIX_EIGHT, TWELVE_EIGHT}) {
            Optional<GridResolution.Tuplet> tuplet = resolution.tupletIn(meter);
            assertThat(tuplet.isPresent()).isEqualTo(resolution.isTupletIn(meter));
        }
    }

    @Test
    void rejectsADegenerateTupletRatio() {
        assertThatThrownBy(() -> new GridResolution.Tuplet(1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GridResolution.Tuplet(3, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullMeter() {
        assertThatThrownBy(() -> GridResolution.BEAT.stepQuarters(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> GridResolution.BEAT.depthIn(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> GridResolution.BEAT.isTupletIn(null))
                .isInstanceOf(NullPointerException.class);
    }
}
