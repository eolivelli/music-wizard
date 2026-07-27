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

package dev.olivelli.musicwizard.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A structural section of the song, such as a verse or a chorus.
 *
 * <p>Boundaries come from signal analysis; the {@link SectionKind} label
 * usually comes from the Claude advisor, because deciding which repeated block
 * is "the chorus" needs knowledge the signal does not carry. The
 * {@code repetitionGroup} records which sections analysis found to be musically
 * the same, and is what lets the pipeline vote across repeats to clean up
 * chords and lyrics.
 *
 * @param kind            what kind of section this is
 * @param label           display label, e.g. "Verse 2"
 * @param startSeconds    when the section starts
 * @param endSeconds      when it ends
 * @param repetitionGroup identifier shared by musically identical sections
 * @param confidence      how much the pipeline trusts this section
 */
public record Section(
        SectionKind kind,
        String label,
        double startSeconds,
        double endSeconds,
        Optional<String> repetitionGroup,
        Confidence confidence) {

    public Section {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(repetitionGroup, "repetitionGroup");
        Objects.requireNonNull(confidence, "confidence");
        if (!Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative, got: " + startSeconds);
        }
        if (!Double.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and after startSeconds; got start=" + startSeconds
                            + " end=" + endSeconds);
        }
    }

    /** An unlabelled section, as boundary detection first produces it. */
    public static Section unlabelled(double startSeconds, double endSeconds,
                                     String repetitionGroup, Confidence confidence) {
        return new Section(SectionKind.UNKNOWN, "Section", startSeconds, endSeconds,
                Optional.ofNullable(repetitionGroup), confidence);
    }

    public double durationSeconds() {
        return endSeconds - startSeconds;
    }

    public boolean contains(double seconds) {
        return seconds >= startSeconds && seconds < endSeconds;
    }
}
