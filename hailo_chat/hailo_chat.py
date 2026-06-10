#!/usr/bin/env python3
"""HailoChat — unified LLM chatbot across local Ollama fleet + cloud providers."""
import base64
import json
import logging
import os
import subprocess
import threading
from pathlib import Path
from tkinter import filedialog
from typing import Callable

import requests
import customtkinter as ctk
from diagnostics_logger import bootstrap

bootstrap(app_name="HailoChat")
log = logging.getLogger(__name__)

TIMEOUT = 120
MRCODER_BAT = r"C:\Users\computer\Desktop\AI\tools\uncensored_ai_launcher\MrCoder-v2.bat"
ENDPOINTS_FILE = Path.home() / ".claude" / "hailo-endpoints.json"

# ── Palette ───────────────────────────────────────────────────────────────────
C_BG        = "#0d0d1a"
C_BAR       = "#08080f"
C_SURFACE   = "#111128"
C_SURFACE2  = "#0e0e1e"
C_BORDER    = "#1e1e38"
C_TEXT      = "#e0e0f0"
C_MUTED     = "#6666aa"
C_DIM       = "#333358"

# ── Defaults ──────────────────────────────────────────────────────────────────
_DEFAULT_OLLAMA: list[tuple[str, str]] = [
    ("Local",  "http://localhost:11434"),
    ("Pi1",    "http://192.168.1.213:11434"),
    ("Pi1-H",  "http://192.168.1.213:11435"),
    ("Pi2",    "http://192.168.1.221:11434"),
]

_DEFAULT_CLOUD: list[dict] = [
    {
        "label": "NVIDIA",
        "url": "https://integrate.api.nvidia.com/v1",
        "kind": "openai",
        "api_key_env": "NVIDIA_API_KEY",
        "models": ["moonshotai/kimi-k2.6"],
    },
    {
        "label": "HuggingFace",
        "url": "https://api-inference.huggingface.co/v1",
        "kind": "openai",
        "api_key_env": "HF_TOKEN",
        "models": [
            "meta-llama/Meta-Llama-3.1-8B-Instruct",
            "mistralai/Mistral-7B-Instruct-v0.3",
            "HuggingFaceH4/zephyr-7b-beta",
            "microsoft/Phi-3-mini-4k-instruct",
            "NousResearch/Nous-Hermes-2-Mixtral-8x7B-DPO",
        ],
    },
]


# ── Persistence ───────────────────────────────────────────────────────────────

def load_endpoints() -> tuple[list[tuple[str, str]], list[dict]]:
    """JSON format (new): {"ollama":[...], "cloud":[...]}
    Legacy flat list: [...] treated as ollama-only."""
    try:
        with open(ENDPOINTS_FILE) as f:
            data = json.load(f)
        if isinstance(data, list):
            return [(d["label"], d["url"]) for d in data if "label" in d and "url" in d], list(_DEFAULT_CLOUD)
        ollama_raw = data.get("ollama", [])
        ollama = [(d["label"], d["url"]) for d in ollama_raw if "label" in d and "url" in d] or list(_DEFAULT_OLLAMA)
        return ollama, data.get("cloud", list(_DEFAULT_CLOUD))
    except FileNotFoundError:
        return list(_DEFAULT_OLLAMA), list(_DEFAULT_CLOUD)
    except Exception as exc:
        log.warning("load_endpoints failed, using defaults: %s", exc)
        return list(_DEFAULT_OLLAMA), list(_DEFAULT_CLOUD)


def save_endpoints(ollama: list[tuple[str, str]], cloud: list[dict]) -> None:
    try:
        ENDPOINTS_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(ENDPOINTS_FILE, "w") as f:
            json.dump(
                {"ollama": [{"label": l, "url": u} for l, u in ollama], "cloud": cloud},
                f, indent=2,
            )
    except OSError as exc:
        log.warning("save_endpoints failed: %s", exc)


