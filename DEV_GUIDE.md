# 🚀 綦桐AI网关 — 超级开发指南（自教文件）

> ⚡ **最高优先级：** 本文件是 AI 代理开发綦桐AI网关的**自教指南**，每次迭代必须优先阅读和执行
> **核心规则：优先启动 Debug 编译**（`./gradlew assembleDebug`），快速验证

---

## 📌 开发铁律（每次必读）

1. **🔥 优先 Debug 编译** — 所有修改先 `assembleDebug` 验证通过
2. **💾 改前先备份单个文件** — 改哪个文件就备份哪个文件（`cp xxx.kt xxx.kt.bak`）
3. **🔍 参照备份开发** — 打开备份文件（xxx.kt.bak）参考，修改现有文件（xxx.kt），避免花括号等格式错误
4. **❌ 编译报错时先恢复备份** — 从刚备份的 `.bak` 文件恢复，**禁止直接从Git拉取**
5. **✅ 编译必须通过** — `BUILD SUCCESSFUL` 才能交付
6. **🚿 Git提交前清理** — 删 `.bak`、`backup_*`、临时文件
7. **📖 每次改代码前先看本文件** — 严格按流程走，不跳步
8. **⌨️ 改代码优先 Vim / Neovim** — 修改代码时优先打开 Vim 或 Neovim 进行编辑，避免使用其他编辑器导致格式错乱
9. **🚫 禁止卸载APP** — 签名不对重新签名，禁止卸载（保持用户数据）
10. **📲 安装使用 Shizuku 权限** — 无 root 权限时用 Shizuku 授权安装
---

## 1. 版本号规则

### 测试版
```
格式：3.x.x-N  （N是测试序号，每次测试递增）
例如：3.8.5-1 → 3.8.5-2 → 3.8.5-3 ...
```
- **测试版** = 只编译安装本地验证，不发Git
- 每次测试 versionCode 递增1，versionName 的 -N 数字递增
- 测试版本 com.qtwl.gateway.cs 用于测试版本专用
### 正式版
```
格式：3.x.x  （去掉 -N）
例如：3.8.5-3 测试通过 → 发布 3.8.5
```
- **正式版** = 编译 + 复制到sdcard + 安装本地 + 推Git + 打标签 + GitHub Release
- 正式发布时删掉 -N 后缀
- versionCode 直接对应当前值

### 示例
| 阶段 | versionName | versionCode | 操作 |
|------|-------------|-------------|------|
| 测试1 | 3.8.5-1 | 109 | 编译安装验证 |
| 测试2 | 3.8.5-2 | 110 | 编译安装验证 |
| 测试3 | 3.8.5-3 | 111 | 编译安装验证 |
| 正式发布 | 3.8.5 | 111 | 编译+安装+Git+Release |

---

## 2. 修改代码（对照备份法）

### 2.1 核心修改流程
```
① 备份要改的文件  →  cp TargetFile.kt TargetFile.kt.bak
② 打开备份文件       →  参考其结构
③ 修改原文件         →  照着备份的逻辑去改
④ 编译验证           →  ./gradlew assembleDebug
⑤ 编译失败？         →  cp TargetFile.kt.bak TargetFile.kt（恢复备份）
⑥ 重新修改再编译
```

### 2.2 对照备份法详解
```
备份文件 TargetFile.kt.bak  ← 打开参考（不改它）
                ↓ 对照
现有文件 TargetFile.kt       ← 实际修改（编译它）
```
- 备份文件是**已知能编译通过的**，打开它看花括号、函数结构
- 照着备份的结构去改现有文件
- 这样就**不会出现花括号错乱、函数被吃**等问题

### 2.3 关键源文件
| 文件 | 路径相对 app/src/main/java/... |
|------|-------------------------------|
| 版本号 | app/build.gradle.kts |
| 网关转发 | gateway/GatewayService.kt |
| 通知栏 | service/GatewayForegroundService.kt |
| 管理页UI | ui/screens/DataManagementScreen.kt |
| 群聊管理器 | service/GroupChatManager.kt |
| 调度器 | gateway/GatewayScheduler.kt |
| ViewModel | ui/viewmodel/GatewayViewModel.kt |

### 2.4 编译报错时**绝对禁止**的操作
```
❌ git checkout -- xxx.kt         ← 禁止！会丢失本地修改
❌ git restore xxx.kt             ← 禁止！会丢失本地修改
✅ cp xxx.kt.bak xxx.kt          ← 正确！从备份恢复
```
备份文件就是用来兜底的，`.bak` 就是你的安全网。

---

## 3. 编译

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# Debug 版（测试用，优先）
./gradlew assembleDebug

# Release 版（正式发布用）
./gradlew clean assembleRelease
```

常见问题: LazyColumn 中 Composable 要用 item {} 包裹

---

## 4. 安装到设备

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

## 5. 验证清单

- 启动网关 -> 通知栏显示端口
- 抓包日志 -> 不闪退，筛选有效
- 通知栏流量 -> 数字动态更新
- 群聊模式 -> 弹窗列表不跳动
- qtai-sj 大脑 -> 綦小桐 xxx 正确响应

---

## 6. 测试版 vs 正式版

### 测试模式（我说"测试"时）
```
① 改版本号 → 3.x.x-N（N递增）
② 改代码（对照备份法）
③ ./gradlew assembleDebug
④ 复制APK到sdcard
⑤ 安装到设备
⑥ 验证功能
```
**不发Git，不打标签，不发Release**

### 正式模式（我说"发布"时）
```
① 改版本号 → 3.x.x（去掉 -N）
② 改代码（对照备份法）
③ ./gradlew assembleDebug（或 assembleRelease）
④ 复制APK到sdcard
⑤ 安装到设备
⑥ 验证功能
⑦ 推Git + 标签 + GitHub Release
```

---

## 7. 推送 Git & 发布

### 7.1 推送 Git
```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# 清理备份文件
find . -name '*.bak' -delete
find . -name '*.before_py' -delete

# 提交
git add -A
git commit -m 'v3.x.x - 更新说明'
git push

# 打标签
git tag -f v3.x.x
git push origin v3.x.x -f
```

### 7.2 GitHub Release
```bash
GIT_TOKEN=$(git remote -v | head -1 | sed 's/.*qtgf520://;s/@.*//')
API_URL='https://api.github.com/repos/qtgf520/qitong-ai-gateway/releases'

# 创建 Release
curl -s -X POST "$API_URL" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  -d '{"tag_name":"v3.x.x","name":"v3.x.x","prerelease":false}'

# 上传 APK
RELEASE_ID=$(curl -s -H "Authorization: Bearer $GIT_TOKEN" \
  "$API_URL/tags/v3.x.x" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

curl -s -X POST \
  "https://uploads.github.com/repos/qtgf520/qitong-ai-gateway/releases/$RELEASE_ID/assets?name=QiTongAI.apk" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  --data-binary @app/build/outputs/apk/debug/app-debug.apk
```

---

## 8. 清理旧文件

```bash
# 清理旧APK（保留最新）
cd /sdcard/Download
ls QiTongAI*.apk 2>/dev/null | grep -v 'QiTongAI-v3.x.x.apk' | while read f; do rm -f "$f"; done

# 清理旧备份（保留最新）
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621
ls -dt backup_*/ | tail -n +2 | xargs rm -rf 2>/dev/null
```

---

> **文档版本:** v14 — 2026-07-14
> **适用于:** 綦桐AI网关 v3.9.x+
> **核心改动:** 新增Vim/Neovim优先编辑 + 禁止卸载APP铁律 + Shizuku安装
