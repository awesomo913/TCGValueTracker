# Block Boss Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a web dashboard, hosted on a Raspberry Pi 5, that lets a 7-year-old start/stop a Minecraft Bedrock server with big buttons while a parent manages it, with Switch support via BedrockConnect.

**Architecture:** One FastAPI process on the Pi serves a static dashboard and a small REST/WebSocket API. It supervises two child processes — the official Bedrock Dedicated Server (run through Box64 on ARM) and BedrockConnect (DNS helper for the Switch). Backend modules are small and single-purpose; risky actions are gated by a hashed parent PIN.

**Tech Stack:** Python 3.11, FastAPI, uvicorn, pytest. Box64 + Bedrock Dedicated Server, Pugmatt's BedrockConnect (Java). Plain HTML/CSS/JS frontend (no build step). systemd service. Deps via `uv`.

**Spec:** `docs/superpowers/specs/2026-05-21-block-boss-pi-bedrock-manager-design.md`

---

## File Structure

```
block_boss/
  app/
    __init__.py
    config.py              # paths, ports, settings
    logging_setup.py       # diagnostic logger (STARTUP/STATE/DECISION/PERF/CRASH)
    log_parser.py          # parse BDS stdout -> player/ready events (pure)
    auth.py                # parent PIN: hash, set, verify
    allowlist.py           # read/write allowlist.json, reload command
    backups.py             # safe world backup / restore / prune
    server_supervisor.py   # start/stop/restart BDS under Box64, stdin/stdout
    bedrockconnect.py      # supervise BedrockConnect, report DNS IP
    main.py                # FastAPI app factory: routes + websocket
    run.py                 # uvicorn entry point (wires real supervisors)
  web/
    index.html
    style.css
    app.js
  deploy/
    install.sh
    block-boss.service
  tests/
    __init__.py
    fake_bds.py            # fake server script for supervisor integration test
    test_config.py
    test_logging_setup.py
    test_log_parser.py
    test_auth.py
    test_allowlist.py
    test_backups.py
    test_server_supervisor.py
    test_bedrockconnect.py
    test_main.py
  requirements.txt
  requirements-dev.txt
  pytest.ini
  README.md
```

All commands below run from `block_boss/` unless stated. Use `uv` for installs (never pip).

---

### Task 1: Project scaffold

**Files:**
- Create: `block_boss/app/__init__.py`
- Create: `block_boss/tests/__init__.py`
- Create: `block_boss/requirements.txt`
- Create: `block_boss/requirements-dev.txt`
- Create: `block_boss/pytest.ini`

- [ ] **Step 1: Create package files**

`block_boss/app/__init__.py`:
```python
__version__ = "1.0.0"
```

`block_boss/tests/__init__.py`:
```python
```

`block_boss/requirements.txt`:
```
fastapi==0.115.*
uvicorn[standard]==0.30.*
```

`block_boss/requirements-dev.txt`:
```
-r requirements.txt
pytest==8.*
httpx==0.27.*
```

`block_boss/pytest.ini`:
```ini
[pytest]
testpaths = tests
python_files = test_*.py
addopts = -q
```

- [ ] **Step 2: Create virtualenv and install dev deps**

Run:
```bash
cd block_boss && uv venv && uv pip install -r requirements-dev.txt
```
Expected: venv created, FastAPI + pytest + httpx installed.

- [ ] **Step 3: Verify pytest runs (no tests yet)**

Run: `cd block_boss && uv run pytest`
Expected: "no tests ran" (exit 5) — confirms pytest is wired.

- [ ] **Step 4: Commit**

```bash
git add block_boss/app/__init__.py block_boss/tests/__init__.py block_boss/requirements.txt block_boss/requirements-dev.txt block_boss/pytest.ini
git commit -m "chore: scaffold block_boss package"
```

---

### Task 2: Config module

**Files:**
- Create: `block_boss/app/config.py`
- Test: `block_boss/tests/test_config.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_config.py`:
```python
from pathlib import Path
from app.config import Config

def test_load_derives_paths(tmp_path):
    cfg = Config.load(home=tmp_path)
    assert cfg.home == tmp_path
    assert cfg.bds_dir == tmp_path / "bedrock-server"
    assert cfg.worlds_dir == tmp_path / "bedrock-server" / "worlds"
    assert cfg.allowlist_path == tmp_path / "bedrock-server" / "allowlist.json"
    assert cfg.backups_dir == tmp_path / "backups"
    assert cfg.pin_file == tmp_path / "pin.hash"
    assert cfg.http_port == 8000
    assert cfg.backup_keep == 7
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_config.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.config'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/config.py`:
```python
from __future__ import annotations
import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_HOME = Path(os.environ.get("BLOCK_BOSS_HOME", str(Path.home() / "block_boss")))

@dataclass(frozen=True)
class Config:
    home: Path
    bds_dir: Path
    worlds_dir: Path
    backups_dir: Path
    logs_dir: Path
    allowlist_path: Path
    server_properties: Path
    box64_bin: str
    bds_executable: Path
    http_port: int
    pin_file: Path
    backup_keep: int

    @staticmethod
    def load(home: Path = DEFAULT_HOME) -> "Config":
        home = Path(home)
        bds_dir = home / "bedrock-server"
        return Config(
            home=home,
            bds_dir=bds_dir,
            worlds_dir=bds_dir / "worlds",
            backups_dir=home / "backups",
            logs_dir=home / "logs",
            allowlist_path=bds_dir / "allowlist.json",
            server_properties=bds_dir / "server.properties",
            box64_bin=os.environ.get("BOX64_BIN", "box64"),
            bds_executable=bds_dir / "bedrock_server",
            http_port=int(os.environ.get("BLOCK_BOSS_PORT", "8000")),
            pin_file=home / "pin.hash",
            backup_keep=int(os.environ.get("BLOCK_BOSS_BACKUP_KEEP", "7")),
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_config.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/config.py block_boss/tests/test_config.py
git commit -m "feat: config module with derived paths"
```

---

### Task 3: Diagnostic logger

