#!/usr/bin/env python3
"""Lightweight static auditor for the PhoneGuard Kotlin sources.

The CI sandbox for this repository has no JDK / Android SDK, so `./gradlew` cannot
be executed here.  This script performs the checks that a compiler would catch
cheaply and deterministically:

  * duplicate top-level declarations in the same package (Kotlin "redeclaration")
  * several `package` directives / mismatched directory in one file
  * undeclared internal symbol references (com.phoneguard.*)
  * `R.<type>.<name>` references that do not exist in res/
  * XML well-formedness of every resource / manifest file
  * unbalanced braces / parentheses (syntax sanity)
  * TODO/FIXME markers owned by this audit

Exit code is 1 when at least one ERROR-level finding is present.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res"
SRC_SETS = [ROOT / "app/src/main/java", ROOT / "app/src/test/java", ROOT / "app/src/androidTest/java"]

ERRORS: list[str] = []
WARNINGS: list[str] = []


def strip_code(text: str) -> str:
    """Remove comments and string literals while preserving line structure."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                if text[i] == '\n':
                    out.append('\n')
                i += 1
            i += 2
        elif c == '"' and text.startswith('\"\"\"', i):
            i += 3
            while i < n and not text.startswith('\"\"\"', i):
                if text[i] == '\n':
                    out.append('\n')
                i += 1
            i += 3
            out.append('""')
        elif c == '"':
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text[i] == '\n':
                    out.append('\n')
                i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
        else:
            out.append(c)
            i += 1
    return ''.join(out)


DECL_RE = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|value|inline|suspend|operator|override|tailrec|infix|external|const|lateinit)\s+)*'
    r'(class|interface|object|fun|val|var|typealias)\s+'
    r'(.+)$'
)

DEFINITION_LINE_RE = re.compile(r'^[\w<>\s,\[\].?*]+$')


def _declared_name(kind: str, tail: str) -> str | None:
    """Extracts the declared identifier from the text after the keyword."""
    body = tail.strip()
    if kind in ('class', 'interface', 'object', 'typealias'):
        # `class Foo @Inject constructor(` -> Foo
        match = re.match(r'([A-Za-z_][A-Za-z0-9_]*)', body)
        return match.group(1) if match else None
    if kind == 'fun':
        body = body.split('(')[0].split('{')[0].split(':')[0].split('<')[0].strip()
        if not body:
            return None
        # extension functions: `Foo.bar` -> `bar`
        return re.split(r'[.\s]', body)[-1]
    # val / var : `NAME: Type = value` or `NAME = value`
    match = re.match(r'([A-Za-z_][A-Za-z0-9_]*)', body)
    if not match:
        return None
    rest = body[match.end():].lstrip()
    # require an assignment/type annotation, otherwise it may be a delegation (`by`)
    if not (rest.startswith(':') or rest.startswith('=') or rest.startswith('by')):
        return None
    return match.group(1)


IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+\.\*|[\w.]+)(?:\s+as\s+(\w+))?", re.MULTILINE)
PACKAGE_RE = re.compile(r'^\s*package\s+([\w.]+)', re.MULTILINE)


def declarations(path: Path) -> list[tuple[str, str, int]]:
    """Top-level declarations only.

    Tracks both brace and parenthesis depth so that multi-line constructor
    parameter lists are not mistaken for top-level properties.
    """
    code = strip_code(path.read_text(encoding='utf-8'))
    brace = 0
    paren = 0
    in_block_comment = False
    found = []
    for line_no, line in enumerate(code.splitlines(), start=1):
        if brace == 0 and paren == 0 and not line.startswith((' ', '\t', '}')):
            stripped = line.strip()
            m = DECL_RE.match(stripped)
            if m and not stripped.startswith(('return', 'import', 'package')):
                name = _declared_name(m.group(1), m.group(2))
                if name:
                    found.append((m.group(1), name, line_no))
        brace = max(brace + line.count('{') - line.count('}'), 0)
        paren = max(paren + line.count('(') - line.count(')'), 0)
    return found


def collect_index() -> tuple[dict[str, set[str]], dict[str, list[Path]]]:
    """package -> top-level names, and (package.name) -> files."""
    index: dict[str, set[str]] = defaultdict(set)
    owners: dict[str, list[Path]] = defaultdict(list)
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            pkgs = PACKAGE_RE.findall(text)
            if not pkgs:
                ERRORS.append(f"{path.relative_to(ROOT)}: missing package declaration")
                continue
            if len(pkgs) > 1:
                ERRORS.append(f"{path.relative_to(ROOT)}: {len(pkgs)} package declarations {pkgs}")
            pkg = pkgs[0]
            for kind, name, line in declarations(path):
                index[pkg].add(name)
                owners[f"{pkg}.{name}"].append(path)
    return index, owners


def check_duplicates(owners: dict[str, list[Path]]) -> None:
    for fq, files in sorted(owners.items()):
        unique = sorted({str(f.relative_to(ROOT)) for f in files})
        if len(unique) > 1:
            ERRORS.append(f"duplicate top-level declaration {fq} in: {', '.join(unique)}")


def check_imports(index: dict[str, set[str]]) -> None:
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            rel = path.relative_to(ROOT)
            own_pkg = (PACKAGE_RE.findall(text) or [''])[0]
            for target, alias in IMPORT_RE.findall(text):
                if not target.startswith('com.phoneguard'):
                    continue
                if target.endswith('.*'):  # star import, e.g. com.phoneguard.model.*
                    if target[:-2] not in index:
                        ERRORS.append(f"{rel}: star import of unknown package {target[:-2]}")
                    continue
                pkg, _, name = target.rpartition('.')
                if name == 'R' or name == 'BuildConfig':
                    continue
                if name in index.get(pkg, set()):
                    continue
                # nested class import, e.g. com.phoneguard.ui.Foo.Bar
                outer_pkg, _, outer = pkg.rpartition('.')
                if outer in index.get(outer_pkg, set()):
                    continue
                ERRORS.append(f"{rel}: unresolved import {target}")


def check_r_refs() -> None:
    res: dict[tuple[str, str], Path] = {}
    for path in RES.rglob('*.xml'):
        folder = path.relative_to(RES).parts[0].split('-')[0]
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        if folder in ('values',):
            for m in re.finditer(r'<(string|color|dimen|style|integer|bool|array|plurals|attr)\b[^>]*\bname="([^"]+)"', text):
                kind = m.group(1)
                if kind in ('string', 'color', 'style', 'dimen', 'integer', 'bool', 'plurals'):
                    res[(kind, m.group(2))] = path
        elif folder in ('drawable', 'mipmap', 'xml', 'raw', 'font', 'layout', 'anim', 'menu'):
            res[(folder, path.name.split('.')[0])] = path
    usage = re.compile(r'(?<![\w.])R\.(string|drawable|mipmap|color|style|xml|raw|font|layout|dimen|integer|bool|array|plurals|anim)\.(\w+)')
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            for m in usage.finditer(path.read_text(encoding='utf-8')):
                key = (m.group(1), m.group(2))
                if key not in res:
                    ERRORS.append(f"{path.relative_to(ROOT)}: R.{key[0]}.{key[1]} is not defined in res/")


def check_r_imports() -> None:
    """Голое `R.string.…` без `import com.phoneguard.R` — ошибка компиляции.

    Раньше такой случай пропускался: `check_r_refs()` проверяет только
    существование ресурса, а сам импорт — нет. В `IntrusionResponseExecutor`
    из-за этого строка `R.string.intrusion_sms_prefix` не собиралась.
    """
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            if 'package ' not in text:
                continue
            own_pkg = (PACKAGE_RE.findall(text) or [''])[0].strip()
            code = strip_code(text)
            # только «голое» обращение: R.string / R.drawable / R.id (не com.phoneguard.R)
            if not re.search(r'(?<![\w.])R\.(?:string|drawable|id|layout|color|xml|raw|menu|plurals|array|style)\b', code):
                continue
            if own_pkg == 'com.phoneguard':
                continue
            if re.search(r'^\s*import\s+com\.phoneguard\.R\s*$', text, re.MULTILINE):
                continue
            ERRORS.append(f"{path.relative_to(ROOT)}: R.… без import com.phoneguard.R")


def check_xml() -> None:
    for path in list(RES.rglob('*.xml')) + [ROOT / 'app/src/main/AndroidManifest.xml']:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            ERRORS.append(f"{path.relative_to(ROOT)}: XML parse error: {exc}")


def check_balance() -> None:
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            code = strip_code(path.read_text(encoding='utf-8'))
            for open_c, close_c in (('{', '}'), ('(', ')'), ('[', ']')):
                if code.count(open_c) != code.count(close_c):
                    ERRORS.append(
                        f"{path.relative_to(ROOT)}: unbalanced {open_c}{close_c} "
                        f"({code.count(open_c)} vs {code.count(close_c)})"
                    )


