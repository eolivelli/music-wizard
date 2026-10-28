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
import java.util.ArrayList;
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

    /**
     * A map as the transcriber builds one, a segment per tracked pulse:
     * {@code quarters[i]} quarters in pulse i, which lasts {@code seconds[i]}.
     */
    private static TempoMap tracked(double[] quarters, double[] seconds,
                                    List<TempoMap.MeterChange> meters) {
        List<TempoMap.TempoSegment> segments = new ArrayList<>();
        double beat = 0;
        double time = 0;
        for (int i = 0; i < quarters.length; i++) {
            segments.add(new TempoMap.TempoSegment(beat, time, 60 * quarters[i] / seconds[i],
                    Provenance.MEASURED));
            beat += quarters[i];
            time += seconds[i];
        }
        return new TempoMap(segments, meters);
    }

    @Test
    @DisplayName("on a tracked map the pulse's quarters decide, however the bars either side are played")
    void rubatoDoesNotOverruleTheTrackedPulse() {
        // Two bars of 6/8, three of 3/4 with the eighth held, two of 6/8, a
        // pulse each; the bars around each change played much slower.
        double[] quarters = new double[14];
        double[] seconds = new double[14];
        for (int i = 0; i < 14; i++) {
            quarters[i] = 1.5;
            seconds[i] = i == 2 || i == 3 || i == 8 || i == 9 ? 0.9 : 0.5;
        }
        TempoMap held = tracked(quarters, seconds, List.of(
                new TempoMap.MeterChange(0, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED),
                new TempoMap.MeterChange(2, TimeSignature.THREE_FOUR, Provenance.SUPPLIED),
                new TempoMap.MeterChange(5, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED)));
        assertThat(MeterHold.holdsTheQuarter(held, 2)).isTrue();
        assertThat(MeterHold.holdsTheQuarter(held, 5)).isTrue();

        // 3/4 then 6/8 with the counted beat held, the first 6/8 bar slower.
        double[] counted = new double[10];
        double[] played = new double[10];
        for (int i = 0; i < 10; i++) {
            counted[i] = i < 6 ? 1.0 : 1.5;
            played[i] = i == 6 || i == 7 ? 0.7 : 0.5;
        }
        TempoMap beatHeld = tracked(counted, played, List.of(
                new TempoMap.MeterChange(0, TimeSignature.THREE_FOUR, Provenance.SUPPLIED),
                new TempoMap.MeterChange(2, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED)));
        assertThat(MeterHold.holdsTheQuarter(beatHeld, 2)).isFalse();
    }

    @Test
    @DisplayName("a stated tempo that changes with the meter does not hold the quarter")
    void aStatedChangeOfTempoIsAChange() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.DECLARED),
                        new TempoMap.TempoSegment(3, 1.5, 100, Provenance.DECLARED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.THREE_FOUR, Provenance.DECLARED),
                        new TempoMap.MeterChange(1, TimeSignature.SIX_EIGHT,
                                Provenance.DECLARED)));

        assertThat(MeterHold.holdsTheQuarter(map, 1)).isFalse();
    }
}
