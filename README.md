# Skopos

Skopos is an Android privacy module that controls which contacts individual apps can
see. It runs on the Vector/libxposed framework: a manager app configures per-app Contact
Scopes, and a small runtime narrows what scoped apps receive from the contacts provider.

## Requirements

- Android 16 / API 36 (arm64 primary target)
- Vector/libxposed environment (Magisk/KernelSU Zygisk, modern libxposed API)
- Root, as required by the Vector environment itself

## What v0.1 does

Each configured app gets one Contact Scope:

- ALL CONTACTS — the app sees contacts normally
- SELECTED — the app sees only chosen contacts (durable across edits)
- NO CONTACTS — the app sees an empty contact dataset
- Not configured (UNSET) — native behavior, no rewriting

## Installation

1. Install the Skopos APK.
2. In Vector, add the target app to the Skopos module scope and approve the prompt.
3. Grant Skopos READ_CONTACTS so the picker and app discovery can list contacts.
4. Open Skopos, pick the app, and choose its Contacts scope.

## Privacy

- No telemetry, no analytics, no crash reporting.
- No runtime network dependency (external links open only when tapped).
- Contact policy is stored per app by the Vector daemon; the manager keeps only UI
  preferences locally, excluded from cloud backup/device transfer.
- Skopos needs package visibility (`QUERY_ALL_PACKAGES`) to identify installed apps
  that declare Contacts access, including non-launchable apps.

## Build

```sh
./gradlew :app:assembleDebug
```

Unit tests:

```sh
./gradlew :core:test :runtime-vector:testDebugUnitTest :app:testDebugUnitTest
```

Release signing is owner-supplied: create a release keystore separately and sign the
release APK outside this repository. No signing material is committed here.

## License

GPLv3. See [LICENSE](LICENSE).
