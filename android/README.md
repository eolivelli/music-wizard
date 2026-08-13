# Music Wizard on a phone

A field-recording instrument for the corpus, not a product: record a take, run
the MW harmony analysis on the device, read the chart as text, and get the take
off the phone so it can join `samples/` or `uncommitted/` on the desktop. Epic
#236, built in #249.

Plain Java, `minSdk` 26, app-private storage, no PDF and no LilyPond.

## Getting a take off the phone

Nothing the microphone records is ever uploaded, and the app holds no
credential. #291 had added a GitHub upload (`INTERNET`, a personal access
token, a release asset and an inbox-issue comment); it was removed in favour of
the share sheet — share to a cloud drive and whoever needs the take fetches it
from there. The `INTERNET` permission came back in #415, for one thing only:
fetching a video the user explicitly shared into the app. What the upload also carried, the player's own account
of what was played, is typed on the result screen and travels in the bundle
(#398): written beside the take on leaving the screen, so it is captured while
it is fresh, which was #291's reason all along.

Two shares, both through `FileProvider`:

- **Share WAV** (library long-press): the audio alone.
- **Share bundle** (library long-press and the result screen): one zip holding
  the recording, the chart as text, the cached `score.json` where one could be
  written, the player's note when one was typed, and an info file with the
  take's duration, recorded date, tempo/meter and the app version.
  `BundleShare` builds it as `<take>.mwz.zip` — searchable by "mwz" in a cloud
  drive — and entries are named by the take, so files pulled out of the zip
  stay identifiable.

**Share chart** on the result screen still sends the chart as plain text, no
file involved — useful when the audio is already on the desktop.

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
secret in CI. The signature is deterministic (#377): `app/debug.keystore` is
committed — a debug keystore is not a secret — and `checkApkSignature` fails
any build signed by anything else, which is what lets one release install as
an update over another. Pushing an `android-v*` tag builds one and attaches it
to the GitHub release.

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

## Importing from YouTube

Share a video from the YouTube app to Music Wizard. The screen states what it
will fetch and does nothing until you tap **Download**; then the audio is
fetched, decoded to a take, and opened on the result screen ready to analyse.

Two things about it are worth knowing before relying on it.

**It is marked, and the marking matters.** An imported take gets a
`<take>.source.txt` beside it, its note is seeded with the link, and the bundle's
`<take>.info.txt` carries `source: youtube`. That is what tells the desktop it is
holding commercial audio, which belongs in `uncommitted/` and never in the
committed corpus — see `docs/phone-to-corpus.md`. A microphone take says
`source: microphone`, so a missing line means an older version of the app rather
than a field recording.

**It will stop working.** The fetch uses an InnerTube client that still serves
plain media URLs; YouTube is progressively enforcing proof-of-origin tokens on
those. When it goes, the app says the build is out of date rather than blaming
the network, and `InnerTubeLiveTest` — `./gradlew testDebugUnitTest --rerun
--tests '*InnerTubeLiveTest*' -Dmw.yt.live=true` — is the one check that
notices, because every other test answers a canned reply.

### Checking it by hand

`AudioImport` drives `MediaExtractor` and `MediaCodec`, which are stubs under
the JVM tests and have no emulator here, so this list is its only coverage. Run
it on a device after any change to the import:

1. Any ordinary music video → take appears, named after the video, and analyses.
   Its WAV header will almost always read 44100: the fetch prefers itag 140
   precisely because that rate decimates exactly to the analysis rate.
2. A video with no itag 140, if you can find one → the take is Opus at 48000.
   That is equally correct — nothing resamples at import — and it is the case
   the decode path is least exercised on, so it is worth hunting for one.
3. An upload old enough to offer no Opus, so the fetch falls back to itag 139
   → the take still decodes. That one is HE-AAC, whose decoders report their
   output format twice, and that is the single path in `AudioImport` no JVM
   test can reach. Checking this against a video that offers itag 249 instead
   proves nothing: 249 is Opus at the same bitrate, and its decoder does not.
4. A playlist URL, a channel URL, and a plain text message → three different
   refusals, **Download** disabled.
5. Cancel mid-download → back to the confirm screen, nothing in the library,
   nothing left in the cache.
6. Airplane mode → a failure that names the network, and **Try again**.
7. Share a second video while one is downloading → the running one survives.
8. **Share a second video after the first has finished** → it is fetched, rather
   than the previous take being reopened.
9. Back from the import screen → returns to YouTube, leaving MW's own screens
   where they were.
10. Long-press the imported take → **Share bundle** → the zip's
    `<take>.info.txt` says `source: youtube` and carries the link.

## What the checks are for

`./gradlew test` runs the JVM unit tests: the WAV header, the recordings
directory, the analysis glue, the background-job lifecycle, the bundle's zip
layout, the YouTube extractor against captured player responses, and the
screen-level facts nothing else checks — among them that the manifest asks for
the microphone and the network and nothing else, and that the share target is
the only door into the app besides the launcher. There are no emulator tests.

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
