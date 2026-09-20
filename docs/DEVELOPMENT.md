# Development

## Layout

```
app/                  Manager application (Compose, Material 3 Expressive, dynamic colors)
core/                 Android-independent contract constants shared by runtime + test app
runtime-vector/       Vector module APK (modern libxposed entry, M0 probe hook)
test-app/             Scoped test target owned by this project
docs/                 This directory
```

The reference repositories `../Vector` and `../Vector_Dev` are read-only
technical references. Inspect them freely; never modify them.

## Toolchain

- Gradle 9.7.0 (wrapper), AGP 9.3.1, Kotlin 2.4.10 (AGP 9 built-in Kotlin —
  only the Compose plugin is applied in modules), Java 21.
- `compileSdk` = 37: the current stable androidx releases
  (`androidx.compose.ui:1.12`, `androidx.core:core-ktx:1.19`) require compiling
  against API 37 even though the app targets Android 16. `targetSdk`/`minSdk` =
  36 (Android 16 only; no legacy compatibility code).
- Reference commits recorded at M0:
  - Vector repo HEAD: `efb82883071643ca16128ecd588be7c40c1e45e6`
  - libxposed module API pinned by Vector
    (`xposed/libxposed`, = `libxposed/api.git`): `39cac0845771547c9c67a3e3ce255af110a54a0e`
  - libxposed service API pinned by Vector
    (`services/libxposed`, = `libxposed/service.git`): `3318940876192e29cf6ab07637e899e22a87ebf0`
  - Module APK compiled against `io.github.libxposed:api:102.0.0` (Maven Central).

## Memory/worker policy

Chosen for a 16 GiB Linux build host with a typical desktop workload: reliability
over speed. Kept in `gradle.properties`:

- `org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m`
- `kotlin.daemon.jvmargs=-Xmx1024m`
- `org.gradle.workers.max=2`
- `org.gradle.parallel=false`
- `org.gradle.caching=true`
- configuration cache off (correctness first)

The Gradle and Kotlin daemons together stay under ~2.5 GiB, leaving the desktop,
editor, filesystem cache, and device tooling their headroom.

## Safe build commands

Always run a single Gradle invocation at a time. Use the narrowest task that
proves what you need.

```bash
./gradlew :core:testDebugUnitTest         # contract unit test
./gradlew :app:assembleDebug              # manager APK
./gradlew :test-app:assembleDebug         # test target APK
./gradlew :runtime-vector:assembleDebug   # Vector module APK
./gradlew assembleDebug                   # all three APKs, still one build
```

Do not run `./gradlew build` or `clean` unless a particular problem genuinely
makes it necessary. Artifacts:

- `app/build/outputs/apk/debug/app-debug.apk`
- `test-app/build/outputs/apk/debug/test-app-debug.apk`
- `runtime-vector/build/outputs/apk/debug/runtime-vector-debug.apk`

Machine-local values (`sdk.dir`, `org.gradle.java.home`) live in
`local.properties` and the user-level Gradle configuration — never commit them.

## Git signing

Commits are signed with the developer's existing GPG identity
(`commit.gpgsign` enabled). Do not commit with a synthetic author or unsigned.
Verify after committing:

```bash
git show --show-signature --no-patch HEAD
```

## Vector module format (why this one)

Vector's daemon (`FileSystem.loadModule`) selects a module as **modern** when
`META-INF/xposed/module.prop` declares `targetApiVersion >= 101` (API 100 is
refused), reading entry classes from `META-INF/xposed/java_init.list`. The
module APK in this repo follows that modern layout, so it loads on the checked-out
Vector reference without legacy `assets/xposed_init` compat.

## Device testing

Physical testing is manual; the steps live in the M0 report. A Vector/Zygisk
environment (Magisk or KernelSU with NeoZygisk) must already be installed and
booted on the device.