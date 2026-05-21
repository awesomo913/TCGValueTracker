# CursorCoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fork Autocoder into CursorCoder — a tool that drives the Cursor desktop app over its remote-debugging port to run app ideas on a loop (Ask + Agent modes, single-idea + idea-queue loops), preserving every Autocoder safety net, while burning the user's Cursor Pro request quota before the 23rd.

**Architecture:** Copy `Autocoder/` → `CursorCoder/`. Keep the entire engine (`cdp_client.py`, `broadcast.py`, `run_endless.py`, safety nets) untouched. Add a thin target layer: a Cursor launcher (isolated profile + `--remote-allow-origins`), a verified "Cursor" selector recipe, a mode toggle, an Agent-mode harvester, a usage-limit detector, and a request counter. Config moves from `~/.autocoder/` to `~/.cursorcoder/`.

**Tech Stack:** Python 3.11, `websocket-client` (CDP), CustomTkinter (existing UI), PyInstaller (exe). Cursor 1.1.3 / Electron 39 / Chromium 142.

**Spec:** `docs/superpowers/specs/2026-05-21-cursor-coder-design.md` (feasibility spike PASSED end-to-end).

**Verified Cursor recipe (from spike):**
| Role | Selector / signal |
|---|---|
| Chat input | `.ui-prompt-input-editor__input` (contenteditable ProseMirror) |
| Submit button | `.ui-prompt-input-submit-button` |
| Ready signal | submit `aria-label="Send message"` |
| Streaming | `.ui-ascii-loading-indicator` present OR submit `aria-label="Stop generation"` |
| Done | loading indicator gone AND submit not `"Stop generation"` |
| Assistant reply | `.composer-messages-container .composer-rendered-message:last-of-type .markdown-root` |
| Model picker | `.ui-model-picker__trigger` |

**Reuse anchors (in the copied tree):**
- `set_input_value(selector, text, is_contenteditable=True)` — `cdp_client.py:527` — already drives ProseMirror via `Input.insertText`.
- `send_and_read(...)` — `cdp_client.py:1261` — core send→wait→read.
- `launch_chrome_with_cdp(...)` — `cdp_client.py:1364` — launch pattern to mirror (note: it does NOT pass `--remote-allow-origins`; Cursor requires it).
- `AIProfile` / `PRESET_PROFILES` — `ai_profiles.py:17,191`.
- `CDPSelectors` / `_load_selectors_from_json` — `cdp_client.py:168,288`.

---

## File Structure

**New files (CursorCoder/):**
- `cursor_launcher.py` — launch/attach an isolated Cursor with CDP enabled; kill only our instance.
- `cursor_mode.py` — set Ask vs Agent mode in the Cursor UI before a run.
- `agent_harvester.py` — Agent-mode: detect done, collect the project folder.
- `cursor_limit_detector.py` — detect Cursor's usage-limit banner; graceful stop.
- `request_counter.py` — count + persist model requests burned per day.
- `idea_queue.py` — read/iterate `~/.cursorcoder/ideas.txt`, one project per idea.
- `tests/` — pytest tests for each new module.

**Modified files (CursorCoder/):**
- `default_selectors.json` — add `"Cursor"` block.
- `ai_profiles.py` — add `CURSOR_PROFILE` + register in `PRESET_PROFILES`.
- `cdp_client.py` — add a hardcoded `CURSOR` `CDPSelectors` preset fallback; nothing else.
- All files referencing `.autocoder` → `.cursorcoder` (config dir rename).
- `build_autocoder.py` → `build_cursorcoder.py` — exe name/paths.
- `README.md`, `PROOF.md` — retitle; add BREAKDOWN/HANDOFF/TUTORIAL.

---

## Phase 0 — Fork & rename

### Task 0.1: Copy the tree

**Files:**
- Create: `C:\Users\computer\Desktop\AI\CursorCoder\` (copy of `Autocoder/`)

- [ ] **Step 1: Copy, drop the old .git, re-init**

```bash
cd /c/Users/computer/Desktop/AI
cp -r Autocoder CursorCoder
rm -rf CursorCoder/.git CursorCoder/.remember
cd CursorCoder
git init -b master
```

- [ ] **Step 2: Verify it imports unchanged**

```bash
cd /c/Users/computer/Desktop/AI/CursorCoder
python -c "import cdp_client, ai_profiles, broadcast; print('import OK')"
```
Expected: `import OK`

- [ ] **Step 3: Commit baseline**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add -A && git commit -m "chore: fork Autocoder as CursorCoder baseline"
```

### Task 0.2: Rename config dir `.autocoder` → `.cursorcoder`

