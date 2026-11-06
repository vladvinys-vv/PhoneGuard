#!/usr/bin/env python3
"""Проверяет, что ссылки и пути в документации существуют.

Зачем: документация стареет молча. В `docs/RELEASE.md` команда проверки mapping вела в каталог
`mapping/release/`, которого у проекта с флейворами не бывает; рядом было «645 строк» при
фактических 635. Такую инструкцию выполняют руками, получают «файла нет» и делают неправильный
вывод — что обфускация не выполнялась. Компилятор о документации молчит, поэтому проверять её
приходится отдельно.

Что проверяется:

* markdown-ссылки `[текст](путь)` — файл или каталог должен существовать (внешние `http(s)`,
  `mailto:` и якоря `#…` пропускаются);
* пути в обратных кавычках, которые выглядят как путь репозитория (`app/…`, `tools/…`,
  `docs/…`, `.github/…`, корневые файлы сборки) — должны существовать.

Шаблоны (`*`, `$…`, `{…}`), примеры путей из будущего и внешние URL не проверяются: поддельная
проверка хуже её отсутствия.

    python3 tools/docs_links.py
    python3 tools/docs_links.py --verbose
    python3 tools/docs_links.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# CHANGELOG намеренно не проверяется: это история, и путь в нём может быть путём того
# времени (`ci.yml` до разделения на три конфигурации), а не текущего дерева.
DOCS = ("README.md", "docs/DEVELOPMENT.md", "docs/RELEASE.md", "docs/MANUAL_TESTING.md",
        "docs/CI_WEBHOOKS.md", "docs/PLAY_STORE_LISTING.md", "docs/PRIVACY_POLICY.md")

# Появляются при сборке или у конкретного разработчика, в репозитории их быть не должно.
GENERATED = (
    "keystore.properties", "local.properties", "app/release.keystore", "release.keystore",
)
GENERATED_PREFIXES = ("app/build/", "build/", ".gradle/", ".tmp")

MD_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
CODE_SPAN = re.compile(r"`([^`\n]+)`")
ROOT_FILES = (
    "build_release.sh", "codemagic.yaml", "gradlew", "gradle.properties", "settings.gradle",
    "build.gradle", "README.md", "CHANGELOG.md", ".gitlab-ci.yml", "keystore.properties",
)
SKIP_MARKERS = ("*", "$", "{", "}", "<", ">", "…", "\\", "|", "?", "«", "»")


def looks_like_path(token: str) -> bool:
    token = token.strip()
    if not token or any(marker in token for marker in SKIP_MARKERS):
        return False
    if token.startswith(("http://", "https://", "mailto:", "#", "@")):
        return False
    if token.startswith("/") or token.endswith("/"):
        return False
    if token in GENERATED or token.startswith(GENERATED_PREFIXES):
        return False
    if token in ROOT_FILES:
        return True
    head = token.split("/", 1)[0]
    if "/" in token and head in {"app", "tools", "docs", ".github"}:
        # `app/src/main/java/com/phoneguard/…` — путь; `app/build/outputs/.../x.apk` — тоже.
        return "." in token.rsplit("/", 1)[-1] or token == token.rstrip("/")
    return False


def check_file(path: Path, text: str, root: Path) -> list[str]:
    errors: list[str] = []
    base = path.parent

    for target in MD_LINK.findall(text):
        clean = target.split("#", 1)[0].strip()
        if not clean or clean.startswith(("http://", "https://", "mailto:", "#")):
            continue
        resolved = (base / clean).resolve()
        if not resolved.exists():
            errors.append(f"{relative(path, root)}: ссылка ведёт в никуда — `{target}`")

    for token in CODE_SPAN.findall(text):
        # В кавычках часто команда целиком (`tools/docs_check.py --verbose`) — проверяем
        # только путь, то есть первое слово.
        candidate = token.strip().split(" ", 1)[0].rstrip(".,;:)")
        if not looks_like_path(candidate):
            continue
        if (root / candidate).exists() or (base / candidate).exists():
            continue
        errors.append(f"{relative(path, root)}: путь `{candidate}` не существует")
    return errors


def relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.name


def run(root: Path, docs: tuple[str, ...] = DOCS) -> list[str]:
    errors: list[str] = []
    for name in docs:
        path = root / name
        if not path.exists():
            errors.append(f"{name}: файла нет — документация ссылается на несуществующий документ")
            continue
        errors += check_file(path, path.read_text(encoding="utf-8"), root)
    return errors


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "docs").mkdir()
        (root / "tools").mkdir()
        (root / "app/src/main/java").mkdir(parents=True)
        (root / "tools/check_all.sh").write_text("#!/bin/sh\n", encoding="utf-8")
        (root / "app/src/main/java/Screen.kt").write_text("class Screen\n", encoding="utf-8")
        (root / "docs/OTHER.md").write_text("# other\n", encoding="utf-8")

        (root / "README.md").write_text(
            "[инструменты](tools/check_all.sh)\n"
            "[внешнее](https://example.com)\n"
            "[якорь](#раздел)\n"
            "Файл `app/src/main/java/Screen.kt` и скрипт `tools/check_all.sh`.\n",
            encoding="utf-8",
        )
        errors = run(root, ("README.md",))
        check("правильные ссылки и пути ошибок не дают", errors == [])

        (root / "README.md").write_text("[нет](docs/GONE.md)\n", encoding="utf-8")
        check("битая ссылка найдена", any("GONE.md" in error for error in run(root, ("README.md",))))

        (root / "README.md").write_text("Файл `app/src/main/java/Missing.kt`.\n", encoding="utf-8")
        check("несуществующий путь найден", any("Missing.kt" in error for error in run(root, ("README.md",))))

        (root / "README.md").write_text("Маска `app/build/outputs/**/*.apk` и `$CM_BUILD_DIR/x`.\n", encoding="utf-8")
        check("шаблоны и переменные не считаются путями", run(root, ("README.md",)) == [])

        errors = run(root, ("docs/ABSENT.md",))
        check("отсутствующий документ из списка — ошибка", any("ABSENT.md" in error for error in errors))

        check(
            "путь вида docs/OTHER.md распознаётся",
            looks_like_path("docs/OTHER.md") and not looks_like_path("http://x/y"),
        )

    if problems:
        for problem in problems:
            print(f"error: docs_links --self-test: {problem}")
        return 1
    print(
        "docs_links --self-test: ok (рабочие ссылки, битая ссылка, отсутствующий путь, "
        "шаблоны и переменные, отсутствующий документ)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать проверяемые документы")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    errors = run(ROOT)
    for problem in errors:
        print(f"error: {problem}")
    if args.verbose:
        for name in DOCS:
            print(f"проверен: {name}")
    print(f"\n{len(errors)} error(s), 0 warning(s): ссылки и пути в {len(DOCS)} документах")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
