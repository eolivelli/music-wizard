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

import dev.olivelli.musicwizard.core.ml.AsrProvider;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;

/**
 * Hears the same invented words in any window, at times relative to that
 * window — so a test can tell from a word's absolute time whether the CLI
 * offset it by its segment's start, and from the count whether every segment
 * was transcribed. The confidence is one nothing else assigns.
 */
public final class FakeAsrProvider implements AsrProvider {

    static final Confidence HEARD = Confidence.of(0.55);

    @Override
    public String id() {
        return "fake-cli-asr";
    }

    @Override
    public List<String> languages() {
        return List.of("en", "it");
    }

    @Override
    public List<LyricWord> transcribe(float[] samples, int sampleRate,
                                      String languageTag) {
        return List.of(
                LyricWord.ofSeconds("la", 0.20, 0.40, HEARD),
                LyricWord.ofSeconds("sol", 0.50, 0.70, HEARD),
                LyricWord.ofSeconds("mi", 0.80, 1.00, HEARD));
    }
}
