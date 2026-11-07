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

step "proguard_check.py"
# Правила R8 проверяются только релизной сборкой, которой в песочнице нет: правило для
# несуществующего пакета создаёт «защиту без защиты», а отсутствие правила для рефлексии
# падает лишь у пользователя релизной сборки.
python3 tools/proguard_check.py || failed=1
python3 tools/proguard_check.py --self-test || failed=1

step "migration_check.py"
# SQL-миграции Room против сущностей: пропущенная колонка = падение у обновляющихся
# пользователей («Migration didn't properly handle»), а не просто красный тест.
python3 tools/migration_check.py || failed=1

step "static_audit.py"
python3 tools/static_audit.py || failed=1
python3 tools/static_audit.py --self-test || failed=1

step "ci_check.py"
# Конфигурации CI против проекта: `|| true` после тестов, задача без флейвора, отсутствие
# Android SDK и «в этом CI нет половины проверок» — всё это делает пайплайн вечнозелёным.
python3 tools/ci_check.py || failed=1
python3 tools/ci_check.py --self-test || failed=1

step "docs_check.py"
# Цифры в README/docs против кода: «217 файлов», «106 из 167 классов» стареют молча.
python3 tools/docs_check.py || failed=1

step "unused_strings.py"
# Строка, добавленная под удалённый экран, живёт годами: компилятор о ней молчит, а переводы и
# словарь sync_strings.py продолжают её сопровождать. Так накопилось 79 мёртвых строк из 710.
python3 tools/unused_strings.py || failed=1
python3 tools/unused_strings.py --self-test || failed=1

step "unused_resources.py"
# Ресурс, на который никто не ссылается, попадает в APK: 21 цвет Material-палитры и стиль
# прозрачной темы ехали в каждой сборке. Строки проверяет unused_strings.py, здесь — остальное.
python3 tools/unused_resources.py || failed=1
python3 tools/unused_resources.py --self-test || failed=1

step "docs_links.py"
# Ссылка и путь в документации ломаются молча: инструкция «открой mapping/release» уводит
# в несуществующий каталог, и релиз выглядит несобранным.
python3 tools/docs_links.py || failed=1
python3 tools/docs_links.py --self-test || failed=1

step "power_audit.py"
# Тихие расходы батареи: периодическая работа чаще 15 минут (WorkManager молча поднимает
# интервал), точные будильники, `acquire()` без таймаута, приёмник без unregister.
python3 tools/power_audit.py || failed=1
python3 tools/power_audit.py --self-test || failed=1

step "hilt_check.py"
# Разрыв в графе Hilt — не ошибка компиляции: сборка зелёная, падение на генерации кода.
# Так уже дважды терялись биндинги (`IntrusionResponseExecutor`, `ShoulderSurferController`).
python3 tools/hilt_check.py || failed=1
python3 tools/hilt_check.py --self-test || failed=1

step "mockk_audit.py"
# MockK-файл не выполняется в офлайн-прогоне, поэтому их число — долг, а не стиль. Бюджет
# падает, когда тест написан без заглушек, и не даёт вырасти «по привычке».
python3 tools/mockk_audit.py || failed=1
python3 tools/mockk_audit.py --self-test || failed=1

step "manual_check.py --self-test"
# Раздел 13 ручного чек-листа: правила проверяются на синтетике, живые проверки требуют
# APK и устройства (python3 tools/manual_check.py --device).
python3 tools/manual_check.py --self-test || failed=1

step "release_check.py"
# Связка «CI пишет keystore сюда → Gradle ищет там», «CI проверяет mapping/*Release», «док
# велит открыть mapping/release» ломается только на реальной релизной сборке.
python3 tools/release_check.py || failed=1
python3 tools/release_check.py --self-test || failed=1

step "junit_gate.py"
# Gradle возвращает 0, даже если тест-класс не попал в прогон: отчёт зелёный, а класса в нём нет.
# Локально отчёта нет — сверка пропускается с сообщением, в CI она обязательна
# (`--require-report`). Здесь проверяются сами правила на синтетике.
python3 tools/junit_gate.py || failed=1
python3 tools/junit_gate.py --self-test || failed=1

step "sync_strings.py --check"
# Сравнение с git ломается при незакоммиченных правках строк, поэтому спрашиваем сам скрипт.
python3 tools/sync_strings.py --check || failed=1

step "kotlin_frontend_check.py (опционально)"
# Классификация сообщений компилятора не требует kotlinc — проверяем её всегда.
python3 tools/kotlin_frontend_check.py --self-test || failed=1
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
