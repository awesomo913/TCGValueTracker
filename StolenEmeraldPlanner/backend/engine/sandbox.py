"""Sandbox design store. Holds the user's *proposed* design changes (route
layout placements, NPC script rewrites, story beats) so they can show "what we
want instead of what is".

CRITICAL: writes ONLY to the Planner's own app-data dir, NEVER to the
StolenEmerald repo. The sandbox is a proposal generator (exportable JSON); the
repo is never modified by this tool.
"""
import json

from backend import config

_EMPTY = {"layout": {}, "scripts": {}, "story": []}
_MAX_BYTES = 5 * 1024 * 1024  # generous cap for a local design file


def _path():
    return config.app_data_dir() / "sandbox.json"


def load() -> dict:
    p = _path()
    if not p.is_file():
        return dict(_EMPTY)
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return dict(_EMPTY)
    if not isinstance(d, dict):
        return dict(_EMPTY)
    d.setdefault("layout", {})
    d.setdefault("scripts", {})
    d.setdefault("story", [])
    return d


def save(data: dict) -> None:
    if not isinstance(data, dict):
        raise ValueError("sandbox must be a JSON object")
    blob = json.dumps(data, indent=2)
    if len(blob.encode("utf-8")) > _MAX_BYTES:
        raise ValueError("sandbox too large")
    _path().write_text(blob, encoding="utf-8")  # app-data only, never the repo
