# PhoneGuard

Android native security application with device malware scanning, local VPN firewall, anti-theft SMS remote commands, call screening, and encrypted biometric vault.

## Features

- **Malware Scanner** — Local ML-based APK analysis with signature & heuristic detection
- **VPN Firewall** — Per-app network control via Android VpnService (no root)
- **Anti-Theft** — SMS remote commands (lock, wipe, locate, siren, photo capture)
- **Call Screening** — Spam/phishing detection with caller ID enrichment
- **Biometric Vault** — Encrypted storage (AES-256-GCM) with BiometricPrompt auth
- **Privacy-First** — Fully offline capable, no telemetry, no cloud dependencies

## 🇩🇪 German API Integrations

PhoneGuard integrates with official German public APIs for enhanced threat intelligence:

| API | Purpose | Auth | Integration |
|---|---|---|---|
| [OpenRegister (Handelsregister)](https://openregister.de/api) | 4M+ German companies for developer verification | `apiKey` | Verify app publisher legitimacy |
| [Bundestag DIP API](https://dip.bundestag.de/ueber-dip/hilfe/api) | German cybersecurity laws, proceedings | No | Legal compliance reference |
| [Bundestag Live API](https://bundestag.api.bund.dev/) | Real-time legislative updates | No | Regulatory change monitoring |
| [Gesetze im Internet](https://www.gesetze-im-internet.de/) | BGB, HGB, DSGVO, IT-Sicherheitsgesetz | No | Legal framework for security features |
| [BSI Cyber-Sicherheit](https://www.bsi.bund.de/) | German Federal Office for IT Security advisories | No | Threat intelligence feeds |
| [OpenPlz API](https://openplzapi.org/de/Localities) | German postal codes for geo-fencing | No | Location-based security rules |
| [Feiertage API](https://feiertage-api.de/api/?jahr=2026) | Official holidays for scheduling | No | Maintenance window optimization |

> **Note:** APIs marked with `apiKey` require environment variables in `.env` or `local.properties`.

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
| **Build** | Gradle Kotlin DSL |

## Architecture

```
PhoneGuard/
├── app/
│   ├── src/main/
│   │   ├── java/com/phoneguard/
│   │   │   ├── malware/          # ML scanner, signature DB
│   │   │   ├── firewall/         # VpnService implementation
│   │   │   ├── antitheft/        # SMS commands, device admin
│   │   │   ├── callscreen/       # Caller ID, spam detection
│   │   │   ├── vault/            # Encrypted storage, biometrics
│   │   │   ├── core/             # Shared: crypto, prefs, utils
│   │   │   └── ui/               # Compose screens, themes
│   │   └── res/                  # Strings (ru/en), XML configs
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

## Quick Start

```bash
# Clone and setup
git clone https://github.com/vladvinys-vv/PhoneGuard.git
cd PhoneGuard

# Configure keystore (never commit!)
cp keystore.properties.example keystore.properties
# Edit keystore.properties with your release keys

# Configure Google Services
cp app/google-services.json.example app/google-services.json
# Add your Firebase config

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