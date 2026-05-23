"""Map metatile behaviors -> terrain classes (grass / water), so the UI can
show exactly which tiles spawn which wild Pokemon.

Behaviors come from a C enum in include/constants/metatile_behaviors.h
(auto-incrementing values). Per-metatile behavior bytes are in each tileset's
metatile_attributes.bin (2 bytes/metatile, behavior = low 9 bits).
"""
import re
import struct
from pathlib import Path

from backend.engine.maprender import (
    METATILES_IN_PRIMARY,
    _tileset_dir,
)


def behavior_names(repo: Path) -> dict:
    """{value: NAME} parsed from the metatile_behaviors enum."""
    txt = (repo / "include" / "constants" / "metatile_behaviors.h").read_text(
        encoding="utf-8", errors="ignore"
    )
    start = txt.find("enum")
    if start == -1:
        return {}  # enum not found (file renamed/restructured) -> no classification
    body = txt[start:]
    body = body[body.find("{") + 1 : body.find("}")]
    names = {}
    val = 0
    for raw in body.split(","):
        item = raw.split("//")[0].strip()
        if not item:
            continue
        m = re.match(r"(MB_\w+)\s*(?:=\s*(0x[0-9A-Fa-f]+|\d+))?$", item)
        if not m:
            continue
        if m.group(2) is not None:
            val = int(m.group(2), 0)
        names[val] = m.group(1)
        val += 1
    return names


def _classify(name: str) -> str:
    if "TALL_GRASS" in name or "LONG_GRASS" in name:
        return "grass"
    if "WATER" in name:  # POND/DEEP/OCEAN/WATERFALL/SOOTOPOLIS etc all contain WATER
        return "water"
    return ""


def _attributes(tdir: Path):
    """metatile index -> behavior value for one tileset."""
    data = (tdir / "metatile_attributes.bin").read_bytes()
    out = []
    for i in range(len(data) // 2):
        (v,) = struct.unpack_from("<H", data, i * 2)
        out.append(v & 0x1FF)
    return out


def terrain_grid(repo: Path, layout: dict) -> list:
    """w*h grid (list of rows) of terrain class strings: 'grass', 'water', or ''."""
    names = behavior_names(repo)
    prim = _attributes(_tileset_dir(repo, layout["primary_tileset"]))
    sec = _attributes(_tileset_dir(repo, layout["secondary_tileset"]))
    blocks = (repo / layout["blockdata_filepath"]).read_bytes()
    w, h = layout["width"], layout["height"]
    grid = []
    for by in range(h):
        row = []
        for bx in range(w):
            off = (by * w + bx) * 2
            cls = ""
            if off + 1 < len(blocks):
                (val,) = struct.unpack_from("<H", blocks, off)
                mid = val & 0x3FF
                if mid < METATILES_IN_PRIMARY:
                    beh = prim[mid] if mid < len(prim) else 0
                else:
                    si = mid - METATILES_IN_PRIMARY
                    beh = sec[si] if si < len(sec) else 0
                cls = _classify(names.get(beh, ""))
            row.append(cls)
        grid.append(row)
    return grid
