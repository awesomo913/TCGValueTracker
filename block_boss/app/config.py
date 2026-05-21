from __future__ import annotations
import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_HOME = Path(os.environ.get("BLOCK_BOSS_HOME", str(Path.home() / "block_boss")))

@dataclass(frozen=True)
class Config:
    home: Path
    bds_dir: Path
    worlds_dir: Path
    backups_dir: Path
    logs_dir: Path
    allowlist_path: Path
    server_properties: Path
    box64_bin: str
    bds_executable: Path
    http_port: int
    pin_file: Path
    backup_keep: int

    @staticmethod
    def load(home: Path = DEFAULT_HOME) -> "Config":
        home = Path(home)
        bds_dir = home / "bedrock-server"
        return Config(
            home=home,
            bds_dir=bds_dir,
            worlds_dir=bds_dir / "worlds",
            backups_dir=home / "backups",
            logs_dir=home / "logs",
            allowlist_path=bds_dir / "allowlist.json",
            server_properties=bds_dir / "server.properties",
            box64_bin=os.environ.get("BOX64_BIN", "box64"),
            bds_executable=bds_dir / "bedrock_server",
            http_port=int(os.environ.get("BLOCK_BOSS_PORT", "8000")),
            pin_file=home / "pin.hash",
            backup_keep=int(os.environ.get("BLOCK_BOSS_BACKUP_KEEP", "7")),
        )
