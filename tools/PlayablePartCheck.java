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

import dev.olivelli.musicwizard.arrange.PlayableMelody;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/PlayablePartCheck.java WORKSPACE REFERENCE.mid [TRACK]
 *   java -cp mw-cli/target/mw.jar tools/PlayablePartCheck.java WORKSPACE REFERENCE.mid Melody 11.4 13.0
 * </pre>
 */
public final class PlayablePartCheck {

    /** How far apart two onsets may be and still be called the same note. */
    private static final double TOLERANCE_SECONDS = 0.25;

    private record Event(double seconds, int pitch) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PlayablePartCheck WORKSPACE REFERENCE.mid"
                    + " [TRACK [FROM TO]]");
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
        List<Event> reference = melodyOf(Path.of(args[1]), args.length > 2 ? args[2] : "Melody");

        System.out.printf(Locale.ROOT, "notes: estimate %d  reduced %d  reference %d%n",
                estimate.size(), reduced.size(), reference.size());
        System.out.printf(Locale.ROOT, "syllables: %d over %d lyric lines%n",
                score.lyrics().lines().stream().mapToInt(l -> l.words().size()).sum(),
                score.lyrics().lines().size());

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
     * What the reduction stopped printing, and whether it was any good.
     *
     * <p>Not the difference of the two matched counts, which is what it looks
     * like: a group's surviving note carries its <em>first</em> piece's onset
     * and its <em>settled</em> pitch, so it is a note-head neither side had.
     * Both directions are therefore counted rather than subtracted.
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
        double secondsPerQuarter = 0.5;
        int numerator = 4;
        int denominator = 4;
        long earliestTempo = Long.MAX_VALUE;
        long earliestMeter = Long.MAX_VALUE;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (!(track.get(i).getMessage() instanceof MetaMessage meta)) {
                    continue;
                }
                byte[] data = meta.getData();
                if (meta.getType() == 0x51 && track.get(i).getTick() < earliestTempo) {
                    earliestTempo = track.get(i).getTick();
                    secondsPerQuarter = (((data[0] & 0xff) << 16)
                            | ((data[1] & 0xff) << 8) | (data[2] & 0xff)) / 1_000_000.0;
                } else if (meta.getType() == 0x58 && track.get(i).getTick() < earliestMeter) {
                    earliestMeter = track.get(i).getTick();
                    numerator = data[0] & 0xff;
                    denominator = 1 << (data[1] & 0xff);
                }
            }
        }
        return numerator * (4.0 / denominator) * secondsPerQuarter;
    }

    /** The named track's note-ons, in seconds, honouring the file's tempo events. */
    private static List<Event> melodyOf(Path file, String wanted) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new File(file.toString()));
        TreeMap<Long, Double> tempo = new TreeMap<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
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
