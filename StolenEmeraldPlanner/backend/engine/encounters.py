import json
from pathlib import Path

_METHODS = ("land_mons", "water_mons", "rock_smash_mons", "fishing_mons")


def _rates_by_type(group: dict):
    return {f["type"]: f.get("encounter_rates", []) for f in group.get("fields", [])}


def load(repo: Path) -> dict:
    """Parse wild_encounters.json into a per-map structure.

    Returns::

        {MAP_X: {base_label, methods: {land_mons: {encounter_rate,
            mons: [{species, min_level, max_level, slot, rarity_pct}]}}}}
    """
    raw = json.loads(
        (repo / "src" / "data" / "wild_encounters.json").read_text(encoding="utf-8")
    )
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
                    mons.append(
                        {
                            "species": mon["species"],
                            "min_level": mon["min_level"],
                            "max_level": mon["max_level"],
                            "slot": slot,
                            "rarity_pct": round(w / total * 100, 1),
                        }
                    )
                methods[meth] = {
                    "encounter_rate": block.get("encounter_rate", 0),
                    "mons": mons,
                }
            result[entry["map"]] = {
                "base_label": entry.get("base_label", ""),
                "methods": methods,
            }
    return result


def species_index(repo: Path):
    """{SPECIES_X: [MAP_A, ...]} — every map a species appears on."""
    idx = {}
    for map_name, info in load(repo).items():
        for m in info["methods"].values():
            for mon in m["mons"]:
                idx.setdefault(mon["species"], set()).add(map_name)
    return {k: sorted(v) for k, v in idx.items()}
