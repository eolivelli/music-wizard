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

package dev.olivelli.musicwizard.teacher;

import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.MidiFixtures.SequenceBuilder;
import dev.olivelli.musicwizard.testkit.MidiFixtures.SequenceBuilder.PartBuilder;
import java.util.Random;
import javax.sound.midi.Sequence;

/**
 * Compiles a spec into a MIDI arrangement: by default the full band — drums,
 * bass, comping and (unless the spec says {@code melody: none}) a generated
 * melody — and, where the spec asks for it, a sustained pad or nothing at all
 * under the melody.
 *
 * <p>Deterministic by construction: one {@link Random} seeded from the spec is
 * consumed in a fixed order, so the same spec always compiles to the same
 * bytes. Positions stay on the exact tick grid — velocities are humanized,
 * timing is not, because the beat grid <em>is</em> the ground truth these
 * packages exist to carry. Swing is written as real triplet positions, which
 * the tick grid holds exactly.
 *
 * <p>A style is a set of patterns of a particular bar length and feel, so a
 * meter is not a parameter a style can be relaxed into: {@link #isArranged}
 * lists the pairs written, and an unlisted one is refused rather than barred at
 * a length nothing wrote (#702). What separates a compound bar from a shuffle
 * here is only whether the middle of each triplet sounds — the shuffle's
 * patterns write both, over the same beats.
 */
public final class Arranger {

    private static final int KICK = 36;
    private static final int SIDE_STICK = 37;
    private static final int SNARE = 38;
    private static final int CLOSED_HAT = 42;
    private static final int OPEN_HAT = 46;
    private static final int CRASH = 49;
    private static final int RIDE = 51;

    private static final double TICK = 1.0 / MidiFixtures.TICKS_PER_QUARTER;

    /** GM "warm pad": slow attack, no transient of its own to be heard as an onset. */
    private static final int PAD_PROGRAM = 89;

    private static final int PAD_VELOCITY = 44;

    /** Subdivisions of a counted beat in compound time, which is what makes it compound. */
    private static final int COMPOUND_DIVISION = 3;

    private static final TimeSignature TWELVE_EIGHT = new TimeSignature(12, 8);

    private final SampleSpec spec;
    private final Random rng;
    private final Voicing voicing;
    private final int formLength;
    private final double barBeats;

    private PartBuilder melody;
    private PartBuilder comp;
    private PartBuilder bass;
    private PartBuilder drums;

    private Arranger(SampleSpec spec) {
        if (!isArranged(spec.style(), spec.meter())) {
            throw new IllegalArgumentException(
                    spec.style().id() + " is not arranged in " + spec.meter());
        }
        if (spec.melodyProgram() != null && !TimeSignature.FOUR_FOUR.equals(spec.meter())) {
            throw new IllegalArgumentException(
                    "the melody generator writes bars of 4/4 only, so " + spec.meter()
                            + " needs 'melody: none' (#715)");
        }
        this.spec = spec;
        this.rng = new Random(spec.seed());
        this.voicing = new Voicing(spec.compVoicing());
        // Crash placement and fills follow the form: 12-bar for grids built of
        // 12-bar choruses, 8-bar phrases otherwise.
        this.formLength = spec.bars().size() % 12 == 0 ? 12 : 8;
        this.barBeats = spec.meter().quarterBeatsPerBar();
    }

    /**
     * The style and meter pairs that have patterns written for them.
     *
     * <p>Each pair beyond 4/4 exists for a benchmark that has to be true by
     * construction: the waltz for a three-pulse bar, the compound ballad for a
     * bar whose every subdivision sounds, and the compound shuffle so that
     * #701's pair differs in one thing.
     */
    private static boolean isArranged(SampleSpec.Style style, TimeSignature meter) {
        if (TimeSignature.FOUR_FOUR.equals(meter)) {
            return true;
        }
        return switch (style) {
            case POP_BALLAD -> TimeSignature.THREE_FOUR.equals(meter)
                    || TimeSignature.SIX_EIGHT.equals(meter);
            case ROCKNROLL_SHUFFLE -> TWELVE_EIGHT.equals(meter);
            default -> false;
        };
    }

    /** The bar line, in quarter beats from the start. */
    private double barStart(int bar) {
        return bar * barBeats;
    }

    public static Sequence arrange(SampleSpec spec) {
        return new Arranger(spec).build();
    }

