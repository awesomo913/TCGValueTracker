from backend.engine import species


def test_basic_species_to_folder():
    assert species.to_folder("SPECIES_ZIGZAGOON") == "zigzagoon"


def test_strips_prefix_and_lowercases():
    assert species.to_folder("SPECIES_MR_MIME") == "mr_mime"


def test_resolve_existing_returns_path(repo):
    p = species.sprite_dir(repo, "SPECIES_ZIGZAGOON")
    assert p is not None and (p / "icon.png").is_file()


def test_resolve_unknown_returns_none(repo):
    assert species.sprite_dir(repo, "SPECIES_NOT_A_REAL_MON") is None
