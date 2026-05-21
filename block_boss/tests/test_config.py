from pathlib import Path
from app.config import Config

def test_load_derives_paths(tmp_path):
    cfg = Config.load(home=tmp_path)
    assert cfg.home == tmp_path
    assert cfg.bds_dir == tmp_path / "bedrock-server"
    assert cfg.worlds_dir == tmp_path / "bedrock-server" / "worlds"
    assert cfg.allowlist_path == tmp_path / "bedrock-server" / "allowlist.json"
    assert cfg.backups_dir == tmp_path / "backups"
    assert cfg.pin_file == tmp_path / "pin.hash"
    assert cfg.http_port == 8000
    assert cfg.backup_keep == 7
