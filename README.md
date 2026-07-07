# 綦桐AI网关 | QiTong AI Gateway

> **包名 / Package：** `com.qtwl.gateway`  
> **最新版本 / Latest：** v3.7.2 (versionCode=93)  
> **开源协议 / License：** Apache 2.0  
> **语言 / Languages：** 🌐 15 languages (CN/EN/JP/KR/FR/DE/ES/RU/PT/VN/TH/AR/HI/ID)
> **官方QQ群 / QQ Group：** [1007488535](https://qm.qq.com/q/1007488535) 💬

---

## 🇨🇳 中文

### 📖 简介
綦桐AI网关是一款运行在 **Android 设备**上的本地 AI API 网关应用。它将手机变成一个 **AI 请求转发中心**，统一管理多个 AI 服务商和模型，支持智能故障转移、代理加速、流量统计等功能。

### ✨ 核心功能

| 功能 | 说明 |
|:-----|:------|
| 🚀 **綦桐AI测速 (qtai-sj)** | 虚拟模型，前缀指令（綦小桐/qtai-sj/XiaoTong+自定义人格名）控制系统 |
| 🧠 **绑定脑子模型** | qtai-sj可绑定专属模型理解自然语言，未命中硬指令时自动调脑子分析意图 |
| 🧠 **大脑记忆系统** | 短期/长期记忆自动保存，情感标签+重要性评分，人格+记忆注入上游请求 |
| 🧑 **人格系统** | 自定义名字/年龄/性格/语气/背景，动态绑定前缀，人格同步通知栏 |
| 🔄 **智能排序 a→d→b→c** | 当前可用→历史成功→从未测→失败，故障转移自动切换 |
| 🔢 **排行编号切换** | 指令查看排行带编号，回复数字直接切换模型 |
| 🚀 **网关代理** | 本地 Ktor Server（默认 8889 端口），转发 `/v1/*` 所有请求 |
| 🔄 **智能故障转移** | 自动测速所有模型，失败时自动切换到最快可用模型 |
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
| 🚀 **qtai-sj Speed Mode** | Virtual model, auto-picks fastest model from pipeline ranking |
| 🎯 **qtai-sj independent** | Works regardless of auto-failover switch, always uses ranking |
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
| v3.7.2 | 🛡️ 代码审查修复：API密钥验证+访问日志+健康检查完善 |
| v3.7.1 | 🐛 豆包调试报告6项修复：SSE格式/500错误体/多模态 |
| v3.7.0 | 🧑 人格名称全程脑子处理，自由对话智能体 |
| v3.6.9 | 🏗️ 进程树重构：拆分GatewayScheduler调度层 |
| v3.6.8 | ⚡ qtai-sj透传转发修复 |
| v3.6.7 | ⚡ SSE流式卡顿修复+指定模型切换 |
| v3.6.6 | 🧠 模型能力标记+脑子智能推荐+非chat路径修复 |
| v3.6.5 | 🧠 全智能思考系统：脑子带排行榜分析，自动推荐模型并切换 |
| v3.6.4 | 🧠 大脑记忆注入网关，记忆内容完整+短期补充 |
| v3.6.3 | 🔢 排行编号+当前模型显示+编号切换+通知联动 |
| v3.6.2 | ✅ 指令系统+脑子模型全部调试通过 |
| v3.6.1 | 🧑 人格名称动态绑定+Toast提示+切换模型ID修复 |
| v3.6.0 | 🔥 前缀指令修复：必须綦小桐/qtai-sj/XiaoTong开头 |
| v3.5.9 | 🧠 绑定脑子UI修复+前缀指令+故障转移保留 |
| v3.5.8 | 🧠 qtai-sj绑定脑子+自然语言理解 |
| v3.5.7 | 🧮 智能排序a→d→b→c+乱码修复+绿灯修复 |
| v3.5.6 | 🟢 红绿灯测速状态指示灯 |
| v3.5.5 | ⏱ 测速UI重构：双框展示+测完一个即可用 |
| v3.5.4 | 📊 进度条+状态提示+自动测速+会话记忆 |
| v3.4.1 | 🎯 手动强制切换模型, 📡实时会话跑马灯 |
| v3.4.0 | 🐛 qtai-sj聊天室发消息请求修复 |
| v3.3.6 | 🐛 markModelSuccess传真实延迟 |
| v3.3.0 | 🌐 Multi-language system (15 languages) |
| v3.2.3 | 🧠 Best model memory, 4xx/5xx triggers failover |
| v3.2.2 | 🛡️ Auto-fix temperature/top_p/penalty |
| v3.2.0 | 🔄 Auto failover, model health cache |
| v3.0.0 | OpenAI full compatibility |
| v2.6.0 | Proxy per-model, provider mutual exclusion |
| v2.5.0 | Smart speed test, auto backup |
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
  📬 官方QQ群：<a href="https://qm.qq.com/q/1007488535">1007488535</a> 💬<br>
  © 2026 <a href="https://github.com/qtgf520">綦桐</a> · Apache 2.0
</p>
