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

package dev.olivelli.musicwizard.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.ml.AlignmentProvider;
import dev.olivelli.musicwizard.core.ml.ModelCacheLocation;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricWord;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forced alignment with wav2vec2 CTC posteriors.
 *
 * <p>The model emits per-frame character posteriors; {@link CtcAligner} walks
 * the known text through them. Everything acoustic is the model's problem and
 * everything textual is handled here: uppercase, accents folded to their base
 * letter (the English vocabulary has none), anything else outside the
 * vocabulary dropped. A word left with no expressible characters still comes
 * back — zero-length at its predecessor's end, confidence zero — because the
 * contract is one result per input word, and a silent omission would shift
 * every later word one slot.
 *
 * <p>Confidence is measured, not assigned: each word carries the exponential
 * of the mean over its tokens of each token's own per-frame mean log
 * posterior. Its scale is the aligner's, low on sung audio, and must not be
 * compared with the parser's constants until calibrated (#386).
 */
public final class Wav2Vec2AlignmentProvider implements AlignmentProvider {

    private final ModelCache cache;
    private final Wav2Vec2Models.Checkpoint fixedCheckpoint;
    private final String modelDirectory;

    /**
     * The session, built once and held for the provider's lifetime.
     *
     * <p>analyze calls {@link #align} once per lyric line, and rebuilding a
     * session per line pays the model load per line. The CLI process ends when
     * the command does, which is when the native memory comes back; a
     * long-lived embedder would want an explicit close, and can have one the
     * day such an embedder exists.
     */
    private OrtSession session;
    private Path sessionPath;
    private OrtEnvironment environment;
    private final PosteriorSource posteriors;

    /** The ServiceLoader constructor: configuration from the environment (#383). */
    public Wav2Vec2AlignmentProvider() {
        this(environmentConfig());
    }

    Wav2Vec2AlignmentProvider(ModelCache cache, Wav2Vec2Models.Checkpoint checkpoint,
                              String modelDirectory) {
        this.cache = cache;
        this.fixedCheckpoint = checkpoint;
        this.modelDirectory = modelDirectory;
        this.posteriors = this::logPosteriors;
    }

    private Wav2Vec2AlignmentProvider(MusicWizardConfig config) {
        this(ModelCache.at(
                        ModelCacheLocation.directoryFor(
                                config.ml() == null ? null : config.ml().modelCacheDirectory()),
                        config.isOffline()),
                null,
                config.ml() == null ? null : config.ml().alignmentModelDirectory());
    }

    /** For tests: the text half — tokens, separators, word spans — without a model. */
    Wav2Vec2AlignmentProvider(PosteriorSource posteriors) {
        this(null, Wav2Vec2Models.ENGLISH, null, posteriors);
    }

    /** For tests: a chosen checkpoint's alphabet against supplied posteriors. */
    Wav2Vec2AlignmentProvider(ModelCache cache, Wav2Vec2Models.Checkpoint checkpoint,
                              String modelDirectory, PosteriorSource posteriors) {
        this.cache = cache;
        this.fixedCheckpoint = checkpoint;
        this.modelDirectory = modelDirectory;
        this.posteriors = posteriors;
    }

    /**
     * The checkpoint for a language, or null when this build cannot run it:
     * a fixed one when a test pinned it, otherwise the table's entry.
     */
    private Wav2Vec2Models.Checkpoint checkpointFor(String languageTag) {
        if (fixedCheckpoint != null) {
            return fixedCheckpoint.name().equals(languageTag) ? fixedCheckpoint : null;
        }
        return Wav2Vec2Models.BY_LANGUAGE.get(languageTag);
    }

    /**
     * Where a checkpoint's model.onnx is: the user's directory when they
     * supplied one for this language, else the published export. A language
     * with neither is not offered by {@link #languages()}, so this returning
     * null means only that a caller went around that gate.
     */
    private Path modelPathFor(Wav2Vec2Models.Checkpoint checkpoint) {
        Path supplied = suppliedModel(checkpoint);
        if (supplied != null) {
            return supplied;
        }
        return cache == null || checkpoint.published() == null
                ? null : cache.fetch(checkpoint.published(), System.out::println);
    }

