import sys
from pathlib import Path

import pytest

# Make the project root importable so `import backend...` works under pytest.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend import config  # noqa: E402


@pytest.fixture(scope="session")
def repo():
    if not config.repo_exists():
        pytest.skip("StolenEmerald repo not found")
    return config.repo_path()
