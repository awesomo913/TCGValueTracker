from backend.engine import encounters


def test_load_returns_maps_dict(repo):
    data = encounters.load(repo)
    assert "MAP_ROUTE101" in data
    assert "land_mons" in data["MAP_ROUTE101"]["methods"]


def test_mon_has_species_levels_rarity(repo):
    data = encounters.load(repo)
    first = data["MAP_ROUTE101"]["methods"]["land_mons"]["mons"][0]
    assert first["species"] == "SPECIES_PIDGEY"
    assert first["min_level"] == 2 and first["max_level"] == 3
    assert 0 < first["rarity_pct"] <= 100


def test_rarities_sum_to_100(repo):
    data = encounters.load(repo)
    for m in data["MAP_ROUTE101"]["methods"].values():
        assert round(sum(x["rarity_pct"] for x in m["mons"]), 0) == 100


def test_species_index_maps_mon_to_maps(repo):
    idx = encounters.species_index(repo)
    assert any(m.startswith("MAP_") for m in idx.get("SPECIES_PIDGEY", []))
