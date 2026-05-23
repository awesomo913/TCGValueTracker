# StolenEmerald Planner — Phase 1 Implementation Plan (Foundation + World Atlas)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a beautiful local web app (Python brain + web face) that reads `C:\StolenEmerald` read-only and shows every wild Pokémon on every route with real sprites — plus the shared Foundation all later rooms ride on.

**Architecture:** FastAPI backend runs on `127.0.0.1` in a background thread; a pywebview window loads it as a native-feeling desktop app. Pure-function parsers turn decomp JSON into clean API JSON, cached in SQLite. Vanilla HTML/CSS/JS frontend (no node build step) for simple exe packaging. Frontend builds DOM with a safe `el()` helper — never `innerHTML` — to avoid injection from repo-derived strings.

**Tech Stack:** Python 3.11, FastAPI, uvicorn, pywebview, Pillow (sprite handling), pytest, PyInstaller, `uv` for deps.

**Hard rule:** Never write to `C:\StolenEmerald`. All app data lives under `Desktop\AI\StolenEmeraldPlanner`.

---

## File Structure

```
Desktop\AI\StolenEmeraldPlanner\
  backend\
    __init__.py
    config.py            # repo path resolution, app data dir
    diagnostics.py       # embedded logger (workspace rule)
    cache.py             # SQLite scan cache + mtime staleness
    engine\
      __init__.py
      species.py         # SPECIES_X -> sprite folder resolution
      encounters.py      # wild_encounters.json -> per-map mon lists
      maps.py            # layouts.json + map.json -> map metadata
      atlas.py           # joins encounters + sprite availability
    server.py            # FastAPI app: data routes + sprite route + static mount
  frontend\
    index.html
    assets\
      app.css            # theme + layout
      dom.js             # safe el() element builder (no innerHTML)
      app.js             # router + data client + shell
    rooms\
      atlas.js           # World Atlas room
  app.py                 # pywebview entry: start server thread, open window
  build_exe.py           # PyInstaller build -> Desktop\My Apps\
  app_spec.json
  requirements.txt
  tests\
    conftest.py
    test_species.py
    test_encounters.py
    test_maps.py
    test_cache.py
    test_server.py
```

---

### Task 0: Project scaffold + deps + diagnostics

**Files:**
- Create: `StolenEmeraldPlanner/requirements.txt`
- Create: `StolenEmeraldPlanner/backend/__init__.py` (empty)
- Create: `StolenEmeraldPlanner/backend/engine/__init__.py` (empty)
- Create: `StolenEmeraldPlanner/backend/config.py`
- Create: `StolenEmeraldPlanner/backend/diagnostics.py`
- Create: `StolenEmeraldPlanner/tests/conftest.py`

- [ ] **Step 1: requirements.txt**

```
fastapi==0.115.6
uvicorn==0.34.0
pywebview==5.3.2
Pillow==11.1.0
pytest==8.3.4
httpx==0.28.1
```

- [ ] **Step 2: Create venv + install with uv**

Run:
```bash
cd "/c/Users/computer/Desktop/AI/StolenEmeraldPlanner"
uv venv --python 3.11 .venv
uv pip install -r requirements.txt
```
Expected: deps install, no errors.

- [ ] **Step 3: config.py** — repo path + app data dir resolution

```python
import os
from pathlib import Path

DEFAULT_REPO = Path(r"C:\StolenEmerald")

def app_data_dir() -> Path:
    d = Path(os.environ.get("SE_PLANNER_DATA", Path.home() / ".stolenemerald_planner"))
    d.mkdir(parents=True, exist_ok=True)
    return d

def repo_path() -> Path:
    """Repo root. Override with SE_PLANNER_REPO. Read-only target."""
    return Path(os.environ.get("SE_PLANNER_REPO", DEFAULT_REPO))

def repo_exists() -> bool:
    return (repo_path() / "src" / "data" / "wild_encounters.json").is_file()
```

- [ ] **Step 4: diagnostics.py** — frozen-exe-safe logger (workspace rule)

```python
import sys, datetime, traceback
from pathlib import Path

def _log_path() -> Path:
    base = Path.home() / ".claude" / "session-data" / datetime.date.today().isoformat()
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path.home()
    return base / "exe_StolenEmeraldPlanner.log"

def _write(line: str) -> None:
    try:
        with open(_log_path(), "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass  # logging must never crash the app

def log(kind: str, msg: str) -> None:
    ts = datetime.datetime.now().isoformat(timespec="seconds")
    _write(f"{ts} {kind} {msg}")

def bootstrap(app_name: str = "StolenEmeraldPlanner") -> None:
    frozen = getattr(sys, "frozen", False)
    log("STARTUP", f"{app_name} python={sys.version.split()[0]} frozen={frozen} argv={sys.argv}")
    def _hook(exc_type, exc, tb):
        log("CRASH", "".join(traceback.format_exception(exc_type, exc, tb)).replace("\n", " | "))
        sys.__excepthook__(exc_type, exc, tb)
    sys.excepthook = _hook
```

- [ ] **Step 5: tests/conftest.py** — point tests at the real repo, skip if absent

