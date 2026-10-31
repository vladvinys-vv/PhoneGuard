# PhoneGuard

Android native security application with device malware scanning, local VPN firewall, anti-theft SMS remote commands, call screening, and encrypted biometric vault.

## Features

- **Malware Scanner** — Local ML-based APK analysis with signature & heuristic detection
- **VPN Firewall** — Per-app network control via Android VpnService (no root)
- **Anti-Theft** — SMS remote commands (lock, wipe, locate, siren, photo capture)
- **Call Screening** — Spam/phishing detection with caller ID enrichment
- **Biometric Vault** — Encrypted storage (AES-256-GCM) with BiometricPrompt auth
- **Phone Check** — Чек всего телефона: блокировка экрана, шифрование, патч-уровень, ADB/root-признаки,
  свободное место, устаревшие и отлаживаемые приложения, разрешения, энергосбережение — с оценкой 0..100
- **Privacy-First** — Fully offline capable, no telemetry, no cloud dependencies

## Integrations

| Component | Library / API | Why | License |
|---|---|---|---|
| Номерной анализ | [libphonenumber](https://github.com/google/libphonenumber) (`com.googlecode.libphonenumber:libphonenumber`) | E.164-нормализация номеров для чёрных/белых списков, скрининга звонков и CSV-импорта. Работает офлайн на локальной metadata | Apache-2.0 |
| Анализ подписей APK | [apk-parser](https://github.com/hsiafan/apk-parser) (`net.dongliu:apk-parser`) | Цепочка сертификатов, алгоритм подписи и срок действия — `PackageManager` этого не отдаёт. Нужно для поиска пересобранных APK | Apache-2.0 |
| Threat intelligence | [URLhaus](https://urlhaus.abuse.ch/) + [OpenPhish](https://openphish.com/) через OkHttp | Проверка доменов из подозрительных SMS. **Только хост**, без текста сообщений; опционально, по умолчанию выключено | бесплатно для некоммерческого использования |
| Сеть | [OkHttp](https://square.github.io/okhttp/) | HTTP-клиент для threat intel, HTTPS + pinning утилиты | Apache-2.0 |
| Геолокация | Google Play Services Location | Последняя известная точка для anti-theft | proprietary SDK |
| Face detection | ML Kit face detection | Детектор «подглядывания через плечо» | proprietary SDK |
| Шифрование | SQLCipher + AndroidX Security (`EncryptedFile`) | БД и файлы сейфа | BSD / Apache-2.0 |

> Всё, что связано с приватностью, остаётся на устройстве: аналитика, метрики производительности и отчёты о падениях
> пишутся в локальные файлы (`AnalyticsHelper`, `PerformanceMonitor`, `CrashHandler`). Firebase удалён —
> см. `docs/PRIVACY_POLICY.md`.

## 🤖 AI Agent Skills Integration

### Recommended Skill Collections
```bash
# Skill management
npm install -g @skillkit/cli && skillkit init
skillkit recommend

# Android / Kotlin / Security skills
npx skills add seb1n/awesome-ai-agent-skills --skill android,kotlin,security
npx skills add alirezarezvani/claude-skills --skill android,security

# Security scan before install
npm install -g @nvidia/skillspector
skillspector scan <repo-url>
```

### MCP Server Configuration
Add to agent config (`.claude/settings.json`, `kilo.jsonc`):
```json
{
  "mcpServers": {
    "openregister": "openregister/openregister-sdk",
    "browser": "browser-use/browser-use",
    "bundestag": "local-bundestag-api",
    "bsi": "local-bsi-threat-intel"
  }
}
```

### Subagent Delegation (Kilo Multi-Agent)
Use native `task(agent="...", ...)` for specialized work:
- `task(agent="architect", ...)` — Before new modules, VPN service changes, crypto implementations
- `task(agent="debugger", ...)` — Test failures, VpnService crashes, BiometricPrompt issues
- `task(agent="revisor", ...)` — Pre-commit: security scan, coverage, diff analysis
- `task(agent="security-reviewer", ...)` — Crypto audit, permission review, privacy impact

### Verification Gates (Required per Commit)
```bash
./gradlew lintDebug test assembleDebug
# Secret scanning
npx -y gitleaks detect --no-git -v
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose, Material 3 |
| **Database** | Room (SQLite) |
| **Security** | AES-256-GCM, Android Keystore, BiometricPrompt |
| **Network** | VpnService, OkHttp |
| **Background** | Coroutines, WorkManager, Foreground Services |
| **Build** | Gradle (Groovy DSL), AGP, R8 |

## Architecture

```
PhoneGuard/
├── app/
│   ├── src/main/java/com/phoneguard/
│   │   ├── antitheft/      # SMS-команды, Device Admin, сигнализация
│   │   ├── callblocker/    # CallScreeningService, блокировка звонков/SMS
│   │   ├── data/           # Room (SQLCipher), DataStore, репозитории
│   │   ├── di/             # Hilt-модули
│   │   ├── domain/         # use-case'ы (shoulder surfer и т.п.)
│   │   ├── deviceaudit/    # чек всего телефона (20 проверок)
│   │   ├── firewall/       # VpnService + правила блокировки
│   │   ├── fullscan/       # оркестратор проверки и 16 шагов сканирования
│   │   ├── intrusion/      # honey-pot, реагирование на вторжение
│   │   ├── malware/        # анализ подписи APK (apk-parser)
│   │   ├── model/          # доменные модели / Room-сущности
│   │   ├── shouldersurfer/ # камера + ML Kit (защита от подглядывания)
│   │   ├── simswap/        # детектор подмены SIM
│   │   ├── spywarecheck/   # проверка шпионских приложений
│   │   ├── threatintel/    # opt-in проверка доменов по URLhaus/OpenPhish
│   │   ├── ui/             # Compose-экраны, темы, навигация
│   │   ├── util/           # нормализация номеров, PDF/CSV, аналитика
│   │   ├── vault/          # шифрованное хранилище файлов
│   │   └── worker/         # WorkManager-задачи
│   └── src/main/res/       # strings (ru/en/de), темы, иконки
├── app/build.gradle
├── build.gradle
└── settings.gradle
```

### Чек всего телефона

`deviceaudit/DeviceAuditChecker` выполняет 20 проверок состояния устройства и возвращает
`DeviceAuditReport` (оценка 0..100 + список `AuditItem`). Элементы содержат `@StringRes`, поэтому
экран (`ui/screens/phonecheck`) рисует их на выбранном языке, а полное сканирование использует те же
проверки как 16-й шаг и добавляет найденные проблемы в отчёт (`ScanIssue`).

| Группа | Что проверяется |
|---|---|
| Безопасность | блокировка экрана, шифрование памяти, SELinux, ADB (USB/Wi-Fi), режим разработчика, администраторы устройства |
| Приложения | неизвестные источники, Play Protect/верификатор, устаревшая `targetSdk`, debuggable-сборки, службы доступности, число приложений |
| Система | уровень патча безопасности, ключевые разрешения, уведомления, энергосбережение, версия Android, модель |
| Сеть и память | активное VPN, свободное место |

Оценка: `100 − 15 × критичных − 7 × предупреждений` (не ниже 0). Результат сохраняется в DataStore,
поэтому дашборд показывает его без повторной проверки.

### Локализация

Интерфейс полностью переведён на русский, английский и немецкий (`values`, `values-en`,
`values-ru`, `values-de`; язык переключается в настройках и применяется в `attachBaseContext`).
Тексты технических отчётов полного сканирования (`ScanIssue`) формируются проверками и
сохраняются в истории на языке по умолчанию (русский) — они не переводятся.

## Quick Start

```bash
# Clone and setup
git clone https://github.com/vladvinys-vv/PhoneGuard.git
cd PhoneGuard

# Configure keystore (never commit!)
cp keystore.properties.example keystore.properties
# Edit keystore.properties with your release keys

# (опционально) ключи threat intel — по умолчанию проверка доменов выключена
# см. app/src/main/java/com/phoneguard/threatintel/ThreatIntelClient.kt

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Build release AAB (requires signed keystore)
./build_release.sh
```

## Security Model

- **Zero Trust**: All components validate inputs, no implicit trust
- **Least Privilege**: Minimal permissions, scoped to feature needs
- **Encryption**: AES-256-GCM for vault, Android Keystore for keys
- **Integrity**: ProGuard/R8 obfuscation, SafetyNet/Play Integrity API
- **Transparency**: Open source, reproducible builds, no hidden code

## Privacy

- **No Telemetry**: Zero analytics, crash reporting, or usage tracking
- **Local-First**: All processing on-device, optional cloud sync (user-controlled)
- **GDPR/DSGVO Ready**: Data minimization, right to deletion, no PII collection
- **German Compliance**: BSI guidelines, IT-Sicherheitsgesetz aligned

## Distribution

- **Google Play Store**: Primary distribution channel
- **GitHub Releases**: Direct APK/AAB downloads with checksums
- **F-Droid**: Planned (requires reproducible builds)

## License

Private - All rights reserved. Security-focused code visible for audit only.