**Files:**
- Create: `block_boss/app/logging_setup.py`
- Test: `block_boss/tests/test_logging_setup.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_logging_setup.py`:
```python
from app.logging_setup import DiagnosticLogger

def test_writes_state_and_crash_lines(tmp_path):
    log = DiagnosticLogger("blockboss", tmp_path)
    log.startup("1.0.0")
    log.state("init", "ready")
    try:
        raise ValueError("boom")
    except ValueError as e:
        log.crash(e)
    text = log.path.read_text(encoding="utf-8")
    assert "STARTUP blockboss v1.0.0" in text
    assert "STATE init->ready" in text
    assert "CRASH" in text and "boom" in text
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_logging_setup.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.logging_setup'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/logging_setup.py`:
```python
from __future__ import annotations
import logging
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path

def _ts() -> str:
    return datetime.now(timezone.utc).isoformat()

class DiagnosticLogger:
    def __init__(self, app_name: str, logs_dir: Path):
        logs_dir = Path(logs_dir)
        logs_dir.mkdir(parents=True, exist_ok=True)
        day = datetime.now().strftime("%Y-%m-%d")
        self.path = logs_dir / f"{app_name}_{day}.log"
        self.app_name = app_name
        self._logger = logging.getLogger(f"blockboss.{app_name}.{id(self)}")
        self._logger.setLevel(logging.INFO)
        self._logger.propagate = False
        handler = logging.FileHandler(self.path, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(message)s"))
        self._logger.addHandler(handler)

    def _emit(self, kind: str, msg: str) -> None:
        self._logger.info(f"{_ts()} {kind} {msg}")

    def startup(self, version: str) -> None:
        self._emit("STARTUP", f"{self.app_name} v{version} python={sys.version.split()[0]}")

    def state(self, frm: str, to: str) -> None:
        self._emit("STATE", f"{frm}->{to}")

    def decision(self, msg: str) -> None:
        self._emit("DECISION", msg)

    def perf(self, op: str, seconds: float) -> None:
        self._emit("PERF", f"{op}={seconds:.3f}s")

    def crash(self, exc: BaseException) -> None:
        tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
        self._emit("CRASH", tb.replace("\n", " | "))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_logging_setup.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/logging_setup.py block_boss/tests/test_logging_setup.py
git commit -m "feat: diagnostic logger with state/crash lines"
```

---

### Task 4: Log parser

**Files:**
- Create: `block_boss/app/log_parser.py`
- Test: `block_boss/tests/test_log_parser.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_log_parser.py`:
```python
from app.log_parser import parse_line, PlayerTracker

def test_parse_connect_disconnect_ready():
    c = parse_line("[2026-05-21 12:00 INFO] Player connected: Steve, xuid: 100")
    assert c.kind == "connect" and c.player == "Steve"
    d = parse_line("[2026-05-21 12:01 INFO] Player disconnected: Steve, xuid: 100")
    assert d.kind == "disconnect" and d.player == "Steve"
    r = parse_line("[2026-05-21 12:02 INFO] Server started.")
    assert r.kind == "ready"
    assert parse_line("[INFO] some noise") is None

def test_tracker_tracks_players_and_ready():
    t = PlayerTracker()
    t.apply(parse_line("Player connected: Steve, xuid: 1"))
    t.apply(parse_line("Player connected: Alex, xuid: 2"))
    t.apply(parse_line("Player disconnected: Steve, xuid: 1"))
    t.apply(parse_line("Server started."))
    assert t.players == ["Alex"]
    assert t.ready is True
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_log_parser.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.log_parser'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/log_parser.py`:
```python
from __future__ import annotations
import re
from dataclasses import dataclass
from typing import Optional

CONNECT_RE = re.compile(r"Player connected:\s*(?P<name>.+?),\s*xuid:\s*(?P<xuid>\d+)")
DISCONNECT_RE = re.compile(r"Player disconnected:\s*(?P<name>.+?),\s*xuid:\s*(?P<xuid>\d+)")
READY_RE = re.compile(r"Server started\.")

@dataclass(frozen=True)
class LogEvent:
    kind: str  # "connect" | "disconnect" | "ready"
    player: Optional[str] = None

def parse_line(line: str) -> Optional[LogEvent]:
    m = CONNECT_RE.search(line)
    if m:
        return LogEvent("connect", m.group("name").strip())
    m = DISCONNECT_RE.search(line)
    if m:
        return LogEvent("disconnect", m.group("name").strip())
    if READY_RE.search(line):
        return LogEvent("ready")
    return None

class PlayerTracker:
    def __init__(self) -> None:
        self._players: set[str] = set()
        self.ready = False

    def apply(self, event: Optional[LogEvent]) -> None:
        if event is None:
            return
        if event.kind == "connect" and event.player:
            self._players.add(event.player)
        elif event.kind == "disconnect" and event.player:
            self._players.discard(event.player)
        elif event.kind == "ready":
            self.ready = True

    @property
    def players(self) -> list[str]:
        return sorted(self._players)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_log_parser.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/log_parser.py block_boss/tests/test_log_parser.py
git commit -m "feat: BDS log parser and player tracker"
```

---

### Task 5: Parent PIN auth

**Files:**
- Create: `block_boss/app/auth.py`
- Test: `block_boss/tests/test_auth.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_auth.py`:
```python
import pytest
from app import auth

def test_set_and_verify_pin(tmp_path):
    pin_file = tmp_path / "pin.hash"
    assert auth.pin_is_set(pin_file) is False
    auth.set_pin("1234", pin_file)
    assert auth.pin_is_set(pin_file) is True
    assert auth.verify_pin("1234", pin_file) is True
    assert auth.verify_pin("0000", pin_file) is False

def test_pin_must_be_four_digits(tmp_path):
    pin_file = tmp_path / "pin.hash"
    with pytest.raises(ValueError):
        auth.set_pin("12", pin_file)
    with pytest.raises(ValueError):
        auth.set_pin("abcd", pin_file)

def test_verify_missing_file_is_false(tmp_path):
    assert auth.verify_pin("1234", tmp_path / "nope.hash") is False

def test_stored_pin_is_not_plaintext(tmp_path):
    pin_file = tmp_path / "pin.hash"
    auth.set_pin("4321", pin_file)
    assert "4321" not in pin_file.read_text(encoding="utf-8")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_auth.py -v`
