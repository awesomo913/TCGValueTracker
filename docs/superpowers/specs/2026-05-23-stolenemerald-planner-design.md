# StolenEmerald Planner — Design Spec

**Date:** 2026-05-23
**Status:** Approved design, pending spec review → writing-plans
**Working name:** StolenEmerald Planner

---

## One-paragraph summary

A beautiful local web application, powered by a Python backend, that reads the
`C:\StolenEmerald` ROM-hack repository **read-only** and turns its scattered
data — wild encounters, maps, NPCs, trainers, scripts, story progression, and
AI development history — into one connected, visual command center. It bridges
the coding/dev side of the project into the artistic/storyboard/planning side:
see where every Pokémon lives on every route (with real sprites), browse maps
and the NPCs/scripts on them, follow the full story timeline from game start
through Battle Frontier and into Kanto, and track the project's status and the
decisions made inside Claude. The app lives in
`C:\Users\computer\Desktop\AI\StolenEmeraldPlanner` and **never modifies the
repo**.

---

## Goals

- Give a single, gorgeous place to *see* the whole project at a glance.
- Answer "where are all my Pokémon right now, and on what route?" visually.
- Browse maps, NPCs, trainers, and the scripts behind them — all cross-linked.
- Show the full story progression (start → Battle Frontier → Kanto) and whether
  the path actually connects.
- Surface project status and the decisions made with Claude over time.
- Bring the artistic vision and the development reality into one view.

## Non-goals (YAGNI, for now)

- **No editing** of the ROM or repo from the app. Viewer/planner only.
- **No live emulator/game hookup.** Static data only.
- **No WebGL tile-walking world.** Possible later upgrade to World Atlas.
- No multi-user / cloud hosting. Single-user, local.

---

## Hard constraints

1. **`C:\StolenEmerald` is READ-ONLY.** The engine opens repo files in read mode
   only. No code path writes, moves, or deletes anything under that folder. All
   cache, exports, and app data live only under
   `Desktop\AI\StolenEmeraldPlanner`. (Enforced per workspace research-only rule.)
2. **Windows packaging rules apply.** Ships as `StolenEmeraldPlanner.exe` in
   `Desktop\My Apps\` with a desktop shortcut and embedded diagnostic logger.
3. **`uv` for all Python package management.** Never `pip`.
4. **Decoupled dev.** Backend (pure JSON-returning functions) built and verified
   first; web UI wired only after backend is correct.

---

## Architecture

Two decoupled halves plus a cache.

### Brain (Python backend)
- Parses the decomp + AI history into clean JSON.
- Serves data over `localhost` (FastAPI or Flask — small, local-only).
- Serves sprite PNGs straight from the repo's `graphics/` tree (read-only).
- Every parser is a pure function returning a dict/list — independently testable.

### Face (web UI)
- HTML/CSS/JS rendered in a native-feeling app window via **pywebview** (so it
  double-click launches and packages to one exe; no separate browser needed).
- Left sidebar navigation with the four rooms. Shared theming layer for the
  "stunning/artistic" look.

### Cache
- First run scans 940 maps + the 1 MB encounters file once → **SQLite** cache in
  the tool's own folder.
- A **Rescan** button re-reads the repo on demand.
- Staleness detected by comparing repo file mtimes against cached scan time;
  the UI shows a "repo changed — rescan?" banner when stale.

```
Desktop\AI\StolenEmeraldPlanner\
  backend\
    engine\            # parsers (one module per data domain)
    server.py          # localhost API + static + sprite serving
    cache.py           # SQLite scan cache + mtime staleness
    diagnostics.py     # embedded logger (workspace rule)
  frontend\
    index.html
    assets\            # css, js, fonts, icons
    rooms\             # one folder per room view
  app.py               # pywebview entry point
  requirements.txt
  build_exe.py
  app_spec.json
