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

import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeterHoldTest {

    @Test
    @DisplayName("one quarter tempo across 6/8 into 3/4 holds the quarter, and the eighth with it")
    void oneQuarterTempoHoldsTheQuarter() {
        TempoMap map = TempoMap.constant(180, TimeSignature.SIX_EIGHT)
                .withMeterChange(2, TimeSignature.THREE_FOUR)
                .withMeterChange(5, TimeSignature.SIX_EIGHT);

        assertThat(MeterHold.holdsTheQuarter(map, 2)).isTrue();
        assertThat(MeterHold.holdsTheQuarter(map, 5)).isTrue();
    }

    @Test
    @DisplayName("a quarter that becomes the dotted quarter's length holds the counted beat (#888)")
    void aDottedQuarterAtTheQuartersRateHoldsTheBeat() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.MEASURED),
                        new TempoMap.TempoSegment(6, 3.0, 180, Provenance.MEASURED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.THREE_FOUR, Provenance.SUPPLIED),
                        new TempoMap.MeterChange(2, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED)));

        assertThat(MeterHold.holdsTheQuarter(map, 2)).isFalse();
    }

    @Test
    @DisplayName("meters counting the same note value hold it whatever the tempo does")
    void theSameCountedValueIsHeld() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(4, 2.0, 60, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR, Provenance.DECLARED),
                        new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR,
                                Provenance.DECLARED)));

        assertThat(MeterHold.holdsTheQuarter(map, 1)).isTrue();
    }
}
