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
 * <p>The symbolic counterpart of {@code ChordEstimator}, and deliberately not a
 * reuse of it. That one matches chroma -- a twelve-bin summary of spectral
 * energy -- against binary templates and smooths the result with Viterbi,
 * because on the audio path a chroma bin is all there is. Here the input is a
 * set of notes, and three things are known that no chroma can carry:
 *
 * <ul>
 *   <li><em>How long each pitch sounds</em>, exactly. A histogram weighted by
 *       sounding duration says a half note matters four times as much as an
 *       eighth. Chroma weights by energy instead, which is dominated by attack
 *       transients and by whichever instrument is loudest.</li>
 *   <li><em>Which pitch it is</em>, rather than which bin it landed in. There is
 *       no tuning offset, no leakage into neighbouring bins, and no bass partial
 *       masquerading as an upper chord tone -- the defect NNLS chroma (#3)
 *       exists to attack on the audio side and which simply does not arise
 *       here.</li>
 *   <li><em>Which part it came from</em>. A note in a {@link PartRole#BASS}
 *       track is known to be bass rather than inferred to be, which is what
 *       makes a root reliable and a slash chord expressible.</li>
 * </ul>
 *
 * <p>Synthesising a chroma from the notes and feeding the audio estimator would
 * discard all three, and would then run the result through constants --
 * {@code EMISSION_SHARPNESS}, the 0.65-to-1 cosine rescale, the flat no-chord
 * template -- calibrated against the statistics of real chroma, which a
 * synthesised profile does not have. It would be a lossy re-encoding performed
 * so that a matcher could be reused.
 *
 * <h2>Method</h2>
 *
 * <p>One decision per counted beat, taken from {@link TimeSignature#beatsPerBar}
 * and {@link TimeSignature#beatUnitQuarters} so a 6/8 bar is two dotted-quarter
 * spans rather than six eighth-note ones. Every span is therefore beat-aligned
 * by construction, and every position in this class is in quarter-note beats;
 * seconds appear once, at the very end, through {@link TempoMap}.
 *
 * <p>Each span gets a duration-weighted pitch-class histogram, each candidate
 * chord a score, and the sequence a Viterbi pass whose only structure is that
 * changing chord costs something. That last part is not decoration: a per-span
 * argmax changes chord on every passing note and produces a chart nobody can
 * read. The cost is halved at a bar line, because that is where harmony
 * actually changes.
 *
 * <h2>What it declines to answer</h2>
 *
 * <p>No chord is a legitimate answer and this returns it: for silence, for a
 * drum-only file, and for any span whose winning chord never manages to sound
 * three of its own notes. A held drone and an unaccompanied melody both fall
 * into the last case, and naming a triad for either would be inventing harmony
 * that is not there. When <em>every</em> span comes back that way the result is
 * {@link ChordProgression#empty()} rather than a page of {@code N.C.}.
 */
public final class SymbolicChordEstimator {

    /**
     * What the chord's own notes actually sounding is worth, against explaining
     * the span's weight at 1.
     *
     * <p>Coverage alone cannot tell C major from C minor over a span holding
     * only a C: both explain all of it. Completeness breaks that tie towards the
     * chord that has more of itself present, and is what keeps a lone pitch
     * class from scoring as highly as a stated triad.
     *
     * <p>One constant rather than two, because only the ratio does anything --
     * {@link #FIT_SCALE} divides the pair back out. Two would look like two
     * degrees of freedom and be one, and a mutation of the numerator would then
     * be indistinguishable from a mutation of this.
     *
     * <p>What the tests pin is that the term is <em>there</em>: removing it
     * changes an answer. They do not pin the size, and it is worth saying so --
     * halving this to 0.25 leaves every fixture's chords identical. It is
     * calibrated only against {@link #NO_CHORD_FIT}, which is stated in terms
     * of it.
     */
    private static final double COMPLETENESS_WEIGHT = 0.5;

    /** So that a fit lands in 0..1 and the constants below read as fractions. */
    private static final double FIT_SCALE = 1 + COMPLETENESS_WEIGHT;

    /**
     * Share of a span's weight a chord tone must carry to count as sounding.
     *
     * <p>This is what stops a passing note from promoting a triad to a seventh:
     * a B touched for half a beat under four beats of C-E-G is 3% of the span
     * and does not make it a Cmaj7, while a B held alongside them is 25% and
     * does.
     */
    private static final double MIN_TONE_SHARE = 0.05;

    /**
     * The fit "no chord" is worth, on the same 0..1 scale as every other state.
     *
     * <p>Sits between the two shapes that have to be told apart. A span sounding
     * one note of a triad scores {@code (1 + 0.5/3) / 1.5 = 0.78}; a span
     * sounding nothing that triad contains scores 0. An arpeggio is the first
     * shape every beat and accumulates into a chord; a passage where nothing
     * fits is the second and comes back as no chord.
     *
     * <p>Worth being precise about what this does <em>not</em> decide, because
     * measuring it was a surprise. An unaccompanied melody does not fail here.
     * It chatters: a moving line takes every triad it passes through down to 0
     * and the decoder pays the change penalty to follow it, one chord per note.
     * What discards those is {@link #MIN_CHORD_TONES} afterwards, since a
     * one-beat run holding one pitch class states nothing. This threshold is
     * what keeps that from happening to an arpeggio as well.
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
     *
     * <p>Not 1.0. {@link Confidence#CERTAIN} is documented as a value read
     * directly from the file, and a template match is inferred from one. Nor is
     * a perfect fit even a guarantee of the right <em>label</em>: C6 and Am7 are
     * the same four pitch classes and only one of them is offered (#122), so a
     * span can state its chord exactly and still be given the other name.
     *
     * <p>A "no chord" over silence is exempt, because that one <em>is</em> read
     * from the file: nothing sounds, and nothing sounding is not an inference.
     *
     * <p>A judgement rather than a measurement, and deliberately the smaller half
     * of the story: how far the winner beat the runner-up is measured, and lives
     * in {@link #DECISIVE_MARGIN}. This one covers what a margin cannot see,
     * which is a rival that was never offered.
     */
    private static final double VOCABULARY_CEILING = 0.9;

    /**
     * The margin over the runner-up at which a label stops being a guess.
     *
     * <p>Measured rather than chosen, over the aggregate of a whole run. The
     * fixture is named for each row because the scores depend on the voicing and
     * on whether a bass note doubles a pitch class, so rows from different
     * fixtures are not one experiment:
     *
     * <pre>
     *   every bar of fourChordSong    C     1.100 vs C7   0.917  margin 0.183
     *   C-E-G-A over an A bass        Am7   1.000 vs Am   0.967  margin 0.033
     *   C-E-G-A over a C bass         C     0.967 vs Am7  0.900  margin 0.067
     *   C-E flat-G-B flat, keys alone Cm7   1.000 vs Cm   0.933  margin 0.067
     * </pre>
     *
     * <p>The first is a chord nobody would argue about and the second is the
     * C6-versus-Am7 ambiguity of #122, so 0.15 puts the first at full confidence
     * and the second at a fifth of it. The two seventh rows sit between, which is
     * right: whether to write Cm or Cm7 for a sustained C-E flat-G-B flat is a
     * question charts genuinely disagree on.
     *
     * <p>Four points is enough to show the quantity discriminates and not enough
     * to fix the threshold, which is part of what #124 is for.
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
     * The most spans this will decide over.
     *
     * <p>The Viterbi back-pointers are one byte per span per state, so 100,000
     * spans is about 13 MB -- fine for anything with music in it, since a
     * fifteen-minute piece is under 4,000. What the cap is really guarding
     * against is a file whose last event carries an enormous delta time: the
     * four-megabyte limit in {@link MidiTranscriber} bounds the note count but
     * not the tick position, so a handful of notes can declare a span of days.
     * Such a file still imports its notes; it just gets no chords, which is the
     * honest answer for music that is almost entirely silence.
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
     * The candidate chords: every quality in the vocabulary on every root.
     *
     * <p>Wider than the audio estimator's major-and-minor vocabulary, because
     * exact pitches support the distinction and a chroma bin does not: a seventh
     * either sounds for a quarter of the bar or it does not. Each quality
     * carries a prior it has to overcome, which is what stops the richer labels
     * winning by default -- every four-note template covers at least as much
     * weight as the triad inside it, so with no cost attached every major chord
     * would be reported as a major seventh.
     *
     * <p>Three of {@link ChordQuality}'s constants are absent. Sixths, because
     * C6 and Am7 are the same four pitch classes and differ only in which one is
     * the root, so offering both would put two states in permanent competition
     * to be settled by the bass alone. What a C6 voicing actually comes back as
     * therefore depends on its bass: over a C it is {@code C}, the sixth simply
     * unnamed, and over an A it is {@code Am7} -- neither wrong, both
     * unidiomatic. See #122.
     *
     * <p>The minor-major seventh for a different reason: it is rare enough that
     * the passing major seventh over a minor triad -- a suspension, or a line
     * descending from the octave -- is a likelier explanation of the same four
     * pitch classes than the chord is. C-E flat-G-B comes back as {@code Cm}.
     */
    private static List<Template> buildTemplates() {
        // Prior per quality, in fit units. Zero for the two qualities a chart is
        // mostly made of; a tone's worth for anything adding a fourth note; a
        // little for the triads that a couple of passing notes can counterfeit.
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
     *                   are dropped and everything else is used
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
     * Every pitched note in the score, in onset order.
     *
     * <p>All parts, not the one that looks like the accompaniment: the audio
     * rule that chords come from the full mix rather than from a separated stem
     * has the same force here, and a melody note is evidence about the harmony
     * whether or not it is a chord tone.
     *
     * <p>Drums are the exception, and not by analogy with that rule but because
     * a percussion note has no pitch to contribute. A General MIDI kit's note
     * numbers are instrument selectors -- 36 is a bass drum, 38 a snare, 42 a
     * closed hi-hat -- so reading them as pitches injects a C, a D and an F
     * sharp in whatever proportion the groove happens to use them.
     *
     * <p>Notes without musical time are skipped rather than converted, for the
     * reason {@link NoteTrack#notesBetweenBeats} gives: they have no position on
     * this axis and inventing one is what the optional fields exist to prevent.
     * Nothing on the MIDI path produces such a note, but this class is not
     * limited to that path.
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
     * The duration-weighted pitch-class histogram of every span.
     *
     * <p>A sweep rather than a scan per span: a note is added when the sweep
     * reaches it and dropped when it stops sounding, so the cost is the total
     * note-span overlap rather than notes times spans. A whole-note pedal under
     * a moving line is the case that makes the difference, and it is also the
     * case this whole class has to get right.
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
     * function of position in quarter-note beats.
     *
     * <p>Built once and integrated per span, rather than worked out inside each
     * span. It is a property of the music, not of a decision window, and
     * treating it as the latter cost more than tidiness: rebuilding it per span
     * re-read every note still sounding, which is quadratic in the notes
     * sounding at once and is not a shape {@link #MAX_SPANS} bounds -- that
     * bounds spans. Measured on a 256,000-note file well inside
     * {@code MidiTranscriber}'s four-megabyte import cap, the per-span form took
     * 39.6 seconds and this one takes under a second.
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
     * Where the bass of a span comes from.
     *
     * <p>A declared {@link PartRole#BASS} part where it is sounding, and the
     * bottom of the texture where it is not. Round 1 of the review on #115
     * replaced that fallback with a single decision for the whole score, on the
     * argument that switching definition bar by bar reads two different things
     * and calls both the bass. Round 2 showed the cure was worse: with no
     * fallback, a bass part resting for a break makes the bass <em>silent</em>,
     * the root bonus vanishes with it, and an unchanged four-bar C over held keys
     * splits into {@code C} then {@code Am7} at the bar the bassist stopped
     * playing. Declaring a bass part was strictly worse than not declaring one.
     *
     * <p>So the fallback is back, and the reason the round-1 change was made --
     * that rebuilding this per span was quadratic -- is answered by building both
     * lines once instead. Two sweeps, and the choice per span is which of two
     * answers to read.
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
            // How far the winner beat whatever came second, which is a different
            // question from how well it fitted. A perfectly stated triad clears
            // its nearest rival by 0.18 on the fixtures; the C6-versus-Am7
            // ambiguity clears its own by 0.03.
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
