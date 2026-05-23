"""Assemble everything overlaid on one map: NPCs, trainers, item balls, hidden
items, signs, warps, progression triggers, the encounter terrain grid, and the
wild Pokemon tied to that terrain. All read-only.
"""
from pathlib import Path

from backend.engine import behavior, encounters, maps

ITEM_BALL_GFX = "OBJ_EVENT_GFX_ITEM_BALL"


def _classify_objects(object_events: list):
    npcs, trainers, item_balls = [], [], []
    for o in object_events:
        base = {
            "x": o.get("x"),
            "y": o.get("y"),
            "graphics_id": o.get("graphics_id", ""),
            "script": o.get("script", ""),
            "flag": o.get("flag", "0"),
            "movement_type": o.get("movement_type", ""),
        }
        if o.get("graphics_id") == ITEM_BALL_GFX:
            item_balls.append(base)
        elif o.get("trainer_type", "TRAINER_TYPE_NONE") != "TRAINER_TYPE_NONE":
            t = dict(base)
            t["sight"] = o.get("trainer_sight_or_berry_tree_id", "0")
            trainers.append(t)
        else:
            npcs.append(base)
    return npcs, trainers, item_balls


def _classify_bg(bg_events: list):
    signs, hidden_items = [], []
    for b in bg_events:
        if b.get("type") == "hidden_item":
            hidden_items.append(
                {
                    "x": b.get("x"),
                    "y": b.get("y"),
                    "item": b.get("item", ""),
                    "flag": b.get("flag", ""),
                    "quantity": b.get("quantity", 1),
                }
            )
        elif b.get("type") == "sign":
            signs.append({"x": b.get("x"), "y": b.get("y"), "script": b.get("script", "")})
    return signs, hidden_items


def build(repo: Path, folder_name: str):
    m = maps.get_map(repo, folder_name)
    if m is None:
        return None
    layout = maps.layout_index(repo).get(m.get("layout", ""))
    npcs, trainers, item_balls = _classify_objects(m.get("object_events", []))
    signs, hidden_items = _classify_bg(m.get("bg_events", []))
    triggers = [
        {
            "x": c.get("x"),
            "y": c.get("y"),
            "var": c.get("var", ""),
            "var_value": c.get("var_value", ""),
            "script": c.get("script", ""),
        }
        for c in m.get("coord_events", [])
        if c.get("type") == "trigger"
    ]
    warps = [
        {"x": w.get("x"), "y": w.get("y"), "dest_map": w.get("dest_map", "")}
        for w in m.get("warp_events", [])
    ]

    enc = encounters.load(repo).get(m["id"], {"methods": {}})
    terrain = behavior.terrain_grid(repo, layout) if layout else []

    return {
        "id": m["id"],
        "name": m["name"],
        "folder": folder_name,
        "layout": m["layout"],
        "width": layout["width"] if layout else 0,
        "height": layout["height"] if layout else 0,
        "render_url": f"/api/map_render/{folder_name}.png",
        "npcs": npcs,
        "trainers": trainers,
        "item_balls": item_balls,
        "hidden_items": hidden_items,
        "signs": signs,
        "warps": warps,
        "triggers": triggers,
        "terrain": terrain,  # rows of '', 'grass', 'water'
        "encounters": enc.get("methods", {}),  # land_mons/water_mons/... -> mons
    }
