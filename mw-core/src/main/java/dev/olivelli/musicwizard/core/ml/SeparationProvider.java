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

package dev.olivelli.musicwizard.core.ml;

/**
 * Separates a mix into a vocal stem and everything else.
 *
 * <p>An SPI, not an implementation: the implementations live in {@code mw-ml}
 * behind {@link java.util.ServiceLoader}, and this interface lives here so that
 * {@code mw-transcribe} can call a provider without depending on the module
 * that carries ONNX Runtime's natives — the split {@code mw-transcribe}'s POM
 * records as the work its missing {@code mw-ml} edge was waiting for (#247).
 *
 * <p>Separated stems feed melody, bass and lyrics only. Chords are estimated
 * from the full mix, never from stems — separation artifacts destroy the
 * partial structure chroma depends on, and that rule outranks any provider.
 */
public interface SeparationProvider {

    /** The id configuration selects this provider by, e.g. {@code onnx-spleeter}. */
    String id();

    /**
     * Separates a recording.
     *
     * <p>Samples are interleaved per channel: {@code channels[c][i]} is channel
     * {@code c}'s {@code i}-th sample, in {@code [-1, 1]}. A mono recording is
     * one channel. The result carries the same shape and rate.
     *
     * @throws ModelUnavailableException when the model this provider needs
     *         cannot be had — absent and offline, or failing its checksum. The
     *         caller degrades the way an absent LilyPond does: says so and
     *         continues without, rather than failing the run.
     */
    Separation separate(float[][] channels, int sampleRate);

    /** The two stems: the voice, and the rest of the mix. */
    record Separation(float[][] vocals, float[][] accompaniment) {
    }
}
