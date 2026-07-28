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

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
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
         * reached from the inside. Unreachable while chords are the only
         * implemented part, and reachable the moment #8 or #10 lands, which is
         * when nobody will be looking for it.
         */
        boolean isImplemented() {
            return emitter != null;
        }

        List<Path> emit(Workspace workspace, Score score, Optional<Path> lilypond) {
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

    /** How one part is produced, once it is known that it can be. */
    @FunctionalInterface
    private interface Emitter {
        List<Path> emit(Workspace workspace, Score score, Optional<Path> lilypond);
    }

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--parts", split = ",", paramLabel = "PART",
            description = "Which parts to render: chords, voice, bass, piano. Only "
                    + "chords can be produced today; naming any of the others reports "
                    + "why it cannot. Defaults to every part that is implemented.")
    List<String> parts;

    @Option(names = "--transpose", paramLabel = "SEMITONES",
            description = "Transpose every part by this many semitones. Has no effect "
                    + "yet: nothing reads it (#129).")
    Integer transpose;

    @Option(names = "--paper", paramLabel = "SIZE",
            description = "Paper size, e.g. a4 or letter. Has no effect yet: nothing "
                    + "reads it (#129).")
    String paperSize;

    @Option(names = "--no-pdf", description = "Write sources only; do not invoke LilyPond.")
    boolean noPdf;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        List<Part> requested = requestedParts();
        warnAboutOptionsThatDoNothing(config);

        // The score is read before anything is announced, because what can be
        // produced is a property of it. Announcing an output directory and an
        // engraver and then writing nothing is the same defect #82 was filed
        // for, one line further down the same command.
        Score score = workspace.readScore().orElseThrow(() -> new IllegalStateException(
                "no transcription yet; run: mw analyze " + workspaceDirectory));

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

        List<Path> written = new ArrayList<>();
        boolean chartWritten = false;
        if (!producible.isEmpty()) {
            System.out.println("Output     " + workspace.outputDirectory());
            Optional<Path> lilypond = announceEngraver(config);
            for (Part part : producible) {
                written.addAll(part.emit(workspace, score, lilypond));
                chartWritten |= part == Part.CHORDS;
            }
        }

        if (!written.isEmpty()) {
            System.out.println();
            written.forEach(file -> System.out.println("Wrote " + file));
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
    private static List<Path> writeChordChart(
            Workspace workspace, Score score, Optional<Path> lilypond) {
        Path out = workspace.outputDirectory();
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(out);
            Path txt = out.resolve("chords.txt");
            Files.writeString(txt, ChordChart.toText(score));
            written.add(txt);

            Path ly = out.resolve("chords.ly");
            Files.writeString(ly, ChordChart.toLilyPond(score));
            written.add(ly);

            if (lilypond.isPresent()) {
                LilyPondRenderer.Result result = new LilyPondRenderer(lilypond.get()).render(ly);
                if (result.succeeded()) {
                    written.add(result.pdf().orElseThrow());
                } else {
                    // Reported, not thrown: the sources are still useful, and
                    // losing them because the engraver failed would be worse.
                    System.out.println();
                    System.out.println("LilyPond could not engrave the chart:");
                    result.output().lines().limit(10)
                            .forEach(line -> System.out.println("  " + line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write output", e);
        }
        return written;
    }

    /**
     * Says which typed options this command will not act on.
     *
     * <p>The same treatment {@code analyze} gives {@code --skip-separation}, and
     * for the reason its javadoc records: silently ignoring a typed instruction
     * is the failure this project keeps finding. It is worse here than there.
     * {@code --parts voice} names a reason and writes nothing; {@code --transpose
     * -2} writes every file, exits 0, and hands a singer a chart in the wrong
     * key -- a confident wrong answer rather than a missing one, which is the
     * category this whole change exists to remove. Nothing reads either value
     * (#129).
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
        List<String> ignored = new ArrayList<>();
        if (differs(notation.transposeSemitones(), defaults.transposeSemitones())) {
            ignored.add("the transposition");
        }
        if (differs(notation.paperSize(), defaults.paperSize())) {
            ignored.add("the paper size");
        }
        if (differs(notation.capo(), defaults.capo())) {
            ignored.add("the capo");
        }
        if (differs(notation.accidentalPreference(), defaults.accidentalPreference())) {
            ignored.add("the accidental preference");
        }
        if (!ignored.isEmpty()) {
            System.err.println("warning: " + String.join(", ", ignored)
                    + (ignored.size() == 1 ? " has" : " have")
                    + " no effect yet, whether set on the command line or in the workspace"
                    + " config; nothing reads any notation setting but lilypondPath, so the"
                    + " chart is engraved exactly as the defaults would engrave it (#129)");
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
