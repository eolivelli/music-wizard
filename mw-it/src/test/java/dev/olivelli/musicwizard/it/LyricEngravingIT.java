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

package dev.olivelli.musicwizard.it;

import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.notation.LyricSheet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LilyPond reading back the lyric line's arithmetic.
 *
 * <p>The same oracle {@code ChordChartEngravingIT} is, one context down, and it
 * exists for the same reason. A lyric block whose durations do not sum engraves
 * a page with the words in the wrong bars and says nothing about it unless the
 * bar checks are there to be failed — so the tests that matter are the ones
 * where LilyPond counts, not the ones where the generator agrees with itself.
 *
 * <p>{@code assertEngravedCleanly} is what holds the other half. A {@code
 * Lyrics} context under {@code ChordNames} with no staff between them warns
 * <em>staff-affinities should only decrease</em> on every run unless the
 * affinity is set, and that warning would otherwise land in the output this
 * tool parses to decide whether engraving went well.
 */
class LyricEngravingIT {

    @TempDir
    Path tempDirectory;

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    private static Score sung(int bars, double barSeconds, TimeSignature meter,
                              double quarterBpm, String lrc) {
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            chords.add(Chord.ofSeconds(root(roots[i % 4]),
                    i % 3 == 2 ? ChordQuality.MINOR : ChordQuality.MAJOR,
                    i * barSeconds, (i + 1) * barSeconds, Confidence.of(0.9)));
        }
        Score score = Score.empty(TempoMap.constant(quarterBpm, meter), bars * barSeconds)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)))
                .withMetadata("Sung", "Tester");
        return score.withLyrics(LrcLyrics.parse(lrc, score.durationSeconds()));
    }

    static Stream<Arguments> sheets() {
        return Stream.of(
                Arguments.of("on-the-beat", sung(6, 2.0, TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>When <00:00.50>I <00:01.00>find <00:01.50>my
                        [00:02.00]<00:02.00>self <00:03.00>in <00:03.50>times
                        [00:04.00]<00:04.00>of <00:05.00>trou <00:05.50>ble
                        [00:08.00]<00:08.00>Mo <00:09.00>ther <00:10.00>Ma <00:11.00>ry
                        """)),
                // Onsets that fall on nothing a duration can name, which is what
                // recognition and hand timing both produce.
                Arguments.of("off-the-grid", sung(6, 2.0, TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.13>ra <00:00.71>gged <00:01.37>ti <00:01.88>ming
                        [00:02.00]<00:02.29>keeps <00:03.11>on <00:03.94>going
                        [00:04.00]<00:04.44>past <00:05.03>the <00:05.61>bar
                        [00:08.00]<00:08.17>and <00:09.29>back
                        """)),
                // A compound meter, where a bar is three quarter beats and the
                // counted beat is not the quarter.
                Arguments.of("six-eight", sung(4, 1.5, TimeSignature.SIX_EIGHT, 120, """
                        [00:00.00]<00:00.00>one <00:00.75>two
                        [00:01.50]<00:01.50>three <00:02.25>four
                        [00:03.00]<00:03.20>five
                        """)),
                // Words with digits and punctuation, which is what a real lyric
                // file carries and what an unquoted syllable turns into an error.
                Arguments.of("awkward-words", sung(4, 2.0, TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>1999 <00:01.00>Apollo8 <00:02.00>24/7
                        [00:04.00]<00:04.00>rock'n'roll <00:05.50>done
                        """)),
                // Lines that print identically, so the repeat-bracket lane is
                // engraved above the chords and the lyrics below them at once.
                Arguments.of("with-repeats", sung(8, 2.0, TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>first <00:01.00>time
                        [00:08.00]<00:08.00>second <00:09.00>time
                        """)),
                // One bar, which has no neighbour to borrow a rate from.
                Arguments.of("one-bar", sung(1, 2.0, TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>a <00:00.50>b <00:01.00>c <00:01.50>d
                        """)),
                // Hyphenated syllables, including a chain running to the end of
                // the lyric: a hyphen with nothing on its right is an
                // unterminated hyphen, which assertEngravedCleanly rejects.
                Arguments.of("hyphenated", hyphenated()),
                // Chords stated on the beat axis, which is the other route
                // through ChartLayout and the one a MIDI import takes.
                Arguments.of("quantized-chords", quantized()),
                // Lines overlapping in time, which is two Lyrics contexts
                // stacked under the chords. Every word hyphenated, so a chain
                // ends each lane: a hyphen closed by a syllable in the other
                // lane is an unterminated hyphen here.
                Arguments.of("overlapping-lines", overlapping()),
                // Italian split into syllables, so most of the page is
                // hyphen-joined and the elided articles are carried onto the
                // syllable after them.
                Arguments.of("italian-syllables", inLanguage("it", sung(6, 2.0,
                        TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>Le <00:00.60>bionde <00:01.40>trecce
                        [00:02.00]<00:02.10>e <00:02.70>l'innocenza <00:03.60>sulle
                        [00:04.00]<00:04.20>gote <00:05.10>tue <00:05.70>due
                        [00:08.00]<00:08.30>arance <00:09.40>ancor <00:10.60>piu
                        """))),
                // English, whose patterns break where a typesetter would.
                Arguments.of("english-syllables", inLanguage("en", sung(4, 2.0,
                        TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>Somebody <00:01.20>once <00:01.80>told
                        [00:02.00]<00:02.40>trouble <00:03.30>wisdom
                        """))),
                // The tokens a real lyric is full of and the patterns say least
                // about: possessives and contractions, whose apostrophe English
                // never mentions, and hyphenated compounds, whose hyphen neither
                // language does.
                Arguments.of("possessives-and-compounds", inLanguage("en", sung(6, 2.0,
                        TimeSignature.FOUR_FOUR, 120, """
                        [00:00.00]<00:00.00>Anderson's <00:00.90>heart <00:01.50>don't
                        [00:02.00]<00:02.30>well-known <00:03.10>sing-along
                        [00:04.00]<00:04.20>trouble, <00:05.00>wisdom.
                        [00:08.00]<00:08.40>(chorus) <00:09.60>abandons
                        """))));
    }

    /** The same score with its lyrics tagged, so their words are split. */
    private static Score inLanguage(String language, Score score) {
        return score.withLyrics(new Lyrics(score.lyrics().lines(), language,
                score.lyrics().confidence()));
    }

    /** Every syllable hyphenated, so the chain runs off the end of the lyric. */
    private static Score hyphenated() {
        Score score = sung(4, 2.0, TimeSignature.FOUR_FOUR, 120,
                "[00:00.00]<00:00.00>Hal <00:01.00>le <00:02.00>lu <00:03.00>jah\n");
        List<LyricWord> words = new ArrayList<>();
        for (LyricWord word : score.lyrics().lines().get(0).words()) {
            words.add(word.withHyphenToNext(true));
        }
        return score.withLyrics(new Lyrics(
                List.of(new LyricLine(words, Confidence.of(0.9))), "und", Confidence.of(0.9)));
    }

    /**
     * Two lines sung over each other, each a hyphen chain to its own end.
     *
     * <p>Built by hand rather than from LRC, which clamps every word into its
     * own line and so cannot state an overlap.
     */
    private static Score overlapping() {
        Confidence sure = Confidence.of(0.9);
        Lyrics lines = new Lyrics(List.of(
                new LyricLine(List.of(
                        word("Hal", 0.10, sure), word("le", 0.90, sure),
                        word("lu", 1.70, sure), word("jah", 13.40, sure)), sure),
                new LyricLine(List.of(
                        word("o", 2.30, sure), word("ver", 4.10, sure),
                        word("head", 7.70, sure), word("now", 11.20, sure)), sure)),
                "und", sure);
        // The LRC only gets sung() as far as a chart; the lyrics it parses are
        // replaced by the two lines above.
        return sung(8, 2.0, TimeSignature.FOUR_FOUR, 120,
                "[00:00.00]<00:00.00>replaced\n").withLyrics(lines);
    }

    /** A word of a fifth of a second, hyphenated to whatever follows it. */
    private static LyricWord word(String text, double startSeconds, Confidence confidence) {
        return LyricWord.ofSeconds(text, startSeconds, startSeconds + 0.2, confidence)
                .withHyphenToNext(true);
    }

    /** A progression on the beat axis, which sends ChartLayout down fromBeats. */
    private static Score quantized() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        List<Chord> chords = new ArrayList<>();
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        for (int i = 0; i < 4; i++) {
            chords.add(new Chord(root(roots[i]), ChordQuality.MAJOR, Optional.empty(),
                    i * 2.0, (i + 1) * 2.0,
                    Optional.of(i * 4.0), Optional.of((i + 1) * 4.0), Confidence.of(0.9)));
        }
        Score score = Score.empty(map, 8.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)))
                .withMetadata("Quantized", "Tester");
        return score.withLyrics(LrcLyrics.parse(
                "[00:00.00]<00:00.00>on <00:01.00>the <00:02.50>beat <00:05.00>axis\n", 8.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sheets")
    @DisplayName("every lyric bar sums to its meter, and the page engraves without complaint")
    void everyLyricBarSumsToItsMeter(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/sheet.ly"),
                        LyricSheet.toLilyPond(score));

        // The narrow question first: a lyric line that does not sum puts the
        // words in the wrong bars, and this is the only reader that counts them.
        assertThat(result.failedBarChecks()).as("%s", result.output()).isEmpty();
        // Then everything else, which is what catches the staff-affinity warning
        // if the override is ever dropped.
        assertEngravedCleanly(name, result);
        assertThat(result.pdf()).isPresent();
    }
}