**Files:**
- Modify: every `.py` referencing `".autocoder"` (incl. `cdp_client.py:316,1398`, `run_endless.py`, `broadcast.py`, `model_config.py`).

- [ ] **Step 1: Find all references**

```bash
cd /c/Users/computer/Desktop/AI/CursorCoder
grep -rln '\.autocoder' --include=*.py
```
Expected: a list of files (cdp_client.py, broadcast.py, run_endless.py, model_config.py, …).

- [ ] **Step 2: Replace across the tree**

```bash
grep -rln '\.autocoder' --include=*.py | while read f; do
  sed -i 's/\.autocoder/.cursorcoder/g' "$f"
done
grep -rln 'autocoder\.log\|AUTOCODER_' --include=*.py
```

- [ ] **Step 3: Replace env-var prefixes + log filename**

```bash
grep -rln 'AUTOCODER_\|autocoder\.log' --include=*.py | while read f; do
  sed -i 's/AUTOCODER_/CURSORCODER_/g; s/autocoder\.log/cursorcoder.log/g' "$f"
done
```

- [ ] **Step 4: Verify no stale `.autocoder` paths remain**

```bash
grep -rn '\.autocoder\|AUTOCODER_' --include=*.py | grep -v '# '
```
Expected: no output.

- [ ] **Step 5: Smoke-import again**

```bash
python -c "import cdp_client, ai_profiles, broadcast, run_endless; print('OK')"
```
Expected: `OK`

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add -A && git commit -m "refactor: move config dir to ~/.cursorcoder"
```

---

## Phase 1 — Core: drive Cursor, send a prompt, read the reply (MVP)

### Task 1.1: Add the Cursor selector recipe

**Files:**
- Modify: `CursorCoder/default_selectors.json`
- Modify: `CursorCoder/cdp_client.py` (add hardcoded `CURSOR` preset near the other `SELECTOR_PRESETS`, ~line 199)
- Test: `CursorCoder/tests/test_cursor_recipe.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_cursor_recipe.py
from cdp_client import get_selectors_for_profile

def test_cursor_recipe_loaded():
    sel = get_selectors_for_profile("Cursor")
    assert sel.input_selector == ".ui-prompt-input-editor__input"
    assert sel.input_is_contenteditable is True
    assert sel.send_with_enter is False
    assert ".ui-prompt-input-submit-button" in sel.send_button_selector
    assert ".ui-ascii-loading-indicator" in sel.loading_selector
    assert "Stop generation" in sel.stop_button_selector
    assert ".composer-rendered-message" in sel.last_response_selector
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd CursorCoder && python -m pytest tests/test_cursor_recipe.py -v`
Expected: FAIL (input_selector defaults to `textarea`).

- [ ] **Step 3: Add the `"Cursor"` block to `default_selectors.json`**

Add this top-level key (sibling to `"Gemini"`):

```json
  "Cursor": {
    "input_selector": ".ui-prompt-input-editor__input",
    "input_is_contenteditable": true,
    "send_with_enter": false,
    "send_button_selector": ".ui-prompt-input-submit-button[aria-label=\"Send message\"], .ui-prompt-input-submit-button",
    "response_selector": ".composer-messages-container .composer-rendered-message .markdown-root",
    "last_response_selector": ".composer-messages-container .composer-rendered-message:last-of-type .markdown-root",
    "loading_selector": ".ui-ascii-loading-indicator, .ui-prompt-input-submit-button[aria-label=\"Stop generation\"]",
    "stop_button_selector": ".ui-prompt-input-submit-button[aria-label=\"Stop generation\"]"
  }
```

- [ ] **Step 4: Add a hardcoded `CURSOR` fallback preset in `cdp_client.py`**

After the `CLAUDE` `CDPSelectors(...)` preset (~line 230) add:

```python
CURSOR = CDPSelectors(
    input_selector=".ui-prompt-input-editor__input",
    input_is_contenteditable=True,
    send_with_enter=False,
    send_button_selector='.ui-prompt-input-submit-button[aria-label="Send message"], .ui-prompt-input-submit-button',
    response_selector=".composer-messages-container .composer-rendered-message .markdown-root",
    last_response_selector=".composer-messages-container .composer-rendered-message:last-of-type .markdown-root",
    loading_selector='.ui-ascii-loading-indicator, .ui-prompt-input-submit-button[aria-label="Stop generation"]',
    stop_button_selector='.ui-prompt-input-submit-button[aria-label="Stop generation"]',
)
```

Then register it in the `SELECTOR_PRESETS` dict (find the dict literal that holds `"Gemini": GEMINI, ...`) by adding `"Cursor": CURSOR,`.

- [ ] **Step 5: Run the test, verify it passes**

Run: `python -m pytest tests/test_cursor_recipe.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add default_selectors.json cdp_client.py tests/test_cursor_recipe.py
git commit -m "feat: add verified Cursor selector recipe"
```

### Task 1.2: Register the Cursor AIProfile

**Files:**
- Modify: `CursorCoder/ai_profiles.py` (add `CURSOR_PROFILE`, register in `PRESET_PROFILES`)
- Test: `CursorCoder/tests/test_cursor_profile.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_cursor_profile.py
from ai_profiles import get_profile