```python
import pytest
from backend import config

@pytest.fixture(scope="session")
def repo():
    if not config.repo_exists():
        pytest.skip("StolenEmerald repo not found")
    return config.repo_path()
```

- [ ] **Step 6: Commit**

```bash
git add StolenEmeraldPlanner/requirements.txt StolenEmeraldPlanner/backend StolenEmeraldPlanner/tests/conftest.py
git commit -m "feat: scaffold StolenEmerald Planner backend + diagnostics"
```

---

### Task 1: Species → sprite folder resolver

**Files:**
- Create: `StolenEmeraldPlanner/backend/engine/species.py`
- Test: `StolenEmeraldPlanner/tests/test_species.py`

- [ ] **Step 1: Write failing test**

```python
from backend.engine import species

def test_basic_species_to_folder():
    assert species.to_folder("SPECIES_ZIGZAGOON") == "zigzagoon"

def test_strips_prefix_and_lowercases():
    assert species.to_folder("SPECIES_MR_MIME") == "mr_mime"

def test_resolve_existing_returns_path(repo):
    p = species.sprite_dir(repo, "SPECIES_ZIGZAGOON")
    assert p is not None and (p / "icon.png").is_file()

def test_resolve_unknown_returns_none(repo):
    assert species.sprite_dir(repo, "SPECIES_NOT_A_REAL_MON") is None
```

- [ ] **Step 2: Run, verify fail**

Run: `cd StolenEmeraldPlanner && .venv/Scripts/python -m pytest tests/test_species.py -v`
Expected: FAIL (module/attr missing).

- [ ] **Step 3: Implement species.py**

```python
from pathlib import Path

def to_folder(species_const: str) -> str:
    """SPECIES_ZIGZAGOON -> zigzagoon. Pure string transform."""
    s = species_const.strip()
    if s.upper().startswith("SPECIES_"):
        s = s[len("SPECIES_"):]
    return s.lower()

def sprite_dir(repo: Path, species_const: str):
    """Return graphics/pokemon/<folder> Path if it exists, else None.
    Tries direct transform, then a relaxed alnum-only match."""
    base = repo / "graphics" / "pokemon"
    folder = to_folder(species_const)
    direct = base / folder
    if direct.is_dir():
        return direct
    key = "".join(c for c in folder if c.isalnum())
    if not base.is_dir():
        return None
    for child in base.iterdir():
        if child.is_dir() and "".join(c for c in child.name if c.isalnum()) == key:
            return child
    return None
```

- [ ] **Step 4: Run, verify pass**

Run: `.venv/Scripts/python -m pytest tests/test_species.py -v`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/backend/engine/species.py StolenEmeraldPlanner/tests/test_species.py
git commit -m "feat: species const -> sprite folder resolver"
```

---

### Task 2: Encounters parser

**Files:**
- Create: `StolenEmeraldPlanner/backend/engine/encounters.py`
- Test: `StolenEmeraldPlanner/tests/test_encounters.py`

Rarity = `encounter_rates[slot] / sum(encounter_rates) * 100`, rounded 1 decimal. Slot order = list order within each method's `mons`.

- [ ] **Step 1: Write failing test**

```python
from backend.engine import encounters

def test_load_returns_maps_dict(repo):
    data = encounters.load(repo)
    assert "MAP_ROUTE101" in data
    assert "land_mons" in data["MAP_ROUTE101"]["methods"]

def test_mon_has_species_levels_rarity(repo):
    data = encounters.load(repo)
    first = data["MAP_ROUTE101"]["methods"]["land_mons"]["mons"][0]
    assert first["species"] == "SPECIES_PIDGEY"
    assert first["min_level"] == 2 and first["max_level"] == 3
    assert 0 < first["rarity_pct"] <= 100

def test_rarities_sum_to_100(repo):
    data = encounters.load(repo)
    for m in data["MAP_ROUTE101"]["methods"].values():
        assert round(sum(x["rarity_pct"] for x in m["mons"]), 0) == 100

def test_species_index_maps_mon_to_maps(repo):
    idx = encounters.species_index(repo)
    assert any(m.startswith("MAP_") for m in idx.get("SPECIES_PIDGEY", []))
```

- [ ] **Step 2: Run, verify fail.** `.venv/Scripts/python -m pytest tests/test_encounters.py -v`

- [ ] **Step 3: Implement encounters.py**

```python
import json
from pathlib import Path

_METHODS = ("land_mons", "water_mons", "rock_smash_mons", "fishing_mons")

def _rates_by_type(group: dict):
    return {f["type"]: f.get("encounter_rates", []) for f in group.get("fields", [])}

