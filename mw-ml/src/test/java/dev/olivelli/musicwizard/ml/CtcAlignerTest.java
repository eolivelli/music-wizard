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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Against synthetic posteriors, where the right answer is constructed rather
 * than believed: a matrix that says token X is being sung at frames 10–19 must
 * come back saying exactly that, to the frame.
 */
@DisplayName("the CTC aligner")
class CtcAlignerTest {

    private static final int BLANK = 0;

    /** Posteriors that put probability ~1 on one vocabulary entry per frame. */
    private static float[][] certain(int vocabulary, int... perFrame) {
        float[][] logProbs = new float[perFrame.length][vocabulary];
        float low = (float) Math.log(0.001 / (vocabulary - 1));
        float high = (float) Math.log(0.999);
        for (int f = 0; f < perFrame.length; f++) {
            java.util.Arrays.fill(logProbs[f], low);
            logProbs[f][perFrame[f]] = high;
        }
        return logProbs;
    }

    @Test
    @DisplayName("reads token spans off unambiguous posteriors, to the frame")
    void alignsCertainPosteriors() {
        // blank blank A A A blank B B blank blank
        float[][] logProbs = certain(3, 0, 0, 1, 1, 1, 0, 2, 2, 0, 0);

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, BLANK, new int[] {1, 2});

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).token()).isZero();
        assertThat(spans.get(0).firstFrame()).isEqualTo(2);
        assertThat(spans.get(0).lastFrame()).isEqualTo(4);
        assertThat(spans.get(1).token()).isEqualTo(1);
        assertThat(spans.get(1).firstFrame()).isEqualTo(6);
        assertThat(spans.get(1).lastFrame()).isEqualTo(7);
    }

    @Test
    @DisplayName("a doubled token must pass through the blank between its halves")
    void doubledTokenKeepsItsBlank() {
        // A A blank A A: the only legal path for tokens [A, A]. If the skip
        // transition wrongly allowed equal tokens, frames 0-4 could all be one
        // A and the second A would land nowhere or steal frames.
        float[][] logProbs = certain(2, 1, 1, 0, 1, 1);

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, BLANK, new int[] {1, 1});

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).lastFrame()).isLessThan(spans.get(1).firstFrame());
    }

    @Test
    @DisplayName("different adjacent tokens may skip the blank between them")
    void differentTokensMaySkipTheBlank() {
        // A B back to back, no blank frame between them.
        float[][] logProbs = certain(3, 1, 2);

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, BLANK, new int[] {1, 2});

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).firstFrame()).isZero();
        assertThat(spans.get(1).firstFrame()).isEqualTo(1);
    }

    @Test
    @DisplayName("more tokens than frames is refused, not truncated")
    void tooManyTokensRefused() {
        float[][] logProbs = certain(3, 1, 2);

        assertThatThrownBy(() -> CtcAligner.align(logProbs, BLANK,
                new int[] {1, 2, 1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than the audio");
    }

    @Test
    @DisplayName("no tokens aligns to nothing")
    void emptyTokens() {
        assertThat(CtcAligner.align(certain(2, 0, 0), BLANK, new int[0])).isEmpty();
    }

    @Test
    @DisplayName("the mean log posterior of a span reflects what was on the path")
    void confidenceReflectsThePath() {
        float[][] logProbs = certain(2, 1, 1, 1, 0);

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, BLANK, new int[] {1});

        assertThat(spans).hasSize(1);
        // Frames 0-2 each carry log(0.999) for the token.
        assertThat(spans.get(0).meanLogProb())
                .isCloseTo(Math.log(0.999), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("an uncertain stretch does not steal frames from a certain one")
    void ambiguityResolvesTowardTheEvidence() {
        // Frames: A A  ?  ?  B B  where ? is uniform. The aligner must keep A
        // on the left of B whatever it does with the middle.
        int vocabulary = 3;
        float[][] logProbs = new float[6][vocabulary];
        float uniform = (float) Math.log(1.0 / vocabulary);
        for (float[] frame : logProbs) {
            java.util.Arrays.fill(frame, uniform);
        }
        System.arraycopy(certain(vocabulary, 1, 1), 0, logProbs, 0, 2);
        System.arraycopy(certain(vocabulary, 2, 2), 0, logProbs, 4, 2);

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, BLANK, new int[] {1, 2});

        assertThat(spans.get(0).firstFrame()).isZero();
        assertThat(spans.get(0).lastFrame()).isLessThan(4);
        assertThat(spans.get(1).lastFrame()).isEqualTo(5);
    }
}
