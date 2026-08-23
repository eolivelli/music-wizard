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

import dev.olivelli.musicwizard.arrange.Melismas;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.ml.AlignmentProvider;
import dev.olivelli.musicwizard.core.ml.MlProviders;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import dev.olivelli.musicwizard.core.text.Hyphenator;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunManifestJson;
import dev.olivelli.musicwizard.core.workspace.StageCache;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Runs the analysis pipeline over a workspace. The manual overrides here are
 * the highest-value controls in the tool: beat and meter estimation is the
 * least reliable stage and every later stage depends on it.
 *
 * <p>Which pipeline runs is decided by {@link SourceKind}, and the two report
 * differently on purpose: the audio path <em>measures</em> (verbs of
 * discovery, every figure an estimate), the MIDI path <em>reads</em> (figures
 * quoted under a heading naming where they came from). Printing the same line
 * for both would make a guess look like a fact.
 */
@Command(name = "analyze", description = "Analyse the recording in a workspace.")
final class AnalyzeCommand implements Callable<Integer> {

    /** The stage whose output the transcription cache holds, per input kind. */
    private static final String STAGE_PREFIX = "transcribe-";

    /** Where the cache keeps what the stages under a key recorded (#674). */
    private static final String STAGES_EXTENSION = ".stages.json";

    /**
     * What the MIDI path's figures are, exactly. The heading names both
     * sources because {@link MidiTranscriber} substitutes the MIDI defaults
     * where a file declares nothing and the {@code TempoMap} records the
     * substitution as a declaration. The tempo row could be tightened via
     * {@link Provenance} (#120); the meter row cannot until #119, and rows of
     * different strengths under one heading would be worse than one honest
     * heading. What is not weakened: nothing under it was measured from audio.
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
                    + "nearest tracked beat, which then begins a bar in the "
                    + "saved beat grid and the chart's first bar line. Does not "
                    + "renumber the tempo map's bars. Audio only.")
    Double firstDownbeat;

    @Option(names = "--melody",
            description = "Read the melody out of the recording and write it into the "
                    + "score, so that 'render --parts lead' can engrave a lead sheet. "
                    + "Read from the separated vocal where a separation provider is "
                    + "available, and otherwise from the mix — where the tracker is "
                    + "monophonic and returns the loudest periodic line, usually not "
                    + "the voice, rather than failing. Audio only.")
    boolean melody;

    @Option(names = "--skip-separation",
            description = "Analyse the mix directly instead of separating stems. Makes "
                    + "--melody and lyric transcription hear the full mix; chords "
                    + "always come from the mix regardless.")
    boolean skipSeparation;

    @Option(names = "--no-llm",
            description = "Disable the Claude advisor layer for this run. The layer does "
                    + "not exist yet (#11), so today this only changes the cache key and "
                    + "forces a recompute.")
    boolean noLlm;

    @Option(names = "--lyrics", paramLabel = "FILE",
            description = "Read lyrics from an LRC file and place them under the "
                    + "chords. Word timings are used when the file carries them "
                    + "and estimated within each line when it does not. Without "
                    + "this option, --lyrics-language alone transcribes the words "
                    + "from the recording when an ASR provider is available.")
    Path lyricsFile;

    @Option(names = "--lyrics-language", paramLabel = "TAG",
            description = "What language the lyrics are in, e.g. it or en. Used to "
                    + "split words into syllables for the engraved sheet. An LRC "
                    + "file does not say, and a wrong answer splits words on "
                    + "another language's rules, so without this they stay whole. "
                    + "Given without --lyrics, also asks for the words to be "
                    + "transcribed from the recording itself.")
    String lyricsLanguage;

    @Option(names = "--force", description = "Ignore cached stage results and recompute.")
    boolean force;

    /**
     * Set when the melody stage was promised the stem and got the mix, which
     * only a separator failing mid-run can do. It suppresses the cache write:
     * the key names the stem this run could not produce, so storing the result
     * under it would serve a mix melody to the next run — the one where the
     * model is finally there — as the answer to a question it never asked.
     */
    private boolean melodyFellBackToTheMix;

    /**
     * What this run records about itself (#674). Written to the workspace at
     * the end and read by nothing that analyses: a record of the run, never an
     * input to one, so it cannot change what the run computes.
     */
    private final RunLog runLog = new RunLog();

    @Override
    public Integer call() {
        Instant startedAt = Instant.now();
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        Path source = workspace.sourceFile();
        SourceKind kind = SourceKind.detect(source);
        // One per run, and lazy: the melody and the lyrics both read it, and
        // neither separation happens unless its stage is reached.
        Optional<VocalStem> stem = skipSeparationRequested(config)
                ? Optional.empty()
                : VocalStem.forRun(source, config, runLog);
        if (skipSeparationRequested(config)) {
            runLog.stage("separation").skipped("--skip-separation");
        } else if (stem.isEmpty()) {
            runLog.stage("separation")
                    .skipped("no separation provider is configured or on this classpath");
        }

        if (!workspace.sourceMatchesDigest()) {
            // The cache is keyed on the file's digest, so a changed source
            // recomputes unasked; what is stale is what was already rendered.
            System.err.println(
                    "warning: the source recording has changed since this workspace was"
                            + " created; this analysis is of the file as it is now, and"
                            + " anything already rendered from the old one is out of date.");
        }
        warnAboutOptionsThatDoNothing(kind, config);

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Source     " + source.getFileName() + " (" + kind.description() + ")");
        // Qualified because the layer does not exist yet (#11); announcing it
        // as simply "enabled" is #82's defect.
        System.out.println("Advisor    " + (config.isLlmEnabled()
                ? "enabled, but advises nothing yet (#11)" : "disabled"));
        System.out.println();

        Transcription result = transcribe(workspace, kind, source, config, stem);
        Score score = withSuppliedLyrics(workspace, titled(workspace, result.score()));
        if (lyricsFile != null) {
            // Only what this run supplied: carried-forward lyrics were aligned
            // when they were supplied, and re-aligning aligned times moves the
            // windows they are computed from, one step per analyze, without
            // bound.
            score = withAlignedLyrics(workspace, score);
        } else if (transcriptionRequested() && kind == SourceKind.AUDIO) {
            // The language alone is the ask: "this is sung in Italian, hear the
            // words". Stated rather than detected, because a recognizer told to
            // guess the language produces fluent wrong words when it guesses
            // wrong, which nothing downstream can notice.
            score = withTranscribedLyrics(workspace, score, config, stem);
        }

        // The score is persisted before the cache entry: the failure cannot
        // then happen before the deliverable is safe.
        workspace.writeScore(score);
        if (!result.fromCache() && !melodyFellBackToTheMix) {
            // The transcription, not the titled score — the key says nothing
            // about metadata, and titled() runs on the way out of the cache.
            storeQuietly(workspace.cache(), result.key(), result.score(), result.stages());
        } else if (melodyFellBackToTheMix) {
            System.err.println("warning: this analysis is not cached, so the next run"
                    + " reads the melody from the stem if the separator works then.");
        }
        recordUnreachedStages();
        writeManifestQuietly(workspace, startedAt, kind, config);

        System.out.println();
        for (String line : summary(kind, score)) {
            System.out.println(line);
        }
        System.out.println("Saved   " + workspace.scoreFile());
        System.out.println();
        System.out.println("Next: mw render " + workspace.root().getFileName());
        return 0;
    }

