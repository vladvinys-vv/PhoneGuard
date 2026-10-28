# HANDOFF: PhoneGuard

## Mission
Deliver a lightweight, private, permission-conscious Android security tool combining local packet inspection via VpnService, trusted-sender remote anti-theft triggers, and PBKDF2/AES-GCM secured vault storage.

## Boundaries
- Allowed write paths: `app/src/`, `docs/`, WORKLOG.md, STUCK.md, STATUS.md, requests/
- Do not touch: release keystores, native gradle wrappers
- Decisions reserved for me: firewall default rules, anti-theft trigger syntax, permission list

## Environment
- OS: Windows 11 (Git Bash). Runtimes: JDK 17, Android SDK.
- External services: none (local on-device processing)

## Finish line
Repository default branch is `main`, untracked gradle caches removed, security hardening committed.

## Queue
### T1: Security hardening and repository hygiene
- Goal: Add AGENTS.md, HANDOFF.md, .gitignore, remove tracked .gradle cache, commit PBKDF2 PIN hashing and VPN packet parsing fixes.
- Context: Migrated to central `projects/` directory, default branch verified as main.
- Constraints: Read-only check.
- Done when: `AGENTS.md` and `HANDOFF.md` present, working directory clean on main.
- Not acceptable: tracking binary build caches.
