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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Qwen3 tokens into words")
class Qwen3TokensTest {

    @Test
    @DisplayName("leading whitespace starts a word; the pieces inside it join")
    void spaceMarksTheBoundary() {
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" la", "aa", " sol", " mi"}, 4.0);

        assertThat(words).extracting(LyricWord::text)
                .containsExactly("laaa", "sol", "mi");
    }

    @Test
    @DisplayName("metaspace markers mean the same as a space")
    void metaspaceVariants() {
        List<LyricWord> words = Qwen3Tokens.words(new String[] {"▁la", "Ġsol"}, 2.0);

        assertThat(words).extracting(LyricWord::text).containsExactly("la", "sol");
    }

    @Test
    @DisplayName("words tile the window, longer words getting more of it")
    void spreadFollowsSyllables() {
        // "lalala" estimates three syllables, "sol" one: 3/4 and 1/4 of 4 s.
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" lalala", " sol"}, 4.0);

        assertThat(words.get(0).startSeconds()).isEqualTo(0.0);
        assertThat(words.get(0).endSeconds()).isCloseTo(3.0, within(1e-9));
        assertThat(words.get(1).startSeconds()).isCloseTo(3.0, within(1e-9));
        assertThat(words.get(1).endSeconds()).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("whitespace-only tokens and dangling-byte markers vanish")
    void noisePiecesVanish() {
        // A bare space closes the word before it; U+FFFD is a dangling byte
        // sherpa appends when the last token was cut mid-character.
        List<LyricWord> words = Qwen3Tokens.words(
                new String[] {" la", " ", "sol�"}, 2.0);

        assertThat(words).extracting(LyricWord::text).containsExactly("la", "sol");
    }

    @Test
    @DisplayName("no tokens, or no window, means no words")
    void degenerateInputs() {
        assertThat(Qwen3Tokens.words(new String[0], 3.0)).isEmpty();
        assertThat(Qwen3Tokens.words(new String[] {" la"}, 0.0)).isEmpty();
    }
}