    /**
     * The score with the workspace's title and artist on it (#216). The
     * workspace outranks the transcription, field by field: a workspace title
     * was typed by a person, a transcribed one is a track name by convention —
     * and a workspace naming only the artist must not discard a title the file
     * did carry. Applied here because the workspace is the CLI's to know, and
     * not in {@code render}, which answers from the score alone (#120).
     */
    private static Score titled(Workspace workspace, Score score) {
        return score.withMetadata(
                workspace.title().or(score::title).orElse(null),
                workspace.artist().or(score::artist).orElse(null));
    }

    /**
     * The language tag to read the lyrics under, or {@code und} for none.
     *
     * <p>An LRC file does not state one, so without the option the lyrics carry
     * {@code und}: their words are not split into syllables and are shared out
     * across a line by a count that assumes English. Warned about rather than
     * guessed at when the tag names a language there are no patterns for — a
     * user who asked for syllables and silently got none would have no way to
     * tell that from a language whose words simply do not split.
     */
    private String languageTag() {
        if (lyricsLanguage == null || lyricsLanguage.isBlank()) {
            return "und";
        }
        if (!Hyphenator.supports(lyricsLanguage)) {
            System.err.println("warning: no hyphenation patterns for language '"
                    + lyricsLanguage + "', so lyric words will not be split into"
                    + " syllables; the lyrics are still placed.");
        }
        return lyricsLanguage;
    }

    /**
     * The lyrics already in this workspace's score, or none. Guarded, and the
     * guard is the point: a corrupt {@code score.json} raising here would
     * abort {@code analyze} before the new score could overwrite it, leaving
     * the workspace unrecoverable through the tool. Warn, carry nothing, and
     * let the good score overwrite the bad one.
     */
    private static Score carriedForward(Workspace workspace, Score score) {
        try {
            return workspace.readScore()
                    .map(Score::lyrics)
                    .filter(existing -> !existing.isEmpty())
                    .map(existing -> withCarriedLyrics(existing, score))
                    .orElse(score);
        } catch (RuntimeException e) {
            System.err.println("warning: the score already in this workspace could not be"
                    + " read, so any lyrics it held are not carried over; it is being"
                    + " replaced: " + e.getMessage());
            return score;
        }
    }

    /**
     * The new score with the previous analysis's lyrics on it, keeping each
     * melisma mark only where this run's own decision still makes it.
     *
     * <p>A mark is a function of the melody, the tempo map and the chords
     * (#597), so carried under a score any of those moved on, it can say a
     * syllable is sung over a run the score no longer holds (#623) — and
     * comparing the inputs one by one is an enumeration the next input would
     * silently break. So the decision is re-made against this run's score and
     * intersected with the carried marks. Intersecting only removes, which is
     * what keeps #597's rule intact: a line the aligner never measured cannot
     * gain a mark here — see {@link #withMelismas} — and a score with no
     * melody leaves the decision alone, so carried marks wait for a melody to
     * be checked against rather than being lost to a run that computed none.
     */
    static Score withCarriedLyrics(Lyrics existing, Score score) {
        Lyrics decided = Melismas.marked(score.withLyrics(existing));
        List<LyricLine> lines = new ArrayList<>(existing.lines().size());
        boolean dropped = false;
        for (int i = 0; i < existing.lines().size(); i++) {
            LyricLine carried = existing.lines().get(i);
            LyricLine checked = decided.lines().get(i);
            List<LyricWord> words = new ArrayList<>(carried.words().size());
            for (int at = 0; at < carried.words().size(); at++) {
                boolean keep = carried.words().get(at).melisma()
                        && checked.words().get(at).melisma();
                dropped |= carried.words().get(at).melisma() && !keep;
                words.add(carried.words().get(at).withMelisma(keep));
            }
            lines.add(new LyricLine(words, carried.confidence()));
        }
        if (!dropped) {
            return score.withLyrics(existing);
        }
        System.out.println("  melisma marks dropped: this analysis no longer"
                + " finds a run under them");
        return score.withLyrics(
                new Lyrics(lines, existing.language(), existing.confidence()));
    }

    /**
     * The score with lyrics on it: from {@code --lyrics} when given, and
     * otherwise {@link #carriedForward} from the workspace.
     *
     * <p>Applied outside the transcription cache. Lyrics are supplied rather
     * than derived, so keying the analysis on them would throw away minutes of
     * DSP every time a typo in the lyric file was corrected; what the cache holds
     * stays a function of the recording and the options that shaped the
     * listening.
     *
     * <p>A file that cannot be read, or that carries no lyrics, is a warning and
     * not a failure — the analysis is the expensive thing and it has already
     * succeeded. {@link LrcLyrics#parse} is total for the same reason: a lyric
     * file must not be able to raise past this method.
     */
    private Score withSuppliedLyrics(Workspace workspace, Score score) {
        if (lyricsFile == null) {
            Score carried = carriedForward(workspace, score);
            if (!carried.lyrics().isEmpty()) {
                runLog.stage("lyrics")
                        .fact("words from", "the previous analysis of this workspace")
                        .fact("language", carried.lyrics().language())
                        .fact("lines", carried.lyrics().lines().size())
                        .computed();
            }
            return carried;
        }
        String text;
        try {
            text = Files.readString(lyricsFile);
        } catch (IOException | RuntimeException e) {
            System.err.println("warning: the lyrics file could not be read, so this"
                    + " analysis has no lyrics: " + e.getMessage());
            runLog.stage("lyrics").failed("the lyrics file could not be read: "
                    + e.getMessage());
            return score;
        }
        Lyrics lyrics = LrcLyrics.parse(text, score.durationSeconds(), languageTag());
        if (lyrics.isEmpty()) {
            System.err.println(LrcLyrics.looksLikeLrc(text)
                    ? "warning: the lyrics file has timestamps but no words under them,"
                            + " so this analysis has no lyrics."
                    : "warning: the lyrics file carries no [mm:ss.xx] timestamps, so it"
                            + " cannot be placed; expected an LRC file.");
            runLog.stage("lyrics").failed(LrcLyrics.looksLikeLrc(text)
                    ? "the lyrics file has timestamps but no words under them"
                    : "the lyrics file carries no [mm:ss.xx] timestamps");
            return score;
        }
        System.out.println("  read " + counted(lyrics.lines().size(), "lyric line")
                + " from " + lyricsFile.getFileName());
        runLog.stage("lyrics")
                .fact("words from", "the file " + lyricsFile.getFileName())
                .fact("language", lyrics.language())
                .fact("lines", lyrics.lines().size())
                .computed();
        return score.withLyrics(lyrics);
    }