Expected: FAIL with "ImportError" / "module 'app.auth' has no attribute 'set_pin'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/auth.py`:
```python
from __future__ import annotations
import hashlib
import hmac
import os
from pathlib import Path

_ITERATIONS = 200_000

def _hash(pin: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, _ITERATIONS)

def set_pin(pin: str, pin_file: Path) -> None:
    if not (pin.isdigit() and len(pin) == 4):
        raise ValueError("PIN must be exactly 4 digits")
    salt = os.urandom(16)
    digest = _hash(pin, salt)
    pin_file = Path(pin_file)
    pin_file.parent.mkdir(parents=True, exist_ok=True)
    pin_file.write_text(f"{salt.hex()}${digest.hex()}", encoding="utf-8")

def verify_pin(pin: str, pin_file: Path) -> bool:
    pin_file = Path(pin_file)
    if not pin_file.exists():
        return False
    raw = pin_file.read_text(encoding="utf-8").strip()
    try:
        salt_hex, digest_hex = raw.split("$", 1)
    except ValueError:
        return False
    expected = bytes.fromhex(digest_hex)
    actual = _hash(pin, bytes.fromhex(salt_hex))
    return hmac.compare_digest(expected, actual)

def pin_is_set(pin_file: Path) -> bool:
    return Path(pin_file).exists()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_auth.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/auth.py block_boss/tests/test_auth.py
git commit -m "feat: hashed parent PIN auth"
```

---

### Task 6: Allowlist management

**Files:**
- Create: `block_boss/app/allowlist.py`
- Test: `block_boss/tests/test_allowlist.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_allowlist.py`:
```python
import json
from app import allowlist

def test_add_list_remove_with_reload(tmp_path):
    path = tmp_path / "allowlist.json"
    sent = []
    send = sent.append

    allowlist.add_player(path, "Steve", send_command=send)
    allowlist.add_player(path, "Alex", send_command=send)
    allowlist.add_player(path, "Steve", send_command=send)  # duplicate ignored

    assert allowlist.list_players(path) == ["Alex", "Steve"]
    data = json.loads(path.read_text(encoding="utf-8"))
    assert {"ignoresPlayerLimit": False, "name": "Steve"} in data
    # reload issued on each real change (2 adds), duplicate did not change file
    assert sent.count("allowlist reload") == 2

    allowlist.remove_player(path, "Steve", send_command=send)
    assert allowlist.list_players(path) == ["Alex"]
    assert sent.count("allowlist reload") == 3

def test_list_missing_file_is_empty(tmp_path):
    assert allowlist.list_players(tmp_path / "none.json") == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_allowlist.py -v`
Expected: FAIL with "module 'app.allowlist' has no attribute 'add_player'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/allowlist.py`:
```python
from __future__ import annotations
import json
from pathlib import Path
from typing import Callable, Optional

def _read(path: Path) -> list[dict]:
    path = Path(path)
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return []
    return data if isinstance(data, list) else []

def _write(path: Path, entries: list[dict]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(entries, indent=2), encoding="utf-8")

def list_players(path: Path) -> list[str]:
    return sorted(e["name"] for e in _read(path) if "name" in e)

def add_player(path: Path, name: str,
               send_command: Optional[Callable[[str], None]] = None) -> None:
    name = name.strip()
    if not name:
        raise ValueError("name required")
    entries = _read(path)
    if any(e.get("name") == name for e in entries):
        return
    entries.append({"ignoresPlayerLimit": False, "name": name})
    _write(path, entries)
    if send_command:
        send_command("allowlist reload")

def remove_player(path: Path, name: str,
                  send_command: Optional[Callable[[str], None]] = None) -> None:
    entries = [e for e in _read(path) if e.get("name") != name]
    _write(path, entries)
    if send_command:
        send_command("allowlist reload")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_allowlist.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/allowlist.py block_boss/tests/test_allowlist.py
git commit -m "feat: allowlist add/remove/list with reload"
```

---

### Task 7: World backups

**Files:**
- Create: `block_boss/app/backups.py`
- Test: `block_boss/tests/test_backups.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_backups.py`:
```python
import pytest
from pathlib import Path
from app import backups

def _make_world(worlds: Path):
    (worlds / "Bedrock level").mkdir(parents=True)
    (worlds / "Bedrock level" / "level.dat").write_text("data", encoding="utf-8")

def test_backup_running_server_holds_and_resumes(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backups_dir = tmp_path / "backups"
    sent = []
    result = backups.make_backup(
        worlds, backups_dir,
        send_command=sent.append,
        is_save_ready=lambda: True,
        server_running=True,
    )
    assert result.path.is_dir()
    assert (result.path / "Bedrock level" / "level.dat").exists()
    assert sent == ["save hold", "save resume"]

def test_resume_runs_even_if_copy_fails(tmp_path):
    missing = tmp_path / "missing_worlds"  # does not exist -> copytree raises
    backups_dir = tmp_path / "backups"
    sent = []
    with pytest.raises(Exception):
        backups.make_backup(
            missing, backups_dir,
            send_command=sent.append,
            is_save_ready=lambda: True,
            server_running=True,
        )
    assert "save resume" in sent

def test_backup_stopped_server_skips_hold(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backups_dir = tmp_path / "backups"
    sent = []
    backups.make_backup(worlds, backups_dir, send_command=sent.append,
                        is_save_ready=lambda: True, server_running=False)
    assert sent == []

def test_prune_keeps_newest(tmp_path):
    backups_dir = tmp_path / "backups"; backups_dir.mkdir()
    for n in ["world-20260101-000000", "world-20260102-000000", "world-20260103-000000"]:
        (backups_dir / n).mkdir()
    removed = backups.prune_backups(backups_dir, keep=2)
    names = [p.name for p in backups.list_backups(backups_dir)]
    assert names == ["world-20260103-000000", "world-20260102-000000"]
    assert removed[0].name == "world-20260101-000000"

def test_restore_replaces_worlds(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backup = tmp_path / "backup"; backup.mkdir()
    (backup / "Bedrock level").mkdir()
    (backup / "Bedrock level" / "level.dat").write_text("restored", encoding="utf-8")
    backups.restore_backup(backup, worlds, server_running=False)
    assert (worlds / "Bedrock level" / "level.dat").read_text(encoding="utf-8") == "restored"

def test_restore_refuses_while_running(tmp_path):
    with pytest.raises(RuntimeError):
        backups.restore_backup(tmp_path / "b", tmp_path / "w", server_running=True)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_backups.py -v`
