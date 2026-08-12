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

package dev.olivelli.musicwizard.ml.sherpa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Qwen3 tokens into words")
class Qwen3TokensTest {

    @Test
    @DisplayName("a leading space starts a word; the pieces inside it join")
    void spaceMarksTheBoundary() {
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" la", "aa", " sol", " mi"},
                new float[] {0.5f, 0.9f, 1.5f, 2.0f},
                new float[] {0.3f, 0.2f, 0.4f, 0.3f});

        assertThat(words).extracting(LyricWord::text)
                .containsExactly("laaa", "sol", "mi");
        assertThat(words.get(0).startSeconds()).isEqualTo(0.5);
        assertThat(words.get(0).endSeconds()).isCloseTo(1.1, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(words.get(1).startSeconds()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("metaspace markers mean the same as a space")
    void metaspaceVariants() {
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {"▁la", "Ġsol"},
                new float[] {0.1f, 0.6f},
                new float[] {0.2f, 0.2f});

        assertThat(words).extracting(LyricWord::text).containsExactly("la", "sol");
    }

    @Test
    @DisplayName("a token with no duration still ends its word at its own start")
    void missingDurationsDoNotShortenWords() {
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" la", "aa"},
                new float[] {0.5f, 1.2f},
                new float[] {0.3f});

        assertThat(words).hasSize(1);
        assertThat(words.get(0).endSeconds())
                .isCloseTo(1.2, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("punctuation-only and empty tokens vanish without breaking a word")
    void emptyPiecesVanish() {
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" la", " ", "", "sol"},
                new float[] {0.5f, 0.8f, 0.9f, 1.0f},
                new float[] {0.2f, 0.0f, 0.0f, 0.2f});

        // The bare space is a boundary with nothing in it: it closes "la" and
        // opens nothing, so "sol" starts the next word at its own time.
        assertThat(words).extracting(LyricWord::text).containsExactly("la", "sol");
        assertThat(words.get(1).startSeconds()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("no tokens means no words")
    void noTokens() {
        assertThat(Qwen3Tokens.words(new String[0], new float[0], new float[0]))
                .isEmpty();
    }
}
