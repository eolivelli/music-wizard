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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule from CLAUDE.md, checked mechanically rather than asserted in prose.
 *
 * <blockquote>LilyPond source is emitted directly from the domain model, not via
 * {@code musicxml2ly}, which is lossy. MusicXML is a parallel export, not the
 * route to PDF.</blockquote>
 *
 * <p>Which means MusicXML is a <em>sibling</em> of {@link StaffNotation} and
 * never a stage before it, and neither export may become a dependency of the
 * path that produces a PDF. That is easy to state, easy to believe, and easy to
 * break by adding one convenient call — the kind of rule that holds until the
 * day somebody needs a measure count and reaches for the exporter that already
 * computes one.
 *
 * <p>So it is checked here. The PR that added the exports claimed the rule held
 * and offered a {@code grep} as evidence; round 1 of review ran the grep and it
 * returned four hits the claim said it would not. The prose was right and the
 * evidence was wrong, which is the worse of the two ways round: a check nobody
 * can run is a check nobody will re-run.
 *
 * <p>This reads the source rather than the bytecode, which is coarser in one
 * direction and finer in the other. Coarser, because a class named in a comment
 * counts; finer, because a compile-time constant that the compiler inlines out
 * of the bytecode still counts here, and that is the case worth catching — an
 * inlined constant is a source dependency that leaves no trace in the class
 * file.
 */
class ExportsAreSiblingsTest {

    /**
     * Everything the PDF path is made of.
     *
     * <p>{@link LilyPondRenderer} runs the binary, {@link StaffNotation} and
     * {@link ChordChart} write what it engraves, and {@link StaffLayout} and
     * the four classes under it make the decisions they write. If any of them
     * reaches an exporter, MusicXML or MIDI is on the route to a PDF.
     */
    private static final List<String> PDF_PATH = List.of(
            "LilyPondRenderer", "StaffNotation", "ChordChart", "StaffLayout",
            "StaffWriter", "MetricSplitter", "LilyPondDuration", "TupletBar", "TupletPlan",
            "NoteValue", "StaffClef");

    /** The two exports that must stay off it. */
    private static final List<String> EXPORTS = List.of("MusicXmlExport", "MidiExport");

    @Test
    @DisplayName("nothing on the route to a PDF mentions either export")
    void thePdfPathCannotReachAnExport() {
        for (String onThePath : PDF_PATH) {
            String source = codeOf(onThePath);
            for (String export : EXPORTS) {
                assertThat(source)
                        .as("%s is on the route to a PDF and must not reach %s:"
                                        + " MusicXML is a parallel export, not the route to PDF",
                                onThePath, export)
                        .doesNotContain(export);
            }
        }
    }

    @Test
    @DisplayName("neither export reaches the other's format")
    void theExportsDoNotReadEachOther() {
        // MusicXmlExport must not build a MIDI sequence and MidiExport must not
        // build a document. They share one thing on purpose -- how finely a
        // quarter note is divided -- and it lives in a third class so that
        // sharing it does not make one an input to the other.
        assertThat(codeOf("MusicXmlExport")).doesNotContain("MidiExport");
        assertThat(codeOf("MidiExport")).doesNotContain("MusicXmlExport");
        // And they do share the one figure, through a third class. Asserted so
        // that a future split of it into two constants fails here rather than
        // producing two files that disagree about where a triplet falls.
        assertThat(codeOf("MusicXmlExport")).contains("ExportGrid");
        assertThat(codeOf("MidiExport")).contains("ExportGrid");
    }

    @Test
    @DisplayName("the MusicXML exporter is the only thing that names proxymusic")
    void onlyOneClassBindsToTheFormat() {
        // A second class importing the bindings would be a second thing able to
        // write MusicXML, which is how the parallel export becomes a stage.
        List<String> binding = sources()
                .filter(path -> code(read(path)).contains("org.audiveris.proxymusic"))
                .map(path -> path.getFileName().toString())
                .toList();
        assertThat(binding).containsExactly("MusicXmlExport.java");
    }

    @Test
    @DisplayName("the source directory this reads is really there")
    void theCheckIsLookingAtSomething() {
        // Every assertion above passes vacuously against an empty directory, so
        // this is what says the directory was found and holds the classes named.
        assertThat(sources().count()).isGreaterThan(10);
        for (String name : Stream.concat(PDF_PATH.stream(), EXPORTS.stream()).toList()) {
            assertThat(codeOf(name))
                    .as("%s", name)
                    .contains("package dev.olivelli.musicwizard.notation;")
                    .containsPattern("(class|interface|record|enum) " + name);
        }
        // And the stripper really strips, or every doesNotContain above passes
        // against an empty string.
        assertThat(codeOf("StaffWriter"))
                .as("a javadoc link is documentation, not a dependency")
                .doesNotContain("MusicXmlExport")
                .contains("interface StaffWriter");
        assertThat(read("StaffWriter")).contains("MusicXmlExport");
    }

    /**
     * The source with its comments removed.
     *
     * <p>A {@code @link} in javadoc is documentation, not a dependency —
     * {@link StaffWriter} names both of its implementations on purpose, and
     * should. What must not appear is a reference the compiler acts on.
     *
     * <p>String and character literals are tracked rather than ignored, which is
     * not fussiness: {@code MusicXmlExport} contains the literal
     * {@code "http://www.musicxml.org/dtds/partwise.dtd"}, and a stripper that
     * treated the {@code //} in it as a line comment would delete the rest of
     * that line and could hide the very reference this is looking for.
     */
    private static String codeOf(String className) {
        return code(read(className));
    }

    private static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(source, i);
                out.append(source, i, end);
                i = end;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                // A newline in its place, so line-oriented reading of the result
                // does not run two statements together.
                out.append('\n');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The index just past a string or character literal beginning at {@code start}. */
    private static int endOfLiteral(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            i++;
            if (c == quote) {
                return i;
            }
        }
        return source.length();
    }

    private static Stream<Path> sources() {
        try {
            return Files.list(sourceDirectory()).filter(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String className) {
        return read(sourceDirectory().resolve(className + ".java"));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * Where this package's sources are.
     *
     * <p>Maven passes {@code -Dbasedir}; outside it the working directory is
     * the fallback. A missing directory is an assertion failure rather than a
     * skip, because a check that quietly stops running is worse than no check.
     */
    private static Path sourceDirectory() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        Path directory = Path.of(Optional.ofNullable(basedir).orElse("."),
                "src", "main", "java", "dev", "olivelli", "musicwizard", "notation");
        assertThat(directory).as("the notation sources are not where this expected them")
                .isDirectory();
        return directory;
    }
}
