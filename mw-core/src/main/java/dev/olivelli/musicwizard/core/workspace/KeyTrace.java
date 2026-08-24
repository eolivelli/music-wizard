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

package dev.olivelli.musicwizard.core.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/**
 * What the key's two decisions were weighed from (#678).
 *
 * <p>A record and never an input: nothing reads it back into an analysis, so a
 * run that writes none names the same key.
 *
 * <p>The two decisions are kept apart here because they are kept apart in the
 * answer: which key signature the piece is written in, and which of a relative
 * pair is home. They are not equally reliable, and a reader deciding whether to
 * correct the key by hand needs to know which of them was the weak one.
 *
 * @param source           {@code chords} where the key was read off the
 *                         estimated chords, {@code declared} where the file
 *                         stated it and nothing was weighed
 * @param soundingSeconds  how much of the span carried a chord that was not
 *                         {@code N.C.}
 * @param spanSeconds      how long the key span is
 * @param weighed          the share of the span the chords accounted for, as it
 *                         entered both confidences. Not quite the ratio of the
 *                         two above, which it is clamped from
 * @param candidates       every key that was scored, in no particular order,
 *                         and empty where nothing was
 * @param signature        which key signature won and what it beat, or null on
 *                         the declared path
 * @param tonic            which of the relative pair won and by what, or null
 *                         on the declared path
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeyTrace(
        String source,
        double soundingSeconds,
        double spanSeconds,
        double weighed,
        List<Candidate> candidates,
        Decision signature,
        Decision tonic) {

    /** The stage this trace belongs to, which is also its report phase. */
    public static final String STAGE = "key";

    /** Read off the estimated chords. */
    public static final String FROM_CHORDS = "chords";

    /** Taken from what the file declares, with nothing weighed. */
    public static final String DECLARED = "declared";

    public KeyTrace {
        Objects.requireNonNull(source, "source");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** A key the file stated outright, so no candidate was ever scored. */
    public static KeyTrace declared() {
        return new KeyTrace(DECLARED, 0, 0, 0, List.of(), null, null);
    }

    /**
     * One of the keys that were scored, and the evidence that got it there.
     *
     * <p>The two tallies are the only things that separate a relative pair,
     * which share every scale note: a chord on the fifth degree of a minor key
     * whose third is that key's raised seventh, and the key's own tonic chord.
     * Where both come back empty for both members of a pair, nothing in the
     * harmony chose between them.
     *
     * @param key                    the key's name
     * @param score                  what the progression was worth to it,
     *                               averaged over the sounding time
     * @param tonicChordSpans        chords that were this key's own tonic chord
     * @param tonicChordSeconds      how long they sounded for
     * @param raisedSeventhSpans     chords scored as this key's harmonic-minor
     *                               dominant, which a major key never has
     * @param raisedSeventhSeconds   how long they sounded for
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            String key,
            double score,
            int tonicChordSpans,
            double tonicChordSeconds,
            int raisedSeventhSpans,
            double raisedSeventhSeconds) {

        public Candidate {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * One of the two comparisons, as the estimator made it.
     *
     * <p>{@code read} is the comparison and not what follows from it: a margin
     * inside the tie tolerance means the scores did not separate the two keys
     * and a stated preference chose, and what that costs the answer is the
     * reader's to be told once rather than claimed here.
     *
     * @param winner   the key this comparison named
     * @param runnerUp the best-scoring key it was taken against
     * @param margin   the winner's score less that one's
     * @param read     {@code separated} where the two scores lie further apart
     *                 than the estimator's tie tolerance, {@code tied} where
     *                 they do not
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(String winner, String runnerUp, double margin, String read) {

        public Decision {
            // The page prints all three, so a decision missing one is one this
            // build cannot draw.
            Objects.requireNonNull(winner, "winner");
            Objects.requireNonNull(runnerUp, "runnerUp");
            Objects.requireNonNull(read, "read");
        }
    }
}
