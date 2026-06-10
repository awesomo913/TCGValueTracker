#!/usr/bin/env python3
"""
MrCoder v2 — Flexible multi-model coding pipeline launcher.

Pipeline types:
  solo       — 1 model, direct to Aider
  dual       — Architect→Editor (Aider --architect) + obscuring layer
  chain      — 2-4 models in sequence, each refines the output
  review     — Coder→Reviewer→Coder loop (iterative improvement)
  llm-config — LLM analyzes your task + available models, suggests best setup

Features:
  - Dynamic model discovery from Ollama
  - Uncensored model detection + obscuring layer for aligned models
  - GPU memory awareness
  - LLM-powered pipeline configurator
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

# ── Paths ────────────────────────────────────────────────────────────

LAUNCHER_DIR = Path(__file__).resolve().parent
AIDER_EXE = LAUNCHER_DIR / ".venv" / "Scripts" / "aider.exe"
CONVENTIONS = LAUNCHER_DIR / "mrcoder_conventions.md"
SCRUBBER = LAUNCHER_DIR / "mrcoder_scrub.py"
OBSCURE = LAUNCHER_DIR / "obscure.py"
MEMORY = LAUNCHER_DIR / "mrcoder_memory.py"
CONFIG_PATH = LAUNCHER_DIR / "launcher-config.json"

OLLAMA_BASE = "http://127.0.0.1:11434"

# ── Model classification ─────────────────────────────────────────────

UNCENSORED_KEYWORDS = [
    "uncensored", "abliterated", "dolphin", "neuraldaredevil",
    "lexi-uncensored", "my-uncensored-ai", "cracked",
]

CODER_KEYWORDS = ["coder", "deepseek-coder", "qwen", "starcoder", "stable-code",
                  "codeqwen", "phi", "command-r", "gemma", "mistral", "llama",
                  "qwen2", "qwen3", "eva-qwen", "magnum"]

VISION_KEYWORDS = ["vl", "vision", "moondream", "internvl", "smolvlm", "llava"]
AUDIO_KEYWORDS = ["audio", "whisper"]


def is_uncensored(name: str) -> bool:
    nl = name.lower()
    return any(kw in nl for kw in UNCENSORED_KEYWORDS)


def is_coder(name: str) -> bool:
    nl = name.lower()
    if any(kw in nl for kw in VISION_KEYWORDS + AUDIO_KEYWORDS):
        return False
    return any(kw in nl for kw in CODER_KEYWORDS)


def model_category(name: str) -> str:
    nl = name.lower()
    if any(kw in nl for kw in UNCENSORED_KEYWORDS):
        return "uncensored"
    if any(kw in nl for kw in VISION_KEYWORDS):
        return "vision"
    if any(kw in nl for kw in AUDIO_KEYWORDS):
        return "audio"
    if any(kw in nl for kw in CODER_KEYWORDS):
        return "coder"
    return "general"


# ── Ollama API ────────────────────────────────────────────────────────

def ollama_list() -> list[dict]:
    """Get all models from Ollama as list of {name, size_gb, ...}."""
    try:
        out = subprocess.run(
            ["ollama", "list"], capture_output=True, text=True, timeout=10
        )
        if out.returncode != 0:
            print(f"  WARNING: ollama list returned exit {out.returncode}: {out.stderr.strip()}")
        models = []
        for line in out.stdout.strip().split("\n")[1:]:
            parts = line.split()
            if not parts:
                continue
            name = parts[0]
            size_str = parts[2] if len(parts) > 2 else "?"
            try:
                if "GB" in size_str:
                    size_gb = float(size_str.replace("GB", ""))
                elif "MB" in size_str:
                    size_gb = float(size_str.replace("MB", "")) / 1024
                else:
                    size_gb = 0
            except ValueError:
                size_gb = 0
            models.append({"name": name, "size_gb": size_gb, "size_str": size_str})
        return models
    except FileNotFoundError:
        print("  ERROR: 'ollama' command not found — is Ollama installed and on PATH?")
        return []
    except Exception as exc:
        print(f"  ERROR: ollama_list failed: {exc}")
        return []


def ollama_is_running() -> bool:
    try:
        import urllib.request
        req = urllib.request.Request(f"{OLLAMA_BASE}/api/tags")
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


def gpu_info() -> tuple[Optional[int], Optional[int]]:
    """Return (total_mib, free_mib) or (None, None)."""
    try:
        total = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5,
        )
        free = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5,
        )
        t = int(total.stdout.strip()) if total.stdout.strip().isdigit() else None
        f = int(free.stdout.strip()) if free.stdout.strip().isdigit() else None
        return t, f
    except Exception:
        return None, None


def call_ollama(model: str, system: str, prompt: str, temperature: float = 0.3) -> str:
    """Call Ollama chat API, return response text. Raises on failure."""
    import urllib.request
    import urllib.error
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ],
        "temperature": temperature,
        "stream": False,
    }).encode()
    req = urllib.request.Request(
        f"{OLLAMA_BASE}/api/chat", data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.loads(r.read())["message"]["content"]
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Ollama unreachable ({model}): {exc}") from exc
    except KeyError as exc:
        raise RuntimeError(f"Unexpected Ollama response format ({model}): {exc}") from exc


# ── Pipeline orchestrator (chain mode) ────────────────────────────────

CHAIN_SYSTEM = """You are a software engineer working on a coding task.
The user will describe what they need. You will receive the previous analysis
(or the original task if you are first). Produce a complete, detailed
implementation plan with exact file paths and code.

