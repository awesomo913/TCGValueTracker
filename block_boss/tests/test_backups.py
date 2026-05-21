import pytest
from pathlib import Path
from app import backups

def _make_world(worlds: Path):
    (worlds / "Bedrock level").mkdir(parents=True)
    (worlds / "Bedrock level" / "level.dat").write_text("data", encoding="utf-8")

def test_backup_running_server_holds_and_resumes(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backups_dir = tmp_path / "backups"
    sent = []
    result = backups.make_backup(
        worlds, backups_dir,
        send_command=sent.append,
        is_save_ready=lambda: True,
        server_running=True,
    )
    assert result.path.is_dir()
    assert (result.path / "Bedrock level" / "level.dat").exists()
    assert sent == ["save hold", "save resume"]

def test_resume_runs_even_if_copy_fails(tmp_path):
    missing = tmp_path / "missing_worlds"
    backups_dir = tmp_path / "backups"
    sent = []
    with pytest.raises(Exception):
        backups.make_backup(
            missing, backups_dir,
            send_command=sent.append,
            is_save_ready=lambda: True,
            server_running=True,
        )
    assert "save resume" in sent

def test_backup_stopped_server_skips_hold(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backups_dir = tmp_path / "backups"
    sent = []
    backups.make_backup(worlds, backups_dir, send_command=sent.append,
                        is_save_ready=lambda: True, server_running=False)
    assert sent == []

def test_prune_keeps_newest(tmp_path):
    backups_dir = tmp_path / "backups"; backups_dir.mkdir()
    for n in ["world-20260101-000000", "world-20260102-000000", "world-20260103-000000"]:
        (backups_dir / n).mkdir()
    removed = backups.prune_backups(backups_dir, keep=2)
    names = [p.name for p in backups.list_backups(backups_dir)]
    assert names == ["world-20260103-000000", "world-20260102-000000"]
    assert removed[0].name == "world-20260101-000000"

def test_restore_replaces_worlds(tmp_path):
    worlds = tmp_path / "worlds"; _make_world(worlds)
    backup = tmp_path / "backup"; backup.mkdir()
    (backup / "Bedrock level").mkdir()
    (backup / "Bedrock level" / "level.dat").write_text("restored", encoding="utf-8")
    backups.restore_backup(backup, worlds, server_running=False)
    assert (worlds / "Bedrock level" / "level.dat").read_text(encoding="utf-8") == "restored"

def test_restore_refuses_while_running(tmp_path):
    with pytest.raises(RuntimeError):
        backups.restore_backup(tmp_path / "b", tmp_path / "w", server_running=True)
