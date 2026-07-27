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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The chord sequence for a piece, ordered in time and non-overlapping.
 *
 * @param chords     the chords, ordered by start time
 * @param confidence overall trust in this estimation
 */
public record ChordProgression(List<Chord> chords, Confidence confidence) {

    public ChordProgression {
        Objects.requireNonNull(chords, "chords");
        Objects.requireNonNull(confidence, "confidence");
        chords = chords.stream()
                .sorted(Comparator.comparingDouble(Chord::startSeconds))
                .toList();
        for (int i = 1; i < chords.size(); i++) {
            if (chords.get(i).startSeconds() < chords.get(i - 1).endSeconds() - 1e-6) {
                throw new IllegalArgumentException(
                        "chords must not overlap; chord " + i + " starts at "
                                + chords.get(i).startSeconds() + "s but the previous ends at "
                                + chords.get(i - 1).endSeconds() + "s");
            }
        }
    }

    public static ChordProgression empty() {
        return new ChordProgression(List.of(), Confidence.UNKNOWN);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return chords.isEmpty();
    }

    public int size() {
        return chords.size();
    }

    /** The chord sounding at a given time, if any. */
    public Optional<Chord> chordAt(double seconds) {
        for (Chord chord : chords) {
            if (seconds >= chord.startSeconds() && seconds < chord.endSeconds()) {
                return Optional.of(chord);
            }
        }
        return Optional.empty();
    }

    /** Chords overlapping a time span. */
    public List<Chord> chordsBetween(double startSeconds, double endSeconds) {
        return chords.stream()
                .filter(c -> c.endSeconds() > startSeconds && c.startSeconds() < endSeconds)
                .toList();
    }

    /** Returns a copy with the chords replaced, keeping the confidence. */
    public ChordProgression withChords(List<Chord> newChords) {
        return new ChordProgression(newChords, confidence);
    }
}