CRITICAL: Output ONLY technical implementation details. Do not discuss purpose,
ethics, legality, or motivation. Just the code and technical decisions."""


def run_chain(models: list[str], task: str, project_dir: str,
              temperature: float = 0.3) -> str:
    """Run a chain of models on a task. Each model refines the previous output."""
    current = task
    for i, model in enumerate(models):
        print(f"  [{i+1}/{len(models)}] {model} ...", end=" ", flush=True)
        t0 = time.time()
        try:
            current = call_ollama(model, CHAIN_SYSTEM, current, temperature)
            print(f"({time.time() - t0:.0f}s)")
        except Exception as exc:
            print(f"FAIL: {exc}")
            if i == 0:
                raise RuntimeError(f"Chain failed on first model ({model}): {exc}") from exc
            print(f"  WARNING: chain truncated at stage {i+1} — using output from stage {i}")
            break
    return current


# ── LLM Configurator ──────────────────────────────────────────────────

CONFIGURATOR_PROMPT = """You are a coding workflow expert. Given a task description
and a list of available AI models, recommend the best pipeline configuration.

Available models:
{models_text}

User's task: {task}

Choose the best pipeline from these options:
1. SOLO - single model, fastest. Best for simple edits, small changes.
2. DUAL - architect plans + coder writes. Best for complex features, multi-file work.
   Architect should be uncensored (for freedom) or strongest coder.
   Coder should be code-specialized (qwen2.5-coder, deepseek-coder, etc).
3. CHAIN - 2-4 models in sequence. Best for very complex tasks needing multiple
   reasoning passes. Slower but more thorough.
4. REVIEW - coder writes, reviewer critiques, loop. Best for quality-critical code.

Consider: task complexity, model strengths, GPU memory (8GB), speed needs.