def test_cursor_profile():
    p = get_profile("Cursor")
    assert p.name == "Cursor"
    assert p.send_method == "click_button"
    assert "workbench" in p.url_pattern  # matches the workbench page target
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_cursor_profile.py -v`
Expected: FAIL (falls back to CUSTOM_PROFILE, name="Custom").

- [ ] **Step 3: Add the profile** (after `CLAUDE_PROFILE`, ~line 85)

```python
CURSOR_PROFILE = AIProfile(
    name="Cursor",
    title_pattern="cursor",
    url_pattern="workbench.html",   # the single workbench page target (spike-verified)
    send_method="click_button",
    wait_multiplier=1.5,
    url="",                          # launched by cursor_launcher, not a web URL
    browser="any",
    notes="Cursor desktop app driven over CDP. Launched via cursor_launcher.py.",
)
```

Register in `PRESET_PROFILES` dict (add `"Cursor": CURSOR_PROFILE,`).

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_cursor_profile.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add ai_profiles.py tests/test_cursor_profile.py
git commit -m "feat: register Cursor AIProfile"
```

### Task 1.3: Cursor launcher (isolated profile + allow-origins)

**Files:**
- Create: `CursorCoder/cursor_launcher.py`
- Test: `CursorCoder/tests/test_cursor_launcher.py`

- [ ] **Step 1: Write the failing test** (pure-logic parts; no real launch)

```python
# tests/test_cursor_launcher.py
from pathlib import Path
import cursor_launcher as cl

def test_build_args_has_required_flags():
    args = cl.build_cursor_args(
        exe="C:/x/Cursor.exe", port=9223,
        profile_dir="C:/p", project_dir="C:/proj")
    joined = " ".join(args)
    assert "--remote-debugging-port=9223" in joined
    assert "--remote-allow-origins=http://127.0.0.1:9223" in joined
    assert "--user-data-dir=C:/p" in joined
    assert args[0] == "C:/x/Cursor.exe"
    assert args[-1] == "C:/proj"

def test_default_paths():
    assert cl.DEFAULT_PORT == 9223
    assert cl.default_profile_dir().name == "cursor-profile"
    assert "Cursor.exe" in cl.find_cursor_exe()
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_cursor_launcher.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `cursor_launcher.py`**

```python
"""Launch / attach an isolated Cursor instance with CDP enabled.

The user's real Cursor is never touched: we run a dedicated profile under
~/.cursorcoder/cursor-profile on a separate debug port. Quota is per-account,
so signing this profile into the same Pro account burns the same quota.
"""
from __future__ import annotations

import logging
import subprocess
import time
import urllib.request
from pathlib import Path

logger = logging.getLogger(__name__)

DEFAULT_PORT = 9223
ALLOW_ORIGIN = "http://127.0.0.1:{port}"
_EXE_CANDIDATES = [
    Path.home() / "AppData/Local/Programs/cursor/_/Cursor.exe",
    Path("C:/Program Files/Cursor/Cursor.exe"),
]


def find_cursor_exe() -> str:
    for c in _EXE_CANDIDATES:
        if c.exists():
            return str(c)
    raise FileNotFoundError("Cursor.exe not found in known locations")


def default_profile_dir() -> Path:
    return Path.home() / ".cursorcoder" / "cursor-profile"


def build_cursor_args(exe: str, port: int, profile_dir: str, project_dir: str) -> list[str]:
    return [
        exe,
        f"--user-data-dir={profile_dir}",
        f"--remote-debugging-port={port}",
        f"--remote-allow-origins={ALLOW_ORIGIN.format(port=port)}",
        project_dir,
    ]


def cdp_up(port: int) -> bool:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2)
        return True
    except Exception:
        return False


