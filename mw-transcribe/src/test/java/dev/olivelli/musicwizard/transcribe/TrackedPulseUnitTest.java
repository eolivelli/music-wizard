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

package dev.olivelli.musicwizard.transcribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transcriber tells the beat grid what its pulse is worth, and tells the
 * tempo map the same thing (#139).
 *
 * <p>This is the caller the issue names: the map and the grid are built forty
 * lines apart from one set of tracked pulses, and a reader of either is entitled
 * to the same tempo. Before this they were given the figure twice, once
 * explicitly and once by assumption, which is how they came to differ by a
 * factor in the first place.
 */
class TrackedPulseUnitTest {

    private static final int SAMPLE_RATE = 22050;

    /**
     * A click track whose first click is {@code offset} seconds in.
     *
     * <p>Offset deliberately, for the reason {@link TempoOverrideTest} records:
     * at {@code t = 0} the lead-in vanishes, and the lead-in is measured in whole
     * pulses rather than whole quarter notes.
     */
    private static AudioBuffer clickTrack(double seconds, double period, double offset) {
        float[] samples = new float[(int) (seconds * SAMPLE_RATE)];
        for (double t = offset; t < seconds; t += period) {
            int start = (int) (t * SAMPLE_RATE);
            for (int i = 0; i < SAMPLE_RATE / 40 && start + i < samples.length; i++) {
                double decay = Math.exp(-i / (SAMPLE_RATE / 400.0));
                samples[start + i] =
                        (float) (0.8 * decay * Math.sin(2 * Math.PI * 1000 * i / SAMPLE_RATE));
            }
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    private static Score transcribeIn(TimeSignature meter) {
        return new AudioTranscriber().transcribe(clickTrack(12.0, 0.5, 0.25),
                new AudioTranscriber.Options(null, meter, null));
    }

    /** The quarter beats between two consecutive measured segments of the map. */
    private static double pulseTheMapWasBuiltWith(TempoMap map) {
        List<TempoMap.TempoSegment> measured = map.segments().stream()
                .filter(segment -> segment.provenance() == Provenance.MEASURED)
                .toList();
        assertThat(measured).as("measured segments").hasSizeGreaterThan(1);
        return measured.get(1).startBeat() - measured.get(0).startBeat();
    }

    @Test
    @DisplayName("records the meter's counted beat on the grid it builds")
    void theGridSaysWhatItWasTrackedAt() {
        assertThat(transcribeIn(TimeSignature.FOUR_FOUR).beatGrid().orElseThrow().pulseQuarters())
                .hasValue(1.0);
        assertThat(transcribeIn(TimeSignature.SIX_EIGHT).beatGrid().orElseThrow().pulseQuarters())
                .hasValue(1.5);
    }

    @Test
    @DisplayName("gives the map and the grid the same pulse, so their tempi agree")
    void theMapAndTheGridGetOneFigure() {
        for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                TimeSignature.SIX_EIGHT, new TimeSignature(9, 8), new TimeSignature(7, 8))) {
            Score score = transcribeIn(meter);
            BeatGrid grid = score.beatGrid().orElseThrow();

            // The figure the map was built with, read back off the map's own
            // beat axis rather than recomputed from the meter -- so a transcriber
            // that passed the two different pulses would fail here.
            assertThat(grid.pulseQuarters()).as("%s", meter)
                    .hasValue(pulseTheMapWasBuiltWith(score.tempoMap()));

            // And the two summaries of one performance land on one tempo. A
            // click every half second is 120 pulses a minute whatever the meter,
            // so the quarter-note tempo is 120 times what a pulse is worth.
            double expected = 120.0 * meter.beatUnitQuarters();
            assertThat(grid.medianTempo(meter)).as("%s grid", meter)
                    .isCloseTo(expected, within(1.0));
            assertThat(score.estimatedTempo()).as("%s score", meter)
                    .isCloseTo(grid.medianTempo(meter), within(1e-9));
        }
    }
}