    private Sequence build() {
        SequenceBuilder builder = MidiFixtures.sequence()
                .name(spec.title())
                .tempo(spec.tempoBpm())
                .timeSignature(spec.meter().numerator(), spec.meter().denominator())
                .keySignature(spec.sharpsOrFlats(), spec.mode());

        MelodyGenerator melodyGenerator = null;
        if (spec.melodyProgram() != null) {
            melody = builder.part("Melody", 0).program(spec.melodyProgram());
            melodyGenerator = new MelodyGenerator(spec);
        }
        switch (spec.accompaniment()) {
            case FULL -> {
                comp = builder.part(compName(), 1).program(compProgram());
                bass = builder.part("Bass", 2).program(33);
                drums = builder.part("Drum Kit", MidiFixtures.DRUM_CHANNEL);
            }
            case PAD -> comp = builder.part("Pad", 1).program(PAD_PROGRAM);
            case NONE -> {
            }
        }

        for (int bar = 0; bar < spec.bars().size(); bar++) {
            switch (spec.accompaniment()) {
                case FULL -> band(bar);
                case PAD -> pad(bar);
                case NONE -> {
                }
            }
            if (melodyGenerator != null) {
                melodyGenerator.writeBar(melody, bar, rng);
            }
        }
        return builder.build();
    }

    private void band(int bar) {
        switch (spec.style()) {
            case POP_BALLAD -> {
                if (TimeSignature.THREE_FOUR.equals(spec.meter())) {
                    waltzComp(bar);
                    waltzBass(bar);
                    waltzDrums(bar);
                } else if (spec.meter().isCompound()) {
                    compoundComp(bar);
                    compoundBass(bar);
                    compoundDrums(bar);
                } else {
                    balladComp(bar);
                    balladBass(bar);
                    balladDrums(bar);
                }
            }
            case POP_ROCK -> {
                popRockComp(bar);
                popRockBass(bar);
                popRockDrums(bar);
            }
            case HIPHOP_BOOM_BAP -> {
                boomBapComp(bar);
                boomBapBass(bar);
                boomBapDrums(bar);
            }
            case ROCKNROLL_SHUFFLE -> {
                shuffleComp(bar);
                shuffleBass(bar);
                shuffleDrums(bar);
            }
        }
    }

    /**
     * One sustained voicing per chord, well below the melody's velocity: enough
     * for the harmony stages to have something to read, quiet and static enough
     * that the loudest thing at any moment is the melody.
     */
    private void pad(int bar) {
        double start = barStart(bar);
        if (spec.bars().get(bar).second() != null) {
            padChord(start, barBeats / 2, chordAt(bar, 0));
            padChord(start + barBeats / 2, barBeats / 2, chordAt(bar, barBeats / 2));
        } else {
            padChord(start, barBeats, chordAt(bar, 0));
        }
    }

    private void padChord(double beat, double duration, ChordSymbol chord) {
        for (int pitch : voicing.of(chord)) {
            comp.note(beat, duration, pitch, humanize(PAD_VELOCITY, 3));
        }
    }

    private String compName() {
        return switch (spec.style()) {
            case POP_BALLAD -> "Piano";
            case POP_ROCK, ROCKNROLL_SHUFFLE -> "Guitar";
            case HIPHOP_BOOM_BAP -> "Electric Piano";
        };
    }

    private int compProgram() {
        return switch (spec.style()) {
            case POP_BALLAD -> 0;         // acoustic grand
            case POP_ROCK -> 27;          // clean electric guitar
            case HIPHOP_BOOM_BAP -> 4;    // electric piano
            case ROCKNROLL_SHUFFLE -> 26; // jazz electric guitar
        };
    }

    private ChordSymbol chordAt(int bar, double offset) {
        return spec.bars().get(bar).chordAt(offset, spec.meter());
    }

    // ------------------------------------------------------------- pop ballad

    private void balladComp(int bar) {
        // Broken-chord eighths climbing through the voicing, let ring.
        double start = barStart(bar);
        int[] shape = arpeggioShape(chordAt(bar, 0));
        for (int eighth = 0; eighth < 8; eighth++) {
            double offset = eighth * 0.5;
            if (eighth == 4 && spec.bars().get(bar).second() != null) {
                shape = arpeggioShape(chordAt(bar, offset));
            }
            comp.note(start + offset, eighth == 7 ? 0.5 : 1.0,
                    shape[eighth % shape.length], humanize(58, 6));
        }
    }

