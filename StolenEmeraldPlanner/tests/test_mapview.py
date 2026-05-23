from backend.engine import behavior, mapdetail, maps


def test_terrain_grid_has_grass_on_route101(repo):
    layout = maps.layout_index(repo)["LAYOUT_ROUTE101"]
    grid = behavior.terrain_grid(repo, layout)
    grass = sum(row.count("grass") for row in grid)
    assert grass > 0
    assert len(grid) == layout["height"]
    assert all(len(r) == layout["width"] for r in grid)


def test_behavior_names_include_tall_grass(repo):
    names = behavior.behavior_names(repo)
    assert "MB_TALL_GRASS" in names.values()
    assert "MB_LONG_GRASS" in names.values()


def test_mapdetail_route101_layers(repo):
    d = mapdetail.build(repo, "Route101")
    assert d["id"] == "MAP_ROUTE101"
    assert len(d["item_balls"]) == 4
    assert len(d["triggers"]) >= 1
    assert d["width"] == 20 and d["height"] == 20
    # the Birch rescue trigger is var-gated
    assert any(t["var"] == "VAR_ROUTE101_STATE" for t in d["triggers"])


def test_mapdetail_route102_has_trainers(repo):
    d = mapdetail.build(repo, "Route102")
    assert len(d["trainers"]) >= 1
    assert "sight" in d["trainers"][0]


def test_mapdetail_missing_returns_none(repo):
    assert mapdetail.build(repo, "NotAMapFolder") is None