    /**
     * The model a user produced for this language, under
     * {@code <ml.alignmentModelDirectory>/<language>/model.onnx}. One key for
     * every language rather than one per language: a third checkpoint needs
     * a subdirectory, not another setting.
     */
    private Path suppliedModel(Wav2Vec2Models.Checkpoint checkpoint) {
        if (modelDirectory == null || modelDirectory.isBlank()) {
            return null;
        }
        Path candidate = Path.of(modelDirectory, checkpoint.name(), "model.onnx");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /** What turns audio into per-frame log posteriors; the model, ordinarily. */
    interface PosteriorSource {
        float[][] posteriorsOf(Path modelPath, float[] samples);
    }

    private static MusicWizardConfig environmentConfig() {
        return new ConfigLoader().effectiveConfig(null, null);
    }

    @Override
    public String id() {
        return "onnx-wav2vec2";
    }

    /**
     * The languages this machine can actually align: a checkpoint with a
     * published export, plus any the user supplied a model for. Italian has
     * no export to publish (#388), so it appears exactly when
     * {@code ml.alignmentModelDirectory/it/model.onnx} does — a caller that
     * asked for a language absent here is told so and keeps its parsed
     * times, which is what analyze already does.
     */
    @Override
    public List<String> languages() {
        if (fixedCheckpoint != null) {
            return List.of(fixedCheckpoint.name());
        }
        List<String> available = new ArrayList<>();
        for (var entry : new java.util.TreeMap<>(Wav2Vec2Models.BY_LANGUAGE).entrySet()) {
            if (entry.getValue().published() != null
                    || suppliedModel(entry.getValue()) != null) {
                available.add(entry.getKey());
            }
        }
        return List.copyOf(available);
    }

    @Override
    public List<LyricWord> align(float[] samples, int sampleRate, String languageTag,
                                 List<String> words) {
        if (!languages().contains(languageTag)) {
            throw new IllegalArgumentException(
                    "language " + languageTag + " is not one of " + languages());
        }
        if (words.isEmpty()) {
            return List.of();
        }
        Wav2Vec2Models.Checkpoint checkpoint = checkpointFor(languageTag);
        Path modelPath = modelPathFor(checkpoint);

        float[] resampled = Resampler.resample(samples, sampleRate,
                Wav2Vec2Models.MODEL_RATE);
        float[][] logProbs = posteriors.posteriorsOf(modelPath, normalized(resampled));

        // Tokens: each word's characters, a separator between words. tokenSpans
        // remembers which token range belongs to which word so the walk back
        // can read word boundaries off the character spans directly.
        List<int[]> perWord = new ArrayList<>(words.size());
        int total = 0;
        for (String word : words) {
            int[] tokens = tokensOf(word, checkpoint);
            perWord.add(tokens);
            total += tokens.length;
        }
        int separators = countSeparatorsBetweenNonEmpty(perWord);
        int[] sequence = new int[total + separators];
        int[] wordFirstToken = new int[words.size()];
        int[] wordLastToken = new int[words.size()];
        int at = 0;
        boolean anyBefore = false;
        for (int w = 0; w < perWord.size(); w++) {
            int[] tokens = perWord.get(w);
            if (tokens.length == 0) {
                wordFirstToken[w] = -1;
                wordLastToken[w] = -1;
                continue;
            }
            if (anyBefore) {
                sequence[at++] = checkpoint.separator();
            }
            wordFirstToken[w] = at;
            for (int token : tokens) {
                sequence[at++] = token;
            }
            wordLastToken[w] = at - 1;
            anyBefore = true;
        }
        if (at == 0) {
            // Nothing expressible at all: every word zero-length at the start.
            List<LyricWord> out = new ArrayList<>(words.size());
            for (String word : words) {
                out.add(LyricWord.ofSeconds(word, 0, 0, Confidence.of(0)));
            }
            return List.copyOf(out);
        }

        List<CtcAligner.TokenSpan> spans =
                CtcAligner.align(logProbs, checkpoint.blank(), sequence);

        // First and last frame per token index, and its mean log posterior.
        int[] firstFrame = new int[sequence.length];
        int[] lastFrame = new int[sequence.length];
        double[] meanLogProb = new double[sequence.length];
        java.util.Arrays.fill(firstFrame, -1);
        for (CtcAligner.TokenSpan span : spans) {
            if (firstFrame[span.token()] < 0) {
                firstFrame[span.token()] = span.firstFrame();
            }
            lastFrame[span.token()] = span.lastFrame();
            meanLogProb[span.token()] = span.meanLogProb();
        }

        double secondsPerFrame =
                (double) Wav2Vec2Models.HOP_SAMPLES / Wav2Vec2Models.MODEL_RATE;
        List<LyricWord> out = new ArrayList<>(words.size());
        double previousEnd = 0;
        for (int w = 0; w < words.size(); w++) {
            if (wordFirstToken[w] < 0) {
                out.add(LyricWord.ofSeconds(words.get(w), previousEnd, previousEnd,
                        Confidence.of(0)));
                continue;
            }
            int start = firstFrame[wordFirstToken[w]];
            int end = lastFrame[wordLastToken[w]];
            double logProbSum = 0;
            int tokenCount = 0;
            for (int k = wordFirstToken[w]; k <= wordLastToken[w]; k++) {
                logProbSum += meanLogProb[k];
                tokenCount++;
            }
            double confidence = Math.min(1, Math.exp(logProbSum / tokenCount));
            double startSeconds = start * secondsPerFrame;
            double endSeconds = (end + 1) * secondsPerFrame;
            out.add(LyricWord.ofSeconds(words.get(w), startSeconds, endSeconds,
                    Confidence.of(confidence)));
            previousEnd = endSeconds;
        }
        return List.copyOf(out);
    }

    /** Separators sit only between words that contributed tokens. */
    private static int countSeparatorsBetweenNonEmpty(List<int[]> perWord) {
        int nonEmpty = 0;
        for (int[] tokens : perWord) {
            if (tokens.length > 0) {
                nonEmpty++;
            }
        }
        return Math.max(0, nonEmpty - 1);
    }

    /**
     * A word as vocabulary indices, normalised the way the checkpoint's own
     * alphabet asks: cased to match it, and accents kept where it spells
     * them. An accented vowel folded away would delete the nucleus of most
     * Italian syllables, and an unfolded one would be dropped outright by
     * the English alphabet, which spells none — so the vocabulary decides,
     * not the language tag.
     */
    private static int[] tokensOf(String word, Wav2Vec2Models.Checkpoint checkpoint) {
        String text = Normalizer.normalize(word, Normalizer.Form.NFC);
        text = checkpoint.isLowerCase()
                ? text.toLowerCase(java.util.Locale.ROOT)
                : text.toUpperCase(java.util.Locale.ROOT);
        int[] tokens = new int[text.length()];
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = plainApostrophe(text.charAt(i));
            int index = vocabularyIndexOf(c, checkpoint);
            if (index < 0) {
                // The fold is a fallback, per character, not a property of the
                // alphabet: an alphabet that spells è still spells no ô, and
                // dropping that outright deletes the nucleus of the syllable
                // being placed -- which is the harm this branch exists for.
                index = vocabularyIndexOf(folded(c), checkpoint);
            }
            if (index >= 0) {
                tokens[count++] = index;
            }
        }
        return java.util.Arrays.copyOf(tokens, count);
    }

