# 🚀 綦桐AI网关 — 超级开发指南（自教文件）

> ⚡ **最高优先级：** 本文件是 AI 代理开发綦桐AI网关的**自教指南**，每次迭代必须优先阅读和执行  
> **核心规则：优先启动 Debug 编译**（`./gradlew assembleDebug`），快速验证，Release 版仅在正式发布时编译

## 📌 开发铁律（每次必读）

1. **🔥 优先 Debug 编译** — 所有修改先 `assembleDebug` 验证通过，最后才 `assembleRelease`
2. **💾 先备份再改** — 整个工作区备份 + 单文件备份，改错了能恢复
3. **🔍 先理解再动手** — 读代码 → 理解上下文 → 用户确认 → 改代码
4. **✅ 编译必须通过** — `BUILD SUCCESSFUL` 才能交付，警告允许但报错必须修
5. **🚿 Git 提交前必须清理** — 删 `.bak`、`backup_*`、临时文件
6. **📖 每次改代码前先看本文件** — 严格按流程走，不跳步

---

# 綦桐AI网关 — 完整开发/编译/发布流程 v12

> 本文档记录从代码修改到最终 GitHub Release 的完整开发流程，适用于綦桐AI网关（包名 `com.qtwl.gateway`）的日常迭代。

---

## 1. 前置准备

### 工作区路径
```
/data/user/0/com.ai.assistance.operit/files/workspace/app621
```

### 签名证书
app/qitong.jks (别名: qitong, 密码: 配置有 )

### 版本号规则
| 版本类型 | 示例 | versionCode |
|---------|------|-------------|
| 测试版 | 3.8.3-1 | 106 |
| 测试版 | 3.8.3-2 | 107 |
| 正式版 | 3.8.4 | 108 |
| 正式版 | 3.8.5 | 109 |

---

## 2. 开发规范

### 2.1 备份优先
每次改代码前先备份整个工作区，每次改文件前先备份单个文件。

### 2.2 先理解再修改
- 先读代码理解上下文，确认无歧义再动手
- 不改动原有功能

### 2.3 编译验证
- BUILD SUCCESSFUL 才能交付
- 编译警告可允许，报错必须阻塞并修复

### 2.4 Git 提交前清理
删除备份文件（.bak、backup_* 目录）再 commit

---

## 3. 修改代码

### 3.1 关键源文件
- 版本号: app/build.gradle.kts
- 网关服务: GatewayService.kt
- 通知栏: GatewayForegroundService.kt
- 管理页UI: DataManagementScreen.kt
- 群聊管理器: GroupChatManager.kt
- 调度器: GatewayScheduler.kt
- ViewModel: GatewayViewModel.kt

### 3.2 常用修改

#### 版本号升级
versionCode += 1, versionName = 新版本号

#### 通知栏流量
通知栏用 trafficUploadBytes/trafficDownloadBytes（可重置）
APP内总统计用 totalUploadBytes/totalDownloadBytes（持久化）
切模型时调用: resetNotificationTraffic()

#### 群聊弹窗（排行榜勾选）
弹窗打开时用 remember 快照数据，避免列表跳动
- viewModel.enabledModels.value（只取一次）
- GatewayScheduler.pipelineSortedModelIds.toList()（只取一次）

#### 流量统计双轨制
每处都同时加两行: traffic + total

---

## 4. 编译

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# Debug 版 优先 
./gradlew assembleDebug

# Release 版
./gradlew clean assembleRelease
```

常见问题: LazyColumn 中 Composable 要用 item {} 包裹

---

## 5. 签名

项目已配置自动签名（qitong.jks），Debug 和 Release 用同一签名。

手动重签名:
```bash
apksigner sign --ks qitong.jks \
    --ks-pass pass:qitongwangluo \
    --ks-key-alias qitong \
    --key-pass pass:qitongwangluo \
    --in input.apk --out signed.apk
```

---

## 6. 安装到设备

```bash
# 复制到 sdcard
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/QiTongAI.apk

# 安装（需先复制到 /data/local/tmp/）
cp /sdcard/Download/QiTongAI.apk /data/local/tmp/app.apk
chmod 644 /data/local/tmp/app.apk
pm install -r /data/local/tmp/app.apk

# 验证
dumpsys package com.qtwl.gateway | grep -E 'versionName|versionCode'
```

---

## 7. 验证

- 启动网关 -> 通知栏显示端口
- 抓包日志 -> 不闪退，筛选有效
- 通知栏流量 -> 数字动态更新
- 群聊模式 -> 弹窗列表不跳动
- qtai-sj 大脑 -> 綦小桐 xxx 正确响应

---

## 8. 推送 Git

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# 清理备份文件
find . -name '*.bak' -delete
find . -name '*.before_py' -delete

# 提交
git add -A
git commit -m 'v3.8.x - 更新说明'
git push

# 打标签
git tag -f v3.8.x
git push origin v3.8.x -f
```

---

## 9. 发布 GitHub Release

```bash
GIT_TOKEN=\$(git remote -v | head -1 | sed 's/.*qtgf520://;s/@.*//')
API_URL='https://api.github.com/repos/qtgf520/qitong-ai-gateway/releases'

# 创建 Release
curl -s -X POST "\$API_URL" \
  -H "Authorization: Bearer \$GIT_TOKEN" \
  -d '{"tag_name":"v3.8.x","name":"v3.8.x","prerelease":false}'

# 上传 APK
RELEASE_ID=\$(curl -s -H "Authorization: Bearer \$GIT_TOKEN" \
  "\$API_URL/tags/v3.8.x" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

curl -s -X POST \
  "https://uploads.github.com/.../releases/\$RELEASE_ID/assets?name=QiTongAI.apk" \
  -H "Authorization: Bearer \$GIT_TOKEN" \
  --data-binary @app/build/outputs/apk/debug/app-debug.apk
```

---

## 10. 清理旧文件

```bash
# 清理旧APK
cd /sdcard/Download
ls QiTongAI*.apk 2>/dev/null | grep -v 'QiTongAI-v3.8.x.apk' | while read f; do rm -f "\$f"; done

# 清理旧备份
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621
ls -dt backup_*/ | tail -n +2 | xargs rm -rf 2>/dev/null
```

---

> 文档版本: v12 - 2026-07-10
> 适用于: 綦桐AI网关 v3.8.x+