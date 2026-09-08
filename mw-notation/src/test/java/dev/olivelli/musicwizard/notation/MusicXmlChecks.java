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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * What every generated MusicXML document is checked for, whichever test
 * generated it: validity against the schema, and measures that add up.
 *
 * <p>A golden file generated from the code under test asserts only that the
 * code is unchanged; these are the checks that keep proving something after it
 * has been regenerated.
 */
final class MusicXmlChecks {

    private MusicXmlChecks() {
    }

    private static final String MUSICXML_XSD = "xsd/musicxml.xsd";

    /** The two namespaces the schema imports, and the vendored file for each. */
    static final Map<String, String> IMPORTED = Map.of(
            XMLConstants.XML_NS_URI, "xsd/xml.xsd",
            "http://www.w3.org/1999/xlink", "xsd/xlink.xsd");

    /** Loaded once: parsing the schema per test dominates the run. */
    static final Schema SCHEMA = loadSchema();

    /**
     * Every measure, re-added from the {@code <duration>} elements that were
     * actually written.
     *
     * <p>A note marked {@code <chord/>} sounds with the note before it rather
     * than after it, so it does not advance the measure — which is the one place
     * a naive sum goes wrong, and it goes wrong by exactly a chord's worth per
     * chord.
     *
     * <p>Read from the parsed document rather than from the exporter's own
     * running total, on purpose. The exporter checks itself too, and a check
     * that shares its arithmetic with the thing it is checking proves that the
     * arithmetic is consistent rather than that it is right.
     */
    static void assertMeasuresFillTheirMeter(String label, Document document) {
        int divisions = 0;
        int beats = 0;
        int beatType = 0;
        int measureNumber = 0;
        for (Element measure : elements(document, "measure")) {
            measureNumber++;
            List<Element> declared = elements(measure, "divisions");
            if (!declared.isEmpty()) {
                divisions = Integer.parseInt(text(declared.getFirst()));
            }
            List<Element> time = elements(measure, "time");
            if (!time.isEmpty()) {
                beats = Integer.parseInt(text(child(time.getFirst(), "beats")));
                beatType = Integer.parseInt(text(child(time.getFirst(), "beat-type")));
            }
            assertThat(divisions).as("%s: measure %d has no divisions in force",
                    label, measureNumber).isPositive();
            assertThat(beatType).as("%s: measure %d has no meter in force",
                    label, measureNumber).isPositive();

            int sounded = 0;
            for (Element note : elements(measure, "note")) {
                if (!child(note, "chord").isEmpty()) {
                    continue;
                }
                sounded += Integer.parseInt(text(child(note, "duration")));
            }
            int full = divisions * 4 * beats / beatType;
            boolean implicit = "yes".equals(measure.getAttribute("implicit"));
            if (implicit) {
                // A pickup is short by construction; what must not happen is
                // that it is longer than the bar it opens.
                assertThat(sounded).as("%s: pickup measure %s overflows its %d/%d bar",
                                label, measure.getAttribute("number"), beats, beatType)
                        .isPositive().isLessThan(full);
            } else {
                assertThat(sounded).as("%s: measure %s of %d/%d holds %d divisions",
                                label, measure.getAttribute("number"), beats, beatType, sounded)
                        .isEqualTo(full);
            }
        }
        assertThat(measureNumber).as("%s: no measures were written", label).isPositive();
    }

    /**
     * The document, against the MusicXML 4.0 schema.
     *
     * <p>The parser is told not to fetch the external DTD the {@code <!DOCTYPE>}
     * line names. Without that this test would reach out to musicxml.org, which
     * makes it slow where there is a network and a failure where there is not —
     * and {@code mvn verify} has to run offline.
     */
    static void assertValidMusicXml(String label, String xml) {
        try {
            Validator validator = SCHEMA.newValidator();
            SAXParserFactory parsers = SAXParserFactory.newInstance();
            parsers.setNamespaceAware(true);
            parsers.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            XMLReader reader = parsers.newSAXParser().getXMLReader();
            validator.validate(new SAXSource(reader,
                    new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            ));
        } catch (SAXException e) {
            throw new AssertionError(label + " is not valid MusicXML 4.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The schema, with its two imports resolved to the files beside it.
     *
     * <p>The parser would otherwise follow the networked locations the schema
     * names, which is a download {@code mvn verify} must not depend on; a
     * namespace this does not know is refused rather than fetched.
     */
    static Schema loadSchema() {
        try {
            SchemaFactory factory =
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            DOMImplementationLS dom = (DOMImplementationLS) DOMImplementationRegistry
                    .newInstance().getDOMImplementation("LS");
            factory.setResourceResolver((type, namespace, publicId, systemId, base) -> {
                String vendored = IMPORTED.get(namespace);
                if (vendored == null) {
                    throw new AssertionError("the MusicXML schema imports " + systemId
                            + ", which is not vendored beside it and must not be fetched");
                }
                LSInput input = dom.createLSInput();
                input.setSystemId(resource(vendored).toString());
                return input;
            });
            return factory.newSchema(new StreamSource(resource(MUSICXML_XSD).toString()));
        } catch (SAXException e) {
            throw new AssertionError("could not load the MusicXML schema", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static URL resource(String name) {
        URL url = MusicXmlChecks.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new AssertionError("missing test resource " + name);
        }
        return url;
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXException | ParserConfigurationException e) {
            throw new AssertionError("could not parse the generated MusicXML", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Element> elements(Document document, String name) {
        return elements(document.getDocumentElement(), name);
    }

    static List<Element> elements(Element root, String name) {
        NodeList found = root.getElementsByTagName(name);
        List<Element> elements = new ArrayList<>(found.getLength());
        for (int i = 0; i < found.getLength(); i++) {
            elements.add((Element) found.item(i));
        }
        return elements;
    }

    static Element first(Document document, String name) {
        List<Element> found = elements(document, name);
        assertThat(found).as("no <%s> in the generated MusicXML", name).isNotEmpty();
        return found.getFirst();
    }

    /**
     * The direct children of an element with a given name.
     *
     * <p>Direct children rather than descendants, which matters for
     * {@code <type>}: {@code <metronome>} has one too, and a descendant search
     * from a {@code <note>} would be fine but the same helper used from a
     * measure would not.
     */
    static List<Element> childElements(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    /** The direct children with this name, as a list to assert emptiness on. */
    static List<Element> child(Element parent, String name) {
        return childElements(parent, name);
    }

    /** The one direct child with this name, failing when there is not exactly one. */
    static Element one(Element parent, String name) {
        List<Element> found = childElements(parent, name);
        assertThat(found).as("exactly one <%s>", name).hasSize(1);
        return found.getFirst();
    }

    static String text(Element element) {
        return element.getTextContent().trim();
    }

    static String text(List<Element> single) {
        assertThat(single).hasSize(1);
        return text(single.getFirst());
    }
}
