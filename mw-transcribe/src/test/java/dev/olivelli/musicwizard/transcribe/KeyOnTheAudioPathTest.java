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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the audio path now names a key at all, and names it over the whole
 * recording.
 *
 * <p>Tier 1: synthetic triads over a click track, so the key that comes out is
 * the one the fixture was built from and a failure is the wiring rather than the
 * front end. {@link dev.olivelli.musicwizard.dsp.KeyEstimator} owns the rules;
 * what real recordings do is {@code tools/score-samples.py}.
 */
class KeyOnTheAudioPathTest {

    private static final int SAMPLE_RATE = 22050;

    /** Sixteen seconds of a four-bar loop at 120 BPM in four. */
    private static AudioBuffer loop(double[][] bars) {
        return new AudioBuffer(
                SignalFactory.clickTrackWithChords(120.0, bars, 4, 16.0, SAMPLE_RATE),
                SAMPLE_RATE);
    }

    private static Score transcribe(AudioBuffer audio, List<String> progress) {
        return new AudioTranscriber(progress::add).transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));
    }

    @Test
    @DisplayName("a minor loop with its dominant comes back as a minor key")
    void aMinorLoopIsNamedMinor() {
        // Am - Dm - E - Am. Every note of it is in C major too, except the G
        // sharp in the E chord, which is what makes the answer A minor rather
        // than its relative.
        List<String> progress = new ArrayList<>();
        Score score = transcribe(loop(new double[][] {
            SignalFactory.minorTriad(57), // Am
            SignalFactory.minorTriad(62), // Dm
            SignalFactory.majorTriad(64), // E
            SignalFactory.minorTriad(57), // Am
        }), progress);

        assertThat(score.primaryKey()).isPresent();
        assertThat(score.primaryKey().orElseThrow().displayName()).isEqualTo("A minor");
        assertThat(progress).anyMatch(line -> line.startsWith("key A minor"));
    }

    @Test
    @DisplayName("the key covers the whole recording, not just the chords")
    void theKeyCoversTheWholeRecording() {
        Score score = transcribe(loop(new double[][] {
            SignalFactory.majorTriad(60), // C
            SignalFactory.majorTriad(67), // G
            SignalFactory.minorTriad(57), // Am
            SignalFactory.majorTriad(65), // F
        }), new ArrayList<>());

        // Both ends, because the estimator's own span starts at the first chord
        // it named and ends at the last, and neither is where the recording is.
        assertThat(score.keyAt(0)).as("the lead-in").isPresent();
        assertThat(score.keyAt(score.durationSeconds() - 1e-6)).as("the tail").isPresent();
        assertThat(score.keys()).hasSize(1);
    }

    @Test
    @DisplayName("silence names no key rather than a default one")
    void silenceNamesNoKey() {
        List<String> progress = new ArrayList<>();
        Score score = transcribe(
                new AudioBuffer(new float[SAMPLE_RATE * 4], SAMPLE_RATE), progress);

        assertThat(score.keys()).isEmpty();
        assertThat(score.primaryKey()).isEmpty();
    }
}
