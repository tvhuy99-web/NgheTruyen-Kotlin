#!/usr/bin/env python3
"""Validate the portable comments pagination fixtures used by source adapters."""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/comment-fixtures"
ALIASES = (("user", "name", "author"), ("time", "date"), ("text", "content", "description"))


def first(obj: dict, names: tuple[str, ...]) -> str:
    return next((str(obj[name]).strip() for name in names if str(obj.get(name, "")).strip()), "")


def load(name: str) -> tuple[list[tuple[str, str, str]], str | None]:
    value = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    raw = value.get("items", value.get("comments", value.get("data", [])))
    assert isinstance(raw, list) and len(raw) <= 100
    comments = []
    for item in raw:
        assert isinstance(item, dict)
        user, time, text = (first(item, aliases) for aliases in ALIASES)
        assert text and len(text) <= 20_000
        assert len(user) <= 200 and len(time) <= 200
        comments.append((user or "Người đọc", time, text))
    next_url = next((value.get(key) for key in ("nextPageUrl", "nextUrl", "next", "cursor") if value.get(key)), None)
    if isinstance(next_url, str) and next_url.upper() == "NO_NEXT":
        next_url = None
    return comments, next_url


def main() -> None:
    page1, next1 = load("page1.json")
    page2, next2 = load("page2.json")
    assert len(page1) == 2 and next1 and next1.endswith("page=2")
    assert len(page2) == 2 and next2 is None
    merged = list(dict.fromkeys(page1 + page2))
    assert len(merged) == 3
    print("COMMENT_FIXTURES_OK")


if __name__ == "__main__":
    main()
