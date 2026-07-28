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

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Reads a Standard MIDI File into a {@link Score}.
 *
 * <p>The symbolic sibling of {@link AudioTranscriber}, and the reason the whole
 * notation half of the pipeline can be built before the audio half is finished:
 * this produces the same artifact, from an input whose answers are already known.
 *
 * <p>What makes it different from the audio path is that <em>musical time is not
 * estimated here</em>. A tick is an exact position in quarter notes, so every
 * note leaves this class with both axes populated and a
 * {@link Confidence#CERTAIN} attached, and nothing downstream needs to quantize
 * it. Quantization (#91) exists for the audio path, where onsets are measured
 * rather than declared; running it over this output would round values that are
 * already exact.
 *
 * <p>What this deliberately does <em>not</em> do is spell pitches. A MIDI number
 * is not a written note -- 61 is both C sharp and D flat -- and choosing between
 * them needs the key and the sounding harmony. Notes therefore leave here with
 * {@link Note#spelling()} empty rather than guessed; #91 fills it in. A key
 * <em>signature</em> is a different matter and is read straight from the file
 * where one is present, because the mapping from a signed count of sharps to a
 * tonic is a bijection rather than an inference.
 */
public final class MidiTranscriber {

    /** The tempo a MIDI file is played at when it declares none. */
    private static final double DEFAULT_TEMPO_BPM = 120;

    /** The meter a MIDI file is barred in when it declares none. */
    private static final TimeSignature DEFAULT_METER = TimeSignature.FOUR_FOUR;

    /**
     * The largest file this will read.
     *
     * <p>The cap is on bytes because bytes are all that can be counted before
     * parsing, but what it is really bounding is heap, and the two are not the
     * same size. This figure is measured rather than modelled, because two
     * successive attempts to model it were both wrong and both wrong low: a
     * {@code MidiEvent} plus a {@code ShortMessage} per three input bytes gives
     * 35x, and the JDK's {@code Track} keeps a {@code HashSet} of its events
     * beside the {@code ArrayList}, which that count leaves out.
     *
     * <p>Measured on this JDK against the densest file the format allows --
     * running-status note events, three bytes each -- by bisecting {@code -Xmx}
     * over the whole {@code transcribe} path:
     *
     * <pre>
     *   4 MB file, 699,040 notes: fails at 256m, succeeds at 288m
     *   6 MB file, 1,048,565 notes: fails at 384m, succeeds at 512m
     *   8 MB file, 1,398,096 notes: fails at 512m
     * </pre>
     *
     * <p>So the real ratio is about 70x, not 35x. Four megabytes is the figure
     * because a JVM with no {@code -Xmx} takes a quarter of the machine, and a
     * 2 GB container therefore gets 512 MB -- which the row above clears with
     * margin, where 8 MB does not clear it at all. It is still far above any
     * real score: a full orchestral piece with every controller gesture intact
     * is a couple of megabytes.
     */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** How much of a track name is kept, in code points. */
    private static final int MAX_NAME_LENGTH = 96;

    /**
     * A track name that identifies a bass part.
     *
     * <p>Whole words only, and only those three, which is the point. "Bassoon"
     * and "Basset horn" both begin with the same four letters and are neither of
     * them bass parts, and a role assigned wrongly here does not show up as a
     * wrong note -- it shows up as a part quietly engraved on the wrong staff, an
     * octave out, several stages later.
     */
    private static final Pattern BASS_NAME = Pattern.compile("(?i)\\bbass(line|es)?\\b");

    /**
     * A track name that identifies a drum, which vetoes {@link #BASS_NAME}.
     *
     * <p>"Bass drum" and "Bass kick" match both patterns and are percussion. A
     * kit on channel 10 never reaches this test, but a kit routed elsewhere --
     * which happens, since channel 10 is a General MIDI convention rather than a
     * rule -- otherwise becomes the bass part.
     */
    private static final Pattern DRUM_NAME = Pattern.compile("(?i)\\b(drum|drums|kick|perc\\w*)\\b");

    /** General MIDI's percussion channel, counted from zero. */
    private static final int DRUM_CHANNEL = 9;

    private static final int META_TRACK_NAME = 0x03;
    private static final int META_INSTRUMENT_NAME = 0x04;
    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIGNATURE = 0x58;
    private static final int META_KEY_SIGNATURE = 0x59;

    /** Largest denominator {@link TimeSignature} accepts, as a power of two. */
    private static final int MAX_METER_DENOMINATOR_LOG2 = 6;

    private final Consumer<String> progress;

    public MidiTranscriber(Consumer<String> progress) {
        this.progress = progress != null ? progress : message -> { };
    }

    public MidiTranscriber() {
        this(null);
    }

    /**
     * Reads a Standard MIDI File.
     *
     * @throws IllegalArgumentException if the file is not a MIDI file this can
     *         represent as a score
     * @throws UncheckedIOException if it cannot be read
     */
    public Score transcribe(Path file) {
        Objects.requireNonNull(file, "file");
        byte[] bytes = readFully(file);
        progress.accept("reading " + file.getFileName());

        // Read twice from the same bytes rather than twice from disk: the file
        // type is needed before the sequence, and re-opening would let the file
        // change between the two reads.
        int type;
        try {
            MidiFileFormat format = MidiSystem.getMidiFileFormat(
                    new ByteArrayInputStream(bytes));
            type = format.getType();
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(
                    "not a readable Standard MIDI File: " + file, e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        if (type == 2) {
            // Type 2 tracks are independent patterns rather than parts playing
            // together. Importing them as if they were simultaneous produces a
            // score that is entirely plausible and entirely wrong, which is
            // worse than refusing the file.
            throw new IllegalArgumentException(
                    "type 2 MIDI files hold independent patterns rather than simultaneous parts,"
                            + " so they cannot be read as one score: " + file);
        }

        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(
                    "not a readable Standard MIDI File: " + file, e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return transcribe(sequence);
    }

    /** Reads an already-parsed sequence. */
    public Score transcribe(Sequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        if (sequence.getDivisionType() != Sequence.PPQ) {
            // A frame-based division measures wall-clock time, not musical time:
            // there is no ticks-per-quarter to divide by, and the tempo events
            // that would supply one are ignored by such a file's own playback.
            // Guessing a resolution would put every note on a musical position
            // it does not occupy.
            throw new IllegalArgumentException(
                    "this MIDI file is timed in SMPTE frames (" + sequence.getDivisionType()
                            + " fps) rather than ticks per quarter note, so it carries no musical"
                            + " time to import");
        }
        int ticksPerQuarter = sequence.getResolution();
        if (ticksPerQuarter < 1) {
            throw new IllegalArgumentException(
                    "a MIDI file must declare at least one tick per quarter note, got: "
                            + ticksPerQuarter);
        }
        Track[] tracks = sequence.getTracks();
        long endTick = sequence.getTickLength();
        if (tracks.length == 0 || endTick <= 0) {
            throw new IllegalArgumentException(
                    "this MIDI file holds no music: every event in it is at tick 0");
        }

        TempoMap tempoMap = new TempoMap(
                readTempoSegments(tracks, ticksPerQuarter),
                readMeterChanges(tracks, ticksPerQuarter));
        double endBeat = endTick / (double) ticksPerQuarter;
        double durationSeconds = tempoMap.beatsToSeconds(endBeat);
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "this MIDI file's tempo events give it a duration of " + durationSeconds
                            + " seconds, which is not a length a score can have");
        }
        progress.accept(String.format(Locale.ROOT,
                "read %d track(s), %.3f beats, %.2fs at %d ticks per quarter",
                tracks.length, endBeat, durationSeconds, ticksPerQuarter));
        if (tempoMap.segments().size() > 1) {
            progress.accept("the tempo changes " + (tempoMap.segments().size() - 1)
                    + " time(s) during the piece");
        }

        List<NoteTrack> noteTracks = readNoteTracks(tracks, ticksPerQuarter, endTick, tempoMap);
        List<Key> keys = readKeys(tracks, ticksPerQuarter, endTick, tempoMap);
        String title = readTitle(tracks);

        return new Score(
                Optional.ofNullable(title),
                Optional.empty(),
                tempoMap,
                Optional.empty(),
                keys,
                List.of(),
                noteTracks,
                ChordProgression.empty(),
                Lyrics.empty(),
                durationSeconds);
    }

    // ------------------------------------------------------------------- tempo

    /**
     * The tempo segments the file's tempo events describe.
     *
     * <p>Each segment's start in seconds is accumulated with exactly the
     * expression {@link TempoMap} validates against -- the previous segment's
     * tempo carried across the intervening beats -- so a map built here differs
     * from the one the constructor expects by nothing at all rather than by an
     * amount that happens to fall inside its tolerance.
     *
     * <p>A file with no tempo event, or whose first one is not at tick 0, is
     * played at 120 by the specification, so that is what the opening segment
     * carries. It also satisfies the map's anchor at (beat 0, second 0), which
     * has to hold whatever the file says.
     */
    private List<TempoMap.TempoSegment> readTempoSegments(Track[] tracks, int ticksPerQuarter) {
        TreeMap<Long, Double> tempi = new TreeMap<>();
        forEachMeta(tracks, META_TEMPO, (tick, data) -> {
            if (data.length < 3) {
                progress.accept("ignoring a malformed tempo event at tick " + tick);
                return;
            }
            int microsecondsPerQuarter = ((data[0] & 0xFF) << 16)
                    | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
            if (microsecondsPerQuarter <= 0) {
                // Zero microseconds a quarter note is an infinitely fast tempo.
                // Left alone it reaches TempoSegment as a non-finite BPM and is
                // rejected there, with a message about a number this file never
                // contained.
                throw new IllegalArgumentException(
                        "the tempo event at tick " + tick + " says zero microseconds per quarter"
                                + " note, which is not a tempo");
            }
            tempi.put(tick, 60_000_000.0 / microsecondsPerQuarter);
        });
        if (!tempi.containsKey(0L)) {
            tempi.put(0L, DEFAULT_TEMPO_BPM);
        }

        List<TempoMap.TempoSegment> segments = new ArrayList<>(tempi.size());
        double previousBeat = 0;
        double previousSeconds = 0;
        double previousTempo = tempi.get(0L);
        segments.add(new TempoMap.TempoSegment(0, 0, previousTempo));
        for (Map.Entry<Long, Double> entry : tempi.tailMap(0L, false).entrySet()) {
            double beat = entry.getKey() / (double) ticksPerQuarter;
            double seconds = previousSeconds + (beat - previousBeat) * (60.0 / previousTempo);
            if (!(seconds > previousSeconds)) {
                // Two tempo events so close together that the map cannot tell
                // them apart. Keeping the later one would break the strict
                // ordering the map requires -- on either axis, and the map is
                // then unbuildable and the file unreadable rather than slightly
                // wrong. The audible difference between dropping it and
                // honouring it is nil.
                //
                // One condition, not three, because the two others worth
                // worrying about both reduce to it. A beat collision -- two
                // ticks that divide to the same double, which happens above
                // 2^53 -- makes the increment below exactly zero, so the seconds
                // do not advance either. And "not greater than" is already false
                // for NaN, while the tick range and the tempo range together
                // bound the sum far below overflow.
                //
                // Both inputs still have a test. The two axes are not always
                // interchangeable, and this is not a rule the class applies
                // everywhere: readKeys deliberately checks seconds *and* beats,
                // because there the two are independent quantities rather than
                // one derived from the other, and its own comment says so.
                progress.accept("ignoring a tempo event at tick " + entry.getKey()
                        + " that is indistinguishable in time from the previous one");
                continue;
            }
            segments.add(new TempoMap.TempoSegment(beat, seconds, entry.getValue()));
            previousBeat = beat;
            previousSeconds = seconds;
            previousTempo = entry.getValue();
        }
        return segments;
    }

    // ------------------------------------------------------------------- meter

    /**
     * The meter changes the file's time-signature events describe.
     *
     * <p>The awkward part is that a MIDI event is at a tick and a
     * {@link TempoMap.MeterChange} is at a <em>bar</em>, and the conversion needs
     * the meter in force up to that point. So this walks the events in order,
     * counting bars as it goes.
     *
     * <p>A change that does not land on a bar line cannot be represented (#97),
     * and is moved forward to the next one. Forward rather than back
     * deliberately: every bar before the change then still divides as the file
     * said it did, and only the incomplete bar the event fell inside is
     * affected.
     *
     * <p>Moving one forward can push it onto a bar a later change also claims,
     * and then it never takes effect anywhere. That is only discoverable when
     * the later change is read, so the move is <em>reported at the end</em>
     * rather than when it happens: a change that turns out not to survive has
     * its report dropped along with it. Reporting as it went produced a line
     * naming a bar the score does not contain in that meter, followed some lines
     * later by another retracting it, and a reader who acted on the first was
     * acting on something untrue.
     *
     * <p>Undoing a change is what forces the bar line of each entry to be kept
     * beside it rather than in a running variable. When a superseding change
     * restates the meter that was in force before the one it displaces, the
     * file's effective meter never changed at all, and the entry has to come
     * out -- along with the bar count that was measured from it. An earlier
     * draft carried the count separately and emitted a change from 4/4 to 4/4,
     * which the model accepts, {@code timeSignatureAtBar} answers correctly, and
     * the notation layer engraves as a redundant time signature mid-piece.
     *
     * <p>"On a bar line" is judged to within <em>half a tick</em>, and that is
     * load-bearing rather than a detail. A tolerance denominated in bars -- what
     * an earlier draft used -- grows with the bar, and once it exceeds a tick it
     * makes an event one tick past the origin count as being <em>on</em> bar
     * zero's line, which sends the walk into the branch that removes an entry.
     * The entry at that point is the bar-0 one the map requires to exist, and
     * removing it took the index below zero.
     *
     * <p>Half a tick cannot say that, and the reason is specifically about the
     * origin rather than about bar lines in general: bar zero's line is at beat
     * 0, the first event in this loop is at tick 1 or later, and one tick is
     * twice the tolerance at every resolution. Later bar lines are <em>not</em>
     * generally at whole ticks -- {@code 4 * ticksPerQuarter / denominator} need
     * not be an integer, and at 120 ticks per quarter an x/64 bar line falls on
     * a half tick -- so the tolerance does admit moving a change backwards
     * there, by less than a tick. That is a rounding decision rather than the
     * bar-sized move the paragraph above is about.
     */
    private List<TempoMap.MeterChange> readMeterChanges(Track[] tracks, int ticksPerQuarter) {
        TreeMap<Long, TimeSignature> meters = new TreeMap<>();
        forEachMeta(tracks, META_TIME_SIGNATURE, (tick, data) -> {
            if (data.length < 2) {
                progress.accept("ignoring a malformed time-signature event at tick " + tick);
                return;
            }
            int numerator = data[0] & 0xFF;
            int denominatorLog2 = data[1] & 0xFF;
            if (numerator < 1 || numerator > 64 || denominatorLog2 > MAX_METER_DENOMINATOR_LOG2) {
                progress.accept("ignoring the time signature at tick " + tick + ": "
                        + numerator + "/"
                        + (denominatorLog2 <= 30 ? String.valueOf(1 << denominatorLog2) : "?")
                        + " is outside what the model can express");
                return;
            }
            meters.put(tick, new TimeSignature(numerator, 1 << denominatorLog2));
        });

        TimeSignature opening = meters.getOrDefault(0L, DEFAULT_METER);
        List<TempoMap.MeterChange> changes = new ArrayList<>();
        // The beat each change's bar line falls on, one per entry in changes, so
        // that removing an entry restores the bar count with it.
        List<Double> barLineBeats = new ArrayList<>();
        // Everything to tell the user, in the order it was discovered -- which
        // is tick order, since the events are walked in tick order. Held rather
        // than printed because one kind of line can stop being true: a report
        // that a change moved to a bar line is false if that change is later
        // displaced before the bar begins. Retracted by nulling the slot rather
        // than removing it, so the surrounding lines keep their order.
        List<String> log = new ArrayList<>();
        // Where in the log each change's own "it moved" line sits, or -1. Only
        // that line dies with its change: a line saying some *other* change
        // never takes effect is a permanent fact, and parking it on an entry
        // that was itself removed a moment later is how it went missing.
        List<Integer> movedLine = new ArrayList<>();
        changes.add(new TempoMap.MeterChange(0, opening));
        barLineBeats.add(0.0);
        movedLine.add(-1);

        // Half a tick, in quarter beats. See the note on the method.
        double halfTick = 0.5 / ticksPerQuarter;

        for (Map.Entry<Long, TimeSignature> entry : meters.tailMap(0L, false).entrySet()) {
            TimeSignature meter = entry.getValue();
            int last = changes.size() - 1;
            TimeSignature current = changes.get(last).timeSignature();
            if (meter.equals(current)) {
                // Restating the meter changes nothing, and the bar count is
                // measured from the last real change, so skipping leaves the
                // walk exactly where it was.
                continue;
            }
            long currentBar = changes.get(last).startBar();
            double barLength = current.quarterBeatsPerBar();
            double beat = entry.getKey() / (double) ticksPerQuarter;
            double barsAhead = (beat - barLineBeats.get(last)) / barLength;
            // Negative when an earlier change was moved forward past this one's
            // tick, in which case this change lands on the bar that one claimed.
            long wholeBars = Math.max(0, (long) Math.ceil(barsAhead));
            if (wholeBars > 0 && barLength > halfTick && Math.abs(barLineBeats.get(last)
                    + (wholeBars - 1) * barLength - beat) <= halfTick) {
                // The bar line just below is this event's own tick, so the
                // ceiling overshot by one: the change is on that line, not after
                // it. This is the whole of the tolerance -- everything else is
                // exact arithmetic on whole ticks.
                //
                // Not applied where a whole bar is shorter than the tolerance,
                // which needs a resolution of 8 ticks per quarter or less at
                // 1/64. Several bar lines then sit inside half a tick, "the one
                // just below" is not meaningfully nearer than the one the
                // ceiling picked, and decrementing put an exactly-on-a-bar-line
                // change one bar early -- silently, since the same tolerance
                // then calls the result on a bar line too.
                wholeBars--;
            }
            long bar = currentBar + wholeBars;
            if (bar > Integer.MAX_VALUE) {
                progress.accept("ignoring the time signature at tick " + entry.getKey()
                        + ": it falls past the last bar a score can number");
                continue;
            }
            double barLineBeat = barLineBeats.get(last) + wholeBars * barLength;
            boolean onBarLine = Math.abs(barLineBeat - beat) <= halfTick;

            if (bar == currentBar) {
                // The change already on this bar is superseded before the bar
                // begins, so it takes effect nowhere. That is permanent, so it
                // goes straight into the log; what does not survive is whatever
                // that change had been told to say about itself.
                //
                // Never the entry at index 0: reaching here needs wholeBars to
                // be zero, which at index 0 needs the event at or within half a
                // tick of the origin, and tick 0 is not in this loop. So the
                // bar-0 entry the map requires cannot be the one removed.
                log.add(String.format(Locale.ROOT,
                        "the time signature %s never takes effect: bar %d is redeclared"
                                + " as %s before it begins",
                        current, bar + 1, meter));
                changes.remove(last);
                barLineBeats.remove(last);
                int retracted = movedLine.remove(last);
                if (retracted >= 0) {
                    log.set(retracted, null);
                }
                last--;
                if (meter.equals(changes.get(last).timeSignature())) {
                    // With the displaced change gone the file's effective meter
                    // has not changed at all, so recording a change here would
                    // engrave a time signature identical to the one in force.
                    continue;
                }
            }
            int movedAt = -1;
            if (!onBarLine) {
                // Reported in the superseding case too. An earlier draft left it
                // out there, so a change that both displaced another and sat
                // mid-bar was moved -- by up to a whole bar -- with nothing said.
                // How far it moved, in quarter beats, rather than how far into
                // a bar it fell. The latter is negative in the superseding case
                // -- the change sits before a bar line an earlier change was
                // already pushed to -- and a negative count of bars is not
                // something a reader can act on. The distance moved is never
                // negative, because the bar taken is the first at or after the
                // event.
                movedAt = log.size();
                log.add(String.format(Locale.ROOT,
                        "the time signature %s at tick %d does not fall on a bar line;"
                                + " taking effect at bar %d, which begins %.3f quarter"
                                + " beats later",
                        meter, entry.getKey(), bar + 1, barLineBeat - beat));
            }
            changes.add(new TempoMap.MeterChange((int) bar, meter));
            barLineBeats.add(barLineBeat);
            movedLine.add(movedAt);
        }
        for (String line : log) {
            if (line != null) {
                progress.accept(line);
            }
        }
        if (changes.size() > 1) {
            progress.accept("the meter changes " + (changes.size() - 1) + " time(s)");
        }
        return changes;
    }

    // ------------------------------------------------------------------- notes

    /** A note-on waiting for the note-off that ends it. */
    private record PendingNote(long tick, int velocity) { }

    /** A part before roles have been reconciled across the whole file. */
    private record ImportedPart(String name, int channel, PartRole role, List<Note> notes) { }

    /**
     * Every part in the file, one per track and channel.
     *
     * <p>Split by channel as well as by track because a single track carrying two
     * channels is two parts sharing a chunk of file, not one part; merging them
     * would put a bass line and a pad on the same staff.
     */
    private List<NoteTrack> readNoteTracks(
            Track[] tracks, int ticksPerQuarter, long endTick, TempoMap tempoMap) {
        List<ImportedPart> parts = new ArrayList<>();
        int droppedZeroLength = 0;
        int unterminated = 0;
        for (int t = 0; t < tracks.length; t++) {
            String trackName = readTrackName(tracks[t]);
            // Sorted so that the parts of a multi-channel track come out in a
            // fixed order whatever order the events happened to arrive in.
            TreeMap<Integer, List<Note>> byChannel = new TreeMap<>();
            Map<Integer, Deque<PendingNote>> pending = new HashMap<>();

            for (int i = 0; i < tracks[t].size(); i++) {
                MidiEvent event = tracks[t].get(i);
                if (!(event.getMessage() instanceof ShortMessage message)) {
                    continue;
                }
                int command = message.getCommand();
                if (command != ShortMessage.NOTE_ON && command != ShortMessage.NOTE_OFF) {
                    continue;
                }
                int channel = message.getChannel();
                int pitch = message.getData1();
                int velocity = message.getData2();
                int voice = channel * 128 + pitch;
                // A note-on at velocity zero is a note-off. Both spellings are
                // ordinary and files mix them freely, sometimes within a bar.
                if (command == ShortMessage.NOTE_ON && velocity > 0) {
                    pending.computeIfAbsent(voice, unused -> new ArrayDeque<>())
                            .addLast(new PendingNote(event.getTick(), velocity));
                    continue;
                }
                Deque<PendingNote> queue = pending.get(voice);
                if (queue == null || queue.isEmpty()) {
                    // A note-off with nothing sounding. Common in files that were
                    // edited by hand, and harmless.
                    continue;
                }
                // Oldest first: when the same pitch is struck twice before either
                // is released -- a held pedal note re-articulated, say -- the
                // first release ends the first strike. Pairing newest-first
                // instead leaves the earlier note open to the end of the track
                // and gives it a wildly long duration.
                PendingNote start = queue.removeFirst();
                Note note = buildNote(start, event.getTick(), pitch, ticksPerQuarter, tempoMap);
                if (note == null) {
                    droppedZeroLength++;
                } else {
                    byChannel.computeIfAbsent(channel, unused -> new ArrayList<>()).add(note);
                }
            }
            // Anything still sounding at the end of the track is held to the end
            // of the piece rather than dropped or given an infinite length.
            for (Map.Entry<Integer, Deque<PendingNote>> entry : pending.entrySet()) {
                int channel = entry.getKey() / 128;
                int pitch = entry.getKey() % 128;
                for (PendingNote start : entry.getValue()) {
                    Note note = buildNote(start, endTick, pitch, ticksPerQuarter, tempoMap);
                    if (note == null) {
                        // Counted once, as the one thing that happened to it. A
                        // note-on at the very last tick is both unterminated and
                        // zero-length, and reporting it under both headings
                        // overstates how much of the file was lost.
                        droppedZeroLength++;
                    } else {
                        unterminated++;
                        byChannel.computeIfAbsent(channel, unused -> new ArrayList<>()).add(note);
                    }
                }
            }

            for (Map.Entry<Integer, List<Note>> entry : byChannel.entrySet()) {
                int channel = entry.getKey();
                String name = partName(trackName, t, channel, byChannel.size() > 1);
                parts.add(new ImportedPart(
                        name, channel, inferRole(channel, name), entry.getValue()));
            }
        }
        if (droppedZeroLength > 0) {
            progress.accept("dropped " + droppedZeroLength + " note(s) of zero length, which a"
                    + " score cannot hold");
        }
        if (unterminated > 0) {
            progress.accept("held " + unterminated + " note(s) with no note-off to the end of"
                    + " the piece");
        }
        return toNoteTracks(parts);
    }

    /**
     * One note, or {@code null} when it has no length a score can hold.
     *
     * <p>Beats come from ticks and not from seconds, because ticks are already
     * exact and a round trip through the tempo map is not. The duration is
     * measured in ticks too, so a quarter note is 1.0 beats however far into the
     * piece it falls -- taking the difference of two converted onsets would leave
     * it a fraction of a beat off, and a quantizer that later sees 0.9999 has to
     * decide what was meant.
     *
     * <p>Seconds are the other way round: both ends go through
     * {@link TempoMap#beatsToSeconds}, so a note that straddles a tempo change
     * gets the length it is really played for rather than the one the tempo at
     * its onset would give it.
     */
    private static Note buildNote(PendingNote start, long endTick, int pitch,
                                  int ticksPerQuarter, TempoMap tempoMap) {
        double onsetBeat = start.tick() / (double) ticksPerQuarter;
        double durationBeats = (endTick - start.tick()) / (double) ticksPerQuarter;
        double onsetSeconds = tempoMap.beatsToSeconds(onsetBeat);
        double durationSeconds =
                tempoMap.beatsToSeconds(endTick / (double) ticksPerQuarter) - onsetSeconds;
        // One check rather than two: an end tick at or before the onset gives a
        // duration of zero or less and is caught here, so a separate guard on the
        // ticks was a branch no input could reach on its own.
        if (!(durationBeats > 0) || !(durationSeconds > 0) || !Double.isFinite(onsetSeconds)) {
            return null;
        }
        return new Note(onsetSeconds, durationSeconds, pitch, start.velocity(),
                // No spelling: 61 is C sharp or D flat depending on the key and
                // the chord under it, and this class knows neither. #91 decides.
                Optional.empty(),
                Optional.of(onsetBeat), Optional.of(durationBeats),
                // A MIDI file is a statement, not a measurement.
                Confidence.CERTAIN);
    }

    /**
     * Reconciles roles and names across the file, then builds the tracks.
     *
     * <p>{@link Score} permits at most one track in each named role and requires
     * distinct names within {@link PartRole#OTHER}, so a file with two kits or
     * two parts both called "Bass" would otherwise be rejected outright at the
     * end of a successful import. The first part to claim a role keeps it and the
     * rest are demoted, which is reported: a demoted part is engraved on a
     * different staff than the file implies, and that is worth a line of output.
     */
    private List<NoteTrack> toNoteTracks(List<ImportedPart> parts) {
        Set<PartRole> claimed = EnumSet.noneOf(PartRole.class);
        Set<String> usedNames = new HashSet<>();
        List<NoteTrack> built = new ArrayList<>(parts.size());
        for (ImportedPart part : parts) {
            PartRole role = part.role();
            if (role != PartRole.OTHER && !claimed.add(role)) {
                progress.accept("\"" + part.name() + "\" also looks like the " + role
                        + " part, but the score already has one; importing it as "
                        + PartRole.OTHER);
                role = PartRole.OTHER;
            }
            String name = part.name();
            for (int suffix = 2; !usedNames.add(name); suffix++) {
                name = part.name() + " (" + suffix + ")";
            }
            built.add(new NoteTrack(role, name, part.notes(), Confidence.CERTAIN));
        }
        return built;
    }

    /**
     * The role a part plays, where the file says so unambiguously.
     *
     * <p>Two signals only, and both are conventions strong enough to act on:
     * channel 10 is percussion in General MIDI, and a track called "Bass" is the
     * bass. Everything else is {@link PartRole#OTHER}, including parts whose
     * General MIDI program is a bass instrument -- see #96. A role is not a label
     * a later stage re-derives; it picks the clef, the staff and, for the bass, a
     * written octave, so a confident wrong answer is more expensive here than no
     * answer at all.
     */
    private static PartRole inferRole(int channel, String name) {
        if (channel == DRUM_CHANNEL) {
            return PartRole.DRUMS;
        }
        if (BASS_NAME.matcher(name).find() && !DRUM_NAME.matcher(name).find()) {
            return PartRole.BASS;
        }
        return PartRole.OTHER;
    }

    /** A display name for one part, distinguishing channels only where needed. */
    private static String partName(String trackName, int trackIndex, int channel,
                                   boolean multiChannel) {
        String base = trackName != null ? trackName : "Track " + (trackIndex + 1);
        // The channel is only worth printing when the track really does carry
        // more than one, where the name alone would name two different parts.
        return multiChannel ? base + " ch " + (channel + 1) : base;
    }

    // -------------------------------------------------------------------- keys

    /**
     * The key signatures the file declares, as spans.
     *
     * <p>Absent is absent: a file with no key-signature event produces an empty
     * list rather than C major, because "the writer did not say" and "the writer
     * said C major" are different claims and only one of them supports a key
     * signature on the printed staff.
     *
     * <p>The tonic is derived rather than guessed. A signed count of sharps and a
     * major/minor flag name exactly one key, so the conversion is a bijection --
     * unlike pitch spelling, which is why one is done here and the other is not.
     */
    private List<Key> readKeys(Track[] tracks, int ticksPerQuarter, long endTick,
                               TempoMap tempoMap) {
        TreeMap<Long, int[]> events = new TreeMap<>();
        forEachMeta(tracks, META_KEY_SIGNATURE, (tick, data) -> {
            if (data.length < 2) {
                progress.accept("ignoring a malformed key-signature event at tick " + tick);
                return;
            }
            int sharpsOrFlats = data[0];
            int modeFlag = data[1] & 0xFF;
            if (sharpsOrFlats < -7 || sharpsOrFlats > 7 || modeFlag > 1) {
                progress.accept("ignoring the key signature at tick " + tick
                        + ": " + sharpsOrFlats + " sharps, mode " + modeFlag
                        + " is not a key signature");
                return;
            }
            events.put(tick, new int[] {sharpsOrFlats, modeFlag});
        });

        List<Key> keys = new ArrayList<>(events.size());
        List<Long> ticks = new ArrayList<>(events.keySet());
        for (int i = 0; i < ticks.size(); i++) {
            long from = ticks.get(i);
            long to = i + 1 < ticks.size() ? ticks.get(i + 1) : endTick;
            int[] signature = events.get(from);
            Mode mode = signature[1] == 1 ? Mode.MINOR : Mode.MAJOR;
            double startBeat = from / (double) ticksPerQuarter;
            double endBeat = to / (double) ticksPerQuarter;
            double startSeconds = tempoMap.beatsToSeconds(startBeat);
            double endSeconds = tempoMap.beatsToSeconds(endBeat);
            // A key declared at the last tick of the piece is in force for no
            // time at all, and a zero-length span is not a key: Key rejects one,
            // so keeping it would fail the whole import at the very end with a
            // message about the model rather than about the file. Checked on
            // both axes because the quantized one is what notation reads.
            if (!(endSeconds > startSeconds) || !(endBeat > startBeat)) {
                continue;
            }
            keys.add(new Key(tonicOf(signature[0], mode), mode,
                    startSeconds, endSeconds,
                    Optional.of(startBeat), Optional.of(endBeat),
                    Confidence.CERTAIN));
        }
        if (!keys.isEmpty()) {
            progress.accept("read " + keys.size() + " key signature(s), opening in "
                    + keys.get(0).displayName());
        }
        return keys;
    }

    /**
     * The tonic a key signature names.
     *
     * <p>Walked round the circle of fifths rather than looked up in a table, so
     * that it is the same arithmetic {@link Key#keySignatureAccidentals()} runs
     * in reverse and the two cannot drift apart. Each step along the circle moves
     * four letters up the ladder and every seventh step adds an accidental; a
     * minor key sits three steps further round than the major that shares its
     * signature, which is what makes A minor and C major both zero sharps.
     *
     * <p>The octave is arbitrary because {@link Key} reads only the letter and
     * the accidental from its tonic; 4 is the octave of middle C.
     */
    private static PitchSpelling tonicOf(int sharpsOrFlats, Mode mode) {
        int fifths = mode == Mode.MINOR ? sharpsOrFlats + 3 : sharpsOrFlats;
        NoteLetter letter = NoteLetter.ofDiatonicStep(Math.floorMod(fifths * 4, 7));
        int alteration = Math.floorDiv(fifths + 1, 7);
        Accidental accidental = switch (alteration) {
            case -1 -> Accidental.FLAT;
            case 0 -> Accidental.NATURAL;
            case 1 -> Accidental.SHARP;
            default -> throw new IllegalArgumentException(
                    "a key signature of " + sharpsOrFlats + " cannot be spelled");
        };
        return new PitchSpelling(letter, accidental, 4);
    }

    // ------------------------------------------------------------------ naming

    /**
     * The piece's title, when the file carries one.
     *
     * <p>In a type 1 file the sequence name is the track-name meta of the
     * <em>first</em> track, which by convention holds tempo and meter and no
     * notes. So that is the only track consulted, and only when it has no notes:
     * in a type 0 file the single track's name is a track name rather than a
     * title, and there is no title to take.
     *
     * <p>Deliberately not "the first track that produced no notes", which an
     * earlier draft used to avoid depending on the file type. A named part whose
     * notes have been deleted -- what a muted or emptied track looks like in a
     * DAW export -- sits between the conductor track and the first sounding one,
     * and the piece was then titled after it.
     */
    private static String readTitle(Track[] tracks) {
        return hasNotes(tracks[0]) ? null : readTrackName(tracks[0]);
    }

    private static boolean hasNotes(Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData2() > 0) {
                return true;
            }
        }
        return false;
    }

    /** A track's name, preferring the track name over the instrument name. */
    private static String readTrackName(Track track) {
        String instrument = null;
        for (int i = 0; i < track.size(); i++) {
            if (!(track.get(i).getMessage() instanceof MetaMessage meta)) {
                continue;
            }
            if (meta.getType() == META_TRACK_NAME) {
                String name = cleanName(meta.getData());
                if (name != null) {
                    return name;
                }
            } else if (meta.getType() == META_INSTRUMENT_NAME && instrument == null) {
                instrument = cleanName(meta.getData());
            }
        }
        return instrument;
    }

    /**
     * A meta event's text, made safe to put in a file name, a staff label and a
     * LilyPond string.
     *
     * <p>MIDI does not say what encoding a text event uses. Most files are ASCII,
     * a good many modern ones are UTF-8, and the rest are some single-byte code
     * page. Decoding as UTF-8 first and falling back to Latin-1 -- which cannot
     * fail, since every byte is a code point -- gets the first two right and
     * turns the third into mojibake rather than into an exception.
     *
     * <p>Control characters are stripped because this string reaches a file name
     * and a LilyPond source file, and the length is capped because nothing stops
     * a file from carrying a megabyte of it.
     */
    private static String cleanName(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(data, StandardCharsets.ISO_8859_1);
        }
        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAX_NAME_LENGTH));
        int codePoints = 0;
        for (int i = 0; i < text.length() && codePoints < MAX_NAME_LENGTH; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            // Whole code points, so a name is never cut between the halves of a
            // surrogate pair and left holding an unpaired one -- which is not a
            // character, and which several of this project's downstream string
            // handlers would carry to somewhere it matters.
            cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            codePoints++;
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // ----------------------------------------------------------------- reading

    /** What to do with one meta event of a given type. */
    private interface MetaHandler {
        void accept(long tick, byte[] data);
    }

    /**
     * Visits every meta event of a type, in tick order across the whole file.
     *
     * <p>Tempo, meter and key events belong on the first track of a type 1 file,
     * but they are legal anywhere and real files put them elsewhere. Collecting
     * from every track and keying by tick is what makes those files import; where
     * two tracks carry an event at the same tick the last one visited wins, which
     * is arbitrary but so is the file.
     */
    private static void forEachMeta(Track[] tracks, int type, MetaHandler handler) {
        for (Track track : tracks) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof MetaMessage meta && meta.getType() == type) {
                    handler.accept(event.getTick(), meta.getData());
                }
            }
        }
    }

    private static byte[] readFully(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("not a readable file: " + file);
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "this file is " + size + " bytes, which is far larger than any score;"
                                + " refusing to read more than " + MAX_FILE_BYTES + ": " + file);
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
