#!/usr/bin/env python3
"""Ищет строковые ресурсы, которые никто не читает.

Зачем: `values/strings.xml` — единственный файл, который растёт сам по себе. Строку добавили
под экран, экран переделали, строку забыли; переводы на четыре локали при этом продолжают
сопровождаться, а `sync_strings.py` считает её частью словаря. Компилятор молчит: неиспользуемая
строка — не ошибка. В этом проекте так накопилось 79 мёртвых строк из 710 (11 %), включая целые
блоки: команды `#LOCK`/`#ALARM`/`#WIPE` с описаниями, тарифы Pro (`upgrade_to_pro`,
`unlock_pro`, `one_time_purchase`), согласие на аналитику и причины блокировки звонка.

Что считается использованием: `R.string.<name>` в Kotlin/Java и `@string/<name>` в XML.
Динамического поиска строк в проекте нет (`getIdentifier`, `getStringArray`, `typedArray` не
используются), поэтому «не упомянута» здесь означает «недостижима» — эту проверку и стоит
сохранять.

Осознанно оставленные строки (задел на будущее, строки для документации) вносятся в
`tools/unused_strings_allow.txt` с причиной: молчаливый список исключений превратился бы в
свалку. Устаревшая запись в этом файле — тоже ошибка: иначе список не чистится никогда.

    python3 tools/unused_strings.py
    python3 tools/unused_strings.py --verbose
    python3 tools/unused_strings.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app/src"
DEFAULT_STRINGS = SOURCES / "main/res/values/strings.xml"
ALLOWLIST = ROOT / "tools/unused_strings_allow.txt"

STRING_DEF = re.compile(r'<string name="([^"]+)">')
# В XML строка может быть подключена как `@string/name`, в коде — как `R.string.name`.
REFERENCE_TEMPLATES = ("R.string.{name}", "@string/{name}")


def default_names(res_file: Path) -> list[str]:
    """Имена строк по умолчанию (порядок файла сохраняется: так список читаемее)."""
    text = res_file.read_text(encoding="utf-8")
    return STRING_DEF.findall(text)


def source_texts(sources: Path) -> str:
    """Содержимое всех исходников и ресурсов — то, где строка может быть использована."""
    parts: list[str] = []
    for path in sources.rglob("*"):
        if path.suffix in {".kt", ".java", ".xml"} and path.is_file():
            parts.append(path.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(parts)


def is_used(name: str, blob: str) -> bool:
    return any(template.format(name=name) in blob for template in REFERENCE_TEMPLATES)


def read_allowlist(path: Path) -> dict[str, str]:
    """`имя — причина` (разделитель `—`, `#` или два пробела). Причина обязательна."""
    allowed: dict[str, str] = {}
    if not path.exists():
        return allowed
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
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


def find_unused(names: list[str], blob: str) -> list[str]:
    return [name for name in names if not is_used(name, blob)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать всё, включая разрешённые")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not DEFAULT_STRINGS.exists():
        print(f"error: {DEFAULT_STRINGS.relative_to(ROOT)} не найден")
        return 1

    names = default_names(DEFAULT_STRINGS)
    blob = source_texts(SOURCES)
    allowed = read_allowlist(ALLOWLIST)
    unused = find_unused(names, blob)

    errors: list[str] = []
    for name in unused:
        if name not in allowed:
            errors.append(f"res/values/strings.xml: строка `{name}` нигде не используется")
        elif args.verbose:
            print(f"allow: {name} — {allowed[name]}")

    for name, reason in allowed.items():
        if not reason:
            errors.append(f"tools/unused_strings_allow.txt: у `{name}` нет причины")
        if name not in names:
            errors.append(
                f"tools/unused_strings_allow.txt: `{name}` больше нет в strings.xml — "
                "запись устарела"
            )

    for problem in errors:
        print(f"error: {problem}")

    used = len(names) - len(unused)
    print(
        f"\n{len(errors)} error(s), 0 warning(s): {used} из {len(names)} строк используются, "
        f"{len(unused)} без ссылок"
        + (f" (разрешено {len(allowed)})" if allowed else "")
    )
    return 1 if errors else 0


def self_test() -> int:
    """Правила проверяются на синтетическом мини-проекте, а не на живом дереве."""
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        res = root / "app/src/main/res/values/strings.xml"
        res.parent.mkdir(parents=True)
        res.write_text(
            "<resources>\n"
            '    <string name="used_in_code">a</string>\n'
            '    <string name="used_in_xml">b</string>\n'
            '    <string name="used_in_comment">c</string>\n'
            '    <string name="dead">d</string>\n'
            "</resources>\n",
            encoding="utf-8",
        )
        (root / "app/src/main/java").mkdir(parents=True)
        (root / "app/src/main/java/Screen.kt").write_text(
            'val x = R.string.used_in_code\n', encoding="utf-8"
        )
        # Упоминание внутри имени другой строки не должно считаться использованием.
        (root / "app/src/main/res/values/other.xml").write_text(
            '<TextView android:text="@string/used_in_xml" />\n', encoding="utf-8"
        )

        names = default_names(res)
        blob = source_texts(root / "app/src")
        unused = find_unused(names, blob)

        check("использованная в коде строка считается используемой", "used_in_code" not in unused)
        check("использованная в XML строка считается используемой", "used_in_xml" not in unused)
        check("неиспользуемая строка найдена", "dead" in unused)

        allow = root / "allow.txt"
        allow.write_text("dead — пригодится в следующей версии\n", encoding="utf-8")
        allowed = read_allowlist(allow)
        check("причина из allow-файла читается", allowed.get("dead") == "пригодится в следующей версии")

        allow.write_text("dead\n", encoding="utf-8")
        check("запись без причины не даёт причину", read_allowlist(allow).get("dead") == "")

        allow.write_text("# только комментарий\n\n", encoding="utf-8")
        check("пустой allow-файл ничего не разрешает", read_allowlist(allow) == {})

        allow.write_text("ghost — удалённая строка\n", encoding="utf-8")
        check(
            "устаревшая запись видна по отсутствию в strings.xml",
            "ghost" not in default_names(res),
        )

        narrow = source_texts(root / "app/src/main/java")
        check(
            "строка, упомянутая только в XML, не считается использованной в коде",
            "used_in_xml" in find_unused(names, narrow),
        )

    if problems:
        for problem in problems:
            print(f"error: unused_strings --self-test: {problem}")
        return 1
    print("unused_strings --self-test: ok (ссылки из кода и XML, allow-файл, устаревшая запись)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
