# Music Wizard on a phone

A field-recording instrument for the corpus, not a product: record a take, run
the MW harmony analysis on the device, read the chart as text, and share the WAV
out so it can join `samples/` or `uncommitted/` on the desktop. Epic #236, built
in #249.

Plain Java, `minSdk` 26, `RECORD_AUDIO` as the only permission, app-private
storage, no network, no PDF and no LilyPond.

## Building

The Maven reactor does not build this. The app consumes the six shared modules
as jars from the local Maven repository, so they have to be installed first:

```sh
mvn -DskipTests install          # from the repository root, on a JDK 25
cd android
./gradlew test assembleDebug     # on a JDK 21
```

Two JDKs, and they are not interchangeable. The reactor's enforcer requires a
build JDK of 25 or newer; AGP does not run on one. `.github/workflows/android.yml`
does exactly this with two `setup-java` steps, which is why the Gradle build must
never be handed the JDK that built the jars.

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. It is a debug
build on purpose: debug-signed APKs install on a real device with no signing
secret in CI. Pushing an `android-v*` tag builds one and attaches it to the
GitHub release.

`local.properties` (pointing at your Android SDK) is generated per machine and
is not committed. The Gradle wrapper jar *is* committed, against the repository
root's `*.jar` rule, because `./gradlew` cannot bootstrap without it.

### One Maven repository per worktree

This project's convention is one local Maven repository per git worktree, and
Gradle needs telling about it as well as Maven — `mavenLocal()` honours the
`maven.repo.local` system property:

```sh
export MAVEN_ARGS="-Dmaven.repo.local=$PWD/.m2"
mvn $MAVEN_ARGS -DskipTests install
cd android && ./gradlew -Dmaven.repo.local="$OLDPWD/.m2" test assembleDebug
```

Without the flag on the Gradle side, Gradle silently resolves the shared
`~/.m2/repository`, which also holds `0.1.0-SNAPSHOT` artifacts — from whatever
branch happened to be built there last. That is the failure the convention
exists to prevent, and it is silent.

## What the checks are for

`./gradlew test` runs the JVM unit tests: the WAV header, the recordings
directory, the analysis glue, and the background-job lifecycle. There are no
emulator tests.

`checkDexedApiLevel` runs after `assembleDebug` and fails the build if the dex
still calls a JDK method Android does not have at `minSdk`. It exists because
two of them — `Math.clamp` and `Stream.toList` — sit on the path every analysis
takes and are handled by the toolchain rather than by any source file here, so a
change of AGP version or of `coreLibraryDesugaringEnabled` would remove them with
no compile error anywhere and kill the app on its first recording.

## Known limitation

`score.json` is not written on Android below 35: Jackson cannot serialize the
model's records once D8 has desugared them. The analysis, the chart and both
share paths work; a take is simply analysed again each time it is opened, and
the screen says so. #254 fixes it in `mw-core`, and this app needs no change
when it lands.
