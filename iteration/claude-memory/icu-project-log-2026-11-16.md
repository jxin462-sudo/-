---
name: icu-project-log-2026-11-16
description: 独立v4教程图片/视频/PDF/文档自动封面已按用户要求打包桌面170315，JUnit83项、Chrome11项及APK26项核验通过，四独立开关/PDF/DOCX保留；未安装真机待验
metadata:
  node_type: memory
  type: project
  originSessionId: 03d745ab-8d7b-4e07-89c2-a1f0ae3b9a02
  modified: 2026-11-16T09:51:38.978Z
---

# icu.apk.1 v4 独立项目台账 2026-11-16

## 用户实测与工程范围

用户反馈重启后 ICU 状态页舱内温度、氧浓度、湿度、CO₂、红外体温、治疗时长均为 `--`；`dataDiag` 为 `notifyChunkCount=43`、`completeFrameCount=1`、`environmentAppliedCount=0`，`notifyBufferChars` 从 9448 增至 25435，`lastDropReason=buffer_overflow`，并明确指出 App 读取不到主机温度/氧浓度/CO₂/湿度四个传感器值。

实际修改仅在独立工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`。用户提供的可读工程 `...\icu.apk.1 v5(1)\icu.apk.1 v4\icu.apk` 全程只读比较，未覆盖、未混用源码/APK或验收结论。本轮按既定范围只修源码并跑 JUnit，**不打包、不安装**。

## 已证实的阻断机制与修复

- `notifyChunkCount` 持续增加而 `completeFrameCount/environmentAppliedCount` 几乎不动，同时缓冲不断增长，说明 Notify 已到达，但数据堵在 JSON 拼帧阶段，尚未进入四路环境字段采纳；不是先怀疑 `temp/o2/humidity/co2` 字段映射。
- 当前版与可读版的 `HostEnvironmentParser` 四路字段映射和量纲一致，因此未修改该解析器。
- 原 `BleManager.appendNotifyChunk()` 在外层帧无法收尾时只检查后续第一个 `{`。若损坏总表留下外层与 `data` 内层两个未闭合起点，第一个候选仍是残缺的 `data`，更后面已经完整到达的总表永远没有机会恢复；缓冲继续增长，最终 `buffer_overflow` 清空，可能重复阻断。
- 新增纯 JVM 辅助类 `HostNotifyFrameScanner`：从损坏起点之后逐个扫描 `{`，每个候选仍按原有 JSON 深度、字符串和转义规则收尾，必须能独立解析为非空 JSON 才作为恢复点；没有完整帧时继续等待，不伪造数据。
- `BleManager` 在不完整帧分支改调用 `findNextCompleteFrame()`；恢复后删除损坏前缀并继续走既有 `isHostFrame/parseStatus/HostEnvironmentParser` 主链路。原私有 `findFrameEnd()` 委托给同一扫描器，未改 `get_all_status` JSON、C304/C305、双舱串行轮询、无 zone 回包归属保护、字段量纲或 H5。
- 修改前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-notify-recovery\BleManager.java`。

## 修改文件与验证

1. `app/src/main/java/com/example/icuapk/BleManager.java`
2. 新增 `app/src/main/java/com/example/icuapk/HostNotifyFrameScanner.java`
3. 新增 `app/src/test/java/com/example/icuapk/HostNotifyFrameScannerTest.java`

新增4项回归覆盖：多个损坏起点后的完整环境帧、原本可用的单候选恢复、平衡但非法 JSON 后继续找完整帧、没有完整帧时等待及字符串内花括号/转义。把旧单候选行为提取出来时定向测试曾 **4项中2项失败**，改为逐候选扫描后通过；未隐瞒首轮失败。

最终 `testDebugUnitTest`：**BUILD SUCCESSFUL**，12个测试类共 **60项，0 skipped / 0 failures / 0 errors**。核心复现帧恢复后经既有环境解析器得到温度27.6、氧浓度21.5、湿度35.7、CO₂ 478。

本机没有连接 adb 设备，也未找到本次 `notifyChunkCount=43/25435` 对应的原始分片导出。因此已证实的是代码缺陷及其可确定性永久堵缓冲机制，**不能声称真机第一帧具体丢了哪些字节已由原始帧证实**。可读版也保留相同潜在单候选问题；没有真机对照日志，不能解释它为何在另一次实测中未暴露。

## 红外体温与治疗时长独立核对

- 红外体温不与四路主机环境帧共用一条完整数据来源：`lanhu-live-state.js` 优先读取 `IcuThermal.getMeasurement()` 的最新有效热帧平均值；重启后该内存测量为空时回退 `host.infraredTemp`。当前 `BleManager` 的 `ZoneEnvironmentState.infraredTemp` 只见声明、读取和清空，未见主机回包赋值；因此本轮拼帧修复**不能保证红外体温恢复**，需真机确认红外测温桥是否重新连接并收到有效热帧。
- 治疗时长走独立会话门槛与快照：重启后由 `loadTreatmentRecordsFromStorage()` 绑定当前进行中病例；三项必填为 petName/recordNo/ownerPhone。无进行中病例、必填不全、从未建立计时或没有可恢复活动快照时仍显示 `--`。已有按设备/舱位及最后活动快照的恢复路径，并在 protocol ready 后续算；本轮未获得实际病例存档和快照，未修改该逻辑，不能把治疗时长 `--` 归因于四路拼帧缺陷。

## 四路首帧延迟与 TX stickyError 续作

用户随后实测四路传感器要等待很久才显示，且主机链路留存 `stickyError=TX failed: writeCharacteristic returned false`，温度设置无 ack/回读、无法确认主机响应。本轮仍只修改上述独立 v4 源码，可读 v5(1) 工程未动；修改前备份为 `C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-host-latency-write\BleManager.java`。

### 已证实的 App 侧根因

- `readAllStatus()` 原先把 `get_main_version/get_ctrl_version/get_bt_ver` 三条版本查询排在环境总表前；当前舱四路值初次均为 null 时，`requestEnvironmentFallbackIfNeeded()` 又立即补排 `get_temp/get_o2/get_humidity/get_co2` 四条。首帧前存在不必要的串行排队，protocol ready 后还固定等待 500ms。
- `writeCharacteristic()==false` 的确定语义只是本地 Android 协议栈没有启动这次写入，不是主机拒绝，也不能证明报文已发出。旧代码在首选及备用写类型都 false 后，每 200ms 无限把同一队头重新排队，没有次数上限，后续状态回读可一直被堵住。
- `stickyError` 原先只在新连接 protocol ready 时清除；同一条此前未启动的指令后来实际写入，也不会清掉旧 TX 错误。新控制请求入队到实际写入之间还会保留上一次 `lastControlSent/Reply`，旧同名 ack 有冒充新确认的风险。
- 已核对 `resolveAwaitingCommand()`：控制回包可在队头错位时按命令名和舱位寻找自己的等待项，正常 ack 匹配不是本轮断点，未把它改成任意 Notify 都算确认。

### 源码修改

- `readAllStatus()` 改为当前舱 `get_all_status` 优先，三条版本查询保留但后排；首轮没有总表回答、10秒查询超时、明确不支持或强制回退证据时，不再因初始 null 立即补四条分项查询。protocol ready 后首次读取等待由 500ms 降为 100ms，800ms 双舱轮询及归属保护未改。
- 新增 `HostTransportPolicy.java`：首轮回退门槛和写入启动重试上限的纯 JVM 决策。两种写类型都明确 false 才累计一次，最多尝试5次；前4次按原200ms重排，第5次放弃该条，释放 `currentWrite/writing` 并放行后续队列。已启动但回执超时/不明的指令仍不物理重发。
- 用户控制最终未启动时保留明确错误、标记 `write_failed`，不登记虚假 sent/awaiting/ack，并尝试真实总表回读；普通后台查询失败不再留下容易被误认为温度设置失败的永久 stickyError。
- TX stickyError 与失败报文关联；同一报文后来实际启动写入才清除，无关轮询或其他控制成功不会误清。新用户控制从入队起清空上一请求的 sent/reply/确认命令及超时记账，实际启动后仍走原有真实回包确认链路。

### 修改文件与验证

1. 修改 `BleManager.java`。
2. 新增 `HostTransportPolicy.java`。
3. 新增 `HostTransportPolicyTest.java` 5项：初始 null 不提前回退、总表回答后缺值回退、总表超时回退、不支持/force 回退、写入启动重试边界。

定向 `HostTransportPolicyTest` 与完整 `testDebugUnitTest` 均 **BUILD SUCCESSFUL**；最终测试结果为 **13个测试类、65项，0 skipped / 0 failures / 0 errors**，`compileDebugJavaWithJavac` 已通过。第一轮 Notify 修复的60项也包含在这65项内。本轮没有首轮编译或测试失败。

没有本轮 Android 原始 GATT 回调/协议栈日志，因此只能确认 App 的排队、无限重排和错误留存缺陷，**不能把系统为何返回 false 唯一归因为 GATT 忙、前序回执异常或连接状态中的某一个**。JVM 测试也不证明真机首帧耗时或温度已被主机执行。

## 最新同类阻断实测的因果边界

用户再次报告 EM-150Vet-20 显示“已连接 · 数据回包超时”、`ready=false`，C305 handle=56 持续通知，缓冲9448→25435、`completeFrameCount=1`、`validHostStateCount=0`、`lastDropReason=buffer_overflow`，并在“发现服务中1/3/自动重连中”循环。指标与前次旧拼帧阻断相同，但本次未提供所测APK哈希/构建时间；本机 `adb devices` 仍无设备，无法核对已安装dex。

代码复核确认：`dataDiag.ready` 来自 `isDataReady()`，必须 `connected && protocolReady && lastValidHostStateAtMs>0 && dataPathState!=timeout`；所以有效状态为0时 ready=false 是结果，不代表C305未启用。`updateDataPathHealth()` 在协议就绪20秒无有效状态后只设置数据超时，不调用closeConnection。任何C305分片都会在 `handleCharacteristicChanged()` 更新 `lastNotifyAtMs`；持续通知会阻止35/70秒静默看门狗以“完全无数据”主动断链。因此“发现服务→自动重连”还需要实际GATT断开回调或其他连接动作解释，不能仅凭buffer_overflow把它归为同一原因。

若实测仍是此前已安装APK，它不含本次源码修复，旧单候选恢复缺陷仍完全符合这些指标。若确认是包含 `HostNotifyFrameScanner` 的新包仍有同样增长，当前逐候选算法会在缓冲中存在任何可独立解析的完整JSON时恢复；此时必须导出 `recentFrames/lastNotifyChunk` 和断连前后的 `status/newState`，判断原始流是否根本没有完整JSON、是否实际运行旧dex，以及物理GATT为何断开，不能继续无原始帧猜测。本次仅复核，未再改源码。

## 第三轮传输与重组续作

用户明确要求同时修改粘滞 TX false/未恢复 Status=0，以及 C305 重组无有效主机状态、溢出后不复位两项。仍只改独立 v4 源码。

- 新增 `HostNotifyReassembler.java` 和7项 `HostNotifyReassemblerTest`：无JSON起点垃圾立即清理、前导噪声剥离、多损坏起点恢复、残缺帧3秒超时复位、溢出硬复位，以及复位后下一条完整环境帧进入既有 `HostEnvironmentParser`。样例仍得到27.6℃/21.5%/35.7%/478PPM。
- `BleManager` 已真正接入重组器，断链清缓冲及计时起点；新增 `notifyResetCount/notifyBufferChars`，同一临界区串行更新分片和计数。删除旧 `notifyBuffer` 拼帧实现。
- 写类型改为 DEFAULT/WriteWithResponse 优先；删除一次 NO_RESPONSE 超时后永久跳过回执的逻辑，两种写类型均等待GATT回执，超时留300ms恢复窗口且不物理重发。控制诊断新增 `writeStatus/writeStatusAtMs/writeStatusPayload/writeStatusMatched`，新请求清旧分代，Status=0不冒充应用ack或物理执行。
- 服务发现只请求一次MTU；MTU/服务/描述符/写回执均过滤旧GATT，只有 `STATE_CONNECTED + GATT_SUCCESS` 才进入服务发现，并记录真实status/newState。
- 修改前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-transport-reassembly-v2\`。
- 定向及完整 `testDebugUnitTest` 均 BUILD SUCCESSFUL；最终XML实测 **14类73项，0 skipped/0 failures/0 errors**，Java编译通过。诊断版本 `v4-diag-20261116-9`。仍未打包/安装/commit/push，真机未验收。

## 交付审核4只读核查

只读打开 `C:\Users\jx\Desktop\优利特动物ICU监护App_功能测试记录_20261116.xlsx`，工作表“功能测试记录”的交付审核4共有10条不通过（行10/11/16/18/25/26/27/32/44/47），未改Excel。

- 行16/18/25/26/27：分别对应环境控制写入、湿度回传、主机BLE、Write Status、Notify重组；本轮已有对应源码修改，但真机结果仍待验。
- 行11：四路环境部分已改；红外体温仍依赖独立热帧，治疗时长仍依赖病例/快照，不能将整行冒称全部覆盖。
- 行10 PDF下载：`IcuDashboardView.java/MainActivity.java` 自2026-11-13后未改，虽已有 `acceptPdfDownloadRequest`、MediaStore非空写入/发布及状态回推，但审核所述 `seq:0`、三轮无文件并未由本轮修复或复测，不能记为已解决。
- 行32护理全部关闭：明确未修。`hostModeValues()` 仍仅四项且无 `no_set_mode`，现有实现还明确是各模式独立开关而非需求中的四选一/全部关闭。
- 行44生命体征统计：明确未修。心率/呼吸率仅检查数值>0，613/212等可进入统计；心电图仍由 `"ECG "+heartRate` 经 `parseFirstNumber` 复用心率做数值统计，血氧/呼吸波形同理，和审核现象一致。
- 行47预置教程：明确未修。`app/src/main/assets/lanhu/tutorial/manifest.json` 自2026-11-03未改，仍为 `items=[]` 且该目录只有清单，页面仍回退系统相册素材。
- 因此不能答复“审核4都已修改”；主机传输修改的73项JUnit也不覆盖上述PDF/WebView、护理UI、治疗统计和教程资产。

## 未做与下一步

**三轮均未 assembleDebug、未生成 APK、未安装、未 commit、未 push；现有已安装旧 APK 不会因源码修改自动恢复。** Chrome/JVM测试不替代 Android WebView与真实 BLE。下一轮真机应先记录 protocol ready 到首条总表实发/首个有效环境回包的墙钟时间，并观察 `completeFrameCount`、`environmentAppliedCount`、`notifyBufferChars`、四路实时值及 `controlReplyState`；温度设置必须区分“本地未启动”“已启动等待 ack”“收到匹配 ack”“真实状态回读确认”。若仍出现 TX false，导出包含 cmd/attempt 的通信日志和 Android GATT 回调日志；若 Notify 仍堵，导出 `dataDiag.recentFrames`、`lastNotifyChunk`，结合 `parseFailureCount/zoneRejectedCount` 判断系统性丢片或归属拒绝，不再猜字段。红外与治疗时长须分别检查热帧及病例/计时快照，不宣称同一修复已覆盖。

## PDF下载、护理全部关闭与DOCX教程续作

用户先明确要求“PDF 下载、护理全部关闭先改这两个”，随后补充“使用教程导入 word格式的 有些问题。浏览窗口数据溢出了 不能滑动。”及“docx也要可以导入”。仍只修改独立v4源码，修改前备份位于 `C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-review4-pdf-care-off\`；本轮没有打包、安装、commit或push授权。

### PDF下载状态链

- 不再把H5 action调用或同步 `accepted` 当作文件成功。`MainActivity.IcuNative.action()` 对 `pdf_download` 同步返回包含 `requestId/accepted/seq/phase/msg` 的受理回执，并只调度同一requestId；其他action保留原路由。
- 新增 `PdfDownloadRequestTracker`：单调requestId、新请求使旧请求失效、旧请求不能覆盖新状态，最终结果只发布一次。`IcuDashboardView` 依次回推 `queued/generating/publishing/success/error`，30秒watchdog只报告“未确认文件已发布”，不假称成功；watchdog按requestId隔离，旧请求结束不能取消新请求计时器。
- `copyPdfToDownloads()` 保留并核验非空复制、Android 10+ `MediaStore.Downloads` 的 `IS_PENDING=1→0` 发布及失败删除；只有非空复制和发布完成后才进入success。
- `lanhu-bridge.js` 保存 `window.__icuPdfDownloadDispatch`，区分 `missing_receipt/invalid_receipt/bridge_error/bridge_unavailable`；最后一轮明确把空回执归为 `missing_receipt`，避免JSON.parse空串造成误导。`lanhu-first-phase.js` 保存 `__icuPdfDownloadStatus`，queued和最终success/error提示，generating/publishing只更新诊断不刷Toast；旧打印 `stage=result/requestId=0` 保留。

### 护理no_set_mode与全部关闭

- 新增 `HostCareModePolicy`，五值为 `maternal_and_infant_care/Postoperative_care/Cardiopulmonary_care/Custom_mode/no_set_mode`。
- 主控页四模式下方新增独立“全部关闭”控件，真实路由 `host_mode_off→BleManager.setCareMode(no_set_mode)`；入队只显示“等待主机确认”，confirmed `no_set_mode` 后才显示“已全部关闭”，不将第五项塞进原四模式数组。
- `set_care_mode` 后发起 `get_care_mode`；连接/切舱及 `readAllStatus()` 也回读护理，且护理回读排在环境总表之后，不重新阻塞四路传感器首帧。支持独立回包与总表真实护理字段，缺字段不解释为全部关闭，非法值不改变确认状态。
- `pendingCareModes` 只表示等待，裸ok仅按实际等待命令归属，任意Notify不冒充确认；写入失败/控制超时只清匹配旧pending，不确认成功、不自动物理重发。confirmed revision跨重连单调，值变化或用户再次确认no_set_mode时推进；只有新revision的confirmed no_set_mode才清本舱四个本地enabled，重复旧回读不会反复清掉之后的新操作。
- 四模式独立功能开关语义保留：H5开关统一读取 `enabled`，不被 `active` 覆盖。源码、JVM和Chrome结果不等同真实主机已经物理执行。

### Word/DOCX导入与长正文滚动

- 原导入器/媒体库已列出DOCX MIME和扩展名，真实缺口是 `TutorialDocumentOpener.kindOf()` 在 `application/octet-stream` 等通用MIME下误判及查看器嵌套高度/滚动约束；未误称此前完全不支持DOCX。
- `kindOf()` 优先按 `.doc/.docx` 扩展名识别，含大写DOCX；真实DOCX从ZIP读取 `word/document.xml`，保留段落、tab、br/cr和XML实体。主XML解压上限4MiB、正文最多200000字并明确提示截断；解析失败不再把ZIP二进制退回纯文本。旧版 `.doc` 没有完整二进制Word正文解析器，不能冒称支持其正文解析。
- `lanhu-tutorial.css` 将卡片/body/pre约束为单一受限滚动区：卡片和body不溢出，只有pre纵向滚动；长无空白字段 `overflow-wrap:anywhere`，横向不溢出，保留 `touch-action:pan-y`。导入提示明确 `Word(doc/docx)`。

### 修改与验证结果

- 新增 `PdfDownloadRequestTracker.java`、`HostCareModePolicy.java` 及对应测试；新增 `TutorialDocumentOpenerTest.java`。修改 `MainActivity.java`、`IcuDashboardView.java`、`BleManager.java`、`TutorialDocumentOpener.java`，以及 `lanhu-bridge.js`、`lanhu-first-phase.js`、`lanhu-live-state.js`、`lanhu-fit.css`、`lanhu-tutorial.js`、`lanhu-tutorial.css`。不得回退工作树中此前第三轮 `HostNotifyReassembler`、GATT写入及诊断修改。
- 首次完整Gradle编译因 `confirmCareMode()` 重复定义局部变量 `commanded` 失败，删除重复声明后重跑 `testDebugUnitTest`：**BUILD SUCCESSFUL，17个测试类89项，0 failure/0 error/0 skipped**。四份修改JS `lanhu-bridge/lanhu-first-phase/lanhu-live-state/lanhu-tutorial.js` 均 `node --check` 通过。
- 新建真实页面探针 `C:\Users\jx\icu-v4-probes-20261111\review4-pdf-care-docx-probe.js`，输出在 `review4-pdf-care-docx-20261116\`。首轮24/30、第二轮29/30：失败主要来自探针在生产的 `lanhu-overlay.js` 覆盖mock后未采集真实Toast、静态函数截取终点错误及把预期error状态对象误判为探针错误；同时真实修正了空回执诊断分类。最终整组 **30/30**：下载按钮touch/click去重、同步受理、queued/generating/publishing/success/error、缺回执和桥异常、全部关闭pending/confirmed/布局/四开关独立、DOCX导入及查看器均通过。
- 教程实测DOM：card `clientHeight=scrollHeight=760`、body均为672，pre `clientHeight=672/scrollHeight=15163`，设置后 `pre.scrollTop=480` 且body保持0；pre宽1033且scrollWidth同为1033，关闭按钮仍可见。既有 `host-mode-control-probe.js` 重跑 **30/30**。

### 验收边界与未做项

Chrome只mock原生读取/回包并运行生产页面、桥和CSS，**不证明Android MediaStore已在公共Download生成真实文件，不证明真实BLE收到/执行no_set_mode，也不证明Android WebView触摸滚动已通过**。真机还需验证实际PDF文件及printStatus/requestId、set/get_care_mode和裸ok/回读/超时/重复回读、DOCX通用MIME导入和触摸滚动。生命体征统计、预置教程manifest、红外体温与治疗时长不在本轮新增修复范围；上述为打包前源码阶段验证，随后用户明确授权打包，结果见下节。

## 2026-11-16 16:01 APK打包交付

用户明确通知“打包apk”后，使用 `JAVA_HOME=C:\Users\jx\.jdks\ms-21.0.12` 执行 `:app:assembleDebug`，**BUILD SUCCESSFUL**（33 actionable tasks，5 executed/28 up-to-date）。

- 桌面交付：`C:\Users\jx\Desktop\icu-v4-debug-20261116-160138.apk`
- 大小：**4,919,628 bytes**
- SHA-256：**`de9e43ceee519b384308297585e6b21b88579fb5d1e46dcc2e3111bbbc9e5c9d`**
- 包名 `com.example.icuapk`，`versionCode=20`，`versionName=2.6.1-env-care-fix`，minSdk 24/targetSdk 34，Debug签名。
- `apksigner verify --verbose --print-certs`：**Verifies，v2=true**；签名者 `C=US, O=Android, CN=Android Debug`。
- `aapt dump badging` 包信息核验通过；ZIP `testzip()` 无CRC错误。
- APK内 `lanhu-bridge.js/lanhu-first-phase.js/lanhu-live-state.js/lanhu-fit.css/lanhu-tutorial.js/lanhu-tutorial.css` 共6份与当前源码逐字节MATCH；DEX包含 `PdfDownloadRequestTracker`、`HostCareModePolicy`、`acceptPdfDownloadRequest`、`get_care_mode`、`no_set_mode`。
- 桌面副本与 `app/build/outputs/apk/debug/app-debug.apk` `cmp`逐字节一致，SHA-256相同。

**仅打包，未安装、未commit、未push。** 2026-11-16 160138 之前的APK不包含本轮PDF分代状态链、护理no_set_mode全部关闭及DOCX查看器最终修改；真机公共Download文件、真实BLE回包和Android WebView触摸滚动仍待验，不因打包或桌面探针通过而宣称真机验收。

## 2026-11-16 16:32 删除护理全部关闭并重新打包

用户随后明确要求“把护理模式全部关闭的这项功能删掉再打包apk”，并特别强调“4个模式的独立开关按钮功能要保留”。本次仅删除额外的全部关闭功能，**四个模式各自的独立开关、保存配置、开启/关闭下发逻辑全部保留，没有改成四选一或联动关闭**。

- 删除H5 `#icu-host-care-off` 按钮、状态文字和对应CSS、`hostCareMode`状态读取；删除原生 `host_mode_off` 路由、`turnOffAllHostModes/sendAllHostModesOff`、confirmed revision清理四个enabled的逻辑及MainActivity回调。
- 产品源码不再包含 `no_set_mode/host_mode_off/hostCareMode/HostCareModePolicy`。删除仅为该功能新增的 `HostCareModePolicy.java` 和6项 `HostCareModePolicyTest.java`。
- 保留四模式 `set_care_mode`、四值过滤及通用 `get_care_mode` 回读；保留 `toggleHostMode/applyHostModeConfig(...false,true)/disableHostModeFunctions/setHostModeEnabled`，四个开关仍按各自enabled渲染，不被active覆盖。PDF分代下载、DOCX识别/正文解析与滚动修复未改。
- 删除前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-remove-care-off\`（8份文件）。
- 完整 `testDebugUnitTest`：**BUILD SUCCESSFUL，16类83项，0 failure/0 error/0 skipped**；减少的6项正是已删除的全部关闭专属策略测试。
- 既有四模式 `host-mode-control-probe.js`：**30/30**，验证独立enabled/active语义、四个fake开关及各自action、连续刷新不回跳。
- 新增 `remove-care-off-probe.js`：即使mock旧原生状态仍带 `hostCareMode/no_set_mode`，真实主控页也不会生成全部关闭入口；最终 **8/8**。首轮7/8仅因探针把自身脚本字符串计入可见文本，排除script/style后通过。产品页面offRows=0、offActions=0，四开关交替enabled状态和各自动作正确，41次状态读取无错误。

### 最新APK

- 桌面交付：**`C:\Users\jx\Desktop\icu-v4-debug-20261116-163220.apk`**
- 大小：**4,918,493 bytes**
- SHA-256：**`fe9576ed07e5b1a1ebd625feb6a257fcf14c4c9611fba33a0812edcdefd35f11`**
- `assembleDebug` BUILD SUCCESSFUL；包名 `com.example.icuapk`，versionCode20/versionName2.6.1-env-care-fix，Debug签名，apksigner **Verifies/v2=true**。
- ZIP CRC正常；6份H5/CSS与当前源码逐字节MATCH；桌面副本与构建产物 `cmp` 一致。
- APK DEX/H5中 `HostCareModePolicy/no_set_mode/host_mode_off/hostCareMode` 均确认不存在；`toggleHostMode/disableHostModeFunctions/setHostModeEnabled/get_care_mode`、PDF `PdfDownloadRequestTracker/acceptPdfDownloadRequest` 和 `TutorialDocumentOpener` 均确认保留。

**最新验收应使用163220包；160138包仍含全部关闭功能，不适用于本次删除验收。** 本次仍未安装、未commit、未push；真机四开关、PDF公共Download及DOCX WebView触摸滚动待验。

## 2026-11-16 教程上传资料自动封面续作（未打包）

用户提出“上传的视频图片和文档可以做显示封面吗？”。本轮仅修改独立v4的 `TutorialMediaLibrary.java`、`lanhu-tutorial.js`、`lanhu-tutorial.css`，未改护理、BLE、PDF下载、DOCX正文解析、生命体征、红外或治疗时长。

- 图片继续使用按比例抽稀的自身缩略图，视频继续使用 `MediaMetadataRetriever` 代表帧；原生最长边480px、JPEG质量60及32条缓存上限保留。
- PDF新增真实首页封面：`file://` 直接打开私有导入文件，`content://` 通过 `ContentResolver.openFileDescriptor` 获取可寻址描述符，`PdfRenderer.openPage(0)` 按最长边480px渲染；页面、renderer和descriptor均关闭，不支持/损坏时返回空并由H5回退，不把该能力冒称为Word/Excel真实首页渲染。
- Word/DOCX、Excel、PPT、文本等没有可用位图时显示带文档类型与真实文件名的自动类型封面；长文件名使用纯文本、任意换行和2/3行截断，不注入HTML、不撑破卡片。
- H5不再无条件清除文档图片。后台清单有真实 `thumb` 时直接使用；只有图片/图文才能把原 `src` 直接当图片，PDF和视频地址不再误塞进 `img`。坏图稳定回退类型封面，同一地址不随全局状态刷新反复加载，新封面地址仍可重试。
- 首轮封面Chrome为10/11，唯一失败发现DOCX无位图结果未缓存，随状态刷新重复调用原生解码；已把空结果也按src缓存，并在权限或素材指纹变化时清缓存允许重试，桥异常不缓存。最终新增 `tutorial-cover-probe.js` **11/11**：图片、视频、PDF mock首页、DOCX长文件名、后台指定封面、坏图回退/防HTML注入及六卡边界全部通过。
- `node --check` 通过；完整 `testDebugUnitTest` **BUILD SUCCESSFUL，16类83项，0 failure/0 error/0 skipped**。修改前3份源码备份于 `C:\Users\jx\icu-v4-probes-20261111\backup-20261116-pre-tutorial-covers\`，探针与输出位于 `tutorial-cover-probe.js` / `tutorial-cover-20261116\`。

**本轮没有获得新的打包指令，未执行assembleDebug、未安装、未commit、未push；桌面163220及此前APK均不包含本封面续作。** Chrome/mock的占位图片不能替代Android `PdfRenderer`、真实视频取帧、系统文档缩略图和WebView显示验收，真机导入图片/视频/PDF/DOCX后的封面及点击打开仍待验。

## 2026-11-16 17:03 教程封面APK打包交付

用户随后明确通知“打包apk”，已按授权将包含上述封面续作的当前独立v4源码执行 `:app:assembleDebug`，**BUILD SUCCESSFUL**（33 actionable tasks，5 executed/28 up-to-date）。

- 桌面交付：**`C:\Users\jx\Desktop\icu-v4-debug-20261116-170315.apk`**
- 大小：**4,919,415 bytes**；SHA-256：**`416438d07f7f9e7a5d7824d6750c571786abb5f42d9f464d4e9d86df35bafec6`**。
- 包名 `com.example.icuapk`，versionCode20/versionName2.6.1-env-care-fix，minSdk24/targetSdk34，Debug签名。
- `apksigner verify --verbose --print-certs`：**Verifies，v2=true**，签名者 `C=US, O=Android, CN=Android Debug`。
- 独立 `py -I` 内容核验 **26/26**：ZIP CRC正常；6份H5/CSS与当前源码逐字节MATCH；DEX保留 `TutorialMediaLibrary/pdfFirstPage/PdfRenderer/getFrameAtTime`、四独立开关、`get_care_mode`、PDF下载与DOCX符号；教程H5保留doc-cover、坏图回退和空结果缓存；DEX/H5仍无 `HostCareModePolicy/no_set_mode/host_mode_off/hostCareMode`。
- 桌面副本与 `app/build/outputs/apk/debug/app-debug.apk` 经 `cmp` 逐字节一致，二者SHA-256相同。构建前同源码完整JUnit16类83项0失败、封面Chrome11/11及JS语法通过。

**170315是包含教程封面续作的最新交付包；163220及此前包不含封面功能。未安装、未commit、未push。** Android真机的图片/视频/PDF/DOCX导入后封面显示、PDF首页真实渲染、视频取帧、WebView布局与点击打开仍待验；四模式独立开关、PDF公共Download和DOCX触摸滚动等既有真机事项也仍需验收，不宣称真机通过。

## 2026-11-16 主机蓝牙协议级仿真（未改产品源码）

用户询问“你能模拟主机蓝牙信号，进行app功能测试吗？”。本轮在外部目录 `C:\Users\jx\icu-v4-probes-20261111\host-ble-simulation-20261116\` 新建可重复运行的JVM仿真器，**没有修改独立v4产品源码、协议或build.gradle，没有新增测试依赖，也没有重新打包/安装/commit/push**。只读对照工程仍未修改。

- 仿真器直接加载真实编译产物 `compileDebugJavaWithJavac/classes`，反射调用生产 `BleManager.handleCharacteristicChanged()`，把构造的C305 Notify UTF-8字节、任意分片、多帧、噪声及异常数据送入真实 `HostNotifyReassembler→parseStatus→HostEnvironmentParser` 链路；假 `BluetoothGatt` 只记录C304写入并触发真实写回调，不做射频I/O。
- 外部轻量Android桩实现同步 `Handler.post()`、内存SharedPreferences和必要GATT类型；`postDelayed()`不自动执行，看门狗由探针显式触发。因此验证的是生产协议/状态逻辑，不是Android系统定时调度或系统BluetoothGatt。
- 15组场景共 **93/93**：版本/裸ok不冒充有效状态；分片总表恢复27.6℃/21.5%/35.7%/478PPM；全0有效；多形态与非法值；左右舱隔离及缺zone拒绝；噪声/非法JSON/溢出恢复；单包多帧；四种护理及非法回读；护理写队列/pending/confirmed；五项剩余分钟和run_time；GATT断连清实时值但保留治疗时长；70秒静默完整复位；20秒全局停滞及有效0帧恢复；10秒单舱超时及恢复；旧GATT/错误特征值隔离。
- 首轮探针为77/79，两处均为探针预期修正而非产品改动：非法JSON在重组层被丢弃，不应期待进入 `diagParseFailureCount`；`65535`按现有归一逻辑只显示“开”，不是“开65535分钟”。修正后79/79，增加断连场景后93/93。首次harness编译还因android.jar没有自定义fake GATT方法而改为反射调用记录/清空写入方法。
- 执行 `cleanTestDebugUnitTest testDebugUnitTest` 强制重跑，**BUILD SUCCESSFUL，16类83项，0 failure/0 error/0 skipped**，未把UP-TO-DATE当作重跑证据。真实H5护理独立开关Chrome/mock **30/30**、治疗倒计时 **14/14**。本轮汇总 **220/220，0失败**。
- 总报告：`host-ble-simulation-20261116\verification-summary.json`；协议报告：`summary.json`；JUnit报告：`junit-summary.json`。运行入口为 `run.py --project <独立v4路径>`，脚本与项目源码分离。

**验收边界：** JVM Android桩不等于真实Android BluetoothGatt；未执行真实扫描/权限/服务发现/C304描述符写入/C305射频Notify/物理断连重连，Chrome/mock也不等于Android WebView真机。真实端到端仍需另一台支持BLE外设模拟的Android设备或真实 `EM-150Vet-20` 主机。桌面最新APK仍为 `icu-v4-debug-20261116-170315.apk`，本轮未生成新包，220/220不能冒称真实BLE或真机验收。