def load(repo: Path) -> dict:
    """{MAP_X: {base_label, methods: {land_mons: {encounter_rate,
        mons:[{species,min_level,max_level,slot,rarity_pct}]}}}}"""
    raw = json.loads((repo / "src" / "data" / "wild_encounters.json").read_text(encoding="utf-8"))
    result = {}
    for group in raw["wild_encounter_groups"]:
        if not group.get("for_maps"):
            continue
        rates = _rates_by_type(group)
        for entry in group.get("encounters", []):
            methods = {}
            for meth in _METHODS:
                if meth not in entry:
                    continue
                block = entry[meth]
                slot_rates = rates.get(meth, [])
                total = sum(slot_rates) or 1
                mons = []
                for slot, mon in enumerate(block.get("mons", [])):
                    w = slot_rates[slot] if slot < len(slot_rates) else 0
                    mons.append({
                        "species": mon["species"],
                        "min_level": mon["min_level"],
                        "max_level": mon["max_level"],
                        "slot": slot,
                        "rarity_pct": round(w / total * 100, 1),
                    })
                methods[meth] = {"encounter_rate": block.get("encounter_rate", 0), "mons": mons}
            result[entry["map"]] = {"base_label": entry.get("base_label", ""), "methods": methods}
    return result

def species_index(repo: Path):
    """{SPECIES_X: [MAP_A, ...]} — every map a species appears on."""
    idx = {}
    for map_name, info in load(repo).items():
        for m in info["methods"].values():
            for mon in m["mons"]:
                idx.setdefault(mon["species"], set()).add(map_name)
    return {k: sorted(v) for k, v in idx.items()}
```

Note: in pokeemerald format each slot has exactly one mon, so per-slot `rarity_pct` is correct; the `if slot < len(slot_rates)` guard keeps mismatched lengths safe.

- [ ] **Step 4: Run, verify pass.** Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/backend/engine/encounters.py StolenEmeraldPlanner/tests/test_encounters.py
git commit -m "feat: wild encounters parser with rarity + species index"
```

---

### Task 3: Maps parser

**Files:**
- Create: `StolenEmeraldPlanner/backend/engine/maps.py`
- Test: `StolenEmeraldPlanner/tests/test_maps.py`

- [ ] **Step 1: Write failing test**

```python
from backend.engine import maps

def test_list_maps_nonempty(repo):
    ms = maps.list_maps(repo)
    assert len(ms) > 100
    assert all("id" in m and "name" in m for m in ms)

def test_get_map_has_objects_and_warps(repo):
    m = maps.get_map(repo, "AbandonedShip_Corridors_1F")
    assert m["id"] == "MAP_ABANDONED_SHIP_CORRIDORS_1F"
    assert len(m["object_events"]) >= 1
    assert "graphics_id" in m["object_events"][0]
    assert len(m["warp_events"]) >= 1

def test_get_map_missing_returns_none(repo):
    assert maps.get_map(repo, "NotAMapFolder") is None
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement maps.py**

```python
import json
from pathlib import Path

def _maps_root(repo: Path) -> Path:
    return repo / "data" / "maps"

