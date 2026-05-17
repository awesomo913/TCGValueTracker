# Heads-Up Alert Reduced to Daily 4 PM EDT

**Date:** 2026-05-17 03:04 EDT
**Change scope:** Single cron job, no code change.

## What changed

`~/.openclaw/cron/jobs.json` — job `8123a1e2-9976-42d6-afbc-ecc5fe9a4ede` (`claude-sessions-alert`).

| Field | Before | After |
|---|---|---|
| `schedule.kind` | `every` | `cron` |
| `schedule.everyMs` | `900000` (15 min) | _(removed)_ |
| `schedule.anchorMs` | `1778361383459` | _(removed)_ |
| `schedule.expr` | _(absent)_ | `0 16 * * *` |
| `schedule.tz` | _(absent)_ | `America/New_York` |
| `description` | "Alert if 5+ sessions need response, any over 40% ctx, or self-grader stalled (dead-man-switch)." | "Daily heads-up at 4pm America/New_York: ... Reduced from every-15min to daily-4pm per user request 2026-05-17." |

## Why

User request 2026-05-17: "all of the openclaw heads-up alerts need to be removed and there needs to only be one at 4pm every day".

The 🔔 "Heads up — ..." Discord summary originates from `claude_sessions_alert_check` (defined at `~/.openclaw/workspace/.openclaw/claude-sessions/index.ts:412`). Only the `claude-sessions-alert` cron invokes it. Reducing that cron from every-15min to daily-4pm fulfills the request without touching any other notification path.

## Verified next firing

After gateway restart at 03:04:13 EDT, `~/.openclaw/cron/jobs-state.json` shows:

```
jobs.8123a1e2-...state.nextRunAtMs = 1779048000000
                                   = 2026-05-17T16:00:00-04:00 (4 PM EDT today)
```

## Other Discord-posting crons NOT touched

These are NOT "heads-up" per the codebase's own terminology and have their own (mostly silent unless something fires) cadences:

| Job | Schedule | Posts only when |
|---|---|---|
| `external-inbox-drain` | every 1 min | New Discord messages to reply to (reactive) |
| `self-grade-quick` | every 6 h | Overall grade is D or F |
| `hiatus-daily-digest` | every 1 h check | Posts at 18:00 if digest queue non-empty |
| `hiatus-usage-poll` | every 10 min | Weekly Claude usage threshold crossed |

If any of those become noisy too, edit `jobs.json` similarly. Cron schedule kinds supported: `at` (ISO timestamp), `every` (intervalMs), `cron` (5-field cron expression + tz).

## Rollback

```bash
cp ~/.openclaw/cron/jobs.json.bak-pre-headsup-4pm-2026-05-17 ~/.openclaw/cron/jobs.json
powershell -ExecutionPolicy Bypass -File ~/.openclaw/gateway-launch.ps1
```

Backup file path: `~/.openclaw/cron/jobs.json.bak-pre-headsup-4pm-2026-05-17`.
