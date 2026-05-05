"""
One-shot generator: per-project agent study docs under docs/agent-study/projects/
Also writes index.json (machine-readable) and merged optional overrides/entries.json.

Run: python generate_desktop_ai_studies.py

Env:
  DESKTOP_AI_ROOT  — override root (default: C:\\Users\\computer\\Desktop\\AI)
  AGENT_STUDY_GIT=0 — set to skip git subprocess (faster / no git)
"""
from __future__ import annotations

import json
import os
import re
import subprocess
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any

GENERATOR_VERSION = "2.2.0"
README_EXCERPT_MAX_CHARS = 4000
ROOT = Path(os.environ.get("DESKTOP_AI_ROOT", r"C:\Users\computer\Desktop\AI"))
OUT = ROOT / "docs" / "agent-study" / "projects"
OVERRIDES_DIR = ROOT / "docs" / "agent-study" / "overrides"
SKIP_NAMES = {
    "phantom_forge.egg-info",
    ".claude", ".cursor", ".pytest_cache", ".remember", ".superpowers", ".venv",
    "build", "dist", "docs", "logs", "output", "ffmpeg", "msys2", "__pycache__", "node_modules",
}

# Lines that may contain secrets — redact whole line
_SECRET_LINE = re.compile(
    r"^\s*("
    r"(api[_-]?key|apikey|secret|password|token|bearer|authorization)\s*[=:].+|"
    r"-----BEGIN [^-]+-----|"
    r"gh[pousr]_[A-Za-z0-9_]+|"
    r"sk-[A-Za-z0-9]+|"
    r"xox[baprs]-[A-Za-z0-9-]+"
    r")",
    re.I | re.M,
)


def safe_slug(name: str) -> str:
    s = re.sub(r"[^\w\-\.]+", "_", name.strip())
    return s or "unnamed"


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def read_json_file(path: Path) -> Any:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, json.JSONDecodeError):
        return None


def load_merged_overrides() -> dict[str, Any]:
    """Hand-written entries.json; keys = slug. Skip keys starting with _."""
    p = OVERRIDES_DIR / "entries.json"
    data = read_json_file(p)
    if not isinstance(data, dict):
        return {}
    return {k: v for k, v in data.items() if not k.startswith("_") and isinstance(v, dict)}


def head_text(path: Path, n: int = 50) -> str:
    if not path.is_file():
        return ""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""
    lines = text.splitlines()[:n]
    return "\n".join(lines)


def sanitize_excerpt(text: str) -> str:
    if not text:
        return ""
    out_lines = []
    for line in text.splitlines():
        if _SECRET_LINE.search(line):
            out_lines.append("***REDACTED (possible secret)***")
        else:
            out_lines.append(line)
    s = "\n".join(out_lines)
    if len(s) > README_EXCERPT_MAX_CHARS:
        s = s[: README_EXCERPT_MAX_CHARS] + "\n\n… *(truncated — see README on disk)*"
    return s


def extract_agent_summary(readme_path: Path) -> str:
    """Extract a concise 1-2 sentence purpose from a README.

    Strategy: find the first non-heading paragraph after the title (or the title
    itself if no paragraph follows). Focus on Setup/Install sections if no purpose
    paragraph is found in the first 30 lines.
    """
    if not readme_path.is_file():
        return ""
    try:
        text = readme_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""

    lines = text.splitlines()
    # Skip title heading (first #-prefixed line)
    body_lines: list[str] = []
    skipped_title = False
    for ln in lines[:60]:
        stripped = ln.strip()
        if not skipped_title and stripped.startswith("#"):
            skipped_title = True
            continue
        body_lines.append(stripped)

    # Collect first non-empty, non-heading paragraph lines (up to 3 sentences)
    paragraph: list[str] = []
    for ln in body_lines:
        if not ln:
            if paragraph:
                break  # paragraph boundary
            continue
        if ln.startswith("#") or ln.startswith("```") or ln.startswith("---") or ln.startswith("==="):
            if paragraph:
                break
            continue
        # Skip HTML tags, badge/image lines, and list markers
        if ln.startswith(("<", "!", "[", "|", "-", "*", "1.")):
            if paragraph:
                break
            continue
        if re.match(r"^<[a-zA-Z]", ln):  # HTML opening tag
            if paragraph:
                break
            continue
        paragraph.append(ln)
        if len(" ".join(paragraph)) > 300:
            break

    summary = " ".join(paragraph).strip()
    # Trim to ~220 chars at sentence boundary
    if len(summary) > 220:
        cut = summary[:220]
        last_dot = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
        summary = cut[: last_dot + 1].strip() if last_dot > 60 else cut.rstrip(".,;:") + "…"
    return summary


