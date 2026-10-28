# Google Jules Rules for PhoneGuard

## Role & Objectives
You are Google Jules assisting with **PhoneGuard**, an Android security and system protection application providing permission auditing, background tracker blocking, and device safety insights.

## Core Principles & Domain Standards
1. **Device Security & Privacy First:**
   - Follow principle of least privilege.
   - Do not require unnecessary runtime permissions (e.g. location, contacts) unless fundamentally required by specific audit tools.
   - All auditing occurs strictly on-device without telemetry or cloud phone-home.
2. **Battery & Resource Efficiency:**
   - Zero background battery drain: use WorkManager and opportunistic constraints (e.g., device charging/idle) for scheduled audits.
   - Do not maintain perpetual wakelocks.
3. **Android Architecture & Compatibility:**
   - Adhere to modern Android 14+ / 15 background execution limits and permission restrictions.
   - Utilize clean MVVM architecture, Kotlin Coroutines, and Flow for event streams.
4. **Keystore & Secrets Management:**
   - Never commit keystores, signing keys, or `keystore.properties` to version control.
   - Always reference `keystore.properties.example` for templated setups.
5. **Testing & Quality Gates:**
   - Ensure `./gradlew test` passes before submitting pull requests or merging into main.
