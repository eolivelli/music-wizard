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
import dev.olivelli.musicwizard.core.workspace.StageCache;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
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
                    + "The tracker is monophonic: give it a recording whose melody is "
                    + "the only thing sounding, or a separated vocal stem. On a full "
                    + "mix it returns the loudest periodic line, usually the bass, "
                    + "rather than failing. Audio only.")
    boolean melody;

    @Option(names = "--skip-separation",
            description = "Analyse the mix directly instead of separating stems. Today "
                    + "this only makes lyric transcription hear the full mix; chords "
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

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        MusicWizardConfig config = workspace.effectiveConfig(overrides());
        Path source = workspace.sourceFile();
        SourceKind kind = SourceKind.detect(source);

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

        Transcription result = transcribe(workspace, kind, source, config);
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
            score = withTranscribedLyrics(workspace, score, config);
        }

        // The score is persisted before the cache entry: the failure cannot
        // then happen before the deliverable is safe.
        workspace.writeScore(score);
        if (!result.fromCache()) {
            // The transcription, not the titled score — the key says nothing
            // about metadata, and titled() runs on the way out of the cache.
            storeQuietly(workspace.cache(), result.key(), result.score());
        }

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
                    .map(score::withLyrics)
                    .orElse(score);
        } catch (RuntimeException e) {
            System.err.println("warning: the score already in this workspace could not be"
                    + " read, so any lyrics it held are not carried over; it is being"
                    + " replaced: " + e.getMessage());
            return score;
        }
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
            return carriedForward(workspace, score);
        }
        String text;
        try {
            text = Files.readString(lyricsFile);
        } catch (IOException | RuntimeException e) {
            System.err.println("warning: the lyrics file could not be read, so this"
                    + " analysis has no lyrics: " + e.getMessage());
            return score;
        }
        Lyrics lyrics = LrcLyrics.parse(text, score.durationSeconds(), languageTag());
        if (lyrics.isEmpty()) {
            System.err.println(LrcLyrics.looksLikeLrc(text)
                    ? "warning: the lyrics file has timestamps but no words under them,"
                            + " so this analysis has no lyrics."
                    : "warning: the lyrics file carries no [mm:ss.xx] timestamps, so it"
                            + " cannot be placed; expected an LRC file.");
            return score;
        }
        System.out.println("  read " + counted(lyrics.lines().size(), "lyric line")
                + " from " + lyricsFile.getFileName());
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
            return score;
        }
        try {
            AudioBuffer audio = AudioDecoder.decode(workspace.sourceFile());
            List<LyricLine> parsed = lyrics.lines();
            List<LyricLine> aligned = new ArrayList<>(parsed.size());
            List<Confidence> measured = new ArrayList<>();
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
                result = shiftedAfter(result, previousEnd);
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
            return score.withLyrics(
                    new Lyrics(aligned, lyrics.language(), lyrics.confidence()));
        } catch (ModelUnavailableException e) {
            System.err.println("warning: lyrics stay at their parsed times: "
                    + e.getMessage());
            return score;
        } catch (RuntimeException e) {
            // An aligner defect must not take down an analysis that already
            // succeeded; the parsed times are what we had before it existed.
            System.err.println("warning: lyric alignment failed, keeping parsed"
                    + " times: " + e.getMessage());
            return score;
        }
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
                                        MusicWizardConfig config) {
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
            return score;
        }
        try {
            AudioBuffer voice = voiceFor(workspace, config);
            var segments = VocalSegments.split(voice.samples(), voice.sampleRate());
            if (segments.isEmpty()) {
                System.out.println("  lyrics not transcribed: no sung stretches found");
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
                return score;
            }
            System.out.println("  transcribed " + counted(lyrics.lines().size(),
                    "lyric line") + " from " + counted(segments.size(),
                    "sung stretch", "sung stretches") + " with " + provider.get().id()
                    + (failed > 0 ? "; " + counted(failed, "stretch", "stretches")
                            + " failed" : ""));
            // The recognizer knows the words but not their times; the aligner
            // measures onsets, and only for lyrics transcribed in this run.
            return withAlignedLyrics(workspace, score.withLyrics(lyrics));
        } catch (ModelUnavailableException e) {
            System.err.println("warning: lyrics not transcribed: " + e.getMessage());
            return score;
        } catch (RuntimeException e) {
            // A transcriber defect must not take down an analysis that already
            // succeeded; without it the score is simply what it always was.
            System.err.println("warning: lyric transcription failed: " + e.getMessage());
            return score;
        }
    }

    /**
     * What the transcriber listens to: the vocal stem, or the mix when
     * separation was skipped or has no provider — said out loud, because mix
     * transcription hears the guitars too and the words are measurably worse.
     */
    private static AudioBuffer voiceFor(Workspace workspace, MusicWizardConfig config) {
        var separation = skipSeparationRequested(config)
                ? java.util.Optional.<dev.olivelli.musicwizard.core.ml.SeparationProvider>empty()
                : MlProviders.separation(
                        config.ml() == null ? null : config.ml().separationProvider());
        if (separation.isEmpty()) {
            AudioBuffer mix = AudioDecoder.decode(workspace.sourceFile());
            System.out.println("  transcribing from the full mix"
                    + (skipSeparationRequested(config)
                            ? " (--skip-separation)" : ": no separation provider"));
            return mix;
        }
        int preferred = separation.get().preferredSampleRate();
        AudioBuffer mix = preferred > 0
                ? AudioDecoder.decode(workspace.sourceFile(), preferred)
                : AudioDecoder.decode(workspace.sourceFile());
        // Announced like every other stage: this takes real time, and a
        // command that reports each step must not sit mute through it.
        System.out.println("  separating the vocal with " + separation.get().id());
        float[] vocals = separation.get()
                .separate(new float[][] {mix.samples()}, mix.sampleRate())
                .vocals()[0];
        return new AudioBuffer(vocals, mix.sampleRate());
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

    /** A score, where it came from, and the key it belongs under. */
    private record Transcription(Score score, StageCache.Key key, boolean fromCache) {
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
            Workspace workspace, SourceKind kind, Path source, MusicWizardConfig config) {
        StageCache cache = workspace.cache();
        StageCache.Key key = transcriptionKey(kind, source, audioOptions(kind, config),
                skipSeparationRequested(config), config.isLlmEnabled());

        if (!force) {
            Score cached = readCached(cache, key);
            if (cached != null) {
                // Said every time: on a development build the key cannot
                // change when the code does, and this is the only warning
                // that the pipeline did not run.
                System.out.println("  reusing the cached analysis of this file;"
                        + " --force recomputes it");
                return new Transcription(cached, key, true);
            }
        }

        Score score = switch (kind) {
            case AUDIO -> new AudioTranscriber(AnalyzeCommand::report)
                    .transcribe(source, audioOptions(kind, config));
            case MIDI -> new MidiTranscriber(AnalyzeCommand::report).transcribe(source);
        };
        return new Transcription(score, key, false);
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
    private static void storeQuietly(StageCache cache, StageCache.Key key, Score score) {
        try {
            cache.writeText(key, ".json", ScoreJson.toJson(score));
        } catch (RuntimeException e) {
            System.err.println("warning: this analysis could not be cached, so the next run"
                    + " will recompute it: " + e.getMessage());
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
     * read. {@code skipSeparation} and {@code advisorEnabled} are components
     * even though nothing reads either yet (#8, #11): a setting that will
     * change the analysis while the key does not is how a corrected run gets
     * served the answer it was correcting, a shape already paid for once with
     * {@code --tempo}. The advisor is keyed on both paths — it is not an audio
     * stage.
     *
     * <p>Package-private so a test can compare two keys over the same file.
     */
    static StageCache.Key transcriptionKey(SourceKind kind, Path source,
                                           AudioTranscriber.Options options,
                                           boolean skipSeparation, boolean advisorEnabled) {
        StageCache.Key key = StageCache.Key
                .forStage(STAGE_PREFIX + kind.name().toLowerCase(Locale.ROOT))
                .with("build", buildVersion())
                .with("advisor", advisorEnabled)
                .withFile("source", source);
        if (kind == SourceKind.AUDIO && options != null) {
            key.with("tempo", options.tempoOverride())
                    .with("meter", options.timeSignatureOrDefault())
                    .with("firstDownbeat", options.firstDownbeatSeconds())
                    .with("melody", options.trackMelody())
                    .with("skipSeparation", skipSeparation);
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
     * <p>Every figure here is an estimate, and the running commentary above it
     * has already said so in the verbs it used. The key carries its confidence
     * anyway, because it is the one row whose failure mode is invisible: a key
     * and its relative minor are the same seven notes, so a wrong answer here
     * reads as plausible as a right one and only the confidence says which was
     * settled and which was a coin flip. Both of the estimator's figures where
     * it recorded them, since a shaky signature and a coin-flip tonic are
     * different complaints and the reader's next move differs. The row is
     * absent when nothing sounded and no key was estimated.
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
        score.track(PartRole.LEAD_VOCAL).ifPresent(melodyTrack ->
                lines.add("Melody  " + melodyTrack.size() + " notes"));
        return List.copyOf(lines);
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
                && !(kind == SourceKind.AUDIO && transcriptionRequested())) {
            System.err.println("warning: skipping separation changes nothing in this run;"
                    + " its only effect today is making lyric transcription"
                    + " (--lyrics-language without --lyrics, audio only) hear the full"
                    + " mix, and separation feeds the rest of the analysis under #8");
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
