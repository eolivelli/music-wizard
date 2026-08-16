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

package dev.olivelli.musicwizard.dsp;

/**
 * How much of a span's spectrum only one pitch class can explain: delete its
 * notes from the dictionary, fit the span again, and read what that costs.
 *
 * <p>A chroma cannot answer this. Folding activations to pitch classes reports
 * what the fit turned on, and the fit turns on a note to soak up a partial the
 * dictionary under-models as readily as to describe a note that is playing —
 * the root's fifth partial is its major third, so a strongly voiced root
 * manufactures one (#537). Deleting a pitch class separates the two: nothing
 * else covers a sounding note's own fundamental, while a column that was only
 * standing in for somebody else's partial can go, because that partial's own
 * note is still there.
 *
 * <p>An interface so {@link ChordEstimator} can be exercised without audio;
 * {@link NnlsAblation} is what the pipeline passes.
 */
public interface PitchClassAblation {

    /**
     * How many beat-synchronous spans this covers, which the estimator checks
     * against its chroma.
     *
     * <p>The one mistake the estimator can catch: an ablation that was never
     * beat-synchronised answers about analysis frames, so every chord would be
     * measured over the first seconds of the recording and nothing would say
     * so.
     */
    int spanCount();

    /**
     * Relative increase in squared residual, per pitch class, over the
     * beat-synchronous spans {@code [fromSpan, toSpan)}.
     *
     * @return twelve values, zero or larger, indexed by pitch class; all zero
     *     where the spans hold nothing to fit
     */
    double[] significanceOver(int fromSpan, int toSpan);
}
