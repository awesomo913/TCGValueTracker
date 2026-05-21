# CursorCoder — Breakdown
**Created:** 2026-05-21
**Location:** `C:\Users\computer\Desktop\AI\CursorCoder\`
**Language/Stack:** Python 3.11 + CustomTkinter (GUI) + websocket-client (CDP) + PyInstaller (exe)

---

## 1. What It Does

CursorCoder is a desktop tool that drives the **Cursor** code editor's AI chat automatically, on a loop, to build apps. It is a fork of Autocoder (which drove Google Gemini in a browser) retargeted at the Cursor desktop application. You give it a task (or a list of tasks), click Start, and it sends prompts to Cursor's AI, waits for replies, harvests the output, and repeats — cycling through improvement passes or a queue of app ideas. A deliberate secondary goal is to consume the user's Cursor Pro monthly request quota before it resets.

## 2. How To Run It

**Install:**
```bash
cd C:\Users\computer\Desktop\AI
uv pip install -r CursorCoder\requirements.txt
```

**Run (from source):**
```bash
cd C:\Users\computer\Desktop\AI
python -m CursorCoder
```
*Must run from the `Desktop\AI` parent directory, not from inside `CursorCoder\`, because `broadcast.py` imports the shared `gemini_coder` sibling package.*

**Run (exe):**
Double-click `Desktop\My Apps\CursorCoder.exe` or the Desktop shortcut.

**Requirements:**
- Python 3.11+, Windows (x64)
- Cursor 1.1.3+ installed at the default path (`AppData\Local\Programs\cursor\_\Cursor.exe`)
- A Cursor Pro account

**First-time setup:**
On first launch, CursorCoder opens a dedicated isolated Cursor window using a separate profile at `~\.cursorcoder\cursor-profile`. Sign that isolated profile into your Cursor account once. After that, the launcher reuses the same profile automatically.

**Basic usage:**
1. Click **Launch Cursor** — the isolated Cursor window opens.
2. Choose mode: **Agent** (Cursor writes real files) or **Ask** (replies stay in chat).
3. Choose loop: **Single idea** (iterate one task with improvement cycles) or **Idea queue** (process a `ideas.txt` file, one idea per project).
4. Type a task, or pick your `ideas.txt` file for queue mode.
5. Click **Start Autocoding**.

## 3. Architecture & File Structure

```
CursorCoder/
├── __init__.py                  # Package root, version, app name
├── __main__.py                  # Entry point — bootstraps logger, launches GUI
├── _cursorcoder_entry.py        # PyInstaller entry wrapper
├── build_cursorcoder.py         # PyInstaller exe build script
├── diagnostics_logger.py        # Shared crash + telemetry logger
├── default_selectors.json       # CSS selector recipes per AI target (includes "Cursor" block)
│
├── cursor_launcher.py           # Launch/attach an isolated Cursor with CDP; kills only our instance
├── cursor_mode.py               # Toggle Cursor chat pane between Ask and Agent before a run
├── cursor_limit_detector.py     # Detect Cursor's usage-limit banner; stop gracefully
├── request_counter.py           # Persist a per-day count of Cursor model requests burned
├── agent_harvester.py           # Agent-mode: detect run done, collect the project folder
├── idea_queue.py                # Load ideas.txt, one idea → one project dir (queue loop)
│
├── cdp_client.py                # CDP WebSocket client: connect, send, wait, read, selectors
├── broadcast.py                 # BroadcastConfig + BroadcastController: the loop brain
├── run_endless.py               # Endless worker: restart loop on crash, route single vs queue
├── ai_profiles.py               # AIProfile presets (Gemini, Claude, Cursor, …)
├── model_config.py              # Model rotation config
│
├── ui/
│   └── app_web.py               # CustomTkinter GUI — all tabs, controls, live status
│
└── tests/
    ├── test_cursor_recipe.py    # Verifies Cursor selector recipe loads correctly
    ├── test_cursor_profile.py   # Verifies Cursor AIProfile registration
    ├── test_cursor_launcher.py  # Verifies launcher arg-building + default paths
    ├── test_broadcast_config_cursor.py  # Verifies mode/loop_shape/ideas_file fields
    ├── test_cursor_mode.py      # Verifies mode→JS mapping + invalid-mode guard
    ├── test_agent_harvester.py  # Verifies file collection (skips .git/node_modules)
    ├── test_idea_queue.py       # Verifies ideas.txt parsing + slug + project-dir
    ├── test_request_counter.py  # Verifies per-day increment + persistence
    └── test_limit_detector.py  # Verifies limit-phrase detection
