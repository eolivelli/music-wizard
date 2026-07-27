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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete transcription of a piece: the single artifact that the analysis
 * half of the pipeline produces and the notation half consumes.
 *
 * <p>This is the meeting point of the two development tracks. Everything
 * upstream exists to populate it; everything downstream reads it and nothing
 * else. Because a MIDI file can be turned into a {@code Score} directly, the
 * whole notation and arrangement half can be developed and tested without any
 * audio analysis being finished.
 *
 * <p>All fields are optional except the tempo map, since a transcription is
 * built up in stages and must be representable while incomplete.
 *
 * @param title       the piece's title, when known
 * @param artist      the performing artist, when known
 * @param tempoMap    the seconds-to-beats mapping; always present
 * @param beatGrid    tracked beats, once beat tracking has run
 * @param keys        detected keys, ordered in time; empty until key detection runs
 * @param sections    structural sections, ordered in time
 * @param tracks      transcribed note tracks, one per part
 * @param chords      the chord progression
 * @param lyrics      the transcribed lyrics
 * @param durationSeconds total duration of the source audio
 */
public record Score(
        Optional<String> title,
        Optional<String> artist,
        TempoMap tempoMap,
        Optional<BeatGrid> beatGrid,
        List<Key> keys,
        List<Section> sections,
        List<NoteTrack> tracks,
        ChordProgression chords,
        Lyrics lyrics,
        double durationSeconds) {

    public Score {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(artist, "artist");
        Objects.requireNonNull(tempoMap, "tempoMap");
        Objects.requireNonNull(beatGrid, "beatGrid");
        Objects.requireNonNull(chords, "chords");
        Objects.requireNonNull(lyrics, "lyrics");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "durationSeconds must be finite and positive, got: " + durationSeconds);
        }
    }

    /** An otherwise-empty score with only a tempo map and a duration. */
    public static Score empty(TempoMap tempoMap, double durationSeconds) {
        return new Score(
                Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(), List.of(),
                ChordProgression.empty(), Lyrics.empty(), durationSeconds);
    }

    /** The first track matching a role, if the transcription produced one. */
    public Optional<NoteTrack> track(PartRole role) {
        return tracks.stream().filter(t -> t.role() == role).findFirst();
    }

    /** The key in force at a given time. */
    public Optional<Key> keyAt(double seconds) {
        return keys.stream()
                .filter(k -> seconds >= k.startSeconds() && seconds < k.endSeconds())
                .findFirst();
    }

    /** The section containing a given time. */
    public Optional<Section> sectionAt(double seconds) {
        return sections.stream().filter(s -> s.contains(seconds)).findFirst();
    }

    /** The dominant key of the piece, taken as the longest-sounding one. */
    public Optional<Key> primaryKey() {
        return keys.stream().max((a, b) -> Double.compare(
                a.endSeconds() - a.startSeconds(),
                b.endSeconds() - b.startSeconds()));
    }

    /** True when there is enough here to engrave something useful. */
    public boolean hasRenderableContent() {
        return !tracks.isEmpty() || !chords.isEmpty();
    }

    /** Returns a copy with an added or replaced track for its role. */
    public Score withTrack(NoteTrack track) {
        Objects.requireNonNull(track, "track");
        List<NoteTrack> merged = new ArrayList<>(tracks);
        merged.removeIf(existing -> existing.role() == track.role());
        merged.add(track);
        return new Score(title, artist, tempoMap, beatGrid, keys, sections, merged,
                chords, lyrics, durationSeconds);
    }

    public Score withChords(ChordProgression newChords) {
        return new Score(title, artist, tempoMap, beatGrid, keys, sections, tracks,
                newChords, lyrics, durationSeconds);
    }

    public Score withLyrics(Lyrics newLyrics) {
        return new Score(title, artist, tempoMap, beatGrid, keys, sections, tracks,
                chords, newLyrics, durationSeconds);
    }

    public Score withBeatGrid(BeatGrid newBeatGrid) {
        return new Score(title, artist, tempoMap, Optional.of(newBeatGrid), keys, sections,
                tracks, chords, lyrics, durationSeconds);
    }

    public Score withTempoMap(TempoMap newTempoMap) {
        return new Score(title, artist, newTempoMap, beatGrid, keys, sections, tracks,
                chords, lyrics, durationSeconds);
    }

    public Score withKeys(List<Key> newKeys) {
        return new Score(title, artist, tempoMap, beatGrid, newKeys, sections, tracks,
                chords, lyrics, durationSeconds);
    }

    public Score withSections(List<Section> newSections) {
        return new Score(title, artist, tempoMap, beatGrid, keys, newSections, tracks,
                chords, lyrics, durationSeconds);
    }

    public Score withMetadata(String newTitle, String newArtist) {
        return new Score(Optional.ofNullable(newTitle), Optional.ofNullable(newArtist),
                tempoMap, beatGrid, keys, sections, tracks, chords, lyrics, durationSeconds);
    }
}
