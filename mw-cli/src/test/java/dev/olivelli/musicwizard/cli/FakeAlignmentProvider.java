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

import dev.olivelli.musicwizard.core.ml.AlignmentProvider;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic times no spread could produce, so a test can tell an aligned
 * word from an estimated one by its value: word i spans
 * [0.111 * (i+1), 0.111 * (i+1) + 0.05] within the given window, at a
 * confidence the parser never assigns.
 */
public final class FakeAlignmentProvider implements AlignmentProvider {

    static final double STEP = 0.111;
    static final Confidence ALIGNED = Confidence.of(0.97);

    @Override
    public String id() {
        return "fake-cli-alignment";
    }

    @Override
    public List<String> languages() {
        return List.of("en", "it");
    }

    @Override
    public List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                                 List<String> words) {
        List<LyricWord> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            double start = STEP * (i + 1);
            out.add(LyricWord.ofSeconds(words.get(i), start, start + 0.05, ALIGNED));
        }
        return List.copyOf(out);
    }
}
