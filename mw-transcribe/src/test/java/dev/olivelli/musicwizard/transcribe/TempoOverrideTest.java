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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What number the user is typing when they type {@code --tempo}.
 *
 * <p>The transcriber builds its tempo map two ways -- from the tracked pulses, or
 * from a tempo the user forced -- and the two have to describe the same music.
 * They stopped doing so the moment the tracked path learned that a pulse in 6/8
 * is a dotted quarter and the override path did not, which put the bar grid 1.5x
 * out in exactly the meter this was all meant to fix.
 */
class TempoOverrideTest {

    private static final int SAMPLE_RATE = 22050;

    /** A click track: a short decaying burst every {@code period} seconds. */
    private static AudioBuffer clickTrack(double seconds, double period) {
        return clickTrack(seconds, period, 0.0);
    }

    /**
     * A click track whose first click is {@code offset} seconds in.
     *
     * <p>The offset is what makes a phase assertion mean anything. Every source
     * of a beat position agrees exactly at {@code t = 0}, so a fixture whose
     * first pulse lands there cannot tell a map that carries the tracked phase
     * from one that threw it away -- which is the trap CLAUDE.md records and the
     * reason the tests below do not use {@link #clickTrack(double, double)}.
     */
    private static AudioBuffer clickTrack(double seconds, double period, double offset) {
        float[] samples = new float[(int) (seconds * SAMPLE_RATE)];
        for (double t = offset; t < seconds; t += period) {
            int start = (int) (t * SAMPLE_RATE);
            for (int i = 0; i < SAMPLE_RATE / 40 && start + i < samples.length; i++) {
                double decay = Math.exp(-i / (SAMPLE_RATE / 400.0));
                samples[start + i] =
                        (float) (0.8 * decay * Math.sin(2 * Math.PI * 1000 * i / SAMPLE_RATE));
            }
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    /** A steady tone, which has one onset and therefore very few beats. */
    private static AudioBuffer tone(double seconds) {
        float[] samples = new float[(int) (seconds * SAMPLE_RATE)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }
        return new AudioBuffer(samples, SAMPLE_RATE);
    }

    private static Score transcribeAt(double tempo, TimeSignature meter) {
        return new AudioTranscriber().transcribe(clickTrack(12.0, 0.5),
                new AudioTranscriber.Options(tempo, meter, null));
    }

    /** Seconds one bar lasts according to the map. */
    private static double barSeconds(Score score) {
        return score.tempoMap().beatsToSeconds(
                score.tempoMap().initialTimeSignature().quarterBeatsPerBar());
    }

    @Test
    @DisplayName("reads a forced tempo as counted beats, so 120 in 6/8 is 120 dotted quarters")
    void forcedTempoIsInCountedBeats() {
        Score common = transcribeAt(120, TimeSignature.FOUR_FOUR);
        Score compound = transcribeAt(120, TimeSignature.SIX_EIGHT);

        // Common time: 120 quarter notes a minute, four to a bar, two seconds.
        assertThat(common.tempoMap().initialTempo()).isCloseTo(120.0, within(1e-9));
        assertThat(barSeconds(common)).isCloseTo(2.0, within(1e-9));

        // 6/8: 120 dotted quarters a minute is 180 quarter notes, and a bar holds
        // two of those dotted quarters, so one second. Reading the 120 as quarter
        // notes would give a 1.5-second bar and put every bar line in the wrong
        // place -- which is what the tracked path would never have done.
        assertThat(compound.tempoMap().initialTempo()).isCloseTo(180.0, within(1e-9));
        assertThat(barSeconds(compound)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("means the same 120 by its fallback as a user means by --tempo 120")
    void theNoBeatsFallbackIsInCountedBeats() {
        // Too short for the tracker to find anything, which is the branch that
        // returns a hard-coded 120. That 120 has to mean counted beats like every
        // other 120 in this class, or the fallback and the override disagree in
        // compound time -- 120 dotted quarters is 180 quarter notes, and reading
        // it as quarter notes gives a bar half again too long.
        float[] samples = new float[(int) (0.1 * SAMPLE_RATE)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }
        AudioBuffer tooShort = new AudioBuffer(samples, SAMPLE_RATE);

        Score compound = new AudioTranscriber().transcribe(tooShort,
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null));

        assertThat(compound.beatGrid()).as("the tracker found nothing").isEmpty();
        assertThat(compound.tempoMap().initialTempo()).isCloseTo(180.0, within(1e-9));
        assertThat(barSeconds(compound)).isCloseTo(1.0, within(1e-9));
        // Which is exactly what a typed --tempo 120 gives on the same meter.
        assertThat(compound.tempoMap().initialTempo())
                .isEqualTo(transcribeAt(120, TimeSignature.SIX_EIGHT).tempoMap().initialTempo());
    }

    @Test
    @DisplayName("leaves the tracked path alone")
    void trackedPathStillTracks() {
        // The override is the branch under test, so pin the other one too: with no
        // override the map must still come from the pulses, at the click track's
        // 120 a minute, which in 6/8 is 180 quarter notes.
        Score compound = new AudioTranscriber().transcribe(clickTrack(12.0, 0.5),
                new AudioTranscriber.Options(null, TimeSignature.SIX_EIGHT, null));

        assertThat(compound.beatGrid()).isPresent();
        assertThat(compound.beatGrid().get().medianPulseRate()).isCloseTo(120.0, within(2.0));
        assertThat(compound.tempoMap().averageTempo(compound.durationSeconds()))
                .isCloseTo(180.0, within(3.0));
        // And the grid bars every two pulses, not every six. Asserted on the
        // positions rather than on a particular beat, because which pulse starts
        // the bar is chosen from onset strength and is not the claim here.
        assertThat(compound.beatGrid().get().beats())
                .allSatisfy(beat -> assertThat(beat.positionInBar()).isBetween(0, 1));
        assertThat(compound.beatGrid().get().downbeatTimes()).hasSizeGreaterThan(8);
    }

    /**
     * The tracked pulse the map has to agree with, and the beat it sits on.
     *
     * <p>Read off a run with no override, so that the expected position is the
     * one the tracked path produces rather than one this test asserts by fiat.
     */
    private static double firstTrackedBeat(AudioBuffer audio, TimeSignature meter) {
        return new AudioTranscriber().transcribe(audio,
                        new AudioTranscriber.Options(null, meter, null))
                .beatGrid().orElseThrow().beatTimes().get(0);
    }

    @Test
    @DisplayName("keeps the tracked beat phase when it replaces the tracked rate")
    void forcedTempoKeepsTheTrackedPhase() {
        // Offset so the tracker does not report a pulse at t=0; without that this
        // test passes against the unfixed code, because a map anchored at the
        // origin already agrees with a grid whose first pulse is the origin.
        AudioBuffer audio = clickTrack(12.0, 0.5, 1.3);
        double firstPulse = firstTrackedBeat(audio, TimeSignature.FOUR_FOUR);
        assertThat(firstPulse).as("the fixture must not start at t=0").isGreaterThan(0.1);

        for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.SIX_EIGHT)) {
            Score forced = new AudioTranscriber().transcribe(audio,
                    new AudioTranscriber.Options(120.0, meter, null));

            // The map is what the grid is stored beside, so "where is beat one"
            // has to have one answer: the first tracked pulse sits a whole number
            // of pulses from the origin, not 0.48 of one.
            double pulses = forced.tempoMap().secondsToBeats(firstPulse)
                    / meter.beatUnitQuarters();
            assertThat(pulses).as("first tracked pulse in %s", meter)
                    .isCloseTo(Math.rint(pulses), within(1e-9))
                    .isGreaterThanOrEqualTo(1.0);
            // And it agrees with what the tracked path says about the same pulse.
            Score tracked = new AudioTranscriber().transcribe(audio,
                    new AudioTranscriber.Options(null, meter, null));
            assertThat(forced.tempoMap().secondsToBeats(firstPulse))
                    .as("phase in %s", meter)
                    .isCloseTo(tracked.tempoMap().secondsToBeats(firstPulse), within(1e-9));

            // The rate is still the one that was typed, everywhere after the
            // lead-in: the phase must not have been bought with the tempo.
            assertThat(forced.tempoMap().tempoAtBeat(
                            forced.tempoMap().secondsToBeats(firstPulse) + 4))
                    .isCloseTo(120.0 * meter.beatUnitQuarters(), within(1e-9));
        }
    }

    @Test
    @DisplayName("still reports the forced tempo, though the map now has a lead-in")
    void forcedTempoStillWinsTheReportedTempo() {
        // The phase fix costs the override map its single-segment shape, which is
        // what Score.estimatedTempo used to recognise a supplied tempo by. Left
        // unhandled, --tempo 60 on a 120 BPM recording would print and bar at
        // 120: the correction would reach the map and nothing else.
        AudioBuffer audio = clickTrack(12.0, 0.5, 1.3);
        Score halved = new AudioTranscriber().transcribe(audio,
                new AudioTranscriber.Options(60.0, TimeSignature.FOUR_FOUR, null));

        assertThat(halved.tempoMap().segments())
                .as("the fixture must exercise the lead-in, or this proves nothing")
                .hasSizeGreaterThan(1);
        assertThat(halved.beatGrid().orElseThrow().medianPulseRate())
                .as("the grid still disagrees, which is what makes the choice matter")
                .isCloseTo(120.0, within(2.0));
        assertThat(halved.estimatedTempo()).isCloseTo(60.0, within(1e-9));
    }

    @Test
    @DisplayName("transcribes a clip that tracks exactly one beat")
    void oneTrackedBeatIsNotAnError() {
        // One beat passes the empty check and then reaches fromBeatTimes, which
        // needs two, so a clip in the narrow band between "no beats" and "enough
        // beats" used to throw. About a fifth of a second lands in it: a tenth
        // yields no beats and half a second yields three.
        AudioBuffer blip = tone(0.25);

        Score score = new AudioTranscriber().transcribe(blip,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        assertThat(score.beatGrid().orElseThrow().size())
                .as("the fixture must track exactly one beat, or this tests nothing")
                .isEqualTo(1);
        // A lone pulse carries no interval, so the tempo is the same default the
        // no-beats branch uses -- and the pulse itself survives, which an empty
        // score would have discarded.
        assertThat(score.estimatedTempo()).isCloseTo(120.0, within(1e-9));
        assertThat(score.beatGrid().orElseThrow().beatTimes()).hasSize(1);
    }

    @Test
    @DisplayName("prefers a forced tempo to the default when the tracker found too little")
    void tooFewBeatsStillHonourAForcedTempo() {
        // Both branches that cannot infer a tempo: no beats at all, and one beat.
        // A user who typed a tempo is exactly the caller these branches must not
        // answer with a hard-coded 120 -- an unreadable track is what a
        // correction is for.
        for (AudioBuffer audio : List.of(tone(0.1), tone(0.25))) {
            Score forced = new AudioTranscriber().transcribe(audio,
                    new AudioTranscriber.Options(90.0, TimeSignature.SIX_EIGHT, null));

            assertThat(forced.beatGrid().map(g -> g.size()).orElse(0)).isLessThan(2);
            // 90 counted beats in 6/8 is 135 quarter notes, as everywhere else.
            assertThat(forced.estimatedTempo()).isCloseTo(135.0, within(1e-9));
        }
    }

    @Test
    @DisplayName("anchors nothing when the first pulse is already the origin")
    void anchorAtTheOriginStaysASingleSegment() {
        // An anchor at the origin is the ordinary case rather than an edge one:
        // hop quantisation is what produces it, so any recording whose first beat
        // falls in frame 0 lands here. Asserted through a recording as well as
        // directly, since it is reachable both ways.
        TempoMap atOrigin = AudioTranscriber.constantPulseFrom(
                120, TimeSignature.FOUR_FOUR, 0.0);
        assertThat(atOrigin).isEqualTo(TempoMap.constantPulse(120, TimeSignature.FOUR_FOUR));

        Score fromTheOrigin = new AudioTranscriber().transcribe(clickTrack(12.0, 0.5, 0.0),
                new AudioTranscriber.Options(90.0, TimeSignature.FOUR_FOUR, null));
        assertThat(fromTheOrigin.beatGrid().orElseThrow().beatTimes().get(0)).isZero();
        assertThat(fromTheOrigin.tempoMap().segments()).hasSize(1);
        assertThat(fromTheOrigin.estimatedTempo()).isCloseTo(90.0, within(1e-9));

        // The genuinely unreachable anchors, which is why the helper is
        // package-private. First, one so small that cramming a whole pulse into
        // it overflows to an infinite tempo: the rate the user asked for is worth
        // more than a thrown map, so the phase is dropped rather than the
        // analysis.
        TempoMap unrepresentable = AudioTranscriber.constantPulseFrom(
                120, TimeSignature.FOUR_FOUR, Double.MIN_VALUE);
        assertThat(unrepresentable.segments()).hasSize(1);
        assertThat(unrepresentable.initialTempo()).isEqualTo(120.0);

        // And a negative anchor cannot be produced by a grid, but must not build
        // a map with a segment running backwards if one ever reaches it.
        assertThat(AudioTranscriber.constantPulseFrom(120, TimeSignature.FOUR_FOUR, -1.0))
                .isEqualTo(TempoMap.constantPulse(120, TimeSignature.FOUR_FOUR));
    }

    @Test
    @DisplayName("rejects a tempo that is not a tempo")
    void rejectsNonsenseOverrides() {
        for (double bad : new double[] {0.0, -120.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException()
                    .as("--tempo %s", bad)
                    .isThrownBy(() -> new AudioTranscriber.Options(
                            bad, TimeSignature.FOUR_FOUR, null))
                    .withMessageContaining("tempo");
        }
    }
}
