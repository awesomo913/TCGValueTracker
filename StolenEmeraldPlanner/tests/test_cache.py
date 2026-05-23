from backend import cache


def test_get_set_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("SE_PLANNER_DATA", str(tmp_path))
    cache.set_payload("atlas", {"hello": 1}, signature="sig1")
    assert cache.get_payload("atlas") == {"hello": 1}
    assert cache.is_stale("atlas", "sig2") is True
    assert cache.is_stale("atlas", "sig1") is False


def test_missing_key_is_stale(tmp_path, monkeypatch):
    monkeypatch.setenv("SE_PLANNER_DATA", str(tmp_path))
    assert cache.is_stale("never_set", "x") is True
    assert cache.get_payload("never_set") is None
