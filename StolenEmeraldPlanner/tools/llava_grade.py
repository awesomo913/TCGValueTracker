"""Grade app screenshots for visual quality using the local llava vision model.

Usage:  python tools/llava_grade.py [--runs N] <image.png> [<image2.png> ...]

llava:7b scores are noisy (±~2 across identical runs), so pass --runs N to grade
each image N times and average — that cuts the variance and gives a stable number
worth optimizing toward. Default --runs 1.
"""
import base64
import json
import re
import sys
import urllib.request
from pathlib import Path

OLLAMA = "http://localhost:11434/api/generate"
MODEL = "llava:7b"

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


def grade(img_path: str) -> str:
    data = Path(img_path).read_bytes()
    b64 = base64.b64encode(data).decode()
    body = json.dumps(
        {"model": MODEL, "prompt": PROMPT, "images": [b64], "stream": False}
    ).encode()
    req = urllib.request.Request(OLLAMA, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read())["response"].strip()


def _score_of(text: str):
    m = re.search(r"SCORE:\s*([0-9]+(?:\.[0-9]+)?)", text)
    return float(m.group(1)) if m else None


def main():
    args = sys.argv[1:]
    runs = 1
    if args and args[0] == "--runs":
        runs = int(args[1])
        args = args[2:]
    if not args:
        print("usage: python tools/llava_grade.py [--runs N] <image.png> [...]")
        return

    totals = []
    for p in args:
        print("=" * 64)
        print(Path(p).name)
        scores = []
        last = ""
        for _ in range(runs):
            try:
                last = grade(p)
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
