# 綦桐AI网关 | QiTong AI Gateway

> **包名 / Package：** `com.qtwl.gateway`  
> **最新版本 / Latest：** v3.18.6 (versionCode=167)  
> **开源协议 / License：** Apache 2.0  
> **语言 / Languages：** 🌐 15 languages (CN/EN/JP/KR/FR/DE/ES/RU/PT/VN/TH/AR/HI/ID)
> **官方QQ群 / QQ Group：** [1007488535](https://qm.qq.com/q/1007488535) 💬

---

## 🇨🇳 中文

### 📖 简介
綦桐AI网关是一款运行在 **Android 设备**上的本地 AI API 网关应用。它将手机变成一个 **AI 请求转发中心**，统一管理多个 AI 服务商和模型，支持智能故障转移、代理加速、流量统计、密钥管理等功能。

### 🚀 核心功能

| 功能 | 说明 |
|:-----|:------|
| 🔑 **API 密钥管理** | 独立密钥管理页面，本地请求免密钥，每把钥匙可单独控制模型权限和 qtai-sj 访问 |
| 📡 **完整 API 接口适配** | 完整支持 OpenAI / Claude / Gemini 三大原生格式，共 **16 个接口** |
| 🚀 **綦桐AI测速 (qtai-sj)** | 虚拟模型，自动选最快模型，支持大脑+人格+记忆系统 |
| 🧠 **大脑记忆系统** | 短期/长期记忆自动保存，情感标签+重要性评分，人格+记忆注入上游请求 |
| 🧑 **人格系统** | 自定义名字/年龄/性格/语气/背景，大五人格维度滑块 |
| 🎯 **三层技能路由器** | 关键词匹配→语义匹配→LLM判断，自然语言触达全部功能 |
| 🔄 **智能故障转移** | 自动测速所有模型，失败时自动切换到最快可用模型 |
| 🎯 **服务商级模型选择** | 排行显示 `#排行ID · P服务商ID · 模型名`，同名模型按服务商独立测速、选择和路由 |
| 🔌 **多服务商管理** | 支持 OpenAI / DeepSeek / Claude / Ollama / Custom 等 |
| 🌐 **代理加速** | HTTP/HTTPS/SOCKS5，订阅导入，按模型粒度控制 |
| 💬 **内置聊天** | 完整聊天对话管理，流式 SSE 输出，Token 用量统计 |
| 💬 **群聊模式** | 多AI协作，并发讨论+总结者输出结论 |
| 📊 **数据管理** | 一键备份/恢复（含密钥），定时自动备份（WorkManager），GZIP+SHA256+AES-256 |
| 🔍 **抓包调试** | 内置网关抓包工具，实时输入/输出流量监控 |
| 🌐 **多语言** | 支持 **15 种语言**，自动跟随系统或手动切换 |
| 🛡️ **参数修正** | temperature/top_p/penalty 越界自动修正 |
| 🟢 **后台保活** | WakeLock + AlarmManager 双保活，关屏不掉线 |

### 📡 完整 API 接口列表（16 个）

| 接口 | 方法 | 路径 | 格式 |
|------|------|------|------|
| 模型列表 | GET | `/v1/models` | OpenAI / Claude / Gemini |
| 对话补全 | POST | `/v1/chat/completions` | OpenAI |
| 文本补全 | POST | `/v1/completions` | OpenAI |
| Claude消息 | POST | `/v1/messages` | Claude |
| 嵌入向量 | POST | `/v1/embeddings` | OpenAI |
| 引擎嵌入 | POST | `/v1/engines/{model}/embeddings` | Gemini |
| 重排序 | POST | `/v1/rerank` | OpenAI |
| 内容审核 | POST | `/v1/moderations` | OpenAI |
| 文本转语音 | POST | `/v1/audio/speech` | OpenAI |
| 图像生成 | POST | `/v1/images/generations` | OpenAI |
| 视频生成（同步） | POST | `/v1/videos` | OpenAI (form-data) |
| 视频任务（异步） | POST | `/v1/video/generations` | OpenAI |
| 视频状态查询 | GET | `/v1/video/generations/{task_id}` | OpenAI |
| Gemini生成 | POST | `/v1beta/models/{model}:generateContent` | Gemini |
| 实时语音 | WS | `/v1/realtime` | OpenAI |
| 文件列表 | GET | `/v1/files` | ⏳ 未实现 |

> 所有接口均支持 `model: "qtai-sj"` 自动解析为当前活跃模型，支持密钥验证+流量统计+访问日志。

### 🛠️ 技术栈
- **语言：** Kotlin 100%
- **UI：** Jetpack Compose + Material Design 3
- **服务器：** Ktor Server (CIO，支持 WebSocket)
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
5. API Key 随意填写即可转发（或可在管理页配置密钥验证）

---

## 🇬🇧 English

### 📖 Introduction
**QiTong AI Gateway** is a local AI API gateway running on **Android devices**. It turns your phone into an **AI request hub**, managing multiple AI providers and models with intelligent failover, proxy acceleration, traffic statistics, and API key management.

### 🚀 Core Features

| Feature | Description |
|:--------|:------------|
| 🔑 **API Key Management** | Dedicated management page, local requests exempt, per-key model access control |
| 📡 **Full API Support** | Complete OpenAI / Claude / Gemini format support, **16 endpoints** |
| 🚀 **qtai-sj Speed Mode** | Virtual model, auto-picks fastest model, with brain + persona + memory |
| 🧠 **Brain Memory System** | Short/long-term memory, emotion tags, importance scoring |
| 🔄 **Smart Failover** | Auto speed-test all models, switch to fastest on failure |
| 🔌 **Multi-Provider** | OpenAI / DeepSeek / Claude / Ollama / Custom support |
| 🌐 **Proxy Acceleration** | HTTP/HTTPS/SOCKS5, subscription import, per-model proxy control |
| 💬 **Built-in Chat** | Full chat management, SSE streaming, token usage tracking |
| 📊 **Data Management** | One-click backup/restore (incl. API keys), scheduled backups |
| 🔍 **Packet Capture** | Built-in debug tool, real-time traffic monitoring |
| 🌐 **Multi-language UI** | **15 languages** supported, auto-follow system or manual switch |
| 🛡️ **Parameter Fix** | Auto-fix temperature/top_p/penalty out-of-range values |
| 🟢 **Keep-Alive** | WakeLock + AlarmManager, stays online when screen off |

### 📡 API Endpoints (16 total)

| Endpoint | Method | Path | Format |
|:---------|:-------|:-----|:-------|
| List Models | GET | `/v1/models` | OpenAI / Claude / Gemini |
| Chat Completions | POST | `/v1/chat/completions` | OpenAI |
| Text Completions | POST | `/v1/completions` | OpenAI |
| Claude Messages | POST | `/v1/messages` | Claude |
| Embeddings | POST | `/v1/embeddings` | OpenAI |
| Engine Embeddings | POST | `/v1/engines/{model}/embeddings` | Gemini |
| Rerank | POST | `/v1/rerank` | OpenAI |
| Moderations | POST | `/v1/moderations` | OpenAI |
| Text-to-Speech | POST | `/v1/audio/speech` | OpenAI |
| Image Generation | POST | `/v1/images/generations` | OpenAI |
| Video (sync) | POST | `/v1/videos` | OpenAI (form-data) |
| Video Task (async) | POST | `/v1/video/generations` | OpenAI |
| Video Task Status | GET | `/v1/video/generations/{task_id}` | OpenAI |
| Gemini Generate | POST | `/v1beta/models/{model}:generateContent` | Gemini |
| Realtime (WebSocket) | WS | `/v1/realtime` | OpenAI |
| File List | GET | `/v1/files` | ⏳ Not implemented |

> All endpoints support `model: "qtai-sj"` as auto-resolve to the current active model, with API key validation, traffic stats, and access logging.

### 🛠️ Tech Stack
- **Language:** Kotlin 100%
- **UI:** Jetpack Compose + Material Design 3
- **Server:** Ktor Server (CIO, WebSocket support)
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
5. Any API Key works for forwarding (or configure key auth in Settings)

---

## 📋 Version History

See [CHANGELOG.md](CHANGELOG.md) for full changelog.

| Version | Key Features |
|:--------|:-------------|
| **v3.18.6** | ⏱ **定时测速倒计时+排行榜三行布局** — 测速跑完显示倒计时，排行每项独立三行展示指标 |
| **v3.18.5** | 🔧 **服务商数据隔离修复** — 感谢 `adybag14-cyber` 修复模型排行榜按服务商作用域隔离，避免跨服务商数据混淆 |
| **v3.18.4** | ✨ **通知栏正文精简 + 自动测速间隔可调 + 稳定优化** — 去掉正文模型名行、可配置5分钟~4小时测速间隔、跑完一圈停等设定时间再跑 |
| **v3.18.3** | 🐛 **通知栏闪烁彻底修复** — startForeground改notify + 备份目录迁移到qtkfbf + 备份保留5天 + DEV_GUIDE.md v16升级 |
| **v3.18.2** | 🐛 **修复双隐藏多任务+同名模型显示服务商+Git冲突清理+ViewModel编译错误** — 通知栏防闪烁+聊天选模型显示服务商名 |
| **v3.18.1** | 🐛 **首页测速遮挡修复 + 定时备份不生效修复 + qtai-sj 测速异常修复 + qtai-sj 状态卡片** — 双行布局/非SSE兼容/30s超时/约束放宽/KEEP策略/测试按钮 |
| **v3.17.2** | ✨ **统计页清空流量按钮** + 统计页模型修复 + 版本号统一 |
| **v3.17.1** | 🎯 **三层技能路由器** + 搜索技能 + 人格系统大五维度滑块 + 备份完善 + 重置确认 |
| **v3.16.0** | ⚡ **三指标测速** + 9维能力标签 + 同名模型分组 + SearXNG搜索 + 备份GZIP+SHA256+AES |
| **v3.14.0** | 🐛 **图像生成修复** — model字段未替换导致404 + 新增replaceModelInBody + 兜底机制 |
| **v3.13.0** | 🎯 **綦小桐技能管理系统 + 连续对话模式** — 自定义技能/手动添加/Git导入/内置技能只读展示 |
| **v3.12.0** | 🛡️ **崩溃捕获+自动更新+网关智能检测+搜索技能** — 闪退自动保存到GitHub Issues |
| **v3.11.1** | 🐛 **请求体校验全面增强** — 缺失messages/model + 空body + messages非数组 全部返回400 |
| **v3.11.0** | 🎯 **6个兼容性修复** — CORS跨域 + GET 400 + 空messages修复 + 500错误体统一 + 非标准stream兼容 + 不带v1路径 |
| **v3.10.0** | 🚀 **完整API接口适配(16个)** + 🔑 **密钥管理系统** + 备份恢复密钥 + WebSocket实时语音 + 群聊不喊前缀 |
| v3.9.4 | 🐛 群聊开关修复 + 后台保活(WakeLock+Alarm) + 启动权限检查 + 自启状态恢复 |
| v3.9.3 | 🐛 群聊开关修复 + 后台保活 + 启动权限检查 |
| v3.9.2 | 🧠 大脑流式修复 + qtai-sj透传优化 |
| v3.9.1 | 🌐 多语言修复 (Adybag14-赛博 PR #2) |
| v3.9.0 | 🐛 通知栏流量修复 + 模型统计 + 大脑直连 |
| v3.8.9 | 🧠 技能编码参数传递 + 前缀无空格匹配 + 兜底fallback |
| v3.8.4 | 📊 通知栏流量统计策略 + 群聊模式排行榜勾选 |
| v3.7.6 | 🚀 qtai-sj无前缀转发修复 + 超时优化 |
| v3.7.4 | 🚀 修复总输入为0 + 全路径统计覆盖 |
| v3.7.3 | 🔥 qtai-sj统计修复 + 通知栏实时更新 |
| v3.7.2 | 🛡️ API密钥验证+访问日志+健康检查完善 |
| v3.7.1 | 🐛 SSE格式/500错误体/多模态 6项修复 |
| v3.7.0 | 🧑 人格名称全程脑子处理，自由对话智能体 |
| v3.6.9 | 🏗️ 拆分GatewayScheduler调度层 |
| v3.6.8 | ⚡ qtai-sj透传转发修复 |
| v3.6.7 | ⚡ SSE流式卡顿修复+指定模型切换 |
| v3.6.6 | 🧠 模型能力标记+脑子智能推荐 |
| v3.6.5 | 🧠 全智能思考系统：排行榜分析+自动推荐模型 |
| v3.6.4 | 🧠 大脑记忆注入网关 |
| v3.6.3 | 🔢 排行编号+当前模型显示+编号切换 |
| v3.6.2 | ✅ 指令系统+脑子模型全部调试通过 |
| v3.6.1 | 🧑 人格名称动态绑定 |
| v3.6.0 | 🔥 前缀指令修复 |
| v3.5.9 | 🧠 绑定脑子UI修复+前缀指令 |
| v3.5.8 | 🧠 qtai-sj绑定脑子+自然语言理解 |
| v3.5.7 | 🧮 智能排序a→d→b→c |
| v3.5.6 | 🟢 红绿灯测速状态指示灯 |
| v3.5.5 | ⏱ 测速UI重构：双框展示 |
| v3.5.4 | 📊 进度条+状态提示+自动测速 |
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

### 👨‍💻 开发与致谢

**开发：** [綦桐](https://github.com/qtgf520) · [adybag14-cyber](https://github.com/adybag14-cyber) 开发工程师

> ⭐ 推荐大家使用 [adybag14-cyber](https://github.com/adybag14-cyber) 开发的 [Hermes Agent](https://github.com/adybag14-cyber/hermes-agent) — 还在完善中，敬请期待！

---

### 👨‍💻 Developers & Credits

**Developed by:** [QiTong](https://github.com/qtgf520) · [adybag14-cyber](https://github.com/adybag14-cyber) Engineer

> ⭐ Check out [Hermes Agent](https://github.com/adybag14-cyber/hermes-agent) by [adybag14-cyber](https://github.com/adybag14-cyber) — still in development, stay tuned!