    /**
     * The score with its lyric words placed by the aligner, when one is
     * configured, present, and speaks the lyrics' language.
     *
     * <p>This is what turns spread-word guesses into measured onsets: each
     * line's words are aligned inside a window around the line's own
     * timestamps — parsed from LRC, or a transcription's sung stretch — which
     * keeps the search small and anchored. Every failure
     * degrades to parsed times — per line, counted in the summary alongside
     * the lines alignment deliberately leaves alone; whole-run with the reason
     * on stderr when the model cannot be had or the aligner fails outright —
     * because the analysis has already succeeded and an aligner must not be
     * able to take it down. Alignment
     * runs outside the transcription cache for the same reason the lyrics do:
     * correcting a lyric file must not recompute the DSP.
     */
    private Score withAlignedLyrics(Workspace workspace, Score score) {
        if (score.lyrics().isEmpty()) {
            return score;
        }
        MusicWizardConfig.MlConfig ml = workspace.effectiveConfig().ml();
        String wanted = ml == null ? null : ml.alignmentProvider();
        var provider = MlProviders.alignment(wanted);
        if (provider.isEmpty()) {
            runLog.stage("lyric-alignment").skipped("no alignment provider"
                    + (wanted == null || wanted.isBlank()
                            ? " is configured (ml.alignmentProvider)"
                            : " named '" + wanted + "' is on this classpath")
                    + "; the words keep the times they were parsed at");
            return score;
        }
        // Providers read the global layer only (#383); a workspace-set model
        // directory never reaches them, and the resulting failure points at
        // the wrong thing entirely.
        warnIfLayerUnreachable("ml.alignmentModelDirectory",
                ml == null ? null : ml.alignmentModelDirectory(),
                global -> global == null ? null : global.alignmentModelDirectory());

        Lyrics lyrics = score.lyrics();
        // The gate normalises the way the SPI contract asks: lowercase language
        // subtag, no region -- the user's en-US and EN both mean en.
        String language = java.util.Locale.forLanguageTag(lyrics.language())
                .getLanguage();
        if (!provider.get().languages().contains(language)) {
            System.out.println("  lyrics not aligned: " + provider.get().id()
                    + " speaks " + provider.get().languages() + ", the lyrics are '"
                    + lyrics.language() + "'");
            runLog.stage("lyric-alignment").fact("provider", provider.get().id())
                    .skipped(provider.get().id() + " speaks " + provider.get().languages()
                            + ", the lyrics are '" + lyrics.language() + "'");
            return score;
        }
        try {
            AudioBuffer audio = AudioDecoder.decode(workspace.sourceFile());
            List<LyricLine> parsed = lyrics.lines();
            List<LyricLine> aligned = new ArrayList<>(parsed.size());
            List<Confidence> measured = new ArrayList<>();
            java.util.Set<LyricLine> measuredLines =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            double previousEnd = 0;
            int kept = 0;
            for (int i = 0; i < parsed.size(); i++) {
                LyricLine line = parsed.get(i);
                // Lines on one moment share a span by design (#340); aligning
                // them would sequence what is sung together and cascade the
                // drift. They keep their shared parsed span, untouched.
                if (sharesAMoment(parsed, i)) {
                    kept++;
                    aligned.add(line);
                    previousEnd = Math.max(previousEnd, line.endSeconds());
                    continue;
                }
                LyricLine result;
                try {
                    // The parser's own rule: a line ends no later than the
                    // next distinct start, so it cannot take that line's
                    // chords.
                    double bound = i + 1 < parsed.size()
                            ? parsed.get(i + 1).startSeconds()
                            : audio.durationSeconds();
                    result = alignedLine(provider.get(), audio, language, line,
                            previousEnd, bound);
                } catch (ModelUnavailableException e) {
                    // No later line will fare better; whole-run degradation.
                    throw e;
                } catch (RuntimeException e) {
                    // One line's failure keeps its parsed times without
                    // discarding the lines that aligned.
                    result = line;
                }
                if (result == line) {
                    kept++;
                } else {
                    measured.add(result.confidence());
                }
                // A no-op on every expected path; a path nobody expected is
                // exactly when the sheet's cursor must still find monotone
                // lines.
                boolean fromTheAligner = result != line;
                result = shiftedAfter(result, previousEnd);
                if (fromTheAligner) {
                    measuredLines.add(result);
                }
                aligned.add(result);
                previousEnd = Math.max(previousEnd, result.endSeconds());
            }
            // Two scales, never compared (#386): the words' own confidence
            // stays on the file, the aligner's is printed beside it.
            Confidence weakest = measured.stream()
                    .min(java.util.Comparator.comparingDouble(Confidence::value))
                    .orElse(null);
            System.out.println("  aligned " + counted(aligned.size() - kept,
                    "lyric line") + " with " + provider.get().id()
                    + (weakest != null
                            ? String.format(java.util.Locale.ROOT,
                                    ", weakest word %.2f on the aligner's own scale",
                                    weakest.value())
                            : "")
                    + (kept > 0 ? "; " + kept + " kept their parsed times" : ""));
            runLog.stage("lyric-alignment")
                    .fact("provider", provider.get().id())
                    .fact("lines measured", aligned.size() - kept)
                    .fact("lines left at their parsed times", kept)
                    .computed();
            Score placed = score.withLyrics(
                    new Lyrics(aligned, lyrics.language(), lyrics.confidence()));
            return placed.withLyrics(withMelismas(placed, measuredLines));
        } catch (ModelUnavailableException e) {
            System.err.println("warning: lyrics stay at their parsed times: "
                    + e.getMessage());
            runLog.stage("lyric-alignment").fact("provider", provider.get().id())
                    .failed("the words keep their parsed times: " + e.getMessage());
            return score;
        } catch (RuntimeException e) {
            // An aligner defect must not take down an analysis that already
            // succeeded; the parsed times are what we had before it existed.
            System.err.println("warning: lyric alignment failed, keeping parsed"
                    + " times: " + e.getMessage());
            runLog.stage("lyric-alignment").fact("provider", provider.get().id())
                    .failed("alignment failed and the words keep their parsed times: "
                            + e.getMessage());
            return score;
        }
    }

    /**
     * The score's lyrics with each syllable told whether it is sung over a run
     * of notes (#597), on the lines whose spans the aligner measured.
     *
     * <p>Only those. A line that kept its parsed times has its words
     * apportioned across it by a syllable count, and a span nobody measured
     * says nothing about what is sung over it: deciding from one marks several
     * times the share of the words that a measured line does, and takes the
     * reduced part most of the way back to the estimate it reduces.
     */
    static Lyrics withMelismas(Score score, java.util.Set<LyricLine> measured) {
        Lyrics placed = score.lyrics();
        Lyrics decided = Melismas.marked(score);
        List<LyricLine> out = new ArrayList<>(placed.lines().size());
        for (int i = 0; i < placed.lines().size(); i++) {
            out.add(measured.contains(placed.lines().get(i))
                    ? decided.lines().get(i) : placed.lines().get(i));
        }
        return new Lyrics(out, placed.language(), placed.confidence());
    }

