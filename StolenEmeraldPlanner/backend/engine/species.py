from pathlib import Path


def to_folder(species_const: str) -> str:
    """SPECIES_ZIGZAGOON -> zigzagoon. Pure string transform."""
    s = species_const.strip()
    if s.upper().startswith("SPECIES_"):
        s = s[len("SPECIES_"):]
    return s.lower()


def sprite_dir(repo: Path, species_const: str):
    """Return graphics/pokemon/<folder> Path if it exists, else None.

    Tries the direct transform first, then a relaxed alnum-only match to
    tolerate punctuation differences (e.g. nidoran forms, mr_mime).
    """
    base = repo / "graphics" / "pokemon"
    folder = to_folder(species_const)
    direct = base / folder
    if direct.is_dir():
        return direct
    key = "".join(c for c in folder if c.isalnum())
    if not base.is_dir():
        return None
    for child in base.iterdir():
        if child.is_dir() and "".join(c for c in child.name if c.isalnum()) == key:
            return child
    return None