```

---

## Data engine modules

Confirmed against the actual repo files (read-only inspection 2026-05-23):

| Module | Reads | Produces |
|---|---|---|
| `encounters.py` | `src/data/wild_encounters.json` | per-map land/water/rock_smash/fishing lists: species, min/max level, rarity % (derived from slot + `encounter_rates`) |
| `maps.py` | `data/layouts/layouts.json` + every `data/maps/*/map.json` | map info, dimensions, `object_events` (NPCs), `warp_events`, `connections`, region section, music, weather |
| `sprites.py` | `graphics/pokemon/<species>/` | resolves & serves `icon.png` (grid view) and `anim_front.png` (detail portrait). Species key → folder is a string transform (`SPECIES_ZIGZAGOON` → `zigzagoon`) |
| `trainers.py` | `src/data/trainers.party` | trainer parties keyed by trainer id |
| `scripts.py` | `data/maps/*/scripts.inc` | script text indexed by script name (e.g. `AbandonedShip_Corridors_1F_EventScript_Charlie`) |
| `story.py` | flag/warp graph + `GOALS_TIMELINE.md` + `STATE.md` | progression graph nodes/edges (**research spike — see Open Questions**) |
| `history.py` | root handoff docs, `STATE.md`, `.remember/`, `sessions/*.jsonl.gz`, Claude decision logs | timeline of decisions + searchable history archive |

**Cross-reference resolution** is the engine's core value: `object_events[].script`
links an NPC to a `scripts.py` entry; `trainer_type`/sight id links to a
`trainers.py` party; `encounters` keys by the same map name as `maps.py`. The
engine builds one index so the UI can hop between them.

---

## The four rooms

### 1. World Atlas (Phase 1)
Pick any map → see every wild Pokémon there with its real `icon.png`, level
range, and rarity %. Filter by encounter method (land/water/rock smash/fishing)
and by type. Click a mon → detail panel with the big `anim_front.png` portrait
and **every other map it appears on**. This is the "where are all my Pokémon"
view and the primary visual payoff.

### 2. Cast & Scripts (Phase 2)
NPCs and trainers per map (from `object_events`). Click an NPC → jump to its
exact script text and, if it's a trainer, its party. Reuses `maps.py`,
`scripts.py`, `trainers.py`, `sprites.py`.

### 3. Command Center (Phase 3)
Project status (current version, goals, open bugs pulled from `logs/`),
**decisions made inside Claude**, a searchable history archive ("what did I ask
about X"), and a "where am I right now" snapshot. Powered by `history.py`.

### 4. Story Timeline (Phase 4)
Events from game start → Battle Frontier → Kanto rendered as a connected graph;
warp/flag links show whether the path actually connects ("does X let me reach
Y"). Most research-heavy room — part auto-parsed flag/warp graph, part your
narrative docs. Built last, after a research spike.

---

## Build phases

Each phase is its own spec→plan→build cycle riding on the shared Foundation.

- **Phase 1 — Foundation + World Atlas** *(this first plan)*: cache engine,
  server, GUI shell + theming, `sprites.py`, `encounters.py`, `maps.py`, Atlas
  room end-to-end, exe packaging.
- **Phase 2 — Cast & Scripts**: `scripts.py`, `trainers.py`, room.
- **Phase 3 — Command Center**: `history.py`, room.
- **Phase 4 — Story Timeline**: `story.py` spike, then room.

---

## Error handling

- Missing/renamed repo file → clear in-UI message naming the file, plus a logged
  error. Never a silent empty list.
- Missing sprite for a species → render a labeled placeholder, log a `DECISION
  missing_sprite=<species>` line; do not crash the grid.
- Repo path not found at startup → first-run dialog asks for the StolenEmerald
  path; stored in app config under `Desktop\AI`.
- Malformed JSON in a map file → skip that map, surface it in a "could not parse"
  list in the UI, log full context. Other maps still load.
- All boundaries (file reads, JSON parse, sprite resolve) validate and log per
  the workspace silent-failure rule.

## Testing

- Each engine parser gets unit tests against real repo fixtures (a few sampled
  maps + a known encounter slice) — pure functions make this clean.
- Cache staleness logic tested with synthetic mtimes.
- Backend API returns verified JSON shapes before any frontend wiring (decoupled
  dev rule; verify with `run_and_catch.py`).

---

## Open questions / risks

1. **Story Timeline data source (Phase 4)** is not a clean file — it's emergent
   from scripts + flags + your narrative docs. Needs a research spike before
   that phase is specced. Does not block Phases 1–3.
2. **`anim_front.png` may be a multi-frame sheet** — detail view may need to crop
   the first frame. Cheap to handle in `sprites.py`; confirm during Phase 1.
3. **Encounter rarity %** is derived from slot position × `encounter_rates`
   table; confirm the per-map encounter list shape (slots array) during Phase 1
   build.

---

## Naming source of truth

App name: **StolenEmerald Planner** → exe `StolenEmeraldPlanner.exe`,
folder `Desktop\AI\StolenEmeraldPlanner`.
