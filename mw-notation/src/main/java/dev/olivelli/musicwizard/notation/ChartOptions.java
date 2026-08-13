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

package dev.olivelli.musicwizard.notation;

/**
 * What a caller wants on the engraved chart beyond the chart itself.
 *
 * <p>Marks for checking a transcription rather than for playing from it, which
 * is why each is a choice and none is on by default: a chart handed to somebody
 * to play from wants the chords and the words and nothing else, and every
 * annotation costs it a little legibility.
 *
 * @param beatMarks  draw the tracked beats under the chords (#416)
 * @param repeatTags label the lines the chart prints more than once (#417)
 */
public record ChartOptions(boolean beatMarks, boolean repeatTags) {

    /** A plain chart: the chords, the words on the lyric sheet, nothing over them. */
    public static ChartOptions defaults() {
        return new ChartOptions(false, false);
    }
}
