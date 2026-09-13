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
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A line alone names no chord (#843). */
class LineAloneChordsTest {

    private static final int SAMPLE_RATE = 22050;

    private static final AudioTranscriber.Options WITH_MELODY =
            new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null, true);

    private static final AudioTranscriber.Options WITHOUT_MELODY =
            new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null);

    private final RunLog log = new RunLog();
    private final List<String> progress = new ArrayList<>();

    private Score transcribe(AudioBuffer audio, AudioTranscriber.Options options) {
        return new AudioTranscriber(progress::add, log).transcribe(audio, options);
    }

    /** One sine per note, a note a beat, no rest: voiced throughout. */
    private static AudioBuffer lineAlone(int... midiPitches) {
        double beat = 0.5;
        float[] samples = new float[(int) (midiPitches.length * beat * SAMPLE_RATE)];
        int at = 0;
        for (int pitch : midiPitches) {
            float[] note = SignalFactory.sine(SignalFactory.midiToHz(pitch), beat, SAMPLE_RATE);
            System.arraycopy(note, 0, samples, at, Math.min(note.length, samples.length - at));
            at += note.length;
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    /** The flute take's tune in C, twice over: E F G F E F G F E F E D C D C. */
    private static AudioBuffer tuneInC() {
        int[] once = {76, 77, 79, 77, 76, 77, 79, 77, 76, 77, 76, 74, 72, 74, 72, 72};
        int[] twice = new int[once.length * 2];
        System.arraycopy(once, 0, twice, 0, once.length);
        System.arraycopy(once, 0, twice, once.length, once.length);
        return lineAlone(twice);
    }

    private ChordTrace chordTrace() {
        Object trace = log.traces().get(ChordTrace.STAGE);
        assertThat(trace).as("the run recorded no chord trace").isInstanceOf(ChordTrace.class);
        return (ChordTrace) trace;
    }

    private RunManifest.StageRun chordStage() {
        return log.stages().stream()
                .filter(stage -> stage.stage().equals(ChordTrace.STAGE))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("on the line-alone route the score carries one no-chord span and says why")
    void aLineAloneNamesNoChord() {
        Score score = transcribe(tuneInC(), WITH_MELODY);

        assertThat(((KeyTrace) log.traces().get(KeyTrace.STAGE)).source())
                .as("the fixture is on the line-alone route")
                .isEqualTo(KeyTrace.FROM_MELODY);
        ChordProgression chords = score.chords();
        assertThat(chords.chords()).hasSize(1);
        Chord span = chords.chords().getFirst();
        assertThat(span.isNoChord()).isTrue();
        assertThat(span.confidence()).isEqualTo(Confidence.UNKNOWN);

        // The record keeps what the estimator read, over the same extent.
        ChordTrace trace = chordTrace();
        assertThat(trace.spans().size()).isGreaterThan(1);
        assertThat(span.startSeconds()).isEqualTo(trace.spans().getFirst().fromSeconds());
        assertThat(span.endSeconds()).isEqualTo(trace.spans().getLast().toSeconds());
        assertThat(chordStage().reason()).contains("a line alone");
        assertThat(progress).anyMatch(line -> line.startsWith("a line alone: nothing sounds"));
    }

    @Test
    @DisplayName("the same recording analysed without the melody keeps the estimator's spans")
    void withoutTheMelodyTheChordsStand() {
        Score score = transcribe(tuneInC(), WITHOUT_MELODY);

        assertThat(score.chords().chords().size()).isEqualTo(chordTrace().spans().size());
        assertThat(chordStage().reason()).isNull();
        assertThat(progress).anyMatch(line -> line.startsWith("found ") && line.endsWith(" chord spans"));
    }

    @Test
    @DisplayName("a melody read from a separated stem leaves the chords as estimated")
    void aStemLeavesTheChordsAlone() {
        AudioBuffer band = new AudioBuffer(SignalFactory.clickTrackWithChords(120.0,
                new double[][] {
                    SignalFactory.majorTriad(60),
                    SignalFactory.majorTriad(65),
                    SignalFactory.majorTriad(67),
                    SignalFactory.majorTriad(60),
                }, 4, 16.0, SAMPLE_RATE), SAMPLE_RATE);

        Score score = new AudioTranscriber(progress::add, log).transcribe(band, WITH_MELODY,
                () -> new AudioBuffer(SignalFactory.sine(
                        SignalFactory.midiToHz(72), 16.0, SAMPLE_RATE), SAMPLE_RATE));

        assertThat(score.chords().chords()).anyMatch(chord -> !chord.isNoChord());
        assertThat(chordStage().reason()).isNull();
    }

    @Test
    @DisplayName("an estimator that named nothing is left as it is")
    void anEmptyProgressionStaysEmpty() {
        assertThat(AudioTranscriber.noChordThroughout(ChordProgression.empty()).isEmpty()).isTrue();
    }
}