    /**
     * The score with lyrics heard from the recording, when an ASR provider is
     * configured, present, and speaks the asked-for language.
     *
     * <p>Fed the vocal stem — that is what separation exists for — and the
     * stem's sung stretches only, as {@link VocalSegments} finds them; a
     * recognizer fed an intro or a solo is free to hallucinate words into it.
     * {@code --skip-separation} makes it hear the full mix instead. The
     * recognizer returns words with spread times, so a fresh transcription is
     * handed straight to {@link #withAlignedLyrics} for measured onsets.
     *
     * <p>Lyrics already carried forward win: they were supplied or transcribed
     * once already, and a run that quietly re-transcribed over a corrected
     * file would undo the correction. Every failure degrades to the score as
     * it stands — per segment for one bad window, whole-run with the reason on
     * stderr when the model cannot be had — because the analysis has already
     * succeeded. Outside the transcription cache like the other lyric paths:
     * asking for lyrics must not recompute the DSP.
     */
    private Score withTranscribedLyrics(Workspace workspace, Score score,
                                        MusicWizardConfig config, Optional<VocalStem> stem) {
        if (!score.lyrics().isEmpty() && !force) {
            // Carried lyrics may be a corrected file someone supplied; quietly
            // re-transcribing over them would undo the correction. --force
            // already means "recompute what you kept", so it reaches here too.
            System.out.println("  lyrics kept from the previous analysis;"
                    + " pass --lyrics to replace them or --force to re-transcribe");
            return score;
        }
        MusicWizardConfig.MlConfig ml = config.ml();
        // Same #383 warning as the alignment key, for the same reason.
        warnIfLayerUnreachable("ml.asrModelDirectory",
                ml == null ? null : ml.asrModelDirectory(),
                global -> global == null ? null : global.asrModelDirectory());
        // The workspace's ml.sherpaNativePath reaches the provider through
        // sherpa's own property, which the provider leaves alone when set.
        String forwardedNativePath = normalized(ml == null ? null : ml.sherpaNativePath());
        if (forwardedNativePath != null
                && normalized(System.getProperty("sherpa_onnx.native.path")) == null) {
            System.setProperty("sherpa_onnx.native.path", forwardedNativePath);
        }
        String wanted = ml == null ? null : ml.asrProvider();
        var provider = MlProviders.asr(wanted);
        if (provider.isEmpty()) {
            runLog.stage("lyrics").skipped("no ASR provider"
                    + (wanted == null || wanted.isBlank()
                            ? " is configured (ml.asrProvider)"
                            : " named '" + wanted + "' is on this classpath"));
            System.out.println("  lyrics not transcribed: no ASR provider"
                    + (wanted == null || wanted.isBlank()
                            ? " is configured (ml.asrProvider)."
                            : " named '" + wanted + "' is on this classpath.")
                    + (MlProviders.asrIds().isEmpty()
                            ? " None are available in this build."
                            : " Available: " + String.join(", ", MlProviders.asrIds())));
            return score;
        }
        String language = java.util.Locale.forLanguageTag(lyricsLanguage).getLanguage();
        if (!provider.get().languages().contains(language)) {
            System.out.println("  lyrics not transcribed: " + provider.get().id()
                    + " speaks " + provider.get().languages()
                    + ", asked for '" + lyricsLanguage + "'");
            runLog.stage("lyrics").fact("provider", provider.get().id())
                    .skipped(provider.get().id() + " speaks " + provider.get().languages()
                            + ", asked for '" + lyricsLanguage + "'");
            return score;
        }
        try {
            AudioBuffer voice = voiceFor(workspace, config, stem);
            var segments = VocalSegments.split(voice.samples(), voice.sampleRate());
            if (segments.isEmpty()) {
                System.out.println("  lyrics not transcribed: no sung stretches found");
                runLog.stage("lyrics").fact("provider", provider.get().id())
                        .skipped("no sung stretches were found to transcribe");
                return score;
            }
            List<List<LyricWord>> stretches = new ArrayList<>();
            int failed = 0;
            for (VocalSegments.Segment segment : segments) {
                float[] window = java.util.Arrays.copyOfRange(
                        voice.samples(), segment.start(), segment.end());
                try {
                    List<LyricWord> words = new ArrayList<>();
                    for (LyricWord word : provider.get().transcribe(
                            window, voice.sampleRate(), language)) {
                        words.add(offsetBy(word, segment.startSeconds(voice.sampleRate())));
                    }
                    stretches.add(words);
                } catch (ModelUnavailableException e) {
                    // No later segment will fare better; whole-run degradation.
                    throw e;
                } catch (RuntimeException e) {
                    failed++;
                }
            }
            Lyrics lyrics = TranscribedLines.grouped(stretches, language);
            if (lyrics.isEmpty()) {
                System.out.println("  lyrics not transcribed: " + provider.get().id()
                        + " heard no words in " + counted(segments.size(),
                                "sung stretch", "sung stretches"));
                runLog.stage("lyrics").fact("provider", provider.get().id())
                        .computed(provider.get().id() + " heard no words in "
                                + counted(segments.size(), "sung stretch", "sung stretches"));
                return score;
            }
            System.out.println("  transcribed " + counted(lyrics.lines().size(),
                    "lyric line") + " from " + counted(segments.size(),
                    "sung stretch", "sung stretches") + " with " + provider.get().id()
                    + (failed > 0 ? "; " + counted(failed, "stretch", "stretches")
                            + " failed" : ""));
            runLog.stage("lyrics")
                    .fact("words from", "the recording, transcribed with "
                            + provider.get().id())
                    .fact("language", lyrics.language())
                    .fact("lines", lyrics.lines().size())
                    .fact("sung stretches", segments.size())
                    .computed(failed > 0
                            ? counted(failed, "stretch", "stretches") + " failed" : null);
            // The recognizer knows the words but not their times; the aligner
            // measures onsets, and only for lyrics transcribed in this run.
            return withAlignedLyrics(workspace, score.withLyrics(lyrics));
        } catch (ModelUnavailableException e) {
            System.err.println("warning: lyrics not transcribed: " + e.getMessage());
            runLog.stage("lyrics").fact("provider", provider.get().id())
                    .failed("lyrics not transcribed: " + e.getMessage());
            return score;
        } catch (RuntimeException e) {
            // A transcriber defect must not take down an analysis that already
            // succeeded; without it the score is simply what it always was.
            System.err.println("warning: lyric transcription failed: " + e.getMessage());
            runLog.stage("lyrics").fact("provider", provider.get().id())
                    .failed("lyric transcription failed: " + e.getMessage());
            return score;
        }
    }

    /**
     * What the transcriber listens to: the vocal stem, or the mix when
     * separation was skipped or has no provider — said out loud, because mix
     * transcription hears the guitars too and the words are measurably worse.
     */
    private static AudioBuffer voiceFor(Workspace workspace, MusicWizardConfig config,
                                        Optional<VocalStem> stem) {
        if (stem.isEmpty()) {
            System.out.println("  transcribing from the full mix"
                    + (skipSeparationRequested(config)
                            ? " (--skip-separation)" : ": no separation provider"));
            return AudioDecoder.decode(workspace.sourceFile());
        }
        // Already separated when this run also read a melody; the stem
        // remembers, so a run asking for both separates once.
        return stem.get().voice(AnalyzeCommand::report);
    }

    /**
     * Says so when a key the provider reads from the global layer only was
     * set somewhere else. The alternative is a run that ignores what the user
     * wrote and reports a symptom with no visible cause.
     */
    private static void warnIfLayerUnreachable(
            String key, String merged,
            java.util.function.Function<MusicWizardConfig.MlConfig, String> fromGlobal) {
        MusicWizardConfig.MlConfig globalMl =
                new ConfigLoader().effectiveConfig(null, null).ml();
        if (!java.util.Objects.equals(normalized(merged),
                normalized(fromGlobal.apply(globalMl)))) {
            System.err.println("warning: " + key + " is read from the global config"
                    + " only (#383); the value in this workspace's config layer does"
                    + " not reach the provider");
        }
    }

    /** Blank and unset mean the same thing to every reader of the key. */
    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String counted(int n, String singular) {
        return counted(n, singular, singular + "s");
    }

