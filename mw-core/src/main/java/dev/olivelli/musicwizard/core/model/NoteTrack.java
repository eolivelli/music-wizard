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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A monophonic or polyphonic sequence of notes belonging to one part.
 *
 * @param role       what this part is, which decides clef and transposition
 * @param name       display name for the staff, e.g. "Voice"
 * @param notes      the notes, ordered by onset
 * @param confidence overall trust in this transcription
 */
public record NoteTrack(PartRole role, String name, List<Note> notes, Confidence confidence) {

    public NoteTrack {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(confidence, "confidence");
        notes = notes.stream()
                .sorted(Comparator.comparingDouble(Note::onsetSeconds))
                .toList();
    }

    public static NoteTrack empty(PartRole role, String name) {
        return new NoteTrack(role, name, List.of(), Confidence.UNKNOWN);
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public int size() {
        return notes.size();
    }

    /** True when no two notes overlap in time, i.e. the part is a single line. */
    public boolean isMonophonic() {
        for (int i = 1; i < notes.size(); i++) {
            if (notes.get(i).onsetSeconds() < notes.get(i - 1).offsetSeconds()) {
                return false;
            }
        }
        return true;
    }

    /** Lowest and highest sounding MIDI pitch, or empty when there are no notes. */
    public java.util.Optional<int[]> pitchRange() {
        if (notes.isEmpty()) {
            return java.util.Optional.empty();
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (Note note : notes) {
            low = Math.min(low, note.midiPitch());
            high = Math.max(high, note.midiPitch());
        }
        return java.util.Optional.of(new int[] {low, high});
    }

    /** True once every note carries quantized musical timing. */
    public boolean isQuantized() {
        return notes.stream().allMatch(Note::isQuantized);
    }

    /** Returns a copy with the notes replaced. */
    public NoteTrack withNotes(List<Note> newNotes) {
        return new NoteTrack(role, name, newNotes, confidence);
    }
}