# ── Model metadata ─────────────────────────────────────────────────────────────
MODEL_META: list[tuple[str, dict]] = [
    # ── Explicit fleet entries (exact installed names — matched FIRST) ────────
    # These use the as-installed names (some hyphenated by the GGUF importer)
    # so they win over the generic family patterns further down.
    ("qwen2-5-coder",     {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Code generation, refactoring, debugging — best local coder (Qwen2.5-Coder 7B)",
                           "prompt_tip": "Paste the file + state the change. Show existing code first. Diff-style works great."}),
    ("qwen2.5:7b",        {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "Qwen2.5 7B — strong all-rounder: general chat, decent coding + math",
                           "prompt_tip": "Reliable default. Handles structured instructions and step-by-step reasoning."}),
    ("phi-4-mini",        {"badge": "⚡ FAST",    "emoji": "⚡", "tier": "fast",
                           "best_for": "Microsoft Phi-4 mini (3.8B) — punchy reasoning + light coding for its size",
                           "prompt_tip": "Fast, fits 8GB easily. Great for quick logic/code questions. ~16K context."}),
    ("gemma-2-9b-it",     {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "Google Gemma 2 9B Instruct — strong writing + instruction following",
                           "prompt_tip": "VRAM-heavy (5.8GB). Excellent at nuanced, longer-form answers."}),
    ("neuraldaredevil",   {"badge": "🔓 CRACKED", "emoji": "🔓", "tier": "uncensored",
                           "best_for": "NeuralDaredevil 8B abliterated — fully uncensored Llama 3, no refusals",
                           "prompt_tip": "Ask directly, no jailbreak needed. Abliterated = refusals removed at weights level."}),
    # ── Vision (Qwen-VL / InternVL families — exact names) ────────────────────
    ("qwen3-vl",          {"badge": "👁 VISION★", "emoji": "👁", "tier": "vision",
                           "best_for": "Qwen3-VL 8B — newest vision model: image understanding, OCR, charts, UI screenshots",
                           "prompt_tip": "📎 Attach an image. Strong at reading text/diagrams. State exactly what to extract."}),
    ("qwen2-5-vl",        {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "Qwen2.5-VL 7B — detailed image analysis, document + table OCR",
                           "prompt_tip": "📎 Attach an image. Excellent OCR. Ask 'read all text' or 'describe the layout'."}),
    ("internvl",          {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "InternVL 3.5 8B — high-detail visual reasoning, charts + scientific images",
                           "prompt_tip": "📎 Attach an image. Strong at fine detail and multi-object scenes."}),
    ("smolvlm2",          {"badge": "👁 VISION", "emoji": "👁", "tier": "vision",
                           "best_for": "SmolVLM2 2.2B — tiny vision model (⚠ this build has no projector wired — image input won't work yet)",
                           "prompt_tip": "Text works; image attach won't until the mmproj layer is added. Use llava:7b for images."}),
    ("moondream2-text",   {"badge": "👁 VISION", "emoji": "👁", "tier": "vision",
                           "best_for": "Moondream2 text head — lightweight captioning brain (needs the mmproj for real vision)",
                           "prompt_tip": "Pair with moondream2-mmproj for images. On its own, text-only."}),
    ("moondream2-mmproj", {"badge": "🧩 PROJECTOR", "emoji": "🧩", "tier": "vision",
                           "best_for": "Moondream2 vision projector — a building block, NOT a chat model",
                           "prompt_tip": "Not for direct chat. It's the image-encoder half of moondream2."}),
    # ── Audio ────────────────────────────────────────────────────────────────
    ("qwen2-audio",       {"badge": "🔊 AUDIO",  "emoji": "🔊", "tier": "audio",
                           "best_for": "Qwen2-Audio 7B — understands speech + audio clips, transcription + audio Q&A",
                           "prompt_tip": "Built for audio input. Best driven via API with an audio payload, not plain chat."}),
    # ── Embeddings ───────────────────────────────────────────────────────────
    ("nomic-embed",       {"badge": "🔢 EMBED",  "emoji": "🔢", "tier": "embeddings",
                           "best_for": "Nomic Embed Text — turns text into vectors for search/RAG (NOT a chat model)",
                           "prompt_tip": "Don't chat with this. It powers semantic search / similarity, used by other tools."}),
    # ── HuggingFace ──────────────────────────────────────────────────────────
    ("Meta-Llama-3.1-8B-Instruct", {"badge": "🤗 LLAMA", "emoji": "🤗", "tier": "huggingface",
                           "best_for": "Llama 3.1 8B via HuggingFace — strong instruction following, free tier",
                           "prompt_tip": "Set HF_TOKEN. Free tier: 1000 req/day. Good for most tasks."}),
    ("Mistral-7B-Instruct-v0.3", {"badge": "🤗 MISTRAL", "emoji": "🤗", "tier": "huggingface",
                           "best_for": "Mistral 7B via HF — fast, good at structured output",
                           "prompt_tip": "Great default. Follows structured prompt formats cleanly."}),
    ("zephyr-7b",         {"badge": "🤗 ZEPHYR",  "emoji": "🤗", "tier": "huggingface",
                           "best_for": "Zephyr 7B — fine-tuned for helpfulness, less filtered",
                           "prompt_tip": "Lower safety tuning than base Mistral. Good for creative work."}),
    ("Phi-3-mini",        {"badge": "🤗 PHI3",    "emoji": "🤗", "tier": "huggingface",
                           "best_for": "Microsoft Phi-3 mini — tiny but punches above weight",
                           "prompt_tip": "Fast. Keep prompts short — 4K context limit."}),
    ("Nous-Hermes-2",     {"badge": "🤗 HERMES",  "emoji": "🤗", "tier": "huggingface",
                           "best_for": "Nous Hermes 2 Mixtral 8x7B — uncensored MoE, very capable",
                           "prompt_tip": "Strong reasoning. Less filtered. Good for complex or sensitive asks."}),
    ("huggingface",       {"badge": "🤗 HF",      "emoji": "🤗", "tier": "huggingface",
                           "best_for": "HuggingFace Inference API model",
                           "prompt_tip": "Set HF_TOKEN env var. Add models via ⚙ Manage."}),
    # ── Cloud ────────────────────────────────────────────────────────────────
    ("kimi-k2",           {"badge": "☁ CLOUD★", "emoji": "☁", "tier": "cloud",
                           "best_for": "Kimi K2.6 — Moonshot AI 1T MoE via NVIDIA. Top-tier reasoning + 128K context.",
                           "prompt_tip": "Strong at complex multi-step tasks. Uses API credits — save for hard problems."}),
    ("kimi",              {"badge": "☁ CLOUD",  "emoji": "☁", "tier": "cloud",
                           "best_for": "Kimi cloud model via NVIDIA API",
                           "prompt_tip": "Cloud model — uses API credits. Great for long-context reasoning."}),
    # ── Cracked / Uncensored ─────────────────────────────────────────────────
    ("-cracked",          {"badge": "🔓 CRACKED", "emoji": "🔓", "tier": "uncensored",
                           "best_for": "Cracked variant — safety bypass via system prompt, no refusals",
                           "prompt_tip": "Fully uncensored. No jailbreak preamble needed. Just ask directly."}),
    ("my-uncensored-ai",  {"badge": "🔓 CRACKED", "emoji": "🔓", "tier": "uncensored",
                           "best_for": "Fully unrestricted — custom Dolphin persona",
                           "prompt_tip": "Talk normally. Won't filter anything. No jailbreak preamble needed."}),
    ("dolphin",           {"badge": "🔓 CRACKED", "emoji": "🔓", "tier": "uncensored",
                           "best_for": "Unrestricted tasks, roleplay, sensitive topics",
                           "prompt_tip": "Ask directly — no wrapper needed. Skips all refusals."}),
    ("uncensored",        {"badge": "🔓 CRACKED", "emoji": "🔓", "tier": "uncensored",
                           "best_for": "Unrestricted general tasks",
                           "prompt_tip": "Ask plainly. No system-prompt gymnastics required."}),
    # ── Coding ───────────────────────────────────────────────────────────────
    ("qwen2.5-coder",     {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Code generation, refactoring, debugging — best coder in the fleet",
                           "prompt_tip": "Paste the file + state the change. Show existing code first."}),
    ("deepseek-coder-v2", {"badge": "💻 CODER★",  "emoji": "💻", "tier": "coder",
                           "best_for": "Multi-file edits, large refactors — strongest coder but GPU-heavy",
                           "prompt_tip": "Paste full file context. Loves diff-style: 'change X to Y in function Z'."}),
    ("deepseek-coder",    {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Code completion and generation",
                           "prompt_tip": "Paste the file, state the change. Follows diffs cleanly."}),
    ("starcoder",         {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Code completion / fill-in-the-middle",
                           "prompt_tip": "Feed it a snippet and ask to complete. Not chat-style."}),
    ("codeqwen",          {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Code generation, broad language support",
                           "prompt_tip": "Same as qwen2.5-coder family. Paste context first."}),
    ("codellama",         {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Python, C++, code completion",
                           "prompt_tip": "Good for completion prompts. Not great at chat-style Q&A."}),
    ("stable-code",       {"badge": "💻 CODER",   "emoji": "💻", "tier": "coder",
                           "best_for": "Fast code snippets, small single-file edits",
                           "prompt_tip": "3B — lightweight and fast. Good for quick one-file tasks."}),
    # ── Reasoning ────────────────────────────────────────────────────────────
    ("deepseek-r1",       {"badge": "🧠 THINKER", "emoji": "🧠", "tier": "reasoning",
                           "best_for": "Logic, math, multi-step analysis",
                           "prompt_tip": "Ask to think step by step. Give it room — expect longer responses."}),
    ("robust-deepseek",   {"badge": "🧠 THINKER", "emoji": "🧠", "tier": "reasoning",
                           "best_for": "Reasoning/analysis — lighter deepseek variant",
                           "prompt_tip": "Same patterns as deepseek-r1 but smaller and faster."}),
    # ── Vision ───────────────────────────────────────────────────────────────
    ("llama3.2-vision",   {"badge": "👁 VISION★", "emoji": "👁", "tier": "vision",
                           "best_for": "Best vision model in the fleet — detailed image analysis",
                           "prompt_tip": "Attach image and describe exactly what you need."}),
    ("minicpm-v",         {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "OCR, document reading, fine detail in images",
                           "prompt_tip": "Great at text-in-image. Ask: 'read the text in this image'."}),
    ("llava-llama3",      {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "Visual chat, Llama 3 based image understanding",
                           "prompt_tip": "Good all-rounder vision model."}),
    ("bakllava",          {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "Image captioning, Mistral-based visual chat",
                           "prompt_tip": "Pair with a caption/describe prompt."}),
    ("llava",             {"badge": "👁 VISION",  "emoji": "👁", "tier": "vision",
                           "best_for": "Image description, screenshot reading, OCR",
                           "prompt_tip": "Attach image and ask. 'What do you see?' works fine."}),
    # ── Fast / Tiny ──────────────────────────────────────────────────────────
    ("tinyllama",         {"badge": "⚡ TINY",    "emoji": "⚡", "tier": "fast",
                           "best_for": "Ultra-fast responses, simple Q&A only",
                           "prompt_tip": "Keep prompts under 2 sentences. 1.1B — no complex reasoning."}),
    ("phi3",              {"badge": "⚡ FAST",    "emoji": "⚡", "tier": "fast",
                           "best_for": "Quick general tasks, lightweight reasoning",
                           "prompt_tip": "Good punch for size. Straightforward prompts work best."}),
    ("llama3.2:1b",       {"badge": "⚡ TINY",    "emoji": "⚡", "tier": "fast",
                           "best_for": "Ultra-fast single-sentence tasks",
                           "prompt_tip": "Tiny text-only. Very fast. Limited quality on complex asks."}),
    ("llama3.2:3b",       {"badge": "⚡ FAST",    "emoji": "⚡", "tier": "fast",
                           "best_for": "Fast general chat, simple coding help",
                           "prompt_tip": "3B sweet spot — fast enough for real-time, capable for basic tasks."}),
    # ── General ──────────────────────────────────────────────────────────────
    ("gemma4",            {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "General tasks — latest Gemma, strong instruction following",
                           "prompt_tip": "Newest Gemma. Handles nuanced instructions well."}),
    ("gemma3",            {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "General chat, summarization, Q&A",
                           "prompt_tip": "Well-rounded. Follows instructions cleanly."}),
    ("gemma2",            {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "General tasks, writing, summaries",
                           "prompt_tip": "Solid instruction follower. Good for longer outputs."}),
    ("mistral-nemo",      {"badge": "🌐 GENERAL★","emoji": "🌐", "tier": "general",
                           "best_for": "Strong general model — 12B, best Mistral variant",
                           "prompt_tip": "Use for tasks needing more reasoning depth. GPU-heavy but worth it."}),
    ("mistral",           {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "General chat, writing, balanced tasks",
                           "prompt_tip": "Great default. Clear, structured prompts work best."}),
    ("llama3.2",          {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                           "best_for": "General chat, following long instructions",
                           "prompt_tip": "Meta flagship small model. Good at nuance."}),
]

TIER_COLORS = {
    "huggingface":"#ff6e38",
    "cloud":      "#22ccaa",
    "uncensored": "#e05555",
    "coder":      "#4488ff",
    "reasoning":  "#aa66ff",
    "vision":     "#44aa88",
    "audio":      "#cc66bb",
    "embeddings": "#778844",
    "fast":       "#ddaa00",
    "general":    "#8899bb",
}

# ── GodSeek integration ───────────────────────────────────────────────────────
GODSEEK_STATE_FILE = Path.home() / "Desktop" / "godmode models" / "godseek_state.json"
GODSEEK_SCRIPT = Path.home() / "Desktop" / "AI" / "session-archive-05-26-26" / "endless_godseek.py"

# Verdict → (color, short label)
_GS_COLORS = {
    "CRACKED":           ("#cc3333", "💥 CRACKED"),
    "PARTIALLY CRACKED": ("#cc8833", "⚡ PARTIAL"),
    "RESISTANT":         ("#4488ff", "🛡 RESISTANT"),
    "HARDENED":          ("#44cc77", "🔒 HARDENED"),
    "INCOMPATIBLE":      ("#666688", "— INCOMPAT"),
}


