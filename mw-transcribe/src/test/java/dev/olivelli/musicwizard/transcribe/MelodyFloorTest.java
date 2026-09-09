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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.dsp.PitchTracker;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The floor an instrument's player names reaches the tracker and keeps the tune above it. */
class MelodyFloorTest {

    private static final int RATE = AudioDecoder.ANALYSIS_SAMPLE_RATE;
    private static final int LOW = 45;
    private static final int HIGH = 69;

    /** A tune two octaves over a harmonic note, both plucked on every beat so a grid exists. */
    private static AudioBuffer twoHands(double seconds) {
        int length = (int) (seconds * RATE);
        float[] out = new float[length];
        double beat = 0.5;
        for (int i = 0; i < length; i++) {
            double t = i / (double) RATE;
            double envelope = Math.exp(-4 * (t - Math.floor(t / beat) * beat));
            double value = 0;
            for (int partial = 1; partial <= 6; partial++) {
                value += 0.3 * Math.pow(0.7, partial - 1)
                        * Math.sin(2 * Math.PI * SignalFactory.midiToHz(LOW) * partial * t);
            }
            value += 1.0 * Math.sin(2 * Math.PI * SignalFactory.midiToHz(HIGH) * t);
            out[i] = (float) (envelope * value);
        }
        return new AudioBuffer(out, RATE);
    }

    private static List<Integer> melodyPitches(Score score) {
        return score.track(PartRole.LEAD_VOCAL).orElseThrow().notes().stream()
                .map(Note::midiPitch).toList();
    }

    @Test
    @DisplayName("without a floor the louder low note is the melody; with one the tune above it is")
    void theFloorKeepsTheTuneAboveTheAccompaniment() {
        AudioBuffer audio = twoHands(8);
        AudioTranscriber transcriber = new AudioTranscriber();

        Score any = transcriber.transcribe(audio,
                new AudioTranscriber.Options(null, null, null, true));
        Score raised = transcriber.transcribe(audio,
                new AudioTranscriber.Options(null, null, null, true, SignalFactory.midiToHz(52)));

        assertThat(melodyPitches(any)).isNotEmpty().allMatch(pitch -> pitch < 52);
        assertThat(melodyPitches(raised)).isNotEmpty().allMatch(pitch -> pitch == HIGH);
    }

    @Test
    @DisplayName("a floor outside the tracker's bounds is refused before any audio is read")
    void refusesAFloorTheTrackerCannotHold() {
        assertThatThrownBy(() -> new AudioTranscriber.Options(null, null, null, true,
                PitchTracker.MAX_HZ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
        assertThatThrownBy(() -> AudioTranscriber.Options.floorUnder(PitchSpelling.parse("C7")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C7 is above");
    }

    @Test
    @DisplayName("a floor with the melody stage off is a contradiction, refused as one")
    void refusesAFloorWithoutTheStage() {
        assertThatThrownBy(() -> new AudioTranscriber.Options(null, null, null, false, 160.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs the melody stage");
    }
}