Expected: FAIL with "module 'app.backups' has no attribute 'make_backup'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/backups.py`:
```python
from __future__ import annotations
import shutil
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

@dataclass
class BackupResult:
    path: Path
    when: datetime

def make_backup(
    worlds_dir: Path,
    backups_dir: Path,
    send_command: Callable[[str], None],
    is_save_ready: Callable[[], bool],
    server_running: bool,
    poll_interval: float = 0.5,
    timeout: float = 30.0,
) -> BackupResult:
    worlds_dir = Path(worlds_dir)
    backups_dir = Path(backups_dir)
    backups_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now()
    dest = backups_dir / f"world-{stamp.strftime('%Y%m%d-%H%M%S')}"

    if not server_running:
        shutil.copytree(worlds_dir, dest)
        return BackupResult(dest, stamp)

    send_command("save hold")
    try:
        deadline = time.monotonic() + timeout
        while not is_save_ready():
            if time.monotonic() > deadline:
                raise TimeoutError("save query never reported ready")
            time.sleep(poll_interval)
        shutil.copytree(worlds_dir, dest)
    finally:
        send_command("save resume")
    return BackupResult(dest, stamp)

def list_backups(backups_dir: Path) -> list[Path]:
    backups_dir = Path(backups_dir)
    if not backups_dir.exists():
        return []
    return sorted((p for p in backups_dir.iterdir() if p.is_dir()), reverse=True)

def prune_backups(backups_dir: Path, keep: int) -> list[Path]:
    removed = []
    for old in list_backups(backups_dir)[keep:]:
        shutil.rmtree(old)
        removed.append(old)
    return removed

def restore_backup(backup_dir: Path, worlds_dir: Path, server_running: bool) -> None:
    if server_running:
        raise RuntimeError("stop the server before restoring")
    backup_dir = Path(backup_dir)
    worlds_dir = Path(worlds_dir)
    if worlds_dir.exists():
        shutil.rmtree(worlds_dir)
    shutil.copytree(backup_dir, worlds_dir)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_backups.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/backups.py block_boss/tests/test_backups.py
git commit -m "feat: safe world backup, prune, restore"
```

---

### Task 8: Server supervisor (integration)

**Files:**
- Create: `block_boss/app/server_supervisor.py`
- Create: `block_boss/tests/fake_bds.py`
- Test: `block_boss/tests/test_server_supervisor.py`

- [ ] **Step 1: Write the fake server script**

`block_boss/tests/fake_bds.py`:
```python
import sys

print("[INFO] Server started.", flush=True)
for line in sys.stdin:
    cmd = line.strip()
    if cmd == "spawn":
        print("[INFO] Player connected: Steve, xuid: 100", flush=True)
    elif cmd == "despawn":
        print("[INFO] Player disconnected: Steve, xuid: 100", flush=True)
    elif cmd == "save hold":
        print("[INFO] Saving...", flush=True)
    elif cmd == "save query":
        print("[INFO] Data saved. Files are now ready to be copied.", flush=True)
    elif cmd == "stop":
        break
sys.exit(0)
```

- [ ] **Step 2: Write the failing test**

`block_boss/tests/test_server_supervisor.py`:
```python
import sys
import time
from pathlib import Path
from app.server_supervisor import ServerSupervisor, Status

FAKE = Path(__file__).parent / "fake_bds.py"

def _wait(predicate, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(0.05)
    return False

def test_start_tracks_players_and_stops_clean(tmp_path):
    sup = ServerSupervisor([sys.executable, str(FAKE)], cwd=tmp_path)
    sup.start()
    assert _wait(lambda: sup.status == Status.RUNNING), sup.status
    sup.send_command("spawn")
    assert _wait(lambda: sup.players == ["Steve"]), sup.players
    sup.send_command("despawn")
    assert _wait(lambda: sup.players == [])
    sup.stop()
    assert sup.status == Status.STOPPED

def test_save_ready_flag(tmp_path):
    sup = ServerSupervisor([sys.executable, str(FAKE)], cwd=tmp_path)
    sup.start()
    assert _wait(lambda: sup.status == Status.RUNNING)
    assert sup.save_ready is False
    sup.send_command("save query")
    assert _wait(lambda: sup.save_ready is True)
    sup.stop()
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_server_supervisor.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.server_supervisor'"

- [ ] **Step 4: Write minimal implementation**

`block_boss/app/server_supervisor.py`:
```python
from __future__ import annotations
import subprocess
import threading
from enum import Enum
from pathlib import Path
from typing import Callable, Optional
from .log_parser import parse_line, PlayerTracker

class Status(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    CRASHED = "crashed"

_SAVE_READY_MARKERS = ("Data saved", "Files are now ready")

class ServerSupervisor:
    def __init__(self, launch_cmd: list[str], cwd: Path,
                 on_line: Optional[Callable[[str], None]] = None):
        self._launch_cmd = launch_cmd
        self._cwd = Path(cwd)
        self._on_line = on_line
        self._proc: Optional[subprocess.Popen] = None
        self._tracker = PlayerTracker()
        self._status = Status.STOPPED
        self._save_ready = False
        self._lock = threading.Lock()

    @property
    def status(self) -> Status:
        return self._status

    @property
    def players(self) -> list[str]:
        return self._tracker.players

    @property
    def save_ready(self) -> bool:
        return self._save_ready

    def start(self) -> None:
        with self._lock:
            if self._proc and self._proc.poll() is None:
                return
            self._tracker = PlayerTracker()
            self._save_ready = False
            self._status = Status.STARTING
            self._proc = subprocess.Popen(
                self._launch_cmd, cwd=str(self._cwd),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
            )
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self) -> None:
        proc = self._proc
        if not proc or not proc.stdout:
            return
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            if any(marker in line for marker in _SAVE_READY_MARKERS):
                self._save_ready = True
            event = parse_line(line)
            self._tracker.apply(event)
            if event and event.kind == "ready":
                self._status = Status.RUNNING
            if self._on_line:
                self._on_line(line)
        code = proc.poll()
        self._status = Status.STOPPED if code == 0 else Status.CRASHED

    def send_command(self, cmd: str) -> None:
        proc = self._proc
        if proc and proc.stdin and proc.poll() is None:
            proc.stdin.write(cmd + "\n")
            proc.stdin.flush()

    def stop(self, timeout: float = 20.0) -> None:
        proc = self._proc
        if not proc or proc.poll() is not None:
            self._status = Status.STOPPED
            return
        self.send_command("stop")
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
        self._status = Status.STOPPED

    def restart(self) -> None:
        self.stop()
        self.start()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_server_supervisor.py -v`
Expected: PASS (both tests)

- [ ] **Step 6: Commit**

```bash
git add block_boss/app/server_supervisor.py block_boss/tests/fake_bds.py block_boss/tests/test_server_supervisor.py
git commit -m "feat: bedrock server supervisor with live player tracking"
```

---

### Task 9: BedrockConnect supervisor

**Files:**
- Create: `block_boss/app/bedrockconnect.py`
- Test: `block_boss/tests/test_bedrockconnect.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_bedrockconnect.py`:
```python
import sys
import time
from app.bedrockconnect import BedrockConnectSupervisor, lan_ip

