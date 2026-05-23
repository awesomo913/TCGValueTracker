"""Aggregate the game's progression triggers into a story timeline.

In the decomp, story progress is gated by VAR_* values: a coord_event fires its
script when its var equals a value. Collecting every var-gated trigger across all
maps gives a map of "what advances the story, where". Paired with the project's
own GOALS_TIMELINE narrative. Read-only.
"""
from pathlib import Path

from backend.engine import maps


def build(repo: Path) -> dict:
    """{vars: {VAR_X: [{map, var_value, script}]}, narrative, narrative_name}."""
    by_var = {}
    root = repo / "data" / "maps"
    if root.is_dir():
        for child in sorted(root.iterdir()):
            m = maps.get_map(repo, child.name)
            if not m:
                continue
            for c in m["coord_events"]:
                if c.get("type") != "trigger":
                    continue
                var = c.get("var", "")
                # keep real story gates; drop temp scratch vars and the
                # run-immediately pseudo-trigger (not progression state).
                if not var.startswith("VAR_") or var.startswith("VAR_TEMP"):
                    continue
                by_var.setdefault(var, []).append(
                    {
                        "map": m["id"],
                        "var_value": c.get("var_value", ""),
                        "script": c.get("script", ""),
                    }
                )

    narrative, narrative_name = "", ""
    for cand in ("GOALS_TIMELINE.md", "STATE.md"):
        p = repo / cand
        if p.is_file():
            try:
                narrative = p.read_text(encoding="utf-8", errors="ignore")
                narrative_name = cand
                break
            except OSError:
                continue

    # sort vars by how many trigger points they have (busiest gates first)
    vars_sorted = dict(sorted(by_var.items(), key=lambda kv: (-len(kv[1]), kv[0])))
    return {"vars": vars_sorted, "narrative": narrative, "narrative_name": narrative_name}
