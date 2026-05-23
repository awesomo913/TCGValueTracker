"""Parse the planner's own ROADMAP markdown into structured sections/items.

Format: `## Section` headers, then checklist items:
  - [x] shipped
  - [~] in progress
  - [ ] planned
Read-only.
"""
import re
from pathlib import Path

_ITEM = re.compile(r"^\s*[-*]\s*\[([ xX~])\]\s*(.+?)\s*$")


def parse(path: Path) -> dict:
    if not path.is_file():
        return {"sections": [], "raw": "", "counts": {}}
    try:
        raw = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return {"sections": [], "raw": "", "counts": {}}
    sections = []
    cur = None
    counts = {"done": 0, "wip": 0, "planned": 0}
    for line in raw.splitlines():
        if line.startswith("## "):
            cur = {"title": line[3:].strip(), "items": []}
            sections.append(cur)
            continue
        m = _ITEM.match(line)
        if m:
            if cur is None:  # items before any header -> default section (not dropped)
                cur = {"title": "Notes", "items": []}
                sections.append(cur)
            c = m.group(1).lower()
            status = "done" if c == "x" else ("wip" if c == "~" else "planned")
            counts[status] += 1
            cur["items"].append({"status": status, "text": m.group(2)})
    return {"sections": sections, "raw": raw, "counts": counts}
