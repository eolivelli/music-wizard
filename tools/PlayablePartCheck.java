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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.Melismas;
import dev.olivelli.musicwizard.arrange.PlayableMelody;
import dev.olivelli.musicwizard.arrange.QuantizationSettings;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Holds {@link PlayableMelody}'s reduction against a sequenced melody track for
 * the same recording, and prints what each of them says.
 *
 * <p>The reference is somebody's arrangement of the song, not ground truth: it
 * makes its own decisions about how many notes a phrase holds, and it is
 * written in its own octave and at its own steady tempo. So this reports note
 * counts first — the quantity a reduction is trying to move — and pitch second,
 * by pitch class, after fitting one affine map between the two clocks. The map
 * is fitted against MW's <em>estimate</em>, so the reduction cannot be scored
 * on an alignment chosen to suit it.
 *
 * <p>Then rhythm: which subdivisions the arranger themselves used, and what
 * each candidate grid vocabulary makes of both parts — the page it produces,
 * what it moves the onsets by, and whether the printed onsets end up nearer
 * the reference or further from it (#594).
 *
 * <p>What a printed note-head welds together needs no reference, so the
 * reference is optional and a workspace on its own gets that table (#598).
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/PlayablePartCheck.java WORKSPACE
 *   java -cp mw-cli/target/mw.jar tools/PlayablePartCheck.java WORKSPACE REFERENCE.mid [TRACK]
 *   java -cp mw-cli/target/mw.jar tools/PlayablePartCheck.java WORKSPACE REFERENCE.mid Melody 11.4 13.0
 * </pre>
 */
public final class PlayablePartCheck {

    /** How far apart two onsets may be and still be called the same note. */
    private static final double TOLERANCE_SECONDS = 0.25;

    /** The largest denominator exponent this reads. */
    private static final int MAX_DENOMINATOR_SHIFT = 6;

    private static final TimeSignature COMMON_TIME = new TimeSignature(4, 4);

    private static final int TEMPO = 0x51;

    private static final int METER = 0x58;

    /**
     * The tighter tolerance the vocabulary table is also read at. Below what
     * the melody stage can place a sung onset to (#497), so it says which
     * printing sits nearer the music rather than which one is right.
     */
    private static final double TIGHT_SECONDS = 0.05;

    /**
     * How much silence inside one printed note-head this counts as a weld, in
     * quarter-note beats. A beat of rest is a rest a reader would have played.
     */
    private static final double WELD_BEATS = 1.0;

    /** The claim bounds swept, in quarter-note beats; the first is no bound at all. */
    private static final double[] CLAIM_BOUNDS =
            {Double.MAX_VALUE, 4, 2, 1.5, 1.25, 1, 0.5, 0.25, 0};

    /** The widest run the melisma sweep asks a syllable's heads to reach. */
    private static final int MELISMA_SEMITONES = 7;

    /** The fold bound the run sweep holds fixed, and the run the fold sweep does. */
    private static final int MELISMA_FOLD = 12;

    private static final int MELISMA_RUN = 2;

    /** The fold bounds swept, in semitones; the last is no bound at all. */
    private static final int[] MELISMA_FOLDS = {5, 8, 12, 24, 128};

    /**
     * A run wider than any two note-heads can reach, so nothing is marked.
     * The row it prints is the reduction with melismas off, whatever marks the
     * workspace was analysed with.
     */
    private static final int MELISMA_OFF = 128;

    private static final double EPSILON = 1e-9;

    private record Event(double seconds, int pitch) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PlayablePartCheck WORKSPACE [REFERENCE.mid"
                    + " [TRACK [FROM TO]]]");
            System.exit(2);
        }
        Path workspace = Path.of(args[0]);
        Path scoreFile = Files.isRegularFile(workspace)
                ? workspace : workspace.resolve("score").resolve("score.json");
        Score score = ScoreJson.read(scoreFile);
        NoteTrack estimate = score.track(PartRole.LEAD_VOCAL).orElseThrow(
                () -> new IllegalStateException("no melody in " + scoreFile
                        + "; analyse with --melody"));
        NoteTrack reduced = PlayableMelody.reduce(score);

        System.out.printf(Locale.ROOT, "notes: estimate %d  reduced %d%n",
                estimate.size(), reduced.size());
        System.out.printf(Locale.ROOT,
                "syllables: %d over %d lyric lines, %d of them marked as melismas%n",
                score.lyrics().lines().stream().mapToInt(l -> l.words().size()).sum(),
                score.lyrics().lines().size(),
                score.lyrics().allWords().stream().filter(LyricWord::melisma).count());
        chart(score, reduced);
        if (args.length < 2) {
            // Everything below reads the reference arrangement. What the part
            // welds together does not, so a recording nobody has sequenced
            // still gets that table rather than a usage error.
            claims(score, estimate, List.of(), Double.NaN);
            melismas(score, estimate, List.of(), Double.NaN);
            return;
        }

        List<Event> reference = melodyOf(Path.of(args[1]), args.length > 2 ? args[2] : "Melody");
        System.out.printf(Locale.ROOT, "reference: %d notes%n", reference.size());
        double[] fit = fit(events(estimate), reference);
        double bar = barSeconds(Path.of(args[1])) * fit[0];
        System.out.printf(Locale.ROOT,
                "clock fit against the estimate: reference x %.4f %+.2f s, its bar %.3f s%n",
                fit[0], fit[1], bar);

        List<Event> mapped = new ArrayList<>(reference.size());
        for (Event event : reference) {
            mapped.add(new Event(event.seconds() * fit[0] + fit[1], event.pitch()));
        }
        report("estimate", events(estimate), mapped, bar);
        report("reduced ", events(reduced), mapped, bar);
        removals(events(estimate), events(reduced), mapped);
        claims(score, estimate, mapped, bar);
        melismas(score, estimate, mapped, bar);
        subdivisions(Path.of(args[1]), args.length > 2 ? args[2] : "Melody");
        vocabularies(score, estimate, reduced, mapped);

        if (args.length >= 5) {
            double from = Double.parseDouble(args[3]);
            double to = Double.parseDouble(args[4]);
            System.out.println();
            System.out.printf(Locale.ROOT, "window %.2f..%.2f s%n", from, to);
            print("  estimate ", events(estimate), from, to);
            print("  reduced  ", events(reduced), from, to);
            print("  reference", mapped, from, to);
            System.out.print("  syllables");
            for (LyricLine line : score.lyrics().lines()) {
                for (LyricWord word : line.words()) {
                    if (word.startSeconds() < to && word.endSeconds() > from) {
                        System.out.printf(Locale.ROOT, " %s(%.3f)", word.text(),
                                word.startSeconds());
                    }
                }
            }
            System.out.println();
        }
    }

    /**
     * What the printed note-heads run across, and what bounding the claim costs.
     *
     * <p>A group prints to its furthest release, so a claim that reached over
     * an instrumental gap prints as one note-head across the silence (#598).
     * Read off the two tracks rather than out of the grouping: what a reader
     * sees is the estimate's own rests vanishing inside a printed span.
     *
     * <p>{@code welded} and {@code widest} are what a note-head runs across,
     * which an unbounded claim is one cause of and a syllable the aligner
     * really does hold another. {@code heads}
     * climbing back toward the estimate's own count is what the bound costs,
     * since a note it refuses to claim prints on its own; the count and F1
     * columns say whether that cost lands on the music, and are left blank
     * where nobody has sequenced the recording.
     */
    private static void claims(Score score, NoteTrack estimate, List<Event> reference,
                               double barSeconds) {
        System.out.println();
        System.out.printf(Locale.ROOT, "%14s %7s %7s %9s %9s %10s %9s%n",
                "claim bound", "heads", "welded", "widest", "span", "per bar", "F1 loose");
        for (double bound : CLAIM_BOUNDS) {
            NoteTrack reduced = PlayableMelody.reduce(score, bound);
            Weld weld = welds(estimate, reduced, score.tempoMap());
            List<Event> events = events(reduced);
            System.out.printf(Locale.ROOT, "%14s %7d %7d %9.2f %9.2f %10s %9s%n",
                    bound == Double.MAX_VALUE ? "none"
                            : String.format(Locale.ROOT, "%.2f", bound),
                    reduced.size(), weld.welded(), weld.widestSilence(), weld.widestSpan(),
                    reference.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f",
                            countError(events, reference, barSeconds)),
                    reference.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f%%",
                            100 * f1(events, reference, TOLERANCE_SECONDS)));
        }
    }

    /**
     * What marking melismas costs and buys, swept over both of the decision's
     * intervals (#597): how far apart a syllable's note-heads must reach to be
     * a run, and how wide a leap between neighbouring heads is read as the
     * melody stage's octave fold instead (#624).
     *
     * <p>Every row but the first decides afresh from the melody, {@code off}
     * included, so the sweep reads the same whether or not the workspace was
     * analysed with melismas on. The first row is the workspace as it stands,
     * which is the part {@code render} writes and the one every other table
     * here reports; it is printed beside the sweep so the two cannot be read
     * as the same quantity. {@code marked} is how many syllables the
     * setting turns into melismas and {@code heads} what that puts on the
     * page; a setting that marks liberally walks {@code heads} back toward the
     * estimate's own count, which is the reduction undoing itself. {@code
     * welded} is what it is for: a note-head running across silence because a
     * syllable the aligner really does hold was collapsed into one.
     */
    private static void melismas(Score score, NoteTrack estimate, List<Event> reference,
                                 double barSeconds) {
        System.out.println();
        System.out.printf(Locale.ROOT, "%7s %5s %7s %7s %7s %9s %9s %10s %9s%n",
                "run", "fold", "marked", "heads", "welded", "widest", "span", "per bar",
                "F1 loose");
        row("as read", "", score, estimate, reference, barSeconds);
        melisma(score, estimate, reference, barSeconds, MELISMA_OFF, MELISMA_OFF);
        for (int run = 0; run <= MELISMA_SEMITONES; run++) {
            melisma(score, estimate, reference, barSeconds, run, MELISMA_FOLD);
        }
        for (int fold : MELISMA_FOLDS) {
            melisma(score, estimate, reference, barSeconds, MELISMA_RUN, fold);
        }
    }

    private static void melisma(Score score, NoteTrack estimate, List<Event> reference,
                                double barSeconds, int run, int fold) {
        boolean off = run == MELISMA_OFF;
        row(off ? "off" : String.valueOf(run), off ? "off" : String.valueOf(fold),
                score.withLyrics(Melismas.marked(score, run, fold)),
                estimate, reference, barSeconds);
    }

    private static void row(String run, String fold, Score marked, NoteTrack estimate,
                            List<Event> reference, double barSeconds) {
        NoteTrack reduced = PlayableMelody.reduce(marked);
        Weld weld = welds(estimate, reduced, marked.tempoMap());
        List<Event> events = events(reduced);
        System.out.printf(Locale.ROOT, "%7s %5s %7d %7d %7d %9.2f %9.2f %10s %9s%n",
                run, fold,
                marked.lyrics().allWords().stream().filter(LyricWord::melisma).count(),
                reduced.size(), weld.welded(), weld.widestSilence(), weld.widestSpan(),
                reference.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f",
                        countError(events, reference, barSeconds)),
                reference.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f%%",
                        100 * f1(events, reference, TOLERANCE_SECONDS)));
    }

    /** Silence inside the printed note-heads, in quarter-note beats. */
    private record Weld(int welded, double widestSilence, double widestSpan) {
    }

    /**
     * How much of what each printed note-head covers was not sounding.
     *
     * <p>The estimate's notes read against the printed span, in order, with
     * the widest hole in what they cover of it taken as that note-head's
     * silence. Nothing here assumes the estimate is monophonic, which is the
     * same reason {@code collapse} takes the furthest release rather than the
     * last one.
     */
    private static Weld welds(NoteTrack estimate, NoteTrack reduced, TempoMap map) {
        int welded = 0;
        double widestSilence = 0;
        double widestSpan = 0;
        for (Note printed : reduced.notes()) {
            double from = map.secondsToBeats(printed.onsetSeconds());
            double to = map.secondsToBeats(printed.offsetSeconds());
            widestSpan = Math.max(widestSpan, to - from);
            double reached = from;
            double silence = 0;
            for (Note note : estimate.notes()) {
                double start = map.secondsToBeats(note.onsetSeconds());
                double end = map.secondsToBeats(note.offsetSeconds());
                // Clamped overlap rather than containment: a head can open or
                // close inside an estimate note (#616), and that note still
                // sounds under it -- reading only contained notes counted the
                // sounding remainder as silence.
                if (end < from - EPSILON || start > to + EPSILON) {
                    continue;
                }
                silence = Math.max(silence, Math.max(start, from) - reached);
                reached = Math.max(reached, Math.min(end, to));
            }
            if (silence > WELD_BEATS) {
                welded++;
            }
            widestSilence = Math.max(widestSilence, silence);
        }
        return new Weld(welded, widestSilence, widestSpan);
    }

    /**
     * Which divisions of its own counted beat the reference arrangement uses.
     *
     * <p>Read in the reference's clock rather than the recording's, so it says
     * what somebody decided a reader should play and not how well the beat
     * tracker followed it.
     */
    private static void subdivisions(Path file, String track) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new File(file.toString()));
        List<Long> ticks = new ArrayList<>();
        for (Track candidate : sequence.getTracks()) {
            if (named(candidate, track)) {
                for (int i = 0; i < candidate.size(); i++) {
                    if (candidate.get(i).getMessage() instanceof ShortMessage message
                            && message.getCommand() == ShortMessage.NOTE_ON
                            && message.getData2() > 0) {
                        ticks.add(candidate.get(i).getTick());
                    }
                }
            }
        }
        // Ticks per COUNTED beat, which is what a resolution divides: the file
        // states ticks per quarter, and in compound time the counted beat is a
        // dotted quarter, so dividing the one by the other would label every
        // row with the wrong note value.
        double perBeat = sequence.getResolution() * meterOf(sequence).beatUnitQuarters();
        StringBuilder line = new StringBuilder();
        for (GridResolution grid : GridResolution.values()) {
            double step = perBeat / grid.divisionsPerBeat();
            int on = 0;
            for (long tick : ticks) {
                if (Math.abs(tick - Math.round(tick / step) * step) < 1e-6) {
                    on++;
                }
            }
            line.append(String.format(Locale.ROOT, " %s %d", grid, on));
        }
        System.out.printf(Locale.ROOT,
                "the reference's own reading: %d melody onsets, of which sit exactly on%s%n",
                ticks.size(), line);
    }

    private static boolean named(Track track, String wanted) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                return new String(meta.getData()).trim().equalsIgnoreCase(wanted);
            }
        }
        return false;
    }

    /**
     * What each way of restricting the divisions makes of the two parts, which
     * is the sweep {@link QuantizationSettings#READING} was chosen from (#594).
     *
     * <p>A page gets hard to read in two ways, and withdrawing a division fixes
     * one by worsening the other: the brackets go and the same notes come back
     * as chains of tied shorter values. {@code tupletBars} counts the first,
     * which needs counting because whether a division is a tuplet depends on
     * the meter. The second is read off {@code grids}, which says how many bars
     * went to each division: a summary of that column loses the handful of bars
     * the defect lives in, whichever way it is summarised. {@code moved} is
     * what the printing costs in literalness, in beats, against the part's own
     * onsets. The F1 columns are the printed onsets against the reference, at a
     * tolerance below what the melody stage can place (#497) and at one above
     * it.
     */
    private static void vocabularies(Score score, NoteTrack estimate, NoteTrack reduced,
                                     List<Event> reference) {
        Map<String, QuantizationSettings> ways = new LinkedHashMap<>();
        QuantizationSettings all = QuantizationSettings.DEFAULT;
        ways.put("every division", all);
        ways.put("the meter's own only", all.withoutTuplets());
        ways.put("the meter's own, one level down",
                all.withoutTuplets().withLevelsBelowTheBeat(1));
        ways.put("the meter's own, two levels down (READING)",
                QuantizationSettings.READING);
        ways.put("the counted beat only", all.withLevelsBelowTheBeat(0));
        System.out.println();
        System.out.printf(Locale.ROOT, "%-52s %8s %8s %8s %10s   %s%n",
                "divisions offered", "F1 tight", "F1 loose", "moved", "tupletBars", "grids");
        for (Map.Entry<String, QuantizationSettings> entry : ways.entrySet()) {
            vocabulary("estimate  " + entry.getKey(), score, estimate,
                    entry.getValue(), reference);
        }
        for (Map.Entry<String, QuantizationSettings> entry : ways.entrySet()) {
            vocabulary("reduced   " + entry.getKey(), score.withTrack(reduced), reduced,
                    entry.getValue(), reference);
        }
    }

    private static void vocabulary(String label, Score score, NoteTrack played,
                                   QuantizationSettings settings, List<Event> reference) {
        QuantizedScore quantized = Quantizer.quantize(score, settings);
        NoteTrack printed = quantized.score().track(PartRole.LEAD_VOCAL).orElseThrow();
        TempoMap map = score.tempoMap();
        List<Event> events = new ArrayList<>(printed.size());
        double moved = 0;
        for (int i = 0; i < printed.size(); i++) {
            Note note = printed.notes().get(i);
            double beat = note.onsetBeat()
                    .orElseGet(() -> map.secondsToBeats(note.onsetSeconds()));
            events.add(new Event(map.beatsToSeconds(beat), note.midiPitch()));
            moved += Math.abs(beat - map.secondsToBeats(played.notes().get(i).onsetSeconds()));
        }
        int tuplets = 0;
        Map<GridResolution, Integer> histogram = new EnumMap<>(GridResolution.class);
        for (BarGrid grid : quantized.grids()) {
            histogram.merge(grid.resolution(), 1, Integer::sum);
            if (grid.resolution().isTupletIn(grid.timeSignature())
                    && grid.resolution() != GridResolution.BEAT) {
                tuplets++;
            }
        }
        System.out.printf(Locale.ROOT, "%-52s %7.1f%% %7.1f%% %8.4f %10d   %s%n",
                label, 100 * f1(events, reference, TIGHT_SECONDS),
                100 * f1(events, reference, TOLERANCE_SECONDS),
                moved / Math.max(1, printed.size()), tuplets, histogram);
    }

    /** Note F1 at a stated onset tolerance, matched one to one by pitch class. */
    private static double f1(List<Event> mine, List<Event> reference, double tolerance) {
        boolean[] used = new boolean[mine.size()];
        int matched = 0;
        for (Event event : reference) {
            int best = -1;
            double nearest = tolerance;
            for (int i = 0; i < mine.size(); i++) {
                double distance = Math.abs(mine.get(i).seconds() - event.seconds());
                if (!used[i] && distance < nearest
                        && Math.floorMod(mine.get(i).pitch(), 12)
                        == Math.floorMod(event.pitch(), 12)) {
                    nearest = distance;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                matched++;
            }
        }
        double precision = mine.isEmpty() ? 0 : matched / (double) mine.size();
        double recall = reference.isEmpty() ? 0 : matched / (double) reference.size();
        return matched == 0 ? 0 : 2 * precision * recall / (precision + recall);
    }

    private static List<Event> events(NoteTrack track) {
        List<Event> out = new ArrayList<>(track.size());
        for (Note note : track.notes()) {
            out.add(new Event(note.onsetSeconds(), note.midiPitch()));
        }
        return out;
    }

    private static void print(String label, List<Event> events, double from, double to) {
        StringBuilder line = new StringBuilder(label);
        for (Event event : events) {
            if (event.seconds() >= from && event.seconds() < to) {
                line.append(String.format(Locale.ROOT, " %s(%.3f)",
                        name(event.pitch()), event.seconds()));
            }
        }
        System.out.println(line);
    }

    private static String name(int pitch) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[Math.floorMod(pitch, 12)] + (pitch / 12 - 1);
    }

    /**
     * Which of my note-heads a reference note was matched to, one to one.
     *
     * <p>Nearest first among the ones whose pitch class agrees, and each of
     * mine may be claimed once: two note-heads a reduction merged must not both
     * be able to answer for the same reference note.
     */
    private static boolean[] matched(List<Event> mine, List<Event> reference) {
        boolean[] used = new boolean[mine.size()];
        for (Event event : reference) {
            int best = -1;
            double nearest = TOLERANCE_SECONDS;
            for (int i = 0; i < mine.size(); i++) {
                double distance = Math.abs(mine.get(i).seconds() - event.seconds());
                if (!used[i] && distance < nearest
                        && Math.floorMod(mine.get(i).pitch(), 12)
                        == Math.floorMod(event.pitch(), 12)) {
                    nearest = distance;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
            }
        }
        return used;
    }

    /**
     * How much of the reduced part the chord chart is responsible for.
     *
     * <p>Measured by reducing the same score with its progression removed,
     * which is the one input the chart reaches: grouping never asks about
     * harmony, so every difference between the two runs is a tie the chart
     * broke. #571 is why this wants stating rather than assuming.
     */
    private static void chart(Score score, NoteTrack reduced) {
        NoteTrack without = PlayableMelody.reduce(
                score.withChords(dev.olivelli.musicwizard.core.model.ChordProgression.empty()));
        int moved = 0;
        for (int i = 0; i < Math.min(reduced.size(), without.size()); i++) {
            if (reduced.notes().get(i).midiPitch() != without.notes().get(i).midiPitch()) {
                moved++;
            }
        }
        System.out.printf(Locale.ROOT,
                "chart: reducing without the progression gives %d note-heads and moves %d"
                        + " of the printed pitches%n",
                without.size(), moved + Math.abs(reduced.size() - without.size()));
    }

    /**
     * What the reduction stopped printing, and whether it was any good.
     *
     * <p>Not the difference of the two matched counts, which is what it looks
     * like: a group's surviving note carries an onset and a <em>settled</em>
     * pitch of the group's own, so it is a note-head neither side had. Both
     * directions are therefore counted rather than subtracted.
     */
    private static void removals(List<Event> estimate, List<Event> reduced,
                                 List<Event> reference) {
        boolean[] agreed = matched(estimate, reference);
        java.util.Set<Event> kept = new java.util.HashSet<>(reduced);
        int gone = 0;
        int goneAndAgreed = 0;
        for (int i = 0; i < estimate.size(); i++) {
            if (!kept.contains(estimate.get(i))) {
                gone++;
                if (agreed[i]) {
                    goneAndAgreed++;
                }
            }
        }
        java.util.Set<Event> was = new java.util.HashSet<>(estimate);
        long fresh = reduced.stream().filter(event -> !was.contains(event)).count();
        System.out.printf(Locale.ROOT,
                "removals: %d of the estimate's note-heads are no longer printed at the same"
                        + " onset and pitch, %d of them ones that had agreed;"
                        + " %d are printed at a new one%n",
                gone, goneAndAgreed, fresh);
    }

    /** Counts per reference bar, and one-to-one pitch-class agreement. */
    private static void report(String label, List<Event> mine, List<Event> reference,
                               double barSeconds) {
        boolean[] used = matched(mine, reference);
        int matched = 0;
        for (boolean claimed : used) {
            if (claimed) {
                matched++;
            }
        }
        double precision = mine.isEmpty() ? 0 : matched / (double) mine.size();
        double recall = reference.isEmpty() ? 0 : matched / (double) reference.size();
        double f1 = matched == 0 ? 0 : 2 * precision * recall / (precision + recall);
        System.out.printf(Locale.ROOT,
                "%s  count per reference bar off by %.2f on average  |  matched %d,"
                        + " precision %.1f%% recall %.1f%% F1 %.1f%%%n",
                label, countError(mine, reference, barSeconds),
                matched, 100 * precision, 100 * recall, 100 * f1);
    }

    /**
     * Mean absolute difference in notes per bar, over the reference's own bars.
     *
     * <p>The reference's bars rather than MW's: the recording's tracked grid is
     * at double rate (#378), so counting into it would price that defect rather
     * than the reduction.
     */
    private static double countError(List<Event> mine, List<Event> reference,
                                     double bar) {
        if (reference.size() < 2 || !(bar > 0)) {
            return Double.NaN;
        }
        double first = reference.get(0).seconds();
        double last = reference.get(reference.size() - 1).seconds();
        int bars = (int) Math.ceil((last - first) / bar);
        int[] here = new int[bars + 1];
        int[] there = new int[bars + 1];
        for (Event event : mine) {
            int index = (int) Math.floor((event.seconds() - first) / bar);
            if (index >= 0 && index <= bars) {
                here[index]++;
            }
        }
        for (Event event : reference) {
            int index = (int) Math.floor((event.seconds() - first) / bar);
            if (index >= 0 && index <= bars) {
                there[index]++;
            }
        }
        double total = 0;
        for (int i = 0; i <= bars; i++) {
            total += Math.abs(here[i] - there[i]);
        }
        return total / (bars + 1);
    }

    /**
     * The affine map from the reference's clock to the recording's, chosen to
     * put as many reference onsets as possible next to one of MW's.
     */
    private static double[] fit(List<Event> mine, List<Event> reference) {
        double[] onsets = mine.stream().mapToDouble(Event::seconds).sorted().toArray();
        double bestScale = 1;
        double bestOffset = 0;
        int best = -1;
        for (int s = -60; s <= 60; s++) {
            double scale = 1 + s * 0.001;
            for (int o = -120; o <= 120; o++) {
                double offset = o * 0.05;
                int hits = 0;
                for (Event event : reference) {
                    double at = event.seconds() * scale + offset;
                    int index = lowerBound(onsets, at - TOLERANCE_SECONDS);
                    if (index < onsets.length && onsets[index] < at + TOLERANCE_SECONDS) {
                        hits++;
                    }
                }
                if (hits > best) {
                    best = hits;
                    bestScale = scale;
                    bestOffset = offset;
                }
            }
        }
        return new double[] {bestScale, bestOffset};
    }

    private static int lowerBound(double[] sorted, double value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * One bar of the reference, in its own seconds.
     *
     * <p>Read from the file's opening tempo and meter rather than assumed. A
     * file that changes either of them is out of scope for this tool, which
     * exists to window a comparison and not to follow an arrangement.
     */
    private static double barSeconds(Path file) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new File(file.toString()));
        MetaMessage tempo = earliest(sequence, TEMPO);
        double secondsPerQuarter = 0.5;
        byte[] data = tempo == null ? null : payload(tempo, 3);
        if (data != null) {
            secondsPerQuarter = (((data[0] & 0xff) << 16)
                    | ((data[1] & 0xff) << 8) | (data[2] & 0xff)) / 1_000_000.0;
        }
        return meterOf(sequence).quarterBeatsPerBar() * secondsPerQuarter;
    }

    /**
     * The earliest event of a meta type in the file, or null.
     *
     * <p>Chosen before it is decoded, so that a malformed one is reported as
     * what was used rather than skipped in favour of a later event the file
     * does not open with.
     */
    private static MetaMessage earliest(Sequence sequence, int type) {
        MetaMessage found = null;
        long at = Long.MAX_VALUE;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta
                        && meta.getType() == type && track.get(i).getTick() < at) {
                    at = track.get(i).getTick();
                    found = meta;
                }
            }
        }
        return found;
    }

    /**
     * A meta event's bytes, or null with a warning when there are too few.
     *
     * <p>Every reference this reads is third-party MIDI off the web, so a
     * truncated event is a thing that happens and must not take down a run that
     * has printed nothing yet. One reader for all of them.
     */
    private static byte[] payload(MetaMessage meta, int minimum) {
        byte[] data = meta.getData();
        if (data.length < minimum) {
            System.err.printf(Locale.ROOT,
                    "warning: a meta event of type 0x%02x carries %d byte(s) where %d are"
                            + " needed, so it is ignored%n", meta.getType(), data.length, minimum);
            return null;
        }
        return data;
    }

    /** The file's opening meter, or common time when it states none this can read. */
    private static TimeSignature meterOf(Sequence sequence) {
        MetaMessage event = earliest(sequence, METER);
        byte[] data = event == null ? null : payload(event, 2);
        if (data == null) {
            return COMMON_TIME;
        }
        int exponent = data[1] & 0xff;
        // A shift is taken mod 32 in Java, so an out-of-range one does not
        // overflow loudly -- it returns a plausible denominator and a silently
        // wrong bar length.
        if (exponent > MAX_DENOMINATOR_SHIFT) {
            System.err.printf(Locale.ROOT,
                    "warning: the opening meter's denominator exponent is %d, past what this"
                            + " reads, so it is read as %s%n", exponent, COMMON_TIME);
            return COMMON_TIME;
        }
        int numerator = data[0] & 0xff;
        int denominator = 1 << exponent;
        try {
            return new TimeSignature(numerator, denominator);
        } catch (IllegalArgumentException e) {
            // The meter only labels a row and scales the bar, so a meter the
            // model refuses is worth a line on stderr rather than a stack trace
            // in place of the whole report.
            System.err.printf(Locale.ROOT,
                    "warning: the opening meter %d/%d is not one this can read, so it is read"
                            + " as %s: %s%n", numerator, denominator, COMMON_TIME, e.getMessage());
            return COMMON_TIME;
        }
    }

    /** The named track's note-ons, in seconds, honouring the file's tempo events. */
    private static List<Event> melodyOf(Path file, String wanted) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new File(file.toString()));
        TreeMap<Long, Double> tempo = new TreeMap<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta
                        && meta.getType() == TEMPO) {
                    byte[] data = payload(meta, 3);
                    if (data == null) {
                        continue;
                    }
                    int microseconds = ((data[0] & 0xff) << 16)
                            | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                    tempo.put(track.get(i).getTick(), microseconds / 1_000_000.0);
                }
            }
        }
        if (tempo.isEmpty()) {
            tempo.put(0L, 0.5);
        }
        for (Track track : sequence.getTracks()) {
            String name = null;
            for (int i = 0; i < track.size() && name == null; i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                    name = new String(meta.getData()).trim();
                }
            }
            if (name == null || !name.equalsIgnoreCase(wanted)) {
                continue;
            }
            List<Event> out = new ArrayList<>();
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON
                        && message.getData2() > 0) {
                    out.add(new Event(seconds(event.getTick(), sequence.getResolution(), tempo),
                            message.getData1()));
                }
            }
            return out;
        }
        throw new IllegalArgumentException("no track named " + wanted + " in " + file);
    }

    private static double seconds(long tick, int resolution, TreeMap<Long, Double> tempo) {
        double at = 0;
        long previous = 0;
        double secondsPerQuarter = tempo.firstEntry().getValue();
        for (Map.Entry<Long, Double> change : tempo.entrySet()) {
            if (change.getKey() >= tick) {
                break;
            }
            at += (change.getKey() - previous) / (double) resolution * secondsPerQuarter;
            previous = change.getKey();
            secondsPerQuarter = change.getValue();
        }
        return at + (tick - previous) / (double) resolution * secondsPerQuarter;
    }

    private PlayablePartCheck() {
    }
}
