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
        // Sorted so that keyAt/sectionAt answer by musical position rather than
        // by the order stages happened to append in.
        keys = Objects.requireNonNull(keys, "keys").stream()
                .sorted(java.util.Comparator.comparingDouble(Key::startSeconds))
                .toList();
        sections = Objects.requireNonNull(sections, "sections").stream()
                .sorted(java.util.Comparator.comparingDouble(Section::startSeconds))
                .toList();
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        // At most one track per named role, since two would make track(role)
        // ambiguous. OTHER may repeat -- source separation produces several
        // unclassified stems -- but its tracks must be distinguishable by name.
        java.util.Set<PartRole> seenRoles = java.util.EnumSet.noneOf(PartRole.class);
        java.util.Set<String> seenOtherNames = new java.util.HashSet<>();
        for (NoteTrack track : tracks) {
            if (track.role().allowsMultipleTracks()) {
                if (!seenOtherNames.add(track.name())) {
                    throw new IllegalArgumentException(
                            "tracks in the " + track.role() + " role must have distinct names,"
                                    + " got two called \"" + track.name() + "\"");
                }
            } else if (!seenRoles.add(track.role())) {
                throw new IllegalArgumentException(
                        "a score may hold at most one track in the " + track.role() + " role");
            }
        }

        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).startSeconds() < keys.get(i - 1).endSeconds() - 1e-6) {
                throw new IllegalArgumentException(
                        "keys must not overlap; key " + i + " starts at "
                                + keys.get(i).startSeconds() + "s but the previous ends at "
                                + keys.get(i - 1).endSeconds() + "s");
            }
        }
        for (int i = 1; i < sections.size(); i++) {
            if (sections.get(i).startSeconds() < sections.get(i - 1).endSeconds() - 1e-6) {
                throw new IllegalArgumentException(
                        "sections must not overlap; section " + i + " starts at "
                                + sections.get(i).startSeconds() + "s but the previous ends at "
                                + sections.get(i - 1).endSeconds() + "s");
            }
        }
        // Once quantized, the beat axis has to agree with the seconds axis. The
        // notation stage reads only the beats, so a quantizer that mapped two
        // keys onto overlapping bars would engrave a key change inside the key
        // it replaces, with nothing in seconds to show for it.
        requireOrderedBeats(keys, Key::startBeat, Key::endBeat, "keys", "key");
        requireOrderedBeats(sections, Section::startBeat, Section::endBeat, "sections", "section");
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "durationSeconds must be finite and positive, got: " + durationSeconds);
        }
    }

    /**
     * Rejects a quantized span that starts before an earlier quantized one ended.
     *
     * <p>Un-quantized spans are skipped rather than compared, because a score is
     * built up in stages and a quantized key followed by an un-quantized one is a
     * normal intermediate state. But they must not break the chain: comparing
     * only against the immediately preceding span let an un-quantized key sit
     * between two overlapping quantized ones and hide the overlap completely,
     * which is the very case the stage-by-stage argument makes likely. So the
     * furthest quantized end seen so far is carried forward instead.
     */
    private static <T> void requireOrderedBeats(List<T> spans,
                                                java.util.function.Function<T, Optional<Double>> startBeat,
                                                java.util.function.Function<T, Optional<Double>> endBeat,
                                                String plural, String singular) {
        double furthestEnd = Double.NEGATIVE_INFINITY;
        int furthestIndex = -1;
        for (int i = 0; i < spans.size(); i++) {
            Optional<Double> start = startBeat.apply(spans.get(i));
            if (furthestIndex >= 0 && start.isPresent() && start.get() < furthestEnd - 1e-6) {
                throw new IllegalArgumentException(
                        plural + " must not overlap; " + singular + " " + i
                                + " starts at beat " + start.get() + " but " + singular + " "
                                + furthestIndex + " ends at beat " + furthestEnd);
            }
            Optional<Double> end = endBeat.apply(spans.get(i));
            if (end.isPresent() && end.get() > furthestEnd) {
                furthestEnd = end.get();
                furthestIndex = i;
            }
        }
    }

    /** An otherwise-empty score with only a tempo map and a duration. */
    public static Score empty(TempoMap tempoMap, double durationSeconds) {
        return new Score(
                Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(), List.of(),
                ChordProgression.empty(), Lyrics.empty(), durationSeconds);
    }

    /**
     * The single track in a role.
     *
     * <p>For {@link PartRole#OTHER}, which may legitimately repeat, this returns
     * only the first and is almost certainly not what you want; use
     * {@link #tracks(PartRole)} there.
     */
    public Optional<NoteTrack> track(PartRole role) {
        return tracks.stream().filter(t -> t.role() == role).findFirst();
    }

    /**
     * Every track in a role, in staff order.
     *
     * <p>Needed because source separation produces several unclassified parts,
     * so {@code OTHER} holds more than one and asking for "the" track would
     * silently drop the rest.
     */
    public List<NoteTrack> tracks(PartRole role) {
        return tracks.stream().filter(t -> t.role() == role).toList();
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

    /**
     * The piece's tempo in quarter notes per minute, from the best evidence
     * available.
     *
     * <p>There are two answers in a score and they do not agree, which is why
     * this exists: derive it twice and the two derivations drift, and a chart
     * whose printed tempo contradicts its own bar lines is worse than one that
     * is merely approximate. Every stage that needs a single tempo figure should
     * come here rather than pick one.
     *
     * <p>The order is:
     *
     * <ol>
     *   <li><b>A single-segment map wins.</b> Such a map did not come from
     *       tracked beats -- {@link TempoMap#fromBeatTimes} emits one segment per
     *       beat interval -- so it is a tempo somebody supplied, and a supplied
     *       tempo is a correction of the tracked one. Ignoring it is ignoring the
     *       instruction.
     *   <li><b>Otherwise the beat grid, if there is one.</b> Median interval, so
     *       one dropped beat does not skew it. Preferred over the map because
     *       {@link TempoMap#fromBeatTimes} gives the audio before the first
     *       tracked beat a whole beat of lead-in, and on a short clip that one
     *       crammed beat pulls the map's average measurably above the real tempo
     *       -- 122 BPM for a 120 BPM source on this project's own fixture. That
     *       distortion is in the map itself and is not fixed here; see #69.
     *   <li><b>Otherwise the map's duration-weighted average</b>, which is all
     *       that is left.
     * </ol>
     *
     * <p>In quarter notes per minute, like every other tempo in the model. For
     * the figure a musician counts, pass it through
     * {@link TimeSignature#countedTempo(double)}.
     */
    public double estimatedTempo() {
        if (tempoMap.segments().size() == 1) {
            return tempoMap.initialTempo();
        }
        if (beatGrid.isPresent() && beatGrid.get().size() >= 2) {
            return beatGrid.get().medianTempo(tempoMap.initialTimeSignature());
        }
        return tempoMap.averageTempo(durationSeconds);
    }

    /** True when there is enough here to engrave something useful. */
    public boolean hasRenderableContent() {
        return !tracks.isEmpty() || !chords.isEmpty();
    }

    /** Returns a copy with an added or replaced track for its role. */
    public Score withTrack(NoteTrack track) {
        Objects.requireNonNull(track, "track");
        List<NoteTrack> merged = new ArrayList<>(tracks.size() + 1);
        boolean replaced = false;
        for (NoteTrack existing : tracks) {
            // A repeatable role is matched by name as well, so adding a second
            // unclassified stem appends rather than overwriting the first.
            boolean sameTrack = track.role().allowsMultipleTracks()
                    ? existing.role() == track.role() && existing.name().equals(track.name())
                    : existing.role() == track.role();
            if (sameTrack) {
                merged.add(track);
                replaced = true;
            } else {
                merged.add(existing);
            }
        }
        if (!replaced) {
            merged.add(track);
        }
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
