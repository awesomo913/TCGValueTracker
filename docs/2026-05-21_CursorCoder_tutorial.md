# CursorCoder — Tutorial
**Last updated:** 2026-05-21 (v1.0.0)

---

## 1. Quickstart

**What you'll accomplish:** get CursorCoder running and send your first automatic prompt to Cursor's AI in under two minutes.

**One-time setup (first launch only):**

1. Double-click `CursorCoder.exe` on your Desktop (or run `python -m CursorCoder` from `Desktop\AI`).
2. Click **Launch Cursor**. A new Cursor window opens — this is a separate, dedicated window just for CursorCoder. Your real Cursor is not touched.
3. In that new Cursor window, sign into your Cursor account (email + password, or GitHub sign-in). You only do this once.
4. Come back to the CursorCoder window.

**Every launch after that:**

1. Open CursorCoder (exe or `python -m CursorCoder`).
2. Click **Launch Cursor** — the isolated Cursor opens already signed in.
3. Pick **Mode** (Agent or Ask) and **Loop** (Single idea or Idea queue).
4. Type a task in the task box, or choose your `ideas.txt` file if you picked Idea queue.
5. Click **Start Autocoding**.
6. Watch the live request counter go up. CursorCoder runs on its own until it hits a cap or you stop it.

**What you should see:** The dedicated Cursor window typing and waiting on its own. The CursorCoder window shows the current request count and the last status message.

---

## 2. Feature Walkthrough

### 2.1 Agent Mode vs Ask Mode

**What it does:** controls how Cursor AI responds to each prompt.

- **Agent mode** — Cursor writes real files into a project folder on your disk. Use this when you want working code you can open and run.
- **Ask mode** — Cursor answers in the chat panel. CursorCoder reads and saves those replies as text files. Use this for research, explanations, or quick code snippets you don't need as full projects.

**How to set it:** The **Mode** dropdown in the CursorCoder window. Default is Agent.

