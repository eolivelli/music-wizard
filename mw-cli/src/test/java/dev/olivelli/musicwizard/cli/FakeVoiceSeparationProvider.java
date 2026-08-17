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

package dev.olivelli.musicwizard.cli;

import dev.olivelli.musicwizard.core.ml.SeparationProvider;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A separator whose "vocal" is a tone at a pitch the mix never holds, so a
 * stage's output says which buffer it read. {@link FakeSeparationProvider}
 * hands its input back, which cannot answer that question.
 *
 * <p>It states a preferred rate that is neither the analysis rate nor any
 * recording's, so a caller that forgot to resample the stem fails loudly:
 * {@code PitchTracker} refuses audio at any other rate.
 */
public final class FakeVoiceSeparationProvider implements SeparationProvider {

    /** A4, so a melody read from this stem is all one pitch and that pitch is 69. */
    static final int VOICE_MIDI_PITCH = 69;

    /** Not a rate anything records at, so the stem has to be resampled to be read. */
    static final int PREFERRED_RATE = 32_000;

    /** How many times anything has separated, for the once-per-run rule. */
    static final AtomicInteger SEPARATIONS = new AtomicInteger();

    @Override
    public String id() {
        return "fake-cli-voice";
    }

    @Override
    public int preferredSampleRate() {
        return PREFERRED_RATE;
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        SEPARATIONS.incrementAndGet();
        float[][] vocals = new float[channels.length][];
        for (int c = 0; c < channels.length; c++) {
            vocals[c] = SignalFactory.sine(
                    SignalFactory.midiToHz(VOICE_MIDI_PITCH),
                    (double) channels[c].length / sampleRate, sampleRate);
        }
        return new Separation(vocals, channels);
    }
}
