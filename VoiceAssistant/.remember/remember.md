# Handoff

## State
Branch `feat/block-boss`. APK installed on A36 (RFCY514BLJH, PID 5938 = new build).
Three audio bugs fixed and pushed (commits 730cf1e9 + 7694c46e):
1. flush() was discarding buffered audio tail — removed
2. AudioTrack started on empty buffer → underrun — fixed with 500ms pre-buffer
3. Multiple speak() calls caused ~680ms inter-sentence gaps — fixed by sending all text as one speak()
Single-speak fix installed but NOT yet verified live (new PID, no responses captured yet).

## Next
1. STOP/START service in app UI, ask a forklift question, check logcat for NO `restartIfDisabled`
2. Run `python C:/Users/computer/.claude/tmp/2026-06-01/voiceassist_listen_test.py` to capture audio
3. If still glitching: increase PRE_BUFFER_BYTES from 16000→32000 in AudioPlayer.kt
4. After audio confirmed clean: run CALIBRATE_WINDOW for voice calibration

## Context
Service can't restart via adb (not exported) — must STOP/START from app UI.
Git add: always target specific files (`git add VoiceAssistant/path`), NOT `git add -A` (embedded repos in Desktop/AI break it).
Listen test script: Focusrite=device 1, Realtek speakers=device 3. Phone must be near Focusrite mic.
