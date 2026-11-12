# CodeForge v2 — Changelog

## v2.0.0 — Full Autonomous Upgrade

### 1. Real Termux & Forge Engine Bridge (Problem 1 Fixed)
- Added `com.termux.permission.RUN_COMMAND` and `<queries><package android:name="com.termux" /></queries>` in `AndroidManifest.xml`.
- Created `TermuxBridge.kt` with Termux flavor detection (`NOT_INSTALLED`, `PLAY_STORE_BUILD`, `INSTALLED_NO_PERMISSION`, `READY`, `CONNECTED`), 1-tap bootstrap of `~/forge/.engine/forge_server.py` (`127.0.0.1` loopback HTTP server with hardware Keystore-backed Bearer token), `/health` heartbeat, real-time command execution, embedded POSIX workspace fallback, and `command_audit.log`.
- Added pull-up **Forge Terminal Drawer** in Chat and **Setup & Diagnostics** self-test in Settings.

### 2. Multi-Tier File Edit Engine & Loop Guard (Problem 2 Fixed)
- Enforced **Read-Before-Edit** in `AgentEngine.kt`: `edit_file`, `insert_after_line`, and `replace_line_range` require reading the target file in the current run first.
- Built `FileEditEngine` (`AgentCoreComponents.kt`) with automatic line-number prefix stripping, exact match, whitespace-tolerant match, indentation-insensitive match, `insert_after_line`, `replace_line_range`, atomic temp-rename writes, and nearest fuzzy-match line-number snippet feedback on failure.
- Built `LoopGuard` (`AgentCoreComponents.kt`) with rolling 10-step window: blocks 3x identical tool calls, forces re-read + strategy switch after 3 failed edits on the same file, and stops after 8 steps without progress.

### 3. Repo & Tool / MCP Installer + APK Save Output Prompt (Problem 3 Fixed)
- Built `RepoToolInstaller.kt` to inspect repositories (`package.json`, `pyproject.toml`, `Cargo.toml`, `go.mod`, `Gradle`), flag Termux/ARM incompatibilities (Docker, CUDA, `sudo`/`apt`), present an interactive **Plan Card** for user approval, install in isolated `~/forge/tools/<tool>/` environments, smoke-test, and register in Room (`InstalledToolEntity`).
- Added **Tools & MCP Manager** in Settings (enable/disable, smoke test, uninstall).
- Added APK/archive analysis generating `ANALYSIS.md` and presenting the **"Save this source to a project?"** interactive card (`Save to a new project`, `Save into "<active-project>"`, `Save report only`, `Don't save`).

### 4. Resilient Multi-Provider Fallback Engine & Server-Only Models (Problem 4 Fixed)
- Removed all hardcoded model IDs and static pricing tables from the codebase (`grep` verified).
- Added live model fetching & metadata caching (`ModelCacheEntity`) with `Test Connection & Load Models` and `Test All` diagnostics.
- Implemented 9-class error classification (`RATE_LIMIT`, `QUOTA_EXHAUSTED`, `AUTH`, `MODEL_UNAVAILABLE`, `CONTEXT_TOO_LONG`, `TRANSIENT`, `CONTENT_BLOCKED`, `NETWORK_DOWN`, `BAD_REQUEST`), multi-key rotation (`ApiKeyEntity`), per-model health/cooldown tracking (`HealthStateEntity`), selectable fallback strategies, and a live **Fault Simulator** in Settings.

### 5. Soft, Premium UI Redesign & Room Migration v1 → v2
- Added non-destructive Room migration `MIGRATION_1_2` preserving all existing projects, chats, messages, and encrypted API keys.
- Redesigned navigation to a 3-item floating pill bar (**Projects · Chat · Settings**) with **Dark (`#0E1014`)**, **Light (`#F6F7F9`)**, **AMOLED (`#000000`)**, and **System** themes plus **5 Accent presets** (Indigo, Teal, Violet, Amber, Rose).
- Added **Projects Home Screen** (`~/forge/projects/<slug>`, per-project `AGENT.md` memory editor, "What is a project?" explainer), **Working Files Bottom Sheet**, and **15-Section Searchable Settings**.
