import os
from pathlib import Path

DEFAULT_REPO = Path(r"C:\StolenEmerald")


def app_data_dir() -> Path:
    d = Path(os.environ.get("SE_PLANNER_DATA", Path.home() / ".stolenemerald_planner"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def repo_path() -> Path:
    """Repo root. Override with SE_PLANNER_REPO. Read-only target."""
    return Path(os.environ.get("SE_PLANNER_REPO", DEFAULT_REPO))


def repo_exists() -> bool:
    return (repo_path() / "src" / "data" / "wild_encounters.json").is_file()
