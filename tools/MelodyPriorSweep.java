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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.HarmonicPrior;
import dev.olivelli.musicwizard.dsp.MelodyEstimator;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import dev.olivelli.musicwizard.dsp.PitchTrack;
import dev.olivelli.musicwizard.dsp.PitchTracker;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * The harmonic prior's own bench: analyse each recording once, then ask
 * {@link MelodyEstimator} the same question at as many prior strengths as
 * wanted.
 *
 * <p>{@code tools/score-melody.py} is the committed baseline and the only thing
 * that decides anything, but it re-runs the whole front end for every question
 * — minutes per corpus — so it cannot derive a constant. This runs the front
 * end once per recording and keeps the pitch track, the envelope, the tuning
 * and the chart in memory, which makes a whole sweep of {@link HarmonicPrior}
 * cost one analysis.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/MelodyPriorSweep.java sweep
 *   java -cp mw-cli/target/mw.jar tools/MelodyPriorSweep.java sweep vocadito
 *   java -cp mw-cli/target/mw.jar tools/MelodyPriorSweep.java profile &lt;mix&gt; [stem [weight floor]]
 * </pre>
 *
 * <p><b>It reads the mix unless it is handed stems</b>, which {@code mw
 * separate} writes. That arm is the bench's answer to the {@code --separated}
 * baselines rather than a reproduction of them: those track the separator's
 * own buffer where this reads the file it was written to.
 *
 * <p>{@code profile} takes any recording, scores nothing, and prints one line
 * per note the stage produced: the median it was rounded from, on the grid it
 * was rounded on, the widest chord span over it, and whether the prior moved
 * it. That is what a commercial recording — which has no ground truth here and
 * cannot be committed — can still be read with, and the median column is what
 * says whether a note was ever in doubt at all.
 */
public final class MelodyPriorSweep {

    /** Onset tolerances the note columns are matched at, matching score-melody.py. */
    private static final double[] TOLERANCES = {0.05, 0.10};

    private static final double FRAME_SECONDS = 0.01;

    private static final String MELODY_TRACK = "Melody";

    private static final Path CORPUS = Path.of("synthetic_samples");

    private static final Path VOCADITO = Path.of("uncommitted", "vocadito");

    private static final int VOCADITO_CLIPS = 40;

    private MelodyPriorSweep() {
    }

    /** A reference note, in seconds, on the semitone. */
    record Ref(double onset, double end, int pitch) {}

