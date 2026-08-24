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

package dev.olivelli.musicwizard.arrange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the reduction says it did, against what it did (#680).
 *
 * <p>The records are read back onto the estimate and the part: a head that says
 * it took its pitch from a note has to have taken it from that note, and the
 * notes the heads cover have to be the estimate, each exactly once. That is the
 * property a record of reasoning can fail while the reasoning stays right.
 *
 * <p>Fixtures run at one beat a second, so a duration in seconds and the same
 * figure in beats are the same number.
 */
class ReductionTraceTest {

    @Nested
    @DisplayName("over generated scores")
    class Differentially {

        @Test
        @DisplayName("the explained part is the reduction")
        void theExplainedPartIsTheReduction() {
            forEachScore(score -> {
                NoteTrack reduced = PlayableMelody.reduce(score);
                NoteTrack explained = PlayableMelody.explain(score).part();
                assertThat(explained.size()).isEqualTo(reduced.size());
                for (int i = 0; i < reduced.size(); i++) {
                    assertThat(explained.notes().get(i)).isEqualTo(reduced.notes().get(i));
                }
            });
        }

        @Test
        @DisplayName("the heads cover every note of the estimate, each exactly once")
        void theHeadsCoverTheEstimate() {
            forEachScore(score -> {
                ReductionTrace trace = PlayableMelody.explain(score);
                NoteTrack estimate = score.track(PartRole.LEAD_VOCAL).orElseThrow();
                assertThat(trace.notes()).hasSize(estimate.size());
                int next = 0;
                for (int i = 0; i < trace.heads().size(); i++) {
                    ReductionTrace.Head head = trace.heads().get(i);
                    assertThat(head.fromNote()).isEqualTo(next);
                    assertThat(head.notes()).isPositive();
                    for (int n = next; n < next + head.notes(); n++) {
                        assertThat(trace.notes().get(n).head()).isEqualTo(i);
                    }
                    next += head.notes();
                }
                assertThat(next).isEqualTo(estimate.size());
            });
        }

        @Test
        @DisplayName("a head prints the pitch, onset and release it names")
        void aHeadPrintsWhatItNames() {
            forEachScore(score -> {
                ReductionTrace trace = PlayableMelody.explain(score);
                List<Note> estimate =
                        score.track(PartRole.LEAD_VOCAL).orElseThrow().notes();
                for (int i = 0; i < trace.heads().size(); i++) {
                    ReductionTrace.Head head = trace.heads().get(i);
                    Note printed = trace.part().notes().get(i);
                    assertThat(head.midiPitch()).isEqualTo(printed.midiPitch());
                    assertThat(head.fromSeconds()).isEqualTo(printed.onsetSeconds());
                    assertThat(head.toSeconds()).isEqualTo(printed.offsetSeconds());

                    ReductionTrace.Return returned = head.returned();
                    assertThat(returned.fromMidi())
                            .isEqualTo(estimate.get(head.pitchNote()).midiPitch());
                    assertThat(printed.midiPitch()).isEqualTo(
                            ReductionTrace.Return.RETURNED.equals(returned.read())
                                    ? returned.homeMidi() : returned.fromMidi());
                    assertThat(printed.offsetSeconds())
                            .isCloseTo(estimate.get(head.releaseNote()).offsetSeconds(),
                                    within());
                    assertThat(printed.onsetSeconds()).isCloseTo(
                            ReductionTrace.Onset.SYLLABLE.equals(head.onset().read())
                                    ? head.onset().syllableSeconds()
                                    : estimate.get(head.fromNote()).onsetSeconds(), within());
                }
            });
        }

        @Test
        @DisplayName("a note is chosen exactly where its head takes its pitch from it")
        void oneNoteOfEachHeadIsChosen() {
            forEachScore(score -> {
                ReductionTrace trace = PlayableMelody.explain(score);
                for (int i = 0; i < trace.notes().size(); i++) {
                    ReductionTrace.Source source = trace.notes().get(i);
                    boolean chosen = trace.heads().get(source.head()).pitchNote() == i;
                    assertThat(source.read()).isEqualTo(chosen
                            ? ReductionTrace.Source.CHOSEN
                            : ReductionTrace.Source.ABSORBED);
                }
            });
        }

        @Test
        @DisplayName("the heads counted as moved are the ones the excursion rule changes")
        void theMovedCountIsTheExcursionRulesOwn() {
            forEachScore(score -> {
                ReductionTrace trace = PlayableMelody.explain(score);
                // What tools/PlayablePartCheck.java sweeps: the same reduction
                // with the rule refusing nothing, compared head by head.
                NoteTrack asRead = PlayableMelody.reduce(score, PlayableMelody.CLAIM_BEATS,
                        PlayableMelody.ORNAMENT_BEATS, PlayableMelody.Excursion.NONE);
                int moved = 0;
                for (int i = 0; i < asRead.size(); i++) {
                    if (asRead.notes().get(i).midiPitch()
                            != trace.part().notes().get(i).midiPitch()) {
                        moved++;
                    }
                }
                assertThat(trace.counts().moved()).isEqualTo(moved);
            });
        }

        @Test
        @DisplayName("a note is grouped alone exactly when its head holds nothing else")
        void beingAloneIsBeingTheOnlyNoteOfAHead() {
            forEachScore(score -> {
                ReductionTrace trace = PlayableMelody.explain(score);
                for (ReductionTrace.Source source : trace.notes()) {
                    assertThat(ReductionTrace.Source.ALONE.equals(source.groupedBy()))
                            .isEqualTo(trace.heads().get(source.head()).notes() == 1);
                }
            });
        }
    }

    @Nested
    @DisplayName("naming the rule that took each note")
    class TheRules {

        @Test
        @DisplayName("a syllable's own head absorbs the notes it claimed")
        void aSyllableAbsorbsWhatItClaimed() {
            Score score = sung(notes(note(0.0, 0.4, 60), note(0.4, 0.6, 62)),
                    line(word("ah", 0.0, 1.0)));

            ReductionTrace trace = PlayableMelody.explain(score);

            assertThat(trace.heads()).hasSize(1);
            assertThat(trace.notes()).extracting(ReductionTrace.Source::groupedBy)
                    .containsExactly(ReductionTrace.Source.SYLLABLE,
                            ReductionTrace.Source.SYLLABLE);
            assertThat(trace.notes()).extracting(ReductionTrace.Source::read)
                    .containsExactly(ReductionTrace.Source.ABSORBED,
                            ReductionTrace.Source.CHOSEN);
            assertThat(trace.heads().get(0).line()).isZero();
            assertThat(trace.heads().get(0).melisma()).isFalse();
            assertThat(trace.counts().collapsed()).isEqualTo(1);
            assertThat(trace.counts().ornaments()).isZero();
        }

        @Test
        @DisplayName("the ornament rule takes a note where there are no words")
        void theOrnamentRuleTakesANoteWithNoWords() {
            Score score = played(notes(note(0.0, 0.2, 60), note(0.2, 1.0, 62)));

            ReductionTrace trace = PlayableMelody.explain(score);

            assertThat(trace.notes()).extracting(ReductionTrace.Source::groupedBy)
                    .containsExactly(ReductionTrace.Source.ORNAMENT,
                            ReductionTrace.Source.ORNAMENT);
            assertThat(trace.counts().ornaments()).isEqualTo(1);
            assertThat(trace.counts().collapsed()).isZero();
            assertThat(trace.counts().unclaimed()).isEqualTo(2);
        }

        @Test
        @DisplayName("a note past the claim bound says how much silence refused it")
        void theClaimBoundIsReadOffTheSilence() {
            Score score = sung(notes(note(0.0, 1.0, 60), note(20.0, 1.0, 62)),
                    line(word("ah", 0.0, 1.0), word("oh", 25.0, 26.0)));

            ReductionTrace trace = PlayableMelody.explain(score);

            ReductionTrace.Source far = trace.notes().get(1);
            assertThat(far.claimed()).isFalse();
            assertThat(far.silenceBeats()).isEqualTo(4.0);
            assertThat(far.claimBeats()).isEqualTo(PlayableMelody.CLAIM_BEATS);
            assertThat(trace.notes().get(0).claimed()).isTrue();
        }

        @Test
        @DisplayName("a note no line reaches over is not measured against the bound")
        void aNoteNoLineCoversIsSaidToBeUncovered() {
            Score score = sung(notes(note(10.0, 1.0, 60)), line(word("ah", 0.0, 1.0)));

            ReductionTrace trace = PlayableMelody.explain(score);

            assertThat(trace.notes().get(0).silenceBeats()).isNull();
            assertThat(trace.notes().get(0).claimed()).isFalse();
        }

        @Test
        @DisplayName("a melisma's run is printed rather than collapsed, and says so")
        void aMelismaIsMarkedOnEveryHeadItPrints() {
            Score score = sung(notes(note(0.0, 0.5, 60), note(0.5, 0.5, 62)),
                    line(word("ah", 0.0, 1.0).withMelisma(true)));

            ReductionTrace trace = PlayableMelody.explain(score);

            assertThat(trace.heads()).hasSize(2);
            assertThat(trace.heads()).allMatch(ReductionTrace.Head::melisma);
            assertThat(trace.counts().syllables()).isEqualTo(1);
            assertThat(trace.counts().melismas()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("where a head's onset came from")
    class TheOnset {

        @Test
        @DisplayName("a syllable start inside the group is taken, and named")
        void theAlignersStartIsTaken() {
            Score score = sung(notes(note(0.0, 1.0, 60), note(1.0, 1.0, 62)),
                    line(word("ah", 0.5, 2.0)));

            ReductionTrace.Head head = PlayableMelody.explain(score).heads().get(0);

            assertThat(head.onset().read()).isEqualTo(ReductionTrace.Onset.SYLLABLE);
            assertThat(head.onset().syllableSeconds()).isEqualTo(0.5);
            assertThat(head.onset().melodySeconds()).isZero();
            assertThat(head.fromSeconds()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("a start that would leave a stub is refused, and says what it left")
        void aStubIsRefused() {
            Score score = sung(notes(note(0.0, 1.0, 60)), line(word("ah", 0.8, 2.0)));

            ReductionTrace.Head head = PlayableMelody.explain(score).heads().get(0);

            assertThat(head.onset().read()).isEqualTo(ReductionTrace.Onset.STUB);
            assertThat(head.onset().leftBeats()).isCloseTo(0.2, within());
            assertThat(head.onset().requiredBeats())
                    .isEqualTo(PlayableMelody.ORNAMENT_BEATS);
            assertThat(head.fromSeconds()).isZero();
        }

        @Test
        @DisplayName("a start no earlier than the group's is refused as having nothing to move")
        void anEarlyStartIsRefused() {
            Score score = sung(notes(note(1.0, 2.0, 60)), line(word("ah", 0.5, 3.0)));

            ReductionTrace.Head head = PlayableMelody.explain(score).heads().get(0);

            assertThat(head.onset().read()).isEqualTo(ReductionTrace.Onset.EARLY);
            assertThat(head.fromSeconds()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a start in the silence inside a group is refused as sounding nothing")
        void aStartInAGapIsRefused() {
            // The first word is what reaches the line's hull over the earlier
            // note; the later note is what leaves a gap for the second to start
            // in.
            Score score = sung(notes(note(1.0, 0.4, 60), note(3.0, 2.0, 62)),
                    line(word("x", 0.0, 0.1), word("ah", 2.0, 5.0)));

            ReductionTrace.Head head = PlayableMelody.explain(score).heads().get(0);

            assertThat(head.notes()).isEqualTo(2);
            assertThat(head.onset().read()).isEqualTo(ReductionTrace.Onset.SILENT);
            assertThat(head.onset().syllableSeconds()).isEqualTo(2.0);
            assertThat(head.fromSeconds()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a head that opens no syllable keeps the melody's onset and says so")
        void aHeadOpeningNoSyllableKeepsItsOwn() {
            ReductionTrace trace =
                    PlayableMelody.explain(played(notes(note(0.0, 1.0, 60))));

            assertThat(trace.heads().get(0).onset().read())
                    .isEqualTo(ReductionTrace.Onset.MELODY);
            assertThat(trace.heads().get(0).onset().syllableSeconds()).isNull();
            assertThat(trace.counts().fromAligner()).isZero();
        }
    }

    @Nested
    @DisplayName("where a head's pitch came from")
    class ThePitch {

        @Test
        @DisplayName("a group that arrives where it spent its time says it settled")
        void anArrivalIsSettled() {
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 0.2, 62), note(0.2, 1.0, 60))))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.SETTLED);
            assertThat(pitch.arrivalMidi()).isEqualTo(60);
            assertThat(pitch.dominantMidi()).isEqualTo(60);
            assertThat(pitch.chord()).isNull();
        }

        @Test
        @DisplayName("a chord tone taken over a passing arrival names the span it came from")
        void theChartNamesTheSpanItAnswersFrom() {
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 1.0, 60), note(1.0, 0.2, 61))))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.CHART);
            assertThat(pitch.arrivalMidi()).isEqualTo(61);
            assertThat(pitch.dominantMidi()).isEqualTo(60);
            assertThat(pitch.arrivalBeats()).isCloseTo(0.2, within());
            assertThat(pitch.dominantBeats()).isCloseTo(1.0, within());
            assertThat(pitch.chord()).isEqualTo("C");
        }

        @Test
        @DisplayName("an unsettled group with no chord under it says that, not that it was read")
        void noChordIsNotAChordThatDidNotSeparate() {
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 1.0, 60), note(1.0, 0.2, 61)),
                            ChordProgression.empty()))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.NO_CHORD);
            assertThat(pitch.arrivalMidi()).isEqualTo(61);
            assertThat(pitch.chord()).isNull();
            assertThat(pitch.chordConfidence()).isNull();
        }

        @Test
        @DisplayName("a chord below the floor is refused as untrusted, not as unhelpful")
        void anUntrustedChordSaysWhatItWasReadAt() {
            // The same group the chart would separate at a confidence it trusts,
            // so the reading is the floor and nothing else.
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 1.0, 60), note(1.0, 0.2, 61)), chart(0.2)))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.UNTRUSTED);
            assertThat(pitch.chord()).isEqualTo("C");
            assertThat(pitch.chordConfidence()).isEqualTo(0.2);
            assertThat(pitch.chordConfidence()).isLessThan(pitch.requiredConfidence());
        }

        @Test
        @DisplayName("a chord admitting the arrival says it did, not that it could not choose")
        void aChordAgreeingWithTheArrivalSaysSo() {
            // The chart separated the two pitches and confirmed the one already
            // chosen, which is not the chart failing to separate them.
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 1.0, 62), note(1.0, 0.2, 60)), chart(1.0)))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.CONFIRMED);
            assertThat(pitch.arrivalMidi()).isEqualTo(60);
            assertThat(pitch.dominantMidi()).isEqualTo(62);
            assertThat(pitch.chord()).isEqualTo("C");
        }

        @Test
        @DisplayName("a trusted chord that admits neither pitch says it did not separate them")
        void aChordThatAnswersNeitherWaySaysSo() {
            ReductionTrace.Pitch pitch = PlayableMelody
                    .explain(sungOver(notes(note(0.0, 1.0, 62), note(1.0, 0.2, 61)), chart(1.0)))
                    .heads().get(0).pitch();

            assertThat(pitch.read()).isEqualTo(ReductionTrace.Pitch.UNAIDED);
            assertThat(pitch.chord()).isEqualTo("C");
            assertThat(pitch.chordConfidence()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("what the pass between the heads made of one")
    class TheReturn {

        @Test
        @DisplayName("a head brought home names both neighbours and where it went")
        void aReturnedHeadNamesItsNeighbours() {
            Score score = inC(notes(note(0.0, 1.0, 60), note(1.0, 0.5, 61),
                    note(1.5, 1.0, 60)));

            ReductionTrace trace = PlayableMelody.explain(score);

            ReductionTrace.Return returned = trace.heads().get(1).returned();
            assertThat(returned.read()).isEqualTo(ReductionTrace.Return.RETURNED);
            assertThat(returned.fromMidi()).isEqualTo(61);
            assertThat(returned.homeMidi()).isEqualTo(60);
            assertThat(returned.leftMidi()).isEqualTo(60);
            assertThat(returned.rightMidi()).isEqualTo(60);
            assertThat(trace.counts().moved()).isEqualTo(1);
        }

        @Test
        @DisplayName("a head returned onto its own pitch is counted as returned and not as moved")
        void aReturnOntoItsOwnPitchIsNotAMove() {
            // The same pitch, accounted for on either side of a span that does
            // not admit it: what the rule reaches and a comparison of the
            // printed pitches cannot see.
            Score score = withKey(played(
                    notes(note(0.0, 1.0, 61), note(1.0, 0.5, 61), note(1.5, 1.0, 61)),
                    new ChordProgression(List.of(
                            triad(NoteLetter.A, ChordQuality.MAJOR, 0.0, 1.1),
                            triad(NoteLetter.C, ChordQuality.MAJOR, 1.1, 1.4),
                            triad(NoteLetter.A, ChordQuality.MAJOR, 1.4, 3.0)),
                            Confidence.of(0.8))));

            ReductionTrace trace = PlayableMelody.explain(score);

            ReductionTrace.Return returned = trace.heads().get(1).returned();
            assertThat(returned.read()).isEqualTo(ReductionTrace.Return.RETURNED);
            assertThat(returned.homeMidi()).isEqualTo(returned.fromMidi());
            assertThat(trace.counts().returned()).isEqualTo(1);
            assertThat(trace.counts().moved()).isZero();
        }

        @Test
        @DisplayName("a head the harmony admits is left alone and says which reading did it")
        void aSupportedHeadIsLeftAlone() {
            Score score = inC(notes(note(0.0, 1.0, 60), note(1.0, 0.5, 64),
                    note(1.5, 1.0, 60)));

            ReductionTrace trace = PlayableMelody.explain(score);

            assertThat(trace.heads()).extracting(head -> head.returned().read())
                    .containsOnly(ReductionTrace.Return.SUPPORTED);
            assertThat(trace.counts().moved()).isZero();
        }

        @Test
        @DisplayName("a head held too long to be a wobble says how long it lasted")
        void aHeldHeadNamesItsLength() {
            Score score = inC(notes(note(0.0, 1.0, 60), note(1.0, 2.0, 61),
                    note(3.0, 1.0, 60)));

            ReductionTrace.Return returned =
                    PlayableMelody.explain(score).heads().get(1).returned();

            assertThat(returned.read()).isEqualTo(ReductionTrace.Return.HELD);
            assertThat(returned.beats()).isCloseTo(2.0, within());
            assertThat(returned.homeMidi()).isNull();
        }

        @Test
        @DisplayName("a head between its neighbours is read as a passing tone")
        void aPassingToneIsNamed() {
            Score score = inC(notes(note(0.0, 1.0, 60), note(1.0, 0.5, 61),
                    note(1.5, 1.0, 62)));

            ReductionTrace.Return returned =
                    PlayableMelody.explain(score).heads().get(1).returned();

            assertThat(returned.read()).isEqualTo(ReductionTrace.Return.PASSING);
        }

        @Test
        @DisplayName("a score with no chart says the rule had nothing to refuse a head with")
        void noChartIsNotSupport() {
            Score score = withKey(played(notes(note(0.0, 1.0, 61))));

            assertThat(PlayableMelody.explain(score).heads().get(0).returned().read())
                    .isEqualTo(ReductionTrace.Return.NO_CHORD);
        }

        @Test
        @DisplayName("a score with no key says the same, and does not read as support")
        void noKeyIsNotSupportEither() {
            // The chord alone cannot refuse a head: a diatonic passing tone is
            // outside every chord and perfectly readable.
            Score score = played(notes(note(0.0, 1.0, 61)), chart(1.0));

            assertThat(PlayableMelody.explain(score).heads().get(0).returned().read())
                    .isEqualTo(ReductionTrace.Return.NO_KEY);
        }

        @Test
        @DisplayName("a head with no supported head on both sides says it was unbounded")
        void anUnboundedHeadIsNamed() {
            Score score = inC(notes(note(0.0, 0.5, 61), note(0.5, 1.0, 60)));

            ReductionTrace.Return returned =
                    PlayableMelody.explain(score).heads().get(0).returned();

            assertThat(returned.read()).isEqualTo(ReductionTrace.Return.UNBOUNDED);
            assertThat(returned.leftMidi()).isNull();
            assertThat(returned.rightMidi()).isEqualTo(60);
        }
    }

    @Test
    @DisplayName("half a syllable is not a claim")
    void aSyllableIsALineAndAWord() {
        assertThatThrownBy(() -> new ReductionTrace.Source(0, 1, 60, 0, null, 0.0, 2.0, 0,
                ReductionTrace.Source.ALONE, ReductionTrace.Source.CHOSEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both a line and a word");
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Scores whose notes, words and chords are drawn at random, so the records
     * are read against reductions nobody wrote by hand.
     *
     * <p>Seeded, because a property that fails on one run in twenty is a
     * property nobody can act on.
     */
    private static void forEachScore(java.util.function.Consumer<Score> check) {
        Random random = new Random(680);
        for (int i = 0; i < 200; i++) {
            check.accept(generated(random));
        }
    }

    private static Score generated(Random random) {
        List<Note> notes = new ArrayList<>();
        double at = random.nextDouble();
        for (int i = 0; i < 1 + random.nextInt(20); i++) {
            double duration = 0.05 + 1.5 * random.nextDouble();
            notes.add(note(at, duration, 55 + random.nextInt(14)));
            at += duration * (random.nextBoolean() ? 1 : 1.4);
        }
        List<LyricLine> lines = new ArrayList<>();
        double word = 0;
        for (int l = 0; l < random.nextInt(3); l++) {
            List<LyricWord> words = new ArrayList<>();
            for (int w = 0; w < 1 + random.nextInt(5); w++) {
                double length = 0.2 + random.nextDouble();
                words.add(word("sy" + w, word, word + length)
                        .withMelisma(random.nextInt(5) == 0));
                // Now and then a gap wide enough to be an instrumental one,
                // which is what puts a note inside a line and far from every
                // word of it.
                word += length * (1 + random.nextDouble())
                        + (random.nextInt(5) == 0 ? 3 + 5 * random.nextDouble() : 0);
            }
            lines.add(new LyricLine(words, Confidence.of(0.8)));
        }
        List<Chord> spans = new ArrayList<>();
        double span = 0;
        while (span < at) {
            double length = 1 + 3 * random.nextDouble();
            spans.add(new Chord(new PitchSpelling(
                    NoteLetter.values()[random.nextInt(NoteLetter.values().length)],
                    Accidental.NATURAL, 4),
                    random.nextBoolean() ? ChordQuality.MAJOR : ChordQuality.MINOR,
                    Optional.empty(), span, span + length,
                    Optional.empty(), Optional.empty(), Confidence.of(random.nextDouble())));
            span += length;
        }
        Score score = score(notes, new ChordProgression(spans, Confidence.of(0.7)),
                new Lyrics(lines, "en", Confidence.of(0.8)));
        return random.nextBoolean() ? withKey(score) : score;
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }

    private static Score withKey(Score score) {
        return score.withKeys(List.of(Key.ofSeconds(
                new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4), Mode.MAJOR,
                0.0, score.durationSeconds(), Confidence.CERTAIN)));
    }

    /** The notes under a C major chart, in C major, with no words. */
    private static Score inC(List<Note> notes) {
        return withKey(played(notes, chart(1.0)));
    }

    /** One syllable held over the whole group, so the chart has a tie to break. */
    private static Score sungOver(List<Note> notes) {
        return sungOver(notes, chart(1.0));
    }

    private static Score sungOver(List<Note> notes, ChordProgression chords) {
        double end = notes.get(notes.size() - 1).offsetSeconds();
        return score(notes, chords, new Lyrics(
                List.of(line(word("ah", notes.get(0).onsetSeconds(), end))), "en",
                Confidence.of(0.8)));
    }

    private static ChordProgression chart(double confidence) {
        return new ChordProgression(List.of(
                triad(NoteLetter.C, ChordQuality.MAJOR, 0.0, 8.0, confidence)),
                Confidence.of(confidence));
    }

    private static Chord triad(NoteLetter root, ChordQuality quality, double fromSeconds,
                               double toSeconds) {
        return triad(root, quality, fromSeconds, toSeconds, 1.0);
    }

    private static Chord triad(NoteLetter root, ChordQuality quality, double fromSeconds,
                               double toSeconds, double confidence) {
        return new Chord(new PitchSpelling(root, Accidental.NATURAL, 4), quality,
                Optional.empty(), fromSeconds, toSeconds, Optional.empty(), Optional.empty(),
                Confidence.of(confidence));
    }

    private static Note note(double onsetSeconds, double durationSeconds, int midiPitch) {
        return Note.ofSeconds(onsetSeconds, durationSeconds, midiPitch, Confidence.of(0.7));
    }

    private static List<Note> notes(Note... notes) {
        return Arrays.asList(notes);
    }

    private static LyricWord word(String text, double startSeconds, double endSeconds) {
        return LyricWord.ofSeconds(text, startSeconds, endSeconds, Confidence.of(0.8));
    }

    private static LyricLine line(LyricWord... words) {
        return new LyricLine(Arrays.asList(words), Confidence.of(0.8));
    }

    private static Score sung(List<Note> notes, LyricLine... lines) {
        return score(notes, ChordProgression.empty(),
                new Lyrics(Arrays.asList(lines), "it", Confidence.of(0.8)));
    }

    private static Score played(List<Note> notes) {
        return played(notes, ChordProgression.empty());
    }

    private static Score played(List<Note> notes, ChordProgression chords) {
        return score(notes, chords, Lyrics.empty());
    }

    private static Score score(List<Note> notes, ChordProgression chords, Lyrics lyrics) {
        // One beat a second, so that a duration in seconds reads as the same
        // number of beats.
        return new Score(Optional.empty(), Optional.empty(), TempoMap.constant(60),
                Optional.empty(), List.of(), List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.of(0.7))),
                chords, lyrics, 60);
    }
}
