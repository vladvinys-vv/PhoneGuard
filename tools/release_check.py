#!/usr/bin/env python3
"""Статическая проверка готовности релизного пути: подпись, R8, версия, mapping, 16 КБ.

Зачем: обычные проверки видят код и правила, но не видят **связку** «CI пишет keystore сюда →
Gradle ищет его там», «CI проверяет `mapping/*Release/mapping.txt` → задача называется
`productionRelease`», «документация велит открыть `mapping/release/mapping.txt` → такого каталога
не бывает». Всё это ломается только на реальной релизной сборке, то есть в самый дорогой момент:
артефакт либо не подписан, либо mapping не найден, либо инструкция из `docs/RELEASE.md` ведёт в
пустоту.

Проверка чисто статическая (её можно запускать без Gradle и без сети):

* `app/build.gradle` — `minifyEnabled`/`shrinkResources`, `proguardFiles`, `signingConfig`
  только при наличии keystore, guard против молча неподписанного релиза, версия из
  `pgVersionCode`/`pgVersionName` с проверкой числа, отсутствие паролей в файле;
* `codemagic.yaml` — keystore материализуется **до** релизной сборки, mapping и 16 КБ
  проверяются **после**, тег проверяется на формат, `-PpgVersionCode`/`-PpgVersionName`
  передаются, артефакты включают `mapping/**` и `.aab`;
* **сквозные пути:** файл, который пишет CI, и `storeFile` в сгенерированном
  `keystore.properties` разрешаются в один и тот же путь; `keystore.properties` лежит там, где
  его ищет Gradle (`rootProject`), и оба файла в `.gitignore`;
* `docs/RELEASE.md` — каталог в командах `--mapping` существует среди флейворов проекта
  (`productionRelease`, а не `release`).

    python3 tools/release_check.py
    python3 tools/release_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app/build.gradle"
PROGUARD = ROOT / "app/proguard-rules.pro"
CODEMAGIC = ROOT / "codemagic.yaml"
GITIGNORE = ROOT / ".gitignore"
RELEASE_DOC = ROOT / "docs/RELEASE.md"

FLAVOR_BLOCK = re.compile(r"productFlavors\s*\{(.*?)\n\s{4}\}", re.S)
FLAVOR_NAME = re.compile(r"^\s{8}(\w+)\s*\{", re.M)
MAPPING_CMD = re.compile(r"--mapping\s+([^\s`]+)")
CI_KEYSTORE_TARGET = re.compile(r">\s*\"?\$CM_BUILD_DIR/([^\s\"]+)\"?")
STORE_FILE = re.compile(r"storeFile=([^\s\"]+)")


def flavors(gradle: str) -> list[str]:
    block = FLAVOR_BLOCK.search(gradle)
    if not block:
        return []
    return FLAVOR_NAME.findall(block.group(1))


def check_gradle(gradle: str, proguard: Path) -> list[str]:
    errors: list[str] = []
    release_block = gradle[gradle.find("buildTypes"):] if "buildTypes" in gradle else ""

    if "minifyEnabled = true" not in release_block:
        errors.append(
            "app/build.gradle: у release нет `minifyEnabled = true` — обфускации не будет, "
            "и релиз будет отличаться от debug только отсутствием отладки"
        )
    if "shrinkResources = true" not in release_block:
        errors.append("app/build.gradle: у release нет `shrinkResources = true` — APK несёт неиспользуемые ресурсы")
    if "proguard-android-optimize.txt" not in release_block:
        errors.append(
            "app/build.gradle: `proguardFiles` не подключает `proguard-android-optimize.txt` — "
            "останутся отладочные имена и лишние проверки"
        )
    if "proguard-rules.pro" not in release_block:
        errors.append("app/build.gradle: `proguardFiles` не подключает `proguard-rules.pro` — правила проекта не применятся")
    if not proguard.exists():
        errors.append("app/proguard-rules.pro отсутствует — R8 пойдёт без правил проекта")

    if "if (hasReleaseKeystore)" not in release_block:
        errors.append(
            "app/build.gradle: `signingConfig` назначается без проверки `hasReleaseKeystore` — "
            "сборка упадёт на пустых паролях вместо понятного сообщения"
        )
    if "allowUnsignedRelease" not in gradle:
        errors.append(
            "app/build.gradle: нет флага `-PallowUnsignedRelease=true` — неподписанный релиз "
            "нельзя отличить от подписанного"
        )
    if "GradleException" not in gradle:
        errors.append("app/build.gradle: guard неподписанного релиза не бросает GradleException — молчаливый неподписанный APK вернётся")

    if 'findProperty("pgVersionCode")' not in gradle:
        errors.append("app/build.gradle: versionCode не берётся из `pgVersionCode` — у всех релизов будет одно число")
    if 'findProperty("pgVersionName")' not in gradle:
        errors.append("app/build.gradle: versionName не берётся из `pgVersionName` — версия разойдётся с тегом")
    if "==~ /\\d+/" not in gradle:
        errors.append(
            "app/build.gradle: `pgVersionCode` не проверяется на число — нечисловое значение "
            "дойдёт до `toInteger()` и упадёт невнятно"
        )

    # Пароли не должны попадать в репозиторий даже в виде значений по умолчанию.
    for match in re.finditer(r"(storePassword|keyPassword)\s*=\s*\"([^\"]+)\"", gradle):
        errors.append(
            f"app/build.gradle: у `{match.group(1)}` зашито значение — секреты должны приходить "
            "из keystore.properties или переменных окружения"
        )

    if "targetSdk = 35" not in gradle:
        errors.append("app/build.gradle: targetSdk не 35 — требование Play к 16 КБ страницам относится именно к 35")
    return errors


def check_codemagic(text: str) -> list[str]:
    errors: list[str] = []
    if not text:
        errors.append("codemagic.yaml отсутствует — релизная сборка описана только в документации")
        return errors

    def position(needle: str) -> int:
        found = text.find(needle)
        return found if found >= 0 else -1

    keystore = position("Materialise release keystore")
    assemble = position("assembleProductionRelease")
    mapping = position("--mapping")
    alignment = position("-P 16")
    if keystore < 0:
        errors.append("codemagic.yaml: нет шага материализации keystore — релиз соберётся неподписанным")
    if assemble < 0:
        errors.append("codemagic.yaml: нет `assembleProductionRelease` — релизный APK не собирается")

    # Порядок шагов — это и есть проверка: keystore нужен до сборки, отчёт mapping и
    # выравнивание — только после неё.
    if keystore >= 0 and assemble >= 0 and keystore > assemble:
        errors.append("codemagic.yaml: keystore создаётся после релизной сборки — подписи в артефакте не будет")
    if mapping < 0:
        errors.append("codemagic.yaml: mapping.txt не проверяется — обфускация останется непроверенной")
    elif assemble >= 0 and mapping < assemble:
        errors.append("codemagic.yaml: `--mapping` вызывается до релизной сборки — файла ещё нет")
    if alignment < 0:
        errors.append("codemagic.yaml: нет проверки выравнивания 16 КБ на собранном APK")

    if "-PpgVersionCode" not in text:
        errors.append("codemagic.yaml: не передаётся `-PpgVersionCode` — у релизов будет один versionCode")
    if "-PpgVersionName" not in text:
        errors.append("codemagic.yaml: не передаётся `-PpgVersionName` — версия разойдётся с тегом")
    if "v[0-9]*.[0-9]*.[0-9]*" not in text:
        errors.append("codemagic.yaml: формат тега не проверяется — `v1.2` даст мусорную версию")

    if "app/build/outputs/mapping/**" not in text:
        errors.append("codemagic.yaml: `mapping/**` не в артефактах — стек-трейс релиза не разобрать")
    if "*.aab" not in text:
        errors.append("codemagic.yaml: `.aab` не в артефактах — для Play нечего публиковать")
    if "allowUnsignedRelease" not in text:
        errors.append("codemagic.yaml: не передаётся `-PallowUnsignedRelease` — проверочная сборка без keystore упадёт")
    return errors


def check_keystore_paths(codemagic: str, gradle: str) -> list[str]:
    """CI пишет keystore и `keystore.properties` — Gradle должен искать ровно их."""
    errors: list[str] = []
    targets = CI_KEYSTORE_TARGET.findall(codemagic)
    store = STORE_FILE.findall(codemagic)
    if not targets:
        errors.append("codemagic.yaml: не видно, куда пишется keystore (ожидался `> \"$CM_BUILD_DIR/…\"`)")
        return errors
    if not store:
        errors.append("codemagic.yaml: `keystore.properties` не содержит `storeFile=…`")
        return errors

    written = targets[0]
    # `storeFile` в Gradle разрешается относительно каталога модуля (`app/`).
    resolved = str(Path("app") / store[0])
    if Path(written).as_posix() != Path(resolved).as_posix():
        errors.append(
            f"codemagic.yaml: keystore пишется в `{written}`, а `storeFile={store[0]}` "
            f"разрешается в `{resolved}` — Gradle не найдёт файл и релиз соберётся без подписи"
        )

    if 'rootProject.file("keystore.properties")' not in gradle:
        errors.append("app/build.gradle: `keystore.properties` ищется не в корне проекта")
    if not any(target.endswith("keystore.properties") for target in targets):
        errors.append(
            "codemagic.yaml: `keystore.properties` пишется не в корень проекта — "
            "`rootProject.file(\"keystore.properties\")` его не увидит"
        )
    return errors


KEYS_IN_DOC = re.compile(r"(\d+)\s+keys in each of")


def check_keys_count(doc: str, actual: int) -> list[str]:
    """Число строк в `docs/RELEASE.md` должно совпадать с `strings.xml` — иначе инструкция врёт."""
    errors: list[str] = []
    for stated in KEYS_IN_DOC.findall(doc):
        if int(stated) != actual:
            errors.append(
                f"docs/RELEASE.md: указано {stated} строк, фактически {actual} — "
                "`sync_strings.py --check` сверяет все четыре локали, и число в инструкции "
                "должно быть тем же"
            )
    return errors


RELEASE_TASK = re.compile(r"gradlew[^\n]*(?:assemble|bundle)\w*Release")


def check_ci_release_builds(configs: dict[str, str]) -> list[str]:
    """Любой CI, собирающий `*Release`, обязан либо подписать, либо явно разрешить неподписанный.

    Guard в `app/build.gradle` падает на `assemble*Release` без keystore — иначе неподписанный
    APK уходил бы в артефакты молча. Значит job, который релиз собирает и про keystore не знает,
    красный при каждом запуске; заметить это можно только по логу, если этот CI не основной.
    """
    errors: list[str] = []
    for name, text in configs.items():
        if not RELEASE_TASK.search(text):
            continue
        has_keystore = "KEYSTORE_BASE64" in text or "keystore.properties" in text
        has_unsigned_flag = "allowUnsignedRelease" in text
        if not has_keystore and not has_unsigned_flag:
            errors.append(
                f"{name}: собирается release-задача без keystore и без `-PallowUnsignedRelease=true` — "
                "guard в app/build.gradle уронит этот job"
            )
        if "--mapping" not in text:
            errors.append(
                f"{name}: release-задача собирается, но `mapping.txt` не проверяется — "
                "обфускация останется непроверенной"
            )
    return errors


def check_gitignore(gitignore: str) -> list[str]:
    errors: list[str] = []
    if not gitignore:
        return ["`.gitignore` отсутствует — keystore может попасть в репозиторий"]
    for pattern in ("keystore.properties", "*.jks", "*.keystore"):
        if pattern not in gitignore:
            errors.append(f".gitignore: `{pattern}` не игнорируется — секрет может попасть в репозиторий")
    return errors


def check_docs(doc: str, flavor_names: list[str], extra_docs: dict[str, str] | None = None) -> list[str]:
    """Команды из документации должны указывать на реально существующий каталог mapping."""
    errors: list[str] = []
    if not doc:
        return ["docs/RELEASE.md отсутствует — релизный путь не описан"]
    documents = {"docs/RELEASE.md": doc}
    documents.update(extra_docs or {})
    for name, text in documents.items():
        for path in MAPPING_CMD.findall(text):
            if "*" in path:
                continue
            match = re.search(r"mapping/(\w+)/mapping\.txt$", path)
            if not match:
                continue
            variant = match.group(1)
            known = {f"{flavor}Release" for flavor in flavor_names}
            if variant not in known:
                errors.append(
                    f"{name}: `--mapping {path}` — каталога `{variant}` не бывает: "
                    f"у флейворов проекта mapping лежит в {sorted(known)}"
                )
    return errors

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def run(root: Path) -> list[str]:
    gradle_path = root / "app/build.gradle"
    gradle = gradle_path.read_text(encoding="utf-8") if gradle_path.exists() else ""
    if not gradle:
        return ["app/build.gradle не найден — проверять нечего"]

    codemagic_path = root / "codemagic.yaml"
    codemagic = codemagic_path.read_text(encoding="utf-8") if codemagic_path.exists() else ""
    gitignore_path = root / ".gitignore"
    gitignore = gitignore_path.read_text(encoding="utf-8") if gitignore_path.exists() else ""
    doc_path = root / "docs/RELEASE.md"
    doc = doc_path.read_text(encoding="utf-8") if doc_path.exists() else ""

    names = flavors(gradle)
    errors = check_gradle(gradle, root / "app/proguard-rules.pro")
    errors += check_codemagic(codemagic)
    errors += check_ci_release_builds({
        ".github/workflows/android-ci.yml": read(root / ".github/workflows/android-ci.yml"),
        ".gitlab-ci.yml": read(root / ".gitlab-ci.yml"),
        "codemagic.yaml": codemagic,
    })
    errors += check_keystore_paths(codemagic, gradle)
    errors += check_gitignore(gitignore)
    strings_xml = root / "app/src/main/res/values/strings.xml"
    if strings_xml.exists():
        actual = len(re.findall(r'<string name="', strings_xml.read_text(encoding="utf-8")))
        errors += check_keys_count(doc, actual)
    errors += check_docs(doc, names, {
        "README.md": read(root / "README.md"),
        "docs/CHANGELOG.md": read(root / "docs/CHANGELOG.md"),
    })
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    errors = run(ROOT)
    for problem in errors:
        print(f"error: {problem}")
    print(f"\n{len(errors)} error(s), 0 warning(s): релизный путь (подпись, R8, версия, mapping, 16 КБ)")
    return 1 if errors else 0


GRADLE_GOOD = '''
def keystorePropertiesFile = rootProject.file("keystore.properties")
def hasReleaseKeystore = releaseStorePath && file(releaseStorePath).isFile()
def versionCodeValue = (project.findProperty("pgVersionCode") ?: 1).toString()
def versionNameValue = (project.findProperty("pgVersionName") ?: "1.0.0").toString()
if (!(versionCodeValue ==~ /\\d+/) || versionCodeValue.toInteger() < 1) {
    throw new GradleException("pgVersionCode")
}
def allowUnsignedRelease = project.hasProperty("allowUnsignedRelease")
android {
    compileSdk = 35
    defaultConfig {
        targetSdk = 35
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            release {
                storeFile = file(releaseStorePath)
                storePassword = keystoreProperties['storePassword'] ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            minifyEnabled = true
            shrinkResources = true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.release
            }
        }
    }
    flavorDimensions "environment"
    productFlavors {
        staging {
            dimension = "environment"
        }
        production {
            dimension = "environment"
        }
    }
}
gradle.taskGraph.whenReady { graph -> }
'''

CODEMAGIC_GOOD = '''
    scripts:
      - name: Check tag
        script: |
          case "$CM_TAG" in
            v[0-9]*.[0-9]*.[0-9]*) ;;
          esac
      - name: Materialise release keystore
        script: |
          printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$CM_BUILD_DIR/app/release.keystore"
          {
            echo "storeFile=release.keystore"
            echo "storePassword=${KEYSTORE_PASSWORD:-}"
          } > "$CM_BUILD_DIR/keystore.properties"
      - name: Build production release APK and bundle
        script: |
          ./gradlew assembleProductionRelease bundleProductionRelease \\
            -PpgVersionCode="$VERSION_CODE" -PpgVersionName="$VERSION_NAME" \\
            $UNSIGNED -PallowUnsignedRelease=true
      - name: Verify R8 obfuscation (mapping.txt)
        script: |
          python3 tools/proguard_check.py --mapping "$(ls app/build/outputs/mapping/*Release/mapping.txt)"
      - name: Verify 16 KB page alignment
        script: |
          "$ZIPALIGN" -c -P 16 -v 4 "$APK"
    artifacts:
      - app/build/outputs/**/*.apk
      - app/build/outputs/**/*.aab
      - app/build/outputs/mapping/**
'''

GITIGNORE_GOOD = "*.keystore\n*.jks\nkeystore.properties\n"
DOC_GOOD = "python3 tools/proguard_check.py --mapping app/build/outputs/mapping/productionRelease/mapping.txt\n"


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "app").mkdir(parents=True)
        (root / "docs").mkdir()
        (root / "app/build.gradle").write_text(GRADLE_GOOD, encoding="utf-8")
        (root / "app/proguard-rules.pro").write_text("-keep class x { *; }\n", encoding="utf-8")
        (root / "codemagic.yaml").write_text(CODEMAGIC_GOOD, encoding="utf-8")
        (root / ".gitignore").write_text(GITIGNORE_GOOD, encoding="utf-8")
        (root / "docs/RELEASE.md").write_text(DOC_GOOD, encoding="utf-8")

        check("правильный релизный путь ошибок не даёт", run(root) == [])

        # Документация с несуществующим каталогом — ровно та ошибка, что была в проекте.
        (root / "docs/RELEASE.md").write_text(
            "python3 tools/proguard_check.py --mapping app/build/outputs/mapping/release/mapping.txt\n",
            encoding="utf-8",
        )
        errors = run(root)
        check("каталог mapping без флейвора в документации пойман", any("release" in e and "mapping" in e for e in errors))
        (root / "docs/RELEASE.md").write_text(DOC_GOOD, encoding="utf-8")

        # keystore пишется не туда, куда смотрит storeFile.
        broken = CODEMAGIC_GOOD.replace('"$CM_BUILD_DIR/app/release.keystore"', '"$CM_BUILD_DIR/release.keystore"')
        (root / "codemagic.yaml").write_text(broken, encoding="utf-8")
        check("несовпадение path keystore и storeFile поймано", any("storeFile" in e for e in run(root)))
        (root / "codemagic.yaml").write_text(CODEMAGIC_GOOD, encoding="utf-8")

        # keystore создаётся после сборки.
        # Сборка стоит раньше материализации keystore — артефакт будет неподписанным.
        late = CODEMAGIC_GOOD.replace(
            "      - name: Materialise release keystore",
            "      - name: Build production release APK and bundle\n"
            "        script: ./gradlew assembleProductionRelease\n"
            "      - name: Materialise release keystore",
        )
        (root / "codemagic.yaml").write_text(late, encoding="utf-8")
        check("keystore после сборки поймано", any("после релизной сборки" in e for e in run(root)))
        (root / "codemagic.yaml").write_text(CODEMAGIC_GOOD, encoding="utf-8")

        # Выключенная обфускация.
        (root / "app/build.gradle").write_text(GRADLE_GOOD.replace("minifyEnabled = true", "minifyEnabled = false"), encoding="utf-8")
        check("minifyEnabled = false поймано", any("minifyEnabled" in e for e in run(root)))
        (root / "app/build.gradle").write_text(GRADLE_GOOD, encoding="utf-8")

        # Пароль в файле сборки.
        (root / "app/build.gradle").write_text(
            GRADLE_GOOD.replace("storePassword = keystoreProperties['storePassword'] ?: System.getenv(\"KEYSTORE_PASSWORD\")", "storePassword = \"hunter2\""),
            encoding="utf-8",
        )
        check("зашитый пароль пойман", any("зашито значение" in e for e in run(root)))
        (root / "app/build.gradle").write_text(GRADLE_GOOD, encoding="utf-8")

        # Число строк в инструкции разошлось с strings.xml.
        (root / "app/src/main/res/values").mkdir(parents=True)
        (root / "app/src/main/res/values/strings.xml").write_text(
            '<resources>\n    <string name="a">a</string>\n</resources>\n',
            encoding="utf-8",
        )
        (root / "docs/RELEASE.md").write_text(DOC_GOOD + "`sync_strings.py --check` (645 keys in each of them)\n", encoding="utf-8")
        check("число строк в инструкции сверяется с strings.xml", any("645 строк" in e for e in run(root)))
        (root / "docs/RELEASE.md").write_text(DOC_GOOD, encoding="utf-8")

        # Ни GitHub Actions, ни GitLab не собирают release — правило не должно ругаться.
        check("CI без release-задач не ругается", run(root) == [])

        # CI собирает release без keystore и без явного флага — guard уронит job.
        (root / ".github/workflows").mkdir(parents=True)
        (root / ".github/workflows/android-ci.yml").write_text(
            "      - run: ./gradlew assembleProductionRelease --no-daemon\n"
            "      - run: python3 tools/proguard_check.py --mapping app/build/outputs/mapping/productionRelease/mapping.txt\n",
            encoding="utf-8",
        )
        check(
            "release без keystore и без флага поймано",
            any("allowUnsignedRelease" in e and "android-ci.yml" in e for e in run(root)),
        )
        (root / ".github/workflows/android-ci.yml").write_text(
            "      - run: ./gradlew assembleProductionRelease -PallowUnsignedRelease=true\n"
            "      - run: python3 tools/proguard_check.py --mapping app/build/outputs/mapping/productionRelease/mapping.txt\n",
            encoding="utf-8",
        )
        check("явный флаг снимает замечание", run(root) == [])
        (root / ".github/workflows/android-ci.yml").unlink()

        # keystore перестал игнорироваться.
        (root / ".gitignore").write_text("*.keystore\n", encoding="utf-8")
        check("не полный .gitignore пойман", any("keystore.properties" in e for e in run(root)))
        (root / ".gitignore").write_text(GITIGNORE_GOOD, encoding="utf-8")

        # В артефактах нет mapping.
        (root / "codemagic.yaml").write_text(CODEMAGIC_GOOD.replace("      - app/build/outputs/mapping/**\n", ""), encoding="utf-8")
        check("mapping без артефакта пойман", any("mapping/**" in e for e in run(root)))
        (root / "codemagic.yaml").write_text(CODEMAGIC_GOOD, encoding="utf-8")

    if problems:
        for problem in problems:
            print(f"error: release_check --self-test: {problem}")
        return 1
    print(
        "release_check --self-test: ok (minify, подпись, версия, пароли, пути keystore, "
        ".gitignore, артефакты mapping, каталог в документации, release без флага)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