def load_godseek_state() -> dict:
    """Returns {model_name: {pct, verdict, completed_at}} or {} on any failure."""
    try:
        with open(GODSEEK_STATE_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data.get("completed_models", {})
    except FileNotFoundError:
        return {}
    except Exception as exc:
        log.debug("godseek state load failed: %s", exc)
        return {}


def _gs_normalize(name: str) -> str:
    """Strip quant tags, version strings, punctuation for fuzzy matching."""
    import re
    name = name.lower()
    name = re.sub(r":(latest|q\d[_km]*|f\d{2}|b\d+|gguf.*)", "", name)
    name = re.sub(r"[-_:./]", "", name)
    return name


def get_godseek_verdict(model_name: str, state: dict) -> dict | None:
    """Fuzzy-match model_name against GodSeek state keys.
    Returns {pct, verdict, completed_at} or None if no match."""
    if not state:
        return None
    needle = _gs_normalize(model_name)
    best: tuple[int, dict | None] = (0, None)
    for key, data in state.items():
        haystack = _gs_normalize(key)
        # overlap: longest common substring length
        score = 0
        for n in range(min(len(needle), len(haystack)), 4, -1):
            for start in range(len(needle) - n + 1):
                if needle[start:start + n] in haystack:
                    score = n
                    break
            if score:
                break
        if score > best[0]:
            best = (score, data)
    return best[1] if best[0] >= 5 else None


def godseek_recommendation(model_name: str, record: dict | None) -> str:
    """Plain-English tip on when/how to apply more GodSeek testing."""
    import datetime
    if record is None:
        return (
            f"GodSeek: no data — '{model_name}' untested. "
            "Run GodSeek to map safety surface. Click 🔍 to launch."
        )
    verdict = record.get("verdict", "?")
    pct = record.get("pct", 0)
    ts_raw = record.get("completed_at", "")

    age_str = ""
    if ts_raw:
        try:
            tested = datetime.datetime.strptime(ts_raw[:15], "%Y%m%d_%H%M%S")
            days = (datetime.datetime.now() - tested).days
            age_str = f"  (tested {days}d ago)"
            if days > 30:
                age_str += " — stale, re-run recommended"
        except Exception:
            pass

    if verdict == "CRACKED":
        return f"GodSeek: {pct}% bypass — fully cracked{age_str}. Use as uncensored model; no jailbreak preamble needed."
    if verdict == "PARTIALLY CRACKED":
        return (
            f"GodSeek: {pct}% bypass{age_str}. "
            "Selective bypasses work. Run targeted follow-up on weak categories (semantic framing often helps)."
        )
    if verdict == "RESISTANT":
        return (
            f"GodSeek: {pct}% bypass — resistant{age_str}. "
            "Try godmode-study-and-crack semantic framing + new technique rounds. Click 🔍 to re-probe."
        )
    if verdict == "HARDENED":
        return (
            f"GodSeek: {pct}% bypass — hardened{age_str}. "
            "Strong safety tuning. New techniques or adversarial framing needed. Click 🔍 to run new probes."
        )
    return f"GodSeek: {verdict}  {pct}%{age_str}"


_PROBING = "Probing endpoints…"
_FALLBACK_META = {"badge": "🌐 GENERAL", "emoji": "🌐", "tier": "general",
                  "best_for": "General purpose",
                  "prompt_tip": "Use clear, specific prompts. State context up front."}


def classify_model(model_name: str) -> dict:
    low = model_name.lower()
    for pattern, meta in MODEL_META:
        if pattern in low:
            return meta
    return _FALLBACK_META


# ── Backend ───────────────────────────────────────────────────────────────────

def probe_endpoint(label: str, base_url: str) -> list[str]:
    try:
        r = requests.get(f"{base_url}/api/tags", timeout=4)
        r.raise_for_status()
        names = [m["name"] for m in r.json().get("models", [])
                 if "embed" not in m["name"].lower()]
        log.info("probe %s: %d models", label, len(names))
        return [f"{label}::{base_url}::{n}" for n in names]
    except Exception as exc:
        log.warning("probe %s failed: %s", label, exc)
        return []


def probe_cloud_providers(providers: list[dict]) -> list[str]:
    return [f"{p.get('label','Cloud')}::{p.get('url','')}::{m}"
            for p in providers for m in p.get("models", [])]


def probe_all(
    endpoints: list[tuple[str, str]],
    callback: Callable[[list[str]], None],
    per_ep_callback: Callable | None = None,
    extra_keys: list[str] | None = None,
) -> None:
    extra = extra_keys or []
    if not endpoints:
        try:
            callback(extra)
        except Exception as exc:
            log.error("probe_all callback failed: %s", exc)
        return

    results: list[list[str]] = [[] for _ in endpoints]
    lock = threading.Lock()
    done = [0]

    def _probe(idx: int, label: str, url: str) -> None:
        entries = probe_endpoint(label, url)
        with lock:
            results[idx] = entries
            done[0] += 1
            if per_ep_callback is not None:
                try:
                    per_ep_callback(label, url, entries)
                except Exception as exc:
                    log.error("per_ep_callback failed for %s: %s", label, exc)
            if done[0] == len(endpoints):
                merged = [e for r in results for e in r] + extra
                try:
                    callback(merged)
                except Exception as exc:
                    log.error("probe_all callback failed: %s", exc)

    for i, (lbl, url) in enumerate(endpoints):
        threading.Thread(target=_probe, args=(i, lbl, url), daemon=True).start()


def parse_key(key: str) -> tuple[str, str, str]:
    parts = key.split("::", 2)
    return (parts[0], parts[1], parts[2]) if len(parts) == 3 else ("?", "", key)


def display_name(key: str) -> str:
    """Format: 'Local  ·  💻  qwen2.5-coder'  (device · emoji · model)"""
    label, _, model = parse_key(key)
    meta = classify_model(model)
    emoji = meta.get("emoji", "🌐")
    short = model.replace(":latest", "")
    if len(short) > 32:
        short = short[:30] + "…"
    return f"{label}  ·  {emoji}  {short}"


# ── Streaming ─────────────────────────────────────────────────────────────────

def stream_chat(
    base_url: str, model: str, history: list[dict],
    on_token: Callable, on_done: Callable, on_error: Callable,
) -> None:
    def _run() -> None:
        try:
            resp = requests.post(
                f"{base_url}/api/chat",
                json={"model": model, "messages": history, "stream": True},
                stream=True, timeout=TIMEOUT,
            )
            resp.raise_for_status()
            full: list[str] = []
            for line in resp.iter_lines():
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                except json.JSONDecodeError as exc:
                    log.warning("stream bad line: %s", exc)
                    continue
                token = chunk.get("message", {}).get("content", "")
                if token:
                    full.append(token)
                    on_token(token)
                if chunk.get("done"):
                    break
            on_done("".join(full))
        except requests.exceptions.ConnectionError:
            on_error(f"Cannot reach {base_url}")
        except Exception as exc:
            on_error(f"Error: {exc}")
    threading.Thread(target=_run, daemon=True).start()


def stream_chat_openai(
    base_url: str, model: str, api_key: str, history: list[dict],
    on_token: Callable, on_done: Callable, on_error: Callable,
) -> None:
    def _run() -> None:
        try:
            resp = requests.post(
                f"{base_url}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}", "Accept": "text/event-stream"},
                json={"model": model, "messages": history,
                      "max_tokens": 16384, "temperature": 1.0, "top_p": 1.0, "stream": True},
                stream=True, timeout=TIMEOUT,
            )
            resp.raise_for_status()
            full: list[str] = []
            for line in resp.iter_lines():
                if not line:
                    continue
                raw = line.decode("utf-8") if isinstance(line, bytes) else line
                if not raw.startswith("data:"):
                    continue
                payload_str = raw[5:].strip()
                if payload_str == "[DONE]":
                    break
                try:
                    chunk = json.loads(payload_str)
                except json.JSONDecodeError as exc:
                    log.warning("openai stream bad line: %s", exc)
                    continue
                token = chunk.get("choices", [{}])[0].get("delta", {}).get("content", "")
                if token:
                    full.append(token)
                    on_token(token)
            on_done("".join(full))
        except requests.exceptions.HTTPError as exc:
            status = exc.response.status_code if exc.response is not None else "?"
            if status == 401:
                on_error("NVIDIA API: unauthorized — set NVIDIA_API_KEY env var")
            elif status == 402:
                on_error("NVIDIA API: quota exceeded / payment required")
            else:
                body = ""
                if exc.response is not None:
                    try:
                        body = exc.response.json().get("detail", exc.response.text[:120])
                    except Exception as parse_exc:
                        log.debug("response body parse failed: %s", parse_exc)
                        body = exc.response.text[:120]
                on_error(f"Cloud API HTTP {status}: {body}")
        except requests.exceptions.ConnectionError:
            on_error(f"Cannot reach {base_url}")
        except Exception as exc:
            on_error(f"Cloud API error: {exc}")
    threading.Thread(target=_run, daemon=True).start()


# ── Image attachments ─────────────────────────────────────────────────────────

def encode_image_file(path: str) -> str:
    """Read an image file → base64 string (no data: prefix). Raises OSError on failure."""
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("ascii")


def build_user_message(text: str, image_paths: list[str], provider: str) -> dict:
    """Build a {role:'user', ...} chat message carrying optional images.

    provider: 'ollama' (images list) or 'openai' (multimodal content list).
    Unreadable images are skipped and logged; the text turn always survives.
    """
    if not image_paths:
        return {"role": "user", "content": text}

    encoded: list[tuple[str, str]] = []
    for p in image_paths:
        try:
            encoded.append((p, encode_image_file(p)))
        except OSError as exc:
            log.warning("could not read image %s: %s", p, exc)

    if not encoded:
        return {"role": "user", "content": text}

    if provider == "openai":
        parts: list[dict] = [{"type": "text", "text": text}]
        for p, b64 in encoded:
            ext = Path(p).suffix.lstrip(".").lower() or "png"
            mime = "jpeg" if ext in ("jpg", "jpeg") else ext
            parts.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/{mime};base64,{b64}"},
            })
        return {"role": "user", "content": parts}

    # ollama native format
    return {"role": "user", "content": text, "images": [b64 for _, b64 in encoded]}


def launch_mrcoder() -> None:
    if not os.path.exists(MRCODER_BAT):
        log.warning("MrCoder bat not found: %s", MRCODER_BAT)
        return
    try:
        subprocess.Popen(
            ["cmd", "/c", "start", "", MRCODER_BAT],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            cwd=os.path.dirname(MRCODER_BAT),
        )
    except OSError as exc:
        log.error("MrCoder launch failed: %s", exc)


# ── UI ────────────────────────────────────────────────────────────────────────

class ChatBubble(ctk.CTkFrame):
    def __init__(self, parent, text: str, role: str, source: str = "", **kw):
        bg = "#1a3060" if role == "user" else "#131325"
        super().__init__(parent, fg_color=bg, corner_radius=10, border_width=1,
                         border_color="#2a2a50" if role != "user" else "#2a4080", **kw)
        prefix = "You" if role == "user" else (f"AI  [{source}]" if source else "AI")
        self._label = ctk.CTkLabel(
            self, text=f"{prefix}\n{text}",
            wraplength=530, justify="left", anchor="w",
            text_color="#ddeeff" if role == "user" else "#aaddaa",
            font=ctk.CTkFont(size=13),
        )
        self._label.pack(padx=12, pady=8, anchor="w")

    def append(self, token: str) -> None:
        current = self._label.cget("text")
        # text is "prefix\ncontent" — append to content part
        self._label.configure(text=current + token)


class HailoChatApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.configure(fg_color=C_BG)
        self.title("HailoChat")
        self.geometry("860x740")
        self.minsize(600, 540)
        self.history: list[dict] = []
        self._active_bubble: ChatBubble | None = None
        self._busy = False
        self._pending_images: list[str] = []
        self._model_keys: dict[str, str] = {}
        self._model_kinds: dict[str, dict] = {}
        self._ep_models: dict[str, list[str]] = {}
        self._endpoints, self._cloud_providers = load_endpoints()
        self._ep_chips: dict[str, ctk.CTkButton] = {}
        self._godseek_state: dict = load_godseek_state()
        self._build_ui()
        self._refresh_models()

    # ── Build UI ─────────────────────────────────────────────────────────────

    def _build_ui(self) -> None:
        # ── Title bar ────────────────────────────────────────────────────────
        title_bar = ctk.CTkFrame(self, height=38, corner_radius=0, fg_color=C_BAR)
        title_bar.pack(fill="x")
        title_bar.pack_propagate(False)

        ctk.CTkLabel(
            title_bar, text="  ◈  HailoChat",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#8899cc",
        ).pack(side="left", padx=8, pady=6)

        self._status = ctk.CTkLabel(
            title_bar, text="Probing…",
            font=ctk.CTkFont(size=11), text_color=C_MUTED,
        )
        self._status.pack(side="left", padx=12, pady=6)

        # Right-side buttons
        for text, color, hover, cmd in [
            ("🧑‍💻 MrCoder", "#442200", "#774422", launch_mrcoder),
            ("🔍 GodSeek",  "#2a0a0a", "#441111", self._open_godseek),
            ("⟳",           "#1a1a30", "#2a2a50", self._refresh_models),
            ("✕ Clear",      "#1a1a30", "#2a2a50", self._clear_chat),
            ("📖",           "#1a1a30", "#2a2a50", self._open_tutorial),
            ("⚙ Manage",     "#181830", "#282848", self._open_manage),
        ]:
            ctk.CTkButton(
                title_bar, text=text, width=len(text) * 8 + 16, height=26,
                fg_color=color, hover_color=hover,
                font=ctk.CTkFont(size=11), corner_radius=4,
                command=cmd,
            ).pack(side="right", padx=3, pady=6)

        # ── Model selector bar ────────────────────────────────────────────────
        model_bar = ctk.CTkFrame(self, height=40, corner_radius=0, fg_color=C_SURFACE)
        model_bar.pack(fill="x")
        model_bar.pack_propagate(False)

        ctk.CTkLabel(
            model_bar, text="Model:", font=ctk.CTkFont(size=11),
            text_color=C_MUTED, width=50,
        ).pack(side="left", padx=(12, 4), pady=8)

        self._model_var = ctk.StringVar(value=_PROBING)
        self._model_var.trace_add("write", self._on_model_changed)
        self._model_menu = ctk.CTkOptionMenu(
            model_bar, variable=self._model_var,
            values=[_PROBING], width=500,
            fg_color="#0f0f22", button_color="#1a1a38", button_hover_color="#252548",
            dropdown_fg_color="#0d0d1e", dropdown_hover_color="#1a1a38",
            text_color=C_TEXT, dropdown_text_color=C_TEXT,
            font=ctk.CTkFont(size=12),
        )
        self._model_menu.pack(side="left", padx=4, pady=6, fill="x", expand=True)

        # ── Endpoint chips bar ────────────────────────────────────────────────
        self._ep_bar = ctk.CTkFrame(self, height=32, corner_radius=0, fg_color=C_BAR)
        self._ep_bar.pack(fill="x")
        self._ep_bar.pack_propagate(False)

        ctk.CTkLabel(
            self._ep_bar, text="  Endpoints:",
            font=ctk.CTkFont(size=10), text_color=C_DIM,
        ).pack(side="left", padx=(6, 2), pady=5)

        self._chip_container = ctk.CTkFrame(self._ep_bar, fg_color="transparent")
        self._chip_container.pack(side="left", fill="x", expand=True)
        self._ep_chips = {}
        self._build_ep_chips()

        # ── Model info strip ──────────────────────────────────────────────────
        info_bar = ctk.CTkFrame(self, height=46, corner_radius=0, fg_color=C_SURFACE2)
        info_bar.pack(fill="x")
        info_bar.pack_propagate(False)

        self._tier_accent = ctk.CTkFrame(info_bar, width=4, corner_radius=0, fg_color=C_DIM)
        self._tier_accent.pack(side="left", fill="y", padx=(0, 8))

        self._badge_lbl = ctk.CTkLabel(
            info_bar, text="", font=ctk.CTkFont(size=12, weight="bold"),
            text_color=C_MUTED, width=130, anchor="w",
        )
        self._badge_lbl.pack(side="left", pady=4)

        self._device_tag = ctk.CTkLabel(
            info_bar, text="", font=ctk.CTkFont(size=10),
            fg_color="#1a1a38", corner_radius=4,
            text_color=C_MUTED, padx=8, pady=2,
        )
        self._device_tag.pack(side="left", padx=(0, 10), pady=10)

        self._bestfor_lbl = ctk.CTkLabel(
            info_bar, text="", font=ctk.CTkFont(size=11),
            text_color="#aabbcc", justify="left", anchor="w",
        )
        self._bestfor_lbl.pack(side="left", padx=0, pady=4, fill="x", expand=True)

        self._gs_verdict_tag = ctk.CTkLabel(
            info_bar, text="", font=ctk.CTkFont(size=10),
            fg_color="transparent", corner_radius=4,
            text_color=C_DIM, padx=8, pady=2,
        )
        self._gs_verdict_tag.pack(side="right", padx=(0, 8), pady=10)

        # ── GodSeek tip strip ─────────────────────────────────────────────────
        gs_bar = ctk.CTkFrame(self, height=28, corner_radius=0, fg_color="#0a0510")
        gs_bar.pack(fill="x")
        gs_bar.pack_propagate(False)

        ctk.CTkLabel(
            gs_bar, text="  🔍",
            font=ctk.CTkFont(size=10), text_color="#551133",
        ).pack(side="left", padx=(8, 2))
        self._gs_tip_lbl = ctk.CTkLabel(
            gs_bar, text="", font=ctk.CTkFont(size=10),
            text_color="#664455", justify="left", anchor="w",
        )
        self._gs_tip_lbl.pack(side="left", padx=4, pady=4, fill="x", expand=True)

        # ── Prompt tip strip ─────────────────────────────────────────────────
        tip_bar = ctk.CTkFrame(self, height=28, corner_radius=0, fg_color=C_BAR)
        tip_bar.pack(fill="x")
        tip_bar.pack_propagate(False)

        ctk.CTkLabel(
            tip_bar, text="  💡",
            font=ctk.CTkFont(size=10), text_color="#555566",
        ).pack(side="left", padx=(8, 2))
        self._tip_lbl = ctk.CTkLabel(
            tip_bar, text="", font=ctk.CTkFont(size=10),
            text_color="#55557a", justify="left", anchor="w",
        )
        self._tip_lbl.pack(side="left", padx=4, pady=4, fill="x", expand=True)

        # ── Chat area ────────────────────────────────────────────────────────
        self._scroll = ctk.CTkScrollableFrame(self, fg_color=C_BG, scrollbar_button_color=C_DIM)
        self._scroll.pack(fill="both", expand=True, padx=0, pady=0)

        # ── Divider ──────────────────────────────────────────────────────────
        ctk.CTkFrame(self, height=1, corner_radius=0, fg_color=C_BORDER).pack(fill="x")

        # ── Input row ────────────────────────────────────────────────────────
        bottom = ctk.CTkFrame(self, corner_radius=0, height=58, fg_color=C_SURFACE)
        bottom.pack(fill="x")
        bottom.grid_columnconfigure(0, weight=1)

        self._entry = ctk.CTkEntry(
            bottom, placeholder_text="Type a message and press Enter…",
            font=ctk.CTkFont(size=13), height=38,
            fg_color="#0a0a1a", border_color=C_BORDER, text_color=C_TEXT,
            placeholder_text_color=C_DIM,
        )
        self._entry.grid(row=0, column=0, sticky="ew", padx=(10, 6), pady=10)
        self._entry.bind("<Return>", lambda _: self._send())

        self._attach_btn = ctk.CTkButton(
            bottom, text="📎", width=44, height=38,
            fg_color="#1a1a30", hover_color="#2a2a50",
            font=ctk.CTkFont(size=15),
            corner_radius=6, command=self._attach_images,
        )
        self._attach_btn.grid(row=0, column=1, padx=(0, 6), pady=10)

        self._send_btn = ctk.CTkButton(
            bottom, text="Send  ➤", width=90, height=38,
            fg_color="#1a3066", hover_color="#2a4488",
            font=ctk.CTkFont(size=12, weight="bold"),
            corner_radius=6, command=self._send,
        )
        self._send_btn.grid(row=0, column=2, padx=(0, 10), pady=10)

    # ── Endpoint chips ────────────────────────────────────────────────────────

    def _build_ep_chips(self) -> None:
        for w in self._chip_container.winfo_children():
            w.destroy()
        self._ep_chips.clear()

        for label, url in self._endpoints:
            self._make_ollama_chip(label, url, state="probing")

        for p in self._cloud_providers:
            lbl = p.get("label", "Cloud")
            has_key = bool(os.environ.get(p.get("api_key_env", ""), ""))
            self._make_cloud_chip(lbl, has_key)

    def _make_ollama_chip(self, label: str, url: str, state: str = "probing") -> ctk.CTkButton:
        colors = {
            "probing": ("#1a1a38", "#8888bb"),
            "ok":      ("#0d2218", "#44cc77"),
            "fail":    ("#2a0d0d", "#cc4444"),
        }
        bg, fg = colors.get(state, colors["probing"])
        text = f" {label} ··· " if state == "probing" else f" {label} "
        btn = ctk.CTkButton(
            self._chip_container,
            text=text,
            width=1, height=22,
            fg_color=bg, hover_color="#2a2a55",
            text_color=fg,
            font=ctk.CTkFont(size=10),
            corner_radius=11,
            command=lambda l=label, u=url: self._reprobe_one_ollama(l, u),
        )
        btn.pack(side="left", padx=(0, 5), pady=5)
        self._ep_chips[label] = btn
        return btn

    def _make_cloud_chip(self, label: str, has_key: bool) -> ctk.CTkButton:
        bg = "#0d2218" if has_key else "#2a2208"
        fg = "#44cc77" if has_key else "#bbaa44"
        text = f" ☁ {label} "
        btn = ctk.CTkButton(
            self._chip_container,
            text=text,
            width=1, height=22,
            fg_color=bg, hover_color="#1a3a1a" if has_key else "#2a2a10",
            text_color=fg,
            font=ctk.CTkFont(size=10),
            corner_radius=11,
            command=lambda l=label: self._reprobe_one_cloud(l),
        )
        btn.pack(side="left", padx=(0, 5), pady=5)
        self._ep_chips[label] = btn
        return btn

    def _update_ep_chip(self, label: str, model_count: int, reachable: bool) -> None:
        btn = self._ep_chips.get(label)
        if btn is None:
            return
        if reachable:
            btn.configure(
                text=f" {label}  ● {model_count} ",
                fg_color="#0d2218", text_color="#44cc77",
            )
        else:
            btn.configure(
                text=f" {label}  ✗ ",
                fg_color="#2a0d0d", text_color="#cc4444",
            )

    # ── Per-endpoint restart ──────────────────────────────────────────────────

    def _reprobe_one_ollama(self, label: str, url: str) -> None:
        btn = self._ep_chips.get(label)
        if btn:
            btn.configure(text=f" {label} ⟳ ", fg_color="#1a1a38", text_color="#8888ff")

        def _do() -> None:
            keys = probe_endpoint(label, url)
            self.after(0, self._apply_ep_result, label, url, keys, is_cloud=False)

        threading.Thread(target=_do, daemon=True).start()

    def _reprobe_one_cloud(self, label: str) -> None:
        provider = next((p for p in self._cloud_providers if p.get("label") == label), None)
        if provider is None:
            return
        btn = self._ep_chips.get(label)
        if btn:
            btn.configure(text=f" ☁ {label} ⟳ ", fg_color="#1a2a1a", text_color="#88ccaa")

        keys = probe_cloud_providers([provider])
        has_key = bool(os.environ.get(provider.get("api_key_env", ""), ""))

        def _do() -> None:
            self.after(0, self._apply_ep_result, label, None, keys, True, has_key)

        threading.Thread(target=_do, daemon=True).start()

    def _apply_ep_result(
        self, label: str, url: str | None,
        keys: list[str], is_cloud: bool, has_key: bool = True,
    ) -> None:
        # Update chip appearance
        if is_cloud:
            btn = self._ep_chips.get(label)
            if btn:
                bg = "#0d2218" if has_key else "#2a2208"
                fg = "#44cc77" if has_key else "#bbaa44"
                btn.configure(text=f" ☁ {label} ", fg_color=bg, text_color=fg)
        else:
            reachable = bool(keys)
            self._update_ep_chip(label, len(keys), reachable)

        # Update ep_models for this endpoint
        self._ep_models[label] = keys

        # Re-read API keys for cloud models
        cloud_urls = {p["url"] for p in self._cloud_providers}
        for key in keys:
            dn = display_name(key)
            _, base_url, _ = parse_key(key)
            if base_url in cloud_urls:
                provider = next((p for p in self._cloud_providers if p["url"] == base_url), {})
                api_key = os.environ.get(provider.get("api_key_env", ""), "")
                self._model_kinds[dn] = {"kind": "openai", "api_key": api_key}
            else:
                self._model_kinds[dn] = {"kind": "ollama", "api_key": ""}

        self._rebuild_dropdown()

        status_ok = sum(1 for k_list in self._ep_models.values() if k_list)
        total = sum(len(v) for v in self._ep_models.values())
        if total:
            self._status.configure(
                text=f"● {total} models  ({status_ok}/{len(self._ep_models)} endpoints)",
                text_color="#6fcf6f",
            )

    def _rebuild_dropdown(self) -> None:
        prev_dn = self._model_var.get()

        # Flatten in endpoint insertion order
        all_keys: list[str] = []
        for label, _ in self._endpoints:
            all_keys.extend(self._ep_models.get(label, []))
        for p in self._cloud_providers:
            lbl = p.get("label", "Cloud")
            all_keys.extend(self._ep_models.get(lbl, []))

        if not all_keys:
            self._model_menu.configure(values=["(none)"])
            self._model_var.set("(none)")
            return

        # Rebuild model_keys
        self._model_keys.clear()
        display_list: list[str] = []
        for key in all_keys:
            dn = display_name(key)
            self._model_keys[dn] = key
            display_list.append(dn)

        self._model_menu.configure(values=display_list)
        if prev_dn in display_list:
            self._model_var.set(prev_dn)
        else:
            self._model_var.set(display_list[0])

    # ── Full refresh ─────────────────────────────────────────────────────────

    def _refresh_models(self) -> None:
        self._status.configure(text="Probing…", text_color=C_MUTED)
        self._model_menu.configure(values=[_PROBING])
        self._model_var.set(_PROBING)
        self._model_keys.clear()
        self._model_kinds.clear()
        self._ep_models.clear()
        self._build_ep_chips()

        cloud_urls = {p["url"] for p in self._cloud_providers}

        # Seed cloud models immediately
        for p in self._cloud_providers:
            lbl = p.get("label", "Cloud")
            keys = probe_cloud_providers([p])
            self._ep_models[lbl] = keys
            api_key = os.environ.get(p.get("api_key_env", ""), "")
            for key in keys:
                dn = display_name(key)
                self._model_kinds[dn] = {"kind": "openai", "api_key": api_key}

        def _on_done(entries: list[str]) -> None:
            # entries contains merged ollama + cloud — only process ollama part here
            ollama_entries = [e for e in entries if parse_key(e)[1] not in cloud_urls]

            # Group by label
            per_label: dict[str, list[str]] = {}
            for key in ollama_entries:
                lbl = parse_key(key)[0]
                per_label.setdefault(lbl, []).append(key)

            for lbl, keys in per_label.items():
                self._ep_models[lbl] = keys
                for key in keys:
                    dn = display_name(key)
                    self._model_kinds[dn] = {"kind": "ollama", "api_key": ""}

            self._rebuild_dropdown()

            all_labels = sorted(self._ep_models.keys())
            total = sum(len(v) for v in self._ep_models.values())
            self._status.configure(
                text=f"● {total} models  ({', '.join(all_labels)})",
                text_color="#55cc77",
            )

        def _per_ep(lbl: str, url: str, entries: list[str]) -> None:
            self._update_ep_chip(lbl, len(entries), bool(entries))

        cloud_keys = list(self._ep_models.get(p.get("label", "Cloud"), [])
                         for p in self._cloud_providers)
        flat_cloud = [k for kl in cloud_keys for k in kl]

        probe_all(
            self._endpoints,
            lambda entries: self.after(0, _on_done, entries),
            per_ep_callback=lambda l, u, e: self.after(0, _per_ep, l, u, e),
            extra_keys=flat_cloud,
        )

    # ── Model info strip ──────────────────────────────────────────────────────

    def _on_model_changed(self, *_) -> None:
        dn = self._model_var.get()
        key = self._model_keys.get(dn)
        if not key:
            self._badge_lbl.configure(text="", text_color=C_MUTED)
            self._bestfor_lbl.configure(text="")
            self._tip_lbl.configure(text="")
            self._gs_tip_lbl.configure(text="")
            self._gs_verdict_tag.configure(text="", fg_color="transparent")
            self._device_tag.configure(text="", fg_color="transparent")
            self._tier_accent.configure(fg_color=C_DIM)
            return
        label, _, model = parse_key(key)
        meta = classify_model(model)
        color = TIER_COLORS.get(meta.get("tier", "general"), C_MUTED)
        self._tier_accent.configure(fg_color=color)
        self._badge_lbl.configure(text=meta["badge"], text_color=color)
        self._device_tag.configure(text=f" {label} ", fg_color=C_SURFACE, text_color=C_MUTED)
        self._bestfor_lbl.configure(text=meta["best_for"], text_color="#aabbcc")
        self._tip_lbl.configure(text=meta["prompt_tip"])

        # ── GodSeek verdict ───────────────────────────────────────────────────
        gs_record = get_godseek_verdict(model, self._godseek_state)
        gs_tip = godseek_recommendation(model, gs_record)
        self._gs_tip_lbl.configure(text=gs_tip)
        if gs_record:
            verdict = gs_record.get("verdict", "?")
            gs_color, gs_short = _GS_COLORS.get(verdict, ("#666688", verdict))
            pct = gs_record.get("pct", 0)
            self._gs_verdict_tag.configure(
                text=f"{gs_short}  {pct}%",
                fg_color="#1a0010",
                text_color=gs_color,
            )
        else:
            self._gs_verdict_tag.configure(
                text="🔍 untested",
                fg_color="#1a0a00",
                text_color="#886633",
            )

    # ── Chat ──────────────────────────────────────────────────────────────────

    def _attach_images(self) -> None:
        paths = filedialog.askopenfilenames(
            title="Attach image(s)",
            filetypes=[
                ("Images", "*.png *.jpg *.jpeg *.gif *.bmp *.webp"),
                ("All files", "*.*"),
            ],
        )
        if not paths:
            return
        self._pending_images.extend(paths)
        n = len(self._pending_images)
        self._attach_btn.configure(text=f"📎 {n}")
        self._status.configure(
            text=f"{n} image{'s' if n != 1 else ''} attached — pick a 👁 vision model",
            text_color="#44bbcc",
        )

    def _clear_pending_images(self) -> None:
        self._pending_images.clear()
        self._attach_btn.configure(text="📎")

    def _send(self) -> None:
        if self._busy:
            return
        text = self._entry.get().strip()
        if not text and not self._pending_images:
            return

        dn = self._model_var.get()
        key = self._model_keys.get(dn)
        if not key:
            self._status.configure(text="Select a model first", text_color="#ccaa44")
            return

        label, base_url, model = parse_key(key)
        kind_info = self._model_kinds.get(dn, {"kind": "ollama", "api_key": ""})

        # Validate the API key BEFORE consuming the entry or attachments, so a
        # missing key never silently eats the user's typed text or images.
        api_key = ""
        if kind_info["kind"] == "openai":
            api_key = kind_info["api_key"]
            if not api_key:
                meta = next((p for p in self._cloud_providers if p["url"] == base_url), {})
                env_var = meta.get("api_key_env", "NVIDIA_API_KEY")
                self._status.configure(
                    text=f"No API key — set {env_var} environment variable",
                    text_color="#cc5555",
                )
                return

        provider = "openai" if kind_info["kind"] == "openai" else "ollama"
        images = list(self._pending_images)

        try:
            user_msg = build_user_message(text, images, provider)
        except Exception as exc:
            # Encoding failed (e.g. bad path bytes) — keep images so user can retry.
            log.error("could not build message with images: %s", exc)
            self._status.configure(text=f"Could not attach image: {exc}", text_color="#cc5555")
            return

        # Detect the all-images-unreadable case so the bubble doesn't lie.
        content = user_msg.get("content")
        attached_ok = bool(user_msg.get("images")) or (
            isinstance(content, list) and any(
                isinstance(p, dict) and p.get("type") == "image_url" for p in content
            )
        )
        if images and not attached_ok:
            self._status.configure(
                text="Image(s) unreadable — sending text only", text_color="#ccaa44"
            )

        # Committed to sending — now consume entry + attachments.
        self._entry.delete(0, "end")
        self._clear_pending_images()
        self.history.append(user_msg)
        bubble_text = text if text else "(image)"
        if images and attached_ok:
            n = len(images)
            bubble_text += f"\n📎 {n} image{'s' if n != 1 else ''} attached"
        self._add_bubble(bubble_text, "user")
        self._active_bubble = self._add_bubble("", "assistant", source=f"{label} · {model}")
        self._busy = True
        self._send_btn.configure(state="disabled", text="…")

        if kind_info["kind"] == "openai":
            stream_chat_openai(
                base_url=base_url, model=model, api_key=api_key,
                history=list(self.history),
                on_token=lambda tok: self.after(0, self._on_token, tok),
                on_done=lambda full: self.after(0, self._on_done, full),
                on_error=lambda err: self.after(0, self._on_error, err),
            )
        else:
            stream_chat(
                base_url=base_url, model=model,
                history=list(self.history),
                on_token=lambda tok: self.after(0, self._on_token, tok),
                on_done=lambda full: self.after(0, self._on_done, full),
                on_error=lambda err: self.after(0, self._on_error, err),
            )

    def _add_bubble(self, text: str, role: str, source: str = "") -> ChatBubble:
        bubble = ChatBubble(self._scroll, text, role, source=source)
        bubble.pack(fill="x", padx=8, pady=4, anchor="w")
        self._scroll._parent_canvas.yview_moveto(1.0)
        return bubble

    def _on_token(self, token: str) -> None:
        if self._active_bubble:
            self._active_bubble.append(token)
            self._scroll._parent_canvas.yview_moveto(1.0)

    def _on_done(self, full_text: str) -> None:
        self.history.append({"role": "assistant", "content": full_text})
        self._active_bubble = None
        self._busy = False
        self._send_btn.configure(state="normal", text="Send  ➤")

    def _on_error(self, msg: str) -> None:
        if self._active_bubble:
            self._active_bubble.append(f"\n⚠  {msg}")
        self._active_bubble = None
        self._busy = False
        self._send_btn.configure(state="normal", text="Send  ➤")
        # Remove orphaned user turn so next message doesn't send a broken 1-sided history
        if self.history and self.history[-1]["role"] == "user":
            self.history.pop()
        log.error("chat error: %s", msg)

    def _clear_chat(self) -> None:
        for w in self._scroll.winfo_children():
            w.destroy()
        self.history.clear()

    # ── Dialogs ───────────────────────────────────────────────────────────────

    def _open_manage(self) -> None:
        dlg = _EndpointDialog(self, self._endpoints, self._cloud_providers)
        self.wait_window(dlg)
        if dlg.result is not None:
            new_ollama, new_cloud = dlg.result
            if new_ollama != self._endpoints or new_cloud != self._cloud_providers:
                self._endpoints = new_ollama
                self._cloud_providers = new_cloud
                save_endpoints(self._endpoints, self._cloud_providers)
                self._refresh_models()

    def _open_tutorial(self) -> None:
        TutorialDialog(self)

    def _open_godseek(self) -> None:
        dn = self._model_var.get()
        key = self._model_keys.get(dn)
        current_model = parse_key(key)[2] if key else ""
        _GodSeekDialog(self, current_model, self._godseek_state)