    private void balladBass(int bar) {
        double start = barStart(bar);
        ChordSymbol first = chordAt(bar, 0);
        bass.note(start, 2, bassRoot(first), humanize(78, 5));
        if (spec.bars().get(bar).second() != null) {
            bass.note(start + 2, 2, bassRoot(chordAt(bar, 2)), humanize(74, 5));
        } else {
            bass.note(start + 2, 2, bassRoot(first) + first.fifthInterval(),
                    humanize(70, 5));
        }
    }

    private void balladDrums(int bar) {
        double start = barStart(bar);
        if (bar % (formLength * 2) == 0) {
            drums.note(start, 1, CRASH, humanize(58, 4));
        }
        for (int eighth = 0; eighth < 8; eighth++) {
            drums.note(start + eighth * 0.5, 0.25, CLOSED_HAT,
                    humanize(eighth % 2 == 0 ? 46 : 36, 5));
        }
        drums.note(start, 0.5, KICK, humanize(72, 4));
        drums.note(start + 2, 0.5, KICK, humanize(62, 4));
        drums.note(start + 1, 0.5, SIDE_STICK, humanize(58, 4));
        drums.note(start + 3, 0.5, SIDE_STICK, humanize(58, 4));
    }

    // ------------------------------------------------------------- pop waltz

    /** The waltz's afterbeats: the chord on two and on three, beat one the bass's. */
    private void waltzComp(int bar) {
        double start = barStart(bar);
        for (int beat = 1; beat < spec.meter().numerator(); beat++) {
            for (int pitch : voicing.of(chordAt(bar, beat))) {
                comp.note(start + beat, 0.9, pitch, humanize(beat == 1 ? 62 : 56, 5));
            }
        }
    }

    private void waltzBass(int bar) {
        double start = barStart(bar);
        bass.note(start, 1, bassRoot(chordAt(bar, 0)), humanize(84, 5));
        if (spec.bars().get(bar).second() != null) {
            bass.note(start + 2, 1, bassRoot(chordAt(bar, 2)), humanize(78, 5));
        }
    }

    /**
     * Kick on one, side stick on the afterbeats, and the hat on the counted
     * beat and nowhere else — nothing sounds between two beats, so the pulse
     * the tracker locks to has no rival below it.
     */
    private void waltzDrums(int bar) {
        double start = barStart(bar);
        if (bar % (formLength * 2) == 0) {
            drums.note(start, 1, CRASH, humanize(58, 4));
        }
        for (int beat = 0; beat < spec.meter().numerator(); beat++) {
            drums.note(start + beat, 0.25, CLOSED_HAT, humanize(beat == 0 ? 48 : 38, 5));
        }
        drums.note(start, 0.5, KICK, humanize(76, 4));
        drums.note(start + 1, 0.5, SIDE_STICK, humanize(58, 4));
        drums.note(start + 2, 0.5, SIDE_STICK, humanize(54, 4));
    }

    // -------------------------------------------------------- compound ballad

    /**
     * Every subdivision of every counted beat, struck: the broken chord runs
     * through the eighths of the bar and the hat below doubles them evenly.
     * That is the whole point of the compound packages — a bar counted in two
     * whose pulse audibly divides in three (#701, #712).
     */
    private void compoundComp(int bar) {
        double start = barStart(bar);
        int[] shape = arpeggioShape(chordAt(bar, 0));
        int eighths = spec.meter().numerator();
        double eighth = spec.meter().beatUnitQuarters() / COMPOUND_DIVISION;
        for (int position = 0; position < eighths; position++) {
            double offset = position * eighth;
            if (position == eighths / 2 && spec.bars().get(bar).second() != null) {
                shape = arpeggioShape(chordAt(bar, offset));
            }
            comp.note(start + offset, position == eighths - 1 ? eighth : 2 * eighth,
                    shape[position % shape.length],
                    humanize(position % COMPOUND_DIVISION == 0 ? 60 : 55, 6));
        }
    }

    private void compoundBass(int bar) {
        double start = barStart(bar);
        double beat = spec.meter().beatUnitQuarters();
        ChordSymbol first = chordAt(bar, 0);
        bass.note(start, beat, bassRoot(first), humanize(80, 5));
        if (spec.bars().get(bar).second() != null) {
            bass.note(start + beat, beat, bassRoot(chordAt(bar, beat)), humanize(76, 5));
        } else {
            bass.note(start + beat, beat, bassRoot(first) + first.fifthInterval(),
                    humanize(72, 5));
        }
    }