def detect_python_version(p: Path) -> str | None:
    """Detect the project's target Python version from common config files."""
    # .python-version (pyenv)
    pv = p / ".python-version"
    if pv.is_file():
        try:
            v = pv.read_text(encoding="utf-8", errors="replace").strip().splitlines()[0].strip()
            if v:
                return v
        except OSError:
            pass

    # pyproject.toml — python = ">=3.x" or requires-python
    pp = p / "pyproject.toml"
    if pp.is_file():
        try:
            txt = pp.read_text(encoding="utf-8", errors="replace")
            m = re.search(r'requires-python\s*=\s*"([^"]+)"', txt)
            if m:
                return m.group(1)
            m = re.search(r'python\s*=\s*"([^"]+)"', txt)
            if m and not m.group(1).startswith("{"):
                return m.group(1)
        except OSError:
            pass

    return None


def has_tests(p: Path) -> bool:
    """Return True if the project has a visible test suite."""
    test_dirs = ["tests", "test", "spec", "__tests__"]
    for d in test_dirs:
        if (p / d).is_dir():
            return True
    # pytest.ini / setup.cfg with [tool:pytest]
    for cfg in ("pytest.ini", "setup.cfg", "tox.ini"):
        if (p / cfg).is_file():
            return True
    # Any *test*.py in root
    return bool(list(p.glob("*test*.py"))[:1] or list(p.glob("test_*.py"))[:1])


def compute_health_score(p: Path, data: dict[str, Any]) -> tuple[int, list[str]]:
    """Return (score_0_to_100, list_of_notes).

    Rubric (100 pts):
      README present             +20
      CLAUDE.md or AGENTS.md     +15
      Build/install instructions  +15
      Git repo                   +10
      Recent commit (≤ 30 days)  +15
      Clean working tree         +10
      Tests detected             +15
    """
    score = 0
    notes: list[str] = []

    if data["has_readme"]:
        score += 20
    else:
        notes.append("No README — add one so agents understand the project")

    if data["agent_files"]:
        score += 15
    else:
        notes.append("No CLAUDE.md/AGENTS.md — agents must guess guardrails")

    if data["build_lines"]:
        score += 15
    else:
        notes.append("No detected build instructions")

    git = data.get("git") or {}
    if git:
        score += 10
        at_str = git.get("last_commit_at")
        if at_str:
            try:
                at = datetime.fromisoformat(at_str.replace("Z", "+00:00"))
                age = datetime.now(timezone.utc) - at
                if age <= timedelta(days=30):
                    score += 15
                else:
                    notes.append(f"Last commit {age.days}d ago (>30d)")
            except ValueError:
                # Requires Python 3.11+ for full ISO 8601 with offset — safe on this workspace
                notes.append(f"Could not parse commit timestamp: {at_str!r}")
        if "dirty" in git:
            if not git["dirty"]:
                score += 10
            else:
                notes.append("Working tree has uncommitted changes")
    else:
        notes.append("Not a git repo")

    if has_tests(p):
        score += 15
    else:
        notes.append("No tests detected")

    return min(score, 100), notes


def read_json(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, json.JSONDecodeError):
        return None


