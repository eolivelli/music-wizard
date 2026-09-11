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

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A melody's notes as onsets (#814): where each lands, what adding them does
 * to an envelope's scale, and the gate's voiced share.
 */
@DisplayName("notes as onsets")
class NoteOnsetTest {

    private static final int RATE = 22050;
    private static final int WINDOW = 2048;
    private static final int HOP = 256;
    private static final double FRAME_RATE = 172.0;

    private static NoteTrack melody(double... onsetSeconds) {
        List<Note> notes = new ArrayList<>();
        for (double onset : onsetSeconds) {
            notes.add(Note.ofSeconds(onset, 0.4, 69, Confidence.UNKNOWN));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "melody", notes, Confidence.UNKNOWN);
    }

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
    @DisplayName("each note is one event, at the frame its onset falls in")
    void eachNoteIsOneEvent() {
        OnsetEnvelope flat = new OnsetEnvelope(new double[400], FRAME_RATE);
        OnsetEnvelope with = flat.withNoteOnsets(melody(0.5, 1.0, 1.5));
        assertThat(peaks(with)).isEqualTo(3);
        int loudest = 0;
        for (int i = 0; i < with.length(); i++) {
            if (with.strength()[i] > with.strength()[loudest]) {
                loudest = i;
            }
        }
        assertThat(loudest).isIn(86, 172, 258);
    }

    @Test
    @DisplayName("a note past the envelope's end is dropped rather than thrown")
    void aNotePastTheEndIsDropped() {
        OnsetEnvelope shortEnvelope = new OnsetEnvelope(new double[60], FRAME_RATE);
        assertThat(peaks(shortEnvelope.withNoteOnsets(melody(0.1, 5.0)))).isEqualTo(1);
    }

    @Test
    @DisplayName("no notes leave the envelope as it was")
    void noNotesLeaveTheEnvelope() {
        double[] flux = new double[400];
        for (int i = 3; i < flux.length; i += 43) {
            flux[i] = 5;
        }
        OnsetEnvelope before = new OnsetEnvelope(flux.clone(), FRAME_RATE);
        OnsetEnvelope with = before.withNoteOnsets(melody());
        assertThat(peaks(with)).isEqualTo(peaks(before));
    }

    @Test
    @DisplayName("the sum is mean-zero and unit-variance, like the envelope it joined")
    void theSumKeepsTheEnvelopeContract() {
        double[] flux = new double[1000];
        for (int i = 0; i < flux.length; i += 43) {
            flux[i] = 5;
        }
        OnsetEnvelope with = new OnsetEnvelope(flux, FRAME_RATE)
                .withNoteOnsets(melody(0.3, 0.9, 1.5, 2.1, 2.7, 3.3, 3.9, 4.5));
        double mean = Arrays.stream(with.strength()).average().orElseThrow();
        double variance = Arrays.stream(with.strength()).map(v -> (v - mean) * (v - mean))
                .average().orElseThrow();
        assertThat(mean).isCloseTo(0, within(1e-9));
        assertThat(variance).isCloseTo(1, within(1e-9));
    }

    @Test
    @DisplayName("the notes' weight against the flux does not depend on how many there are")
    void theNotesWeightDoesNotDependOnTheirCount() {
        double[] flux = new double[1000];
        for (int i = 3; i < flux.length; i += 43) {
            flux[i] = 5;
        }
        // As many notes as attacks, between them.
        double[] onsets = new double[23];
        for (int i = 0; i < onsets.length; i++) {
            onsets[i] = (24 + 43 * i) / FRAME_RATE;
        }
        OnsetEnvelope with = new OnsetEnvelope(flux, FRAME_RATE).withNoteOnsets(melody(onsets));
        int note = 24 + 43 * 3;
        int attack = 3 + 43 * 3;
        double ratio = with.strength()[note] / with.strength()[attack];
        // Half as many notes, each one then twice as tall: the same ratio.
        double[] fewer = new double[12];
        for (int i = 0; i < fewer.length; i++) {
            fewer[i] = onsets[2 * i];
        }
        OnsetEnvelope sparse = new OnsetEnvelope(flux.clone(), FRAME_RATE).withNoteOnsets(melody(fewer));
        double sparseRatio = sparse.strength()[24 + 43 * 2] / sparse.strength()[attack];
        assertThat(ratio).isBetween(0.2, 1.0);
        assertThat(sparseRatio).isCloseTo(ratio * Math.sqrt(2), within(0.25 * ratio));
    }

    /**
     * The flute's shape: an attack on every beat, alternating between two
     * heights with the interval, so the envelope repeats at two beats more
     * strongly than at one. A note on every beat is what the recording
     * actually holds.
     */
    @Test
    @DisplayName("attacks that alternate in height read half tempo until the notes join them")
    void alternatingAttacksReadHalfTempoWithoutTheNotes() {
        double tempo = 100;
        double seconds = 30;
        double period = 60 / tempo;
        int frames = (int) (seconds * FRAME_RATE);
        double[] flux = new double[frames];
        java.util.Random noise = new java.util.Random(7);
        for (int i = 0; i < frames; i++) {
            flux[i] = 0.05 * noise.nextGaussian();
        }
        List<Double> onsets = new ArrayList<>();
        int beat = 0;
        for (double t = 0; t < seconds; t += period, beat++) {
            onsets.add(t);
            int at = (int) Math.round(t * FRAME_RATE);
            double height = beat % 2 == 0 ? 3 : 1;
            for (int k = -2; k <= 2; k++) {
                if (at + k >= 0 && at + k < frames) {
                    flux[at + k] += height * (1 - Math.abs(k) / 3.0);
                }
            }
        }
        OnsetEnvelope envelope = new OnsetEnvelope(flux, FRAME_RATE);
        assertThat(TempoEstimator.estimate(envelope).beatsPerMinute())
                .isCloseTo(tempo / 2, within(2.0));

        NoteTrack notes = melody(onsets.stream().mapToDouble(Double::doubleValue).toArray());
        assertThat(TempoEstimator.estimate(envelope.withNoteOnsets(notes)).beatsPerMinute())
                .isCloseTo(tempo, within(2.0));
    }

    @Test
    @DisplayName("the voiced share is read over the sounding stretch, not the whole track")
    void voicedShareIgnoresTheSilenceAroundTheMusic() {
        assertThat(track(0, 0, 60, 60, 0, 60, 0, 0).voicedShare()).isCloseTo(0.75, within(1e-9));
        assertThat(track(60, 60, 60).voicedShare()).isEqualTo(1.0);
        assertThat(track(0, 0).voicedShare()).isEqualTo(0.0);
    }
}
