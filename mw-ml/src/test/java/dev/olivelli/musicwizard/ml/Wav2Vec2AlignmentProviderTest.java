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

package dev.olivelli.musicwizard.ml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text half — tokens, separators, word spans, the inexpressible-word
 * paths — through synthetic posteriors, because none of it needs a model. All
 * fixtures are invented syllables; only the timing machinery is under test.
 */
@DisplayName("the wav2vec2 provider's word reconstruction")
class Wav2Vec2AlignmentProviderTest {

    /**
     * Posteriors that spell out a plan: for each entry, that vocabulary index
     * is near-certain for that many consecutive frames.
     */
    private static float[][] plan(int[]... runs) {
        int frames = 0;
        for (int[] run : runs) {
            frames += run[1];
        }
        float[][] logProbs = new float[frames]
                [Wav2Vec2Models.ENGLISH.vocabulary().length];
        float low = (float) Math.log(1e-4);
        float high = (float) Math.log(0.999);
        int f = 0;
        for (int[] run : runs) {
            for (int i = 0; i < run[1]; i++) {
                java.util.Arrays.fill(logProbs[f], low);
                logProbs[f][run[0]] = high;
                f++;
            }
        }
        return logProbs;
    }

    private static int v(char c) {
        for (int i = 5; i < Wav2Vec2Models.ENGLISH.vocabulary().length; i++) {
            if (Wav2Vec2Models.ENGLISH.vocabulary()[i].charAt(0) == c) {
                return i;
            }
        }
        throw new IllegalArgumentException("not in vocabulary: " + c);
    }

    /** One second of audio at 16 kHz maps to fifty 20 ms frames. */
    private Wav2Vec2AlignmentProvider provider(float[][] logProbs) {
        return new Wav2Vec2AlignmentProvider((path, samples) -> logProbs);
    }

    @Test
    @DisplayName("words come back at the frames their characters occupied")
    void wordsAtTheirFrames() {
        // blank, then LA, separator, SOL as runs of frames.
        float[][] logProbs = plan(
                new int[] {Wav2Vec2Models.ENGLISH.blank(), 10},
                new int[] {v('L'), 5}, new int[] {v('A'), 5},
                new int[] {Wav2Vec2Models.ENGLISH.separator(), 5},
                new int[] {v('S'), 5}, new int[] {v('O'), 5}, new int[] {v('L'), 5},
                new int[] {Wav2Vec2Models.ENGLISH.blank(), 10});
        var words = provider(logProbs).align(new float[16_000], 16_000, "en",
                List.of("la", "sol"));

        assertThat(words).hasSize(2);
        // Frames 10-19 at 20 ms each.
        assertThat(words.get(0).startSeconds()).isEqualTo(10 * 0.02);
        assertThat(words.get(0).endSeconds()).isEqualTo(20 * 0.02);
        assertThat(words.get(1).startSeconds()).isEqualTo(25 * 0.02);
        assertThat(words.get(1).endSeconds()).isEqualTo(40 * 0.02);
        assertThat(words.get(0).confidence().value()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("an inexpressible word rides its predecessor's end, and shifts nothing")
    void inexpressibleWordKeepsItsSlot() {
        // One separator frame, not a run: a run absorbs the extra token a
        // double-separator regression emits, and the mutant stays green.
        float[][] logProbs = plan(
                new int[] {v('L'), 10},
                new int[] {Wav2Vec2Models.ENGLISH.separator(), 1},
                new int[] {v('S'), 10},
                new int[] {Wav2Vec2Models.ENGLISH.blank(), 29});
        // "..." maps to no vocabulary entry; "l" and "s" surround it.
        var words = provider(logProbs).align(new float[16_000], 16_000, "en",
                List.of("l", "...", "s"));

        assertThat(words).hasSize(3);
        assertThat(words.get(1).startSeconds())
                .isEqualTo(words.get(1).endSeconds())
                .isEqualTo(words.get(0).endSeconds());
        assertThat(words.get(1).confidence().value()).isZero();
        // The third word aligned normally: the separator count skipped the
        // empty word rather than emitting a double separator.
        assertThat(words.get(2).endSeconds()).isGreaterThan(words.get(2).startSeconds());
        // To the frame: S starts right after the single separator frame.
        assertThat(words.get(2).startSeconds()).isEqualTo(11 * 0.02);
    }

    @Test
    @DisplayName("nothing expressible at all comes back zero-length, one per word")
    void allInexpressible() {
        float[][] logProbs = plan(new int[] {Wav2Vec2Models.ENGLISH.blank(), 50});
        var words = provider(logProbs).align(new float[16_000], 16_000, "en",
                List.of("...", "123"));

        assertThat(words).hasSize(2);
        for (LyricWord word : words) {
            assertThat(word.startSeconds()).isEqualTo(word.endSeconds());
            assertThat(word.confidence().value()).isZero();
        }
    }

    @Test
    @DisplayName("accents fold to their base letter rather than being dropped")
    void accentsFold() {
        // An accented vowel must land on the plain vowel's vocabulary entry:
        // dropped instead, the word would lose its nucleus.
        float[][] logProbs = plan(
                new int[] {v('S'), 10}, new int[] {v('I'), 10},
                new int[] {Wav2Vec2Models.ENGLISH.blank(), 30});
        var words = provider(logProbs).align(new float[16_000], 16_000, "en",
                List.of("sì"));

        assertThat(words).hasSize(1);
        assertThat(words.get(0).endSeconds()).isEqualTo(20 * 0.02);
        assertThat(words.get(0).confidence().value()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("an unsupported language is refused, not guessed at")
    void unsupportedLanguageRefused() {
        var provider = provider(plan(new int[] {Wav2Vec2Models.ENGLISH.blank(), 10}));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> provider.align(new float[1600], 16_000, "xx", List.of("la")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xx");
    }

    @Test
    @DisplayName("no words align to no words")
    void emptyWords() {
        assertThat(provider(plan(new int[] {Wav2Vec2Models.ENGLISH.blank(), 10}))
                .align(new float[1600], 16_000, "en", List.of())).isEmpty();
    }
}
