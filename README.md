# CodeForge — Android AI Coding Agent

Chat-based coding agent for Android (Kotlin + Jetpack Compose). Import a project ZIP, chat about bugs/features, send screenshots and files, and get the fixed project back as a ZIP. Works like Claude Code / Codex, but on a phone, with your own API keys.

## Interface
- Clean light/dark UI (Settings → Appearance: System / Light / Dark).
- Chat header shows the active model; tap it to switch provider/model. Each reply shows which model answered.
- The agent's work is shown live as a terminal-style log (Read / Edit / Search / Write …); tap a line to see details or the exact edit.
- Every app start opens a NEW chat. Old chats are in the side drawer (menu button, top left), across all projects.
- The model selector sits inside the message box next to the send button.
- While the agent works, a one-line English summary is shown; tap it to open the "Summary" sheet (title + step-by-step progress, with the exact files read/edited).
- Attachments are sent together with your message: images, PDF (read natively by Claude/Gemini/OpenAI), text/code files, .docx, and project .zip (imported when you press send; the chat stays).
- Builds tab: every GitHub build result and its error log, with Copy / Share / Send to AI. When a build fails, the AI asks your permission before fixing it (Settings → GitHub).

## How it works
1. Add an API key in the **Providers** tab (Anthropic, OpenAI, Gemini, DeepSeek, Groq, OpenRouter, Mistral, Ollama, or any custom endpoint).
2. Import a ZIP in the **Files** tab.
3. Chat. The agent reads/searches files with tools, edits them with exact-match patches, and summarises what changed.
4. Review the diff, accept or reject per file, restore any checkpoint, export the fixed ZIP.

## Build (no PC needed)
Push this repo to GitHub. `.github/workflows/build.yml` builds a debug APK on every push; download it from the run's **Artifacts**.
Android Studio is not required. The app uses the default auto-generated debug keystore.

## GitHub auto-build & auto-fix
Settings → GitHub: repository (`owner/repo`), branch, and a fine-grained token (Contents: read/write, Actions: read).
- **Push project & build now**, or enable **Auto push, build & fix**.
- The app commits only changed files as ONE commit, waits for the Actions run, and on failure feeds the extracted error log to the agent, which fixes and pushes again (max attempts configurable).

## Providers, failover and thinking
- Per-provider format: OpenAI-compatible, Anthropic, Gemini. "Fetch models" loads the current model list from the provider.
- Failover chain follows the priority order (arrows in Providers). On rate limit, quota/billing, auth error, overload or network error the app switches to the next provider and continues the same chat. Bad requests (HTTP 400) are not failed over.
- Thinking level: Off / Low / Medium / High / Auto (Auto picks per request). Mapped to Anthropic extended thinking, OpenAI reasoning effort and Gemini thinking budget. Unsupported options are dropped automatically.

## Settings that are saved
Thinking level, safe mode (confirm each edit/write/delete), reply language, max agent steps, global instructions, monthly budget with hard stop, GitHub repo/branch/token (token encrypted with Android Keystore).

## Security
- API keys are encrypted with the Android Keystore; cloud backup is disabled.
- Secret-looking files (`.env`, keystores, `google-services.json`, ...) are never read by the agent, searched, or pushed to GitHub.
- ZIP import is protected against path traversal.

## Known limits
- The app cannot compile code on the phone; builds run on GitHub Actions.
- One API key per provider entry (add a second provider entry with the same URL for a second key).
- Cost figures are estimates from a built-in price table.
