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

package dev.olivelli.musicwizard.transcribe;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Recognises chords from notes whose pitches, onsets and durations are already
 * exact.
 *
 * <p>The symbolic counterpart of {@code ChordEstimator}, and deliberately not
 * a reuse of it: notes carry what no chroma can — exact sounding durations,
 * exact pitches, and which part each note came from. Synthesising a chroma to
 * reuse the audio matcher would discard all three and then run the result
 * through constants calibrated against real chroma statistics.
 *
 * <p>One decision per counted beat (a 6/8 bar is two dotted-quarter spans),
 * every position in quarter-note beats, seconds appearing once at the end
 * through {@link TempoMap}. Each span gets a duration-weighted pitch-class
 * histogram, each candidate a score, and the sequence a Viterbi pass whose
 * only structure is that changing chord costs something, halved at a bar
 * line.
 *
 * <p>No chord is a legitimate answer: silence, a drum-only file, and any span
 * whose winning chord never sounds three of its own notes. When every span
 * comes back that way the result is {@link ChordProgression#empty()} rather
 * than a page of {@code N.C.} An unaccompanied melody is <em>not</em> reliably
 * discarded — an arpeggio is an unaccompanied melody too, and a line dwelling
 * on three tones of one chord is real evidence about the harmony; what shows
 * the ambiguity is the confidence, not the label.
 */
public final class SymbolicChordEstimator {

    /**
     * What the chord's own notes actually sounding is worth, against
     * explaining the span's weight at 1. Coverage alone cannot tell C major
     * from C minor over a span holding only a C; completeness breaks the tie
     * towards the chord with more of itself present. One constant rather than
     * two because only the ratio does anything — {@link #FIT_SCALE} divides
     * the pair back out. Tests pin that the term exists, not its size; it is
     * calibrated only against {@link #NO_CHORD_FIT}.
     */
    private static final double COMPLETENESS_WEIGHT = 0.5;

    /** So that a fit lands in 0..1 and the constants below read as fractions. */
    private static final double FIT_SCALE = 1 + COMPLETENESS_WEIGHT;

    /**
     * Share of a span's weight a chord tone must carry to count as sounding —
     * what stops a briefly touched passing note from promoting a triad to a
     * seventh while a held one still does.
     */
    private static final double MIN_TONE_SHARE = 0.05;

    /**
     * The fit "no chord" is worth, on the same 0..1 scale as every other
     * state. Sits between a span sounding one note of a triad — an arpeggio's
     * shape, which accumulates into a chord — and one sounding nothing the
     * triad contains. It does not discard a moving line (that chatters and is
     * discarded by {@link #MIN_CHORD_TONES} afterwards), and neither is a
     * filter for "unaccompanied" — see the class javadoc.
     */
    private static final double NO_CHORD_FIT = 0.47;

    /**
     * Fit given up to change chord away from a bar line.
     *
     * <p>Roughly the gain a correct chord shows over a stale one for a single
     * span, so a one-span chord has to be paid for twice over and a two-span
     * chord pays for itself. This is the same job {@code ChordEstimator}'s
     * self-transition does, stated as a cost rather than as a probability
     * because these scores are fits rather than likelihoods.
     */
    private static final double CHANGE_PENALTY = 0.45;

    /**
     * What the penalty is multiplied by on the first beat of a bar.
     *
     * <p>Harmony changes on bar lines far more often than inside them, and the
     * difference between a chart that changes on the downbeat and one that
     * changes a beat early is the difference between readable and not.
     */
    private static final double DOWNBEAT_DISCOUNT = 0.5;

    /**
     * Fit added when the sounding bass agrees with a candidate's root.
     *
     * <p>Awarded only where the span states {@link #MIN_CHORD_TONES} pitch
     * classes, and that condition is not a refinement -- it is what keeps the
     * bonus from doing harm. Under a thin texture every candidate rooted on the
     * one sounding note collects it, so a held chord whose accompaniment drops
     * to a single note would change to a chord named after that note, and then
     * be thrown out for stating nothing. Knowing which pitch class is the bass
     * is only worth anything when there is more than one to choose between.
     */
    private static final double ROOT_BASS_BONUS = 0.10;

    /**
     * The most confidence a chord label from this vocabulary can be given.
     * Not full: a template match is inferred, and a perfect fit does not even
     * guarantee the right label — C6 and Am7 are the same four pitch classes
     * and only one is offered (#122). "No chord" over silence is exempt,
     * being read from the file rather than inferred. This covers what
     * {@link #DECISIVE_MARGIN}'s measured margin cannot see: a rival that was
     * never offered.
     */
    private static final double VOCABULARY_CEILING = 0.9;

    /**
     * The margin over the runner-up at which a label stops being a guess.
     * Measured over run aggregates: an unarguable chord clears it fully, the
     * C6-versus-Am7 ambiguity of #122 reaches a fraction of it, and the
     * Cm-versus-Cm7 rows sit between, which is right — charts genuinely
     * disagree there. Not enough measurement to fix the threshold, which is
     * part of what #124 is for.
     */
    private static final double DECISIVE_MARGIN = 0.15;

    /** Share of the time a pitch class must be the bottom of the texture to be the bass. */
    private static final double BASS_DOMINANCE = 0.6;

    /**
     * Chord tones that must sound before a span is called a chord at all.
     *
     * <p>Three, because that is what a triad is. Two pitch classes name an
     * interval, not a chord: C and E alone are equally the top of an A minor
     * seventh and the bottom of a C major, and one alone is a note. Applied over
     * the whole merged span rather than per beat, so an arpeggio that spells its
     * triad across a bar passes and a drone held for the same bar does not.
     */
    private static final int MIN_CHORD_TONES = 3;

    /**
     * The most spans this will decide over — far above any real piece. Guards
     * against a file whose last event carries an enormous delta time:
     * {@link MidiTranscriber}'s size limit bounds the note count but not the
     * tick position, so a handful of notes can declare a span of days. Such a
     * file still imports its notes; it just gets no chords.
     */
    private static final int MAX_SPANS = 100_000;

    /** Guards the span walk against a rounding residue at the end of the piece. */
    private static final double BEAT_EPSILON = 1e-9;

    private SymbolicChordEstimator() {
    }

    private record Template(int root, ChordQuality quality, int[] pitchClasses, double prior) {
    }

    /**
     * A counted beat: the unit one chord decision is taken over.
     *
     * <p>Package-private, along with {@link Voiced}, {@link BassLine} and
     * {@link BassReader}, so the bass sweep can be checked against a
     * brute-force reference directly. It is the part of this class that has
     * already been wrong once -- an early draft never enqueued a started note,
     * so every bar reported the lowest pitch of the whole piece -- and a
     * property test over random input is worth more than the fixtures that
     * happened to catch it.
     */
    record Span(double startBeat, double endBeat, boolean downbeat) {
    }

    /** A pitched note reduced to what chord estimation needs. */
    record Voiced(double startBeat, double endBeat, int midiPitch, boolean bassPart) {
    }

    private static final List<Template> TEMPLATES = buildTemplates();

    /** Index of the "no chord" state, which sits one past the templates. */
    private static final int NO_CHORD_STATE = TEMPLATES.size();

    /**
     * The candidate chords: every quality in the vocabulary on every root —
     * wider than the audio estimator's, because exact pitches support the
     * distinction and a chroma bin does not. Each quality carries a prior it
     * has to overcome, or every four-note label would win by covering at
     * least as much weight as the triad inside it.
     *
     * <p>Absent: sixths, because C6 and Am7 are the same four pitch classes
     * and offering both puts two states in permanent competition settled by
     * the bass alone (#122); and the minor-major seventh, rare enough that a
     * passing major seventh over a minor triad is the likelier explanation of
     * the same notes.
     */
    private static List<Template> buildTemplates() {
        // Prior per quality, in fit units: zero for the two a chart is mostly
        // made of, a tone's worth for a fourth note, a little for triads a
        // couple of passing notes can counterfeit.
        Object[][] vocabulary = {
            {ChordQuality.MAJOR, 0.00},
            {ChordQuality.MINOR, 0.00},
            {ChordQuality.DOMINANT_SEVENTH, 0.10},
            {ChordQuality.MAJOR_SEVENTH, 0.10},
            {ChordQuality.MINOR_SEVENTH, 0.10},
            {ChordQuality.SUSPENDED_FOURTH, 0.08},
            {ChordQuality.SUSPENDED_SECOND, 0.10},
            {ChordQuality.DIMINISHED, 0.08},
            {ChordQuality.AUGMENTED, 0.14},
            {ChordQuality.HALF_DIMINISHED_SEVENTH, 0.14},
            {ChordQuality.DIMINISHED_SEVENTH, 0.14},
        };
        List<Template> templates = new ArrayList<>(vocabulary.length * 12);
        for (Object[] entry : vocabulary) {
            ChordQuality quality = (ChordQuality) entry[0];
            double prior = (Double) entry[1];
            for (int root = 0; root < 12; root++) {
                int[] intervals = quality.intervals();
                int[] classes = new int[intervals.length];
                for (int i = 0; i < intervals.length; i++) {
                    classes[i] = Math.floorMod(root + intervals[i], 12);
                }
                templates.add(new Template(root, quality, classes, prior));
            }
        }
        return List.copyOf(templates);
    }

    /**
     * Estimates the chords of a symbolic score.
     *
     * @param tracks     every part in the piece; {@link PartRole#DRUMS} tracks
     *                   are dropped and everything else is used, which on the
     *                   MIDI path means channel-10 percussion is dropped and
     *                   percussion routed elsewhere is not -- see #137
     * @param tempoMap   the score's tempo map, the only route from beats to seconds
     * @param totalBeats the length of the piece in quarter-note beats
     * @param keys       key signatures, used only to spell roots as flats where
     *                   the key is a flat one; may be empty
     */
    public static ChordProgression estimate(List<NoteTrack> tracks, TempoMap tempoMap,
                                            double totalBeats, List<Key> keys) {
        return estimate(tracks, tempoMap, totalBeats, keys, null);
    }

    /**
     * Estimates the chords of a symbolic score, reporting progress.
     *
     * @param progress notified of anything a caller would want to see in a log;
     *                 may be null
     */
    public static ChordProgression estimate(List<NoteTrack> tracks, TempoMap tempoMap,
                                            double totalBeats, List<Key> keys,
                                            Consumer<String> progress) {
        Objects.requireNonNull(tracks, "tracks");
        Objects.requireNonNull(tempoMap, "tempoMap");
        Objects.requireNonNull(keys, "keys");
        Consumer<String> log = progress != null ? progress : message -> { };
        if (!Double.isFinite(totalBeats) || totalBeats <= 0) {
            return ChordProgression.empty();
        }

        List<Voiced> notes = pitchedNotes(tracks);
        if (notes.isEmpty()) {
            // A drum-only file, or one whose notes never got musical time. There
            // is no harmony to find and inventing one would be worse than saying
            // so.
            return ChordProgression.empty();
        }

        // Decided over the sounding length rather than the declared one. They
        // differ when a file's last event carries an enormous delta time, and an
        // ordinary song with one stray far-out event would otherwise trip the
        // cap below and lose the harmony it does have.
        double soundingBeats = 0;
        for (Voiced note : notes) {
            soundingBeats = Math.max(soundingBeats, note.endBeat());
        }
        double decidedBeats = Math.min(totalBeats, soundingBeats);
        if (!(decidedBeats > 0)) {
            return ChordProgression.empty();
        }

        List<Span> spans = beatSpans(tempoMap, decidedBeats);
        if (spans.isEmpty()) {
            return ChordProgression.empty();
        }
        if (spans.size() > MAX_SPANS) {
            log.accept(String.format(Locale.ROOT,
                    "this file sounds over %.0f beats, which is %d chord decisions and more than"
                            + " the %d this will take on; leaving it without chords",
                    decidedBeats, spans.size(), MAX_SPANS));
            return ChordProgression.empty();
        }

        Histogram[] histograms = histograms(spans, notes, BassReader.of(notes));
        int[] path = decode(spans, histograms);
        List<Run> runs = mergeRuns(path, histograms);
        return toProgression(runs, spans, histograms, tempoMap, keys);
    }

    // -------------------------------------------------------------- the spans

    /**
     * The counted beats of the piece, in order.
     *
     * <p>Walked bar by bar rather than by adding a beat at a time, so a meter
     * change lands on the bar it belongs to and the accumulated position stays
     * exact. Both quantities involved are dyadic rationals -- a bar is
     * {@code numerator * 4 / denominator} quarters with a power-of-two
     * denominator no larger than 64 -- so the sums here are exact rather than
     * nearly so, and the last span of a bar cannot round into the next one.
     */
    private static List<Span> beatSpans(TempoMap tempoMap, double totalBeats) {
        List<Span> spans = new ArrayList<>();
        double barStart = 0;
        int bar = 0;
        while (barStart < totalBeats - BEAT_EPSILON && spans.size() <= MAX_SPANS) {
            TimeSignature meter = tempoMap.timeSignatureAtBar(bar);
            double unit = meter.beatUnitQuarters();
            int perBar = meter.beatsPerBar();
            for (int i = 0; i < perBar; i++) {
                double start = barStart + i * unit;
                if (start >= totalBeats - BEAT_EPSILON) {
                    break;
                }
                double end = Math.min(start + unit, totalBeats);
                if (end > start) {
                    spans.add(new Span(start, end, i == 0));
                }
                if (spans.size() > MAX_SPANS) {
                    return spans;
                }
            }
            barStart += meter.quarterBeatsPerBar();
            bar++;
        }
        return spans;
    }

    // --------------------------------------------------------- the histograms

    /**
     * What one span knows about what is sounding in it.
     *
     * @param weight         duration in beats each pitch class sounds for
     * @param total          the sum of those, so shares can be taken
     * @param bassPitchClass the pitch class at the bottom of the texture, or -1
     * @param distinctTones  how many pitch classes sound for a share worth counting
     */
    private record Histogram(double[] weight, double total, int bassPitchClass,
                             int distinctTones) {
    }

    /**
     * Every pitched note in the score, in onset order. All parts — the audio
     * rule that chords come from the full mix has the same force here — except
     * drums, whose note numbers are instrument selectors rather than pitches.
     * That exclusion is a claim about {@link PartRole#DRUMS}, which today
     * means channel 10 only, so percussion routed elsewhere still reaches
     * this histogram as a pitched part (#137). Notes without musical time are
     * skipped rather than converted — they have no position on this axis and
     * inventing one is what the optional fields exist to prevent.
     */
    private static List<Voiced> pitchedNotes(List<NoteTrack> tracks) {
        List<Voiced> voiced = new ArrayList<>();
        for (NoteTrack track : tracks) {
            if (track.role() == PartRole.DRUMS) {
                continue;
            }
            boolean bassPart = track.role() == PartRole.BASS;
            for (Note note : track.notes()) {
                if (!note.isQuantized()) {
                    continue;
                }
                voiced.add(new Voiced(note.onsetBeat().orElseThrow(),
                        note.offsetBeat().orElseThrow(), note.midiPitch(), bassPart));
            }
        }
        voiced.sort(Comparator.comparingDouble(Voiced::startBeat));
        return voiced;
    }

    /**
     * The duration-weighted pitch-class histogram of every span. A sweep
     * rather than a scan per span, so the cost is total note-span overlap
     * rather than notes times spans — the whole-note pedal under a moving
     * line is the case that makes the difference. On adversarial input the
     * overlap bound still degenerates; #135 records the measurements and the
     * {@link BassLine}-style treatment that would fix it. Real arrangements
     * are nowhere near it.
     */
    private static Histogram[] histograms(List<Span> spans, List<Voiced> notes,
                                          BassReader bass) {
        Histogram[] out = new Histogram[spans.size()];
        PriorityQueue<Voiced> sounding =
                new PriorityQueue<>(Comparator.comparingDouble(Voiced::endBeat));
        int next = 0;

        for (int s = 0; s < spans.size(); s++) {
            Span span = spans.get(s);
            while (next < notes.size() && notes.get(next).startBeat() < span.endBeat()) {
                sounding.add(notes.get(next++));
            }
            while (!sounding.isEmpty() && sounding.peek().endBeat() <= span.startBeat()) {
                sounding.poll();
            }

            double[] weight = new double[12];
            double total = 0;
            for (Voiced note : sounding) {
                double overlap = Math.min(note.endBeat(), span.endBeat())
                        - Math.max(note.startBeat(), span.startBeat());
                if (overlap <= 0) {
                    continue;
                }
                weight[Math.floorMod(note.midiPitch(), 12)] += overlap;
                total += overlap;
            }
            out[s] = new Histogram(weight, total, bass.pitchClassOver(span),
                    distinctTones(weight, total));
        }
        return out;
    }

    /** How many pitch classes sound for a share large enough to count as stated. */
    private static int distinctTones(double[] weight, double total) {
        if (total <= 0) {
            return 0;
        }
        int distinct = 0;
        for (double value : weight) {
            if (value >= MIN_TONE_SHARE * total) {
                distinct++;
            }
        }
        return distinct;
    }

    /**
     * The lowest sounding pitch across the whole piece, as a piecewise-constant
     * function of position in quarter-note beats. Built once and integrated
     * per span: it is a property of the music, not of a decision window, and
     * rebuilding it per span was measured quadratic in the notes sounding at
     * once — a shape {@link #MAX_SPANS} does not bound.
     *
     * <p>Which notes go into one is {@link BassReader}'s question, not this
     * class's: it builds one of these over the declared bass part and one over
     * the whole texture, and reads whichever is sounding.
     */
    static final class BassLine {

        /** Nothing sounding. */
        private static final int SILENT = -1;

        /** Segment starts, ascending; the last runs to the end of the piece. */
        private final double[] from;

        /** The lowest pitch sounding in each segment, or {@link #SILENT}. */
        private final int[] pitch;

        private final int count;

        /**
         * Where the last query left off.
         *
         * <p>Spans are asked for in order, so a cursor makes the whole walk
         * linear in segments plus spans rather than segments times spans. A
         * query that goes backwards resets it, so this is an optimisation and
         * not a contract the caller has to keep -- one that answered differently
         * depending on what was asked before it would be a trap, however
         * carefully the one caller today avoids it.
         */
        private int cursor;

        private BassLine(double[] from, int[] pitch, int count) {
            this.from = from;
            this.pitch = pitch;
            this.count = count;
        }

        /**
         * Sweeps the notes once, recording where the bottom of the texture moves.
         *
         * <p>The merge is between two already-ordered sequences -- the notes by
         * onset, which is how they arrive, and the sounding ones by offset, which
         * is what the queue keeps -- so nothing is sorted a second time.
         */
        static BassLine of(List<Voiced> candidates) {
            double[] from = new double[2 * candidates.size() + 1];
            int[] pitch = new int[from.length];
            int count = 0;
            PriorityQueue<Voiced> active =
                    new PriorityQueue<>(Comparator.comparingDouble(Voiced::endBeat));
            int[] sounding = new int[128];
            int lowest = SILENT;
            int next = 0;

            while (next < candidates.size() || !active.isEmpty()) {
                double at;
                if (active.isEmpty()) {
                    at = candidates.get(next).startBeat();
                } else if (next < candidates.size()) {
                    at = Math.min(candidates.get(next).startBeat(), active.peek().endBeat());
                } else {
                    at = active.peek().endBeat();
                }
                // Every event at this position is applied before the segment is
                // recorded, so a note handed over exactly at an edge never shows
                // as a momentary gap. Both conditions are "<=" rather than "<"
                // for a second reason as well: "at" is the earliest pending
                // event, so leaving an event of that position unconsumed would
                // compute the same "at" again and the loop would never finish.
                while (!active.isEmpty() && active.peek().endBeat() <= at) {
                    int stopped = active.poll().midiPitch();
                    if (--sounding[stopped] == 0 && stopped == lowest) {
                        lowest = SILENT;
                        for (int p = stopped + 1; p < sounding.length; p++) {
                            if (sounding[p] > 0) {
                                lowest = p;
                                break;
                            }
                        }
                    }
                }
                while (next < candidates.size() && candidates.get(next).startBeat() <= at) {
                    Voiced started = candidates.get(next++);
                    active.add(started);
                    sounding[started.midiPitch()]++;
                    if (lowest == SILENT || started.midiPitch() < lowest) {
                        lowest = started.midiPitch();
                    }
                }
                if (count == 0 || pitch[count - 1] != lowest) {
                    from[count] = at;
                    pitch[count] = lowest;
                    count++;
                }
            }
            return new BassLine(from, pitch, count);
        }

        /**
         * Adds how long each pitch class spends at the bottom of a span, and
         * answers with the total.
         *
         * <p>Zero means nothing sounded here at all, which is a different answer
         * from "several things did and none of them dominated" -- and telling
         * the two apart is what lets a resting bass part hand over to the
         * texture instead of reporting no bass.
         */
        double weighOver(Span span, double[] weight) {
            // Spans arrive in order, so the cursor normally only moves forward.
            // The rewind is not dead code guarding an impossible call: it is what
            // makes the method a function of its argument rather than of the call
            // history, which is the property a reader will assume it has.
            if (cursor > 0 && from[cursor] > span.startBeat()) {
                cursor = 0;
            }
            while (cursor + 1 < count && from[cursor + 1] <= span.startBeat()) {
                cursor++;
            }
            double total = 0;
            for (int i = cursor; i < count; i++) {
                if (from[i] >= span.endBeat()) {
                    break;
                }
                if (pitch[i] == SILENT) {
                    continue;
                }
                double segmentEnd = i + 1 < count ? from[i + 1] : Double.POSITIVE_INFINITY;
                double sounds = Math.min(segmentEnd, span.endBeat())
                        - Math.max(from[i], span.startBeat());
                if (sounds > 0) {
                    weight[Math.floorMod(pitch[i], 12)] += sounds;
                    total += sounds;
                }
            }
            return total;
        }
    }

    /**
     * Where the bass of a span comes from: a declared {@link PartRole#BASS}
     * part where it is sounding, and the bottom of the texture where it is
     * not. The fallback is load-bearing — without it a bass part resting for
     * a break silences the bass, the root bonus vanishes, and a held chord
     * splits at the bar the bassist stopped playing. Both lines are built
     * once; the choice per span is which of two answers to read.
     */
    static final class BassReader {

        /** The declared bass part, or null when the score names none. */
        private final BassLine declared;

        /** Every pitched part, which is what the bottom of the texture means. */
        private final BassLine texture;

        private BassReader(BassLine declared, BassLine texture) {
            this.declared = declared;
            this.texture = texture;
        }

        static BassReader of(List<Voiced> notes) {
            List<Voiced> bassPart = notes.stream().filter(Voiced::bassPart).toList();
            return new BassReader(bassPart.isEmpty() ? null : BassLine.of(bassPart),
                    BassLine.of(notes));
        }

        /**
         * The pitch class at the bottom of a span, or -1 when there is no single
         * one.
         *
         * <p>{@link #BASS_DOMINANCE} is what stops a walking bass from becoming
         * an inversion: a span the line walks through reports no bass rather
         * than whichever step it happened to start on.
         */
        int pitchClassOver(Span span) {
            double[] weight = new double[12];
            double total = declared != null ? declared.weighOver(span, weight) : 0;
            if (total <= 0) {
                Arrays.fill(weight, 0);
                total = texture.weighOver(span, weight);
            }
            if (total <= 0) {
                return -1;
            }
            int best = 0;
            for (int pc = 1; pc < 12; pc++) {
                if (weight[pc] > weight[best]) {
                    best = pc;
                }
            }
            return weight[best] >= BASS_DOMINANCE * total ? best : -1;
        }
    }

    // ------------------------------------------------------------- the decode

    /**
     * How well a template explains a span, in 0..1.
     *
     * <p>Two terms, because either alone is defeated by an easy case. Coverage
     * alone says a lone sounding C is a perfect C major, a perfect C minor and a
     * perfect C7. Completeness alone says a bar of C-E-G with a loud passing F
     * sharp is as good a C major as one without it.
     */
    private static double fit(Template template, Histogram histogram) {
        if (histogram.total() <= 0) {
            return 0;
        }
        double covered = 0;
        int present = 0;
        for (int pitchClass : template.pitchClasses()) {
            double weight = histogram.weight()[pitchClass];
            covered += weight;
            if (weight >= MIN_TONE_SHARE * histogram.total()) {
                present++;
            }
        }
        double coverage = covered / histogram.total();
        double completeness = present / (double) template.pitchClasses().length;
        return (coverage + COMPLETENESS_WEIGHT * completeness) / FIT_SCALE;
    }

    /** The score a state gets in a span, which is its fit less what it has to justify. */
    private static double emission(int state, Histogram histogram) {
        if (state == NO_CHORD_STATE) {
            return NO_CHORD_FIT;
        }
        Template template = TEMPLATES.get(state);
        double score = fit(template, histogram) - template.prior();
        boolean rootIsWorthDeciding = histogram.distinctTones() >= MIN_CHORD_TONES;
        return rootIsWorthDeciding && histogram.bassPitchClass() == template.root()
                ? score + ROOT_BASS_BONUS : score;
    }

    /**
     * Viterbi over the spans, with a flat transition model.
     *
     * <p>The only structure in the chain is that changing state costs something,
     * so the best predecessor of any state is either that state or whichever
     * state led the previous span. Finding the leader once per span keeps this
     * linear in the state count rather than quadratic, and lets the back
     * pointers be a single boolean -- did the path stay put -- instead of a
     * state index, which is what makes a long piece affordable.
     */
    private static int[] decode(List<Span> spans, Histogram[] histograms) {
        int states = NO_CHORD_STATE + 1;
        int frames = spans.size();
        double[] previousScore = new double[states];
        double[] currentScore = new double[states];
        boolean[][] stayed = new boolean[frames][states];
        int[] leader = new int[frames];

        for (int state = 0; state < states; state++) {
            previousScore[state] = emission(state, histograms[0]);
        }
        leader[0] = argmax(previousScore);

        for (int frame = 1; frame < frames; frame++) {
            double cost = CHANGE_PENALTY * (spans.get(frame).downbeat() ? DOWNBEAT_DISCOUNT : 1);
            double moving = previousScore[leader[frame - 1]] - cost;
            for (int state = 0; state < states; state++) {
                double staying = previousScore[state];
                // Ties go to staying put, which is the same bias the cost
                // expresses and keeps the result independent of state order.
                boolean stayPut = staying >= moving;
                stayed[frame][state] = stayPut;
                currentScore[state] = (stayPut ? staying : moving)
                        + emission(state, histograms[frame]);
            }
            double[] swap = previousScore;
            previousScore = currentScore;
            currentScore = swap;
            leader[frame] = argmax(previousScore);
        }

        int[] path = new int[frames];
        path[frames - 1] = argmax(previousScore);
        for (int frame = frames - 1; frame > 0; frame--) {
            path[frame - 1] = stayed[frame][path[frame]] ? path[frame] : leader[frame - 1];
        }
        return path;
    }

    private static int argmax(double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- the runs

    /** A maximal stretch of spans the decoder assigned to one state. */
    private record Run(int state, int fromSpan, int toSpan) {
    }

    /**
     * Merges the decoded path into chord spans, then demotes the ones that turn
     * out not to state a chord.
     *
     * <p>The demotion is the rule that keeps this honest, and it is applied here
     * rather than per beat because a beat is the wrong window to apply it over:
     * an arpeggio sounds one note at a time and still states its triad across
     * the bar, while a drone sounds one note at a time and never states
     * anything. Only the merged span can tell them apart.
     */
    private static List<Run> mergeRuns(int[] path, Histogram[] histograms) {
        List<Run> runs = new ArrayList<>();
        int from = 0;
        for (int i = 1; i <= path.length; i++) {
            if (i < path.length && path[i] == path[from]) {
                continue;
            }
            runs.add(new Run(statesChord(path[from], histograms, from, i), from, i));
            from = i;
        }
        // Demotion can leave two no-chord runs adjacent, and a progression that
        // printed "N.C. N.C." would be reporting a boundary that no longer
        // exists.
        List<Run> merged = new ArrayList<>(runs.size());
        for (Run run : runs) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).state() == run.state()) {
                merged.set(last, new Run(run.state(), merged.get(last).fromSpan(), run.toSpan()));
            } else {
                merged.add(run);
            }
        }
        return merged;
    }

    /** The state a run keeps, which is no chord unless it sounds enough of one. */
    private static int statesChord(int state, Histogram[] histograms, int fromSpan, int toSpan) {
        if (state == NO_CHORD_STATE) {
            return NO_CHORD_STATE;
        }
        Histogram aggregate = aggregate(histograms, fromSpan, toSpan);
        if (aggregate.total() <= 0) {
            return NO_CHORD_STATE;
        }
        int present = 0;
        for (int pitchClass : TEMPLATES.get(state).pitchClasses()) {
            if (aggregate.weight()[pitchClass] >= MIN_TONE_SHARE * aggregate.total()) {
                present++;
            }
        }
        return present >= MIN_CHORD_TONES ? state : NO_CHORD_STATE;
    }

    private static Histogram aggregate(Histogram[] histograms, int fromSpan, int toSpan) {
        double[] weight = new double[12];
        double total = 0;
        for (int s = fromSpan; s < toSpan; s++) {
            for (int pc = 0; pc < 12; pc++) {
                weight[pc] += histograms[s].weight()[pc];
            }
            total += histograms[s].total();
        }
        // No bass: a run's bass is decided from the spans agreeing rather than
        // from their sum, since a line that walks through a run sums to whatever
        // it spent longest on.
        return new Histogram(weight, total, -1, distinctTones(weight, total));
    }

    // ------------------------------------------------------------- the output

    /**
     * Turns runs into chords, filling both time axes.
     *
     * <p>MIDI states its rhythm exactly, so unlike the audio path there is no
     * reason to leave the musical timing empty: a chord here is quantized from
     * the moment it exists. Seconds come from {@link TempoMap} and only from
     * there, so a chord boundary and a note boundary on the same beat agree to
     * the last bit.
     */
    private static ChordProgression toProgression(List<Run> runs, List<Span> spans,
                                                  Histogram[] histograms, TempoMap tempoMap,
                                                  List<Key> keys) {
        List<Chord> chords = new ArrayList<>(runs.size());
        double confidenceTotal = 0;
        boolean anyChord = false;

        for (Run run : runs) {
            double startBeat = spans.get(run.fromSpan()).startBeat();
            double endBeat = spans.get(run.toSpan() - 1).endBeat();
            double startSeconds = tempoMap.beatsToSeconds(startBeat);
            double endSeconds = tempoMap.beatsToSeconds(endBeat);
            if (!(endSeconds > startSeconds) || !(endBeat > startBeat)) {
                continue;
            }
            Histogram aggregate = aggregate(histograms, run.fromSpan(), run.toSpan());
            int bass = runBassPitchClass(histograms, run);
            // How far the winner beat whatever came second — a different
            // question from how well it fitted.
            double winner = run.state() == NO_CHORD_STATE
                    ? NO_CHORD_FIT
                    : scoreOver(TEMPLATES.get(run.state()), aggregate, bass);
            double separation = Math.clamp(
                    (winner - runnerUpScore(run.state(), aggregate, bass)) / DECISIVE_MARGIN, 0, 1);

            Chord chord;
            if (run.state() == NO_CHORD_STATE) {
                // How sure we are that there is nothing here is how badly the
                // best chord fitted: silence scores every chord at zero and is
                // therefore certain, a passage that nearly stated a chord is not.
                // No ceiling, because silence is read from the file rather than
                // inferred from it.
                chord = Chord.noChord(startSeconds, endSeconds,
                                Confidence.clamped((1 - bestFit(aggregate)) * separation))
                        .quantizedTo(startBeat, endBeat);
            } else {
                anyChord = true;
                Template template = TEMPLATES.get(run.state());
                boolean flats = spellWithFlats(keys, startSeconds);
                chord = Chord.ofSeconds(spell(template.root(), flats), template.quality(),
                                startSeconds, endSeconds,
                                Confidence.clamped(VOCABULARY_CEILING
                                        * fit(template, aggregate) * separation))
                        .quantizedTo(startBeat, endBeat);
                // A slash only for an inversion: a bass outside the chord is
                // either a passing note or a genuine C/D, indistinguishable
                // here — and Chord.pitchClasses() omits the bass, so a C/D
                // would promise a note the model does not report (#134).
                if (bass >= 0 && bass != template.root() && isChordTone(template, bass)) {
                    chord = chord.withBass(spell(bass, flats));
                }
            }
            confidenceTotal += chord.confidence().value();
            chords.add(chord);
        }

        if (!anyChord) {
            // Nothing but silence, percussion, a drone or a bare line. Saying so
            // by returning nothing is more useful than a chart of N.C. bars.
            return ChordProgression.empty();
        }
        return new ChordProgression(chords,
                Confidence.clamped(chords.isEmpty() ? 0 : confidenceTotal / chords.size()));
    }


    /**
     * A template's score over a whole run, which is {@link #emission} with the
     * run's bass rather than a span's.
     */
    private static double scoreOver(Template template, Histogram aggregate, int bass) {
        double score = fit(template, aggregate) - template.prior();
        return aggregate.distinctTones() >= MIN_CHORD_TONES && bass == template.root()
                ? score + ROOT_BASS_BONUS : score;
    }

    /**
     * The best any state other than the winner manages over a run.
     *
     * <p>"No chord" counts as a rival to a chord, so a label that barely beats
     * silence is reported as the guess it is. It has no rival of its own beyond
     * the chords, which is why the seed differs.
     */
    private static double runnerUpScore(int winner, Histogram aggregate, int bass) {
        double best = winner == NO_CHORD_STATE ? Double.NEGATIVE_INFINITY : NO_CHORD_FIT;
        for (int state = 0; state < TEMPLATES.size(); state++) {
            if (state != winner) {
                best = Math.max(best, scoreOver(TEMPLATES.get(state), aggregate, bass));
            }
        }
        return best;
    }

    /** The best fit any chord manages over an aggregate, used to score no-chord spans. */
    private static double bestFit(Histogram aggregate) {
        double best = 0;
        for (Template template : TEMPLATES) {
            best = Math.max(best, fit(template, aggregate));
        }
        return best;
    }

    private static boolean isChordTone(Template template, int pitchClass) {
        for (int tone : template.pitchClasses()) {
            if (tone == pitchClass) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bass of a whole run, which is a slash chord only if it holds
     * throughout.
     *
     * <p>Every span in the run has to agree. A bass that moves -- the ordinary
     * case, where the line walks around the root -- gives no single answer, and
     * printing a slash for the note that happened to be lowest at the downbeat
     * would litter a chart with inversions the music does not have.
     */
    private static int runBassPitchClass(Histogram[] histograms, Run run) {
        int bass = histograms[run.fromSpan()].bassPitchClass();
        for (int s = run.fromSpan() + 1; s < run.toSpan(); s++) {
            if (histograms[s].bassPitchClass() != bass) {
                return -1;
            }
        }
        return bass;
    }

    /**
     * Whether a chord starting at a given time should be spelled with flats.
     *
     * <p>Read from the file's own key signature where there is one. This is the
     * one spelling decision the symbolic path can make without guessing: a MIDI
     * key signature of two flats says B flat major, and printing a B flat chord
     * as A sharp in that key would be arithmetically right and visibly wrong.
     */
    private static boolean spellWithFlats(List<Key> keys, double seconds) {
        for (Key key : keys) {
            if (seconds >= key.startSeconds() && seconds < key.endSeconds()) {
                return key.isFlatKey();
            }
        }
        return !keys.isEmpty() && keys.get(0).isFlatKey();
    }

    /** A pitch class as a written root, in the octave the chord model uses for one. */
    private static PitchSpelling spell(int pitchClass, boolean flats) {
        int midiPitch = 60 + Math.floorMod(pitchClass, 12);
        return flats ? PitchSpelling.ofMidiPitchFlat(midiPitch)
                : PitchSpelling.ofMidiPitchSharp(midiPitch);
    }
}