# Символы Compose/AndroidX, которые почти всегда используются в UI-коде.
# Если такой символ встречается, а импорта нет ни прямого, ни звёздочного —
# это ошибка компиляции, которую легко пропустить глазами.
KNOWN_SYMBOLS: dict[str, str] = {
    'stringResource': 'androidx.compose.ui.res',
    'painterResource': 'androidx.compose.ui.res',
    'pluralStringResource': 'androidx.compose.ui.res',
    'collectAsState': 'androidx.compose.runtime',
    'collectAsStateWithLifecycle': 'androidx.lifecycle.compose',
    'LaunchedEffect': 'androidx.compose.runtime',
    'DisposableEffect': 'androidx.compose.runtime',
    'remember': 'androidx.compose.runtime',
    'rememberSaveable': 'androidx.compose.runtime.saveable',
    'rememberCoroutineScope': 'androidx.compose.runtime',
    'mutableStateOf': 'androidx.compose.runtime',
    'mutableIntStateOf': 'androidx.compose.runtime',
    'mutableStateListOf': 'androidx.compose.runtime',
    'derivedStateOf': 'androidx.compose.runtime',
    'produceState': 'androidx.compose.runtime',
    'hiltViewModel': 'androidx.hilt.navigation.compose',
    'LocalContext': 'androidx.compose.ui.platform',
    'LocalView': 'androidx.compose.ui.platform',
    'ContextCompat': 'androidx.core.content',
    'NotificationManagerCompat': 'androidx.core.app',
    'Icons': 'androidx.compose.material.icons',
    'LazyColumn': 'androidx.compose.foundation.lazy',
    'rememberLazyListState': 'androidx.compose.foundation.lazy',
    'TabRow': 'androidx.compose.material3',
    'Tab': 'androidx.compose.material3',
    'Scaffold': 'androidx.compose.material3',
    'TopAppBar': 'androidx.compose.material3',
    'SnackbarHost': 'androidx.compose.material3',
    'SnackbarHostState': 'androidx.compose.material3',
    'ExposedDropdownMenuBox': 'androidx.compose.material3',
    'DropdownMenuItem': 'androidx.compose.material3',
    'TextField': 'androidx.compose.material3',
    'OutlinedButton': 'androidx.compose.material3',
    'TextButton': 'androidx.compose.material3',
    'FloatingActionButton': 'androidx.compose.material3',
    'CircularProgressIndicator': 'androidx.compose.material3',
    'LinearProgressIndicator': 'androidx.compose.material3',
    'AlertDialog': 'androidx.compose.material3',
    'Switch': 'androidx.compose.material3',
    'Checkbox': 'androidx.compose.material3',
    'Divider': 'androidx.compose.material3',
    'CardDefaults': 'androidx.compose.material3',
    'ButtonDefaults': 'androidx.compose.material3',
    'MaterialTheme': 'androidx.compose.material3',
    'rememberNavController': 'androidx.navigation.compose',
    'NavHost': 'androidx.navigation.compose',
    'composable': 'androidx.navigation.compose',
    'navDeepLink': 'androidx.navigation.compose',
    'currentBackStackEntryAsState': 'androidx.navigation.compose',
}


# Android/Java-символы, которые чаще всего забывают импортировать.
ANDROID_SYMBOLS: dict[str, str] = {
    'Manifest': 'android.Manifest',
    'ComponentName': 'android.content',
    'Context': 'android.content',
    'Intent': 'android.content',
    'SharedPreferences': 'android.content',
    'ContentValues': 'android.content',
    'Uri': 'android.net',
    'Build': 'android.os',
    'Bundle': 'android.os',
    'Handler': 'android.os',
    'Looper': 'android.os',
    'PowerManager': 'android.os',
    'StatFs': 'android.os',
    'Parcelable': 'android.os',
    'Log': 'android.util',
    'Base64': 'android.util',
    'Settings': 'android.provider',
    'Activity': 'android.app',
    'Service': 'android.app',
    'Notification': 'android.app',
    'NotificationManager': 'android.app',
    'PendingIntent': 'android.app',
    'KeyguardManager': 'android.app',
    'MediaPlayer': 'android.media',
    'RingtoneManager': 'android.media',
    'AudioManager': 'android.media',
    'Location': 'android.location',
    'TelephonyManager': 'android.telephony',
    'SmsManager': 'android.telephony',
    'Toast': 'android.widget',
    'Bitmap': 'android.graphics',
    'Drawable': 'android.graphics.drawable',
    'NotificationCompat': 'androidx.core.app',
    'JSONObject': 'org.json',
    'JSONArray': 'org.json',
    'Gson': 'com.google.gson',
    'File': 'java.io',
    'SimpleDateFormat': 'java.text',
    'DateFormat': 'java.text',
    'Date': 'java.util',
    'Locale': 'java.util',
    'Calendar': 'java.util',
    'UUID': 'java.util',
    'Inject': 'javax.inject',
    'Singleton': 'javax.inject',
    'launch': 'kotlinx.coroutines',
    'withContext': 'kotlinx.coroutines',
    'Dispatchers': 'kotlinx.coroutines',
    'SupervisorJob': 'kotlinx.coroutines',
    'StateFlow': 'kotlinx.coroutines.flow',
    'MutableStateFlow': 'kotlinx.coroutines.flow',
    'Flow': 'kotlinx.coroutines.flow',
}


IMPORT_LINE_RE = re.compile(r'^\s*import\s+((?:\w+\.)*\w+)(\.\*)?(?:\s+as\s+(\w+))?', re.M)


def check_known_symbol_imports(index: dict[str, set[str]]) -> None:
    """Использован известный Compose/AndroidX-символ, но импорта нет."""
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            if 'package ' not in text:
                continue
            own_pkg = (PACKAGE_RE.findall(text) or [''])[0]
            code = strip_code(text)
            imports = [
                (m.group(1) + ('.*' if m.group(2) else ''), m.group(3))
                for m in IMPORT_LINE_RE.finditer(text)
            ]
            exact = {alias or fq.rpartition('.')[2] for fq, alias in imports}
            stars = {fq[:-2] for fq, _ in imports if fq.endswith('.*')}
            rel = path.relative_to(ROOT)
            own_decls = index.get(own_pkg, set())
            for symbol, pkg in {**KNOWN_SYMBOLS, **ANDROID_SYMBOLS}.items():
                if symbol in exact:
                    continue
                if symbol in stars:
                    continue
                if pkg in stars:
                    continue
                # использование: вызов или компоузер, но не обращение через точку
                use_re = re.compile(r'(?<![\w.])' + re.escape(symbol) + r'\s*[({<]')
                if not use_re.search(code):
                    continue
                if symbol in own_decls:
                    continue
                # локальное объявление с тем же именем (например, свой Composable)
                if re.search(r'\b(fun|val|var|class|object|interface|typealias)\s+' + re.escape(symbol) + r'\b', code):
                    continue
                ERRORS.append(
                    f"{rel}: {symbol} используется без импорта ({pkg}.{symbol} или {pkg}.*)"
                )

            # Делегирование `by` требует getValue / setValue из compose.runtime
            if re.search(r'\bby\s+(remember|rememberSaveable|\w+\.collectAsState)', code):
                if 'androidx.compose.runtime.getValue' not in {fq for fq, _ in imports} and 'androidx.compose.runtime' not in stars:
                    ERRORS.append(f"{rel}: 'by' делегирование без импорта androidx.compose.runtime.getValue")
            if re.search(r'\bvar\s+\w+\s+by\s+(remember|rememberSaveable|mutableStateOf)', code):
                if 'androidx.compose.runtime.setValue' not in {fq for fq, _ in imports} and 'androidx.compose.runtime' not in stars:
                    ERRORS.append(f"{rel}: 'var ... by' без импорта androidx.compose.runtime.setValue")



DAO_FUN_RE = re.compile(r'\bfun\s+(\w+)\s*\(')
DAO_DECL_RE = re.compile(r'\binterface\s+(\w*Dao)\b')
DB_GETTER_RE = re.compile(r'\bfun\s+(\w+)\s*\(\s*\)\s*:\s*(\w*Dao)\b')