def list_maps(repo: Path):
    """[{id, name, region_section, map_type}] sorted by name."""
    out = []
    root = _maps_root(repo)
    if not root.is_dir():
        return out
    for child in sorted(root.iterdir()):
        mj = child / "map.json"
        if not mj.is_file():
            continue
        try:
            d = json.loads(mj.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue  # surfaced separately via parse_errors()
        out.append({
            "id": d.get("id", ""),
            "name": d.get("name", child.name),
            "region_section": d.get("region_map_section", ""),
            "map_type": d.get("map_type", ""),
        })
    return out

def get_map(repo: Path, folder_name: str):
    mj = _maps_root(repo) / folder_name / "map.json"
    if not mj.is_file():
        return None
    d = json.loads(mj.read_text(encoding="utf-8"))
    return {
        "id": d.get("id", ""),
        "name": d.get("name", folder_name),
        "layout": d.get("layout", ""),
        "region_section": d.get("region_map_section", ""),
        "music": d.get("music", ""),
        "weather": d.get("weather", ""),
        "map_type": d.get("map_type", ""),
        "object_events": d.get("object_events", []) or [],
        "warp_events": d.get("warp_events", []) or [],
        "connections": d.get("connections") or [],
    }

def parse_errors(repo: Path):
    """Folders whose map.json failed to parse — shown in UI, never silent."""
    bad = []
    root = _maps_root(repo)
    if not root.is_dir():
        return bad
    for child in root.iterdir():
        mj = child / "map.json"
        if mj.is_file():
            try:
                json.loads(mj.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                bad.append(child.name)
    return bad
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/backend/engine/maps.py StolenEmeraldPlanner/tests/test_maps.py
git commit -m "feat: maps parser (list + detail + parse-error surfacing)"
```

---

### Task 4: SQLite scan cache + staleness

**Files:**
- Create: `StolenEmeraldPlanner/backend/cache.py`
- Test: `StolenEmeraldPlanner/tests/test_cache.py`

Cache stores the assembled Atlas payload as one JSON blob keyed by a scan signature (mtime+size across `wild_encounters.json` + `data/maps`). Staleness = current signature differs from stored.

- [ ] **Step 1: Write failing test**

```python
from backend import cache

def test_get_set_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("SE_PLANNER_DATA", str(tmp_path))
    cache.set_payload("atlas", {"hello": 1}, signature="sig1")
    assert cache.get_payload("atlas") == {"hello": 1}
    assert cache.is_stale("atlas", "sig2") is True
    assert cache.is_stale("atlas", "sig1") is False

def test_missing_key_is_stale(tmp_path, monkeypatch):
    monkeypatch.setenv("SE_PLANNER_DATA", str(tmp_path))
    assert cache.is_stale("never_set", "x") is True
    assert cache.get_payload("never_set") is None
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement cache.py**

```python
import json, sqlite3
from pathlib import Path
from backend import config

def _db() -> sqlite3.Connection:
    con = sqlite3.connect(config.app_data_dir() / "cache.db")
    con.execute("CREATE TABLE IF NOT EXISTS payloads (key TEXT PRIMARY KEY, sig TEXT, json TEXT)")
    return con

def _signature(paths) -> str:
    parts = []
    for p in sorted(paths, key=lambda x: str(x)):
        try:
            st = p.stat(); parts.append(f"{p}:{int(st.st_mtime)}:{st.st_size}")
        except OSError:
            parts.append(f"{p}:missing")
    return "|".join(parts)

def repo_signature() -> str:
    repo = config.repo_path()
    return _signature([repo / "src" / "data" / "wild_encounters.json", repo / "data" / "maps"])

def set_payload(key: str, payload: dict, signature: str) -> None:
    con = _db()
    con.execute("INSERT OR REPLACE INTO payloads (key, sig, json) VALUES (?,?,?)",
                (key, signature, json.dumps(payload)))
    con.commit(); con.close()

def get_payload(key: str):
    con = _db()
    row = con.execute("SELECT json FROM payloads WHERE key=?", (key,)).fetchone()
    con.close()
    return json.loads(row[0]) if row else None

def is_stale(key: str, signature: str) -> bool:
    con = _db()
    row = con.execute("SELECT sig FROM payloads WHERE key=?", (key,)).fetchone()
    con.close()
    return row is None or row[0] != signature
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/backend/cache.py StolenEmeraldPlanner/tests/test_cache.py
git commit -m "feat: SQLite scan cache + repo mtime staleness"
```

---

### Task 5: Atlas payload builder + FastAPI server

**Files:**
- Create: `StolenEmeraldPlanner/backend/engine/atlas.py`
- Create: `StolenEmeraldPlanner/backend/server.py`
- Test: `StolenEmeraldPlanner/tests/test_server.py`

- [ ] **Step 1: atlas.py — join encounters + sprite availability**

```python
from pathlib import Path
from backend.engine import encounters, species

def build(repo: Path) -> dict:
    enc = encounters.load(repo)
    sidx = encounters.species_index(repo)
    sprite_map = {}
    for sp in sidx:
        d = species.sprite_dir(repo, sp)
        sprite_map[sp] = d.name if d else None
    return {
        "maps": {name: {"base_label": info["base_label"], "methods": info["methods"]}
                 for name, info in enc.items()},
        "species_index": sidx,
        "sprite_folders": sprite_map,  # SPECIES_X -> folder name or None
    }
```

- [ ] **Step 2: Write failing server test**

```python
from fastapi.testclient import TestClient
from backend.server import app

client = TestClient(app)

def test_health():
    assert client.get("/api/health").json()["ok"] is True

def test_atlas_endpoint_has_route101():
    r = client.get("/api/atlas")
    if r.status_code == 503:
        return  # repo absent on this machine
    assert "MAP_ROUTE101" in r.json()["maps"]
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement server.py**

```python
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from backend import config, cache, diagnostics
from backend.engine import atlas, maps, species

app = FastAPI(title="StolenEmerald Planner")
FRONTEND = Path(__file__).resolve().parent.parent / "frontend"

@app.get("/api/health")
def health():
    return {"ok": True, "repo": str(config.repo_path()), "repo_found": config.repo_exists()}

@app.get("/api/atlas")
def get_atlas():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="StolenEmerald repo not found")
    sig = cache.repo_signature()
    if cache.is_stale("atlas", sig):
        diagnostics.log("DECISION", "atlas cache miss -> rebuild")
        payload = atlas.build(config.repo_path())
        cache.set_payload("atlas", payload, sig)
    else:
        payload = cache.get_payload("atlas")
    return payload

@app.post("/api/rescan")
def rescan():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    payload = atlas.build(config.repo_path())
    cache.set_payload("atlas", payload, cache.repo_signature())
    diagnostics.log("STATE", "manual rescan complete")
    return {"ok": True}

@app.get("/api/maps")
def get_maps():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    repo = config.repo_path()
    return {"maps": maps.list_maps(repo), "parse_errors": maps.parse_errors(repo)}

@app.get("/api/sprite/{species_const}")
def get_sprite(species_const: str, kind: str = "icon"):
    d = species.sprite_dir(config.repo_path(), species_const)
    fname = {"icon": "icon.png", "front": "anim_front.png"}.get(kind, "icon.png")
    if d is None or not (d / fname).is_file():
        diagnostics.log("DECISION", f"missing_sprite={species_const} kind={kind}")
        raise HTTPException(status_code=404, detail="sprite not found")
    return FileResponse(d / fname)

# static frontend mounted last so /api/* wins
if FRONTEND.is_dir():
    app.mount("/", StaticFiles(directory=str(FRONTEND), html=True), name="frontend")
```

- [ ] **Step 5: Run, verify pass.** `.venv/Scripts/python -m pytest tests/test_server.py -v`

- [ ] **Step 6: Manual API smoke**

```bash
.venv/Scripts/python -c "from backend.engine import atlas; from backend import config; d=atlas.build(config.repo_path()); print(len(d['maps']),'maps;', len(d['species_index']),'species')"
```
Expected: ~388 maps and several hundred species.

- [ ] **Step 7: Commit**

```bash
git add StolenEmeraldPlanner/backend/engine/atlas.py StolenEmeraldPlanner/backend/server.py StolenEmeraldPlanner/tests/test_server.py
git commit -m "feat: atlas payload builder + FastAPI server (data + sprite routes)"
```

---

### Task 6: pywebview app entry

**Files:**
- Create: `StolenEmeraldPlanner/app.py`

- [ ] **Step 1: Implement app.py**

```python
import threading, time, socket
import uvicorn, webview
from backend import diagnostics

def _free_port() -> int:
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    return port

def _serve(port: int):
    uvicorn.run("backend.server:app", host="127.0.0.1", port=port, log_level="warning")

def main():
    diagnostics.bootstrap("StolenEmeraldPlanner")
    port = _free_port()
    threading.Thread(target=_serve, args=(port,), daemon=True).start()
    for _ in range(50):  # wait for server to accept connections
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            time.sleep(0.1)
    diagnostics.log("STATE", f"init->ready port={port}")
    webview.create_window("StolenEmerald Planner", f"http://127.0.0.1:{port}",
                          width=1400, height=900, min_size=(1100, 700))
    webview.start()
    diagnostics.log("STATE", "ready->shutdown")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Manual launch test**

Run: `.venv/Scripts/python app.py`
Expected: window opens (blank until Task 7); close it; check `~/.claude/session-data/<today>/exe_StolenEmeraldPlanner.log` has STARTUP + STATE lines.

- [ ] **Step 3: Commit**

```bash
git add StolenEmeraldPlanner/app.py
git commit -m "feat: pywebview desktop entry wrapping the localhost server"
```

---

### Task 7: Frontend shell + theme + safe DOM helper

**Files:**
- Create: `StolenEmeraldPlanner/frontend/index.html`
- Create: `StolenEmeraldPlanner/frontend/assets/app.css`
- Create: `StolenEmeraldPlanner/frontend/assets/dom.js`
- Create: `StolenEmeraldPlanner/frontend/assets/app.js`

Theme: dark emerald palette (deep teal/emerald gradients, gold accents), card-based, generous spacing, smooth transitions. Sidebar lists four rooms; only Atlas active in Phase 1.

- [ ] **Step 1: index.html**

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>StolenEmerald Planner</title>
  <link rel="stylesheet" href="/assets/app.css" />
</head>
<body>
  <aside id="sidebar">
    <h1 class="brand">StolenEmerald<span>Planner</span></h1>
    <nav id="nav">
      <button class="nav-item active" data-room="atlas">World Atlas</button>
      <button class="nav-item" data-room="cast" disabled>Cast &amp; Scripts</button>
      <button class="nav-item" data-room="command" disabled>Command Center</button>
      <button class="nav-item" data-room="timeline" disabled>Story Timeline</button>
    </nav>
    <button id="rescan" title="Re-read the repo">Rescan repo</button>
    <div id="status" class="status"></div>
  </aside>
  <main id="view"></main>
  <script src="/assets/dom.js"></script>
  <script src="/rooms/atlas.js"></script>
  <script src="/assets/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: assets/app.css**

```css
:root{
  --bg:#0b1f1c; --panel:#102b27; --panel2:#16403a; --emerald:#2fbf8f;
  --emerald-bright:#4fe0ad; --gold:#e8c66b; --text:#e7f4ef; --muted:#8fb3a9;
  --shadow:0 8px 30px rgba(0,0,0,.45);
}
*{box-sizing:border-box} html,body{margin:0;height:100%}
body{display:flex;font:15px/1.5 "Segoe UI",system-ui,sans-serif;
  background:radial-gradient(1200px 800px at 80% -10%,#173f38,var(--bg));color:var(--text)}
#sidebar{width:260px;flex:0 0 260px;background:linear-gradient(180deg,var(--panel),#0c2420);
  padding:22px 16px;display:flex;flex-direction:column;gap:14px;box-shadow:var(--shadow);z-index:2}
.brand{font-size:20px;font-weight:800;letter-spacing:.5px;margin:0 0 8px}
.brand span{color:var(--emerald-bright);display:block;font-weight:600;font-size:14px;opacity:.85}
.nav-item{display:flex;justify-content:space-between;align-items:center;gap:8px;text-align:left;
  background:transparent;border:1px solid transparent;color:var(--text);padding:11px 13px;border-radius:12px;
  cursor:pointer;font-size:14px;transition:.15s}
.nav-item:hover:not(:disabled){background:var(--panel2)}
.nav-item.active{background:linear-gradient(90deg,var(--emerald),#1c7a5c);font-weight:700;box-shadow:var(--shadow)}
.nav-item:disabled{opacity:.45;cursor:default}
#rescan{margin-top:auto;background:var(--panel2);border:1px solid #2c5e54;color:var(--text);
  padding:10px;border-radius:10px;cursor:pointer}
#rescan:hover{border-color:var(--emerald-bright)}
.status{font-size:12px;color:var(--muted);min-height:16px}
#view{flex:1;overflow:auto;padding:28px 34px}
.view-head{display:flex;align-items:center;gap:16px;margin-bottom:18px;flex-wrap:wrap}
.view-head h2{margin:0;font-size:24px}
input,select{background:var(--panel);border:1px solid #2c5e54;color:var(--text);
  padding:9px 12px;border-radius:10px;font-size:14px}
.map-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}
.map-card{background:var(--panel);border:1px solid #1d4a43;border-radius:14px;padding:14px;cursor:pointer;transition:.15s}
.map-card:hover{transform:translateY(-2px);border-color:var(--emerald-bright);box-shadow:var(--shadow)}
.map-card .mt{font-size:11px;color:var(--muted)}
.mon-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:14px;margin-bottom:22px}
.mon-card{background:var(--panel);border:1px solid #1d4a43;border-radius:14px;padding:14px;text-align:center;transition:.15s;cursor:pointer}
.mon-card:hover{transform:translateY(-3px);box-shadow:var(--shadow)}
.mon-card img{width:64px;height:64px;image-rendering:pixelated;object-fit:contain}
.mon-card .name{font-weight:700;text-transform:capitalize;margin-top:6px}
.mon-card .lv{color:var(--muted);font-size:13px}
.rarity{height:6px;border-radius:4px;background:#0006;margin-top:8px;overflow:hidden}
.rarity i{display:block;height:100%;background:linear-gradient(90deg,var(--gold),var(--emerald-bright))}
.method-tag{display:inline-block;font-size:11px;color:var(--gold);border:1px solid #3a3a1f;
  padding:2px 8px;border-radius:8px;margin-bottom:8px}
.placeholder-img{width:64px;height:64px;display:flex;align-items:center;justify-content:center;
  background:#0006;border-radius:8px;color:var(--muted);font-size:11px;margin:0 auto}
.empty{color:var(--muted);padding:40px;text-align:center}
.back-btn{background:var(--panel2);border:1px solid #2c5e54;color:var(--text);padding:8px 12px;border-radius:10px;cursor:pointer}
```

- [ ] **Step 3: assets/dom.js** — safe element builder (no innerHTML anywhere)

```javascript
// el(tag, props, ...children) -> Element. Strings become text nodes (escaped by the DOM).
function el(tag, props, ...kids){
  const e = document.createElement(tag);
  const p = props || {};
  for (const k in p){
    const v = p[k];
    if (k === "cls") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k === "style" && typeof v === "object") Object.assign(e.style, v);
    else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) e.setAttribute(k, v);
  }
  for (const kid of kids.flat()){
    if (kid === null || kid === undefined) continue;
    e.appendChild(typeof kid === "object" ? kid : document.createTextNode(String(kid)));
  }
  return e;
}
function clear(node){ while (node.firstChild) node.removeChild(node.firstChild); }
function mount(node, ...kids){ clear(node); for (const k of kids.flat()) if (k) node.appendChild(k); }
```

- [ ] **Step 4: assets/app.js** — router + data client + shell

```javascript
const api = {
  async atlas(){ const r = await fetch('/api/atlas'); if(!r.ok) throw new Error('repo not found ('+r.status+')'); return r.json(); },
  async rescan(){ const r = await fetch('/api/rescan',{method:'POST'}); return r.ok; },
};
const state = { atlas:null };
const view = document.getElementById('view');
const statusEl = document.getElementById('status');
function setStatus(msg){ statusEl.textContent = msg || ''; }

async function ensureAtlas(){
  if(state.atlas) return state.atlas;
  setStatus('Reading repo…');
  state.atlas = await api.atlas();
  setStatus(Object.keys(state.atlas.maps).length + ' maps loaded');
  return state.atlas;
}

const rooms = { atlas: () => window.renderAtlas(view, ensureAtlas, setStatus) };

function activate(room){
  document.querySelectorAll('.nav-item').forEach(b=>b.classList.toggle('active', b.dataset.room===room));
  if (rooms[room]) rooms[room]();
  else mount(view, el('div',{cls:'empty',text:'Coming soon.'}));
}

document.querySelectorAll('.nav-item').forEach(b=>{
  b.addEventListener('click', ()=>{ if(!b.disabled) activate(b.dataset.room); });
});
document.getElementById('rescan').addEventListener('click', async ()=>{
  setStatus('Rescanning…'); state.atlas=null;
  try{ await api.rescan(); await ensureAtlas(); activate('atlas'); }
  catch(e){ setStatus('Rescan failed: '+e.message); }
});

activate('atlas');
```

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/frontend/index.html StolenEmeraldPlanner/frontend/assets
git commit -m "feat: frontend shell, emerald theme, safe DOM helper, sidebar router"
```

---

### Task 8: World Atlas room

**Files:**
- Create: `StolenEmeraldPlanner/frontend/rooms/atlas.js`

Behavior: map list (search) → click map → encounter grid grouped by method, each mon shows sprite, level range, rarity bar → click mon → detail with big front sprite + "also appears on" maps. Missing sprite → labeled placeholder. Built entirely with `el()` (no innerHTML).

- [ ] **Step 1: Implement atlas.js**

```javascript
window.renderAtlas = async function(view, ensureAtlas, setStatus){
  mount(view, el('div',{cls:'empty',text:'Loading atlas…'}));
  let data;
  try{ data = await ensureAtlas(); }
  catch(e){ mount(view, el('div',{cls:'empty'}, 'Could not read the repo. ', e.message)); return; }

  const mapNames = Object.keys(data.maps).sort();
  const pretty = n => n.replace(/^MAP_/,'').replace(/_/g,' ').toLowerCase().replace(/\b\w/g,c=>c.toUpperCase());
  const monName = s => s.replace(/^SPECIES_/,'').replace(/_/g,' ').toLowerCase();
  const spriteUrl = (s,kind) => '/api/sprite/'+encodeURIComponent(s)+(kind?('?kind='+kind):'');

  function spriteEl(species, big){
    const folder = data.sprite_folders[species];
    const ph = () => el('div',{cls:'placeholder-img', style: big?{width:'128px',height:'128px'}:{}, text:'no sprite'});
    if(!folder) return ph();
    const img = el('img',{src:spriteUrl(species, big?'front':null), alt:monName(species), loading:'lazy'});
    if(big){ img.style.width='128px'; img.style.height='128px'; img.style.imageRendering='pixelated'; }
    img.addEventListener('error', ()=> img.replaceWith(ph()));
    return img;
  }

  function monCard(mon){
    return el('div',{cls:'mon-card', onclick:()=>showMon(mon.species)},
      spriteEl(mon.species, false),
      el('div',{cls:'name', text:monName(mon.species)}),
      el('div',{cls:'lv', text:`Lv ${mon.min_level}-${mon.max_level} · ${mon.rarity_pct}%`}),
      el('div',{cls:'rarity'}, el('i',{style:{width:Math.min(100,mon.rarity_pct)+'%'}}))
    );
  }

  function showMap(name){
    const m = data.maps[name];
    const head = el('div',{cls:'view-head'},
      el('button',{cls:'back-btn', text:'← Maps', onclick:listMaps}),
      el('h2',{text:pretty(name)}));
    const blocks = Object.entries(m.methods).map(([meth,blk])=>
      el('div',{},
        el('div',{cls:'method-tag', text:`${meth.replace('_mons','').replace('_',' ')} · rate ${blk.encounter_rate}`}),
        el('div',{cls:'mon-grid'}, ...blk.mons.map(monCard))));
    mount(view, head, ...(blocks.length?blocks:[el('div',{cls:'empty',text:'No wild encounters on this map.'})]));
  }

  function showMon(species){
    const where = data.species_index[species]||[];
    const head = el('div',{cls:'view-head'},
      el('button',{cls:'back-btn', text:'←', onclick:listMaps}),
      el('h2',{style:{textTransform:'capitalize'}, text:monName(species)}));
    const list = el('div',{cls:'map-list'}, ...where.map(n=>
      el('div',{cls:'map-card', onclick:()=>showMap(n)}, el('div',{text:pretty(n)}))));
    const body = el('div',{style:{display:'flex',gap:'24px',flexWrap:'wrap'}},
      el('div',{}, spriteEl(species, true)),
      el('div',{}, el('h3',{text:`Appears on ${where.length} map(s)`}), list));
    mount(view, head, body);
  }

  function listMaps(filter){
    filter = typeof filter === 'string' ? filter : '';
    const shown = mapNames.filter(n=>pretty(n).toLowerCase().includes(filter.toLowerCase()));
    const search = el('input',{id:'mapsearch', placeholder:`Search ${mapNames.length} maps…`, value:filter});
    search.addEventListener('input', ()=>listMaps(search.value));
    const head = el('div',{cls:'view-head'}, el('h2',{text:'World Atlas'}), search,
      el('span',{cls:'status', text:`${shown.length} shown`}));
    const cards = shown.map(n=>{
      const count = Object.values(data.maps[n].methods).reduce((a,b)=>a+b.mons.length,0);
      return el('div',{cls:'map-card', onclick:()=>showMap(n)},
        el('div',{text:pretty(n)}), el('div',{cls:'mt', text:`${count} wild mon`}));
    });
    mount(view, head, el('div',{cls:'map-list'}, ...cards));
    search.focus(); search.setSelectionRange(filter.length, filter.length);
  }

  listMaps();
};
```

- [ ] **Step 2: Manual UI test**

Run: `.venv/Scripts/python app.py`
Verify: map list shows; search filters; click Route 101 → Pidgey/Poochyena with sprites + rarity bars; click a mon → big sprite + "appears on" maps; back buttons work; Rescan reloads.

- [ ] **Step 3: Commit**

```bash
git add StolenEmeraldPlanner/frontend/rooms/atlas.js
git commit -m "feat: World Atlas room — maps, encounter grid, sprites, mon detail"
```

---

### Task 9: Exe packaging

**Files:**
- Create: `StolenEmeraldPlanner/app_spec.json`
- Create: `StolenEmeraldPlanner/build_exe.py`

- [ ] **Step 1: app_spec.json**

```json
{
  "name": "StolenEmeraldPlanner",
  "entry": "app.py",
  "kind": "gui",
  "build_mode": "onedir",
  "hidden_imports": ["uvicorn.logging","uvicorn.loops.auto","uvicorn.protocols.http.auto",
                     "uvicorn.protocols.websockets.auto","uvicorn.lifespan.on"],
  "data_dirs": ["frontend"],
  "icon": ""
}
```
Rationale: `onedir` (per workspace ML/large-bundle exception) because uvicorn + pywebview + bundled frontend extract slowly under onefile; wrap in a folder + Desktop shortcut to the inner exe.

- [ ] **Step 2: build_exe.py**

```python
import subprocess, sys, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = "StolenEmeraldPlanner"
MY_APPS = Path.home() / "Desktop" / "My Apps"

def main():
    MY_APPS.mkdir(parents=True, exist_ok=True)
    sep = ';' if sys.platform == 'win32' else ':'
    cmd = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--windowed", "--name", APP,
           "--add-data", f"{ROOT/'frontend'}{sep}frontend",
           "--collect-submodules", "uvicorn",
           "--hidden-import", "uvicorn.lifespan.on",
           str(ROOT / "app.py")]
    subprocess.run(cmd, check=True, cwd=ROOT)
    dist = ROOT / "dist" / APP
    target = MY_APPS / APP
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(dist, target)
    print("Built ->", target / (APP + ".exe"))