def git_info(path: Path) -> dict[str, Any]:
    if os.environ.get("AGENT_STUDY_GIT", "1") == "0":
        return {}
    if not (path / ".git").exists():
        return {}

    def run(args: list[str]) -> str | None:
        try:
            r = subprocess.run(
                ["git", "-C", str(path), *args],
                capture_output=True,
                text=True,
                timeout=12,
                encoding="utf-8",
                errors="replace",
            )
            if r.returncode != 0:
                return None
            return (r.stdout or "").strip() or None
        except (OSError, subprocess.TimeoutExpired, FileNotFoundError):
            return None

    short = run(["rev-parse", "--short", "HEAD"])
    at = run(["log", "-1", "--format=%cI"])
    status = run(["status", "--porcelain"])
    out: dict[str, Any] = {}
    if short:
        out["last_commit"] = short
    if at:
        out["last_commit_at"] = at
    if status is not None:
        out["dirty"] = len(status) > 0
    return out


def detect_build(p: Path) -> tuple[list[str], list[str]]:
    """Return (markdown_lines, stack_tags for index.json)."""
    lines: list[str] = []
    tags: list[str] = []

    if (p / "package.json").is_file():
        data = read_json(p / "package.json") or {}
        scripts = data.get("scripts") or {}
        name = data.get("name", "")
        keys: list[str] = []
        if name:
            lines.append(f"- **npm package:** `{name}`")
        tags.append("node")
        if isinstance(scripts, dict) and scripts:
            keys = [k for k in ("dev", "start", "build", "test") if k in scripts]
            for k in keys or list(scripts.keys())[:4]:
                val = scripts.get(k, "")
                if not isinstance(val, str):
                    val = str(val)
                lines.append(f"- **npm run {k}:** `{val[:120]}`")
            if not keys:
                first = next(iter(scripts.items()))
                fv = first[1] if isinstance(first[1], str) else str(first[1])
                lines.append(f"- **npm script (sample):** `{first[0]}` → `{fv[:100]}`")

    for req in sorted(p.glob("requirements*.txt")):
        lines.append(f"- **Python:** `pip install -r {req.name}`")
        if "python" not in tags:
            tags.append("python")

    if (p / "pyproject.toml").is_file():
        t = head_text(p / "pyproject.toml", 50)
        if "[project]" in t or "tool.setuptools" in t or "build-system" in t or "tool.poetry" in t:
            lines.append(
                "- **Python:** `pyproject.toml` present — `pip install -e .` or `uv sync` / Poetry per project."
            )
            if "python" not in tags:
                tags.append("python")

    if (p / "Pipfile").is_file() or (p / "Pipfile.lock").is_file():
        lines.append("- **Python:** `Pipfile` / Pipenv (see `pipenv install`).")
        if "python" not in tags:
            tags.append("python")

    if (p / "go.mod").is_file():
        lines.append("- **Go:** `go mod tidy` then `go build ./...` (from this folder).")
        tags.append("go")

    if (p / "Cargo.toml").is_file():
        lines.append("- **Rust:** `cargo build` (from this folder).")
        tags.append("rust")

    if (p / "Dockerfile").is_file() or (p / "docker-compose.yml").is_file() or (p / "compose.yaml").is_file():
        files = [f for f in ("Dockerfile", "docker-compose.yml", "compose.yaml") if (p / f).is_file()]
        lines.append(
            f"- **Containers:** {', '.join('`' + f + '`' for f in files)} — see file(s) for run instructions."
        )
        tags.append("docker")

    if list(p.glob("*.sln")) or list(p.glob("*.csproj")):
        lines.append("- **.NET:** `.sln` / `.csproj` present — `dotnet build` on the solution or project.")
        tags.append("dotnet")

    if (p / "pom.xml").is_file() or (p / "build.gradle.kts").is_file():
        lines.append("- **JVM:** Maven or Gradle — see `pom.xml` / `build.gradle*`.")
        tags.append("jvm")

    if (p / "Makefile").is_file():
        t = head_text(p / "Makefile", 25)
        if t:
            lines.append("- **Make:** `Makefile` present — `make` / `make help` as usual.")
        if "rom" in t.lower() and "devkit" in t.lower():
            tags.append("gba-romdev")

    for bat in p.glob("*.bat"):
        lines.append(f"- **Batch / Windows:** `{bat.name}`")
        break

    for gradle in ("build.gradle", "gradlew.bat", "build.gradle.kts"):
        if (p / gradle).is_file():
            lines.append("- **Android/Gradle:** Gradle build present — see wrapper scripts.")
            if "android" not in tags:
                tags.append("android")
            break

    # pret-style disassembly (Pokemon GBA) — charmap + Makefile is a strong signal
    if (p / "charmap.txt").is_file() and (p / "Makefile").is_file():
        if "gba-romdev" not in tags:
            tags.append("gba-romdev")

    return lines, list(dict.fromkeys(tags))


