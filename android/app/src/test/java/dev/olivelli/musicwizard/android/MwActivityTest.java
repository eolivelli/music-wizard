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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
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

    private static final List<String> SCREENS = List.of(
            PACKAGE + ".RecordActivity",
            PACKAGE + ".LibraryActivity",
            PACKAGE + ".ResultActivity",
            PACKAGE + ".ImportActivity");

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
     * The microphone and the network, and nothing else.
     *
     * <p>The app used to promise that nothing it held ever left the phone by any
     * route but the share sheet, and #415 retired that deliberately: a shared
     * YouTube link is fetched over the network. What replaced it is narrower and
     * still worth pinning — nothing the microphone records is uploaded, the app
     * holds no credential, and the only host it contacts is one the user asked
     * it to fetch from. A third permission added here would go past all of that
     * with no other symptom: the app would run exactly as before.
     *
     * <p>Compared as a sorted set rather than in document order, because pinning
     * the order of a manifest fails on a cosmetic edit and teaches nothing.
     * (This reads the app's own manifest; what a library merges in is visible
     * only in the built APK.)
     */
    @Test
    public void theAppAsksForTheMicrophoneTheNetworkAndNothingElse() throws Exception {
        NodeList permissions = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(MANIFEST))
                .getElementsByTagName("uses-permission");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < permissions.getLength(); i++) {
            names.add(((Element) permissions.item(i)).getAttribute("android:name"));
        }
        names.sort(String::compareTo);
        assertEquals(List.of(
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO"), names);
    }

    /**
     * The app is called Music Wizard everywhere the phone shows a name.
     *
     * <p>Nothing about this is visible in a code review — the manifest looks
     * right and the name is wrong only on a phone — and the number of ways to
     * get it wrong is the reason this reads a gathered set rather than asking
     * about each in turn. Writing it the other way missed a declaration site
     * three times running.
     */
    @Test
    public void everyScreenThePhoneNamesTheAppByIsCalledMusicWizard() throws Exception {
        // Explicitly, not merely "not overridden": with no application label
        // the launcher falls all the way through to the class name.
        assertEquals("the name every other declaration falls back to",
                "@string/app_name",
                ((Element) manifest().getElementsByTagName("application").item(0))
                        .getAttribute("android:label"));
        // And what it resolves to, since the assertion above would pass just as
        // happily while app_name said "Record".
        assertEquals("Music Wizard", appName());

        for (Map.Entry<String, String> label : nameLabels().entrySet()) {
            assertTrue(label.getKey() + " decides what the phone calls this app,"
                            + " and it says: " + label.getValue(),
                    label.getValue().isEmpty()
                            || "@string/app_name".equals(label.getValue()));
        }
    }

    /**
     * Every label the phone can draw as this app's name, by where it is declared.
     *
     * <p>Four declarations can supply it: a component's own label overrides
     * the application's on the home screen and in the share sheet; an
     * {@code activity-alias} is a component that {@code getElementsByTagName(
     * "activity")} does not return; and an {@code intent-filter}'s label
     * overrides its parent's for exactly the presentation that filter describes
     * — which is what a launcher entry and a share target are.
     *
     * <p>An inner screen may still label itself, and Recordings does. That is a
     * heading rather than a name, and nothing exported inherits it today, so
     * only exported components are gathered here — see #430 for the alias that
     * could change that.
     */
    private Map<String, String> nameLabels() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element entry : exportedEntryPoints()) {
            String name = entry.getAttribute("android:name");
            out.put(name, entry.getAttribute("android:label"));
            NodeList filters = entry.getElementsByTagName("intent-filter");
            for (int i = 0; i < filters.getLength(); i++) {
                out.put(name + " intent-filter " + i,
                        ((Element) filters.item(i)).getAttribute("android:label"));
            }
        }
        return out;
    }

    /**
     * The share target is exported, and it is the only thing besides the
     * launcher that is.
     *
     * <p>Exported means any app on the phone can start it with any text, which
     * is inherent to being a share target. What keeps that safe is that the
     * screen does nothing until a tap — so the thing worth pinning here is that
     * the set of doors into the app has not quietly grown.
     */
    @Test
    public void onlyTheLauncherAndTheShareTargetAreExported() throws Exception {
        List<String> exported = new ArrayList<>();
        for (Element entry : exportedEntryPoints()) {
            exported.add(entry.getAttribute("android:name"));
        }
        exported.sort(String::compareTo);
        assertEquals(List.of(".ImportActivity", ".RecordActivity"), exported);
    }

    /**
     * The exported screens, whatever tag declares them.
     *
     * <p>Both tags, because {@code activity-alias} is the ordinary way to put a
     * second entry on the home screen and carries its own label and filters.
     * Shared by the two tests above so that choice is made once; the
     * inheritance test keeps its own list on purpose, since an alias declares
     * no class of its own.
     */
    private List<Element> exportedEntryPoints() throws Exception {
        Document manifest = manifest();
        List<Element> out = new ArrayList<>();
        for (String tag : List.of("activity", "activity-alias")) {
            NodeList declared = manifest.getElementsByTagName(tag);
            for (int i = 0; i < declared.getLength(); i++) {
                Element element = (Element) declared.item(i);
                if ("true".equals(element.getAttribute("android:exported"))) {
                    out.add(element);
                }
            }
        }
        return out;
    }

    private static Document manifest() throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(MANIFEST));
    }

    /** What {@code app_name} actually says. */
    private static String appName() throws Exception {
        NodeList strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/values/strings.xml"))
                .getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element string = (Element) strings.item(i);
            if ("app_name".equals(string.getAttribute("name"))) {
                return string.getTextContent().trim();
            }
        }
        throw new AssertionError("no app_name in strings.xml");
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
