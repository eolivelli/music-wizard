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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one line of this command that is not plumbing.
 *
 * <p>What it prints has to be a number the user can hand straight back to
 * {@code --tempo}, which is the correction the README calls the highest-value
 * thing they can do. That has failed twice: once by printing quarter notes where
 * the option takes counted beats, and once by printing the map's figure where
 * the engraved chart printed the grid's.
 */
class AnalyzeCommandTest {

    /**
     * Pulses starting a little after t=0, which is where a beat tracker actually
     * puts them and what makes this fixture discriminate.
     *
     * <p>A grid starting at exactly 0.0 is the trap CLAUDE.md records: with no
     * lead-in, the tempo map's average equals the grid's own rate to the bit, so
     * every source of a tempo agrees and a test cannot tell which one was read.
     * This grid is evenly spaced, so its rate and its median coincide too and it
     * cannot tell those apart either -- which is #200's half of the same trap,
     * and why {@code GridRateTest} exists rather than a case being added here.
     * The 0.05 s here is a whole pulse crammed into a twentieth of one, which
     * pulls the map's average to 124.5 against the grid's 120.
     */
    private static List<Double> pulses(int count, double interval) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(LEAD_IN + i * interval);
        }
        return times;
    }

    private static final double LEAD_IN = 0.05;

    private static Score trackedAt(double interval, TimeSignature meter) {
        List<Double> times = pulses(24, interval);
        return Score.empty(TempoMap.fromBeatTimes(times, meter), LEAD_IN + 24 * interval)
                .withBeatGrid(BeatGrid.ofTimes(times, meter, Confidence.of(0.9)));
    }

    @Nested
    @DisplayName("the tempo line")
    class TempoLine {

        @Test
        @DisplayName("prints quarter notes only where they are what the user counts")
        void printsTheCountedBeat() {
            assertThat(AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.FOUR_FOUR)))
                    .isEqualTo("Tempo   120.0 BPM");
            // 120 dotted quarters a minute, which the map stores as 180 quarters.
            assertThat(AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.SIX_EIGHT)))
                    .isEqualTo("Tempo   120.0 BPM (180.0 quarter notes/min)");
        }

        @Test
        @DisplayName("prints the tracked beats' tempo, not the map's inflated average")
        void prefersTheTrackedBeatsOverTheMap() {
            // fromBeatTimes forces a whole pulse into the audio before the first
            // tracked beat, so the map's average runs above the real tempo --
            // 124.5 for the 120 BPM grid below, and 122 for the longer fixture
            // EndToEndIT uses, where the same one pulse is spread thinner. Guarded here
            // because the two agree exactly whenever the grid starts at t=0, and
            // a fixture that starts there cannot tell the two sources apart.
            Score tracked = trackedAt(0.5, TimeSignature.FOUR_FOUR);

            assertThat(tracked.tempoMap().averageTempo(tracked.durationSeconds()))
                    .as("the map is inflated, so this fixture discriminates")
                    .isGreaterThan(124.0);
            assertThat(AnalyzeCommand.tempoLine(tracked)).isEqualTo("Tempo   120.0 BPM");
        }

        @Test
        @DisplayName("prints the tempo the user forced, not the one that was tracked")
        void honoursAForcedTempo() {
            Score corrected = trackedAt(0.5, TimeSignature.FOUR_FOUR)
                    .withTempoMap(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR));

            assertThat(AnalyzeCommand.tempoLine(corrected)).isEqualTo("Tempo   60.0 BPM");
        }

        @Test
        @DisplayName("is typeable back into --tempo in any locale")
        void isLocaleIndependent() {
            // picocli parses --tempo with Double.valueOf, which rejects "120,0".
            Locale original = Locale.getDefault();
            try {
                for (Locale locale : List.of(Locale.forLanguageTag("fr-FR"),
                        Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                    Locale.setDefault(locale);
                    String line = AnalyzeCommand.tempoLine(trackedAt(0.5, TimeSignature.FOUR_FOUR));

                    assertThat(line).as("under %s", locale).isEqualTo("Tempo   120.0 BPM");
                    assertThat(Double.valueOf(line.replace("Tempo   ", "").replace(" BPM", "")))
                            .as("parseable under %s", locale)
                            .isEqualTo(120.0);
                }
            } finally {
                Locale.setDefault(original);
            }
        }
    }
}
