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
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.StageCache;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Which pipeline runs is decided by {@link SourceKind}, from the file's
 * header. The two report differently on purpose, and the difference is the point
 * rather than a cosmetic one:
 *
 * <ul>
 *   <li>The audio path <em>measures</em>. Its stage lines are verbs of discovery
 *       -- "tracking beats", "found 65 beats at 120.2 beats/min" -- because every
 *       figure in them is an estimate that could be wrong.
 *   <li>The MIDI path <em>reads</em>. A MIDI file declares its tempo and its
 *       meter, so those figures are quoted under a heading naming where they
 *       came from, and the word "found" does not appear.
 * </ul>
 *
 * <p>Printing the same line for both would make a fact look like a guess and, in
 * the direction that actually costs somebody something, make a guess look like a
 * fact. The one place the MIDI path is deliberately the <em>less</em> confident
 * of the two is a file whose tempo changes: there is then no single stated
 * tempo, so the opening one is quoted with the number of changes beside it,
 * rather than {@link Score#estimatedTempo()}'s duration-weighted average, which
 * is a derivation and would be printed under a heading claiming it was read.
 */
@Command(name = "analyze", description = "Analyse the recording in a workspace.")
final class AnalyzeCommand implements Callable<Integer> {

    /** The stage whose output the transcription cache holds, per input kind. */
    private static final String STAGE_PREFIX = "transcribe-";

    /**
     * What the MIDI path's figures are, exactly.
     *
     * <p>Two sources, and the heading names both because the code cannot tell
     * them apart. {@link MidiTranscriber} substitutes 120 BPM when a file carries
     * no tempo event at tick 0, and 4/4 when it carries no time signature -- the
     * values the MIDI specification says such a file is played at -- and the
     * {@code TempoMap} that comes back records the substitution as though it were
     * a declaration.
     *
     * <p>An earlier draft said "Read from the file, not estimated", which for
     * such a file is untrue in the direction this whole change is about: it
     * presents a default as a statement. The alternative -- asking the file
     * whether it declared a tempo -- means a second reader of MIDI meta events in
     * the CLI, and the rule it would have to reproduce ("at tick 0", not
     * "anywhere") is precisely the kind of rule this project keeps teaching to
     * one of two places. So the claim is narrowed to one that holds for every
     * file, and #119 asks the importer to report which values it defaulted, after
     * which this can be tightened per row.
     *
     * <p>What is <em>not</em> weakened is the distinction that matters: nothing
     * under this heading was measured from audio.
     */
    private static final String DECLARED_HEADING =
            "From the file, or the MIDI default where it declares nothing:";

    /** How many part names to print before summarising the rest. */
    private static final int MAX_LISTED_PARTS = 6;

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Option(names = "--tempo", paramLabel = "BPM",
            description = "Force a tempo instead of tracking it, in counted beats "
                    + "per minute (dotted quarters in 6/8, not quarters). Audio only: "
                    + "a MIDI file states its own tempo.")
    Double tempo;

    @Option(names = "--time-signature", paramLabel = "N/D",
            description = "Force a time signature, e.g. 4/4 or 6/8. Audio only: "
                    + "a MIDI file states its own meter.")
    String timeSignature;

    @Option(names = "--first-downbeat", paramLabel = "SECONDS",
            description = "Force where a bar begins, in seconds. Snapped to the "
                    + "nearest tracked beat, which then begins every bar in the "
                    + "saved score. Does not move the engraved chart's bar lines "
                    + "yet (issue #83). Audio only.")
    Double firstDownbeat;

    @Option(names = "--skip-separation",
            description = "Analyse the mix directly instead of separating stems. Audio only.")
    boolean skipSeparation;

    @Option(names = "--no-llm", description = "Disable the Claude advisor layer for this run.")
    boolean noLlm;

    @Option(names = "--force", description = "Ignore cached stage results and recompute.")
    boolean force;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        Path source = workspace.sourceFile();
        SourceKind kind = SourceKind.detect(source);

        if (!workspace.sourceMatchesDigest()) {
            System.err.println(
                    "warning: the source recording has changed since this workspace was created;"
                            + " cached results may not correspond to it. Re-run with --force to recompute.");
        }
        if (kind == SourceKind.MIDI) {
            warnAboutAudioOnlyOptions();
        }

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Source     " + source.getFileName() + " (" + kind.description() + ")");
        System.out.println("Advisor    " + (config.isLlmEnabled() ? "enabled" : "disabled"));
        System.out.println();

        Score score = transcribe(workspace, kind, source, config);

        workspace.updateMetadata(
                workspace.title().orElse(null), workspace.artist().orElse(null));
        workspace.writeScore(score);

        System.out.println();
        for (String line : summary(kind, score)) {
            System.out.println(line);
        }
        System.out.println("Saved   " + workspace.scoreFile());
        System.out.println();
        System.out.println("Next: mw render " + workspace.root().getFileName());
        return 0;
    }

    // ------------------------------------------------------------------- cache

    /**
     * The transcription, from the cache when it is there and from the pipeline
     * when it is not.
     *
     * <p>A cached entry that cannot be read is recomputed rather than raised. The
     * cache is an optimisation, and a workspace whose {@code cache/} was
     * truncated by a full disk -- or written by a build whose score schema has
     * since moved on -- must still analyse rather than become unusable.
     */
    private Score transcribe(
            Workspace workspace, SourceKind kind, Path source, MusicWizardConfig config) {
        StageCache cache = workspace.cache();
        StageCache.Key key = transcriptionKey(kind, source, audioOptions(kind, config));

        if (!force) {
            Score cached = readCached(cache, key);
            if (cached != null) {
                // Said every time, and deliberately not quietly. A cached result
                // is the previous answer, and on a development build -- which
                // reports no version, so the key cannot change when the code does
                // -- it is the only warning that the pipeline did not run.
                System.out.println("  reusing the cached analysis of this file;"
                        + " --force recomputes it");
                return cached;
            }
        }

        Score score = switch (kind) {
            case AUDIO -> new AudioTranscriber(AnalyzeCommand::report)
                    .transcribe(source, audioOptions(kind, config));
            case MIDI -> new MidiTranscriber(AnalyzeCommand::report).transcribe(source);
        };
        cache.writeText(key, ".json", ScoreJson.toJson(score));
        return score;
    }

    private static void report(String message) {
        System.out.println("  " + message);
    }

    private static Score readCached(StageCache cache, StageCache.Key key) {
        try {
            return cache.readText(key, ".json").map(ScoreJson::fromJson).orElse(null);
        } catch (RuntimeException e) {
            System.err.println("warning: a cached analysis could not be read and will be"
                    + " recomputed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The cache key for one transcription.
     *
     * <p>The input kind is carried by the <em>stage name</em> rather than by a
     * component, and that is a deliberate second attempt. Both paths read the
     * same bytes, so the file's digest cannot separate them; the first version of
     * this method added the kind as one component among several and a mutation
     * run showed it was doing nothing -- deleting it left every test passing,
     * because the audio branch's option components happened to differ from the
     * MIDI branch's absence of them. The keys were distinct by accident, and an
     * accident is not a separation.
     *
     * <p>As a stage name it cannot be accidental: {@code StageCache} gives each
     * stage its own directory, so {@code cache/transcribe-audio} and
     * {@code cache/transcribe-midi} cannot serve one another's entries whatever
     * the components beneath them turn out to be. It is also the honest
     * modelling -- these are two different stages that happen to produce the same
     * type -- and it makes a workspace diagnosable by eye. That matters here
     * because before #114 the audio path really did read MIDI files, and it
     * answered plausibly and wrongly.
     *
     * <p>The build is a component, and what it buys is narrower than it looks.
     * The key can name every input but cannot name the code, and a score is the
     * output of the whole DSP stack, so upgrading and re-running {@code analyze}
     * would otherwise return the previous version's answer. What the component
     * carries is the jar's {@code Implementation-Version} -- which no jar this
     * project shipped actually had until the manifest entry was added alongside
     * this, so the first version of this javadoc described a protection that did
     * not exist at all.
     *
     * <p>It still only invalidates when the <em>version string</em> changes. A
     * rebuild at the same version does not, which covers every SNAPSHOT build
     * and every run from {@code target/classes}, where there is no manifest and
     * the component reads "development". That is why the reuse is announced on
     * every hit rather than being silent, and why {@code --force} exists.
     *
     * <p>The audio overrides are components only on the audio path. On the MIDI
     * path they change nothing, because nothing reads them, so keying on them
     * would miss the cache for a reason that is not a reason.
     *
     * <p>Package-private so a test can compare two keys over the same file.
     */
    static StageCache.Key transcriptionKey(
            SourceKind kind, Path source, AudioTranscriber.Options options) {
        StageCache.Key key = StageCache.Key
                .forStage(STAGE_PREFIX + kind.name().toLowerCase(Locale.ROOT))
                .with("build", buildVersion())
                .withFile("source", source);
        if (kind == SourceKind.AUDIO && options != null) {
            key.with("tempo", options.tempoOverride())
                    .with("meter", options.timeSignatureOrDefault())
                    .with("firstDownbeat", options.firstDownbeatSeconds());
        }
        return key;
    }

    /** The build's own version, or a placeholder when it is not running from a jar. */
    private static String buildVersion() {
        Package pkg = AnalyzeCommand.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "development";
    }

    // --------------------------------------------------------------- reporting

    /** The summary block for a finished analysis. */
    static List<String> summary(SourceKind kind, Score score) {
        return kind == SourceKind.MIDI ? midiSummary(score) : audioSummary(score);
    }

    /**
     * What the audio path reports.
     *
     * <p>Unchanged, and meant to stay that way. Every figure here is an estimate,
     * and the running commentary above it has already said so in the verbs it
     * used.
     */
    private static List<String> audioSummary(Score score) {
        return List.of(
                tempoLine(score),
                "Meter   " + score.tempoMap().initialTimeSignature(),
                "Chords  " + score.chords().size() + " spans");
    }

    /**
     * What the MIDI path reports.
     *
     * <p>Grouped under a heading rather than suffixed line by line, so the
     * provenance is structural: everything indented under it comes from the
     * input, and everything outside it does not. What the file does not say is
     * reported as not said wherever that is knowable -- "the writer did not name
     * a key" and "the writer said C major" are different claims and only one of
     * them belongs on a staff. Where it is not knowable, see
     * {@link #DECLARED_HEADING}.
     */
    private static List<String> midiSummary(Score score) {
        List<String> lines = new ArrayList<>();
        lines.add(DECLARED_HEADING);
        lines.add("  Tempo   " + statedTempo(score));
        lines.add("  Meter   " + statedMeter(score));
        lines.add("  Key     " + statedKey(score));
        lines.add("");
        // Outside the block, because a part name is not a declaration in the way
        // a tempo is. MidiTranscriber synthesises "Track 3" for an unnamed track,
        // appends " ch 2" where one track carries two channels, and adds " (2)"
        // to disambiguate a repeat -- so some of what is printed here was read
        // and some was constructed, and the heading above must not cover both.
        lines.add("Parts   " + partsLine(score));
        lines.add(score.chords().isEmpty()
                // Not "0 spans", which reads as the result of looking. Nothing
                // looked: a MIDI file states which notes sound, and naming the
                // harmony they spell is a stage that does not exist yet (#115).
                ? "Chords  none: a MIDI file states notes, not harmony (#115)"
                : "Chords  " + score.chords().size() + " spans");
        return lines;
    }

    /**
     * The tempo the file declares, with its changes counted.
     *
     * <p>Taken from the map's opening segment rather than from
     * {@link Score#estimatedTempo()}, which for a changing tempo falls back to a
     * duration-weighted average. That average is a perfectly good summary and an
     * unacceptable thing to print under this heading: it is a number the file
     * never contains.
     */
    private static String statedTempo(Score score) {
        TempoMap map = score.tempoMap();
        TimeSignature meter = map.initialTimeSignature();
        String opening = formatTempo(map.segments().get(0).beatsPerMinute(), meter);
        int changes = countChanges(map.segments(), TempoMap.TempoSegment::beatsPerMinute);
        return changes == 0 ? opening : opening + " at the start, " + changed(changes);
    }

    private static String statedMeter(Score score) {
        TempoMap map = score.tempoMap();
        int changes = countChanges(map.meterChanges(), TempoMap.MeterChange::timeSignature);
        String opening = map.initialTimeSignature().toString();
        return changes == 0 ? opening : opening + " at the start, " + changed(changes);
    }

    private static String statedKey(Score score) {
        if (score.keys().isEmpty()) {
            return "not declared by the file";
        }
        String opening = score.keys().get(0).displayName();
        int changes = countChanges(score.keys(), key -> key.tonic() + "/" + key.mode());
        return changes == 0 ? opening : opening + " at the start, " + changed(changes);
    }

    /**
     * How many times a value actually changes along a list.
     *
     * <p>Transitions, not entries. Counting {@code size() - 1} counts the
     * <em>events</em> the file happens to contain, and a sequencer export that
     * restates the same tempo or the same key signature at every section
     * boundary contains a great many that change nothing. Reporting those as
     * changes is a claim about the music that the file does not make -- and it
     * disagreed with the meter line in the same block, since
     * {@code MidiTranscriber} already drops a restated meter and drops neither of
     * the other two (#118).
     *
     * <p>Compared with {@code equals}, which for the tempo means comparing two
     * doubles exactly. That is right here rather than sloppy: a restated tempo is
     * the same integer count of microseconds decoded by the same division, so it
     * produces the identical double. Two tempi that differ in the last bit came
     * from different microsecond counts and really are a change, however
     * inaudible.
     */
    private static <T> int countChanges(
            List<T> values, java.util.function.Function<T, Object> valueOf) {
        int changes = 0;
        for (int i = 1; i < values.size(); i++) {
            if (!valueOf.apply(values.get(i)).equals(valueOf.apply(values.get(i - 1)))) {
                changes++;
            }
        }
        return changes;
    }

    private static String changed(int changes) {
        return "changed " + changes + (changes == 1 ? " time" : " times") + " later";
    }

    private static String partsLine(Score score) {
        if (score.tracks().isEmpty()) {
            return "none";
        }
        List<String> names = score.tracks().stream()
                .limit(MAX_LISTED_PARTS)
                .map(NoteTrack::name)
                .toList();
        int remaining = score.tracks().size() - names.size();
        return String.join(", ", names) + (remaining > 0 ? " and " + remaining + " more" : "");
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
        return "Tempo   " + formatTempo(
                score.estimatedTempo(), score.tempoMap().initialTimeSignature());
    }

    /**
     * One tempo, in the beat its meter is counted in.
     *
     * <p>The single formatter both paths go through, because there are now two
     * callers of it and the 6/8 qualification is exactly the kind of rule that
     * gets taught to one of them and not the other.
     *
     * <p>One decimal place, and not more. A MIDI tempo event carries whole
     * microseconds per quarter note, so a file asked for 140 BPM holds
     * 140.00014; printing further digits would advertise a precision that is an
     * artefact of the encoding rather than anything about the music. Nothing in
     * this class compares two tempi for equality, for the same reason.
     */
    private static String formatTempo(double quarterBpm, TimeSignature meter) {
        // Locale.ROOT, because the whole point is that the user can type this
        // number back in via --tempo, and picocli parses it with Double.valueOf:
        // under fr_FR this printed "120,0", which that rejects outright.
        if (meter.beatUnitQuarters() == 1.0) {
            return String.format(Locale.ROOT, "%.1f BPM", quarterBpm);
        }
        return String.format(Locale.ROOT, "%.1f BPM (%.1f quarter notes/min)",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    /**
     * Says which typed options the MIDI path will not act on.
     *
     * <p>Said rather than passed over. These options correct stages that a MIDI
     * import does not run, so honouring them would mean overriding what the file
     * states with a guess; ignoring them silently would mean discarding an
     * instruction the user typed, which is the failure this project keeps
     * finding elsewhere.
     *
     * <p>Only the options typed on this command line, not the effective config.
     * A value in a config file is a preference that happens not to apply here; a
     * value on the command line is an instruction for this run.
     */
    private void warnAboutAudioOnlyOptions() {
        List<String> ignored = new ArrayList<>();
        if (tempo != null) {
            ignored.add("--tempo");
        }
        if (timeSignature != null) {
            ignored.add("--time-signature");
        }
        if (firstDownbeat != null) {
            ignored.add("--first-downbeat");
        }
        if (skipSeparation) {
            ignored.add("--skip-separation");
        }
        if (!ignored.isEmpty()) {
            System.err.println("warning: " + String.join(", ", ignored)
                    + (ignored.size() == 1 ? " has" : " have")
                    + " no effect on a MIDI workspace; the file states its own tempo and"
                    + " meter, and nothing is separated");
        }
    }

    /**
     * The audio pipeline's options, or {@code null} on the MIDI path.
     *
     * <p>Null rather than defaults, so that a caller which reads them on the MIDI
     * path fails visibly rather than silently acting on 4/4.
     */
    private static AudioTranscriber.Options audioOptions(
            SourceKind kind, MusicWizardConfig config) {
        if (kind != SourceKind.AUDIO) {
            return null;
        }
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
