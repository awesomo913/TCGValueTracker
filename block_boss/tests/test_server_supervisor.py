import sys
import time
from pathlib import Path
from app.server_supervisor import ServerSupervisor, Status

FAKE = Path(__file__).parent / "fake_bds.py"

def _wait(predicate, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(0.05)
    return False

def test_start_tracks_players_and_stops_clean(tmp_path):
    sup = ServerSupervisor([sys.executable, str(FAKE)], cwd=tmp_path)
    sup.start()
    assert _wait(lambda: sup.status == Status.RUNNING), sup.status
    sup.send_command("spawn")
    assert _wait(lambda: sup.players == ["Steve"]), sup.players
    sup.send_command("despawn")
    assert _wait(lambda: sup.players == [])
    sup.stop()
    assert sup.status == Status.STOPPED

def test_save_ready_flag(tmp_path):
    sup = ServerSupervisor([sys.executable, str(FAKE)], cwd=tmp_path)
    sup.start()
    assert _wait(lambda: sup.status == Status.RUNNING)
    assert sup.save_ready is False
    sup.send_command("save query")
    assert _wait(lambda: sup.save_ready is True)
    sup.stop()
