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

/**
 * The two things the reporting path remembers: the token, and an unsent comment.
 *
 * <p>Plain {@link SharedPreferences} in app-private storage, deliberately, and
 * the trade-off is worth naming. The file is readable by this app's uid and by
 * root, so it is protected from other apps and not from someone holding an
 * unlocked, rooted phone. The alternative is a hardware-backed keystore, which
 * on this instrument buys little: the token is fine-grained and scoped to one
 * repository, the owner can revoke it from a web page, and the thing it guards
 * is the ability to post a comment. Encrypting it against a key the same app
 * must be able to use unattended is mostly ceremony. If that calculation ever
 * changes — a shared phone, a broader token — this is the one class to change.
 *
 * <p>The draft is the other half of a promise the send screen makes: a failed
 * upload must never cost someone the sentence they typed about what they played,
 * which is the part that cannot be recovered by trying again.
 */
final class ReportSettings {

    private static final String FILE = "report";
    private static final String TOKEN = "github_token";
    private static final String DRAFT_PREFIX = "draft:";

    private ReportSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The stored token, or an empty string when there is none. */
    static String token(Context context) {
        return prefs(context).getString(TOKEN, "");
    }

    static boolean hasToken(Context context) {
        return !token(context).trim().isEmpty();
    }

    /** Stores a token, or forgets the stored one when {@code token} is blank. */
    static void setToken(Context context, String token) {
        String trimmed = token == null ? "" : token.trim();
        SharedPreferences.Editor editor = prefs(context).edit();
        if (trimmed.isEmpty()) {
            editor.remove(TOKEN);
        } else {
            editor.putString(TOKEN, trimmed);
        }
        editor.apply();
    }

    /** What was typed about this take and not yet sent. */
    static String draft(Context context, String takeName) {
        return prefs(context).getString(DRAFT_PREFIX + takeName, "");
    }

    static void setDraft(Context context, String takeName, String text) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (text == null || text.trim().isEmpty()) {
            editor.remove(DRAFT_PREFIX + takeName);
        } else {
            editor.putString(DRAFT_PREFIX + takeName, text);
        }
        editor.apply();
    }
}
