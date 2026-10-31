#!/usr/bin/env python3
"""Отправляет уведомление о сборке на внешний webhook (Codemagic, GitHub Actions, локально).

Зачем: у Codemagic нет отдельной секции `webhook` — стандартный способ это скрипт в
`publishing:`, который выполняется даже при падении сборки. Здесь он один на все CI:
Codemagic, GitHub Actions и локальный запуск, с подписью HMAC-SHA256 и повторами.

    # боевой режим (URL берётся из переменной окружения)
    WEBHOOK_URL=https://example.com/hooks/phoneguard WEBHOOK_SECRET=... \
        python3 tools/webhook_notify.py

    # локальная проверка без сети
    python3 tools/webhook_notify.py --dry-run

    # отметить, что предыдущие шаги сборки прошли (Codemagic: publishing-скрипты
    # выполняются и при падении, поэтому статус берётся из маркера)
    python3 tools/webhook_notify.py --mark-success

Переменные окружения:
    WEBHOOK_URL      — куда отправлять; если не задана, скрипт тихо пропускает отправку;
    WEBHOOK_SECRET   — секрет для заголовка `X-PhoneGuard-Signature: sha256=<hmac>`;
    WEBHOOK_TIMEOUT  — таймаут запроса в секундах (по умолчанию 10);
    WEBHOOK_RETRIES  — число попыток (по умолчанию 3);
    CM_* / GITHUB_*  — определяются автоматически (провайдер, ветка, коммит, build id).

Скрипт **никогда не валит сборку**: сеть недоступна — печатается предупреждение.
Ненулевой код возврата только при `--strict`.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import platform
import socket
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUCCESS_MARKER = Path.home() / ".phoneguard-build-success"
EVENT = "build.finished"
ARTIFACT_GLOBS = ("app/build/outputs/apk/**/*.apk", "app/build/outputs/bundle/**/*.aab")


def _env(*names: str) -> str | None:
    for name in names:
        value = os.environ.get(name)
        if value:
            return value
    return None


def detect_provider() -> tuple[str, dict[str, str | None]]:
    """Определяет CI и собирает общие поля (id сборки, ссылка, репозиторий, ветка)."""
    if os.environ.get("CM_BUILD_ID"):
        project = _env("CM_PROJECT_ID")
        build_id = _env("CM_BUILD_ID")
        return "codemagic", {
            "build_id": build_id,
            "build_url": (
                f"https://codemagic.io/app/{project}/build/{build_id}"
                if project and build_id
                else None
            ),
            "repository": _env("CM_REPO_SLUG", "CM_REPOSITORY_URL") or _git_remote_slug(),
            "branch": _env("CM_BRANCH"),
            "commit": _env("CM_COMMIT"),
        }

    if os.environ.get("GITHUB_RUN_ID"):
        server = _env("GITHUB_SERVER_URL") or "https://github.com"
        repo = _env("GITHUB_REPOSITORY")
        run_id = _env("GITHUB_RUN_ID")
        return "github-actions", {
            "build_id": run_id,
            "build_url": f"{server}/{repo}/actions/runs/{run_id}" if repo and run_id else None,
            "repository": repo,
            "branch": _env("GITHUB_HEAD_REF", "GITHUB_REF_NAME"),
            "commit": _env("GITHUB_SHA"),
        }

    return "local", {
        "build_id": None,
        "build_url": None,
        "repository": _git_remote_slug(),
        "branch": _git("rev-parse", "--abbrev-ref", "HEAD"),
        "commit": _git("rev-parse", "HEAD"),
    }


def _git(*args: str) -> str | None:
    try:
        import subprocess

        result = subprocess.run(
            ["git", "-C", str(ROOT), *args], capture_output=True, text=True, timeout=10
        )
        value = result.stdout.strip()
        return value or None
    except Exception:
        return None


def _git_remote_slug() -> str | None:
    """`owner/repo` из remote origin — чтобы payload был информативен и без CI."""
    url = _git("remote", "get-url", "origin")
    if not url:
        return None
    url = url.strip().removesuffix(".git")
    if url.startswith("git@"):
        url = url.split("@", 1)[1].replace(":", "/", 1)
    elif "://" in url:
        url = url.split("://", 1)[1]
        url = url.split("/", 1)[1] if "/" in url else ""
    parts = [part for part in url.split("/") if part]
    return "/".join(parts[-2:]) if len(parts) >= 2 else None


def collect_artifacts() -> list[dict[str, object]]:
    """Сначала ссылки Codemagic (CM_ARTIFACT_LINKS), иначе — локальные файлы сборки."""
    links = os.environ.get("CM_ARTIFACT_LINKS")
    if links:
        try:
            parsed = json.loads(links)
            if isinstance(parsed, list):
                return [
                    {"name": item.get("name"), "url": item.get("url"), "size": item.get("size")}
                    for item in parsed
                    if isinstance(item, dict)
                ]
        except json.JSONDecodeError:
            pass

    artifacts: list[dict[str, object]] = []
    for pattern in ARTIFACT_GLOBS:
        for path in sorted(ROOT.glob(pattern)):
            artifacts.append(
                {"name": path.name, "url": None, "size": path.stat().st_size}
            )
    return artifacts


def resolve_status(explicit: str | None) -> str:
    if explicit:
        return explicit
    env_status = _env("BUILD_STATUS", "PHONEGUARD_BUILD_STATUS")
    if env_status:
        return env_status
    # Codemagic и GH Actions выполняют notify и при падении: статус берём из маркера,
    # который ставится последним успешным шагом (`--mark-success`).
    return "success" if SUCCESS_MARKER.exists() else "failure"


def build_payload(status: str) -> dict[str, object]:
    provider, info = detect_provider()
    return {
        "event": EVENT,
        "status": status,
        "provider": provider,
        "repository": info["repository"],
        "branch": info["branch"],
        "commit": info["commit"],
        "build_id": info["build_id"],
        "build_url": info["build_url"],
        "workflow": _env("CM_WORKFLOW_NAME", "CM_WORKFLOW_ID", "GITHUB_WORKFLOW"),
        "job": _env("GITHUB_JOB"),
        "artifacts": collect_artifacts(),
        "source": {"host": socket.gethostname(), "platform": platform.platform()},
        "sent_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


def sign(body: bytes, secret: str | None) -> str | None:
    if not secret:
        return None
    return "sha256=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()


def post(url: str, body: bytes, signature: str | None, timeout: float) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": "PhoneGuard-CI-Notifier/1.0",
            "X-PhoneGuard-Event": EVENT,
            **({"X-PhoneGuard-Signature": signature} if signature else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read(512).decode("utf-8", "replace")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", choices=["success", "failure", "canceled"], default=None)
    parser.add_argument("--dry-run", action="store_true", help="напечатать payload и выйти")
    parser.add_argument("--mark-success", action="store_true", help="поставить маркер успешной сборки")
    parser.add_argument("--strict", action="store_true", help="падать, если уведомление не доставлено")
    args = parser.parse_args()

    if args.mark_success:
        SUCCESS_MARKER.write_text(
            datetime.now(timezone.utc).isoformat(timespec="seconds"), encoding="utf-8"
        )
        print(f"webhook_notify: маркер успешной сборки создан ({SUCCESS_MARKER})")
        return 0

    payload = build_payload(resolve_status(args.status))
    body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")

    if args.dry_run:
        print(body.decode("utf-8"))
        return 0

    url = os.environ.get("WEBHOOK_URL")
    if not url:
        print("webhook_notify: WEBHOOK_URL не задан — уведомление пропущено")
        return 0

    secret = os.environ.get("WEBHOOK_SECRET")
    timeout = float(os.environ.get("WEBHOOK_TIMEOUT", "10"))
    retries = max(1, int(os.environ.get("WEBHOOK_RETRIES", "3")))
    signature = sign(body, secret)

    last_error = "unknown error"
    for attempt in range(1, retries + 1):
        try:
            status_code, response = post(url, body, signature, timeout)
            if 200 <= status_code < 300:
                print(
                    f"webhook_notify: отправлено {payload['status']} "
                    f"({payload['provider']}, попытка {attempt}, HTTP {status_code})"
                )
                if response.strip():
                    print(f"webhook_notify: ответ сервера: {response[:200]}")
                return 0
            last_error = f"HTTP {status_code}: {response[:200]}"
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
            last_error = str(exc)
        if attempt < retries:
            time.sleep(min(2 ** attempt, 10))

    print(f"webhook_notify: не удалось отправить уведомление — {last_error}")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