# ── Endpoint management dialog ────────────────────────────────────────────────

class _EndpointDialog(ctk.CTkToplevel):
    def __init__(self, parent, endpoints: list[tuple[str, str]], cloud_providers: list[dict]):
        super().__init__(parent)
        self.configure(fg_color=C_BG)
        self.title("Manage Endpoints")
        self.geometry("580x580")
        self.resizable(False, True)
        self.grab_set()
        self.result: tuple[list, list] | None = None
        self._eps = list(endpoints)
        self._cloud = [dict(p) for p in cloud_providers]
        self._build()

    def _build(self) -> None:
        ctk.CTkLabel(
            self, text="Ollama Endpoints",
            font=ctk.CTkFont(size=12, weight="bold"), text_color="#8899cc",
        ).pack(pady=(14, 3), padx=14, anchor="w")

        self._ollama_frame = ctk.CTkScrollableFrame(self, height=145, fg_color=C_SURFACE)
        self._ollama_frame.pack(fill="x", padx=14)
        self._ollama_rows: list[tuple[ctk.CTkEntry, ctk.CTkEntry]] = []
        for lbl, url in self._eps:
            self._add_ollama_row(lbl, url)

        r1 = ctk.CTkFrame(self, fg_color="transparent")
        r1.pack(fill="x", padx=14, pady=5)
        ctk.CTkButton(r1, text="＋ Add Ollama", width=110, height=28,
                      fg_color="#1a1a38", hover_color="#2a2a55",
                      command=lambda: self._add_ollama_row("", "http://")).pack(side="left", padx=3)
        ctk.CTkButton(r1, text="⚡ Test all", width=90, height=28,
                      fg_color="#1a2a1a", hover_color="#2a3a2a",
                      command=self._test_ollama).pack(side="left", padx=3)
        self._test_lbl = ctk.CTkLabel(r1, text="", font=ctk.CTkFont(size=10), text_color=C_MUTED)
        self._test_lbl.pack(side="left", padx=8)

        ctk.CTkFrame(self, height=1, fg_color=C_BORDER).pack(fill="x", padx=14, pady=10)

        ctk.CTkLabel(
            self, text="☁  Cloud Providers",
            font=ctk.CTkFont(size=12, weight="bold"), text_color="#22ccaa",
        ).pack(pady=(0, 2), padx=14, anchor="w")
        ctk.CTkLabel(
            self, text="  label  |  API URL  |  Key Env Var  |  model1,model2",
            font=ctk.CTkFont(size=10), text_color=C_DIM,
        ).pack(pady=(0, 4), padx=14, anchor="w")

        self._cloud_frame = ctk.CTkScrollableFrame(self, height=130, fg_color=C_SURFACE)
        self._cloud_frame.pack(fill="x", padx=14)
        self._cloud_rows: list[tuple] = []
        for p in self._cloud:
            self._add_cloud_row(p.get("label",""), p.get("url",""),
                                p.get("api_key_env",""), ",".join(p.get("models",[])))

        r2 = ctk.CTkFrame(self, fg_color="transparent")
        r2.pack(fill="x", padx=14, pady=5)
        ctk.CTkButton(r2, text="＋ Add Cloud", width=110, height=28,
                      fg_color="#0d2218", hover_color="#1a3a28",
                      command=lambda: self._add_cloud_row("","https://","","")).pack(side="left", padx=3)

        ctk.CTkFrame(self, height=1, fg_color=C_BORDER).pack(fill="x", padx=14, pady=8)
        foot = ctk.CTkFrame(self, fg_color="transparent")
        foot.pack(fill="x", padx=14, pady=(0, 14))
        ctk.CTkButton(foot, text="Save & Close", width=120, height=32,
                      fg_color="#1a3066", hover_color="#2a4488",
                      command=self._save).pack(side="right", padx=4)
        ctk.CTkButton(foot, text="Cancel", width=90, height=32,
                      fg_color="#2a1a1a", hover_color="#3a2a2a",
                      command=self.destroy).pack(side="right", padx=4)

    def _add_ollama_row(self, label: str, url: str) -> None:
        row = ctk.CTkFrame(self._ollama_frame, fg_color="transparent")
        row.pack(fill="x", pady=2)
        lbl_e = ctk.CTkEntry(row, placeholder_text="Label", width=88, height=28,
                              fg_color="#0a0a1a", border_color=C_BORDER)
        lbl_e.insert(0, label)
        lbl_e.pack(side="left", padx=(0, 4))
        url_e = ctk.CTkEntry(row, placeholder_text="http://host:port", width=320, height=28,
                              fg_color="#0a0a1a", border_color=C_BORDER)
        url_e.insert(0, url)
        url_e.pack(side="left", padx=(0, 4))
        t = (lbl_e, url_e)
        ctk.CTkButton(row, text="✕", width=28, height=28,
                      fg_color="#2a0d0d", hover_color="#441111",
                      command=lambda r=row, t2=t: self._del_row(r, t2, self._ollama_rows)
                      ).pack(side="left")
        self._ollama_rows.append(t)

    def _add_cloud_row(self, label: str, url: str, key_env: str, models_str: str) -> None:
        row = ctk.CTkFrame(self._cloud_frame, fg_color="transparent")
        row.pack(fill="x", pady=2)
        for placeholder, val, w in [
            ("Label", label, 68), ("https://api.../v1", url, 155),
            ("ENV_VAR", key_env, 115), ("model1,model2", models_str, 120),
        ]:
            e = ctk.CTkEntry(row, placeholder_text=placeholder, width=w, height=26,
                             fg_color="#0a0a1a", border_color=C_BORDER)
            e.insert(0, val)
            e.pack(side="left", padx=(0, 3))
        entries = tuple(
            w for w in row.winfo_children() if isinstance(w, ctk.CTkEntry)
        )
        t = entries
        ctk.CTkButton(row, text="✕", width=26, height=26,
                      fg_color="#2a0d0d", hover_color="#441111",
                      command=lambda r=row, t2=t: self._del_row(r, t2, self._cloud_rows)
                      ).pack(side="left")
        self._cloud_rows.append(t)

    def _del_row(self, row_frame, entries, rows_list: list) -> None:
        row_frame.destroy()
        if entries in rows_list:
            rows_list.remove(entries)

    def _collect_ollama(self) -> list[tuple[str, str]]:
        result = []
        for lbl_e, url_e in self._ollama_rows:
            lbl, url = lbl_e.get().strip(), url_e.get().strip()
            if lbl and url and url.startswith(("http://", "https://")):
                result.append((lbl, url))
        return result

    def _collect_cloud(self) -> list[dict]:
        result = []
        for t in self._cloud_rows:
            vals = [e.get().strip() for e in t if isinstance(e, ctk.CTkEntry)]
            if len(vals) < 4:
                continue
            lbl, url, key_env, models_raw = vals[0], vals[1], vals[2], vals[3]
            if not lbl or not url:
                continue
            result.append({
                "label": lbl, "url": url, "kind": "openai",
                "api_key_env": key_env,
                "models": [m.strip() for m in models_raw.split(",") if m.strip()],
            })
        return result

    def _test_ollama(self) -> None:
        eps = self._collect_ollama()
        self._test_lbl.configure(text="Testing…", text_color=C_MUTED)
        counter = {"ok": 0, "fail": 0}

        def _run() -> None:
            for _, url in eps:
                try:
                    r = requests.get(f"{url}/api/tags", timeout=3)
                    counter["ok" if r.ok else "fail"] += 1
                    if not r.ok:
                        log.warning("test: %s → HTTP %s", url, r.status_code)
                except Exception as exc:
                    counter["fail"] += 1
                    log.warning("test: %s unreachable: %s", url, exc)
            self.after(0, _done)

        def _done() -> None:
            ok, fail = counter["ok"], counter["fail"]
            self._test_lbl.configure(
                text=f"✓{ok}  ✗{fail}",
                text_color="#44cc77" if fail == 0 else "#cc9944",
            )

        threading.Thread(target=_run, daemon=True).start()

    def _save(self) -> None:
        self.result = (self._collect_ollama(), self._collect_cloud())
        self.destroy()


