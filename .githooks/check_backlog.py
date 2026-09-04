"""
Stop hook: warns if the last assistant message contains trigger phrases
that often signal out-of-scope observations but BACKLOG.md wasn't touched.

Fail-safe: any error -> exit 0 silently. Never block the model on errors.
"""
import json
import subprocess
import sys
from pathlib import Path

TRIGGERS = [
    "todo",
    "fixme",
    "hack",
    "future work",
    "we should also",
    "would be nice to",
    "eventually we'd want",
    "this is a workaround",
    "good enough for now",
    "ideally we'd",
    "by the way i noticed",
]


def main():
    try:
        raw = sys.stdin.read()
        if not raw.strip():
            return 0
        data = json.loads(raw)
    except Exception:
        return 0

    transcript_path = data.get("transcript_path")
    cwd = data.get("cwd") or "."

    if not transcript_path or not Path(transcript_path).exists():
        return 0

    last_text = ""
    try:
        with open(transcript_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if entry.get("type") != "assistant":
                    continue
                msg = entry.get("message", {})
                content = msg.get("content", [])
                if isinstance(content, str):
                    last_text = content
                elif isinstance(content, list):
                    parts = []
                    for block in content:
                        if isinstance(block, dict) and block.get("type") == "text":
                            parts.append(block.get("text", ""))
                    if parts:
                        last_text = "\n".join(parts)
    except Exception:
        return 0

    if not last_text:
        return 0

    lower = last_text.lower()
    found = [t for t in TRIGGERS if t in lower]
    if not found:
        return 0

    backlog = Path(cwd) / "BACKLOG.md"
    if not backlog.exists():
        return 0

    # Any uncommitted change to BACKLOG.md (modified/staged/untracked) counts as captured.
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain", "BACKLOG.md"],
            cwd=cwd, capture_output=True, text=True, timeout=5,
        )
        if result.stdout.strip():
            return 0
    except Exception:
        return 0

    detected = ", ".join(f'"{t}"' for t in found[:5])
    sys.stderr.write(
        f"Backlog discipline check: your last message used phrases that often "
        f"signal out-of-scope observations ({detected}), but BACKLOG.md wasn't "
        f"modified in this session. Either append the items to BACKLOG.md now, "
        f"or explicitly state that there's nothing actionable to capture.\n"
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
