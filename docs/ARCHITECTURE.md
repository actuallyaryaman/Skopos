# Architecture

## Current shape (M0)

Four Gradle modules, deliberately small.

```
app/                  Manager UI. One screen: title, runtime status placeholder,
                      theme selector (System/Light/Dark, persisted via DataStore).
                      Material 3 Expressive theme, dynamic colors where available,
                      static Material scheme fallback.

core/                 SkoposContract — package/class/method and marker strings the
                      runtime and the test app must agree on. Nothing speculative.

runtime-vector/       Vector module. Modern libxposed entry (XposedModule), scoped
                      only to org.a4real.skopos.test. Installs one harmless,
                      reversible hook on the test app's probe method.

test-app/             Test target. Exposes SkoposProbe.value() ("Skopos test:
                      original") that the runtime rewrites to "Skopos test: hooked"
                      when the module is loaded and scoped.
```

The runtime hooks only Java-reflection targets; it does not replace
ContactsProvider, does not proxy app-facing APIs, and does not manufacture
MatrixCursor results. The future scoping design keeps target applications on
normal Android APIs receiving a constrained native-like dataset.

## v0.1 direction

Contact Scopes only, over this vector:

- a scope policy distinguishing `FULL` / `SELECTED` / `EMPTY` as first-class states;
- the runtime continuing to rely on the hooking/runtime abstraction (Vector),
  never on a specific root-manager application.

Everything else (other scope types, native hooks, Zygisk modules, servers,
telemetry) is intentionally out of scope.