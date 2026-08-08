# 🚀 Android 应用 — 超级开发指南（自教文件）

> ⚡ **最高优先级：** 本文件是 AI 代理开发 Android 应用的**自教指南**，每次迭代必须优先阅读和执行
> **核心规则：优先启动 Debug 编译**（`./gradlew assembleDebug`），快速验证

---

## 📌 开发铁律（每次必读，违反即回滚）

0. **📖 每次开发前先读本文件** — 不跳步，不省略，禁止跳过任何一步
1. **🔥 优先 Debug 编译** — 所有修改先 `assembleDebug` 验证通过才交付
2. **💾 改前先备份单个文件** — 改哪个文件就备份哪个文件（`cp xxx.kt xxx.kt.bak`），**不备份不准改**
3. **🔍 参照备份开发** — 打开备份文件（xxx.kt.bak）参考，修改现有文件（xxx.kt），避免花括号等格式错误
4. **❌ 编译报错时先恢复备份** — 从刚备份的 `.bak` 文件恢复，**绝对禁止从Git拉取**
5. **✅ 编译必须通过** — `BUILD SUCCESSFUL` 才能交付，不通过不交付
6. **🚿 Git提交前清理** — 删 `.bak`、`backup_*`、临时文件，不清不提交
7. **📖 每次改代码前先看本文件** — 严格按流程走，不跳步
8. **⌨️ 改代码优先使用工具编辑** — 用 `edit_file` 工具修改，避免手动替换导致格式错乱
9. **🚫 禁止卸载APP** — 签名不对重新签名，禁止卸载（保持用户数据）
10. **📲 安装使用 Shizuku 权限** — 无 root 权限时用 Shizuku 授权安装
11. **🔒 发布不泄漏本地凭证** — 签名证书(`*.jks`/`*.keystore`)、密码(`storePassword`/`keyPassword`)、构建产物(`*.idsig`/`*.apk`/`*.aab`) 禁止提交Git。`.gitignore` 已含规则，`git add` 前先 `git status` 检查有无敏感文件
12. **📄 每次发布前同步更新README.md和CHANGELOG.md** — 版本号、更新日志、功能描述必须与当前版本一致，改完再提交Git
13. **🏠 双轨Git制 — 测试版提交本地Git，正式版才推远程** — 测试版只 commit 到本地 `.git`，不 `git push`；正式版才 `git push origin` + 打标签 + Release。本地Git作为"测试版存档"，远程Git作为"正式版发布"
14. **🔐 编译前必须设置签名环境变量** — 根据项目实际签名配置设置，**不设不编译**
15. **🔢 每次开发必须升级版本号，严禁重复使用** — 无论测试版还是正式版，**每次修改代码（含修复、新功能、文档调整）都必须先递增 versionCode，versionName 同步递增**（测试版 `-N` 每次 +1，正式版去 `-N`）。**禁止重复使用同一个版本号**（重复会导致覆盖安装不生效、系统判定版本未更新或更新不兼容）。每次 `git commit` 前必须先确认 build.gradle.kts 版本号已递增且未与历史重复
16. **📛 APK 文件名必须统一格式 `AppName-v版本号.apk`** — 测试版和正式版复制到 sdcard 的 APK 文件名必须统一使用 `QiTongGateway-v版本号.apk` 格式（如 `QiTongGateway-v3.18.15-1.apk`、`QiTongGateway-v3.18.16.apk`），**禁止使用 `app-debug.apk` 等无版本号或不同格式的文件名直接安装**，否则 Android 系统会因包签名不一致或文件名差异导致更新不兼容（安装失败或无法覆盖安装）

---

## 1. 版本号规则

### 测试版
```
格式：主版本.次版本.修订号-N  （N是测试序号，每次测试递增）
例如：1.0.0-1 → 1.0.0-2 → 1.0.0-3 ...
```
- **测试版** = 只编译安装本地验证，不发Git远程
- 每次测试 versionCode 递增1，versionName 的 -N 数字递增
- **绝对不能重复使用同一个 -N 号**

### 正式版
```
格式：主版本.次版本.修订号  （去掉 -N）
例如：1.0.0-3 测试通过 → 发布 1.0.0
```
- **正式版** = 编译 + 复制到sdcard + 安装本地 + 推Git + 打标签 + GitHub Release
- 正式发布时 versionCode 保持测试版最后的值，versionName 去掉 -N 后缀

### 示例
| 阶段 | versionName | versionCode | 操作 |
|------|-------------|-------------|------|
| 测试1 | 1.0.0-1 | 1 | 编译安装验证 |
| 测试2 | 1.0.0-2 | 2 | 编译安装验证 |
| 测试3 | 1.0.0-3 | 3 | 编译安装验证 |
| 正式发布 | 1.0.0 | 3 | 编译+安装+Git+Release |

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

### 2.3 编译报错时**绝对禁止**的操作
```
❌ git checkout -- xxx.kt         ← 禁止！会丢失本地修改
❌ git restore xxx.kt             ← 禁止！会丢失本地修改
✅ cp xxx.kt.bak xxx.kt          ← 正确！从备份恢复
```
备份文件就是用来兜底的，`.bak` 就是你的安全网。

