# CodeForge v2 — Autonomous Android Coding & Execution Workspace

CodeForge v2 is an autonomous AI coding agent and Termux/POSIX execution workspace for Android.

## Key Capabilities in v2
1. **Termux & Forge Engine Bridge (`TermuxBridge.kt`)**:
   - Connects to F-Droid / GitHub Termux via `com.termux.permission.RUN_COMMAND` and boots a loopback daemon (`127.0.0.1`, `~/forge/.engine/forge_server.py`) authenticated with a Keystore-backed Bearer token.
   - Falls back automatically to an embedded POSIX workspace shell (`~/forge`) when Termux is not installed.
   - Pull-up **Forge Terminal Drawer** in Chat and **"Show command"** toggle + 1-tap Copy on every command step.
2. **Multi-Tier File Edit Engine & Loop Guard (`AgentCoreComponents.kt`)**:
   - Enforces **Read-Before-Edit** on every run.
   - Multi-tier matching (exact → line-prefix stripped → whitespace-tolerant → indentation-insensitive → `insert_after_line` / `replace_line_range`) with nearest-match line-number snippets when an edit fails.
   - `LoopGuard` prevents 3x identical calls, recovers from 3x failed edits on the same file, and halts 8-step no-progress loops.
3. **Server-Only Live Model Discovery & 9-Class Failover (`ProviderRepository.kt`)**:
   - **Zero hardcoded model names** anywhere in the codebase. Models, context windows, and capabilities are fetched live from each provider's `/models` endpoint when you tap **Test Connection** or **Test All**.
   - Multi-key rotation per provider and automatic failover across `RATE_LIMIT` (429), `QUOTA_EXHAUSTED`, `AUTH` (401/403), `MODEL_UNAVAILABLE` (404), `CONTEXT_TOO_LONG`, `TRANSIENT` (5xx), `CONTENT_BLOCKED`, `NETWORK_DOWN`, and `BAD_REQUEST`.
   - Includes a live **Fault Simulator** in Settings to test failover on demand.
4. **Projects Workspace (`~/forge/projects/<slug>`), `AGENT.md` Memory & Tools Manager (`~/forge/tools/<tool>`)**:
   - Every project has its own directory, Git branch tracking, automatic checkpoints, and `AGENT.md` memory file.
   - GitHub repositories can be cloned as a project or installed as a reusable CLI/MCP tool after Termux/ARM compatibility inspection and Plan Card approval.
   - APK / archive attachments are analyzed into `ANALYSIS.md` and end with the **"Save this source to a project?"** prompt.

## Manual Verification Checklist
- [ ] **Termux & Diagnostics**: Open **Settings → 1. Setup & Diagnostics** and tap **Run Self-Test** and **Start / Repair Engine**. Open the **Terminal Drawer** (`>_` icon in Chat header) and run `ls -la` or `echo hello`.
- [ ] **Live Server Model Discovery**: Open **Settings → Manage Providers & Keys**, enter an API key (or local endpoint), and tap **Test Connection & Load Models**. Verify the green status dot, latency in ms, and live model list in the **Model Picker** bottom sheet.
- [ ] **Fault Simulator & Failover**: In **Settings → 4. Fallback Engine & Fault Simulator**, tap **Simulate 429** and send a chat message to observe automatic key/provider failover and the entry in the **Recent Failover Activity Log**.
- [ ] **Build Mode & Working Files**: Switch the header pill to **Build**, attach or create a project in **Projects**, ask the agent to edit a file, and tap the **Working Files** badge in the header to inspect `+X -Y` diffs or revert individual files.
- [ ] **APK / Archive Analysis Save Prompt**: Attach an `.apk` or archive in Chat using `+` and send; verify the **"Save this source to a project?"** modal offers saving to a new project, active project (`reference/<name>/`), report only, or temporary output.
