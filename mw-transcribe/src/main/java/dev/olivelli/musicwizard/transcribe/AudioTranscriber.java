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
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.ChordEstimator;
import dev.olivelli.musicwizard.dsp.DownbeatEstimator;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
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
     * Confidence in a forced phase that fell exactly between two tracked pulses.
     *
     * <p>Counted rather than chosen, the way {@link DownbeatEstimator} counts its
     * own floor. A request equidistant from two pulses is one the grid can still
     * express -- the user meant one of the two -- and which of them comes out is
     * {@link #nearestBeatIndex}'s tie-break, so the phase reported is right half
     * the time. Half is what it is worth.
     *
     * <p>This is the bottom of the ambiguous band only, not of the function. A
     * request further out than this names no tracked pulse at all, and
     * {@link #snappedPhaseConfidence} answers that case separately and lower; the
     * two used to share this constant, which gave a downbeat typed 588 seconds
     * past the end of a recording more confidence than the estimator's own
     * unsupported phase on the same audio.
     *
     * <p>It is deliberately <em>not</em> placed below what a harmony-backed phase
     * scores, which an earlier draft claimed. Since #48 that band runs from 0.35
     * to a 0.6 ceiling, and measured on this project's own four-chord fixtures a
     * unanimous harmonic phase reaches 0.49 to 0.59 -- so there is no value above
     * a blind guess and below typical agreeing harmony left to pick. The two
     * numbers are answering different questions anyway: how often a tie-break is
     * right, and how far harmonic novelty backs a phase. Ordering them was the
     * mistake, not the value.
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
            FallbackTempo fallback = FallbackTempo.forOptions(settings);
            return Score.empty(
                    TempoMap.constantPulse(
                            fallback.pulsesPerMinute(), meter, fallback.provenance()),
                    audio.durationSeconds());
        }

        List<Double> beatTimes = beats.beatTimes();
        // Named as beats per minute rather than as a tempo on purpose: the
        // tracker counts pulses, and a pulse is a quarter note only in simple
        // time, so this figure is 1.5x under the quarter-note tempo in 6/8.
        //
        // The grid's own statistic rather than the tracker's median, and not for
        // tidiness. The comment beside --tempo below tells the user they may type
        // back "the rate this very run just reported", and a supplied tempo beats
        // the grid -- so a figure here that differs from the one the chart is
        // headed with is a documented route back to the defect #200 removes.
        // BeatTracker.Result.beatsPerMinute is the tracker's median interval,
        // which since #200 is not what Score.estimatedTempo answers with.
        //
        // Read off the times rather than off a grid because there is no grid yet:
        // the downbeat phase is chosen from chroma, which is extracted below, and
        // holding this message back until then would delay the one line that says
        // beat tracking worked at all past the slowest stage in the run.
        //
        // The ternary's other arm does NOT hold that property, and saying so is
        // the point of naming it. A lone tracked pulse carries no interval, so
        // there is no rate in it for either accessor to return, and this falls
        // back to the tracker's own figure -- which on that path is the
        // autocorrelation seed rather than any median, and on a clip this short
        // that seed is a function of the clip's length rather than of the music.
        // The mechanism is two lines of TempoEstimator rather than anything
        // observed: maxLag is min(envelope.length() - 1, the lag of MIN_TEMPO),
        // so below the 1.5s that 40 BPM needs, the slowest tempo the estimator
        // is permitted to consider is set by how long the clip is -- and shorter
        // still, maxLag <= minLag and it returns PREFERRED_TEMPO outright. One
        // 0.40s clip here printed 188 beats/min, then said the clip carries no
        // tempo, then headed the chart 120.
        // That predates #200 and is unchanged by it, so it is #240 rather than
        // something to fix under cover of this change; what #200 did was make it
        // the only remaining path on which the two figures can disagree.
        progress.accept(String.format(Locale.ROOT, "found %d beats at %.1f beats/min",
                beatTimes.size(),
                beatTimes.size() >= 2
                        ? BeatGrid.steadyPulseRate(beatTimes)
                        : beats.beatsPerMinute()));

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
        if (beatTimes.size() >= 2 && settings.tempoOverride() == null) {
            tempoMap = TempoMap.fromBeatTimes(beatTimes, meter);
        } else {
            // Everything else is the fallback -- a typed tempo, or a clip with
            // no interval to infer one from -- and both take the figure and its
            // origin from the same place, so a tempo cannot arrive here wearing
            // the other branch's label. ASSUMED rather than MEASURED for the
            // lone pulse: one pulse carries no interval, so nothing here was
            // measured, and labelling it MEASURED would let a later stage treat
            // a hard-coded 120 as evidence.
            //
            // Same four outcomes as the three-way branch this replaces, case by
            // case: a typed tempo wins whether one beat was tracked or many and
            // says nothing on the way past, and only an untyped lone pulse
            // announces the assumption.
            FallbackTempo fallback = FallbackTempo.forOptions(settings);
            if (settings.tempoOverride() == null) {
                progress.accept("only one beat was tracked, which carries no tempo; assuming "
                        + (int) fallback.pulsesPerMinute() + " beats/min");
            }
            tempoMap = constantPulseFrom(fallback.pulsesPerMinute(), meter,
                    beatTimes.get(0), fallback.provenance());
        }

        // Chroma before the beat grid, because the downbeat phase is chosen from
        // harmonic change rather than from onset energy. The order stays acyclic:
        // chroma needs the beats, the downbeat phase needs the chroma, and chord
        // estimation needs neither the phase nor the grid.
        //
        // NNLS rather than the plain fold, because the plain fold does not work
        // on records. Measured over every frame of samples/gmajorblues.mp3 that
        // carries any energy -- 15,305 of 15,321, #185's probe -- plain chroma
        // matched a flat profile better
        // than it matched the best of all twenty-four triads -- 0.882 against
        // 0.691, a margin of -0.191 -- so "no chord" was the maximum-likelihood
        // answer for the whole recording, and that is what came out: a single
        // N.C. span covering all 711 seconds. Through NNLS the same probe gives
        // +0.151 on the treble register and +0.038 on the sum of the two used
        // here; on a second full mix, -0.146 becomes +0.172.
        //
        // Both registers, not the treble alone: the two fail on different chords
        // and adding them takes per-bar root accuracy on that recording from
        // 42.7% to 86.6%. Both of those, and the 77.7% below, were measured on
        // the beat grid as it was before #196; chroma is averaged per tracked
        // beat, so they move with it. ChordEstimator's class javadoc states
        // that and #232 tracks re-measuring them -- the comparisons are not in
        // doubt, the cells just read as current. The margin figures above rank
        // the two the other way
        // round, which is worth knowing rather than smoothing over -- #185's
        // probe asks whether a frame looks like some triad, and the sum looks
        // less like one while naming the right one more often. See
        // NnlsChroma.combined for the per-chord breakdown.
        progress.accept("extracting chroma");
        // combined() before beatSynchronous(), and the order is not cosmetic --
        // see NnlsChroma.combined. Beat-synchronising first normalises each
        // register separately, which makes every beat half treble and half bass
        // whatever they actually contained; on this recording that ordering
        // scores 77.7% where this one scores 86.6%.
        NnlsChroma registers = NnlsChroma.extract(audio);
        Chroma chroma = registers.combined().beatSynchronous(beatTimes);
        // The treble alone, for the quality half of chord recognition only --
        // ChordEstimator.estimate(Chroma, Chroma, List) has the measurement. Not
        // combined(), so this one is beat-synchronised on its own: the question
        // it answers is what share of the *chordal* register each pitch class
        // holds, and normalising the sum would put the bass back into it.
        Chroma treble = registers.treble().beatSynchronous(beatTimes);

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
        ChordProgression chords = ChordEstimator.estimate(chroma, treble, beatTimes);
        progress.accept(String.format(Locale.ROOT, "found %d chord spans", chords.size()));

        return Score.empty(tempoMap, audio.durationSeconds())
                .withBeatGrid(grid)
                .withChords(chords);
    }

    /**
     * The tempo to use when there is nothing to infer one from, together with
     * where it came from.
     *
     * <p>One value carrying both, rather than two methods testing the same
     * condition a dozen lines apart. Two methods is how a figure and its label
     * drift: the day this grows a third source -- a configured default, an
     * advised tempo -- whichever one was not updated keeps answering for the two
     * it knows, and a number acquires an origin that is not its own. That is the
     * two-readers failure this project keeps paying for, in miniature, and the
     * fix is to leave one reader rather than to remember to update both.
     */
    private record FallbackTempo(double pulsesPerMinute, Provenance provenance) {

        static FallbackTempo forOptions(Options settings) {
            return settings.tempoOverride() != null
                    ? new FallbackTempo(settings.tempoOverride(), Provenance.SUPPLIED)
                    : new FallbackTempo(DEFAULT_PULSES_PER_MINUTE, Provenance.ASSUMED);
        }
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
     * <p>An anchor at the origin is not degenerate and is ordinary: hop
     * quantisation is what produces it, since any recording whose first beat
     * falls in frame 0 reports it at exactly 0.0. There is no lead-in to model
     * then, and the map stays the single constant segment it always was.
     *
     * <p>Package-private so the anchors that really are unreachable through a
     * recording -- a negative one, or one small enough that a whole pulse
     * crammed into it overflows -- can be driven directly.
     *
     * <p>The lead-in is {@link Provenance#DERIVED} rather than carrying
     * {@code provenance}, exactly as {@link TempoMap#fromBeatTimes} labels its
     * own: its rate is whatever it took to reach the first tracked pulse, so
     * reporting it as the tempo somebody supplied would name a figure nobody
     * asked for. That labelling is what {@link Score#estimatedTempo()} reads,
     * in place of identifying the lead-in by its position.
     *
     * @param pulsesPerMinute  the counted tempo to hold throughout
     * @param firstBeatSeconds when the first tracked pulse falls
     * @param provenance       where {@code pulsesPerMinute} came from
     */
    static TempoMap constantPulseFrom(double pulsesPerMinute, TimeSignature meter,
                                      double firstBeatSeconds, Provenance provenance) {
        // Built first so that a bad tempo is rejected in the units it was typed
        // in, before any of the arithmetic below can turn it into something else.
        TempoMap constant = TempoMap.constantPulse(pulsesPerMinute, meter, provenance);
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
                List.of(new TempoMap.TempoSegment(0, 0.0, leadInTempo, Provenance.DERIVED),
                        new TempoMap.TempoSegment(leadInPulses * pulseQuarters,
                                firstBeatSeconds, quarterBpm, provenance)),
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
     * phase harmony ever backs.
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
                beatsPerBar == 1
                        // Every beat begins a bar, so phase 0 is the only phase
                        // there is and no snap can have chosen the wrong one.
                        // DownbeatEstimator answers this meter with CERTAIN
                        // before gathering any evidence, and a forced phase that
                        // reported less would put a claim that cannot be wrong
                        // below one that can -- which is the one thing these
                        // numbers are supposed to guarantee.
                        ? Confidence.CERTAIN
                        : snappedPhaseConfidence(
                                beatTimes, index, downbeatSeconds, beatsPerBar));
    }

    /**
     * How far to trust a phase the user chose but the grid had to move.
     *
     * <p>Measured against half the distance to the pulse the request would have
     * snapped to instead: at zero the user named a tracked pulse and the phase is
     * theirs, at the midpoint between two pulses the two are equally close and
     * which one comes out is {@link #nearestBeatIndex}'s tie-break rather than an
     * instruction. Linear between, since there is no reason to think the
     * confidence falls any particular other way.
     *
     * <p>The scale is the <em>smaller</em> of that rival gap and the median
     * interval, and it needs both. Taking the local gap alone over-reports where
     * the tracker dropped a beat: the rival pulse is then two beats away, so a
     * snap of a whole beat looks like a third of the way to ambiguity, when in
     * truth the user was probably pointing at a beat that is not in the grid at
     * all and the phase is off by one. Taking the median alone over-reports the
     * mirror case, a passage that speeds up: where the local gap is 70% of the
     * median, a request exactly equidistant from two pulses -- a coin flip --
     * scored 0.65. Neither scale bounds the ambiguity on its own; the minimum
     * bounds it in both directions.
     *
     * <p>That falloff covers every request that lands <em>between</em> two
     * tracked pulses, which is every request inside the tracked range. Two
     * candidates, the nearer one taken, and confidence falling to a half as they
     * become equally good. It does not distinguish a user who meant one of those
     * two pulses from one who meant a beat the tracker dropped between them.
     * A rival gap at about twice the median is that signature, and it is
     * deliberately spent on the scale rather than on the answer: the same
     * evidence cannot both bound the ambiguity and be read as a separate claim
     * about a missing pulse, and bounding it is what stops the over-reporting
     * round two found. The warning above is what tells the user.
     *
     * <p>Outside the range there is no second candidate. A request before the
     * first pulse or after the last is not between anything, and past half a
     * typical pulse it is not naming the end pulse either: the phase that comes
     * back is what clamping produced, and that is worth one guess in
     * {@code beatsPerBar}. It is the same count {@link DownbeatEstimator} builds
     * its own floor from, without the margin it adds for having at least looked
     * at the audio -- this looked at nothing. Letting that case share the
     * tie-break floor put a downbeat typed 588 seconds past the end of a
     * recording above the estimator's unsupported phase on the same audio.
     *
     * <p>Keyed on being outside the range rather than on the distance alone,
     * which matters more than it sounds: a request at the exact midpoint of an
     * ordinary gap sits within a hair of half the median either way, so a
     * distance test would hand the commonest case in the method to whichever side
     * of the median that gap's jitter fell.
     *
     * <p>Nearer than half a pulse the plain falloff still applies outside the
     * range too, since a request ten milliseconds before the first pulse is
     * unmistakably naming it. The step at that boundary is real, and is the
     * honest shape: it is where "just before the first beat" stops meaning the
     * first beat.
     *
     * <p>The two counts meet at two beats to the bar, where being lost between
     * two candidates and being lost among every phase there is are the same
     * predicament and both give a half. At one beat to the bar the two branches
     * part company instead: the out-of-range count reaches certainty, correctly,
     * while the falloff still bottoms out at a half against an alternative phase
     * that does not exist -- the neighbouring pulse is real enough, it just
     * begins the same bar. So it is the caller that supplies the certainty for that meter,
     * rather than this method agreeing with itself, and that meter never reaches
     * here.
     *
     * <p>What this is <em>not</em> ordered against, though an earlier draft said
     * it was, is {@link DownbeatEstimator}'s floor. That floor is 0.35 at every
     * meter by a deliberate choice its own file records -- the count behind it is
     * one phase in four, stated for the common meter and applied flat, on the
     * grounds that neither number is calibrated well enough to make it depend on
     * the meter. So the intended ordering holds only where the flat figure sits
     * above the honest count, which is from three counted beats to the bar
     * upward. At exactly two it inverts: a request outside the tracked range
     * reports 0.5 against the estimator's 0.35, in 6/8 and in 2/4 alike. At one
     * the question does not arise, since neither side is guessing -- the
     * estimator answers that meter with certainty before gathering evidence and
     * the caller short-circuits to the same, so the two agree exactly.
     *
     * <p>The count here is the accurate one of the pair -- a blind phase in a
     * two-beat bar really is right half the time, and the flat figure understates
     * that meter by the estimator's own account -- so the inversion is not
     * repaired by capping this number against one that is less defensible. A
     * caller comparing the two across meters is comparing things that were not
     * built to be compared. Making them commensurate is #88, and
     * {@code theBlindPhaseOnlyOutranksNothingInWiderBars} fails when it is
     * done.
     *
     * <p>With fewer than two pulses there is no interval and no alternative pulse
     * to have meant, so the one that exists is the one the user named.
     *
     * <p>Package-private so the grids that make the two scales disagree can be
     * driven directly rather than approximated with a fixture.
     */
    static Confidence snappedPhaseConfidence(
            List<Double> beatTimes, int index, double downbeatSeconds, int beatsPerBar) {
        if (beatTimes.size() < 2) {
            return Confidence.CERTAIN;
        }
        double chosen = beatTimes.get(index);
        // The pulse the snap would have picked had the request fallen a little
        // further the same way -- which is the one it is being confused with.
        // Absent exactly when the request is outside the tracked range, since
        // nothing outside it lies between two pulses.
        int rival = downbeatSeconds >= chosen ? index + 1 : index - 1;
        double rivalGap = rival >= 0 && rival < beatTimes.size()
                ? Math.abs(beatTimes.get(rival) - chosen)
                : Double.POSITIVE_INFINITY;
        double halfPulse = Math.min(medianInterval(beatTimes), rivalGap) / 2;
        double missed = halfPulse > 0
                ? Math.abs(chosen - downbeatSeconds) / halfPulse
                // Also the NaN path: a grid whose median is zero cannot say how
                // far out anything is.
                : Double.POSITIVE_INFINITY;
        if (Double.isInfinite(rivalGap) && !(missed <= 1)) {
            return Confidence.clamped(1.0 / beatsPerBar);
        }
        return Confidence.clamped(
                1.0 - (1.0 - SNAPPED_PHASE_FLOOR) * Math.clamp(missed, 0, 1));
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
