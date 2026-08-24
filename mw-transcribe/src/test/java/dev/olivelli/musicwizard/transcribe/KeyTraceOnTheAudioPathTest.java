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
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the key estimator leaves behind on the audio path (#678). */
class KeyTraceOnTheAudioPathTest {

    private static final int SAMPLE_RATE = 22050;

    private final RunLog log = new RunLog();

    private Score transcribe(AudioBuffer audio) {
        return new AudioTranscriber(message -> { }, log).transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));
    }

    private KeyTrace recorded() {
        Object trace = log.traces().get(KeyTrace.STAGE);
        assertThat(trace).as("the run recorded no key trace").isInstanceOf(KeyTrace.class);
        return (KeyTrace) trace;
    }

    /** Am - Dm - E - Am, whose E carries the raised seventh of A minor. */
    private static AudioBuffer minorLoop() {
        return new AudioBuffer(SignalFactory.clickTrackWithChords(120.0,
                new double[][] {
                    SignalFactory.minorTriad(57),
                    SignalFactory.minorTriad(62),
                    SignalFactory.majorTriad(64),
                    SignalFactory.minorTriad(57),
                }, 4, 16.0, SAMPLE_RATE), SAMPLE_RATE);
    }

    @Test
    @DisplayName("the trace names the key the score carries, and every key it beat")
    void theTraceNamesWhatTheScoreCarries() {
        Score score = transcribe(minorLoop());

        KeyTrace trace = recorded();
        assertThat(trace.source()).isEqualTo(KeyTrace.FROM_CHORDS);
        assertThat(trace.tonic().winner())
                .isEqualTo(score.primaryKey().orElseThrow().displayName());
        assertThat(trace.signature().winner()).isEqualTo(trace.tonic().winner());
        // Twelve tonics in two modes, so a shorter list means a candidate went
        // unrecorded rather than unscored.
        assertThat(trace.candidates()).hasSize(24);
        assertThat(trace.candidates()).extracting(KeyTrace.Candidate::key)
                .contains(trace.tonic().winner(), trace.tonic().runnerUp(),
                        trace.signature().runnerUp());
    }

    @Test
    @DisplayName("what separated the relative pair is in the trace, not only the margin")
    void theEvidenceBehindTheTonicIsRecorded() {
        // The fixture's E major is a chord on the fifth degree of A minor with
        // a major third, which is the raised seventh C major does not hold --
        // the strongest evidence the estimator has, and the whole reason this
        // loop comes back minor.
        transcribe(minorLoop());

        KeyTrace trace = recorded();
        KeyTrace.Candidate home = candidate(trace, trace.tonic().winner());
        KeyTrace.Candidate relative = candidate(trace, trace.tonic().runnerUp());
        assertThat(home.raisedSeventhSpans()).isPositive();
        assertThat(home.raisedSeventhSeconds()).isPositive();
        assertThat(relative.raisedSeventhSpans()).isZero();
        assertThat(home.score() - relative.score()).isEqualTo(trace.tonic().margin());
        assertThat(trace.tonic().read()).isEqualTo("separated");
    }

    @Test
    @DisplayName("how much of the span carried a chord is recorded beside the margins")
    void whatWasWeighedIsRecorded() {
        Score score = transcribe(minorLoop());

        KeyTrace trace = recorded();
        assertThat(trace.spanSeconds()).isEqualTo(score.durationSeconds());
        assertThat(trace.soundingSeconds()).isPositive()
                .isLessThanOrEqualTo(trace.spanSeconds());
        assertThat(trace.weighed()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a recording that names no key records no weighing rather than an empty one")
    void silenceRecordsNoTrace() {
        Score score = transcribe(new AudioBuffer(new float[SAMPLE_RATE * 4], SAMPLE_RATE));

        assertThat(score.keys()).isEmpty();
        assertThat(log.traces()).doesNotContainKey(KeyTrace.STAGE);
    }

    private static KeyTrace.Candidate candidate(KeyTrace trace, String key) {
        return trace.candidates().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow();
    }
}
