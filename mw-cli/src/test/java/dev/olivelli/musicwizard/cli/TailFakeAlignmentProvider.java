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
 * Places every word inside the final quarter second of the window — past the
 * tail bound whenever the window's slack reaches beyond it, which is the shape
 * that makes a naive compression scale negative and reverse the words.
 */
public final class TailFakeAlignmentProvider implements AlignmentProvider {

    @Override
    public String id() {
        return "fake-cli-tail-alignment";
    }

    @Override
    public List<String> languages() {
        return List.of("en", "it");
    }

    @Override
    public List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                                 List<String> words) {
        double window = samples.length / (double) sampleRate;
        double start = Math.max(0, window - 0.25);
        double step = (window - start) / Math.max(1, words.size());
        List<LyricWord> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            double at = start + step * i;
            out.add(LyricWord.ofSeconds(words.get(i), at, at + step * 0.9,
                    Confidence.of(0.8)));
        }
        return List.copyOf(out);
    }
}