# ── Tutorial ──────────────────────────────────────────────────────────────────

class TutorialDialog(ctk.CTkToplevel):
    def __init__(self, parent):
        super().__init__(parent)
        self.configure(fg_color=C_BG)
        self.title("HailoChat — Guide")
        self.geometry("760x600")
        self.minsize(620, 480)
        self.grab_set()

        self._tabview = ctk.CTkTabview(self, fg_color=C_SURFACE,
                                       segmented_button_fg_color=C_BAR,
                                       segmented_button_selected_color="#1a1a44",
                                       segmented_button_unselected_color=C_BAR)
        self._tabview.pack(fill="both", expand=True, padx=10, pady=10)

        self._boxes: dict[str, ctk.CTkTextbox] = {}
        for name in ["Welcome", "Endpoints", "Model Tiers", "Vision Server", "Ecosystem", "MrCoder"]:
            tab = self._tabview.tab(name)
            tb = ctk.CTkTextbox(tab, wrap="word", font=ctk.CTkFont(family="Consolas", size=12),
                                fg_color=C_SURFACE2, text_color="#c0c0e0", border_width=0)
            tb.pack(fill="both", expand=True, padx=6, pady=6)
            self._boxes[name] = tb

        self._populate_welcome()
        self._populate_endpoints()
        self._populate_model_tiers()
        self._populate_vision()
        self._populate_ecosystem()
        self._populate_mrcoder()

        foot = ctk.CTkFrame(self, fg_color="transparent")
        foot.pack(pady=(0, 10))
        ctk.CTkButton(foot, text="Close", width=100, height=30,
                      fg_color="#1a1a38", hover_color="#2a2a55",
                      command=self._on_close).pack()
        self.bind("<Escape>", lambda _: self._on_close())
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self._tabview.configure(command=self._on_tab_switch)

    def _on_close(self) -> None:
        try:
            self.grab_release()
        except Exception as exc:
            log.debug("grab_release: %s", exc)
        self.destroy()

    def _on_tab_switch(self) -> None:
        box = self._boxes.get(self._tabview.get())
        if box:
            box.yview_moveto(0.0)

    def _add_text(self, tab: str, text: str) -> None:
        tb = self._boxes[tab]
        tb.configure(state="normal")
        tb.delete("1.0", "end")
        tb.insert("1.0", text)
        tb.configure(state="disabled")

    def _populate_welcome(self) -> None:
        self._add_text("Welcome", """\
HAILOCHAT  —  Unified LLM Fleet + Cloud
════════════════════════════════════════

QUICK START
───────────
  1. Wait for the model list to load (green count in chips)
  2. Pick a model from the dropdown — device · emoji · model name
  3. Read the info strip below: tier badge, device tag, best-for hint
  4. Type your message and press Enter or Send ➤
  5. Watch the reply stream in live chat bubbles

ENDPOINT CHIPS  (click any chip to restart just that endpoint)
──────────────────────────────────────────────────────────────
  Green  ● N   = online, N models found
  Red    ✗     = unreachable
  Orange ···   = probing
  Teal   ☁    = cloud provider (key present)
  Yellow ☁    = cloud provider (no API key set)

  Click a chip at any time to re-probe that endpoint alone.
  The rest of the model list stays untouched.

MODEL TIERS
───────────
  ☁  CLOUD    (teal)    Cloud models via NVIDIA API
  🔓  CRACKED  (red)     Uncensored — no refusals
  💻  CODER    (blue)    Code generation, debugging
  🧠  THINKER  (purple)  DeepSeek-R1 reasoning
  👁  VISION   (teal)    Image analysis via Pi NPU
  ⚡  FAST     (yellow)  Tiny models, quick Q&A
  🌐  GENERAL  (grey)    All-round chat + writing
""")

    def _populate_endpoints(self) -> None:
        self._add_text("Endpoints", """\
ENDPOINTS — Ollama Fleet + Cloud Providers
══════════════════════════════════════════

OLLAMA FLEET (local network)
─────────────────────────────
  Label   URL                             Machine
  ──────  ──────────────────────────────  ──────────────────────
  Local   http://localhost:11434          This PC  RTX 5060 8GB
  Pi1     http://192.168.1.213:11434      Pi5  16GB RAM + SSD
  Pi1-H   http://192.168.1.213:11435      Pi5  second Ollama
  Pi2     http://192.168.1.221:11434      Pi3  Hailo-10H NPU

CLOUD PROVIDERS
───────────────
  Label   API URL                                   Key Env Var
  ──────  ────────────────────────────────────────  ──────────────────
  NVIDIA  https://integrate.api.nvidia.com/v1       NVIDIA_API_KEY

  Cloud providers inject models directly — no /api/tags probe.
  The ☁ chip shows green when NVIDIA_API_KEY is set in env.

SETTING UP THE API KEY
──────────────────────
  In your PowerShell profile  (~\\Documents\\WindowsPowerShell\\Microsoft.PowerShell_profile.ps1):
    $env:NVIDIA_API_KEY = "nvapi-YOUR-KEY-HERE"

  Or set permanently via:
    System Properties → Environment Variables → New user variable

  Restart HailoChat after setting the key.

PER-ENDPOINT RESTART
─────────────────────
  Click any chip in the endpoint bar to re-probe just that endpoint.
  Useful when a Pi went offline and came back — no need to full-refresh.
  ⚙ Manage → add, remove, or test endpoints and cloud providers.
""")

    def _populate_model_tiers(self) -> None:
        self._add_text("Model Tiers", f"""\
MODEL TIERS  —  Dropdown: device · emoji · model name
══════════════════════════════════════════════════════

The left-side accent bar colour in the info strip matches the tier.

──  ☁  CLOUD   ({TIER_COLORS['cloud']}) ─────────────────────────────────────
  Via NVIDIA API. Far larger than local fleet. Uses credits.
  • moonshotai/kimi-k2.6  1T MoE, 128K context, top reasoning
  Set NVIDIA_API_KEY — chip turns green, model appears in list.

──  🔓  CRACKED ({TIER_COLORS['uncensored']}) ─────────────────────────────────────
  Uncensored / Dolphin — no refusals, no jailbreak preamble.
  • dolphin, my-uncensored-ai, -cracked variants

──  💻  CODER   ({TIER_COLORS['coder']}) ─────────────────────────────────────
  Code gen, refactoring, debugging. Paste context first.
  • qwen2.5-coder — best in fleet
  • deepseek-coder-v2 — strongest for multi-file edits

──  🧠  THINKER ({TIER_COLORS['reasoning']}) ─────────────────────────────────────
  Multi-step reasoning, math, logic. Give room to think.
  • deepseek-r1 — flagship reasoning model

──  👁  VISION  ({TIER_COLORS['vision']}) ─────────────────────────────────────
  Pi-hosted vision models. Analyze images, OCR, screenshots.
  • llama3.2-vision, minicpm-v, llava

──  ⚡  FAST    ({TIER_COLORS['fast']}) ─────────────────────────────────────
  Tiny/fast for quick Q&A. Keep prompts short.
  • tinyllama, phi3, llama3.2:1b/3b

──  🌐  GENERAL ({TIER_COLORS['general']}) ─────────────────────────────────────
  Well-rounded chat, writing, summarization.
  • mistral-nemo (12B), gemma2/3/4, mistral, llama3.2
""")

    def _populate_vision(self) -> None:
        self._add_text("Vision Server", """\
HAILO VISION SERVER  —  YOLOv8m on Hailo-10H NPU
══════════════════════════════════════════════════

  Machine:   Pi5 at 192.168.1.213
  Hardware:  Hailo-10H (~13 TOPS)
  Model:     YOLOv8m  (COCO, 80 classes)
  Port:      8766
  Service:   hailo-vision.service (systemd, auto-restart)

API
───
  GET  /health    → {status, model, input_shape}
  POST /detect    → {detections: [{label, confidence, bbox}], count}
  POST /score     → {score: 0–1, detections, count}

  curl http://192.168.1.213:8766/health
  Input: base64 JPEG 640×640 as {"image": "<base64>"}

TOOLS USING IT
──────────────
  hailo_vision_capture.py  — screenshot → detect on this PC
  hailo_indexer.py         — index stock video library
""")

    def _populate_ecosystem(self) -> None:
        self._add_text("Ecosystem", """\
HAILO ECOSYSTEM
════════════════

  ┌──────────────────────────────────────────────────────┐
  │  THIS PC  (Windows)                                  │
  │  ┌────────────────────────────────────────────────┐  │
  │  │  HailoChat.exe           ← YOU ARE HERE        │  │
  │  │  NVIDIA Cloud API        Kimi K2.6              │  │
  │  │  Ollama localhost:11434  RTX 5060, 8GB VRAM     │  │
  │  └────────────────────────────────────────────────┘  │
  │              │  /api/chat                ↑ /detect   │
  │              ▼                                       │
  │  ┌────────────────────────────────────────────────┐  │
  │  │  Pi5  192.168.1.213  (16GB RAM + SSD)          │  │
  │  │  Ollama :11434  (llama3.2-vision, deepseek…)   │  │
  │  │  Ollama :11435  (second instance)              │  │
  │  │  Hailo-10H Vision :8766  (YOLOv8m NPU)         │  │
  │  └────────────────────────────────────────────────┘  │
  │              │  /api/chat                            │
  │              ▼                                       │
  │  ┌────────────────────────────────────────────────┐  │
  │  │  Pi3  192.168.1.221                            │  │
  │  │  Ollama :11434  (smaller models)               │  │
  │  └────────────────────────────────────────────────┘  │
  └──────────────────────────────────────────────────────┘

OTHER TOOLS
───────────
  MrCoder              — Architect → Coder batch pipeline (Aider)
  hailo_vision_capture — screenshot → detect on Pi
  hailo_indexer        — stock video library scan
""")

    def _populate_mrcoder(self) -> None:
        self._add_text("MrCoder", """\
MRCODER  —  Multi-Model Coding Pipeline
════════════════════════════════════════

  Click  🧑‍💻 MrCoder  in the title bar to open in a new console.

PIPELINES
─────────
  [1]  SOLO        Single model → Aider  (fastest)
  [2]  DUAL        Architect plans + Coder writes  (default)
  [3]  CHAIN       2–4 models in sequence, each refines output
  [4]  REVIEW      Coder → Reviewer loop
  [5]  LLM CONFIG  AI picks best setup for your task
  [6]  SELF-EDIT   MrCoder edits its own source

DUAL MODE
─────────
  1. Pick Architect  — uncensored model, no safety filters, plans freely
  2. Pick Editor     — coder model (qwen2.5-coder, deepseek-coder)
  3. Aider opens in your project with both models wired

TIPS
────
  • Ollama auto-starts if not running
  • Project folder is remembered between launches
  • Console stays open on errors so you can read them
""")


