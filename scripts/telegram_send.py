#!/usr/bin/env python3
"""Send Telegram channel posts without curl -F.

GitHub Actions masks the substring AngelaBox (from TG_CHANNEL_ID=@AngelaBox)
inside logs and can corrupt curl's @filename form field. Use stdlib multipart
instead so the APK is always read from disk.
"""
from __future__ import annotations

import json
import os
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path


def multipart(fields: dict[str, str], files: dict[str, tuple[str, bytes, str]]) -> tuple[bytes, str]:
    boundary = "----AngelaBoxForm7a3c"
    crlf = b"\r\n"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{boundary}".encode(),
                f'Content-Disposition: form-data; name="{name}"'.encode(),
                b"",
                value.encode("utf-8"),
            ]
        )
    for name, (filename, data, content_type) in files.items():
        chunks.extend(
            [
                f"--{boundary}".encode(),
                (
                    f'Content-Disposition: form-data; name="{name}"; '
                    f'filename="{filename}"'
                ).encode(),
                f"Content-Type: {content_type}".encode(),
                b"",
                data,
            ]
        )
    chunks.append(f"--{boundary}--".encode())
    chunks.append(b"")
    body = crlf.join(chunks)
    return body, f"multipart/form-data; boundary={boundary}"


def post(token: str, method: str, fields: dict[str, str], files: dict[str, tuple[str, bytes, str]] | None = None) -> dict:
    url = f"https://api.telegram.org/bot{token}/{method}"
    if files:
        data, content_type = multipart(fields, files)
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", content_type)
    else:
        payload = json.dumps(fields).encode("utf-8")
        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=180) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        print(raw.decode("utf-8", "replace"), file=sys.stderr)
        raise SystemExit(f"Telegram {method} HTTP {exc.code}") from exc
    parsed = json.loads(raw.decode("utf-8"))
    print(json.dumps(parsed, ensure_ascii=False, indent=2)[:4000])
    if not parsed.get("ok"):
        raise SystemExit(f"Telegram {method} failed: {parsed}")
    return parsed


def main() -> None:
    token = os.environ["TG_BOT_TOKEN"]
    chat_id = os.environ["TG_CHANNEL_ID"]
    apk = Path(os.environ.get("APK_FILE", "AngelaBox-android.apk"))
    caption_file = Path("telegram-caption.txt")
    message_file = Path("telegram-message.txt")
    markup_file = Path("telegram-markup.json")
    document_only = os.environ.get("DOCUMENT_ONLY", "").lower() in {"1", "true", "yes"}
    skip_document = os.environ.get("SKIP_DOCUMENT", "").lower() in {"1", "true", "yes"}

    if not skip_document:
        if not apk.is_file() or apk.stat().st_size < 1_000_000:
            raise SystemExit(f"APK missing or too small: {apk}")
        print(f"uploading {apk} ({apk.stat().st_size} bytes)")
        caption = caption_file.read_text(encoding="utf-8").strip() if caption_file.is_file() else apk.name
        post(
            token,
            "sendDocument",
            {
                "chat_id": chat_id,
                "caption": caption[:1024],
                "disable_content_type_detection": "true",
            },
            {
                "document": (
                    "AngelaBox-android.apk",
                    apk.read_bytes(),
                    "application/vnd.android.package-archive",
                )
            },
        )
    if not document_only:
        text = message_file.read_text(encoding="utf-8").strip()
        fields: dict = {
            "chat_id": chat_id,
            "text": text,
            "disable_web_page_preview": True,
        }
        if markup_file.is_file():
            fields["reply_markup"] = json.loads(markup_file.read_text(encoding="utf-8"))
        post(token, "sendMessage", fields)


if __name__ == "__main__":
    main()
