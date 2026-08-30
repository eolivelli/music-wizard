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
import static org.assertj.core.api.Assertions.withinPercentage;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meter the audio path reads, and what a typed one does to it (#700).
 *
 * <p>The fixture is a click track whose chord changes every third beat and
 * nowhere else, so its bar length is exact by construction and its bar lines
 * are the only period in the recording. Before this the transcriber barred it
 * in four however plainly the harmony said three.
 */
class MeterDetectionTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /** Long enough that the estimator's own minimum is well clear. */
    private static final double SECONDS = 60;

    private static AudioBuffer clickTrackWithBarsOf(int beatsPerBar) {
        return new AudioBuffer(SignalFactory.clickTrackWithChords(120, new double[][] {
                SignalFactory.majorTriad(60),
                SignalFactory.majorTriad(67),
                SignalFactory.minorTriad(69),
                SignalFactory.majorTriad(65),
        }, beatsPerBar, SECONDS, RATE), RATE);
    }

    /** The bar positions the grid wrote, in order and without repeats. */
    private static List<Integer> positions(Score score) {
        BeatGrid grid = score.beatGrid().orElseThrow();
        List<Integer> seen = new ArrayList<>();
        for (BeatGrid.Beat beat : grid.beats()) {
            if (!seen.contains(beat.positionInBar())) {
                seen.add(beat.positionInBar());
            }
        }
        return seen;
    }

    /**
     * The same clicks after a little silence, so the first tracked pulse is not
     * at the origin.
     */
    private static AudioBuffer clickTrackAfterSilence(int beatsPerBar, double silenceSeconds) {
        float[] music = clickTrackWithBarsOf(beatsPerBar).samples();
        float[] padded = new float[music.length + (int) Math.round(silenceSeconds * RATE)];
        System.arraycopy(music, 0, padded, padded.length - music.length, music.length);
        return new AudioBuffer(padded, RATE);
    }

    /**
     * Seconds a bar lasts once the music is under way.
     *
     * <p>Taken between two bar lines well past the lead-in, never from the
     * origin: the interval from the origin to the first bar line <em>is</em> the
     * lead-in, which both a measured and a supplied map stretch to land on the
     * same tracked pulse, so it is the same number whatever tempo built it.
     */
    private static double steadyBarSeconds(Score score) {
        double quartersPerBar =
                score.tempoMap().initialTimeSignature().quarterBeatsPerBar();
        return score.tempoMap().beatsToSeconds(5 * quartersPerBar)
                - score.tempoMap().beatsToSeconds(4 * quartersPerBar);
    }

    @Test
    @DisplayName("harmony that changes every third beat is barred in three")
    void barsOfThree() {
        Score score = new AudioTranscriber()
                .transcribe(clickTrackWithBarsOf(3), AudioTranscriber.Options.defaults());

        assertThat(score.tempoMap().initialTimeSignature())
                .isEqualTo(TimeSignature.THREE_FOUR);
        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    @DisplayName("harmony that changes every fourth beat keeps the assumption")
    void barsOfFour() {
        Score score = new AudioTranscriber()
                .transcribe(clickTrackWithBarsOf(4), AudioTranscriber.Options.defaults());

        assertThat(score.tempoMap().initialTimeSignature())
                .isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    @DisplayName("a typed meter wins over what the recording says")
    void aTypedMeterIsAnInstruction() {
        Score score = new AudioTranscriber().transcribe(clickTrackWithBarsOf(3),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        assertThat(score.tempoMap().initialTimeSignature())
                .isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    @DisplayName("the meter is reported with what it is worth")
    void theReadingIsReported() {
        List<String> said = new ArrayList<>();

        new AudioTranscriber(said::add)
                .transcribe(clickTrackWithBarsOf(3), AudioTranscriber.Options.defaults());

        assertThat(said).anyMatch(line -> line.startsWith("meter 3/4 (")
                && line.contains("% confidence), ")
                && line.endsWith(" beats/min"));
    }

    @Test
    @DisplayName("a bar of six tracked pulses is barred in six, at the beat its meter counts")
    void aPulseThatIsNotTheCountedBeat() {
        List<String> said = new ArrayList<>();

        Score score = new AudioTranscriber(said::add)
                .transcribe(clickTrackWithBarsOf(6), AudioTranscriber.Options.defaults());

        // Six pulses to a bar means the tracker landed on a subdivision, so the
        // pulse is not the counted beat and every figure downstream has to be
        // converted through it -- the tempo map above all, which refuses a bar
        // its pulses do not fill.
        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
        // #200: the rate the run prints is the one --tempo takes, so it is the
        // meter's counted beat and not the pulse.
        String counted = String.format(Locale.ROOT, ", %.1f beats/min",
                score.tempoMap().initialTimeSignature().countedTempo(score.estimatedTempo()));
        assertThat(said).anyMatch(line -> line.startsWith("meter ") && line.endsWith(counted));
    }

    @Test
    @DisplayName("the rate a read meter is printed in is the rate that types back")
    void thePrintedRateTypesBack() {
        // What --tempo's help promises (#705): the unit follows the meter even
        // where MW chose the meter itself, so the figure the run showed
        // reproduces the run. This fixture is where that could go wrong -- the
        // tracker is on the eighth, so the pulse rate and the counted rate are
        // different figures and only one of them types back.
        AudioBuffer audio = clickTrackAfterSilence(6, 0.3);
        Score read = new AudioTranscriber().transcribe(audio, AudioTranscriber.Options.defaults());
        TimeSignature meter = read.tempoMap().initialTimeSignature();
        double printed = meter.countedTempo(read.estimatedTempo());

        Score typed = new AudioTranscriber()
                .transcribe(audio, new AudioTranscriber.Options(printed, null, null));

        assertThat(typed.tempoMap().initialTimeSignature()).isEqualTo(meter);
        assertThat(positions(typed)).containsExactlyElementsOf(positions(read));
        assertThat(steadyBarSeconds(typed))
                .isCloseTo(steadyBarSeconds(read), withinPercentage(1.0));

        // The same run's quarter-note figure, which is what a user reading the
        // unit wrongly would type, must not: without this the assertions above
        // can be satisfied by a map that ignored the unit entirely.
        Score misread = new AudioTranscriber()
                .transcribe(audio, new AudioTranscriber.Options(read.estimatedTempo(), null, null));

        assertThat(steadyBarSeconds(misread))
                .isNotCloseTo(steadyBarSeconds(read), withinPercentage(10.0));
    }

    @Test
    @DisplayName("a typed meter is not reported as a reading")
    void aTypedMeterIsNotAReading() {
        List<String> said = new ArrayList<>();

        new AudioTranscriber(said::add).transcribe(clickTrackWithBarsOf(3),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        assertThat(said).anyMatch(line -> line.startsWith("meter 4/4 as supplied, "))
                .noneMatch(line -> line.startsWith("meter ") && line.contains("confidence"));
    }
}
