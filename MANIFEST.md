# 归档清单

| 目录 | 内容 | 数量 / 规模 |
|---|---|---:|
| `src/` | 独立 v4 Android 工程源码 | 490 个文件，约 6.3 MB |
| `iteration/claude-memory/` | Claude Code 台账与独立项目记忆 | 10 个 Markdown |
| `iteration/session-extracts/` | v4 相关会话文本摘录 | 19 个会话，744 条文本 |
| `iteration/probes/` | 探针脚本、验证摘要、差异记录 | 214 个文本文件，约 2.1 MB |
| `docs/` | ICU 八份 DOCX、月报、阶段说明、V1.02 清单 | 11 个文件 |
| `tools/` | 会话摘录生成脚本 | 1 个文件 |

生成时间：2026-11-25  
归档目标：`https://github.com/jxin462-sudo/-`

## 来源映射

- `src/`：来自 `icu.apk.1 v4\icu.apk`，已排除构建目录、IDE 缓存、本地配置和签名相关文件。
- `iteration/claude-memory/`：来自 `C:\Users\jx\.claude\projects\C--Users-jx\memory\`。
- `iteration/session-extracts/`：来自 `C:\Users\jx\.claude\projects\C--Users-jx\*.jsonl`，由 `tools/extract_sessions.mjs` 生成。
- `iteration/probes/`：来自 `C:\Users\jx\icu-v4-probes-20261111\`，仅文本探针与证据。
- `docs/`：来自 `C:\Users\jx\Desktop\ICU\`、`G:\报告\`、`G:\ICU\`。

## 安全处理

- 未入库：`keystore.properties`、JKS/keystore、APK、class、Gradle/IDE 缓存。
- 已移除 `src/app/build.gradle` 中的签名口令硬编码兜底。
- 会话摘录中对疑似凭据字段做了脱敏；工具调用与工具结果未入库。
