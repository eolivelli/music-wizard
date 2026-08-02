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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Android seam (#248): the six modules the field-recording app (#236) links
 * must not carry desktop-only machinery onto the phone.
 *
 * <p>A phone has no {@code javax.sound}, cannot load ffsampledsp's or ONNX
 * Runtime's desktop natives, and has no LilyPond to run. What the run below
 * calls of those modules is {@code Resampler.resample}, {@code
 * AudioTranscriber.transcribe(AudioBuffer, Options)} and {@code
 * ChordChart.toText(Score)}, in the app's order. Everything else in those jars
 * is desktop code that happens to ship beside them.
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
 *       the fixture makes, so a branch not taken is a branch not checked, and
 *       what the app does beyond these three calls is not covered. It caches
 *       its score through {@code ScoreJson}, for one, and Jackson is
 *       deliberately off the classpath below.
 * </ul>
 *
 * <p>LilyPond is the one family whose ban needed a decision, since emitting
 * {@code .ly} text is part of the notation module's job. The ban is on
 * <em>invoking</em> the binary — {@code java.lang.Process} and friends — and not
 * on writing the source. So the run engraves a chart to LilyPond text, and
 * {@link #theConfinedClassesTripTheRefusal} then fails to start {@link
 * dev.olivelli.musicwizard.notation.LilyPondRenderer} under the same loader.
 * Nothing the app calls reaches {@code toLilyPond} today; it is exercised
 * because a boundary nobody crosses is a boundary nobody has checked.
 *
 * <p>mw-cli is not among the six, deliberately: it is the desktop entry point,
 * it declares ONNX Runtime at runtime scope on purpose (#247), and the app does
 * not link it.
 */
class DesktopOnlyCodeStaysOffThePhoneTest {

    /**
     * A desktop-only API family, in the two forms this test has to recognise it.
     *
     * @param name       what to call it in a failure message
     * @param needle     how it appears in a class file's constant pool, in
     *                   internal form. Method descriptors are UTF-8 constants
     *                   like everything else, so {@code java/lang/Process}
     *                   catches a method that only returns one as well as a
     *                   {@code new ProcessBuilder}. The dotted spelling of the
     *                   same prefix is looked for too, since a reflective
     *                   {@code Class.forName} names it that way
     * @param probe      a class of this family, used to prove that the deny-list
     *                   below is what refuses it and that the needle above
     *                   really matches it
     * @param confinedTo the classes of the six modules that may name it, by
     *                   binary name — a simple name would let a class of the
     *                   same name in another module inherit the exemption
     */
    private record DesktopOnly(
            String name,
            String needle,
            String probe,
            List<String> confinedTo) {

        /**
         * The needle in source form, which has two readers: the phone
         * classloader refuses a class whose name starts with it, and {@link
         * #isNamedIn} looks for it in a constant pool beside the internal form.
         * A prefix of a package for three of the four families and of a class
         * name for {@code java.lang.Process}.
         */
        String dottedPrefix() {
            return needle.replace('/', '.');
        }

        boolean isNamedIn(List<String> constants) {
            String dotted = dottedPrefix();
            return constants.stream()
                    .anyMatch(text -> text.contains(needle) || text.contains(dotted));
        }
    }

    private static final List<DesktopOnly> FAMILIES = List.of(
            new DesktopOnly("javax.sound", "javax/sound/",
                    "javax.sound.sampled.AudioSystem",
                    // AudioDecoder decodes files the app never hands it -- it
                    // reads its own WAV from AudioRecord. The other two are the
                    // MIDI import and export, which are desktop features.
                    List.of("dev.olivelli.musicwizard.audio.AudioDecoder",
                            "dev.olivelli.musicwizard.notation.MidiExport",
                            "dev.olivelli.musicwizard.transcribe.MidiTranscriber")),
            new DesktopOnly("ffsampledsp", "com/tagtraum/ffsampledsp/",
                    "com.tagtraum.ffsampledsp.FFAudioFileReader",
                    // Empty because it is reached through javax.sound's service
                    // loader and never named: mw-audio depends on the artifact,
                    // no source mentions it. Which is why the app can drop a jar
                    // of desktop natives without a compile error, and why this
                    // list going non-empty matters.
                    List.of()),
            new DesktopOnly("ONNX Runtime", "ai/onnxruntime/",
                    "ai.onnxruntime.OrtEnvironment",
                    List.of()),
            new DesktopOnly("process invocation", "java/lang/Process",
                    "java.lang.ProcessBuilder",
                    List.of("dev.olivelli.musicwizard.notation.LilyPondRenderer")));

    /**
     * Everything the phone classloader may see: the six modules, from {@link
     * AppFacingModules}, and the FFT library the analysis runs on.
     *
     * <p>An allow-list rather than this test's own classpath, and a shorter one
     * than the app's — the app also serialises its cache with Jackson, which
     * nothing here needs. What it buys is that a stage growing a new dependency
     * fails below with a {@code ClassNotFoundException} naming it, rather than
     * silently adding an artifact to an APK.
     */
    private static final List<String> SEAM_CLASSPATH = seamClasspath();

    private static List<String> seamClasspath() {
        List<String> classpath = new ArrayList<>(AppFacingModules.PROBE_CLASSES.values());
        classpath.add("org.jtransforms.fft.FloatFFT_1D");
        classpath.add("org.visnow.jlargearrays.FloatLargeArray");
        return List.copyOf(classpath);
    }

    /** What a phone records at, and what the analysis runs at. */
    private static final int RECORDED_RATE = 44_100;

    private static final int ANALYSIS_RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    @Test
    @DisplayName("only the classes that own a desktop-only API mention it")
    void desktopOnlyApisAreConfinedToTheClassesThatOwnThem() throws Exception {
        Map<String, TreeSet<String>> mentions = new LinkedHashMap<>();
        for (DesktopOnly family : FAMILIES) {
            mentions.put(family.name(), new TreeSet<>());
        }

        for (Map.Entry<String, String> module : AppFacingModules.PROBE_CLASSES.entrySet()) {
            Map<String, byte[]> classes = AppFacingModules.classFilesOf(module.getValue());
            // A scan that found nothing would pass every assertion below.
            assertThat(classes).describedAs("class files of %s", module.getKey()).isNotEmpty();

            for (Map.Entry<String, byte[]> classFile : classes.entrySet()) {
                List<String> constants = utf8ConstantsOf(classFile.getValue());
                for (DesktopOnly family : FAMILIES) {
                    if (family.isNamedIn(constants)) {
                        mentions.get(family.name()).add(declaringClassOf(classFile.getKey()));
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
    @DisplayName("each needle matches the API it is meant to name")
    void theNeedlesMatchTheApisTheyName() throws Exception {
        // Two of the four families are expected to be named by nothing, and a
        // mistyped needle produces exactly that answer. A class of each family
        // carries its own name in its own constant pool, so its class file is
        // the one input where every needle must hit.
        for (DesktopOnly family : FAMILIES) {
            // Loaded without initialising it: ffsampledsp's static initialiser
            // loads a native library, which is exactly the kind of thing this
            // test is about and has no business running in the fast suite.
            Class<?> probe = Class.forName(family.probe(), false,
                    DesktopOnlyCodeStaysOffThePhoneTest.class.getClassLoader());
            // Asked of the class itself rather than of a classloader, because
            // two of the four live in a JDK module rather than on a classpath.
            // A .class file is readable either way; other resources are not.
            byte[] classFile;
            try (InputStream in = probe.getResourceAsStream(
                    "/" + family.probe().replace('.', '/') + ".class")) {
                assertThat(in).describedAs("class file of %s", family.probe()).isNotNull();
                classFile = in.readAllBytes();
            }
            assertThat(utf8ConstantsOf(classFile))
                    .describedAs("%s must be recognisable by the needle that looks"
                            + " for it, or the classes naming it are missed silently",
                            family.probe())
                    .anyMatch(constant -> constant.contains(family.needle()));
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

            // The app records at 44.1 kHz and resamples, because it reads its
            // own WAV rather than loading AudioDecoder, which is what would
            // otherwise have done it.
            Class<?> resampler = phone.loadClass("dev.olivelli.musicwizard.audio.Resampler");
            float[] analysed = (float[]) resampler
                    .getMethod("resample", float[].class, int.class, int.class)
                    .invoke(null, fourChordSong(RECORDED_RATE), RECORDED_RATE, ANALYSIS_RATE);

            Class<?> audioBuffer = phone.loadClass("dev.olivelli.musicwizard.audio.AudioBuffer");
            Object audio = audioBuffer.getConstructor(float[].class, int.class)
                    .newInstance(analysed, ANALYSIS_RATE);
            assertThat((boolean) audioBuffer.getMethod("isEffectivelySilent").invoke(audio))
                    .describedAs("the resampled fixture the analysis is about to read")
                    .isFalse();

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
    @DisplayName("it is the refusal, and not an absent jar, that keeps each family out")
    void theRefusalIsWhatKeepsThemOut() throws Exception {
        // Two loaders over the same URLs -- the seam's, plus the jars the
        // desktop-only families ship in -- differing only in whether they
        // refuse. Without the second, "ClassNotFoundException" would be the
        // right answer for ffsampledsp and ONNX Runtime whatever the deny-list
        // said, since the allow-listed classpath excludes them anyway, and the
        // two clauses of this test that matter most would assert nothing.
        try (URLClassLoader refusing = seamLoader(true, desktopCodeSources());
                URLClassLoader permissive = seamLoader(false, desktopCodeSources())) {
            for (DesktopOnly family : FAMILIES) {
                assertThatNoException()
                        .describedAs("%s must be reachable without the refusal, or refusing"
                                        + " it proves nothing", family.name())
                        .isThrownBy(() -> Class.forName(family.probe(), false, permissive));
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .describedAs("the refusal must be what stops %s", family.name())
                        .isThrownBy(() -> Class.forName(family.probe(), false, refusing));
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

    /** The loader the seam runs under: the app's classpath, refusing the four. */
    private static URLClassLoader phoneClassLoader() throws Exception {
        return seamLoader(true, List.of());
    }

    /**
     * A classloader over the seam's classpath and whatever else is asked for,
     * optionally refusing the desktop-only families by name.
     *
     * <p>The parent is the platform loader rather than this test's own, so that
     * the modules are loaded <em>here</em> and every name they resolve comes
     * back through {@code loadClass}. With the ordinary application loader as
     * parent, delegation would satisfy each reference from the test classpath
     * before this loader ever saw it. The JDK is still visible through that
     * parent, which is why {@code javax.sound} has to be refused by name and not
     * merely left off the classpath.
     */
    private static URLClassLoader seamLoader(boolean refusing, List<URL> alsoVisible)
            throws Exception {
        List<URL> code = new ArrayList<>();
        for (String linked : SEAM_CLASSPATH) {
            code.add(AppFacingModules.codeSourceOf(linked));
        }
        code.addAll(alsoVisible);
        URL[] urls = code.toArray(URL[]::new);
        if (!refusing) {
            return new URLClassLoader("permissive", urls, ClassLoader.getPlatformClassLoader());
        }
        return new URLClassLoader("phone", urls, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                for (DesktopOnly family : FAMILIES) {
                    if (name.startsWith(family.dottedPrefix())) {
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
     * The jars the desktop-only families ship in, for the loaders that must see
     * them. Two of the four are JDK modules and have no code source; the
     * platform parent supplies those.
     */
    private static List<URL> desktopCodeSources() throws Exception {
        List<URL> jars = new ArrayList<>();
        for (DesktopOnly family : FAMILIES) {
            CodeSource source = Class.forName(family.probe(), false,
                            DesktopOnlyCodeStaysOffThePhoneTest.class.getClassLoader())
                    .getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                jars.add(source.getLocation());
            }
        }
        return jars;
    }

    /**
     * Eight bars of I-V-vi-IV in C at 120 BPM with a click, built on this side
     * of the boundary and handed over as a {@code float[]}: the testkit reads
     * and writes WAV files through {@code javax.sound}, so it is desktop code
     * itself and cannot be loaded over there.
     */
    private static float[] fourChordSong(int sampleRate) {
        double[][] bars = {
            SignalFactory.majorTriad(60), // C
            SignalFactory.majorTriad(67), // G
            SignalFactory.minorTriad(57), // Am
            SignalFactory.majorTriad(65), // F
        };
        return SignalFactory.clickTrackWithChords(120.0, bars, 4, 16.0, sampleRate);
    }

    /**
     * The class a class file belongs to: {@code
     * dev.olivelli.musicwizard.notation.MusicXmlExport$Context} is part of
     * {@code MusicXmlExport}, and the constant pool of a nested class is the
     * nested class's own.
     */
    private static String declaringClassOf(String binaryName) {
        int nested = binaryName.indexOf('$');
        return nested < 0 ? binaryName : binaryName.substring(0, nested);
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
