"""Resolve an object_event graphics id (e.g. OBJ_EVENT_GFX_YOUNGSTER) to its
overworld sprite-sheet PNG, by parsing the decomp's object_event_graphics.h
INCBIN lines. Best-effort name match (gfx id -> Pic symbol); returns None if
unresolved so the UI can show a placeholder. Read-only.
"""
import re
from pathlib import Path

# gObjectEventPic_Youngster[] = INCBIN_U32("graphics/object_events/pics/people/youngster.4bpp", ...)
_PIC = re.compile(r"gObjectEventPic_(\w+)\[\]\s*=\s*INCBIN_\w+\(\s*\"([^\"]+)\"")
_cache = {"mtime": None, "map": None}


def _pic_map(repo: Path) -> dict:
    path = repo / "src" / "data" / "object_events" / "object_event_graphics.h"
    if not path.is_file():
        return {}
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return {}
    if _cache["map"] is not None and _cache["mtime"] == mtime:
        return _cache["map"]
    text = path.read_text(encoding="utf-8", errors="ignore")
    out = {}
    for name, incpath in _PIC.findall(text):
        png = re.sub(r"\.(4bpp|8bpp)$", ".png", incpath)
        out[name.lower()] = png  # key by lowercased symbol for matching
    _cache.update(mtime=mtime, map=out)
    return out


def _picname(gfx_id: str) -> str:
    """OBJ_EVENT_GFX_NINJA_BOY -> ninjaboy (lowercased, joined) for matching."""
    s = gfx_id.replace("OBJ_EVENT_GFX_", "")
    return s.replace("_", "").lower()


def resolve(repo: Path, gfx_id: str):
    """Return the Path to the overworld sprite PNG for a gfx id, or None."""
    pics = _pic_map(repo)
    key = _picname(gfx_id)
    incpath = pics.get(key)
    if incpath is None:
        # relaxed: some pic symbols carry suffixes (Normal, Walking) — prefix match
        for k, v in pics.items():
            if k.startswith(key) and key:
                incpath = v
                break
    if not incpath:
        return None
    p = repo / incpath
    return p if p.is_file() else None
