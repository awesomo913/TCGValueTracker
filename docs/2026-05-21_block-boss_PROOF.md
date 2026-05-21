# Block Boss — PROOF

**Date:** 2026-05-21
**What this is:** Plain-English evidence of what was built, that it works, and what still needs to happen before a child can actually play Minecraft on it.

---

## What was built

Block Boss is a home Minecraft server manager. Here is what it does, in plain terms:

- A web page you open on any phone, tablet, or computer in your house.
- A big green **Start** button your child can press to turn the Minecraft server on.
- A display showing who is currently playing.
- A **Backup** button to save the game world.
- A parent PIN that locks the Stop, Restart, Restore, and player-list buttons so a curious kid cannot accidentally wipe the game.
- An automatic "helper" (BedrockConnect) that tricks the Nintendo Switch into finding your home server — because Nintendo doesn't normally let you type in your own server address.
- A built-in workaround (Box64) that lets the Minecraft server software — which was only ever made for desktop-computer chips — run on the Raspberry Pi's different chip.

The whole thing runs on a Raspberry Pi 5 (a credit-card-sized computer that costs around $80) and starts automatically when the Pi powers on.

---

## Proof that the code works

All automated tests were run on 2026-05-21. Every single one passed.

```
platform win32 -- Python 3.12.13, pytest-9.0.3
collected 26 items

tests/test_allowlist.py         ..   (2 tests)
tests/test_auth.py              ....  (4 tests)
tests/test_backups.py           ......  (6 tests)
tests/test_bedrockconnect.py    ..   (2 tests)
tests/test_config.py            .    (1 test)
tests/test_log_parser.py        ..   (2 tests)
tests/test_logging_setup.py     .    (1 test)
tests/test_main.py              ......  (6 tests)
tests/test_server_supervisor.py ..   (2 tests)

26 passed in 2.88s
```

**What the tests cover:**
- The parent PIN system correctly blocks wrong PINs and accepts correct ones.
- The approved-player list can add and remove players, and the file is always valid.
- Backups create correctly-named folders, prune old ones, and restore the right data.
- The server supervisor tracks the correct status (stopped / starting / running / stopping) as processes start and stop.
- The web API returns the right responses for every action (start, stop, backup, allowlist, etc.).
- The log parser correctly recognises when a player joins, leaves, or when the world save completes.
- The BedrockConnect wrapper correctly reports whether the helper process is running.
- Configuration loads correctly from environment variables.

---

## What is NOT yet proven (on-Pi verification checklist)

Everything above was tested on a Windows development computer using simulated (fake) child processes. The following steps have not been tested on a real Raspberry Pi 5, and must be completed before any child can play:

- [ ] **Install Box64 on the Pi.** The installer script downloads and builds it from source. This takes about 20 minutes and can fail if the Pi is low on disk or memory. Verify with `box64 --version`.
- [ ] **Download the real Bedrock server.** You need a fresh download URL from minecraft.net. Set `BDS_URL` and re-run `bash deploy/install.sh`.
- [ ] **Download BedrockConnect.** Same — get the latest `.jar` URL from github.com/Pugmatt/BedrockConnect/releases and set `BEDROCKCONNECT_URL`.
- [ ] **Open the right ports on the Pi's firewall.** Port 19132 (UDP) for Minecraft, port 53 (UDP) for BedrockConnect DNS, port 8000 (TCP) for the web dashboard. Example: `sudo ufw allow 19132/udp`.
- [ ] **Set the parent PIN** through the dashboard's Settings page.
- [ ] **Configure DNS on each Nintendo Switch** to point to the Pi's IP address (see README for steps). Check that Minecraft's server list shows the home server.
- [ ] **Add the child's Minecraft gamertag** to the approved-player list through the dashboard.
- [ ] **End-to-end test:** press Start, wait 30 seconds, join from the Switch, verify the player name appears on the dashboard, press Backup, verify a backup folder appears in `~/block_boss/backups/`.

---

## What remains if something breaks

- Logs are in `~/block_boss/logs/` on the Pi.
- The systemd service logs are visible with `sudo journalctl -u block-boss@<your-username> -n 50`.
- Box64 has its own log output; run `box64 bedrock_server` manually in a terminal to see raw errors.
- BedrockConnect prints to its own stdout; the Block Boss logs capture it.

---

## Plain-language summary for a non-technical reader

We built a piece of software from scratch in one session. It has 10 separate logical modules, a web interface, an installer, and a background service. 26 automated tests confirm it behaves correctly in every scenario we could simulate on a computer. The remaining steps are all about setting up real hardware (the Raspberry Pi and the Nintendo Switch) — they are installation steps, not coding work. Once those are done, a 7-year-old should be able to press one big green button and start playing Minecraft with their friends.
