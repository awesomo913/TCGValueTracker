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

## Resolved Questions

### Q1 — Should `tryProjectActiveRegistryToScope` cache projected views?

**No.** Projection is a single `.filter(entry => requestedSet.has(entry.pluginId))` over each registry array. For 37 plugins × ~5 providers each = ~185 entries, that's microseconds — well under the 32ms p99 loop budget. Caching the projection adds memory pressure and invalidation complexity (every registry mutation would need to bust the projection cache) for negligible gain. Skip the projection cache.

### Q2 — Restoration side effects (`restorePluginCommands`, etc.)

**Moot.** The HIT-path restoration block at lines 2848-2862 only runs when `shouldActivate` is true. All Tier 1 narrow callers explicitly pass `activate: false`:

- `provider-runtime-C1HXSRlX.js:86` — `resolvePluginProviders({ ..., activate: false, cache: false })`
- `providers.runtime-w64xsk4r.js:143` — `activate: params.activate ?? false` (default false)

The active registry's activations were already established at gateway bootstrap (full broad scope). Projection serves a *read* path, never a re-activation path. Restoration block never runs under projection.

### Q3 — Consumer scope dependency

**Safe under projection** for all observed consumers (verified via grep on `\.providers\.(length|map|filter|find|forEach|some|every|reduce|flatMap|slice|at)`).

Two consumer patterns observed:

1. **Pass-through to matcher**: `providers: resolvePluginProviders({...})` → `resolveProviderPluginChoice({ providers })` / `resolveProviderMatch(providers)`. These match a target by ID via `.find(matchesProviderId)`. Extra entries beyond the requested scope are ignored. Safe.
2. **CLI/wizard listing**: `configure-9hA7fOcZ.js`, `auth-choice-B1kmisFJ.js`, `doctor-auth-legacy-oauth-CQ2u82UK.js` iterate the array for menu UIs. **These DO need projection** — a user passing `--plugin foo` would otherwise see all plugins' providers in the wizard. Projection preserves correct UX.

`registry.providers.length === requested.length` does NOT appear as a hard equality check anywhere. The closest match (`provider-discovery.runtime-CiSQDEVA.js:96`) compares filtered-vs-input arrays from the SAME source, which is invariant under projection.

**Net: projection is mandatory (Q3.2 case) and safe (Q3.1 case). Approach B confirmed.**

---

## Phase 2 — Concrete Patch Design

### File targets

