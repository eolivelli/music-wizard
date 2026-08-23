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
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the chroma front end leaves behind on the audio path (#676). */
class ChromaTraceOnTheAudioPathTest {

    private static final int SAMPLE_RATE = 22050;

    private final RunLog log = new RunLog();

    private Score transcribe(AudioBuffer audio) {
        return new AudioTranscriber(message -> { }, log).transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));
    }

    private ChromaTrace trace() {
        Object recorded = log.traces().get(ChromaTrace.STAGE);
        assertThat(recorded).as("the run recorded no chroma trace")
                .isInstanceOf(ChromaTrace.class);
        return (ChromaTrace) recorded;
    }

    @Test
    @DisplayName("the spans tile the beat-synchronous grid, with no gap and no overlap")
    void theSpansTileTheBeatGrid() {
        Score score = transcribe(new AudioBuffer(SignalFactory.clickTrackWithChords(120.0,
                new double[][] {
                    SignalFactory.majorTriad(60),
                    SignalFactory.majorTriad(67),
                    SignalFactory.minorTriad(57),
                    SignalFactory.majorTriad(65),
                }, 4, 16.0, SAMPLE_RATE), SAMPLE_RATE));

        // Span i of the trace has to be span i of the chroma the decisions were
        // read from: the indices are the only thing tying a recorded reading to
        // the beats it was taken over, and nothing downstream would notice them
        // drifting.
        int beatSpans = score.beatGrid().orElseThrow().beatTimes().size() - 1;
        List<ChromaTrace.Span> spans = trace().spans();
        assertThat(spans).hasSize(score.chords().size());
        assertThat(spans.get(0).fromBeat()).isZero();
        assertThat(spans.get(spans.size() - 1).toBeat()).isEqualTo(beatSpans);
        for (int i = 1; i < spans.size(); i++) {
            assertThat(spans.get(i).fromBeat()).isEqualTo(spans.get(i - 1).toBeat());
        }
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.toBeat()).isGreaterThan(span.fromBeat());
            assertThat(span.significance()).hasSize(12);
            assertThat(span.combined()).hasSize(12);
        });
    }

    @Test
    @DisplayName("a run that finds no pulse still records the tuning and the model")
    void aRunWithNoPulseStillRecordsTheFrontEnd() {
        // The front end runs before the beats and everything below them stops
        // here, so this is the only run in which the line it records first is
        // the line the workspace keeps.
        float[] blink = new float[(int) (0.1 * SAMPLE_RATE)];
        for (int i = 0; i < blink.length; i++) {
            blink[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }

        Score score = transcribe(new AudioBuffer(blink, SAMPLE_RATE));

        assertThat(score.beatGrid()).isEmpty();
        assertThat(trace().fit()).isNotNull();
        assertThat(trace().spans()).isEmpty();
    }
}
