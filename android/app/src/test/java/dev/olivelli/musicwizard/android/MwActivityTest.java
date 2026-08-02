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
 * Screen-level facts that nothing else in this build checks.
 *
 * <p>Each is silent when it goes wrong: a screen that forgets the base class
 * compiles, installs and runs, and is simply drawn underneath the status and
 * action bars on Android 15 — which on the result screen looked like a blank
 * page (#281). There is no emulator here, so these read the manifest, the
 * layout and the type mask; what any of it measures out to is checked on a
 * device.
 */
public class MwActivityTest {

    /** Matches the {@code namespace} in {@code build.gradle}. */
    private static final String PACKAGE = "dev.olivelli.musicwizard.android";

    private static final String MANIFEST = "src/main/AndroidManifest.xml";

    private static final String REPORT_LAYOUT = "src/main/res/layout/activity_report.xml";

    private static final List<String> SCREENS = List.of(
            PACKAGE + ".RecordActivity",
            PACKAGE + ".LibraryActivity",
            PACKAGE + ".ResultActivity",
            PACKAGE + ".ReportActivity",
            PACKAGE + ".TokenActivity");

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

    /**
     * The comment field must not have its text saved by the framework.
     *
     * <p>Its text already lives in the app's preferences, written by
     * {@code onPause} and read back by {@code onCreate}. The framework's copy
     * is the same fact stored twice, and restoring it dispatches a text change
     * that nobody typed — which the send screen reads as "this comment is no
     * longer the one that was sent" and uses to un-file a take already on
     * GitHub, deleting the record that says so. Nothing else in the build
     * notices: it needs a configuration change the manifest does not list, or
     * a killed process.
     */
    @Test
    public void theCommentFieldDoesNotLetTheFrameworkSaveItsText() throws Exception {
        Element field = elementById(REPORT_LAYOUT, "@+id/comment");
        assertEquals("android:saveEnabled on the comment field in " + REPORT_LAYOUT,
                "false", field.getAttribute("android:saveEnabled"));
    }

    /** The one element in {@code layout} whose {@code android:id} is {@code id}. */
    private static Element elementById(String layout, String id) throws Exception {
        File file = new File(layout);
        assertTrue("not found from " + new File(".").getAbsolutePath() + ": " + layout,
                file.isFile());
        NodeList all = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (id.equals(element.getAttribute("android:id"))) {
                return element;
            }
        }
        throw new AssertionError("no element with android:id=\"" + id + "\" in " + layout);
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
