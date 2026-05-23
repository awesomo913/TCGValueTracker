import sys
import datetime
import traceback
from pathlib import Path


def _log_path() -> Path:
    base = Path.home() / ".claude" / "session-data" / datetime.date.today().isoformat()
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path.home()
    return base / "exe_StolenEmeraldPlanner.log"


def _write(line: str) -> None:
    try:
        with open(_log_path(), "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass  # logging must never crash the app


def log(kind: str, msg: str) -> None:
    ts = datetime.datetime.now().isoformat(timespec="seconds")
    _write(f"{ts} {kind} {msg}")


def bootstrap(app_name: str = "StolenEmeraldPlanner") -> None:
    frozen = getattr(sys, "frozen", False)
    log("STARTUP", f"{app_name} python={sys.version.split()[0]} frozen={frozen} argv={sys.argv}")

    def _hook(exc_type, exc, tb):
        log("CRASH", "".join(traceback.format_exception(exc_type, exc, tb)).replace("\n", " | "))
        sys.__excepthook__(exc_type, exc, tb)

    sys.excepthook = _hook
