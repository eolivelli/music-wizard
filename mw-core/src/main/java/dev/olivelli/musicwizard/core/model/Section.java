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

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * <p>Musical timing is optional and follows the same rule as {@link Note} and
 * {@link Chord}: present once quantization has run, absent before. A section
 * boundary becomes a rehearsal mark and a double bar line, both of which sit on
 * a bar line, so the notation stage needs the position the quantizer chose
 * rather than one it re-derives from seconds and rounds differently.
 *
 * @param kind            what kind of section this is
 * @param label           display label, e.g. "Verse 2"
 * @param startSeconds    when the section starts
 * @param endSeconds      when it ends
 * @param startBeat       quantized start in quarter-note beats, once decided
 * @param endBeat         quantized end in quarter-note beats, once decided
 * @param repetitionGroup identifier shared by musically identical sections
 * @param confidence      how much the pipeline trusts this section
 */
public record Section(
        SectionKind kind,
        String label,
        double startSeconds,
        double endSeconds,
        Optional<Double> startBeat,
        Optional<Double> endBeat,
        Optional<String> repetitionGroup,
        Confidence confidence) {

    public Section {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(startBeat, "startBeat");
        Objects.requireNonNull(endBeat, "endBeat");
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
        // Checked here rather than only in quantizedTo, because deserialization
        // and direct construction both bypass the factory methods.
        if (startBeat.isPresent() != endBeat.isPresent()) {
            throw new IllegalArgumentException(
                    "a section must carry both startBeat and endBeat or neither");
        }
        if (startBeat.isPresent()) {
            double from = startBeat.get();
            double to = endBeat.get();
            if (!Double.isFinite(from) || from < 0) {
                throw new IllegalArgumentException("startBeat must be finite and non-negative, got: " + from);
            }
            if (!Double.isFinite(to) || to <= from) {
                throw new IllegalArgumentException(
                        "endBeat must be finite and after startBeat; got start=" + from + " end=" + to);
            }
        }
    }

    /** A labelled section known only in wall-clock terms. */
    public static Section ofSeconds(SectionKind kind, String label,
                                    double startSeconds, double endSeconds,
                                    String repetitionGroup, Confidence confidence) {
        return new Section(kind, label, startSeconds, endSeconds,
                Optional.empty(), Optional.empty(),
                Optional.ofNullable(repetitionGroup), confidence);
    }

    /** An unlabelled section, as boundary detection first produces it. */
    public static Section unlabelled(double startSeconds, double endSeconds,
                                     String repetitionGroup, Confidence confidence) {
        return ofSeconds(SectionKind.UNKNOWN, "Section", startSeconds, endSeconds,
                repetitionGroup, confidence);
    }

    public double durationSeconds() {
        return endSeconds - startSeconds;
    }

    /** How long the section lasts in quarter-note beats, once quantized. */
    public Optional<Double> durationBeats() {
        return startBeat.map(from -> endBeat.orElseThrow() - from);
    }

    /** True once this section carries quantized musical timing. */
    @JsonIgnore
    public boolean isQuantized() {
        return startBeat.isPresent() && endBeat.isPresent();
    }

    public boolean contains(double seconds) {
        return seconds >= startSeconds && seconds < endSeconds;
    }

    /** Returns a copy carrying quantized musical timing. */
    public Section quantizedTo(double newStartBeat, double newEndBeat) {
        if (!(newEndBeat > newStartBeat)) {
            throw new IllegalArgumentException(
                    "endBeat must be after startBeat; got start=" + newStartBeat + " end=" + newEndBeat);
        }
        return new Section(kind, label, startSeconds, endSeconds,
                Optional.of(newStartBeat), Optional.of(newEndBeat), repetitionGroup, confidence);
    }
}
