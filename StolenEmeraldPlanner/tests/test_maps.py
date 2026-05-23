from backend.engine import maps


def test_list_maps_nonempty(repo):
    ms = maps.list_maps(repo)
    assert len(ms) > 100
    assert all("id" in m and "name" in m for m in ms)


def test_get_map_has_objects_and_warps(repo):
    m = maps.get_map(repo, "AbandonedShip_Corridors_1F")
    assert m["id"] == "MAP_ABANDONED_SHIP_CORRIDORS_1F"
    assert len(m["object_events"]) >= 1
    assert "graphics_id" in m["object_events"][0]
    assert len(m["warp_events"]) >= 1


def test_get_map_missing_returns_none(repo):
    assert maps.get_map(repo, "NotAMapFolder") is None


def test_get_map_corrupt_json_returns_none(tmp_path):
    bad = tmp_path / "data" / "maps" / "BadMap"
    bad.mkdir(parents=True)
    (bad / "map.json").write_text("{ this is not valid json", encoding="utf-8")
    assert maps.get_map(tmp_path, "BadMap") is None
