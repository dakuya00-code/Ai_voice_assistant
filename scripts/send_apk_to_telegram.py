#!/usr/bin/env python3
"""Send an APK file to Telegram using the Bot API.

Usage:
  TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... \
    python scripts/send_apk_to_telegram.py /path/to/app.apk
"""

from __future__ import annotations

import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: send_apk_to_telegram.py /path/to/file.apk", file=sys.stderr)
        return 2

    apk_path = Path(sys.argv[1]).expanduser().resolve()
    if not apk_path.is_file():
        print(f"APK not found: {apk_path}", file=sys.stderr)
        return 2

    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat_id = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat_id:
        print("Missing TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID", file=sys.stderr)
        return 2

    boundary = "----HermesTelegramBoundary7e2c1f"
    url = f"https://api.telegram.org/bot{token}/sendDocument"
    fields = [
        ("chat_id", chat_id),
        ("caption", f"Voice Journal APK: {apk_path.name}"),
    ]

    body = bytearray()
    for name, value in fields:
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        body.extend(f"{value}\r\n".encode())

    body.extend(f"--{boundary}\r\n".encode())
    body.extend(
        f'Content-Disposition: form-data; name="document"; filename="{apk_path.name}"\r\n'.encode()
    )
    body.extend(b"Content-Type: application/vnd.android.package-archive\r\n\r\n")
    body.extend(apk_path.read_bytes())
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        url,
        data=bytes(body),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = resp.read().decode("utf-8", errors="replace")
        print(payload)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
