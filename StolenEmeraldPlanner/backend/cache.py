import json
import os
import sqlite3
import time
from pathlib import Path

from backend import config, diagnostics

_sig_cache = {"t": 0.0, "sig": ""}


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
    """Fingerprint the repo so the UI can detect changes and auto-refresh.

    Covers: wild_encounters.json (mtime+size), and every map.json via a count
    (catches add/remove) + newest mtime (catches content edits — a directory's
    own mtime does NOT change when a file inside it is edited). Memoized ~2s so
    the 5s status poll doesn't re-walk ~940 files every tick.
    """
    now = time.monotonic()
    if _sig_cache["sig"] and (now - _sig_cache["t"]) < 2.0:
        return _sig_cache["sig"]

    repo = config.repo_path()
    parts = []
    enc = repo / "src" / "data" / "wild_encounters.json"
    try:
        st = enc.stat()
        parts.append(f"enc:{int(st.st_mtime)}:{st.st_size}")
    except OSError:
        parts.append("enc:missing")

    maps_dir = repo / "data" / "maps"
    latest = 0
    count = 0
    if maps_dir.is_dir():
        try:
            for entry in os.scandir(maps_dir):
                if not entry.is_dir():
                    continue
                try:
                    st = os.stat(os.path.join(entry.path, "map.json"))
                    latest = max(latest, int(st.st_mtime))
                    count += 1
                except OSError:
                    continue
        except OSError:
            pass
    parts.append(f"maps:{count}:{latest}")

    sig = "|".join(parts)
    _sig_cache.update(t=now, sig=sig)
    return sig


def set_payload(key: str, payload: dict, signature: str) -> None:
    con = _db()
    try:
        con.execute(
            "INSERT OR REPLACE INTO payloads (key, sig, json) VALUES (?,?,?)",
            (key, signature, json.dumps(payload)),
        )
        con.commit()
    except sqlite3.Error as e:
        diagnostics.log("CRASH", f"cache set_payload failed key={key} err={e}")
    finally:
        con.close()


def get_payload(key: str):
    con = _db()
    try:
        row = con.execute("SELECT json FROM payloads WHERE key=?", (key,)).fetchone()
    except sqlite3.Error as e:
        diagnostics.log("CRASH", f"cache get_payload failed key={key} err={e}")
        return None
    finally:
        con.close()
    if not row:
        return None
    try:
        return json.loads(row[0])
    except (ValueError, TypeError) as e:
        diagnostics.log("CRASH", f"cache payload corrupt key={key} err={e}")
        return None  # caller treats None as a miss and rebuilds


def is_stale(key: str, signature: str) -> bool:
    con = _db()
    try:
        row = con.execute("SELECT sig FROM payloads WHERE key=?", (key,)).fetchone()
    except sqlite3.Error as e:
        diagnostics.log("CRASH", f"cache is_stale failed key={key} err={e}")
        return True  # safe fallback: force a rebuild
    finally:
        con.close()
    return row is None or row[0] != signature
