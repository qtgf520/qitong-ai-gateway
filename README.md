# 綦桐AI网关 | QiTong AI Gateway

> **包名 / Package：** `com.qtwl.gateway`  
> **最新版本 / Latest：** v3.3.0 (versionCode=39)  
> **开源协议 / License：** Apache 2.0  
> **语言 / Languages：** 🌐 15 languages (CN/EN/JP/KR/FR/DE/ES/RU/PT/VN/TH/AR/HI/ID)

---

## 🇨🇳 中文

### 📖 简介
綦桐AI网关是一款运行在 **Android 设备**上的本地 AI API 网关应用。它将手机变成一个 **AI 请求转发中心**，统一管理多个 AI 服务商和模型，支持智能故障转移、代理加速、流量统计等功能。

### ✨ 核心功能

| 功能 | 说明 |
|:-----|:------|
| 🚀 **网关代理** | 本地 Ktor Server（默认 8889 端口），转发 `/v1/*` 所有请求 |
| 🔄 **智能故障转移** | 自动测速所有模型，失败时自动切换到最快可用模型 |
| 🧠 **最优模型记忆** | 响应最快的模型自动记住（5分钟缓存），下次直接走最优 |
| 🔌 **多服务商管理** | 支持 OpenAI / DeepSeek / Claude / Ollama / Custom 等 |
| 🌐 **代理加速** | HTTP/HTTPS/SOCKS5，订阅导入，按模型粒度控制 |
| 💬 **内置聊天** | 完整聊天对话管理，流式 SSE 输出，Token 用量统计 |
| 📊 **数据管理** | 一键备份/恢复，定时自动备份，JSON 导出/导入 |
| 🔍 **抓包调试** | 内置网关抓包工具，实时输入/输出流量监控 |
| 🌐 **多语言** | 支持 **15 种语言**，自动跟随系统或手动切换 |
| 🛡️ **参数修正** | temperature/top_p/penalty 越界自动修正 |

### 🛠️ 技术栈
- **语言：** Kotlin 100%
- **UI：** Jetpack Compose + Material Design 3
- **服务器：** Ktor Server (CIO)
- **HTTP 客户端：** OkHttp
- **数据库：** Room
- **序列化：** Kotlinx Serialization
- **构建：** Gradle + AGP 9.x

### 📦 快速开始
```bash
# 1. 克隆仓库
git clone https://github.com/qtgf520/qitong-ai-gateway.git

# 2. 配置签名 (自行准备 qitong.jks)
#    放入 app/ 目录

# 3. 编译安装
./gradlew assembleDebug
# 或从 Releases 下载 APK 直接安装
```

### 📱 使用流程
1. 打开 APP → **服务商** → 添加 AI 服务商
2. **同步模型列表**
3. 返回 **首页** → 启动网关
4. 第三方 APP 设置 Base URL: `http://手机IP:8889/v1`
5. API Key 随意填写即可转发

---

## 🇬🇧 English

### 📖 Introduction
**QiTong AI Gateway** is a local AI API gateway running on **Android devices**. It turns your phone into an **AI request hub**, managing multiple AI providers and models with intelligent failover, proxy acceleration, and traffic statistics.

### ✨ Core Features

| Feature | Description |
|:--------|:------------|
| 🚀 **Gateway Proxy** | Local Ktor Server (default port 8889), proxies all `/v1/*` requests |
| 🔄 **Smart Failover** | Auto speed-test all models, switch to fastest on failure |
| 🧠 **Best Model Memory** | Remembers fastest model (5min cache), auto-prioritize next time |
| 🔌 **Multi-Provider** | OpenAI / DeepSeek / Claude / Ollama / Custom support |
| 🌐 **Proxy Acceleration** | HTTP/HTTPS/SOCKS5, subscription import, per-model proxy control |
| 💬 **Built-in Chat** | Full chat management, SSE streaming, token usage tracking |
| 📊 **Data Management** | One-click backup/restore, scheduled backups, JSON export/import |
| 🔍 **Packet Capture** | Built-in debug tool, real-time traffic monitoring |
| 🌐 **Multi-language UI** | **15 languages** supported, auto-follow system or manual switch |
| 🛡️ **Parameter Fix** | Auto-fix temperature/top_p/penalty out-of-range values |

### 🛠️ Tech Stack
- **Language:** Kotlin 100%
- **UI:** Jetpack Compose + Material Design 3
- **Server:** Ktor Server (CIO)
- **HTTP Client:** OkHttp
- **Database:** Room
- **Serialization:** Kotlinx Serialization
- **Build:** Gradle + AGP 9.x

### 📦 Quick Start
```bash
# 1. Clone
git clone https://github.com/qtgf520/qitong-ai-gateway.git

# 2. Add your keystore (qitong.jks) to app/

# 3. Build & Install
./gradlew assembleDebug
# Or download APK from Releases
```

### 📱 Usage Guide
1. Open APP → **Providers** tab → Add AI service provider
2. **Sync model list**
3. Go to **Home** → Start Gateway
4. Set Base URL in 3rd-party app: `http://phone-ip:8889/v1`
5. Any API Key works for forwarding

---

## 📋 Version History

See [CHANGELOG.md](CHANGELOG.md) for full changelog.

| Version | Key Features |
|:--------|:-------------|
| v3.3.0 | 🌐 Multi-language system (15 languages), language settings UI |
| v3.2.3 | 🧠 Best model memory, 4xx/5xx triggers failover |
| v3.2.2 | 🛡️ Auto-fix temperature/top_p/penalty params |
| v3.2.1 | 🧵 Fix blocking IO with Dispatchers.IO |
| v3.2.0 | 🔄 Auto failover, model health cache |
| v3.0.0 | OpenAI full compatibility, standard error format |
| v2.6.0 | Proxy per-model, provider mutual exclusion |
| v2.5.0 | Smart speed test, auto backup, provider-model sync |
| v1.9.0 | Initial release |

---

## 📜 License

```
Apache License 2.0

Copyright 2026 綦桐 (qtgf520)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

> ⚠️ **Note:** The signing certificate `qitong.jks` and API keys are **not** included in this repository. You need to provide your own keystore to build Release APKs.

---

<p align="center">
  <b>綦桐AI网关</b> · <a href="https://github.com/qtgf520/qitong-ai-gateway/releases">Releases</a> · <a href="CHANGELOG.md">Changelog</a><br>
  © 2026 <a href="https://github.com/qtgf520">綦桐</a> · Apache 2.0
</p>
