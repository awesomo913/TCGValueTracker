from backend.engine import history, scripts, timeline, trainers


def test_scripts_parse_route101(repo):
    s = scripts.load_map_scripts(repo, "Route101")
    assert len(s) > 10
    assert "Route101_EventScript_ArmBalls" in s
    assert "setvar VAR_ROUTE101_STATE" in s["Route101_EventScript_ArmBalls"]


def test_scripts_missing_map_returns_empty(repo):
    assert scripts.load_map_scripts(repo, "NotAMapFolder") == {}


def test_trainers_parse_and_get(repo):
    t = trainers.get(repo, "TRAINER_SAWYER_1")
    assert t is not None and "TRAINER_SAWYER_1" in t


def test_trainers_find_in_text(repo):
    body = "trainerbattle_single TRAINER_SAWYER_1, Foo, Bar\nend"
    found = trainers.find_in_text(repo, body)
    assert found is not None and found[0] == "TRAINER_SAWYER_1"


def test_history_lists_and_reads(repo):
    docs = history.list_docs(repo)
    assert any(d["name"] == "GOALS_TIMELINE.md" for d in docs)
    text = history.read_doc(repo, "GOALS_TIMELINE.md")
    assert text and len(text) > 100


def test_history_blocks_traversal(repo):
    assert history.read_doc(repo, "../CLAUDE.md") is None
    assert history.read_doc(repo, "src/data/wild_encounters.json") is None


def test_history_search(repo):
    hits = history.search(repo, "battle frontier")
    assert isinstance(hits, list)


def test_timeline_real_vars_only(repo):
    d = timeline.build(repo)
    assert all(v.startswith("VAR_") and not v.startswith("VAR_TEMP") for v in d["vars"])
    assert "VAR_ROUTE101_STATE" in d["vars"]
    assert d["narrative_name"] in ("GOALS_TIMELINE.md", "STATE.md")
