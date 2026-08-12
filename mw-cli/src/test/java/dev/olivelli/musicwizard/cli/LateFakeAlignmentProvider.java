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
 * Places every word in the last fifth of its window, so consecutive lines'
 * results genuinely collide unless the windows are sequential — the shape the
 * overlap test needs. An aligner returning early times never makes windows
 * interact, and a test built on one passes with the fix reverted.
 */
public final class LateFakeAlignmentProvider implements AlignmentProvider {

    @Override
    public String id() {
        return "fake-cli-late-alignment";
    }

    @Override
    public List<String> languages() {
        return List.of("en", "it");
    }

    @Override
    public List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                                 List<String> words) {
        double window = samples.length / (double) sampleRate;
        double start = window * 0.8;
        double step = (window - start) / Math.max(1, words.size() + 1);
        List<LyricWord> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            double at = start + step * i;
            out.add(LyricWord.ofSeconds(words.get(i), at, at + step * 0.8,
                    Confidence.of(0.8)));
        }
        return List.copyOf(out);
    }
}
