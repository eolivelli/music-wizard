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
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.ml.MlProviders;
import dev.olivelli.musicwizard.core.ml.SeparationProvider;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.MelodyEstimator;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import dev.olivelli.musicwizard.dsp.PitchTrack;
import dev.olivelli.musicwizard.dsp.PitchTracker;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * The octave fold's own bench: track each recording's pitch once, then ask
 * {@link MelodyEstimator} for notes at as many bands as wanted.
 *
 * <p>{@code tools/score-melody.py} is the committed instrument and answers the
 * question that decides anything, through the shipped CLI. It also re-analyses
 * every recording for every question, which makes a sweep cost hours. This
 * caches what the segmenter reads — the pitch track, the onset envelope and the
 * recording's tuning — so a whole grid costs one pass.
 *
 * <p>The four settings are the band the fold judges an octave against — a floor
 * on its half-width and the share of the melody's own sounding time it reaches
 * out to — how many octaves out a note may be and still be folded, and how far
 * apart two notes may be and still be one gesture the fold decides once. A band
 * under an octave wide folds nothing, since no pitch then has a representative
 * inside it; a quantile of one is the fold off, and so is a bound of zero; a
 * gesture of zero decides every note alone.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java                    # the grid
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java 14 0.9 2 2       # one setting
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java rows 14 0.9 2 2    # its rows
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java octaves 14 0.9 2 2 # how it is wrong
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java splits 14 0.9 2 2  # what it moved
 *   java -cp mw-cli/target/mw.jar tools/OctaveSweep.java --separated rows 14 0.9 2 2
 * </pre>
 *
 * <p><b>Its rows reproduce {@code score-melody.py}'s</b> for both corpora at the
 * shipped constants — that agreement is the only reason to trust the grid, and
 * {@code rows} is what re-checks it against {@code tools/baselines/}. A figure
 * landing exactly half way is the one thing that will not match, and never will:
 * Java rounds a tie up where Python rounds it to even.
 *
 * <p>{@code --separated} reads each recording through the separated vocal, which
 * is what {@code analyze --melody} does (#559) and what the two
 * {@code --separated} baselines score. It separates with whatever provider this
 * machine has and caches the stem's front end like any other, so the first run
 * costs a separation per recording and later ones cost nothing. The tuning still
 * comes from the mix, and the envelope from the stem, exactly as the pipeline
 * has it.
 *
 * <p>{@code octaves} and {@code splits} answer the diagnosis rather than the
 * setting. The first counts, for every reference note the estimate reads at the
 * wrong semitone, how often the two agree on the pitch class, which is what an
 * octave error looks like and what a random misreading does not. The second
 * counts what the fold itself did: the notes it moved, whether truth called them
 * right before and after, and the gestures it cut in half (#614).
 *
 * <p><b>The cache cannot go stale silently.</b> Its key is a digest of the
 * recording's bytes and of every class on the classpath except the segmenter's
 * own, so a change anywhere upstream of segmentation — the tracker, the
 * decoder, the envelope, the tuning estimate — makes a new key and re-tracks,
 * while editing {@link MelodyEstimator} between two runs of the sweep does not
 * (that is the whole point). Keying a bench cache by file name has poisoned a
 * sweep here before.
 */
public final class OctaveSweep {

    private static final Path REPO = Path.of(".");
    private static final Path CACHE = Path.of("target", "octave-sweep-cache");
    private static final Path VOCADITO = Path.of("uncommitted", "vocadito");
    private static final Path SYNTHETIC = Path.of("synthetic_samples");

    /** The melody track of a package's MIDI, named as mw-teacher writes it. */
    private static final String MELODY_TRACK = "Melody";

    /** Onset tolerances the note columns are matched at, as score-melody.py has them. */
    private static final double[] TOLERANCES = {0.05, 0.10};

    /** Sampling step of the framewise columns, in seconds. */
    private static final double FRAME_SECONDS = 0.01;

    /** The steadiness the shipped stage segments at; this bench does not move it. */
    private static final double STEADY = 0.7;

    /** Half-width floors the grid walks. */
    private static final double[] FLOORS = {0, 6, 9, 10, 11, 12, 13, 14, 15, 18, 24};

    /** Spread quantiles the grid walks against each floor; at one the fold is off. */
    private static final double[] QUANTILES = {0, 0.5, 0.75, 0.9, 0.95, 0.99, 1};

    /** Print every benchmark's row rather than the corpus mean. */
    private static final String ROWS = "rows";

    /** Count how the wrong frames are wrong rather than scoring the estimate. */
    private static final String OCTAVES = "octaves";

    /** Read every recording through its separated vocal rather than as given. */
    private static final String SEPARATED = "--separated";

    /** Count the gestures the fold cut in half rather than scoring the estimate. */
    private static final String SPLITS = "splits";

    /** How many octaves out the grid lets a note be and still be folded. */
    private static final double[] BOUNDS = {1, 2, 3, 4};

    /** Gesture widths the grid walks; at zero every note is decided alone. */
    private static final double[] GESTURES = {0, 1, 2, 3, 4, 5, 6, 7, 9, 12};

    /**
     * How far apart two notes may be and still be one gesture, for the {@code
     * splits} count only -- the stage has no such notion, and this is the
     * question being asked of it rather than a setting it is asked at.
     */
    private static final int ONE_GESTURE_SEMITONES = 2;

    private OctaveSweep() {
    }

    /** One note, of an estimate or of a reference, in seconds and semitones. */
    private record Span(double onset, double end, int pitch) {}

    /** What the segmenter reads, cached: everything upstream of it. */
    private record Front(PitchTrack track, OnsetEnvelope envelope, double tuning) {}

    public static void main(String[] commandLine) throws Exception {
        List<String> rest = new ArrayList<>();
        boolean separated = false;
        for (String argument : commandLine) {
            if (argument.equals(SEPARATED)) {
                separated = true;
            } else {
                rest.add(argument);
            }
        }
        String[] args = rest.toArray(new String[0]);
        String mode = args.length > 0 && !isNumber(args[0]) ? args[0] : "";
        // Checked against the list, not merely for being a word: an unknown
        // one would otherwise select the score question in silence, so a
        // mistyped "rows" answers a question nobody asked in the format of an
        // answer to the one they did.
        if (!mode.isEmpty() && !mode.equals(ROWS) && !mode.equals(OCTAVES)
                && !mode.equals(SPLITS)) {
            System.err.println("unknown mode: " + mode + " (expected " + ROWS + ", " + OCTAVES
                    + " or " + SPLITS + ")");
            System.exit(2);
        }
        List<double[]> bands = new ArrayList<>();
        int first = mode.isEmpty() ? 0 : 1;
        // Rejected rather than rounded down to what does group: an argument
        // list one short is the previous release's syntax, and silently
        // answering the whole grid to it prints a row that reads exactly like
        // the answer to the question asked.
        if ((args.length - first) % 4 != 0) {
            System.err.println("each setting is four numbers: floor, quantile, octaves, gesture");
            System.exit(2);
        }
        for (int i = first; i + 3 < args.length; i += 4) {
            bands.add(new double[] {Double.parseDouble(args[i]), Double.parseDouble(args[i + 1]),
                    Double.parseDouble(args[i + 2]), Double.parseDouble(args[i + 3])});
        }
        if (bands.isEmpty()) {
            for (double floor : FLOORS) {
                for (double quantile : QUANTILES) {
                    for (double bound : BOUNDS) {
                        for (double gesture : GESTURES) {
                            bands.add(new double[] {floor, quantile, bound, gesture});
                        }
                    }
                }
            }
        }
        Map<String, Path> vocadito = vocaditoClips();
        Map<String, Path> synthetic = syntheticPackages();
        if (vocadito.isEmpty()) {
            System.out.println("vocadito is not on this machine; see uncommitted/list.txt"
                    + " to fetch it. Only the synthetic packages are scored.");
        }
        for (double[] band : bands) {
            if (mode.equals(SPLITS)) {
                splits("vocadito ", vocadito, band, separated);
                splits("synthetic", synthetic, band, separated);
            } else if (mode.equals(OCTAVES)) {
                octaves("vocadito ", vocadito, band, separated);
                octaves("synthetic", synthetic, band, separated);
            } else {
                score("vocadito ", vocadito, band, mode.equals(ROWS), separated);
                score("synthetic", synthetic, band, mode.equals(ROWS), separated);
            }
        }
    }

    private static boolean isNumber(String argument) {
        try {
            Double.parseDouble(argument);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * How the estimate's wrong semitones are wrong: the share of the reference's
     * sounding time read at another octave of the right pitch class, against the
     * share read at a pitch class that is simply not the reference's.
     *
     * <p>This is the diagnosis #596 rests on, and it is measured rather than
     * assumed: a factor error keeps the pitch class and a random misreading does
     * not, so the two columns say which population the wrong frames belong to.
     */
    private static void octaves(String corpus, Map<String, Path> benchmarks, double[] band,
                                boolean separated) throws Exception {
        long octave = 0;
        long other = 0;
        long right = 0;
        for (Map.Entry<String, Path> benchmark : benchmarks.entrySet()) {
            List<Span> reference = truth(benchmark.getValue());
            if (reference.isEmpty()) {
                continue;
            }
            List<Span> estimate = estimate(front(benchmark.getValue(), separated), band);
            int frames = (int) (reference.get(reference.size() - 1).end() / FRAME_SECONDS);
            for (int frame = 0; frame < frames; frame++) {
                int want = soundingAt(reference, frame * FRAME_SECONDS);
                int got = soundingAt(estimate, frame * FRAME_SECONDS);
                if (want < 0 || got < 0) {
                    continue;
                }
                if (got == want) {
                    right++;
                } else if (Math.floorMod(got - want, 12) == 0) {
                    octave++;
                } else {
                    other++;
                }
            }
        }
        long sounding = right + octave + other;
        if (sounding == 0) {
            return;
        }
        System.out.printf(Locale.ROOT, "%s floor=%.0f quantile=%.2f octaves=%.0f gesture=%.0f  of the frames both call"
                        + " sounding: right %.1f%%  wrong by whole octaves %.1f%%"
                        + "  wrong otherwise %.1f%%%n",
                corpus, band[0], band[1], band[2], band[3], 100.0 * right / sounding,
                100.0 * octave / sounding, 100.0 * other / sounding);
    }

    /**
     * What the fold moved and what it cut: for each benchmark, how many notes
     * the fold moved, how many of them were right before the move and are right
     * after it, and how many pairs of notes next to each other in time and
     * within {@link #ONE_GESTURE_SEMITONES} of each other it left an octave or
     * more apart.
     *
     * <p>That last count is #614 measured rather than argued: two notes a
     * semitone apart are one gesture whatever else is true of them, and a rule
     * that puts them in different octaves has cut a gesture in half. Right and
     * wrong are read at each note's midpoint against the same truth the columns
     * use, so a benchmark with none says nothing about the moves and prints
     * zeroes.
     *
     * <p>The fold does not move onsets, so the estimate at a setting and the
     * estimate with the fold off run note for note.
     */
    private static void splits(String corpus, Map<String, Path> benchmarks, double[] band,
                               boolean separated) throws Exception {
        for (Map.Entry<String, Path> benchmark : benchmarks.entrySet()) {
            Front front = front(benchmark.getValue(), separated);
            List<Span> unfolded = estimate(front, new double[] {band[0], band[1], 0, band[3]});
            List<Span> folded = estimate(front, band);
            List<Span> reference = truth(benchmark.getValue());
            int moved = 0;
            int rightBefore = 0;
            int rightAfter = 0;
            int cut = 0;
            for (int i = 0; i < unfolded.size(); i++) {
                if (unfolded.get(i).pitch() != folded.get(i).pitch()) {
                    moved++;
                    int want = reference.isEmpty() ? -1 : soundingAt(reference,
                            (unfolded.get(i).onset() + unfolded.get(i).end()) / 2);
                    rightBefore += want == unfolded.get(i).pitch() ? 1 : 0;
                    rightAfter += want == folded.get(i).pitch() ? 1 : 0;
                }
                if (i + 1 < unfolded.size()
                        && Math.abs(unfolded.get(i).pitch() - unfolded.get(i + 1).pitch())
                                <= ONE_GESTURE_SEMITONES
                        && Math.abs(folded.get(i).pitch() - folded.get(i + 1).pitch()) >= 12) {
                    cut++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "%s floor=%.0f quantile=%.2f octaves=%.0f gesture=%.0f  %s: notes=%d moved=%d"
                            + "  right before %d after %d  gestures cut %d%n",
                    corpus, band[0], band[1], band[2], band[3], benchmark.getKey(),
                    unfolded.size(), moved, rightBefore, rightAfter, cut);
        }
    }

    /** Scores one corpus at one setting, printing a mean row and optionally its rows. */
    private static void score(String corpus, Map<String, Path> benchmarks, double[] band,
                              boolean rows, boolean separated) throws Exception {
        double first = 0;
        double second = 0;
        double pitch = 0;
        double voiced = 0;
        int notes = 0;
        int scored = 0;
        for (Map.Entry<String, Path> benchmark : benchmarks.entrySet()) {
            List<Span> reference = truth(benchmark.getValue());
            if (reference.isEmpty()) {
                continue;
            }
            List<Span> estimate = estimate(front(benchmark.getValue(), separated), band);
            double at50 = noteF1(estimate, reference, TOLERANCES[0]);
            double at100 = noteF1(estimate, reference, TOLERANCES[1]);
            double[] framewise = framewise(estimate, reference);
            if (rows) {
                System.out.printf(Locale.ROOT, "  %s: notes=%d/%d  F1@50ms %.1f%%"
                                + "  F1@100ms %.1f%%  pitch %.1f%%  voiced %.1f%%%n",
                        benchmark.getKey(), estimate.size(), reference.size(),
                        100 * at50, 100 * at100, 100 * framewise[0], 100 * framewise[1]);
            }
            first += at50;
            second += at100;
            pitch += framewise[0];
            voiced += framewise[1];
            notes += estimate.size();
            scored++;
        }
        if (scored == 0) {
            return;
        }
        System.out.printf(Locale.ROOT, "%s floor=%.0f quantile=%.2f octaves=%.0f gesture=%.0f  benchmarks=%d notes=%d"
                        + "  F1@50ms %.2f%%  F1@100ms %.2f%%  pitch %.2f%%  voiced %.2f%%%n",
                corpus, band[0], band[1], band[2], band[3], scored, notes,
                100 * first / scored, 100 * second / scored,
                100 * pitch / scored, 100 * voiced / scored);
    }

    // ------------------------------------------------------------- the corpora

    /** Every vocadito clip on this machine, by the name its baseline row carries. */
    private static Map<String, Path> vocaditoClips() throws IOException {
        Map<String, Path> found = new TreeMap<>(OctaveSweep::byTrailingNumber);
        Path audio = REPO.resolve(VOCADITO).resolve("Audio");
        if (!Files.isDirectory(audio)) {
            return found;
        }
        try (var listing = Files.list(audio)) {
            listing.filter(path -> path.getFileName().toString().endsWith(".wav"))
                    .forEach(path -> found.put(
                            path.getFileName().toString().replace(".wav", ""), path));
        }
        return found;
    }

    /** Every synthetic package, whether or not it carries a melody track. */
    private static Map<String, Path> syntheticPackages() throws IOException {
        Map<String, Path> found = new TreeMap<>();
        try (var listing = Files.list(REPO.resolve(SYNTHETIC))) {
            listing.filter(path -> path.getFileName().toString().endsWith(".mp3"))
                    .forEach(path -> found.put(
                            path.getFileName().toString().replace(".mp3", ""), path));
        }
        return found;
    }

    /** score-melody.py's own ordering, so its rows and these can be read side by side. */
    private static int byTrailingNumber(String left, String right) {
        return Integer.compare(trailingNumber(left), trailingNumber(right));
    }

    private static int trailingNumber(String name) {
        return Integer.parseInt(name.substring(name.lastIndexOf('_') + 1));
    }

    /**
     * A benchmark's annotated notes: vocadito's first annotator, or a package's
     * own MIDI melody track. Empty where a package has no melody to score.
     */
    private static List<Span> truth(Path audio) throws Exception {
        String name = audio.getFileName().toString();
        if (name.endsWith(".wav")) {
            return annotated(audio.getParent().getParent()
                    .resolve("Annotations").resolve("Notes")
                    .resolve(name.replace(".wav", "_notesA1.csv")));
        }
        return midiMelody(audio.resolveSibling(name.replace(".mp3", ".mid")));
    }

    /** One annotator's notes; the file is onset seconds, hertz, duration seconds. */
    private static List<Span> annotated(Path csv) throws IOException {
        List<Span> notes = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",");
            double onset = Double.parseDouble(fields[0]);
            double hertz = Double.parseDouble(fields[1]);
            double duration = Double.parseDouble(fields[2]);
            notes.add(new Span(onset, onset + duration,
                    (int) Math.round(69 + 12 * (Math.log(hertz / 440.0) / Math.log(2)))));
        }
        notes.sort(Comparator.comparingDouble(Span::onset));
        return notes;
    }

    /**
     * A package's melody track, in seconds. One tempo is assumed, because
     * mw-teacher writes one — the same assumption score-melody.py makes, and it
     * asserts on a second there rather than averaging.
     */
    private static List<Span> midiMelody(Path midi) throws Exception {
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        double microsPerQuarter = 500_000;
        int tempos = 0;
        for (Track track : sequence.getTracks()) {
            for (int event = 0; event < track.size(); event++) {
                if (track.get(event).getMessage() instanceof MetaMessage meta
                        && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    microsPerQuarter = ((data[0] & 0xff) << 16)
                            | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                    tempos++;
                }
            }
        }
        if (tempos > 1) {
            throw new IllegalStateException(midi + ": more than one tempo;"
                    + " this bench assumes one, as score-melody.py does");
        }
        double secondsPerTick = microsPerQuarter / 1e6 / sequence.getResolution();
        for (Track track : sequence.getTracks()) {
            if (!MELODY_TRACK.equals(trackName(track))) {
                continue;
            }
            List<Span> notes = new ArrayList<>();
            Map<Integer, List<Long>> sounding = new TreeMap<>();
            for (int event = 0; event < track.size(); event++) {
                MidiEvent entry = track.get(event);
                if (!(entry.getMessage() instanceof ShortMessage message)) {
                    continue;
                }
                boolean strike = message.getCommand() == ShortMessage.NOTE_ON
                        && message.getData2() > 0;
                boolean release = message.getCommand() == ShortMessage.NOTE_OFF
                        || (message.getCommand() == ShortMessage.NOTE_ON
                                && message.getData2() == 0);
                if (strike) {
                    sounding.computeIfAbsent(message.getData1(), key -> new ArrayList<>())
                            .add(entry.getTick());
                } else if (release) {
                    List<Long> starts = sounding.get(message.getData1());
                    if (starts != null && !starts.isEmpty()) {
                        notes.add(new Span(starts.remove(0) * secondsPerTick,
                                entry.getTick() * secondsPerTick, message.getData1()));
                    }
                }
            }
            notes.sort(Comparator.comparingDouble(Span::onset));
            return notes;
        }
        return List.of();
    }

    private static String trackName(Track track) {
        for (int event = 0; event < track.size(); event++) {
            if (track.get(event).getMessage() instanceof MetaMessage meta
                    && meta.getType() == 0x03) {
                return new String(meta.getData(), StandardCharsets.ISO_8859_1);
            }
        }
        return "";
    }

    // ------------------------------------------------------------- the stage

    private static List<Span> estimate(Front front, double[] band) {
        NoteTrack track = MelodyEstimator.estimate(
                front.track(), front.envelope(), front.tuning(), STEADY, band[0], band[1],
                (int) band[2], band[3]);
        List<Span> notes = new ArrayList<>(track.size());
        for (Note note : track.notes()) {
            notes.add(new Span(note.onsetSeconds(),
                    note.onsetSeconds() + note.durationSeconds(), note.midiPitch()));
        }
        notes.sort(Comparator.comparingDouble(Span::onset));
        return notes;
    }

    /**
     * Everything the segmenter reads, computed once and cached.
     *
     * <p>Read as given, the tuning comes from the recording being tracked,
     * which is what the pipeline hands the stage when nothing has been
     * separated — the configuration score-melody.py pins its committed rows to.
     * Read through a stem, the pitch and the envelope come from the stem and
     * the tuning still from the mix, which is what {@code analyze --melody}
     * does: a lead sheet whose chords and whose melody were rounded on
     * different grids can name one sounding pitch two ways.
     */
    private static Front front(Path audio, boolean separated) throws Exception {
        Path cached = CACHE.resolve(audio.getFileName() + (separated ? "-separated-" : "-")
                + key(audio) + ".bin");
        if (Files.isRegularFile(cached)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(cached)))) {
                return read(in);
            }
        }
        AudioBuffer buffer = AudioDecoder.decode(audio);
        AudioBuffer tracked = separated ? vocalStem(audio, buffer.sampleRate()) : buffer;
        Front front = new Front(PitchTracker.track(tracked), OnsetEnvelope.fromAudio(tracked),
                Chroma.estimateTuning(NnlsChroma.transform(buffer)));
        Files.createDirectories(CACHE);
        Path partial = cached.resolveSibling(cached.getFileName() + ".partial");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(partial)))) {
            write(out, front);
        }
        Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
        return front;
    }

    /**
     * The vocal, separated by whatever provider this machine has and resampled
     * to the analysis rate — the two steps {@code VocalStem} and the melody
     * stage take between them, which is why the separator's own preferred rate
     * is decoded at rather than resampled up to.
     */
    private static AudioBuffer vocalStem(Path audio, int analysisRate) throws IOException {
        // The default provider rather than this machine's configured one, for
        // score-melody.py's reason for running against an empty config home: a
        // local ml.separationProvider must not decide what a baseline is
        // compared with.
        String id = MusicWizardConfig.DEFAULTS.ml().separationProvider();
        SeparationProvider provider = MlProviders.separation(id).orElseThrow(
                () -> new IllegalStateException(id + " cannot be had here;"
                        + " see docs/local-setup.md"));
        int preferred = provider.preferredSampleRate();
        AudioBuffer mix = preferred > 0
                ? AudioDecoder.decode(audio, preferred) : AudioDecoder.decode(audio);
        float[] voice = provider.separate(new float[][] {mix.samples()}, mix.sampleRate())
                .vocals()[0];
        return mix.sampleRate() == analysisRate
                ? new AudioBuffer(voice, analysisRate)
                : new AudioBuffer(Resampler.resample(voice, mix.sampleRate(), analysisRate),
                        analysisRate);
    }

    private static Front read(DataInputStream in) throws IOException {
        int frames = in.readInt();
        double[] frequencies = new double[frames];
        boolean[] voiced = new boolean[frames];
        double[] voicedness = new double[frames];
        for (int frame = 0; frame < frames; frame++) {
            frequencies[frame] = in.readDouble();
        }
        for (int frame = 0; frame < frames; frame++) {
            voiced[frame] = in.readBoolean();
        }
        for (int frame = 0; frame < frames; frame++) {
            voicedness[frame] = in.readDouble();
        }
        PitchTrack track = new PitchTrack(frequencies, voiced, voicedness,
                in.readInt(), in.readInt(), in.readInt());
        double[] strength = new double[in.readInt()];
        for (int frame = 0; frame < strength.length; frame++) {
            strength[frame] = in.readDouble();
        }
        return new Front(track, new OnsetEnvelope(strength, in.readDouble()), in.readDouble());
    }

    private static void write(DataOutputStream out, Front front) throws IOException {
        PitchTrack track = front.track();
        out.writeInt(track.frameCount());
        for (double frequency : track.frequenciesHz()) {
            out.writeDouble(frequency);
        }
        for (boolean voiced : track.voiced()) {
            out.writeBoolean(voiced);
        }
        for (double voicedness : track.voicedness()) {
            out.writeDouble(voicedness);
        }
        out.writeInt(track.sampleRate());
        out.writeInt(track.windowSize());
        out.writeInt(track.hopSize());
        out.writeInt(front.envelope().strength().length);
        for (double strength : front.envelope().strength()) {
            out.writeDouble(strength);
        }
        out.writeDouble(front.envelope().frameRate());
        out.writeDouble(front.tuning());
    }

    /**
     * What a cache entry is keyed by: the recording, and every class that could
     * change what is cached.
     */
    private static String key(Path audio) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(audio));
        digest.update(classpathDigest());
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /**
     * A digest of the classpath with {@link MelodyEstimator}'s own classes left
     * out — the one thing a sweep is expected to edit between runs.
     */
    private static byte[] classpathDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String[] elements = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String element : elements) {
            Path path = Path.of(element);
            if (Files.isRegularFile(path)) {
                try (ZipFile jar = new ZipFile(path.toFile())) {
                    Map<String, Long> entries = new TreeMap<>();
                    jar.stream().filter(entry -> !swept(entry.getName()))
                            .forEach(entry -> entries.put(entry.getName(), entry.getCrc()));
                    entries.forEach((name, crc) -> {
                        digest.update(name.getBytes(StandardCharsets.UTF_8));
                        digest.update(Long.toString(crc).getBytes(StandardCharsets.UTF_8));
                    });
                }
            } else if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
                    for (Path file : files) {
                        String name = path.relativize(file).toString();
                        if (swept(name.replace(File.separatorChar, '/'))) {
                            continue;
                        }
                        digest.update(name.getBytes(StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(file));
                    }
                }
            }
        }
        return digest.digest();
    }

    /** Whether a classpath entry is one this bench expects to be edited under it. */
    private static boolean swept(String name) {
        return name.startsWith("dev/olivelli/musicwizard/dsp/MelodyEstimator");
    }

    // -------------------------------------------------------------- the metrics

    /**
     * One-to-one note F1 at an onset tolerance, on the semitone; matched greedily
     * in reference order, exactly as score-melody.py matches.
     */
    private static double noteF1(List<Span> estimate, List<Span> reference, double tolerance) {
        boolean[] used = new boolean[estimate.size()];
        int hits = 0;
        for (Span want : reference) {
            int best = -1;
            for (int index = 0; index < estimate.size(); index++) {
                Span candidate = estimate.get(index);
                if (used[index] || candidate.pitch() != want.pitch()
                        || Math.abs(candidate.onset() - want.onset()) > tolerance) {
                    continue;
                }
                if (best < 0 || Math.abs(candidate.onset() - want.onset())
                        < Math.abs(estimate.get(best).onset() - want.onset())) {
                    best = index;
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

    /** Raw pitch accuracy and voicing recall over the reference's sounding time. */
    private static double[] framewise(List<Span> estimate, List<Span> reference) {
        int frames = (int) (reference.get(reference.size() - 1).end() / FRAME_SECONDS);
        int voiced = 0;
        int right = 0;
        int sounding = 0;
        for (int frame = 0; frame < frames; frame++) {
            double when = frame * FRAME_SECONDS;
            int want = soundingAt(reference, when);
            if (want < 0) {
                continue;
            }
            voiced++;
            int got = soundingAt(estimate, when);
            if (got >= 0) {
                sounding++;
                if (got == want) {
                    right++;
                }
            }
        }
        return voiced == 0 ? new double[] {0, 0}
                : new double[] {(double) right / voiced, (double) sounding / voiced};
    }

    /** The pitch sounding at a moment, or a negative number for none. */
    private static int soundingAt(List<Span> notes, double when) {
        for (Span note : notes) {
            if (note.onset() <= when && when < note.end()) {
                return note.pitch();
            }
            if (note.onset() > when) {
                break;
            }
        }
        return -1;
    }
}
