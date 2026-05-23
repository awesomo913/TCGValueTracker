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


def _write_enc(path, species_list):
    import json

    mons = [{"min_level": 2, "max_level": 3, "species": s} for s in species_list]
    rates = [100 // len(mons)] * len(mons)
    data = {
        "wild_encounter_groups": [
            {
                "label": "g",
                "for_maps": True,
                "fields": [{"type": "land_mons", "encounter_rates": rates}],
                "encounters": [
                    {
                        "map": "MAP_TEST",
                        "base_label": "t",
                        "land_mons": {"encounter_rate": 20, "mons": mons},
                    }
                ],
            }
        ]
    }
    path.write_text(json.dumps(data), encoding="utf-8")


def test_atlas_serves_updated_data_after_change(tmp_path, monkeypatch):
    """End-to-end: when repo data changes, /api/status signature changes and
    /api/atlas serves the NEW data (cache rebuilt, not stale)."""
    import os
    import time

    from fastapi.testclient import TestClient

    from backend import cache
    from backend.server import app

    repo = tmp_path / "repo"
    (repo / "src" / "data").mkdir(parents=True)
    (repo / "data" / "maps").mkdir(parents=True)
    enc = repo / "src" / "data" / "wild_encounters.json"
    _write_enc(enc, ["SPECIES_PIDGEY"])

    monkeypatch.setenv("SE_PLANNER_REPO", str(repo))
    monkeypatch.setenv("SE_PLANNER_DATA", str(tmp_path / "appdata"))
    cache._sig_cache.update(t=0.0, sig="")  # clear signature memo

    client = TestClient(app)

    sig1 = client.get("/api/status").json()["signature"]
    atlas1 = client.get("/api/atlas").json()
    assert [m["species"] for m in atlas1["maps"]["MAP_TEST"]["methods"]["land_mons"]["mons"]] == ["SPECIES_PIDGEY"]

    # change the data + force a newer mtime, clear the 2s signature memo
    time.sleep(0.05)
    _write_enc(enc, ["SPECIES_PIDGEY", "SPECIES_RATTATA"])
    os.utime(enc, (time.time() + 10, time.time() + 10))
    cache._sig_cache.update(t=0.0, sig="")

    sig2 = client.get("/api/status").json()["signature"]
    assert sig2 != sig1  # the change was detected

    atlas2 = client.get("/api/atlas").json()
    species = [m["species"] for m in atlas2["maps"]["MAP_TEST"]["methods"]["land_mons"]["mons"]]
    assert species == ["SPECIES_PIDGEY", "SPECIES_RATTATA"]  # NEW data served, not the stale cache
