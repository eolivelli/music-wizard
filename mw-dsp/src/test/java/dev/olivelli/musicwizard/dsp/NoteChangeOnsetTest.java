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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pitch track's note changes as onsets (#814): which frames count as a
 * change, and what adding them does to an envelope's scale.
 */
@DisplayName("note changes as onsets")
class NoteChangeOnsetTest {

    private static final int RATE = 22050;
    private static final int WINDOW = 2048;
    private static final int HOP = 256;
    private static final double FRAME_RATE = 172.0;

    /** A track holding each pitch for a run of frames; a pitch of zero is silence. */
    private static PitchTrack track(double... midiPerRun) {
        int run = 40;
        double[] hz = new double[midiPerRun.length * run];
        boolean[] voiced = new boolean[hz.length];
        double[] voicedness = new double[hz.length];
        for (int i = 0; i < hz.length; i++) {
            double midi = midiPerRun[i / run];
            voiced[i] = midi > 0;
            hz[i] = 440 * Math.pow(2, ((midi > 0 ? midi : 69) - 69) / 12.0);
            voicedness[i] = voiced[i] ? 1 : 0;
        }
        return new PitchTrack(hz, voiced, voicedness, RATE, WINDOW, HOP);
    }

    private static OnsetEnvelope flat(PitchTrack track) {
        int frames = (int) Math.ceil(track.timeOf(track.frameCount()) * FRAME_RATE) + 1;
        return new OnsetEnvelope(new double[frames], FRAME_RATE);
    }

    private static int peaks(OnsetEnvelope envelope) {
        double[] s = envelope.strength();
        int count = 0;
        for (int i = 1; i < s.length - 1; i++) {
            if (s[i] > 0 && s[i] > s[i - 1] && s[i] >= s[i + 1]) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("a step to another note is one event, at the frame the step is heard")
    void aStepIsOneEvent() {
        PitchTrack track = track(60, 60, 67, 67);
        OnsetEnvelope with = flat(track).withNoteChanges(track);
        assertThat(peaks(with)).isEqualTo(2);
        int stepFrame = (int) Math.round(track.timeOf(80) * FRAME_RATE);
        int loudest = 0;
        for (int i = 1; i < with.length(); i++) {
            if (with.strength()[i] > with.strength()[loudest] && i > 20) {
                loudest = i;
            }
        }
        assertThat(loudest).isEqualTo(stepFrame);
    }

    @Test
    @DisplayName("a wobble inside the held note is not an event")
    void aWobbleIsNotAnEvent() {
        PitchTrack track = track(60, 60.3, 59.8, 60.4);
        assertThat(peaks(flat(track).withNoteChanges(track))).isEqualTo(1);
    }

    @Test
    @DisplayName("the voice returning after silence is an event, and the silence is none")
    void returningAfterSilenceIsAnEvent() {
        PitchTrack track = track(60, 0, 60, 60);
        assertThat(peaks(flat(track).withNoteChanges(track))).isEqualTo(2);
    }

    @Test
    @DisplayName("a change past the envelope's end is dropped rather than thrown")
    void aChangePastTheEndIsDropped() {
        PitchTrack track = track(60, 67, 60, 67);
        OnsetEnvelope shortEnvelope = new OnsetEnvelope(new double[60], FRAME_RATE);
        assertThat(peaks(shortEnvelope.withNoteChanges(track))).isEqualTo(1);
    }

    @Test
    @DisplayName("the sum is mean-zero and unit-variance, like the envelope it joined")
    void theSumKeepsTheEnvelopeContract() {
        PitchTrack track = track(60, 67, 60, 67, 60, 67);
        OnsetEnvelope flat = flat(track);
        double[] flux = new double[flat.length()];
        for (int i = 0; i < flux.length; i += 43) {
            flux[i] = 5;
        }
        OnsetEnvelope with = new OnsetEnvelope(flux, FRAME_RATE).withNoteChanges(track);
        double mean = Arrays.stream(with.strength()).average().orElseThrow();
        double variance = Arrays.stream(with.strength()).map(v -> (v - mean) * (v - mean))
                .average().orElseThrow();
        assertThat(mean).isCloseTo(0, within(1e-9));
        assertThat(variance).isCloseTo(1, within(1e-9));
    }

    @Test
    @DisplayName("the changes carry as much weight as the flux, however many there are")
    void theChangesWeighAsMuchAsTheFlux() {
        PitchTrack track = track(60, 67, 60, 67, 60, 67);
        OnsetEnvelope flat = flat(track);
        double[] flux = new double[flat.length()];
        for (int i = 3; i < flux.length; i += 43) {
            flux[i] = 5;
        }
        OnsetEnvelope with = new OnsetEnvelope(flux, FRAME_RATE).withNoteChanges(track);
        int change = (int) Math.round(track.timeOf(40) * FRAME_RATE);
        int attack = 3 + 43 * 3;
        assertThat(with.strength()[change]).isCloseTo(with.strength()[attack],
                within(0.35 * with.strength()[attack]));
    }

    @Test
    @DisplayName("the voiced share is read over the sounding stretch, not the whole track")
    void voicedShareIgnoresTheSilenceAroundTheMusic() {
        assertThat(track(0, 0, 60, 60, 0, 60, 0, 0).voicedShare()).isCloseTo(0.75, within(1e-9));
        assertThat(track(60, 60, 60).voicedShare()).isEqualTo(1.0);
        assertThat(track(0, 0).voicedShare()).isEqualTo(0.0);
    }
}
