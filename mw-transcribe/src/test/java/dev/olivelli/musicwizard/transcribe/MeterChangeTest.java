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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A supplied meter change that holds the counted beat (#888) or the eighth (#883). */
class MeterChangeTest {

    private static final int SAMPLE_RATE = 22050;

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

    private static final AudioBuffer AUDIO = clickTrack(20.0, 0.5, 1.3);

    private static Score transcribe(List<String> messages, Double firstDownbeat,
                                    AudioTranscriber.MeterChangeAt... changes) {
        return new AudioTranscriber(messages::add).transcribe(AUDIO,
                new AudioTranscriber.Options(null, TimeSignature.THREE_FOUR, firstDownbeat,
                        false, null, List.of(changes)));
    }

    private static List<Double> pulses() {
        return transcribe(new ArrayList<>(), null).beatGrid().orElseThrow().beatTimes();
    }

    @Test
    @DisplayName("3/4 then 6/8: the pulse that was a quarter is a dotted quarter from the change")
    void holdsTheCountedBeat() {
        List<Double> pulses = pulses();
        int change = 13;
        Score score = transcribe(new ArrayList<>(), null, new AudioTranscriber.MeterChangeAt(
                pulses.get(change) + 0.02, TimeSignature.SIX_EIGHT));
        TempoMap map = score.tempoMap();

        assertThat(map.meterChanges()).hasSize(2);
        TempoMap.MeterChange sixEight = map.meterChanges().get(1);
        assertThat(sixEight.timeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(sixEight.provenance()).isEqualTo(Provenance.SUPPLIED);

        // The change pulse opens the 6/8 bar, and every tracked pulse keeps its
        // second: only the beats each one spans change.
        double changeBeat = map.toBeat(new MusicalTime(sixEight.startBar(), 0,
                TimeSignature.SIX_EIGHT));
        assertThat(map.beatsToSeconds(changeBeat)).isCloseTo(pulses.get(change), within(1e-9));
        for (int i = change; i + 1 < pulses.size(); i++) {
            assertThat(map.secondsToBeats(pulses.get(i + 1)) - map.secondsToBeats(pulses.get(i)))
                    .as("pulse %d", i).isCloseTo(1.5, within(1e-9));
        }
        for (int i = 1; i < change; i++) {
            assertThat(map.secondsToBeats(pulses.get(i)) - map.secondsToBeats(pulses.get(i - 1)))
                    .as("pulse %d", i).isCloseTo(1.0, within(1e-9));
        }
        assertThat(map.tempoAtBeat(changeBeat - 0.5)).isCloseTo(120, within(3.0));
        assertThat(map.tempoAtBeat(changeBeat + 0.5)).isCloseTo(180, within(4.5));

        // Bars of three pulses up to the change, which places them, and of two after.
        BeatGrid grid = score.beatGrid().orElseThrow();
        for (int i = 0; i < pulses.size(); i++) {
            boolean downbeat = i < change ? (change - i) % 3 == 0 : (i - change) % 2 == 0;
            assertThat(grid.beats().get(i).downbeat()).as("pulse %d", i).isEqualTo(downbeat);
            assertThat(map.toMusicalTime(map.secondsToBeats(pulses.get(i))).beatInBar() == 0)
                    .as("pulse %d on the map", i).isEqualTo(downbeat);
        }
    }

    @Test
    @DisplayName("a change off the bar line the supplied downbeat set moves to the nearest one")
    void snapsToTheNearestBarLine() {
        List<Double> pulses = pulses();
        List<String> messages = new ArrayList<>();
        // Bars of 3/4 from pulse 1 put bar lines on 13 and 16; 14 is nearer 13.
        Score score = transcribe(messages, pulses.get(1),
                new AudioTranscriber.MeterChangeAt(pulses.get(14), TimeSignature.SIX_EIGHT));

        assertThat(score.beatGrid().orElseThrow().beats().get(13).downbeat()).isTrue();
        assertThat(score.beatGrid().orElseThrow().beats().get(14).downbeat()).isFalse();
        assertThat(messages).anyMatch(m -> m.contains("is not on a bar line of 3/4"));
    }

    @Test
    @DisplayName("a second change holds the beat again, and is said on its own line")
    void changesBack() {
        List<Double> pulses = pulses();
        List<String> messages = new ArrayList<>();
        Score score = transcribe(messages, null,
                new AudioTranscriber.MeterChangeAt(pulses.get(10), TimeSignature.SIX_EIGHT),
                new AudioTranscriber.MeterChangeAt(pulses.get(20), TimeSignature.THREE_FOUR));
        TempoMap map = score.tempoMap();

        assertThat(map.meterChanges()).extracting(TempoMap.MeterChange::timeSignature)
                .containsExactly(TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                        TimeSignature.THREE_FOUR);
        assertThat(map.secondsToBeats(pulses.get(22)) - map.secondsToBeats(pulses.get(21)))
                .isCloseTo(1.0, within(1e-9));
        assertThat(map.secondsToBeats(pulses.get(20)) - map.secondsToBeats(pulses.get(10)))
                .isCloseTo(15.0, within(1e-9));
        assertThat(messages).filteredOn(m -> m.contains("the counted beat held")).hasSize(2);
    }

    @Test
    @DisplayName("6/8 then 3/4 with the eighth held: the pulse keeps its quarters, bars of two (#883)")
    void holdsTheEighth() {
        List<Double> pulses = pulses();
        List<String> messages = new ArrayList<>();
        Score score = transcribe(messages, null,
                new AudioTranscriber.MeterChangeAt(pulses.get(4), TimeSignature.SIX_EIGHT),
                new AudioTranscriber.MeterChangeAt(pulses.get(10), TimeSignature.THREE_FOUR, true),
                new AudioTranscriber.MeterChangeAt(pulses.get(16), TimeSignature.SIX_EIGHT, true));
        TempoMap map = score.tempoMap();

        assertThat(map.meterChanges()).extracting(TempoMap.MeterChange::timeSignature)
                .containsExactly(TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                        TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT);
        for (int i = 4; i + 1 < pulses.size(); i++) {
            assertThat(map.secondsToBeats(pulses.get(i + 1)) - map.secondsToBeats(pulses.get(i)))
                    .as("pulse %d", i).isCloseTo(1.5, within(1e-9));
        }
        // The 3/4 bars hold two pulses each, so the second change is three bars on.
        assertThat(map.meterChanges().get(3).startBar() - map.meterChanges().get(2).startBar())
                .isEqualTo(3);
        BeatGrid grid = score.beatGrid().orElseThrow();
        for (int i = 4; i < pulses.size(); i++) {
            boolean downbeat = (i - 4) % 2 == 0;
            assertThat(grid.beats().get(i).downbeat()).as("pulse %d", i).isEqualTo(downbeat);
            assertThat(map.toMusicalTime(map.secondsToBeats(pulses.get(i))).beatInBar() == 0)
                    .as("pulse %d on the map", i).isEqualTo(downbeat);
        }
        assertThat(messages).filteredOn(m -> m.contains("the eighth held")).hasSize(2);
        assertThat(messages).filteredOn(m -> m.contains("the counted beat held")).hasSize(1);
    }

    @Test
    @DisplayName("an eighth held into a bar the pulse does not divide is refused")
    void refusesAnEighthHeldIntoAnUnevenBar() {
        List<Double> pulses = pulses();
        List<String> messages = new ArrayList<>();
        Score score = transcribe(messages, null,
                new AudioTranscriber.MeterChangeAt(pulses.get(4), TimeSignature.SIX_EIGHT),
                new AudioTranscriber.MeterChangeAt(pulses.get(10), TimeSignature.FOUR_FOUR, true));

        assertThat(score.tempoMap().meterChanges()).hasSize(2);
        assertThat(messages).anyMatch(m -> m.contains("a bar of 4/4 is not a whole number"));
    }

    @Test
    @DisplayName("a change needs the opening meter, cannot ride a supplied tempo, and must move on")
    void refusesWhatItCannotMean() {
        AudioTranscriber.MeterChangeAt sixEight =
                new AudioTranscriber.MeterChangeAt(5.0, TimeSignature.SIX_EIGHT);
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioTranscriber.Options(
                null, null, null, false, null, List.of(sixEight)))
                .withMessageContaining("opening signature");
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioTranscriber.Options(
                120.0, TimeSignature.THREE_FOUR, null, false, null, List.of(sixEight)))
                .withMessageContaining("supplied tempo");
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioTranscriber.Options(
                null, TimeSignature.SIX_EIGHT, null, false, null, List.of(sixEight)))
                .withMessageContaining("already in force");
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioTranscriber.Options(
                null, TimeSignature.THREE_FOUR, null, false, null, List.of(sixEight,
                        new AudioTranscriber.MeterChangeAt(4.0, TimeSignature.THREE_FOUR))))
                .withMessageContaining("follow one another");
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AudioTranscriber.MeterChangeAt(0.0, TimeSignature.SIX_EIGHT));
    }

    @Test
    @DisplayName("a change at the opening's own first bar line is refused rather than barring nothing")
    void ignoresAChangeWithNoBarBeforeIt() {
        List<Double> pulses = pulses();
        List<String> messages = new ArrayList<>();
        Score score = transcribe(messages, pulses.get(0),
                new AudioTranscriber.MeterChangeAt(pulses.get(1), TimeSignature.SIX_EIGHT));

        assertThat(score.tempoMap().meterChanges()).hasSize(1);
        assertThat(messages).anyMatch(m -> m.contains("no whole bar of 3/4 before it"));
    }

    @Test
    @DisplayName("a change that is ignored does not place the bar lines either")
    void anIgnoredChangeLeavesThePhaseToTheEstimator() {
        List<Double> pulses = pulses();
        BeatGrid plain = transcribe(new ArrayList<>(), null).beatGrid().orElseThrow();
        for (int i = 0; i < 3; i++) {
            List<String> messages = new ArrayList<>();
            Score score = transcribe(messages, null,
                    new AudioTranscriber.MeterChangeAt(pulses.get(i), TimeSignature.SIX_EIGHT));

            assertThat(messages).as("pulse %d", i).anyMatch(m -> m.contains("is ignored"));
            assertThat(score.beatGrid().orElseThrow().downbeatTimes()).as("pulse %d", i)
                    .isEqualTo(plain.downbeatTimes());
        }
    }
}
