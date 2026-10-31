#!/usr/bin/env python3
"""Локальный приёмник для проверки уведомлений `tools/webhook_notify.py`.

Поднимает HTTP-сервер на 127.0.0.1 и печатает каждый присланный payload; если задан
`WEBHOOK_SECRET`, дополнительно проверяет подпись `X-PhoneGuard-Signature`.

    python3 tools/webhook_listen.py --port 8099 --secret s3cret
    # в другом терминале:
    WEBHOOK_URL=http://127.0.0.1:8099/hook WEBHOOK_SECRET=s3cret \
        python3 tools/webhook_notify.py --status success

Полезно, чтобы проверить формат, подпись и поведение CI-скрипта до настройки
настоящего эндпоинта.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import signal
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    secret: str | None = None

    def do_POST(self) -> None:  # noqa: N802 - имя задаёт http.server
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        signature = self.headers.get("X-PhoneGuard-Signature")

        verified: bool | None = None
        if self.secret:
            expected = "sha256=" + hmac.new(
                self.secret.encode("utf-8"), body, hashlib.sha256
            ).hexdigest()
            verified = hmac.compare_digest(expected, signature or "")

        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            payload = {"raw": body.decode("utf-8", "replace")[:400]}

        print(f"POST {self.path} event={self.headers.get('X-PhoneGuard-Event')}", flush=True)
        if verified is not None:
            print(f"  подпись: {'верна' if verified else 'НЕВЕРНА'}", flush=True)
        print(json.dumps(payload, ensure_ascii=False, indent=2), flush=True)

        self.send_response(200 if verified is not False else 401)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *args) -> None:  # тише стандартного лога
        return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--secret", default=None)
    args = parser.parse_args()

    Handler.secret = args.secret
    server = HTTPServer(("127.0.0.1", args.port), Handler)

    def _stop(signum, _frame) -> None:  # SIGTERM обрабатывается так же, как Ctrl+C
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, _stop)
    print(f"webhook_listen: слушаю http://127.0.0.1:{args.port}/ (Ctrl+C для выхода)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nwebhook_listen: остановлен")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
