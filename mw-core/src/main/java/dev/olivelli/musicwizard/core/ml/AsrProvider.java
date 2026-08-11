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

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;

/**
 * Transcribes sung words from audio, with the time each word is sung at.
 *
 * <p>An SPI — see {@link SeparationProvider} for why it lives here and the
 * implementations do not. Fed the vocal stem, not the mix: that is what
 * separation exists for.
 *
 * <p>The words come back as {@link LyricWord}s in seconds, exactly as parsed
 * lyrics do, so everything downstream — the sheet, the engraving, the harness —
 * reads a transcription and a supplied file through one model. The provider
 * sets each word's confidence honestly; a word whose time is inferred rather
 * than recognised must not carry a recognised word's confidence.
 */
public interface AsrProvider {

    /** The id configuration selects this provider by. */
    String id();

    /**
     * BCP 47 language subtags this provider can transcribe, e.g. {@code it},
     * {@code en} — lowercase language subtags only, matched exactly, no region
     * (a caller with {@code it-IT} passes {@code it}). A caller asked for a
     * language outside this set reports the gap rather than guessing: a
     * transcriber run on the wrong language produces plausible wrong words,
     * which is the expensive kind of wrong.
     */
    List<String> languages();

    /**
     * Transcribes one mono recording.
     *
     * <p>Samples in {@code [-1, 1]} at the given rate, which the caller states
     * truthfully and the <b>provider</b> resamples from — the model's wanted
     * rate is the provider's implementation detail, and a caller that guessed
     * it would produce fluent wrong words the day the model changes. The
     * language tag names what is sung; it must be one of {@link #languages()}.
     *
     * @throws ModelUnavailableException when the model cannot be had — absent
     *         and offline, or failing its checksum. The type's javadoc says
     *         what callers do with it.
     */
    List<LyricWord> transcribe(float[] samples, int sampleRate, String languageTag);
}
