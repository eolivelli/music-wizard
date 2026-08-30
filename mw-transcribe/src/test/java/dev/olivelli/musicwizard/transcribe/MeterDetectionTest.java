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
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
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
     * Seconds between the grid's own bar lines, which the map's bar length has
     * to agree with: the chart bars on the downbeats where they are plausible
     * (#187) and the staff bars on the map, so a run whose two axes differ
     * engraves one file barred two ways (#501).
     */
    private static double gridBarSeconds(Score score) {
        List<Double> downbeats = new ArrayList<>();
        for (BeatGrid.Beat beat : score.beatGrid().orElseThrow().beats()) {
            if (beat.downbeat()) {
                downbeats.add(beat.seconds());
            }
        }
        return (downbeats.get(downbeats.size() - 1) - downbeats.get(0))
                / (downbeats.size() - 1);
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
    @DisplayName("a read meter reaches the score as read, with what the reading was worth")
    void aReadMeterCarriesItsConfidence() {
        // Until #703 the confidence was on the progress line and nowhere else,
        // so a reader of the score file could not tell this from a typed 3/4.
        List<String> said = new ArrayList<>();

        Score score = new AudioTranscriber(said::add)
                .transcribe(clickTrackWithBarsOf(3), AudioTranscriber.Options.defaults());

        TempoMap.MeterChange meter = score.tempoMap().meterChanges().get(0);
        assertThat(meter.provenance()).isEqualTo(Provenance.MEASURED);
        assertThat(meter.confidence()).isPresent();
        // The same figure the run printed, rounded the way the line rounds it,
        // so the file and the commentary cannot come to say different things.
        String printed = String.format(Locale.ROOT, "meter 3/4 (%.0f%% confidence), ",
                100 * meter.confidence().orElseThrow().value());
        assertThat(said).anyMatch(line -> line.startsWith(printed));
    }

    @Test
    @DisplayName("a typed meter reaches the score as the user's, with no reading beside it")
    void aTypedMeterIsCarriedAsSupplied() {
        Score score = new AudioTranscriber().transcribe(clickTrackWithBarsOf(3),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        TempoMap.MeterChange meter = score.tempoMap().meterChanges().get(0);
        assertThat(meter.provenance()).isEqualTo(Provenance.SUPPLIED);
        // The recording was still read -- the pulse count comes from that
        // reading (#736) -- but the signature is the instruction, and a
        // confidence beside it would describe a meter nobody used.
        assertThat(meter.confidence()).isEmpty();
    }

    @Test
    @DisplayName("a run with no beats says its meter is the assumption, not a reading")
    void aRunWithNoBeatsAssumesItsMeter() {
        // The branch that returns an empty score: with no pulses there is
        // nothing to read a meter off, so 4/4 here is a documented default.
        float[] samples = new float[(int) (0.1 * RATE)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / RATE));
        }

        Score score = new AudioTranscriber()
                .transcribe(new AudioBuffer(samples, RATE), AudioTranscriber.Options.defaults());
        Score typed = new AudioTranscriber().transcribe(new AudioBuffer(samples, RATE),
                new AudioTranscriber.Options(null, TimeSignature.THREE_FOUR, null));

        assertThat(score.beatGrid()).as("the tracker found nothing").isEmpty();
        assertThat(score.tempoMap().meterChanges().get(0).provenance())
                .isEqualTo(Provenance.ASSUMED);
        assertThat(score.tempoMap().meterChanges().get(0).confidence()).isEmpty();
        // And a signature typed for that same clip is still the user's: the
        // branch has no reading to prefer, not no instruction.
        assertThat(typed.tempoMap().meterChanges().get(0).provenance())
                .isEqualTo(Provenance.SUPPLIED);
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
    @DisplayName("a typed signature keeps the pulse count the recording was read to hold")
    void aTypedMeterKeepsTheReadPulseCount() {
        // The three ways of #736, on the fixture the tracker sits on the eighth
        // of: a signature is an instruction about the signature, and the pulse
        // count travels with neither it nor the tempo. Before this the middle
        // row barred in two and was a third of the others' length.
        AudioBuffer audio = clickTrackAfterSilence(6, 0.3);
        Score read = new AudioTranscriber().transcribe(audio, AudioTranscriber.Options.defaults());
        TimeSignature meter = read.tempoMap().initialTimeSignature();

        Score typed = new AudioTranscriber()
                .transcribe(audio, new AudioTranscriber.Options(null, meter, null));
        Score typedWithTempo = new AudioTranscriber().transcribe(audio,
                new AudioTranscriber.Options(
                        meter.countedTempo(read.estimatedTempo()), meter, null));

        assertThat(positions(read)).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(positions(typed)).containsExactlyElementsOf(positions(read));
        assertThat(positions(typedWithTempo)).containsExactlyElementsOf(positions(read));
        assertThat(steadyBarSeconds(typed))
                .isCloseTo(steadyBarSeconds(read), withinPercentage(1.0));
        assertThat(steadyBarSeconds(typedWithTempo))
                .isCloseTo(steadyBarSeconds(read), withinPercentage(1.0));
    }

    @Test
    @DisplayName("the pulse count travels to any signature those pulses can count")
    void theCountTravelsToAnotherSignature() {
        // 3/4 and 6/8 hold the same three quarter notes, so the six eighths the
        // recording bars itself in fill either -- what the instruction decides
        // is which is written, and it does not touch where the bar lines fall.
        AudioBuffer audio = clickTrackWithBarsOf(6);
        Score read = new AudioTranscriber().transcribe(audio, AudioTranscriber.Options.defaults());

        Score typed = new AudioTranscriber().transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.THREE_FOUR, null));

        assertThat(typed.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.THREE_FOUR);
        assertThat(positions(typed)).containsExactlyElementsOf(positions(read));
        assertThat(steadyBarSeconds(typed))
                .isCloseTo(steadyBarSeconds(read), withinPercentage(1.0));
    }

    @Test
    @DisplayName("a pulse count a typed bar does not hold is refused, and said")
    void aCountTheTypedSignatureCannotBar() {
        List<String> said = new ArrayList<>();

        Score score = new AudioTranscriber(said::add).transcribe(clickTrackWithBarsOf(6),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        // A 4/4 bar is four quarter notes where the read bar is three, so the
        // six pulses that bar was read to hold are not this bar's, and it takes
        // its own counted beat instead.
        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(said).anyMatch(line -> line.startsWith("the recording bars in 6 tracked beats"));
    }

    @Test
    @DisplayName("a bar of another length is not tiled with a pulse nothing measured")
    void aShorterTypedBarTakesItsOwnBeat() {
        // A 2/4 bar is two quarter notes where the read bar is three, and its
        // counted beats divide the six exactly -- so a rule asking only that
        // would tile every beat of it with a triplet the estimator itself will
        // not claim without reading the division off the envelope.
        Score score = new AudioTranscriber().transcribe(clickTrackWithBarsOf(6),
                new AudioTranscriber.Options(null, new TimeSignature(2, 4), null));

        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName("a typed signature does not take the assumed bar length for a pulse count")
    void theAssumptionIsNotAPulseCount() {
        List<String> said = new ArrayList<>();

        // Harmony every fourth beat reads as the four-pulse prior, whose bar is
        // four quarter notes where a 6/8 bar is three: a different bar, so 6/8
        // is barred in the two dotted quarters it counts. Nothing is refused
        // that was measured below the counted beat, so nothing is said.
        Score score = new AudioTranscriber(said::add).transcribe(clickTrackWithBarsOf(4),
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null));

        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1);
        assertThat(said).noneMatch(line -> line.startsWith("the recording bars in"));
    }

    @Test
    @DisplayName("a supplied tempo decides the pulse count, and the two axes agree")
    void aSuppliedTempoDecidesTheCount() {
        // The tempo names the counted beat and the reading says the bar holds
        // six pulses; both cannot bar one file. The tempo decides, being a
        // correction rather than an estimate, and what that has to leave behind
        // is one bar length -- the grid's bar lines and the map's.
        AudioBuffer audio = clickTrackWithBarsOf(6);
        Score score = new AudioTranscriber().transcribe(audio,
                new AudioTranscriber.Options(120.0, TimeSignature.THREE_FOUR, null));

        assertThat(positions(score)).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(gridBarSeconds(score))
                .isCloseTo(steadyBarSeconds(score), withinPercentage(1.0));
    }

    @Test
    @DisplayName("a tempo that settles nothing leaves the reading's bar standing")
    void aTempoThatSettlesNothingLeavesTheReading() {
        // A correction of a tenth is no relation a pulse and a counted beat
        // stand in, so the tempo says nothing about how the bar is filled and
        // the reading's own count still fills it. This is the ordinary shape of
        // a tempo correction, and the reading is all there is to bar it with.
        AudioBuffer audio = clickTrackWithBarsOf(6);
        Score read = new AudioTranscriber().transcribe(audio, AudioTranscriber.Options.defaults());
        double nudged = 1.1 * read.tempoMap().initialTimeSignature()
                .countedTempo(read.estimatedTempo());

        Score score = new AudioTranscriber()
                .transcribe(audio, new AudioTranscriber.Options(nudged, null, null));

        assertThat(positions(score)).containsExactlyElementsOf(positions(read));
        assertThat(gridBarSeconds(score))
                .isCloseTo(gridBarSeconds(read), withinPercentage(1.0));
    }

    @Test
    @DisplayName("a bar of the same length keeps the count whatever the pulse was called")
    void aBarOfTheSameLengthKeepsTheCount() {
        // Three quarter-note pulses to a bar, read as 3/4 and typed as 6/8:
        // the same three quarters either way, so the bar lines do not move and
        // the signature decides only what is printed.
        AudioBuffer audio = clickTrackWithBarsOf(3);
        Score read = new AudioTranscriber().transcribe(audio, AudioTranscriber.Options.defaults());

        Score typed = new AudioTranscriber().transcribe(audio,
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null));

        assertThat(typed.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(positions(typed)).containsExactlyElementsOf(positions(read));
        assertThat(steadyBarSeconds(typed))
                .isCloseTo(steadyBarSeconds(read), withinPercentage(1.0));
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
