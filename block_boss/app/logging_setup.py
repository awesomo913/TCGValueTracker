from __future__ import annotations
import logging
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path

def _ts() -> str:
    return datetime.now(timezone.utc).isoformat()

class DiagnosticLogger:
    def __init__(self, app_name: str, logs_dir: Path):
        logs_dir = Path(logs_dir)
        logs_dir.mkdir(parents=True, exist_ok=True)
        day = datetime.now().strftime("%Y-%m-%d")
        self.path = logs_dir / f"{app_name}_{day}.log"
        self.app_name = app_name
        self._logger = logging.getLogger(f"blockboss.{app_name}.{id(self)}")
        self._logger.setLevel(logging.INFO)
        self._logger.propagate = False
        handler = logging.FileHandler(self.path, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(message)s"))
        self._logger.addHandler(handler)

    def _emit(self, kind: str, msg: str) -> None:
        self._logger.info(f"{_ts()} {kind} {msg}")

    def startup(self, version: str) -> None:
        self._emit("STARTUP", f"{self.app_name} v{version} python={sys.version.split()[0]}")

    def state(self, frm: str, to: str) -> None:
        self._emit("STATE", f"{frm}->{to}")

    def decision(self, msg: str) -> None:
        self._emit("DECISION", msg)

    def perf(self, op: str, seconds: float) -> None:
        self._emit("PERF", f"{op}={seconds:.3f}s")

    def crash(self, exc: BaseException) -> None:
        tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
        self._emit("CRASH", tb.replace("\n", " | "))
