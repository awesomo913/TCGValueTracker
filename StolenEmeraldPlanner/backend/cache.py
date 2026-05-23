import json
import sqlite3
from pathlib import Path

from backend import config


def _db() -> sqlite3.Connection:
    con = sqlite3.connect(config.app_data_dir() / "cache.db")
    con.execute(
        "CREATE TABLE IF NOT EXISTS payloads (key TEXT PRIMARY KEY, sig TEXT, json TEXT)"
    )
    return con


def _signature(paths) -> str:
    parts = []
    for p in sorted(paths, key=lambda x: str(x)):
        try:
            st = p.stat()
            parts.append(f"{p}:{int(st.st_mtime)}:{st.st_size}")
        except OSError:
            parts.append(f"{p}:missing")
    return "|".join(parts)


def repo_signature() -> str:
    repo = config.repo_path()
    return _signature(
        [repo / "src" / "data" / "wild_encounters.json", repo / "data" / "maps"]
    )


def set_payload(key: str, payload: dict, signature: str) -> None:
    con = _db()
    con.execute(
        "INSERT OR REPLACE INTO payloads (key, sig, json) VALUES (?,?,?)",
        (key, signature, json.dumps(payload)),
    )
    con.commit()
    con.close()


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
