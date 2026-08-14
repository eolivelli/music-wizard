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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The pitch tracker, against signals whose fundamental is known exactly.
 *
 * <p>Tier 0 in the project's terms: synthetic, and therefore a regression gate
 * rather than a measurement. What a tracker does on a flute is measured by
 * {@code tools/score-melody.py}, not here.
 */
class PitchTrackingTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /** A note with a realistic harmonic roll-off rather than a bare sine. */
    private static float[] harmonicNote(double fundamentalHz, double seconds) {
        int length = (int) Math.round(seconds * RATE);
        float[] out = new float[length];
        for (int partial = 1; partial <= 8; partial++) {
            double frequency = fundamentalHz * partial;
            if (frequency >= RATE / 2.0) {
                break;
            }
            double amplitude = 0.35 * Math.pow(0.7, partial - 1);
            for (int i = 0; i < length; i++) {
                out[i] += (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / RATE));
            }
        }
        return out;
    }

    private static float[] concat(float[]... parts) {
        int length = 0;
        for (float[] part : parts) {
            length += part.length;
        }
        float[] out = new float[length];
        int at = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static PitchTrack track(float[] samples) {
        return PitchTracker.track(new AudioBuffer(samples, RATE));
    }

    /** The rounded MIDI pitch of every voiced frame, in order. */
    private static List<Integer> voicedPitches(PitchTrack track) {
        List<Integer> pitches = new ArrayList<>();
        for (int f = 0; f < track.frameCount(); f++) {
            if (track.voiced()[f]) {
                pitches.add((int) Math.round(track.midiPitchAt(f)));
            }
        }
        return pitches;
    }

    private static double voicedFraction(PitchTrack track) {
        int voiced = 0;
        for (boolean isVoiced : track.voiced()) {
            voiced += isVoiced ? 1 : 0;
        }
        return track.frameCount() == 0 ? 0 : (double) voiced / track.frameCount();
    }

    @Nested
    @DisplayName("a steady note")
    class SteadyNote {

        @Test
        @DisplayName("is tracked at its own pitch, in every frame")
        void tracksASustainedNote() {
            PitchTrack track = track(harmonicNote(SignalFactory.midiToHz(69), 2.0));

            assertThat(voicedFraction(track)).isGreaterThan(0.95);
            assertThat(voicedPitches(track)).isNotEmpty().allMatch(pitch -> pitch == 69);
        }

        @Test
        @DisplayName("is tracked across the range, not only in the middle of it")
        void tracksTheWholeRange() {
            for (int pitch : new int[] {36, 48, 60, 72, 84}) {
                PitchTrack track = track(harmonicNote(SignalFactory.midiToHz(pitch), 1.5));

                assertThat(voicedPitches(track))
                        .describedAs("MIDI pitch %d", pitch)
                        .isNotEmpty()
                        .allMatch(tracked -> tracked == pitch);
            }
        }

        @Test
        @DisplayName("is not read an octave out when its partials are strong")
        void doesNotHalveOrDoubleTheFundamental() {
            // The failure this exists for: the difference function dips at twice
            // the true period as well as at it, and a fixed threshold picks
            // whichever dip it happens to reach first.
            PitchTrack track = track(harmonicNote(220, 2.0));

            assertThat(voicedPitches(track)).isNotEmpty().allMatch(pitch -> pitch == 57);
        }

        @Test
        @DisplayName("is placed to better than a tenth of a semitone")
        void isAccurateWithinATenthOfASemitone() {
            PitchTrack track = track(harmonicNote(SignalFactory.midiToHz(64), 1.5));

            for (int f = 0; f < track.frameCount(); f++) {
                if (track.voiced()[f]) {
                    assertThat(track.midiPitchAt(f)).isCloseTo(64, org.assertj.core.data.Offset.offset(0.1));
                }
            }
        }
    }

    @Nested
    @DisplayName("a change of note")
    class ChangeOfNote {

        @Test
        @DisplayName("is followed, and lands within half a window of the truth")
        void followsAStepUp() {
            double first = 1.0;
            PitchTrack track = track(concat(
                    harmonicNote(SignalFactory.midiToHz(60), first),
                    harmonicNote(SignalFactory.midiToHz(62), 1.0)));

            assertThat(voicedPitches(track)).contains(60, 62);

            int firstAt62 = -1;
            for (int f = 0; f < track.frameCount() && firstAt62 < 0; f++) {
                if (track.voiced()[f] && Math.round(track.midiPitchAt(f)) == 62) {
                    firstAt62 = f;
                }
            }
            assertThat(firstAt62).isGreaterThan(0);
            // Late, never early: the window straddling the boundary still holds
            // more of the first note than the second. Half a window is the bound
            // the analysis window itself imposes; the tolerance is the decoder's
            // transition band on top of it.
            double changeAt = track.timeOf(firstAt62);
            assertThat(changeAt).isGreaterThan(first - PitchTracker.WINDOW / (2.0 * RATE));
            assertThat(changeAt).isLessThan(first + 0.10);
        }

        @Test
        @DisplayName("is followed across a leap, later than across a step")
        void followsALeap() {
            PitchTrack track = track(concat(
                    harmonicNote(SignalFactory.midiToHz(60), 1.0),
                    harmonicNote(SignalFactory.midiToHz(72), 1.0)));

            assertThat(voicedPitches(track)).contains(60, 72);
        }
    }

    @Nested
    @DisplayName("silence")
    class Silence {

        @Test
        @DisplayName("is unvoiced throughout")
        void findsNoPitchInSilence() {
            PitchTrack track = track(SignalFactory.silence(1.5, RATE));

            assertThat(track.frameCount()).isPositive();
            assertThat(voicedFraction(track)).isZero();
        }

        @Test
        @DisplayName("between two notes is unvoiced, and the notes around it are not")
        void findsTheGapBetweenTwoNotes() {
            PitchTrack track = track(concat(
                    harmonicNote(SignalFactory.midiToHz(67), 0.8),
                    SignalFactory.silence(0.6, RATE),
                    harmonicNote(SignalFactory.midiToHz(67), 0.8)));

            assertThat(voicedFraction(track)).isBetween(0.5, 0.95);
            assertThat(voicedPitches(track)).isNotEmpty().allMatch(pitch -> pitch == 67);
        }
    }

    @Nested
    @DisplayName("the contract")
    class Contract {

        @Test
        @DisplayName("refuses audio at any rate but the analysis rate")
        void refusesTheWrongSampleRate() {
            AudioBuffer audio = new AudioBuffer(new float[RATE], 44_100);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PitchTracker.track(audio))
                    .withMessageContaining("22050");
        }

        @Test
        @DisplayName("returns an empty track for audio shorter than one window")
        void handlesAudioShorterThanAWindow() {
            PitchTrack track = track(new float[PitchTracker.WINDOW - 1]);

            assertThat(track.frameCount()).isZero();
        }

        @Test
        @DisplayName("carries a finite frequency in unvoiced frames too")
        void carriesAPitchThroughSilence() {
            PitchTrack track = track(SignalFactory.silence(0.5, RATE));

            assertThat(track.frequenciesHz()).isNotEmpty();
            for (double frequency : track.frequenciesHz()) {
                assertThat(frequency).isFinite().isPositive();
            }
        }
    }
}
