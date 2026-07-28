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

package dev.olivelli.musicwizard.transcribe;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.ChordEstimator;
import dev.olivelli.musicwizard.dsp.DownbeatEstimator;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs the analysis stages over a recording and assembles a {@link Score}.
 *
 * <p>The order is fixed by what each stage needs: beats come first because
 * chords are estimated over beat-synchronous chroma, and chroma is taken from
 * the full mix rather than from any separated stem — separation artifacts
 * destroy the partial structure chroma estimation depends on.
 */
public final class AudioTranscriber {

    /**
     * The tempo assumed when nothing could be tracked and nothing was supplied.
     *
     * <p>In counted beats per minute, so that it means the same thing as a typed
     * {@code --tempo 120} in every meter.
     */
    private static final double DEFAULT_PULSES_PER_MINUTE = 120;

    /**
     * How far a requested downbeat may sit from a tracked beat in silence.
     *
     * <p>Beyond this the number the user typed was quietly moved, which they
     * cannot see in the output, and it means the beats themselves are wrong --
     * so phasing them correctly will not rescue the bar lines and the run needs
     * {@code --tempo} as well. Fifty milliseconds is roughly a tenth of a beat
     * at a typical tempo: wide enough that a time read off a waveform and
     * rounded to two decimals passes without comment, narrow enough that landing
     * between two beats does not.
     */
    private static final double DOWNBEAT_SNAP_TOLERANCE_SECONDS = 0.05;

    /**
     * Confidence in a forced phase whose snap moved it as far as it can move.
     *
     * <p>Above {@link DownbeatEstimator}'s floor for a phase nothing supports and
     * above its ceiling for one resting on onsets alone, since a human aiming
     * badly still says more than a guess -- and below what harmony agreeing with
     * the beats can reach, since at half a pulse the phase reported is no longer
     * the one that was asked for.
     */
    private static final double SNAPPED_PHASE_FLOOR = 0.5;

    /**
     * Optional overrides for the stages that most often need correcting.
     *
     * @param tempoOverride       tempo in the meter's <em>counted</em> beats per
     *                            minute -- dotted quarters in 6/8, not quarters.
     *                            The same unit a metronome and this class's own
     *                            progress output use, and the same one
     *                            {@link TempoMap#constantPulse} takes. Reading it
     *                            as quarter notes puts the bar grid 1.5x out in
     *                            compound time. It corrects the rate; the phase
     *                            comes from the tracked pulses either way.
     * @param timeSignature       the meter to assume, 4/4 when null
     * @param firstDownbeatSeconds when a bar starts, in seconds. Chooses the
     *                            phase of the bar grid outright rather than
     *                            contributing to it: the estimator is not run at
     *                            all, because a human who counted the bars is
     *                            better evidence than harmonic novelty and
     *                            averaging the two would let a confident wrong
     *                            estimate outvote them.
     */
    public record Options(
            Double tempoOverride,
            TimeSignature timeSignature,
            Double firstDownbeatSeconds) {

        public Options {
            // Checked here rather than where they are used, so that a mistyped
            // command line is rejected before a minute of decoding rather than
            // after it -- and named as the option the user typed. A negative
            // downbeat otherwise reached nearestBeatIndex, which has no opinion
            // about it and silently answers "the first beat".
            if (firstDownbeatSeconds != null
                    && (!Double.isFinite(firstDownbeatSeconds) || firstDownbeatSeconds < 0)) {
                throw new IllegalArgumentException(
                        "the first downbeat must be a finite, non-negative number of seconds,"
                                + " got: " + firstDownbeatSeconds);
            }
            if (tempoOverride != null && (!Double.isFinite(tempoOverride) || tempoOverride <= 0)) {
                throw new IllegalArgumentException(
                        "the tempo must be finite and positive, got: " + tempoOverride);
            }
        }

        public static Options defaults() {
            return new Options(null, TimeSignature.FOUR_FOUR, null);
        }

        public TimeSignature timeSignatureOrDefault() {
            return timeSignature != null ? timeSignature : TimeSignature.FOUR_FOUR;
        }
    }

    private final Consumer<String> progress;

    public AudioTranscriber(Consumer<String> progress) {
        this.progress = progress != null ? progress : message -> { };
    }

    public AudioTranscriber() {
        this(null);
    }