    /** A word as the alphabet spells it: the indices the aligner consumes, as text. */
    static String spelling(String word, Wav2Vec2Models.Checkpoint checkpoint) {
        StringBuilder out = new StringBuilder();
        for (int index : tokensOf(word, checkpoint)) {
            out.append(checkpoint.vocabulary()[index]);
        }
        return out.toString();
    }

    /**
     * A typographic apostrophe as the plain one the alphabets spell. Italian
     * elides constantly -- c'è, un'altra -- and a lyric file typed in a word
     * processor carries U+2019, which no vocabulary has: dropped, the
     * aligner listens for a word that is not being sung.
     */
    private static char plainApostrophe(char c) {
        return c == '\u2018' || c == '\u2019' || c == '\u02bc' || c == '\u00b4'
                ? '\'' : c;
    }

    /** The character without its combining marks, or itself when it has none. */
    private static char folded(char c) {
        String stripped = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.isEmpty() ? c : stripped.charAt(0);
    }

    /**
     * The alphabet's index for a character, or -1. Multi-character entries are
     * the sequence tokens ({@code <pad>}, {@code <s>}…) and the separator is
     * the aligner's own, so both are skipped by what they are rather than by
     * a count of leading specials — the Italian alphabet already spells real,
     * alignable entries at 5 and 6.
     */
    private static int vocabularyIndexOf(char c, Wav2Vec2Models.Checkpoint checkpoint) {
        String[] vocabulary = checkpoint.vocabulary();
        int separator = checkpoint.separator();
        for (int v = 0; v < vocabulary.length; v++) {
            if (v != separator && vocabulary[v].length() == 1
                    && vocabulary[v].charAt(0) == c) {
                return v;
            }
        }
        return -1;
    }

