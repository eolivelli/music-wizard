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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Android seam: every module the field-recording app (#236) links against
 * must be compiled to a class file version Android's D8 can read.
 *
 * <p>D8 refuses anything above major version 65 — Java 21 — outright, and the
 * reactor built at 25 until #246. The hazard this test exists for is that
 * nothing in a Maven build notices: the reactor compiles, {@code mvn verify}
 * passes, and the failure appears only in a Gradle build most contributors
 * never run, on a workflow that is path-filtered to {@code android/}.
 *
 * <p>The parent pom's {@code requireProperty} rule guards the reactor property.
 * This guards the artifacts, which is a different thing: a module-level
 * {@code <release>} on the compiler plugin, or a plugin default changing under
 * us, moves the class files without moving the property. So the check reads the
 * bytes — every class in the module, not a sampled one, located through the
 * probe class's own code source so it works whether the reactor handed us
 * {@code target/classes} or a packaged jar.
 *
 * <p>mw-cli is the only module that can see the whole closure, which is why an
 * Android test lives here; mw-cli itself is deliberately not in the list, since
 * the app links the libraries and not the command line.
 */
class AndroidBytecodeTargetTest {

    /** Class file major version for Java 21, the newest D8 accepts. */
    private static final int JAVA_21 = 65;

    /**
     * One class per module the app's compile closure reaches, named rather than
     * referenced so the list reads as data and so mw-cli need not declare a
     * direct dependency on modules it only uses transitively.
     *
     * <p>mw-ml is deliberately absent: #247 removed {@code mw-transcribe}'s
     * unused compile dependency on it, so it is no longer in the app's closure
     * at all and D8 never sees it. {@code HarmonyPathNeedsNoMlTest} in
     * mw-transcribe is what keeps it out; if it ever comes back, it belongs in
     * this list.
     */
    private static final Map<String, String> APP_FACING_MODULES = appFacingModules();

    private static Map<String, String> appFacingModules() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("mw-core", "dev.olivelli.musicwizard.core.config.MusicWizardConfig");
        modules.put("mw-audio", "dev.olivelli.musicwizard.audio.AudioBuffer");
        modules.put("mw-dsp", "dev.olivelli.musicwizard.dsp.BeatTracker");
        modules.put("mw-transcribe", "dev.olivelli.musicwizard.transcribe.AudioTranscriber");
        modules.put("mw-arrange", "dev.olivelli.musicwizard.arrange.GridResolution");
        modules.put("mw-notation", "dev.olivelli.musicwizard.notation.ChordChart");
        return modules;
    }

    @Test
    @DisplayName("every class the Android app links against is Java 21 bytecode or older")
    void appFacingModulesAreReadableByD8() throws Exception {
        List<String> tooNew = new ArrayList<>();

        for (Map.Entry<String, String> module : APP_FACING_MODULES.entrySet()) {
            List<ClassFile> classes = classesOfModuleContaining(module.getValue());

            // A walk that finds nothing would pass silently, which is the one
            // way this test could be worse than no test at all.
            assertThat(classes)
                    .describedAs("classes found for %s", module.getKey())
                    .isNotEmpty();

            for (ClassFile candidate : classes) {
                if (candidate.majorVersion() > JAVA_21) {
                    tooNew.add("%s: %s is class file major %d"
                            .formatted(module.getKey(), candidate.name(), candidate.majorVersion()));
                }
            }
        }

        assertThat(tooNew)
                .describedAs(
                        "Android's D8 rejects class file major versions above %d (Java 21). "
                                + "Check maven.compiler.release in the parent pom and any "
                                + "module-level compiler configuration.",
                        JAVA_21)
                .isEmpty();
    }

    private record ClassFile(String name, int majorVersion) {}

    /**
     * Every class file alongside {@code className}, found through the code
     * source that class was loaded from: a directory in a reactor build that has
     * not packaged yet, a jar once it has, and a jar again when the module is
     * resolved from the local repository.
     */
    private static List<ClassFile> classesOfModuleContaining(String className) throws Exception {
        Class<?> probe = Class.forName(className);
        CodeSource source = probe.getProtectionDomain().getCodeSource();
        assertThat(source).describedAs("code source of %s", className).isNotNull();

        URL location = source.getLocation();
        assertThat(location).describedAs("code source location of %s", className).isNotNull();

        Path path = Path.of(location.toURI());
        return Files.isDirectory(path) ? classesUnder(path) : classesInJar(path);
    }

    private static List<ClassFile> classesUnder(Path root) throws IOException {
        List<ClassFile> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    try (InputStream in = Files.newInputStream(file)) {
                        found.add(new ClassFile(root.relativize(file).toString(), majorVersionOf(in)));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static List<ClassFile> classesInJar(Path jar) throws IOException {
        List<ClassFile> found = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                // A multi-release jar's versioned copies are compiled for a
                // newer runtime on purpose and are not what the app links.
                if (entry.getName().startsWith("META-INF/versions/")) {
                    continue;
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    found.add(new ClassFile(entry.getName(), majorVersionOf(in)));
                }
            }
        }
        return found;
    }

    /** Bytes 4..7 of a class file: {@code u2 minor_version; u2 major_version;}. */
    private static int majorVersionOf(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int magic = data.readInt();
        assertThat(magic).describedAs("class file magic number").isEqualTo(0xCAFEBABE);
        data.readUnsignedShort(); // minor_version
        return data.readUnsignedShort();
    }
}
