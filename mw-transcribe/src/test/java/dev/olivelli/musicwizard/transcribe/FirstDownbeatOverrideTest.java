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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
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
