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
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.ChordEstimator;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.DownbeatEstimator;
import dev.olivelli.musicwizard.dsp.HarmonicRhythm;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The chord estimator's own bench: extract each benchmark's chroma once, then
 * ask questions of {@link ChordEstimator} as often as wanted.
 *
 * <p>{@code tools/score-samples.py} answers the only question that decides
 * anything — is the right chord in the right bar — and answers it through the
 * shipped CLI, which is why it is the committed baseline. It also re-runs the
 * whole front end for every question, minutes per benchmark, which makes it
 * useless for finding out <em>why</em> an answer is wrong. This caches the
 * beat-synchronous chroma of both registers and the downbeat phase, so a
 * question costs seconds.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/ChordSweep.java cache     # minutes, once
 *   java -cp mw-cli/target/mw.jar tools/ChordSweep.java score     # seconds
 *   java -cp mw-cli/target/mw.jar tools/ChordSweep.java profile   # seconds
 * </pre>
 *
 * <p><b>{@code score} reproduces {@code tools/score-samples.py} line for line</b>
 * — same bars, same rotation, same counts — and that agreement is the only
 * reason to trust anything else here. What it adds is the number of chord spans
 * and the quality reported on the bars whose root is right, which is where #208
 * was visible and the two accuracy columns alone were not.
 *
 * <p>{@code profile} is the measurement #208 was diagnosed from and the one
 * {@link ChordEstimator#estimate(Chroma, Chroma, List)} argues from: the mean
 * chroma above the root the estimator itself decoded, per register, and the
 * share of the root-third-fifth mass carried by the flat seventh. A binary
 * four-note template beats the major triad on the same root exactly when that
 * share clears 2/sqrt(3) - 1.
 *
 * <p>The two pop backing tracks are here as the negative control the seventh
 * benchmarks need, since a discriminator that fires on everything is not one:
 * their chords are plain triads, so any four-note label on them is a false one
 * and {@code profile} counts every label reported. {@code
 * pop-c-g-am-f-120.mp3} is now scored as well; {@code pop-am-f-c-g-144.mp3}
 * is not, because on its stated grid the estimator finds under half the roots
 * and a quality column read off bars that wrong says nothing. The b7 share is
 * read off the decoded spans and needs no grid either way.
 */
public final class ChordSweep {

    private static final Path CACHE = Path.of("target", "chord-sweep-cache");

    private ChordSweep() {
    }

    /**
     * @param truth one cycle of the known changes, or null for a recording kept
     *     only as a control for {@code profile}
     */
    record Bench(String file, String truth) {
    }

    static final List<Bench> BENCHMARKS = List.of(
            new Bench("g-blues-shuffle-cc.mp3", "G7 G7 G7 G7 C7 C7 G7 G7 D7 C7 G7 D7"),
            new Bench("blues-a-90bpm.mp3", "A7 A7 A7 A7 D7 D7 A7 A7 E7 D7 A7 E7"),
            new Bench("blues-shuffle-a-106bpm.mp3", "A7 A7 A7 A7 D7 D7 A7 A7 E7 D7 A7 E7"),
            new Bench("blues-e-90bpm.mp3", "E7 E7 E7 E7 A7 A7 E7 E7 B7 A7 E7 B7"),
            new Bench("slow-68-40.mp3", "A7 A7 A7 A7 D7 D7 A7 A7 E7 D7 A7 E7"),
            new Bench("bm-blues-slow.mp3", "Bm Bm Bm Bm Em Em Bm Bm G7 F#7 Bm Bm"),
            new Bench("cm-blues-68-95.mp3", "Cm7 Cm7 Cm7 C7 Fm7 Fm7 Cm7 Cm7 Ab7 G7 Cm7 G7"),
            new Bench("waltz-am-e7-160.mp3", "Am Am Am E7 E7 E7 E7 Am"),
            new Bench("f-blues-swing-170.mp3",
                    "F7 Bb7 F7 F7 Bb7 Bdim F7 Am7b5-D7 Gm7 C7 F7-D7 Gm7-C7"),
            new Bench("jazz-251-c-140.mp3", "Dm7 Dm7 G7 G7 Cmaj7 Cmaj7 Cmaj7 Cmaj7"),
            new Bench("fm7-vamp-110.mp3", "Fm7"),
            new Bench("eb7-vamp-130.mp3", "Eb7"),
            new Bench("bossa-cm.mp3",
                    "Cm7 Cm7 Fm6 Fm6 Dm7b5 G7 Cm6 Cm6 Ebm7 Ab7 Dbmaj7 Dbmaj7 "
                            + "Dm7b5 G7 Cm6 Dm7b5-G7"),
            new Bench("pop-c-g-am-f-120.mp3", "C G Am F"),
            new Bench("pop-am-f-c-g-144.mp3", null));

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "score";
        switch (mode) {
            case "cache" -> cacheAll();
            case "score" -> {
                System.out.println("samples with known ground truth:");
                for (Bench b : cached()) {
                    if (b.truth() != null) {
                        score(b);
                    }
                }
            }
            case "profile" -> {
                System.out.printf("%-26s %-8s", "recording", "register");
                for (int interval = 0; interval < 12; interval++) {
                    System.out.printf("%6d", interval);
                }
                System.out.println("   b7 share (needs >0.155)");
                for (Bench b : cached()) {
                    profile(b);
                }
            }
            default -> throw new IllegalArgumentException("usage: cache | score | profile");
        }
    }

    static List<Bench> cached() {
        return BENCHMARKS.stream()
                .filter(b -> Files.isRegularFile(CACHE.resolve(b.file() + ".bin"))).toList();
    }

    // ---------------------------------------------------------------- caching

    static void cacheAll() throws Exception {
        Files.createDirectories(CACHE);
        for (Bench b : BENCHMARKS) {
            Path mp3 = Path.of("samples", b.file());
            if (!Files.isRegularFile(mp3)) {
                System.out.println("  " + b.file() + ": not present (see samples/list.txt)");
                continue;
            }
            cache(mp3);
            System.out.println("  cached " + b.file());
        }
    }

    static void cache(Path mp3) throws Exception {
        AudioBuffer audio = AudioDecoder.decode(mp3);
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);
        // The pipeline's own composition, and both orderings matter: chroma is
        // extracted before the beats so the tracker can weigh tempo candidates
        // by the harmonic rhythm (#231), and folding precedes
        // beat-synchronising -- see NnlsChroma.combined.
        NnlsChroma registers = NnlsChroma.extract(audio);
        List<Double> beatTimes = BeatTracker
                .track(envelope, HarmonicRhythm.of(registers.combined())).beatTimes();
        Chroma combined = registers.combined().beatSynchronous(beatTimes);
        DownbeatEstimator.Estimate down = DownbeatEstimator.estimate(
                beatTimes, combined, envelope, TimeSignature.FOUR_FOUR.beatsPerBar());
        // Written aside and moved into place, so an interrupted run leaves no
        // truncated file for the next one to read back as complete.
        Path partial = CACHE.resolve(mp3.getFileName() + ".bin.partial");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(partial)))) {
            out.writeDouble(audio.durationSeconds());
            out.writeInt(down.phase());
            out.writeInt(down.beatsPerBar());
            out.writeInt(beatTimes.size());
            for (double t : beatTimes) {
                out.writeDouble(t);
            }
            for (Chroma c : List.of(combined, registers.treble().beatSynchronous(beatTimes),
                    registers.bass().beatSynchronous(beatTimes))) {
                out.writeInt(c.frameCount());
                for (double[] frame : c.vectors()) {
                    for (int i = 0; i < 12; i++) {
                        out.writeDouble(frame[i]);
                    }
                }
            }
        }
        Files.move(partial, CACHE.resolve(mp3.getFileName() + ".bin"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    record Cached(double duration, int phase, int beatsPerBar, List<Double> beats,
                  double[][] combined, double[][] treble, double[][] bass) {
    }

    static Cached load(String name) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(CACHE.resolve(name + ".bin"))))) {
            double duration = in.readDouble();
            int phase = in.readInt();
            int beatsPerBar = in.readInt();
            List<Double> beats = new ArrayList<>();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                beats.add(in.readDouble());
            }
            double[][][] registers = new double[3][][];
            for (int k = 0; k < 3; k++) {
                registers[k] = new double[in.readInt()][12];
                for (double[] frame : registers[k]) {
                    for (int i = 0; i < 12; i++) {
                        frame[i] = in.readDouble();
                    }
                }
            }
            return new Cached(duration, phase, beatsPerBar, beats,
                    registers[0], registers[1], registers[2]);
        }
    }

    /** The estimator run exactly as {@code AudioTranscriber} runs it. */
    static ChordProgression estimate(Cached c) {
        return ChordEstimator.estimate(new Chroma(c.combined(), 0),
                new Chroma(c.treble(), 0), c.beats());
    }

    // ---------------------------------------------------------------- scoring

    static void score(Bench b) throws Exception {
        Cached c = load(b.file());
        ChordProgression chords = estimate(c);

        List<Double> downbeats = new ArrayList<>();
        for (int i = 0; i < c.beats().size(); i++) {
            if (Math.floorMod(i - c.phase(), c.beatsPerBar()) == 0) {
                downbeats.add(c.beats().get(i));
            }
        }
        List<int[][]> want = parseTruth(b.truth());
        int bars = downbeats.size() - 1;
        int[][] labels = new int[bars][];
        for (int i = 0; i < bars; i++) {
            labels[i] = labelOf(chords, downbeats.get(i), downbeats.get(i + 1));
        }

        int bestRoot = 0;
        int bestFull = 0;
        int bestRotation = 0;
        for (int rotation = 0; rotation < want.size(); rotation++) {
            int rootOk = 0;
            int fullOk = 0;
            for (int i = 0; i < bars; i++) {
                for (int[] acceptable : acceptableAt(want, i + rotation)) {
                    if (labels[i] != null && labels[i][0] == acceptable[0]) {
                        rootOk++;
                        break;
                    }
                }
                for (int[] acceptable : acceptableAt(want, i + rotation)) {
                    if (labels[i] != null && labels[i][0] == acceptable[0]
                            && labels[i][1] == acceptable[1]) {
                        fullOk++;
                        break;
                    }
                }
            }
            if (rootOk > bestRoot) {
                bestRoot = rootOk;
                bestFull = fullOk;
                bestRotation = rotation;
            }
        }

        // What the accuracy columns cannot show: on the bars whose root is
        // right, which quality was reported in place of the one that is there.
        Map<String, Integer> confusion = new TreeMap<>();
        for (int i = 0; i < bars; i++) {
            for (int[] acceptable : acceptableAt(want, i + bestRotation)) {
                if (labels[i] != null && labels[i][0] == acceptable[0]) {
                    confusion.merge(name(acceptable[1]) + "->" + name(labels[i][1]),
                            1, Integer::sum);
                    break;
                }
            }
        }

        double noChord = 0;
        for (Chord chord : chords.chords()) {
            if (chord.quality() == ChordQuality.NONE) {
                noChord += chord.endSeconds() - chord.startSeconds();
            }
        }
        System.out.printf(Locale.ROOT,
                "  %s: bars=%d  root %d/%d (%.1f%%)  root+quality %d/%d (%.1f%%)"
                        + "  N.C. %.1f%% of %.0fs%n"
                        + "      spans=%d  quality on root-correct bars: %s%n",
                b.file(), bars, bestRoot, bars, 100.0 * bestRoot / bars,
                bestFull, bars, 100.0 * bestFull / bars,
                100 * noChord / c.duration(), c.duration(), chords.size(), confusion);
    }

    static int[][] acceptableAt(List<int[][]> want, int index) {
        return want.get(Math.floorMod(index, want.size()));
    }

    static String name(int qualityOrdinal) {
        ChordQuality quality = ChordQuality.values()[qualityOrdinal];
        return quality.symbol().isEmpty() ? "maj" : quality.symbol();
    }

    static int[] labelOf(ChordProgression chords, double start, double end) {
        Chord best = null;
        double bestOverlap = 0;
        for (Chord c : chords.chords()) {
            double overlap = Math.min(end, c.endSeconds()) - Math.max(start, c.startSeconds());
            if (overlap > bestOverlap) {
                best = c;
                bestOverlap = overlap;
            }
        }
        if (best == null || best.quality() == ChordQuality.NONE) {
            return null;
        }
        return new int[] {Math.floorMod(best.root().pitchClass(), 12), best.quality().ordinal()};
    }

    // --------------------------------------------------------------- profiles

    static void profile(Bench b) throws Exception {
        Cached c = load(b.file());
        ChordProgression chords = estimate(c);

        // The root the estimator decoded for each beat. Conditioning on that
        // rather than on ground truth is what lets the pop controls, whose bar
        // grids are unconfirmed, be measured at all -- and score reports how
        // often the decoded root is the right one.
        int[] root = new int[c.combined().length];
        Arrays.fill(root, -1);
        for (Chord chord : chords.chords()) {
            if (chord.quality() == ChordQuality.NONE) {
                continue;
            }
            for (int f = 0; f < root.length; f++) {
                if (c.beats().get(f) >= chord.startSeconds()
                        && c.beats().get(f) < chord.endSeconds()) {
                    root[f] = Math.floorMod(chord.root().pitchClass(), 12);
                }
            }
        }

        // Every quality reported, not just the dominant seventh: on a recording
        // whose chords are plain triads, any four-note label is a false one, and
        // that is the only negative control this corpus has (#273).
        Map<String, Integer> qualities = new TreeMap<>();
        for (Chord chord : chords.chords()) {
            qualities.merge(chord.quality().symbol().isEmpty() ? "maj"
                    : chord.quality().symbol(), 1, Integer::sum);
        }
        System.out.printf(Locale.ROOT, "%-26s spans=%d, by quality: %s%n",
                b.file(), chords.size(), qualities);
        printProfile("combined", c.combined(), root);
        printProfile("treble", c.treble(), root);
        printProfile("bass", c.bass(), root);
    }

    static void printProfile(String register, double[][] vectors, int[] root) {
        double[] mean = new double[12];
        int counted = 0;
        for (int f = 0; f < vectors.length; f++) {
            if (root[f] < 0) {
                continue;
            }
            double sum = 0;
            for (double x : vectors[f]) {
                sum += x;
            }
            if (sum <= 0) {
                continue;
            }
            counted++;
            for (int interval = 0; interval < 12; interval++) {
                mean[interval] += vectors[f][(root[f] + interval) % 12] / sum;
            }
        }
        if (counted == 0) {
            System.out.printf(Locale.ROOT, "%-26s %-8s no chord span covers any beat%n",
                    "", register);
            return;
        }
        StringBuilder line =
                new StringBuilder(String.format(Locale.ROOT, "%-26s %-8s", "", register));
        for (int interval = 0; interval < 12; interval++) {
            line.append(String.format(Locale.ROOT, "%6.3f", mean[interval] / counted));
        }
        double triad = mean[0] + mean[4] + mean[7];
        System.out.println(line.append(String.format(Locale.ROOT, "   %.3f", mean[10] / triad)));
    }

    // ----------------------------------------------------------- ground truth

    /** One cycle as a list of bars, each holding the chords acceptable there. */
    static List<int[][]> parseTruth(String line) {
        List<int[][]> bars = new ArrayList<>();
        for (String bar : line.trim().split("\\s+")) {
            String[] alternatives = bar.split("-");
            int[][] parsed = new int[alternatives.length][];
            for (int i = 0; i < alternatives.length; i++) {
                parsed[i] = parseChord(alternatives[i]);
            }
            bars.add(parsed);
        }
        return bars;
    }

    static int[] parseChord(String symbol) {
        int pitchClass = switch (symbol.charAt(0)) {
            case 'C' -> 0;
            case 'D' -> 2;
            case 'E' -> 4;
            case 'F' -> 5;
            case 'G' -> 7;
            case 'A' -> 9;
            case 'B' -> 11;
            default -> throw new IllegalArgumentException(symbol);
        };
        String rest = symbol.substring(1);
        while (rest.startsWith("b") || rest.startsWith("#")) {
            pitchClass += rest.charAt(0) == '#' ? 1 : -1;
            rest = rest.substring(1);
        }
        // ChordQuality's own symbols, which is what score-samples.py parses too:
        // one spelling for the truth, the tool's own, so the two harnesses
        // cannot come to disagree about what the right answer is.
        String suffix = rest;
        ChordQuality quality = Arrays.stream(ChordQuality.values())
                .filter(q -> q != ChordQuality.NONE && q.symbol().equals(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(symbol));
        return new int[] {Math.floorMod(pitchClass, 12), quality.ordinal()};
    }
}
