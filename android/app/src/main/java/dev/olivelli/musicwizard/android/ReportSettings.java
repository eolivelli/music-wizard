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
    private static final String FILED_PREFIX = "filed:";

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

    /**
     * Whether this take's current comment is already on GitHub.
     *
     * <p>Here rather than in the screen because the screen is not the right
     * scope for it: back out of a finished send, open the take again, and a
     * fresh instance would offer to send the same thing a second time.
     */
    static boolean isFiled(Context context, String takeName) {
        return prefs(context).getBoolean(FILED_PREFIX + takeName, false);
    }

    static void setFiled(Context context, String takeName, boolean filed) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (filed) {
            editor.putBoolean(FILED_PREFIX + takeName, true);
        } else {
            editor.remove(FILED_PREFIX + takeName);
        }
        editor.apply();
    }

    /**
     * Drops what is remembered about a take, because it no longer exists.
     *
     * <p>Same reason {@code AnalysisJobs.forget} exists and called from beside
     * it: these are keyed by the take's name, and a name is reusable, so a
     * later take renamed onto a deleted one's would inherit its draft.
     */
    static void forget(Context context, String takeName) {
        prefs(context).edit()
                .remove(DRAFT_PREFIX + takeName)
                .remove(FILED_PREFIX + takeName)
                .apply();
    }

    /** Carries a take's unsent comment to its new name, for the same reason. */
    static void moved(Context context, String from, String to) {
        if (from.equals(to)) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String draft = prefs.getString(DRAFT_PREFIX + from, "");
        boolean filed = prefs.getBoolean(FILED_PREFIX + from, false);
        SharedPreferences.Editor editor = prefs.edit()
                .remove(DRAFT_PREFIX + from)
                .remove(FILED_PREFIX + from)
                .remove(DRAFT_PREFIX + to)
                .remove(FILED_PREFIX + to);
        if (!draft.trim().isEmpty()) {
            editor.putString(DRAFT_PREFIX + to, draft);
        }
        if (filed) {
            editor.putBoolean(FILED_PREFIX + to, true);
        }
        editor.apply();
    }
}
