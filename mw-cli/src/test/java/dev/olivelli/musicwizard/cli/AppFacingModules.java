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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The modules the Android app (#236) links, and their compiled output.
 *
 * <p>Shared because two tests here ask the same question of the same six
 * modules and get different answers from it — {@link AndroidBytecodeTargetTest}
 * reads each class file's version, {@link DesktopOnlyCodeStaysOffThePhoneTest}
 * reads its constant pool. A module missing from a copy of the list fails
 * neither test; it just stops being checked, which is why the two tests read one
 * list. The app's own copy, in {@code android/app/build.gradle}, is a third and
 * is not cross-checked against this one (#268).
 */
final class AppFacingModules {

    private AppFacingModules() {
    }

    /**
     * One class per module the app's compile closure reaches, named rather than
     * referenced so the list reads as data and so mw-cli need not declare a
     * direct dependency on modules it only uses transitively.
     *
     * <p>mw-cli is deliberately absent: the app links the libraries and not the
     * command line. So is mw-ml — #247 removed {@code mw-transcribe}'s unused
     * compile dependency on it, so it is no longer in the app's closure at all;
     * {@code HarmonyPathNeedsNoMlTest} in mw-transcribe is what keeps it out. It
     * has no sources either way, so scanning it would find nothing.
     */
    static final Map<String, String> PROBE_CLASSES = probeClasses();

    private static Map<String, String> probeClasses() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("mw-core", "dev.olivelli.musicwizard.core.config.MusicWizardConfig");
        modules.put("mw-audio", "dev.olivelli.musicwizard.audio.AudioBuffer");
        modules.put("mw-dsp", "dev.olivelli.musicwizard.dsp.BeatTracker");
        modules.put("mw-transcribe", "dev.olivelli.musicwizard.transcribe.AudioTranscriber");
        modules.put("mw-arrange", "dev.olivelli.musicwizard.arrange.GridResolution");
        modules.put("mw-notation", "dev.olivelli.musicwizard.notation.ChordChart");
        return modules;
    }

    /**
     * Where the class named was loaded from: a directory in a reactor build that
     * has not packaged yet, a jar once it has, and a jar again when the module
     * is resolved from the local repository.
     */
    static URL codeSourceOf(String className) throws ClassNotFoundException {
        // Without initialising it: a static initialiser that loads a native
        // library has no business running because a test wanted a file path.
        CodeSource source = Class.forName(className, false, AppFacingModules.class.getClassLoader())
                .getProtectionDomain().getCodeSource();
        assertThat(source).describedAs("code source of %s", className).isNotNull();
        assertThat(source.getLocation()).describedAs("code source location of %s", className)
                .isNotNull();
        return source.getLocation();
    }

    /**
     * Every class file of the module holding {@code probeClassName}, by binary
     * class name.
     *
     * <p>By name rather than by path, so that a caller comparing against a list
     * of classes is comparing like with like whichever shape the module came in
     * — and on whichever platform, since a directory walk yields the host's file
     * separator and a jar always yields {@code /}.
     */
    static Map<String, byte[]> classFilesOf(String probeClassName) throws Exception {
        Path path = Path.of(codeSourceOf(probeClassName).toURI());
        return Files.isDirectory(path) ? classFilesUnder(path) : classFilesInJar(path);
    }

    private static Map<String, byte[]> classFilesUnder(Path root) throws IOException {
        Map<String, byte[]> found = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    found.put(binaryNameOf(root.relativize(file).toString()),
                            Files.readAllBytes(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static Map<String, byte[]> classFilesInJar(Path jar) throws IOException {
        Map<String, byte[]> found = new LinkedHashMap<>();
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
                    found.put(binaryNameOf(entry.getName()), in.readAllBytes());
                }
            }
        }
        return found;
    }

    private static String binaryNameOf(String path) {
        return path.substring(0, path.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }
}
