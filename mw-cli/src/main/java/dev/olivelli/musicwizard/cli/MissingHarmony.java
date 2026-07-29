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

import dev.olivelli.musicwizard.core.model.Score;
import java.util.Objects;

/**
 * Why a score has no chord progression, worded once for every command that has
 * to say so.
 *
 * <p>This class exists because the same explanation was written three times and
 * was wrong in a different way each time. {@code render} first decided it by
 * sniffing the workspace's source file, which broke when the file was deleted
 * and lied when it was replaced. Its replacement read the score's track list as
 * a stand-in for provenance, which was wrong for a MIDI file holding only a
 * conductor track. And {@code analyze} carried a third wording of its own,
 * unconditionally naming #115 -- so on that same conductor-track file the two
 * commands gave different reasons for the same fact three lines apart, and the
 * {@code analyze} one was false: a file with no notes has no harmony for #115 to
 * name.
 *
 * <p>Two fixes at the layer the symptom was noticed is this project's most
 * frequent failure. A third edit in a third place was not the answer; removing
 * the choice was. Every command that explains an absent chord progression calls
 * this, and there is nowhere left for a fourth wording to appear.
 *
 * <p>What the answer may depend on is the <em>score</em>, and only the score.
 * Where the score came from is not knowable here and is not guessed at.
 *
 * <p>#120 closed while this was in review, and it does <em>not</em> make it
 * knowable, which is worth saying because the issue number alone would suggest
 * otherwise. What it added is a {@code Provenance} on each tempo segment -- how
 * that tempo was arrived at -- and not a record of what produced the score. A
 * {@code DECLARED} opening tempo does imply a MIDI import today, and reading it
 * that way would be a proxy for provenance rather than provenance, which is
 * exactly the move that was wrong twice here already.
 *
 * <p>Neither branch names a cause any more, and #115 landing on main while this
 * change was in review is why. Until then a score with note tracks and no chords
 * was always a MIDI import whose harmony nothing had estimated, so the branch
 * for it named that missing stage. {@code SymbolicChordEstimator} now runs on
 * the MIDI path, so a score with parts and no chords is one where an estimator
 * ran and found nothing -- on either path, by different means, since audio
 * chords come from the mix and symbolic ones from the notes. There is no single
 * true cause left to name, and the fourth attempt at naming one would have been
 * wrong the same way the first three were.
 *
 * <p>What is left is a description of the score, which is all this ever safely
 * had. #125 tracked the surviving cause-claim as coming due with #8; #115 made
 * it due sooner.
 */
final class MissingHarmony {

    private MissingHarmony() {
    }

    /**
     * The explanation, as a clause a caller prefixes with a comma.
     *
     * <p>Both branches now state what the score holds and stop. The restraint is
     * the point -- this sentence was wrong three times by reaching past what it
     * can know, and the first two are worth keeping on the record:
     *
     * <ul>
     *   <li>"so there is nothing to engrave" was a claim about the whole score
     *       from two of its ten fields, and #9 falsifies it: a chords-and-lyrics
     *       sheet is this project's strongest output and needs neither a chord
     *       progression nor a note track to be worth printing.
     *   <li>"so there is nothing for harmony to be derived from" was a claim
     *       about mechanism, true on the MIDI path and false on the audio one,
     *       where chords are estimated from the mix and never from note tracks.
     *       On a recording the estimator had genuinely run and found nothing,
     *       and this told the user it had never had anything to run on -- while
     *       {@code analyze} three lines earlier reported the spans it looked for.
     *       It also pointed an audio user at a note-transcription issue that has
     *       no bearing on why the chord estimator returned nothing.
     * </ul>
     *
     * <p>Both were introduced by fixes for the sentence being too narrow, which
     * is the standing lesson: what keeps this line safe on two paths that share
     * nothing but a {@link Score} is describing the score rather than explaining
     * it.
     *
     * <p>The third was the {@code parts > 0} branch naming #115 as the missing
     * stage, which #115 landing made false. It is not replaced by a fourth guess:
     * telling a user <em>why</em> an estimator found nothing needs to know which
     * estimator ran, which needs to know what produced the score, and #120 -- the
     * issue that sounds like it answers that -- records how a tempo was arrived
     * at instead. See the note on the class.
     */
    static String explain(Score score) {
        Objects.requireNonNull(score, "score");
        int parts = score.tracks().size();
        return parts == 0
                ? "and no parts either"
                : "though it holds " + parts + " part(s)";
    }
}
