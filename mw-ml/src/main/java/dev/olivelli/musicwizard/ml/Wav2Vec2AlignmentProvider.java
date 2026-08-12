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
 * of its mean per-frame log posterior along the chosen path. Downstream draws
 * the measured-against-guessed line on this number, so a constant here would
 * quietly erase that distinction.
 */
public final class Wav2Vec2AlignmentProvider implements AlignmentProvider {

    private final ModelCache cache;
    private final ModelRef model;

    /** The ServiceLoader constructor: configuration from the environment (#383). */
    public Wav2Vec2AlignmentProvider() {
        this(environmentCache(), Wav2Vec2Models.ENGLISH);
    }

    Wav2Vec2AlignmentProvider(ModelCache cache, ModelRef model) {
        this.cache = cache;
        this.model = model;
    }

    private static ModelCache environmentCache() {
        MusicWizardConfig config = new ConfigLoader().effectiveConfig(null, null);
        MusicWizardConfig.MlConfig ml = config.ml();
        return ModelCache.at(
                ModelCacheLocation.directoryFor(
                        ml == null ? null : ml.modelCacheDirectory()),
                config.isOffline());
    }

    @Override
    public String id() {
        return "onnx-wav2vec2";
    }

    @Override
    public List<String> languages() {
        // English only until a trusted Italian ONNX export exists; the
        // follow-up issue tracks it, and the vocabulary simply grows a table.
        return List.of("en");
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
        Path modelPath = cache.fetch(model, System.out::println);

        float[] resampled = Resampler.resample(samples, sampleRate,
                Wav2Vec2Models.MODEL_RATE);
        float[][] logProbs = logPosteriors(modelPath, normalized(resampled));

        // Tokens: each word's characters, a separator between words. tokenSpans
        // remembers which token range belongs to which word so the walk back
        // can read word boundaries off the character spans directly.
        List<int[]> perWord = new ArrayList<>(words.size());
        int total = 0;
        for (String word : words) {
            int[] tokens = tokensOf(word);
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
                sequence[at++] = Wav2Vec2Models.SEPARATOR;
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
                CtcAligner.align(logProbs, Wav2Vec2Models.BLANK, sequence);

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
     * A word as vocabulary indices: uppercased, accents folded, the rest
     * dropped.
     *
     * <p>Folding first, because the vocabulary is bare ASCII and an accented
     * vowel dropped outright would delete the nucleus of most Italian
     * syllables the day the Italian model lands — the fold is the part of
     * normalisation that is language-independent.
     */
    private static int[] tokensOf(String word) {
        String folded = Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT);
        int[] tokens = new int[folded.length()];
        int count = 0;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            int index = vocabularyIndexOf(c);
            if (index >= 0) {
                tokens[count++] = index;
            }
        }
        return java.util.Arrays.copyOf(tokens, count);
    }

    private static int vocabularyIndexOf(char c) {
        for (int v = 5; v < Wav2Vec2Models.VOCABULARY.length; v++) {
            if (Wav2Vec2Models.VOCABULARY[v].length() == 1
                    && Wav2Vec2Models.VOCABULARY[v].charAt(0) == c) {
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

    /** Runs the model and log-softmaxes each frame's logits. */
    private float[][] logPosteriors(Path modelPath, float[] samples) {
        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession session = environment.createSession(modelPath.toString(),
                     new OrtSession.SessionOptions());
             OnnxTensor tensor = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(samples), new long[] {1, samples.length});
             OrtSession.Result result = session.run(Map.of("input_values", tensor))) {
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
        } catch (OrtException e) {
            throw new ModelUnavailableException(
                    "ONNX Runtime could not run the alignment model: " + e.getMessage(), e);
        }
    }
}
