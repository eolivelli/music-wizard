# Why this directory exists

Maven computes `maven.multiModuleProjectDirectory` by walking up from the
*current working directory* — or, under `-f`/`--file`, from the named pom's
directory — looking for a `.mvn/` marker, falling back to the starting
directory when none is found. Two module poms (`mw-cli`, `mw-ml`) point their
test plugins' `XDG_CONFIG_HOME` at
`${maven.multiModuleProjectDirectory}/test-config`; without this marker,
`cd mw-ml && mvn test` resolves that to `mw-ml/test-config`, which does not
exist, and the offline pin silently points nowhere — the exact hole it was
added to close. `OfflinePinTest` in each of those modules fails loudly if this
regresses.

Maven reads only `maven.config`, `jvm.config` and `extensions.xml` from here;
this file is inert and exists so git tracks the directory.
