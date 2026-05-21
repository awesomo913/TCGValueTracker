# CursorCoder — Design Spec

**Date:** 2026-05-21
**Author of record (designer):** the user. AI implements to spec.
**Status:** approved design, pre-implementation.

## Goal

Adapt Autocoder (drives Gemini-in-Chrome via Chrome DevTools Protocol) into a
twin that drives the **Cursor desktop app** instead. Runs app ideas on a loop
with the same controls and safety nets. Primary user goal: build **real apps**
(quality first), while consuming the user's Cursor Pro monthly request quota
before the 23rd as a deliberate side effect.

## Grounding facts (verified 2026-05-21)

- Cursor installed at `C:\Users\computer\AppData\Local\Programs\cursor\_\Cursor.exe`.
- Version 1.1.3, built on VS Code 1.93 — an Electron app, so its window runs on
  the same Chromium engine that Autocoder's CDP layer already targets.
- Ships an editor CLI (`resources/app/bin/cursor.cmd`) but **no headless agent
  CLI** on this version. There is no terminal path to prompt the model — the GUI
  app must be driven. Driving the app is also what consumes Pro quota (each
  Agent/Ask request counts against the monthly limit).
- Autocoder engine is fully CDP- and selector-driven: each AI target is a recipe
  of CSS selectors in `default_selectors.json`; the send→wait→extract loop and
  all safety nets live above that layer and are target-agnostic.

## Chosen approach

**Approach A** — attach to Cursor over its remote-debugging port and reuse ~90%
of the Autocoder engine — **with a hotkey-send fallback (Approach C)** for any
action CDP handles awkwardly inside Cursor.

Rejected:
- **B (blind desktop automation):** loses reliable completion detection and
  precise reply harvest; fragile to focus loss. Kept only as the fallback's
  mechanism for send/mode-toggle.
- **Headless CLI:** not available on Cursor 1.1.3.

## Architecture

Fork of Autocoder into a new sibling project `CursorCoder/`. Keep the entire
brain — `cdp_client.py`, the `broadcast` engine, `run_endless.py`, every safety
net. Swap only the **target layer**.

### New components (the only genuinely new code)

1. **`cursor_launcher.py`**
   - Closes any running Cursor instance.
   - Relaunches `Cursor.exe --remote-debugging-port=9223` opened on a target
     project folder.
   - Waits for the CDP endpoint (`http://127.0.0.1:9223/json`) to come up.
   - Attaches via the existing CDP client, locating the chat-panel target.
   - **Uses the user's real Cursor profile** (their logged-in Pro account) — not
     a throwaway profile — because the goal is to burn *their* quota. This means
     the program disrupts the user's live editor: it must close and reopen
     Cursor. Surfaced and accepted by the user.

2. **`cursor_discovery.py`**
   - One-time helper that dumps the chat-panel DOM (Cursor scrambles its CSS
     class names) so the correct handles are captured.
   - Output is hand-distilled into the "Cursor" selector recipe.

3. **Cursor recipe** (new entry in the selectors file)
   - Fields: chat input, send button, reply container, "still streaming"
     marker, plus an `agent_or_ask` mode flag.
   - Hotkey fallback wired through the existing keyboard path: `Ctrl+L` (Ask),
     `Ctrl+I` (Agent), submit — used when CDP send/mode-toggle is unreliable.

4. **`agent_harvester.py`** (Agent mode only)
   - Detects a run is done: streaming marker clears **and** Cursor's run-summary
     message appears.
   - Collects the project folder Cursor wrote into; optionally runs/builds it.

### Modes (user chose "both, switchable")

- **Ask mode:** behaves like Autocoder today — read reply, extract code blocks,
  save to Downloads.
- **Agent mode:** each idea gets its own project folder; Cursor writes real
  files; harvest the folder + read the summary to confirm completion.

### Loop shapes (user chose "both modes")

- **Single-idea deep loop:** reuse Autocoder's improvement-focus cycle (pressure
  test, add features, beautify, …) pointed at the project/chat.
- **Idea-queue loop:** reads ideas from `~/.cursorcoder/ideas.txt` (or generates
  them); each idea → its own project, built to a "good enough" bar, then next.

## Data flow

```
idea
  → engineered prompt
  → (Ask: chat | Agent: composer into project folder)
  → Cursor model request  (Pro quota −1)
  → detect done (streaming stops; Agent: + summary appears)
  → harvest (Ask: code blocks → Downloads | Agent: project folder)
  → next improvement focus  OR  next idea in queue
  → repeat until cap / Stop / usage-limit-hit
```

## Safety nets

Inherited from Autocoder, unchanged:
- Stop / Stop-All (threading event checked every loop), F10 panic key, `KILL`
  file watcher.
- Stagnation detection (MD5 + difflib similarity).
- Smart recovery (non-code reply → reset conversation, self-diagnose).
- Rate-limit circuit breaker (short/junk-reply streak → cooldown + rotate).
- Iteration / per-chat / time caps.
- Crash-resume via persisted state; endless worker auto-restart.

Cursor-specific additions:
- **Usage-limit detector:** watch for Cursor's own "you've reached your usage
  limit" banner. On appearance, **stop gracefully** and report
  `limit reached on <date>` — hitting the limit is the goal, not a failure to
  fight against.
- **Live request counter:** running count of model requests sent this session so
  the burn is visible.

## Config

`~/.cursorcoder/*.json`, mirroring `~/.autocoder/`. Keys include:
`mode` (ask|agent), `loop_shape` (single|queue), `debug_port` (default 9223),
`cursor_exe_path`, `project_root`, `ideas_file`, plus all inherited caps/intervals.

## Error handling

- Debug door won't open → fall back to hotkey-send mode; log the decision.
- Reply is chatter not code → inherited smart recovery.
- Cursor crash / window closed → endless worker restarts and re-attaches.
- Usage limit exhausted → graceful stop + report (not an error path).
- Focus stolen (hotkey-fallback mode) → re-focus Cursor window via
  `window_manager` before each send.

## Testing

- **Feasibility spike first (manual, gating):** relaunch Cursor with the debug
  port; confirm (a) the door opens and (b) the chat handles are findable. Build
  nothing else until this passes; if it fails, commit to hotkey-send fallback.
- Headless unit tests (reuse `_test_headless.py` pattern): prompt engineering,
  queue parsing, harvest logic.
- Live smoke: 3 ideas through Agent mode → confirm 3 project folders created and
  the request counter decrements.

## Build & docs

- Diagnostic logger bootstrapped at startup (per workspace exe rules).
- Build to `Desktop/My Apps/CursorCoder.exe`.
- Ship BREAKDOWN / HANDOFF / TUTORIAL / PROOF quartet.

## Out of scope (YAGNI)

- Multi-window / fleet support (that's FleetAutocoder's job).
- Non-Cursor targets (this twin is Cursor-only).
- Headless CLI integration (unavailable on this Cursor version; revisit if the
  user upgrades to a build that ships `cursor-agent`).