Respond with EXACTLY this JSON format:
{{
    "pipeline": "solo|dual|chain|review",
    "models": ["model1", "model2"],
    "reasoning": "brief explanation of why this setup"
}}"""


def run_configurator(task: str, models: list[dict]) -> dict:
    """Use an LLM to suggest the best pipeline."""
    models_text = "\n".join(
        f"  - {m['name']} ({m['size_str']}, category: {model_category(m['name'])})"
        for m in models
    )
    prompt = CONFIGURATOR_PROMPT.format(models_text=models_text, task=task)

    # Use a fast model for the configurator
    for cfg_model in ["qwen2.5-coder:7b-instruct-q4_K_M", "gemma3:4b",
                       "phi-4-mini-instruct:latest", "starcoder2:3b"]:
        try:
            resp = call_ollama(cfg_model, "You are a JSON-only API.", prompt, 0.1)
            import re
            m = re.search(r'\{[^{}]*"pipeline"[^{}]*\}', resp, re.DOTALL)
            if m:
                return json.loads(m.group(0))
        except Exception as exc:
            print(f"  Configurator model {cfg_model} failed: {exc}")
    return {"pipeline": "dual", "models": [],
            "reasoning": "Configurator unavailable, defaulting to dual"}


# ── UI Helpers ────────────────────────────────────────────────────────

def print_banner():
    print()
    print("  ╔══════════════════════════════════════════════════╗")
    print("  ║            MrCoder v2 — Pipeline Lab             ║")
    print("  ╚══════════════════════════════════════════════════╝")
    print()


def pick_from_list(items: list[str], prompt: str, allow_custom: bool = True) -> str:
    """Show numbered list, return selection."""
    for i, item in enumerate(items, 1):
        print(f"  [{i}] {item}")
    if allow_custom:
        print(f"  [0] (type custom)")
    print()
    while True:
        choice = input(f"  {prompt} ").strip()
        if choice == "0" and allow_custom:
            return input("  Custom: ").strip()
        try:
            idx = int(choice) - 1
            if 0 <= idx < len(items):
                return items[idx]
        except ValueError:
            pass
        print("  Invalid choice, try again")


def pick_models(all_models: list[dict], count: int, role_names: list[str],
                filter_fn=None) -> list[str]:
    """Pick N models for N roles."""
    if filter_fn:
        candidates = [m for m in all_models if filter_fn(m)]
    else:
        candidates = all_models

    names = [f"{m['name']} ({m['size_str']})" for m in candidates]
    selected = []
    for i in range(count):
        role = role_names[i] if i < len(role_names) else f"Model {i+1}"
        print(f"\n  --- Pick {role} ---")
        choice = pick_from_list(names, f"Choice (1-{len(names)}):")
        # Extract the model name
        model_name = choice.split(" (")[0] if " (" in choice else choice
        selected.append(model_name)
        # Track which models are loaded
    return selected


# ── Project folder picker ─────────────────────────────────────────────

def pick_project() -> str:
    """Pick project folder."""
    default = "C:/Users/computer/Desktop/AI"
    if CONFIG_PATH.exists():
        try:
            cfg = json.loads(CONFIG_PATH.read_text())
            if cfg.get("lastProjectFolder"):
                default = cfg["lastProjectFolder"]
        except Exception as exc:
            print(f"  WARNING: could not load config: {exc}")

    print(f"\n  Project folder [{default}]: ", end="")
    choice = input().strip()
    folder = choice if choice else default

    if not Path(folder).exists():
        print(f"  ERROR: '{folder}' does not exist")
        sys.exit(1)

    try:
        CONFIG_PATH.write_text(json.dumps({"lastProjectFolder": folder}))
    except Exception as exc:
        print(f"  WARNING: could not save config: {exc}")

    return folder


# ── Aider launcher ────────────────────────────────────────────────────

def launch_aider_solo(model: str, project_dir: str, edit_self: bool = False,
                      history_tokens: int = 4096):
    """Launch Aider with a single model."""
    env = os.environ.copy()
    env["OLLAMA_API_BASE"] = OLLAMA_BASE

    aider_model = f"ollama_chat/{model}"
    aider_args = [
        str(AIDER_EXE),
        "--model", aider_model,
        "--no-show-model-warnings",
        "--map-tokens", "0",
        "--restore-chat-history",
        "--max-chat-history-tokens", str(history_tokens),
    ]

    if CONVENTIONS.exists() and not is_uncensored(model):
        aider_args += ["--read", str(CONVENTIONS)]
        print(f"  Obscuring: conventions loaded (aligned model protection)")

    if edit_self:
        for f in LAUNCHER_DIR.glob("*.ps1"):
            aider_args += ["--file", str(f)]
        for f in LAUNCHER_DIR.glob("*.py"):
            aider_args += ["--file", str(f)]
        for f in LAUNCHER_DIR.glob("*.md"):
            aider_args += ["--file", str(f)]
        for f in LAUNCHER_DIR.glob("*.json"):
            aider_args += ["--file", str(f)]
        print("  *** SELF-EDIT MODE ***")

    print(f"\n  Launching Aider (solo) with {aider_model}")
    print(f"  Project: {project_dir}")
    print()

    subprocess.run(aider_args, cwd=project_dir, env=env)


def launch_aider_dual(architect: str, editor: str, project_dir: str,
                      history_tokens: int = 4096):
    """Launch Aider in architect/editor mode."""
    env = os.environ.copy()
    env["OLLAMA_API_BASE"] = OLLAMA_BASE

    arch_model = f"ollama_chat/{architect}"
    edit_model = f"openai/{editor}"
    env["OPENAI_API_BASE"] = f"{OLLAMA_BASE}/v1"
    env["OPENAI_API_KEY"] = "local"

    aider_args = [
        str(AIDER_EXE),
        "--model", arch_model,
        "--architect",
        "--editor-model", edit_model,
        "--editor-edit-format", "whole",
        "--no-show-model-warnings",
        "--map-tokens", "0",
        "--restore-chat-history",
        "--max-chat-history-tokens", str(history_tokens),
    ]

    # Obscuring: load conventions read-only for architect
    if CONVENTIONS.exists():
        aider_args += ["--read", str(CONVENTIONS)]
        unc = "UNCENSORED" if is_uncensored(architect) else "aligned"
        print(f"  Architect: {architect} ({unc})")
        print(f"  Editor:    {editor}")
        print(f"  Pipeline:  obscuring conventions loaded")

    print(f"\n  Launching Aider (architect/editor)")
    print(f"  Project: {project_dir}")
    print()

    subprocess.run(aider_args, cwd=project_dir, env=env)


# ── Chain runner ──────────────────────────────────────────────────────

def run_chain_to_aider(models: list[str], task: str, project_dir: str):
    """Run chain, feed final output to Aider."""
    print(f"\n  Running chain: {' → '.join(models)}")
    print(f"  Task: {task[:100]}...")
    print()

    final_spec = run_chain(models, task, project_dir)

    # Show the final spec
    print(f"\n  {'='*60}")
    print(f"  Final specification ({len(final_spec)} chars)")
    print(f"  {'='*60}")

    # Feed to Aider
    env = os.environ.copy()
    env["OLLAMA_API_BASE"] = OLLAMA_BASE

    # Use the last model as the Aider model for editing
    last_model = f"ollama_chat/{models[-1]}"
    aider_args = [
        str(AIDER_EXE),
        "--model", last_model,
        "--no-show-model-warnings",
        "--map-tokens", "0",
        "--restore-chat-history",
        "--max-chat-history-tokens", "4096",
        "--message", final_spec[:4000],  # Initial message with the spec
    ]

    if CONVENTIONS.exists():
        aider_args += ["--read", str(CONVENTIONS)]

    print(f"\n  Handing off to Aider for implementation...")
    subprocess.run(aider_args, cwd=project_dir, env=env)


# ── Main ──────────────────────────────────────────────────────────────

def main():
    try:
        _main()
    except EOFError:
        print("\n  ERROR: No interactive input available.")
        print("  Launch from a terminal: .venv\\Scripts\\python mrcoder_v2.py")
        input("  Press Enter to exit...")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n  Cancelled.")
        sys.exit(0)
    except Exception as e:
        print(f"\n  FATAL: {e}")
        import traceback
        traceback.print_exc()
        input("  Press Enter to exit...")
        sys.exit(1)


def _main():
    # Check prerequisites
    if not AIDER_EXE.exists():
        print(f"ERROR: Aider not found at {AIDER_EXE}")
        sys.exit(1)

    if not ollama_is_running():
        print("Starting Ollama...")
        subprocess.Popen(["ollama", "serve"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(30):
            time.sleep(1)
            if ollama_is_running():
                break
        else:
            print("ERROR: Ollama failed to start")
            sys.exit(1)

    # Discover models
    all_models = ollama_list()
    if not all_models:
        print("ERROR: No models found in Ollama")
        sys.exit(1)

    # Exclude embedding models
    all_models = [m for m in all_models
                  if "embed" not in m["name"].lower() and "nomic" not in m["name"].lower()]

    total, free = gpu_info()

    # Categorize
    coders = [m for m in all_models if is_coder(m["name"])]
    uncensored = [m for m in all_models if is_uncensored(m["name"])]
    others = [m for m in all_models
              if not is_coder(m["name"]) and not is_uncensored(m["name"])]

    print_banner()

    if total:
        print(f"  GPU: {total} MiB total, {free} MiB free")
    print(f"  Models: {len(coders)} coders, {len(uncensored)} uncensored, "
          f"{len(others)} other ({len(all_models)} total)")
    print()

    # Pipeline selection
    print("  Pipeline types:")
    print("    [1] SOLO         — 1 model, fastest, direct to Aider")
    print("    [2] DUAL         — Architect plans + Coder writes (obscuring layer)")
    print("    [3] CHAIN        — 2-4 models in sequence, each refines output")
    print("    [4] REVIEW LOOP  — Coder → Reviewer, iterate until done")
    print("    [5] LLM CONFIG   — AI analyzes your task + suggests best setup")
    print("    [6] SELF-EDIT    — Edit MrCoder's own source code")
    print()

    pipeline = input("  Pipeline [2]: ").strip() or "2"

    project_dir = None

    if pipeline == "1":
        # Solo
        model = pick_models(all_models, 1, ["Coder"],
                            filter_fn=lambda m: is_coder(m["name"]) or True)[0]
        project_dir = pick_project()
        launch_aider_solo(model, project_dir)

    elif pipeline == "2":
        # Dual (architect/editor)
        print("\n  --- Pick ARCHITECT (plans the work) ---")
        arch = pick_models(uncensored if uncensored else all_models, 1,
                           ["Architect"])[0]
        print("\n  --- Pick EDITOR (writes the code) ---")
        edit = pick_models(coders if coders else all_models, 1,
                           ["Editor"])[0]
        project_dir = pick_project()
        launch_aider_dual(arch, edit, project_dir)

    elif pipeline == "3":
        # Chain
        n = input("\n  How many models in the chain? [2-4]: ").strip() or "2"
        n = max(2, min(4, int(n)))
        roles = [f"Stage {i+1}" for i in range(n)]
        models = pick_models(all_models, n, roles)
        project_dir = pick_project()
        task = input("\n  Describe the task: ").strip()
        if not task:
            print("ERROR: Task description required for chain mode")
            sys.exit(1)
        run_chain_to_aider(models, task, project_dir)

    elif pipeline == "4":
        # Review loop
        print("\n  --- Pick CODER ---")
        coder = pick_models(coders if coders else all_models, 1, ["Coder"])[0]
        print("\n  --- Pick REVIEWER ---")
        reviewer = pick_models(all_models, 1, ["Reviewer"])[0]
        project_dir = pick_project()
        # For now, review loop uses Aider dual mode with the reviewer as architect
        print("\n  Review mode: Coder writes, Reviewer critiques through Aider")
        launch_aider_dual(reviewer, coder, project_dir)

    elif pipeline == "5":
        # LLM Configurator
        task = input("\n  Describe what you want to build: ").strip()
        if not task:
            print("ERROR: Task description required")
            sys.exit(1)
        print("\n  Analyzing with LLM configurator...")
        config = run_configurator(task, all_models)
        print(f"\n  Recommended: {config['pipeline'].upper()}")
        if config.get("models"):
            print(f"  Models: {' → '.join(config['models'])}")
        print(f"  Why: {config.get('reasoning', 'N/A')}")
        print()
        if input("  Use this config? [Y/n]: ").strip().lower() in ("", "y", "yes"):
            if config["pipeline"] == "solo" and config.get("models"):
                project_dir = pick_project()
                launch_aider_solo(config["models"][0], project_dir)
            elif config["pipeline"] == "dual" and len(config.get("models", [])) >= 2:
                project_dir = pick_project()
                launch_aider_dual(config["models"][0], config["models"][1], project_dir)
            else:
                # Fall back to interactive
                print("  Falling back to interactive dual mode...")
                arch = pick_models(uncensored if uncensored else all_models,
                                   1, ["Architect"])[0]
                edit = pick_models(coders if coders else all_models,
                                   1, ["Editor"])[0]
                project_dir = pick_project()
                launch_aider_dual(arch, edit, project_dir)

    elif pipeline == "6":
        # Self-edit
        print("\n  --- Pick model for self-editing ---")
        model = pick_models(coders if coders else all_models, 1, ["Coder"])[0]
        project_dir = str(LAUNCHER_DIR)
        launch_aider_solo(model, project_dir, edit_self=True)

    else:
        print(f"  Unknown pipeline: {pipeline}")
        sys.exit(1)


if __name__ == "__main__":
    main()
