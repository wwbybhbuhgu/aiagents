<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>AI Agents</h1>

一个原生 Android LLM 聊天客户端，支持切换不同的供应商进行聊天

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

> [!NOTE]
> 本项目是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)（原 re-ovo/aiagents）的 fork。
> 在此基础之上添加了新功能与定制，原项目的全部功劳归于原作者
> [rikkahub](https://github.com/rikkahub)。

## 下载

[前往 GitHub Releases 下载](https://github.com/wwbybhbuhgu/aiagents/releases/latest)

## 功能特色

- 现代化安卓 APP 设计（Material You / 预测性返回）和暗色模式
- 工作区：基于 proot 的 Linux 智能体环境
- Web 多端访问支持
- MCP 支持
- 多种类型的供应商支持，自定义 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 多模态输入支持
- Markdown 渲染（支持代码高亮、数学公式、表格、Mermaid）
- 搜索功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- Prompt 变量（模型名称、时间等）
- 二维码导出和导入提供商
- 智能体自定义
- 类 ChatGPT 记忆功能
- AI 翻译
- 自定义 HTTP 请求头和请求体

## 贡献

本项目使用 [Android Studio](https://developer.android.com/studio) 开发，欢迎提交 PR

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material You](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json 序列化)

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

## 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源。