---

## 3. 编译

```bash
cd /path/to/your/project

# 先设置签名环境变量（必须，根据项目实际配置修改）
# export YOUR_KEY_PASSWORD=xxx
# export YOUR_KEY_ALIAS=xxx
# export YOUR_STORE_PASSWORD=xxx

# Debug 版（测试用，优先）
./gradlew assembleDebug

# Release 版（正式发布用）
./gradlew clean assembleRelease
```

---

## 4. 安装到设备

```bash
# ★★★ 复制到 sdcard（必须带版本号，命名统一为 AppName-v版本号.apk）★★★
# 例如：QiTongGateway-v3.18.16.apk（正式版） / QiTongGateway-v3.18.15-1.apk（测试版）
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/QiTongGateway-v版本号.apk

# 安装（需先复制到 /data/local/tmp/）
cp /sdcard/Download/QiTongGateway-v版本号.apk /data/local/tmp/app.apk
chmod 644 /data/local/tmp/app.apk
pm install -r /data/local/tmp/app.apk

# 验证版本
dumpsys package 你的包名 | grep -E 'versionName|versionCode'
```

---

## 5. 验证清单（通用）

- 应用正常启动 → 不闪退
- 核心功能正常 → 界面无错位
- 通知/服务 → 正常显示和运行
- 后台保活 → 关屏不掉线
- 权限 → 按需申请，不滥用
- 安装覆盖 → 旧版本数据不丢失
- 页面切换 → 无卡顿/崩溃

---

## 6. 测试版 vs 正式版

### 测试模式（AI说"测试"时）
```
① 改版本号 → x.x.x-N（N必须递增，不能重复）
② 改代码（对照备份法）
③ 设置签名环境变量
④ ./gradlew assembleDebug
⑤ 复制APK到sdcard（带版本号，命名统一：QiTongGateway-vx.x.x-N.apk）
⑥ 安装到设备
⑦ 验证功能
⑧ 提交本地Git（不 push 远程）
```
**不推远程Git，不打远程标签，不发GitHub Release**

### 正式模式（AI说"发布"时）
```
① 改版本号 → x.x.x（去掉 -N，versionCode保持测试版最后值）
② 更新CHANGELOG.md（追加新版本日志）
③ 更新README.md（版本号+版本历史表）
④ 设置签名环境变量
⑤ ./gradlew assembleDebug
⑥ 复制APK到sdcard（带版本号，命名统一：QiTongGateway-vx.x.x.apk）
⑦ 安装到设备
⑧ 验证功能
⑨ 清理备份文件（删.bak、backup_*）
⑩ 提交本地Git
⑪ 推远程Git + 打标签 + GitHub Release（APK上传）
```

---

## 7. 推送 Git & 发布

### 7.1 本地Git（测试版用，不碰远程）
测试版禁止推远程，但需要本地存档，方便回滚和比对。
```bash
cd /path/to/your/project

# ⚠️ 先检查有无敏感文件被跟踪
git status

# 清理备份文件
find . -name '*.bak' -delete
find . -name '*.before_py' -delete

# 提交到本地（不 push）
git add -A
git commit -m 'vx.x.x-N - 更新说明'
```

### 7.2 远程Git（正式版用）
正式版才推远程 + 打标签。
```bash
cd /path/to/your/project

# ⚠️ 先检查有无敏感文件被跟踪
git status
# 确认没有 *.jks *.keystore *.idsig *.apk *.aab 等文件再提交

# 清理备份文件
find . -name '*.bak' -delete
find . -name '*.before_py' -delete

# 提交
git add -A
git commit -m 'vx.x.x - 更新说明'
git push

# 打标签
git tag -f vx.x.x
git push origin vx.x.x -f
```

### 7.3 GitHub Release
```bash
GIT_TOKEN=$(git remote -v | head -1 | sed 's/.*用户名://;s/@.*//')
API_URL='https://api.github.com/repos/用户名/仓库名/releases'

# 创建 Release
curl -s -X POST "$API_URL" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  -d '{"tag_name":"vx.x.x","name":"vx.x.x","prerelease":false}'

# 上传 APK（带版本号）
RELEASE_ID=$(curl -s -H "Authorization: Bearer $GIT_TOKEN" \
  "$API_URL/tags/vx.x.x" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

curl -s -X POST \
  "https://uploads.github.com/repos/用户名/仓库名/releases/$RELEASE_ID/assets?name=AppName-vx.x.x.apk" \
  -H "Authorization: Bearer $GIT_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @app/build/outputs/apk/debug/app-debug.apk
```

---

## 8. 清理旧文件

```bash
# 清理旧APK（保留最新）
cd /sdcard/Download
ls YourAppName*.apk 2>/dev/null | grep -v 'YourAppName-v当前版本.apk' | while read f; do rm -f "$f"; done
```

---

> **文档版本:** v1 — 通用版
> **适用于:** 任何 Android 应用的 AI 辅助开发
> **通用化说明:** 本指南去除了特定项目引用，所有项目名/路径/包名/证书名均为占位符，使用时替换为实际值即可