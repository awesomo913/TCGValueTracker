import os
import time
from pathlib import Path

from backend import cache
from backend.engine import roadmap


def _make_repo(root: Path):
    (root / "src" / "data").mkdir(parents=True)
    (root / "src" / "data" / "wild_encounters.json").write_text('{"wild_encounter_groups": []}')
    (root / "data" / "maps" / "TownA").mkdir(parents=True)
    (root / "data" / "maps" / "TownA" / "map.json").write_text('{"id": "MAP_TOWN_A"}')


def test_signature_detects_map_content_edit(tmp_path, monkeypatch):
    _make_repo(tmp_path)
    monkeypatch.setenv("SE_PLANNER_REPO", str(tmp_path))
    cache._sig_cache.update(t=0.0, sig="")  # clear memo

    sig1 = cache.repo_signature()
    time.sleep(2.1)  # pass the 2s memo window AND ensure mtime tick
    mj = tmp_path / "data" / "maps" / "TownA" / "map.json"
    mj.write_text('{"id": "MAP_TOWN_A", "music": "MUS_NEW"}')
    os.utime(mj, (time.time() + 5, time.time() + 5))  # force a newer mtime

    sig2 = cache.repo_signature()
    assert sig2 != sig1  # content edit to a map.json is detected


def test_signature_detects_map_add(tmp_path, monkeypatch):
    _make_repo(tmp_path)
    monkeypatch.setenv("SE_PLANNER_REPO", str(tmp_path))
    cache._sig_cache.update(t=0.0, sig="")

    sig1 = cache.repo_signature()
    cache._sig_cache.update(t=0.0, sig="")  # bypass memo for the assert
    (tmp_path / "data" / "maps" / "TownB").mkdir()
    (tmp_path / "data" / "maps" / "TownB" / "map.json").write_text('{"id": "MAP_TOWN_B"}')
    sig2 = cache.repo_signature()
    assert sig2 != sig1  # added map changes the count


def test_roadmap_parse_counts(tmp_path):
    p = tmp_path / "roadmap.md"
    p.write_text(
        "# R\n## Shipped\n- [x] done one\n- [x] done two\n## Planned\n- [ ] later\n- [~] doing now\n"
    )
    r = roadmap.parse(p)
    assert r["counts"] == {"done": 2, "wip": 1, "planned": 1}
    assert len(r["sections"]) == 2
    assert r["sections"][0]["title"] == "Shipped"
    assert r["sections"][0]["items"][0] == {"status": "done", "text": "done one"}


def test_roadmap_missing_file(tmp_path):
    r = roadmap.parse(tmp_path / "nope.md")
    assert r["sections"] == [] and r["raw"] == ""
