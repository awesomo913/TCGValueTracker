import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = "StolenEmeraldPlanner"
MY_APPS = Path.home() / "Desktop" / "My Apps"


def main():
    MY_APPS.mkdir(parents=True, exist_ok=True)
    sep = ";" if sys.platform == "win32" else ":"
    cmd = [
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--windowed", "--name", APP,
        "--add-data", f"{ROOT / 'frontend'}{sep}frontend",
        "--collect-submodules", "uvicorn",
        "--hidden-import", "uvicorn.lifespan.on",
        "--hidden-import", "uvicorn.protocols.http.auto",
        "--hidden-import", "uvicorn.protocols.websockets.auto",
        "--hidden-import", "uvicorn.loops.auto",
        str(ROOT / "app.py"),
    ]
    subprocess.run(cmd, check=True, cwd=ROOT)
    dist = ROOT / "dist" / APP
    target = MY_APPS / APP
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(dist, target)
    print("Built ->", target / (APP + ".exe"))


if __name__ == "__main__":
    main()
