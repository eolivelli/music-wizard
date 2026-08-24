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
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the chord decoder leaves behind on the audio path (#677). */
class ChordTraceOnTheAudioPathTest {

    private static final int SAMPLE_RATE = 22050;

    private final RunLog log = new RunLog();

    private Score transcribe(AudioBuffer audio) {
        return new AudioTranscriber(message -> { }, log).transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));
    }

    private <T> T trace(String stage, Class<T> type) {
        Object recorded = log.traces().get(stage);
        assertThat(recorded).as("the run recorded no %s trace", stage).isInstanceOf(type);
        return type.cast(recorded);
    }

    private static Score fourChords(RunLog log) {
        return new AudioTranscriber(message -> { }, log).transcribe(
                new AudioBuffer(SignalFactory.clickTrackWithChords(120.0,
                        new double[][] {
                            SignalFactory.majorTriad(60),
                            SignalFactory.majorTriad(67),
                            SignalFactory.minorTriad(57),
                            SignalFactory.majorTriad(65),
                        }, 4, 16.0, SAMPLE_RATE), SAMPLE_RATE),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));
    }

    @Test
    @DisplayName("the decoder's spans are the front end's spans, index for index")
    void theTwoTracesShareTheirSpanIndices() {
        // The index is the only thing tying a reading to the decision made on
        // it: the chroma trace says what the span held and this one says what
        // was made of it, and nothing downstream would notice them drifting.
        Score score = fourChords(log);

        List<ChromaTrace.Span> read = trace(ChromaTrace.STAGE, ChromaTrace.class).spans();
        List<ChordTrace.Span> decided = trace(ChordTrace.STAGE, ChordTrace.class).spans();
        List<Chord> chords = score.chords().chords();
        assertThat(decided).hasSameSizeAs(chords);
        assertThat(decided).hasSameSizeAs(read);
        for (int i = 0; i < decided.size(); i++) {
            assertThat(decided.get(i).chord()).isEqualTo(chords.get(i).symbol());
            assertThat(decided.get(i).fromBeat()).isEqualTo(read.get(i).fromBeat());
            assertThat(decided.get(i).toBeat()).isEqualTo(read.get(i).toBeat());
            assertThat(decided.get(i).fromSeconds()).isEqualTo(read.get(i).fromSeconds());
        }
    }

    @Test
    @DisplayName("every span names what it beat, and every root names what settled it")
    void everySpanCarriesItsCompetition() {
        Score score = fourChords(log);

        ChordTrace trace = trace(ChordTrace.STAGE, ChordTrace.class);
        assertThat(trace.spans()).isNotEmpty();
        assertThat(trace.spans()).allSatisfy(span -> {
            assertThat(span.decoded()).isNotNull();
            assertThat(span.runnerUp()).isNotNull();
            assertThat(span.runnerUp().chord()).isNotEqualTo(span.decoded().chord());
            assertThat(span.settledBy()).isIn("decoder", "run", "sevenths", "thirds");
            // The pipeline measures a residual and every root of this fixture
            // is in the fit, so a span on a root with no gate reading would
            // mean the ablation never reached the record.
            assertThat(span.gates()).hasSize(span.chord().equals("N.C.") ? 0 : 6);
        });
        assertThat(trace.roots()).isNotEmpty();
        assertThat(trace.roots()).allSatisfy(root ->
                assertThat(root.thirds().beats()).isPositive());
        assertThat(score.chords().chords()).isNotEmpty();
    }

    @Test
    @DisplayName("a run that finds no pulse records no decoding rather than an empty one")
    void aRunWithNoPulseRecordsNoDecoding() {
        float[] blink = new float[(int) (0.1 * SAMPLE_RATE)];
        for (int i = 0; i < blink.length; i++) {
            blink[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }

        Score score = transcribe(new AudioBuffer(blink, SAMPLE_RATE));

        assertThat(score.chords().chords()).isEmpty();
        assertThat(log.traces()).doesNotContainKey(ChordTrace.STAGE);
    }
}
