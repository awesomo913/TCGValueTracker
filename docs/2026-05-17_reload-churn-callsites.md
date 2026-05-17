# Reload-Churn Callsite Audit — Phase 1

> **Date:** 2026-05-17
> **Author:** Claude (Opus 4.7), continuation of 2026-05-16 marathon
> **Phase status:** Phase 1 complete. Phase 2 (patch) NOT applied. Phase 3 (verify) pending Phase 2.
> **Followup:** decide Approach A vs B; apply via patch-package against `~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js`.

---

## Summary

The dist already has a registry cache (`loader-NucjcOgv.js` lines 2845-2867). It misses ~5x/day because `buildCacheKey` (line 2341) includes `onlyPluginIds` among 16 inputs. The active registry is established at gateway startup with one `onlyPluginIds` set; later callers ask for SUBSETS of that set; each subset has a different `cacheKey`; each miss triggers the full plugin cascade (~12% CPU on `prepareBundledPluginRuntimeDistMirror` + ~14% on the SQLite `migrateLegacyOwnerColumns` migration).

**Decision: Approach B (relaxed-lookup with projection) is the correct fix. Approach A (strip `onlyPluginIds` from the cacheKey) is unsafe and rejected — see "Why not Approach A" below.**

---

## Callsite Inventory

Grep target: `loadOpenClawPlugins|resolveRuntimePluginRegistry` across `~/.openclaw/node22/node_modules/openclaw/dist/*.js`. 21 callsites in 15 files.

### Tier 1 — Cascade-firing callsites (narrow scope, called frequently)

These are the live cascade engines. All four explicitly set `onlyPluginIds` to a SUBSET of the gateway-startup set.

| File | Line | Function | Scope source |
|---|---|---|---|
| `providers.runtime-w64xsk4r.js` | 157 | `resolvePluginProviders` (setup mode) | `resolveSetupProviderPluginLoadState` → `loadState.loadOptions.onlyPluginIds` |
| `providers.runtime-w64xsk4r.js` | 159 | `resolvePluginProviders` (runtime mode) | `resolveRuntimeProviderPluginLoadState` → `providerPluginIds` (L140) |
| `web-provider-runtime-shared-p1TR3lYT.js` | 101 | `resolveSetupWebProviders` | `resolveWebProviderLoadOptions` |
| `web-provider-runtime-shared-p1TR3lYT.js` | 165 | `resolveWebProviders` | `params.onlyPluginIds` direct |

**Evidence the stack-trace from `fs-write-tracer.log` (20:16 on 2026-05-16) terminates at line 159:**

```
loadOpenClawPlugins              loader-NucjcOgv.js       line 3114 (the activation logger inside load)
  ← resolveRuntimePluginRegistry                          line 2443
  ← resolvePluginProviders         providers.runtime-w64xsk4r.js:152 (actually 159 — minified offset)
  ← resolveProviderPluginsForHooks provider-runtime-C1HXSRlX.js:86
  ← normalizeProviderConfigWithPlugin (and chain up via models-config)
```

`resolveProviderPluginsForHooks` has its OWN per-config `cacheBucket` (provider-runtime-C1HXSRlX.js line 75) on TOP of the loader cache. That bucket is keyed by `{ workspaceDir, onlyPluginIds, providerRefs, env, config }`. On bucket miss, it falls through to `resolvePluginProviders` → into the loader cache. Different `providerRefs` per call → different bucket entries → many first-misses per provider → many cascades.

### Tier 2 — Established the active registry (broad scope, called once)

Gateway bootstrap. Establishes the active registry that Tier 1 callers try to project from.

| File | Line | Function | Scope source |
|---|---|---|---|
| `server-plugin-bootstrap-DBjRPg3N.js` | 10999 | `loadGatewayPlugins` | `params.pluginIds ?? resolveGatewayStartupPluginIds(...)` at L10985 |

`resolveGatewayStartupPluginIds` returns the **full set of plugins enabled for gateway startup** — superset of any Tier 1 request.

### Tier 3 — Pass-through callers (scope inherited from upstream)

These accept `loadOptions` from a caller and pass it through. Their scope is whatever upstream set; analysis applies recursively.

