#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Test Google Translate widget translateHtml API (same as WebnovelReader / twkan.com HAR).

Usage:
  pip install requests
  set GOOGLE_TRANSLATE_API_KEY=AIzaSy...   # Windows
  export GOOGLE_TRANSLATE_API_KEY=AIzaSy...  # Linux/macOS
  python test_translate_html_api.py
  python test_translate_html_api.py --source auto --target en
  python test_translate_html_api.py --find-limit
  python test_translate_html_api.py --file testchapter.txt
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

# Windows console: avoid UnicodeEncodeError on CJK output
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def safe_preview(text: str, n: int = 200) -> str:
    return text[:n].encode("utf-8", errors="replace").decode("utf-8", errors="replace")

try:
    import requests
except ImportError:
    print("Install requests: pip install requests", file=sys.stderr)
    sys.exit(1)

TRANSLATE_HTML_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
ORIGIN = "https://twkan.com"


def get_api_key() -> str:
    key = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "").strip()
    if not key:
        print(
            "Set GOOGLE_TRANSLATE_API_KEY (see docs/GOOGLE_TRANSLATE_API_KEY.md).",
            file=sys.stderr,
        )
        sys.exit(1)
    return key
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
)

DEFAULT_CHAPTER = Path(__file__).resolve().parent / "testchapter.txt"


def build_payload(content_parts: list[str], source: str, target: str) -> str:
    """[[[part1, part2, ...], source, target], "te_lib"]"""
    body = [[content_parts, source, target], "te_lib"]
    return json.dumps(body, ensure_ascii=False)


def build_tagged_html(paragraphs: list[str]) -> str:
    return "".join(f"<a i={i}>\n    {p}</a>" for i, p in enumerate(paragraphs))


def request_headers(api_key: str) -> dict[str, str]:
    return {
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Cache-Control": "no-cache",
        "Content-Type": "application/json+protobuf",
        "Origin": ORIGIN,
        "Pragma": "no-cache",
        "Referer": f"{ORIGIN}/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "cross-site",
        "User-Agent": USER_AGENT,
        "X-Browser-Channel": "stable",
        "X-Browser-Copyright": "Copyright 2026 Google LLC. All Rights Reserved.",
        "X-Browser-Validation": "+f/6R40gd6znZQYfwfSnAdnLwLk=",
        "X-Browser-Year": "2026",
        "X-Client-Data": "CKmdygEIlKHLAQiFoM0BCJHLlDA=",
        "X-Goog-Api-Key": api_key,
    }


def translate_html(
    content_parts: list[str],
    source: str,
    target: str,
    timeout: float = 60.0,
) -> tuple[int, str, int]:
    """Returns (http_status, response_text, payload_bytes)."""
    payload = build_payload(content_parts, source, target)
    payload_bytes = len(payload.encode("utf-8"))
    r = requests.post(
        TRANSLATE_HTML_URL,
        data=payload.encode("utf-8"),
        headers=request_headers(get_api_key()),
        timeout=timeout,
    )
    return r.status_code, r.text, payload_bytes


def parse_first_string(response_text: str) -> str:
    data = json.loads(response_text)
    if not data or not data[0]:
        return ""
    first = data[0]
    if isinstance(first, list) and first:
        return first[0] if isinstance(first[0], str) else str(first[0])
    return ""


def parse_tagged_paragraphs(html: str) -> dict[int, str]:
    out: dict[int, str] = {}
    for m in re.finditer(r"<a\s+i=(\d+)>(.*?)</a>", html, re.DOTALL):
        out[int(m.group(1))] = m.group(2).strip()
    return out