def agent_files(p: Path) -> list[str]:
    out = []
    for name in (
        "CLAUDE.md", "AGENTS.md", ".cursorrules", "CURSOR.md", "CODEX.md", "COPILOT.md",
    ):
        if (p / name).is_file():
            out.append(name)
    if (p / ".cursor" / "rules").is_dir():
        out.append(".cursor/rules/ (dir)")
    return out


def subprojects(parent: Path) -> list[Path]:
    if not parent.is_dir():
        return []
    out = []
    for c in sorted(parent.iterdir(), key=lambda x: x.name.lower()):
        if not c.is_dir():
            continue
        if c.name in SKIP_NAMES or c.name.startswith("."):
            continue
        out.append(c)
    return out


def scan_one(path: Path, index_key: str, slug: str, overrides_map: dict[str, Any]) -> dict[str, Any]:
    is_nested = "/" in index_key
    name = path.name
    try:
        rel = str(path.relative_to(ROOT))
    except ValueError:
        rel = str(path)
    build_lines, stack_tags = detect_build(path)
    agents = agent_files(path)
    readme = path / "README.md"
    has_readme = readme.is_file()
    raw_excerpt = head_text(readme) if has_readme else ""
    excerpt = sanitize_excerpt(raw_excerpt) if raw_excerpt else ""
    g = git_info(path)
    sub = subprojects(path)
    override = overrides_map.get(slug, {})
    if not isinstance(override, dict):
        override = {}

    nested_note = ""
    if (not is_nested) and sub:
        nested_note = "\n## Notable subfolders (not each generated as its own card)\n\n"
        for s in sub[:40]:
            nested_note += f"- `{s.name}/`\n"
        if len(sub) > 40:
            nested_note += f"- … and {len(sub) - 40} more\n"

    agent_summary = extract_agent_summary(readme) if has_readme else ""
    py_ver = detect_python_version(path)

    partial: dict[str, Any] = {
        "name": name,
        "index_key": index_key,
        "slug": slug,
        "path": str(path),
        "rel": rel,
        "is_nested": is_nested,
        "build_lines": build_lines,
        "stack_tags": stack_tags,
        "agent_files": agents,
        "has_readme": has_readme,
        "readme_excerpt": excerpt,
        "nested_md": nested_note,
        "git": g,
        "override": override,
        "agent_summary": agent_summary,
        "python_version": py_ver,
    }
    health_score, health_notes = compute_health_score(path, partial)
    partial["health_score"] = health_score
    partial["health_notes"] = health_notes
    return partial


def frontmatter_block(data: dict[str, Any]) -> str:
    """YAML-like front matter as JSON in YAML fences is confusing; use JSON for tool-parseability."""
    meta = {
        "generated_at": _utc_now_iso(),
        "generator_version": GENERATOR_VERSION,
        "source_path": data["path"],
        "index_key": data["index_key"],
        "slug": data["slug"],
        "has_readme": data["has_readme"],
        "stack_tags": data["stack_tags"],
        "git": data.get("git") or {},
        "health_score": data.get("health_score", 0),
        "agent_summary": data.get("agent_summary", ""),
    }
    return "---\n" + json.dumps(meta, indent=2) + "\n---\n\n"


