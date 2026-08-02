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

import android.app.Activity;
import android.os.Build;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Base class for every screen: keeps the layout out from under the system bars.
 *
 * <p>Android 15 lays a {@code targetSdk} 35 app's content edge to edge, and the
 * platform action bar stops insetting the content below itself along with it.
 * Nothing pads the content on the app's behalf any more, so the top of each
 * screen is drawn underneath the status and action bars and is neither visible
 * nor touchable — on the result screen that is the whole of what it shows
 * before an analysis has run, which is why it opened black (#281).
 *
 * <p>The padding is applied in {@link #onContentChanged()}, which runs after any
 * {@code setContentView} overload, rather than from each screen's
 * {@code onCreate}.
 */
public abstract class MwActivity extends Activity {

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // The insets arriving here already carry the action bar's height, added
        // by the decor before it dispatches them inwards.
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets padding = insets.getInsets(insetTypes(Build.VERSION.SDK_INT));
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /**
     * The insets to pad by on a device at {@code sdkInt}.
     *
     * <p>The cutout is asked for only from API 30, which is where androidx
     * begins answering that type from the insets being dispatched rather than
     * from the <em>root</em> window's — below it, at a view the decor has
     * already inset, the cutout is counted a second time. Nothing is lost:
     * while the theme leaves {@code windowLayoutInDisplayCutoutMode} at its
     * default the cutout stays inside the bars the padding already counts.
     * Android 15 reads that default as {@code ALWAYS} for a {@code targetSdk}
     * 35 app, and that is where the term is needed.
     */
    static int insetTypes(int sdkInt) {
        int bars = WindowInsetsCompat.Type.systemBars();
        return sdkInt >= Build.VERSION_CODES.R
                ? bars | WindowInsetsCompat.Type.displayCutout()
                : bars;
    }
}
