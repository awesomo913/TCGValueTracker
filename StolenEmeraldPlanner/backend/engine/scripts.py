"""Read a map's scripts.inc and split it into named script blocks.

Labels look like `Route101_EventScript_ArmBalls::` (a symbol followed by `::`).
A block runs until the next top-level label. Read-only.
"""
import re
from pathlib import Path

_LABEL = re.compile(r"^(\w+)::?\s*$")


def load_map_scripts(repo: Path, folder_name: str) -> dict:
    """{label_name: body_text} for one map's scripts.inc (empty dict if none)."""
    path = repo / "data" / "maps" / folder_name / "scripts.inc"
    if not path.is_file():
        return {}
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return {}
    blocks = {}
    current = None
    buf = []
    for line in text.splitlines():
        m = _LABEL.match(line)
        if m:
            if current is not None:
                blocks[current] = "\n".join(buf).strip("\n")
            current = m.group(1)
            buf = [line]
        elif current is not None:
            buf.append(line)
    if current is not None:
        blocks[current] = "\n".join(buf).strip("\n")
    return blocks
