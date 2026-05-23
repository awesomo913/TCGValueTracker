"""Surface the project's AI/development history: root handoff/planning docs and
the .remember notes. Read-only, and path-guarded so only known docs are served.
"""
from pathlib import Path

# Curated, ordered set of history-bearing docs (relative to repo root).
ROOT_DOCS = [
    "STATE.md",
    "GOALS_TIMELINE.md",
    "AI_SUPERHANDOFF.md",
    "HANDOFF.md",
    "OVERNIGHT_FOR_CLAUDE.md",
    "CROSS_COMPUTER_SYNC.md",
    "learnings.md",
    "CHANGELOG.md",
    "FEATURES.md",
    "AGENTS.md",
    "SYNC_PROTOCOL.md",
    "CURSOR_PASTE_AUTOEMERALD.md",
]
REMEMBER_DOCS = ["remember.md", "now.md"]


def _candidates(repo: Path):
    rels = list(ROOT_DOCS)
    rdir = repo / ".remember"
    if rdir.is_dir():
        for name in REMEMBER_DOCS:
            rels.append(f".remember/{name}")
        for f in sorted(rdir.glob("today-*.md")):
            rels.append(f".remember/{f.name}")
    return rels


def _allowed(repo: Path) -> set:
    return set(_candidates(repo))


def list_docs(repo: Path):
    out = []
    for rel in _candidates(repo):
        p = repo / rel
        if not p.is_file():
            continue
        try:
            st = p.stat()
            preview = p.read_text(encoding="utf-8", errors="ignore")[:280].replace("\n", " ")
        except OSError:
            continue
        out.append(
            {"rel": rel, "name": Path(rel).name, "size": st.st_size, "mtime": int(st.st_mtime), "preview": preview}
        )
    return out


def read_doc(repo: Path, rel: str):
    """Return doc text, or None. Guarded: rel must be in the allowed set and stay
    inside the repo (no traversal)."""
    if rel not in _allowed(repo):
        return None
    p = (repo / rel).resolve()
    try:
        repo_root = repo.resolve()
        # defense-in-depth: resolved path must live inside the repo root
        if repo_root != p and repo_root not in p.parents:
            return None
        if not p.is_file():
            return None
        return p.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None


def search(repo: Path, query: str):
    q = (query or "").strip().lower()
    if not q:
        return []
    hits = []
    for rel in _candidates(repo):
        p = repo / rel
        if not p.is_file():
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if q in line.lower():
                hits.append({"rel": rel, "line": i, "text": line.strip()[:200]})
                if len(hits) >= 200:
                    return hits
    return hits
