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

package dev.olivelli.musicwizard.it;

import static dev.olivelli.musicwizard.it.LilyPondComplaints.TOLERATED_COMPLAINT;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static dev.olivelli.musicwizard.it.LilyPondComplaints.failedBarChecksIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.notation.StaffNotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole of #92, end to end: a human-timed MIDI file goes in and a page with
 * triplet brackets on it comes out.
 *
 * <p>Three merges landed the pieces separately — {@code MidiTranscriber} (#89),
 * {@code StaffNotation} (#90) and {@code Quantizer} (#91) — and each is tested
 * against its own fixtures. What none of them can test is that the grid the
 * quantizer chose is the grid the emitter printed, because that is a fact about
 * the join. So {@link PlayedTriplets} is <em>played</em> rather than typed, and
 * nothing in the test tells the quantizer which bars are triplet bars. If it
 * stopped choosing {@code THIRD_BEAT} for them the assertion on the emitted
 * source would fail, which is the right way round — the point is the agreement,
 * not either half of it.
 *
 * <p>And then it is engraved. A bar check that fails is a warning rather than an
 * error, so LilyPond will draw a triplet bar that does not add up and say
 * nothing that stops a build; treating any warning as a failure is what makes
 * this worth the seconds it costs. {@link StaffNotationIT} established that
 * pattern, {@link LilyPondComplaints#assertEngravedCleanly} now holds it, and
 * {@link #bracketsAreEngravedWithTheDurationTheyClaim} shows the check still has
 * teeth through a bracket, which is the new thing to doubt.
 */
class TupletEngravingIT {

    @TempDir
    Path tempDirectory;

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a played triplet passage survives import, quantization and engraving as triplets")
    void aPlayedTripletPassageIsEngravedAsTriplets() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        QuantizedScore quantized = PlayedTriplets.transcribed(4242);

        // The quantizer's own verdict, asserted before the emitter is asked
        // anything: bars three and four in triplets and the rest in eighths. If
        // that ever changes, the assertions below stop meaning what they say,
        // and this is where it becomes visible rather than confusing.
        assertThat(quantized.grids().stream().map(BarGrid::resolution).toList())
                .containsExactly(GridResolution.HALF_BEAT, GridResolution.HALF_BEAT,
                        GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT,
                        GridResolution.HALF_BEAT);
        NoteTrack voice = quantized.score().tracks().getFirst();
        // Sixteen eighths, twenty-four triplet eighths and eight of the run: the
        // importer dropped nothing, which is worth saying before reading the
        // page, since a bar can also come out right by losing a note.
        assertThat(voice.notes()).hasSize(48);

        String source = StaffNotation.toLilyPond(quantized, voice);

        // What a musician reads: the triplet bars bracketed, the plain ones left
        // plain, and the flats spelled as flats. An emitter that bracketed
        // everything would pass the first of these on its own.
        assertThat(source)
                .contains("\\tuplet 3/2 { des'8 ees'8 f'8 } \\tuplet 3/2 { aes'8 bes'8 aes'8 }")
                .contains("des'8 ees'8 f'8 aes'8 bes'8 aes'8 f'8 ees'8 |")
                .contains("c'8 des'8 d'8 ees'8 fes'8 f'8 ges'8 g'8 |")
                .doesNotContain("64");
        assertThat(source.lines().filter(line -> line.contains("\\tuplet")).count())
                .as("both triplet bars are bracketed and nothing else is")
                .isEqualTo(2);

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("triplets/part.ly"), source);
        assertEngravedCleanly("the triplet page", result);
        Path pdf = result.pdf().orElseThrow();
        assertThat(pageCount(pdf)).isEqualTo(1);
        assertThat(Files.size(pdf)).as("an empty page").isGreaterThan(10_000);
    }

    @Test
    @DisplayName("LilyPond still counts a bar whose bracket is short, so the clean run means something")
    void bracketsAreEngravedWithTheDurationTheyClaim() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        // The same bracket with one note too few in it. If LilyPond ever stopped
        // checking bars, or stopped reading \tuplet as a duration scaler, this
        // would go quiet -- and the warning-free assertions above would stop
        // proving anything, silently.
        String source = """
                \\version "2.24.0"
                \\score {
                  \\new Staff {
                    \\time #'(1 1 1 1) 4/4
                    \\tuplet 3/2 { c'8 d'8 } c'2. |
                    c'1 |
                    \\bar "|."
                  }
                  \\layout { }
                }
                """;
        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("short/bar.ly"), source);

        assertThat(result.succeeded()).isTrue();
        // A bracket holding two eighths where three were promised leaves the bar
        // a triplet eighth short, so the bar check fires at 11/12 rather than at
        // 1. Asserting the moment is what says \tuplet was read as a duration
        // scaler; asserting merely that LilyPond complained would not.
        assertThat(failedBarChecksIn(result.output()))
                .as("%s", result.output())
                .contains("11/12");
        // And the gate the clean runs above rest on fails here, on a real
        // binary, with a real failed bar check in whichever spelling this
        // LilyPond uses.
        //
        // It used to say it showed the *tolerance* did not swallow the bar
        // check, and round 2 of review on #164 measured that this run emits no
        // tolerated line at all, on either version -- so nothing was being
        // swallowed and nothing was proved. That claim is
        // LilyPondComplaintsTest.theToleranceIsNarrowerThanTheWordItContains's
        // to make, where a synthetic Result puts the two lines side by side
        // without asking LilyPond to produce both at once.
        assertThatThrownBy(() -> assertEngravedCleanly("short bracket", result))
                .as("a failed bar check did not fail the gate")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("a fully subdivided bar of every tuplet grid of every usable meter engraves")
    void everyTupletGridOfEveryUsableMeterEngraves() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        LilyPondRenderer renderer = new LilyPondRenderer(lilypond);

        // Meters, not shapes. Every bar here is fully subdivided, so every
        // bracket it writes is a complete one -- the partial brackets, the ties
        // out of brackets and the plain beats between them are the five cases
        // below, in one meter each. This sweep is the other axis.
        //
        // Not every meter the model admits, either: there are 448 of those and
        // the ones no one writes in are #131's business. These are the ones
        // music is written in -- which is not the same as saying they are all
        // clean, and #136 is exactly that distinction: the complaint recorded
        // there fires in 4/4 and 3/4, on a shape this sweep does not produce.
        List<TimeSignature> meters = List.of(
                new TimeSignature(4, 4), new TimeSignature(3, 4), new TimeSignature(2, 4),
                new TimeSignature(5, 4), new TimeSignature(7, 8), TimeSignature.SIX_EIGHT,
                new TimeSignature(9, 8), new TimeSignature(12, 8), new TimeSignature(6, 16),
                new TimeSignature(2, 2), new TimeSignature(3, 2), new TimeSignature(12, 16));

        int engraved = 0;
        for (TimeSignature meter : meters) {
            for (GridResolution resolution : GridResolution.values()) {
                // divisionsPerBeat 1 is #130: reported as a duplet in compound
                // time, and there is nothing under the bracket to engrave.
                if (!resolution.isTupletIn(meter) || resolution.divisionsPerBeat() == 1) {
                    continue;
                }
                String name = meter.numerator() + "-" + meter.denominator() + "-" + resolution;
                QuantizedScore quantized = everyPositionSounding(meter, resolution);
                String source = StaffNotation.toLilyPond(
                        quantized, quantized.score().tracks().getFirst());
                assertThat(source).as("%s has no bracket at all", name).contains("\\tuplet");

                LilyPondRenderer.Result result = renderer.renderSource(
                        tempDirectory.resolve("meters/" + name + "/part.ly"), source);
                assertEngravedCleanly(name, result);
                engraved++;
            }
        }
        // Twelve meters hold 29 tuplet grids between them. Pinned so that a
        // thirteenth meter, or a seventh GridResolution, has to come past this
        // line rather than quietly widening or narrowing what LilyPond is asked
        // to engrave -- and #136 is why that matters: the meters outside this
        // list are not all clean.
        assertThat(engraved).as("tuplet grids engraved across %d meters", meters.size())
                .isEqualTo(29);
    }

    @Test
    @DisplayName("every shape of bracket this emits is one LilyPond engraves without complaint")
    void everyBracketShapeEngraves() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        LilyPondRenderer renderer = new LilyPondRenderer(lilypond);

        record Case(String name, QuantizedScore quantized) {
        }
        List<Case> cases = List.of(
                new Case("triplet-eighths", evenBar(GridResolution.THIRD_BEAT, 3)),
                new Case("triplet-sixteenths", evenBar(GridResolution.SIXTH_BEAT, 6)),
                new Case("compound-duplets", compoundDuplets()),
                new Case("mixed", tripletsAmongPlainBeats()),
                new Case("pickup", pickupInsideABracket()));

        for (Case engraved : cases) {
            NoteTrack track = engraved.quantized().score().tracks().getFirst();
            String source = StaffNotation.toLilyPond(engraved.quantized(), track);
            assertThat(source).as("%s has no bracket at all", engraved.name()).contains("\\tuplet");

            LilyPondRenderer.Result result = renderer.renderSource(
                    tempDirectory.resolve(engraved.name() + "/part.ly"), source);
            assertEngravedCleanly(engraved.name(), result);
            assertThat(Files.size(result.pdf().orElseThrow()))
                    .as("%s is an empty page", engraved.name()).isGreaterThan(10_000);
        }
    }

    @Test
    @DisplayName("the complaint this suite tolerates is real, is reachable, and is only that one")
    void theToleratedComplaintIsReachableAndIsOnlyThisOne() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        // The material round 3 of review found this with, reduced to the notes
        // rather than kept as the LilyPond it produced, and shrunk from three
        // bars to the one that still complains.
        //
        // One bar of sixteenth triplets, on the SIXTH_BEAT grid, with a third of
        // the positions silent and the rest one or two steps long -- so most
        // brackets are partial -- over four octaves, which is what makes the
        // beams steep enough for the number to have nowhere to go. Reduced from
        // the three bars round 3 sent to the one bar that still complains.
        //
        // {step index, length in steps, MIDI pitch}
        int[][] played = {
                {0, 1, 87}, {1, 2, 86}, {3, 1, 72}, {4, 1, 74}, {5, 1, 72},
                {6, 1, 76}, {7, 1, 68}, {8, 2, 82}, {11, 2, 54}, {13, 1, 67},
                {16, 1, 56}, {18, 2, 75}, {21, 1, 82}, {22, 2, 47}};
        double step = 1.0 / 6;
        List<Note> notes = new ArrayList<>(played.length);
        for (int[] event : played) {
            notes.add(note(event[0] * step, event[1] * step, event[2]));
        }
        QuantizedScore quantized = verdict(TimeSignature.FOUR_FOUR, 89, notes,
                GridResolution.SIXTH_BEAT);
        NoteTrack voice = quantized.score().tracks().getFirst();
        String source = StaffNotation.toLilyPond(quantized, voice);

        // Driven through the emitter, so this says the shape is one *we* write.
        // Round 4 of review pointed out that engraving hand-copied LilyPond pins
        // LilyPond's behaviour and says nothing about ours -- and a tolerance
        // left standing for a shape this emitter can no longer produce is
        // exactly the dead carve-out worth avoiding. The day StaffNotation stops
        // writing partial sixteenth-triplet brackets, this fails here rather
        // than leaving the tolerance to cover whatever comes next.
        assertThat(source).contains("\\tuplet 3/2 { ").contains("16 ");

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("tolerated/part.ly"), source);

        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
        assertThat(result.output())
                .as("the tolerated complaint is no longer reachable; re-read #136 before"
                        + " widening anything on the strength of it")
                .contains(TOLERATED_COMPLAINT);
        // And the music is still right underneath it: a page came out, and the
        // bar checks -- which is what the warning ban is really for -- passed.
        // Round 4 rendered this page at 900 dpi and looked at both complaint
        // sites: the tuplet number is present and legible, placed above the
        // steep beam rather than inside it. Nothing missing, nothing colliding.
        // Round 5 of review found this written "barcheck" and corrected it to
        // "bar check", on the grounds that "barcheck" is a string LilyPond never
        // emits. Both halves were half right and #145 is the other half: 2.26
        // spells it with the space and 2.24 -- which is what the integration job
        // installs -- without, so whichever of the two was written here, this
        // assertion could not fail on one of the two versions in use. Asking for
        // the moments rather than for the prose is what removes the question.
        assertThat(failedBarChecksIn(result.output())).isEmpty();
        assertThat(result.pdf()).isPresent();
        // The only call site in the module that names the tolerance, and the
        // only one that reaches it. Round 2 of review on #164 stripped the
        // argument from all six sites in this file and got exactly one failure,
        // here -- so the other five were carrying a carve-out for a line their
        // fixtures never produce, on either LilyPond version, which is the dead
        // carve-out the argument was introduced to make visible. They no longer
        // do. If one of them starts complaining, that is a fact about the
        // emitter and it should go red rather than be tolerated by inheritance.
        assertEngravedCleanly("the tolerated case", result, TOLERATED_COMPLAINT);
    }

    @Test
    @DisplayName("a part whose file name is not ASCII still engraves")
    void aNonAsciiFileNameStillEngraves() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        LilyPondRenderer renderer = new LilyPondRenderer(lilypond);

        // Round 5 of review found round 4's locale fix breaking this outright:
        // LC_ALL and LANG set the character type as well as the message
        // catalogue, and LilyPond cannot decode a non-ASCII name off its own
        // command line under a C ctype -- "fatal error: failed files:
        // canci??n.ly". A fix for a test that had silently stopped checking had
        // become a fix that silently stopped engraving.
        //
        // It is here rather than in mw-notation because only a real LilyPond can
        // show it: the unit test beside speakEnglish pins which variables are
        // touched, and this pins what that buys. The tool writes one file per
        // part and a part is named after the song, so this is the ordinary case
        // for most of the world rather than an exotic one.
        // A JVM whose sun.jnu.encoding is not UTF-8 -- a bare container, cron,
        // a systemd unit with no locale at all -- cannot turn these into a Path
        // in the first place, and would throw InvalidPathException long before
        // reaching LilyPond. That is a fact about the JVM rather than about this
        // emitter, so it is skipped and said out loud rather than failed.
        assumeThat(System.getProperty("sun.jnu.encoding", ""))
                .as("the JVM cannot represent a non-ASCII file name")
                .containsIgnoringCase("UTF");

        QuantizedScore quantized = tripletsAmongPlainBeats();
        String source = StaffNotation.toLilyPond(
                quantized, quantized.score().tracks().getFirst());

        for (String name : List.of("canción", "Präludium", "夜曲", "Пример")) {
            LilyPondRenderer.Result result = renderer.renderSource(
                    tempDirectory.resolve("names/" + name + ".ly"), source);
            assertThat(result.succeeded()).as("%s: %s", name, result.output()).isTrue();
            assertEngravedCleanly(name, result);
            assertThat(result.pdf()).as("%s produced no page", name).isPresent();
        }
    }

    // -------------------------------------------------------- built fixtures

    private static Note note(double onsetBeat, double beats, int midiPitch) {
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, midiPitch, Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats);
    }

    /** A hand-built quantizer verdict: one grid per bar, in bar order. */
    private static QuantizedScore verdict(TimeSignature meter, double tempo, List<Note> notes,
                                          GridResolution... perBar) {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = Score.empty(TempoMap.constant(tempo, meter), 60).withTrack(voice);
        List<BarGrid> grids = new ArrayList<>(perBar.length);
        double startBeat = 0;
        for (int bar = 0; bar < perBar.length; bar++) {
            grids.add(new BarGrid(bar, startBeat, perBar[bar], meter));
            startBeat += meter.quarterBeatsPerBar();
        }
        return new QuantizedScore(score, grids, SwingFeel.STRAIGHT);
    }

    /** One bar of the meter with a note on every position of the grid. */
    private static QuantizedScore everyPositionSounding(TimeSignature meter,
                                                        GridResolution resolution) {
        double step = resolution.stepQuarters(meter);
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < resolution.divisionsPerBar(meter); i++) {
            notes.add(note(i * step, step, 60 + i % 12));
        }
        return verdict(meter, 120, notes, resolution);
    }

    /** One 4/4 bar divided {@code perBeat} ways, with every position sounding. */
    private static QuantizedScore evenBar(GridResolution resolution, int perBeat) {
        double step = 1.0 / perBeat;
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < 4 * perBeat; i++) {
            notes.add(note(i * step, step, 60 + i % 12));
        }
        return verdict(TimeSignature.FOUR_FOUR, 120, notes, resolution);
    }

    /** A 6/8 bar in duplets, which is the compound half of the ratio. */
    private static QuantizedScore compoundDuplets() {
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            notes.add(note(i * 0.75, 0.75, 60 + i));
        }
        return verdict(TimeSignature.SIX_EIGHT, 180, notes, GridResolution.HALF_BEAT);
    }

    /** Triplets, a held beat that needs no bracket, and a tie out of one over a bar line. */
    private static QuantizedScore tripletsAmongPlainBeats() {
        List<Note> notes = List.of(
                note(0, 1.0 / 3, 60), note(1.0 / 3, 2.0 / 3, 62),
                note(1, 2, 64),
                note(3, 1.0 / 3, 65), note(3 + 1.0 / 3, 1.0 / 3, 67),
                note(3 + 2.0 / 3, 1.0 / 3 + 2, 69),
                note(6, 2, 71));
        return verdict(TimeSignature.FOUR_FOUR, 120, notes,
                GridResolution.THIRD_BEAT, GridResolution.HALF_BEAT);
    }

    /** A pickup entering two triplet eighths before the first bar line. */
    private static QuantizedScore pickupInsideABracket() {
        List<Note> notes = List.of(
                note(3 + 1.0 / 3, 1.0 / 3, 67), note(3 + 2.0 / 3, 1.0 / 3, 69),
                note(4, 4, 72));
        return verdict(TimeSignature.FOUR_FOUR, 120, notes,
                GridResolution.THIRD_BEAT, GridResolution.BEAT);
    }

    /** Pages in a PDF, read from the page objects rather than from the trailer. */
    private static int pageCount(Path pdf) throws Exception {
        // Latin-1 so every byte maps to one character and no byte is lost to a
        // decoding error; the structure being matched is ASCII.
        String text = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile("/Type\\s*/Page[^s]").matcher(text);
        int pages = 0;
        while (matcher.find()) {
            pages++;
        }
        return pages;
    }
}