```

**Data flow:**

```
idea (typed or from ideas.txt)
  → cursor_launcher.launch()         # open isolated Cursor on port 9223
  → cursor_mode.set_mode()           # flip to Ask or Agent
  → broadcast loop (broadcast.py)
      → cdp_client.send_and_read()   # type prompt via Input.insertText, click send,
                                     # wait for .ui-ascii-loading-indicator to clear,
                                     # read last .composer-rendered-message .markdown-root
      → request_counter.bump()       # log the request
      → cursor_limit_detector.check() # look for usage-limit banner
      → agent_harvester.collect()    # (Agent mode) harvest the project folder
      → next improvement focus  OR  next idea in queue
  → stop on: cap hit / Stop / limit reached / KILL file
```

The `broadcast.py` engine and `cdp_client.py` are inherited from Autocoder, unchanged. CursorCoder adds only the thin target layer (everything in the `cursor_*` + `*_queue` + `*_harvester` + `*_counter` files) over that existing brain.

## 4. Key Decisions & Why

- **Isolated Cursor profile (`~\.cursorcoder\cursor-profile`)** — Running a separate profile means the user's real Cursor editor is never disrupted. Quota is per-account, not per-profile, so signing the isolated profile into the same Pro account still burns from the same monthly pool. One-time manual sign-in; zero disruption thereafter.

- **`--remote-allow-origins=http://127.0.0.1:9223` is mandatory** — Chromium 142 (Cursor 1.1.3's engine) returns 403 Forbidden on the CDP WebSocket if this flag is absent. The spike caught this; the launcher always passes it.

- **CDP over blind desktop automation (Approach A over B)** — CDP gives precise completion detection (streaming-indicator checks) and reliable reply harvest. Blind desktop automation (Approach B) was rejected because focus loss breaks it and partial replies get dropped silently.

- **`--onefile` PyInstaller build** — CursorCoder has no ML models; `--onefile` is the right default and produces the ~212 MB exe without the 30-second cold-start penalty that large bundles cause.

- **`run as python -m CursorCoder` from the parent directory** — `broadcast.py` and `ai_profiles.py` import the shared `gemini_coder` sibling package. Running from inside `CursorCoder/` breaks that import. This is documented and baked into the build entry point.

- **Short-reply filter inherited from Autocoder** — Replies under ~5 characters are treated as non-answers and trigger recovery. This filter was calibrated for Gemini; Cursor replies are generally longer, but the filter can occasionally drop a valid very-short reply. A tuning task is queued (see HANDOFF Plan).

## 5. Development Log

### 2026-05-21 — Initial build (fork of Autocoder)

