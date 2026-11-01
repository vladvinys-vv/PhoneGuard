#!/usr/bin/env bash
# Все проверки, доступные без Android SDK. Запускать из корня репозитория.
#
#   ./tools/check_all.sh          # аудит + строки (+ компилятор, aapt2 и парсер, если установлены)
#
# Код возврата ненулевой, если хоть одна обязательная проверка упала.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
failed=0

step() { printf '\n=== %s ===\n' "$1"; }

step "gradle_check.py"
# Конфигурация Gradle: ошибка здесь валит lint/тесты/сборку ещё до компиляции
# (так падал mapOf() из Kotlin stdlib в Groovy-скрипте).
python3 tools/gradle_check.py || failed=1
python3 tools/gradle_check.py --self-test || failed=1

step "migration_check.py"
# SQL-миграции Room против сущностей: пропущенная колонка = падение у обновляющихся
# пользователей («Migration didn't properly handle»), а не просто красный тест.
python3 tools/migration_check.py || failed=1

step "static_audit.py"
python3 tools/static_audit.py || failed=1

step "sync_strings.py --check"
# Сравнение с git ломается при незакоммиченных правках строк, поэтому спрашиваем сам скрипт.
python3 tools/sync_strings.py --check || failed=1

step "kotlin_frontend_check.py (опционально)"
if command -v kotlinc >/dev/null 2>&1; then
    # android.jar делает проверку строже; скрипт берёт его из --android-jar / $ANDROID_JAR /
    # $ANDROID_HOME. Скачать его без dl.google.com: ./tools/fetch_android_jar.sh
    jar_args=()
    if [ -n "${ANDROID_JAR:-}" ]; then
        jar_args=(--android-jar "$ANDROID_JAR")
    fi
    python3 tools/kotlin_frontend_check.py ${jar_args[@]+"${jar_args[@]}"} || failed=1
else
    echo "skip: kotlinc не найден (нужен Kotlin 2.1+ и JDK 17)"
fi

step "jvm_unit_test.py (опционально)"
# Реальный прогон тестов чистой логики без Gradle: kotlinc + шим org.junit.
# Нужен там, где нет доступа к Google Maven (например, в песочнице) — Gradle-тесты там не идут.
if command -v kotlinc >/dev/null 2>&1; then
    python3 tools/jvm_unit_test.py --self-test || failed=1
    python3 tools/jvm_unit_test.py || failed=1
else
    echo "skip: kotlinc не найден — JVM-тесты без Gradle пропущены"
fi

step "aapt2_check.py (опционально)"
# Ресурсы и манифест так, как их проверяет Gradle. Нужен aapt2 ≥ 2.20 и android.jar:
# см. docstring tools/aapt2_check.py (npm pack aaptjs3 / Android SDK build-tools).
if command -v aapt2 >/dev/null 2>&1 || [ -n "${AAPT2:-}" ] || [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    # Регресс-тест выбора android.jar: android-9 не должен считаться новее android-35.
    python3 tools/aapt2_check.py --self-test || failed=1
    python3 tools/aapt2_check.py || failed=1
else
    echo "skip: aapt2 не найден (Android SDK build-tools или npm-пакет aaptjs3)"
fi

step "kotlin_analysis.py (опционально)"
python3 tools/kotlin_analysis.py || failed=1

if [ "$failed" -ne 0 ]; then
    echo -e "\nПроверки не прошли."
    exit 1
fi
echo -e "\nВсе доступные проверки пройдены."
