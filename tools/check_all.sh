#!/usr/bin/env bash
# Все проверки, доступные без Android SDK. Запускать из корня репозитория.
#
#   ./tools/check_all.sh          # аудит + строки (+ компилятор и парсер, если установлены)
#
# Код возврата ненулевой, если хоть одна обязательная проверка упала.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
failed=0

step() { printf '\n=== %s ===\n' "$1"; }

step "static_audit.py"
python3 tools/static_audit.py || failed=1

step "sync_strings.py (идемпотентность)"
python3 tools/sync_strings.py >/tmp/check_all_strings.log 2>&1 || failed=1
if ! git diff --quiet -- app/src/main/res; then
    echo "error: app/src/main/res изменился после sync_strings.py — строки не синхронизированы"
    git --no-pager diff --stat -- app/src/main/res
    failed=1
else
    echo "ok: строки в синхронизации"
fi

step "kotlin_frontend_check.py (опционально)"
if command -v kotlinc >/dev/null 2>&1; then
    python3 tools/kotlin_frontend_check.py || failed=1
else
    echo "skip: kotlinc не найден (нужен Kotlin 2.1+ и JDK 17)"
fi

step "kotlin_analysis.py (опционально)"
python3 tools/kotlin_analysis.py || failed=1

if [ "$failed" -ne 0 ]; then
    echo -e "\nПроверки не прошли."
    exit 1
fi
echo -e "\nВсе доступные проверки пройдены."