    private void compoundDrums(int bar) {
        double start = barStart(bar);
        if (bar % (formLength * 2) == 0) {
            drums.note(start, 1, CRASH, humanize(58, 4));
        }
        double eighth = spec.meter().beatUnitQuarters() / COMPOUND_DIVISION;
        for (int position = 0; position < spec.meter().numerator(); position++) {
            drums.note(start + position * eighth, 0.25, CLOSED_HAT, humanize(44, 5));
        }
        drums.note(start, 0.5, KICK, humanize(78, 4));
        drums.note(start + spec.meter().beatUnitQuarters(), 0.5, SIDE_STICK, humanize(60, 4));
    }

    // --------------------------------------------------------------- pop rock

    private void popRockComp(int bar) {
        double start = barStart(bar);
        // Strummed hits; the accent pattern is the backbone of the groove.
        double[][] hits = {{0, 1.0, 1}, {1, 0.5, 0}, {1.5, 1.0, 0}, {2.5, 0.5, 1}, {3, 1.0, 0}};
        for (double[] hit : hits) {
            strum(comp, start + hit[0], hit[1], voicing.of(chordAt(bar, hit[0])),
                    humanize(hit[2] > 0 ? 76 : 62, 6));
        }
    }

    private void popRockBass(int bar) {
        // Driving eighth-note roots, the octave above on the last pair now and then.
        double start = barStart(bar);
        for (int eighth = 0; eighth < 8; eighth++) {
            double offset = eighth * 0.5;
            int root = bassRoot(chordAt(bar, offset));
            int pitch = eighth >= 6 && rng.nextInt(3) == 0 ? root + 12 : root;
            bass.note(start + offset, 0.5, pitch, humanize(eighth % 2 == 0 ? 84 : 74, 5));
        }
    }

    private void popRockDrums(int bar) {
        double start = barStart(bar);
        boolean fillBar = bar % formLength == formLength - 1;
        if (bar % formLength == 0) {
            drums.note(start, 1, CRASH, humanize(88, 4));
        }
        for (int eighth = 0; eighth < 8; eighth++) {
            if (fillBar && eighth >= 6) {
                break; // the fill owns beat four
            }
            boolean open = eighth == 7 && bar % 4 == 3;
            drums.note(start + eighth * 0.5, 0.25, open ? OPEN_HAT : CLOSED_HAT,
                    humanize(eighth % 2 == 0 ? 66 : 52, 5));
        }
        drums.note(start, 0.5, KICK, humanize(92, 4));
        drums.note(start + 2.5, 0.5, KICK, humanize(84, 4));
        if (rng.nextInt(4) == 0) {
            drums.note(start + 3.5, 0.5, KICK, humanize(78, 4));
        }
        drums.note(start + 1, 0.5, SNARE, humanize(94, 4));
        drums.note(start + 3, 0.5, SNARE, humanize(94, 4));
        if (fillBar) {
            for (int sixteenth = 0; sixteenth < 4; sixteenth++) {
                drums.note(start + 3 + sixteenth * 0.25, 0.25, SNARE,
                        humanize(64 + sixteenth * 8, 4));
            }
        }
    }

    // ------------------------------------------------------- hip-hop boom bap

    private void boomBapComp(int bar) {
        double start = barStart(bar);
        if (spec.bars().get(bar).second() != null) {
            softChord(start, 2, chordAt(bar, 0), 66);
            softChord(start + 2, 2, chordAt(bar, 2), 62);
        } else {
            softChord(start, rng.nextInt(3) == 0 ? 2.5 : 4.0, chordAt(bar, 0), 66);
            if (rng.nextInt(3) == 0) {
                softChord(start + 2.5, 1.0, chordAt(bar, 2.5), 56);
            }
        }
    }

    private void softChord(double beat, double duration, ChordSymbol chord, int velocity) {
        for (int pitch : voicing.of(chord)) {
            comp.note(beat, duration, pitch, humanize(velocity, 5));
        }
    }

    private void boomBapBass(int bar) {
        double start = barStart(bar);
        ChordSymbol chord = chordAt(bar, 0);
        int root = bassRoot(chord);
        bass.note(start, 1.5, root, humanize(88, 5));
        bass.note(start + 1.5, 1.0, root, humanize(72, 5));
        int pitch = spec.bars().get(bar).second() == null
                ? root + chord.fifthInterval()
                : bassRoot(chordAt(bar, 2.5));
        bass.note(start + 2.5, 1.5, pitch, humanize(80, 5));
    }

