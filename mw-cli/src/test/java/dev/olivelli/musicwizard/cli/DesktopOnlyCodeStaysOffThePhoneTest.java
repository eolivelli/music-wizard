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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
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
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Android seam (#248): the six modules the field-recording app (#236) links
 * must not carry desktop-only machinery onto the phone.
 *
 * <p>A phone has no {@code javax.sound}, cannot load ffsampledsp's or ONNX
 * Runtime's desktop natives, and has no LilyPond to run. The app links these
 * modules for one way in — {@code AudioTranscriber.transcribe(AudioBuffer,
 * Options)} — and one way out — {@code ChordChart.toText(Score)}. Everything
 * else in them is desktop code that happens to ship in the same jars.
 *
 * <p>So the rule is not "these modules never mention a desktop API": three
 * classes exist to use one, and deleting them would cost the desktop its MP3
 * decoding and its MIDI import and export. The rule is that the seam does not
 * <em>reach</em> them, which is checked two ways here because neither alone is
 * enough:
 *
 * <ul>
 *   <li><b>Confinement</b>, from the class files: exactly the named classes
 *       mention each family. This is what fails when a new class reaches for
 *       {@code AudioSystem}, and it is class-granular, so it cannot tell which
 *       method of {@link dev.olivelli.musicwizard.transcribe.AudioTranscriber}
 *       calls {@code AudioDecoder} — both overloads are in one class file.
 *   <li><b>Reachability</b>, by running the seam under a classloader that
 *       refuses all four families. Resolution is what a call triggers, so this
 *       sees past the class file: a decode from the {@code AudioBuffer} overload
 *       fails here with {@code NoClassDefFoundError} while confinement stays
 *       green. Its limit is the other side of the same coin — it sees the calls
 *       this fixture makes, so a branch not taken is a branch not checked.
 * </ul>
 *
 * <p>LilyPond is the one family whose ban needed a decision, since emitting
 * {@code .ly} text is part of the notation module's job. The ban is on
 * <em>invoking</em> the binary — {@code java.lang.Process} and friends — and not
 * on writing the source, so the run below engraves a chart to LilyPond text and
 * then fails to start {@link
 * dev.olivelli.musicwizard.notation.LilyPondRenderer}. Nothing on the app's seam
 * calls {@code toLilyPond} today; it is exercised because a boundary nobody
 * crosses is a boundary nobody has checked.
 *
 * <p>mw-cli is not among the six, deliberately: it is the desktop entry point,
 * it declares ONNX Runtime at runtime scope on purpose (#247), and the app does
 * not link it.
 */
class DesktopOnlyCodeStaysOffThePhoneTest {

    /**
     * A desktop-only API family, in the two forms this test has to recognise it.
     *
     * @param name        what to call it in a failure message
     * @param needle      how it appears in a class file's constant pool. Method
     *                    descriptors are UTF-8 constants like everything else,
     *                    so {@code java/lang/Process} catches a method that only
     *                    returns one as well as a {@code new ProcessBuilder}
     * @param packagePrefix how the phone classloader recognises it by name
     * @param probe       a class of this family that is on this module's own
     *                    test classpath, so that "the phone loader refuses it"
     *                    is a statement about the loader and not about an
     *                    absent jar
     * @param confinedTo  the classes of the six modules that may name it, outer
     *                    class only — a nested class is part of the class that
     *                    declares it
     */
    private record DesktopOnly(
            String name,
            String needle,
            String packagePrefix,
            String probe,
            List<String> confinedTo) {}

    private static final List<DesktopOnly> FAMILIES = List.of(
            new DesktopOnly("javax.sound", "javax/sound/", "javax.sound.",
                    "javax.sound.sampled.AudioSystem",
                    // AudioDecoder decodes files the app never hands it -- it
                    // reads its own WAV from AudioRecord. The other two are the
                    // MIDI import and export, which are desktop features.
                    List.of("AudioDecoder", "MidiExport", "MidiTranscriber")),
            new DesktopOnly("ffsampledsp", "com/tagtraum/ffsampledsp/",
                    "com.tagtraum.ffsampledsp.",
                    "com.tagtraum.ffsampledsp.FFAudioFileReader",
                    // Empty because it is reached through javax.sound's service
                    // loader and never named: mw-audio depends on the artifact,
                    // no source mentions it. Which is why the app can drop the
                    // artifact -- 25 MB of desktop natives -- without a compile
                    // error, and why this list going non-empty matters.
                    List.of()),
            new DesktopOnly("ONNX Runtime", "ai/onnxruntime/", "ai.onnxruntime.",
                    "ai.onnxruntime.OrtEnvironment",
                    List.of()),
            new DesktopOnly("process invocation", "java/lang/Process",
                    "java.lang.Process", "java.lang.ProcessBuilder",
                    List.of("LilyPondRenderer")));

    /**
     * The modules the app links, each with a class that locates its compiled
     * output. Named rather than referenced, so mw-cli need not declare a direct
     * dependency on a module it only uses transitively.
     *
     * <p>mw-ml is not among them and has no sources to scan either, so the check
     * that keeps ONNX Runtime off the phone is the reachability half below,
     * where the runtime is on the classpath and refused by name.
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

    /**
     * Everything the phone classloader may see: the six modules and the FFT
     * library the analysis runs on.
     *
     * <p>An allow-list rather than this test's own classpath, and a shorter one
     * than the app's — the app also serialises its cache with Jackson, which
     * nothing here needs. What it buys is that a stage growing a new dependency
     * fails below with a {@code ClassNotFoundException} naming it, rather than
     * silently adding an artifact to an APK.
     */
    private static final List<String> SEAM_CLASSPATH = List.of(
            "dev.olivelli.musicwizard.core.config.MusicWizardConfig",
            "dev.olivelli.musicwizard.audio.AudioBuffer",
            "dev.olivelli.musicwizard.dsp.BeatTracker",
            "dev.olivelli.musicwizard.transcribe.AudioTranscriber",
            "dev.olivelli.musicwizard.arrange.GridResolution",
            "dev.olivelli.musicwizard.notation.ChordChart",
            "org.jtransforms.fft.FloatFFT_1D",
            "org.visnow.jlargearrays.FloatLargeArray");

    private static final int SAMPLE_RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    @Test
    @DisplayName("only the classes that own a desktop-only API mention it")
    void desktopOnlyApisAreConfinedToTheClassesThatOwnThem() throws Exception {
        Map<String, TreeSet<String>> mentions = new LinkedHashMap<>();
        for (DesktopOnly family : FAMILIES) {
            mentions.put(family.name(), new TreeSet<>());
        }

        for (Map.Entry<String, String> module : APP_FACING_MODULES.entrySet()) {
            Map<String, byte[]> classes = classFilesOfModuleContaining(module.getValue());
            // A scan that found nothing would pass every assertion below.
            assertThat(classes).describedAs("class files of %s", module.getKey()).isNotEmpty();

            for (Map.Entry<String, byte[]> classFile : classes.entrySet()) {
                List<String> constants = utf8ConstantsOf(classFile.getValue());
                for (DesktopOnly family : FAMILIES) {
                    if (constants.stream().anyMatch(text -> text.contains(family.needle()))) {
                        mentions.get(family.name()).add(outerClassNameOf(classFile.getKey()));
                    }
                }
            }
        }

        for (DesktopOnly family : FAMILIES) {
            assertThat(mentions.get(family.name()))
                    .describedAs("classes of the six modules the app links that name %s."
                                    + " A new one here reaches an API the phone does not"
                                    + " have; if it is desktop-only on purpose, say so by"
                                    + " adding it, and check the seam still cannot reach it",
                            family.name())
                    .containsExactlyElementsOf(new TreeSet<>(family.confinedTo()));
        }
    }

    @Test
    @DisplayName("the app's seam runs with every desktop-only API refused")
    void theSeamRunsWithEveryDesktopOnlyApiRefused() throws Exception {
        try (URLClassLoader phone = phoneClassLoader()) {
            Class<?> transcriber =
                    phone.loadClass("dev.olivelli.musicwizard.transcribe.AudioTranscriber");
            // Otherwise this would be the ordinary classpath under another name,
            // and every refusal below would be someone else's problem.
            assertThat(transcriber.getClassLoader())
                    .describedAs("AudioTranscriber must come from the phone-shaped loader")
                    .isSameAs(phone);

            Class<?> audioBuffer = phone.loadClass("dev.olivelli.musicwizard.audio.AudioBuffer");
            Object audio = audioBuffer.getConstructor(float[].class, int.class)
                    .newInstance(fourChordSong(), SAMPLE_RATE);
            Class<?> options =
                    phone.loadClass("dev.olivelli.musicwizard.transcribe.AudioTranscriber$Options");

            Object score = transcriber.getMethod("transcribe", audioBuffer, options)
                    .invoke(transcriber.getConstructor().newInstance(),
                            audio, options.getMethod("defaults").invoke(null));

            Class<?> scoreClass = phone.loadClass("dev.olivelli.musicwizard.core.model.Score");
            Class<?> chart = phone.loadClass("dev.olivelli.musicwizard.notation.ChordChart");

            String text = (String) chart.getMethod("toText", scoreClass).invoke(null, score);
            assertThat(text)
                    .describedAs("the chart the app shows")
                    .contains("Tempo")
                    .doesNotContain("(no chords were found)");
            // The other half of the LilyPond decision: emitting the source is
            // not invoking anything, and the app is free to do it.
            assertThat((String) chart.getMethod("toLilyPond", scoreClass).invoke(null, score))
                    .describedAs("the LilyPond source the app can share out")
                    .contains("\\version");
        }
    }

    @Test
    @DisplayName("the phone-shaped loader refuses what this module's classpath has")
    void theRefusalIsReal() throws Exception {
        try (URLClassLoader phone = phoneClassLoader()) {
            for (DesktopOnly family : FAMILIES) {
                assertThatNoException()
                        .describedAs("%s must be on mw-cli's own test classpath, or refusing"
                                        + " it below proves nothing", family.name())
                        .isThrownBy(() -> Class.forName(family.probe(), false,
                                DesktopOnlyCodeStaysOffThePhoneTest.class.getClassLoader()));
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .describedAs("the phone-shaped loader must refuse %s", family.name())
                        .isThrownBy(() -> Class.forName(family.probe(), false, phone));
            }
        }
    }

    @Test
    @DisplayName("using a desktop-only class under that loader fails, so reaching one would")
    void theConfinedClassesTripTheRefusal(@TempDir Path directory) throws Exception {
        try (URLClassLoader phone = phoneClassLoader()) {
            Path file = Files.writeString(directory.resolve("not-audio.wav"), "no");

            // Loading AudioDecoder is fine -- resolution is lazy, which is why
            // AudioTranscriber can name it and the app still run. A call is what
            // resolves javax.sound, so a call is what this makes: the same call
            // the transcribe path must never grow.
            assertThatDesktopOnly("AudioDecoder.decode", () -> phone.loadClass(
                            "dev.olivelli.musicwizard.audio.AudioDecoder")
                    .getMethod("decode", Path.class).invoke(null, file));

            // The MIDI pair does not even need a call: their signatures are
            // javax.sound-shaped, so asking what methods they have is enough.
            assertThatDesktopOnly("MidiExport", () -> phone.loadClass(
                    "dev.olivelli.musicwizard.notation.MidiExport").getDeclaredMethods());
            assertThatDesktopOnly("MidiTranscriber", () -> phone.loadClass(
                    "dev.olivelli.musicwizard.transcribe.MidiTranscriber").getDeclaredMethods());

            // And the renderer cannot start a process, which is what "no
            // LilyPond invocation" means for a module whose job includes
            // writing LilyPond.
            Class<?> renderer =
                    phone.loadClass("dev.olivelli.musicwizard.notation.LilyPondRenderer");
            Object instance = renderer.getConstructor(Path.class)
                    .newInstance(directory.resolve("lilypond"));
            assertThatDesktopOnly("LilyPondRenderer.version",
                    () -> renderer.getMethod("version").invoke(instance));
        }
    }

    /**
     * Asserts that a use of a desktop-only class fails the way the phone would
     * fail: the class is missing at the moment it is needed.
     *
     * <p>Reflection wraps whatever the callee threw, so the cause is what is
     * asserted on. {@code NoClassDefFoundError} rather than {@code
     * ClassNotFoundException} because it is the JVM, not this test, resolving
     * the name — the loader's refusal is what it is wrapping.
     */
    private static void assertThatDesktopOnly(String use, Use call) {
        Throwable thrown = catchThrowable(call::run);
        Throwable failure = thrown instanceof InvocationTargetException wrapped
                ? wrapped.getCause() : thrown;
        assertThat(failure)
                .describedAs("%s must fail under the phone-shaped loader", use)
                .isInstanceOf(NoClassDefFoundError.class);
    }

    /** A use of a class that is expected to fail. */
    @FunctionalInterface
    private interface Use {
        void run() throws Exception;
    }

    /**
     * A classloader shaped like the app's: the six modules and their permitted
     * libraries, with every desktop-only family refused by name.
     *
     * <p>The parent is the platform loader rather than this test's own, so that
     * the modules are loaded <em>here</em> and every name they resolve comes
     * back through {@link #loadClass}. With the ordinary application loader as
     * parent, delegation would satisfy each reference from the test classpath
     * before this loader ever saw it.
     */
    private static URLClassLoader phoneClassLoader() throws Exception {
        List<URL> code = new ArrayList<>();
        for (String linked : SEAM_CLASSPATH) {
            code.add(codeSourceOf(linked));
        }
        return new URLClassLoader("phone", code.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                for (DesktopOnly family : FAMILIES) {
                    if (name.startsWith(family.packagePrefix())) {
                        throw new ClassNotFoundException(
                                name + " is " + family.name() + ", which an Android build"
                                        + " does not have: " + family.confinedTo()
                                        + " may use it, and the app's seam must not reach them");
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    /**
     * Eight bars of I-V-vi-IV in C at 120 BPM with a click, built on this side
     * of the boundary and handed over as a {@code float[]}: the testkit reads
     * and writes WAV files through {@code javax.sound}, so it is desktop code
     * itself and cannot be loaded over there.
     */
    private static float[] fourChordSong() {
        double[][] bars = {
            SignalFactory.majorTriad(60), // C
            SignalFactory.majorTriad(67), // G
            SignalFactory.minorTriad(57), // Am
            SignalFactory.majorTriad(65), // F
        };
        return SignalFactory.clickTrackWithChords(120.0, bars, 4, 16.0, SAMPLE_RATE);
    }

    /** Where the class named was loaded from: a directory or a jar. */
    private static URL codeSourceOf(String className) throws Exception {
        CodeSource source = Class.forName(className).getProtectionDomain().getCodeSource();
        assertThat(source).describedAs("code source of %s", className).isNotNull();
        assertThat(source.getLocation()).describedAs("code source location of %s", className)
                .isNotNull();
        return source.getLocation();
    }

    /**
     * Every class file of the module holding {@code className}, by its path
     * within that module: a directory in a reactor build, a jar once packaged
     * or resolved from the local repository.
     */
    private static Map<String, byte[]> classFilesOfModuleContaining(String className)
            throws Exception {
        Path path = Path.of(codeSourceOf(className).toURI());
        return Files.isDirectory(path) ? classFilesUnder(path) : classFilesInJar(path);
    }

    private static Map<String, byte[]> classFilesUnder(Path root) throws IOException {
        Map<String, byte[]> found = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    found.put(root.relativize(file).toString(), Files.readAllBytes(file));
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
                try (InputStream in = archive.getInputStream(entry)) {
                    found.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return found;
    }

    /**
     * The outer class a class file belongs to: {@code
     * dev/olivelli/.../MusicXmlExport$Context.class} is part of
     * {@code MusicXmlExport}, and the constant pool of a nested class is the
     * nested class's own.
     */
    private static String outerClassNameOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        name = name.substring(0, name.length() - ".class".length());
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    /**
     * The UTF-8 constants of a class file, which is where every type name, every
     * method descriptor and every string literal in it appears.
     *
     * <p>Reading the pool rather than the source is what makes this see a
     * descriptor: a method returning {@code Process} names it nowhere else.
     * It is coarser in the other direction — a string literal counts — and that
     * is the safe way round for a ban.
     */
    private static List<String> utf8ConstantsOf(byte[] classFile) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(classFile));
        assertThat(data.readInt()).describedAs("class file magic number").isEqualTo(0xCAFEBABE);
        data.readUnsignedShort(); // minor_version
        data.readUnsignedShort(); // major_version

        int poolSize = data.readUnsignedShort();
        List<String> constants = new ArrayList<>();
        for (int index = 1; index < poolSize; index++) {
            int tag = data.readUnsignedByte();
            switch (tag) {
                // CONSTANT_Utf8 is a u2 length and modified UTF-8, which is what
                // DataInputStream.readUTF reads.
                case 1 -> constants.add(data.readUTF());
                case 7, 8, 16, 19, 20 -> data.skipBytes(2);
                case 15 -> data.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> data.skipBytes(4);
                // A long or a double takes two pool entries; the second is unused
                // and must be stepped over, or everything after it is misread.
                case 5, 6 -> {
                    data.skipBytes(8);
                    index++;
                }
                default -> throw new AssertionError("unknown constant pool tag " + tag);
            }
        }
        return constants;
    }
}
