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
     * file.
     *
     * <p>#120 landed while this was in review and made half of that answerable:
     * {@link TempoMap.TempoSegment} now carries a {@link Provenance}, and
     * {@code MidiTranscriber} marks a defaulted opening tempo {@code ASSUMED}
     * where a declared one is {@code DECLARED}. So the <em>tempo</em> row could
     * be tightened today. The meter row could not -- {@code MeterChange} carries
     * no provenance -- and a block whose rows made claims of different strengths
     * without saying which was which would be worse than one honest heading. #119
     * is where the other half goes, and tightening both rows together is the
     * change to make after it.
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
                    + "nearest tracked beat, which then begins a bar in the "
                    + "saved beat grid and the chart's first bar line. Does not "
                    + "renumber the tempo map's bars. Audio only.")
    Double firstDownbeat;

    @Option(names = "--skip-separation",
            description = "Analyse the mix directly instead of separating stems. Has no "
                    + "effect yet: nothing separates stems until #8 lands.")
    boolean skipSeparation;

    @Option(names = "--no-llm",
            description = "Disable the Claude advisor layer for this run. The layer does "
                    + "not exist yet (#11), so today this only changes the cache key and "
                    + "forces a recompute.")
    boolean noLlm;

    @Option(names = "--lyrics", paramLabel = "FILE",
            description = "Read lyrics from an LRC file and place them under the "
                    + "chords. Word timings are used when the file carries them "
                    + "and estimated within each line when it does not. Nothing "
                    + "transcribes lyrics from audio yet (#9).")
    Path lyricsFile;

    @Option(names = "--lyrics-language", paramLabel = "TAG",
            description = "What language the lyrics are in, e.g. it or en. Used to "
                    + "split words into syllables for the engraved sheet. An LRC "
                    + "file does not say, and a wrong answer splits words on "
                    + "another language's rules, so without this they stay whole.")
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
            // No longer "re-run with --force to recompute". The transcription
            // cache this command added is keyed on the file's own digest, so a
            // changed source misses it and recomputes without being asked. What
            // is genuinely stale is anything already written from the old file.
            System.err.println(
                    "warning: the source recording has changed since this workspace was"
                            + " created; this analysis is of the file as it is now, and"
                            + " anything already rendered from the old one is out of date.");
        }
        warnAboutOptionsThatDoNothing(kind, config);

        System.out.println("Workspace  " + workspace.root());
        System.out.println("Source     " + source.getFileName() + " (" + kind.description() + ")");
        // Qualified, because the layer does not exist yet: #11. Announcing it
        // as simply "enabled" is the defect #82 was filed for, in the command
        // next door -- and it matters slightly more now that the flag is a cache
        // key component, since invalidating an entry is currently its only
        // effect.
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
        }

        // The score is persisted before the cache entry, and never after. The
        // score is what the user asked for; the cache is an optimisation for the
        // next run, and losing minutes of DSP because a cache write failed is a
        // poor trade in either order -- but this order also means the failure
        // cannot happen before the deliverable is safe.
        workspace.writeScore(score);
        if (!result.fromCache()) {
            // The transcription, not the titled score. The key covers the
            // recording and the options and says nothing about the metadata, so
            // an entry carrying a title would be served to a workspace that
            // names the piece differently -- and titled() runs on the way out of
            // the cache as well as past it, so nothing is lost by leaving it
            // out.
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
     * The score with the workspace's title and artist on it.
     *
     * <p>The workspace knows them and the score is what the engraver reads, so
     * without this step {@code mw init --title} reached {@code workspace.yaml}
     * and stopped there: every chart the audio path produced was headed
     * "Untitled", with no artist, however carefully the workspace had been
     * labelled. That is #216. The line this replaces wrote the workspace's own
     * metadata back to the workspace, which was a no-op in every field.
     *
     * <p><b>The workspace outranks the transcription, field by field.</b> A
     * title in {@code workspace.yaml} was typed by a person about this
     * recording; the one {@link MidiTranscriber} finds is the first track name
     * in a file, which is a title only by convention -- and where the workspace
     * says nothing, that convention is still better than nothing, so the
     * transcription's value is kept rather than cleared. Per field, because a
     * workspace naming the artist and not the piece must not discard a title the
     * file did carry.
     *
     * <p>Applied here rather than inside a transcriber because the workspace is
     * the CLI's to know, and here rather than in {@code render} because a
     * renderer that re-read the workspace would be the second reader of a fact
     * -- {@code RenderCommand} answers from the score and nothing else, for
     * reasons #120 records. The cost is that a workspace analysed before this
     * change keeps its untitled score until {@code analyze} runs again, which
     * costs nothing beyond a cache hit.
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
     * The lyrics already in this workspace's score, or none.
     *
     * <p>Guarded, and the guard is the point. {@code readScore} raises on a
     * {@code score.json} that is truncated, half-written or not valid UTF-8 —
     * and unguarded here that would abort {@code analyze} after the pipeline had
     * run, before the new score could be written, leaving the corrupt file in
     * place and the workspace unrecoverable through the tool: {@code render}
     * fails the same way, and the one command that would overwrite the bad file
     * is the one that will not run.
     *
     * <p>That is the failure this whole path is shaped to avoid, arriving by the
     * other door: the previous score is a decoration on an analysis that has
     * already succeeded, exactly as a lyric file is, so it gets the same
     * treatment. Warn, carry nothing, and let the good score overwrite the bad
     * one.
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
        System.out.println("  read " + lyrics.lines().size() + " lyric lines from "
                + lyricsFile.getFileName());
        return score.withLyrics(lyrics);
    }

    /**
     * The score with its lyric words placed by the aligner, when one is
     * configured, present, and speaks the lyrics' language.
     *
     * <p>This is what turns {@code SPREAD_WORD} guesses into measured onsets:
     * each line's words are aligned inside a window around the line's own LRC
     * timestamps, which keeps the search small and anchored. Every failure
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
            double previousEnd = 0;
            int kept = 0;
            for (int i = 0; i < parsed.size(); i++) {
                LyricLine line = parsed.get(i);
                // Lines on one moment share a span by the model's own design
                // (#340) -- a second voice, a two-line display. Aligning them
                // would sequence what is sung together, and any spacing rule
                // keyed on the predecessor's end would displace the twin by a
                // whole line and cascade the drift to the end of the file.
                // They keep their shared parsed span, untouched.
                if (sharesAMoment(parsed, i)) {
                    kept++;
                    aligned.add(line);
                    previousEnd = Math.max(previousEnd, line.endSeconds());
                    continue;
                }
                LyricLine result;
                try {
                    // The tail bound is the parser's own rule: a line ends no
                    // later than the next distinct start. An aligned line
                    // honouring it cannot take the next line's chords, which
                    // was round 1's harm.
                    double bound = i + 1 < parsed.size()
                            ? parsed.get(i + 1).startSeconds()
                            : audio.durationSeconds();
                    result = alignedLine(provider.get(), audio, language, line,
                            previousEnd, bound);
                } catch (ModelUnavailableException e) {
                    // The model itself cannot be had: no later line will fare
                    // better, and retrying the fetch once per line would
                    // re-download a checksum-failing model per line. Whole-run
                    // degradation, with the reason.
                    throw e;
                } catch (RuntimeException e) {
                    // One line's failure keeps that line's parsed times without
                    // discarding the lines that aligned.
                    result = line;
                }
                if (result == line) {
                    kept++;
                }
                // Belt and braces at the one assembly point: the sequential
                // window head and the tail bound above make this a no-op on
                // every expected path, and a path nobody expected is exactly
                // when the sheet's cursor must still find monotone lines.
                result = shiftedAfter(result, previousEnd);
                aligned.add(result);
                previousEnd = Math.max(previousEnd, result.endSeconds());
            }
            Confidence overall = aligned.stream()
                    .map(LyricLine::confidence)
                    .min(java.util.Comparator.comparingDouble(Confidence::value))
                    .orElse(lyrics.confidence());
            System.out.println("  aligned " + (aligned.size() - kept)
                    + " lyric lines with " + provider.get().id()
                    + (kept > 0 ? "; " + kept + " kept their parsed times" : ""));
            return score.withLyrics(new Lyrics(aligned, lyrics.language(), overall));
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
            shifted.add(new LyricWord(word.text(),
                    word.startSeconds() + shift, word.endSeconds() + shift,
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
     * can place a last word the singer holds — the reported times still end at
     * the tail bound, by compression. The head is sequential: an aligned line
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
            // window head -- which a word-tagged twin can produce -- would
            // compress the whole line to a point. The parsed guess is better
            // than a point.
            return line;
        }
        float[] window = new float[end - start];
        System.arraycopy(audio.samples(), start, window, 0, window.length);
        List<String> texts = line.words().stream().map(LyricWord::text).toList();
        List<LyricWord> placed = aligner.align(window, audio.sampleRate(),
                language, texts);
        if (placed.size() != line.words().size()) {
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
        // both ends flattened every overrunning word into a zero-length pile
        // on the bound -- neither measured nor the parser's guess, worse than
        // both, on a third of a real file's lines, since a half second of
        // slack routinely lets the last word cross the next line's tag. A
        // linear squeeze into [first word, bound] keeps the order and the
        // proportions the aligner measured; it binds only when the result
        // overruns, and gently when the overrun is small.
        double lineStart = from + placed.get(0).startSeconds();
        double lineEnd = from + placed.get(placed.size() - 1).endSeconds();
        double scale = lineEnd > tailBound && lineEnd > lineStart
                ? (tailBound - lineStart) / (lineEnd - lineStart)
                : 1.0;
        List<LyricWord> out = new ArrayList<>(line.words().size());
        for (int i = 0; i < placed.size(); i++) {
            LyricWord original = line.words().get(i);
            LyricWord timed = placed.get(i);
            // The aligner's clock starts at the window; the line's starts at
            // the recording. Keep the original text and engraving flags -- the
            // aligner only ever decides when.
            double wordStart = lineStart
                    + (from + timed.startSeconds() - lineStart) * scale;
            double wordEnd = lineStart
                    + (from + timed.endSeconds() - lineStart) * scale;
            out.add(new LyricWord(original.text(), wordStart,
                    Math.max(wordStart, wordEnd),
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    original.hyphenatedToNext(), original.melisma(),
                    timed.confidence()));
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
                // Said every time, and deliberately not quietly. A cached result
                // is the previous answer, and on a development build -- which
                // reports no version, so the key cannot change when the code does
                // -- it is the only warning that the pipeline did not run.
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
     * Stores a result for the next run, or says why it could not.
     *
     * <p>Symmetrical with {@link #readCached}, which was guarded from the start
     * for a reason the write side needed just as much: a cache is an
     * optimisation, and a {@code cache/} that is full, read-only, or occupied by
     * something that is not a directory must not cost the user an analysis that
     * has already succeeded. Unguarded, this raised out of {@code analyze} after
     * the pipeline had run -- on the audio path, minutes of DSP thrown away over
     * a failure to write a file nothing was waiting for.
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
     * <p>The audio settings are components only on the audio path. On the MIDI
     * path they change nothing, because nothing reads them, so keying on them
     * would miss the cache for a reason that is not a reason.
     *
     * <p>{@code skipSeparation} and {@code advisorEnabled} are components even
     * though <em>nothing reads either yet</em>, and that is the point:
     * separation lands under #8 and the advisor under #11, and a setting that
     * will change the analysis while the key does not change is how a corrected
     * run gets served the answer it was correcting. This project has already
     * paid for that shape once with {@code --tempo}. Keying on them now costs a
     * recompute that would have produced the same score anyway; keying on them
     * later costs a wrong one.
     *
     * <p>The advisor is keyed on <em>both</em> paths, unlike the audio settings.
     * It is not an audio stage: #11 advises on meter, structure and spelling,
     * every one of which a symbolic import produces too. Round 6 found it keyed
     * nowhere while {@code skipSeparation} was keyed, with nothing in this
     * javadoc saying whether that was a decision -- it was not, and the argument
     * above applies to it word for word.
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
     * reads as plausible as a right one and only the number says which was
     * settled and which was a coin flip. The row is absent when nothing sounded
     * and no key was estimated.
     */
    private static List<String> audioSummary(Score score) {
        List<String> lines = new ArrayList<>();
        lines.add(tempoLine(score));
        lines.add("Meter   " + score.tempoMap().initialTimeSignature());
        score.primaryKey().ifPresent(key -> lines.add(String.format(Locale.ROOT,
                "Key     %s (%.0f%% confidence)", key.displayName(),
                100 * key.confidence().value())));
        lines.add("Chords  " + score.chords().size() + " spans");
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
        // Outside the block, because a part name is not a declaration in the way
        // a tempo is. MidiTranscriber synthesises "Track 3" for an unnamed track,
        // appends " ch 2" where one track carries two channels, and adds " (2)"
        // to disambiguate a repeat -- so some of what is printed here was read
        // and some was constructed, and the heading above must not cover both.
        lines.add("Parts   " + partsLine(score));
        // Outside the declared block, and that placement is now doing more work
        // than when it was written. Until #115 landed a MIDI import produced no
        // chords at all; SymbolicChordEstimator now runs on this path, so a MIDI
        // score has an estimated harmony over declared tempo and meter. Keeping
        // chords out of the block is what stops the one being read as the other
        // -- and it needed no change when the estimator arrived, which is the
        // test of whether the split was drawn in the right place.
        //
        // The empty wording comes from MissingHarmony rather than being written
        // here: this line used to name #115 unconditionally, which was false for
        // a file holding no notes and contradicted what render said about the
        // same score.
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
     * The key the file declares, and from where.
     *
     * <p>Where matters here and does not in the two rows above it, which is the
     * whole reason this one is longer. {@link TempoMap}'s constructor requires
     * its first tempo segment and first meter change to sit at the origin, so
     * {@code segments().get(0)} and {@code initialTimeSignature()} really are
     * "at the start" by construction. Nothing imposes that on
     * {@link Score#keys()}: {@code MidiTranscriber.readKeys} emits exactly the
     * key-signature events the file contains, at whatever tick they carry, so the
     * first of them may be four bars in.
     *
     * <p>Printing it unqualified said the piece opens in a key the file says
     * nothing about, and with a second key present it read "E minor at the start,
     * changed 1 time later" for a file whose first four bars are undeclared --
     * the overstatement round 1 removed from the tempo and meter rows, surviving
     * in the third row of the same block because that row's fixture, like every
     * key fixture in the repo, declared at beat 0.
     *
     * <p>Judged on the beat axis when there is one and on seconds otherwise, and
     * the position is <em>labelled with the axis it was read from</em>. A key
     * imported from MIDI always carries both; one deserialized from a score some
     * other producer wrote need not, and an earlier draft printed that fallback's
     * seconds under the word "beat" -- at 120 BPM, off by a factor of two against
     * the axis it named, in the one unit CLAUDE.md makes load-bearing everywhere
     * downstream of the grid.
     *
     * <p>Changes are counted on {@link Key#displayName()}, which is what the user
     * reads. Counting on the tonic instead compared a {@link PitchSpelling}
     * whose {@code toString} carries an octave -- a field {@code Key} documents
     * as ignored and never prints -- so two spans of one key differing only there
     * were reported as a change, which is the exact defect {@link #countChanges}
     * was introduced to remove.
     *
     * <p>What this row cannot fix is the importer's own line four above it, which
     * still says "opening in E minor" about the same file (#127). Same shape as
     * #118, same reason: it is emitted by {@code mw-transcribe}. A test asserts
     * that contradiction still exists, so fixing #127 trips a reminder.
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
     * How near the origin a position has to be to count as being at it.
     *
     * <p>A key imported from MIDI at tick 0 divides to exactly 0.0, so this is
     * not doing any rounding for that path. It is here for a score read back from
     * somebody else's JSON, where a position of 1e-15 means the origin and
     * saying "from beat 0.000" about it would be true and useless.
     */
    private static final double ORIGIN_TOLERANCE = 1e-9;

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
     * <p>What this fixes is the three rows of the block agreeing with each other.
     * It does <em>not</em> reach the importer's own stage line a few lines above
     * them, which still counts entries and still says "the tempo changes 1
     * time(s) during the piece" about a file whose tempo never changes. That line
     * is emitted by {@code mw-transcribe} and is the same defect at its source;
     * #118 is where it gets fixed, and until then this command's output does
     * contradict itself on screen. Saying so here rather than claiming the
     * contradiction is gone, which an earlier draft of this paragraph did.
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
     * Says which typed options this run will not act on, and why.
     *
     * <p>Said rather than passed over. Ignoring an instruction the user typed is
     * the failure this project keeps finding elsewhere, and it is the same defect
     * #82 was filed for -- announcing something that does not happen -- one
     * command over.
     *
     * <p>Two separate reasons, because they are separate. The tempo, meter and
     * downbeat overrides correct stages a MIDI import does not run, so on that
     * path honouring them would mean overriding what the file declares with a
     * guess. {@code --skip-separation} is different: it does nothing on
     * <em>either</em> path, because nothing separates anything yet (#8). Round 3
     * found it announced as an audio option, quietly ignored by an audio run and
     * reported to a MIDI user in words implying an audio run would honour it.
     *
     * <p>The two are read from different places, and the split is the rule
     * {@code render} arrived at in round 10. The tempo, meter and downbeat
     * overrides are read from the <em>typed fields</em>: they apply on the audio
     * path and not the MIDI one, so a config file carrying them is a preference
     * that happens not to apply to this run, and saying so every time somebody
     * analysed a MIDI file would be noise. {@code --skip-separation} is read
     * from the <em>effective config</em>, because it applies to no run at all --
     * "happens not to apply here" and "cannot apply anywhere" are different
     * claims and only the first is safe to leave unsaid. An earlier draft of
     * this paragraph stated the first rule flatly and governed both, which made
     * a false sentence out of the one option it was wrong about.
     */
    private void warnAboutOptionsThatDoNothing(SourceKind kind, MusicWizardConfig config) {
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
        }
        if (skipSeparationRequested(config)) {
            System.err.println("warning: skipping separation has no effect yet on any input,"
                    + " whether asked for on the command line or in the config;"
                    + " nothing separates stems until #8 lands, so the mix is what every"
                    + " stage already analyses");
        }
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