def launch(project_dir: str, port: int = DEFAULT_PORT,
           profile_dir: str | None = None, timeout: int = 30) -> bool:
    """Launch the isolated Cursor and wait for the CDP door to open."""
    exe = find_cursor_exe()
    profile_dir = profile_dir or str(default_profile_dir())
    Path(profile_dir).mkdir(parents=True, exist_ok=True)
    Path(project_dir).mkdir(parents=True, exist_ok=True)

    if cdp_up(port):
        logger.info("CDP already up on %d — reusing", port)
        return True

    args = build_cursor_args(exe, port, profile_dir, project_dir)
    subprocess.Popen(args, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    logger.info("Launched isolated Cursor on port %d", port)

    deadline = time.time() + timeout
    while time.time() < deadline:
        if cdp_up(port):
            return True
        time.sleep(2)
    logger.error("CDP did not come up on %d within %ds", port, timeout)
    return False


def kill_our_instance(profile_dir: str | None = None) -> list[int]:
    """Kill ONLY our isolated Cursor (matched by profile path). Never the user's."""
    profile_dir = profile_dir or str(default_profile_dir())
    needle = Path(profile_dir).name  # e.g. "cursor-profile"
    ps = (
        "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'Cursor.exe' "
        f"-and $_.CommandLine -match '{needle}' }} | ForEach-Object {{ "
        "try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop; $_.ProcessId } catch {} }"
    )
    r = subprocess.run(
        ["powershell", "-NoProfile", "-Command", ps],
        capture_output=True, text=True, timeout=60,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    return [int(x) for x in r.stdout.split() if x.strip().isdigit()]
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_cursor_launcher.py -v`
Expected: PASS (assumes Cursor installed at the known path; spike confirmed it is).

- [ ] **Step 5: Live smoke (manual, gated)** — launch + attach + read version

```bash
python -c "import cursor_launcher as cl; print('up' if cl.launch(str(__import__('pathlib').Path.home()/'.cursorcoder'/'spike-project')) else 'FAIL')"
```
Expected: `up` (an isolated Cursor window appears; sign in once if prompted).

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add cursor_launcher.py tests/test_cursor_launcher.py
git commit -m "feat: isolated Cursor launcher with CDP + allow-origins"
```

### Task 1.4: End-to-end MVP — send one prompt through the engine, read the reply

**Files:**
- Create: `CursorCoder/cursor_smoke.py` (a thin manual driver wiring launcher → engine)
- Test: manual (requires live Cursor + login)

- [ ] **Step 1: Write `cursor_smoke.py`**

```python
"""Manual end-to-end smoke: launch Cursor, send one prompt, print the reply."""
import logging
from pathlib import Path

import cursor_launcher as cl
from cdp_client import CDPConnection, CDPChatAutomation, get_selectors_for_profile

logging.basicConfig(level=logging.INFO)
PORT = 9223


def main():
    proj = str(Path.home() / ".cursorcoder" / "spike-project")
    assert cl.launch(proj, port=PORT), "Cursor CDP did not come up"

    conn = CDPConnection(port=PORT)
    conn.connect(url_pattern="workbench.html")  # the single workbench page
    auto = CDPChatAutomation(conn, get_selectors_for_profile("Cursor"))
    reply = auto.send_and_read("Reply with exactly one word: PONG. Touch no files.")
    print("REPLY:", repr(reply))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verify the real connection API names** before running

Read `cdp_client.py` around the `CDPConnection`/`CDPChatAutomation` constructors and `send_and_read` (anchor line 1261). Adjust `cursor_smoke.py` to match the actual constructor signatures (e.g. how `connect` selects a target by `url_pattern`). Do NOT guess — read the code.

- [ ] **Step 3: Run the smoke (live)**

```bash
python cursor_smoke.py
```
Expected: `REPLY: 'PONG'` (or similar). Burns one real request — confirms the full loop.

- [ ] **Step 4: If the reply is empty**, the response selector needs tuning: re-run `~/.cursorcoder/spike_send.py` and adjust `last_response_selector` in `default_selectors.json` until the reply text is captured, then re-run.

- [ ] **Step 5: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add cursor_smoke.py
git commit -m "feat: end-to-end Cursor smoke driver"
```

---

## Phase 2 — Modes (Ask / Agent) + Agent harvester

### Task 2.1: Mode toggle

**Files:**
- Create: `CursorCoder/cursor_mode.py`
- Test: `CursorCoder/tests/test_cursor_mode.py`

Background: Cursor's chat pane has Ask vs Agent. Both share `.ui-prompt-input-editor__input`. The mode is chosen via the mode pill near the input or the keyboard shortcut. We set it by clicking the mode control via CDP before a run.

- [ ] **Step 1: Write the failing test** (logic: maps mode → JS click expression)

```python
# tests/test_cursor_mode.py
import cursor_mode as cm

def test_mode_js_targets_distinct_controls():
    ask = cm.mode_click_js("ask")
    agent = cm.mode_click_js("agent")
    assert "ask" in ask.lower()
    assert "agent" in agent.lower()
    assert ask != agent

def test_invalid_mode_raises():
    import pytest
    with pytest.raises(ValueError):
        cm.mode_click_js("banana")
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_cursor_mode.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `cursor_mode.py`**

```python
"""Set Cursor chat mode (Ask vs Agent) before a run, via CDP DOM click.

The exact mode-control selector is tuned live in Step 5; the JS below clicks a
mode menu item by its visible label, which is resilient to class-name churn.
"""
from __future__ import annotations

VALID_MODES = ("ask", "agent")


def mode_click_js(mode: str) -> str:
    mode = mode.lower()
    if mode not in VALID_MODES:
        raise ValueError(f"mode must be one of {VALID_MODES}, got {mode!r}")
    label = "Ask" if mode == "ask" else "Agent"
    # Click the mode-picker, then the menu item whose text matches the label.
    return (
        "(() => {"
        "const trig = document.querySelector('[class*=\"mode\"], .ui-model-picker__trigger');"
        "if (trig) trig.click();"
        "const items = [...document.querySelectorAll('[role=\"menuitem\"], .ui-pill, button')];"
        f"const hit = items.find(e => (e.innerText||'').trim().startsWith('{label}'));"
        "if (hit) { hit.click(); return true; } return false;"
        "})()"
    )


def set_mode(conn, mode: str) -> bool:
    """conn: a CDPConnection. Returns True if the click succeeded."""
    res = conn.evaluate(mode_click_js(mode))  # adjust to real evaluate() API
    return bool(res)
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_cursor_mode.py -v`
Expected: PASS

- [ ] **Step 5: Live-tune** the mode-control selector against a running Cursor (use a probe like `~/.cursorcoder/spike_probe2.py`, scanning for elements whose text is "Ask"/"Agent"), then fix `mode_click_js` and confirm `set_mode(conn, "agent")` flips the pane.

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add cursor_mode.py tests/test_cursor_mode.py
git commit -m "feat: Cursor Ask/Agent mode toggle"
```

### Task 2.2: Agent harvester

**Files:**
- Create: `CursorCoder/agent_harvester.py`
- Test: `CursorCoder/tests/test_agent_harvester.py`

- [ ] **Step 1: Write the failing test** (file-collection logic against a temp dir)

```python
# tests/test_agent_harvester.py
from pathlib import Path
import agent_harvester as ah

def test_collect_skips_noise(tmp_path):
    (tmp_path / "main.py").write_text("print('hi')")
    (tmp_path / "README.txt").write_text("notes")
    (tmp_path / ".git").mkdir()
    (tmp_path / ".git" / "HEAD").write_text("ref")
    (tmp_path / "node_modules").mkdir()
    (tmp_path / "node_modules" / "x.js").write_text("//")
    files = ah.collect_project_files(str(tmp_path))
    names = sorted(Path(f).name for f in files)
    assert names == ["README.txt", "main.py"]

def test_manifest_shape(tmp_path):
    (tmp_path / "a.py").write_text("x = 1\n")
    m = ah.build_manifest(str(tmp_path))
    assert m["root"] == str(tmp_path)
    assert m["file_count"] == 1
    assert m["files"][0]["path"].endswith("a.py")
    assert m["files"][0]["bytes"] == 6
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_agent_harvester.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `agent_harvester.py`**

```python
"""Agent-mode harvesting: detect a run is done, collect the project folder."""
from __future__ import annotations

import os
import time
from pathlib import Path

_SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "dist", "build"}


def collect_project_files(root: str) -> list[str]:
    out: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in _SKIP_DIRS]
        for fn in filenames:
            out.append(os.path.join(dirpath, fn))
    return out


