"""Grade app screenshots for visual quality using the local llava vision model.

Usage:  python tools/llava_grade.py [--runs N] <image.png> [<image2.png> ...]

llava:7b scores are noisy (±~2 across identical runs), so pass --runs N to grade
each image N times and average — that cuts the variance and gives a stable number
worth optimizing toward. Default --runs 1. llava:13b (--model llava:13b) is much
steadier.

Shared Ollama: the model server is often shared with many other jobs. This grader
is built for that — set OLLAMA_HOST to point at any (incl. remote/shared) server,
it retries with backoff when the server is busy, and uses a short keep_alive so it
releases the GPU instead of hogging it. Tunables (env):
  OLLAMA_HOST (default http://localhost:11434)
  LLAVA_MODEL, LLAVA_TIMEOUT, LLAVA_RETRIES, LLAVA_BACKOFF, LLAVA_KEEP_ALIVE
"""
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Shared-Ollama friendly: the model server may be busy serving many others.
# Point at any host with OLLAMA_HOST; tolerate contention with retries/backoff;
# release the GPU after each call with a short keep_alive so we don't hog it.
_HOST = os.environ.get("OLLAMA_HOST", "http://localhost:11434").rstrip("/")
OLLAMA = _HOST + "/api/generate"
MODEL = os.environ.get("LLAVA_MODEL", "llava:7b")  # override with --model (e.g. llava:13b)
TIMEOUT = int(os.environ.get("LLAVA_TIMEOUT", "300"))
MAX_RETRIES = int(os.environ.get("LLAVA_RETRIES", "5"))
BACKOFF = float(os.environ.get("LLAVA_BACKOFF", "3"))
KEEP_ALIVE = os.environ.get("LLAVA_KEEP_ALIVE", "30s")  # unload soon = good citizen

PROMPT = (
    "You are a strict UX and visual-design grader. The image is ONE screen of a "
    "desktop app that helps a Pokemon ROM-hack developer visually understand their "
    "game: maps, wild Pokemon, NPCs, trainers, scripts, story progression, and plans. "
    "Grade this screen from 0 to 10 on overall VISUAL quality, weighing: clarity, "
    "layout and visual hierarchy, color and contrast, information density, and how "
    "well it visually 'puts the whole thing together'. "
    "Reply in EXACTLY this format and nothing else:\n"
    "SCORE: <number 0-10>\n"
    "STRENGTHS: <one short line>\n"
    "IMPROVE: <one short line>"
)


def grade(img_path: str, model: str = MODEL) -> str:
    b64 = base64.b64encode(Path(img_path).read_bytes()).decode()
    body = json.dumps(
        {
            "model": model,
            "prompt": PROMPT,
            "images": [b64],
            "stream": False,
            "keep_alive": KEEP_ALIVE,
        }
    ).encode()
    last_err = None
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(OLLAMA, body, {"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                data = json.loads(r.read())
            if isinstance(data, dict) and data.get("error"):
                raise RuntimeError(data["error"])  # e.g. model loading / server busy
            return (data.get("response") or "").strip()
        except (urllib.error.URLError, TimeoutError, ConnectionError, RuntimeError, ValueError) as e:
            last_err = e
            if attempt < MAX_RETRIES - 1:
                wait = BACKOFF * (2 ** attempt)
                print(f"  [shared llava busy/err: {e} — retry {attempt + 1}/{MAX_RETRIES - 1} in {wait:.0f}s]")
                time.sleep(wait)
    raise RuntimeError(
        f"llava unavailable after {MAX_RETRIES} tries at {OLLAMA} "
        f"(shared server busy?): {last_err}"
    )


def _score_of(text: str):
    m = re.search(r"SCORE:\s*([0-9]+(?:\.[0-9]+)?)", text)
    return float(m.group(1)) if m else None


def main():
    args = sys.argv[1:]
    runs = 1
    model = MODEL
    while args and args[0] in ("--runs", "--model"):
        if args[0] == "--runs":
            runs = int(args[1])
        else:
            model = args[1]
        args = args[2:]
    if not args:
        print("usage: python tools/llava_grade.py [--runs N] [--model llava:13b] <image.png> [...]")
        return
    print(f"[grader: model={model}, runs={runs}]")

    totals = []
    for p in args:
        print("=" * 64)
        print(Path(p).name)
        scores = []
        last = ""
        for _ in range(runs):
            try:
                last = grade(p, model)
            except Exception as e:  # noqa: BLE001 - keep going on failure
                print(f"GRADE FAILED: {e}")
                continue
            s = _score_of(last)
            if s is not None:
                scores.append(s)
        if runs == 1:
            print(last)
        else:
            avg = round(sum(scores) / len(scores), 2) if scores else None
            print(f"AVG SCORE ({len(scores)} runs): {avg}   runs={scores}")
            print(f"last notes:\n{last}")
            if avg is not None:
                totals.append((Path(p).name, avg))

    if totals:
        print("=" * 64)
        print("SUMMARY (averaged):")
        for name, avg in totals:
            print(f"  {avg:>5}  {name}")
        print(f"  OVERALL AVG: {round(sum(a for _, a in totals) / len(totals), 2)}")


if __name__ == "__main__":
    main()
