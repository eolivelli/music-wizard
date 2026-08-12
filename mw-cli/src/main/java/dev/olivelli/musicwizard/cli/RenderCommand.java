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

package dev.olivelli.musicwizard.cli;

import dev.olivelli.musicwizard.arrange.ChordSpeller;
import dev.olivelli.musicwizard.arrange.Transposer;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.notation.LyricSheet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Produces sheet music from an analysed workspace.
 *
 * <p>LilyPond source is written for every part that can be produced; the PDF is
 * produced only if a LilyPond binary can be found. A missing binary degrades the
 * output rather than failing the command, since the source is still useful on
 * its own. MusicXML and MIDI export are named in the epic and are not written by
 * anything yet, so this says so rather than listing them.
 *
 * <p>The same posture governs the parts themselves, which is what #82 was
 * about. This command used to announce {@code voice, piano, bass, chords} and
 * then write only the chord files, with exit status 0 — so a user could not tell
 * whether three parts had failed, been skipped, or never existed. Melody, bass
 * and piano are M2 and M4 work; a part that cannot be produced is now named
 * along with the reason, exactly as a missing engraver is. The default is
 * therefore what is actually implemented, and naming an unimplemented part
 * explicitly is answered rather than ignored.
 *
 * <p>Non-zero exit is reserved for producing <em>nothing at all</em>. Writing
 * three parts of four is a partial success and the command says which three; a
 * run that wrote nothing has failed at the only thing it was asked to do, and a
 * script chaining {@code render} needs to be able to tell.
 *
 * <p>A file that was written and is <em>wrong</em> falls under the same rule,
 * and that is #156. LilyPond treats a bar that does not fill its meter as a
 * warning: it engraves the short bar, exits zero and writes a real PDF, so a
 * command that branches on the exit status alone can only print
 * {@code Wrote .../chords.pdf} about a page whose music does not match the
 * transcription. This one now names the bars, on stderr, immediately after the
 * list of files — and still exits zero, because the other outputs are intact and
 * a script losing all of them over a defect it cannot act on is worse than one
 * told plainly what is wrong. See {@link #wrongBars}.
 *
 * <p><b>This is reachable through this command, and was not when it was
 * written.</b> {@code ChordChart.toLilyPond} emitted no {@code |} at all, so
 * nothing engraved here could fail a bar check and the fact existed only in
 * {@code StaffNotation}'s output, which only {@code mw-it} engraved. #160 gave
 * the chord chart a {@code \time} and a bar check per bar, so a defect in what
 * this command engraves now reaches the user as this warning instead of as a
 * silently wrong page. The lyric sheet is engraved too, and its bar checks cover
 * the lyric line as well as the chords. No valid input produces one, which is
 * what the checks are for; what changed is the failure mode. A staff part
 * (#8, #10) adds a second source of it rather than the first.
 */
@Command(name = "render", description = "Generate sheet music from a workspace.")
final class RenderCommand implements Callable<Integer> {

    /**
     * A part this command knows how to be asked for.
     *
     * <p>The unimplemented ones are listed here rather than left out, because
     * being able to answer "not yet, and here is the issue" is the whole point:
     * a name this command does not recognise at all is a typo, and deserves a
     * different answer from a name it recognises and cannot yet honour.
     */
    private enum Part {
        CHORDS("chords", null, RenderCommand::writeChordChart),
        LYRICS("lyrics", null, RenderCommand::writeLyricSheet),
        VOICE("voice", "melody transcription is not implemented yet (#8)", null),
        BASS("bass", "bass transcription is not implemented yet (#8)", null),
        PIANO("piano", "the piano reduction is not implemented yet (#10)", null);

        private final String partName;
        private final String notImplemented;
        private final Emitter emitter;

        Part(String partName, String notImplemented, Emitter emitter) {
            this.partName = partName;
            this.notImplemented = notImplemented;
            this.emitter = emitter;
        }

        String partName() {
            return partName;
        }

        /**
         * Whether anything can produce this part.
         *
         * <p>Answered by the emitter's presence rather than by a second field,
         * so that "implemented" and "there is code to run" cannot disagree. They
         * were two separate places -- a {@code notImplemented} string and a
         * {@code switch} on the constant -- and a part marked implemented with no
         * branch to match would have been selected, produced nothing, and ended
         * the run with "Nothing could be written." and no reason: #82's defect
         * reached from the inside. Unreachable while every implemented part has
         * an emitter, and reachable the moment one is added without, which is
         * when nobody will be looking for it.
         */
        boolean isImplemented() {
            return emitter != null;
        }

        Emitted emit(Workspace workspace, Score score, Optional<Path> lilypond) {
            return emitter.emit(workspace, score, lilypond);
        }

        /**
         * Why this part cannot be produced from this score, or {@code null} when
         * it can.
         *
         * <p>Answered from the <em>score</em> and from nothing else. An earlier
         * version worded the empty-harmony case differently depending on whether
         * the workspace's source file was MIDI or audio, which it re-sniffed here
         * -- and that was wrong twice. It made {@code render} fail outright on a
         * workspace whose recording had been deleted, though the score alone
         * holds everything an engraver needs; and after a source file was
         * replaced it described a MIDI-derived score as audio-derived, which is
         * the same quietly-wrong answer this change exists to remove, one layer
         * down. Provenance re-derived by each reader is provenance that can
         * disagree between readers; carrying it on the score is #120.
         *
         * <p>The wording of the empty-harmony case now lives in
         * {@link MissingHarmony}, and this method does not have its own copy of
         * it. Round 3 found the third divergent wording of the same explanation
         * -- {@code analyze} had one too, and on a conductor-track-only file the
         * two commands contradicted each other three lines apart. Two rounds of
         * fixing this in place was the signal to remove the choice rather than
         * make the same edit again.
         */
        String unavailableReason(Score score) {
            if (notImplemented != null) {
                return notImplemented;
            }
            if (this == CHORDS && score.chords().isEmpty()) {
                return "this score holds no chord progression, " + MissingHarmony.explain(score);
            }
            // Gated on lyrics and not on harmony: a lyric sheet with no chords
            // is still a lyric sheet, which is exactly the case MissingHarmony's
            // javadoc names when it says this output needs neither a chord
            // progression nor a note track to be worth printing.
            //
            // Names no source kind, for the reason the rest of this method does
            // not: what would fill this field differs by path -- recognition on
            // one, lyric meta events on the other -- and this command cannot
            // tell which score it is holding.
            if (this == LYRICS && score.lyrics().isEmpty()) {
                return "this score holds no lyrics; analyze with --lyrics and an"
                        + " LRC file, or with --lyrics-language alone to ask for"
                        + " transcription";
            }
            return null;
        }

        static Optional<Part> byName(String name) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            for (Part part : values()) {
                if (part.partName.equals(wanted)) {
                    return Optional.of(part);
                }
            }
            return Optional.empty();
        }

        static List<Part> implemented() {
            return java.util.Arrays.stream(values()).filter(Part::isImplemented).toList();
        }
    }

    /**
     * What producing one part left behind: the files, and anything the user has
     * to be told about them.
     *
     * <p>The warnings are returned rather than printed where they arise, so they
     * can be printed <em>after</em> the list of files written. A warning that the
     * PDF's bars do not sum is only useful next to the line saying the PDF was
     * written; printed before it, it reads as a reason the file is missing. That
     * pairing is what #156 asked for, and
     * {@code RenderDiagnosticsTest.theWarningComesAfterTheFileItIsAbout} is what
     * holds it — round 1 of
     * review found the ordering untestable through two separate capture buffers,
     * so moving the block back left every test green.
     *
     * @param files    every file produced, in the order to report them
     * @param warnings complete lines, already worded for a user, no prefix
     */
    private record Emitted(List<Path> files, List<String> warnings) {
    }

    /** How one part is produced, once it is known that it can be. */
    @FunctionalInterface
    private interface Emitter {
        Emitted emit(Workspace workspace, Score score, Optional<Path> lilypond);
    }

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--parts", split = ",", paramLabel = "PART",
            description = "Which parts to render: chords, lyrics, voice, bass, piano. "
                    + "Only chords and lyrics can be produced today; naming any of the "
                    + "others reports why it cannot. Defaults to every part that is "
                    + "implemented.")
    List<String> parts;

    @Option(names = "--transpose", paramLabel = "SEMITONES",
            description = "Transpose every part by this many semitones, up to "
                    + Transposer.MAX_SEMITONES + " either way. The key moves with the "
                    + "chords, and both are written for the key the piece lands in.")
    Integer transpose;

    @Option(names = "--paper", paramLabel = "SIZE",
            description = "Paper size, e.g. a4 or letter. Has no effect yet: nothing "
                    + "reads it (#180).")
    String paperSize;

    @Option(names = "--no-pdf", description = "Write sources only; do not invoke LilyPond.")
    boolean noPdf;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        List<Part> requested = requestedParts();
        int semitones = requestedTransposition(config);
        warnAboutOptionsThatDoNothing(config);

        // The score is read before anything is announced, because what can be
        // produced is a property of it. Announcing an output directory and an
        // engraver and then writing nothing is the same defect #82 was filed
        // for, one line further down the same command.
        Score score = workspace.readScore().orElseThrow(() -> new IllegalStateException(
                "no transcription yet; run: mw analyze " + workspaceDirectory));

        // Before the speller, and that order is the whole design: the transposer
        // moves the key and every sounding pitch, and the speller then writes
        // the harmony from the key the piece landed in. The other way round, the
        // chart would be spelled for the key it came from.
        Score asAnalysed = score;
        Transposer.Result transposed = Transposer.transpose(score, semitones);
        score = transposed.score();

        // Once, here, rather than in each emitter. The estimator writes every
        // black key as a sharp from a fixed table, so a chart in B flat major
        // printed as A# F Gm D# (#227); ChordSpeller decides how the harmony is
        // written from the piece as a whole. It is applied to the score every
        // part then reads, so the chart, the staff parts and the exports cannot
        // spell the same chord three ways -- the failure this command already
        // has a history of, one layer up. The workspace's saved transcription
        // keeps the estimate untouched: this is a decision about the page.
        score = ChordSpeller.respell(score);

        List<Part> producible = new ArrayList<>();
        List<String> notWritten = new ArrayList<>();
        for (Part part : requested) {
            String reason = part.unavailableReason(score);
            if (reason != null) {
                notWritten.add(String.format("  %-8s %s", part.partName(), reason));
            } else {
                producible.add(part);
            }
        }

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Parts      " + String.join(", ",
                requested.stream().map(Part::partName).toList()));
        if (semitones != 0) {
            System.out.println("Transpose  " + transposition(semitones, asAnalysed, score));
        }

        List<Path> written = new ArrayList<>();
        // Seeded with whatever the shift could not move, so that a part left out
        // is reported next to the files, under the same rule as a PDF whose bars
        // do not sum.
        List<String> warnings = new ArrayList<>(transposed.partsLeftOut());
        boolean chartWritten = false;
        if (!producible.isEmpty()) {
            System.out.println("Output     " + workspace.outputDirectory());
            Optional<Path> lilypond = announceEngraver(config);
            for (Part part : producible) {
                Emitted emitted = part.emit(workspace, score, lilypond);
                written.addAll(emitted.files());
                warnings.addAll(emitted.warnings());
                chartWritten |= part == Part.CHORDS;
            }
        }

        if (!written.isEmpty()) {
            System.out.println();
            written.forEach(file -> System.out.println("Wrote " + file));
        }
        if (!warnings.isEmpty()) {
            // On stderr, and after the files, so that "the PDF exists" and "the
            // PDF is wrong" arrive together and the second survives a stdout
            // that has been redirected -- this command's last act is to print
            // the chart itself, which is exactly the thing someone pipes.
            System.err.println();
            warnings.forEach(warning -> System.err.println("warning: " + warning));
        }
        if (!notWritten.isEmpty()) {
            System.out.println();
            System.out.println("Not written:");
            notWritten.forEach(System.out::println);
        }

        if (written.isEmpty()) {
            System.out.println();
            System.out.println("Nothing could be written.");
            return CommandLine.ExitCode.SOFTWARE;
        }
        if (chartWritten) {
            System.out.println();
            System.out.println(ChordChart.toText(score));
        }
        return CommandLine.ExitCode.OK;
    }

    /**
     * The parts to attempt.
     *
     * <p>Absent rather than a four-name default, so that "the user asked for
     * everything that works" and "the user asked for piano" stay distinguishable:
     * the second deserves an answer about piano and the first does not.
     */
    private List<Part> requestedParts() {
        if (parts == null) {
            return Part.implemented();
        }
        // Ordered and de-duplicated: --parts chords,chords should not write the
        // chart twice, and the order the user typed is the order to report in.
        LinkedHashSet<Part> resolved = new LinkedHashSet<>();
        for (String name : parts) {
            resolved.add(Part.byName(name).orElseThrow(() -> new CommandLine.ParameterException(
                    spec.commandLine(),
                    "unknown part '" + name + "'; expected one of: " + String.join(", ",
                            java.util.Arrays.stream(Part.values()).map(Part::partName).toList()))));
        }
        if (resolved.isEmpty()) {
            // Reachable, and round 3 removed this guard on the strength of an
            // argument rather than a run. picocli requires at least one
            // *argument* for --parts, not one value: with split=",", the argument
            // "," splits into none. Without this the command printed an empty
            // parts line and exited 1 -- the code it reserves for "the score had
            // nothing to engrave" -- for what is a usage error, with no message.
            //
            // "--parts ''" and "chords,,voice" do not reach here, because an
            // empty name among others is an unrecognised name and is refused
            // above. Only the pure-separator forms do, which is exactly why the
            // reasoning was believable and why it needed executing.
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "--parts was given no part names");
        }
        return List.copyOf(resolved);
    }

    /** Reports which engraver will be used, and returns it. */
    private Optional<Path> announceEngraver(MusicWizardConfig config) {
        if (noPdf) {
            System.out.println("Engraver   skipped (--no-pdf)");
            return Optional.empty();
        }
        // Resolved once. It used to be looked up twice, which on a machine whose
        // PATH changed between the two calls would announce one binary and run
        // another.
        Optional<Path> lilypond = ConfigLoader.findLilyPond(config);
        if (lilypond.isPresent()) {
            System.out.println("Engraver   " + lilypond.get());
            return lilypond;
        }
        System.out.println("Engraver   not found");
        System.out.println();
        System.out.println("LilyPond is not installed, so no PDF will be produced.");
        // Named exactly, and no longer ".ly, .musicxml and .midi": nothing emits
        // MusicXML or MIDI yet, and this command promising two files it does not
        // write is the same defect as the parts list #82 was filed for.
        System.out.println("The .ly source is still written, and can be engraved");
        System.out.println("elsewhere. To install it:");
        System.out.println("  brew install lilypond      (macOS, or Homebrew on Linux)");
        System.out.println("  apt install lilypond       (Debian or Ubuntu)");
        System.out.println("Or set notation.lilypondPath in the workspace config.");
        return Optional.empty();
    }

    /** Writes the chord chart's sources, and its PDF where there is an engraver. */
    private static Emitted writeChordChart(
            Workspace workspace, Score score, Optional<Path> lilypond) {
        Path out = workspace.outputDirectory();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectories(out);
            Path txt = out.resolve("chords.txt");
            Files.writeString(txt, ChordChart.toText(score));
            written.add(txt);

            Path ly = out.resolve("chords.ly");
            Files.writeString(ly, ChordChart.toLilyPond(score));
            written.add(ly);

            Emitted engraved = engrave(lilypond, ly);
            written.addAll(engraved.files());
            warnings.addAll(engraved.warnings());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write output", e);
        }
        return new Emitted(written, warnings);
    }

    /** Writes the chords-and-lyrics sheet, and its PDF where there is an engraver. */
    private static Emitted writeLyricSheet(
            Workspace workspace, Score score, Optional<Path> lilypond) {
        Path out = workspace.outputDirectory();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectories(out);
            Path txt = out.resolve("chords-lyrics.txt");
            Files.writeString(txt, LyricSheet.toText(score));
            written.add(txt);

            Path ly = out.resolve("chords-lyrics.ly");
            Files.writeString(ly, LyricSheet.toLilyPond(score));
            written.add(ly);

            Emitted engraved = engrave(lilypond, ly);
            written.addAll(engraved.files());
            warnings.addAll(engraved.warnings());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write output", e);
        }
        return new Emitted(written, warnings);
    }

    /**
     * Engraves one source and reports both the PDF and anything wrong with it.
     *
     * <p>Every emitter goes through here rather than calling
     * {@link LilyPondRenderer} itself, and that is the point of the method
     * existing: reading {@link LilyPondRenderer.Result#failedBarChecks()} is not
     * something a future emitter should have to <em>remember</em> to do. Round 1
     * of review named it — chords are the only part today, and #8 and #10 add
     * three more, each of which would otherwise have its own copy of "render,
     * add the PDF, and also check the bars", with the third copy the one that
     * forgets. This project's recorded dominant failure mode is a fix that
     * reaches one caller and misses another; removing the choice is what
     * {@code Score.estimatedTempo()} came from.
     *
     * <p>The failure branch prints where it stands rather than returning a
     * warning, and deliberately: it is not a warning about a file, it is the
     * reason there is no file. Its output is LilyPond's own, quoted, and it
     * belongs next to the engraver line rather than after a list it is not in.
     */
    private static Emitted engrave(Optional<Path> lilypond, Path ly) {
        if (lilypond.isEmpty()) {
            return new Emitted(List.of(), List.of());
        }
        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond.get()).render(ly);
        if (!result.succeeded()) {
            // Reported, not thrown: the sources are still useful, and losing
            // them because the engraver failed would be worse.
            System.out.println();
            System.out.println("LilyPond could not engrave the chart:");
            result.output().lines().limit(10)
                    .forEach(line -> System.out.println("  " + line));
            return new Emitted(List.of(), List.of());
        }
        return new Emitted(List.of(result.pdf().orElseThrow()),
                wrongBars(result, ly).stream().toList());
    }

    /** How many failed bar checks are worth naming before the list stops being read. */
    private static final int MOMENTS_LISTED = 8;

    /**
     * What to tell the user about a PDF LilyPond wrote and then said was wrong.
     *
     * <p>This is #156. A failed bar check is a <em>warning</em> to LilyPond: it
     * engraves the short bar, exits zero and writes a real PDF, so a caller that
     * reads only {@code succeeded()} has nothing to print but
     * {@code Wrote .../chords.pdf}. The whole integration suite bans this one
     * diagnostic on the grounds that it means the engraved music is wrong, and
     * the shipped tool had no way to read it — the project's own thesis pointed
     * at its tests but not at its output.
     *
     * <p>Only this diagnostic, and not "LilyPond said something". #136 records a
     * tuplet-number placement complaint that LilyPond words as a {@code
     * programming error}, that is reachable from ordinary 4/4 about once in
     * eighty staves, and whose page is correct; #92 found benign diagnostics
     * carrying the word {@code error} as well. Warning on those would train the
     * user to ignore the line that matters.
     *
     * <p>The exit status stays zero, which is the command's existing rule rather
     * than a new one: non-zero is reserved for producing <em>nothing at all</em>,
     * and the {@code .txt}, {@code .ly} and {@code .pdf} were all produced. The
     * argument for failing is that a wrong PDF is worse than a missing one; the
     * argument against, which wins here, is that the wrongness is in bars a user
     * can see named on this line, the other outputs are unaffected and useful,
     * and a script that chains {@code render} would lose all of them over a
     * defect it cannot act on. It is the same posture a missing LilyPond binary
     * gets: emit what you can, say plainly what is wrong.
     *
     * <p><b>The count is how many times LilyPond complained, not how many bars
     * are wrong, and the wording says so.</b> Round 1 of review measured 2.26.0
     * on a score with three staves each short by a different amount: one
     * warning, naming one moment, from the last staff — because {@code
     * Timing_translator} lives at the {@code Score} level from 2.25.6 and
     * reports once per context rather than once per failure. So the number is a
     * floor. Saying "the bar at 3/4 is wrong" would be the overclaim this
     * project treats as a defect in its own right on a tool whose output is
     * estimates; "LilyPond reported N, at these moments, check the rest too" is
     * what the evidence supports.
     */
    private static Optional<String> wrongBars(LilyPondRenderer.Result result, Path ly) {
        List<String> moments = result.failedBarChecks();
        if (moments.isEmpty()) {
            return Optional.empty();
        }
        // Capped rather than joined whole: LilyPond 2.24 reports every failure
        // in the part, so a score that goes wrong early can produce hundreds,
        // and a warning line long enough to scroll off is a warning nobody
        // reads. 2.25.6 onwards reports only the first per context, so the cap
        // is version-dependent in when it bites and must not be relied on to.
        String named = String.join(", ", moments.subList(0, Math.min(MOMENTS_LISTED,
                moments.size())));
        if (moments.size() > MOMENTS_LISTED) {
            named += ", and " + (moments.size() - MOMENTS_LISTED) + " more";
        }
        // Locale.ROOT, for the same reason the engraved tempo mark carries it
        // -- mw-notation's TempoMark -- and LilyPondRenderer.speakEnglish
        // exists one process out: %d is
        // localised, so on an ar_EG machine this sentence read "LilyPond
        // reported ١٠ failed bar checks" with the rest of it in English.
        return Optional.of(String.format(Locale.ROOT,
                "LilyPond reported %d failed bar %s while engraving %s, at %s.%n"
                        + "  A bar check fails when a bar does not add up to its time"
                        + " signature, so the PDF%n"
                        + "  was written but its music is wrong there. LilyPond may report"
                        + " only the first%n"
                        + "  such bar in a part, so treat that as a floor and check the rest"
                        + " of the page.%n"
                        + "  The .txt and .ly are unaffected. This is a defect in the source"
                        + " this tool%n"
                        + "  emitted rather than in your recording; please report it with %s"
                        + " attached.",
                moments.size(), moments.size() == 1 ? "check" : "checks",
                ly.getFileName(), named, ly.getFileName()));
    }

    /**
     * How far to move the score, from the flag or from the config file.
     *
     * <p>Read from the <em>effective</em> config, so {@code notation:
     * transposeSemitones: -2} in {@code workspace.yaml} works exactly as the flag
     * does -- which is what #129 asked for by name, and is a preference that
     * belongs in a file rather than in every command line.
     *
     * <p>The bound is refused here as well as in {@link Transposer} because the
     * two refusals are different things. This one is a usage error: it is the
     * user's typo, and it is reported as picocli reports a bad flag, with exit
     * code 2 and the usage text. The library's is a guard on a public API. The
     * <em>comparison</em> is shared, which is
     * {@link Transposer#isWithinRange}'s reason for existing.
     */
    private int requestedTransposition(MusicWizardConfig config) {
        Integer requested = config.notation() == null ? null
                : config.notation().transposeSemitones();
        if (requested == null) {
            return 0;
        }
        if (!Transposer.isWithinRange(requested)) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "a transposition of " + requested + " semitones is beyond the "
                            + Transposer.MAX_SEMITONES + " this accepts; pitch classes repeat"
                            + " every twelve, so a larger shift would print a chart"
                            + " indistinguishable from a correct one");
        }
        return requested;
    }

    /**
     * The header line naming the shift and the keys either side of it.
     *
     * <p>The keys are named only when the score carries one, and they are
     * {@code Score.primaryKey()} -- the longest-sounding key -- so on a piece that
     * modulates this states the shift exactly and its endpoints loosely.
     */
    private static String transposition(int semitones, Score before, Score after) {
        String shift = String.format(Locale.ROOT, "%+d semitone%s",
                semitones, Math.abs(semitones) == 1 ? "" : "s");
        Optional<String> from = before.primaryKey().map(Key::displayName);
        Optional<String> to = after.primaryKey().map(Key::displayName);
        return from.isPresent() && to.isPresent()
                ? shift + ", " + from.get() + " to " + to.get()
                : shift;
    }

    /**
     * Says which typed options this command will not act on.
     *
     * <p>The same treatment {@code analyze} gives {@code --skip-separation}, and
     * for the reason its javadoc records: silently ignoring a typed instruction
     * is the failure this project keeps finding. It is worse here than there.
     * {@code --parts voice} names a reason and writes nothing; a discarded
     * notation setting writes every file and exits 0, which is a confident wrong
     * answer rather than a missing one. {@code --transpose} was the worst of
     * these and is honoured now (#129); what is left is listed below with the
     * issue each is waiting on.
     *
     * <p>Read from the <em>effective</em> config rather than from the typed
     * fields, which is the opposite of what {@code analyze} does with its own
     * overrides, and the difference is the point. {@code --tempo} applies on the
     * audio path and not the MIDI one, so a config file carrying it is a
     * preference that happens not to apply to this run and passing over it in
     * silence is right. These two apply on no path, ever. "Happens not to apply
     * here" and "cannot apply anywhere" are different claims, and only the first
     * is safe to leave unsaid -- so a paper size set once in {@code
     * workspace.yaml}, which is exactly where a persistent preference belongs,
     * warns just as the flag does.
     */
    private static void warnAboutOptionsThatDoNothing(MusicWizardConfig config) {
        var notation = config.notation();
        var defaults = MusicWizardConfig.DEFAULTS.notation();
        if (notation == null || defaults == null) {
            return;
        }
        // Against the defaults, not against null. Every one of these keys has a
        // built-in default, so the effective config always carries a value and a
        // non-null test would warn on every run in the tool. What is worth
        // saying is that somebody asked for something other than what the tool
        // would have done anyway -- and asking for the default is not asking for
        // anything, since honouring it would produce the same chart.
        // Each carries its own issue number rather than the one this list was
        // filed under: #129 was two features and the transposition half of it is
        // done, so a user reading "#129" about a paper size would find an issue
        // closed by a change that did not touch it.
        List<String> ignored = new ArrayList<>();
        if (differs(notation.paperSize(), defaults.paperSize())) {
            ignored.add("the paper size (#180)");
        }
        if (differs(notation.capo(), defaults.capo())) {
            ignored.add("the capo (#181)");
        }
        if (differs(notation.accidentalPreference(), defaults.accidentalPreference())) {
            ignored.add("the accidental preference (#181)");
        }
        if (!ignored.isEmpty()) {
            System.err.println("warning: " + String.join(", ", ignored)
                    + (ignored.size() == 1 ? " has" : " have")
                    + " no effect yet, whether set on the command line or in the workspace"
                    + " config, so the chart is engraved exactly as the defaults would"
                    + " engrave it");
        }
    }

    private static boolean differs(Object requested, Object byDefault) {
        return requested != null && !requested.equals(byDefault);
    }

    private MusicWizardConfig overrides() {
        if (transpose == null && paperSize == null) {
            return null;
        }
        return new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, paperSize, transpose, null, null),
                null, null, null);
    }
}