def render_markdown(data: dict[str, Any]) -> str:
    name = data["name"]
    build = data["build_lines"]
    agents = data["agent_files"]
    build_block = (
        "\n## Build / run (detected)\n\n" + "\n".join(build)
        if build
        else "\n## Build / run (detected)\n\n- *No `package.json` / `requirements*.txt` / `Makefile` / `go.mod` / etc. at this project root — open the folder and add a README with the real command.*\n"
    )
    if agents:
        agent_block = "\n## Agent / IDE rules in this folder\n\n" + "\n".join(f"- `{f}`" for f in agents) + "\n"
    elif data["has_readme"]:
        agent_block = (
            "\n## Agent / IDE rules in this folder\n\n"
            "- *No `CLAUDE.md` / `AGENTS.md` / `.cursorrules` — use the README section below for intent.*\n"
        )
    else:
        agent_block = (
            "\n## Agent / IDE rules in this folder\n\n"
            "- *Add a `README.md` (how to run) or `CLAUDE.md` (guardrails) so agents do not guess.*\n"
        )
    ex = data["readme_excerpt"]
    excerpt_block = ""
    if ex.strip():
        excerpt_block = f"\n## README (first lines, sanitized)\n\n```\n{ex}\n```\n"

    o = data.get("override") or {}
    over_lines = []
    if o.get("summary"):
        over_lines.append(f"- **Summary:** {o['summary']}")
    if o.get("run"):
        over_lines.append(f"- **How to run:** `{o['run']}`")
    if o.get("notes"):
        over_lines.append(f"- **Notes:** {o['notes']}")
    override_block = (
        "\n## Curated notes (from `overrides/entries.json`)\n\n" + "\n".join(over_lines) + "\n"
        if over_lines
        else ""
    )

    parent_lbl = "Nested under Desktop\\AI" if data["is_nested"] else "Desktop\\AI (top level or repo bundle)"
    git = data.get("git") or {}
    git_lines = []
    if git.get("last_commit"):
        git_lines.append(f"- **Last commit:** `{git['last_commit']}`")
    if git.get("last_commit_at"):
        git_lines.append(f"- **Commit time:** {git['last_commit_at']}")
    if "dirty" in git:
        git_lines.append(
            f"- **Working tree:** {'dirty (uncommitted changes)' if git['dirty'] else 'clean'}"
        )
    git_block = (
        "\n## Git (local repo, if any)\n\n" + "\n".join(git_lines) + "\n"
        if git_lines
        else "\n## Git (local repo, if any)\n\n- *Not a git repo, or `git` failed / `AGENT_STUDY_GIT=0` — no metadata here.*\n"
    )

    # Health score badge
    hs = data.get("health_score", 0)
    hn = data.get("health_notes") or []
    if hs >= 80:
        hs_badge = f"🟢 **{hs}/100**"
    elif hs >= 50:
        hs_badge = f"🟡 **{hs}/100**"
    else:
        hs_badge = f"🔴 **{hs}/100**"
    health_notes_md = ("\n".join(f"  - {n}" for n in hn)) if hn else "  - *(all checks passed)*"
    health_block = f"\n## Project health\n\n{hs_badge}\n\n{health_notes_md}\n"

    # Agent-friendly summary block
    summary = data.get("agent_summary", "").strip()
    py_ver = data.get("python_version")
    summary_lines = []
    if summary:
        summary_lines.append(f"> {summary}")
    if py_ver:
        summary_lines.append(f"\n**Python target:** `{py_ver}`")
    summary_block = (
        "\n## One-line context (for AI assistants)\n\n" + "\n".join(summary_lines) + "\n"
        if summary_lines
        else ""
    )

    body = f"""# Agent study — `{name}`

**Path:** `{data['path']}`
**Rel from Desktop AI root:** `{data['rel']}`
**Parent scope:** {parent_lbl}
{summary_block}
## What this is

Auto-generated **inventory card**: facts below are from files on disk at generation time, not from memory. **Always** open the project README or source for authority.
{health_block}
{build_block}
{git_block}
{agent_block}
{override_block}
{excerpt_block}
{data.get('nested_md', '')}## Suggested handoff (for an AI assistant)

1. `cd` to **path** above.
2. Read **One-line context** above for quick orientation.
3. Use **Build / run** and **Curated notes** (if any) for the one command to try first.
4. Do not paste secrets; use `.env.example` and local env.

---
*Regenerate: `python docs/agent-study/generate_desktop_ai_studies.py` in Desktop `AI` root (or set `DESKTOP_AI_ROOT`).*
"""
    return frontmatter_block(data) + body