**Gotcha:** In Agent mode, Cursor needs a project folder to write into. CursorCoder creates one automatically (under `~\.cursorcoder\projects\` or the folder you configured). If Cursor seems to be answering but no files appear, check that you are in Agent mode (not Ask) and that Cursor is in the right chat pane.

---

### 2.2 Single-Idea Loop vs Idea Queue

**What it does:** controls how many tasks CursorCoder runs.

- **Single idea** — CursorCoder takes the one task you typed and keeps improving it across multiple passes, cycling through improvement focuses (pressure test, add features, beautify, etc.). It stops when it hits the iteration cap or you click Stop.
- **Idea queue** — CursorCoder reads a plain text file (`ideas.txt`) where each line is one app idea. It builds the first idea, then the second, then the third, until the file is done or it hits the monthly request limit.

**How to set it:** The **Loop** dropdown. When you switch to "Idea queue", the **Choose ideas.txt…** button enables — click it and pick your file.

**Example `ideas.txt`:**
```
A todo app with due dates and color tags
A weather CLI that shows rain probability
A simple budget tracker with monthly totals
```

**Gotcha:** Blank lines and lines starting with `#` are skipped, so you can comment out done ideas.

---

### 2.3 Live Request Counter

**What it does:** shows how many Cursor Pro requests have been sent today.

The counter is visible at the bottom of the CursorCoder window and updates after every prompt sent. It persists across restarts (saved in `~\.cursorcoder\request_count.json`).

**When to use it:** track burn rate when you want to hit a specific quota target before the 23rd.

---

### 2.4 Usage-Limit Auto-Stop

**What it does:** when Cursor's own "you've reached your monthly limit" message appears, CursorCoder stops cleanly and shows a message instead of crashing or looping forever.

**This is the expected end state, not an error.** The message will say something like: *"Cursor usage limit reached on 2026-05-21 14:32. Total requests burned (all days): 487."*

You do not need to do anything. Just note the date and pick up again next month when the quota resets.

---

### 2.5 Stop Controls

**Stop button** — stops after the current task finishes (clean stop).
**Stop All button** — stops immediately, mid-task if needed.
**F10 key** — panic stop; works even if the window is not focused.
**KILL file** — create a file called `KILL` in the `~\.cursorcoder\` folder; CursorCoder will see it on the next loop and stop.

---

### 2.6 Crash Resume

If CursorCoder crashes or your computer restarts mid-run, it saves state automatically. On the next launch, click **Start Autocoding** and it picks up close to where it left off.

---

## 3. Common Workflows / Recipes

### Recipe A: Build one app, iterate until it's good

**Goal:** build a single app and keep improving it across many passes.

1. Open CursorCoder.
2. Click **Launch Cursor**.
3. Set **Mode** to **Agent**.
4. Set **Loop** to **Single idea**.
5. Type your task, e.g. `Build a simple desktop timer app in Python`.
6. Click **Start Autocoding**.
7. CursorCoder sends the task, waits for Cursor to write files, then sends improvement passes ("add features", "make it more solid", etc.) automatically.
8. Click **Stop** when you are happy with the result.
9. The project folder is under `~\.cursorcoder\projects\` (or the folder you configured).

**Result:** a project folder with working files, built and improved over multiple passes.

---

### Recipe B: Burn through a list of app ideas

**Goal:** build several different apps in one session, one per idea in a list.

1. Create a `ideas.txt` file anywhere on your computer. One idea per line.
2. Open CursorCoder.
3. Click **Launch Cursor**.
4. Set **Mode** to **Agent**.
5. Set **Loop** to **Idea queue**.
6. Click **Choose ideas.txt…** and pick your file.
7. Click **Start Autocoding**.
8. CursorCoder builds each idea in its own folder, one after another.
9. It stops automatically when the list is done or the monthly limit is hit.

**Result:** one project folder per idea, all under `~\.cursorcoder\projects\`.

---

### Recipe C: Quick Ask — get answers without writing files

**Goal:** send a series of questions and save the answers to text files.

1. Open CursorCoder.
2. Click **Launch Cursor**.
3. Set **Mode** to **Ask**.
4. Set **Loop** to **Single idea**.
5. Type your question or task.
6. Click **Start Autocoding**.
7. Answers are saved as text in `~\.cursorcoder\outputs\` (same folder structure as Autocoder).

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| "Cursor CDP did not come up" on launch | Cursor.exe not found at the default path, or it is slow to start | Wait 30 seconds and try again; if it keeps failing, check that Cursor 1.1.3+ is installed at `AppData\Local\Programs\cursor\_\Cursor.exe` |
| Cursor window opens but CursorCoder says "not connected" | The `--remote-allow-origins` flag is missing or the port is blocked | This is handled automatically by the launcher; if you see it persistently, restart CursorCoder |
| Reply is always empty | The Cursor UI changed its CSS class names since the selector recipe was written | Open an issue or re-run the discovery probe (`~\.cursorcoder\spike_send.py`) and update `default_selectors.json` |
| Short one-word replies seem to disappear | The inherited short-reply filter is trimming them | This is a known issue; a fix is planned. For now, phrase your task so Cursor gives longer replies |
| "Usage limit reached" message | You hit your monthly Cursor Pro quota | That is the goal — it stopped on purpose. Wait for the 23rd reset |
| Exe won't open | Missing Visual C++ runtime on an older Windows | Install the latest Microsoft Visual C++ Redistributable (x64) from Microsoft's site |
| "Module not found: gemini_coder" when running from source | Running `python -m CursorCoder` from inside the `CursorCoder\` folder instead of `Desktop\AI` | Run from `Desktop\AI`: `cd C:\Users\computer\Desktop\AI && python -m CursorCoder` |

---

## 5. FAQ

**Q: Will CursorCoder affect my real Cursor editor?**
A: No. It opens a completely separate Cursor window using its own profile folder (`~\.cursorcoder\cursor-profile`). Your real Cursor, its settings, extensions, and open files are untouched.

**Q: Does it use my Cursor Pro quota?**
A: Yes, intentionally. The isolated profile is signed into your Pro account, so every model request counts against your monthly quota — the same as if you typed the prompts yourself.

**Q: Do I need to leave my computer on?**
A: Yes, for now. CursorCoder runs on your machine; it is not a cloud service. If your computer sleeps, the loop pauses. Use Windows power settings to prevent sleep during a long run.

**Q: Can I use it without a Cursor Pro account?**
A: You can use it on a free Cursor account, but the free tier has a lower request limit and may prompt for an upgrade sooner.

**Q: Where are the generated files?**
A: Agent-mode projects go in the folder Cursor is told to use as the project root (configured via `~\.cursorcoder\config.json`). Ask-mode outputs go in `~\.cursorcoder\outputs\`.

**Q: How do I change the project folder?**
A: Edit `project_root` in `~\.cursorcoder\config.json`, or use the configuration panel in the GUI.

**Q: What happens if Cursor crashes mid-run?**
A: The endless worker in `run_endless.py` detects it and re-attaches. CursorCoder resumes automatically in most cases. State is persisted between runs.

**Q: Can I run this on macOS or Linux?**
A: The launcher and CDP code are platform-agnostic, but the `Cursor.exe` path and some Windows-specific process management code are Windows-only. macOS/Linux support would need launcher changes. Not tested.

---

## 6. Changelog (user-facing)

### 2026-05-21 — v1.0.0 — Initial release

- **Added:** Drives Cursor desktop app (instead of Gemini web) — dedicated isolated profile, no disruption to your real editor.
- **Added:** Agent mode — Cursor writes real project files per idea.
- **Added:** Ask mode — Cursor answers in chat; replies saved as text.
- **Added:** Single-idea loop — iterates improvement passes on one task.
- **Added:** Idea queue loop — reads `ideas.txt` and builds one project per line.
- **Added:** Live request counter — shows how many Cursor Pro requests have been sent today.
- **Added:** Usage-limit auto-stop — stops cleanly when Cursor says quota is exhausted; shows a summary.
- **Added:** All Autocoder safety nets preserved — Stop, Stop-All, F10 panic, KILL file, stagnation detection, smart recovery, crash resume.
- **Added:** `CursorCoder.exe` built to `Desktop\My Apps\CursorCoder.exe` (212 MB).