def check_dao_calls() -> None:
    """Вызовы методов Room-DAO, которых нет в интерфейсе DAO (ошибка компиляции)."""
    db_file = ROOT / 'app/src/main/java/com/phoneguard/data/local/AppDatabase.kt'
    if not db_file.exists():
        return
    getters: dict[str, str] = dict(DB_GETTER_RE.findall(db_file.read_text(encoding='utf-8')))
    dao_methods: dict[str, set[str]] = {}
    for path in (ROOT / 'app/src/main/java/com/phoneguard/data/local').glob('*.kt'):
        text = path.read_text(encoding='utf-8')
        for name in DAO_DECL_RE.findall(text):
            dao_methods[name] = set(DAO_FUN_RE.findall(text))
    if not getters or not dao_methods:
        return

    # Только если вся правая часть — вызов геттера DAO (`val dao = db.fooDao()`),
    # иначе `val events = db.intrusionEventDao().getOlderThan(cutoff)` считается алиасом.
    alias_re = re.compile(r'^[^\n]*\bval\s+(\w+)\s*=\s*[\w.]*?(\w+)\(\)\s*$', re.M)
    call_re = re.compile(r'(?<![\w.])(\w+)\.(\w+)\s*(?:\(|<)')
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            aliases = {alias: getters[getter] for alias, getter in alias_re.findall(text) if getter in getters}
            # прямой вызов appDatabase.xxxDao().method(...)
            for getter, method in re.findall(r'(\w+)\(\)\.(\w+)\s*\(', text):
                dao = getters.get(getter)
                if dao and method not in dao_methods.get(dao, set()):
                    ERRORS.append(
                        f"{path.relative_to(ROOT)}: {dao}.{method}() не объявлен в DAO"
                    )
            if not aliases:
                continue
            for alias, method in call_re.findall(text):
                dao = aliases.get(alias)
                if not dao:
                    continue
                if method not in dao_methods.get(dao, set()):
                    ERRORS.append(
                        f"{path.relative_to(ROOT)}: {alias}.{method}() — нет метода в {dao}"
                    )



CLASS_DECL_RE = re.compile(r'^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |abstract |open |sealed |data |enum |annotation |value |inline )*(class|interface|object)\s+(\w+)', re.M)
CTOR_PROP_RE = re.compile(r'(?:private\s+|internal\s+)?val\s+(\w+)\s*:\s*([\w.]+)')
FUN_NAME_RE = re.compile(r'\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(')

# Имена, которые приходят из stdlib/корутин и не объявляются в проекте.
BUILTIN_MEMBERS = {
    'copy', 'toString', 'equals', 'hashCode', 'let', 'run', 'apply', 'also', 'takeIf', 'repeat',
    'first', 'last', 'collect', 'emit', 'value', 'await', 'map', 'filter', 'launch', 'invoke',
    'getValue', 'setValue', 'add', 'remove', 'isEmpty', 'isNotEmpty', 'contains', 'size',
}


def check_member_calls() -> None:
    """Вызов метода у свойства с типом из проекта, которого нет в этом классе."""
    # класс -> набор имён функций (из файла, где класс объявлен)
    members: dict[str, set[str]] = {}
    files = [p for src in SRC_SETS if src.exists() for p in src.rglob('*.kt')]
    for path in files:
        text = path.read_text(encoding='utf-8')
        code = strip_code(text)
        for kind, name in CLASS_DECL_RE.findall(code):
            if kind == 'object':
                continue
            members.setdefault(name, set()).update(FUN_NAME_RE.findall(code))
    if not members:
        return

    for path in files:
        text = path.read_text(encoding='utf-8')
        code = strip_code(text)
        props = {alias: type_.rpartition('.')[2] for alias, type_ in CTOR_PROP_RE.findall(code)}
        if not props:
            continue
        rel = path.relative_to(ROOT)
        for alias, method in re.findall(r'(?<![\w.])(\w+)\.(\w+)\s*(?:\(|<)', code):
            type_name = props.get(alias)
            if not type_name or type_name not in members:
                continue
            if method in BUILTIN_MEMBERS:
                continue
            if method in members[type_name]:
                continue
            ERRORS.append(
                f"{rel}: {alias}.{method}() — метод не найден в {type_name}"
            )



def check_manifest_classes(index: dict[str, set[str]]) -> None:
    """Компоненты манифеста должны указывать на существующие классы проекта."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    text = manifest.read_text(encoding='utf-8')
    default_pkg = re.search(r'<manifest[^>]*\bpackage="([^"]+)"', text)
    if default_pkg is None:
        gradle = ROOT / 'app/build.gradle'
        ns = re.search(r'namespace\s*=?\s*["\']([\w.]+)["\']', gradle.read_text(encoding='utf-8')) if gradle.exists() else None
        default_pkg = ns
    for m in re.finditer(r'<(?:activity|service|receiver|provider|application)\b[^>]*?android:name="([^"]+)"', text):
        name = m.group(1)
        if name.startswith('android.') or not (name.startswith('com.phoneguard') or name.startswith('.')):
            continue
        if default_pkg is None:
            return
        fq = default_pkg.group(1) + name if name.startswith('.') else name
        pkg, _, cls = fq.rpartition('.')
        if cls in index.get(pkg, set()):
            continue
        ERRORS.append(f"AndroidManifest.xml: компонент {fq} не найден в исходниках")



def check_hilt_bindings() -> None:
    """Hilt: у интерфейсов, которые запрашиваются через @Inject, должно быть привязка."""
    main = ROOT / 'app/src/main/java'
    if not main.exists():
        return
    files = list(main.rglob('*.kt'))
    db_file = main / 'com/phoneguard/data/local/AppDatabase.kt'
    db_getters: dict[str, str] = (
        dict(DB_GETTER_RE.findall(db_file.read_text(encoding='utf-8'))) if db_file.exists() else {}
    )
    abstracts: set[str] = set()
    for path in files:
        code = strip_code(path.read_text(encoding='utf-8'))
        for kind, name in re.findall(r'\b(interface|abstract class)\s+(\w+)', code):
            abstracts.add(name)
    # что Hilt умеет предоставить сам
    provided: set[str] = set()
    for path in files:
        text = path.read_text(encoding='utf-8')
        code = strip_code(text)
        if '@Module' not in code and '@Provides' not in code and '@Binds' not in code:
            continue
        # return-типы функций модуля:  fun name(params): Type
        for ret in re.findall(r'\bfun\s+\w+\s*\([^)]*\)\s*:\s*([\w.<>?]+)', code):
            base = ret.split('<')[0].rstrip('?').rpartition('.')[2]
            provided.add(base)
        # Kotlin выводит тип из тела выражения: `fun x(db: AppDatabase) = db.vaultDao()`
        for getter in re.findall(r'\bfun\s+\w+\s*\([^)]*\)\s*=\s*\w+\.(\w+)\(\)', code):
            dao = db_getters.get(getter)
            if dao:
                provided.add(dao)
        if '@Module' in code and '@InstallIn' not in code:
            ERRORS.append(f"{path.relative_to(ROOT)}: @Module без @InstallIn")
    # DAO и база данных предоставляются модулями/компонентами Room
    for alias in ('AppDatabase',):
        provided.add(alias)

    inject_re = re.compile(r'@Inject\s+constructor\s*\(([^)]*)\)', re.S)
    for path in files:
        text = path.read_text(encoding='utf-8')
        code = strip_code(text)
        for params in inject_re.findall(code):
            for raw in params.split(','):
                if ':' not in raw:
                    continue
                type_ = raw.split(':', 1)[1].strip()
                base = type_.split('<')[0].rstrip('?').rpartition('.')[2]
                if base in abstracts and base not in provided:
                    ERRORS.append(
                        f"{path.relative_to(ROOT)}: @Inject просит {base}, но Hilt-привязки нет"
                    )



def check_manifest_exported() -> None:
    """Android 12+: компонент с intent-filter обязан указывать android:exported."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    try:
        tree = ET.parse(manifest)
    except ET.ParseError:
        return
    android = '{http://schemas.android.com/apk/res/android}'
    for tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
        for node in tree.iter(tag):
            if node.find('intent-filter') is None:
                continue
            if node.get(android + 'exported') is None:
                name = node.get(android + 'name', '?')
                ERRORS.append(
                    f"AndroidManifest.xml: <{tag} {name}> с intent-filter без android:exported"
                )



def check_unused_imports() -> None:
    """Импорт, чьё имя больше нигде в файле не встречается (замечание, не ошибка)."""
    for src in SRC_SETS:
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            lines = text.splitlines()
            body = '\n'.join(l for l in lines if not l.lstrip().startswith('import '))
            for m in IMPORT_LINE_RE.finditer(text):
                fq, star, alias = m.group(1), m.group(2), m.group(3)
                if star:
                    continue
                symbol = alias or fq.rpartition('.')[2]
                # `getValue`/`setValue` подключаются ради оператора `by` и в тексте не встречаются
                if symbol in ('getValue', 'setValue'):
                    continue
                if re.search(r'\b' + re.escape(symbol) + r'\b', body):
                    continue
                WARNINGS.append(f"{path.relative_to(ROOT)}: неиспользуемый импорт {fq}")



