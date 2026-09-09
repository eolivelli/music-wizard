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
package dev.olivelli.musicwizard.android;

import android.content.Context;
import android.content.SharedPreferences;

/** What the person chose, kept across takes and launches. */
final class Preferences {

    private static final String FILE = "settings";
    private static final String PLAYABLE_PART = "playablePart";
    private static final String MELODY_FLOOR = "melodyFloor";

    private Preferences() {
    }

    /** Whether analyses track the melody and the PDF carries the part reduced from it. */
    static boolean playablePart(Context context) {
        return of(context).getBoolean(PLAYABLE_PART, true);
    }

    static void setPlayablePart(Context context, boolean on) {
        of(context).edit().putBoolean(PLAYABLE_PART, on).apply();
    }

    /** The lowest note the melody may be, as a note name, or blank for any. */
    static String melodyFloor(Context context) {
        return of(context).getString(MELODY_FLOOR, "");
    }

    static void setMelodyFloor(Context context, String note) {
        of(context).edit().putString(MELODY_FLOOR, note == null ? "" : note).apply();
    }

    private static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
