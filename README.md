# 綦桐AI网关

> **包名：** com.qtwl.gateway  
> **签名：** qitong.jks  
> **最新版本：** v2.6.2

---

## 📖 简介

綦桐AI网关是一款运行在 Android 设备上的本地 AI API 网关应用。它可以将手机变成一个 AI 请求转发中心，统一管理多个 AI 服务商（OpenAI、Anthropic、Ollama 等），支持代理加速、模型路由、流量统计等功能。

---

## ✨ 核心功能

### 🚀 网关代理
- 本地 Ktor Server 监听端口（默认 8889）
- 通用代理模式：支持 `/v1/*` 所有路径（chat、image、audio 等）
- 智能模型路由：自动根据 `model` 字段匹配对应服务商
- 流式管道直通：32KB 大缓冲区，低延迟转发

### 🔌 多服务商管理
- 支持 OpenAI Compatible、Anthropic、Ollama、Custom 等类型
- 一键同步模型列表
- 别名自定义

### 🌐 代理加速
- 支持 HTTP / HTTPS / SOCKS5 协议
- 多代理列表管理（订阅导入、剪贴板导入）
- 代理互斥（单选生效）
- 按模型粒度控制代理
- 智能测速（同时测试百度+谷歌）

### 💬 内置聊天
- 完整的聊天对话管理
- 流式 SSE 输出
- Token 用量统计

### 📊 流量统计
- 实时上传/下载流量监控
- Token 用量记录
- 通知栏动态显示

### 💾 数据管理
- 一键备份/恢复
- 定时自动备份
- JSON 导出/导入

### 🔍 调试工具
- 内置网关抓包工具
- 通知栏唤醒保活

---

## 🛠️ 技术栈

- **语言：** Kotlin 100%
- **UI：** Jetpack Compose + Material Design 3
- **服务器：** Ktor Server (CIO)
- **HTTP 客户端：** OkHttp
- **数据库：** Room
- **序列化：** Kotlinx Serialization
- **构建：** Gradle + AGP 9.x

---

## 📦 构建

```bash
./gradlew assembleDebug
```

---

## 📄 版本历史

详见 [CHANGELOG.md](CHANGELOG.md)

---

## 📜 开源协议

本项目采用 **Apache License 2.0** 开源协议，欢迎学习、使用和贡献。

> ⚠️ 签名证书 `qitong.jks` 及 API Key 配置不在仓库中，请自行配置。

© 2026 [綦桐](https://github.com/qtgf520)