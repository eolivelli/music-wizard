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

import java.util.ArrayList;
import java.util.List;

/**
 * Forced alignment over CTC posteriors: given what was sung, when.
 *
 * <p>The standard blank-interleaved Viterbi. The token sequence — the words'
 * characters with a separator between words — becomes states
 * {@code blank, t1, blank, t2, ..., tn, blank}; each frame a state may hold,
 * advance from the previous state, or skip the blank between two
 * <em>different</em> tokens; the best full path is walked back from the end.
 * Because the text is known, the search space is a ribbon rather than a
 * lattice, which is what makes alignment robust where transcription of the
 * same audio is not.
 *
 * <p>Pure Java over a {@code [frames][tokens]} log-posterior array; nothing
 * here knows ONNX or audio. That is what makes it testable to the frame with
 * synthetic posteriors, which is where its correctness is pinned.
 */
final class CtcAligner {

    /**
     * One aligned token span: which token, and the frame range it occupied.
     */
    record TokenSpan(int token, int firstFrame, int lastFrame, double meanLogProb) {
    }

    private CtcAligner() {
    }

    /**
     * Aligns a token sequence to the posteriors.
     *
     * <p>{@code logProbs[f][v]} is the log posterior of vocabulary entry
     * {@code v} at frame {@code f}; {@code blank} names the CTC blank's index;
     * {@code tokens} is the known text as vocabulary indices, in order.
     * Returns one span per token, in order. Empty tokens align to nothing and
     * return an empty list.
     *
     * @throws IllegalArgumentException when there are more required states than
     *         frames could ever visit — text longer than the audio can carry —
     *         because a silently truncated alignment places every remaining
     *         word at the end, which reads as a result and is not one
     */
    static List<TokenSpan> align(float[][] logProbs, int blank, int[] tokens) {
        if (tokens.length == 0) {
            return List.of();
        }
        int frames = logProbs.length;
        int states = 2 * tokens.length + 1;
        // A path must at least pass every token once; blanks may be skipped
        // between differing tokens, so tokens.length is the true floor.
        if (frames < tokens.length) {
            throw new IllegalArgumentException(
                    "cannot align " + tokens.length + " tokens to " + frames
                    + " frames; the text is longer than the audio can carry");
        }

        final float impossible = Float.NEGATIVE_INFINITY;
        float[] previous = new float[states];
        float[] current = new float[states];
        // Backpointers: 0 = held this state, 1 = advanced from s-1, 2 = skipped
        // from s-2. One byte per cell keeps a five-minute window affordable.
        byte[][] back = new byte[frames][states];

        java.util.Arrays.fill(previous, impossible);
        previous[0] = logProbs[0][blank];
        previous[1] = logProbs[0][tokens[0]];

        for (int f = 1; f < frames; f++) {
            for (int s = 0; s < states; s++) {
                float stay = previous[s];
                float fromPrevious = s >= 1 ? previous[s - 1] : impossible;
                float skip = impossible;
                if (s >= 2 && s % 2 == 1) {
                    // Skipping the blank between tokens is only legal when the
                    // tokens differ; a doubled letter must pass through it.
                    int token = (s - 1) / 2;
                    if (tokens[token] != tokens[token - 1]) {
                        skip = previous[s - 2];
                    }
                }
                float best = stay;
                byte from = 0;
                if (fromPrevious > best) {
                    best = fromPrevious;
                    from = 1;
                }
                if (skip > best) {
                    best = skip;
                    from = 2;
                }
                float emit = s % 2 == 0 ? logProbs[f][blank]
                        : logProbs[f][tokens[(s - 1) / 2]];
                current[s] = best == impossible ? impossible : best + emit;
                back[f][s] = from;
            }
            float[] swap = previous;
            previous = current;
            current = swap;
        }

        // The path must end having consumed every token: final blank or final token.
        int end = previous[states - 1] >= previous[states - 2] ? states - 1 : states - 2;
        if (previous[end] == impossible) {
            throw new IllegalArgumentException(
                    "no alignment path: " + tokens.length + " tokens do not fit "
                    + frames + " frames under CTC transitions");
        }

        // Walk back, collecting which state held each frame.
        int[] stateAt = new int[frames];
        int state = end;
        for (int f = frames - 1; f >= 0; f--) {
            stateAt[f] = state;
            if (f > 0) {
                state -= back[f][state];
            }
        }

        // Token spans: contiguous frames in each odd state.
        List<TokenSpan> spans = new ArrayList<>(tokens.length);
        int at = 0;
        while (at < frames) {
            int s = stateAt[at];
            if (s % 2 == 0) {
                at++;
                continue;
            }
            int token = (s - 1) / 2;
            int start = at;
            double sum = 0;
            while (at < frames && stateAt[at] == s) {
                sum += logProbs[at][tokens[token]];
                at++;
            }
            spans.add(new TokenSpan(token, start, at - 1, sum / (at - start)));
        }
        return List.copyOf(spans);
    }
}
