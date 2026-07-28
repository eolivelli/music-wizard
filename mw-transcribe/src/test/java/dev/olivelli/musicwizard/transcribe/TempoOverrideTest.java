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
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What number the user is typing when they type {@code --tempo}.
 *
 * <p>The transcriber builds its tempo map two ways -- from the tracked pulses, or
 * from a tempo the user forced -- and the two have to describe the same music.
 * They stopped doing so the moment the tracked path learned that a pulse in 6/8
 * is a dotted quarter and the override path did not, which put the bar grid 1.5x
 * out in exactly the meter this was all meant to fix.
 */
class TempoOverrideTest {

    private static final int SAMPLE_RATE = 22050;

    /** A click track: a short decaying burst every {@code period} seconds. */
    private static AudioBuffer clickTrack(double seconds, double period) {
        float[] samples = new float[(int) (seconds * SAMPLE_RATE)];
        for (double t = 0; t < seconds; t += period) {
            int start = (int) (t * SAMPLE_RATE);
            for (int i = 0; i < SAMPLE_RATE / 40 && start + i < samples.length; i++) {
                double decay = Math.exp(-i / (SAMPLE_RATE / 400.0));
                samples[start + i] =
                        (float) (0.8 * decay * Math.sin(2 * Math.PI * 1000 * i / SAMPLE_RATE));
            }
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    private static Score transcribeAt(double tempo, TimeSignature meter) {
        return new AudioTranscriber().transcribe(clickTrack(12.0, 0.5),
                new AudioTranscriber.Options(tempo, meter, null));
    }

    /** Seconds one bar lasts according to the map. */
    private static double barSeconds(Score score) {
        return score.tempoMap().beatsToSeconds(
                score.tempoMap().initialTimeSignature().quarterBeatsPerBar());
    }

    @Test
    @DisplayName("reads a forced tempo as counted beats, so 120 in 6/8 is 120 dotted quarters")
    void forcedTempoIsInCountedBeats() {
        Score common = transcribeAt(120, TimeSignature.FOUR_FOUR);
        Score compound = transcribeAt(120, TimeSignature.SIX_EIGHT);

        // Common time: 120 quarter notes a minute, four to a bar, two seconds.
        assertThat(common.tempoMap().initialTempo()).isCloseTo(120.0, within(1e-9));
        assertThat(barSeconds(common)).isCloseTo(2.0, within(1e-9));

        // 6/8: 120 dotted quarters a minute is 180 quarter notes, and a bar holds
        // two of those dotted quarters, so one second. Reading the 120 as quarter
        // notes would give a 1.5-second bar and put every bar line in the wrong
        // place -- which is what the tracked path would never have done.
        assertThat(compound.tempoMap().initialTempo()).isCloseTo(180.0, within(1e-9));
        assertThat(barSeconds(compound)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("leaves the tracked path alone")
    void trackedPathStillTracks() {
        // The override is the branch under test, so pin the other one too: with no
        // override the map must still come from the pulses, at the click track's
        // 120 a minute, which in 6/8 is 180 quarter notes.
        Score compound = new AudioTranscriber().transcribe(clickTrack(12.0, 0.5),
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null));

        assertThat(compound.beatGrid()).isPresent();
        assertThat(compound.beatGrid().get().medianPulseRate()).isCloseTo(120.0, within(2.0));
        assertThat(compound.tempoMap().averageTempo(compound.durationSeconds()))
                .isCloseTo(180.0, within(3.0));
        // And the grid bars every two pulses, not every six. Asserted on the
        // positions rather than on a particular beat, because which pulse starts
        // the bar is chosen from onset strength and is not the claim here.
        assertThat(compound.beatGrid().get().beats())
                .allSatisfy(beat -> assertThat(beat.positionInBar()).isBetween(0, 1));
        assertThat(compound.beatGrid().get().downbeatTimes()).hasSizeGreaterThan(8);
    }
}
