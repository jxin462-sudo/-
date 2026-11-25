---
name: icu-project-log-2026-11-13
description: 独立v4断连六项回显与报告文字删除已交付135217 APK，Chrome/JUnit通过，签名/ZIP/资产及桌面副本核验完成
metadata:
  node_type: memory
  type: project
  originSessionId: b108d895-6227-48f1-9450-532aff080c17
  modified: 2026-11-13T06:01:42.322Z
---

# icu.apk.1 v4 独立项目台账 2026-11-13

## 范围与需求

仅修改独立工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不与 Desktop 旧 ICU 工程混用。用户反馈：四个护理模式“仅保存”后用开关下发时开关在开/关间反复，未连接主机蓝牙也出现；提示“15条只执行14条未完成”；要求检查原因，并暂时注释主机控制页治疗时长“设置”按钮及四个护理模式编辑弹窗的治疗时长项。

## 根因与修改

- **开关抖动根因是双渲染器字段冲突**：`lanhu-first-phase.js` 以 `hostModes[].enabled` 写 `.icu-switch-fake`，`lanhu-live-state.js` 却以 `hostModes[].active` 每秒覆盖同一 DOM。独立功能开关本来就不发送护理模式标识，`active=false` 不代表功能开关关闭。现将后者四路也统一读取 `enabled`；`active` 字段仍保留给主机确认逻辑。
- **14/15 的直接原因是 phantom skip，不是已证实的主机少执行一条**：旧 `sendHostModeConfig()` 在 `changeCareMode=false` 时仍进入 `else progress.skip()`，把“不需要发送护理模式标识”多算成一条未完成。现只有 `changeCareMode=true` 才记录或跳过该命令；真正前置失败导致跳过开关/模式标识的计数仍保留。
- Toast 已明确写“已排队，等待主机确认”或“已排队，本次功能未完整下发”，`HostModeBatchProgress` 只描述 App 蓝牙发送队列，**不等于主机已实际执行**。
- 开启分支不再预先无条件 `setHostModeEnabled(true)`；整批全部成功入队后才保持开。无连接、零入队或部分失败时保持关，已保存配置不删除；`setHostModeEnabled()` 后立即 `notifyLanhuStateChanged()`。关闭仍立即清除本地开启状态。
- 四个模式共用基础参数数组由温度/氧浓度/CO2/治疗时长改为仅温度/氧浓度/CO2，并删除 `applyHostModeNumbers()` 的 `setControlTime(15, ...)` 分支。因此弹窗不再出现治疗时长，旧配置里的 `treatmentMinutes` 即使尚未重存也不再参与批量下发。其他五项功能定时 `redTime/blueTime/uvTime/nebulizerTime/anionTime` 未改。
- 主机控制 H5 的“设置”按钮已注释，且同步移除 `group_63/control_time` 的 data、文本、节点、坐标 action 路径，避免只隐藏文字仍可点击；原生 `control_time` 兼容入口改为只读提示，Canvas 不再为 commandIndex 15 画按钮或注册点击。治疗时长自动累计和只读数值显示保留。
- 本轮未修改 `BleManager.java` 或蓝牙协议。

## 修改文件

1. `app/src/main/java/com/example/icuapk/IcuDashboardView.java`
2. `app/src/main/assets/lanhu/lanhu-live-state.js`
3. `app/src/main/assets/lanhu/lanhu-bridge.js`
4. `app/src/main/assets/lanhu/src/views/lanhu_2zhujikongzhi/index.html`