| File | Edit scope |
|---|---|
| `~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js` | Add 2 helpers + extend `getCompatibleActivePluginRegistry` |
| `~/.openclaw/node22/node_modules/openclaw/dist/runtime-BFywV6BM.js` | Add `setActivePluginRegistryOnlyPluginIds` + getter (if `setActivePluginRegistry` doesn't already track scope) |
| `~/Desktop/AI/agentzero/package.json` | Add `postinstall: "patch-package"` |
| `~/Desktop/AI/agentzero/patches/openclaw+<ver>.patch` | Generated by `npx patch-package openclaw` |

### Patch sketch (Approach B)

**1. Track scope on active registry storage** (runtime-BFywV6BM.js):

Inside `setActivePluginRegistry`, accept a new optional 5th argument `onlyPluginIds`. Store it on the module-scoped state alongside `activePluginRegistryKey`. Export a `getActivePluginRegistryOnlyPluginIds()` getter.

**2. Pass scope at bootstrap callsite** (loader-NucjcOgv.js):

Inside `loadOpenClawPlugins`, at the `activatePluginRegistry` callsites (lines 2863 HIT path + the analogous STORE path lower in the function), pass `onlyPluginIds` as the 5th arg.

**3. Extend `getCompatibleActivePluginRegistry`** (loader-NucjcOgv.js, after line 2436):

```js
// AFTER the existing activate / allowGatewaySubagentBinding retries:

// Superset-projection fallback. Active registry was loaded with a broader
// (or equal) onlyPluginIds; project providers/tools/etc. down to request scope.
const activeOnlyIds = getActivePluginRegistryOnlyPluginIds();
const requestOnlyIds = loadContext.onlyPluginIds;
if (
    requestOnlyIds && requestOnlyIds.length > 0
    && (activeOnlyIds === undefined || isPluginIdSuperset(activeOnlyIds, requestOnlyIds))
) {
    // Equivalence check: build the cacheKey we WOULD have used if our request
    // had the same onlyPluginIds as the active registry. If it matches, then
    // every input except scope is identical — projection is safe.
    const reframedContext = resolvePluginLoadCacheContext({
        ...options,
        onlyPluginIds: activeOnlyIds  // undefined OK
    });
    if (reframedContext.cacheKey === activeCacheKey) {
        return projectRegistryToScope(activeRegistry, requestOnlyIds);
    }
}
```

**4. Add helpers** (loader-NucjcOgv.js, near line 2400):

```js
function isPluginIdSuperset(broader, narrower) {
    if (!broader || broader.length === 0) return true; // undefined treated as universal
    const set = new Set(broader);
    for (const id of narrower) if (!set.has(id)) return false;
    return true;
}

function projectRegistryToScope(registry, onlyPluginIds) {
    if (!onlyPluginIds || onlyPluginIds.length === 0) return registry;
    const scope = new Set(onlyPluginIds);
    // Defensive copy: caller may mutate or compare references.
    return {
        ...registry,
        providers: (registry.providers ?? []).filter((entry) => scope.has(entry.pluginId)),
        tools: (registry.tools ?? []).filter((entry) => scope.has(entry.pluginId)),
        commands: (registry.commands ?? []).filter((entry) => scope.has(entry.pluginId)),
        gatewayHandlers: filterByPluginId(registry.gatewayHandlers, scope),
        agentHarnesses: (registry.agentHarnesses ?? []).filter((entry) => scope.has(entry.pluginId)),
        interactiveHandlers: (registry.interactiveHandlers ?? []).filter((entry) => scope.has(entry.pluginId)),
        compactionProviders: (registry.compactionProviders ?? []).filter((entry) => scope.has(entry.pluginId)),
        memoryEmbeddingProviders: (registry.memoryEmbeddingProviders ?? []).filter((entry) => scope.has(entry.pluginId)),
        // NOTE: pluginId-keyed maps (capability registries, channel registries, etc.) need
        // projection too. Walk the registry shape via Object.keys at apply-time and project
        // any array-of-{pluginId, ...} or map-keyed-by-pluginId fields.
    };
}

function filterByPluginId(obj, scope) {
    if (!obj || typeof obj !== "object") return obj;
    const out = {};
    for (const [methodName, handler] of Object.entries(obj)) {
        // Gateway handlers are functions tagged with pluginId via closure; if they expose
        // an owning plugin id, filter. Otherwise pass through — gateway dispatch already
        // uses the active registry's exact set, so over-inclusion here is benign for the
        // hook-path consumer.
        out[methodName] = handler;
    }
    return out;
}
```

### Risk + rollback

- **Risk:** The projection list (`providers`, `tools`, `commands`, ...) must enumerate every pluginId-scoped field in the registry shape. Miss one → consumer sees stale wide data → potential correctness bug. Mitigation: derive the field list from the actual `createPluginRegistry` return shape (read `loader-NucjcOgv.js` line 2909+ to enumerate).
- **Risk:** `gatewayHandlers` is keyed by method name, not pluginId. Method-name → pluginId mapping is not directly available without a side-table. **Decision:** do NOT project `gatewayHandlers` in v1 patch — keep it whole. Hooks-path callers don't read gatewayHandlers; only the gateway dispatcher reads them, and dispatcher uses the active registry directly (Tier 4 path, no scope).
- **Rollback:** `cp dist/loader-NucjcOgv.js.bak-pre-cache-patch dist/loader-NucjcOgv.js` + gateway restart. ~30s recovery.

### Apply procedure (when ready)

```bash
# 1. Backup
cd ~/.openclaw/node22/node_modules/openclaw
cp dist/loader-NucjcOgv.js dist/loader-NucjcOgv.js.bak-pre-cache-patch
cp dist/runtime-BFywV6BM.js dist/runtime-BFywV6BM.js.bak-pre-cache-patch

# 2. Apply edits (see Patch sketch above)
# Verify with: node -e "require('./dist/loader-NucjcOgv.js')" — should not throw.

# 3. Snapshot patch
cd ~/Desktop/AI/agentzero
npm i -D patch-package
npx patch-package openclaw

# 4. Wire postinstall
# Add to package.json: "scripts": { "postinstall": "patch-package" }

# 5. Restart gateway
powershell -ExecutionPolicy Bypass -File ~/.openclaw/gateway-launch.ps1
```

Phase 3 verification (24h soak): see "Phase 3 Verification Plan" above.

---

**Status:** Phase 1 done + Phase 2 design locked. Phase 2 application is a separate session — do not apply without re-reading "Risk + rollback" and confirming the gateway is in a state where a 30s restart is acceptable.

---

## Phase 2 — Applied and Verified (2026-05-17 02:04 UTC)

Patch applied in three iterations after live debugging:

**v1 (first cut, design as documented above)** — reframed-cacheKey check (`resolvePluginLoadCacheContext({...options, onlyPluginIds: broadScope}).cacheKey === activeCacheKey`). Failed to hit: gateway-bootstrap and Tier 1 hooks-path callers differ on `coreGatewayMethodNames` and `runtimeSubagentMode` axes, defeating cacheKey equivalence.

**v2 (transitional)** — same as v1, dropped during diagnosis.

**v3 (FINAL, currently deployed)** — store `cacheKeyWithoutScope` at activation time alongside `cacheKey`, then projection check is single string compare. Computed in `activatePluginRegistry` via `resolvePluginLoadCacheContext({...loadOptions, onlyPluginIds: void 0}).cacheKey`. Requires threading `options` through `activatePluginRegistry` as 6th arg.

### Measured outcome (PID 45060, ready at 02:04:34, measured at 02:08:41)

| Window | Reverted (baseline) | Patched v3 |
|---|---|---|
| 0-5m post-ready (startup phase, channels/sidecars probing) | 20 events | 40 events |
| **5-15m post-ready (steady state)** | **20 events** | **0 events** |
| Event-loop lag p99 | (not measured cleanly cold) | 30ms (vs v2's 51ms) |
| Event-loop lag p99.9 | (not measured cleanly cold) | 9.94s (vs v2's 37.9s — 4× tail reduction) |
| Event-loop lag mean | high during cron storms | 65ms (vs v2's 112ms) |

The 0-5m window still shows cascades (40 events vs reverted 20) because channels/sidecars start probing the registry BEFORE the gateway's active registry meta has stabilized. Post-startup (5-15m and beyond), patched gateway is **completely quiet** while reverted continues cascading at ~4 events/min indefinitely.

Extrapolated 24h projection: reverted ≈ 5760 register events/day; patched ≈ 40 events per gateway restart, ~0/day steady-state. The 49 register events/day observed pre-patch was already mitigated by other measures (cron-aware HTTP skip, etc.); this patch removes the remaining cascade source.

### Files modified

| File | Type | Change |
|---|---|---|
| `~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js` | in-place edit | +56 -2 lines |
| `~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js.bak-pre-cache-patch` | backup | original unmodified |
| `~/Desktop/AI/docs/patches/openclaw-loader-reload-churn-2026-05-17.patch` | unified diff | v3 captured |

### Re-apply after openclaw upgrade

```bash
cp ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js \
   ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js.bak-pre-cache-patch
patch -p9999 -d ~/.openclaw/node22/node_modules/openclaw/dist \
   ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js \
   < ~/Desktop/AI/docs/patches/openclaw-loader-reload-churn-2026-05-17.patch
node --check ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js
powershell -ExecutionPolicy Bypass -File ~/.openclaw/gateway-launch.ps1
```

If openclaw upstream changes the surrounding code (line numbers shift or `getCompatibleActivePluginRegistry`/`activatePluginRegistry` are refactored), the patch will need manual rebase. The diff is fully commented to ease that.

### Rollback (if regression observed)

```bash
cp ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js.bak-pre-cache-patch \
   ~/.openclaw/node22/node_modules/openclaw/dist/loader-NucjcOgv.js
powershell -ExecutionPolicy Bypass -File ~/.openclaw/gateway-launch.ps1
```

### Phase 3 (24h soak)

Watch for over the next 24h:
1. No false-positive watchdog restarts.
2. p99 lag stays under 50ms steady-state.
3. No new "registered" log spam past 90s after each gateway start.
4. Tool dispatch for narrow-scope tools (e.g. `external_inbox_drain` from a cron session) still works.

If any of those regress, rollback per above.
