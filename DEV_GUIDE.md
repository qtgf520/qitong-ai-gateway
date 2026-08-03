# 🚀 綦桐AI网关 — 超级开发指南（自教文件）

> ⚡ **最高优先级：** 本文件是 AI 代理开发綦桐AI网关的**自教指南**，每次迭代必须优先阅读和执行
> **核心规则：优先启动 Debug 编译**（`./gradlew assembleDebug`），快速验证

---

## 📌 开发铁律（每次必读）

0. **📖 每次开发前先读本文件** — 不跳步，不省略
1. **🔥 优先 Debug 编译** — 所有修改先 `assembleDebug` 验证通过
2. **💾 改前先备份单个文件** — 改哪个文件就备份哪个文件（`cp xxx.kt xxx.kt.bak`）
3. **🔍 参照备份开发** — 打开备份文件（xxx.kt.bak）参考，修改现有文件（xxx.kt），避免花括号等格式错误
4. **❌ 编译报错时先恢复备份** — 从刚备份的 `.bak` 文件恢复，**禁止直接从Git拉取**
5. **✅ 编译必须通过** — `BUILD SUCCESSFUL` 才能交付
6. **🚿 Git提交前清理** — 删 `.bak`、`backup_*`、临时文件
7. **📖 每次改代码前先看本文件** — 严格按流程走，不跳步
8. **⌨️ 改代码优先使用工具编辑** — 用 `edit_file` 工具修改，避免手动替换导致格式错乱
9. **🚫 禁止卸载APP** — 签名不对重新签名，禁止卸载（保持用户数据）
10. **📲 安装使用 Shizuku 权限** — 无 root 权限时用 Shizuku 授权安装
11. **🔒 发布不泄漏本地凭证** — 签名证书(`*.jks`/`*.keystore`)、密码(`storePassword`/`keyPassword`)、构建产物(`*.idsig`/`*.apk`/`*.aab`) 禁止提交Git。`.gitignore` 已含规则，`git add` 前先 `git status` 检查有无敏感文件
12. **📄 每次发布前同步更新README.md和CHANGELOG.md** — 版本号、更新日志、功能描述必须与当前版本一致，改完再提交Git
13. **🏠 双轨Git制 — 测试版提交本地Git，正式版才推远程** — 测试版只 commit 到本地 `.git`，不 `git push`；正式版才 `git push origin` + 打标签 + Release。本地Git作为"测试版存档"，远程Git作为"正式版发布"
14. **📦 本地Git archive 发布到目录** — 测试通过后，用 `git archive` 导出干净代码到发布目录，供产出比对

---

## 1. 版本号规则

### 测试版
```
格式：3.x.x-N  （N是测试序号，每次测试递增）
例如：3.18.2-1 → 3.18.2-2 → 3.18.2-3 ...
```
- **测试版** = 只编译安装本地验证，不发Git
- 每次测试 versionCode 递增1，versionName 的 -N 数字递增
### 正式版
```
格式：3.x.x  （去掉 -N）
例如：3.18.2-3 测试通过 → 发布 3.18.3
```
- **正式版** = 编译 + 复制到sdcard + 安装本地 + 推Git + 打标签 + GitHub Release
- 正式发布时 versionCode 保持测试版最后的值，versionName 去掉 -N 后缀

### 示例
| 阶段 | versionName | versionCode | 操作 |
|------|-------------|-------------|------|
| 测试1 | 3.18.2-1 | 162 | 编译安装验证 |
| 测试2 | 3.18.2-2 | 163 | 编译安装验证 |
| 正式发布 | 3.18.3 | 163 | 编译+安装+Git+Release |

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
| 备份管理器 | data/db/BackupManager.kt |
| 自动备份 | data/db/AutoBackupWorker.kt |
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

---

## 4. 安装到设备

```bash
# 复制到 sdcard（带版本号）
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/QiTongAI-v3.x.x(-N).apk

# 安装（需先复制到 /data/local/tmp/）
cp /sdcard/Download/QiTongAI-v3.x.x(-N).apk /data/local/tmp/app.apk
chmod 644 /data/local/tmp/app.apk
pm install -r /data/local/tmp/app.apk

# 验证
dumpsys package com.qtwl.gateway | grep -E 'versionName|versionCode'
```

---

## 5. 验证清单

- 启动网关 -> 通知栏显示端口
- 通知栏模型名 -> 不闪烁，内容变化才更新
- 通知栏流量 -> 数字动态更新
- 抓包日志 -> 不闪退，筛选有效
- 群聊模式 -> 弹窗列表不跳动
- qtai-sj 大脑 -> 綦小桐 xxx 正确响应
- 备份 -> 导出到 /sdcard/Download/Operit/backup/qtkfbf/
- 恢复 -> 自动扫描该目录，5天内的备份文件可恢复

---

## 6. 备份目录规则