- Forked `Autocoder/` tree into `CursorCoder/`; re-initialized git.
- Renamed config dir from `~/.autocoder` to `~/.cursorcoder` across all Python files.
- Added the `"Cursor"` selector recipe to `default_selectors.json` (input, submit, streaming marker, stop button, reply container) — all verified live in the feasibility spike.
- Added `CURSOR_PROFILE` to `ai_profiles.py`; registered in `PRESET_PROFILES`.
- Created `cursor_launcher.py` — builds the arg list with required `--remote-allow-origins`, finds the installed `Cursor.exe`, waits for the CDP port, kills only our isolated instance on shutdown.
- Created `cursor_mode.py` — JS-based Ask/Agent mode toggle.
- Created `agent_harvester.py` — done-detection + project-folder file collection, skipping `.git`/`node_modules`/`__pycache__`.
- Created `cursor_limit_detector.py` — phrase-based detection of Cursor's usage-limit banner; graceful stop + report.
- Created `request_counter.py` — per-day JSON counter; persists across restarts.
- Created `idea_queue.py` — `ideas.txt` parser + slug generator + per-idea project-dir builder.
- Added `mode`, `loop_shape`, `ideas_file` fields to `BroadcastConfig` in `broadcast.py`.
- Added `choose_loop()` to `run_endless.py` for single vs queue routing.
- Rebranded the GUI (`__init__.py`, `ui/app_web.py`): window title "CursorCoder v1.0.0", "Launch Cursor" button, mode/loop dropdowns, ideas-file picker, live request counter, limit-reached status label.
- Bootstrapped diagnostic logger in `__main__.py`.
- Built `CursorCoder.exe` (212 MB) to `Desktop\My Apps\CursorCoder.exe`.
- End-to-end feasibility spike PASSED: live Cursor driven over CDP, real model request sent (quota −1), real reply read back in ~9s.
- All 18 automated unit tests passing.
- Deferred: live-tuning of the mode-control selector against real DOM (requires a running Cursor); queue-loop integration test with real Cursor (burns real requests); short-reply filter calibration for Cursor reply lengths.

### 2026-05-21 — Added App Mode (Viral Play-Store App preset)

**New preset — `viral_app` in `coding_profiles.py`**

Added a `viral_app` coding profile preset. Settings: target = Android App, perfection loop = on, expand = off, acceptance = strict. When selected from the GUI preset dropdown the engine tightens its quality bar and holds each iteration to a higher standard before moving on.

**Four new improvement focuses in `broadcast.py`**

Added `ad_monetization`, `play_store_readiness`, `design_polish`, and `virality_retention` to the pool of improvement focus strings that the broadcast loop cycles through. Together they encode the Play-ready / monetized / polished / viral rubric: ads that earn from the first install, manifest + icon + store metadata that pass Google's review, the same look-and-feel polish benchmarked against the user's shipped Typing Speed Test, and retention mechanics that bring users back.

**Quality-bar checklist — `docs/app_mode/playstore_quality_bar.md`**

Added a bundled Markdown checklist that enumerates the Play Store quality bar: working splash screen, required permissions declared, AdMob or equivalent integration, versioned `build.gradle`, README with store description. When the `viral_app` preset is active the checklist is auto-attached to the prompt context at startup (same mechanism as the OpenClaw reference-pack attach — keys off the saved preset on launch).

**Honest ship gate — `app_mode_acceptance.py`**

Added a ship-gate module that runs before the broadcast loop marks an iteration "accepted". Gate logic:

1. If the Android SDK (`ANDROID_HOME` or `ANDROID_SDK_ROOT`) and either `gradlew` or a system `gradle` are found in the project directory, the gate runs `gradle :app:assembleDebug` (or `./gradlew :app:assembleDebug`) and passes only on exit code 0.
2. If the SDK or Gradle are absent the gate falls back to a structure + manifest check (verifies `AndroidManifest.xml`, `build.gradle`, and `src/` exist) and logs a clear `DECISION compile_gate=skipped reason=no_android_sdk` line — it never silently passes a "compiled" status when compilation was not actually verified.
3. Both paths log a `DECISION app_mode_gate=<passed|failed|skipped>` entry to the diagnostic log.

**New `BroadcastConfig` field — `app_mode_project_dir`**

Added `app_mode_project_dir: str = ""` to `BroadcastConfig`. The run-launch code must set this to the Cursor project folder path when the `viral_app` preset is active; the gradle / structure gate reads it to know where to look. An empty string disables the gate (safe default for non-app presets).

**Known follow-up items**

- `app_mode_project_dir` must be fed from the GUI at run-launch (the field exists in config; wiring to the UI picker is a deferred task).
- The quality-bar checklist attach keys off the saved preset at startup — same as OpenClaw's reference-pack pattern; verified works in OpenClaw, pending smoke test for CursorCoder.
- `app_mode_acceptance.py` gradle path tested with a stub; live test against a real Android project (with SDK) is deferred until a suitable test project is available.
