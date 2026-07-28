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
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code --first-downbeat} does, now that it does anything.
 *
 * <p>It was carried from the command line through the config into
 * {@code Options} and then never read, so the tool accepted the correction
 * CLAUDE.md calls one of the two highest-value a user can make, persisted it,
 * and ignored it. The tests here are written so that they fail against that
 * code: each one asserts that a requested downbeat <em>changes</em> the grid,
 * which is precisely what the ignored option could not do.
 */
class FirstDownbeatOverrideTest {

    private static final int SAMPLE_RATE = 22050;

    /** A click track whose first click is {@code offset} seconds in. */
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

    private static final AudioBuffer AUDIO = clickTrack(12.0, 0.5, 1.3);

    private static Score transcribe(Double firstDownbeat, TimeSignature meter) {
        return new AudioTranscriber().transcribe(AUDIO,
                new AudioTranscriber.Options(null, meter, firstDownbeat));
    }

    private static BeatGrid grid(Double firstDownbeat, TimeSignature meter) {
        return transcribe(firstDownbeat, meter).beatGrid().orElseThrow();
    }

    @Test
    @DisplayName("makes the beat nearest the requested time begin every bar")
    void theRequestedBeatBecomesTheDownbeat() {
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();

        // Every tracked pulse in turn, so this cannot pass by landing on the
        // phase the estimator would have chosen anyway.
        for (int i = 0; i < 8; i++) {
            BeatGrid forced = grid(pulses.get(i), TimeSignature.FOUR_FOUR);

            assertThat(forced.beats().get(i).downbeat())
                    .as("pulse %d, at %ss, was asked to begin a bar", i, pulses.get(i))
                    .isTrue();
            // And the bars carry on from there, four pulses apart.
            assertThat(forced.downbeatTimes())
                    .containsAll(everyFourth(pulses, i));
        }
    }

    private static List<Double> everyFourth(List<Double> pulses, int from) {
        List<Double> expected = new ArrayList<>();
        for (int i = from; i < pulses.size(); i += 4) {
            expected.add(pulses.get(i));
        }
        return expected;
    }

    @Test
    @DisplayName("snaps a time between two beats to the nearer of them")
    void snapsToTheNearestBeat() {
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        double before = pulses.get(2);
        double after = pulses.get(3);

        assertThat(grid(before + 0.2 * (after - before), TimeSignature.FOUR_FOUR)
                .beats().get(2).downbeat()).isTrue();
        assertThat(grid(before + 0.8 * (after - before), TimeSignature.FOUR_FOUR)
                .beats().get(3).downbeat()).isTrue();

        // Outside the tracked range at either end, the nearest beat is an end one
        // rather than an error: a user who mistypes gets the bar grid the number
        // points at, and the message below tells them it was moved.
        assertThat(grid(0.0, TimeSignature.FOUR_FOUR).beats().get(0).downbeat()).isTrue();
        assertThat(grid(600.0, TimeSignature.FOUR_FOUR).beats()
                .get(pulses.size() - 1).downbeat()).isTrue();
    }

    @Test
    @DisplayName("says so when the requested downbeat is not near a tracked beat")
    void reportsASnapThatMoved() {
        List<String> messages = new ArrayList<>();
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        double between = (pulses.get(2) + pulses.get(3)) / 2;

        new AudioTranscriber(messages::add).transcribe(AUDIO,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, between));

        // Worth saying: if no tracked beat is near the requested time the beats
        // themselves are wrong, and phasing them will not rescue the bar lines.
        assertThat(messages).anyMatch(m -> m.contains("requested downbeat"));

