import logging
import os
import re

import requests

logger = logging.getLogger(__name__)

OLLAMA_BASE  = os.getenv("OLLAMA_BASE",  "http://localhost:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "granite4.1:8b")
_CHAT_URL    = f"{OLLAMA_BASE}/api/chat"

_SYSTEM = (
    "You analyze OCR text captured from a camera image of a screen or document.\n"
    "Identify and solve every question or problem in the text.\n"
    "Format your response in clean markdown:\n"
    "- MCQ: bold the correct answer\n"
    "- Coding/DSA: complete working code in a code block, then Time/Space complexity\n"
    "- Concepts: clear direct explanation\n"
    "Be concise and accurate."
)


def _call_lm(text: str, num_captures: int = 1) -> str:
    cap_note = f" ({num_captures} captures combined)" if num_captures > 1 else ""
    payload = {
        "model":    OLLAMA_MODEL,
        "messages": [
            {"role": "system", "content": _SYSTEM},
            {"role": "user",   "content": f"OCR text{cap_note}:\n\n{text}"},
        ],
        "stream":  False,
        "options": {"temperature": 0.1, "num_predict": 1024},
    }
    resp = requests.post(_CHAT_URL, json=payload, timeout=120)
    resp.raise_for_status()
    content = resp.json()["message"]["content"]
    content = re.sub(r"<think>[\s\S]*?</think>", "", content, flags=re.IGNORECASE).strip()
    logger.info("Ollama response (first 400): %.400s", content)
    return content


def sort_ocr_items(items: list) -> list:
    def key(item):
        bbox = item["bbox"]
        return (min(p[1] for p in bbox) // 40, min(p[0] for p in bbox))
    return sorted(items, key=key)


def analyze_with_lm(ocr_items: list, num_captures: int = 1) -> dict:
    """Returns {markdown, raw_text, error}."""
    if not ocr_items:
        return {"markdown": "", "raw_text": "", "error": None}

    raw_text = "\n".join(i["text"] for i in sort_ocr_items(ocr_items))

    try:
        markdown = _call_lm(raw_text, num_captures)
        return {"markdown": markdown, "raw_text": raw_text, "error": None}
    except requests.exceptions.ConnectionError:
        return {"markdown": "", "raw_text": raw_text,
                "error": "Ollama offline — run: ollama serve"}
    except Exception as exc:
        logger.error("Ollama call failed: %s", exc)
        return {"markdown": "", "raw_text": raw_text, "error": str(exc)}