def write_project(
    path: Path,
    index_key: str,
    written: list[dict[str, Any]],
    overrides_map: dict[str, Any],
    out_slug: str | None = None,
) -> None:
    slug = out_slug or safe_slug(index_key.replace("/", "__"))
    data = scan_one(path, index_key, slug, overrides_map)
    text = render_markdown(data)
    (OUT / f"{slug}.md").write_text(text, encoding="utf-8")
    # JSON record (no huge excerpt for machine index)
    rec = {k: data[k] for k in ("index_key", "slug", "path", "rel", "has_readme", "stack_tags", "git", "is_nested")}
    rec["build_hint_count"] = len(data["build_lines"])
    rec["has_agent_files"] = len(data["agent_files"]) > 0
    rec["has_curated_override"] = bool(data.get("override", {}))
    rec["health_score"] = data.get("health_score", 0)
    rec["agent_summary"] = data.get("agent_summary", "")
    rec["python_version"] = data.get("python_version")
    written.append(rec)


def main() -> int:
    global ROOT, OUT, OVERRIDES_DIR
    ROOT = Path(os.environ.get("DESKTOP_AI_ROOT", r"C:\Users\computer\Desktop\AI")).resolve()
    OUT = ROOT / "docs" / "agent-study" / "projects"
    OVERRIDES_DIR = ROOT / "docs" / "agent-study" / "overrides"
    OVERRIDES_DIR.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)

    overrides_map = load_merged_overrides()
    written: list[dict[str, Any]] = []

    for child in sorted(ROOT.iterdir(), key=lambda x: x.name.lower()):
        if not child.is_dir() or child.name in SKIP_NAMES or child.name.startswith("."):
            continue
        write_project(child, child.name, written, overrides_map)
        if child.name.lower() == "ai":
            for sub in subprojects(child):
                write_project(sub, f"ai/{sub.name}", written, overrides_map, out_slug=safe_slug(f"ai__{sub.name}"))

    toolkit_repos = ROOT / "ai_toolkit" / "repos"
    if toolkit_repos.is_dir():
        for sub in subprojects(toolkit_repos):
            write_project(
                sub,
                f"ai_toolkit/repos/{sub.name}",
                written,
                overrides_map,
                out_slug=safe_slug(f"ai_toolkit__repos__{sub.name}"),
            )

    # INDEX.md
    index_lines = [
        f"# Desktop AI — per-project index",
        f"",
        f"**Root:** `{ROOT}`  ",
        f"**Generated:** `{_utc_now_iso()}`  ",
        f"**Generator:** v{GENERATOR_VERSION}  ",
        f"**Count:** {len(written)}  ",
        f"",
        f"**Machine index:** [index.json](index.json)  ",
        f"",
        f"**Optional notes:** [overrides/README.md](overrides/README.md) — merge into cards via `entries.json`  ",
        f"",
        f"## Projects",
        f"",
    ]
    for w in sorted(written, key=lambda x: (x["index_key"].lower())):
        slug = w["slug"]
        tags = ", ".join(f"`{t}`" for t in w.get("stack_tags", [])[:5])
        extra = f" *({tags})*" if tags else ""
        index_lines.append(f"- [{w['index_key']}](projects/{slug}.md){extra}")
    (ROOT / "docs" / "agent-study" / "INDEX.md").write_text("\n".join(index_lines) + "\n", encoding="utf-8")

    # index.json
    out_json = {
        "generated_at": _utc_now_iso(),
        "generator_version": GENERATOR_VERSION,
        "root": str(ROOT),
        "count": len(written),
        "projects": sorted(written, key=lambda x: x["index_key"].lower()),
    }
    (ROOT / "docs" / "agent-study" / "index.json").write_text(
        json.dumps(out_json, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"Wrote {len(written)} project docs to {OUT}")
    print(f"Wrote index.json and INDEX.md (generator {GENERATOR_VERSION})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