        List<String> quiet = new ArrayList<>();
        new AudioTranscriber(quiet::add).transcribe(AUDIO,
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, pulses.get(2)));
        assertThat(quiet).noneMatch(m -> m.contains("requested downbeat"));
    }

    @Test
    @DisplayName("bars 6/8 every two pulses, like every other phase in the tool")
    void compoundMeterBarsOnCountedBeats() {
        List<Double> pulses = grid(null, TimeSignature.SIX_EIGHT).beatTimes();
        BeatGrid forced = grid(pulses.get(3), TimeSignature.SIX_EIGHT);

        assertThat(forced.beats().get(3).downbeat()).isTrue();
        assertThat(forced.beats().get(4).downbeat()).isFalse();
        assertThat(forced.beats().get(5).downbeat()).isTrue();
        assertThat(forced.beats()).allSatisfy(
                beat -> assertThat(beat.positionInBar()).isBetween(0, 1));
    }

    @Test
    @DisplayName("overrides the estimator rather than being averaged into it")
    void overridesRatherThanContributes() {
        // The estimator picks one phase from harmony and onsets. If the override
        // were weighed against it, some requested phases would lose; all four
        // have to be reachable, including the three the estimator rejected.
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        List<Integer> phases = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            phases.add(grid(pulses.get(i), TimeSignature.FOUR_FOUR).beats().get(0)
                    .positionInBar());
        }
        assertThat(phases).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    @DisplayName("trusts the phase as far as it trusts the beats it phases")
    void confidenceReflectsAHumanHavingChosen() {
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        BeatGrid estimated = grid(null, TimeSignature.FOUR_FOUR);
        BeatGrid forced = grid(pulses.get(1), TimeSignature.FOUR_FOUR);

        // A human counted the bars, so the phase itself is not in doubt -- but a
        // phase is only as good as the beats it phases, and toBeatGrid multiplies
        // the two. So the downbeat claim rises to exactly the beat claim, and no
        // further.
        assertThat(forced.downbeatConfidence()).isEqualTo(forced.beatConfidence());
        assertThat(forced.downbeatConfidence()).isGreaterThan(estimated.downbeatConfidence());
    }

    @Test
    @DisplayName("says less about a phase it had to move than about one it was handed")
    void confidenceFallsAwayWithTheSnap() {
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        double interval = pulses.get(3) - pulses.get(2);

        // Halfway between two pulses names neither, and the phase that comes out
        // is this code's guess at which neighbour was meant. Reporting that as
        // certain -- above anything harmony can reach -- claims an instruction
        // that was not given.
        BeatGrid exact = grid(pulses.get(2), TimeSignature.FOUR_FOUR);
        BeatGrid nudged = grid(pulses.get(2) + 0.25 * interval, TimeSignature.FOUR_FOUR);
        BeatGrid midway = grid(pulses.get(2) + 0.5 * interval, TimeSignature.FOUR_FOUR);

        assertThat(exact.downbeatConfidence().value())
                .isGreaterThan(nudged.downbeatConfidence().value());
        assertThat(nudged.downbeatConfidence().value())
                .isGreaterThan(midway.downbeatConfidence().value());
        // At the halfway point the phase claim is halved: the user meant one of
        // two pulses and the tie-break is right half the time.
        assertThat(midway.downbeatConfidence().value())
                .isCloseTo(0.5 * midway.beatConfidence().value(), within(1e-9));
        assertThat(exact.downbeatConfidence()).isEqualTo(exact.beatConfidence());

        // Further out than that is a different claim, not more of the same one:
        // a time ten minutes past the end of the recording names no pulse at all,
        // so the phase is worth one guess in four rather than one in two -- below
        // what the estimator gives a phase it could not choose, not above it.
        assertThat(grid(600.0, TimeSignature.FOUR_FOUR).downbeatConfidence().value())
                .isCloseTo(0.25 * exact.beatConfidence().value(), within(1e-9));
        assertThat(grid(600.0, TimeSignature.FOUR_FOUR).downbeatConfidence().value())
                .isLessThan(grid(null, TimeSignature.FOUR_FOUR).downbeatConfidence().value());
    }

    @Test
    @DisplayName("measures ambiguity against the nearer rival, not against the tempo")
    void snapConfidenceBoundsAmbiguityInBothDirections() {
        // Driven directly, because a recording cannot be made to hold a grid with
        // one gap twice the median and another well under it -- and a fixture
        // whose intervals differ only by hop rounding would let either scale pass.
        //
        // 0.5s pulses with beat 4 dropped (a 1.0s gap) and beats 8-9 crowded
        // (a 0.2s gap): a grid that has both faults a real tracker has one of.
        List<Double> ragged = List.of(
                0.0, 0.5, 1.0, 1.5, /* 2.0 dropped */ 2.5, 3.0, 3.5, 4.0, 4.2, 4.7);

        // Landing on a pulse is the user's instruction whatever the neighbours do.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 3, 1.5, 4))
                .isEqualTo(Confidence.CERTAIN);

        // In the dropped-beat gap: half a typical beat out is as ambiguous as a
        // request between two pulses ever gets, whether the user meant the pulse
        // this snapped to or one the tracker dropped. Scaling by the 1.0s rival
        // gap would call it a third of the way to ambiguity and report 0.83.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 3, 2.0, 4).value())
                .isCloseTo(0.5, within(1e-9));

        // In the crowded gap: equidistant from beats 7 and 8 is a genuine coin
        // flip, whatever the median says. Scaling by the 0.5s median would report
        // 0.8 for a phase chosen by a tie-break.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 7, 4.1, 4).value())
                .isCloseTo(0.5, within(1e-9));
        // And a quarter of the way across that short gap is a quarter of the way
        // to ambiguity, not a twentieth.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 7, 4.05, 4).value())
                .isCloseTo(0.75, within(1e-9));

        // The same crowded gap approached from the other side. Asserted because
        // the rival is chosen by which side of the pulse the request fell, and
        // every case above happens to fall after it -- so reading the rival as
        // "always the next pulse" would pass all of them and report 0.90 here.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 8, 4.15, 4).value())
                .isCloseTo(0.75, within(1e-9));
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 8, 4.1, 4).value())
                .isCloseTo(0.5, within(1e-9));

        // Past the last pulse there is no second candidate at all -- the request
        // is not between two pulses -- so beyond half a typical pulse it is worth
        // a guess in beatsPerBar. Asserted at three meters, because at two beats
        // to the bar that count and the tie-break count coincide at a half and a
        // single meter could not tell them apart.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 9, 600.0, 4).value())
                .isCloseTo(0.25, within(1e-9));
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 9, 600.0, 3).value())
                .isCloseTo(1.0 / 3, within(1e-9));
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 9, 600.0, 2).value())
                .isCloseTo(0.5, within(1e-9));
        // Before the first pulse, likewise -- and this is the only shape that
        // tells "no rival on that side" apart from "the rival is the next pulse",
        // since it needs a non-zero distance and an opening gap shorter than the
        // median. Reading the rival as the next pulse would score 0.50.
        List<Double> shortOpening = List.of(1.0, 1.1, 1.6, 2.1, 2.6, 3.1);
        assertThat(AudioTranscriber.snappedPhaseConfidence(shortOpening, 0, 0.95, 4).value())
                .isCloseTo(0.90, within(1e-9));

        // Landing exactly on the first pulse is certain because the distance is
        // zero, not because of anything about rivals.
        assertThat(AudioTranscriber.snappedPhaseConfidence(ragged, 0, 0.0, 4))
                .isEqualTo(Confidence.CERTAIN);

        // One pulse: no interval, and no other pulse they could have meant.
        assertThat(AudioTranscriber.snappedPhaseConfidence(List.of(3.0), 0, 90.0, 4))
                .isEqualTo(Confidence.CERTAIN);

        // A grid with no time between its pulses cannot say how far out anything
        // is, so a request outside it is as blind as any other. Unreachable
        // through a BeatGrid, which rejects non-increasing beats -- but this
        // method takes a bare list, and the branch that treats an unmeasurable
        // distance as infinite rather than as one pulse is only visible here.
        assertThat(AudioTranscriber.snappedPhaseConfidence(List.of(1.0, 1.0), 0, 0.5, 4).value())
                .isCloseTo(0.25, within(1e-9));
    }

    @Test
    @DisplayName("beats what the estimator gives a phase it could not choose")
    void theSnapFloorOutranksAPhaseNothingSupports() {
        // Measured across the seam rather than asserted from two files agreeing,
        // because SNAPPED_PHASE_FLOOR is only meaningful next to what
        // DownbeatEstimator gives a phase it could not choose -- and #48 retuned
        // that scale while this branch was open.
        //
        // This is the *only* ordering pinned here. An earlier draft also asserted
        // the floor stays under what harmony reaches, measuring that ceiling from
        // a synthesised four-chord loop. It was wrong twice over: the loop scored
        // 0.539 against a nominal ceiling of 0.6, so it measured its own fixture
        // rather than the ceiling and would have passed with the ceiling retuned
        // to 0.55; and shortening it to four bars dropped it to 0.493, failing a
        // test about the snap floor for a reason in another module. The claim it
        // defended is not true either -- see SNAPPED_PHASE_FLOOR.
        BeatGrid estimatedGrid = grid(null, TimeSignature.FOUR_FOUR);
        double beatConfidence = estimatedGrid.beatConfidence().value();

        // The estimator lands on exactly its own floor here, and the mechanism is
        // worth naming rather than guessing at: a click track does have chroma
        // novelty, but it is around 1e-6 and the phases are indistinguishable, so
        // the accent decides -- and it decides for a phase the harmony did not
        // prefer, which makes the margin negative and `decided` exactly zero.
        // This is the estimator claiming as little as it can claim.
        double estimated = estimatedGrid.downbeatConfidence().value() / beatConfidence;
        // A request exactly halfway between two pulses: this code claiming as
        // little as it can claim, which is one candidate of two.
        List<Double> pulses = estimatedGrid.beatTimes();
        double worstSnap = grid((pulses.get(2) + pulses.get(3)) / 2, TimeSignature.FOUR_FOUR)
                .downbeatConfidence().value() / beatConfidence;

        assertThat(worstSnap).isGreaterThan(estimated);
        // Pinned as values too, so that either side drifting is visible here
        // rather than only in the ordering, which has slack in it. Note the gap
        // is 0.5 against 0.35 rather than 0.5 against the 0.25 a blind guess in
        // four is worth: BASE_CONFIDENCE is that count plus a deliberate margin
        // for the bars having to start somewhere.
        assertThat(worstSnap).isCloseTo(0.5, within(1e-9));
        assertThat(estimated).isCloseTo(0.35, within(1e-9));

        // A request outside the tracked range rests on nothing at all, and in
        // this meter it ranks below the estimator's own least-supported answer.
        double blind = grid(600.0, TimeSignature.FOUR_FOUR).downbeatConfidence().value()
                / beatConfidence;
        assertThat(blind).isCloseTo(0.25, within(1e-9)).isLessThan(estimated);
    }

    @Test
    @DisplayName("pins a known inversion: two beats to the bar rank a blind phase too high")
    void theBlindPhaseOnlyOutranksNothingInWiderBars() {
        // Recorded rather than fixed, and #88 is why. The estimator's floor is a
        // flat 0.35 whatever the meter, by a choice its own file argues for; this
        // counts one phase in beatsPerBar. The two therefore cross between three
        // beats to the bar and two, and in 6/8 or 2/4 a downbeat typed ten
        // minutes past the end of the recording reads as *more* trustworthy than
        // a harmony-informed estimate on the same audio.
        //
        // Pinned so that the limit is a known one rather than something the next
        // round rediscovers -- and so that fixing #88 fails here rather than
        // silently changing what a saved score means.
        for (TimeSignature meter : List.of(TimeSignature.SIX_EIGHT, new TimeSignature(2, 4))) {
            BeatGrid estimatedGrid = grid(null, meter);
            double beatConfidence = estimatedGrid.beatConfidence().value();
            double estimated = estimatedGrid.downbeatConfidence().value() / beatConfidence;
            double blind = grid(600.0, meter).downbeatConfidence().value() / beatConfidence;

            assertThat(meter.beatsPerBar()).as("%s", meter).isEqualTo(2);
            assertThat(blind).as("blind phase in %s", meter).isCloseTo(0.5, within(1e-9));
            // The inversion, asserted rather than tolerated in silence.
            assertThat(blind).as("still above the estimator in %s", meter)
                    .isGreaterThan(estimated);
        }

        // From three beats to the bar the count falls below the flat floor and
        // the intended ordering holds again.
        BeatGrid threeFour = grid(null, TimeSignature.THREE_FOUR);
        assertThat(grid(600.0, TimeSignature.THREE_FOUR).downbeatConfidence().value())
                .isLessThan(threeFour.downbeatConfidence().value());

        // At one beat to the bar the question does not arise: neither side is
        // guessing, so they agree exactly rather than one outranking the other.
        // Asserted here as well as in oneBeatToTheBarIsAlwaysCertain, because the
        // javadoc this test records once said the blind phase outranked the
        // estimator at that meter too, and it does not.
        TimeSignature oneFour = new TimeSignature(1, 4);
        assertThat(grid(600.0, oneFour).downbeatConfidence())
                .isEqualTo(grid(null, oneFour).downbeatConfidence());
    }

    @Test
    @DisplayName("does not rank a phase that cannot be wrong below one that can")
    void oneBeatToTheBarIsAlwaysCertain() {
        // At one beat to the bar every beat begins a bar, so there is no phase to
        // choose and no snap can have chosen it wrongly. DownbeatEstimator answers
        // this meter with CERTAIN before looking at anything; a forced phase that
        // reported less would invert the one ordering these numbers guarantee --
        // and would halve the saved confidence for a byte-identical grid.
        TimeSignature oneFour = new TimeSignature(1, 4);
        BeatGrid estimated = grid(null, oneFour);
        List<Double> pulses = estimated.beatTimes();
        BeatGrid forced = grid((pulses.get(2) + pulses.get(3)) / 2, oneFour);

        assertThat(forced.beats()).allSatisfy(beat -> assertThat(beat.downbeat()).isTrue());
        assertThat(forced.downbeatConfidence()).isEqualTo(estimated.downbeatConfidence());
        assertThat(forced.downbeatConfidence()).isEqualTo(forced.beatConfidence());
    }

    @Test
    @DisplayName("works alongside a forced tempo without either disturbing the other")
    void combinesWithAForcedTempo() {
        // The two overrides are read from the same Options and neither had a test
        // that set both. --tempo drives the map, --first-downbeat the grid, and
        // each has to still do its own job with the other present.
        List<Double> pulses = grid(null, TimeSignature.FOUR_FOUR).beatTimes();
        Score both = new AudioTranscriber().transcribe(AUDIO,
                new AudioTranscriber.Options(90.0, TimeSignature.FOUR_FOUR, pulses.get(2)));

        assertThat(both.estimatedTempo()).isCloseTo(90.0, within(1e-9));
        assertThat(both.beatGrid().orElseThrow().beats().get(2).downbeat()).isTrue();
        // The map still anchors the first tracked pulse on a whole pulse.
        double firstPulseBeats = both.tempoMap().secondsToBeats(pulses.get(0));
        assertThat(firstPulseBeats).isCloseTo(Math.rint(firstPulseBeats), within(1e-9));
    }

    @Test
    @DisplayName("phases a one-beat clip, and says so when there are no beats at all")
    void copesWithTooFewBeatsToPhase() {
        // One pulse: there is no other pulse the user could have meant, so it
        // takes the downbeat whatever time was typed, and the phase is not in
        // doubt. Reached only through the branch that cannot infer a tempo.
        float[] tone = new float[(int) (0.25 * SAMPLE_RATE)];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }
        Score blip = new AudioTranscriber().transcribe(new AudioBuffer(tone, SAMPLE_RATE),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, 5.0));
        BeatGrid onePulse = blip.beatGrid().orElseThrow();
        assertThat(onePulse.size()).isEqualTo(1);
        assertThat(onePulse.beats().get(0).downbeat()).isTrue();
        // The phase confidence itself is asserted in
        // snapConfidenceBoundsAmbiguityInBothDirections, not here: a one-beat
        // grid has a beat confidence of zero and toBeatGrid multiplies, so every
        // assertion on this grid's downbeat confidence is zero either way and
        // proves nothing about the branch above it.
        assertThat(onePulse.beatConfidence().value()).isZero();

        // No pulses at all: nothing to mark, but the override must not vanish
        // without comment -- that is the shape of the bug this option had.
        float[] blink = new float[(int) (0.1 * SAMPLE_RATE)];
        for (int i = 0; i < blink.length; i++) {
            blink[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }
        List<String> messages = new ArrayList<>();
        Score empty = new AudioTranscriber(messages::add).transcribe(
                new AudioBuffer(blink, SAMPLE_RATE),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, 1.0));
        assertThat(empty.beatGrid()).isEmpty();
        assertThat(messages).anyMatch(m -> m.contains("nothing to mark"));
    }

    @Test
    @DisplayName("leaves the tempo map to the tempo")
    void doesNotDisturbTheTempoMap() {
        // The two overrides are about different things -- one the rate, one where
        // the bar starts -- and the map is anchored on the first tracked pulse
        // either way. A downbeat that silently moved the map would make --tempo
        // and --first-downbeat interact, which neither of them documents.
        Score plain = transcribe(null, TimeSignature.FOUR_FOUR);
        List<Double> pulses = plain.beatGrid().orElseThrow().beatTimes();

        assertThat(transcribe(pulses.get(5), TimeSignature.FOUR_FOUR).tempoMap())
                .isEqualTo(plain.tempoMap());
    }

    @Test
    @DisplayName("rejects a downbeat that is not a time")
    void rejectsNonsenseDownbeats() {
        for (double bad : new double[] {-0.001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException()
                    .as("--first-downbeat %s", bad)
                    .isThrownBy(() -> new AudioTranscriber.Options(
                            null, TimeSignature.FOUR_FOUR, bad))
                    .withMessageContaining("downbeat");
        }
    }
}
