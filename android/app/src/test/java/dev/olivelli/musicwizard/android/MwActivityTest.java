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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Every screen the manifest declares must inherit the system-bar padding, and
 * the padding must count the cutout only where asking for it means anything.
 *
 * <p>Nothing else in this build notices either one: a screen that forgets the
 * base class compiles, installs and runs, and is simply drawn underneath the
 * status and action bars on Android 15 — which on the result screen looked like
 * a blank page (#281). There is no emulator here, so these read the manifest and
 * the type mask; what the padding then measures out to is checked on a device.
 */
public class MwActivityTest {

    /** Matches the {@code namespace} in {@code build.gradle}. */
    private static final String PACKAGE = "dev.olivelli.musicwizard.android";

    private static final String MANIFEST = "src/main/AndroidManifest.xml";

    private static final List<String> SCREENS = List.of(
            PACKAGE + ".RecordActivity",
            PACKAGE + ".LibraryActivity",
            PACKAGE + ".ResultActivity");

    @Test
    public void everyDeclaredActivityInheritsSystemBarPadding() throws Exception {
        List<String> declared = declaredActivities();
        // Positive control: a manifest read from the wrong place, or an
        // attribute renamed, would otherwise pass with nothing to check.
        assertTrue("activities found in " + MANIFEST + ": " + declared,
                declared.containsAll(SCREENS));

        for (String name : declared) {
            Class<?> screen = Class.forName(name, false, getClass().getClassLoader());
            assertTrue(name + " must extend MwActivity, or it will draw under the system bars",
                    MwActivity.class.isAssignableFrom(screen));
        }
    }

    /**
     * Below API 30 the cutout must not be asked for: androidx answers it from
     * the root window rather than the dispatched insets, and the padding is then
     * added on top of the inset the decor already applied.
     */
    @Test
    public void theCutoutCountsOnlyFromApi30() {
        int bars = WindowInsetsCompat.Type.systemBars();
        int cutout = WindowInsetsCompat.Type.displayCutout();

        assertEquals("the bars alone at minSdk",
                bars, MwActivity.insetTypes(Build.VERSION_CODES.O));
        assertEquals("the bars alone below API 30",
                bars, MwActivity.insetTypes(Build.VERSION_CODES.Q));
        assertEquals("the bars and the cutout from API 30",
                bars | cutout, MwActivity.insetTypes(Build.VERSION_CODES.R));
    }

    /** The fully qualified name of every {@code <activity>} in the manifest. */
    private static List<String> declaredActivities() throws Exception {
        File manifest = new File(MANIFEST);
        assertTrue("not found from " + new File(".").getAbsolutePath() + ": " + MANIFEST,
                manifest.isFile());
        NodeList activities = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(manifest)
                .getElementsByTagName("activity");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < activities.getLength(); i++) {
            String name = ((Element) activities.item(i))
                    .getAttribute("android:name");
            names.add(name.startsWith(".") ? PACKAGE + name : name);
        }
        return names;
    }
}