    private void boomBapDrums(int bar) {
        double start = barStart(bar);
        for (int eighth = 0; eighth < 8; eighth++) {
            drums.note(start + eighth * 0.5, 0.25, CLOSED_HAT,
                    humanize(eighth % 2 == 0 ? 64 : 42, 5));
        }
        drums.note(start, 0.5, KICK, humanize(98, 3));
        if (rng.nextInt(3) > 0) {
            drums.note(start + 1.75, 0.25, KICK, humanize(76, 4));
        }
        drums.note(start + 2.5, 0.5, KICK, humanize(90, 3));
        drums.note(start + 1, 0.5, SNARE, humanize(100, 3));
        drums.note(start + 3, 0.5, SNARE, humanize(100, 3));
    }

    // -------------------------------------------------- rock and roll shuffle

    /**
     * Where a swung beat is struck and for how long, as fractions of the beat:
     * the first two thirds of the triplet and the last, the middle silent.
     */
    private static final double[][] SWUNG = {{0, 2.0 / 3}, {2.0 / 3, 1.0 / 3}};

    /**
     * The same beat with its middle third sounding. It is the one thing that
     * separates the compound package from the shuffle one, and whether that
     * difference is legible over the corpus's shuffles is #701.
     */
    private static final double[][] COMPOUND_BEAT = {
            {0, 1.0 / 3}, {1.0 / 3, 1.0 / 3}, {2.0 / 3, 1.0 / 3}};

    private double[][] shuffleBeat() {
        return spec.meter().isCompound() ? COMPOUND_BEAT : SWUNG;
    }

    private void shuffleComp(int bar) {
        // The boogie pattern: root-plus-fifth on the bar's first half of beats,
        // root-plus-sixth on the second, on every subdivision the feel strikes.
        double start = barStart(bar);
        double beatQuarters = spec.meter().beatUnitQuarters();
        int beats = spec.meter().beatsPerBar();
        double[][] subdivisions = shuffleBeat();
        for (int beat = 0; beat < beats; beat++) {
            ChordSymbol chord = chordAt(bar, beat * beatQuarters);
            int low = boogieRoot(chord);
            int upper = beat < beats / 2 ? chord.fifthInterval() : boogieSixth(chord);
            for (int i = 0; i < subdivisions.length; i++) {
                double at = start + beat * beatQuarters + subdivisions[i][0] * beatQuarters;
                double duration = subdivisions[i][1] * beatQuarters;
                comp.note(at, duration, low, humanize(i == 0 ? 72 : 58, 5))
                        .note(at, duration, low + upper, humanize(i == 0 ? 68 : 54, 5));
            }
        }
    }

    private void shuffleBass(int bar) {
        // One note to the counted beat, walking up the chord: root, third,
        // fifth, sixth.
        double start = barStart(bar);
        double beatQuarters = spec.meter().beatUnitQuarters();
        for (int beat = 0; beat < spec.meter().beatsPerBar(); beat++) {
            ChordSymbol chord = chordAt(bar, beat * beatQuarters);
            int root = bassRoot(chord);
            int step = switch (beat) {
                case 0 -> 0;
                case 1 -> chord.thirdInterval();
                case 2 -> chord.fifthInterval();
                default -> boogieSixth(chord);
            };
            bass.note(start + beat * beatQuarters, beatQuarters, root + step, humanize(84, 5));
        }
    }

