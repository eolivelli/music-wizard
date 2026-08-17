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
 * The melody segmenter's own bench: track each recording's pitch once, then ask
 * {@link MelodyEstimator} for notes at as many steadiness settings as wanted.
 *
 * <p>{@code tools/score-melody.py} is the committed instrument and answers the
 * question that decides anything, through the shipped CLI. It also re-analyses
 * every recording for every question, which makes a sweep cost hours. This
 * caches what the segmenter reads — the pitch track, the onset envelope and the
 * recording's tuning — so a whole ladder costs one pass.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/GlideSweep.java              # the ladder
 *   java -cp mw-cli/target/mw.jar tools/GlideSweep.java 0.7          # one setting
 *   java -cp mw-cli/target/mw.jar tools/GlideSweep.java rows 0.7     # its rows
 * </pre>
 *
 * <p><b>Its rows reproduce {@code score-melody.py}'s</b> for both corpora at the
 * shipped constant — that agreement is the only reason to trust the ladder, and
 * {@code rows} is what re-checks it against {@code tools/baselines/}. A figure
 * landing exactly half way is the one thing that will not match, and never will:
 * Java rounds a tie up where Python rounds it to even. It scores what the pinned
 * harness rows score — the audio as given, with no separation — so the two
 * {@code --separated} baselines are outside its reach.
 *
 * <p><b>The cache cannot go stale silently.</b> Its key is a digest of the
 * recording's bytes and of every class on the classpath except the segmenter's
 * own, so a change anywhere upstream of segmentation — the tracker, the
 * decoder, the envelope, the tuning estimate — makes a new key and re-tracks,
 * while editing {@link MelodyEstimator} between two runs of the sweep does not
 * (that is the whole point). Keying a bench cache by file name has poisoned a
 * sweep here before.
 */
public final class GlideSweep {

    private static final Path REPO = Path.of(".");
    private static final Path CACHE = Path.of("target", "glide-sweep-cache");
    private static final Path VOCADITO = Path.of("uncommitted", "vocadito");
    private static final Path SYNTHETIC = Path.of("synthetic_samples");

    /** The melody track of a package's MIDI, named as mw-teacher writes it. */
    private static final String MELODY_TRACK = "Melody";

    /** Onset tolerances the note columns are matched at, as score-melody.py has them. */
    private static final double[] TOLERANCES = {0.05, 0.10};

    /** Sampling step of the framewise columns, in seconds. */
    private static final double FRAME_SECONDS = 0.01;

    /** What the ladder walks when no setting is named. */
    private static final double[] LADDER =
            {0.4, 0.5, 0.6, 0.65, 0.7, 0.75, 0.8, 0.9, 1.0, 1.2, 1.6, 2.0};

    private GlideSweep() {
    }

    /** One note, of an estimate or of a reference, in seconds and semitones. */
    private record Span(double onset, double end, int pitch) {}

    /** What the segmenter reads, cached: everything upstream of it. */
    private record Front(PitchTrack track, OnsetEnvelope envelope, double tuning) {}

    public static void main(String[] args) throws Exception {
        boolean rows = args.length > 0 && args[0].equals("rows");
        List<Double> settings = new ArrayList<>();
        for (int i = rows ? 1 : 0; i < args.length; i++) {
            settings.add(Double.parseDouble(args[i]));
        }
        if (settings.isEmpty()) {
            for (double value : LADDER) {
                settings.add(value);
            }
        }
        Map<String, Path> vocadito = vocaditoClips();
        Map<String, Path> synthetic = syntheticPackages();
        if (vocadito.isEmpty()) {
            System.out.println("vocadito is not on this machine; see uncommitted/list.txt"
                    + " to fetch it. Only the synthetic packages are scored.");
        }
        for (double steady : settings) {
            score("vocadito ", vocadito, steady, rows);
            score("synthetic", synthetic, steady, rows);
        }
    }

    /** Scores one corpus at one setting, printing a mean row and optionally its rows. */
    private static void score(String corpus, Map<String, Path> benchmarks, double steady,
                              boolean rows) throws Exception {
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
            List<Span> estimate = estimate(front(benchmark.getValue()), steady);
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
        System.out.printf(Locale.ROOT, "%s steady=%.2f  benchmarks=%d notes=%d"
                        + "  F1@50ms %.2f%%  F1@100ms %.2f%%  pitch %.2f%%  voiced %.2f%%%n",
                corpus, steady, scored, notes, 100 * first / scored, 100 * second / scored,
                100 * pitch / scored, 100 * voiced / scored);
    }

    // ------------------------------------------------------------- the corpora

    /** Every vocadito clip on this machine, by the name its baseline row carries. */
    private static Map<String, Path> vocaditoClips() throws IOException {
        Map<String, Path> found = new TreeMap<>(GlideSweep::byTrailingNumber);
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

    private static List<Span> estimate(Front front, double steady) {
        NoteTrack track = MelodyEstimator.estimate(
                front.track(), front.envelope(), front.tuning(), steady);
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
     * <p>The tuning comes from the recording being tracked, which is what the
     * pipeline hands the stage when nothing has been separated — the
     * configuration score-melody.py pins its committed rows to.
     */
    private static Front front(Path audio) throws Exception {
        Path cached = CACHE.resolve(audio.getFileName() + "-" + key(audio) + ".bin");
        if (Files.isRegularFile(cached)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(cached)))) {
                return read(in);
            }
        }
        AudioBuffer buffer = AudioDecoder.decode(audio);
        Front front = new Front(PitchTracker.track(buffer), OnsetEnvelope.fromAudio(buffer),
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
