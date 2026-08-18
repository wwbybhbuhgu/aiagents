<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>AI Agents</h1>

A native Android LLM chat client that supports switching between different providers for
conversations.

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

> [!NOTE]
> This is a fork of [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) (formerly
> re-ovo/aiagents), the original project by [rikkahub](https://github.com/rikkahub). This fork
> adds new features and customizations on top of it; all credit for the base project goes to the
> original authors.

## Download

[Download from GitHub Releases](https://github.com/wwbybhbuhgu/aiagents/releases/latest)

## Features

- Material You Design and Dark mode
- Workspace: a proot-based Linux agent environment
- Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- Multimodal input support (Image, Text Documentation, PDF, Docx)
- Web access for multi-platform use
- MCP support
- Markdown Rendering (with code highlighting, Latex formulas, tables, Mermaid)
- Message Branching
- Search capabilities (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, etc.)
- Prompt variables (model name, time, etc.)
- QR code export and import for providers
- Agent customization
- ChatGPT-like memory feature
- AI Translation
- Custom HTTP request headers and request bodies
- Silly Tavern character card import

## Contributing

This project is developed using [Android Studio](https://developer.android.com/studio). PRs are
welcome!

Technology stack documentation:

- [Kotlin](https://kotlinlang.org/) (Development language)
- [Koin](https://insert-koin.io/) (Dependency Injection)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI framework)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preference data
  storage)
- [Room](https://developer.android.com/training/data-storage/room) (Database)
- [Coil](https://coil-kt.github.io/coil/) (Image loading)
- [Material You](https://m3.material.io/) (UI design)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (Navigation)
- [Okhttp](https://square.github.io/okhttp/) (HTTP client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON serialization)

> [!TIP]
> You need a `google-services.json` file at `app` folder to build the app.

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).