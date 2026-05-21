---
public-visible: false
---

# CursorCoder — Handoff
**Last updated:** 2026-05-21
**Current owner:** User (primary designer) + Claude (implementation)
**Status:** in-progress (v1.0.0 shipped; tuning tasks remain)

---

## 1. Goals

- Drive the Cursor desktop code editor's AI chat on a loop, automatically, to build real apps without the user watching.
- Support both **Ask mode** (answers saved to files) and **Agent mode** (Cursor writes real files into project folders).
- Support both **single-idea deep iteration** (cycle improvement passes on one task) and **idea-queue** (process `ideas.txt`, one project per idea).
- Consume the user's Cursor Pro monthly request quota before it resets — hitting the limit is a success condition, not a failure.
- Leave the user's real Cursor editor completely untouched while running.

## 2. Outline (architecture at 30k ft)

- **Thin Cursor target layer** (new code): `cursor_launcher.py`, `cursor_mode.py`, `agent_harvester.py`, `cursor_limit_detector.py`, `request_counter.py`, `idea_queue.py`, plus the `"Cursor"` recipe in `default_selectors.json` and `CURSOR_PROFILE` in `ai_profiles.py`.
- **Inherited Autocoder engine** (unchanged): `cdp_client.py` (CDP WebSocket), `broadcast.py` (loop brain), `run_endless.py` (crash recovery + loop routing), plus all safety nets (Stop/Stop-All, F10, KILL file, stagnation detection, smart recovery, rate-limit circuit breaker, crash-resume).
- **GUI** (`ui/app_web.py`): CustomTkinter, rebranded to CursorCoder; adds mode/loop dropdowns, ideas-file picker, live request counter, limit-reached status label.
- The package is run as `python -m CursorCoder` from `Desktop\AI` (not from inside the folder) because `broadcast.py` imports the shared `gemini_coder` sibling package.
- Config lives at `~/.cursorcoder/*.json`. The isolated Cursor profile lives at `~/.cursorcoder/cursor-profile`.
- The exe (`Desktop\My Apps\CursorCoder.exe`, 212 MB, --onefile) is the user-facing binary.

## 3. Context (why this exists)

The user already had Autocoder — a tool that drives Google's Gemini web chat in a browser via the Chrome DevTools Protocol. The problem: Gemini's free tier has prompt limits; the user wanted a tool aimed at an existing paid subscription. Cursor Pro gives the user a monthly request allowance that resets on the 23rd. The user wanted to burn it productively — build real apps — rather than leave it unused.

Cursor is built on Electron (VS Code's engine), which exposes the same CDP WebSocket interface that Autocoder already knows how to talk to. The design decision was: don't build something new from scratch; fork Autocoder and swap only the target layer. 90% of the brain stays untouched. A feasibility spike ran on 2026-05-21 before a line of production code was written — it proved Cursor accepts a `--remote-debugging-port` flag and that its chat DOM has stable, readable CSS class names. Only after that spike passed did implementation begin.

The isolated-profile approach was the user's explicit decision: run a separate Cursor profile for automation, sign it into the same Pro account (so quota burns), and never touch the user's real editor. This separates concerns cleanly and means the user can keep using their own Cursor normally while CursorCoder runs in the background.

## 4. History (dated, append-only)

### 2026-05-21 — Initial design + full implementation

- **User's vision:** Fork Autocoder into a Cursor-targeted twin. Both Ask and Agent modes. Both single-idea and idea-queue loops. Isolated Cursor profile so real editor is untouched. Live request counter to see quota burn. Graceful stop when the monthly limit is hit.
- **User's key decisions:** isolated profile (not hijacking the real editor); both modes and both loops from day one; quota exhaustion is a success condition, not an error; `python -m CursorCoder` run convention preserved from Autocoder; no new GUI framework (keep CustomTkinter).
- **Feasibility spike (user-directed, pre-build gate):** ran a live CDP attach against the installed Cursor 1.1.3. Confirmed: `--remote-debugging-port=9223` works; `--remote-allow-origins` is required (Chromium 142 returns 403 without it); chat input is `.ui-prompt-input-editor__input` (ProseMirror, requires `Input.insertText`); done signal is the loading indicator clearing; replies live in `.composer-rendered-message .markdown-root`. Full send→generate→read loop confirmed. Pro request count decremented.
- **Claude implemented:** all six new modules (`cursor_launcher`, `cursor_mode`, `agent_harvester`, `cursor_limit_detector`, `request_counter`, `idea_queue`); selector recipe and profile; `BroadcastConfig` field additions; `choose_loop` in `run_endless`; GUI rebrand + new controls; diagnostic logger bootstrap; build script; BREAKDOWN / HANDOFF / TUTORIAL / PROOF docs.
- **Verified:** 18 automated unit tests passing; smoke test drove Cursor through Autocoder's own classes, reply returned in ~9s; exe built (212 MB) to `Desktop\My Apps\CursorCoder.exe`.
- **Deferred:** live mode-control selector tuning (requires running Cursor + DOM probe); queue integration test with real Cursor; short-reply filter calibration.

## 5. Credit & Authorship

> **The user designed this product.** The user defined the goals, the isolated-profile strategy, the mode/loop choices, the quota-burn framing, the feasibility spike gate, and the acceptance criteria. Claude (session 2026-05-21) implemented the code to those specifications. The user reviewed the feasibility spike results and gave the go-ahead for full implementation. This is the user's product; AI was a tool.

This section exists to protect the user's position in sales/licensing discussions — it demonstrates that the user is the designer of record.

## 6. Plan (what's next)

- [ ] **Live mode-control selector tuning** — run `cursor_mode.set_mode()` against a live Cursor, check which DOM element holds the Ask/Agent toggle, update the JS in `cursor_mode.py` if needed.
- [ ] **Short-reply filter calibration** — the inherited Autocoder filter drops replies shorter than ~5 chars. Cursor replies are usually longer, but tune the threshold against real Cursor output to avoid false drops. (Known issue; a fix task is queued.)
- [ ] **Queue-loop live smoke test** — run a 2-idea `ideas.txt` through Agent mode, confirm two project folders are created and the request counter increments. Burns real requests; user-gates this.
- [ ] **Limit detector phrase tuning** — `cursor_limit_detector._LIMIT_PHRASES` was written before the real Cursor limit banner was ever seen. Tune against the actual banner text the first time the monthly quota is hit.
- [ ] **Model picker usage** — `default_selectors.json` includes `.ui-model-picker__trigger`; no code currently uses it. Wire it if the user wants to force a heavier model per run (burns quota faster).

## 7. Handoff checklist for the next AI

- [ ] Read Goals — what this product is FOR (burn quota productively; both modes + loops; don't disrupt real Cursor)
- [ ] Read Context — why isolated profile; why CDP over blind automation; why fork not build
- [ ] Read the last entry in History — what was just built and what was deferred
- [ ] Read BREAKDOWN.md — full architecture, file structure, data flow, key decisions
- [ ] Read TUTORIAL.md — how users use it (sign in once, pick mode + loop, start)
- [ ] **Do NOT modify `gemini_coder/`** — it is a shared sibling package used by Autocoder, opencoder, and others; CursorCoder imports it but does not own it
- [ ] Run `python -m pytest CursorCoder/tests/ -q` from `Desktop/AI` before touching any code — 18 tests should pass
- [ ] Check `~/.claude/session-data/<today>/exe_CursorCoder.log` for any unresolved startup errors
- [ ] The plan for mode-control selector tuning (item 1 above) requires a LIVE running Cursor — do not guess the selector; probe the DOM first