def load_chapter(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def split_paragraphs(text: str) -> list[str]:
    parts = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    return parts if parts else [text]


def print_stats(label: str, text: str, payload_bytes: int) -> None:
    print(f"\n=== {label} ===")
    print(f"  chars: {len(text):,}  utf-8 bytes: {len(text.encode('utf-8')):,}")
    print(f"  payload bytes: {payload_bytes:,}")


def test_quick(source: str, target: str, sample: str) -> bool:
    html = build_tagged_html([sample[:200] if len(sample) > 200 else sample])
    status, body, pb = translate_html([html], source, target)
    print_stats(f"quick test source={source!r} -> {target!r}", html, pb)
    print(f"  HTTP {status}")
    if status != 200:
        print(f"  body preview: {body[:500]}")
        return False
    translated = parse_first_string(body)
    print(f"  translated preview: {safe_preview(translated)!r}...")
    return bool(translated.strip())


def test_auto_detection(chapter: str) -> None:
    print("\n" + "=" * 60)
    print("AUTO LANGUAGE DETECTION")
    print("=" * 60)
    print(
        "translateHtml accepts source='auto' for detect-language (see tests below).\n"
        "Android app: pick 'auto' as source in translator settings (added to language list).\n"
        "twkan.com HAR used explicit zh-TW; auto is optional on the API.\n"
    )
    snippet = chapter[:400]
    for src in ("auto", "zh-TW", "zh-CN"):
        ok = test_quick(src, "en", snippet)
        print(f"  -> {'OK' if ok else 'FAILED'}\n")
        time.sleep(0.5)


def test_chapter_file(
    path: Path, source: str, target: str, max_chars: int | None, full_one_shot: bool = False
) -> None:
    text = load_chapter(path)
    paragraphs = split_paragraphs(text)
    print("\n" + "=" * 60)
    print(f"CHAPTER FILE: {path.name}")
    print("=" * 60)
    print(f"  total chars: {len(text):,}")
    print(f"  paragraphs: {len(paragraphs)}")
    print(f"  repeated blocks (~4x paste): expect ~{len(text) // 4:,} chars per copy")

    html_full = build_tagged_html(paragraphs)
    if full_one_shot:
        max_chars = None

    if max_chars and len(html_full) > max_chars:
        print(f"\n  Full HTML {len(html_full):,} chars > limit {max_chars:,}, chunking...")
        chunk_size = max_chars
        translated_parts: list[str] = []
        for i in range(0, len(paragraphs), 20):
            chunk_paras = paragraphs[i : i + 20]
            chunk_html = build_tagged_html(chunk_paras)
            if len(chunk_html) > max_chars:
                # fall back: single paragraph batches
                for p in chunk_paras:
                    h = build_tagged_html([p])
                    status, body, pb = translate_html([h], source, target)
                    if status != 200:
                        print(f"  FAIL para HTTP {status} payload={pb}")
                        continue
                    t = parse_tagged_paragraphs(parse_first_string(body)).get(0, "")
                    translated_parts.append(t)
                continue
            status, body, pb = translate_html([chunk_html], source, target)
            print(f"  chunk {i//20 + 1}: HTTP {status}, payload={pb:,} bytes")
            if status != 200:
                print(f"    {body[:300]}")
                continue
            tagged = parse_tagged_paragraphs(parse_first_string(body))
            translated_parts.extend(tagged.get(j, "") for j in range(len(chunk_paras)))
            time.sleep(0.3)
        out = "\n\n".join(translated_parts)
        print(f"\n  combined translation chars: {len(out):,}")
        print(f"  preview:\n{safe_preview(out, 500)}...\n")
        return

    status, body, pb = translate_html([html_full], source, target)
    print_stats("full chapter (single request)", html_full, pb)
    print(f"  HTTP {status}")
    if status != 200:
        print(f"  {body[:800]}")
        return
    result = parse_first_string(body)
    tagged = parse_tagged_paragraphs(result)
    print(f"  parsed <a i=N> tags: {len(tagged)}")
    if tagged:
        print(f"  [0]: {safe_preview(tagged.get(0, ''), 120)}...")
    else:
        print(f"  raw preview: {safe_preview(result, 300)}...")


def find_payload_limit(source: str, target: str, base_text: str) -> None:
    """Binary search max UTF-8 payload size that still returns HTTP 200 + content."""
    print("\n" + "=" * 60)
    print("FIND PAYLOAD LIMIT (binary search on payload bytes)")
    print("=" * 60)

    filler = base_text
    if len(filler) < 500:
        filler = filler * 50

    lo, hi = 500, min(len(filler) * 2 + 8000, 120_000)
    best = 0

    def try_size(n: int) -> bool:
        chunk = filler[:n]
        html = build_tagged_html([chunk])
        try:
            status, body, pb = translate_html([html], source, target, timeout=90.0)
        except requests.RequestException as e:
            print(f"  n={n}: request error {e}")
            return False
        ok = status == 200 and len(parse_first_string(body).strip()) > 0
        if ok:
            nonlocal best
            best = pb
        print(f"  content_chars={n:6} payload_bytes={pb:6} HTTP={status} ok={ok}")
        time.sleep(0.4)
        return ok

    # expand hi until failure
    while hi < 120_000 and try_size(hi // 2):
        hi = min(hi * 2, 120_000)

    lo, hi = 1000, hi
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if try_size(mid):
            lo = mid
        else:
            hi = mid - 1

    print(f"\n  Approx max content chars in one <a i=0> block: ~{lo:,}")
    print(f"  Largest successful payload_bytes seen: ~{best:,}")
    print("  (App uses 6500 content chars + splits; HAR ~6785-byte bodies.)\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Test translateHtml API")
    parser.add_argument("--file", type=Path, default=DEFAULT_CHAPTER)
    parser.add_argument("--source", default="auto", help="Source lang, e.g. auto, zh-TW, zh-CN")
    parser.add_argument("--target", default="en")
    parser.add_argument("--find-limit", action="store_true", help="Binary-search payload limit")
    parser.add_argument("--test-auto", action="store_true", help="Compare auto vs zh-TW/zh-CN")
    parser.add_argument("--max-chars", type=int, default=6500, help="Chunk if HTML exceeds this")
    parser.add_argument(
        "--full-file-one-shot",
        action="store_true",
        help="Try entire file in one request (no chunking)",
    )
    args = parser.parse_args()

    if not args.file.is_file():
        print(f"Missing file: {args.file}", file=sys.stderr)
        sys.exit(1)

    chapter = load_chapter(args.file)

    if args.test_auto:
        test_auto_detection(chapter)

    if args.find_limit:
        find_payload_limit(args.source, args.target, chapter)

    test_chapter_file(
        args.file, args.source, args.target, args.max_chars, args.full_file_one_shot
    )

    if not args.test_auto and not args.find_limit:
        test_auto_detection(chapter)


if __name__ == "__main__":
    main()
