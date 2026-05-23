"""Parse src/data/trainers.party into per-trainer text blocks.

Each block starts with `=== TRAINER_X ===` and runs until the next one.
Read-only. The full file is parsed once and cached in-process.
"""
import re
from pathlib import Path

_HEADER = re.compile(r"^===\s*(TRAINER_\w+)\s*===\s*$")
_cache = {"path": None, "mtime": None, "data": None}


def load(repo: Path) -> dict:
    """{TRAINER_X: block_text}. Cached by file mtime."""
    path = repo / "src" / "data" / "trainers.party"
    if not path.is_file():
        return {}
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return {}
    if _cache["path"] == path and _cache["mtime"] == mtime and _cache["data"] is not None:
        return _cache["data"]
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return {}  # unreadable (deleted/permissions between stat and read)
    blocks = {}
    current = None
    buf = []
    for line in text.splitlines():
        m = _HEADER.match(line)
        if m:
            if current is not None:
                blocks[current] = "\n".join(buf).strip("\n")
            current = m.group(1)
            buf = [line]
        elif current is not None:
            buf.append(line)
    if current is not None:
        blocks[current] = "\n".join(buf).strip("\n")
    _cache.update(path=path, mtime=mtime, data=blocks)
    return blocks


def get(repo: Path, trainer_const: str):
    return load(repo).get(trainer_const)


def find_in_text(repo: Path, body: str):
    """If a script body references a TRAINER_ constant, return (const, party_text)."""
    m = re.search(r"\bTRAINER_\w+\b", body or "")
    if not m:
        return None
    const = m.group(0)
    party = get(repo, const)
    if party is None:
        return None
    return const, party
