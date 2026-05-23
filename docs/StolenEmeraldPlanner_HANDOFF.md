# StolenEmerald Planner — HANDOFF (AI-to-AI / co-worker)

## Goals
Build the "super tool" for the StolenEmerald ROM-hack: one beautiful local app
that bridges the dev/code side into the artistic/story/planning side. Read-only
over `C:\StolenEmerald`. Four rooms total; built in phases.

- **Phase 1 (DONE):** Foundation (data engine + cache + web shell) + World Atlas.
- **Phase 2 (DONE):** Map View — accurate rendered map + overlays (encounter
  zones, NPCs, trainers, item balls, hidden items, signs, warps, triggers).
- **Phase 3 (DONE):** Cast & Scripts — per-map people/trainers/items/signs +
  script viewer + trainer party.
- **Phase 4 (DONE):** Command Center — searchable AI history/doc archive.
- **Phase 5 (DONE):** Story Timeline — var-gated progression flags across all
  maps + GOALS_TIMELINE narrative.

All five views shipped. Future ideas: WebGL tile-walking world; per-tile
encounter overlap; live emulator hookup; link Cast trainers to Map View markers.

## History
- 2026-05-23: Brainstormed → spec (`docs/superpowers/specs/2026-05-23-stolenemerald-planner-design.md`)
  → Phase 1 plan (`docs/superpowers/plans/2026-05-23-stolenemerald-planner-phase1.md`)
  → built + verified Phase 1.
- Verified in a real browser (Playwright): map list, encounter grid with sprites,
  mon detail with cross-navigation. 15 pytest tests green.
- Frozen-exe bug found & fixed: uvicorn must receive the app **object**, not the
  `"backend.server:app"` import string (string form fails in PyInstaller bundles).

## Where things live
- Code: `Desktop/AI/StolenEmeraldPlanner/` (backend/, frontend/, app.py, build_exe.py)
- Exe: `Desktop/My Apps/StolenEmeraldPlanner/StolenEmeraldPlanner.exe`
- Docs: `Desktop/AI/docs/StolenEmeraldPlanner_*.md`
- Logs: `~/.claude/session-data/<date>/exe_StolenEmeraldPlanner.log`

## Important constraints
- **Never write to `C:\StolenEmerald`.** Read-only, always. App data is external.
- All deps via `uv`, never `pip`.
- Frontend uses safe DOM (`el()` in `dom.js`); never reintroduce `innerHTML`.
- Rebuild the exe (`build_exe.py`) after any source change before calling it done.

## How to extend (Phase 2 starting point)
- `backend/engine/maps.py` already returns `object_events` (NPCs, with `script`
  + `trainer_type`) and `warp_events`. Add `scripts.py` (read `data/maps/*/scripts.inc`)
  and `trainers.py` (parse `src/data/trainers.party`), a `/api/map/{folder}` route,
  and a `frontend/rooms/cast.js` room. Wire the disabled "Cast & Scripts" nav button.

## Credit & Authorship
Built collaboratively by the project owner (vision, direction, decisions) and
Claude (implementation), 2026-05-23.
