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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * bytes — every class in the module, not a sampled one, located by {@link
 * AppFacingModules} through the probe class's own code source, so it works
 * whether the reactor handed us {@code target/classes} or a packaged jar.
 *
 * <p>mw-cli is the only module that can see the whole closure, which is why an
 * Android test lives here.
 */
class AndroidBytecodeTargetTest {

    /** Class file major version for Java 21, the newest D8 accepts. */
    private static final int JAVA_21 = 65;

    @Test
    @DisplayName("every class the Android app links against is Java 21 bytecode or older")
    void appFacingModulesAreReadableByD8() throws Exception {
        List<String> tooNew = new ArrayList<>();

        for (Map.Entry<String, String> module : AppFacingModules.PROBE_CLASSES.entrySet()) {
            Map<String, byte[]> classes = AppFacingModules.classFilesOf(module.getValue());

            // A walk that finds nothing would pass silently, which is the one
            // way this test could be worse than no test at all.
            assertThat(classes)
                    .describedAs("classes found for %s", module.getKey())
                    .isNotEmpty();

            for (Map.Entry<String, byte[]> candidate : classes.entrySet()) {
                int major = majorVersionOf(candidate.getValue());
                if (major > JAVA_21) {
                    tooNew.add("%s: %s is class file major %d"
                            .formatted(module.getKey(), candidate.getKey(), major));
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

    /** Bytes 4..7 of a class file: {@code u2 minor_version; u2 major_version;}. */
    private static int majorVersionOf(byte[] classFile) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(classFile));
        int magic = data.readInt();
        assertThat(magic).describedAs("class file magic number").isEqualTo(0xCAFEBABE);
        data.readUnsignedShort(); // minor_version
        return data.readUnsignedShort();
    }
}
