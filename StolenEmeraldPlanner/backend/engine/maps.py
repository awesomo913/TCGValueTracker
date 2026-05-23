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
        out.append(
            {
                "id": d.get("id", ""),
                "name": d.get("name", child.name),
                "region_section": d.get("region_map_section", ""),
                "map_type": d.get("map_type", ""),
            }
        )
    return out


def get_map(repo: Path, folder_name: str):
    mj = _maps_root(repo) / folder_name / "map.json"
    if not mj.is_file():
        return None
    try:
        d = json.loads(mj.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None  # corrupt/unreadable map.json -> treat as missing (caller 404s)
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
