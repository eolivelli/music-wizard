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
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A line alone whose first note is a downbeat, with one tracked pulse in the
 * silence before it, opens the map on that downbeat (#859).
 */
class SilentPulseBeforeTheLineTest {

    private static final int SAMPLE_RATE = 22050;
    private static final double BEAT = 0.5;

    private static final int[] TUNE = {76, 77, 79, 77, 76, 77, 79, 77, 76, 77, 76, 74, 72, 74, 72, 72,
        76, 77, 79, 77, 76, 77, 79, 77, 76, 77, 76, 74, 72, 74, 72, 72};

    /** The tune from {@code firstNote}, and before it a pickup or nothing. */
    private static AudioBuffer line(double firstNote, boolean pickup) {
        float[] samples = new float[(int) ((firstNote + TUNE.length * BEAT) * SAMPLE_RATE)];
        if (pickup) {
            place(samples, 74, firstNote - BEAT);
        }
        for (int i = 0; i < TUNE.length; i++) {
            place(samples, TUNE[i], firstNote + i * BEAT);
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    private static void place(float[] samples, int pitch, double at) {
        float[] note = SignalFactory.sine(SignalFactory.midiToHz(pitch), BEAT, SAMPLE_RATE);
        int from = (int) Math.round(at * SAMPLE_RATE);
        System.arraycopy(note, 0, samples, from, Math.min(note.length, samples.length - from));
    }

    private static Score transcribe(double firstNote, boolean pickup, Double tempo) {
        // The phase is supplied so the fixture tests the lead-in, not the
        // downbeat estimator.
        return new AudioTranscriber().transcribe(line(firstNote, pickup), new AudioTranscriber.Options(
                tempo, TimeSignature.FOUR_FOUR, firstNote, true));
    }


    private static void assertOnePulseBeforeTheDownbeat(Score score, double firstNote) {
        List<Double> beats = score.beatGrid().orElseThrow().beatTimes();
        assertThat(beats.get(0))
                .as("the fixture's tracker puts a pulse inside the unmodelled stretch")
                .isLessThan(TempoMap.UNMODELLED_LEAD_IN_PULSES * BEAT);
        assertThat(beats.get(1)).isCloseTo(firstNote, within(BEAT / 4));
    }

    @ParameterizedTest(name = "first note at {0} s, --tempo {1}")
    @CsvSource({"0.53,", "0.53, 120", "0.60,", "0.60, 120"})
    @DisplayName("nothing played on the pulse before the downbeat: the map opens on the downbeat")
    void aSilentPulseIsLeftOut(double firstNote, Double tempo) {
        Score score = transcribe(firstNote, false, tempo);
        assertOnePulseBeforeTheDownbeat(score, firstNote);
        TempoMap map = score.tempoMap();
        double downbeat = score.beatGrid().orElseThrow().beatTimes().get(1);

        assertThat(map.segments()).noneMatch(
                segment -> segment.provenance() == Provenance.DERIVED);
        // A typed tempo holds its own pulse from the origin, so the tracked
        // downbeat sits on the bar line only as nearly as the two rates agree.
        assertThat(map.secondsToBeats(downbeat)).as("the downbeat is the bar line")
                .isCloseTo(0.0, within(tempo == null ? 1e-9 : TempoMap.UNMODELLED_LEAD_IN_PULSES));
        assertThat(map.toMusicalTime(map.secondsToBeats(
                score.tracks().getFirst().notes().getFirst().onsetSeconds())).bar())
                .as("the first note is in bar one").isZero();
        Chord span = score.chords().chords().getFirst();
        assertThat(span.isNoChord()).isTrue();
        assertThat(span.startSeconds()).as("the chart opens where the staff does")
                .isEqualTo(map.beatsToSeconds(0.0));
    }

    @ParameterizedTest(name = "first note at {0} s, --tempo {1}")
    @CsvSource({"0.53,", "0.53, 120", "0.60,", "0.60, 120"})
    @DisplayName("a pickup played on that pulse keeps it as the opening bar's last beat")
    void aPlayedPulseIsKept(double firstNote, Double tempo) {
        Score score = transcribe(firstNote, true, tempo);
        assertOnePulseBeforeTheDownbeat(score, firstNote);
        double pickup = score.beatGrid().orElseThrow().beatTimes().get(0);

        assertThat(score.tempoMap().secondsToBeats(pickup)).isCloseTo(3.0, within(1e-9));
    }
}
