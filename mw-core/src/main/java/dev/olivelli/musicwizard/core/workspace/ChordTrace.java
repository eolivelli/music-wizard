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
 * Why each chord span carries the label it does (#677).
 *
 * <p>A record and never an input: nothing reads it back into an analysis, so a
 * run that writes none decodes the same chords.
 *
 * <p>The spans are the chord spans as they were finally named, in the same
 * order and count as {@link ChromaTrace}'s, so a reading and the decision made
 * on it are found under one index.
 *
 * @param spans one entry per chord span, in time order
 * @param roots one entry per root the recording put a chord on, for the two
 *              decisions that are settled across every run on a root rather
 *              than run by run
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChordTrace(List<Span> spans, List<Root> roots) {

    /** The stage this trace belongs to, which is also its report phase. */
    public static final String STAGE = "chords";

    public ChordTrace {
        spans = spans == null ? List.of() : List.copyOf(spans);
        roots = roots == null ? List.of() : List.copyOf(roots);
    }

    /**
     * What one span was decided by.
     *
     * <p>Three labels, in the order the span passed through them: what the
     * Viterbi held, what the run's own chroma made of it, and what the chart
     * prints. Where the last two differ a count taken over other spans on the
     * same root is what named this one, and {@code settledBy} says which.
     *
     * @param fromSeconds       where the span starts
     * @param toSeconds         where it ends
     * @param fromBeat          first beat-synchronous span it covers
     * @param toBeat            one past the last
     * @param chord             the symbol the span was named with
     * @param fromRun           the symbol the run's own chroma chose, before
     *                          either per-root count
     * @param settledBy         which of the three last set {@code chord} —
     *                          {@code decoder}, {@code run}, {@code sevenths}
     *                          or {@code thirds}, read off those labels, so no
     *                          row can name a decision its own columns deny
     * @param decoded           the state the decoder held over most of the span,
     *                          scored over the span's own beats. Where it held
     *                          more than one quality this is the majority, so a
     *                          run decision that agreed with it reads as the
     *                          decoder's
     * @param runnerUp          the best-scoring state other than that one, or
     *                          null where nothing else was scored
     * @param bassRoot          the root the bass register argued for over these
     *                          beats, or null where it argued for none — which
     *                          is also what a no-chord span leaves, the
     *                          no-chord state taking no root prior at all
     * @param bassOnDecoded     what that prior added to {@code decoded}'s score,
     *                          which is at most zero
     * @param majorSeventhBeats beats of the span on which the fit's residual let
     *                          the decoder consider a major seventh on this
     *                          root, or null where the span has no root
     * @param gates             what the residual said about each degree the
     *                          quality decision gates, over the whole run this
     *                          span belongs to. Empty where there was nothing to
     *                          read: no root, no residual, or a root the fit
     *                          needs nothing on, against which every share is
     *                          cleared by every value
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Span(
            double fromSeconds,
            double toSeconds,
            int fromBeat,
            int toBeat,
            String chord,
            String fromRun,
            String settledBy,
            Candidate decoded,
            Candidate runnerUp,
            String bassRoot,
            double bassOnDecoded,
            Integer majorSeventhBeats,
            List<Gate> gates) {

        public Span {
            // The page prints both, so a span without them is a span this build
            // cannot draw.
            Objects.requireNonNull(chord, "chord");
            Objects.requireNonNull(fromRun, "fromRun");
            Objects.requireNonNull(settledBy, "settledBy");
            Objects.requireNonNull(decoded, "decoded");
            gates = gates == null ? List.of() : List.copyOf(gates);
        }
    }

    /**
     * A state and what it scored over one span, in the decoder's own units:
     * mean per-beat log-likelihood, emission and bass prior together.
     *
     * <p>Not a posterior, and not comparable between spans. The decoder chooses
     * a path rather than a state per beat, so the state it held can score below
     * one it passed over — that is the transition prior holding the chord, and
     * it is why a margin read from two of these may be negative.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(String chord, double score) {

        public Candidate {
            Objects.requireNonNull(chord, "chord");
        }
    }

    /**
     * One comparison a residual gate makes on one degree of the decoded root.
     *
     * <p>Recorded whether or not the printed chord states the degree: it is
     * what the fit said about the run, and the candidates that were refused are
     * as much of the answer as the one that won.
     *
     * <p>A degree decided by more than one comparison has a row per comparison,
     * and {@code counted} is the outcome for the degree rather than for the row:
     * the major third is withheld only where it fails both of its.
     *
     * @param degree   the degree above the root the reading is of
     * @param rule     what it was compared against
     * @param reading  how much of the run's residual only that degree explains
     * @param required what the rule asked of it
     * @param counted  whether the quality decision counted the degree at all
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gate(String degree, String rule, double reading, double required,
                       boolean counted) {

        public Gate {
            Objects.requireNonNull(degree, "degree");
            Objects.requireNonNull(rule, "rule");
        }
    }

    /**
     * The two decisions taken over every run on one root at once, which is why
     * a span's printed quality is often decided by other spans (#558, #272).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(String root, Count thirds, Count sevenths) {

        public Root {
            // The page prints all three, so a root missing one is a root this
            // build cannot draw — refused here so a later build's rename costs
            // the picture rather than the page.
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(thirds, "thirds");
            Objects.requireNonNull(sevenths, "sevenths");
        }
    }

    /**
     * A count read across a root's runs, and what the rule reading it did.
     *
     * <p>{@code read} is the count and not the verdict, because the two come
     * apart: a rule can reach a verdict over beats no run could carry, and
     * {@code runsChanged} is then zero. What each rule does with each reading
     * is the reader's to be told once, not a claim per root.
     *
     * @param stated      beats whose label carried the degree being counted
     * @param beats       beats the count was read against, which each rule
     *                    scopes for itself
     * @param read        {@code minority}, {@code even}, {@code majority}, or
     *                    {@code none} where the rule counted no beat at all
     * @param runsChanged runs the rule rewrote
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Count(int stated, int beats, String read, int runsChanged) {

        public Count {
            Objects.requireNonNull(read, "read");
        }
    }
}
