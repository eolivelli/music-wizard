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
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A line alone is barred from its notes, not from chroma it has no harmony in (#869). */
class LineAloneMeterTest {

    private static final int SAMPLE_RATE = 22050;

    private static final double BEAT = 0.5;

    private final RunLog log = new RunLog();
    private final List<String> progress = new ArrayList<>();

    /** The flute take's phrase shape, a new pitch on every note. */
    private static final double[][] PHRASE = {{2, 1}, {1.5, 0.5, 1}, {2, 1}, {1, 1, 1}};

    private static AudioBuffer waltzLine(int bars) {
        int[] scale = {72, 74, 76, 77, 79, 77, 76, 74, 72, 71};
        float[] samples = new float[(int) (bars * 3 * BEAT * SAMPLE_RATE)];
        int at = 0;
        int note = 0;
        for (int bar = 0; bar < bars; bar++) {
            for (double length : PHRASE[bar % PHRASE.length]) {
                float[] tone = SignalFactory.sine(
                        SignalFactory.midiToHz(scale[note++ % scale.length]),
                        length * BEAT, SAMPLE_RATE);
                System.arraycopy(tone, 0, samples, at, Math.min(tone.length, samples.length - at));
                at += tone.length;
            }
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    private Score transcribe() {
        return transcribe(null);
    }

    private Score transcribe(TimeSignature supplied) {
        return new AudioTranscriber(progress::add, log).transcribe(waltzLine(24),
                new AudioTranscriber.Options(null, supplied, null, true));
    }

    /** Quarter beats between two instants well inside the line, two tracked pulses apart. */
    private static double quartersInTwoPulses(Score score) {
        double from = 10 * BEAT;
        return score.tempoMap().secondsToBeats(from + 2 * BEAT)
                - score.tempoMap().secondsToBeats(from);
    }

    @Test
    @DisplayName("a waltz played alone is barred in three")
    void aLineAloneInThree() {
        Score score = transcribe();

        assertThat(((KeyTrace) log.traces().get(KeyTrace.STAGE)).source())
                .as("the fixture is on the line-alone route")
                .isEqualTo(KeyTrace.FROM_MELODY);
        assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.THREE_FOUR);
        assertThat(progress).contains("a line alone: the meter is read from its notes");
        assertThat(log.stages().stream()
                .filter(stage -> stage.stage().equals(BeatTrace.STAGE))
                .findFirst().orElseThrow().facts().get("meter"))
                .startsWith("3/4, read from the melody's notes at ");
    }

    @Test
    @DisplayName("a supplied 6/8 on a line alone counts the tracked pulse as its dotted quarter (#887)")
    void aSuppliedCompoundMeterCountsThePulseAsItsBeat() {
        Score score = transcribe(TimeSignature.SIX_EIGHT);

        assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(quartersInTwoPulses(score)).isCloseTo(3.0, within(0.1));
        assertThat(score.beatGrid().orElseThrow().beats())
                .as("two tracked pulses to a bar")
                .extracting(beat -> beat.positionInBar())
                .containsOnly(0, 1);
    }

    @Test
    @DisplayName("a supplied 3/4 on the same line still counts the pulse as a quarter (#869)")
    void aSuppliedSimpleMeterKeepsTheQuarterPulse() {
        Score score = transcribe(TimeSignature.THREE_FOUR);

        assertThat(quartersInTwoPulses(score)).isCloseTo(2.0, within(0.1));
    }
}
