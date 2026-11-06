#!/usr/bin/env python3
"""Ищет ресурсы, на которые никто не ссылается: файлы `res/` и именованные значения.

Зачем: `unused_strings.py` закрыл только строки, а остальное `res/` продолжает жить само по
себе. Картинка, оставшаяся от удалённого экрана, цвет, который никто не читает, стиль под
выключенную тему — всё это попадает в APK, проходит aapt2 (он проверяет **битые** ссылки, а не
отсутствие ссылок) и не мешает сборке. Найти такое руками почти невозможно: имя resources
упоминается не в коде, а через `@drawable/…` в XML, поэтому поиск по проекту ничего не находит.

Что считается ссылкой:

* `R.<тип>.<имя>` в Kotlin/Java;
* `@<тип>/<имя>` в XML (манифест, темы, layout, правила бэкапа).
Файлы `res/values*/*.xml` — источник имён, а не ссылок.

Осознанно оставленные ресурсы вносятся в `tools/unused_resources_allow.txt` строкой
`пакет/имя — причина`; запись без причины и устаревшая запись — ошибки, иначе список не чистится.

    python3 tools/unused_resources.py
    python3 tools/unused_resources.py --verbose
    python3 tools/unused_resources.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app/src"
RES = SOURCES / "main/res"
ALLOWLIST = ROOT / "tools/unused_resources_allow.txt"

# Каталоги `res/`, где ресурс — это файл. `values*` обрабатывается отдельно: там имена.
FILE_TYPES = (
    "drawable", "mipmap", "layout", "xml", "raw", "anim", "animator", "menu", "font",
    "color", "navigation", "transition",
)
# Именованные значения в `values*`: имя есть, файла нет.
VALUE_TYPES = {
    "color": "color",
    "dimen": "dimen",
    "style": "style",
    "bool": "bool",
    "integer": "integer",
    "plurals": "plurals",
    "string-array": "array",
    "integer-array": "array",
    "array": "array",
    "attr": "attr",
}
VALUE_DEF = re.compile(r'<([a-zA-Z-]+)\s+name="([^"]+)"')


def resource_name(path: Path) -> str:
    """`ic_launcher.9.png` → `ic_launcher`; `themes.xml` → `themes`."""
    name = path.name
    for suffix in (".9.png",):
        if name.endswith(suffix):
            return name[: -len(suffix)]
    return path.stem


def collect(res: Path) -> dict[str, set[str]]:
    """`тип → имена`, объявленные в проекте."""
    found: dict[str, set[str]] = {}
    for directory in sorted(res.iterdir()) if res.exists() else []:
        if not directory.is_dir():
            continue
        if directory.name.startswith("values"):
            for xml in directory.glob("*.xml"):
                text = xml.read_text(encoding="utf-8", errors="ignore")
                for tag, name in VALUE_DEF.findall(text):
                    kind = VALUE_TYPES.get(tag)
                    if kind:
                        found.setdefault(kind, set()).add(name)
            continue
        if directory.name in FILE_TYPES:
            for path in directory.iterdir():
                if path.is_file():
                    found.setdefault(directory.name, set()).add(resource_name(path))
    return found


def references(sources: Path) -> set[str]:
    """Все упомянутые `тип/имя` (в виде `тип:имя`), независимо от способа ссылки."""
    used: set[str] = set()
    for path in sources.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".java", ".xml"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for kind, name in re.findall(r"R\.(\w+)\.(\w+)", text):
            used.add(f"{kind}:{name}")
        for kind, name in re.findall(r"@(\w+)/([\w.]+)", text):
            used.add(f"{kind}:{name}")
            # Девятипатчевый файл называется `logo.9.png`, а ссылаются на него как
            # `@drawable/logo` — иначе `logo.9` никогда бы не нашёл ссылку.
            if name.endswith(".9"):
                used.add(f"{kind}:{name[:-2]}")
    return used


def read_allowlist(path: Path) -> dict[str, str]:
    allowed: dict[str, str] = {}
    if not path.exists():
        return allowed
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        for separator in ("—", "#", "  "):
            if separator in stripped:
                name, reason = stripped.split(separator, 1)
                break
        else:
            name, reason = stripped, ""
        allowed[name.strip()] = reason.strip()
    return allowed


def find_unused(found: dict[str, set[str]], used: set[str]) -> list[str]:
    unused: list[str] = []
    for kind in sorted(found):
        for name in sorted(found[kind]):
            # `@color/foo.9` и подобное не встречается, но имя файла могло попасть в XML
            # с суффиксом — поэтому сравнение идёт по имени без расширения.
            if f"{kind}:{name}" not in used:
                unused.append(f"{kind}/{name}")
    return unused


def run(res: Path, sources: Path, allowlist: Path) -> tuple[list[str], int, int]:
    found = collect(res)
    used = references(sources)
    unused = find_unused(found, used)
    allowed = read_allowlist(allowlist)

    errors: list[str] = []
    for item in unused:
        if item in allowed:
            continue
        errors.append(f"res/: ресурс `{item}` нигде не используется")
    for item, reason in allowed.items():
        if not reason:
            errors.append(f"tools/unused_resources_allow.txt: у `{item}` нет причины")
        if item not in unused:
            errors.append(
                f"tools/unused_resources_allow.txt: `{item}` больше не ресурс без ссылок — "
                "запись устарела"
            )
    total = sum(len(names) for names in found.values())
    return errors, total, len(unused)


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        res = root / "app/src/main/res"
        (res / "drawable").mkdir(parents=True)
        (res / "xml").mkdir()
        (res / "values").mkdir()

        (res / "drawable/used_icon.xml").write_text("<vector/>", encoding="utf-8")
        (res / "drawable/dead_icon.xml").write_text("<vector/>", encoding="utf-8")
        (res / "drawable/photo.png").write_text("png", encoding="utf-8")
        (res / "drawable/logo.9.png").write_text("png", encoding="utf-8")
        (res / "xml/backup_rules.xml").write_text("<full-backup/>", encoding="utf-8")
        (res / "values/colors.xml").write_text(
            '<resources>\n'
            '    <color name="used_color">#fff</color>\n'
            '    <color name="dead_color">#000</color>\n'
            '    <style name="AppTheme" parent="X"/>\n'
            '    <string name="title">t</string>\n'
            "</resources>\n",
            encoding="utf-8",
        )

        (root / "app/src/main/java").mkdir(parents=True)
        (root / "app/src/main/java/Screen.kt").write_text(
            "val icon = R.drawable.used_icon\nval c = R.color.used_color\n", encoding="utf-8"
        )
        (root / "app/src/main/AndroidManifest.xml").write_text(
            '<manifest android:fullBackupContent="@xml/backup_rules" '
            'android:theme="@style/AppTheme" '
            'android:icon="@drawable/photo"/>\n',
            encoding="utf-8",
        )

        found = collect(res)
        used = references(root / "app/src")
        unused = find_unused(found, used)

        check("файл, упомянутый через R.*, не считается мёртвым", "drawable/used_icon" not in unused)
        check("файл, упомянутый через @drawable, не считается мёртвым", "drawable/photo" not in unused)
        check("мёртвый файл найден", "drawable/dead_icon" in unused)
        check("style из манифеста не считается мёртвым", "style/AppTheme" not in unused)
        check("мёртвый цвет найден", "color/dead_color" in unused)
        check("строки здесь не проверяются", not any(item.startswith("string/") for item in unused))
        check(
            "имя nine-patch берётся без суффикса",
            "logo" in found["drawable"] and "logo.9" not in found["drawable"],
        )

        allow = root / "allow.txt"
        allow.write_text(
            "drawable/dead_icon — задел на следующий экран\n"
            "color/dead_color — задел на следующий экран\n"
            "drawable/logo — nine-patch без ссылок\n",
            encoding="utf-8",
        )
        errors, _, _ = run(res, root / "app/src", allow)
        check("разрешённые ресурсы не считаются ошибкой", errors == [])
        # Стиль, на который ссылаются из манифеста, обязан считаться используемым: имя с
        # точками (`Theme.PhoneGuard`) не должно обрезаться до `Theme`.
        check("составное имя стиля не теряет хвост", "style/AppTheme" not in unused)

        allow.write_text("drawable/dead_icon\n", encoding="utf-8")
        errors, _, _ = run(res, root / "app/src", allow)
        check("запись без причины — ошибка", any("нет причины" in error for error in errors))

        allow.write_text("drawable/used_icon — давно не нужен\n", encoding="utf-8")
        _ = unused
        errors, _, _ = run(res, root / "app/src", allow)
        check("устаревшая запись — ошибка", any("устарела" in error for error in errors))

    if problems:
        for problem in problems:
            print(f"error: unused_resources --self-test: {problem}")
        return 1
    print(
        "unused_resources --self-test: ok (ссылки R.* и @type/, мёртвый файл, мёртвый цвет, "
        "nine-patch, allow-файл)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать всё, включая разрешённое")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not RES.exists():
        print(f"error: {RES} не найден")
        return 1

    errors, total, unused_count = run(RES, SOURCES, ALLOWLIST)
    for problem in errors:
        print(f"error: {problem}")
    if args.verbose:
        allowed = read_allowlist(ALLOWLIST)
        for item, reason in sorted(allowed.items()):
            print(f"allow: {item} — {reason}")

    print(
        f"\n{len(errors)} error(s), 0 warning(s): {total - unused_count} из {total} ресурсов "
        f"используются, {unused_count} без ссылок"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
