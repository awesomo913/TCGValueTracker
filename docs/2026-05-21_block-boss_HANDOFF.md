# Block Boss — HANDOFF

**Date:** 2026-05-21
**Branch:** feat/block-boss
**Project location:** `~/AI/block_boss` (on the development machine)
**Handoff type:** AI-to-AI / developer handoff

---

## Goals

Build a kid-safe, parent-controlled web dashboard for a home Minecraft Bedrock server hosted on a Raspberry Pi 5. The child gets big friendly buttons to start the game and watch who is playing. The parent PIN gates anything that could break the game: stop, restart, edit the approved-player list, restore a backup.

Secondary goals:
- Work around the Bedrock server's x86-only limitation on ARM hardware (Pi 5) via Box64.
- Work around the Nintendo Switch's inability to join custom servers via BedrockConnect.
- Keep the frontend safe from XSS (cross-site scripting attacks) by building all DOM (web page structure) elements programmatically rather than concatenating raw HTML strings.

---

## History

This project was built in a single development sprint using subagent-driven development (multiple Claude AI agents, each responsible for one or two modules, working from a shared plan). The plan document is at `docs/superpowers/plans/2026-05-21-block-boss-plan.md`.

**Thirteen tasks were executed in sequence:**
1. Project scaffold (pyproject, requirements, pytest config, directory layout)
2. `config.py` — immutable settings dataclass
3. `logging_setup.py` — single-call log configuration
4. `log_parser.py` — parses Bedrock server stdout for join/leave/save events
5. `auth.py` — PBKDF2 PIN hashing and verification (Python's built-in hashlib, no external crypto library)
6. `allowlist.py` — reads/writes `allowlist.json`, sends live reload command
7. `backups.py` — timestamped world backups with pruning and restore
8. `server_supervisor.py` — child process management, status tracking, player list
9. `bedrockconnect.py` — BedrockConnect child process manager
10. `main.py` — FastAPI app with all REST endpoints and WebSocket
11. `run.py` — entry point wiring config + supervisors + app
12. Frontend (`web/index.html`, `style.css`, `app.js`) — XSS-safe DOM-only UI
13. Deploy (`deploy/install.sh`, `deploy/block-boss.service`) + this documentation quartet

---

## What is done

- All 10 Python modules written, tested, and passing (26 tests, 0 failures).
- Full REST API: start, stop, restart, status, players, backup, list backups, restore, allowlist CRUD (Create Read Update Delete), set PIN, switch status.
- Live WebSocket feed pushing status + player list every 2 seconds.
- Frontend served by FastAPI as static files; no separate web server needed.
- XSS-safe frontend: every dynamic element created with `createElement`/`textContent`.
- One-shot installer (`deploy/install.sh`) handles Box64 build, BDS download, BedrockConnect download, Python env setup.
- Systemd service file (`deploy/block-boss.service`) for auto-start on boot.
- README, BREAKDOWN, TUTORIAL, HANDOFF, and PROOF docs complete.

## What is NOT done (manual steps required on real hardware)

None of the following have been tested on actual hardware. They require a physical Pi 5 with Raspberry Pi OS 64-bit:

| Step | Notes |
|---|---|
| Box64 build and install | The installer handles it, but Box64 build time is ~20 minutes on Pi hardware; build errors are possible. Verify at `box64 --version` after install. |
| Bedrock Dedicated Server download and first run | Requires the correct `.zip` URL for the current Minecraft version. URL changes every release. |
| BedrockConnect download and Java permissions | Requires the correct `.jar` URL. The `setcap` command that lets Java bind port 53 without root may need adjustment for different Java paths. |
| Switch DNS configuration | Must be set per Switch. Nintendo updates can reset it. |
| Network/firewall configuration | Port 19132 (UDP) must be open on the Pi's firewall (ufw) for Bedrock clients. Port 53 (UDP) for BedrockConnect. Port 8000 (TCP) for the dashboard. |
| End-to-end play test | Starting the server, joining from a Switch, making a backup, and restoring it — all untested on real hardware. |

---

## Architecture in one sentence

One FastAPI process serves the dashboard and a JSON API, and supervises two child processes (Box64 + Bedrock server, and BedrockConnect). The browser connects via HTTP for commands and WebSocket for live status.

---

## File map for the next engineer

```
block_boss/
  app/
    config.py            — settings (read this first)
    logging_setup.py
    log_parser.py
    auth.py
    allowlist.py
    backups.py
    server_supervisor.py  — the heart of server management
    bedrockconnect.py
    main.py               — all API routes
    run.py                — entry point
  web/
    index.html
    style.css
    app.js                — all frontend logic
  deploy/
    install.sh
    block-boss.service
  tests/                  — 26 tests, all green
```

---

## Known rough edges

- **Box64 instability:** Box64 translates x86 to ARM on the fly. Some Minecraft versions or server behaviors may expose translation bugs. If the server crashes with a signal or exits unexpectedly, check for Box64 version mismatches.
- **BedrockConnect DNS binding:** Binding port 53 typically requires root or a Linux capability grant (`setcap`). The installer sets this for the Java binary it finds. If Java is updated or reinstalled, the capability grant may need to be re-run.
- **Allowlist live reload:** The `reloadallowlist` console command is sent to the running server's stdin. If the server is busy or slow to respond, the reload may be delayed. The allowlist file itself is always written first, so a server restart will always pick up changes.
- **No HTTPS:** The dashboard is plain HTTP. For a home network this is acceptable, but if you ever expose the Pi to the internet (not recommended), add an HTTPS reverse proxy (e.g. nginx with a certificate from Let's Encrypt).

---

## Credit and authorship

Built with Claude Code (Anthropic) using the subagent-driven development workflow. Thirteen parallel and sequential agent tasks executed from a single plan document. No human wrote the Python source code directly — all modules were generated, reviewed by automated test runners, and iterated to pass 26 tests.

External libraries used: FastAPI and uvicorn (and their own dependencies, such as Starlette and anyio). All open-source, MIT or Apache licensed. PIN hashing uses Python's built-in `hashlib` (PBKDF2) — no extra crypto library is installed.

BedrockConnect is Pugmatt's open-source project: `https://github.com/Pugmatt/BedrockConnect`.
Box64 is ptitSeb's open-source project: `https://github.com/ptitSeb/box64`.
