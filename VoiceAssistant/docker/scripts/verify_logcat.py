#!/usr/bin/env python3
"""
Parses logcat output from VoiceAssistant and asserts expected/forbidden patterns.

Exit 0 = PASSED, exit 1 = FAILED.

Smoke level  — no crash + service started (no audio needed, no API keys needed)
Integration  — + STT connected + speaker filter active (requires Deepgram key + audio)
"""
import re
import sys
from pathlib import Path


CRASH_PATTERNS = [
    (r"FATAL EXCEPTION",                       "AndroidRuntime FATAL EXCEPTION"),
    (r"Process: com\.owner\.assist.*has died", "App process died"),
]

SMOKE_REQUIRED = [
    (r"AssistantService",
     "AssistantService never logged (service may not have started)"),
]

# Either the STT opened (keys present) or the service reported missing keys.
# If neither shows up, the startup path is broken.
SMOKE_STARTUP_ALTERNATIVES = [
    r"STT WS open",
    r"keystore unavailable",
    r"keys missing",
]

INTEGRATION_REQUIRED = [
    (r"AssistantService",       "AssistantService start"),
    (r"STT WS open",            "Deepgram STT WebSocket connected"),
    (r"Audio route:",           "Audio route logged by BluetoothScoManager"),
    (r"OTHER\[spk=|SELF\[spk=", "Speaker filter active (OTHER or SELF log line)"),
]

INTERESTING_PATTERNS = [
    r"TRIAGE RESPOND",
    r"TRIAGE SKIP",
    r"OTHER\[spk=",
    r"SELF\[spk=",
    r"STT WS open",
    r"Audio route:",
    r"Latency",
    r"keystore unavailable",
    r"keys missing",
    r"RECORD_AUDIO not granted",
    r"SCO route set",
    r"Self calibrated",
]


def check(log: str, level: str) -> list[str]:
    failures = []

    # Hard failures — always forbidden
    for pattern, label in CRASH_PATTERNS:
        if re.search(pattern, log):
            failures.append(f"CRASH: {label}")

    if level == "smoke":
        for pattern, label in SMOKE_REQUIRED:
            if not re.search(pattern, log):
                failures.append(f"MISSING: {label}")

        if not any(re.search(p, log) for p in SMOKE_STARTUP_ALTERNATIVES):
            failures.append(
                "MISSING: startup evidence — expected one of: "
                + " | ".join(SMOKE_STARTUP_ALTERNATIVES)
            )

    elif level == "integration":
        for pattern, label in INTEGRATION_REQUIRED:
            if not re.search(pattern, log):
                failures.append(f"MISSING: {label}  (pattern: {pattern!r})")

    return failures


def main():
    if len(sys.argv) < 2:
        print("Usage: verify_logcat.py <logcat_file> [smoke|integration]")
        sys.exit(1)

    path = Path(sys.argv[1])
    level = sys.argv[2] if len(sys.argv) > 2 else "smoke"

    if not path.exists():
        print(f"ERROR: logcat file not found: {path}")
        sys.exit(1)

    log = path.read_text(errors="replace")
    line_count = log.count("\n")

    print(f"=== Log Verification ({level}) ===")
    print(f"Lines: {line_count}")
    print()

    failures = check(log, level)

    if failures:
        print("FAILED:")
        for f in failures:
            print(f"  ✗ {f}")
        print()
        print("--- Last 30 log lines ---")
        for line in log.splitlines()[-30:]:
            print(f"  {line}")
        sys.exit(1)

    print("PASSED ✓")

    # Print interesting lines to help the user understand what happened
    interesting = [
        line for line in log.splitlines()
        if any(re.search(p, line) for p in INTERESTING_PATTERNS)
    ]
    if interesting:
        print(f"\n--- Key events ({len(interesting)} lines) ---")
        for line in interesting[:40]:
            print(f"  {line}")

    sys.exit(0)


if __name__ == "__main__":
    main()