| File | Line | Function | Notes |
|---|---|---|---|
| `cli-Dq8MTQrq.js` | 86 | `loadPluginCliEnv` | Explicit narrow when `onlyPluginIds.length > 0` — CLI-time, not gateway-time |
| `metadata-registry-loader-BE2F827u.js` | 6 | `loadPluginMetadataRegistrySnapshot` | `cache: false` — BYPASSES cache, not a cascade source |
| `plugin-install-1vgYMlie.js` | 55 | `loadPluginsForInstall` | One-shot install flow |
| `read-only-IxGh3z7a.js` | 326 | `addSetupChannelPlugins` | Conditional on `setupMissingChannelIds.length > 0` |
| `runtime-plugins-D6tgO0ac.js` | 8 | (agents bridge) | No `onlyPluginIds` passed |
| `runtime-registry-loader-CO1cck7F.js` | 37 | `resolveOrLoadRuntimePluginRegistry` | Pass-through |
| `status-Clo7fOEI.js` | 147 | gateway status | Diagnostic only |
| `tools-CZr3orc0.js` | 30,31 | `resolvePluginToolRegistry` | Pass-through `params.loadOptions` |

### Tier 4 — Undefined-options callers (fast path, no cascade)

These call `resolveRuntimePluginRegistry()` with no args. Hits the `!hasExplicitCompatibilityInputs(options)` shortcut at line 2439 → returns the active registry directly. Zero cache work.

| File | Line | Function |
|---|---|---|
| `capability-provider-runtime-DlsoCSX1.js` | 97 | `resolvePluginCapabilityProvider` |
| `capability-provider-runtime-DlsoCSX1.js` | 110 | `resolvePluginCapabilityProvider` (compat path, conditional on `compatConfig`) |
| `capability-provider-runtime-DlsoCSX1.js` | 116 | `resolvePluginCapabilityProviders` |
| `capability-provider-runtime-DlsoCSX1.js` | 128 | `resolvePluginCapabilityProviders` (compat path) |
| `channel-bootstrap.runtime-CTVP86NU.js` | 21 | channel bootstrap (only on first bootstrap) |
| `memory-runtime-CKatHNRH.js` | 8 | memory runtime init (only when `getMemoryRuntime()` returns null) |
| `web-provider-runtime-shared-p1TR3lYT.js` | 172 | `resolveRuntimeWebProviders` (only when `params.config === void 0`) |
| `runtime-plugins-D6tgO0ac.js` | 8 | (same as Tier 3, also no onlyPluginIds) |
| `tools-CZr3orc0.js` | 30,31 | (same as Tier 3) |

---

## Cache-Key Inputs (loader-NucjcOgv.js line 2341)

`buildCacheKey` ingests:

| # | Input | Stable per gateway? | Required for correctness? |
|---|---|---|---|
| 1 | `workspaceDir` | Yes | Yes — registry is workspace-scoped |
| 2 | `plugins` (trustNormalized: allow/deny/loadPaths) | Yes (per config) | Yes — different trust lists mean different plugin sets |
| 3 | `activationMetadataKey` | Yes (per config) | Yes — different activation contexts mean different registrations |
| 4 | `installs` | Yes (per config) | Yes |
| 5 | `env` | Mostly | Yes — env affects auto-enable, vitest mode, etc. |
| 6 | **`onlyPluginIds`** | **NO — varies per caller** | **Conditional — see below** |
| 7 | `includeSetupOnlyChannelPlugins` | Yes (per call kind) | Yes |
| 8 | `forceSetupOnlyChannelPlugins` | Yes | Yes |
| 9 | `requireSetupEntryForSetupOnlyChannelPlugins` | Yes | Yes |
| 10 | `preferSetupRuntimeForChannelPlugins` | Yes | Yes |
| 11 | `loadModules` | Mostly | Yes — modules load is expensive, callers opt in/out |
| 12 | `installBundledRuntimeDeps` | Yes | Yes |
| 13 | `runtimeSubagentMode` | Yes (per gateway boot) | Yes |
| 14 | `pluginSdkResolution` | Yes | Yes |
| 15 | `coreGatewayMethodNames` (sorted) | Yes (per gateway boot) | Yes |
| 16 | `activate` | Mostly | Yes — activation has side effects |

Only `onlyPluginIds` varies per caller within a single gateway lifecycle. Everything else is stable per (gateway boot, call kind).

---

## Why Not Approach A (strip `onlyPluginIds` from `cacheKey`)

Approach A would make all narrow-scope callers hit the broad active registry cache. **It is unsafe** for two reasons:

1. **`onlyPluginIds` affects what gets registered, not just what gets returned.** During the load path (line 2843: `createPluginIdScopeSet(onlyPluginIds)`), scope filters which plugin modules get `register()`-ed. If we serve a narrow request from a broad cache entry, the consumer receives plugin registrations they never asked to load. Consumers like `resolveProviderRuntimePlugin` filter via `.find((plugin) => matchesProviderId(...))` so they would *survive* this — but other consumers don't, particularly `resolvePluginProviders` at L161 returns `registry.providers.map(...)` unfiltered.