    private void shuffleDrums(int bar) {
        double start = barStart(bar);
        double beatQuarters = spec.meter().beatUnitQuarters();
        int beats = spec.meter().beatsPerBar();
        double[][] subdivisions = shuffleBeat();
        boolean fillBar = bar % formLength == formLength - 1;
        if (bar % formLength == 0) {
            drums.note(start, 1, CRASH, humanize(84, 4));
        }
        for (int beat = 0; beat < beats; beat++) {
            for (int i = 0; i < subdivisions.length; i++) {
                if (i > 0 && fillBar && beat == beats - 1) {
                    break; // the fill owns the last beat
                }
                // Half a beat, or to the next strike where that comes first:
                // the ride is one voice, and the same pitch struck again before
                // its own note-off leaves the file's note pairing ambiguous.
                drums.note(start + beat * beatQuarters + subdivisions[i][0] * beatQuarters,
                        Math.min(beatQuarters / 2, subdivisions[i][1] * beatQuarters),
                        RIDE, humanize(i == 0 ? 74 : 56, 5));
            }
        }
        drums.note(start, 0.5, KICK, humanize(88, 4));
        drums.note(start + 2 * beatQuarters, 0.5, KICK, humanize(84, 4));
        drums.note(start + beatQuarters, 0.5, SNARE, humanize(88, 4));
        drums.note(start + 3 * beatQuarters, 0.5, SNARE, humanize(88, 4));
        if (fillBar) {
            // A triplet drag into the next bar line.
            for (int third = 0; third < COMPOUND_DIVISION; third++) {
                drums.note(
                        start + (beats - 1) * beatQuarters
                                + third * beatQuarters / COMPOUND_DIVISION,
                        beatQuarters / COMPOUND_DIVISION, SNARE,
                        humanize(62 + third * 10, 4));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Spreads a chord's onsets two ticks apart, so the attack is not machine-simultaneous. */
    private void strum(PartBuilder part, double beat, double duration, int[] pitches,
                       int velocity) {
        for (int i = 0; i < pitches.length; i++) {
            part.note(beat + i * 2 * TICK, duration, pitches[i],
                    Math.clamp(velocity - i * 2, 1, 127));
        }
    }

    /** A bass root in the octave and a half above low E. */
    private static int bassRoot(ChordSymbol chord) {
        return 28 + Math.floorMod(chord.rootPitchClass() - 4, 12);
    }

    /** A boogie-pattern root around the guitar's low register. */
    private static int boogieRoot(ChordSymbol chord) {
        return 40 + Math.floorMod(chord.rootPitchClass() - 4, 12);
    }

    /** The boogie sixth: the major sixth, or the seventh over a minor chord. */
    private static int boogieSixth(ChordSymbol chord) {
        return chord.thirdInterval() == 3 ? 10 : 9;
    }

    /** The ballad arpeggio's note cycle, low to high, from the current voicing. */
    private int[] arpeggioShape(ChordSymbol chord) {
        int[] voiced = voicing.of(chord);
        int[] shape = new int[4];
        shape[0] = voiced[0] - 12;
        shape[1] = voiced.length > 3 ? voiced[1] : voiced[0];
        shape[2] = voiced[voiced.length - 2];
        shape[3] = voiced[voiced.length - 1];
        return shape;
    }

    private int humanize(int velocity, int spread) {
        return Math.clamp(velocity + rng.nextInt(2 * spread + 1) - spread, 1, 127);
    }

    /**
     * Places chord tones in the comping register with minimal movement from the
     * previous chord, which is what a player's hand does.
     *
     * <p>A spec asking for {@link SampleSpec.CompVoicing#ROOTLESS_MAJ7} gets
     * third-fifth-seventh above middle C on its major sevenths, and the hand
     * still moves as the close voicing would have moved it: the substitution
     * is what the pair under test varies, so it must not also displace the
     * chords after it, which are voiced from history rather than from
     * themselves (#589, #611).
     */
    private static final class Voicing {

        private final SampleSpec.CompVoicing mode;
        private double center = 64;

        Voicing(SampleSpec.CompVoicing mode) {
            this.mode = mode;
        }

        int[] of(ChordSymbol chord) {
            int[] close = close(chord);
            if (mode == SampleSpec.CompVoicing.ROOTLESS_MAJ7
                    && chord.quality() == ChordSymbol.Quality.MAJOR_SEVENTH) {
                return rootless(chord);
            }
            return close;
        }

        private static int[] rootless(ChordSymbol chord) {
            int[] intervals = chord.quality().intervals();
            int third = (chord.rootPitchClass() + intervals[1]) % 12;
            while (third < 60) {
                third += 12;
            }
            return new int[] {
                third,
                third - intervals[1] + intervals[2],
                third - intervals[1] + intervals[3],
            };
        }

        private int[] close(ChordSymbol chord) {
            int[] pitchClasses = chord.pitchClasses();
            int[] pitches = new int[pitchClasses.length];
            double sum = 0;
            for (int i = 0; i < pitchClasses.length; i++) {
                int pitch = pitchClasses[i]
                        + 12 * (int) Math.round((center - pitchClasses[i]) / 12.0);
                while (pitch < 52) {
                    pitch += 12;
                }
                while (pitch > 79) {
                    pitch -= 12;
                }
                pitches[i] = pitch;
                sum += pitch;
            }
            java.util.Arrays.sort(pitches);
            center = sum / pitches.length;
            return pitches;
        }
    }
}
