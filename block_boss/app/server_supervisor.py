from __future__ import annotations
import subprocess
import threading
from enum import Enum
from pathlib import Path
from typing import Callable, Optional
from .log_parser import parse_line, PlayerTracker

class Status(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    CRASHED = "crashed"

_SAVE_READY_MARKERS = ("Data saved", "Files are now ready")

class ServerSupervisor:
    def __init__(self, launch_cmd: list[str], cwd: Path,
                 on_line: Optional[Callable[[str], None]] = None):
        self._launch_cmd = launch_cmd
        self._cwd = Path(cwd)
        self._on_line = on_line
        self._proc: Optional[subprocess.Popen] = None
        self._tracker = PlayerTracker()
        self._status = Status.STOPPED
        self._save_ready = False
        self._lock = threading.Lock()

    @property
    def status(self) -> Status:
        return self._status

    @property
    def players(self) -> list[str]:
        return self._tracker.players

    @property
    def save_ready(self) -> bool:
        return self._save_ready

    def start(self) -> None:
        with self._lock:
            if self._proc and self._proc.poll() is None:
                return
            self._tracker = PlayerTracker()
            self._save_ready = False
            self._status = Status.STARTING
            self._proc = subprocess.Popen(
                self._launch_cmd, cwd=str(self._cwd),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
            )
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self) -> None:
        proc = self._proc
        if not proc or not proc.stdout:
            return
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            if any(marker in line for marker in _SAVE_READY_MARKERS):
                self._save_ready = True
            event = parse_line(line)
            self._tracker.apply(event)
            if event and event.kind == "ready":
                self._status = Status.RUNNING
            if self._on_line:
                self._on_line(line)
        code = proc.poll()
        self._status = Status.STOPPED if code == 0 else Status.CRASHED

    def send_command(self, cmd: str) -> None:
        proc = self._proc
        if proc and proc.stdin and proc.poll() is None:
            proc.stdin.write(cmd + "\n")
            proc.stdin.flush()

    def stop(self, timeout: float = 20.0) -> None:
        proc = self._proc
        if not proc or proc.poll() is not None:
            self._status = Status.STOPPED
            return
        self.send_command("stop")
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
        self._status = Status.STOPPED

    def restart(self) -> None:
        self.stop()
        self.start()