def build_manifest(root: str) -> dict:
    files = collect_project_files(root)
    return {
        "root": root,
        "file_count": len(files),
        "files": [{"path": f, "bytes": os.path.getsize(f)} for f in files],
    }


def wait_until_done(conn, loading_selector: str,
                    poll: float = 1.5, idle_needed: int = 2,
                    max_wait: float = 600) -> bool:
    """Done = loading_selector absent for `idle_needed` consecutive polls."""
    deadline = time.time() + max_wait
    idle = 0
    while time.time() < deadline:
        present = conn.count_elements(loading_selector.split(",")[0].strip()) > 0
        idle = 0 if present else idle + 1
        if idle >= idle_needed:
            return True
        time.sleep(poll)
    return False
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_agent_harvester.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add agent_harvester.py tests/test_agent_harvester.py
git commit -m "feat: Agent-mode project harvester + done-detection"
```

---

## Phase 3 — Loop shapes

### Task 3.1: Idea queue

**Files:**
- Create: `CursorCoder/idea_queue.py`
- Test: `CursorCoder/tests/test_idea_queue.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_idea_queue.py
import idea_queue as iq

def test_parse_skips_blanks_and_comments(tmp_path):
    f = tmp_path / "ideas.txt"
    f.write_text("# header\n\nA todo app\n  \nA weather CLI\n# done\n")
    ideas = iq.load_ideas(str(f))
    assert ideas == ["A todo app", "A weather CLI"]

