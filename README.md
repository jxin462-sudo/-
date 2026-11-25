# ICU v4 独立项目归档

本项目归档从 2026-11-04 至 2026-11-25 期间，Claude Code 中关于 `icu.apk.1 v4` 独立工程的源码与迭代记录。

## 来源与边界

- **源码来源**：`C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`
- **只读对照、未混用**：`C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v5(1)\icu.apk.1 v4\icu.apk`
- **统计范围**：2026-11-04 ~ 2026-11-25
- **实际 v4 记录起点**：2026-11-07。11-04 ~ 11-06 未发现独立 v4 工程会话或台账；最早的独立项目记录是 `iteration/claude-memory/icu-v4-standalone-2026-11-07.md`。
- **红线**：本仓库只对独立 v4 线负责，不与 Desktop 旧 ICU 主工程、V92~V95、GitHub `icu` 仓库混用版本、APK、提交或验收结论。

## 目录结构

- `src/`：独立 v4 Android 工程源码，约 490 个文件。
- `iteration/claude-memory/`：Claude Code 日常台账与独立项目记忆。
- `iteration/session-extracts/`：从 Claude Code JSONL 会话中抽取的用户文本与助手文本，共 19 个会话、744 条摘录；不含工具调用、工具结果和思考块。
- `iteration/probes/`：v4 迭代过程中的探针脚本、验证摘要和差异记录。
- `docs/`：ICU 八份交付文档、11 月月报、APK 阶段说明、V1.02 对照清单。
- `tools/extract_sessions.mjs`：生成本仓库会话摘录的脚本。

## 排除内容

为避免泄露凭据和提交构建垃圾，以下内容未入库：

- `build/`、`.gradle/`、`.idea/`
- `local.properties`、`keystore.properties`
- `*.apk`、`*.class`、`*.jks`、`*.keystore`
- 工程内 `%TEMP%` 临时验证目录
- Claude Code 原始会话中的工具调用、工具结果、思考块

另外，`src/app/build.gradle` 中原有的签名口令硬编码兜底已移除，改为必须从本地 `keystore.properties` 读取；缺项时构建会直接报错，不再使用任何明文口令。

## 本地构建提示

1. 在仓库根目录创建 `keystore.properties`（不要提交）：

   ```properties
   storeFile=路径/icu-release.jks
   storePassword=你的签名库口令
   keyAlias=icu
   keyPassword=你的签名密钥口令
   ```

2. Android SDK 路径由本机 `local.properties` 或环境变量提供，同样不要提交。
3. 构建命令：

   ```bash
   ./gradlew :app:assembleDebug
   ```

## 说明

本仓库是源码与迭代归档，不声明任何新的真机、外设、打印纸件或物理动作验收结论。软件功能验收状态以用户确认和 `docs/ICU月报-2026-11.md` 为准。
