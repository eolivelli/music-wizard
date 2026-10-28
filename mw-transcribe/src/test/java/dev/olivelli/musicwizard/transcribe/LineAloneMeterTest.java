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
import java.util.Arrays;
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

    /** A line in 6/8, in tracked pulses: a dotted quarter's beat divided long-short. */
    private static final double[][] COMPOUND_PHRASE = {
            {2.0 / 3, 1.0 / 3, 1}, {2.0 / 3, 1.0 / 3, 2.0 / 3, 1.0 / 3}, {1, 2.0 / 3, 1.0 / 3},
            {2}};

    private static AudioBuffer waltzLine(int bars) {
        return line(bars, PHRASE, 3, 0);
    }

    /**
     * {@code detached} seconds of silence end each note: the melody stage places
     * the note after a legato change late, where a 6/8 eighth reads as a dotted
     * sixteenth (#900).
     */
    private static AudioBuffer line(int bars, double[][] phrase, int pulsesPerBar,
                                    double detached) {
        return line(bars, phrase, pulsesPerBar, detached, false);
    }

    /** {@code ramped} fades each note in and out, so that its start is one onset. */
    private static AudioBuffer line(int bars, double[][] phrase, int pulsesPerBar,
                                    double detached, boolean ramped) {
        int[] scale = {72, 74, 76, 77, 79, 77, 76, 74, 72, 71};
        float[] samples = new float[(int) (bars * pulsesPerBar * BEAT * SAMPLE_RATE)];
        int at = 0;
        int note = 0;
        for (int bar = 0; bar < bars; bar++) {
            for (double length : phrase[bar % phrase.length]) {
                float[] tone = SignalFactory.sine(
                        SignalFactory.midiToHz(scale[note++ % scale.length]),
                        length * BEAT, SAMPLE_RATE);
                Arrays.fill(tone,
                        Math.max(0, tone.length - (int) (detached * SAMPLE_RATE)), tone.length, 0f);
                if (ramped) {
                    int stop = Math.max(0, tone.length - (int) (detached * SAMPLE_RATE));
                    int ramp = Math.min((int) (0.01 * SAMPLE_RATE), stop / 2);
                    for (int i = 0; i < ramp; i++) {
                        tone[i] *= i / (float) ramp;
                        tone[stop - 1 - i] *= i / (float) ramp;
                    }
                }
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
        return transcribe(waltzLine(24), supplied);
    }

    private Score transcribe(AudioBuffer audio, TimeSignature supplied) {
        return new AudioTranscriber(progress::add, log).transcribe(audio,
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
    @DisplayName("a line in 6/8 is read as a supplied 6/8 bars it (#871)")
    void aCompoundLineIsReadAsSupplied() {
        AudioBuffer audio = line(36, COMPOUND_PHRASE, 2, 0.02);
        Score read = transcribe(audio, null);
        assertThat(log.stages().stream()
                .filter(stage -> stage.stage().equals(BeatTrace.STAGE))
                .findFirst().orElseThrow().facts().get("meter"))
                .startsWith("6/8, read from the melody's notes at ");
        Score supplied = transcribe(audio, TimeSignature.SIX_EIGHT);

        assertThat(read.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(quartersInTwoPulses(read)).isCloseTo(3.0, within(0.1));
        assertThat(read.beatGrid().orElseThrow().beats())
                .extracting(beat -> beat.positionInBar())
                .isEqualTo(supplied.beatGrid().orElseThrow().beats().stream()
                        .map(beat -> beat.positionInBar()).toList());
        for (double seconds = 1; seconds < 30; seconds += 1.7) {
            assertThat(read.tempoMap().secondsToBeats(seconds))
                    .isEqualTo(supplied.tempoMap().secondsToBeats(seconds));
        }
    }

    /** Facts the beat stage recorded on the last run. */
    private java.util.Map<String, String> beatFacts() {
        return log.stages().stream()
                .filter(stage -> stage.stage().equals(BeatTrace.STAGE))
                .reduce((first, second) -> second).orElseThrow().facts();
    }

    @Test
    @DisplayName("bars of 3/4 with the eighth held inside 6/8 are proposed as a change to paste (#895)")
    void anEighthHeldRunIsProposed() {
        double[][] phrase = new double[36][];
        for (int bar = 0; bar < phrase.length; bar++) {
            phrase[bar] = bar >= 16 && bar < 19
                    ? new double[] {2.0 / 3, 2.0 / 3, 2.0 / 3}
                    : COMPOUND_PHRASE[bar % COMPOUND_PHRASE.length];
        }
        AudioBuffer audio = line(36, phrase, 2, 0.02, true);

        Score supplied = transcribe(audio, TimeSignature.SIX_EIGHT);
        String proposal = beatFacts().get(AudioTranscriber.PROPOSED_METER);

        assertThat(proposal).matches("--time-signature 6/8,3/4@\\d+\\.\\d\\d=8,6/8@\\d+\\.\\d\\d=8");
        String[] changes = proposal.substring("--time-signature ".length()).split(",");
        double into = Double.parseDouble(changes[1].substring(4, changes[1].length() - 2));
        double back = Double.parseDouble(changes[2].substring(4, changes[2].length() - 2));
        assertThat(into).isCloseTo(16 * 2 * BEAT, within(BEAT / 4));
        assertThat(back).isCloseTo(19 * 2 * BEAT, within(BEAT / 4));
        assertThat(supplied.tempoMap().meterChanges()).hasSize(1);

        Score pasted = new AudioTranscriber(progress::add, log).transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null, true, null,
                        List.of(new AudioTranscriber.MeterChangeAt(into, TimeSignature.THREE_FOUR, true),
                                new AudioTranscriber.MeterChangeAt(back, TimeSignature.SIX_EIGHT, true))));

        assertThat(pasted.tempoMap().meterChanges())
                .extracting(change -> change.timeSignature())
                .containsExactly(TimeSignature.SIX_EIGHT, TimeSignature.THREE_FOUR,
                        TimeSignature.SIX_EIGHT);
        assertThat(beatFacts()).doesNotContainKey(AudioTranscriber.PROPOSED_METER);
    }

    @Test
    @DisplayName("nothing is proposed on a 6/8 line without such a run, nor on a waltz")
    void nothingIsProposedWithoutTheRun() {
        transcribe(line(36, COMPOUND_PHRASE, 2, 0.02, true), TimeSignature.SIX_EIGHT);
        assertThat(beatFacts()).doesNotContainKey(AudioTranscriber.PROPOSED_METER);
        transcribe();
        assertThat(beatFacts()).doesNotContainKey(AudioTranscriber.PROPOSED_METER);
    }

    @Test
    @DisplayName("a supplied 3/4 on the same line still counts the pulse as a quarter (#869)")
    void aSuppliedSimpleMeterKeepsTheQuarterPulse() {
        Score score = transcribe(TimeSignature.THREE_FOUR);

        assertThat(quartersInTwoPulses(score)).isCloseTo(2.0, within(0.1));
    }
}
