# Skopos

A modern Android privacy-scoping framework for rooted devices.

Version 0.1 will implement **Contact Scopes only** — letting a target application
keep its Contacts permission while seeing all real contacts, a selected subset, or
an intentionally empty contact dataset. Contact scopes are **not** implemented in
this build.

## Current milestone: M0 (bootstrap)

M0 establishes the project foundation and proves the runtime integration only:

- manager application foundation (Jetpack Compose, Material 3 Expressive, dynamic colors);
- a minimal Vector module/runtime spike;
- a dedicated test application the runtime hooks.

**This M0 build is a runtime/bootstrap spike. It does not scope, filter, or
intercept anything.** Do not treat it as a working privacy product.

## Platform focus

- Android 16 (API 36, `minSdk`/`targetSdk`) on arm64-v8a; `compileSdk` 37 so the
  current stable androidx releases can link
  (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md));
- primary runtime/framework: [Vector](https://github.com/JingMatrix/Vector)
  (Magisk/KernelSU Zygisk environment, modern libxposed API 102);
- deliberately no Magisk-, KernelSU-, or APatch-specific application logic.

## Contact Scope semantics

The eventual scope model distinguishes three deliberately distinct states:

```
FULL      all real contacts
SELECTED  a chosen subset
EMPTY     enabled but exposing zero contacts
```

`EMPTY` is intentional user choice — it is never merely permission-denied,
disabled, or an accidental empty selection, and it is not represented as
`Selected(emptySet())`. The model is deferred to the contact-scope milestone and
does not exist in this M0 build.

## Modules

| Module            | Package                     | Role                                          |
|-------------------|-----------------------------|-----------------------------------------------|
| `app`             | `org.a4real.skopos`         | Manager application (M0 UI + theming)         |
| `core`            | `org.a4real.skopos.core`    | Probe contract shared by runtime and test app |
| `runtime-vector`  | `org.a4real.skopos.runtime` | Vector module APK (M0 hook spike)             |
| `test-app`        | `org.a4real.skopos.test`    | Scoped test target                            |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).