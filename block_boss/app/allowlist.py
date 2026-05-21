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