    /** Decodes and analyses a recording. */
    public Score transcribe(Path file, Options options) {
        Objects.requireNonNull(file, "file");
        Options settings = options != null ? options : Options.defaults();

        progress.accept("decoding " + file.getFileName());
        AudioBuffer audio = AudioDecoder.decode(file);
        if (audio.isEffectivelySilent()) {
            throw new IllegalArgumentException(
                    "the recording is silent, so there is nothing to transcribe: " + file);
        }
        progress.accept(String.format(Locale.ROOT, "decoded %.1fs at %d Hz",
                audio.durationSeconds(), audio.sampleRate()));

        return transcribe(audio, settings);
    }

    /** Analyses already-decoded audio. */
    public Score transcribe(AudioBuffer audio, Options options) {
        Objects.requireNonNull(audio, "audio");
        Options settings = options != null ? options : Options.defaults();
        TimeSignature meter = settings.timeSignatureOrDefault();

        progress.accept("detecting onsets");
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);

        progress.accept("tracking beats");
        BeatTracker.Result beats = BeatTracker.track(envelope);
        if (beats.isEmpty()) {
            progress.accept("no beats found; returning an empty score");
            if (settings.firstDownbeatSeconds() != null) {
                // Said rather than passed over. There is genuinely no pulse to
                // mark as a downbeat, but an override that vanishes without
                // comment is the shape of the bug this branch's sibling had.
                progress.accept("no beats were tracked, so the requested downbeat"
                        + " has nothing to mark; ignoring it");
            }
            // A tempo the user supplied still applies when the tracker found
            // nothing: they are the caller most likely to have typed one, since
            // an unreadable track is exactly what a correction is for, and
            // answering them with a hard-coded 120 discards an instruction.
            //
            // Counted beats a minute, not quarter notes: the default has to mean
            // the same thing as a typed --tempo 120, or the fallback and the
            // override disagree in compound time.
            return Score.empty(
                    TempoMap.constantPulse(pulsesPerMinute(settings), meter),
                    audio.durationSeconds());
        }

        List<Double> beatTimes = beats.beatTimes();
        // Named as beats per minute rather than as a tempo on purpose: the
        // tracker counts pulses, and a pulse is a quarter note only in simple
        // time, so this figure is 1.5x under the quarter-note tempo in 6/8.
        progress.accept(String.format(Locale.ROOT, "found %d beats at %.1f beats/min",
                beatTimes.size(), beats.beatsPerMinute()));

        // A tempo override replaces the tracked tempo but not the tracked beats:
        // the beats are measured evidence, whereas the tempo is a summary of
        // them, and a user correcting the tempo is usually correcting a
        // half-or-double reading rather than claiming the beats are misplaced.
        // So the override supplies the rate and the tracked pulses still supply
        // the phase -- see constantPulseFrom, which is what stops the map and the
        // grid in the same file from disagreeing about where beat one is.
        //
        // The override is read as counted beats per minute, which is what the
        // user is looking at when they type it -- a metronome marking, or the
        // rate this very run just reported. Passing it to TempoMap.constant
        // instead would read it as quarter notes per minute, so in 6/8 the two
        // branches below would describe bars 1.5x apart and correcting the tempo
        // would silently move every bar line.
        //
        // Two tracked pulses are needed to infer a tempo and one is a legitimate
        // result -- a clip of about a fifth of a second yields exactly one, where
        // a tenth yields none. Falling back keeps that clip transcribable: a lone
        // pulse carries no interval, so there is no tempo in it to find, but the
        // pulse itself is still worth keeping and the chords over it are still
        // worth estimating.
        TempoMap tempoMap;
        if (settings.tempoOverride() != null) {
            tempoMap = constantPulseFrom(settings.tempoOverride(), meter, beatTimes.get(0));
        } else if (beatTimes.size() >= 2) {
            tempoMap = TempoMap.fromBeatTimes(beatTimes, meter);
        } else {
            progress.accept("only one beat was tracked, which carries no tempo; assuming "
                    + (int) DEFAULT_PULSES_PER_MINUTE + " beats/min");
            tempoMap = constantPulseFrom(DEFAULT_PULSES_PER_MINUTE, meter, beatTimes.get(0));
        }

        // Chroma before the beat grid, because the downbeat phase is chosen from
        // harmonic change rather than from onset energy. The order stays acyclic:
        // chroma needs the beats, the downbeat phase needs the chroma, and chord
        // estimation needs neither the phase nor the grid.
        progress.accept("extracting chroma");
        Chroma chroma = Chroma.extract(audio).beatSynchronous(beatTimes);