2. **Symmetry violation.** Approach A makes a narrow load and a broad load share a cache slot but produce different registries (one filtered, one not). The cache becomes order-dependent: whoever loads first wins. After a narrow load, a subsequent broad request would get the narrow registry incorrectly.

**A is rejected. Use B.**

## Approach B (relaxed lookup with projection) — Recommended

### Design

Modify `getCompatibleActivePluginRegistry` (line 2404) to add a fallback after the existing exact-match logic:

```js
function getCompatibleActivePluginRegistry(options = {}) {
    const activeRegistry = getActivePluginRegistry() ?? void 0;
    if (!activeRegistry) return;
    if (!hasExplicitCompatibilityInputs(options)) return activeRegistry;
    const activeCacheKey = getActivePluginRegistryKey();
    if (!activeCacheKey) return;
    const loadContext = resolvePluginLoadCacheContext(options);
    if (loadContext.cacheKey === activeCacheKey) return activeRegistry;
    // ... existing activate / allowGatewaySubagentBinding retries ...

    // NEW: superset-projection fallback
    // If the only difference between requested and active is onlyPluginIds,
    // AND active was loaded with a superset (or undefined), project down.
    const projected = tryProjectActiveRegistryToScope({
        active: activeRegistry,
        activeCacheKey,
        activeOnlyPluginIds: getActivePluginRegistryOnlyPluginIds(),  // new accessor
        request: loadContext,
        requestOptions: options
    });
    if (projected) return projected;
}
```

`tryProjectActiveRegistryToScope` returns a registry view with:
- `providers` filtered to `entry.pluginId ∈ requestOnlyPluginIds`
- `commands`, `tools`, `agentHarnesses`, etc. filtered the same way
- Same `compactionProviders`, `memoryCapability`, etc. for pluginIds that pass the filter
- Other registry maps projected similarly

Requirements:
- A new accessor `getActivePluginRegistryOnlyPluginIds()` to track what scope the active registry was loaded with. Persist this in the `setActivePluginRegistry` call.
- Equivalence check: every key OTHER than `onlyPluginIds` in `loadContext` matches the active load context. Easiest implementation: store `activeLoadContext` alongside the active registry, compare cheaply.

### Estimated patch size

~50-80 lines of dist code. Worth doing carefully because the dist is minified; we'll be inserting readable code into a single-letter-variable codebase.

### patch-package application

```bash
cd ~/.openclaw/node22/node_modules/openclaw
cp dist/loader-NucjcOgv.js dist/loader-NucjcOgv.js.bak-pre-cache-patch
# apply Approach B edits
cd ~/Desktop/AI/agentzero
npm i -D patch-package
npx patch-package openclaw
# commits patches/openclaw+<version>.patch
```

Add postinstall to `agentzero/package.json`:
```json
"scripts": { "postinstall": "patch-package" }
```

---

## Phase 3 Verification Plan (unchanged from handoff)

After Phase 2 patch lands:

1. `grep -c '\[self-grader\] registered' /c/tmp/openclaw/openclaw-2026-05-18.log` — should equal `grep -c 'ready (' /c/tmp/openclaw/openclaw-2026-05-18.log` (one register per restart).
2. `curl http://127.0.0.1:18789/api/external/_loop_lag?detail=true` after fresh reset + 15min — p99 < 20ms steady; p100 < 10s.
3. Spot-check narrow-scope tool dispatch (e.g. `external_inbox_drain` from a cron session).
4. Tail `~/.openclaw/watchdog.log` for false-positive restart cascades.

Rollback:
```bash
cp ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js.bak-pre-cache-patch \
   ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js
git revert <patch-package commit hash>
powershell -ExecutionPolicy Bypass -File ~/.openclaw/gateway-launch.ps1
```

---

## Open Questions (for next session)

1. Should `tryProjectActiveRegistryToScope` cache the projected view? On a high-frequency narrow caller, projection itself could become a hotspot. Probably yes — keyed by `(activeCacheKey, sortedScopeSet)`.
2. What happens to `interactiveHandlers`, `compactionProviders`, `memoryEmbeddingProviders` under projection? They were restored from the cache entry in the original HIT path at lines 2849-2862. The projection path needs to either restore the FULL set or filter to the scope. **Restoring the full set is fine because narrow callers care only about the `providers`/`tools` arrays.** Cleaner: do the full restore once and only project the read-only arrays for the return value.
3. Does any caller depend on `registry.providers.length === requested.length`? Search `.length` accesses on `.providers` after `resolvePluginProviders`. If yes, projection is mandatory; if no, we could even skip projection and just return the broad registry. (Searching outside this audit's scope.)

---

**Status:** Phase 1 done. STOP. Do not apply patch without explicit user go-ahead and a fresh review of the design above.
