from __future__ import annotations
import shutil
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

@dataclass
class BackupResult:
    path: Path
    when: datetime

def make_backup(
    worlds_dir: Path,
    backups_dir: Path,
    send_command: Callable[[str], None],
    is_save_ready: Callable[[], bool],
    server_running: bool,
    poll_interval: float = 0.5,
    timeout: float = 30.0,
) -> BackupResult:
    worlds_dir = Path(worlds_dir)
    backups_dir = Path(backups_dir)
    backups_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now()
    dest = backups_dir / f"world-{stamp.strftime('%Y%m%d-%H%M%S')}"

    if not server_running:
        shutil.copytree(worlds_dir, dest)
        return BackupResult(dest, stamp)

    send_command("save hold")
    try:
        deadline = time.monotonic() + timeout
        while not is_save_ready():
            if time.monotonic() > deadline:
                raise TimeoutError("save query never reported ready")
            time.sleep(poll_interval)
        shutil.copytree(worlds_dir, dest)
    finally:
        send_command("save resume")
    return BackupResult(dest, stamp)

def list_backups(backups_dir: Path) -> list[Path]:
    backups_dir = Path(backups_dir)
    if not backups_dir.exists():
        return []
    return sorted((p for p in backups_dir.iterdir() if p.is_dir()), reverse=True)

def prune_backups(backups_dir: Path, keep: int) -> list[Path]:
    removed = []
    for old in list_backups(backups_dir)[keep:]:
        shutil.rmtree(old)
        removed.append(old)
    return removed

def restore_backup(backup_dir: Path, worlds_dir: Path, server_running: bool) -> None:
    if server_running:
        raise RuntimeError("stop the server before restoring")
    backup_dir = Path(backup_dir)
    worlds_dir = Path(worlds_dir)
    if worlds_dir.exists():
        shutil.rmtree(worlds_dir)
    shutil.copytree(backup_dir, worlds_dir)