SQL_KEYWORDS = {
    'select', 'from', 'where', 'and', 'or', 'not', 'null', 'is', 'in', 'exists', 'order', 'by',
    'asc', 'desc', 'limit', 'offset', 'count', 'sum', 'avg', 'min', 'max', 'as', 'on', 'join',
    'left', 'inner', 'outer', 'group', 'having', 'distinct', 'case', 'when', 'then', 'else', 'end',
    'like', 'between', 'insert', 'into', 'values', 'update', 'set', 'delete', 'replace', 'conflict',
    'primary', 'key', 'autoincrement', 'integer', 'text', 'real', 'blob', 'numeric', 'unique',
    'create', 'table', 'if', 'collate', 'nocase', 'true', 'false', 'all', 'any', 'cast', 'int',
    'coalesce', 'ifnull', 'length', 'lower', 'upper', 'substr', 'abs', 'round', 'total', 'current_timestamp',
}



def strip_comments(code: str) -> str:
    """Убирает // и /* */ комментарии, сохраняя строковые литералы (в них бывают // и http://)."""
    out: list[str] = []
    i = 0
    n = len(code)
    quote: str | None = None
    block = False
    while i < n:
        ch = code[i]
        if block:
            if code.startswith('*/', i):
                block = False
                i += 2
                continue
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue
        if quote:
            out.append(ch)
            if ch == '\\' and i + 1 < n:
                out.append(code[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in '"\'':
            quote = ch
            out.append(ch)
            i += 1
            continue
        if code.startswith('//', i):
            while i < n and code[i] != '\n':
                i += 1
            continue
        if code.startswith('/*', i):
            block = True
            i += 2
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def _balanced(text: str, open_idx: int) -> str:
    """Возвращает содержимое скобки, начиная с индекса `open_idx` (символ '(')."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
    return text[open_idx + 1:]


def check_room_queries() -> None:
    """Таблицы в @Query должны совпадать с @Entity (Room проверяет это при сборке)."""
    main = ROOT / 'app/src/main/java'
    tables: dict[str, set[str]] = {}
    for path in main.rglob('*.kt'):
        text = path.read_text(encoding='utf-8')
        for m in re.finditer(r'@Entity\s*(\(|$)', text):
            ann = _balanced(text, m.start(1)) if m.group(1) == '(' else ''
            cls_pos = text.find('class ', m.end())
            if cls_pos < 0:
                continue
            paren = text.find('(', cls_pos)
            if paren < 0:
                continue
            cls_name = text[cls_pos + len('class '):paren].strip()
            fields: set[str] = set()
            for raw in _balanced(text, paren).split(','):
                if ':' not in raw:
                    continue
                name = raw.split(':')[0].split()[-1]
                fields.add(name)
                col = re.search(r'@ColumnInfo\s*\(\s*name\s*=\s*"([^"]+)"', raw)
                if col:
                    fields.add(col.group(1))
            tn = re.search(r'tableName\s*=\s*"([^"]+)"', ann)
            tables[tn.group(1) if tn else cls_name] = fields
    if not tables:
        return

    query_re = re.compile(r'@Query\(\s*"((?:[^"\\]|\\.)*)"\s*\)')
    for path in main.rglob('*.kt'):
        text = path.read_text(encoding='utf-8')
        rel = path.relative_to(ROOT)
        for sql in query_re.findall(text):
            froms = re.findall(r'\b(?:from|update|into|join)\s+([A-Za-z_]\w*)', sql, re.I)
            if not froms:
                continue
            known_columns: set[str] = set()
            for t in froms:
                if t.lower() in SQL_KEYWORDS:
                    continue
                if t not in tables:
                    ERRORS.append(f"{rel}: в @Query нет такой таблицы: {t}")
                else:
                    known_columns |= tables[t]
            if not known_columns:
                continue
            for token in set(re.findall(r'\b([A-Za-z_]\w*)\b', sql)):
                if token.lower() in SQL_KEYWORDS or token in known_columns or token in froms:
                    continue
                if ':' + token in sql:
                    continue
                WARNINGS.append(f"{rel}: @Query ссылается на неизвестную колонку {token}")



def check_permission_usage() -> None:
    """Разрешение используется в коде, но не объявлено в манифесте (SecurityException в рантайме)."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    text = manifest.read_text(encoding='utf-8')
    declared = set(re.findall(r'<uses-permission[^>]*android:name="android\.permission\.(\w+)"', text))
    main = ROOT / 'app/src/main/java'
    if not main.exists():
        return
    # Проверяем только реальные запросы разрешений, а не перечисление чужих
    # разрешений (Privacy Scanner/Permission Abuse читают их у других приложений).
    request_re = re.compile(
        r'(?:checkSelfPermission|requestPermissions|RequestPermission)\([^)]*?Manifest\.permission\.(\w+)'
        r'|launch\(\s*Manifest\.permission\.(\w+)'
    )
    for path in main.rglob('*.kt'):
        code = path.read_text(encoding='utf-8')
        used = {a or b for a, b in request_re.findall(code)}
        for perm in sorted(used):
            if perm not in declared:
                ERRORS.append(
                    f"{path.relative_to(ROOT)}: используется Manifest.permission.{perm}, но в манифесте его нет"
                )


def collect_resources() -> dict[tuple[str, str], Path]:
    res_map: dict[tuple[str, str], Path] = {}
    for path in RES.rglob('*.xml'):
        parts = path.relative_to(RES).parts
        folder = parts[0].split('-')[0]
        if folder == 'values':
            text = path.read_text(encoding='utf-8')
            for m in re.finditer(r'<(string|color|dimen|style|integer|bool|array|plurals|attr)\b[^>]*\bname="([^"]+)"', text):
                res_map[(m.group(1), m.group(2))] = path
        elif folder in ('drawable', 'mipmap', 'xml', 'raw', 'font', 'layout', 'menu', 'anim'):
            res_map[(folder, path.name.split('.')[0])] = path
    return res_map


def check_xml_resource_refs() -> None:
    """Ссылки @type/name внутри ресурсов и манифеста должны существовать."""
    res_map = collect_resources()
    files = list(RES.rglob('*.xml')) + [ROOT / 'app/src/main/AndroidManifest.xml']
    for path in files:
        if not path.exists() or 'layout' in path.parts:
            continue
        text = path.read_text(encoding='utf-8')
        for m in re.finditer(r'"@(string|color|dimen|style|integer|bool|array|plurals|drawable|mipmap|xml|raw|font|layout|menu|anim)/([\w.]+)"', text):
            key = (m.group(1), m.group(2))
            if key not in res_map:
                ERRORS.append(
                    f"{path.relative_to(ROOT)}: ссылка @{key[0]}/{key[1]} не найдена в res/"
                )



def check_locale_parity() -> None:
    """Все локали должны содержать одинаковый набор строк."""
    base = RES / 'values/strings.xml'
    if not base.exists():
        return
    def keys(path: Path) -> set[str]:
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            return set()
        return set(re.findall(r'<string\b[^>]*\bname="([^"]+)"', text))
    base_keys = keys(base)
    for folder in sorted(p for p in RES.iterdir() if p.is_dir() and p.name.startswith('values-')):
        path = folder / 'strings.xml'
        if not path.exists():
            continue
        locale_keys = keys(path)
        for missing in sorted(base_keys - locale_keys):
            ERRORS.append(f"{path.relative_to(ROOT)}: нет строки {missing} (есть в values/)")
        for extra in sorted(locale_keys - base_keys):
            WARNINGS.append(f"{path.relative_to(ROOT)}: строка {extra} отсутствует в values/ (не переводится)")



def _split_top_level(text: str, angle: bool = False) -> list[str]:
    """Делит список аргументов по запятым верхнего уровня (скобки/строки учитываются).

    `angle=True` дополнительно учитывает generic-углы `Map<String, String>`:
    `<` считается открывающей скобкой только если стоит вплотную к имени типа,
    поэтому сравнения вида `a < b` не ломают разбор.
    """
    parts: list[str] = []
    depth = 0
    angle_depth = 0
    quote: str | None = None
    current = []
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            current.append(ch)
            if ch == '\\' and i + 1 < len(text):
                current.append(text[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in '"\'' and not text[max(0, i - 1):i].endswith('"' * 2):
            quote = ch
            current.append(ch)
        elif ch in '([{':
            depth += 1
            current.append(ch)
        elif ch in ')]}':
            depth -= 1
            current.append(ch)
        elif angle and ch == '<' and current and current[-1].isalnum() and i + 1 < len(text) and (
            text[i + 1].isalnum() or text[i + 1] in '?*_'
        ):
            angle_depth += 1
            current.append(ch)
        elif angle and ch == '>' and angle_depth > 0:
            angle_depth -= 1
            current.append(ch)
        elif ch == ',' and depth == 0 and angle_depth == 0:
            parts.append(''.join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    if current:
        parts.append(''.join(current))
    return [p.strip() for p in parts if p.strip()]


def _format_slots(value: str) -> int:
    """Сколько аргументов ожидает строка формата (`%1$s`, `%d`, `%s`, `%%` — литерал)."""
    positional: list[int] = []
    plain = 0
    for m in re.finditer(r'%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])', value):
        if m.group(2) == '%':
            continue
        if m.group(1):
            positional.append(int(m.group(1)[:-1]))
        else:
            plain += 1
    if positional:
        return max(max(positional), plain)
    return plain


def check_format_args() -> None:
    """Число аргументов getString/stringResource должно совпадать с плейсхолдерами строки."""
    base = RES / 'values/strings.xml'
    if not base.exists():
        return
    strings: dict[str, str] = {}
    for m in re.finditer(r'<string\b[^>]*\bname="([^"]+)"[^>]*>(.*?)</string>', base.read_text(encoding='utf-8'), re.S):
        strings[m.group(1)] = m.group(2)
    call_re = re.compile(r'\b(?:getString|stringResource)\(\s*R\.string\.(\w+)')
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        code = strip_comments(path.read_text(encoding='utf-8'))
        for m in call_re.finditer(code):
            name = m.group(1)
            if name not in strings:
                continue
            open_idx = code.find('(', m.start())
            args = _split_top_level(_balanced(code, open_idx))
            passed = max(0, len(args) - 1)
            required = _format_slots(strings[name])
            if passed != required:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: R.string.{name} ждёт {required} арг., передано {passed}"
                )



def check_pending_intent_flags() -> None:
    """С API 31 PendingIntent обязан иметь FLAG_IMMUTABLE или FLAG_MUTABLE (иначе IllegalArgumentException)."""
    flag_re = re.compile(r'PendingIntent\.get(?:Activity|Service|Broadcast|ForegroundService)\(')
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        code = strip_comments(path.read_text(encoding='utf-8'))
        for m in flag_re.finditer(code):
            open_idx = code.find('(', m.start())
            args = _balanced(code, open_idx)
            if 'FLAG_IMMUTABLE' not in args and 'FLAG_MUTABLE' not in args:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: PendingIntent без FLAG_IMMUTABLE/FLAG_MUTABLE "
                    f"(краш на Android 12+)"
                )



def check_start_activity() -> None:
    """startActivity вне IntentUtils/try роняет приложение, если экрана нет на прошивке."""
    allowed = {'IntentUtils.kt'}
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        if path.name in allowed:
            continue
        code = strip_comments(path.read_text(encoding='utf-8'))
        for m in re.finditer(r'\.\s*startActivity(?:ForResult)?\(', code):
            window = code[max(0, m.start() - 500):m.start()]
            if 'runCatching' in window or 'try {' in window:
                continue
            line = code.count('\n', 0, m.start()) + 1
            ERRORS.append(
                f"{path.relative_to(ROOT)}:{line}: startActivity без IntentUtils.open/runCatching "
                f"(ActivityNotFoundException на части прошивок)"
            )



def check_service_start_guards() -> None:
    """startService/startForegroundService вне try: Android 12+ бросает ForegroundServiceStartNotAllowedException."""
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        code = strip_comments(path.read_text(encoding='utf-8'))
        for m in re.finditer(r'\.\s*start(?:Foreground)?Service\(\s*[^)\s]', code):
            window = code[max(0, m.start() - 500):m.start()]
            if 'runCatching' in window or 'try {' in window:
                continue
            line = code.count('\n', 0, m.start()) + 1
            ERRORS.append(
                f"{path.relative_to(ROOT)}:{line}: startService без try/runCatching "
                f"(Android 12+ запрещает запуск из фона)"
            )


def check_dead_components() -> None:
    """Сервис/приёмник в манифесте, на который никто не ссылается, — мёртвый компонент."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    text = manifest.read_text(encoding='utf-8')
    sources = '\n'.join(
        strip_comments(p.read_text(encoding='utf-8'))
        for p in (ROOT / 'app/src/main/java').rglob('*.kt')
    )
    for m in re.finditer(
        r'<(service|receiver|provider)\b(.*?)(?:/>|</\1>)', text, re.S
    ):
        block = m.group(2)
        if '<intent-filter' in block:
            continue
        if 'android:exported="true"' in block:
            continue
        name = re.search(r'android:name="([^"]+)"', block)
        if not name:
            continue
        if name.group(1).startswith(('android.', 'androidx.', 'com.google.', 'com.android.')):
            continue
        simple = name.group(1).split('.')[-1]
        if simple and simple not in sources:
            ERRORS.append(
                f"AndroidManifest.xml: <{m.group(1)}> {name.group(1)} нигде не запускается "
                f"(мёртвый компонент)"
            )



SQL_TYPES = [
    (('String', 'List', 'Set', 'Map', 'UUID', 'enum'), 'TEXT'),
    (('Int', 'Long', 'Boolean', 'Byte', 'Short'), 'INTEGER'),
    (('Double', 'Float'), 'REAL'),
]


def _sql_type(kotlin_type: str) -> str | None:
    base = kotlin_type.split('<')[0].strip().rstrip('?')
    for prefixes, sql in SQL_TYPES:
        if base in prefixes:
            return sql
    # Байтовые массивы Room хранит как BLOB, enum'ы и прочие классы — как TEXT
    # (через TypeConverter).
    if base == 'ByteArray':
        return 'BLOB'
    if base and base[0].isupper():
        return 'TEXT'
    return None


def _entity_schemas() -> dict[str, dict[str, tuple[str, bool]]]:
    """tableName -> {column: (sql_type, not_null)} из @Entity-классов."""
    schemas: dict[str, dict[str, tuple[str, bool]]] = {}
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        text = path.read_text(encoding='utf-8')
        for m in re.finditer(r'@Entity\s*\(', text):
            ann = _balanced(text, m.end() - 1)
            table_m = re.search(r'tableName\s*=\s*"([^"]+)"', ann)
            cls_pos = text.find('data class ', m.end())
            if cls_pos < 0 or not table_m:
                continue
            paren = text.find('(', cls_pos)
            if paren < 0:
                continue
            columns: dict[str, tuple[str, bool]] = {}
            for raw in _split_top_level(_balanced(text, paren)):
                if '@Ignore' in raw:
                    continue
                col_m = re.search(r'@ColumnInfo\s*\(\s*name\s*=\s*"([^"]+)"', raw)
                prop_m = re.search(r'\b(?:val|var)\s+(\w+)\s*:\s*([\w<>,. ?]+)', raw)
                if not prop_m:
                    continue
                name = col_m.group(1) if col_m else prop_m.group(1)
                kotlin_type = prop_m.group(2).strip()
                sql = _sql_type(kotlin_type)
                if sql is None:
                    continue
                columns[name] = (sql, '?' not in kotlin_type)
            schemas[table_m.group(1)] = columns
    return schemas


def _migration_schemas() -> dict[str, dict[str, tuple[str, bool]]]:
    """Таблицы, созданные/изменённые миграциями: tableName -> колонки."""
    path = ROOT / 'app/src/main/java/com/phoneguard/data/local/AppDatabase.kt'
    if not path.exists():
        return {}
    text = path.read_text(encoding='utf-8')
    schemas: dict[str, dict[str, tuple[str, bool]]] = {}
    for m in re.finditer(r'CREATE TABLE IF NOT EXISTS\s+`(\w+)`\s*\(', text):
        table = m.group(1)
        body = _balanced(text, m.end() - 1)
        columns: dict[str, tuple[str, bool]] = {}
        for part in _split_top_level(body):
            col_m = re.match(r'`(\w+)`\s+([A-Z]+)(.*)$', part, re.S)
            if not col_m:
                continue
            columns[col_m.group(1)] = (
                col_m.group(2),
                'NOT NULL' in col_m.group(3),
            )
        schemas[table] = columns
    for m in re.finditer(r'ALTER TABLE\s+`(\w+)`\s+ADD COLUMN\s+`(\w+)`\s+([A-Z]+)([^"\n]*?)"', text):
        table, column, sql_type, rest = m.groups()
        schemas.setdefault(table, {})[column] = (sql_type, 'NOT NULL' in rest)
    return schemas


def check_migrations_match_entities() -> None:
    """Миграции должны давать ровно ту схему, что описана в @Entity (иначе краш на обновлении)."""
    entities = _entity_schemas()
    migrations = _migration_schemas()
    for table, expected in sorted(migrations.items()):
        actual = entities.get(table)
        if actual is None:
            continue
        for column in sorted(set(expected) | set(actual)):
            if column not in actual:
                ERRORS.append(
                    f"AppDatabase.kt: миграция создаёт колонку `{table}.{column}`, "
                    f"которой нет в @Entity"
                )
                continue
            if column not in expected:
                ERRORS.append(
                    f"AppDatabase.kt: в миграциях нет колонки `{table}.{column}`, "
                    f"но она есть в @Entity (Room бросит IllegalStateException)"
                )
                continue
            exp_type, exp_not_null = expected[column]
            act_type, act_not_null = actual[column]
            if exp_type != act_type:
                ERRORS.append(
                    f"AppDatabase.kt: `{table}.{column}` — миграция {exp_type}, @Entity {act_type}"
                )
            if exp_not_null != act_not_null:
                ERRORS.append(
                    f"AppDatabase.kt: `{table}.{column}` — NOT NULL в миграции "
                    f"{'есть' if exp_not_null else 'нет'}, в @Entity "
                    f"{'есть' if act_not_null else 'нет'}"
                )



def _normalize_route(route: str) -> str:
    """`privacy_detail/${app.packageName}` -> `privacy_detail/{}`."""
    route = re.sub(r'\$\{[^}]*\}', '{}', route)
    route = re.sub(r'\$\w+', '{}', route)
    route = re.sub(r'\{[^}]*\}', '{}', route)
    return route.strip().strip('/')


def check_navigation_routes() -> None:
    """navigate("...") на несуществующий маршрут — IllegalArgumentException в рантайме."""
    sources = {
        p: strip_comments(p.read_text(encoding='utf-8'))
        for p in (ROOT / 'app/src/main/java').rglob('*.kt')
    }
    screens: dict[str, str] = {}
    for code in sources.values():
        for m in re.finditer(r'object\s+(\w+)\s*:\s*Screen\(\s*"([^"]+)"', code):
            screens[m.group(1)] = m.group(2)

    destinations: set[str] = set()
    for code in sources.values():
        for m in re.finditer(r'composable\(\s*(?:route\s*=\s*)?', code):
            tail = code[m.end():m.end() + 120]
            literal = re.match(r'"([^"]*)"', tail)
            if literal:
                destinations.add(_normalize_route(literal.group(1)))
                continue
            ref = re.match(r'Screen\.(\w+)\.route', tail)
            if ref and ref.group(1) in screens:
                destinations.add(_normalize_route(screens[ref.group(1)]))

    for path, code in sources.items():
        for m in re.finditer(r'\.navigate\(\s*', code):
            tail = code[m.end():m.end() + 160]
            target: str | None = None
            literal = re.match(r'"([^"]*)"', tail)
            if literal:
                target = _normalize_route(literal.group(1))
            else:
                ref = re.match(r'Screen\.(\w+)\.route', tail)
                if ref and ref.group(1) in screens:
                    target = _normalize_route(screens[ref.group(1)])
            if target is None or not destinations:
                continue
            if target not in destinations:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: переход на маршрут «{target}», "
                    f"которого нет ни в одном composable()"
                )



ICON_STYLES = {
    'Default': 'filled',
    'Filled': 'filled',
    'Outlined': 'outlined',
    'Rounded': 'rounded',
    'Sharp': 'sharp',
    'TwoTone': 'twotone',
}


def check_icon_imports() -> None:
    """`Icons.Default.X` — это extension-свойство, нужен import `material.icons.filled.X`."""
    pattern = re.compile(
        r'\bIcons\.(?:AutoMirrored\.)?(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.(\w+)'
    )
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        code = strip_comments(path.read_text(encoding='utf-8'))
        imports = set(re.findall(r'^import\s+([\w.]+)', code, re.M))
        for m in pattern.finditer(code):
            style = ICON_STYLES[m.group(1)]
            name = m.group(2)
            if name in ('AutoMirrored',):
                continue
            mirrored = 'AutoMirrored.' in m.group(0)
            expected = (
                f"androidx.compose.material.icons.automirrored.{style}.{name}"
                if mirrored else
                f"androidx.compose.material.icons.{style}.{name}"
            )
            star = f"androidx.compose.material.icons{'.automirrored' if mirrored else ''}.{style}.*"
            if expected not in imports and star not in imports:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: Icons.{m.group(1)}.{name} без импорта "
                    f"`{expected}`"
                )



def check_comment_terminators() -> None:
    """`/*` или `*/` внутри блочного комментария закрывает его раньше времени — код перестаёт компилироваться."""
    for path in list((ROOT / 'app/src').rglob('*.kt')):
        in_block = False
        for number, line in enumerate(path.read_text(encoding='utf-8').split('\n'), 1):
            stripped = line.strip()
            if in_block:
                body = stripped[:-2] if stripped.endswith('*/') else stripped
                if '*/' in body or '/*' in body:
                    ERRORS.append(
                        f"{path.relative_to(ROOT)}:{number}: внутри блочного комментария есть "
                        f"«/*» или «*/» — комментарий закроется раньше времени"
                    )
                if stripped.endswith('*/'):
                    in_block = False
            elif stripped.startswith('/*') and not stripped.endswith('*/'):
                in_block = True



FEATURE_REQUIREMENTS = {
    'android.permission.CAMERA': [
        'android.hardware.camera', 'android.hardware.camera.autofocus',
        'android.hardware.camera.flash',
    ],
    'android.permission.RECORD_AUDIO': ['android.hardware.microphone'],
    'android.permission.ACCESS_FINE_LOCATION': [
        'android.hardware.location', 'android.hardware.location.gps',
    ],
    'android.permission.ACCESS_COARSE_LOCATION': ['android.hardware.location.network'],
    'android.permission.READ_PHONE_STATE': ['android.hardware.telephony'],
    'android.permission.SEND_SMS': ['android.hardware.telephony'],
    'android.permission.CALL_PHONE': ['android.hardware.telephony'],
}


def check_uses_features() -> None:
    """Аппаратные разрешения без `<uses-feature required="false">` скрывают приложение в Play."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    text = manifest.read_text(encoding='utf-8')
    permissions = set(re.findall(r'<uses-permission[^>]*android:name="([\w.]+)"', text))
    optional = set(
        re.findall(
            r'<uses-feature[^>]*android:name="([\w.]+)"[^>]*android:required="false"', text
        )
    )
    for permission, features in FEATURE_REQUIREMENTS.items():
        if permission not in permissions:
            continue
        for feature in features:
            if feature not in optional:
                ERRORS.append(
                    f"AndroidManifest.xml: разрешение {permission.split('.')[-1]} требует "
                    f"<uses-feature android:name=\"{feature}\" android:required=\"false\"/>"
                )


def _target_sdk() -> int:
    gradle = ROOT / 'app/build.gradle'
    if not gradle.exists():
        return 0
    m = re.search(r'targetSdk\s+(\d+)', gradle.read_text(encoding='utf-8'))
    return int(m.group(1)) if m else 0


def check_backup_rules() -> None:
    """allowBackup=false не запрещает D2D-перенос: нужны dataExtractionRules без include."""
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if not manifest.exists():
        return
    text = manifest.read_text(encoding='utf-8')
    backup_disabled = 'android:allowBackup="false"' in text
    if not backup_disabled:
        return
    if _target_sdk() >= 31:
        if 'android:dataExtractionRules=' not in text:
            ERRORS.append(
                "AndroidManifest.xml: allowBackup=\"false\", но нет android:dataExtractionRules — "
                "на Android 12+ данные всё равно уедут при переносе на новое устройство"
            )
    rules = ROOT / 'app/src/main/res/xml/data_extraction_rules.xml'
    if rules.exists() and '<include' in rules.read_text(encoding='utf-8'):
        ERRORS.append(
            "data_extraction_rules.xml: есть <include> — резервная копия вынесет данные "
            "приложения (сейф, хэш PIN, базу) за пределы устройства"
        )



PRIMITIVE_RETURNS = {'Boolean', 'Int', 'Long', 'Float', 'Double', 'String', 'Unit'}
RETURN_TYPE_RE = re.compile(r'\bfun\s+(\w+)\s*\([^)]*\)\s*:\s*([\w<>.?]+)')


def _method_return_types() -> dict[str, set[str]]:
    """Имя функции -> возможные объявленные типы возврата (только примитивы/Unit)."""
    result: dict[str, set[str]] = {}
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        code = strip_comments(path.read_text(encoding='utf-8'))
        for name, ret in RETURN_TYPE_RE.findall(code):
            base = ret.rstrip('?').split('<')[0]
            if base in PRIMITIVE_RETURNS:
                result.setdefault(name, set()).add(base)
    return result


def check_mockk_stub_types() -> None:
    """`coEvery { mock.fn() } returns Unit` для функции, возвращающей Boolean — ошибка компиляции."""
    return_types = _method_return_types()
    if not return_types:
        return
    stub_re = re.compile(
        r'\b(?:coEvery|every)\s*\{\s*[\w.]*?(\w+)\s*\([^)]*\)\s*\}\s*returns\s+([^\n]+)'
    )
    literal_types = [
        (re.compile(r'^(true|false)$'), 'Boolean'),
        (re.compile(r'^-?\d+L$'), 'Long'),
        (re.compile(r'^-?\d+$'), 'Int'),
        (re.compile(r'^-?\d+\.\d*f?$'), 'Double'),
        (re.compile(r'^"'), 'String'),
        (re.compile(r'^Unit$'), 'Unit'),
    ]
    for src in (ROOT / 'app/src/test/java', ROOT / 'app/src/androidTest/java'):
        if not src.exists():
            continue
        for path in src.rglob('*.kt'):
            code = strip_comments(path.read_text(encoding='utf-8'))
            for m in stub_re.finditer(code):
                method, value = m.group(1), m.group(2).strip()
                declared = return_types.get(method)
                if not declared or len(declared) != 1:
                    continue
                expected = next(iter(declared))
                actual = next((t for pattern, t in literal_types if pattern.match(value)), None)
                if actual is None or actual == expected:
                    continue
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: заглушка {method}() возвращает {actual}, "
                    f"а функция объявлена как {expected}"
                )



def _primary_constructor_arity(text: str) -> dict[str, tuple[int, int]]:
    """Класс -> (обязательных параметров, всего параметров)."""
    result: dict[str, tuple[int, int]] = {}
    for m in re.finditer(r'\bclass\s+(\w+)\s*(?:<[^>]*>)?\s*(?:@\w+(?:\([^)]*\))?\s*)*\(', text):
        name = m.group(1)
        open_idx = text.index('(', m.end() - 1)
        body = _balanced(text, open_idx)
        required = total = 0
        for raw in _split_top_level(body, angle=True):
            if not raw:
                continue
            cleaned = re.sub(r'@\w+(?:\([^)]*\))?', '', raw).strip()
            if not cleaned:
                continue
            total += 1
            if '=' not in cleaned:
                required += 1
        if total:
            result[name] = (required, total)
    return result


def check_constructor_arity() -> None:
    """Вызов конструктора класса проекта с неверным числом аргументов (ошибка компиляции)."""
    arity: dict[str, tuple[int, int]] = {}
    sources = {
        p: strip_comments(p.read_text(encoding='utf-8'))
        for src in SRC_SETS if src.exists()
        for p in src.rglob('*.kt')
    }
    for code in sources.values():
        for name, value in _primary_constructor_arity(code).items():
            arity.setdefault(name, value)
    if not arity:
        return
    for path, code in sources.items():
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\s*(?:<[^>]+>)?\s*\(', code):
            name = m.group(1)
            if name not in arity:
                continue
            before = code[max(0, m.start() - 12):m.start()]
            if 'class ' in before or '::' in before:
                continue
            open_idx = code.index('(', m.end() - 1)
            if name in ('Intent', 'Bundle', 'MutableStateFlow', 'StateFlow'):
                continue
            args = [a for a in _split_top_level(_balanced(code, open_idx)) if a]
            close_idx = open_idx + 2 + len(_balanced(code, open_idx))
            if code[close_idx:].lstrip().startswith('{'):
                args.append('<lambda>')
            required, total = arity[name]
            if required <= len(args) <= total:
                continue
            # `Foo()` без параметров может быть вызовом companion-функции/интерфейса — пропускаем.
            if not args:
                continue
            line = code.count('\n', 0, m.start()) + 1
            ERRORS.append(
                f"{path.relative_to(ROOT)}:{line}: {name}(...) — передано {len(args)} арг., "
                f"конструктор ждёт {required}..{total}"
            )



def _primary_constructor_params(text: str) -> dict[str, list[tuple[str, str, bool]]]:
    """Класс -> [(имя параметра, простой тип или '', есть ли значение по умолчанию)]."""
    result: dict[str, list[tuple[str, str, bool]]] = {}
    for m in re.finditer(r'\bclass\s+(\w+)\s*(?:<[^>]*>)?\s*(?:@\w+(?:\([^)]*\))?\s*)*\(', text):
        name = m.group(1)
        open_idx = text.index('(', m.end() - 1)
        body = _balanced(text, open_idx)
        params: list[tuple[str, str, bool]] = []
        for raw in _split_top_level(body, angle=True):
            cleaned = re.sub(r'@\w+(?:\([^)]*\))?', '', raw).strip()
            if not cleaned or ':' not in cleaned:
                continue
            head, _, tail = cleaned.partition(':')
            param_name = head.split()[-1]
            has_default = '=' in tail
            type_text = tail.split('=')[0].strip()
            simple = re.fullmatch(r'(String|Int|Long|Short|Byte|Float|Double|Boolean)(\?)?', type_text)
            params.append((param_name, simple.group(1) if simple else '', has_default))
        if params:
            result[name] = params
    return result


_KIND_OF_LITERAL = (
    (re.compile(r'^"[^"]*"$'), 'String'),
    (re.compile(r'^(true|false)$'), 'Boolean'),
    (re.compile(r'^-?\d+[lL]$'), 'Long'),
    (re.compile(r'^-?\d+\.\d*[fF]$'), 'Float'),
    (re.compile(r'^-?\d+\.\d*$'), 'Double'),
    (re.compile(r'^-?\d+$'), 'Int'),
)
_NUMERIC = {'Int', 'Long', 'Short', 'Byte', 'Float', 'Double'}


def check_constructor_argument_kinds() -> None:
    """Литерал явно не того типа на позиции параметра конструктора.

    Реальный случай: `BlockedNumber("+7999…", "Spam", false, ts)` — у сущности первым
    идёт `id: Long`, поэтому строка попадала в `id`, а `false` — в `name: String?`.
    Число аргументов при этом совпадало, поэтому проверка арности молчала.
    Сравниваются только однозначные случаи: литерал против простого типа.
    """
    sources = {
        p: strip_comments(p.read_text(encoding='utf-8'))
        for src in SRC_SETS if src.exists()
        for p in src.rglob('*.kt')
    }
    params: dict[str, list[tuple[str, str, bool]]] = {}
    for code in sources.values():
        for name, value in _primary_constructor_params(code).items():
            params.setdefault(name, value)
    if not params:
        return

    for path, code in sources.items():
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\s*(?:<[^>]+>)?\s*\(', code):
            name = m.group(1)
            decl = params.get(name)
            if not decl:
                continue
            before = code[max(0, m.start() - 12):m.start()]
            if 'class ' in before or '::' in before:
                continue
            open_idx = code.index('(', m.end() - 1)
            raw_args = [a.strip() for a in _split_top_level(_balanced(code, open_idx)) if a.strip()]
            if not raw_args or any('=' in a for a in raw_args):
                continue
            for arg, (param_name, param_type, _default) in zip(raw_args, decl):
                if not param_type:
                    continue
                kind = None
                for pattern, literal_kind in _KIND_OF_LITERAL:
                    if pattern.match(arg):
                        kind = literal_kind
                        break
                if kind is None:
                    continue
                mismatch = (
                    (param_type in _NUMERIC and kind in ('String', 'Boolean'))
                    or (param_type == 'String' and kind in _NUMERIC | {'Boolean'})
                    or (param_type == 'Boolean' and kind != 'Boolean')
                )
                if not mismatch:
                    continue
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: {name}(...) — аргумент {arg} ({kind}) "
                    f"на позиции {param_name}: {param_type}"
                )


def check_missing_named_arguments() -> None:
    """Именованные аргументы конструктора не покрывают обязательные параметры.

    Реальный случай: `SimSwapEvent(id = …, oldImsi = …)` без `latitude/longitude/
    addressString` — у этих параметров нет значения по умолчанию, поэтому код не
    компилировался («No value passed for parameter 'latitude'»). Проверка арности
    такое не видит: количество аргументов «в диапазоне».
    """
    sources = {
        p: strip_comments(p.read_text(encoding='utf-8'))
        for src in SRC_SETS if src.exists()
        for p in src.rglob('*.kt')
    }
    params: dict[str, list[tuple[str, str, bool]]] = {}
    overloaded: set[str] = set()
    for code in sources.values():
        for name, value in _primary_constructor_params(code).items():
            params.setdefault(name, value)
        for m in re.finditer(r'\bconstructor\s*\(', code):
            # У класса есть дополнительные конструкторы — сопоставление по именам ненадёжно.
            head = code[:m.start()]
            cls = list(re.finditer(r'\bclass\s+(\w+)', head))
            if cls:
                overloaded.add(cls[-1].group(1))

    for path, code in sources.items():
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\s*(?:<[^>]+>)?\s*\(', code):
            name = m.group(1)
            decl = params.get(name)
            if not decl or name in overloaded:
                continue
            before = code[max(0, m.start() - 12):m.start()]
            if 'class ' in before or '::' in before:
                continue
            open_idx = code.index('(', m.end() - 1)
            raw_args = [a.strip() for a in _split_top_level(_balanced(code, open_idx)) if a.strip()]
            if not raw_args:
                continue
            named = {a.split('=', 1)[0].strip() for a in raw_args if re.match(r'^\w+\s*=', a)}
            if len(named) != len(raw_args):
                continue
            declared = {param[0] for param in decl}
            unknown = sorted(named - declared)
            if unknown:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: {name}(...) — таких параметров нет: "
                    + ", ".join(unknown)
                )
                continue
            missing = [param[0] for param in decl if not param[2] and param[0] not in named]
            if missing:
                line = code.count('\n', 0, m.start()) + 1
                ERRORS.append(
                    f"{path.relative_to(ROOT)}:{line}: {name}(...) — не переданы обязательные "
                    "параметры: " + ", ".join(missing)
                )


def check_gradle_wiring() -> None:
    """Согласованность Gradle-конфигурации: неопределённые ext-свойства и schema-экспорт Room."""
    root_gradle = ROOT / 'build.gradle'
    app_gradle = ROOT / 'app/build.gradle'
    if not root_gradle.exists() or not app_gradle.exists():
        return
    root_text = root_gradle.read_text(encoding='utf-8')
    app_text = app_gradle.read_text(encoding='utf-8')

    if 'ext {' in root_text:
        ext_block = root_text.split('ext {', 1)[1].split('}', 1)[0]
        defined = set(re.findall(r'(\w+)\s*=', ext_block))
    else:
        defined = set()
    used: set[str] = set()
    for stale in [root_gradle, app_gradle, *ROOT.glob('*/build.gradle')]:
        text = stale.read_text(encoding='utf-8')
        used |= set(re.findall(r'(?:rootProject\.)?ext\.(\w+)', text))
        used |= set(re.findall(r'["\']\$(\w+_version)["\']', text))
    for name in sorted(used - defined):
        ERRORS.append(
            f"build.gradle: свойство `{name}` используется, но не объявлено в rootProject.ext "
            f"(конфигурация проекта упадёт)"
        )

    db = ROOT / 'app/src/main/java/com/phoneguard/data/local/AppDatabase.kt'
    exports_schema = db.exists() and 'exportSchema = true' in db.read_text(encoding='utf-8')
    if exports_schema and 'room.schemaLocation' not in app_text:
        ERRORS.append(
            "app/build.gradle: @Database(exportSchema = true), но не задан аргумент "
            "room.schemaLocation — сборка упадёт на room-compiler"
        )

    uses_migration_helper = any(
        'MigrationTestHelper' in p.read_text(encoding='utf-8')
        for p in (ROOT / 'app/src/androidTest/java').rglob('*.kt')
    )
    if uses_migration_helper:
        if 'schemas' not in app_text or 'androidTest.assets.srcDirs' not in app_text:
            ERRORS.append(
                "app/build.gradle: MigrationTestHelper читает схемы из assets, но "
                "`androidTest.assets.srcDirs += files(\"$projectDir/schemas\")` не настроен"
            )
        if 'androidx.room:room-testing' not in app_text:
            ERRORS.append(
                "app/build.gradle: тесты миграций используют MigrationTestHelper без "
                "androidTestImplementation androidx.room:room-testing"
            )


def check_notification_ids() -> None:
    """Числовые id уведомлений не должны совпадать у разных компонентов.

    Раньше блокировщик звонков использовал id 1001 — тот же, что постоянное
    уведомление VPN: сообщение о заблокированном звонке затирало статус
    брандмауэра и наоборот. Проверка ловит такие совпадения до запуска.
    """
    main = ROOT / 'app/src/main/java'
    used: dict[int, list[str]] = {}
    const_re = re.compile(r'const\s+val\s+([A-Z0-9_]*ID)\s*(?::\s*Int\s*)?=\s*(\d+)\b')
    for path in main.rglob('*.kt'):
        text = path.read_text(encoding='utf-8')
        rel = str(path.relative_to(ROOT))
        for m in const_re.finditer(text):
            value = int(m.group(2))
            used.setdefault(value, []).append(f"{rel}:{m.group(1)}")

    for value, places in sorted(used.items()):
        if len({p.split(':')[0] for p in places}) > 1:
            ERRORS.append(
                "одинаковый id уведомления "
                f"{value} у разных компонентов: {', '.join(sorted(places))}"
            )


def check_deep_links() -> None:
    """Ссылки `phoneguard://…` должны быть объявлены и обработаны.

    Ярлыки на рабочем столе (`res/xml/shortcuts.xml`) и внешние intent'ы открывают
    приложение по схеме `phoneguard`. Раньше ссылки были только в графе навигации:
    манифест их не принимал, а `handleDeepLink` никто не вызывал — ярлык открывал
    дашборд вместо нужного экрана. Проверка следит за тремя частями сразу.
    """
    shortcuts = ROOT / 'app/src/main/res/xml/shortcuts.xml'
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    nav_host = ROOT / 'app/src/main/java/com/phoneguard/ui/navigation/PhoneGuardNavHost.kt'
    if not (shortcuts.exists() and manifest.exists() and nav_host.exists()):
        return

    shortcut_uris = set(re.findall(r'android:data="([^"]+)"', shortcuts.read_text(encoding='utf-8')))
    patterns = set(re.findall(r'uriPattern\s*=\s*"([^"]+)"', nav_host.read_text(encoding='utf-8')))
    manifest_text = manifest.read_text(encoding='utf-8')

    for uri in shortcut_uris:
        if uri not in patterns:
            ERRORS.append(f"res/xml/shortcuts.xml: ссылка {uri} не объявлена ни в одном navDeepLink")

    if shortcut_uris or patterns:
        if 'android:scheme="phoneguard"' not in manifest_text:
            ERRORS.append(
                "AndroidManifest.xml: нет intent-filter со схемой phoneguard — "
                "ярлыки и внешние ссылки не открывают приложение"
            )
        main_activity = re.search(
            r'<activity[^>]*android:name="\.ui\.MainActivity".*?</activity>', manifest_text, re.S
        )
        if main_activity is None or 'android.intent.category.BROWSABLE' not in main_activity.group(0):
            ERRORS.append(
                "AndroidManifest.xml: у MainActivity нет BROWSABLE-фильтра для phoneguard://"
            )
        if not re.search(r'handleDeepLink\s*\(', nav_host.read_text(encoding='utf-8')):
            ERRORS.append(
                "PhoneGuardNavHost.kt: граф объявляет navDeepLink, но не вызывает "
                "navController.handleDeepLink(intent) — ссылки игнорируются"
            )


def main() -> int:
    index, owners = collect_index()
    check_duplicates(owners)
    check_imports(index)
    check_r_refs()
    check_r_imports()
    check_xml()
    check_balance()
    check_known_symbol_imports(index)
    check_dao_calls()
    check_manifest_classes(index)
    check_hilt_bindings()
    check_manifest_exported()
    check_unused_imports()
    check_room_queries()
    check_permission_usage()
    check_xml_resource_refs()
    check_locale_parity()
    check_format_args()
    check_pending_intent_flags()
    check_start_activity()
    check_service_start_guards()
    check_dead_components()
    check_migrations_match_entities()
    check_navigation_routes()
    check_icon_imports()
    check_comment_terminators()
    check_uses_features()
    check_backup_rules()
    check_mockk_stub_types()
    check_constructor_arity()
    check_constructor_argument_kinds()
    check_missing_named_arguments()
    check_gradle_wiring()
    check_member_calls()
    check_notification_ids()
    check_deep_links()

    for w in sorted(set(WARNINGS)):
        print(f"warning: {w}")
    for e in sorted(set(ERRORS)):
        print(f"error: {e}")
    print(f"\n{len(set(ERRORS))} error(s), {len(set(WARNINGS))} warning(s)")
    return 1 if ERRORS else 0


if __name__ == '__main__':
    sys.exit(main())
