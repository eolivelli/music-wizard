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
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seam #559 added: which signal the melody stage reads.
 *
 * <p>The mix here holds a bass note under a click track, which is what a
 * monophonic tracker returns from a band recording, and every "stem" is a tone
 * two octaves above it — so the pitch that comes out names the buffer that was
 * read. Nothing separates anything: this module has no separator, and the
 * point of the seam is that it does not need one.
 */
@DisplayName("the melody stage's input signal")
class MelodySignalTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;
    private static final double SECONDS = 8.0;

    /** A2 in the mix, A4 in the stem: two octaves apart, and both in range. */
    private static final int BASS_MIDI_PITCH = 45;
    private static final int VOICE_MIDI_PITCH = 69;

    private static final AudioTranscriber.Options WITH_MELODY =
            new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null, true);

    /** Clicks for the beat tracker -- with no grid the melody stage is never reached. */
    private static AudioBuffer mix() {
        float[] samples = SignalFactory.clickTrack(120, SECONDS, RATE);
        float[] bass = SignalFactory.sine(
                SignalFactory.midiToHz(BASS_MIDI_PITCH), SECONDS, RATE);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f * samples[i] + 0.5f * bass[i];
        }
        return new AudioBuffer(samples, RATE);
    }

    private static AudioBuffer voiceAt(int sampleRate) {
        return new AudioBuffer(SignalFactory.sine(
                SignalFactory.midiToHz(VOICE_MIDI_PITCH), SECONDS, sampleRate), sampleRate);
    }

    private static List<Note> melodyOf(Score score) {
        return score.track(PartRole.LEAD_VOCAL).map(NoteTrack::notes).orElseThrow();
    }

    @Test
    @DisplayName("the supplied stem is tracked, and the mix is not")
    void theStemIsWhatIsTracked() {
        Score fromStem = new AudioTranscriber().transcribe(
                mix(), WITH_MELODY, () -> voiceAt(RATE));
        Score fromMix = new AudioTranscriber().transcribe(mix(), WITH_MELODY);

        assertThat(melodyOf(fromStem)).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(VOICE_MIDI_PITCH));
        assertThat(melodyOf(fromMix)).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(BASS_MIDI_PITCH));
    }

    @Test
    @DisplayName("a stem at the separator's own rate is resampled, keeping the clock")
    void aStemAtAnotherRateIsResampled() {
        // A rate no recording here uses and the pitch tracker refuses outright,
        // which is what a separator states through preferredSampleRate.
        Score score = new AudioTranscriber().transcribe(
                mix(), WITH_MELODY, () -> voiceAt(32_000));

        List<Note> notes = melodyOf(score);
        assertThat(notes).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(VOICE_MIDI_PITCH));
        // Still the recording's clock: a stem read at the wrong rate would put
        // the same notes at 32000/22050 of their times, and the melody would
        // no longer line up with the beats and chords read from the mix.
        Note last = notes.get(notes.size() - 1);
        assertThat(last.onsetSeconds() + last.durationSeconds())
                .isCloseTo(SECONDS, within(0.2));
    }

    @Test
    @DisplayName("nothing is asked for a stem unless the melody stage runs")
    void theStemIsNotAskedForWhenTheStageIsOff() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<AudioBuffer> counted = () -> {
            asked.incrementAndGet();
            return voiceAt(RATE);
        };

        new AudioTranscriber().transcribe(mix(),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null, false),
                counted);

        assertThat(asked.get())
                .as("separating costs minutes; a run with no melody must not pay it")
                .isZero();
        new AudioTranscriber().transcribe(mix(), WITH_MELODY, counted);
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier with no stem to give leaves the mix melody")
    void aNullStemFallsBackToTheMix() {
        Score score = new AudioTranscriber().transcribe(mix(), WITH_MELODY, () -> null);

        assertThat(melodyOf(score)).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(BASS_MIDI_PITCH));
    }

    @DisplayName("the melody is rounded on the mix's tuning")
    @Nested
    class Tuning {

        /** How far the flat transfer's band sits under concert pitch. */
        private static final double FLAT = -0.44;

        /**
         * A voice under the flat band's own A by a little over a tenth of a
         * semitone. Under concert-pitch A by more than half of one, which is
         * the whole of the defect in #566: rounded on A440 it is a G sharp.
         */
        private static final double VOICE_MIDI = VOICE_MIDI_PITCH - 0.6;

        private AudioBuffer bandAt(double offsetSemitones) {
            float[] samples = SignalFactory.clickTrack(120, SECONDS, RATE);
            for (int pitch : new int[] {45, 52, 57}) {
                float[] tone = SignalFactory.sine(hz(pitch + offsetSemitones), SECONDS, RATE);
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = 0.5f * samples[i] + 0.5f * tone[i];
                }
            }
            return new AudioBuffer(samples, RATE);
        }

        private AudioBuffer voice() {
            return new AudioBuffer(SignalFactory.sine(hz(VOICE_MIDI), SECONDS, RATE), RATE);
        }

        private double hz(double midiPitch) {
            return 440 * Math.pow(2, (midiPitch - 69) / 12);
        }

        @Test
        @DisplayName("the same voice is an A over a flat band and a G sharp over one in tune")
        void theMixsTuningNamesTheNote() {
            Score flat = new AudioTranscriber().transcribe(
                    bandAt(FLAT), WITH_MELODY, this::voice);
            Score inTune = new AudioTranscriber().transcribe(
                    bandAt(0), WITH_MELODY, this::voice);

            assertThat(melodyOf(flat)).isNotEmpty().allSatisfy(note ->
                    assertThat(note.midiPitch()).isEqualTo(VOICE_MIDI_PITCH));
            // Nothing about the stem differs between the two runs, so this is
            // the mix's estimate reaching the melody stage and nothing else.
            assertThat(melodyOf(inTune)).isNotEmpty().allSatisfy(note ->
                    assertThat(note.midiPitch()).isEqualTo(VOICE_MIDI_PITCH - 1));
        }
    }
}