def test_lan_ip_returns_string():
    ip = lan_ip()
    assert isinstance(ip, str)
    assert ip.count(".") == 3

def test_supervisor_lifecycle_and_status(tmp_path):
    cmd = [sys.executable, "-c", "import time; time.sleep(5)"]
    bc = BedrockConnectSupervisor(cmd, cwd=tmp_path)
    assert bc.running is False
    bc.start()
    time.sleep(0.3)
    assert bc.running is True
    status = bc.status()
    assert status["running"] is True
    assert "dns_ip" in status
    bc.stop()
    time.sleep(0.3)
    assert bc.running is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_bedrockconnect.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.bedrockconnect'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/bedrockconnect.py`:
```python
from __future__ import annotations
import socket
import subprocess
from pathlib import Path
from typing import Optional

def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()

class BedrockConnectSupervisor:
    def __init__(self, launch_cmd: list[str], cwd: Path):
        self._launch_cmd = launch_cmd
        self._cwd = Path(cwd)
        self._proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        if self._proc and self._proc.poll() is None:
            return
        self._proc = subprocess.Popen(self._launch_cmd, cwd=str(self._cwd))

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()

    @property
    def running(self) -> bool:
        return bool(self._proc and self._proc.poll() is None)

    def status(self) -> dict:
        return {"running": self.running, "dns_ip": lan_ip()}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_bedrockconnect.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add block_boss/app/bedrockconnect.py block_boss/tests/test_bedrockconnect.py
git commit -m "feat: bedrockconnect supervisor and LAN IP detection"
```

---

### Task 10: FastAPI app + endpoints

**Files:**
- Create: `block_boss/app/main.py`
- Test: `block_boss/tests/test_main.py`

- [ ] **Step 1: Write the failing test**

`block_boss/tests/test_main.py`:
```python
from fastapi.testclient import TestClient
from app import auth
from app.config import Config
from app.main import create_app
from app.server_supervisor import Status

class FakeServer:
    def __init__(self):
        self.status = Status.STOPPED
        self.players = []
        self.save_ready = True
        self.commands = []
    def start(self): self.status = Status.RUNNING
    def stop(self): self.status = Status.STOPPED
    def restart(self): self.status = Status.RUNNING
    def send_command(self, c): self.commands.append(c)

class FakeBC:
    def status(self): return {"running": True, "dns_ip": "192.168.1.50"}

def _client(tmp_path):
    cfg = Config.load(home=tmp_path)
    cfg.allowlist_path.parent.mkdir(parents=True, exist_ok=True)
    return cfg, TestClient(create_app(cfg, FakeServer(), FakeBC()))

def test_status_starts_stopped(tmp_path):
    _, client = _client(tmp_path)
    body = client.get("/api/status").json()
    assert body["status"] == "stopped"
    assert body["pin_set"] is False

def test_start_is_open_no_pin(tmp_path):
    _, client = _client(tmp_path)
    r = client.post("/api/start")
    assert r.status_code == 200
    assert r.json()["status"] == "running"

def test_stop_requires_pin(tmp_path):
    cfg, client = _client(tmp_path)
    assert client.post("/api/stop", json={"pin": ""}).status_code == 403
    auth.set_pin("1234", cfg.pin_file)
    assert client.post("/api/stop", json={"pin": "0000"}).status_code == 403
    assert client.post("/api/stop", json={"pin": "1234"}).status_code == 200

def test_switch_status(tmp_path):
    _, client = _client(tmp_path)
    body = client.get("/api/switch").json()
    assert body["running"] is True
    assert body["dns_ip"] == "192.168.1.50"

def test_allowlist_add_requires_pin_then_lists(tmp_path):
    cfg, client = _client(tmp_path)
    auth.set_pin("1234", cfg.pin_file)
    assert client.post("/api/allowlist/add", json={"name": "Alex", "pin": "x"}).status_code == 403
    r = client.post("/api/allowlist/add", json={"name": "Alex", "pin": "1234"})
    assert r.status_code == 200
    assert client.get("/api/allowlist").json()["players"] == ["Alex"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd block_boss && uv run pytest tests/test_main.py -v`
Expected: FAIL with "ModuleNotFoundError: No module named 'app.main'"

- [ ] **Step 3: Write minimal implementation**

`block_boss/app/main.py`:
```python
from __future__ import annotations
from pathlib import Path
from typing import Optional
from fastapi import FastAPI, HTTPException, Body, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from . import auth, allowlist, backups
from .config import Config
from .server_supervisor import Status

WEB_DIR = Path(__file__).resolve().parent.parent / "web"

def _require_pin(cfg: Config, pin: Optional[str]) -> None:
    if not auth.verify_pin(pin or "", cfg.pin_file):
        raise HTTPException(status_code=403, detail="Bad or missing parent PIN")

def create_app(cfg: Config, server, bc) -> FastAPI:
    app = FastAPI(title="Block Boss")

    @app.get("/api/status")
    def status():
        return {"status": server.status.value, "players": server.players,
                "pin_set": auth.pin_is_set(cfg.pin_file)}

    @app.post("/api/start")
    def start():
        server.start()
        return {"ok": True, "status": server.status.value}

    @app.post("/api/stop")
    def stop(pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin)
        server.stop()
        return {"ok": True, "status": server.status.value}

    @app.post("/api/restart")
    def restart(pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin)
        server.restart()
        return {"ok": True, "status": server.status.value}

    @app.get("/api/players")
    def players():
        return {"players": server.players}

    @app.post("/api/backup")
    def backup():
        result = backups.make_backup(
            cfg.worlds_dir, cfg.backups_dir,
            send_command=server.send_command,
            is_save_ready=lambda: server.save_ready,
            server_running=server.status == Status.RUNNING,
        )
        backups.prune_backups(cfg.backups_dir, cfg.backup_keep)
        return {"ok": True, "backup": result.path.name, "when": result.when.isoformat()}

    @app.get("/api/backups")
    def list_backups_route():
        return {"backups": [p.name for p in backups.list_backups(cfg.backups_dir)]}

    @app.post("/api/restore")
    def restore(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin)
        src = cfg.backups_dir / name
        if not src.is_dir():
            raise HTTPException(status_code=404, detail="backup not found")
        if server.status == Status.RUNNING:
            server.stop()
        backups.restore_backup(src, cfg.worlds_dir, server_running=False)
        return {"ok": True}

    @app.get("/api/allowlist")
    def get_allowlist():
        return {"players": allowlist.list_players(cfg.allowlist_path)}

    @app.post("/api/allowlist/add")
    def allowlist_add(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin)
        allowlist.add_player(cfg.allowlist_path, name, send_command=server.send_command)
        return {"ok": True, "players": allowlist.list_players(cfg.allowlist_path)}

    @app.post("/api/allowlist/remove")
    def allowlist_remove(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin)
        allowlist.remove_player(cfg.allowlist_path, name, send_command=server.send_command)
        return {"ok": True, "players": allowlist.list_players(cfg.allowlist_path)}

    @app.get("/api/switch")
    def switch():
        return bc.status()

    @app.post("/api/pin")
    def set_pin(new_pin: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        if auth.pin_is_set(cfg.pin_file):
            _require_pin(cfg, pin)
        auth.set_pin(new_pin, cfg.pin_file)
        return {"ok": True}

    @app.websocket("/ws")
    async def ws(websocket: WebSocket):
        import asyncio
        await websocket.accept()
        try:
            while True:
                await websocket.send_json(
                    {"status": server.status.value, "players": server.players})
                await asyncio.sleep(2)
        except WebSocketDisconnect:
            return

    if WEB_DIR.exists():
        @app.get("/")
        def index():
            return FileResponse(str(WEB_DIR / "index.html"))
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")

    return app
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd block_boss && uv run pytest tests/test_main.py -v`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Run the full suite**

