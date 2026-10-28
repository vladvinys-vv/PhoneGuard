# Project rules: PhoneGuard

Global rules live in ~/.zcode/AGENTS.md. This file adds project specifics.
HANDOFF.md is the full contract (mission, queue, acceptance per task).

## Project
- What it is: Android native security application with device malware scanning, local VPN firewall, anti-theft SMS remote commands, call screening, and encrypted biometric vault.
- Stack: Android SDK (Kotlin), Jetpack Compose, Room DB, Android VpnService, Biometric API, Coroutines
- Target users / market: Global (UI in Russian/English)

## Commands (run from the workspace root with absolute paths)
- Build: `./gradlew assembleDebug` (Windows: `.\gradlew.bat assembleDebug`)
- Test: `./gradlew test` (Windows: `.\gradlew.bat test`)
- Language check (must print nothing before each commit):
  `git -C C:/Users/vladv/projects/PhoneGuard diff --cached | grep -nP '[\x{4e00}-\x{9fff}]' || true`

## Boundaries
- Workspace: C:\Users\vladv\projects\PhoneGuard
- Allowed write paths: `app/src/`, `docs/`, `WORKLOG.md`, `STUCK.md`, `STATUS.md`, `requests/`
- Read-only: `gradle/`, `gradlew*`, `build.gradle` root
- Project red lines: NEVER commit .keystore, .jks, or hardcoded cryptographic master keys; keep allowBackup="false".

## Conventions
- Kotlin: idiomatic Kotlin, safe coroutines, proper timeout handling in background services
- One feature per commit; commit message format: `T<id>: <what>` or conventional commit

## AI Agent Skills & MCP Integration (2026)

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
Configure in agent settings (`.claude/settings.json`, `kilo.jsonc`):
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

### German API Integration Points
| API | MCP/REST | Use Case | Config |
|---|---|---|---|
| OpenRegister | REST + SDK | Verify app publisher legitimacy | `OPENREGISTER_API_KEY` |
| Bundestag DIP | REST | Legal compliance reference | No auth |
| Bundestag Live | REST | Regulatory change monitoring | No auth |
| BSI Advisories | RSS/API | Threat intelligence feeds | No auth |
| OpenPlz | REST | Geo-fencing for anti-theft | No auth |
| Feiertage | REST | Maintenance window optimization | No auth |

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