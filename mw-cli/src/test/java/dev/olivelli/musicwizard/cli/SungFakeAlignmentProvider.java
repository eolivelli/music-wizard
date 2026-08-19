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
 * An aligner that gives each word a share of the whole window, the way a real
 * one does for a syllable held over a phrase, so that a line's hull covers the
 * notes a test means a syllable to be sung over.
 */
public final class SungFakeAlignmentProvider implements AlignmentProvider {

    static final Confidence ALIGNED = Confidence.of(0.91);

    @Override
    public String id() {
        return "sung-fake-cli-alignment";
    }

    @Override
    public List<String> languages() {
        return List.of("en", "it");
    }

    @Override
    public List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                                 List<String> words) {
        double window = (double) samples.length / sampleRate;
        double each = window / words.size();
        List<LyricWord> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            out.add(LyricWord.ofSeconds(words.get(i), i * each, (i + 1) * each, ALIGNED));
        }
        return List.copyOf(out);
    }
}