Run: `cd block_boss && uv run pytest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add block_boss/app/main.py block_boss/tests/test_main.py
git commit -m "feat: FastAPI app with PIN-gated endpoints and websocket"
```

---

### Task 11: Web dashboard (frontend)

**Files:**
- Create: `block_boss/web/index.html`
- Create: `block_boss/web/style.css`
- Create: `block_boss/web/app.js`
- Create: `block_boss/app/run.py` (entry point for uvicorn)

> **Security note:** Player gamertags are untrusted text. The frontend builds list
> items with `document.createElement` + `textContent` — never `innerHTML` with a
> name interpolated in — to avoid stored XSS.

- [ ] **Step 1: Create the entry point**

`block_boss/app/run.py`:
```python
from __future__ import annotations
from .config import Config
from .logging_setup import DiagnosticLogger
from .server_supervisor import ServerSupervisor
from .bedrockconnect import BedrockConnectSupervisor
from .main import create_app
from . import __version__

cfg = Config.load()
log = DiagnosticLogger("blockboss", cfg.logs_dir)
log.startup(__version__)

server = ServerSupervisor(
    [cfg.box64_bin, str(cfg.bds_executable)],
    cwd=cfg.bds_dir,
    on_line=lambda line: log.decision(f"bds: {line}") if "ERROR" in line else None,
)
bc = BedrockConnectSupervisor(
    ["java", "-jar", str(cfg.home / "bedrockconnect" / "BedrockConnect.jar"),
     "nodns=false"],
    cwd=cfg.home / "bedrockconnect",
)

app = create_app(cfg, server, bc)
```

- [ ] **Step 2: Create the dashboard HTML**

`block_boss/web/index.html`:
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Block Boss</title>
  <link rel="stylesheet" href="/static/style.css" />
</head>
<body>
  <h1>Block Boss</h1>

  <section class="card">
    <div id="light" class="light grey"></div>
    <div id="statusText">Checking...</div>
    <div class="big-buttons">
      <button id="startBtn" class="btn green">Start</button>
      <button id="stopBtn" class="btn red">Stop</button>
      <button id="restartBtn" class="btn amber">Restart</button>
    </div>
  </section>

  <section class="card">
    <h2>Players online</h2>
    <ul id="players"><li class="muted">nobody yet</li></ul>
  </section>

  <section class="card">
    <h2>Backup</h2>
    <button id="backupBtn" class="btn blue">Save a backup now</button>
    <div id="backupMsg" class="muted"></div>
  </section>

  <section class="card">
    <h2>Approved players</h2>
    <ul id="allowlist"></ul>
    <input id="newPlayer" placeholder="gamertag" />
    <button id="addPlayerBtn" class="btn blue">Add</button>
  </section>

  <section class="card">
    <h2>Switch setup</h2>
    <div id="switchLight" class="light grey small"></div>
    <p>On the Switch: Internet Settings -> your WiFi -> Change Settings ->
       DNS Settings -> Manual -> Primary DNS:</p>
    <p class="dns" id="dnsIp">...</p>
    <p class="muted">Then open any Featured Server and pick this one from the menu.</p>
  </section>

  <dialog id="pinDialog">
    <form method="dialog">
      <p>Enter parent PIN</p>
      <input id="pinInput" type="password" inputmode="numeric" maxlength="4" />
      <menu>
        <button value="cancel">Cancel</button>
        <button id="pinOk" value="ok">OK</button>
      </menu>
    </form>
  </dialog>

  <script src="/static/app.js"></script>
</body>
</html>
```

- [ ] **Step 3: Create the stylesheet**

`block_boss/web/style.css`:
```css
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0 auto; padding: 16px;
       background: #f0f4f8; color: #1b2733; max-width: 680px; }
h1 { text-align: center; }
.card { background: #fff; border-radius: 16px; padding: 16px; margin: 12px 0;
        box-shadow: 0 2px 8px rgba(0,0,0,.08); }