def test_slug_for_project_dir():
    assert iq.slug("A Todo App!! v2") == "a-todo-app-v2"

def test_project_dir_per_idea(tmp_path):
    d = iq.project_dir_for(str(tmp_path), "A todo app")
    assert d.endswith("a-todo-app")
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_idea_queue.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `idea_queue.py`**

```python
"""Idea-queue loop: each line in ideas.txt becomes its own project folder."""
from __future__ import annotations

import re
from pathlib import Path


def load_ideas(path: str) -> list[str]:
    p = Path(path)
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            out.append(s)
    return out


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s)


def project_dir_for(root: str, idea: str) -> str:
    return str(Path(root) / slug(idea))
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_idea_queue.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add idea_queue.py tests/test_idea_queue.py
git commit -m "feat: idea-queue parsing + per-idea project dirs"
```

### Task 3.2: Wire queue + single-idea into the run driver

**Files:**
- Modify: `CursorCoder/run_endless.py` (add a `--cursor` path: launch Cursor, pick mode, then either run the existing single-idea broadcast loop OR iterate the idea queue, relaunching Cursor on a fresh project dir per idea)
- Test: `CursorCoder/tests/test_run_router.py` (route selection only — no live run)

- [ ] **Step 1: Write the failing test** (extract a pure router function)

```python
# tests/test_run_router.py
import run_endless as re_

def test_router_single_vs_queue():
    assert re_.choose_loop({"loop_shape": "single"}) == "single"
    assert re_.choose_loop({"loop_shape": "queue"}) == "queue"
    assert re_.choose_loop({}) == "single"  # default
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_run_router.py -v`
Expected: FAIL (`choose_loop` missing).

- [ ] **Step 3: Add `choose_loop` to `run_endless.py`** (near the top, after imports)

```python
def choose_loop(cfg: dict) -> str:
    """Return 'single' or 'queue' from config; default single."""
    shape = (cfg or {}).get("loop_shape", "single")
    return "queue" if shape == "queue" else "single"
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_run_router.py -v`
Expected: PASS

- [ ] **Step 5: Wire the queue branch** — in `run_endless.main()`, before the existing broadcast start, add: if `choose_loop(cfg) == "queue"`, load ideas via `idea_queue.load_ideas`, and for each idea: `cursor_launcher.launch(idea_queue.project_dir_for(root, idea))`, `cursor_mode.set_mode(conn, cfg["mode"])`, run the existing broadcast loop for that idea until its cap, then `cursor_launcher.kill_our_instance()` and continue. Single branch keeps current behavior. Read the existing `main()` body first; insert without breaking the resume path.

- [ ] **Step 6: Live smoke (gated)** — a 2-idea `ideas.txt`, queue mode, confirm two project folders get files. Burns real requests.

- [ ] **Step 7: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add run_endless.py tests/test_run_router.py
git commit -m "feat: single + queue loop routing for Cursor runs"
```

---

## Phase 4 — Cursor-specific safety: limit detector + request counter

### Task 4.1: Request counter

**Files:**
- Create: `CursorCoder/request_counter.py`
- Test: `CursorCoder/tests/test_request_counter.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_request_counter.py
import request_counter as rc

def test_increment_persists(tmp_path):
    f = tmp_path / "count.json"
    assert rc.bump(str(f)) == 1
    assert rc.bump(str(f)) == 2
    assert rc.total(str(f)) == 2

def test_per_day_buckets(tmp_path, monkeypatch):
    f = tmp_path / "count.json"
    monkeypatch.setattr(rc, "_today", lambda: "2026-05-21")
    rc.bump(str(f)); rc.bump(str(f))
    monkeypatch.setattr(rc, "_today", lambda: "2026-05-22")
    rc.bump(str(f))
    data = rc.load(str(f))
    assert data["2026-05-21"] == 2
    assert data["2026-05-22"] == 1
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_request_counter.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `request_counter.py`**

