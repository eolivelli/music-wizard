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

package dev.olivelli.musicwizard.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Optional;

/**
 * Preferences controlling a transcription.
 *
 * <p>Every field is nullable so that an instance can express "not specified
 * here", which is what makes layering work: a global user config, a
 * per-workspace config and command-line flags are each parsed into one of these
 * and then merged, with the more specific layer winning. Defaults are applied
 * only at the end, by {@link #resolved()}.
 *
 * <p>Unknown properties are ignored on read so that a config written by a newer
 * version does not make an older one refuse to start.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicWizardConfig(
        Integer schemaVersion,
        AnalysisConfig analysis,
        NotationConfig notation,
        ArrangementConfig arrangement,
        MlConfig ml,
        LlmConfig llm) {

    /** Current config schema version. Bump when a breaking change lands. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** How the audio should be analysed, including manual overrides. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalysisConfig(
            /* Force a tempo instead of tracking it, in the meter's counted beats
             * per minute -- dotted quarters in 6/8, not quarters. The single
             * highest-value override: one correct number fixes everything
             * downstream. */
            Double tempoOverride,
            /* Force a time signature, written as e.g. "4/4". */
            String timeSignatureOverride,
            /* Force where a bar begins, in seconds. Snapped to the nearest
             * tracked beat, which then begins a bar in the saved beat grid and
             * the first bar line of the chart drawn from it (#83). The tempo
             * map's own bar numbering is deliberately left alone, so that this
             * and a tempo override cannot interact. Later chart bar lines are
             * spaced at the printed tempo rather than off the grid (#187). */
            Double firstDownbeatSecondsOverride,
            /* Skip stem separation and analyse the mix directly. */
            Boolean skipSeparation) {
    }

    /** How scores should be engraved. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotationConfig(
            /* Explicit path to the LilyPond binary; discovered if absent. */
            String lilypondPath,
            /* Paper size passed to LilyPond, e.g. "a4" or "letter". */
            String paperSize,
            /* Semitones to transpose every part by. */
            Integer transposeSemitones,
            /* Guitar capo position, which affects printed chord symbols only. */
            Integer capo,
            /* Whether to prefer sharps, flats, or let the key decide. */
            AccidentalPreference accidentalPreference) {
    }

    /** How the piano reduction should be generated. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArrangementConfig(
            /* 0 = easiest, 1 = most faithful. A continuous knob rather than a
             * set of named presets, since playability is a spectrum. */
            Double pianoDifficulty,
            /* Maximum simultaneous notes per hand. */
            Integer maxNotesPerHand,
            /* Maximum span per hand, in semitones. */
            Integer maxHandSpanSemitones) {
    }

    /** Which providers run the neural stages. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MlConfig(
            /* Provider id for stem separation, e.g. "onnx-spleeter". */
            String separationProvider,
            /* Provider id for pitch tracking, e.g. "onnx-crepe". */
            String pitchProvider,
            /* Provider id for lyric transcription, e.g. "sherpa-qwen3". */
            String asrProvider,
            /* Provider id for forced alignment of known lyrics, e.g. "onnx-wav2vec2". */
            String alignmentProvider,
            /* Directory holding downloaded models; defaults under the user cache. */
            String modelCacheDirectory,
            /* Directory holding the sherpa-onnx native libraries; unset means
             * the JVM's own library path. tools/build-sherpa-native.sh writes
             * the directory this names. */
            String sherpaNativePath,
            /* Directory holding an ASR model the user produced or chose
             * themselves, in the provider's own layout; unset means the
             * provider fetches its published archive. What made it exist:
             * only the 0.6B Qwen3-ASR export is published, and running the
             * 1.7B means exporting it locally (#396). */
            String asrModelDirectory,
            /* Refuse to download anything, failing instead. */
            Boolean offline) {
    }

    /** Whether and how the Claude advisor layer runs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmConfig(
            Boolean enabled,
            String model,
            /* One of low, medium, high, xhigh, max. */
            String effort,
            /* Individually toggleable advisors. */
            Boolean repairLyrics,
            Boolean labelSections,
            Boolean chooseSpelling,
            Boolean adviseArrangement,
            Boolean repairHarmony) {
    }

    /** Whether to prefer sharps or flats when spelling pitches. */
    public enum AccidentalPreference {
        /** Let the detected key decide, which is almost always right. */
        FROM_KEY,
        SHARPS,
        FLATS
    }

    /**
     * Merges another layer on top of this one. Non-null values in {@code other}
     * win; null values leave this layer's value in place.
     */
    public MusicWizardConfig overriddenBy(MusicWizardConfig other) {
        if (other == null) {
            return this;
        }
        return new MusicWizardConfig(
                pick(other.schemaVersion, schemaVersion),
                mergeAnalysis(analysis, other.analysis),
                mergeNotation(notation, other.notation),
                mergeArrangement(arrangement, other.arrangement),
                mergeMl(ml, other.ml),
                mergeLlm(llm, other.llm));
    }

    /** Fills in every unset value with its default. */
    public MusicWizardConfig resolved() {
        return DEFAULTS.overriddenBy(this);
    }

    /** The default configuration, used as the base of every merge. */
    public static final MusicWizardConfig DEFAULTS = new MusicWizardConfig(
            CURRENT_SCHEMA_VERSION,
            new AnalysisConfig(null, null, null, false),
            new NotationConfig(null, "a4", 0, 0, AccidentalPreference.FROM_KEY),
            new ArrangementConfig(0.5, 4, 9),
            new MlConfig("onnx-spleeter", "onnx-crepe", "sherpa-qwen3",
                    "onnx-wav2vec2", null, null, null, false),
            new LlmConfig(false, "claude-opus-5", "high", true, true, true, true, true));

    /** An entirely unset layer, which merges as a no-op. */
    public static MusicWizardConfig empty() {
        return new MusicWizardConfig(null, null, null, null, null, null);
    }

    // Convenience accessors, safe to call only on a resolved config.
    //
    // These are derived, not stored. They must be hidden from Jackson: it treats
    // isXxx()/xxx() as bean getters and would otherwise write phantom keys into
    // every persisted config file.

    @JsonIgnore
    public Optional<String> lilypondPath() {
        return Optional.ofNullable(notation).map(NotationConfig::lilypondPath);
    }

    @JsonIgnore
    public boolean isLlmEnabled() {
        return llm != null && Boolean.TRUE.equals(llm.enabled());
    }

    @JsonIgnore
    public boolean isOffline() {
        return ml != null && Boolean.TRUE.equals(ml.offline());
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static AnalysisConfig mergeAnalysis(AnalysisConfig base, AnalysisConfig over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new AnalysisConfig(
                pick(over.tempoOverride(), base.tempoOverride()),
                pick(over.timeSignatureOverride(), base.timeSignatureOverride()),
                pick(over.firstDownbeatSecondsOverride(), base.firstDownbeatSecondsOverride()),
                pick(over.skipSeparation(), base.skipSeparation()));
    }

    private static NotationConfig mergeNotation(NotationConfig base, NotationConfig over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new NotationConfig(
                pick(over.lilypondPath(), base.lilypondPath()),
                pick(over.paperSize(), base.paperSize()),
                pick(over.transposeSemitones(), base.transposeSemitones()),
                pick(over.capo(), base.capo()),
                pick(over.accidentalPreference(), base.accidentalPreference()));
    }

    private static ArrangementConfig mergeArrangement(ArrangementConfig base, ArrangementConfig over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new ArrangementConfig(
                pick(over.pianoDifficulty(), base.pianoDifficulty()),
                pick(over.maxNotesPerHand(), base.maxNotesPerHand()),
                pick(over.maxHandSpanSemitones(), base.maxHandSpanSemitones()));
    }

    private static MlConfig mergeMl(MlConfig base, MlConfig over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new MlConfig(
                pick(over.separationProvider(), base.separationProvider()),
                pick(over.pitchProvider(), base.pitchProvider()),
                pick(over.asrProvider(), base.asrProvider()),
                pick(over.alignmentProvider(), base.alignmentProvider()),
                pick(over.modelCacheDirectory(), base.modelCacheDirectory()),
                pick(over.sherpaNativePath(), base.sherpaNativePath()),
                pick(over.asrModelDirectory(), base.asrModelDirectory()),
                pick(over.offline(), base.offline()));
    }

    private static LlmConfig mergeLlm(LlmConfig base, LlmConfig over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new LlmConfig(
                pick(over.enabled(), base.enabled()),
                pick(over.model(), base.model()),
                pick(over.effort(), base.effort()),
                pick(over.repairLyrics(), base.repairLyrics()),
                pick(over.labelSections(), base.labelSections()),
                pick(over.chooseSpelling(), base.chooseSpelling()),
                pick(over.adviseArrangement(), base.adviseArrangement()),
                pick(over.repairHarmony(), base.repairHarmony()));
    }
}
