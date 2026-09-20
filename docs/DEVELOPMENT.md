# Development

## Layout

```
app/                  Manager APK: Compose UI + Vector module merged in! The authority
                      provider, the module resources, and PolicyRepository live here.
core/                 ContactScope + ScopeConstraint domain, SkoposContract; JVM unit tests
runtime-vector/       Android library (com.android.library), no standalone APK — its
                      interceptor and module entry merge into app/. Its manifest is empty.
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
- libxposed artifacts (Maven Central):
  - `io.github.libxposed:api:102.0.0` (`compileOnly`)
  - `io.github.libxposed:annotation:102.0.0` (`compileOnly`)
  - `io.github.libxposed:service:102.0.0` (`implementation` on app; pulls only
    `interface` transitively). The service artifact provides
    `XposedService`, `XposedServiceHelper`, and
    `IXposedService.AUTHORITY_SUFFIX = ".XposedService"`.
- Vector module format: `META-INF/xposed/module.prop` declares
  `targetApiVersion=102`, entries in `META-INF/xposed/java_init.list`
  (`org.a4real.skopos.runtime.XposedEntry`). These resources live under
  `app/src/main/resources`, so the merged manager APK is itself the installable
  module. Authority: `org.a4real.skopos.XposedService`.

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
./gradlew :core:test                     # domain unit tests (JVM, no device)
./gradlew :app:assembleDebug             # manager APK (runtime merged in)
./gradlew :test-app:assembleDebug        # test target APK
./gradlew assembleDebug                  # everything, still one build
```

Do not run `./gradlew build` or `clean` unless a particular problem genuinely
makes it necessary. Artifacts:

- `app/build/outputs/apk/debug/app-debug.apk` (also the Vector module to flash)
- `test-app/build/outputs/apk/debug/test-app-debug.apk`

Machine-local values (`sdk.dir`, `org.gradle.java.home`) live in
`local.properties` and the user-level Gradle configuration — never commit them.

## Git signing

Commits are signed with the developer's existing GPG identity
(`commit.gpgsign` enabled). Do not commit with a synthetic author or unsigned.
Verify after committing:

```bash
git show --show-signature --no-patch HEAD
```

## Domain invariants (core)

- `ContactScope` is sealed: `FULL`, `EMPTY`, `SELECTED(Set<lookupKey>)`.
  `SELECTED` with an empty set is unrepresentable — `ContactScope.from(empty)`
  returns `EMPTY`. Encode: `FULL` / `EMPTY` / `SELECTED\n<key>\n<key>…`.
- Decode of anything unparsable → `EMPTY` (fail closed). Missing policy → `EMPTY`.
- `ScopeFamily`: CONTACTS (`_id`), RAW_CONTACTS/DATA/PHONES/EMAILS/PHONE_LOOKUP
  (`contact_id`). All lineages except `PHONE_LOOKUP` are enforced by selection
  merge; `PHONE_LOOKUP` by cursor post-filter.

## Physical device validation

1. Build and install the app APK (it is the module), install the test app.
2. Grant the test app contacts permissions from the launcher-request dialog.
3. Manager: publish `EMPTY` → test app must show `Contacts visible: 0` and
   `PhoneLookup hits: 0` even with markers seeded and READ granted.
4. Manager: publish `FULL` → both counts return; `Contacts cursor` reports the
   native class, `PhoneLookup cursor` still the wrapper (`PhoneLookupScopeCursor`).
5. Manager: publish `SELECTED` with one marker → only that marker's lookup key
   is visible; all other families agree on exactly that contact.

A Vector/Zygisk environment (Magisk or KernelSU with NeoZygisk) must already be
installed and booted on the device.