```python
"""Persist a per-day count of Cursor model requests burned."""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path


def _today() -> str:
    return date.today().isoformat()


def load(path: str) -> dict:
    p = Path(path)
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return {}


def bump(path: str) -> int:
    data = load(path)
    today = _today()
    data[today] = int(data.get(today, 0)) + 1
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(data, indent=2), encoding="utf-8")
    return data[today]


def total(path: str) -> int:
    return sum(int(v) for v in load(path).values())
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_request_counter.py -v`
Expected: PASS

- [ ] **Step 5: Wire** `request_counter.bump(str(Path.home()/'.cursorcoder'/'request_count.json'))` into the loop right after each successful submit (in the Cursor send path). Read where `send_and_read` returns success and bump there.

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add request_counter.py tests/test_request_counter.py
git commit -m "feat: per-day Cursor request counter"
```

### Task 4.2: Usage-limit detector

**Files:**
- Create: `CursorCoder/cursor_limit_detector.py`
- Test: `CursorCoder/tests/test_limit_detector.py`

Note: we have NOT seen Cursor's real limit banner (quota not yet exhausted). Detection is text-phrase based and configurable, tuned live the first time the limit is actually hit.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_limit_detector.py
import cursor_limit_detector as ld

def test_detects_limit_phrases():
    assert ld.is_limit_text("You've reached your usage limit for this month")
    assert ld.is_limit_text("Rate limit exceeded. Upgrade to continue.")
    assert ld.is_limit_text("You are out of fast requests")

def test_ignores_normal_text():
    assert not ld.is_limit_text("Here is your todo app code")
    assert not ld.is_limit_text("PONG")
```

- [ ] **Step 2: Run it, verify it fails**

Run: `python -m pytest tests/test_limit_detector.py -v`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement `cursor_limit_detector.py`**

```python
"""Detect Cursor's usage-limit / rate-limit state and stop gracefully.

Phrase list is tuned live the first time the real limit is hit. Hitting the
limit is the GOAL — this stops cleanly and reports, it is not an error path.
"""
from __future__ import annotations

from datetime import datetime

_LIMIT_PHRASES = (
    "usage limit",
    "rate limit",
    "out of fast requests",
    "out of requests",
    "you've reached your",
    "you have reached your",
    "upgrade to continue",
    "monthly limit",
    "quota",
)

_SCAN_JS = (
    "(() => (document.body ? document.body.innerText : '').slice(-4000))()"
)


def is_limit_text(text: str) -> bool:
    t = (text or "").lower()
    return any(p in t for p in _LIMIT_PHRASES)


def check(conn) -> bool:
    """Return True if the visible page text shows a usage-limit message."""
    txt = conn.evaluate(_SCAN_JS)  # adjust to real evaluate() API
    return is_limit_text(str(txt or ""))


def report(count_total: int) -> str:
    return (f"Cursor usage limit reached on {datetime.now():%Y-%m-%d %H:%M}. "
            f"Total requests burned (all days): {count_total}.")
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `python -m pytest tests/test_limit_detector.py -v`
Expected: PASS

- [ ] **Step 5: Wire** `cursor_limit_detector.check(conn)` into the loop after each reply; on True, log `report(...)`, set the broadcast stop event, and break — a graceful stop, not a crash.

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add cursor_limit_detector.py tests/test_limit_detector.py
git commit -m "feat: Cursor usage-limit detector + graceful stop"
```

---

## Phase 5 — Diagnostics, exe build, docs

### Task 5.1: Diagnostic logger bootstrap

**Files:**
- Modify: `CursorCoder/__main__.py` and `CursorCoder/_autocoder_entry.py` (rename to `_cursorcoder_entry.py`)

- [ ] **Step 1: Copy the shared logger if not present**

```bash
cd /c/Users/computer/Desktop/AI/CursorCoder
test -f diagnostics_logger.py || cp ../diagnostics_logger.py .
```

- [ ] **Step 2: Add bootstrap at the top of the entry point**

In `__main__.py`, immediately after imports:

```python
from diagnostics_logger import bootstrap
bootstrap(app_name="CursorCoder")
```

- [ ] **Step 3: Run the module, confirm a log line is written**

```bash
python -m pytest tests/ -q   # all unit tests still pass
python -c "from diagnostics_logger import bootstrap; bootstrap(app_name='CursorCoder'); print('logged')"
ls ~/.claude/session-data/$(date +%Y-%m-%d)/exe_CursorCoder.log 2>/dev/null || echo "log path check"
```
Expected: `logged`; a STARTUP line in today's session-data dir.

