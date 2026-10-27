# Hermes Mobile (Android)

**Current development build: 0.13.4-dev (Android versionCode 41).** Local setup presents one next action: once Termux is ready, one install click prepares dependencies, installs Hermes, and configures the connection. Saving local model settings starts and connects to chat. Fixes successful Termux callbacks being treated as failures, rechecks on return, and collapses diagnostics and optional services. See [deployment and model settings](docs/model-settings.md). Real Android permissions, arm64 installation, and account authorization still require device validation.

English | [中文](README.zh-CN.md)

An Android app for chatting with [Hermes Agent](https://github.com/NousResearch/hermes-agent). It shows final replies by default; settings let you enable streaming replies or expandable thinking and tool progress supplied by the server. When Hermes needs your approval or asks a follow-up question, a card appears in the chat for you to tap.

Built with Kotlin and Jetpack Compose (Material 3, liquid-glass UI). It talks to the Hermes API Server; features that the API does not cover are done over SSH using Hermes's own commands.

## Features

- **Connections**: direct (LAN / Tailscale), HTTPS, **SSH tunnel** (generate a key on the phone or import one, password login, host key pinning, automatic reconnect) and **local Termux**. Each connection has a step-by-step "Test connection".
- **Chat**: final replies by default, optional continuous streaming and collapsible server-supplied thinking/tool progress, approval and clarify cards, stop, **steer** Hermes while it is replying, resume after the network drops or the app is killed, notifications when a reply arrives in the background. Markdown rendering (tables, formulas, task lists, footnotes, and the quirks models tend to produce), in-chat search, swipe a reply to quote it, select text and ask a follow-up.
- **Sessions**: new / fork / rename / pin / **archive** / delete, automatic titles. Sessions are grouped by Hermes **projects**; create a project, move a chat into one, or start a new chat inside it.
- **Attachments**: photos, camera, files. PDFs are sent as page images, text files are inlined, other files are uploaded over SSH. Original photos are also saved on the server so the agent can work with the files.
- **Models** (lock a model per chat or set the default for new chats), **scheduled jobs** (create / edit / pause / run now), **skills and toolsets**.
- **Admin over SSH**: memory, MCP servers, `config.yaml` (validated before saving), logs. Gateway status works without SSH.
- **Bots**: every Hermes profile is a bot, as in Hermes Desktop. List them, switch the chat to any bot (its own API key, its own "Bot Chat"), and create / edit (name, avatar, description, personality, model) / delete bots. **Group chats**: put 2–6 bots in a room and let them discuss; @mention one to ask only it. Rooms run inside the Hermes gateway and are shared with Hermes Desktop. Changes to the server config are made only when you tap a button that explains them first.
- **Bot screen**: watch the bot's desktop on the server (Hermes Bot Desktop) and take over / hand back control, just like Hermes Desktop. The viewer is noVNC bundled in the app; the picture goes over your SSH connection, no extra ports.
- **Offline cache**: the session list and recent messages are kept on the phone, so they show up instantly and stay readable when Hermes is unreachable.
- **Version-tolerant**: on connect the app probes what the server supports (`/v1/capabilities`, falling back to probing endpoints on older versions) and hides what is not available, instead of comparing version numbers.

## Roadmap

Release numbering follows delivery order. Historical 0.15/0.16 dev names remain in development records; they are consolidated into 0.12. Details and validation requirements are in [docs/roadmap.md](docs/roadmap.md), in Chinese.

- **0.12** Consolidate the existing notification assistant, streaming/work progress, and draggable Dock.
- **0.13** Integrated development build: external Termux deployment, local Hermes, Python services and reverse proxies, per-connection notification profiles, and basic Chromium + external Termux:X11 setup. The replaceable runtime backend supports later integration work; real Android deployment remains unverified.
- **0.14** Optional extensions: Home Assistant and phone control over ADB wireless debugging.
- **0.15** Hermes-driven local browser automation and user take-over, building on 0.13's browser setup.
- **Later** Optional subscriptions, external-service synchronization, rewarded deployment credits, and long-term memory; providers and service interfaces remain undecided.

See [docs/setup.md](docs/setup.md) for how to connect the phone to Hermes (in Chinese) and [docs/protocol.md](docs/protocol.md) for the API details and version notes.

## Requirements

- Android 8.0 (API 26) or newer.
- Hermes Agent with the API Server enabled (`API_SERVER_ENABLED=true`, an `API_SERVER_KEY`). Some features (admin, projects, file uploads, bot screen) need an SSH login to the machine running Hermes.

## Install

Download the APK from [Releases](https://github.com/zhanghahaA/hermes/releases) and install it. The APKs are currently signed with a debug key.

## Build

Needs JDK 17+ and the Android SDK (API 37).

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (minified, debug-signed for now)
```

GitHub Actions (`.github/workflows/android.yml`) builds both APKs and renders UI screenshots on every push.

## Preview the UI without the Android SDK

`tools/ui-preview` is a Compose Desktop project that compiles the app's UI code directly and renders phone-sized (412×892 dp) screenshots of the main screens and states, light and dark.

```bash
cd tools/ui-preview
gradle run                                   # screenshots go to tools/ui-preview/screenshots/
gradle run --args="$(pwd)/screenshots chat"  # only scenes whose name contains "chat"
gradle test                                  # unit, UI-interaction and integration tests
```

On Linux, install a CJK font (for example `fonts-noto-cjk`), otherwise Chinese text renders as boxes.

## Tests

`gradle test` in `tools/ui-preview` runs:

- unit tests for the API client, SSE parsing, run tracking, the SSH tunnel (against an in-process Apache MINA SSHD), the admin commands (against a real local shell), Markdown and the bot screen bridge;
- simulated-touch UI tests (back gesture, swipe actions with inertia, quoting, selection, take-over);
- a fake Hermes server that mimics several Hermes versions and non-Hermes servers.

Integration tests against a real Hermes run when you set `HERMES_IT_HOST` / `HERMES_IT_KEY`. Chat tests use `tools/fake-llm/fake_llm.py`, a scripted model that deliberately emits reasoning and triggers approvals and clarifications, so no model API key is needed. `HERMES_IT_REAL_MODEL=1` adds a test with a real model that checks no stored reasoning ever shows up in the app. With `HERMES_IT_SSH_DIR` / `HERMES_IT_SSH_PORT` / `HERMES_IT_SSH_USER` set, the SSH features are also tested against a real OpenSSH server.

## Code layout

```
app/src/main/java/com/hermes/mobile/
├─ MainActivity.kt
├─ platform/          Android-specific pieces (notifications, file import, WebView for the bot screen…)
├─ data/
│  ├─ HermesRepository.kt   the single interface the UI depends on
│  ├─ model/                data models
│  ├─ remote/               the real implementation: API Server (REST + SSE), admin over SSH
│  ├─ ssh/                  SSH tunnel, commands, SFTP
│  ├─ screen/               bot screen stream bridge
│  ├─ store/                local cache
│  └─ fake/                 demo data for previews and screenshots
└─ ui/                chats, chat, jobs, capabilities, admin, settings, welcome, glass, navigation…
```

## Third-party components

- [noVNC](https://github.com/novnc/noVNC) 1.7.0 (MPL-2.0) including pako (MIT): the bot screen viewer. Their licenses ship with the app in `assets/screen/`. Rebuild it with `tools/novnc/build.sh`.
- [commonmark-java](https://github.com/commonmark/commonmark-java) (BSD-2-Clause): Markdown parsing.
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass): liquid-glass effects.
- [sshj](https://github.com/hierynomus/sshj), [OkHttp](https://github.com/square/okhttp), [Bouncy Castle](https://www.bouncycastle.org/), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization).
