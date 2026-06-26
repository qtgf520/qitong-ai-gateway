# 綦桐AI网关 完整更新日志

> 包名：com.qtwl.gateway
> 签名证书：qitong.jks (别名: qitong)
> 最后更新：2026-06-25

---

## 🔄 v3.0.1（当前最新）
### 🔧 修复
- ❌ 通知栏清零导致网关逻辑中断 — 流量满9秒无变化就归零，大模型思考中被打断
- ✅ **动态累计模式** — 像手机流量一样只增不减，永不归零！加 🟢传输中/⚪空闲 指示灯

---

## 🔄 v3.0.0
### 🆕 新增
- **全面 OpenAI 兼容** — 响应格式标准化（`id`/`object`/`created`/`model`）
- **标准错误格式** — 全部改为 `{"error":{"message":"...","type":"...","code":null}}` 格式
- **新增 API 端点** — 显式支持 `/v1/embeddings`、`/v1/completions`、`/v1/moderations`、`/v1/images/generations`、`/v1/images/edits`、`/v1/audio/transcriptions`、`/v1/audio/translations`
- **流式标准格式** — SSE chunk 使用 `chat.completion.chunk` 标准

### 🔧 修复
- ❌ 禁用服务商后模型页仍显示该服务商的模型
- ✅ 模型页使用 `INNER JOIN providers` 过滤已禁用服务商的模型
- ❌ APP不适配网关（格式不标准）
- ✅ 完全遵循 OpenAI API 格式规范

---

## 🔄 v2.6.2
### 🆕 新增
- **内置网关抓包工具** — 管理页「网关抓包」卡片，实时记录请求/响应日志（含输入输出流量）
- **备份全面覆盖** — 新增 `代理列表` + `网关端口` 的备份与恢复

### 🔧 修复
- ❌ 网关长流式断开（`callTimeout(600s)` 限制）→ ✅ `callTimeout(0)` + `readTimeout(0)` 无上限
- ❌ OkHttp 无连接池 → ✅ `ConnectionPool(5, 30s)` 连接复用加速
- ❌ 抓包清空按钮 → ✅ 真清空（`clearDebugLogs()` 调用 clear）
- ❌ 抓包按钮颜色不变 → ✅ `debugMode` 改为 StateFlow，Compose 响应式变化

---

## 🔄 v2.6.1
### ⚡ 性能优化
- **网关流式转发提速** — 取消每块 `decodeToString`，改用 `ByteArrayOutputStream` 累积原始字节，流结束后只解码一次
- **去冗余 `runBlocking`** — 流式 usage 解析直接调用 suspend DAO，不再额外包一层

### 🗂️ 备份页面重构
- **合并为一个卡片** — 删除重复的「文件备份」「导入备份」独立卡片，整合到「备份 & 恢复」中
- **定时自动备份** — 新增 ⏰ 开关 + 时间设置（小时/分钟），可设置每天指定时间自动备份到 Downloads
- **布局精简** — 一键恢复、立即备份、定时开关都在一个卡片内完成

---

## 🔄 v2.6.0
### 🆕 新增
- **代理服务商单选互斥** — 开启一个代理时，其他代理自动禁用
- **按模型粒度控制代理** — 模型卡片新增 🔄/🔗 按钮，可单独设置每个模型是否走代理
- **UpstreamClient 直连客户端** — 新增 `getDirectClient()` / `getClientForModel(useProxy)`，不走代理的模型请求直连

### 🔧 修复
- ❌ 代理开关未互斥（开启一个后另一个还是开启状态）
- ✅ 开启一个代理时，自动关闭其他所有代理并持久化

### 🗄️ 数据库
- AiModel 新增 `use_proxy` 字段（默认 true）
- 数据库迁移 v4→v5：添加 `use_proxy` 列

---

## 🔄 v2.5.1
### 🆕 新增
- **模型别名输出修复** — 网关`/v1/models`保持`id`为原始`modelId`不变（**防止第三方客户端调API失败**），`display_name`展示别名，`custom_alias`字段单独返回

### 🔧 修复
- ❌ v2.5.0 误将`id`字段改为别名 → 第三方客户端用`id`调接口找不到模型
- ✅ 恢复`id`保留原始modelId，别名仅影响`display_name`和`custom_alias`展示

---

## 🔄 v2.5.0
### 🆕 新增
- **智能测速** — 同时并发测试百度和谷歌：谷歌通=🌍海外，仅百度通=🇨🇳国内，都不通=❌
- **HTTP/HTTPS代理修复** — 改用`java.net.Proxy`完整限定名解决与OkHttp冲突；HTTPS代理添加自签名SSL信任
- **自动备份 & 一键恢复** — 管理页新增卡片：点击「立即备份」导出到Downloads + 点击「一键恢复」扫描备份文件列表点选恢复
- **服务商禁用联动模型** — `getEnabledModels`改为`INNER JOIN providers p`，禁用服务商后模型自动隐藏

### 🔧 修复
- 测速只猜内外不准确（改为国内外同时测）
- 代理列表不能正确应用HTTP/HTTPS代理
- 服务商禁用但模型仍在列表显示

---

## 🔄 v2.4.1
### 🔧 修复
- 内容中疯狂出现 "null" 文字 — 流式响应`jsonPrimitive?.content`对JSON `null`值已过滤
- 聊天UI适配 — MessageBubble双层防御
- 更新日志保存到工作区 CHANGELOG.md

---

## 🔄 v2.4.0
### 🆕 新增
- 网关Token用量记录（非流式+流式自动解析 usage）
- API连接动态提示（输入地址实时显示最终URL）
- 模型别名输出（网关 `/v1/models` 返回 display_name + custom_alias）
### 🔧 修复
- 统计页面没有数据（网关代理未记录TokenUsage）
- 编辑服务商重复supportingText

---

## 🔄 v2.3.0
### 🆕 新增
- 代理订阅导入（一键订阅、Base64解码、批量去重）
- 剪贴板检测导入
- 手动添加VMESS/SS/VLESS/Trojan/Hysteria2
- 统计页网关流量
### 🔧 修复
- 订阅弹窗点击无响应
- 剪贴板导入闪退（String.startsWith冲突）
- 代理列表不可滑动
- 通知栏闲置不归零

---

## 🔄 v2.2.0
### 🆕 新增
- 代理列表可滑动
- 通知栏自动归零
- 代理卡片格式优化

---

## 🔄 v2.1.0
### 🆕 新增
- 手动添加代理支持扩展协议
### 🔧 修复
- 版本号真实更新
- 订阅/剪贴板崩溃

---

## 🔄 v2.0.0
### 🆕 新增
- 通用代理模式（支持/v1/*所有路径）
- 流式输出开关
- 一键订阅导入
- 剪贴板检测导入
- ProxyLinkParser解析器（vmess/ss/vless/trojan/hysteria2/socks5）
- 局域网IP自动获取+复制
### 🔧 修复
- 网关数据憋死（32KB缓冲区+管道直通）
- 所有Media Type支持
- 超时+body限制解除

---

## 🔄 v1.9.0（初始版本）
- 底部导航栏（首页/服务商/模型/聊天/统计/管理/关于）
- 服务商CRUD
- 模型同步+别名编辑
- 内置聊天
- 网关代理（Ktor Server）
- 代理管理（HTTP/HTTPS/SOCKS5）
- Token用量统计（仅内置聊天）
- 数据备份/恢复/重置
