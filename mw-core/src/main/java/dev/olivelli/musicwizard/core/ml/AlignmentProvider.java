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
 * Places known words against audio: when each one is sung.
 *
 * <p>Alignment, not transcription. For commercial songs the lyrics are
 * published, so placing known text is usually the <em>available</em> problem —
 * and because the text constrains the path, it is far more robust than
 * transcription on the same audio. This is what replaces the even-division
 * guess {@code LrcLyrics.spread} makes for a file with line times but no word
 * times: an estimated onset carries {@code SPREAD_WORD} confidence, an aligned
 * one carries what the aligner measured.
 *
 * <p>An SPI — see {@link SeparationProvider} for why it lives here and the
 * implementations do not.
 */
public interface AlignmentProvider {

    /** The id configuration selects this provider by, e.g. {@code onnx-wav2vec2}. */
    String id();

    /**
     * BCP 47 language subtags this provider can align — lowercase language
     * subtags only, matched exactly, no region. A caller asked for a language
     * outside this set reports the gap rather than guessing: an aligner run on
     * the wrong language's acoustic model places plausible wrong boundaries,
     * which is the expensive kind of wrong.
     */
    List<String> languages();

    /**
     * When each word is sung, one {@link LyricWord} per input word, in order.
     *
     * <p>Samples in {@code [-1, 1]} at the given rate, which the caller states
     * truthfully and the provider resamples from. Times in the result are
     * seconds <b>within the given samples</b>; a caller aligning a window of a
     * longer recording adds its own offset. The words are text only — the
     * provider decides its own normalisation toward the model's vocabulary,
     * and a word with nothing the vocabulary can express still comes back, at
     * its neighbours' boundary with a confidence that says so.
     *
     * <p>Each word's confidence is the aligner's own measure of that word's
     * path, never a constant: downstream draws the line between measured and
     * guessed times on exactly this number.
     *
     * @throws ModelUnavailableException when the model cannot be had — absent
     *         and offline, or failing its checksum. The type's javadoc says
     *         what callers do with it.
     */
    List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                          List<String> words);
}
