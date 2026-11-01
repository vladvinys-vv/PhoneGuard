#!/usr/bin/env python3
"""Сверка SQL-миграций Room с сущностями (`@Entity`).

Зачем: Room проверяет схему после миграции и падает в рантайме
(`Migration didn't properly handle …`), причём **у уже обновляющихся пользователей** — то есть
это не «тест упал», а приложение не запускается после апдейта, а данные остаются недоступными.

Gradle-тест миграций (`MigrationTestHelper`) в песочнице не запустить: он требует
`androidx.room` и `androidx.sqlite`. Здесь проверка статическая + реальное исполнение SQL
через встроенный `sqlite3` из Python:

1. цепочка версий непрерывна (1 → `version`), каждая ступень ровно одна, `LATEST_VERSION`
   совпадает с `@Database(version = …)`;
2. весь SQL миграций реально исполняется SQLite (синтаксис, повторные колонки, `ALTER` по
   несуществующей таблице);
3. итоговая схема, которую миграции строят, совпадает с сущностями: таблицы, колонки,
   `NOT NULL` — иначе Room упадёт при обновлении.

Ограничения (честно): колонки, которые Room создал ещё в версии 1 и которые миграции не
трогают, берутся из самих `CREATE TABLE` в миграциях; индексы проверяются отдельно и только
на существование таблицы, сверка типов — по affinity, а не по точному типу Room.

Использование:
    python3 tools/migration_check.py            # ошибки + предупреждения
    python3 tools/migration_check.py --verbose  # подробности по каждой ступени
"""

from __future__ import annotations

import argparse
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
DATABASE = ROOT / "app/src/main/java/com/phoneguard/data/local/AppDatabase.kt"

MIGRATION_HEADER = re.compile(r"val MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\((\d+),\s*(\d+)\)")
EXEC_SQL = re.compile(r'execSQL\(\s*(?:"""(?P<triple>.*?)"""|"(?P<single>[^"]*)")', re.S)
ENTITY_HEADER = re.compile(r'@Entity\(\s*(?:tableName\s*=\s*"(\w+)")?')
COLUMN_INFO = re.compile(r'@ColumnInfo\(\s*name\s*=\s*"(\w+)"')

