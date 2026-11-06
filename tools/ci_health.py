#!/usr/bin/env python3
"""Проверяет, что прогоны CI вообще **запускались**, а не просто покраснели.

Зачем: красный крестик в GitHub ничего не говорит о коде. Прогон может упасть за 6 секунд,
потому что job не был выдан раннер (например, заблокирован биллинг аккаунта: «The job was not
started because your account is locked due to a billing issue»). Все шаги при этом имеют
`steps: 0`. Со стороны это выглядит как «тесты не прошли», и легко решить, что виноват код —
особенно когда красные все прогоны подряд.

Скрипт смотрит последние прогоны через `gh api` и разделяет два случая:

* **настоящее падение** — у упавшего job есть шаги, значит код/конфигурация действительно
  проверялись и не прошли;
* **прогон не запускался** — шагов ноль, значит раннер не выдавался и код не проверялся вовсе.

    python3 tools/ci_health.py              # сводка + вердикт (код 1, если CI не проверяет код)
    python3 tools/ci_health.py --limit 10
    python3 tools/ci_health.py --self-test

Нужен установленный и авторизованный `gh` (`gh auth status`). Без него скрипт печатает, что
проверка невозможна, и выходит с кодом 0: сеть и gh — не часть статических проверок.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys


def gh_json(args: list[str]) -> dict | list | None:
    """`gh api …` → разобранный JSON; None, если gh нет или он не авторизован."""
    try:
        result = subprocess.run(
            ["gh", "api", *args], capture_output=True, text=True, check=False, timeout=60
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def classify(jobs: list[dict]) -> dict:
    """Делит job'ы прогона на «не запускались» и «упали по-настоящему».

    `steps == 0` — единственный надёжный признак: job был отменён или не получил раннер, ни один
    шаг не выполнялся. У настоящего падения шаги есть всегда, даже если упал первый из них.
    """
    not_started: list[str] = []
    failures: list[str] = []
    for job in jobs:
        conclusion = job.get("conclusion")
        if conclusion not in {"failure", "timed_out"}:
            continue
        if len(job.get("steps") or []) == 0:
            not_started.append(job.get("name", "?"))
        else:
            failures.append(job.get("name", "?"))
    return {"not_started": not_started, "failures": failures}


def verdict(runs: list[tuple[dict, list[dict]]]) -> tuple[list[str], list[str]]:
    """Возвращает (ошибки, предупреждения) по последним прогонам."""
    errors: list[str] = []
    warnings: list[str] = []
    if not runs:
        return (["прогонов CI не найдено — workflow ни разу не запускался"], [])

    dead = 0
    failed = 0
    for run, jobs in runs:
        name = run.get("name", "?")
        number = run.get("id", "?")
        conclusion = run.get("conclusion")
        if conclusion == "success":
            continue
        if conclusion in {"skipped", "cancelled", None}:
            continue
        if conclusion is None:
            warnings.append(f"{name} #{number}: прогон ещё идёт")
            continue
        result = classify(jobs)
        if result["not_started"] and not result["failures"]:
            dead += 1
        else:
            failed += 1
            errors.append(
                f"{name} #{number}: упали job'ы {', '.join(result['failures']) or '—'} — "
                "шаги выполнялись, значит проверялся код"
            )

    if dead:
        errors.insert(
            0,
            f"CI не проверяет код: {dead} из {len(runs)} последних прогонов не запускались "
            "(0 шагов, «job was not started»). Обычные причины: блокировка биллинга GitHub, "
            "исчерпанные минуты, отключённые Actions. Разберись в Settings → Billing → Actions, "
            "иначе «красный CI» не значит «сломан код», а зелёного не будет вовсе",
        )
    return errors, warnings


def fetch_runs(repo: str, limit: int) -> list[tuple[dict, list[dict]]]:
    listing = gh_json([f"repos/{repo}/actions/runs?per_page={limit}"])
    if not isinstance(listing, dict):
        return []
    collected: list[tuple[dict, list[dict]]] = []
    for run in listing.get("workflow_runs", [])[:limit]:
        jobs_json = gh_json([f"repos/{repo}/actions/runs/{run['id']}/jobs"])
        jobs = jobs_json.get("jobs", []) if isinstance(jobs_json, dict) else []
        collected.append((run, jobs))
    return collected


def self_test() -> int:
    """Правила проверяются на синтетических прогонах, а не на живом GitHub."""
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    def run(conclusion: str) -> dict:
        return {"id": 1, "name": "Android CI", "conclusion": conclusion}

    not_started_job = {"name": "Static checks", "conclusion": "failure", "steps": []}
    real_failure_job = {
        "name": "Unit tests",
        "conclusion": "failure",
        "steps": [{"name": "Run tests", "conclusion": "failure"}],
    }

    result = classify([not_started_job])
    check("job без шагов считается незапущенным", result["not_started"] == ["Static checks"])
    check("job без шагов не считается падением кода", result["failures"] == [])

    result = classify([real_failure_job])
    check("job с шагами считается настоящим падением", result["failures"] == ["Unit tests"])

    errors, _ = verdict([(run("success"), [{"name": "Static checks", "conclusion": "success", "steps": [{}]}])])
    check("успешный прогон ошибок не даёт", errors == [])

    errors, _ = verdict([(run("failure"), [not_started_job])])
    check("мёртвый CI виден как ошибка", any("не проверяет код" in error for error in errors))

    errors, _ = verdict([(run("failure"), [real_failure_job])])
    check("настоящее падение отличается от мёртвого CI", any("Unit tests" in error for error in errors))
    check("настоящее падение не выдаёт себя за биллинг", not any("не проверяет код" in error for error in errors))

    errors, _ = verdict([(run("failure"), [not_started_job]), (run("failure"), [real_failure_job])])
    check("смешанные прогоны дают обе причины", len(errors) == 2)

    errors, _ = verdict([({"id": 1, "name": "Android CI", "conclusion": None}, [])])
    check("идущий прогон — не ошибка", errors == [])

    errors, _ = verdict([])
    check("отсутствие прогонов — ошибка", any("ни разу" in error for error in errors))

    if problems:
        for problem in problems:
            print(f"error: ci_health --self-test: {problem}")
        return 1
    print(
        "ci_health --self-test: ok (job без шагов, настоящее падение, успех, идущий прогон, "
        "смешанные прогоны, отсутствие прогонов)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="vladvinys-vv/PhoneGuard", help="owner/repo")
    parser.add_argument("--limit", type=int, default=5, help="сколько последних прогонов смотреть")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    runs = fetch_runs(args.repo, args.limit)
    if not runs:
        print("ci_health: не удалось получить прогоны (нет gh, нет авторизации или сети) — проверка пропущена")
        return 0

    for run, jobs in runs:
        result = classify(jobs)
        state = run.get("conclusion") or "в процессе"
        if result["not_started"] and not result["failures"]:
            state += " (не запускался)"
        print(f"{run.get('id')}  {run.get('name', '?'):<32} {state}")

    errors, warnings = verdict(runs)
    for warning in warnings:
        print(f"warning: {warning}")
    for problem in errors:
        print(f"error: {problem}")
    print(
        f"\n{len(errors)} error(s), {len(warnings)} warning(s): "
        f"последние {len(runs)} прогонов CI, из них успешных "
        f"{sum(1 for run, _ in runs if run.get('conclusion') == 'success')}"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