    /** Zero-mean, unit-variance — the checkpoint's own feature extraction. */
    private static float[] normalized(float[] samples) {
        if (samples.length == 0) {
            return samples;
        }
        double sum = 0;
        for (float s : samples) {
            sum += s;
        }
        double mean = sum / samples.length;
        double variance = 0;
        for (float s : samples) {
            variance += (s - mean) * (s - mean);
        }
        double deviation = Math.sqrt(variance / samples.length + 1e-7);
        float[] out = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = (float) ((samples[i] - mean) / deviation);
        }
        return out;
    }

    /**
     * The session for a model, held across calls and rebuilt when the model
     * changes. Keyed on the path, not merely non-null: this provider picks a
     * checkpoint per language now, and a session reused across two of them
     * reads one model's logits with the other's alphabet — which throws in
     * one order and, in the other, quietly returns plausible words spelled
     * against the wrong letters.
     */
    private synchronized OrtSession session(Path modelPath) throws OrtException {
        if (session != null && modelPath.equals(sessionPath)) {
            return session;
        }
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                // A session that will not close is one this process is done
                // with either way; the new model is what the caller needs.
            }
            // Before the create, not after: a create that throws would
            // otherwise leave the field naming the session just closed, and
            // the next request for that path would be handed it. What escapes
            // then is an IllegalStateException from a closed session rather
            // than the OrtException this class converts into
            // ModelUnavailableException, so a whole run degrades line by line
            // with no reason printed.
            session = null;
            sessionPath = null;
        }
        environment = OrtEnvironment.getEnvironment();
        session = environment.createSession(modelPath.toString(),
                new OrtSession.SessionOptions());
        sessionPath = modelPath;
        return session;
    }

    /** Runs the model and log-softmaxes each frame's logits. */
    private float[][] logPosteriors(Path modelPath, float[] samples) {
        try {
            OrtSession held = session(modelPath);
            try (OnnxTensor tensor = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(samples), new long[] {1, samples.length});
                 OrtSession.Result result = held.run(Map.of("input_values", tensor))) {
                float[][][] logits = (float[][][]) result.get(0).getValue();
                float[][] frames = logits[0];
                for (float[] frame : frames) {
                    float max = Float.NEGATIVE_INFINITY;
                    for (float logit : frame) {
                        max = Math.max(max, logit);
                    }
                    double sum = 0;
                    for (float logit : frame) {
                        sum += Math.exp(logit - max);
                    }
                    float logSum = (float) (max + Math.log(sum));
                    for (int v = 0; v < frame.length; v++) {
                        frame[v] -= logSum;
                    }
                }
                return frames;
            }
        } catch (OrtException e) {
            throw new ModelUnavailableException(
                    "ONNX Runtime could not run the alignment model: " + e.getMessage(), e);
        }
    }
}
