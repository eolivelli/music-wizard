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

package dev.olivelli.musicwizard.testkit;

import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Standard MIDI files with answers known in advance.
 *
 * <p>The symbolic counterpart of {@link SignalFactory}. Where a synthetic signal
 * gives a DSP stage a tempo it can be measured against, a fixture MIDI file gives
 * the notation and arrangement stages an <em>exact</em> score to work from: every
 * position here is a whole number of ticks, so a beat position is not an estimate
 * that has to be compared within a tolerance but a value that either matches or
 * does not.
 *
 * <p>Two things the fixtures deliberately do that a first draft of a test never
 * does, because they are the two that catch real bugs:
 *
 * <ul>
 *   <li><b>They do not start at beat 0.</b> Every derivation of a musical
 *       position agrees exactly at the origin, so a fixture whose first note
 *       falls there cannot tell a correct tempo map from one that threw the
 *       phase away.
 *   <li><b>They change tempo and meter part-way through.</b> A constant-tempo
 *       fixture is converted correctly by a single multiplication, which is
 *       precisely the implementation that is wrong.
 * </ul>
 *
 * <p>Positions are given in quarter-note beats throughout, matching the rest of
 * the pipeline, and converted to ticks on the way out. That includes compound
 * meters: a 6/8 bar is three quarter beats here, exactly as it is in
 * {@link TimeSignature#quarterBeatsPerBar()}.
 */
public final class MidiFixtures {

    /**
     * Ticks per quarter note in the fixtures below.
     *
     * <p>480 is what nearly every sequencer writes, and it divides evenly by 2,
     * 3, 4, 5, 6 and 8, so triplets and sixteenths are whole numbers of ticks
     * rather than positions that have already been rounded before the code under
     * test sees them.
     */
    public static final int TICKS_PER_QUARTER = 480;

    /** The tempo a MIDI file is played at when it names none. */
    public static final double DEFAULT_TEMPO_BPM = 120;

    /** MIDI channel 10, counted from zero, which General MIDI reserves for drums. */
    public static final int DRUM_CHANNEL = 9;

    private static final int META_TEXT = 0x01;
    private static final int META_TRACK_NAME = 0x03;
    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIGNATURE = 0x58;
    private static final int META_KEY_SIGNATURE = 0x59;

    /** MIDI clocks in one quarter note, fixed by the specification. */
    private static final int CLOCKS_PER_QUARTER = 24;

    private MidiFixtures() {
    }

    /** A sequence at the standard {@value #TICKS_PER_QUARTER} ticks per quarter. */
    public static SequenceBuilder sequence() {
        return new SequenceBuilder(TICKS_PER_QUARTER);
    }

    /**
     * A sequence at a chosen tick resolution.
     *
     * <p>Worth varying in a test: an importer that hard-codes 480, or that
     * assumes ticks are directly comparable between files, passes everything
     * built from {@link #sequence()} alone.
     */
    public static SequenceBuilder sequence(int ticksPerQuarter) {
        return new SequenceBuilder(ticksPerQuarter);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Eight quarter notes rising from middle C, 4/4 at 120, starting at beat 0.
     *
     * <p>The smoke fixture: the one to reach for when a test needs a score at
     * all and does not care what is in it. It is deliberately the <em>only</em>
     * one that starts at the origin, so a test that wants to prove something
     * about timing has to name a fixture that does not.
     */
    public static Sequence cMajorScale() {
        SequenceBuilder builder = sequence()
                .name("C major scale")
                .tempo(120)
                .timeSignature(4, 4);
        int[] scale = {60, 62, 64, 65, 67, 69, 71, 72};
        SequenceBuilder.PartBuilder part = builder.part("Melody", 0);
        for (int i = 0; i < scale.length; i++) {
            part.note(i, 1, scale[i]);
        }
        return builder.build();
    }

    /**
     * Melody and bass over a two-bar lead-in, with the tempo changing mid-piece.
     *
     * <p>The fixture for anything that converts between seconds and beats. The
     * first note is at beat 4, so a conversion that quietly treats the first note
     * as the origin lands a bar out; the tempo halves at beat 8, so one that
     * multiplies by a single tempo lands progressively further out after it.
     *
     * <p>Two parts, on two channels, so part splitting has something to split.
     */
    public static Sequence leadInAndTempoChange() {
        SequenceBuilder builder = sequence()
                .name("Lead-in and tempo change")
                .tempo(120)
                .tempoAt(8, 60)
                .timeSignature(4, 4);
        SequenceBuilder.PartBuilder melody = builder.part("Melody", 0);
        SequenceBuilder.PartBuilder bass = builder.part("Bass", 1);
        for (int bar = 1; bar < 4; bar++) {
            double barStart = bar * 4;
            melody.note(barStart, 1, 72)
                    .note(barStart + 1, 1, 71)
                    .note(barStart + 2, 2, 69);
            bass.note(barStart, 2, 40).note(barStart + 2, 2, 47);
        }
        return builder.build();
    }

    /**
     * The full exercise: three parts, a lead-in, a tempo change and a meter
     * change from 4/4 into 6/8.
     *
     * <p>This is the fixture the MIDI import in #89 is specified against, and it
     * is built to break the two mistakes that produce a plausible wrong answer.
     * The meter change is to a <em>compound</em> meter, so an importer that reads
     * the numerator as a count of quarter-note beats bars the second half three
     * times too slowly; and the tempo change sits inside a sustained note, so an
     * importer that converts a duration with one tempo gets that note's length
     * wrong while every onset around it stays right.
     *
     * <p>The bass part is named and on its own channel, and the drum part is on
     * channel 10, so role inference has both of its safe signals present and a
     * third part that carries neither.
     */
    public static Sequence tempoAndMeterChange() {
        SequenceBuilder builder = sequence()
                .name("Tempo and meter change")
                // 4/4 for two bars, then 6/8 -- three quarter beats to the bar,
                // so bar 2 begins at quarter beat 8 and bar 3 at beat 11.
                .timeSignature(4, 4)
                .timeSignatureAt(8, 6, 8)
                .tempo(100)
                .tempoAt(8, 140)
                .keySignature(2, Mode.MAJOR);

        SequenceBuilder.PartBuilder melody = builder.part("Melody", 0);
        SequenceBuilder.PartBuilder bass = builder.part("Bass Guitar", 1);
        SequenceBuilder.PartBuilder drums = builder.part("Drum Kit", DRUM_CHANNEL);

        // Bar 0 is a lead-in: nothing sounds until beat 1.
        melody.note(1, 1, 74).note(2, 1, 76).note(3, 1, 78);
        // Straddles the tempo change at beat 8 on purpose.
        melody.note(7, 2, 74).note(9, 1, 71).note(10, 1, 69);
        bass.note(1, 3, 38).note(4, 4, 45).note(8, 3, 40);
        for (int i = 1; i < 11; i++) {
            drums.note(i, 0.5, 42);
        }
        return builder.build();
    }

    /**
     * A four-bar 6/8 melody, so compound time is exercised on its own.
     *
     * <p>A 6/8 bar holds three quarter beats and two counted beats. Nothing here
     * lands on a quarter-beat boundary except by accident: the notes are eighths
     * and dotted quarters, which is what the meter is for.
     */
    public static Sequence compoundTime() {
        SequenceBuilder builder = sequence()
                .name("Compound time")
                .tempo(120)
                .timeSignature(6, 8);
        SequenceBuilder.PartBuilder melody = builder.part("Melody", 0);
        // One bar is three quarter beats; the first bar is left empty as a rest
        // so the fixture does not begin at the origin.
        for (int bar = 1; bar < 4; bar++) {
            double barStart = bar * 3.0;
            melody.note(barStart, 0.5, 67)
                    .note(barStart + 0.5, 0.5, 69)
                    .note(barStart + 1.0, 0.5, 71)
                    .note(barStart + 1.5, 1.5, 72);
        }
        return builder.build();
    }

    /**
     * The I-V-vi-IV progression the audio track is verified on, as symbols.
     *
     * <p>Same music as the end-to-end audio fixture -- C, G, A minor, F, one
     * chord to the bar -- so a chord chart engraved from this file and one
     * engraved from the analysed recording can be compared directly. The chords
     * are block triads in an accompaniment part with a separate bass part below
     * them.
     */
    public static Sequence fourChordSong() {
        SequenceBuilder builder = sequence()
                .name("Four chords")
                .tempo(120)
                .timeSignature(4, 4)
                .keySignature(0, Mode.MAJOR);
        SequenceBuilder.PartBuilder keys = builder.part("Piano", 0);
        SequenceBuilder.PartBuilder bass = builder.part("Bass", 1);
        int[][] triads = {{60, 64, 67}, {59, 62, 67}, {60, 64, 69}, {60, 65, 69}};
        int[] roots = {36, 43, 45, 41};
        for (int bar = 0; bar < 8; bar++) {
            double barStart = 4 + bar * 4.0;
            keys.chord(barStart, 4, triads[bar % 4]);
            bass.note(barStart, 2, roots[bar % 4]).note(barStart + 2, 2, roots[bar % 4]);
        }
        return builder.build();
    }

    /**
     * The tempo a MIDI file actually holds when asked for a given one.
     *
     * <p>A tempo event carries whole microseconds per quarter note, so most
     * tempi are not representable: 140 BPM is 428571.43 microseconds and the file
     * stores 428571, which reads back as 140.00014 BPM. The error is inaudible
     * and it is not a rounding bug to be fixed -- it is what the format holds --
     * but a test that asserts an imported tempo is exactly 140 fails, and the
     * temptation is then to loosen the assertion until it passes, which loses the
     * exactness everywhere else.
     *
     * <p>So the expected value is computed here instead. 60, 100, 120 and 150 are
     * among the tempi that <em>are</em> exact; 140 and 90 are not.
     */
    public static double storedTempo(double beatsPerMinute) {
        if (!Double.isFinite(beatsPerMinute) || beatsPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "beatsPerMinute must be finite and positive, got: " + beatsPerMinute);
        }
        long microseconds = Math.round(60_000_000.0 / beatsPerMinute);
        if (microseconds < 1 || microseconds > 0xFFFFFF) {
            throw new IllegalArgumentException(
                    "a tempo of " + beatsPerMinute + " BPM is outside what a MIDI tempo event"
                            + " can express");
        }
        return 60_000_000.0 / microseconds;
    }

    /** Writes a sequence as a Standard MIDI File. */
    public static Path write(Sequence sequence, Path file) {
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(file, "file");
        // Type 1 whenever there is more than one track, which every builder
        // product is: the meta track is always present and always separate.
        // Type 0 would flatten the tracks into one, and part splitting would
        // then have nothing to split.
        int type = sequence.getTracks().length > 1 ? 1 : 0;
        if (!MidiSystem.isFileTypeSupported(type, sequence)) {
            throw new IllegalStateException(
                    "this JDK cannot write a type " + type + " MIDI file for this sequence");
        }
        try {
            MidiSystem.write(sequence, type, file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
        return file;
    }

    // ----------------------------------------------------------------- builder

    /**
     * Assembles a sequence from positions given in quarter-note beats.
     *
     * <p>Tempo, meter and key events go on a dedicated first track, which is
     * where a type 1 file conventionally keeps them and where an importer will
     * find them without depending on a note track's contents.
     *
     * <p>Reusable: {@link #build()} materialises a fresh {@link Sequence} each
     * time, so a fixture method can hand the same builder to two tests without
     * them sharing mutable state.
     */
    public static final class SequenceBuilder {

        private final int ticksPerQuarter;
        private final TreeMap<Long, Double> tempi = new TreeMap<>();
        private final TreeMap<Long, TimeSignature> meters = new TreeMap<>();
        private final TreeMap<Long, int[]> keys = new TreeMap<>();
        private final List<PartBuilder> parts = new ArrayList<>();
        private String name;

        private SequenceBuilder(int ticksPerQuarter) {
            if (ticksPerQuarter < 1 || ticksPerQuarter > 0x7FFF) {
                throw new IllegalArgumentException(
                        "ticksPerQuarter must be within 1..32767, got: " + ticksPerQuarter);
            }
            this.ticksPerQuarter = ticksPerQuarter;
        }

        /** Names the sequence, written as a track name on the meta track. */
        public SequenceBuilder name(String sequenceName) {
            this.name = Objects.requireNonNull(sequenceName, "sequenceName");
            return this;
        }

        /** Sets the tempo from the start, in quarter notes per minute. */
        public SequenceBuilder tempo(double beatsPerMinute) {
            return tempoAt(0, beatsPerMinute);
        }

        /**
         * Changes tempo at a quarter-beat position.
         *
         * @param beat           quarter-note beats from the start
         * @param beatsPerMinute quarter notes per minute from there on
         */
        public SequenceBuilder tempoAt(double beat, double beatsPerMinute) {
            // Validated through storedTempo so that a tempo the wire format
            // cannot carry is refused here rather than silently truncated into a
            // different one, and so that the two cannot disagree about which
            // tempi those are.
            storedTempo(beatsPerMinute);
            tempi.put(tickOf(beat), beatsPerMinute);
            return this;
        }

        /** Sets the meter from the start. */
        public SequenceBuilder timeSignature(int numerator, int denominator) {
            return timeSignatureAt(0, numerator, denominator);
        }

        /**
         * Changes meter at a quarter-beat position.
         *
         * <p>The position is in quarter beats, not bars: a 6/8 bar is three of
         * them, so a change two 4/4 bars in goes at beat 8.
         */
        public SequenceBuilder timeSignatureAt(double beat, int numerator, int denominator) {
            meters.put(tickOf(beat), new TimeSignature(numerator, denominator));
            return this;
        }

        /** Sets the key signature from the start. */
        public SequenceBuilder keySignature(int sharpsOrFlats, Mode mode) {
            return keySignatureAt(0, sharpsOrFlats, mode);
        }

        /**
         * Changes key signature at a quarter-beat position.
         *
         * @param sharpsOrFlats sharps when positive, flats when negative, -7..7
         */
        public SequenceBuilder keySignatureAt(double beat, int sharpsOrFlats, Mode mode) {
            Objects.requireNonNull(mode, "mode");
            if (sharpsOrFlats < -7 || sharpsOrFlats > 7) {
                throw new IllegalArgumentException(
                        "sharpsOrFlats must be within -7..7, got: " + sharpsOrFlats);
            }
            keys.put(tickOf(beat), new int[] {sharpsOrFlats, mode == Mode.MINOR ? 1 : 0});
            return this;
        }

        /**
         * Adds a part on its own track and channel.
         *
         * @param partName the track name, which is also what role inference reads
         * @param channel  MIDI channel counted from zero; {@value #DRUM_CHANNEL}
         *                 is the drum channel
         */
        public PartBuilder part(String partName, int channel) {
            Objects.requireNonNull(partName, "partName");
            if (channel < 0 || channel > 15) {
                throw new IllegalArgumentException(
                        "channel must be within 0..15, got: " + channel);
            }
            PartBuilder part = new PartBuilder(this, partName, channel);
            parts.add(part);
            return part;
        }

        /** Builds the sequence. Safe to call more than once. */
        public Sequence build() {
            Sequence sequence;
            try {
                sequence = new Sequence(Sequence.PPQ, ticksPerQuarter);
            } catch (InvalidMidiDataException e) {
                throw new IllegalStateException("PPQ sequences are always valid", e);
            }
            Track meta = sequence.createTrack();
            if (name != null) {
                meta.add(metaEvent(META_TRACK_NAME, name.getBytes(StandardCharsets.UTF_8), 0));
            }
            tempi.forEach((tick, bpm) -> {
                long microseconds = Math.round(60_000_000.0 / bpm);
                meta.add(metaEvent(META_TEMPO, new byte[] {
                        (byte) (microseconds >> 16), (byte) (microseconds >> 8),
                        (byte) microseconds}, tick));
            });
            meters.forEach((tick, meter) -> meta.add(metaEvent(META_TIME_SIGNATURE, new byte[] {
                    (byte) meter.numerator(),
                    (byte) Integer.numberOfTrailingZeros(meter.denominator()),
                    // Clocks per metronome click. Written from the meter's own
                    // counted beat so the file says what a metronome would do:
                    // 24 in 4/4, 36 for the dotted quarter of 6/8.
                    (byte) Math.clamp(
                            Math.round(CLOCKS_PER_QUARTER * meter.beatUnitQuarters()), 1, 255),
                    // Demisemiquavers per quarter note; 8 in every file anyone
                    // writes, and the one value a reader may safely assume.
                    (byte) 8}, tick)));
            keys.forEach((tick, signature) -> meta.add(metaEvent(META_KEY_SIGNATURE,
                    new byte[] {(byte) signature[0], (byte) signature[1]}, tick)));

            for (PartBuilder part : parts) {
                part.writeTo(sequence.createTrack());
            }
            return sequence;
        }

        /** Builds the sequence and writes it as a Standard MIDI File. */
        public Path writeTo(Path file) {
            return write(build(), file);
        }

        private long tickOf(double beat) {
            if (!Double.isFinite(beat) || beat < 0) {
                throw new IllegalArgumentException(
                        "beat must be finite and non-negative, got: " + beat);
            }
            long tick = Math.round(beat * ticksPerQuarter);
            // A position that is not a whole number of ticks would be rounded
            // here and then read back as a different beat, which turns a fixture
            // from ground truth into another estimate.
            if (Math.abs(tick - beat * ticksPerQuarter) > 1e-9) {
                throw new IllegalArgumentException(
                        "beat " + beat + " is not a whole number of ticks at "
                                + ticksPerQuarter + " ticks per quarter");
            }
            return tick;
        }

        /**
         * One part: a track, a channel and the notes on it.
         *
         * <p>Note positions and lengths are in quarter beats. The chaining
         * methods return this builder, and {@link #end()} returns the sequence
         * builder, so a fixture reads in the order it is written.
         */
        public static final class PartBuilder {

            private record NoteSpec(long tick, long durationTicks, int pitch, int velocity) { }

            private final SequenceBuilder owner;
            private final String partName;
            private final int channel;
            private final List<NoteSpec> notes = new ArrayList<>();
            private Integer program;
            private String text;

            private PartBuilder(SequenceBuilder owner, String partName, int channel) {
                this.owner = owner;
                this.partName = partName;
                this.channel = channel;
            }

            /** Sets a General MIDI program number, 0..127. */
            public PartBuilder program(int gmProgram) {
                if (gmProgram < 0 || gmProgram > 127) {
                    throw new IllegalArgumentException(
                            "program must be within 0..127, got: " + gmProgram);
                }
                this.program = gmProgram;
                return this;
            }

            /** Adds a free-text meta event at the start of the track. */
            public PartBuilder text(String value) {
                this.text = Objects.requireNonNull(value, "value");
                return this;
            }

            /** Adds a note at a default velocity. */
            public PartBuilder note(double beat, double durationBeats, int midiPitch) {
                return note(beat, durationBeats, midiPitch, 80);
            }

            /** Adds a note. */
            public PartBuilder note(double beat, double durationBeats, int midiPitch,
                                    int velocity) {
                if (midiPitch < 0 || midiPitch > 127) {
                    throw new IllegalArgumentException(
                            "midiPitch must be within 0..127, got: " + midiPitch);
                }
                if (velocity < 1 || velocity > 127) {
                    // Zero is not a velocity here: on the wire a note-on at
                    // velocity 0 is a note-off, so a fixture asking for one would
                    // silently produce a note that never sounds.
                    throw new IllegalArgumentException(
                            "velocity must be within 1..127, got: " + velocity);
                }
                if (!Double.isFinite(durationBeats) || durationBeats <= 0) {
                    throw new IllegalArgumentException(
                            "durationBeats must be finite and positive, got: " + durationBeats);
                }
                long tick = owner.tickOf(beat);
                long endTick = owner.tickOf(beat + durationBeats);
                if (endTick <= tick) {
                    throw new IllegalArgumentException(
                            "a note of " + durationBeats + " beats is shorter than one tick at "
                                    + owner.ticksPerQuarter + " ticks per quarter");
                }
                notes.add(new NoteSpec(tick, endTick - tick, midiPitch, velocity));
                return this;
            }

            /** Adds several notes sounding together. */
            public PartBuilder chord(double beat, double durationBeats, int... midiPitches) {
                Objects.requireNonNull(midiPitches, "midiPitches");
                for (int pitch : midiPitches) {
                    note(beat, durationBeats, pitch);
                }
                return this;
            }

            /** Returns to the sequence builder. */
            public SequenceBuilder end() {
                return owner;
            }

            /** Builds the sequence this part belongs to. */
            public Sequence build() {
                return owner.build();
            }

            private void writeTo(Track track) {
                track.add(metaEvent(META_TRACK_NAME,
                        partName.getBytes(StandardCharsets.UTF_8), 0));
                if (text != null) {
                    track.add(metaEvent(META_TEXT, text.getBytes(StandardCharsets.UTF_8), 0));
                }
                if (program != null) {
                    track.add(shortEvent(ShortMessage.PROGRAM_CHANGE, channel, program, 0, 0));
                }
                for (NoteSpec note : notes) {
                    track.add(shortEvent(ShortMessage.NOTE_ON, channel,
                            note.pitch(), note.velocity(), note.tick()));
                    track.add(shortEvent(ShortMessage.NOTE_OFF, channel,
                            note.pitch(), 0, note.tick() + note.durationTicks()));
                }
            }
        }
    }

    private static MidiEvent metaEvent(int type, byte[] data, long tick) {
        try {
            return new MidiEvent(new MetaMessage(type, data, data.length), tick);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException("invalid meta event of type " + type, e);
        }
    }

    private static MidiEvent shortEvent(int command, int channel, int data1, int data2,
                                        long tick) {
        try {
            return new MidiEvent(new ShortMessage(command, channel, data1, data2), tick);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(
                    "invalid MIDI message: command " + command + " channel " + channel, e);
        }
    }
}
