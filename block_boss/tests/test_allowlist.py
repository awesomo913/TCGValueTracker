import json
from app import allowlist

def test_add_list_remove_with_reload(tmp_path):
    path = tmp_path / "allowlist.json"
    sent = []
    send = sent.append

    allowlist.add_player(path, "Steve", send_command=send)
    allowlist.add_player(path, "Alex", send_command=send)
    allowlist.add_player(path, "Steve", send_command=send)  # duplicate ignored

    assert allowlist.list_players(path) == ["Alex", "Steve"]
    data = json.loads(path.read_text(encoding="utf-8"))
    assert {"ignoresPlayerLimit": False, "name": "Steve"} in data
    assert sent.count("allowlist reload") == 2

    allowlist.remove_player(path, "Steve", send_command=send)
    assert allowlist.list_players(path) == ["Alex"]
    assert sent.count("allowlist reload") == 3

def test_list_missing_file_is_empty(tmp_path):
    assert allowlist.list_players(tmp_path / "none.json") == []
