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

package dev.olivelli.musicwizard.testkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Synthetic signals with answers known in advance.
 *
 * <p>These are the tier-0 fixtures: a 440 Hz sine must be detected as A4, and a
 * click track at 120 BPM must be tracked at 120 BPM. If a stage cannot get these
 * exactly right, no amount of tuning on real music will help, and a failure here
 * is always a real defect rather than a hard input.
 */
public final class SignalFactory {

    public static final int DEFAULT_SAMPLE_RATE = 22_050;

    private SignalFactory() {
    }

    /** A pure sine wave. */
    public static float[] sine(double frequencyHz, double seconds, int sampleRate) {
        int length = (int) Math.round(seconds * sampleRate);
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = (float) (0.5 * Math.sin(2 * Math.PI * frequencyHz * i / sampleRate));
        }
        return out;
    }

    /** Several sine waves summed, for testing polyphonic behaviour. */
    public static float[] chord(double[] frequenciesHz, double seconds, int sampleRate) {
        int length = (int) Math.round(seconds * sampleRate);
        float[] out = new float[length];
        for (double frequency : frequenciesHz) {
            for (int i = 0; i < length; i++) {
                out[i] += (float) (0.4 / frequenciesHz.length
                        * Math.sin(2 * Math.PI * frequency * i / sampleRate));
            }
        }
        return out;
    }

    /**
     * A click track at an exact tempo.
     *
     * <p>Each click is a short burst with a sharp attack and a fast decay, which
     * is what an onset detector is built to find. The tempo is exact by
     * construction, so a beat tracker's answer can be compared against truth
     * rather than against another estimate.
     */
    public static float[] clickTrack(double beatsPerMinute, double seconds, int sampleRate) {
        int length = (int) Math.round(seconds * sampleRate);
        float[] out = new float[length];
        double interval = 60.0 / beatsPerMinute;
        int clickLength = Math.max(1, sampleRate / 100);

        for (double t = 0; t < seconds; t += interval) {
            int start = (int) Math.round(t * sampleRate);
            for (int i = 0; i < clickLength && start + i < length; i++) {
                double decay = Math.exp(-8.0 * i / clickLength);
                // A mid-range tone rather than an impulse, so the click has energy
                // in the bands an onset envelope actually looks at.
                out[start + i] += (float) (0.8 * decay
                        * Math.sin(2 * Math.PI * 1000 * i / sampleRate));
            }
        }
        return out;
    }

    /**
     * A click track with a note sounding on every beat, so that a single fixture
     * exercises both rhythm and pitch.
     */
    public static float[] clickTrackWithChords(double beatsPerMinute, double[][] chordsPerBar,
                                               int beatsPerBar, double seconds, int sampleRate) {
        float[] out = clickTrack(beatsPerMinute, seconds, sampleRate);
        double interval = 60.0 / beatsPerMinute;
        int beat = 0;
        for (double t = 0; t < seconds; t += interval, beat++) {
            double[] frequencies = chordsPerBar[(beat / beatsPerBar) % chordsPerBar.length];
            int start = (int) Math.round(t * sampleRate);
            int noteLength = (int) Math.round(interval * sampleRate);
            for (double frequency : frequencies) {
                for (int i = 0; i < noteLength && start + i < out.length; i++) {
                    // Sustains rather than decaying to nothing: a chord that has
                    // faded out before the next beat gives the harmony stages a
                    // frame of noise to analyse, which tests the wrong thing.
                    double envelope = 0.7 + 0.3 * Math.exp(-3.0 * i / noteLength);
                    out[start + i] += (float) (0.6 / frequencies.length * envelope
                            * Math.sin(2 * Math.PI * frequency * i / sampleRate));
                }
            }
        }
        return out;
    }

    /** Silence, for checking that stages refuse to invent structure. */
    public static float[] silence(double seconds, int sampleRate) {
        return new float[(int) Math.round(seconds * sampleRate)];
    }

    /** Writes samples as a 16-bit mono WAV. */
    public static void writeWav(Path file, float[] samples, int sampleRate) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = Math.round(Math.clamp(samples[i], -1f, 1f) * 32767);
            bytes[2 * i] = (byte) (value & 0xFF);
            bytes[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(
                new ByteArrayInputStream(bytes), format, samples.length)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    /** MIDI note number to frequency in hertz. */
    public static double midiToHz(int midiPitch) {
        return 440.0 * Math.pow(2, (midiPitch - 69) / 12.0);
    }

    /** The frequencies of a major triad rooted at a MIDI pitch. */
    public static double[] majorTriad(int rootMidi) {
        return new double[] {
                midiToHz(rootMidi), midiToHz(rootMidi + 4), midiToHz(rootMidi + 7)};
    }

    /** The frequencies of a minor triad rooted at a MIDI pitch. */
    public static double[] minorTriad(int rootMidi) {
        return new double[] {
                midiToHz(rootMidi), midiToHz(rootMidi + 3), midiToHz(rootMidi + 7)};
    }
}