if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Install PyInstaller + build**

```bash
cd "/c/Users/computer/Desktop/AI/StolenEmeraldPlanner"
uv pip install pyinstaller
.venv/Scripts/python build_exe.py
```
Expected: `Desktop/My Apps/StolenEmeraldPlanner/StolenEmeraldPlanner.exe` exists.

- [ ] **Step 4: Launch exe, verify window + log**

Run the exe. Verify window opens, Atlas works, log file gets a STARTUP line with `frozen=True`.

- [ ] **Step 5: Commit**

```bash
git add StolenEmeraldPlanner/app_spec.json StolenEmeraldPlanner/build_exe.py
git commit -m "build: PyInstaller packaging for StolenEmerald Planner exe"
```

---

### Task 10: Docs quartet + README

**Files:**
- Create: `StolenEmeraldPlanner/README.md`
- Create: `Desktop/AI/docs/StolenEmeraldPlanner_BREAKDOWN.md`
- Create: `Desktop/AI/docs/StolenEmeraldPlanner_HANDOFF.md`
- Create: `Desktop/AI/docs/StolenEmeraldPlanner_TUTORIAL.md`
- Create: `Desktop/AI/docs/StolenEmeraldPlanner_PROOF.md`

- [ ] **Step 1:** README — Windows install steps (`uv venv` + `uv pip install -r requirements.txt`), run (`python app.py`), the read-only guarantee, repo-path override env var.
- [ ] **Step 2:** BREAKDOWN (per `memory/breakdown_template.md`), HANDOFF (Goals/History/Credit), TUTORIAL (per template), PROOF (plain-language dated record: "On 2026-05-23, built Phase 1 — a read-only viewer that shows every wild Pokémon on every route with real sprites.").
- [ ] **Step 3: Commit**

