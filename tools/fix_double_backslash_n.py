#!/usr/bin/env python3
"""修复 Kotlin 字符串里 \\n 双反斜杠：显示成字面 \\n 应为真实换行 \\n
只处理 UI 相关 .kt 文件，把 \\n（两个字符反斜杠+n）替换为 \\n（一个反斜杠+n）"""
import os, io

BASE = "/data/user/0/com.ai.assistance.operit/files/workspace/app621/app/src/main/java/com/qtwl/gateway/ui"
targets = []
for root, dirs, files in os.walk(BASE):
    if "bak" in root:
        continue
    for f in files:
        if f.endswith(".kt") and ".bak" not in f:
            targets.append(os.path.join(root, f))

changed = []
for tp in targets:
    try:
        s = io.open(tp, encoding="utf-8").read()
    except Exception:
        continue
    # 只处理普通双引号字符串内的双反斜杠 \n：把 \\n 变成 \n
    # 注意不要误伤已经正确的 \n（即文件里反斜杠+n）
    if "\\\\n" in s:
        s2 = s.replace("\\\\n", "\\n")
        if s2 != s:
            io.open(tp, "w", encoding="utf-8").write(s2)
            changed.append(tp)
print("FIXED %d files:" % len(changed))
for c in changed:
    print(" ", c)