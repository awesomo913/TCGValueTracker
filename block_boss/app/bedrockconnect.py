from __future__ import annotations
import socket
import subprocess
from pathlib import Path
from typing import Optional

def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()

class BedrockConnectSupervisor:
    def __init__(self, launch_cmd: list[str], cwd: Path):
        self._launch_cmd = launch_cmd
        self._cwd = Path(cwd)
        self._proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        if self._proc and self._proc.poll() is None:
            return
        self._proc = subprocess.Popen(self._launch_cmd, cwd=str(self._cwd))

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()

    @property
    def running(self) -> bool:
        return bool(self._proc and self._proc.poll() is None)

    def status(self) -> dict:
        return {"running": self.running, "dns_ip": lan_ip()}
