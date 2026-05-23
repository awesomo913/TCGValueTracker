# StolenEmerald Planner

A beautiful local desktop app that reads your `C:\StolenEmerald` ROM-hack
project **read-only** and turns it into a visual command center. Phase 1 ships
the **World Atlas**: see every wild Pokémon on every map, with real sprites,
level ranges, and rarity — and click any Pokémon to see every route it appears on.

> **Read-only guarantee:** the app never writes to, moves, or deletes anything
> in `C:\StolenEmerald`. All app data (cache, logs) lives outside the repo.

## What it does today (Phase 1)

- **World Atlas** — browse 240 maps that have wild encounters; per map, see each
  mon's sprite, level range, rarity %, grouped by method (land/water/rock
  smash/fishing). Click a mon for a big portrait + every map it appears on.
- **Rescan** — re-read the repo any time you change it.
- Coming next: Cast & Scripts, Command Center, Story Timeline.

## Install & run (Windows)

You need [`uv`](https://github.com/astral-sh/uv) (already used across this
workspace) and Python 3.11.

```bash
cd StolenEmeraldPlanner
uv venv --python 3.11 .venv
uv pip install -r requirements.txt --python .venv/Scripts/python.exe
.venv/Scripts/python app.py
```

A desktop window opens. That's it.

### Or just run the exe

A packaged build is produced at:

```
Desktop\My Apps\StolenEmeraldPlanner\StolenEmeraldPlanner.exe
```

Double-click it — no Python needed.

## Configuration

- **Repo location:** defaults to `C:\StolenEmerald`. Override with the
  `SE_PLANNER_REPO` environment variable.
- **App data location:** defaults to `~/.stolenemerald_planner`. Override with
  `SE_PLANNER_DATA`.

## How it's built

- **Python brain** (`backend/`): FastAPI server + read-only parsers for wild
  encounters, maps, and sprites; results cached in SQLite keyed by repo file
  mtimes.
- **Web face** (`frontend/`): vanilla HTML/CSS/JS rendered in a native window
  via pywebview. DOM is built with a safe `el()` helper — no `innerHTML`.

## Tests

```bash
.venv/Scripts/python -m pytest tests/ -v
```

15 tests cover the parsers, cache, and API (they skip automatically if the
StolenEmerald repo isn't present).
