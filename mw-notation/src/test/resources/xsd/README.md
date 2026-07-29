# Why these two files are here

`MusicXmlExportTest` validates every document it generates against the real
MusicXML 4.0 schema, which proxymusic ships inside its own jar at
`META-INF/jaxb/xsd/musicxml.xsd`. That is the point: a golden file generated
from the code under test asserts only that the code is unchanged, and the schema
is the one authority in the room that was not written by this project.

The schema will not load on its own. It references `xml:lang`, `xml:space` and
six `xlink:` attributes, and its own `<xs:import>` declarations for those two
namespaces are commented out in the shipped copy — with a note saying the
networked versions moved. Xerces therefore reports

    src-resolve: Cannot resolve the name 'xml:lang' to a(n) 'attribute declaration'

and loads nothing at all. Supplying the two namespaces alongside it fixes that
without touching the jar.

These are minimal declarations of exactly the attributes MusicXML references,
matching the W3C definitions of `http://www.w3.org/XML/1998/namespace` and
`http://www.w3.org/1999/xlink`. They are deliberately not fetched at build time:
`mvn verify` has to stay offline, and a validation that silently downloads its
schema is a validation that silently stops happening behind a proxy.

Nothing this project emits uses either namespace, so what they contribute to the
check is only that the schema loads. If a future export does start writing an
`xlink:href`, these are the definitions it will be checked against.