修改前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261113-pre-host-mode`。

## 验证

- `gradlew.bat testDebugUnitTest --console=plain`：**BUILD SUCCESSFUL**，Java 编译成功；JUnit 6 类共 **39 项，0 skipped / 0 failures / 0 errors**。
- `lanhu-bridge.js`、`lanhu-live-state.js`、`lanhu-first-phase.js` 最终 `node --check` 全通过。
- 新探针 `C:\Users\jx\icu-v4-probes-20261111\host-mode-control-probe.js`：首轮有两项静态检查因探针截取结束标记未匹配而失败，修正探针后完整重跑 **30/30 通过**。结果在 `C:\Users\jx\icu-v4-probes-20261111\host-mode-control-20261113\summary.json`。
- Chrome 使用真实主机控制页及 mock：52 次原生状态读取，12 组 `enabled=true` 快照期间不断切换 `active`，四个开关始终开，MutationObserver 未捕获任何 on→off 回写；`enabled=false/active=true` 时保持关。治疗时长设置节点为 0、只读值仍显示，点击标题/数值/卡片均未发送 `control_time`，无运行时错误。
- 静态检查覆盖独立路径无 phantom skip、旧治疗时长不再下发、开启状态由 `progress.isComplete()` 决定、其他五项功能定时保留及 H5/Canvas 入口关闭。

## 打包交付（用户随后明确要求“打包apk”）

- 在同一独立 v4 工程运行 `gradlew.bat assembleDebug --console=plain`：**BUILD SUCCESSFUL**。
- 桌面交付：`C:\Users\jx\Desktop\icu-v4-debug-20261113-0948.apk`，**4,910,203 bytes**，SHA-256 **`a5ca5123652ac899580a8fe1c2f786db34a98d6d14ea142d2974dfdf8ea0d121`**。
- aapt：package `com.example.icuapk`，versionCode `20`，versionName `2.6.1-env-care-fix`，minSdk 24 / targetSdk 34，application-debuggable。
- apksigner：**Verifies**，v2=true，1 signer；v1/v3/v3.1/v4=false。
- ZIP 完整性通过，`classes.dex` 存在；APK 内 `lanhu-live-state.js`、`lanhu-bridge.js`、`lanhu-first-phase.js`、主机控制页 `index.html` 与最终源码逐字节 MATCH；构建输出与桌面副本 `cmp` 一致且 SHA-256 相同。

## 未做与待验

**未安装、未 commit、未 push**。2026-11-12 的 `icu-v4-debug-20261112-1537.apk` 不包含本轮修改，不可用于本轮验收。Chrome/JVM/静态/APK核验不证明 Android WebView、真实 BLE 入队、回包或主机执行；需真机重点复核：仅保存后开关下发、无蓝牙及断连操作、完整/部分入队提示、四个弹窗无治疗时长、主机控制页治疗时长只读及其他五项功能定时。

## 同日续作：监护断连、历史趋势、三波形、提醒与治疗记录

- 仅修改同一独立 v4 工程。用户要求监护宝断开后心率/血氧/脉率/呼吸率保留断开前数值、检查治疗记录单三波形、被动提醒同一事件不反复；又要求历史趋势近5/30分钟/全程和六参数，实测 `monitorHistory` 仅 p=5 且全0；最后指出“是否打开”与 `recordEnabled=true` 不一致。
- **断连显示与报警分离**：`Am4100Manager.connectDevice()` 同设备重连保留最后标量，换设备清空；实时波形、帧缓冲与链路诊断仍重置。新增 `MonitorConnectionPolicy.isParamFresh()`，每参数须本次 protocol ready 后收到新有效帧才恢复报警。连接状态继续真实显示断开/重连中。
- **历史趋势**：新增 `MeasurementHistoryBuffer`，容量3600，按参数公平淘汰并抑制高频重复点，保留真实时间戳/数值；相同值至少60秒才留检查点，变化值至少间隔1秒，不生成补零或演示数据。`MonitorHistoryJson` 为桥唯一序列化出口，保留 p=0..6 与 double 值；H5 跳过未知参数、无效时间及 NaN/Infinity，不转0，并按时间排序。空态保留。**真实设备“仅p=5且全0”的唯一根因仍未由原始帧证实**：全局缓冲被高频参数挤占是已修结构风险，TEMP全零帧当前源码原本已过滤；真机仍须看原始返回，不宣称根治。
- **报告三波形**：`WaveformSampler` 对长会话全窗口分桶，按原先后顺序保留每桶最小/最大值；替代 PDF 只取尾段约400点的旧逻辑，避免前段尖峰丢失/尾段恒定呈直线。短数组原样复制，三通道和病例快照来源未改，协议帧偏移未改。
- **提醒**：`MonitorAlarmTracker` 按参数+高/低方向只提示新事件，持续不重复，真实恢复后再越界或换向才新报；无效/等待新帧不算恢复，另一参数新报警不重提旧报警，断连仍取消排队Toast，红色报警条保留。按钮主动Toast未做全局去重。BLE弹窗区分未恢复/正在展示/已确认集合，新链路断开原地更新，不关旧框重弹，不带出已确认旧事件。教程权限被动提示取消后同事件不被轮询重开，授权后重置；主动按钮反馈不受全局封禁。
- **治疗记录**：原生 `enabled` 恢复为 `entry.enabled`（纳入统计/“是否打开”），`recordEnabled` 同值，`featureOn` 独立表示设备实时状态；H5图标读取 `recordEnabled`，行title分别标明记录状态和设备状态；Canvas与PDF“开/关”也改用 `entry.enabled`，时段仍只在period列。
- 新增5个生产辅助类及5个测试类；`gradlew testDebugUnitTest` BUILD SUCCESSFUL，11类共 **56项，0 skipped / 0 failures / 0 errors**，覆盖七参数非零JSON、参数饥饿、重复点、断连保留/换设备清理、新帧新鲜度、报警事件、全窗口峰谷及三通道隔离。
- 用户随后明确要求“修改后直接打包apk”。`assembleDebug` BUILD SUCCESSFUL；桌面 **`C:\Users\jx\Desktop\icu-v4-debug-20261113-111421.apk`**，**4,912,615 bytes**，SHA-256 **`5ec8318ecc2294197ff04fa644ff4be725a0df10bb510f42b449ecd27cc7cb50`**；package `com.example.icuapk`，versionCode20/versionName `2.6.1-env-care-fix`，v2验签 Verifies、1 signer，ZIP通过，APK内 `lanhu-first-phase.js`/`lanhu-tutorial.js` 与最终源码 MATCH，桌面副本与构建输出逐字节一致。
- 按直接打包要求，本轮最终 Chrome/JS语法探针未重跑，不能把旧探针结果算作本轮通过；治疗探针已改预期但未执行。**未安装、未commit、未push**。真机BLE断连/重连、Android WebView、六参数真实趋势、实际PDF三波形、弹窗与治疗记录列仍待验，不宣称真机验收。此前0948 APK不含本轮续作。

## 同日主机定时续作：1 分钟被界面显示成最大值

- 用户反馈“主机发送1分钟的信号，app却接收到了直接打开最大值的信号”，并说明 `C:\Users\jx\Desktop\icu.apk.1 v4\icu.apk` 的蓝牙读取正常。本轮只把 Desktop 版作为只读基线对比，**没有覆盖或混用其源码，实际修改仍仅在 Documents 独立 v4 工程**。两版 `updateTimedSwitch/readRemainMinutes/normalizePresetMinutes` 等核心读取函数基本相同；发送仍是 JSON 数字 `value:minutes`，未发现 1 被字节序或进制转换成最大值的证据。
- **源码链路+真实H5页面探针复现的直接原因**：主机状态中的 `time_remaining:1` 会进入 `switchRemainingMinutes`，但 `getControlValue()` 给 H5 的五项定时功能只使用本地配置 `redTime/blueTime/...`；主机侧开启而本地配置为空时，H5 只收到“开”。`lanhu-live-state.js` 对无有效数字的“开”默认创建 99 分钟会话，因此修复前 Chrome 实测五项均显示 `98:59`。这属于“剩余分钟未传到H5+前端虚构默认值”，不是已证实的 BLE 原始帧解码错误；真实主机原始帧仍须真机复核。
- `BleManager.getControlValue()` 的红光/蓝光/雾化/负离子/紫外改为与 `getControlDurationText()` 一致，优先使用主机回报剩余分钟、缺失时回退本地配置。新时长成功入队后清除对应旧剩余缓存，防止旧最大值覆盖刚设置的1分钟；剩余分钟键增加当前舱位，避免双舱轮询时跨舱串倒计时。指令 JSON、Notify拼帧、帧字段和物理执行流程未改。
- `lanhu-live-state.js` 删除 `DEFAULT_THERAPY_MINUTES=99`：没有有效分钟或值无效时只显示“开”并清掉本地倒计时，不再生成99分钟；合法99分钟仍保留，65535等无效值不转默认99，关闭仍显示关。
- 修改文件仅 `BleManager.java`、`lanhu-live-state.js`；修改前备份在 `C:\Users\jx\icu-v4-probes-20261111\backup-20261113-pre-host-timed-control`，最终差异存档 `host-timed-control-final.diff`。
- 新探针 `C:\Users\jx\icu-v4-probes-20261111\host-timed-control-probe.js`：修复前 **12项仅4通过/8失败**，实际捕获 `98:59`；补齐缓存与双舱检查后最终 **14/14通过**，覆盖五项原生输出、1分钟倒数、无时长不虚构99、65535无效值、合法99、关闭、清旧缓存和按舱隔离。结果在 `host-timed-control-20261113/after-final/summary.json`。
- `host-mode-control-probe.js` 最终 **30/30通过**；`lanhu-live-state.js` 与探针 `node --check` 通过；`gradlew testDebugUnitTest` BUILD SUCCESSFUL，11类共 **56项，0 skipped / 0 failures / 0 errors**。
- 用户随后明确要求“打包apk”。`gradlew assembleDebug` BUILD SUCCESSFUL；桌面 **`C:\Users\jx\Desktop\icu-v4-debug-20261113-120209.apk`**，**4,912,758 bytes**，SHA-256 **`fb0c8d77755ea4f7e0b66bbf318737dc8a12e0ae437422072137bb76959f76ce`**；package `com.example.icuapk`，versionCode20/versionName `2.6.1-env-care-fix`，minSdk24/targetSdk34，application-debuggable。apksigner **Verifies**，v2=true、1 signer，v1/v3/v3.1/v4=false；ZIP无压缩数据错误、`classes.dex`存在，APK内 `lanhu-live-state.js` 与最终源码逐字节 MATCH，桌面副本与构建输出 SHA-256一致且 `cmp`一致。
- **未安装、未commit、未push**；旧桌面 `icu-v4-debug-20261113-111421.apk` 不包含此续作，不可用于本次修复验收。Chrome/JVM/静态/APK核验不替代 Android WebView、真实BLE、真实主机1分钟回包及双舱操作，真机待验不宣称根治。

## 同日续作：删除报告波形状态文字 + 断连六项数值全部回显

- 用户要求删除治疗记录单三波形右上角“无波形 · 仅趋势 14 点”等状态文字。`IcuDashboardView.drawPdfTemplateWave()` 已删除“已捕获波形 N 点 / 无波形 · 仅趋势 N 点 / 未捕获到波形”整段生成与绘制；真实波形、趋势替代曲线、“无信号”空态及监护页其他 `waveSourceText()` 均保留，未改波形采集或趋势数据。修改前备份在 `backup-20261113-pre-remove-wave-status`，最终 diff 只删除目标7行。
- 用户随后实测：监护蓝牙断开后右侧心率/血氧/脉率/呼吸率无残留，血压/体温有残留，要求六项都显示断开前数据。原生同设备标量保留与逐项新帧报警门槛此前已存在，但 H5 `writeMonitorValues()` 直接把空值/`--`写入 DOM，没有独立的最后有效值显示缓存；本轮只修显示与轻量桥，不猜改 AM4100 协议。
- `lanhu-first-phase.js` 新增六项共用的最后有效值缓存：断连时原生值为空或血压为 `--/--` 时回退各自最后有效值；连接中无效测量仍显示空态，不拿旧值冒充实时数据。`MainActivity.buildVitalsJson()` 增加 `deviceId`，H5 按设备隔离缓存，换监护仪清空旧设备回显值；同设备断连/重连保留，断开状态文字与既有报警新鲜度/断连不报警规则不变。
- 产品源码本轮只改 `MainActivity.java`、`lanhu-first-phase.js`，加上前一项的 `IcuDashboardView.java`。备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261113-pre-disconnect-vitals-retention`；最终差异：`monitor-disconnect-vitals-final.diff`。
- 新探针 `monitor-disconnect-vitals-probe.js` 修复前在真实监护页 Chrome mock 中复现四项掉成 `--`；初次探针另有一项初始血压预期写成空白而非实际 `--/--`，已修正探针。最终 **14/14通过**：四项被清、六项全清、连接中无效值、同设备断连、换设备不串值及第二台设备各自回显均覆盖，无运行时错误。结果：`monitor-disconnect-vitals-20261113/after/summary.json`。
- 既有侧栏/监护 Chrome 回归 **157/157通过**（mock 同步全量桥与轻量桥连接状态，未放宽产品预期）；数值/治疗静态 **88/88通过**；相机/数值/断连报警探针 **65/65通过**。后者原有两项仍断言已被用户后续需求取代的“30秒重复提醒/旧通知数组”，已更新为当前 `MonitorAlarmTracker` 事件去重断言，产品未恢复重复提醒。
- `lanhu-first-phase.js` 与探针 `node --check` 通过；`gradlew testDebugUnitTest` BUILD SUCCESSFUL，11类共 **56项，0 skipped / 0 failures / 0 errors**。
- 用户随后明确要求“打包apk”。`gradlew assembleDebug` BUILD SUCCESSFUL；桌面 **`C:\Users\jx\Desktop\icu-v4-debug-20261113-135217.apk`**，**4,912,574 bytes**，SHA-256 **`7a6164edc625338185d5bf96039c59bb151f0175094f9ad31b75acb655431ba6`**；package `com.example.icuapk`，versionCode20/versionName `2.6.1-env-care-fix`，minSdk24/targetSdk34，application-debuggable。apksigner **Verifies**，v2=true、1 signer，v1/v3/v3.1/v4=false；ZIP完整性通过、`classes.dex`存在，APK内 `lanhu-first-phase.js` 与 `lanhu-live-state.js` 和最终源码逐字节 MATCH，构建输出与桌面副本 SHA-256 相同且 `cmp` 一致。
- **未安装、未commit、未push**。旧桌面 `icu-v4-debug-20261113-120209.apk` 及此前包不包含“删除波形状态文字”和“断连六项全部回显”续作；135217 包包含当时全部主机定时、报告文字与断连回显修复。Chrome/JVM/静态/APK核验不替代 Android WebView、真实 BLE 断连/重连/换设备、真实主机回包和实际 PDF，真机待验不宣称验收。