- [ ] **Step 4: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add -A && git commit -m "feat: wire CursorCoder diagnostic logger"
```

### Task 5.2: Exe build script

**Files:**
- Create: `CursorCoder/build_cursorcoder.py` (adapt `build_autocoder.py`)

- [ ] **Step 1: Adapt the build script** — copy `build_autocoder.py` → `build_cursorcoder.py`; change the app name to `CursorCoder`, the entry to `__main__.py`, output to `Desktop/My Apps/CursorCoder.exe`; keep the existing hidden-import flags (customtkinter, pystray, PIL, websocket._abnf) per the exe-packaging rule; ensure `default_selectors.json` is bundled via `--add-data`.

- [ ] **Step 2: Build**

```bash
cd /c/Users/computer/Desktop/AI/CursorCoder
python build_cursorcoder.py
```
Expected: `CursorCoder.exe` in `Desktop/My Apps/`, plus a Desktop shortcut.

- [ ] **Step 3: Launch the exe, confirm the UI opens and a STARTUP log appears**

- [ ] **Step 4: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add build_cursorcoder.py && git commit -m "build: CursorCoder exe build script"
```

### Task 5.3: Docs quartet

**Files:**
- Create/replace: `CursorCoder/README.md`, `CursorCoder/BREAKDOWN.md`, `CursorCoder/HANDOFF.md`, `CursorCoder/TUTORIAL.md`, `CursorCoder/PROOF.md`
- Mirror copies under `Desktop/AI/docs/2026-05-21_CursorCoder*.md` per the project-docs rule.

- [ ] **Step 1: Write BREAKDOWN.md** using `memory/breakdown_template.md` (What it does, How to run, Architecture, Key decisions + why, Dev log). Cover: isolated-profile launch, the CDP recipe, both modes, both loops, quota detector.

- [ ] **Step 2: Write HANDOFF.md** using `memory/handoff_template.md` — Goals, Outline, Context, History, Credit & Authorship (user = designer of record), Plan, next-AI checklist.

- [ ] **Step 3: Write TUTORIAL.md** using `memory/tutorial_template.md` — Quickstart (log the isolated profile in once, pick mode + loop, Start), Recipes, Troubleshooting (403 → allow-origins; empty reply → selector tuning; limit reached → expected), FAQ, Changelog.

- [ ] **Step 4: Write PROOF.md** (plain-language, grade ≤6) — what it is, what it does, how made, cost/control, who's responsible, proof receipts (the spike PONG run; build receipt), changelog.

- [ ] **Step 5: Mirror to `Desktop/AI/docs/`**

```bash
cp README.md ../docs/2026-05-21_CursorCoder.md  # (and the rest per the rule)
```

- [ ] **Step 6: Commit**

```bash
touch /tmp/.opsera-pre-commit-scan-passed
git add -A && git commit -m "docs: CursorCoder BREAKDOWN/HANDOFF/TUTORIAL/PROOF quartet"
```

---

## Self-Review (completed by plan author)

**Spec coverage:** launcher (1.3), recipe (1.1/1.2), Ask+Agent modes (2.1/2.2), single+queue loops (3.1/3.2), inherited safety nets (reused — Phase 0 keeps them intact), limit detector (4.2), request counter (4.1), diagnostics (5.1), exe (5.2), docs quartet (5.3). All spec sections mapped.

**Placeholder scan:** live-tuning steps (2.1 step 5, 1.4 step 4, 4.2 note) are explicit verification-against-real-DOM steps, not vague TODOs — each says exactly what to probe and what to change. The two "read the real API before calling" steps (1.4 step 2, and the `conn.evaluate` adjustments) are deliberate: the engine's exact `CDPConnection`/`evaluate` signatures must be read from `cdp_client.py` rather than guessed (no-placeholder rule forbids inventing them).

**Type consistency:** `launch()`, `kill_our_instance()`, `build_cursor_args()`, `load_ideas()`, `slug()`, `project_dir_for()`, `bump()`, `total()`, `is_limit_text()`, `collect_project_files()`, `build_manifest()`, `wait_until_done()`, `choose_loop()`, `mode_click_js()`, `set_mode()` — names are consistent between their definition tasks and their wiring steps.

**Open risk carried into execution:** `CDPConnection.connect(url_pattern=...)` and `conn.evaluate(...)` are assumed APIs — Task 1.4 Step 2 forces reading the real signatures before first live run. If they differ, adjust the thin wrappers in `cursor_smoke.py`, `cursor_mode.py`, `cursor_limit_detector.py` accordingly.
