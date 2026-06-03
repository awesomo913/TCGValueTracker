#!/usr/bin/env python3
"""
Audio event log analyzer for VoiceAssistant.

Usage:
    # Pull from device and analyze in one shot:
    python scripts/analyze_audio.py --pull
    python scripts/analyze_audio.py --pull RFCY514BLJH

    # Analyze a local file:
    python scripts/analyze_audio.py audio_events_20260601_143500.csv
    python scripts/analyze_audio.py audio_events.csv
"""
import sys
import csv
import subprocess
from pathlib import Path
from datetime import datetime

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
DEFAULT_DEVICE = "RFCY514BLJH"
REMOTE_CSV = "/sdcard/Download/audio_events.csv"

SILENCE_WARN_MS = 100   # flag silence gaps longer than this as notable
CHUNK_MS = 40           # approximate ms per Deepgram PCM chunk


def pull_csv(device: str) -> Path:
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = Path(f"audio_events_{ts}.csv")
    cmd = [ADB, "-s", device, "exec-out", f"cat {REMOTE_CSV}"]
    try:
        data = subprocess.check_output(cmd, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError as e:
        print(f"adb pull failed: {e}")
        sys.exit(1)
    out.write_bytes(data)
    print(f"Pulled {len(data)} bytes → {out}")
    return out


def analyze(path: Path):
    rows = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append({
                "ts":       int(row.get("ts_ms", 0)),
                "event":    row.get("event", ""),
                "chunk":    int(row.get("chunk_bytes", 0)),
                "rms":      int(row.get("rms_sq", 0)),
                "consec":   int(row.get("consec_silent", 0)),
                "speech":   int(row.get("last_speech", 0)),
                "written":  int(row.get("bytes_written", 0)),
                "head":     int(row.get("head_pos", 0)),
            })

    if not rows:
        print("No events found.")
        return

    total_ms = rows[-1]["ts"] - rows[0]["ts"] if len(rows) > 1 else 0
    print(f"\n{'=' * 50}")
    print(f"AUDIO SESSION — {path.name}")
    print(f"{'=' * 50}")
    print(f"Events: {len(rows)}  |  Duration: {total_ms}ms ({total_ms / 1000:.1f}s)")
    print()

    # ── Timeline ────────────────────────────────────────────────────────────────
    print("TIMELINE:")
    silence_start_ts = None
    silence_runs = []
    prev_event = None

    for i, r in enumerate(rows):
        ev = r["event"]

        if ev == "PRE_BUF" and i == 0:
            print(f"  {r['ts']:6d}ms  [PRE-BUFFER] accumulating...")

        elif ev == "PLAY_START":
            print(f"  {r['ts']:6d}ms  ▶ PLAY_START  buffered={r['chunk']}B lastSpeech={r['speech']}")

        elif ev == "SIL_FADE":
            silence_start_ts = r["ts"]
            print(f"  {r['ts']:6d}ms  ▼ SILENCE start  fade-out from amplitude={r['speech']}  rms²={r['rms']}")

        elif ev == "SIL_ZERO":
            pass  # don't print every zero chunk — only the first (SIL_FADE) and resume

        elif ev == "SPEECH" and prev_event in ("SIL_FADE", "SIL_ZERO"):
            if silence_start_ts is not None:
                gap_ms = r["ts"] - silence_start_ts
                silence_runs.append((silence_start_ts, r["ts"], gap_ms))
                flag = "  ⚠️ LONG" if gap_ms > SILENCE_WARN_MS else ""
                print(f"  {r['ts']:6d}ms  ▲ SPEECH resume  silence was {gap_ms}ms{flag}")
                silence_start_ts = None

        elif ev == "WRITE_ERR":
            print(f"  {r['ts']:6d}ms  ❌ WRITE_ERR  chunk={r['chunk']}B rms²={r['rms']}")

        elif ev == "STOP":
            frames_written = r["written"] // 2
            frames_played = r["head"]
            drift_ms = max(0, frames_written - frames_played) * 1000 // 16000
            print(f"  {r['ts']:6d}ms  ⏹ STOP  written={r['written']}B head={r['head']} unplayed≈{drift_ms}ms")

        prev_event = ev

    # ── Summary ─────────────────────────────────────────────────────────────────
    prebuf   = [r for r in rows if r["event"] == "PRE_BUF"]
    speeches = [r for r in rows if r["event"] == "SPEECH"]
    sil_fade = [r for r in rows if r["event"] == "SIL_FADE"]
    sil_zero = [r for r in rows if r["event"] == "SIL_ZERO"]
    errors   = [r for r in rows if r["event"] == "WRITE_ERR"]

    total_sil_chunks = len(sil_fade) + len(sil_zero)
    total_sil_ms = total_sil_chunks * CHUNK_MS

    print()
    print("SUMMARY:")
    print(f"  Pre-buffer chunks:  {len(prebuf)}")
    print(f"  Speech chunks:      {len(speeches)}")
    print(f"  Silence chunks:     {total_sil_chunks} total ({len(sil_fade)} fade-out + {len(sil_zero)} zeros ≈ {total_sil_ms}ms replaced)")
    print(f"  Silence runs:       {len(silence_runs)}")
    for start, end, gap_ms in silence_runs:
        flag = "  ⚠️" if gap_ms > SILENCE_WARN_MS else ""
        print(f"    {start}ms–{end}ms ({gap_ms}ms){flag}")
    print(f"  Write errors:       {len(errors)}")

    # ── RMS stats ────────────────────────────────────────────────────────────────
    if speeches:
        rms_vals = [r["rms"] for r in speeches]
        avg_rms = int(sum(rms_vals) / len(rms_vals))
        max_rms = max(rms_vals)
        print(f"  Speech RMS² range:  avg={avg_rms}  max={max_rms}")

    # ── Issue flags ──────────────────────────────────────────────────────────────
    issues = []
    for start, end, gap_ms in silence_runs:
        if gap_ms > SILENCE_WARN_MS:
            issues.append(f"Long silence at {start}ms ({gap_ms}ms) — may be audible as a gap")
    if errors:
        issues.append(f"{len(errors)} AudioTrack write error(s) — buffer may have overflowed")
    stop_row = next((r for r in reversed(rows) if r["event"] == "STOP"), None)
    if stop_row:
        drift_ms = max(0, stop_row["written"] // 2 - stop_row["head"]) * 1000 // 16000
        if drift_ms > 300:
            issues.append(f"Large unplayed buffer at stop: {drift_ms}ms — tail of response may have been cut")
    if not any(r["event"] == "PLAY_START" for r in rows):
        issues.append("PLAY_START never logged — pre-buffer never filled, playback may not have started")

    print()
    if issues:
        print("ISSUES:")
        for iss in issues:
            print(f"  ⚠️  {iss}")
    else:
        print("  ✓ No issues detected.")
    print()


def main():
    args = sys.argv[1:]
    if "--pull" in args:
        idx = args.index("--pull")
        device = args[idx + 1] if idx + 1 < len(args) and not args[idx + 1].startswith("-") else DEFAULT_DEVICE
        path = pull_csv(device)
    elif args and not args[0].startswith("-"):
        path = Path(args[0])
        if not path.exists():
            print(f"File not found: {path}")
            sys.exit(1)
    else:
        print(__doc__)
        sys.exit(0)

    analyze(path)


if __name__ == "__main__":
    main()