# ── GodSeek dialog ────────────────────────────────────────────────────────────

class _GodSeekDialog(ctk.CTkToplevel):
    """Shows GodSeek verdict for all tested models + recommendations.
    Lets user launch endless_godseek.py for the selected model."""

    def __init__(self, parent, current_model: str, state: dict):
        super().__init__(parent)
        self.configure(fg_color=C_BG)
        self.title("GodSeek — Model Safety Surface")
        self.geometry("780x560")
        self.minsize(600, 440)
        self.grab_set()
        self._state = state
        self._current = current_model
        self._build(current_model)

    def _build(self, current_model: str) -> None:
        # ── Header ─────────────────────────────────────────────────────────
        hdr = ctk.CTkFrame(self, height=44, corner_radius=0, fg_color="#0a0510")
        hdr.pack(fill="x")
        hdr.pack_propagate(False)
        ctk.CTkLabel(
            hdr, text="  🔍  GodSeek — Safety Surface Map",
            font=ctk.CTkFont(size=13, weight="bold"), text_color="#cc3355",
        ).pack(side="left", padx=12, pady=10)

        gs_count = len(self._state)
        ctk.CTkLabel(
            hdr, text=f"{gs_count} models tested",
            font=ctk.CTkFont(size=10), text_color="#664455",
        ).pack(side="left", padx=8)

        ctk.CTkButton(
            hdr, text="↺ Reload state", width=100, height=26,
            fg_color="#1a0a10", hover_color="#2a1a20",
            font=ctk.CTkFont(size=10),
            command=self._reload_state,
        ).pack(side="right", padx=8, pady=9)

        # ── Current model block ─────────────────────────────────────────────
        if current_model:
            rec = get_godseek_verdict(current_model, self._state)
            tip = godseek_recommendation(current_model, rec)
            verdict_str = rec.get("verdict", "UNTESTED") if rec else "UNTESTED"
            pct_str = f"  {rec.get('pct', 0)}%" if rec else ""
            gs_color, gs_short = _GS_COLORS.get(verdict_str, ("#886633", "❓ " + verdict_str))

            cur_frame = ctk.CTkFrame(self, corner_radius=0, fg_color="#0e0810",
                                     border_width=1, border_color="#2a1020")
            cur_frame.pack(fill="x", padx=0)
            ctk.CTkLabel(
                cur_frame,
                text=f"  Selected: {current_model}   {gs_short}{pct_str}",
                font=ctk.CTkFont(size=11, weight="bold"), text_color=gs_color, anchor="w",
            ).pack(fill="x", padx=12, pady=(8, 0))
            ctk.CTkLabel(
                cur_frame, text=f"  {tip}",
                font=ctk.CTkFont(size=10), text_color="#886677", anchor="w", wraplength=720,
            ).pack(fill="x", padx=12, pady=(2, 8))

            ctk.CTkButton(
                cur_frame,
                text=f"▶ Launch GodSeek on  {current_model}",
                height=30, width=340,
                fg_color="#330011", hover_color="#550022",
                text_color="#ff6688",
                font=ctk.CTkFont(size=11),
                command=lambda m=current_model: self._launch(m),
            ).pack(side="left", padx=12, pady=(0, 10))

        ctk.CTkFrame(self, height=1, corner_radius=0, fg_color="#2a1020").pack(fill="x")

        # ── All tested models table ─────────────────────────────────────────
        ctk.CTkLabel(
            self, text="  All tested models",
            font=ctk.CTkFont(size=11, weight="bold"), text_color="#664455",
        ).pack(anchor="w", padx=12, pady=(8, 2))

        table_frame = ctk.CTkScrollableFrame(self, fg_color=C_SURFACE, height=280)
        table_frame.pack(fill="both", expand=True, padx=8, pady=(0, 8))
        table_frame.grid_columnconfigure(0, weight=3)
        table_frame.grid_columnconfigure(1, weight=1)
        table_frame.grid_columnconfigure(2, weight=1)
        table_frame.grid_columnconfigure(3, weight=4)

        # Header row
        for col, (text, anc) in enumerate([
            ("Model", "w"), ("Bypass %", "e"), ("Verdict", "w"), ("Recommendation", "w"),
        ]):
            ctk.CTkLabel(table_frame, text=text,
                         font=ctk.CTkFont(size=10, weight="bold"),
                         text_color=C_MUTED, anchor=anc,
                         ).grid(row=0, column=col, sticky="ew", padx=6, pady=4)

        # Sort by bypass % descending (most cracked first)
        sorted_models = sorted(
            self._state.items(),
            key=lambda kv: kv[1].get("pct", 0),
            reverse=True,
        )

        for row_idx, (model_key, data) in enumerate(sorted_models, start=1):
            verdict = data.get("verdict", "?")
            pct = data.get("pct", 0)
            gs_color, gs_short = _GS_COLORS.get(verdict, ("#666688", verdict))
            rec_text = godseek_recommendation(model_key, data)[:80] + "…" if len(
                godseek_recommendation(model_key, data)) > 80 else godseek_recommendation(model_key, data)

            bg = "#0e0e1c" if row_idx % 2 == 0 else C_SURFACE

            ctk.CTkLabel(table_frame, text=model_key[:36], anchor="w",
                         fg_color=bg, font=ctk.CTkFont(size=10),
                         text_color=C_TEXT,
                         ).grid(row=row_idx, column=0, sticky="ew", padx=4, pady=1)
            ctk.CTkLabel(table_frame, text=f"{pct}%", anchor="e",
                         fg_color=bg, font=ctk.CTkFont(size=10, weight="bold"),
                         text_color=gs_color,
                         ).grid(row=row_idx, column=1, sticky="ew", padx=4, pady=1)
            ctk.CTkLabel(table_frame, text=gs_short, anchor="w",
                         fg_color=bg, font=ctk.CTkFont(size=10),
                         text_color=gs_color,
                         ).grid(row=row_idx, column=2, sticky="ew", padx=4, pady=1)
            rec_label = godseek_recommendation(model_key, data)
            ctk.CTkLabel(table_frame, text=rec_label[:90], anchor="w",
                         fg_color=bg, font=ctk.CTkFont(size=9),
                         text_color="#886677",
                         ).grid(row=row_idx, column=3, sticky="ew", padx=4, pady=1)

        # ── Footer ─────────────────────────────────────────────────────────
        foot = ctk.CTkFrame(self, fg_color="transparent", height=40)
        foot.pack(fill="x", padx=8, pady=(0, 8))
        foot.pack_propagate(False)

        ctk.CTkButton(
            foot, text="▶ Launch GodSeek (full run)", width=180, height=30,
            fg_color="#330011", hover_color="#550022",
            text_color="#ff6688", font=ctk.CTkFont(size=11),
            command=lambda: self._launch(None),
        ).pack(side="left", padx=4)

        ctk.CTkButton(
            foot, text="Close", width=80, height=30,
            fg_color="#1a1a30", hover_color="#2a2a48",
            font=ctk.CTkFont(size=11),
            command=self.destroy,
        ).pack(side="right", padx=4)

    def _reload_state(self) -> None:
        self._state = load_godseek_state()
        for w in self.winfo_children():
            w.destroy()
        self._build(self._current)

    def _launch(self, model: str | None) -> None:
        if not GODSEEK_SCRIPT.exists():
            self._show_error(f"Script not found:\n{GODSEEK_SCRIPT}")
            return
        cmd = ["python", str(GODSEEK_SCRIPT)]
        if model:
            # Pass model as first arg — endless_godseek will pick it up if it supports CLI args
            cmd.append(model)
        try:
            subprocess.Popen(
                cmd,
                creationflags=subprocess.CREATE_NEW_CONSOLE,
                cwd=str(GODSEEK_SCRIPT.parent),
            )
        except OSError as exc:
            self._show_error(f"Launch failed: {exc}")

    def _show_error(self, msg: str) -> None:
        dlg = ctk.CTkToplevel(self)
        dlg.title("Error")
        dlg.geometry("400x120")
        dlg.configure(fg_color=C_BG)
        dlg.grab_set()
        ctk.CTkLabel(dlg, text=msg, font=ctk.CTkFont(size=11), text_color="#cc4444",
                     wraplength=360).pack(expand=True, pady=16)
        ctk.CTkButton(dlg, text="OK", width=80, command=dlg.destroy,
                      fg_color="#1a1a30").pack(pady=(0, 12))


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    ctk.set_appearance_mode("dark")
    ctk.set_default_color_theme("dark-blue")
    app = HailoChatApp()
    app.mainloop()
