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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.PitchSpeller;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.notation.StaffNotation;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.midi.Sequence;
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
 * the join. So the fixture here is <em>played</em> rather than typed: the onsets
 * carry 25 ms of Gaussian timing, exactly as the reproduction on the issue does,
 * and nothing in the test tells the quantizer which bars are triplet bars. If it
 * stopped choosing {@code THIRD_BEAT} for them the assertion on the emitted
 * source would fail, which is the right way round — the point is the agreement,
 * not either half of it.
 *
 * <p>And then it is engraved. A bar check that fails is a warning rather than an
 * error, so LilyPond will draw a triplet bar that does not add up and say
 * nothing that stops a build; treating any warning as a failure is what makes
 * this worth the seconds it costs. {@link StaffNotationIT} established that
 * pattern, and {@link #bracketsAreEngravedWithTheDurationTheyClaim} shows the
 * check still has teeth through a bracket, which is the new thing to doubt.
 */
class TupletEngravingIT {

    /** Onset spread of a decent human player, which is what the fixture plays with. */
    private static final double JITTER_SECONDS = 0.025;

    private static final double TEMPO_BPM = 120;

    @TempDir
    Path tempDirectory;

    // ---------------------------------------------------------- played fixture

    /**
     * The reproduction from #92: D flat major, eighths in bars 1 and 2, triplet
     * eighths in bars 3 and 4, a chromatic run in bar 5.
     *
     * <p>The chromatic run is not decoration. It is what makes the page worth
     * looking at as music rather than as arithmetic: in D flat it has to spell
     * all flats, and a triplet bar engraved correctly beside a run spelled
     * wrongly is still not a lead sheet.
     *
     * @param seed fixed per call, so a failure is reproducible and a marginal
     *             pass is visible rather than intermittent
     */
    private static Sequence playedTriplets(long seed) {
        Random random = new Random(seed);
        int ticks = MidiFixtures.TICKS_PER_QUARTER;
        MidiFixtures.SequenceBuilder.PartBuilder part = MidiFixtures.sequence(ticks)
                .name("Triplets").tempo(TEMPO_BPM).timeSignature(4, 4)
                .keySignature(-5, Mode.MAJOR)
                .part("Voice", 0).program(0);

        int[] eighths = {61, 63, 65, 68, 70, 68, 65, 63};
        for (int bar = 0; bar < 2; bar++) {
            for (int i = 0; i < 8; i++) {
                part.note(played(bar * 4 + i * 0.5, random, ticks), 0.45, eighths[i]);
            }
        }
        int[] triplets = {61, 63, 65, 68, 70, 68, 65, 63, 61, 63, 65, 68};
        for (int bar = 2; bar < 4; bar++) {
            for (int i = 0; i < 12; i++) {
                part.note(played(bar * 4 + i / 3.0, random, ticks), 0.30, triplets[i]);
            }
        }
        int[] chromatic = {60, 61, 62, 63, 64, 65, 66, 67};
        for (int i = 0; i < 8; i++) {
            part.note(played(16 + i * 0.5, random, ticks), 0.45, chromatic[i]);
        }
        return part.end().build();
    }

    /**
     * A nominal beat position as it was actually played, rounded to the file's
     * own tick grid.
     *
     * <p>Truncated at three sigma so one draw cannot invent a note somewhere
     * else entirely, and rounded to ticks because that is the only resolution a
     * MIDI file has — a fixture asking for a position between two ticks would be
     * silently moved and would stop being ground truth.
     */
    private static double played(double nominalBeat, Random random, int ticksPerQuarter) {
        double sigma = Math.clamp(random.nextGaussian(), -3, 3);
        double beat = nominalBeat + sigma * JITTER_SECONDS * TEMPO_BPM / 60.0;
        return Math.max(0, Math.round(beat * ticksPerQuarter)) / (double) ticksPerQuarter;
    }

    /** Import, quantize and spell, which is what a symbolic run of the pipeline is. */
    private static QuantizedScore transcribed(long seed) {
        Score imported = new MidiTranscriber().transcribe(playedTriplets(seed));
        QuantizedScore quantized = Quantizer.quantize(imported);
        return quantized.withScore(PitchSpeller.spell(quantized.score()));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a played triplet passage survives import, quantization and engraving as triplets")
    void aPlayedTripletPassageIsEngravedAsTriplets() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        QuantizedScore quantized = transcribed(4242);

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
        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
        assertThat(result.output())
                .as("the triplet page engraved with complaints")
                .doesNotContainIgnoringCase("warning")
                .doesNotContainIgnoringCase("error");
        Path pdf = result.pdf().orElseThrow();
        assertThat(pageCount(pdf)).isEqualTo(1);
        assertThat(Files.size(pdf)).as("an empty page").isGreaterThan(10_000);
    }

    @Test
    @DisplayName("the same fixture without the grids is still the noise the issue reported")
    void theScoreOverloadStillProducesTheReportedNoise() {
        // Not a check on LilyPond, which accepts both -- that is the whole
        // problem -- but on the difference between the two overloads being real,
        // and being this difference. Pinned character for character against what
        // #92 reported, so a change that quietly made the Score overload guess
        // would show up here rather than in a golden nobody reads as music.
        QuantizedScore quantized = transcribed(4242);
        NoteTrack voice = quantized.score().tracks().getFirst();

        assertThat(StaffNotation.toLilyPond(quantized.score(), voice))
                .doesNotContain("\\tuplet")
                .contains("des'16~ des'64 ees'32.~ ees'32. f'64~ f'16");
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
        assertThat(result.output()).containsIgnoringCase("bar check failed");
    }

    @Test
    @DisplayName("every tuplet grid of every meter anyone writes engraves without complaint")
    void everyTupletGridOfEveryUsableMeterEngraves() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        LilyPondRenderer renderer = new LilyPondRenderer(lilypond);

        // Not every meter the model admits -- there are 448 of those and the
        // absurd ones are #131's business. These are the ones music is written
        // in, crossed with every grid GridResolution calls a tuplet in them, and
        // that is 29 combinations rather than the five shapes below.
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
                assertThat(result.succeeded()).as("%s: %s", name, result.output()).isTrue();
                assertThat(result.output())
                        .as("%s engraved with complaints", name)
                        .doesNotContainIgnoringCase("warning")
                        .doesNotContainIgnoringCase("error");
                engraved++;
            }
        }
        assertThat(engraved).as("the sweep engraved nothing").isEqualTo(29);
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
            assertThat(result.succeeded()).as("%s: %s", engraved.name(), result.output()).isTrue();
            assertThat(result.output())
                    .as("%s engraved with complaints", engraved.name())
                    .doesNotContainIgnoringCase("warning")
                    .doesNotContainIgnoringCase("error");
            assertThat(Files.size(result.pdf().orElseThrow()))
                    .as("%s is an empty page", engraved.name()).isGreaterThan(10_000);
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
