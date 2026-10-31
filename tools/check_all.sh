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

step "sync_strings.py --check"
# Сравнение с git ломается при незакоммиченных правках строк, поэтому спрашиваем сам скрипт.
python3 tools/sync_strings.py --check || failed=1

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
