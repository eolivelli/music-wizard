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

package dev.olivelli.musicwizard.android.mw;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The whole of the app's contact with the analysis pipeline.
 *
 * <p>Two entry points and nothing else, both named in epic #236:
 * {@code AudioTranscriber.transcribe(AudioBuffer, Options)} in and
 * {@code ChordChart.toText(Score)} out. Everything Android-shaped lives above
 * this class, so what it does can be tested on a JVM.
 *
 * <p>It is also where the app pays attention to sample rates. A take is
 * recorded at 44100 Hz — the rate {@code AudioRecord} is guaranteed to support
 * — but the desktop pipeline analyses at 22050 Hz, and handing the analysis a
 * 44100 Hz buffer would silently halve every window's length in seconds and
 * double the frame rate underneath the beat tracker. So the buffer is
 * resampled here exactly as {@code AudioDecoder.decode(Path)} would resample a
 * file, which is what makes "copy the WAV to the desktop and run the CLI"
 * produce the same chart. Halving 44100 is an exact decimation, so the two
 * paths see the same samples and not merely similar ones.
 */
public final class MwAnalysis {

    /**
     * The rate the analysis runs at.
     *
     * <p>A copy of {@code AudioDecoder.ANALYSIS_SAMPLE_RATE}, which cannot be
     * referenced from app code that must never load that class — it is the one
     * class in the reactor that touches {@code javax.sound}, absent on Android.
     * {@code MwAnalysisTest.analysisRateMatchesTheDesktopPipeline} reads the
     * real field reflectively and fails if the two drift apart. A plain
     * reference would not: {@code static final int} is inlined at compile time,
     * so it would neither load the class nor notice a change.
     */
    public static final int ANALYSIS_SAMPLE_RATE = 22_050;

    /** The rate a take is recorded at. Owner's choice; see epic #236. */
    public static final int RECORD_SAMPLE_RATE = 44_100;

    private MwAnalysis() {
    }

    /**
     * Reads a recording, analyses it, and reports each stage as it starts.
     *
     * <p>From "detecting onsets" onwards the strings are the transcriber's own,
     * the same ones {@code mw analyze} prints, so the phone and the desktop
     * describe a run in the same words. The two lines before that are this
     * method's: the desktop decodes where the phone reads a WAV it wrote, and
     * it reports the file's rate before resampling rather than after.
     */
    public static Score analyze(File wav, Consumer<String> progress) throws IOException {
        Consumer<String> report = progress != null ? progress : line -> { };

        report.accept("reading " + wav.getName());
        AudioBuffer recorded = WavFile.read(wav);
        if (recorded.length() == 0) {
            throw new IOException("the recording holds no audio");
        }
        report.accept(String.format(Locale.ROOT, "read %.1fs at %d Hz",
                recorded.durationSeconds(), recorded.sampleRate()));

        AudioBuffer audio = atAnalysisRate(recorded);
        if (audio.isEffectivelySilent()) {
            throw new IOException("the recording is silent, so there is nothing to transcribe");
        }

        return new AudioTranscriber(report).transcribe(audio, AudioTranscriber.Options.defaults());
    }

    /** The buffer the analysis stages should see, resampled if it is not already there. */
    public static AudioBuffer atAnalysisRate(AudioBuffer audio) {
        if (audio.sampleRate() == ANALYSIS_SAMPLE_RATE) {
            return audio;
        }
        return new AudioBuffer(
                Resampler.resample(audio.samples(), audio.sampleRate(), ANALYSIS_SAMPLE_RATE),
                ANALYSIS_SAMPLE_RATE);
    }

    /** Where a recording's cached analysis lives: beside the WAV, same stem. */
    public static File scoreFileFor(File wav) {
        String name = wav.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return new File(wav.getParentFile(), stem + ".score.json");
    }

    /**
     * What the result screen says when a score could not be cached.
     *
     * <p>Deliberately about the consequence rather than the cause, because
     * there are two: a disk that will not take the file, and the Android
     * limitation in {@link #writeCache}. Neither costs the user the analysis
     * they are looking at; both cost them the next opening of it.
     */
    public static final String CACHE_UNAVAILABLE_NOTE =
            "not cached — this take will be analysed again next time it is opened";

    /**
     * Writes the cache, replacing whatever was there — "re-analyze" overwrites.
     *
     * @return null when it was written, or {@link #CACHE_UNAVAILABLE_NOTE}
     *
     * <p>It can fail, and on most Android versions today it always does, which
     * is why this reports rather than throws. {@code ScoreJson} serializes the
     * model's records, and Jackson recognises a record by asking
     * {@code Class.isRecord()} — added in API 34. Below API 35, D8 rewrites
     * every record to extend its own {@code com.android.tools.r8.RecordTag}
     * instead of {@code java.lang.Record}, so the answer is no on every
     * Android version: the classes have no getters and no bindable
     * constructor, and Jackson finds no properties at all. The reactor cannot
     * be worked around from here — parameter names are not compiled in, so
     * there is nothing for a mix-in or a differently configured mapper to bind
     * to — and the fix belongs in {@code ScoreJson} rather than in a second
     * serializer here that would write a file the desktop could not read.
     *
     * <p>So: the analysis itself is unaffected, the chart is shown, and the
     * only thing lost is that re-opening a take re-runs it. When the reactor
     * side is fixed this starts working with no change here.
     */
    public static String writeCache(File scoreFile, Score score) {
        try {
            ScoreJson.write(scoreFile.toPath(), score);
            return null;
        } catch (RuntimeException | LinkageError e) {
            // LinkageError as well as RuntimeException: what is missing on a
            // phone is a platform class, and a class that is not there arrives
            // as NoClassDefFoundError rather than as an exception.
            return CACHE_UNAVAILABLE_NOTE;
        }
    }

    /** Reads the cache, or null when there is none and when what is there is unreadable. */
    public static Score readCache(File scoreFile) {
        if (!scoreFile.isFile()) {
            return null;
        }
        try {
            return ScoreJson.read(scoreFile.toPath());
        } catch (RuntimeException | LinkageError e) {
            // A half-written cache, one from an older format, or a platform
            // that cannot deserialize the model at all: none of those is worth
            // an error screen, because the audio is still there and
            // re-analyzing rebuilds the result.
            return null;
        }
    }

    /** The chart, exactly as the desktop's text renderer draws it. */
    public static String chartText(Score score) {
        return ChordChart.toText(score);
    }

    /**
     * The one line the result screen leads with: tempo and meter.
     *
     * <p>The tempo comes from {@link Score#estimatedTempo()} — a derived
     * accessor, not a field of {@code score.json}, so it is read off the object
     * rather than looked for in the file. It is stated in quarter notes per
     * minute and says so, because the chart underneath states it in the beat
     * the meter is counted in and the two differ in compound time.
     *
     * <p>It is not folded into the chart text. What the chart shows and what
     * "share" sends is {@link #chartText} unaltered, so that a chart shared off
     * the phone and the {@code chords.txt} that {@code mw render} writes for the
     * same take can be compared line for line. This line is also the only place the tempo
     * appears when no chords were found, which is the case the chart's own
     * header does not cover.
     */
    public static String summary(Score score) {
        return String.format(Locale.ROOT, "%.1f quarter notes/min  ·  %s",
                score.estimatedTempo(), score.tempoMap().initialTimeSignature());
    }
}
