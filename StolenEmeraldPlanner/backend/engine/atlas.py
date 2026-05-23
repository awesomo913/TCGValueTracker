from pathlib import Path

from backend.engine import encounters, species


def build(repo: Path) -> dict:
    """Assemble the full Atlas payload: per-map encounters joined with the
    species->map index and sprite-folder availability."""
    enc = encounters.load(repo)
    sidx = encounters.species_index(repo)
    sprite_map = {}
    for sp in sidx:
        d = species.sprite_dir(repo, sp)
        sprite_map[sp] = d.name if d else None
    return {
        "maps": {
            name: {"base_label": info["base_label"], "methods": info["methods"]}
            for name, info in enc.items()
        },
        "species_index": sidx,
        "sprite_folders": sprite_map,  # SPECIES_X -> folder name or None
    }
