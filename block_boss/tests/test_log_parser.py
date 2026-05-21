from app.log_parser import parse_line, PlayerTracker

def test_parse_connect_disconnect_ready():
    c = parse_line("[2026-05-21 12:00 INFO] Player connected: Steve, xuid: 100")
    assert c.kind == "connect" and c.player == "Steve"
    d = parse_line("[2026-05-21 12:01 INFO] Player disconnected: Steve, xuid: 100")
    assert d.kind == "disconnect" and d.player == "Steve"
    r = parse_line("[2026-05-21 12:02 INFO] Server started.")
    assert r.kind == "ready"
    assert parse_line("[INFO] some noise") is None

def test_tracker_tracks_players_and_ready():
    t = PlayerTracker()
    t.apply(parse_line("Player connected: Steve, xuid: 1"))
    t.apply(parse_line("Player connected: Alex, xuid: 2"))
    t.apply(parse_line("Player disconnected: Steve, xuid: 1"))
    t.apply(parse_line("Server started."))
    assert t.players == ["Alex"]
    assert t.ready is True