.big-buttons { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 12px; }
.btn { flex: 1 1 30%; font-size: 1.4rem; padding: 18px; border: none;
       border-radius: 14px; color: #fff; cursor: pointer; min-width: 120px; }
.btn:active { transform: scale(.97); }
.green { background: #2e7d32; } .red { background: #c62828; }
.amber { background: #f57f17; } .blue { background: #1565c0; }
.light { width: 28px; height: 28px; border-radius: 50%; display: inline-block;
         vertical-align: middle; margin-right: 8px; }
.light.small { width: 18px; height: 18px; }
.grey { background: #9e9e9e; } .yellow { background: #fdd835; }
.lightgreen { background: #43a047; } .lightred { background: #e53935; }
#statusText { display: inline-block; font-size: 1.2rem; font-weight: 600; }
.muted { color: #78909c; }
.dns { font-size: 1.8rem; font-weight: 700; letter-spacing: 1px; }
ul { padding-left: 20px; }
input { font-size: 1.1rem; padding: 8px; border-radius: 8px; border: 1px solid #b0bec5; }
```

- [ ] **Step 4: Create the frontend script (XSS-safe DOM building)**

`block_boss/web/app.js`:
```javascript
const $ = (id) => document.getElementById(id);

function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }

function li(text, cls) {
  const e = document.createElement("li");
  e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function askPin() {
  return new Promise((resolve) => {
    const dlg = $("pinDialog");
    $("pinInput").value = "";
    dlg.showModal();
    dlg.addEventListener("close", function handler() {
      dlg.removeEventListener("close", handler);
      resolve(dlg.returnValue === "ok" ? $("pinInput").value : null);
    });
  });
}

async function post(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : null,
  });
  if (res.status === 403) { alert("Wrong PIN"); return null; }
  return res.json();
}

async function postWithPin(path, extra = {}) {
  const pin = await askPin();
  if (pin === null) return null;
  return post(path, { ...extra, pin });
}

function renderStatus(s) {
  const map = { stopped: "grey", starting: "yellow", running: "lightgreen", crashed: "lightred" };
  $("light").className = "light " + (map[s.status] || "grey");
  $("statusText").textContent = {
    stopped: "Stopped", starting: "Starting...", running: "Running!", crashed: "Crashed",
  }[s.status] || s.status;
  const ul = $("players");
  clear(ul);
  if (s.players && s.players.length) {
    s.players.forEach((p) => ul.appendChild(li(p)));
  } else {
    ul.appendChild(li("nobody yet", "muted"));
  }
}

async function refreshAllowlist() {
  const data = await (await fetch("/api/allowlist")).json();
  const ul = $("allowlist");
  clear(ul);
  if (!data.players.length) { ul.appendChild(li("no one yet", "muted")); return; }
  data.players.forEach((p) => {
    const row = document.createElement("li");
    row.appendChild(document.createTextNode(p + " "));
    const btn = document.createElement("button");
    btn.className = "rm";
    btn.textContent = "remove";
    btn.addEventListener("click", async () => {
      await postWithPin("/api/allowlist/remove", { name: p });
      refreshAllowlist();
    });
    row.appendChild(btn);
    ul.appendChild(row);
  });
}

async function refreshSwitch() {
  const data = await (await fetch("/api/switch")).json();
  $("switchLight").className = "light small " + (data.running ? "lightgreen" : "lightred");
  $("dnsIp").textContent = data.dns_ip;
}

$("startBtn").onclick = () => post("/api/start");
$("stopBtn").onclick = () => postWithPin("/api/stop");
$("restartBtn").onclick = () => postWithPin("/api/restart");
$("backupBtn").onclick = async () => {
  $("backupMsg").textContent = "Saving...";
  const r = await post("/api/backup");
  $("backupMsg").textContent = r ? `Saved: ${r.backup}` : "Backup failed";
};
$("addPlayerBtn").onclick = async () => {
  const name = $("newPlayer").value.trim();
  if (!name) return;
  await postWithPin("/api/allowlist/add", { name });
  $("newPlayer").value = "";
  refreshAllowlist();
};

function connectWs() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onmessage = (e) => renderStatus(JSON.parse(e.data));
  ws.onclose = () => setTimeout(connectWs, 2000);
}

(async function init() {
  renderStatus(await (await fetch("/api/status")).json());
  await refreshAllowlist();
  await refreshSwitch();
  setInterval(refreshSwitch, 10000);
  connectWs();
})();
```

- [ ] **Step 5: Manual smoke test (local dev machine)**

Run: `cd block_boss && uv run uvicorn app.run:app --port 8000`
Then open `http://localhost:8000`.
Expected: dashboard loads, status light grey "Stopped" (no real BDS off-Pi — Start
flips to "Crashed" locally, which is correct). Buttons render big; PIN dialog opens
for Stop. Full server lifecycle is verified on the Pi in Task 12.

- [ ] **Step 6: Commit**

```bash
git add block_boss/web/index.html block_boss/web/style.css block_boss/web/app.js block_boss/app/run.py
git commit -m "feat: kid-friendly web dashboard and uvicorn entry point"
```

---

### Task 12: Deploy (install script + systemd) and on-Pi verification

**Files:**
- Create: `block_boss/deploy/install.sh`
- Create: `block_boss/deploy/block-boss.service`

- [ ] **Step 1: Create the install script**

`block_boss/deploy/install.sh`:
```bash
#!/usr/bin/env bash
# Block Boss installer for Raspberry Pi OS 64-bit (ARM64).
# Installs Box64, the Bedrock Dedicated Server, Java + BedrockConnect, and Python deps.
set -euo pipefail

HOME_DIR="${BLOCK_BOSS_HOME:-$HOME/block_boss}"
BDS_DIR="$HOME_DIR/bedrock-server"
BC_DIR="$HOME_DIR/bedrockconnect"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> Block Boss install into $HOME_DIR"
mkdir -p "$HOME_DIR" "$BDS_DIR" "$BC_DIR" "$HOME_DIR/backups" "$HOME_DIR/logs"

# 1. System packages
sudo apt-get update
sudo apt-get install -y curl unzip openjdk-17-jre-headless ca-certificates

# 2. Box64 (ARM64) — confirm current install steps at https://github.com/ptitSeb/box64
if ! command -v box64 >/dev/null 2>&1; then
  echo ">> Installing Box64"
  sudo apt-get install -y git build-essential cmake
  tmp="$(mktemp -d)"; git clone https://github.com/ptitSeb/box64 "$tmp"
  cmake -S "$tmp" -B "$tmp/build" -DRPI5ARM64=1 -DCMAKE_BUILD_TYPE=RelWithDebInfo
  make -C "$tmp/build" -j4
  sudo make -C "$tmp/build" install
fi

# 3. Bedrock Dedicated Server — download the current Linux build from minecraft.net
#    (URL changes per release; set BDS_URL to the latest Linux server zip).
if [ ! -f "$BDS_DIR/bedrock_server" ]; then
  : "${BDS_URL:?Set BDS_URL to the current Bedrock Dedicated Server Linux zip URL from minecraft.net/download/server/bedrock}"
  echo ">> Downloading Bedrock Dedicated Server"
  curl -fSL "$BDS_URL" -o /tmp/bds.zip
  unzip -o /tmp/bds.zip -d "$BDS_DIR"
  chmod +x "$BDS_DIR/bedrock_server"
fi

# enable allowlist in server.properties
if grep -q '^allow-list=' "$BDS_DIR/server.properties" 2>/dev/null; then
  sed -i 's/^allow-list=.*/allow-list=true/' "$BDS_DIR/server.properties"
else
  echo 'allow-list=true' >> "$BDS_DIR/server.properties"
fi

# 4. BedrockConnect — Pugmatt's build (confirm latest jar at github.com/Pugmatt/BedrockConnect)
if [ ! -f "$BC_DIR/BedrockConnect.jar" ]; then
  : "${BEDROCKCONNECT_URL:?Set BEDROCKCONNECT_URL to the latest BedrockConnect jar release}"
  echo ">> Downloading BedrockConnect"
  curl -fSL "$BEDROCKCONNECT_URL" -o "$BC_DIR/BedrockConnect.jar"
fi
# allow binding DNS port 53 without root
sudo setcap 'cap_net_bind_service=+ep' "$(readlink -f "$(command -v java)")" || true

# 5. Python deps via uv
cd "$REPO_DIR"
command -v uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh
uv venv
uv pip install -r requirements.txt

echo ">> Done. Install the service with:"
echo "   sudo cp deploy/block-boss.service /etc/systemd/system/block-boss@.service"
echo "   sudo systemctl enable --now block-boss@$USER"
```

- [ ] **Step 2: Create the systemd unit**

`block_boss/deploy/block-boss.service`:
```ini
[Unit]
Description=Block Boss — Minecraft Bedrock server manager
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=%i
WorkingDirectory=/home/%i/AI/block_boss
Environment=BLOCK_BOSS_HOME=/home/%i/block_boss
ExecStart=/home/%i/AI/block_boss/.venv/bin/uvicorn app.run:app --host 0.0.0.0 --port 8000
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

> Note: this is a templated unit, installed as `block-boss@.service`. Enable with
> `sudo systemctl enable --now block-boss@<your-pi-username>`. Adjust
> `WorkingDirectory`/paths if the repo lives elsewhere on the Pi.

- [ ] **Step 3: On-Pi verification (manual, run on the Pi)**

1. `BDS_URL=<latest> BEDROCKCONNECT_URL=<latest> bash deploy/install.sh`
2. Install + start the templated service; open `http://<pi-ip>:8000` from the PC browser.
3. Set a parent PIN: `curl -X POST http://<pi-ip>:8000/api/pin -H "Content-Type: application/json" -d '{"new_pin":"1234"}'`
4. Press **Start** -> status light goes green within ~30s.
5. From a phone (Bedrock), add server `<pi-ip>:19132` -> join -> confirm name appears in Players.
6. On the Switch, set Primary DNS to the Pi IP shown in the Switch panel -> open a Featured Server -> pick this server from the BedrockConnect menu -> join.
7. Press **Backup** -> confirm a `world-*` folder appears under `~/block_boss/backups/`.
8. Add a gamertag to Approved players (PIN) -> confirm `allowlist.json` updated.

- [ ] **Step 4: Commit**

```bash
git add block_boss/deploy/install.sh block_boss/deploy/block-boss.service
git commit -m "feat: Pi installer and systemd service for block boss"
```

---

### Task 13: Documentation quartet + README

**Files:**
- Create: `block_boss/README.md`
- Create: `docs/2026-05-21_block-boss_BREAKDOWN.md`
- Create: `docs/2026-05-21_block-boss_HANDOFF.md`
- Create: `docs/2026-05-21_block-boss_TUTORIAL.md`
- Create: `docs/2026-05-21_block-boss_PROOF.md`

> Follow the workspace project-docs rule and the templates in
> `memory/breakdown_template.md`, `memory/tutorial_template.md`,
> `memory/handoff_template.md`. This is a Linux/Pi web service — the exe-packaging
> rule does NOT apply (no `.exe`); the shipped artifact is the systemd service.

- [ ] **Step 1: Write `block_boss/README.md`**

Sections (real content, no placeholders): What it is (one paragraph, plain English);
Hardware (Pi 5 8GB, Pi OS 64-bit); Install (the two env vars + `install.sh`, then the
systemd enable command); Daily use (open `http://<pi-ip>:8000`, big buttons);
Switch setup (DNS steps); Backups location; Parent PIN; Troubleshooting (server won't
start -> check `~/block_boss/logs/`, Switch can't see server -> BedrockConnect light).

- [ ] **Step 2: Write the BREAKDOWN** following `memory/breakdown_template.md` — module
  map (the 10 app modules + web + deploy), data flow, why Box64 + BedrockConnect.

- [ ] **Step 3: Write the TUTORIAL** following `memory/tutorial_template.md` — a parent's
  first-run walkthrough and a kid's "how to start the server" with the big green button.

- [ ] **Step 4: Write the HANDOFF** following `memory/handoff_template.md` — Goals,
  History, what's done vs. not (on-Pi manual steps remain), Credit & Authorship.

- [ ] **Step 5: Write the PROOF** — plain-language record: what was built, that tests
  pass (`uv run pytest`), and the on-Pi verification checklist from Task 12.

- [ ] **Step 6: Commit**

```bash
git add block_boss/README.md docs/2026-05-21_block-boss_*.md
git commit -m "docs: block boss README and BREAKDOWN/HANDOFF/TUTORIAL/PROOF quartet"
```

---

## Final verification

- [ ] Run full suite: `cd block_boss && uv run pytest` — all tests PASS.
- [ ] Complete the on-Pi manual checklist (Task 12, Step 3).
- [ ] Confirm the dashboard loads and the kid can start the server with one button.

---

## Self-review notes

- **Spec coverage:** Start/Stop/Restart (T8,T10,T11); player count (T4,T8,T10,T11);
  backup + nightly prune + restore (T7,T10); allowlist (T6,T10,T11); parent PIN gating
  (T5,T10,T11); BedrockConnect + Switch DNS panel (T9,T10,T11); diagnostic logging (T3);
  systemd auto-start/restart (T12); install of Box64+BDS+BedrockConnect (T12); error
  handling — server-not-installed banner via crashed status, resume-on-failure backup,
  DNS bind capability (T7,T11,T12); docs quartet (T13). Nightly auto-backup scheduler is
  deferred to a v1.1 note (manual + on-demand backup ships in v1; not in the task list to
  keep scope tight — flag for the executor if the user wants it now).
- **Placeholder scan:** install.sh uses required env vars (`BDS_URL`, `BEDROCKCONNECT_URL`)
  rather than guessed URLs, because release URLs change — these are explicit inputs, not
  placeholders.
- **Type consistency:** `ServerSupervisor` exposes `.status` (Status enum), `.players`,
  `.save_ready`, `.start/.stop/.restart/.send_command`; `FakeServer` in T10 mirrors these;
  `create_app(cfg, server, bc)` signature matches `run.py` and the test. `backups.make_backup`
  signature identical in T7 impl and T10 caller.
