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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tier-0 harmony: synthesised chords whose identity is known exactly. */
class ChordEstimationTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    private static ChordProgression analyse(float[] samples) {
        AudioBuffer audio = new AudioBuffer(samples, RATE);
        List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();
        return ChordEstimator.estimate(Chroma.extract(audio).beatSynchronous(beats), beats);
    }

    @Nested
    @DisplayName("chroma")
    class ChromaExtraction {

        @Test
        @DisplayName("puts a C major triad in C, E and G")
        void findsTriadPitchClasses() {
            AudioBuffer audio = new AudioBuffer(
                    SignalFactory.chord(SignalFactory.majorTriad(60), 2.0, RATE), RATE);

            double[] vector = Chroma.extract(audio).normalisedPerFrame().vectors()[20];

            // C=0, E=4, G=7 must dominate the other nine pitch classes.
            double chordTones = vector[0] + vector[4] + vector[7];
            double rest = 1.0 - chordTones;
            assertThat(chordTones).isGreaterThan(rest * 3);
        }

        @Test
        @DisplayName("detects a recording tuned away from A440")
        void detectsTuningOffset() {
            // A quarter-tone sharp: every pitch sits halfway between two chroma
            // bins, which is the case that destroys template matching.
            double quarterToneSharp = Math.pow(2, 0.25 / 12);
            float[] detuned = SignalFactory.chord(new double[] {
                    440 * quarterToneSharp, 554.37 * quarterToneSharp,
                    659.26 * quarterToneSharp}, 3.0, RATE);

            double offset = Chroma.estimateTuning(
                    Spectrogram.compute(new AudioBuffer(detuned, RATE), 4096, 1024));

            assertThat(offset).isCloseTo(0.25, within(0.15));
        }

        @Test
        @DisplayName("beat-synchronous averaging yields one vector per inter-beat span")
        void averagesBetweenBeats() {
            AudioBuffer audio = new AudioBuffer(SignalFactory.clickTrack(120, 8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(audio)).beatTimes();

            Chroma sync = Chroma.extract(audio).beatSynchronous(beats);

            assertThat(sync.frameCount()).isEqualTo(beats.size() - 1);
            assertThat(sync.isBeatSynchronous()).isTrue();
        }
    }

    @Nested
    @DisplayName("chord recognition")
    class Recognition {

        @Test
        @DisplayName("recovers a I-V-vi-IV progression in C")
        void recognisesTheFourChordProgression() {
            // The most common progression in popular music: C, G, Am, F, one
            // chord per bar of four beats at 120 BPM.
            float[] signal = SignalFactory.clickTrackWithChords(120, new double[][] {
                    SignalFactory.majorTriad(60),   // C
                    SignalFactory.majorTriad(67),   // G
                    SignalFactory.minorTriad(69),   // Am
                    SignalFactory.majorTriad(65),   // F
            }, 4, 32, RATE);

            ChordProgression chords = analyse(signal);

            assertThat(chords.chords()).extracting(Chord::symbol)
                    .startsWith("C", "G", "Am", "F", "C", "G", "Am", "F");
            // Sixteen bars of four beats in 32 seconds.
            assertThat(chords.size()).isBetween(14, 18);
            assertThat(chords.confidence().value()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("distinguishes major from minor on the same root")
        void distinguishesMajorFromMinor() {
            float[] major = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60)}, 4, 12, RATE);
            float[] minor = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.minorTriad(60)}, 4, 12, RATE);

            assertThat(analyse(major).chords().get(0).symbol()).isEqualTo("C");
            assertThat(analyse(minor).chords().get(0).symbol()).isEqualTo("Cm");
        }

        @Test
        @DisplayName("holds one chord across its bar instead of chattering")
        void doesNotChatter() {
            // Without the transition model this returns a different chord almost
            // every beat. Four bars of one chord must come back as one span.
            float[] signal = SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60)}, 4, 16, RATE);

            ChordProgression chords = analyse(signal);

            assertThat(chords.size()).isLessThanOrEqualTo(2);
            assertThat(chords.chords().get(0).symbol()).isEqualTo("C");
        }

        @Test
        @DisplayName("reports no chord for silence rather than inventing one")
        void silenceHasNoChords() {
            AudioBuffer silent = new AudioBuffer(SignalFactory.silence(8, RATE), RATE);
            List<Double> beats = BeatTracker.track(OnsetEnvelope.fromAudio(silent)).beatTimes();

            // Silence yields no beats at all, so there is nothing to segment.
            assertThat(beats).isEmpty();
            assertThat(ChordEstimator.estimate(
                    Chroma.extract(silent).beatSynchronous(beats), beats).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("chord spans are contiguous and ordered")
        void spansAreContiguous() {
            ChordProgression chords = analyse(SignalFactory.clickTrackWithChords(120,
                    new double[][] {SignalFactory.majorTriad(60), SignalFactory.majorTriad(65)},
                    4, 24, RATE));

            List<Chord> list = chords.chords();
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i).startSeconds())
                        .isGreaterThanOrEqualTo(list.get(i - 1).startSeconds());
                assertThat(list.get(i).startSeconds())
                        .isCloseTo(list.get(i - 1).endSeconds(), within(0.05));
            }
        }
    }
}