```
备份根目录：/sdcard/Download/Operit/backup/qtkfbf/
格式：backup_yyyyMMdd_HHmmss.qtbk 或 auto_backup_yyyyMMdd_HHmmss.qtbk
保留：删除超过5天的旧备份，当前备份保留不动
```

- `getBackupDir()` 返回上述目录
- 定时备份自动清理超过5天的备份文件（只删旧的，保留当前）
- 手动备份也存放在同一目录，方便统一管理
- ⚠️ 重要：只删除5天前的旧备份，当前这次备份不删

---

## 7. 测试版 vs 正式版

### 测试模式（我说"测试"时）
```
① 改版本号 → 3.x.x-N（N递增）
② 改代码（对照备份法）
③ ./gradlew assembleDebug
④ 复制APK到sdcard（带版本号）
⑤ 安装到设备
⑥ 验证功能
⑦ 提交本地Git（不 push 远程）
⑧ 可选：git archive 导出到发布目录 ~/publish/
```
**不推远程Git，不打远程标签，不发GitHub Release**

### 正式模式（我说"发布"时）
```
① 改版本号 → 3.x.x（去掉 -N）
② 改代码（对照备份法）
③ 更新README.md（版本号+更新日志）
④ 更新CHANGELOG.md（追加新版本日志）
⑤ ./gradlew assembleDebug
⑥ 复制APK到sdcard（带版本号）
⑦ 安装到设备
⑧ 验证功能
⑨ 清理备份文件（删.bak）
⑩ 提交本地Git
⑪ 推远程Git + 打标签 + GitHub Release（APK上传）
```

---

## 8. 推送 Git & 发布

### 8.1 本地Git（测试版用，不碰远程）
测试版禁止推远程，但需要本地存档，方便回滚和比对。
```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# ⚠️ 先检查有无敏感文件被跟踪
git status

# 清理备份文件
find . -name '*.bak' -delete
find . -name '*.before_py' -delete

# 提交到本地（不 push）
git add -A
git commit -m 'v3.x.x-N - 更新说明'
```

### 8.2 远程Git（正式版用）
正式版才推远程 + 打标签。
```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/app621

# ⚠️ 先检查有无敏感文件被跟踪
git status
# 确认没有 *.jks *.keystore *.idsig *.apk *.aab 等文件再提交

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

### 8.3 GitHub Release
```bash
GIT_TOKEN=$(git remote -v | head -1 | sed 's/.*qtgf520://;s/@.*//')
API_URL='https://api.github.com/repos/qtgf520/qitong-ai-gateway/releases'

# 创建 Release
curl -s -X POST "$API_URL" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  -d '{"tag_name":"v3.x.x","name":"v3.x.x","prerelease":false}'

# 上传 APK（带版本号）
RELEASE_ID=$(curl -s -H "Authorization: Bearer $GIT_TOKEN" \
  "$API_URL/tags/v3.x.x" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

curl -s -X POST \
  "https://uploads.github.com/repos/qtgf520/qitong-ai-gateway/releases/$RELEASE_ID/assets?name=QiTongAI-v3.x.x.apk" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @app/build/outputs/apk/debug/app-debug.apk
```

### 8.4 本地Git archive 发布到目录（可选）
测试通过后，想把当前版本导出成干净目录：
```bash
# 创建发布目录
mkdir -p ~/publish/v3.x.x

# 用 git archive 导出干净代码
git archive --format=tar HEAD | tar -x -C ~/publish/v3.x.x

# 或直接复制构建产物
cp -r app/build/outputs/apk/debug/app-debug.apk ~/publish/v3.x.x/QiTongAI-v3.x.x.apk

# 发布目录在 proot 中，可 cp 到 /sdcard/ 共享给 Android
cp -r ~/publish/v3.x.x /sdcard/Download/publish/
```

---

## 9. 清理旧文件

```bash
# 清理旧APK（保留最新）
cd /sdcard/Download
ls QiTongAI*.apk 2>/dev/null | grep -v 'QiTongAI-v3.18.3.apk' | while read f; do rm -f "$f"; done

# 清理旧备份（保留最近5天）
# 自动由 BackupManager.cleanupOldBackups(5) 在导出时执行
# 手动清理：
cd /sdcard/Download/Operit/backup/qtkfbf
ls -t *.qtbk 2>/dev/null | tail -n +2 | while read f; do rm -f "$f"; done
```

---

> **文档版本:** v18 — 2026-08-03
> **适用于:** 綦桐AI网关 v3.18.x+
> **核心改动:**
> - 铁律13：新增"双轨Git制 — 测试版提交本地Git，正式版才推远程"
> - 铁律14：新增"本地Git archive 发布到目录"
> - 第7节：测试版流程新增⑦提交本地Git + ⑧可选archive导出；正式版流程拆出⑩提交本地Git和⑪推远程
> - 第8节：拆分为8.1本地Git（测试版）、8.2远程Git（正式版）、8.3 GitHub Release、8.4 本地Git archive 发布到目录
