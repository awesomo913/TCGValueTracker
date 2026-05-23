"""Grade app screenshots for visual quality using the local llava vision model.

Usage:  python tools/llava_grade.py <image.png> [<image2.png> ...]
Calls the local Ollama server (llava:7b) and prints a score + notes per image.
"""
import base64
import json
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


def main():
    if len(sys.argv) < 2:
        print("usage: python tools/llava_grade.py <image.png> [...]")
        return
    for p in sys.argv[1:]:
        print("=" * 64)
        print(Path(p).name)
        try:
            print(grade(p))
        except Exception as e:  # noqa: BLE001 - report any grading failure, keep going
            print(f"GRADE FAILED: {e}")


if __name__ == "__main__":
    main()