        // Pulses per bar, not the numerator: the tracker emits one pulse per
        // counted beat, and 6/8 counts two of them to a bar rather than six.
        // DownbeatEstimator asks for "the assumed bar length in beats", and the
        // beats it means are the tracked ones it is phasing.
        BeatGrid grid = BeatTracker.toBeatGrid(beats,
                settings.firstDownbeatSeconds() != null
                        ? forcedDownbeat(beatTimes, settings.firstDownbeatSeconds(),
                                meter.beatsPerBar())
                        : DownbeatEstimator.estimate(
                                beatTimes, chroma, envelope, meter.beatsPerBar()));

        progress.accept("estimating chords");
        ChordProgression chords = ChordEstimator.estimate(chroma, beatTimes);
        progress.accept(String.format(Locale.ROOT, "found %d chord spans", chords.size()));

        return Score.empty(tempoMap, audio.durationSeconds())
                .withBeatGrid(grid)
                .withChords(chords);
    }

    /** The tempo to assume when there is nothing to infer one from. */
    private static double pulsesPerMinute(Options settings) {
        return settings.tempoOverride() != null
                ? settings.tempoOverride()
                : DEFAULT_PULSES_PER_MINUTE;
    }

    /**
     * A constant-tempo map whose pulses start where the tracked ones do.
     *
     * <p>{@link TempoMap#constantPulse} anchors at (beat 0, second 0) with no
     * lead-in, so at 120 a minute a grid whose first pulse falls at 0.24 s reads
     * that pulse as 0.48 of a pulse -- half a beat from the whole-numbered
     * position it occupies in the grid stored beside it in the same file, and a
     * bar line half a beat from where the recording puts it. The rate was
     * corrected and the phase was thrown away, which on the highest-value manual
     * override in the tool is a poor trade.
     *
     * <p>Anchored the way {@link TempoMap#fromBeatTimes} anchors, so that the two
     * branches differ only in where the tempo came from: the audio before the
     * first tracked pulse is given a whole number of <em>pulses</em> -- of pulses
     * rather than quarter beats, since a whole number of quarters is not a whole
     * number of dotted quarters -- stretched to land exactly on that pulse. Every
     * tracked pulse then sits a whole number of pulses from the origin, at the
     * user's tempo.
     *
     * <p>Package-private so the degenerate anchors can be driven directly; they
     * are not reachable through a recording, since a beat tracker quantises its
     * output to the analysis hop.
     *
     * @param pulsesPerMinute  the counted tempo to hold throughout
     * @param firstBeatSeconds when the first tracked pulse falls
     */
    static TempoMap constantPulseFrom(
            double pulsesPerMinute, TimeSignature meter, double firstBeatSeconds) {
        // Built first so that a bad tempo is rejected in the units it was typed
        // in, before any of the arithmetic below can turn it into something else.
        TempoMap constant = TempoMap.constantPulse(pulsesPerMinute, meter);
        if (!(firstBeatSeconds > 0)) {
            // The first pulse is already the origin, so there is no lead-in and
            // nothing to anchor: the constant map carries the phase as it is.
            return constant;
        }
        double pulseQuarters = meter.beatUnitQuarters();
        double quarterBpm = constant.initialTempo();
        double pulseSeconds = 60.0 * pulseQuarters / quarterBpm;

        double ratio = firstBeatSeconds / pulseSeconds;
        // Capped and floored exactly as fromBeatTimes caps and floors it: at
        // least one pulse whenever the first beat is after t=0, since a lead-in
        // of zero pulses over a non-zero stretch of audio is not a tempo.
        long rounded = Double.isFinite(ratio) ? Math.round(Math.min(ratio, 1e6)) : 1;
        int leadInPulses = (int) Math.max(1, rounded);

        double leadInTempo = 60.0 * leadInPulses * pulseQuarters / firstBeatSeconds;
        if (!Double.isFinite(leadInTempo) || leadInTempo <= 0) {
            // Only reachable from an absurdly small first beat, where cramming a
            // whole pulse into it overflows. The phase is then unrepresentable,
            // and the rate the user asked for is worth more than a thrown map.
            return constant;
        }
        return new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0.0, leadInTempo),
                        new TempoMap.TempoSegment(
                                leadInPulses * pulseQuarters, firstBeatSeconds, quarterBpm)),
                List.of(new TempoMap.MeterChange(0, meter)));
    }

    /**
     * The bar phase a user asked for, in place of the estimated one.
     *
     * <p>Snapped to the nearest tracked pulse, because the grid can only mark a
     * pulse as a downbeat and a typed time is not going to land on one exactly.
     * That is also the honest reading of the instruction: the user is saying
     * which beat begins the bar, not proposing a beat the tracker missed.
     *
     * <p>Reported as {@link Confidence#CERTAIN} when the request landed on a
     * tracked pulse: a human counted the bars, and nothing this pipeline measures
     * outranks that. It is not a claim about the grid, only about the phase --
     * {@link BeatTracker#toBeatGrid} multiplies it by the confidence in the beats
     * being phased, so a hand-picked downbeat over shaky beats still reads as
     * shaky, which is what it is.
     *
     * <p>It falls away with the distance the snap had to move, because at that
     * point two different things are being reported as one. A request halfway
     * between two pulses names neither of them, and the phase that comes out is
     * this method's guess at which neighbour was meant, not the user's
     * instruction -- yet it would otherwise be recorded as more certain than any
     * phase harmony ever backs. The distance is already measured for the warning
     * above; it costs nothing to let it say something.
     */
    private DownbeatEstimator.Estimate forcedDownbeat(
            List<Double> beatTimes, double downbeatSeconds, int beatsPerBar) {
        int index = nearestBeatIndex(beatTimes, downbeatSeconds);
        double distance = Math.abs(beatTimes.get(index) - downbeatSeconds);
        if (distance > DOWNBEAT_SNAP_TOLERANCE_SECONDS) {
            progress.accept(String.format(Locale.ROOT,
                    "the requested downbeat at %.3fs is %.3fs from the nearest tracked beat"
                            + " at %.3fs; using that beat",
                    downbeatSeconds, distance, beatTimes.get(index)));
        }
        return new DownbeatEstimator.Estimate(
                Math.floorMod(index, beatsPerBar), beatsPerBar,
                snappedPhaseConfidence(beatTimes, distance));
    }

    /**
     * How far to trust a phase the user chose but the grid had to move.
     *
     * <p>Measured against half a pulse, which is the furthest a snap can ever
     * travel while still landing on the nearer of two neighbours: at zero the
     * user named a tracked pulse and the phase is theirs, at half a pulse the two
     * neighbours are equally close and which one comes out is arithmetic rather
     * than instruction. Linear between, since there is no reason to think the
     * confidence falls any particular other way.
     *
     * <p>The floor is deliberately above what an unsupported phase scores in
     * {@link DownbeatEstimator} and above its onsets-only ceiling, and below what
     * harmony that agrees with the beats can reach. A badly-aimed human downbeat
     * still says more than a guess; it should not outrank evidence that actually
     * lines up with the pulses it is phasing.
     *
     * <p>The median interval is the scale, not the interval either side of the
     * chosen pulse: a request can land in a gap where the tracker dropped a beat,
     * and normalising by that gap would call a snap of a whole beat a small one.
     * With fewer than two pulses there is no interval and no alternative pulse to
     * have meant, so the one that exists is the one the user named.
     */
    private static Confidence snappedPhaseConfidence(List<Double> beatTimes, double distance) {
        if (beatTimes.size() < 2) {
            return Confidence.CERTAIN;
        }
        double halfPulse = medianInterval(beatTimes) / 2;
        double missed = halfPulse > 0 ? Math.clamp(distance / halfPulse, 0, 1) : 1;
        return Confidence.clamped(1.0 - (1.0 - SNAPPED_PHASE_FLOOR) * missed);
    }

    /** The median gap between tracked pulses, as a robust idea of pulse length. */
    private static double medianInterval(List<Double> beatTimes) {
        double[] intervals = new double[beatTimes.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beatTimes.get(i + 1) - beatTimes.get(i);
        }
        Arrays.sort(intervals);
        int middle = intervals.length / 2;
        return intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
    }

    /**
     * Index of the tracked pulse nearest a time.
     *
     * <p>{@link BeatGrid#nearestBeatIndex} would do, but the grid does not exist
     * yet: its bar positions are what this is being asked for. A linear scan that
     * stops once the distance grows, over a list that is sorted by construction.
     */
    private static int nearestBeatIndex(List<Double> beatTimes, double seconds) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < beatTimes.size(); i++) {
            double distance = Math.abs(beatTimes.get(i) - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            } else {
                break;
            }
        }
        return best;
    }
}