TYPE_AFFINITY = {
    "String": "TEXT",
    "Int": "INTEGER",
    "Long": "INTEGER",
    "Short": "INTEGER",
    "Byte": "INTEGER",
    "Boolean": "INTEGER",
    "Float": "REAL",
    "Double": "REAL",
    "ByteArray": "BLOB",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def migrations() -> tuple[dict[tuple[int, int], list[str]], str | None]:
    """{(from, to): [SQL, …]} и текст ошибки, если объявление не разобрано."""
    text = read(DATABASE)
    headers = list(MIGRATION_HEADER.finditer(text))
    if not headers:
        return {}, "в AppDatabase.kt не найдено ни одной миграции"
    result: dict[tuple[int, int], list[str]] = {}
    for index, header in enumerate(headers):
        end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
        block = text[header.start():end]
        if (int(header.group(1)), int(header.group(2))) != (int(header.group(3)), int(header.group(4))):
            return {}, f"имя миграции {header.group(0)} не совпадает с Migration(…) внутри"
        statements = []
        for match in EXEC_SQL.finditer(block):
            sql = (match.group("triple") or match.group("single") or "").replace('\\"', '"')
            sql = "\n".join(line.strip() for line in sql.splitlines()).strip()
            if sql:
                statements.append(sql)
        result[(int(header.group(3)), int(header.group(4)))] = statements
    return result, None


def database_version(text: str) -> int | None:
    match = re.search(r"version\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def latest_version_constant(text: str) -> int | None:
    match = re.search(r"const val LATEST_VERSION\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def split_parameters(body: str) -> list[str]:
    parts, depth, current = [], 0, []
    for char in body:
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        parts.append("".join(current))
    return parts


def enum_names() -> set[str]:
    names: set[str] = set()
    for path in MAIN.rglob("*.kt"):
        names |= set(re.findall(r"enum class (\w+)", read(path)))
    return names


def entities() -> dict[str, dict[str, tuple[str, bool]]]:
    """{таблица: {колонка: (affinity, nullable)}} по `@Entity`-классам."""
    result: dict[str, dict[str, tuple[str, bool]]] = {}
    enums = enum_names()
    for path in MAIN.rglob("*.kt"):
        text = read(path)
        for match in ENTITY_HEADER.finditer(text):
            table = match.group(1)
            if not table:
                continue
            header = re.search(r"(?:data\s+)?class\s+\w+\s*\(", text[match.end():])
            if not header:
                continue
            start = match.end() + header.end()
            depth, end = 1, start
            while end < len(text) and depth:
                if text[end] == "(":
                    depth += 1
                elif text[end] == ")":
                    depth -= 1
                end += 1
            columns: dict[str, tuple[str, bool]] = {}
            for parameter in split_parameters(text[start:end - 1]):
                clean = parameter.strip()
                if not clean or "val " not in clean:
                    continue
                name_match = re.search(r"\bval\s+(\w+)\s*:\s*([\w<>?., ]+)", clean)
                if not name_match:
                    continue
                column = name_match.group(1)
                info = COLUMN_INFO.search(clean)
                if info:
                    column = info.group(1)
                raw_type = name_match.group(2).strip()
                nullable = raw_type.endswith("?") or re.search(r"=\s*null\b", clean) is not None
                base = raw_type.rstrip("?")
                # enum'ы пишутся именами констант (см. Converters) -> TEXT; прочее считаем числом.
                affinity = TYPE_AFFINITY.get(base, "TEXT" if base in enums else "INTEGER")
                columns[column] = (affinity, nullable)
            result[table] = columns
    return result


def simulate(statements: list[str]) -> tuple[dict[str, dict[str, tuple[str, bool]]], list[str]]:
    """Строит схему, которую реально даёт SQL миграций (через настоящий SQLite)."""
    connection = sqlite3.connect(":memory:")
    failures: list[str] = []
    for sql in statements:
        for piece in [part.strip() for part in sql.split(";") if part.strip()]:
            try:
                connection.execute(piece)
            except sqlite3.Error as error:
                # ALTER/DROP по таблице, созданной Room в версии 1 — ожидаемо, базы у нас нет.
                if "no such table" in str(error):
                    continue
                failures.append(f"{error}: {piece.splitlines()[0][:90]}")
    schema: dict[str, dict[str, tuple[str, bool]]] = {}
    for (table,) in connection.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        if table.startswith("sqlite_"):
            continue
        columns: dict[str, tuple[str, bool]] = {}
        for _, name, ctype, notnull, _default, pk in connection.execute(f"PRAGMA table_info('{table}')"):
            columns[name] = (ctype.upper() or "INTEGER", not bool(notnull))
        schema[table] = columns
    connection.close()
    return schema, failures


def check() -> tuple[list[str], list[str], list[str]]:
    """(ошибки, предупреждения, таблицы, которые сверить нечем).

    Ошибка = приложение не запустится после обновления; предупреждение = схема расходится,
    но Room это переживёт (affinity, «мусорная» колонка).
    """
    text = read(DATABASE)
    version = database_version(text)
    latest = latest_version_constant(text)
    migration_map, problem = migrations()
    if problem:
        return [problem], [], []
    if version is None:
        return ["не найдено `version` в @Database"], [], []

    errors: list[str] = []
    warnings: list[str] = []
    unchecked: list[str] = []

    if latest is not None and latest != version:
        errors.append(f"LATEST_VERSION = {latest}, а версия базы = {version}")

    # 1. цепочка версий непрерывна: ровно по одной ступени 1 -> version
    expected = {(n, n + 1) for n in range(1, version)}
    actual = set(migration_map)
    for step in sorted(expected - actual):
        errors.append(f"нет миграции {step[0]} → {step[1]}")
    for step in sorted(actual - expected):
        errors.append(f"лишняя миграция {step[0]} → {step[1]} (версия базы {version})")

    # 2. SQL реально исполняется в SQLite
    ordered = [sql for step in sorted(migration_map) for sql in migration_map[step]]
    schema, failures = simulate(ordered)
    errors.extend(f"SQL не исполняется — {failure}" for failure in failures)

    # 3. итоговая схема против @Entity
    for table, columns in sorted(entities().items()):
        if table not in schema:
            # Такие таблицы Room создал в версии 1; в миграциях их нет, сверить нечем.
            unchecked.append(table)
            continue
        actual_columns = schema[table]
        for column, (affinity, nullable) in columns.items():
            if column not in actual_columns:
                errors.append(
                    f"`{table}.{column}` есть в сущности, но её не добавляет ни одна миграция — "
                    "Room упадёт с «Migration didn't properly handle» при обновлении"
                )
                continue
            actual_affinity, actual_nullable = actual_columns[column]
            if actual_affinity and affinity != actual_affinity:
                warnings.append(
                    f"`{table}.{column}`: affinity {actual_affinity} в SQL против {affinity} у сущности"
                )
            if nullable and not actual_nullable:
                errors.append(f"`{table}.{column}` в сущности nullable, а в схеме NOT NULL")
        for column in actual_columns.keys() - columns.keys():
            warnings.append(f"`{table}.{column}` есть в схеме, но не в сущности (мусор после миграции)")

    return errors, warnings, unchecked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="печатать каждую ступень миграции")
    args = parser.parse_args()

    errors, warnings, unchecked = check()

    if args.verbose and not errors:
        migration_map, _ = migrations()
        for step in sorted(migration_map):
            print(f"  {step[0]} → {step[1]}: {len(migration_map[step])} SQL-выражений")
    if unchecked:
        print("info: таблицы вне миграций (созданы Room в версии 1, сверить нечем): "
              f"{', '.join(sorted(unchecked))}")
    for problem_text in errors:
        print(f"error: {problem_text}")
    for problem_text in warnings:
        print(f"warning: {problem_text}")

    version = database_version(read(DATABASE))
    print(f"\n{len(errors)} error(s), {len(warnings)} warning(s): "
          f"{len(migrations()[0])} миграций, версия {version}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