```bash
git add StolenEmeraldPlanner/README.md Desktop/AI/docs/StolenEmeraldPlanner_*.md
git commit -m "docs: README + BREAKDOWN/HANDOFF/TUTORIAL/PROOF for Phase 1"
```

---

## Self-Review

**Spec coverage (Phase 1 slice):**
- Read-only engine ✓ (all parsers read-mode; no write paths) — Tasks 1–5
- Cache + staleness + Rescan ✓ — Tasks 4, 5, 7
- Sprites ✓ — Tasks 1, 5, 8
- Encounters w/ rarity ✓ — Task 2
- Maps parser (feeds later rooms; list + parse-error surfacing) ✓ — Task 3
- Web UI + Python brain, pywebview, beautiful theme, safe DOM ✓ — Tasks 6, 7, 8
- World Atlas room ✓ — Task 8
- Exe in My Apps + diagnostics logger ✓ — Tasks 0, 9
- Error handling (missing repo 503, missing sprite placeholder + log, bad map.json surfaced) ✓ — Tasks 3, 5, 8
- Docs quartet ✓ — Task 10
- Out-of-scope respected (no repo writes, no emulator, no WebGL) ✓

**Placeholder scan:** No TBD/TODO. Every code step has full code. `icon` left `""` in app_spec (optional; documented).

**Type consistency:** `species.to_folder`/`sprite_dir`; `encounters.load`/`species_index`; `maps.list_maps`/`get_map`/`parse_errors`; `cache.repo_signature`/`is_stale`/`get_payload`/`set_payload`; `atlas.build` → consumed consistently by `server.py` and `atlas.js` (`data.maps`, `data.species_index`, `data.sprite_folders`). `/api/sprite/{species_const}?kind=icon|front` matches frontend `spriteUrl`. Frontend uses only `el()`/`mount()`/`clear()` from `dom.js` — no `innerHTML`.

**Phases 2–4** get their own plans after Phase 1 ships and the data engine is proven.
