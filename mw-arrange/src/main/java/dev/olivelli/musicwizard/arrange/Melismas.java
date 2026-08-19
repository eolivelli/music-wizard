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

import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which sung syllables are melismas (#597).
 *
 * <p><b>Pitch movement, not length.</b> A syllable is marked when the melody
 * moves under it: the notes it is sung on print more than one note-head, and
 * those reach far enough apart to be a run rather than one tracked note
 * wavering — and near enough to be a voice rather than the octave fold. A
 * syllable that is merely held, and one re-articulated on the same pitch, are
 * left unmarked: the first is a long note and the second a repeated one, and
 * neither is a run a reader needs printed separately.
 *
 * <p>The heads are the ones {@link PlayableMelody} prints for the syllable
 * once it is marked, read from that class rather than predicted: a scoop and
 * the note it arrives at count once, so a syllable whose extra notes are all
 * scoop fragments stays collapsed.
 *
 * <p>The decision is a function of the melody alone, so marks already on the
 * lyrics are replaced rather than added to.
 */
public final class Melismas {

    /**
     * How far apart, in semitones, the note-heads under one syllable must
     * reach for the syllable to be sung over a run: a step, since a tracked
     * note that wanders inside one is not a melody moving.
     *
     * <p>Swept by {@code tools/PlayablePartCheck.java}.
     */
    private static final int RUN_SEMITONES = 2;

    /**
     * How far apart the heads must reach to be read as the melody stage's
     * octave fold (#614, #615) rather than as a run. Printing one of those
     * would put the fold on the page instead of leaving it under a single
     * head.
     *
     * <p>Swept alongside the other.
     */
    private static final int FOLD_SEMITONES = 12;

    private Melismas() {
    }

    /**
     * The score's lyrics with every syllable marked as a melisma or not.
     *
     * <p>Returns them unchanged when the score holds no melody: nothing then
     * says what any syllable is sung over.
     */
    public static Lyrics marked(Score score) {
        return marked(score, RUN_SEMITONES, FOLD_SEMITONES);
    }

    /**
     * The same at chosen intervals, which is what
     * {@code tools/PlayablePartCheck.java} sweeps.
     *
     * @param runSemitones  how far apart the syllable's note-heads must reach
     * @param foldSemitones how far apart they reach before they are read as an
     *                      octave fold instead
     * @throws IllegalArgumentException if either interval is negative
     */
    public static Lyrics marked(Score score, int runSemitones, int foldSemitones) {
        Objects.requireNonNull(score, "score");
        if (runSemitones < 0 || foldSemitones < 0) {
            throw new IllegalArgumentException(
                    "the intervals must not be negative, got: " + runSemitones
                            + " and " + foldSemitones);
        }
        Lyrics lyrics = score.lyrics();
        if (lyrics.isEmpty() || score.track(PartRole.LEAD_VOCAL).isEmpty()) {
            return lyrics;
        }
        boolean[][] melisma = new boolean[lyrics.lines().size()][];
        for (int l = 0; l < melisma.length; l++) {
            melisma[l] = new boolean[lyrics.lines().get(l).words().size()];
        }
        for (PlayableMelody.SungSyllable sung : PlayableMelody.sungSyllables(score)) {
            melisma[sung.line()][sung.word()] =
                    isRun(sung.heads(), runSemitones, foldSemitones);
        }
        List<LyricLine> lines = new ArrayList<>(lyrics.lines().size());
        for (int l = 0; l < lyrics.lines().size(); l++) {
            LyricLine line = lyrics.lines().get(l);
            List<LyricWord> words = new ArrayList<>(line.words().size());
            for (int w = 0; w < line.words().size(); w++) {
                LyricWord word = line.words().get(w);
                words.add(word.melisma() == melisma[l][w]
                        ? word : word.withMelisma(melisma[l][w]));
            }
            lines.add(new LyricLine(words, line.confidence()));
        }
        return new Lyrics(lines, lyrics.language(), lyrics.confidence());
    }

    private static boolean isRun(List<Note> heads, int runSemitones, int foldSemitones) {
        if (heads.size() < 2) {
            return false;
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (Note head : heads) {
            lowest = Math.min(lowest, head.midiPitch());
            highest = Math.max(highest, head.midiPitch());
        }
        int reach = highest - lowest;
        return reach >= runSemitones && reach < foldSemitones;
    }
}
