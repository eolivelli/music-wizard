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
        requireGridPulseFitsTheBar(beatGrid, tempoMap);
    }

    /**
     * Rejects a grid whose recorded pulse contradicts the bars it actually
     * marks.
     *
     * <p>A grid says what one pulse is worth and marks which pulses begin a bar.
     * Multiply the two and the answer has to be the meter's bar length, or the
     * grid and the map describe bars of different sizes -- which is #139's
     * symptom re-entering by a different door, since a grid can be handed a
     * pulse after it was barred, or read from a file somebody edited.
     *
     * <p><b>Checked here because nowhere lower can.</b> A {@link BeatGrid} holds
     * no meter, so the factory that bars from a pulse validates only what it is
     * given; this is the one place holding the grid, the pulse and the meter at
     * once.
     *
     * <p>Four things make it safe to throw on. It runs only for a grid that
     * <em>records</em> a pulse, so no file written before #139 can reach it. It
     * needs two consecutive downbeats, so a clip shorter than a bar -- which
     * shows a bar cycle that is observably wrong -- is skipped rather than
     * guessed at. It needs a map with a single meter, since bar lengths differ
     * either side of a meter change and the first cycle would not describe the
     * rest. And it reads the first complete cycle only, so the cost is one bar
     * rather than a scan of a grid that on a long track holds a hundred
     * thousand pulses.
     */
    private static void requireGridPulseFitsTheBar(
            Optional<BeatGrid> beatGrid, TempoMap tempoMap) {
        if (beatGrid.isEmpty() || beatGrid.get().pulseQuarters().isEmpty()
                || tempoMap.meterChanges().size() != 1) {
            return;
        }
        List<BeatGrid.Beat> beats = beatGrid.get().beats();
        int firstDownbeat = -1;
        int pulsesPerBar = -1;
        for (int i = 0; i < beats.size() && pulsesPerBar < 0; i++) {
            if (!beats.get(i).downbeat()) {
                continue;
            }
            if (firstDownbeat < 0) {
                firstDownbeat = i;
            } else {
                pulsesPerBar = i - firstDownbeat;
            }
        }
        if (pulsesPerBar < 0) {
            return;
        }
        double pulseQuarters = beatGrid.get().pulseQuarters().getAsDouble();
        TimeSignature meter = tempoMap.initialTimeSignature();
        double barQuarters = pulsesPerBar * pulseQuarters;
        double expected = meter.quarterBeatsPerBar();
        // The same tolerance TimeSignature.pulsesPerBar allows, so a pulse that
        // method accepts for this meter cannot be rejected here.
        if (Math.abs(barQuarters - expected) > 1e-9 * Math.max(1.0, expected)) {
            throw new IllegalArgumentException(
                    "the beat grid records a pulse of " + pulseQuarters
                            + " quarter notes and bars every " + pulsesPerBar
                            + " pulses, which is " + barQuarters + " quarter notes to the bar,"
                            + " but the tempo map's " + meter + " bar is " + expected);
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
     *   <li><b>A supplied tempo wins.</b> A tempo somebody typed is a correction
     *       of the tracked one, and a correction that loses to the value it
     *       corrects is not a correction. Which segments are supplied is read
     *       from {@link TempoMap.TempoSegment#provenance()} rather than inferred
     *       from the map's shape -- the whole point of #120. The lead-in of an
     *       anchored override is {@link Provenance#DERIVED}, so it is skipped
     *       because it says it is an artefact, not because it happens to sit
     *       before the grid's first beat.
     *       <p>Where several segments are supplied and disagree, no single
     *       figure was supplied, and the map itself is the instruction:
     *       {@link TempoMap#averageTempoIgnoringLeadIn(double)} answers instead.
     *       Falling through to the beat grid there would answer a correction
     *       with the thing being corrected, and the plain average would fold in
     *       an anchoring lead-in the user did not ask for.
     *   <li><b>Otherwise, for a map that records no provenance at all, the old
     *       shape proxy.</b> A map that states one tempo from the first tracked
     *       beat onwards is taken to be a correction. This is a guess, and it is
     *       kept only for values that predate the field -- a {@code score.json}
     *       written by an earlier build, or a map assembled by a caller that did
     *       not say. For anything a current producer builds it never runs.
     *   <li><b>Otherwise the beat grid, if there is one.</b> Median interval, so
     *       one dropped beat does not skew it, converted to quarter notes by the
     *       pulse the grid itself records -- or, for a grid that records none,
     *       by the meter's counted beat, which is what such a grid was tracked
     *       at. Reading the meter for a grid that does say is #139: it answered
     *       half the tempo for a grid tracked at half tempo, and the map built
     *       from those very pulses said the other figure. Preferred over the map
     *       because
     *       {@link TempoMap#fromBeatTimes} gives the audio before the first
     *       tracked beat a whole beat of lead-in, and on a short clip that one
     *       crammed beat pulls the map's average measurably above the real tempo
     *       -- 122 BPM for a 120 BPM source on this project's own fixture. That
     *       distortion is in the map itself and is not fixed here; see #69.
     *   <li><b>Otherwise the map's duration-weighted average</b>, which is all
     *       that is left -- taken over the segments that describe the music,
     *       skipping a lead-in the map labels as an artefact of anchoring. See
     *       {@link TempoMap#averageTempoIgnoringLeadIn(double)}: a clip that
     *       tracks one lone beat is the reachable case, since its grid is too
     *       short for a median and its map is a lead-in plus one segment.
     * </ol>
     *
     * <p>A {@link Provenance#DECLARED} map -- one imported from a MIDI file --
     * deliberately does <em>not</em> short-circuit to its own opening tempo. A
     * file that changes tempo states no single figure, and the weighted average
     * is the honest summary of one that does. Since such a score carries no beat
     * grid today (#98), the answer is the same either way -- but when #98 gives
     * it a derived grid, a grid derived from the file must not be allowed to
     * overrule the file, and this rule will need revisiting there rather than
     * in the importer.
     *
     * <p>In quarter notes per minute, like every other tempo in the model. For
     * the figure a musician counts, pass it through
     * {@link TimeSignature#countedTempo(double)}.
     */
    public double estimatedTempo() {
        double candidate = Double.NaN;
        boolean anySupplied = false;
        boolean suppliedAgree = true;
        for (TempoMap.TempoSegment segment : tempoMap.segments()) {
            if (segment.provenance() != Provenance.SUPPLIED) {
                continue;
            }
            if (!anySupplied) {
                anySupplied = true;
                candidate = segment.beatsPerMinute();
            } else if (segment.beatsPerMinute() != candidate) {
                suppliedAgree = false;
            }
        }
        if (anySupplied) {
            // Disagreeing corrections state no single tempo, but they are still
            // corrections: the map summarises itself rather than deferring to
            // the grid, which is the evidence they were issued against.
            return suppliedAgree
                    ? candidate
                    : tempoMap.averageTempoIgnoringLeadIn(durationSeconds);
        }
        if (recordsNoProvenance()) {
            double stated = statedConstantTempo();
            if (!Double.isNaN(stated)) {
                return stated;
            }
        }
        if (beatGrid.isPresent() && beatGrid.get().size() >= 2) {
            return beatGrid.get().medianTempo(tempoMap.initialTimeSignature());
        }
        return tempoMap.averageTempoIgnoringLeadIn(durationSeconds);
    }

    /**
     * Whether no segment in the map says where its tempo came from.
     *
     * <p>The gate on the shape proxy. One recorded label is enough to retire it
     * for the whole map: a producer that labelled anything has said more than
     * the guess can, and applying the guess to the segments it did not label
     * would mix an answer from evidence with an answer from shape.
     */
    private boolean recordsNoProvenance() {
        for (TempoMap.TempoSegment segment : tempoMap.segments()) {
            if (segment.provenance() != Provenance.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    /**
     * The one tempo the map states, or {@code NaN} when it states none.
     *
     * <p><b>A proxy for provenance, kept only for maps that carry none.</b> It
     * was the whole rule before #120 and is now the fallback for a score file
     * written before segments recorded their origin. Two things it gets wrong
     * and reading the label does not: a supplied map whose score carries no beat
     * grid, where there is nothing to identify the lead-in against and this
     * gives up; and a measured map that happens to state one tempo, which this
     * reads as a correction. Neither is reachable for a map a current producer
     * built, because such a map is never all-{@code UNKNOWN}.
     *
     * <p>A single-segment map states its only tempo. A longer one states a tempo
     * when every segment from the first tracked beat onwards agrees. Only a
     * lead-in segment is exempt, and it is identified rather than assumed: the
     * segment before the one that begins exactly at the grid's first beat. That
     * segment exists to land the first tracked pulse on a whole pulse and carries
     * whatever rate that took, which is a fact about the pickup and not about the
     * piece. Without the exemption, correcting the tempo of a recording whose
     * first beat is a fraction of a second in would leave the chart headed
     * with -- and barred at -- the tracked tempo the correction was issued
     * against. Exempting segment zero <em>unconditionally</em> would be the other
     * error: a map with no lead-in would have its opening tempo ignored.
     *
     * <p>Compared exactly rather than within a tolerance. A stated tempo is one
     * value copied into every segment, so it is bit-identical by construction; a
     * measured one is a division per beat interval, and asking whether two of
     * those are close enough is asking how steady a performance has to be before
     * its map counts as a claim, which is not a question this can answer.
     */
    private double statedConstantTempo() {
        List<TempoMap.TempoSegment> segments = tempoMap.segments();
        int from = 0;
        if (segments.size() > 1 && beatGrid.isPresent()
                && segments.get(1).startSeconds() == beatGrid.get().beats().get(0).seconds()) {
            from = 1;
        }
        double candidate = segments.get(from).beatsPerMinute();
        for (int i = from + 1; i < segments.size(); i++) {
            if (segments.get(i).beatsPerMinute() != candidate) {
                return Double.NaN;
            }
        }
        return candidate;
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
