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

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Runs the analysis pipeline over a workspace.
 *
 * <p>The manual overrides here are the highest-value controls in the tool. Beat
 * and meter estimation is the least reliable stage, and every later stage
 * depends on it, so one corrected number from a user who can count bars fixes
 * the entire output.
 */
@Command(name = "analyze", description = "Analyse the recording in a workspace.")
final class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--tempo", paramLabel = "BPM",
            description = "Force a tempo instead of tracking it, in counted beats "
                    + "per minute (dotted quarters in 6/8, not quarters).")
    Double tempo;

    @Option(names = "--time-signature", paramLabel = "N/D",
            description = "Force a time signature, e.g. 4/4 or 6/8.")
    String timeSignature;

    @Option(names = "--first-downbeat", paramLabel = "SECONDS",
            description = "Force the time of the first downbeat.")
    Double firstDownbeat;

    @Option(names = "--skip-separation",
            description = "Analyse the mix directly instead of separating stems.")
    boolean skipSeparation;

    @Option(names = "--no-llm", description = "Disable the Claude advisor layer for this run.")
    boolean noLlm;

    @Option(names = "--force", description = "Ignore cached stage results and recompute.")
    boolean force;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());

        if (!workspace.sourceMatchesDigest()) {
            System.err.println(
                    "warning: the source recording has changed since this workspace was created;"
                            + " cached results may not correspond to it. Re-run with --force to recompute.");
        }

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Source     " + workspace.sourceFile().getFileName());
        System.out.println("Advisor    " + (config.isLlmEnabled() ? "enabled" : "disabled"));
        System.out.println();

        AudioTranscriber transcriber = new AudioTranscriber(
                message -> System.out.println("  " + message));
        Score score = transcriber.transcribe(workspace.sourceFile(), options(config));

        workspace.updateMetadata(
                workspace.title().orElse(null), workspace.artist().orElse(null));
        workspace.writeScore(score);

        System.out.println();
        System.out.println(tempoLine(score));
        System.out.println("Meter   " + score.tempoMap().initialTimeSignature());
        System.out.println("Chords  " + score.chords().size() + " spans");
        System.out.println("Saved   " + workspace.scoreFile());
        System.out.println();
        System.out.println("Next: mw render " + workspace.root().getFileName());
        return 0;
    }

    /**
     * Prints the tempo in the unit the user counts in.
     *
     * <p>The map stores quarter notes per minute, which is the same number in
     * every x/4 meter and a different one in 6/8, where the counted beat is a
     * dotted quarter. Printing the stored figure unqualified there would show a
     * tempo the user cannot type back in via {@code --tempo}.
     *
     * <p>The tempo itself comes from {@link Score#estimatedTempo()} rather than
     * straight off the map, so this and the engraved chart's header print the
     * same number.
     *
     * <p>The meter is the one the piece opens in, so a piece that changes meter
     * part-way would be converted with the wrong beat unit for its later
     * sections. Nothing emits a meter change today; see #66.
     */
    static String tempoLine(Score score) {
        double quarterBpm = score.estimatedTempo();
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        // Locale.ROOT, because the whole point is that the user can type this
        // number back in via --tempo, and picocli parses it with Double.valueOf:
        // under fr_FR this printed "120,0", which that rejects outright.
        if (meter.beatUnitQuarters() == 1.0) {
            return String.format(Locale.ROOT, "Tempo   %.1f BPM", quarterBpm);
        }
        return String.format(Locale.ROOT, "Tempo   %.1f BPM (%.1f quarter notes/min)",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    private AudioTranscriber.Options options(MusicWizardConfig config) {
        var analysis = config.analysis();
        TimeSignature meter = parseMeter(analysis != null ? analysis.timeSignatureOverride() : null);
        return new AudioTranscriber.Options(
                analysis != null ? analysis.tempoOverride() : null,
                meter,
                analysis != null ? analysis.firstDownbeatSecondsOverride() : null);
    }

    private static TimeSignature parseMeter(String text) {
        if (text == null || text.isBlank()) {
            return TimeSignature.FOUR_FOUR;
        }
        String[] parts = text.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "time signature must look like 4/4 or 6/8, got: " + text);
        }
        try {
            return new TimeSignature(
                    Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "time signature must look like 4/4 or 6/8, got: " + text, e);
        }
    }

    private MusicWizardConfig overrides() {
        var analysis = new MusicWizardConfig.AnalysisConfig(
                tempo, timeSignature, firstDownbeat, skipSeparation ? Boolean.TRUE : null);
        var llm = noLlm
                ? new MusicWizardConfig.LlmConfig(false, null, null, null, null, null, null, null)
                : null;
        return new MusicWizardConfig(null, analysis, null, null, null, llm);
    }
}
