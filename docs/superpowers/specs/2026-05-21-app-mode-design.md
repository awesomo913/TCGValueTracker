# CursorCoder "App Mode" — Design Spec

**Date:** 2026-05-21
**Designer of record:** the user. AI implements to spec.
**Status:** approved design, pre-implementation.
**Parent project:** CursorCoder (`C:\Users\computer\Desktop\AI\CursorCoder`).

## Goal

Add a reusable **"App Mode"** preset to CursorCoder that tunes every run toward
**complete, Google-Play-ready, viral, ad-monetized Android apps** built to the
polish bar of the user's shipped Typing Speed Test — not lazy output. Selectable
from the existing preset dropdown; reusable forever.

## Approved decisions

- **Platform:** Android / Google Play first, but rubric phrased so an iOS /
  cross-platform sibling preset can be added later (no iOS work now).
- **Ship gate:** HARD — an app is not "done" until it **builds** (gradle) AND
  satisfies the quality-bar checklist. Strict acceptance.
- **Sequence:** build App Mode first, verify, then the user supplies the first
  app idea to run it on.
- **Loop:** Agent mode + single-idea deep loop + perfection loop (iterate one
  app to high quality), matching the user's earlier run choices.

## Quality bar (distilled from the Typing Speed Test audit, 2026-05-21)

Concrete, ship-ready micro-app checklist the mode must drive toward:
- **Build/Play:** release signing via `keystore.properties`; R8 minification +
  resource shrinking; minimal permissions; versionCode/Name; AAB; real adaptive
  launcher icon (no placeholder); hosted privacy policy + in-app link; store
  metadata (short/full desc, IARC rating, data-safety).
- **Monetization:** AdMob interstitial placed after the core action (e.g. after
  a round / on result screen) so even a single download yields an ad play;
  test vs release ad-IDs split by build variant; preload + retry; triple-guard
  show() safety (no self-clicks, no crash on finishing activity).
- **Design polish ("just so"):** cohesive 30-40+ named color system; 3-stop
  gradients; Material3 theme; custom typography w/ letter-spacing; drop shadows
  + elevation; 4dp spacing grid; Konfetti / particle celebrations; spring-physics
  animations; count-up number animations; optional sound; responsive autoSize
  layouts. Intentional, not default-Android.
- **Completeness:** tight single-session loop (onboarding-light → core loop →
  results → retention hook); persistence (SharedPreferences/DataStore); proper
  lifecycle cleanup; accessibility (color+icon feedback, TalkBack, content
  descriptions); unit + a couple E2E tests.
- **Virality/retention:** high-score persistence; share-score button; streaks /
  daily challenge; celebration tiers (milestone / high-score / perfect); rate-app
  prompt.
- **Concept fit:** simple, repeatable, single-session, broad-appeal category.

## Architecture (how it plugs in)

App Mode is data + directives layered on CursorCoder's existing preset/focus
system. No new GUI plumbing — presets and build targets already surface in the
broadcast tab.

### 1. New preset — `coding_profiles.py:PRESETS`

```
"viral_app": {
  "label": "Viral Play-Store App (high polish)",
  "description": "Complete, Google-Play-ready, ad-monetized Android apps at ship polish.",
  "build_target": "Android App",          # existing target, framing tightened
  "focuses": [ ... curated list incl. the 4 new focuses below ... ],
  "expand_on_stagnation": False,           # depth on one app, not scope creep
  "perfection_loop": True,                 # iterate to high quality
  "acceptance_commands": [ <gradle build, see Ship Gate> ],
  "strict_acceptance": True,               # HARD gate
  "promote_only_passing": True,
}
```

Curated focus list = the 4 new focuses + best existing ones:
`design_polish, ad_monetization, play_store_readiness, virality_retention,
solid_functional, beautiful_gui, animations_transitions, accessibility,
onboarding_firstrun, save_system, state_management, performance, security,
test_suite, review_grade, packaging`.

### 2. Four new improvement focuses — `broadcast.py:IMPROVEMENT_FOCUSES`

Each is a dict matching the existing focus shape (same keys as e.g.
`beautiful_gui`), with a directive body that embeds the relevant slice of the
quality bar above. New keys:
- `ad_monetization`
- `play_store_readiness`
- `design_polish`
- `virality_retention`

Add the four keys to `FOCUS_ORDER` and to the `"Android App"` entry in
`BUILD_TARGET_PRESETS` so they're available there too. Directive text must be
phrased Android-first but not Android-exclusive where reasonable (so an iOS
sibling can reuse the intent). Each directive ends by pointing at the bundled
checklist (below).

### 3. Bundled quality-bar reference — auto-attached

Create `CursorCoder/docs/app_mode/playstore_quality_bar.md` containing the full
checklist above. When the `viral_app` preset is active, CursorCoder attaches this
file to every prompt (reuse the existing reference-attach mechanism that the
`openclaw_reference_bootstrap` path uses — a per-preset "attach these md files"
hook). This is the lever that makes Cursor *see the exact bar every iteration*.

### 4. Ship gate (HARD)

`strict_acceptance=True` + `acceptance_commands` that verify the app compiles:
- Preferred: a gradle build of the harvested project, e.g.
  `gradlew :app:assembleDebug` (or `assembleRelease` if signing present), run in
  the project dir.
- **Toolchain caveat:** real compilation needs the Android SDK + gradle on the
  host. The acceptance runner must:
  1. If `gradlew`/`gradle` + an Android SDK are detected → run the gradle build;
     pass only on success.
  2. If NOT detected → degrade to a **structure gate** (assert presence of
     `AndroidManifest.xml`, `build.gradle(.kts)` with applicationId + signing
     stanza, `res/mipmap*` launcher icon, AdMob app-id in manifest, a privacy
     policy reference) and clearly LOG that compilation was not verified
     (never silently pass nothing).
  Implement this as a small acceptance helper so the gate is honest about which
  level it ran.

## Out of scope (YAGNI)

- iOS build (only rubric phrasing keeps the door open).
- New GUI widgets (preset + target already surface).
- Auto-publishing to Play (we build/verify; the user submits).
- Generating store screenshots/feature graphics (checklist names them; creation
  is a later enhancement).

## Testing

- Unit: `viral_app` preset resolves via `effective_bundle("viral_app")` with the
  4 new focuses present, `perfection_loop=True`, `strict_acceptance=True`.
- Unit: each new focus key exists in `IMPROVEMENT_FOCUSES`, is in `FOCUS_ORDER`,
  and is included in the `"Android App"` target bundle.
- Unit: the acceptance helper picks gradle-vs-structure correctly (monkeypatch a
  "gradle present/absent" probe; assert which path it returns).
- Manual: select the preset in the GUI; confirm it loads the bundle + attaches
  the checklist md.

## Proof obligations

Update CursorCoder PROOF.md + BREAKDOWN dev log with the App Mode addition.