    /** Everything about a recording that a sweep does not change. */
    record Analysed(String name, PitchTrack pitches, OnsetEnvelope envelope, double tuning,
                    ChordProgression chords, List<Ref> reference) {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "sweep";
        switch (mode) {
            case "sweep" -> {
                String source = args.length > 1 ? args[1] : "synthetic";
                Path stems = args.length > 2 ? Path.of(args[2]) : null;
                sweep(source.equals("vocadito") ? vocadito(stems) : synthetic(stems));
            }
            case "profile" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("profile needs a recording");
                }
                Analysed one = analyse(Path.of(args[1]), List.of());
                if (args.length > 2) {
                    AudioBuffer stem = AudioDecoder.decode(Path.of(args[2]));
                    one = new Analysed(one.name(), PitchTracker.track(stem),
                            OnsetEnvelope.fromAudio(stem), one.tuning(), one.chords(), List.of());
                }
                profile(one, args.length > 4
                        ? HarmonicPrior.of(one.chords(), Double.parseDouble(args[3]),
                                Double.parseDouble(args[4]))
                        : HarmonicPrior.of(one.chords()));
            }
            default -> throw new IllegalArgumentException("usage: sweep [synthetic|vocadito]"
                    + " | profile <mix> [stem [weight floor]]");
        }
    }

    // ------------------------------------------------------------------ corpora

    private static List<Analysed> synthetic(Path stems) throws Exception {
        List<Analysed> analysed = new ArrayList<>();
        try (var specs = Files.list(CORPUS)) {
            for (Path spec : specs.filter(p -> p.getFileName().toString().endsWith(".spec.txt"))
                    .sorted().toList()) {
                String name = spec.getFileName().toString().replace(".spec.txt", "");
                Path midi = spec.resolveSibling(name + ".mid");
                Path mp3 = spec.resolveSibling(name + ".mp3");
                List<Ref> reference = melodyNotes(midi);
                if (reference.isEmpty()) {
                    continue;   // no melody track: score-melody.py does not score it either
                }
                analysed.add(throughStem(analyse(mp3, reference), stems));
            }
        }
        return analysed;
    }

    private static List<Analysed> vocadito(Path stems) throws Exception {
        List<Analysed> analysed = new ArrayList<>();
        for (int clip = 1; clip <= VOCADITO_CLIPS; clip++) {
            Path audio = VOCADITO.resolve("Audio/vocadito_" + clip + ".wav");
            Path notes = VOCADITO.resolve("Annotations/Notes/vocadito_" + clip + "_notesA1.csv");
            if (!Files.isRegularFile(audio) || !Files.isRegularFile(notes)) {
                System.err.println("skipping vocadito_" + clip + ": not present");
                continue;
            }
            analysed.add(throughStem(analyse(audio, annotatedNotes(notes)), stems));
        }
        return analysed;
    }

    /** The chart, the tuning and the reference stay the mix's; only the melody moves. */
    private static Analysed throughStem(Analysed one, Path stems) {
        if (stems == null) {
            return one;
        }
        Path stem = stems.resolve(one.name() + ".wav");
        if (!Files.isRegularFile(stem)) {
            System.err.println("no stem for " + one.name() + "; reading the mix");
            return one;
        }
        AudioBuffer audio = AudioDecoder.decode(stem);
        return new Analysed(one.name(), PitchTracker.track(audio),
                OnsetEnvelope.fromAudio(audio), one.tuning(), one.chords(), one.reference());
    }

    /** The front end, once: everything a sweep holds fixed. */
    private static Analysed analyse(Path file, List<Ref> reference) {
        AudioBuffer audio = AudioDecoder.decode(file);
        Score score = new AudioTranscriber(message -> { })
                .transcribe(audio, AudioTranscriber.Options.defaults());
        double tuning = Chroma.estimateTuning(NnlsChroma.transform(audio));
        String name = file.getFileName().toString().replaceAll("\\.(mp3|wav)$", "");
        System.err.println("analysed " + name);
        return new Analysed(name, PitchTracker.track(audio), OnsetEnvelope.fromAudio(audio),
                tuning, score.chords(), reference);
    }

    // -------------------------------------------------------------------- sweep

    private static final double[] WEIGHTS = {0, 0.10, 0.16, 0.20, 0.24, 0.28, 0.32, 0.40, 0.49};

    private static final double[] FLOORS = {0, 0.3, 0.4, 0.45, 0.5, 0.6};

    private static void sweep(List<Analysed> corpus) {
        System.out.printf("%-6s %-6s %8s %8s %8s %8s %5s %5s%n",
                "weight", "floor", "F1@50", "F1@100", "pitch", "moved", "fixed", "broke");
        for (double weight : WEIGHTS) {
            for (double floor : FLOORS) {
                double strict = 0;
                double loose = 0;
                double pitch = 0;
                int moved = 0;
                int total = 0;
                int fixed = 0;
                int broke = 0;
                for (Analysed one : corpus) {
                    List<Note> plain = notes(one, HarmonicPrior.NONE);
                    List<Note> primed = notes(one,
                            HarmonicPrior.of(one.chords(), weight, floor));
                    strict += noteF1(primed, one.reference(), TOLERANCES[0]);
                    loose += noteF1(primed, one.reference(), TOLERANCES[1]);
                    pitch += pitchAccuracy(primed, one.reference());
                    moved += differing(plain, primed);
                    total += primed.size();
                    int[] verdict = verdicts(plain, primed, one.reference());
                    fixed += verdict[0];
                    broke += verdict[1];
                }
                int n = corpus.size();
                System.out.printf(Locale.ROOT,
                        "%-6.2f %-6.2f %7.1f%% %7.1f%% %7.1f%% %5d/%d %5d %5d%n",
                        weight, floor, 100 * strict / n, 100 * loose / n, 100 * pitch / n,
                        moved, total, fixed, broke);
                if (weight == 0) {
                    break;      // the prior is off, so the floor cannot matter
                }
            }
        }
        System.out.println();
        System.out.println("per recording, at the shipped strength:");
        for (Analysed one : corpus) {
            List<Note> plain = notes(one, HarmonicPrior.NONE);
            List<Note> primed = notes(one, HarmonicPrior.of(one.chords()));
            System.out.printf(Locale.ROOT,
                    "  %-24s F1@50 %5.1f%% -> %5.1f%%   pitch %5.1f%% -> %5.1f%%   moved %d/%d%n",
                    one.name(),
                    100 * noteF1(plain, one.reference(), TOLERANCES[0]),
                    100 * noteF1(primed, one.reference(), TOLERANCES[0]),
                    100 * pitchAccuracy(plain, one.reference()),
                    100 * pitchAccuracy(primed, one.reference()),
                    differing(plain, primed), primed.size());
        }
    }

    private static List<Note> notes(Analysed one, HarmonicPrior prior) {
        NoteTrack track = MelodyEstimator.estimate(
                one.pitches(), one.envelope(), one.tuning(), prior);
        return track.notes().stream()
                .sorted(Comparator.comparingDouble(Note::onsetSeconds)).toList();
    }

    /**
     * How the notes the prior moved came out against the reference sounding at
     * their midpoint: {@code {fixed, broke}}. A move onto or off a pitch the
     * reference does not state at all counts as neither.
     */
    private static int[] verdicts(List<Note> plain, List<Note> primed, List<Ref> reference) {
        int fixed = 0;
        int broke = 0;
        for (Note note : primed) {
            double middle = note.onsetSeconds() + note.durationSeconds() / 2;
            Note before = plain.stream()
                    .filter(other -> Math.abs(other.onsetSeconds() - note.onsetSeconds()) < 1e-9)
                    .findFirst().orElse(null);
            if (before == null || before.midiPitch() == note.midiPitch()) {
                continue;
            }
            Integer want = soundingReference(reference, middle);
            if (want == null) {
                continue;
            }
            if (want == note.midiPitch()) {
                fixed++;
            } else if (want == before.midiPitch()) {
                broke++;
            }
        }
        return new int[] {fixed, broke};
    }

    /** How many notes the prior changed, matched by onset. */
    private static int differing(List<Note> plain, List<Note> primed) {
        int changed = 0;
        for (Note note : primed) {
            boolean same = plain.stream().anyMatch(
                    other -> Math.abs(other.onsetSeconds() - note.onsetSeconds()) < 1e-9
                            && other.midiPitch() == note.midiPitch());
            if (!same) {
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ profile

    private static void profile(Analysed one, HarmonicPrior prior) {
        List<Note> plain = notes(one, HarmonicPrior.NONE);
        List<Note> primed = notes(one, prior);
        double grid = honoursTuning(one) ? one.tuning() : 0;
        System.out.printf(Locale.ROOT, "tuning %+.4f semitones, rounded on %s%n",
                one.tuning(), grid == 0 ? "A440" : "the recording's own grid");
        System.out.printf("%-9s %-9s %-8s %-7s %-7s %-22s %s%n",
                "onset", "duration", "median", "plain", "primed", "widest span over it",
                "moved");
        for (Note note : primed) {
            Note before = plain.stream()
                    .filter(other -> Math.abs(other.onsetSeconds() - note.onsetSeconds()) < 1e-9)
                    .findFirst().orElse(null);
            Chord chord = widestOver(one.chords(), note);
            // Every span overlapping the note, not the one the prior settled
            // on: which that is depends on the floor, and a column that named
            // it would read as a chord the prior had listened to.
            System.out.printf(Locale.ROOT, "%-9.3f %-9.3f %-8s %-7s %-7s %-22s %s%n",
                    note.onsetSeconds(), note.durationSeconds(),
                    median(one, note, grid).map(m -> String.format(Locale.ROOT, "%.3f", m))
                            .orElse("-"),
                    before == null ? "-" : name(before.midiPitch()), name(note.midiPitch()),
                    chord == null ? "-"
                            : String.format(Locale.ROOT, "%s (%.2f)", chord.symbol(),
                                    chord.confidence().value()),
                    before != null && before.midiPitch() != note.midiPitch() ? "MOVED" : "");
        }
    }

    /**
     * The note's own median on the grid it was rounded on, which is the whole
     * opportunity a rounding prior has: how far this sits from a whole number
     * is how far the note was from being called something else.
     *
     * <p>Empty where the frames in the note's extent do not reproduce the pitch
     * the stage gave it — a re-articulated note is several notes over one span
     * (#495), and reporting one of their medians as the note's would be wrong.
     */
    private static java.util.Optional<Double> median(Analysed one, Note note, double grid) {
        PitchTrack pitches = one.pitches();
        List<Double> voiced = new ArrayList<>();
        double end = note.onsetSeconds() + note.durationSeconds();
        for (int frame = 0; frame < pitches.frameCount(); frame++) {
            double when = pitches.timeOf(frame);
            if (when >= note.onsetSeconds() && when < end && pitches.voiced()[frame]) {
                voiced.add(pitches.midiPitchAt(frame));
            }
        }
        if (voiced.isEmpty()) {
            return java.util.Optional.empty();
        }
        voiced.sort(null);
        int middle = voiced.size() / 2;
        double median = (voiced.size() % 2 == 1 ? voiced.get(middle)
                : (voiced.get(middle - 1) + voiced.get(middle)) / 2) - grid;
        return Math.round(median) == note.midiPitch()
                || Math.abs(median - note.midiPitch()) < 1
                ? java.util.Optional.of(median) : java.util.Optional.empty();
    }

    /**
     * Whether {@link MelodyEstimator} rounded this track on the recording's
     * tuning, by the rule that class applies: the offset has to say something,
     * and the track's own pitches have to sit on the grid it names.
     */
    private static boolean honoursTuning(Analysed one) {
        if (Chroma.readsAsConcertPitch(one.tuning())) {
            return false;
        }
        PitchTrack pitches = one.pitches();
        double agreement = 0;
        double weight = 0;
        for (int frame = 0; frame < pitches.frameCount(); frame++) {
            if (!pitches.voiced()[frame]) {
                continue;
            }
            double pitch = pitches.midiPitchAt(frame);
            double distance = pitch - Math.round(pitch) - one.tuning();
            agreement += pitches.voicedness()[frame] * Math.cos(2 * Math.PI * distance);
            weight += pitches.voicedness()[frame];
        }
        return weight > 0 && agreement / weight >= TUNING_CORROBORATION_FLOOR;
    }

    /** Mirrors MelodyEstimator's own floor; the bench cannot see the constant. */
    private static final double TUNING_CORROBORATION_FLOOR = 0.2;

    private static Chord widestOver(ChordProgression chords, Note note) {
        Chord best = null;
        double bestOverlap = 0;
        double end = note.onsetSeconds() + note.durationSeconds();
        for (Chord chord : chords.chords()) {
            double overlap = Math.min(end, chord.endSeconds())
                    - Math.max(note.onsetSeconds(), chord.startSeconds());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = chord;
            }
        }
        return best;
    }

    private static final String[] NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private static String name(int midiPitch) {
        return NAMES[Math.floorMod(midiPitch, 12)] + (midiPitch / 12 - 1);
    }

    // ------------------------------------------------------------------ scoring

    /** One-to-one note F1 at an onset tolerance, matching score-melody.py's rule. */
    private static double noteF1(List<Note> estimate, List<Ref> reference, double tolerance) {
        boolean[] used = new boolean[estimate.size()];
        int hits = 0;
        for (Ref want : reference) {
            int best = -1;
            for (int i = 0; i < estimate.size(); i++) {
                Note candidate = estimate.get(i);
                if (used[i] || candidate.midiPitch() != want.pitch()) {
                    continue;
                }
                double away = Math.abs(candidate.onsetSeconds() - want.onset());
                if (away <= tolerance
                        && (best < 0
                            || away < Math.abs(estimate.get(best).onsetSeconds() - want.onset()))) {
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        double precision = (double) hits / estimate.size();
        double recall = (double) hits / reference.size();
        return 2 * precision * recall / (precision + recall);
    }

    /** Raw pitch accuracy over the reference's sounding time. */
    private static double pitchAccuracy(List<Note> estimate, List<Ref> reference) {
        if (reference.isEmpty()) {
            return 0;
        }
        double end = reference.get(reference.size() - 1).end();
        int voiced = 0;
        int right = 0;
        for (int frame = 0; frame < (int) (end / FRAME_SECONDS); frame++) {
            double when = frame * FRAME_SECONDS;
            Integer want = soundingReference(reference, when);
            if (want == null) {
                continue;
            }
            voiced++;
            Integer got = soundingEstimate(estimate, when);
            if (got != null && got.intValue() == want.intValue()) {
                right++;
            }
        }
        return voiced == 0 ? 0 : (double) right / voiced;
    }

    private static Integer soundingReference(List<Ref> notes, double when) {
        for (Ref note : notes) {
            if (note.onset() <= when && when < note.end()) {
                return note.pitch();
            }
            if (note.onset() > when) {
                break;
            }
        }
        return null;
    }

    private static Integer soundingEstimate(List<Note> notes, double when) {
        for (Note note : notes) {
            if (note.onsetSeconds() <= when
                    && when < note.onsetSeconds() + note.durationSeconds()) {
                return note.midiPitch();
            }
            if (note.onsetSeconds() > when) {
                break;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- reference

    /** The package's own MIDI melody track, in seconds. */
    private static List<Ref> melodyNotes(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Sequence sequence = MidiSystem.getSequence(new File(file.toString()));
        double micros = 500_000;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    micros = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                }
            }
        }
        double secondsPerTick = micros / 1e6 / sequence.getResolution();
        List<Ref> notes = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            String name = "";
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                    name = new String(meta.getData()).trim();
                }
            }
            if (!MELODY_TRACK.equalsIgnoreCase(name)) {
                continue;
            }
            long[] open = new long[128];
            boolean[] sounding = new boolean[128];
            for (int i = 0; i < track.size(); i++) {
                var event = track.get(i);
                if (!(event.getMessage() instanceof ShortMessage message)) {
                    continue;
                }
                int pitch = message.getData1();
                boolean on = message.getCommand() == ShortMessage.NOTE_ON
                        && message.getData2() > 0;
                boolean off = message.getCommand() == ShortMessage.NOTE_OFF
                        || (message.getCommand() == ShortMessage.NOTE_ON
                            && message.getData2() == 0);
                if (on) {
                    open[pitch] = event.getTick();
                    sounding[pitch] = true;
                } else if (off && sounding[pitch]) {
                    notes.add(new Ref(open[pitch] * secondsPerTick,
                            event.getTick() * secondsPerTick, pitch));
                    sounding[pitch] = false;
                }
            }
        }
        notes.sort(Comparator.comparingDouble(Ref::onset));
        return notes;
    }

    /** One vocadito annotator's notes: onset seconds, hertz, duration seconds. */
    private static List<Ref> annotatedNotes(Path file) throws Exception {
        List<Ref> notes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",");
            double onset = Double.parseDouble(fields[0]);
            double hertz = Double.parseDouble(fields[1]);
            double duration = Double.parseDouble(fields[2]);
            notes.add(new Ref(onset, onset + duration,
                    (int) Math.round(69 + 12 * Math.log(hertz / 440) / Math.log(2))));
        }
        notes.sort(Comparator.comparingDouble(Ref::onset));
        return notes;
    }
}