    private static String counted(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** The word, moved from segment-relative to recording time. */
    private static LyricWord offsetBy(LyricWord word, double seconds) {
        return LyricWord.ofSeconds(word.text(),
                word.startSeconds() + seconds, word.endSeconds() + seconds,
                word.confidence());
    }

    /**
     * A line's words split into the syllables they are sung on, each keeping
     * its word's parsed span so a failure downstream falls back to what the
     * caller had.
     *
     * <p>Every piece but the last joins the next, and the last carries whatever
     * the word itself said. The flag says the piece continues into the one
     * after it and nothing else — which is what makes the chain readable as one
     * word by everything downstream that rejoins it, the text sheet and the
     * engraver's all-or-nothing among them. Whether a hyphen is <em>drawn</em>
     * between two pieces is not this decision and is not encoded here: a piece
     * that already ends at a break of its own draws none, and the engraver asks
     * {@link Hyphenator#endsAtItsOwnBreak} where it draws.
     *
     * <p>A language with no patterns, or a word the patterns leave in one
     * piece, comes back unchanged: this can only ever split a word further,
     * never merge two, so the aligner's contract of one result per token
     * holds either way.
     */
    private static List<LyricWord> syllablesOf(LyricLine line, String language) {
        var hyphenator = Hyphenator.forLanguage(language);
        if (hyphenator.isEmpty()) {
            return line.words();
        }
        List<LyricWord> out = new ArrayList<>(line.words().size());
        for (LyricWord word : line.words()) {
            List<Hyphenator.Syllable> parts = hyphenator.get().syllables(word.text());
            if (parts.size() < 2) {
                out.add(word);
                continue;
            }
            for (int i = 0; i < parts.size(); i++) {
                boolean last = i == parts.size() - 1;
                out.add(new LyricWord(parts.get(i).text(),
                        word.startSeconds(), word.endSeconds(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        last ? word.hyphenatedToNext() : true,
                        word.melisma(), word.confidence()));
            }
        }
        return List.copyOf(out);
    }

    /** Whether this line shares its parsed start with either neighbour. */
    private static boolean sharesAMoment(List<LyricLine> lines, int i) {
        double start = lines.get(i).startSeconds();
        return (i > 0 && lines.get(i - 1).startSeconds() == start)
                || (i + 1 < lines.size() && lines.get(i + 1).startSeconds() == start);
    }

    /**
     * The line, shifted forward just enough to start at or after the previous
     * line's end, keeping its duration and every within-line interval.
     */
    static LyricLine shiftedAfter(LyricLine line, double previousEnd) {
        double shift = previousEnd - line.startSeconds();
        if (shift <= 0) {
            return line;
        }
        List<LyricWord> shifted = new ArrayList<>(line.words().size());
        for (LyricWord word : line.words()) {
            // The max: s + (p - s) can round below p (never when the shift
            // is at most the start; Sterbenz), so the guard must not trust
            // the addition to land where it aimed -- this is the
            // total-enforcement point.
            double from = Math.max(previousEnd, word.startSeconds() + shift);
            shifted.add(new LyricWord(word.text(),
                    from, Math.max(from, word.endSeconds() + shift),
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    word.hyphenatedToNext(), word.melisma(), word.confidence()));
        }
        return new LyricLine(shifted, line.confidence());
    }

    /**
     * One line, aligned inside a window that starts where the previous line's
     * alignment ended.
     *
     * <p>The window hears half a second past the parsed end, so the trellis
     * can place a last word the singer holds — the reported times never end
     * past the tail bound. The head is sequential: an aligned line
     * cannot start before its predecessor ended. The paths that keep parsed
     * times instead can, which is why the invariant every sheet cursor depends
     * on is enforced where the list is assembled, not here. The window also
     * caps the trellis, so a whole song is many small alignments rather than
     * one enormous one.
     */
    private LyricLine alignedLine(AlignmentProvider aligner, AudioBuffer audio,
                                  String language, LyricLine line,
                                  double previousAlignedEnd, double tailBound) {
        double from = Math.max(Math.max(0, line.startSeconds() - 0.5),
                previousAlignedEnd);
        double to = Math.min(audio.durationSeconds(), line.endSeconds() + 0.5);
        int start = audio.indexOf(from);
        int end = Math.max(start, audio.indexOf(to));
        if (end - start < audio.sampleRate() / 10 || tailBound <= from + 0.1) {
            // No room to align: a degenerate window, or a tail bound at the
            // window head -- which a word-tagged twin can produce (#390).
            return line;
        }
        float[] window = new float[end - start];
        System.arraycopy(audio.samples(), start, window, 0, window.length);
        // Syllables, not words, where the language has patterns for them
        // (#414). The engraved sheet prints a syllable per note, and dividing
        // a word's measured span evenly between its syllables is wrong in a
        // way a reader sees: sung Italian holds the stressed one, so a
        // downbeat inside a long first syllable printed on the second. The
        // aligner is placing tokens either way, and a syllable is a token it
        // can place -- so the measurement is taken where it is used.
        List<LyricWord> tokens = syllablesOf(line, language);
        List<String> texts = tokens.stream().map(LyricWord::text).toList();
        List<LyricWord> placed = aligner.align(window, audio.sampleRate(),
                language, texts);
        if (placed.size() != tokens.size()) {
            return line;
        }
        boolean anyExpressed = placed.stream()
                .anyMatch(word -> word.endSeconds() > word.startSeconds());
        if (!anyExpressed) {
            // A line of digits or punctuation has nothing the vocabulary can
            // carry; the aligner stacks it at the window start, which is worse
            // than the parsed guess. Keep the guess.
            return line;
        }
        // The tail bound is honoured by compression, not clamping: clamping
        // flattened overrunning words into zero-length piles on the bound; a
        // proportional squeeze keeps the order and spacing the aligner
        // measured. The max, not the last word's end: nothing here may assume
        // recognition spans cannot overlap.
        // Min and max over the words, symmetrically: the SPI promises one
        // word per input word, in order, and promises nothing about the times
        // being monotone -- so neither end may be read off one word.
        double lineStart = Double.POSITIVE_INFINITY;
        double lineEnd = Double.NEGATIVE_INFINITY;
        for (LyricWord word : placed) {
            lineStart = Math.min(lineStart, from + word.startSeconds());
            lineEnd = Math.max(lineEnd, from + word.endSeconds());
        }
        double scale = lineEnd > tailBound && lineEnd > lineStart
                ? (tailBound - lineStart) / (lineEnd - lineStart)
                : 1.0;
        if (scale < 0.5) {
            // One predicate for every degenerate shape: a result wholly past
            // the bound scales negative and would reverse the words; one
            // barely inside it scales toward zero and becomes a sliver; and
            // anything below half is no longer the aligner's measurement but
            // structure overriding it. The parsed guess wins all three.
            return line;
        }
        List<LyricWord> out = new ArrayList<>(tokens.size());
        double joinedAfter = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < placed.size(); i++) {
            LyricWord original = tokens.get(i);
            LyricWord timed = placed.get(i);
            // The aligner's clock starts at the window; the line's starts at
            // the recording. Keep the original text and engraving flags -- the
            // aligner only ever decides when.
            double wordStart = lineStart
                    + (from + timed.startSeconds() - lineStart) * scale;
            // Syllables of one word are kept in order, and only they. The SPI
            // promises nothing about the times being monotone, and LyricLine
            // sorts its words by start -- so two syllables returned the wrong
            // way round would come back as a misspelt word rather than as two
            // words out of order. Words are left alone: their order is a
            // measurement, and overriding it here would hide a real fault.
            wordStart = Math.max(wordStart, joinedAfter);
            double wordEnd = lineStart
                    + (from + timed.endSeconds() - lineStart) * scale;
            out.add(new LyricWord(original.text(), wordStart,
                    Math.max(wordStart, wordEnd),
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    original.hyphenatedToNext(), original.melisma(),
                    timed.confidence()));
            joinedAfter = original.hyphenatedToNext()
                    ? wordStart : Double.NEGATIVE_INFINITY;
        }
        return new LyricLine(out, weakestOf(out));
    }

    private static Confidence weakestOf(List<LyricWord> words) {
        return words.stream().map(LyricWord::confidence)
                .min(java.util.Comparator.comparingDouble(Confidence::value))
                .orElse(Confidence.of(0));
    }

    // ------------------------------------------------------------------- cache

    /**
     * A score, where it came from, the key it belongs under, and what the
     * stages under that key recorded — empty when the score came from the
     * cache, since those entries came with it.
     */
    private record Transcription(Score score, StageCache.Key key, boolean fromCache,
                                 List<RunManifest.StageRun> stages) {
    }

    /**
     * The transcription, from the cache when it is there and from the pipeline
     * when it is not.
     *
     * <p>A cached entry that cannot be read is recomputed rather than raised. The
     * cache is an optimisation, and a workspace whose {@code cache/} was
     * truncated by a full disk -- or written by a build whose score schema has
     * since moved on -- must still analyse rather than become unusable.
     */
    private Transcription transcribe(
            Workspace workspace, SourceKind kind, Path source, MusicWizardConfig config,
            Optional<VocalStem> stem) {
        StageCache cache = workspace.cache();
        AudioTranscriber.Options options = audioOptions(kind, config);
        StageCache.Key key = transcriptionKey(kind, source, options,
                skipSeparationRequested(config), config.isLlmEnabled(),
                melodySignal(options, stem));
        if (kind == SourceKind.AUDIO && melody && stem.isEmpty()) {
            // Said before the analysis and whether or not it is recomputed: it
            // describes the melody the score ends up carrying, and a cached one
            // was read from the mix too.
            System.out.println("  the melody is read from the full mix"
                    + (skipSeparationRequested(config)
                            ? " (--skip-separation)" : ": no separation provider")
                    + "; the tracker is monophonic, so it returns the loudest periodic"
                    + " line rather than the voice");
        }

        if (!force) {
            Score cached = readCached(cache, key);
            if (cached != null) {
                // Said every time: on a development build the key cannot
                // change when the code does, and this is the only warning
                // that the pipeline did not run.
                System.out.println("  reusing the cached analysis of this file;"
                        + " --force recomputes it");
                recordCachedStages(cache, key);
                return new Transcription(cached, key, true, List.of());
            }
        }

        // A branch for the stages under the cache key, because they are stored
        // with the score and replayed for the run that is served it; the rest
        // of this run's stages are not a function of the key. A branch rather
        // than a log of its own so that separating, which happens inside this
        // call and is not keyed, is recorded in this run where it ran.
        RunLog keyed = runLog.branch();
        Score score = switch (kind) {
            case AUDIO -> new AudioTranscriber(AnalyzeCommand::report, keyed)
                    .transcribe(source, options, melodySupplier(stem));
            case MIDI -> new MidiTranscriber(AnalyzeCommand::report, keyed).transcribe(source);
        };
        return new Transcription(score, key, false, keyed.stages());
    }

    /**
     * Replays what the stages recorded when this cached answer was computed,
     * as this run reporting that it was served them.
     *
     * <p>They are facts about the input and the options, which is exactly what
     * the key is made of, so a run served the answer is served the record with
     * it. An entry written before there was one leaves the single line that
     * says so, rather than leaving the page to imply that no stage ran.
     */
    private void recordCachedStages(StageCache cache, StageCache.Key key) {
        List<RunManifest.StageRun> stages;
        try {
            stages = cache.readText(key, STAGES_EXTENSION)
                    .map(RunManifestJson::stagesFromJson)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            stages = List.of();
        }
        if (stages.isEmpty()) {
            runLog.stage("analysis").cached("this file's cached analysis was computed"
                    + " before runs were recorded, so what its stages did is not known");
            return;
        }
        runLog.recordAll(stages.stream().map(RunManifest.StageRun::asCached).toList());
    }

    /**
     * What the melody stage listens to, or null for the mix.
     *
     * <p>A separator that fails hands back the mix rather than taking down an
     * analysis that has otherwise succeeded — the same degradation every
     * provider-backed stage here makes, and it leaves {@code --melody} working
     * exactly where it worked before separation was wired to it. The mix
     * melody is worth having: it is what this stage produced until now.
     */
    private Supplier<AudioBuffer> melodySupplier(Optional<VocalStem> stem) {
        if (!melody || stem.isEmpty()) {
            return null;
        }
        return () -> {
            try {
                return stem.get().voice(AnalyzeCommand::report);
            } catch (RuntimeException e) {
                melodyFellBackToTheMix = true;
                // The provider's own message last, with nothing of ours after
                // it: what distinguishes an offline machine from a failed
                // checksum from a dropped connection is somewhere in it, it
                // has no length anyone here controls, and the melody harness
                // quotes it into a bounded skip row by taking what follows the
                // marker.
                System.err.println("warning: the melody is read from the full mix,"
                        + " where the tracker returns the loudest periodic line"
                        + " rather than the voice; the vocal could not be separated: "
                        + e.getMessage());
                return null;
            }
        };
    }

    /**
     * What the melody stage will read, for the cache key: {@code off}, the mix,
     * or the separator that will produce the stem.
     *
     * <p>Package-private so a test can compare two keys without a recording.
     */
    static String melodySignal(AudioTranscriber.Options options, Optional<VocalStem> stem) {
        if (options == null || !options.trackMelody()) {
            return "off";
        }
        return stem.map(VocalStem::providerId).map(id -> "stem:" + id).orElse("mix");
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
     * Stores a result for the next run, or says why it could not. Guarded
     * like {@link #readCached}: a cache is an optimisation, and a full or
     * read-only {@code cache/} must not cost an analysis that has already
     * succeeded.
     */
    private static void storeQuietly(StageCache cache, StageCache.Key key, Score score,
                                     List<RunManifest.StageRun> stages) {
        try {
            cache.writeText(key, ".json", ScoreJson.toJson(score));
        } catch (RuntimeException e) {
            System.err.println("warning: this analysis could not be cached, so the next run"
                    + " will recompute it: " + e.getMessage());
            return;
        }
        try {
            // Separately, and after: what the stages did is worth having and
            // worth nothing on its own, so it must neither be the reason a
            // computed analysis goes uncached nor go missing in silence.
            cache.writeText(key, STAGES_EXTENSION, RunManifestJson.stagesToJson(stages));
        } catch (RuntimeException e) {
            System.err.println("warning: what these stages did was not cached with the"
                    + " analysis, so a run served it will not be able to say: "
                    + e.getMessage());
        }
    }

    /**
     * The cache key for one transcription.
     *
     * <p>The input kind is carried by the <em>stage name</em>, not a
     * component: both paths read the same bytes, so the digest cannot
     * separate them, and a kind component was once distinct only by accident.
     * {@code StageCache} gives each stage its own directory, which cannot be
     * accidental and makes a workspace diagnosable by eye.
     *
     * <p>The build is a component so an upgrade does not serve the previous
     * version's answer — but it only invalidates when the version string
     * changes, which no SNAPSHOT or {@code target/classes} run does; that is
     * why the reuse is announced on every hit and why {@code --force} exists.
     * The audio settings are components only on the audio path, where they are
     * read. {@code advisorEnabled} is a component even though nothing reads it
     * yet (#11): a setting that will change the analysis while the key does not
     * is how a corrected run gets served the answer it was correcting, a shape
     * already paid for once with {@code --tempo}. The advisor is keyed on both
     * paths — it is not an audio stage.
     *
     * <p>{@code melodySignal} is that rule applied to what {@code --melody}
     * now reads (#559): the same options over the same file give a different
     * melody depending on whether a separator was there, so the key says which
     * one was, and not merely that a melody was asked for. {@code
     * skipSeparation} stays a component of its own because it also decides what
     * the lyric stages hear.
     *
     * <p>Package-private so a test can compare two keys over the same file.
     */
    static StageCache.Key transcriptionKey(SourceKind kind, Path source,
                                           AudioTranscriber.Options options,
                                           boolean skipSeparation, boolean advisorEnabled,
                                           String melodySignal) {
        StageCache.Key key = StageCache.Key
                .forStage(STAGE_PREFIX + kind.name().toLowerCase(Locale.ROOT))
                .with("build", buildVersion())
                .with("advisor", advisorEnabled)
                .withFile("source", source);
        if (kind == SourceKind.AUDIO && options != null) {
            key.with("tempo", options.tempoOverride())
                    .with("meter", options.timeSignatureOrDefault())
                    .with("firstDownbeat", options.firstDownbeatSeconds())
                    .with("melody", melodySignal)
                    .with("skipSeparation", skipSeparation);
        }
        return key;
    }

    // ---------------------------------------------------------------- manifest

    /**
     * Says so for the stages nothing reached, rather than leaving them out.
     *
     * <p>A stage missing from the record and a stage that did not run read the
     * same on a page, and only one of them is a statement about this run. Each
     * of these is a stage that runs on a condition none of its own code sees.
     */
    private void recordUnreachedStages() {
        if (!recorded("separation")) {
            runLog.stage("separation").skipped("nothing in this run needed a separated vocal");
        }
        if (!recorded("lyrics")) {
            runLog.stage("lyrics")
                    .skipped("no words were supplied to this run and none were transcribed");
        }
        if (!recorded("lyric-alignment")) {
            runLog.stage("lyric-alignment").skipped("this run placed no new words to align");
        }
    }

    private boolean recorded(String stage) {
        return runLog.stages().stream().anyMatch(entry -> entry.stage().equals(stage));
    }

    /**
     * Writes what this run did, or says why it could not.
     *
     * <p>Guarded like the cache write, and for the same reason: a record of an
     * analysis must not be able to cost the analysis.
     */
    private void writeManifestQuietly(Workspace workspace, Instant startedAt,
                                      SourceKind kind, MusicWizardConfig config) {
        try {
            workspace.writeRunManifest(new RunManifest(
                    RunManifest.CURRENT_SCHEMA_VERSION,
                    buildVersion(),
                    startedAt.toString(),
                    Instant.now().toString(),
                    settingsThatSteeredTheRun(kind, config),
                    runLog.stages()));
        } catch (RuntimeException e) {
            System.err.println("warning: this run could not be recorded, so the analysis"
                    + " report will not be able to say what it did: " + e.getMessage());
        }
    }

    /**
     * The settings this run acted on, and only those: a correction that the
     * path taken never reads is not something that steered anything.
     */
    private Map<String, String> settingsThatSteeredTheRun(
            SourceKind kind, MusicWizardConfig config) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("source", kind.description());
        var analysis = config.analysis();
        if (kind == SourceKind.AUDIO) {
            if (analysis != null && analysis.tempoOverride() != null) {
                settings.put("tempo forced to", String.format(Locale.ROOT,
                        "%.1f counted beats a minute", analysis.tempoOverride()));
            }
            if (analysis != null && analysis.timeSignatureOverride() != null) {
                settings.put("meter forced to", analysis.timeSignatureOverride());
            }
            if (analysis != null && analysis.firstDownbeatSecondsOverride() != null) {
                settings.put("first downbeat forced to", String.format(Locale.ROOT,
                        "%.3f s", analysis.firstDownbeatSecondsOverride()));
            }
            settings.put("melody", melody ? "read from the recording" : "not read");
        }
        settings.put("advisor", config.isLlmEnabled() ? "enabled" : "disabled");
        if (force) {
            settings.put("cached results", "ignored (--force)");
        }
        if (lyricsFile != null) {
            settings.put("lyrics file", lyricsFile.getFileName().toString());
        }
        if (lyricsLanguage != null && !lyricsLanguage.isBlank()) {
            settings.put("lyrics language", lyricsLanguage);
        }
        return settings;
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
     * <p>Every figure here is an estimate, and the running commentary above it
     * has already said so in the verbs it used. The key carries its confidence
     * anyway, because it is the one row whose failure mode is invisible: a key
     * and its relative minor are the same seven notes, so a wrong answer here
     * reads as plausible as a right one and only the confidence says which was
     * settled and which was a coin flip. Which figures those are is
     * {@link Key#displayNameWithConfidence()}'s answer. The row is absent when
     * nothing sounded and no key was estimated.
     */
    private static List<String> audioSummary(Score score) {
        List<String> lines = new ArrayList<>();
        lines.add(tempoLine(score));
        lines.add("Meter   " + score.tempoMap().initialTimeSignature());
        score.primaryKey().ifPresent(key ->
                lines.add("Key     " + key.displayNameWithConfidence()));
        lines.add("Chords  " + score.chords().size() + " spans");
        // Absent when there is no melody, which covers both the stage not being
        // asked for and its having heard nothing: the transcriber adds no empty
        // track, so this row cannot read zero. The run that heard nothing has
        // already said so in its progress line.
        score.track(PartRole.LEAD_VOCAL).ifPresent(melodyTrack -> {
            lines.add("Melody  " + melodyTrack.size() + " notes");
            // Stated where the run reports (#602): every second of placed
            // words with no note under it, which is a wider count than the
            // page's bar-level marks. Guarded on the figure printed, so the
            // row cannot assert a gap of zero.
            long unread = Math.round(unreadSeconds(score, melodyTrack));
            if (unread > 0) {
                lines.add("        no notes under " + unread + " s of placed words");
            }
        });
        return List.copyOf(lines);
    }

    /**
     * How much of the placed words' time holds no melody note at all, in
     * seconds.
     */
    static double unreadSeconds(Score score, NoteTrack melody) {
        List<double[]> spans = new ArrayList<>();
        for (LyricLine line : score.lyrics().lines()) {
            for (LyricWord word : line.words()) {
                spans.add(new double[] {word.startSeconds(), word.endSeconds()});
            }
        }
        spans.sort(java.util.Comparator.comparingDouble(span -> span[0]));
        double unread = 0;
        double reached = Double.NEGATIVE_INFINITY;
        for (double[] span : spans) {
            double from = Math.max(span[0], reached);
            if (span[1] <= from) {
                continue;
            }
            double covered = 0;
            // A frontier over the notes as well as the words: notes may
            // overlap (#93), and summing raw overlaps would count one moment
            // twice and report a gap as covered.
            double noteReached = from;
            for (var note : melody.notes()) {
                double start = Math.max(note.onsetSeconds(), noteReached);
                double end = Math.min(note.offsetSeconds(), span[1]);
                if (end > start) {
                    covered += end - start;
                    noteReached = end;
                }
            }
            unread += Math.max(0, span[1] - from - covered);
            reached = Math.max(reached, span[1]);
        }
        return unread;
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
        // Outside the block: part names are partly read and partly
        // synthesised, and the heading must not cover both.
        lines.add("Parts   " + partsLine(score));
        // Outside the block too: chords are estimated (#115) over declared
        // tempo and meter, and the split is what stops the one being read as
        // the other. The empty wording comes from MissingHarmony so it cannot
        // contradict what render says about the same score.
        lines.add(score.chords().isEmpty()
                ? "Chords  none, " + MissingHarmony.explain(score)
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

    /**
     * The key the file declares, and from where. Where matters here and not in
     * the rows above: {@link TempoMap} pins its first tempo and meter to the
     * origin by construction, but {@link Score#keys()} holds the file's events
     * at whatever tick they carry, so the first may be four bars in and
     * printing it unqualified claims a key the file does not state. The
     * position is labelled with the axis it was read from — a deserialized key
     * need not carry beats, and printing seconds under the word "beat" is
     * wrong in the one unit that is load-bearing. Changes are counted on
     * {@link Key#displayName()}, what the user reads; counting on the tonic
     * compared a {@link PitchSpelling} octave the key never prints. The
     * importer's own line still contradicts this row (#127, same shape as
     * #118); a test asserts that contradiction so fixing it trips a reminder.
     */
    private static String statedKey(Score score) {
        if (score.keys().isEmpty()) {
            return "not declared by the file";
        }
        Key first = score.keys().get(0);
        String opening = first.displayName();
        int changes = countChanges(score.keys(), Key::displayName);
        String tail = changes == 0 ? "" : ", " + changed(changes);
        // Whichever axis the key actually carries, labelled as that axis, and
        // null when it begins at the origin and there is nothing to qualify.
        String from;
        if (first.startBeat().isPresent()) {
            double beat = first.startBeat().get();
            from = beat <= ORIGIN_TOLERANCE
                    ? null : String.format(Locale.ROOT, "from beat %.3f", beat);
        } else {
            double seconds = first.startSeconds();
            from = seconds <= ORIGIN_TOLERANCE
                    ? null : String.format(Locale.ROOT, "from %.3fs", seconds);
        }
        if (from == null) {
            return changes == 0 ? opening : opening + " at the start" + tail;
        }
        return "not declared at the start; " + opening + " " + from + tail;
    }

    /**
     * How near the origin a position has to be to count as being at it. MIDI
     * tick 0 divides to exactly zero; this is for somebody else's JSON, where
     * "from beat 0.000" would be true and useless.
     */
    private static final double ORIGIN_TOLERANCE = 1e-9;

    /**
     * How many times a value actually changes along a list — transitions, not
     * entries: a sequencer export restates the same tempo at every section
     * boundary, and reporting those as changes is a claim the file does not
     * make. The importer's own stage line still counts entries (#118), so the
     * output does contradict itself until that lands. Exact {@code equals} on
     * the tempo doubles is right here: a restated tempo decodes to the
     * identical double, and one differing in the last bit came from a
     * different microsecond count and really is a change.
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
     * The audio summary's tempo row, in the unit the user counts in — in 6/8
     * the stored quarter rate is not a number the user can type back via
     * {@code --tempo}. From {@link Score#estimatedTempo()}, so this and the
     * engraved chart's header print the same number.
     */
    static String tempoLine(Score score) {
        return "Tempo   " + formatTempo(
                score.estimatedTempo(), score.tempoMap().initialTimeSignature());
    }

    /**
     * One tempo, in the beat its meter is counted in — the single formatter
     * both paths go through, so the 6/8 qualification cannot be taught to one
     * and not the other. One decimal place: a MIDI tempo event carries whole
     * microseconds per quarter, and further digits advertise a precision that
     * is an artefact of the encoding.
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
     * Says which typed options this run will not act on, and why — ignoring an
     * instruction the user typed is #82's defect one command over. The tempo,
     * meter and downbeat overrides are read from the typed fields: a config
     * file carrying them is a preference that happens not to apply, and
     * warning on every MIDI analysis would be noise. {@code --skip-separation}
     * is read from the effective config — "does nothing in this run" is not
     * safe to leave unsaid.
     */
    private void warnAboutOptionsThatDoNothing(SourceKind kind, MusicWizardConfig config) {
        if (transcriptionRequested() && kind != SourceKind.AUDIO) {
            // render's no-lyrics message offers this option without naming a
            // source kind, because render cannot know one; this command can,
            // and following that advice on a MIDI workspace must not be
            // answered with silence. "Nothing is transcribed", not "no lyrics
            // are produced": carried-forward lyrics may reach the score anyway.
            System.err.println("warning: --lyrics-language alone asks for"
                    + " transcription, which needs a recording; this workspace"
                    + " holds a " + kind.description() + ", so nothing is"
                    + " transcribed");
        }
        if (kind == SourceKind.MIDI) {
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
            if (!ignored.isEmpty()) {
                System.err.println("warning: " + String.join(", ", ignored)
                        + (ignored.size() == 1 ? " has" : " have")
                        + " no effect on a MIDI workspace; the file declares its own tempo"
                        + " and meter");
            }
            // Its own line: the list above's reason (the file declares these)
            // is not this one's — no MIDI part is ever read as the melody
            // role, so what the flag points at is unreachable here (#500).
            if (melody) {
                System.err.println("warning: --melody has no effect on a MIDI workspace;"
                        + " the parts are read from the file, and none of them is read as"
                        + " the melody role, so no lead sheet can be rendered from one"
                        + " (#500)");
            }
        }
        if (skipSeparationRequested(config)
                && !(kind == SourceKind.AUDIO && (transcriptionRequested() || melody))) {
            System.err.println("warning: skipping separation changes nothing in this run;"
                    + " its only effects today are making --melody and lyric"
                    + " transcription (--lyrics-language without --lyrics) hear the full"
                    + " mix, both audio only, and separation feeds the rest of the"
                    + " analysis under #8");
        }
    }

    /**
     * Whether this run asks for lyrics to be heard from the recording: a
     * language stated, no file supplied. One predicate, shared with the
     * do-nothing warning — two spellings of it disagreed on a blank tag,
     * which neither transcribed nor warned.
     */
    private boolean transcriptionRequested() {
        return lyricsFile == null
                && lyricsLanguage != null && !lyricsLanguage.isBlank();
    }

    /** Whether this run was asked to skip separation, from any config layer. */
    private static boolean skipSeparationRequested(MusicWizardConfig config) {
        var analysis = config.analysis();
        return analysis != null && Boolean.TRUE.equals(analysis.skipSeparation());
    }

    /**
     * The audio pipeline's options, or {@code null} on the MIDI path.
     *
     * <p>Null rather than defaults, so that a caller which reads them on the MIDI
     * path fails visibly rather than silently acting on 4/4.
     */
    private AudioTranscriber.Options audioOptions(
            SourceKind kind, MusicWizardConfig config) {
        if (kind != SourceKind.AUDIO) {
            return null;
        }
        var analysis = config.analysis();
        TimeSignature meter = parseMeter(analysis != null ? analysis.timeSignatureOverride() : null);
        // The melody flag alone comes from the command line rather than from the
        // config: the other three are corrections a workspace keeps, and this one
        // is a statement about the recording handed in on the day.
        return new AudioTranscriber.Options(
                analysis != null ? analysis.tempoOverride() : null,
                meter,
                analysis != null ? analysis.firstDownbeatSecondsOverride() : null,
                melody);
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
