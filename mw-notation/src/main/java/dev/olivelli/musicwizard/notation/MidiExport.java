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

package dev.olivelli.musicwizard.notation;

import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Writes a {@link Score} out as a Standard MIDI File.
 *
 * <p>The inverse of {@code MidiTranscriber}, and that is what it is for: a score
 * exported here and read back through the importer should be the same score.
 * That loop is the cheapest real test in the project, since nothing about it
 * needs audio, a model or an external binary — see {@code MidiRoundTripTest} in
 * {@code mw-it}, which lives there because a test of the pair cannot live in
 * either module without one of them depending on the other.
 *
 * <p><b>Not part of the notation layer's layout.</b> MIDI has no bars, no note
 * values and no ties; it has ticks and pitches. So this does not go through
 * {@link StaffLayout} — there is nothing in a laid-out staff that a MIDI file
 * wants, and running the music through an engraving decision on the way to a
 * playback format would make the file disagree with the score it came from
 * wherever the two rounded differently.
 *
 * <p><b>Musical time is required.</b> A note is written at the tick its
 * {@link Note#onsetBeat()} names, and a track holding a note without one is
 * refused rather than placed by its seconds. Two paths into the same tick — one
 * exact, one converted — is how a file comes to disagree with itself, and it is
 * the rule {@link StaffLayout} already applies. #149 is the request to export an
 * un-quantized transcription, which needs a decision about what it means rather
 * than a second conversion here.
 *
 * <p>What a MIDI file cannot hold, and therefore what this loses:
 *
 * <ul>
 *   <li><b>Pitch spelling.</b> A MIDI number is 61, not C sharp or D flat, and
 *       there is no event that says which. This is the whole reason the model
 *       carries pitch twice, and it is why MusicXML rather than MIDI is the
 *       export a notation program should be handed.
 *   <li><b>Part roles.</b> Percussion survives, because General MIDI's channel
 *       10 says so. A bass part survives only if it is <em>called</em> one,
 *       since that is the only other signal the importer trusts (#96). Every
 *       other role comes back as {@code OTHER}.
 *   <li><b>Tempo, exactly.</b> A tempo event holds whole microseconds per
 *       quarter note, so 140 BPM is stored as 428571 and reads back as
 *       140.00014. That is the format rather than a rounding bug;
 *       {@code MidiFixtures.storedTempo} computes what a given tempo becomes,
 *       and the right response to a round-trip test is to expect that number
 *       rather than to widen the tolerance until exactness is lost everywhere.
 *   <li><b>Chords, lyrics, sections and the beat grid.</b> None of them have an
 *       event. A chord chart is not a set of notes, and inventing one would be
 *       an arrangement (#10) rather than an export.
 * </ul>
 */
public final class MidiExport {

    /**
     * Ticks per quarter note in the written file.
     *
     * <p>Divisible by everything the quantizer produces, which is the only
     * requirement: 16 for the 64th-note grid, 3 for triplets, and their
     * multiples for the finer tuplet grids. {@value} covers a 64th note (48
     * ticks), a triplet 64th (16) and a duplet 64th of compound time (72), so
     * every position and length the notation layer can name lands on a whole
     * tick and nothing rounds.
     *
     * <p>It is also the number of MusicXML divisions per quarter that
     * {@link MusicXmlExport} uses, and for exactly the same reasons — one figure
     * derived twice would be two figures the day one of them moved.
     *
     * <p>A file header holds this in fifteen bits, so the ceiling is 32767 and
     * this is well inside it.
     */
    static final int TICKS_PER_QUARTER = MusicXmlExport.DIVISIONS_PER_QUARTER;

    private static final int META_TRACK_NAME = 0x03;
    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIGNATURE = 0x58;
    private static final int META_KEY_SIGNATURE = 0x59;

    /** General MIDI's percussion channel, counted from zero. */
    private static final int DRUM_CHANNEL = 9;

    /** Channels a MIDI file has. */
    private static final int CHANNELS = 16;

    /**
     * The velocity a note with none is struck at.
     *
     * <p>A note-on at velocity zero <em>is</em> a note-off, in every reader
     * including this project's own importer, so a note recorded at zero would
     * vanish rather than sound quietly. The model permits zero; MIDI does not
     * mean by it what the model does.
     */
    private static final int SILENT_NOTE_VELOCITY = 1;

    /** The largest value a tempo event can hold, in microseconds per quarter. */
    private static final int MAX_MICROSECONDS_PER_QUARTER = 0xFFFFFF;

    private MidiExport() {
    }

    /**
     * The score as a type 1 sequence.
     *
     * <p>Type 1, with the tempo map and the meter on a track of their own and
     * one track per part. Type 0 would flatten them into one and a re-import
     * would have nothing to split the parts on.
     *
     * @throws IllegalArgumentException if the score holds no notes, or holds a
     *         note without musical timing
     */
    public static Sequence toSequence(Score score) {
        Objects.requireNonNull(score, "score");
        if (score.tracks().stream().allMatch(NoteTrack::isEmpty)) {
            // Refused rather than written empty. A MIDI file with no notes reads
            // back as an error rather than as an empty score -- the importer
            // requires a tick length -- so writing one moves the failure to
            // whoever opens it.
            throw new IllegalArgumentException(
                    "this score holds no notes, so there is no MIDI to write; a chord"
                            + " progression is not a set of notes and turning one into notes"
                            + " is an arrangement rather than an export, see #10");
        }
        Sequence sequence;
        try {
            sequence = new Sequence(Sequence.PPQ, TICKS_PER_QUARTER);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("PPQ at " + TICKS_PER_QUARTER + " ticks is not a"
                    + " division this JDK accepts", e);
        }
        writeConductor(sequence.createTrack(), score);

        int channel = 0;
        for (NoteTrack track : score.tracks()) {
            if (track.isEmpty()) {
                // An empty part would be a named track with no notes, which the
                // importer reads as a title candidate rather than as a part.
                continue;
            }
            int assigned;
            if (track.role() == PartRole.DRUMS) {
                assigned = DRUM_CHANNEL;
            } else {
                if (channel == DRUM_CHANNEL) {
                    channel++;
                }
                // Wrapped rather than refused past the sixteenth part. Two parts
                // on one channel still import as two, because the importer
                // splits on the track as well as the channel; what they share is
                // a playback instrument, which is a worse rendering rather than
                // a lost part.
                assigned = channel % CHANNELS;
                channel = (channel + 1) % CHANNELS;
            }
            writePart(sequence.createTrack(), track, assigned);
        }
        return sequence;
    }

    /**
     * Writes the score to a file.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    public static Path write(Score score, Path file) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(file, "file");
        Sequence sequence = toSequence(score);
        try {
            MidiSystem.write(sequence, 1, file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
        return file;
    }

    // ------------------------------------------------------------- conductor

    /**
     * The track holding tempo, meter, key and the title.
     *
     * <p>Notes are deliberately not on it. In a type 1 file the first track's
     * name is the name of the <em>piece</em> by convention, and that is how the
     * importer reads it — but only when that track has no notes, since in a type
     * 0 file the single track's name is a part name. Putting the tempo map on a
     * track of its own is what makes the title unambiguous.
     */
    private static void writeConductor(Track conductor, Score score) {
        score.title().ifPresent(title -> add(conductor, 0, text(META_TRACK_NAME, title)));
        writeTempi(conductor, score.tempoMap());
        writeMeters(conductor, score.tempoMap());
        writeKeys(conductor, score);
    }

    /**
     * The tempo map, one event per segment.
     *
     * <p>Microseconds per quarter note is what the format holds, and it is a
     * whole number, so a tempo generally does not survive the trip exactly. That
     * is the format rather than a defect here; see the note on this class.
     */
    private static void writeTempi(Track conductor, TempoMap tempoMap) {
        for (TempoMap.TempoSegment segment : tempoMap.segments()) {
            long microseconds = Math.round(60_000_000.0 / segment.beatsPerMinute());
            if (microseconds < 1 || microseconds > MAX_MICROSECONDS_PER_QUARTER) {
                throw new IllegalArgumentException(
                        "a tempo of " + segment.beatsPerMinute() + " BPM is outside what a MIDI"
                                + " tempo event can express, which is "
                                + (60_000_000.0 / MAX_MICROSECONDS_PER_QUARTER) + " BPM upwards");
            }
            add(conductor, tickOf(segment.startBeat()), meta(META_TEMPO, new byte[] {
                    (byte) (microseconds >> 16), (byte) (microseconds >> 8), (byte) microseconds}));
        }
    }

    /**
     * The meter changes, one event per change, at the bar line each takes effect
     * on.
     *
     * <p>The model puts a meter change at a <em>bar</em> and MIDI puts it at a
     * tick, so the bar has to be converted — through {@link TempoMap#toBeat},
     * which is the only thing that knows how long the bars before it were.
     *
     * <p>The last two bytes are the ones nobody reads and everybody has to
     * write: MIDI clocks per metronome click and 32nd notes per quarter. 24 and
     * 8 are the values that mean "the ordinary thing", and a file with anything
     * else in them is claiming a click that this project has no opinion about.
     */
    private static void writeMeters(Track conductor, TempoMap tempoMap) {
        for (TempoMap.MeterChange change : tempoMap.meterChanges()) {
            TimeSignature meter = change.timeSignature();
            double beat = tempoMap.toBeat(new MusicalTime(change.startBar(), 0, meter));
            add(conductor, tickOf(beat), meta(META_TIME_SIGNATURE, new byte[] {
                    (byte) meter.numerator(),
                    (byte) Integer.numberOfTrailingZeros(meter.denominator()),
                    24, 8}));
        }
    }

    /**
     * The key signatures.
     *
     * <p>A signed count of sharps and a major/minor flag, which is a bijection
     * with a tonic and a mode — the same arithmetic the importer runs backwards,
     * so a key does survive the round trip where a pitch spelling does not.
     *
     * <p>A key that carries musical time is placed by it. One that does not is
     * placed through the tempo map, which is the sanctioned conversion and the
     * only thing available: unlike a note, a key with no beat is metadata rather
     * than music, and dropping it would lose the key signature from the printed
     * score rather than misplace it by a fraction of a beat.
     */
    private static void writeKeys(Track conductor, Score score) {
        for (Key key : score.keys()) {
            double beat = key.startBeat()
                    .orElseGet(() -> score.tempoMap().secondsToBeats(key.startSeconds()));
            add(conductor, tickOf(beat), meta(META_KEY_SIGNATURE, new byte[] {
                    (byte) key.keySignatureAccidentals(),
                    (byte) (key.mode() == Mode.MINOR ? 1 : 0)}));
        }
    }

    // ------------------------------------------------------------------ parts

    /**
     * One part, as note events on one channel.
     *
     * <p>Sounding pitch, not written pitch. {@link PartRole#BASS} reads an
     * octave above where it sounds and its clef is what says so, so transposing
     * here as well would play the part an octave high and re-import it there.
     */
    private static void writePart(Track out, NoteTrack track, int channel) {
        add(out, 0, text(META_TRACK_NAME, track.name()));
        for (Note note : track.notes()) {
            double onsetBeat = note.onsetBeat().orElseThrow(() -> new IllegalArgumentException(
                    "track \"" + track.name() + "\" holds a note at " + note.onsetSeconds()
                            + "s with no musical timing; quantize before exporting MIDI"));
            long start = tickOf(onsetBeat);
            // At least one tick long, so that a note shorter than a tick is
            // played briefly rather than silently dropped -- a note-on and a
            // note-off at the same tick is a note the importer discards. At 768
            // ticks a quarter this needs a note under a 3072nd of a whole note,
            // which the quantizer cannot produce; it is a floor rather than a
            // rounding policy.
            long end = Math.max(start + 1, tickOf(onsetBeat + note.durationBeats().orElseThrow()));
            int velocity = Math.max(SILENT_NOTE_VELOCITY, note.velocity());
            add(out, start, shortMessage(ShortMessage.NOTE_ON, channel, note.midiPitch(),
                    velocity));
            add(out, end, shortMessage(ShortMessage.NOTE_OFF, channel, note.midiPitch(), 0));
        }
    }

    // ----------------------------------------------------------------- events

    /**
     * The tick a beat position falls on.
     *
     * <p>Rounded, and every position the quantizer produces is a whole tick
     * already: {@link #TICKS_PER_QUARTER} is a multiple of both the 64th-note
     * grid and every tuplet grid. The rounding is what makes a position built by
     * adding thirds of a beat land on the tick that multiplying would have
     * reached, which is the same reason {@link TupletBar} works in step counts.
     */
    private static long tickOf(double beat) {
        if (!Double.isFinite(beat) || beat < 0) {
            throw new IllegalArgumentException(
                    "a MIDI position must be a finite non-negative beat, got: " + beat);
        }
        return Math.round(beat * TICKS_PER_QUARTER);
    }

    private static MetaMessage meta(int type, byte[] data) {
        try {
            return new MetaMessage(type, data, data.length);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException(
                    "could not build meta event of type " + type, e);
        }
    }

    /**
     * A text meta event.
     *
     * <p>UTF-8, which the format does not specify and which the importer tries
     * first. A name that is not ASCII is therefore written and read back
     * unchanged here, and read as mojibake by a program that assumes a code
     * page — which is the best any writer can do, since MIDI has no way to
     * declare the encoding.
     */
    private static MetaMessage text(int type, String value) {
        return meta(type, value.getBytes(StandardCharsets.UTF_8));
    }

    private static ShortMessage shortMessage(int command, int channel, int data1, int data2) {
        try {
            return new ShortMessage(command, channel, data1, data2);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(
                    "note " + data1 + " on channel " + channel + " is not something MIDI can"
                            + " express", e);
        }
    }

    /**
     * Adds an event.
     *
     * <p>{@link Track#add} keeps its events in tick order and keeps its own
     * end-of-track marker last, so nothing here has to be written in order. A
     * track whose end marker sat before its last note would be a file that plays
     * silence from that point, which is invisible until something else reads it.
     */
    private static void add(Track track, long tick, MidiMessage message) {
        track.add(new MidiEvent(message, tick));
    }
}
