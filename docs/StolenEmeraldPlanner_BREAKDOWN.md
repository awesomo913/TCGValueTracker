# StolenEmerald Planner — BREAKDOWN

## What it is
A local desktop app (Python backend + web frontend in a pywebview window) that
reads the `C:\StolenEmerald` decomp **read-only** and visualizes it. Phase 1 =
Foundation + World Atlas.

## Why it exists
The project's data (encounters, maps, sprites, scripts, AI history) is spread
across hundreds of files in 5+ formats. This tool unifies it into one connected,
visual command center, bridging the dev side into the artistic/planning side.

## Architecture
- **Brain** (`backend/`): FastAPI on `127.0.0.1`, started in a background thread.
  Pure-function parsers → JSON. SQLite cache keyed by repo file mtimes.
- **Face** (`frontend/`): vanilla HTML/CSS/JS in a pywebview window. Safe DOM
  builder (`el()`), no `innerHTML`.
- **Entry** (`app.py`): picks a free port, serves, waits for readiness, opens the
  window, logs lifecycle.

## Key components
| File | Responsibility |
|---|---|
| `backend/config.py` | Repo path + app-data path resolution (env overridable) |
| `backend/diagnostics.py` | Frozen-exe-safe logger (STARTUP/STATE/DECISION/CRASH) |
| `backend/cache.py` | SQLite payload cache + mtime staleness signature |
| `backend/engine/species.py` | `SPECIES_X` → sprite folder (direct + relaxed match) |
| `backend/engine/encounters.py` | wild_encounters.json → per-map mon lists + rarity |
| `backend/engine/maps.py` | layouts + map.json → map info, NPCs, warps; parse-error surfacing |
| `backend/engine/atlas.py` | joins encounters + species index + sprite availability |
| `backend/server.py` | API routes (`/api/atlas`, `/api/maps`, `/api/sprite`, `/api/rescan`) + static mount |
| `frontend/assets/dom.js` | safe element builder |
| `frontend/rooms/atlas.js` | World Atlas room (list → encounters → mon detail) |

## Data sources (all read-only)
- `src/data/wild_encounters.json` — wild encounters
- `data/maps/*/map.json` + `data/layouts/layouts.json` — maps
- `graphics/pokemon/<species>/icon.png` (grid) + `anim_front.png` (portrait, first frame cropped)

## Numbers (verified 2026-05-23)
- 240 maps with encounters · 192 distinct species · 100% sprite resolution
- 939 total maps listed · 0 parse errors · 15 tests passing

## Known polish items / next phases
- Phase 2: Cast & Scripts (NPCs/trainers/scripts) — `maps.py` already extracts object_events/warps.
- Phase 3: Command Center (AI history + decisions + status).
- Phase 4: Story Timeline (research spike on flag/warp graph + narrative docs).
