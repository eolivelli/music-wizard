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

package dev.olivelli.musicwizard.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the melody stage writes down about the notes it cut (#679). */
class MelodyTraceTest {

    private static MelodyTrace trace() {
        return new MelodyTrace(MelodyTrace.SEPARATED_VOCAL,
                new MelodyTrace.Track(22050, 2048, 256, 86.13, 800, 240, 9.29),
                new MelodyTrace.Tuning(0.14, 0.31, 0.2, 0.14, MelodyTrace.Tuning.CORROBORATED),
                new MelodyTrace.Fold(67.5, 14, MelodyTrace.Fold.APPLIED),
                List.of(new MelodyTrace.Run(1.5, 3.25, 4, 3, 1, 1, 3, 0.12)),
                List.of(new MelodyTrace.Gesture(1.62, 3.25, 3, 79, 81, 80, -12,
                        MelodyTrace.Gesture.MOVED)),
                List.of(new MelodyTrace.Note(1.62, 2.1, 67, MelodyTrace.Note.RUN, 0, 0, -12),
                        new MelodyTrace.Note(2.1, 2.7, 69, MelodyTrace.Note.PITCH, 0, 0, -12),
                        new MelodyTrace.Note(2.7, 3.25, 69,
                                MelodyTrace.Note.REARTICULATION, 0, 0, -12)));
    }

    @Test
    @DisplayName("comes back from the file a workspace keeps it in as it went in")
    void survivesTheRoundTrip() {
        RunTraces written = RunTraceJson.of(Map.of(MelodyTrace.STAGE, trace()));

        RunTraces read = RunTraceJson.fromJson(RunTraceJson.toJson(written));

        assertThat(read.trace(MelodyTrace.STAGE, MelodyTrace.class)).contains(trace());
    }

    @Test
    @DisplayName("whether a tuning was measured is read off the offset, not stored")
    void whetherATuningWasMeasuredIsDerived() {
        // A record an earlier build wrote carries no such field, and a stored
        // one would default to false and contradict its own offset. So it is
        // derived — and must stay out of the file it is derived from.
        String json = RunTraceJson.toJson(RunTraceJson.of(Map.of(MelodyTrace.STAGE, trace())));

        assertThat(json).doesNotContain("measured");
        assertThat(trace().tuning().measured()).isTrue();
        assertThat(new MelodyTrace.Tuning(0, null, 0.2, 0, MelodyTrace.Tuning.CONCERT_PITCH)
                .measured()).as("a zero offset is the estimator's no-evidence answer").isFalse();
    }

    @Test
    @DisplayName("a stage that found nothing round-trips as one that found nothing")
    void anEmptyPassSurvivesTheRoundTrip() {
        MelodyTrace empty = new MelodyTrace(MelodyTrace.FULL_MIX,
                new MelodyTrace.Track(22050, 2048, 256, 86.13, 800, 0, 9.29),
                new MelodyTrace.Tuning(0.001, null, 0.2, 0, MelodyTrace.Tuning.CONCERT_PITCH),
                null, List.of(), List.of(), List.of());

        MelodyTrace read = RunTraceJson.fromJson(
                        RunTraceJson.toJson(RunTraceJson.of(Map.of(MelodyTrace.STAGE, empty))))
                .trace(MelodyTrace.STAGE, MelodyTrace.class).orElseThrow();

        assertThat(read).isEqualTo(empty);
        // The three absences the page words separately: a track with nothing
        // voiced in it, an agreement nothing measured, and a fold no note
        // reached.
        assertThat(read.track().voicedFrames()).isZero();
        assertThat(read.tuning().agreement()).isNull();
        assertThat(read.fold()).isNull();
    }

    @Test
    @DisplayName("the signal is added by the caller that knows which one it was")
    void theSignalIsTheCallersToName() {
        MelodyTrace unnamed = new MelodyTrace(null, null, null, null,
                List.of(), List.of(), List.of());

        assertThat(unnamed.signal()).isNull();
        assertThat(unnamed.readFrom(MelodyTrace.FULL_MIX).signal())
                .isEqualTo(MelodyTrace.FULL_MIX);
    }

    @Test
    @DisplayName("a note or a gesture with nothing to draw it by is refused")
    void everyRecordNamesWhatThePagePrints() {
        assertThatThrownBy(() -> new MelodyTrace.Note(0, 1, 60, null, 0, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MelodyTrace.Gesture(0, 1, 1, 60, 60, 60, 0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MelodyTrace.Fold(60, 14, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MelodyTrace.Tuning(0, null, 0.2, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a trace written by a build whose shape has moved on reads as absent")
    void anUnreadableTraceIsAbsent() {
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"melody\":{\"notes\":[{\"pitch\":60}]}}}");
        RunTraces sentence = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"melody\":\"a sentence, not a trace\"}}");

        assertThat(renamed.trace(MelodyTrace.STAGE, MelodyTrace.class)).isEmpty();
        assertThat(sentence.trace(MelodyTrace.STAGE, MelodyTrace.class)).isEmpty();
    }
}
