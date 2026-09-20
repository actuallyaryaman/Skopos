# Architecture

## Modules

```
app/                  Manager — Compose (Material 3 Expressive) UI, the Vector module APK,
                      and the policy channel. Sole policy authority. Installs the module.

core/                 Android-independent domain + contract: ContactScope (FULL/EMPTY/
                      SELECTED), ScopeConstraint (selection rewrite), SkoposContract
                      (package, group/key, marker names). Unit-tested in pure JVM.

runtime-vector/       Android library merged into the manager APK. Vector module entry
                      (XposedEntry) + the ContactsInterceptor. Scoped only to
                      org.a4real.skopos.test. No standalone APK.

test-app/             Scoped test target. Owns marker contacts; never writes policy.
```

`docs/` holds the build/dev notes; the reference repos `../Vector` and
`../Vector_Dev` are read-only technical references.

## Policy channel (manager → runtime)

The manager is the only writer. Delivery travels through libxposed's
remote-preferences machinery:

- The provider authority is `org.a4real.skopos.XposedService` — handed to the
  daemon as `IXposedService.AUTHORITY_SUFFIX = ".XposedService"` appended to the
  manager's application id. The daemon binds it via `getContentProviderExternal`
  and calls `provider.call(authority, SEND_BINDER, ...)` with the binder in the
  extras; both the manager (app) and the runtime (Vector module) then bind the
  same `XposedServiceHelper`/`XposedService`.
- Both sides reach the same key-value store —
  `PreferenceStore.getModulePrefs(org.a4real.skopos, userId, "org.a4real.skopos.test")`
  — through `XposedService.getRemotePreferences(group)`.
- The manager writes scope under group `org.a4real.skopos.test`, key
  `contact_scope`, as `ContactScope.encode()` (see `core`). The injected runtime
  keeps a snapshot and registers `OnServiceListener` (`onServiceBind` / change
  listeners) so scope refreshes propagate while the target process lives.
- A missing or unparsable policy decodes to `EMPTY`: the sensible fail-closed
  default — no state at launch means no data leakage, and the manager's absence
  (it is uninstalled or its scope was never set) still reads correctly.

`data/PolicyRepository` is the manager-side singleton: connect state,
`currentScope()`, `writeScope()`, `markerContacts()`.

## Interception design

Hooks `ContentResolver.query` and `ContentProviderClient.query` in the target
process (reflection + `MethodHookParam`). Every public/resolver route into the
Contacts provider funnels through `ContentProviderClient.query`, which we once
guarded; a re-entrancy token makes the inner layer pass through untouched and
isolates the skopos-owned resolution queries from their own hooks.

Scope is enforced by **selection merge**, not URI rewriting:

- The hook classifies the URI (`ScopeFamily`) and rewrites position 2 of the
  caller's SQL `EntitySet` when non-null:
  `(<original>) AND (<constraint>)`. `SQLiteQueryBuilder` (ContactsProvider2
  `doQuery`) folds caller selection and the builder's WHERE this way for the
  contacts / raw_contacts / data lineages — including direct-id, lookup, and
  filter URIs — so a single rewrite covers them all.
- The `Bundle.EntitySet` form rewrites its `QUERY_ARG_SQL_SELECTION` the same
  law.
- Constraint columns: contacts family key on `_id`; raw_contacts / data /
  phones / emails key on `contact_id`. `EMPTY` yields `col = -1`; `SELECTED`
  yields `(col IN (<ids>))` chunked to ≤ 500 literals; zero resolved ids are
  `col = -1` (fail closed). Literals are integers only — caller `?` arguments
  are untouched, nothing user-controlled reaches SQL.
- **`PhoneLookup` is the one sanctioned exception**: ContactsProvider2 drops
  the caller's selection for that family, so an AND-merge cannot work. The hook
  post-filters with `PhoneLookupScopeCursor`, which wraps the native cursor,
  maps kept rows by `PhoneLookup.CONTACT_ID`, and adds zeros to the projection
  when the caller omitted `CONTACT_ID` (masking the injected column from the
  wrapped API). This is the only invented cursor in v0.1 and every other
  family deliberately never sees one.

No provider is replaced, no app-facing API is proxied, no sentinel values are
ever written into inputs, and no cursor beyond the sole lookup wrapper is
manufactured.

## scope runtime in the target

`ContactsInterceptor` hooks at `onPackageReady` (the target Application has
launched), fetching `currentApplication()` via the hidden
`android.app.ActivityThread` reflection. The probe hook from M0 stays.

## v0.1 scope (what is in)

- `FULL` / `EMPTY` / `SELECTED` first-class, `SELECTED` resolved to ids by the
  manifest prefs `lookupKeys`.
- Manager picker publishing through the Vector daemon; runtime honoring it.

Everything else (other scope types, native hooks, Zygisk modules, servers,
telemetry, live DB-change re-resolution) is intentionally out of scope; the
only current invalidation source is policy change.