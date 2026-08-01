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

package dev.olivelli.musicwizard.transcribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The harmony path carries no neural inference, so it must not carry the runtime
 * that would perform it (#247).
 *
 * <p>This module declared a compile dependency on {@code mw-ml} and never wrote
 * a line against it — {@code mw-ml} has no sources at all — so the only thing
 * the declaration ever did was pull ONNX Runtime into the compile closure of
 * everything downstream. On the desktop that is 80-odd megabytes of unused
 * native library. On Android (#236) it is worse than unused: the artifact ships
 * x86-64 and aarch64 <em>desktop</em> natives, and the app links this module for
 * exactly one entry point, {@link AudioTranscriber#transcribe(AudioBuffer,
 * AudioTranscriber.Options)}, which reaches beats, chroma and chords and nothing
 * else.
 *
 * <p>The two tests here are the issue's acceptance criterion, executed rather
 * than argued. One pins the classpath this module is compiled and tested
 * against; the other runs the analysis in it and reads the result. Neither
 * passes against the dependency as it stood before #247.
 *
 * <p>Note what is deliberately <em>not</em> asserted: that no ML runtime exists
 * anywhere. {@code mw-cli} declares {@code mw-ml} directly, at runtime scope,
 * and {@code MlRuntimeStaysOnTheDesktopTest} over there pins that — the desktop
 * tool keeps its provider layer. The rule is about which modules the phone
 * links, not about what the command line ships.
 */
class HarmonyPathNeedsNoMlTest {

    /**
     * A class every ONNX Runtime jar has, used as the probe for the runtime's
     * presence rather than a jar filename, because a filename tells you what is
     * on disk and this needs to know what a classloader can reach.
     */
    private static final String ONNX_PROBE = "ai.onnxruntime.OrtEnvironment";

    private static final int SAMPLE_RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    /**
     * Eight bars of I-V-vi-IV in C at 120 BPM, click track and all.
     *
     * <p>The fixture is a means, not the subject: what matters is that the run
     * reaches every stage the app's entry point reaches — onsets, beat tracking,
     * NNLS chroma, chord estimation — and comes back with something a chord
     * chart could be printed from. What the chords actually are is tier-0
     * territory and belongs to the estimator's own tests, not here.
     */
    private static AudioBuffer fourChordSong() {
        double[][] bars = {
            SignalFactory.majorTriad(60), // C
            SignalFactory.majorTriad(67), // G
            SignalFactory.minorTriad(57), // Am
            SignalFactory.majorTriad(65), // F
        };
        // clickTrackWithChords cycles the bars itself, so sixteen seconds at
        // 120 BPM in four is the four-bar loop twice over.
        return new AudioBuffer(
                SignalFactory.clickTrackWithChords(120.0, bars, 4, 16.0, SAMPLE_RATE),
                SAMPLE_RATE);
    }

    @Test
    @DisplayName("harmony analysis runs to a Score with no ML runtime on the classpath")
    void harmonyAnalysisRunsWithMlAbsent() {
        // The precondition, stated here rather than assumed, so that a future
        // classpath change cannot turn this into a test of nothing in
        // particular: if ONNX Runtime were back, the run below would prove only
        // that the run works, not that it works without it.
        assertThatExceptionOfType(ClassNotFoundException.class)
                .as("ONNX Runtime must not be reachable from mw-transcribe")
                .isThrownBy(() -> Class.forName(ONNX_PROBE));

        List<String> stages = new ArrayList<>();
        Score score = new AudioTranscriber(stages::add).transcribe(fourChordSong(),
                new AudioTranscriber.Options(null, TimeSignature.FOUR_FOUR, null));

        assertThat(score.beatGrid()).as("beats").isPresent();
        assertThat(score.beatGrid().orElseThrow().beats())
                .as("tracked beats over sixteen seconds at 120 BPM")
                .hasSizeGreaterThan(8);
        assertThat(score.chords().isEmpty()).as("chord progression is empty").isFalse();
        assertThat(score.estimatedTempo()).as("tempo").isGreaterThan(0.0);
        // And the stages really ran, rather than the transcriber bailing out
        // early and handing back an empty Score that satisfies the above.
        assertThat(stages).as("progress messages").isNotEmpty();
    }

    @Test
    @DisplayName("this module declares no dependency on mw-ml, at any scope")
    void theModuleDoesNotDeclareMwMl() {
        // The classpath probe above catches the artifact mw-ml drags in today.
        // This catches the declaration itself, which is the thing #247 is
        // about: mw-ml gaining sources of its own, or swapping ONNX Runtime for
        // another inference library, must not quietly put a provider module
        // back into the phone's compile closure.
        assertThat(declaredDependencies())
                .as("direct dependencies of mw-transcribe")
                .isNotEmpty()
                .doesNotContain("mw-ml");
    }

    /** The artifactIds of this module's own {@code <dependencies>}, in pom order. */
    private static List<String> declaredDependencies() {
        try {
            Path pom = Path.of(
                    Optional.ofNullable(System.getProperty("basedir",
                                    System.getProperty("user.dir")))
                            .orElse("."),
                    "pom.xml");
            assertThat(pom).as("this module's pom is not where the test expected it")
                    .isRegularFile();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Our own file, but a parser that will not resolve an external
            // entity is the only kind worth writing.
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            Element project =
                    factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();

            // Only the project's own <dependencies>, not a <dependencyManagement>
            // block's copy of it: managed versions are declarations of what a
            // dependency would be, not of one this module has.
            List<String> declared = new ArrayList<>();
            for (Element dependencies : childrenNamed(project, "dependencies")) {
                for (Element dependency : childrenNamed(dependencies, "dependency")) {
                    for (Element artifactId : childrenNamed(dependency, "artifactId")) {
                        declared.add(artifactId.getTextContent().trim());
                    }
                }
            }
            return declared;
        } catch (Exception e) {
            throw new AssertionError("could not read this module's declared dependencies", e);
        }
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                found.add((Element) child);
            }
        }
        return found;
    }
}
