# The MusicXML 4.0 schema, vendored

`MusicXmlExportTest` validates every document it generates against the real
MusicXML 4.0 schema. That is the point: a golden file generated from the code
under test asserts only that the code is unchanged, and the schema is the one
authority in the room that was not written by this project.

The three files are the W3C Music Notation Community Group's, taken unchanged
from the `v4.0` tag of https://github.com/w3c/musicxml (`schema/musicxml.xsd`,
`schema/xml.xsd`, `schema/xlink.xsd`). `musicxml.xsd` is published under the
W3C Community Final Specification Agreement; `xml.xsd` is the W3C's own
namespace schema. They are test resources: nothing ships them, and `NOTICE`
does not list them.

`musicxml.xsd` imports the other two by their networked locations under
`http://www.musicxml.org/xsd/`. `mvn verify` has to stay offline, and a
validation that silently downloads its schema is a validation that silently
stops happening behind a proxy — so the test installs a resource resolver that
maps exactly those two namespaces onto the files beside it and refuses anything
else, rather than letting the parser follow the URLs.
