---
name: icu-project-log-2026-11-11
description: 独立 v4 多轮源码与打包台账；最新修复侧栏滚动局部刷新重叠及监护底栏高亮，模拟验证通过、真机待验
metadata:
  node_type: memory
  type: project
  originSessionId: 6b7c1e85-b880-4ea7-94c3-15f1b2c79c3d
  modified: 2026-11-11T09:49:25.317Z
---

# icu.apk.1 v4 独立项目台账 2026-11-11

## 范围与约束

仅修改独立工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不与 Desktop 下任何旧 ICU 项目比较或混用源码、版本、APK、提交及验收结论。沿用用户约定：只改源码；未编译、未运行 Gradle/JUnit、未生成或复制 APK、未安装、未 commit、未 push。未修改 BLE 协议、轮询或尚未真机验收的数据通路，也未读取或披露签名凭据。

## 今日完成的源码修改

### 1. LOGO 与登录账号

- 新增 `app/src/main/assets/lanhu/lanhu-account-brand.js`，由 `lanhu-bridge.js` 在公共动态模块区注入；在 `DOMContentLoaded`、`icu-native-state` 和 1.5 秒刷新时读取 `IcuNative.firstPhaseState()`。
- 顶栏账号按 `isLoggedIn()` 和 `state.accountName` 动态显示，未登录显示“未登录”，没有写死 `test` 或其他用户名。原生登录链路已核对：`AccountStore.login()` 保存登录页输入的账号，`MainActivity.applyAccountUser()` 将其同步到 `IcuDashboardView.accountName`。
- 9 份原来静态写“黄医生”的 HTML 改为 `<span class="text_4" data-account-live="name">--</span>`；主页和主机控制页原有动态绑定保留，共 11 页有账号标记。账户菜单 `account_menu` 动作未删除。
- 公共页及设置页 LOGO 通过现有 `IcuNative.galleryThumbnail(uri)` / `TutorialMediaLibrary.thumbnail()` 获取缩略图 data URI，不再让 WebView 直接加载 `content://`。
- LOGO 按 URI 和 `loading/loaded/error` 状态幂等加载，URI 变化时令旧回调失效；同一 URI 多轮刷新不重复解码。成功显示图片并隐藏文字占位，无图或失败恢复 `logoName/LOGO/+`，不伪报成功。缩略图按需获取，未放入周期性全量状态 JSON。
- `lanhu-hospital-info.js` 删除与公共模块竞争的旧 `renderLogo()` 和直接 `content://` 加载；医院信息输入及 LOGO 上传动作保留。

### 2. 患者空字段统一“待录入”

- `lanhu-first-phase.js` 与备用路径 `lanhu-live-state.js` 对 `petName/species/sex/age/ownerName/ownerPhone/doctor/note` 使用患者专用显示函数：空值及旧占位值显示“待录入”，真实值原样显示，数值 `0` 不误判为空。
- 病历编号 `000001`、就诊时间、单例自动选中及列表逻辑不变；未全局替换通用 `--`。
- 临时编辑表单仍读取原始病例字段，不把“待录入”写回患者数据，不虚构患者资料。
- `blankPatient()` 补上 `sex: ""`，修复临时病例复制时丢失性别字段的问题。

### 3. PDF 下载状态、缺项反馈和真实落盘条件

- `MainActivity.LanhuNativeBridge.action()` 在 `pdf_download` 排入 UI 生成队列前调用 `IcuDashboardView.acceptPdfDownloadRequest()`；桥方法返回仍只表示动作受理，不表示下载成功。
- 受理时立即检查当前病例及 `petName/species/ownerName/ownerPhone`。无病例或缺项时直接写最终 `error`、具体原因和耗时并 Toast，返回 `false`，跳过后续 UI 生成任务；校验通过才写 `running`，返回 `true` 继续生成。`generatePdfReport()` 保留二次校验，覆盖排队期间病例变化。
- `printStatus` 保留 `seq/ok/msg`，新增 `phase/fileName/uri/elapsedMs`；序号统一由 `updatePrintStatusLocked()` 在 `zoneStateLock` 内递增。受理、处理中和最终成功/失败分离，普通打印回调使用 `result`，不冒充 PDF 下载成功。
- `downloadPdfAction()` 成功和异常均回推状态，覆盖 `IOException`、`SecurityException` 等异常；空异常消息回退异常类名。只有源 PDF 存在且非空、实际复制字节数大于 0、输出流关闭并完成下载发布后才报告 `success/ok=true`，同时返回真实文件名、URI 和耗时。
- Android Q+ 使用 `MediaStore.Downloads` 与 `IS_PENDING=1 → 0`，失败清理半成品；旧版本检查目标文件非空并删除失败的部分文件。`copyFile()` 返回实际字节数、try-with-resources 关闭流并 flush。

## 验证结果（不等于真机验收）

- 5 份变更 JavaScript 的 `node --check` 全部通过。
- 独立探针目录 `C:\Users\jx\icu-v4-probes-20261111`，不在应用源码目录。真实主页/设置页 HTML 加模拟 `IcuNative` 的 Chrome 探针：主页 29 项、设置页 7 项全部通过，`errors=[]`，共 36 项。
- 探针覆盖：LOGO data 图片可见且 `thumbnailCalls=1`，多轮刷新不重复加载；账号由 `test` 动态切换为 `loginInputA`，两页均跟随；7 个可见空字段统一“待录入”；编号、就诊时间、单例选中和账户菜单保留；临时编辑可打开且原始空值不被占位污染；真实资料替换占位，年龄 `0` 和性别 `雌性` 保留。
- `check-java-static.js` 最新复跑 27 项全部 `pass:true`：括号平衡、桥受理顺序、缺项拒绝不进入 UI 队列、状态字段、唯一序号递增点、复制后才成功、MediaStore pending 生命周期和失败清理等。
- 浏览器和静态检查不能替代 Java/Android 编译、真实图片解码、MediaStore 落盘或设备验证。当前空患者缺项路径已在源码层改为立即最终失败，但资料完整病例仍是同步生成模型；原约 80 秒延迟的全部原因未由静态排查证明，不能宣称已根治。

## 待真机复核

1. 实际上传图片后公共区和设置区 LOGO 可见，失败时保留真实占位。
2. 登录页输入不同账号后，主页、设置页及其他公共页顶栏同步为实际输入账号。
3. 空患者字段统一“待录入”，编辑表单仍可打开并保存真实资料。
4. 缺项下载及时推进 `printStatus.seq` 并显示具体失败；补齐必填项后，下载目录出现可打开的非空 PDF，状态 URI/文件名与实物一致。
5. 用 `elapsedMs` 复核原约 80 秒延迟；如仍慢，继续区分 UI 排队、PDF 生成和 MediaStore 复制耗时。

**Why:** 本次实测问题涉及界面真实状态、登录输入一致性和下载完成语义，不能把桥调用返回、任务受理或静态探针通过当成真实文件下载成功或真机验收通过。

**How to apply:** 后续继续只在该独立 v4 工程处理；保留动态账号、显示层占位与原始病例模型分离、真实复制发布后才成功及缺项二次校验。等待用户另行通知后再编译或打包，真机结果返回前不宣称验收通过。关联 [[icu-project-log-2026-11-10]]。

---

## 追加：2026-11-11 11:26 会话中断与接手补完（5 项新计划）

### 交接状态

- Claude Code 会话 `6b7c1e85-b880-4ea7-94c3-15f1b2c79c3d`（名称 `fix-icu-ble-issues`，cwd `C:\Users\jx`）在 11:26:38 因 5 小时用量配额 429 中断，配额 13:55:36 重置。中断时正在执行 11:09 生成的计划 `crispy-wishing-octopus.md`（5 项），已落盘第 1、3 项源码，验证与台账均未做。
- 由 WorkBuddy 从断点接手，继续严格限于独立 v4 工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不与旧 ICU 工程比较或混用。只改源码；未编译、未运行 Gradle/JUnit、未生成或复制 APK、未安装、未 commit、未 push。

### 五项计划落地情况

1. **治疗计时持久化**（Claude Code 已落盘，本轮核对）：`BleManager` 用 `icu_ble_settings` 按「设备+舱」存带版本快照，`getLiveTreatmentMinutes()` 为唯一计算入口，`release()`／进程被杀不误判结束；`IcuDashboardView` 在病例加载、新建、结束流程分别绑定／结束会话。恢复只恢复显示与记录，不补发 `set_run_time`、不回放物理输出。
2. **六页签导航计时**（本轮补完）：新增 `lanhu-nav-timing.js` 并由 `lanhu-bridge.js` 注入；`navigateRoute()` 与 `handleNodeTextAction()` 合并到同一入口，生成 `icuNavId/icuNavAt/icuNavTo` 随整页跳转传递；目标页校验规范化路由与该页专属首屏节点，`requestAnimationFrame` 记录首次正确渲染，记录 `route_mismatch`／`first_frame_missing`／`timeout`，结果只保留最近一次，经 `IcuNative.log()` 输出起止、目标、结果、耗时。未改成 SPA，未动摄像监控或 BLE 生命周期。
3. **湿度只读**（Claude Code 已落盘，本轮核对）：湿度卡改「只读」且保留 `host.controls.humidity` 实测绑定；`control_humidity` 仅提示并记日志；`set_humidity`／`setHumidity` 及 custom mode 湿度下发均已移除；`get_humidity`、显示、范围解析、舱位缓存保留。补偿设置页的温度／氧浓度／CO₂ 补偿入口未被误伤（工程原本不存在「湿度补偿」文案）。
4. **控制确认层级**（本轮补完）：`BleManager` 新增逐控制项确认快照（`requestedAtMs/writtenAtMs/repliedAtMs/updatedAtMs/expected/hostValue` 与 `pending/acknowledged/confirmed/unconfirmed/contradicted`），pending 清除前保留终态；GATT `Status=0` 与 setter `ret:ok` 不再被表述成主机已执行。`applyEnvironmentValueResponse()` 仍只认 `get_*` 实测。H5 经 `lanhu-live-state.js` 显示「等待回读／超时未确认／主机已回读」，数值卡在 `dataState=timeout` 或 `dataError` 非空时标陈旧但继续显示最后真实实测值。
5. **数据停滞诊断**（本轮补完）：`BleManager` 在 Notify 分片、完整帧、丢弃分支、解析失败、zone 归属拒绝、实测采纳、有效状态入口增加有界计数与最后丢弃原因，保留最近 8 条截断原始帧；两套 host JSON 同步加入 `dataDiagVersion = v4-diag-20261111-1`、`dataDiag`、`controlConfirmations`。维持单舱 10 秒／全局 20 秒阈值，任意 Notify、写成功或 setter 回包不喂活数据健康；未新增自动重连、未改 800ms 双舱轮询、未新增命令格式、未清空健康数据伪造刷新。**未宣称 262–589 秒停滞根因已修复**，需真机导出原始帧与阶段计数后再定位。

### 额外修复

- `BleManager.java` 原 `private long dataPathState = "waiting";` 与 `getDataPathState(): String` 及 9 处字符串赋值类型冲突，属编译级错误；改为 `private volatile String dataPathState = "waiting";`。因项目约定不编译，该错误此前一直未被发现。

### 验证结果（不等于真机验收）

- 独立探针目录 `C:\Users\jx\icu-v4-probes-20261111`，不在应用源码目录；改码前 4 个源文件已备份至 `backup-20261111-pre-diag/`。
- 静态检查 `check-20261111.js`：**40/40 通过**（3 个 Java 文件括号平衡、类型冲突排除、两套 JSON 字段一致、诊断计数点、控制终态、湿度残留、导航共用入口、6 个 JS 语法）。
- Node vm 运行时探针 `runtime-probe-20261111.js`：**8/8 通过**（普通加载无噪音、成功路径、路由不符、首屏节点缺失、超时、有界结果、结构化日志）。
- 桌面 Chrome + 模拟 IcuNative `chrome-diag-probe.js`：**15/15 通过**（超时时 3 个数值卡标陈旧、控制提示区分等待回读与主机已回读、提示可见、实测值不被设定值覆盖、湿度只读、无 JS 运行时错误、湿度卡无「设置」、补偿设置未误伤、六页专属首屏节点均存在）。
- 合计 **63 项**静态／模拟检查通过。静态与桌面浏览器检查不能替代 Java/Android 编译、真实 BLE 或真机性能验证。

### 待真机复核（沿用计划 Verification）

1. 开始治疗后强停重开 App、重启手机，匹配病例／设备／舱位的时长按墙钟继续；切设备、切舱、新建病例不串值，结束治疗不再增加，全程无物理命令补发。
2. 六个页签分别多次点击，导出点击到正确首帧的原始耗时、失败与超时记录，再判断单次是否 ≤1000ms；桌面 Chrome 不作 Android 性能验收证据。
3. 湿度无设置弹窗、无 `set_humidity` 流量，仍随主机真实值变化；温度、冷光等只有 GATT 写入或 setter 回包时显示待确认／未确认，收到有效回读后才标「主机已回读」。
4. 复现 EM-150Vet-20 停滞，导出 `dataDiagVersion`、分阶段计数、丢弃原因及原始帧；停滞时须在现有阈值内出现明确错误而非长期 `ready=true`。取得证据前不宣称根因已修复。
5. 编译／打包／安装／commit／push 仍等待用户另行通知。

**Why:** 会话因配额中断时验证与台账均未做，不能把源码落盘或静态探针通过当成任务完成或真机验收通过。

**How to apply:** 后续继续只在该独立 v4 工程处理；保留治疗计时持久化、导航计时单一入口、湿度只读、setter 与实测分离、控制确认终态与有界诊断。会话恢复后先读本台账，避免重复修改已完成项。关联 [[icu-project-log-2026-11-10]]。

---

## 追加：12:08 第二轮真机实测反馈的判定与源码修复

### 版本判定（先于一切改码）

用户约定「不打包，等另行通知」，而本轮源码在 11:49 才改完，工程内 APK 仍是 11/6 23:42 与 11/7 12:42 的旧产物，`build/` 目录 mtime 仍为 11/7。因此这轮真机实测**对应尚未包含 11/10–11/11 源码修改的旧 APK**。按计划第 11 行的既定约定，以当前源码为准，不把旧包行为误判成新源码缺陷、不重复修改。新增的 `dataDiagVersion` 与界面版本标记正是用来在下次打包后消除这一歧义。

### 八项反馈逐条判定

| # | 反馈 | 当前源码状态 | 本轮动作 |
|---|---|---|---|
| 1 | `set_temp(27.6)`/`set_cold_light` 真实 GATT 写入 Status=0、写入队列停滞已恢复；主机无 ack/属性回读；轮询清空 `lastError` | 无回读属主机行为，源码已有 `controlConfirmations` 标注「等待回读/超时未确认」，不得宣称闭环；**但 `lastError` 确实被 800ms 轮询入队（3043）与任意 Notify 帧（4125）清空，且 `host.lastError` 在所有 H5 文件中均未被读取** | 修错误留存与 H5 明文 |
| 2 | C305 通知持续但分帧重组后无可用环境状态，`appendNotifyChunk/findFrameEnd/isHostFrame` 「实际未生效」 | 三个函数确实在调用链上（4088→4109→4164/4167），无 `protocolReady` 门槛，仅 UUID 与旧 GATT 两道硬门槛；拼帧/JSON/zone 归属有多处丢弃分支。上一轮已加分阶段计数与原始帧缓存，但 H5 未展示，真机无法导出证据 | 把计数、丢弃原因、版本标记暴露到 H5；不改轮询、不加自动重连、不新增命令格式 |
| 3 | 停滞 5–10 分钟界面与连接状态页仍「已连接/就绪」，无超时/丢帧提示 | **源码里 `linkWatchdogRunnable` 每 800ms 周期调用 `updateDataPathHealth(now)`，单舱 10 秒/全局 20 秒阈值完整，`ready` 判定依赖 `dataPathState`**；旧包不含该逻辑。但 `host.dataError` 只在数值卡 `title` 里，`host.ready` 完全未被 H5 消费，`host.dataDiag` 未展示 | 补 H5 明文：连接状态页显示「数据通路异常/数据回包超时」+ 诊断版本；浮层显示错误与阶段计数 |
| 4 | 湿度可显示但最长 589 秒不更新；治疗记录中仍有采集的历史值 | 根因同 #3。另核实治疗记录显示值取 `BleManager` 当前实时缓存而非采样快照，历史病例由 `seedHistoricalTreatmentStats()` 注入硬编码演示值（湿度 58%、温度 31.6℃ 等） | 只补陈旧标注；历史值语义属产品决策，本轮未改，列为待确认 |
| 5 | 控制后 ICU 状态页温度仍 27.6℃（与设置前一致），冷光两页均无回读 | **源码刻意不让 setter 回包/`ret:ok` 冒充实测值**（`applyEnvironmentValueResponse` 只认 `get_*`），这是正确保守行为；无主机回读时不得出现「设置→回传→刷新」闭环 | 不重复改；H5 已标注确认层级 |
| 6 | `hostModes` 仅 4 项、无「全部关闭」，`assets` 无 `no_set_mode/全部关闭`，台架锁定母幼模式；4 选 1 互斥本轮未重复操作；4 个模式参数配置入口存在但未逐项保存下发 | **协议 `set_care_mode` 值域经 `isSupportedCareMode()` 校验仅 4 值，无 off/none/close**；工程与历史台账均无 `no_set_mode` 约定 | **未实现**：不凭空新增协议命令格式。待用户提供协议值，或确认用「`Custom_mode` + 逐条关闭现有功能开关」实现。互斥切换与参数独立设置为待真机验证项 |
| 7 | 7 项上下限均可修改并持久化（心率 60-120 等） | 正常 | 未改 |
| 8 | 哨兵 -1 误报未修，alarms 常挂 5 条：心率/脉率/血氧/呼吸率「-1 低于下限」+ 体温 0.0℃「低于下限 35.0℃」 | **源码确实存在**：`collectMonitorAlarms()` 用裸 `-1` 的 `getXxxValue()`，`addMonitorAlarm()` 只拦 `NaN` 与 `enabled=false`；体温 `"0.0"` 由全零 TEMP 帧 `data[1]=0,data[2]=0` 拼出并进入显示/告警/历史；`hasVitals()` 是整体级 OR 保护，不生效 | 修复 |

### 本轮源码修复（5 个文件、16 处补丁，改前已备份）

- `Am4100Manager.java`：全零 TEMP 帧直接丢弃；新增 `isValidTempText()`，`getBodyTempText()` 三级回退全部走有效性判断，杜绝 `0.0℃` 进入显示、告警与历史曲线。
- `IcuDashboardView.java`：新增 `isValidMonitorAlarmValue(limitIndex, value)`，拦 `NaN`、`-1` 哨兵及体温 `<= 0`；`addMonitorAlarm()` 与 `isOutsideLimit()` 共用，实时告警、趋势卡片高亮、PDF 报告口径一致。
- `BleManager.java`：新增 `stickyError`/`stickyErrorAtMs`；`setError()` 写入留存副本，`getLastError()` 在即时错误之后回退 sticky；800ms 轮询入队与任意 Notify 帧**只清 `lastError`、不清 sticky**；仅新连接 `markProtocolReady()` 解除；`buildDataDiagJson()` 增加 `stickyError`/`stickyErrorAtMs`。
- `lanhu-live-state.js`：新增 `diagnosticHintText()`，浮层显示诊断版本、数据通路错误、留存主机提示、最近丢弃原因与「分片/完整/解析失败/归属拒绝/实测采纳/有效状态」计数；陈旧判定纳入 `ready=false`；徽标改为多行有界显示。
- `lanhu-ble-panel.js`：`statusText()` 在 `dataError` 或超时时追加可见明文，并在连接状态页输出 `诊断: <dataDiagVersion>`，用于判定 APK 是否包含本轮诊断。

改前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261111-pre-feedback\`（5 个文件）。补丁脚本 `apply-20261111-feedback-patch.js` 要求每处精确命中一次，否则整文件不写回。

### 验证结果（不等于真机验收）

- `check-20261111-feedback.js` **38/38**：4 个 Java 文件括号平衡、哨兵过滤口径、sticky 只在协议就绪解除、两处清空点不含 sticky、诊断与版本露出、6 个 JS 语法、上一轮成果回归。
- `runtime-panel-probe.js` **7/7**：从真实 `lanhu-ble-panel.js` 提取 `statusText()` 在 Node vm 执行，正常连接不追加错误、停滞时显示明文与诊断版本、无明细时仍有超时兜底。
- Chrome + 模拟 IcuNative **21/21**（含本轮新增 6 项：版本标记、数据通路明文、留存写入错误、丢弃原因、阶段计数、多行徽标）。
- 回归：`check-20261111.js` **40/40**、`runtime-probe-20261111.js` **8/8**。合计 **114 项**静态/模拟检查通过。
- 未编译、未运行 Gradle/JUnit、未生成或复制 APK、未安装、未 commit/push。

### 待真机 / 待确认

1. **打包后先看界面上的 `诊断: v4-diag-20261111-1`**，确认安装包含本轮诊断；再复现停滞，导出浮层里的分阶段计数、最近丢弃原因与 `dataDiag.recentFrames`，判断卡在拼帧、JSON 校验、zone 归属还是环境采纳。取得证据前不宣称 262–589 秒停滞根因已修复。
2. 确认 `alarms` 不再常挂 5 条误报，真实越界值仍能正常报警，报告与页面口径一致。
3. 确认写入失败/主机拒绝提示在后续轮询与 Notify 帧到达后仍可见，直到新连接协议就绪才解除。
4. 确认数据停滞时连接状态页与浮层出现明确超时/错误文案，数值卡标陈旧且仍显示最后真实实测值。
5. **待用户决策**：「全部关闭」模式的协议值，或是否用 `Custom_mode` + 全开关 off 实现；模式参数独立设置与 4 选 1 互斥切换仍待逐项真机验证。
6. **待用户决策**：治疗记录/历史病例中的硬编码演示值（湿度 58% 等）是否移除，以及实时缓存值是否应改为按采样时刻保存快照。
7. 编译/打包/安装/commit/push 仍等待用户另行通知。

**Why:** 这批实测来自旧 APK，若不先做版本判定就会把旧包行为当成新源码缺陷并重复改码；而 `-1` 误报与 `lastError` 清空是源码里确实存在的问题，必须独立于 APK 版本修复。

**How to apply:** 后续继续只在该独立 v4 工程处理；保留哨兵值不参与告警、错误留存与 H5 明文、诊断版本可见。会话恢复后先读本台账，避免重复修改已完成项。关联 [[icu-project-log-2026-11-10]]。

---

## 13:02 第三轮真机实测反馈的判定与源码修复（治疗记录 18 项）

### 版本判定

顶层 APK 仍是 2026/11/6 23:42，`app\build\outputs\apk\debug\app-debug.apk` 仍是 2026/11/7 12:42，`build/` mtime 未变；本轮未编译/打包/安装/commit/push。反馈里的 `enabled 全 false`、体温写入 `0℃`、样本极少等仍属旧 APK 行为；**但字段语义、体温取值、采样与统计口径、H5 列渲染必须在当前源码中核对并修复**。诊断版本从 `v4-diag-20261111-1` 升到 `v4-diag-20261111-2`，供下次打包后区分安装包。

### 反馈逐条判定

| # | 反馈 | 当前源码状态 | 本轮动作 |
|---|---|---|---|
| 1 | 18 项 `enabled` 均 false、体温项写入 0℃ | `treatmentsToJson()` 的 `enabled` 是 `treatmentFeatureOn()`（功能此刻真实状态），`recordEnabled=entry.enabled` 才是「纳入统计」勾选，`includeInReport` 是「是否录入报告」。三个布尔量含义不同；`TREATMENT_TEMPLATES` 18 项中 14 项默认纳入统计、4 项默认否。全 false 更接近旧 APK 的旧字段语义。0℃ 由全零 TEMP 帧拼出，第二轮已修 | 补 `featureOn` 显式字段并保留 `enabled` 兼容别名；无效值不再进样本与统计 |
| 2 | 「是否打开」列渲染时段文本，列显示与底层状态不一致 | H5 用 10 列 grid：8 个 span 在列 1/2/3/4/7/8/9/10，2 个 img 在列 5/6，视觉上没错位；但 `renderTreatments` 按 `spans[j]` 位置写值，任何按 DOM 顺序读取的探针都会把 `spans[4]`（时段文本）当成「是否打开」列，语义无法核对 | 每列打 `data-icu-col-field`，改为按字段查表写值；图标列写 `data-icu-state/data-icu-text/alt/title`；行上导出 5 个状态属性 |
| 3 | 「报告录入」入口存在、`includeInReport` 全 true，理疗项该列仍 `--` | 旧 APK 占位；当前源码把两列图标改成显式布尔状态 | 理疗项也输出「是/否」，不再停在 `--` |
| 4 | 统计失真：`sampleCount=1`/波形 2/血压 19、`avg=max=min`、体温 26/27.2/25.1 | **`getBodyTempText()` 第三级回退用 `objectTemp`（0x20 物体/环境温度）当体温，26℃ 上下正是环境温度**；`TreatmentEntry.record(NaN, display)` 会给非 `--` 显示值伪造 `sampleCount=1`；`parseFirstNumber` 把 `-1/0.0/0/0` 占位当有效数值；单点样本必然 avg=max=min。`seedHistoricalTreatmentStats()`/`seedEntry()` 写死 31.6℃/37.1℃ 与 `sampleCount=6`，当前源码已无任何调用（死代码） | 移除环境温度回退；新增 `isValidTreatmentSample()`；`record()` 拒绝 NaN/<=0；单点样本不给最高/最低并标注「样本不足」；读档清洗历史脏样本；整体移除演示值代码 |

### 额外发现（阻断级，Chrome 探针暴露）

`patientValue()` 只定义在 `installPatientTempStore()` 内部，`renderCommon()` 在没有 `[data-patient-live]` 节点的页面（含治疗记录页）会抛 `ReferenceError: patientValue is not defined`，`render()` 整个中断，`renderTreatments` 根本不会执行。首次运行治疗记录页探针 17 项只过 2 项，错误即此。已把 `patientValue` 提升到 IIFE 顶层并删除内部重复定义，修复后 17/17。

### 本轮源码改动（4 个文件，19 处补丁 + 1 处阻断修复）

- `BleManager.java`：`DATA_DIAG_VERSION` → `v4-diag-20261111-2`。
- `Am4100Manager.java`：`getBodyTempText()` 只保留体温/耳温两级回退，不再把 `objectTemp` 当体温；新增 `getObjectTempText()` 单独暴露物体温度。
- `IcuDashboardView.java`：`MIN_RELIABLE_SAMPLES=2`；`treatmentHighText/LowText` 单样本返回 `--`；`TreatmentEntry.record()` 拒绝 NaN/无穷/<=0；`treatmentFeatureOn()` 与采样入口共用 `isValidTreatmentSample()`（体温限 30–45℃）；`treatmentsToJson()` 输出 `featureOn/recordEnabled/includeInReport/statsReliable/statsNote`；读档新增 `sanitizeLoadedTreatmentEntry()` 按有效样本重算 sum/high/low/sampleCount；删除 `seedHistoricalTreatmentStats()`+`seedEntry()` 死代码。
- `lanhu-first-phase.js`：`patientValue` 提升到顶层；新增 `TREATMENT_COL_FIELDS`/`TREATMENT_SPAN_FIELDS`/`setTreatmentField()`/`setTreatmentRowState()`；表头与行单元格按语义字段绑定；表格写值改为按字段查表；`setTreatmentChecks()` 写显式布尔状态；平均值单元格带样本不足提示。

改前备份：`backup-20261111-pre-treatment\`、`backup-20261111-pre-render-fix\`。补丁脚本 `apply-20261111-treatment-patch.js`、`apply-20261111-render-fix.js` 均要求精确命中一次，否则整文件不写回。

### 验证结果（不等于真机验收）

- `check-20261111-treatment.js` **39/39**（含 5 个 JS 语法、4 个 Java 括号平衡、体温/采样/统计/H5 字段口径、前两轮回归）。
- 新增 `treatment-table-probe.js`：用真实 `lanhu_6zhiliaojilu/index.html` + 模拟 `IcuNative` 在桌面 Chrome 渲染，**17/17**。
- 全量回归：`check-20261111-feedback.js` 38/38、`check-20261111.js` 40/40、`runtime-probe-20261111.js` 8/8、`runtime-panel-probe.js` 7/7、`chrome-diag-probe.js` 21/21。合计 **170 项**静态/模拟检查通过。
- 未编译、未运行 Gradle/JUnit、未生成或复制 APK、未安装、未 commit/push。

### 待真机 / 待确认

1. 打包后先确认界面 `诊断: v4-diag-20261111-2`，再验治疗记录页 10 列语义、理疗项「报告录入」显示「是」、体温不再出现 26℃ 环境温度。
2. 确认单样本时最高/最低为 `--` 且平均值带「样本不足(n次)」提示；确认读档后旧脏样本被剔除。
3. 确认 `patientValue` 修复后其余 5 个页签首屏仍能正常渲染（六页签计时结果不受影响）。
4. **待用户决策**：「全部关闭」模式的协议值，或是否用 `Custom_mode` + 全开关 off 实现。
5. **待用户决策**：实时缓存显示值是否改成按采样时刻保存快照（本轮未做，只清掉了无效样本与演示值）。
6. 编译/打包/安装/commit/push 仍等待用户另行通知。

**Why:** 第三轮反馈里「列语义不一致」本质是 DOM 位置与语义没有绑定，「体温失真」本质是物体温度通道被当体温、占位值被当样本；两者都是当前源码可独立修复的缺陷，不能因为实测来自旧 APK 就一律搁置。跨作用域 `patientValue` 是会让治疗记录页完全不渲染的阻断错误，必须一并修。

**How to apply:** 后续只在该独立 v4 工程处理；保留三布尔量语义、采样有效性过滤、语义列绑定与诊断版本可见。会话恢复后先读本台账，避免重复修改已完成项。

---

## 13:28 第四轮：样本必填 / 删除释放残留 / 病例编号 / ICU 标识

用户反馈：`在新建样本宠物名和病例编号， 联系电话时必填项，以及删除和释放后的数据好像有残留，病例编号的逻辑好像也有问题你检查一下。左边区域的样本信息框中的ICU标识每次新建样本位置都不一样，你统一改到信息框的左上角`

**版本判定不变**：工程内 APK 仍是 `app-debug_20261106_2342.apk`（11/6 23:42）与 `app\build\outputs\apk\debug\app-debug.apk`（11/7 12:42），`build/` mtime 仍是 11/7 12:42:27。真机实测仍属旧 APK 行为，但下面四项是当前源码里确实存在的缺陷。诊断版本 `v4-diag-20261111-2` → **`v4-diag-20261111-3`**。

### 四项判定与源码修复

| 反馈 | 当前源码根因 | 处理 |
|---|---|---|
| 新建样本三项必填 | H5 `saveFromModal()` 直接把 9 个输入框写进本地档案，无任何校验；原生 `showEditPatientDialog()` 用 `cleanInput(input, fallback)`，空输入还会回退到旧值；页面写回 `optNonEmpty` 忽略空值但不拦截缺项 | H5 新增 `REQUIRED_PATIENT_FIELDS`/`missingRequiredPatientFields()`、`data-required` 标记、弹窗错误条，缺项不保存不关窗不写回；原生接管 `AlertDialog` positive 按钮（`setOnShowListener` + `getButton().setOnClickListener`），缺项 Toast 且不 dismiss；`syncTempPatientFromWebLocked()` 在 `resolveWebPatient` 之后校验三项，拒绝时仍 `invalidate()` 刷新画布 |
| 删除/释放后数据残留 | `confirmDeletePatient()` 只 `cases.remove()` + `loadPetScopedData()`；该 petId 名下门限/护理模式/时段/摄像头快照/主机模式配置的 SharedPreferences 键、`monitorSnapshots` 波形留档、BLE 治疗计时绑定、当前波形缓冲都没清；`treatment_new/finish` 不通知 H5 重拉列表 | 新增 `purgePetScopedData(petId)`（按 `_pet_<id>` 前缀清全部私有键 + 按 `zone\|petId` 清波形留档）；删除时先 `finishTreatmentSession(recordNo)` 解绑定时，删后清档 + `resetSessionWaves()`；结束治疗时重置波形 + `savePetScopedData()`；`treatment_new/treatment_finish` 与原生编辑保存都调 `activity.notifyLanhuStateChanged()` |
| 病例编号逻辑 | `nextCaseNumber()` = `max(cases.size()+1, max(caseNo)+1)`，删掉最后一条后同一个号会再发一次；H5 `saveFromModal()` 有 `p.caseNo = p.recordNo`，用户改「病历编号」会把系统号一起改掉；原生写回也允许页面覆盖 `caseNo` | 新增按舱持久化 `KEY_NEXT_CASE_SEQ = "next_case_seq"`，发号取 `prefs.getInt(seqKey,1)` 与历史最大值后 `putInt(next+1).commit()`，删除不回退；H5 删掉 `p.caseNo = p.recordNo`；原生移除 `patient.caseNo = optNonEmpty(...)`，`caseNo` 只由 `nextCaseNumber()` 生成 |
| ICU 标识位置每次不同 | 蓝湖导出的 6 个槽位 CSS 不一致：`.image_2/.image_4..7` 有 `margin-left:151px`，`.image_3` 没有；新建样本换槽位后标识就跳到不同位置。原生 Canvas 也把 ICU 按钮画在 `r.right - dp(58)` | H5 `renderList()` 调 `pinIcuBadges()` 给 `img.image_2..7` 打 `data-icu-badge`，CSS 统一 `order:-1 / margin:0 10px 0 0 / position:static / align-self:flex-start`，固定到信息框左上角；原生改为 `icuLeft = r.left + dp(12)`、`r.top + dp(12)`，宠物名右移 `dp(50)` 避让 |

补充确认：7 个主页面的 `image_2..image_7` 都是同一组 30×16（第二个 32×16）ICU 徽章，只有第一个槽是蓝色（选中态），其余为白色，因此可以统一打标；`lanhu_84lianjiezhuangtai` 的 `image_2` 是 34×18 且没有侧栏容器，不受影响。

### 本轮源码改动（4 个文件，18 处补丁 + 1 处补漏）

- `BleManager.java`：诊断版本 → `v4-diag-20261111-3`。
- `IcuDashboardView.java`：`KEY_NEXT_CASE_SEQ`、`isMissingRequiredField()`、`nextCaseNumber()` 持久化自增、`purgePetScopedData()`；`syncTempPatientFromWebLocked()` 必填校验与 caseNo 只读；`confirmDeletePatient()` 清档；`showEditPatientDialog()` 接管保存按钮；`finishCurrentTreatmentLocked()` 清波形；`treatment_new/finish` 通知页面；Canvas ICU 按钮改左上角。
- `lanhu-first-phase.js`：必填字段表/判定/标记/错误条/保存拦截；去掉 `p.caseNo = p.recordNo`；`pinIcuBadges()`；导出 `pinIcuBadges/requiredFields/missingRequired`。
- `lanhu-fit.css`：ICU 徽章统一定位、必填星号、错误条样式。

改前备份：`backup-20261111-pre-patient\`、`backup-20261111-pre-patient-followup\`、`backup-20261111-pre-patient-followup2\`。补丁脚本 `apply-20261111-patient-patch.js`、`apply-20261111-patient-followup.js`、`apply-20261111-patient-followup2.js`；三个旧探针的版本断言同步升到 `-3`。两处补漏：必填拒绝写回时仍 `invalidate()` 刷新画布；`KEY_NEXT_CASE_SEQ` 登记进 `ACCOUNT_SCOPED_KEY_PREFIXES` 保持账号隔离语义。

### 验证结果（不等于真机验收）

- 新增 `check-20261111-patient.js` **51/51**；新增 `patient-sidebar-probe.js`（真实 ICU 状态页 + 复制页面 assets + 模拟 `IcuNative` + 桌面 Chrome）**18/18**：6 个徽章 `order=-1 / marginLeft=0 / relLeft=0 / beforeName=true`，缺项拦截不写回、caseNo 保持 `000121` 而 recordNo 改成 `CUSTOM-9`。
- 全量回归 9 个探针合计 **239 项全通过**：`check-20261111.js` 40、`check-20261111-feedback.js` 38、`check-20261111-treatment.js` 39、`check-20261111-patient.js` 51、`runtime-probe-20261111.js` 8、`runtime-panel-probe.js` 7、`chrome-diag-probe.js` 21、`treatment-table-probe.js` 17、`patient-sidebar-probe.js` 18。
- 探针本身修正两处断言而非源码问题：运行时 `lanhu-overlay.js` 会接管 `window.IcuToast`（改为页面就绪后包一层记录器）；周期性 `render()` 本来就回推一次状态（改为比较「保存动作有没有新增写回」）。
- 未编译、未运行 Gradle/JUnit、未生成或复制 APK、未安装、未 commit/push。

### 待真机 / 待确认

1. 打包后先确认界面 `诊断: v4-diag-20261111-3`，再验三项必填在 H5 与原生弹窗都生效、缺项不落库。
2. 删除/结束后确认左舱样本列表、门限、护理模式、波形都不带上一只宠物的旧值；连续「新建→删除→新建」时病例号不重复、不回退。
3. 确认 7 个页签的 ICU 标识都在信息框左上角且位置一致，宠物名不与徽章重叠。
4. **待用户决策（沿用）**：「全部关闭」模式的协议值，或是否用 `Custom_mode` + 全开关 off 实现。
5. **待用户决策（沿用）**：实时缓存显示值是否改成按采样时刻保存快照。
6. 编译/打包/安装/commit/push 仍等待用户另行通知。

**Why:** 这四项都是源码层可独立修复的问题，不能因为实测来自旧 APK 就搁置；其中病例号复用会让病历归档出现重号，删除残留会让新样本读到上一只宠物的门限与波形，属于数据正确性问题。

**How to apply:** 后续只在该独立 v4 工程处理；保留必填三处校验、按舱自增序号、样本私有存档清理、ICU 徽章统一打标与诊断版本可见。会话恢复后先读本台账，避免重复修改已完成项。关联 [[icu-project-log-2026-11-10]]。

---

## 13:56 用户授权打包：首次真编译 + 编译级错误修复 + APK 交付

用户消息：`你打包成apk吧` —— 此前「不编译/不打包」的约定由此解除，**只打包，未安装、未 commit/push**。

### 构建环境与必要修正

- 工程独立 v4：`...\icu.apk.1 v4\icu.apk`，**没有**套用桌面旧 ICU 工程（`C:\Users\jx\Desktop\icu.apk\icu_verify_all.mjs`，H5 在 `lanhu/v2/`）的一键脚本，避免混用源码/产物。
- 工具链现成：`gradle/wrapper/gradle-9.4.1-bin` 已缓存、`D:\AndroidSdk` 有 `platforms/android-36.1` 与 `build-tools/36.0.0`、JDK `C:\Program Files\Android\Android Studio\jbr`（OpenJDK 25）。`gradle.properties` 里 `org.gradle.java.home` 已指向该 jbr。
- **`local.properties` 的 `sdk.dir` 指向不存在的中文用户目录** `C:\Users\吉成思\AppData\Local\Android\Sdk`（`Test-Path=False`），而 `ANDROID_HOME=D:\AndroidSdk` 有效 → 已备份原文到 `C:\Users\jx\icu-v4-probes-20261111\backup-local.properties-original` 并改成 `sdk.dir=D\:\\AndroidSdk`。这是本地构建配置（文件头注明不入库），保留修正以免下次再失败。
- PowerShell 工具安全策略禁止 `cmd.exe` 与 `Add-Type`；构建改用 PowerShell 原生 `& .\gradlew.bat :app:assembleDebug`，APK 内容校验改用零依赖 Node ZIP 解析，签名用 `D:\AndroidSdk\build-tools\36.0.0\apksigner.bat verify`。

### 首次编译暴露的编译级错误（静态检查漏网）

`compileDebugJavaWithJavac FAILED`：`Am4100Manager.java:719 错误: 已在类 Am4100Manager 中定义了方法 getObjectTempText()`。第三轮新增 `getObjectTempText()` 时，源码 `getAmbientTempText()` 之后原本已有一处同名 getter → 两处完全相同的定义。此前 239 项静态/模拟检查只做 Java 括号平衡与 JS 语法，**查不出重复方法**，只有真编译能发现。

- 修复：保留 693–696 行带注释「物体/环境温度单独暴露」的那处，删除 719–721 行重复处。脚本 `apply-20261111-dedup-fix.js`，备份 `backup-20261111-pre-dedup-fix\`。
- 防回归：`check-20261111-patient.js` 新增两条 —— 扫描四个 Java 文件里**类成员级**（行首恰好 4 空格）public 方法签名、参数名规范化后查重（匿名内部类的 `run()/onClick()` 缩进更深，不算重复）；`getObjectTempText()` 定义数必须恰好 1。通用检查第一次因把匿名内部类计入而误报，改用缩进过滤后 53/53。

### 构建结果

- `.\gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL in 44s**（33 tasks，6 executed / 27 up-to-date），日志 `C:\Users\jx\icu-v4-probes-20261111\gradle-build.log`。
- 产物 `app\build\outputs\apk\debug\app-debug.apk`，**4,900,658 字节**，debug + 工程统一 `icu` 签名；`apksigner verify` → `Verifies`、v2 scheme = true、Number of signers = 1（v1/v3 为 false 属正常，AGP 用 APK Signature Scheme v2）。
- 交付副本：`C:\Users\jx\Desktop\icu-v4-debug-20261111-1356.apk`，SHA256 `E6DA7F616BF0545D871E9774C3F8A489DFD380A1E272CF33C35FE0CAFD0DF9CE`。
- 新增 `verify-apk-content.js` **20/20**：直接解析 APK central directory，确认 `assets/lanhu/lanhu-first-phase.js` 含 `REQUIRED_PATIENT_FIELDS`/`pinIcuBadges`/`TREATMENT_COL_FIELDS` 且不再有 `p.caseNo = p.recordNo;`、`lanhu-fit.css` 含 `data-icu-badge`/`icu-patient-error`、4 个 `classes*.dex` 含 `v4-diag-20261111-3`/`next_case_seq`/`purgePetScopedData`/`isMissingRequiredField` 且不含旧 `-2` 常量。**证明产物不是旧缓存**。
- 打包后全量回归 10 个探针 **261 项全通过**（40+38+39+53+8+7+21+17+18+20）。

### 待真机（现在可以开始了）

1. 安装后先确认连接状态页 `诊断: v4-diag-20261111-3`，确认是新包。
2. 按第四轮 4 项验收：三项必填、删除/结束后无残留、病例号不重复不回退、ICU 标识在左上角且位置一致。
3. 沿用前几轮待办：数据停滞诊断帧导出、告警误报、控制回读闭环、`patientValue` 修复后其余 5 个页签首屏。
4. **待用户决策**：「全部关闭」协议值 / `Custom_mode` 方案；实时缓存是否改采样时刻快照。
5. 未安装、未 commit/push；如需 release 包或覆盖安装请另行说明。

**Why:** 长期「只改源码」约定下积累的多轮改动从未真正编译过，静态探针无法覆盖 Java 语义错误（重复方法、类型冲突）；这次授权打包第一次暴露并修掉了它，同时用产物内容校验确认改动确实进了 APK。

**How to apply:** 后续继续只在该独立 v4 工程处理；每次改码后若用户允许，优先跑一次 `assembleDebug` 再跑探针，不要只靠静态检查。`local.properties` 的 SDK 路径修正保留。会话恢复后先读本台账。关联 [[icu-project-log-2026-11-10]]。

---

## 14:25 第五轮：ICU 右上角 / 删诊断浮层 / 治疗计时双闸 / 设置页导航文字 + 重新打包

用户连续四条需求：① ICU 标识从左上角改到**右上角**并打包；② 删掉页面右下角的诊断版本信息浮层；③ 治疗时长必须**连上主机蓝牙后**才开始，未录入信息的样本不能计时；④ 设置页「医院信息」导航点击后文字变成标识符、「仪器状态」导航文字波动。诊断版本 `v4-diag-20261111-3` → **`v4-diag-20261111-4`**（连接状态页仍显示，右下角浮层已删）。

### 逐项判定与源码修复

| 反馈 | 根因 | 处理 |
|---|---|---|
| ICU 标识改右上角 | 第四轮用 `order:-1 + margin:0` 排到行首；蓝湖六个槽位的父元素 `margin-left` 本身有 5px/19px 差异，flex 方案只能保证「相对父元素」位置一致，绝对位置仍会漂 | H5 改为**绝对定位**：`.icu-sidebar-card{position:relative}`，徽章 `position:absolute;top:10px;right:12px;width:30px;height:16px;margin:0`，六个徽章统一钉在卡片右上角；原生 Canvas 改 `icuLeft = r.right - dp(12) - dp(42)`，宠物名回到 `r.left + dp(18)` 不再避让 |
| 右下角诊断浮层 | `lanhu-live-state.js` 的 `renderConfirmationHints()` 每轮创建/更新 `#icu-control-hint`（`position:fixed;right:8px;bottom:8px`） | 移除浮层创建与可见文本，改为只把诊断结论写入**不可见**的 `body[data-icu-control-hint]` 供探针/排障；同时移除遗留节点；数值卡 `data-icu-stale` 陈旧标记保留 |
| 治疗时长提前计时 | `bindTreatmentSession(recordNo,true)` 在**病例创建/加载**时就 `deviceSessionStartedAt = now` 起算，完全不看蓝牙状态；`createNewTreatmentRecordLocked()` 新建的是空白病历（`petName/ownerPhone` 为空）却立刻 `bindTreatmentSession(created.recordNo,true)`；`get_run_time`/`set_run_time` 回包也能绕过 | 新增**双闸** `treatmentClockAllowed()` = `treatmentSessionActive && treatmentInfoComplete && connected && protocolReady && 病例号非空`；新病例锚点全清零，只在「协议就绪」或「信息补齐且已连」时由 `resumeTreatmentClockIfAllowed()` 落到当前时刻；未连主机时恢复的存档只定格不续涨；缺项样本 `getTreatmentTimeText()` 返回 `--`、`getTreatmentMinutesValue()` 返回 0，主机 run_time 回读/回执也受门控。病例侧新增 `hasCompleteTreatmentInfo()`（复用三项必填判定），新建/H5 写回/原生编辑保存/删除后都调 `bindTreatmentClockToCurrentCase()` 重算资格 |
| 设置页导航文字 | `lanhu-account-brand.js` 的 `logoSlots()` 用宽泛选择器 `.text-wrapper_1 > .text_5` 猜 LOGO 槽；**设置页左侧菜单的白色药丸恰好也是这个结构**（文字是「医院信息」，其他页是「LOGO」），于是被误标 `data-org-logo-slot="common"`，`restorePlaceholder()` 每 1.5s 把菜单文字改写成 `logoName`/`LOGO` →「变成标识符」；与 `settings-nav` 的 DOM 重排相互竞争 →「文本波动」 | `logoSlots()` 只认文字为 `LOGO`/`+`/空或带 `data-org-logo-text` 的节点；新增 `isSettingsNavPill()` 按祖先文本（含「医院信息/仪器状态/管理员设置」）二次排除；显式 `[data-org-logo-slot]` 槽不受影响 |

### 改动文件（6 个，18 处精确补丁）

`BleManager.java`（7 处：版本 -4、资格字段/方法、显示门、三参 bind、协议就绪起算、两类 run_time 门控）、`IcuDashboardView.java`（6 处：Canvas 右上角、`hasCompleteTreatmentInfo`、三参绑定、新建/H5 写回/原生保存/删除后重算）、`lanhu-fit.css`（2 处）、`lanhu-first-phase.js`（注释）、`lanhu-live-state.js`（浮层）、`lanhu-account-brand.js`（LOGO 槽）。补丁脚本 `apply-20261111-followup3.js`，备份 `backup-20261111-pre-followup3\`。

### 验证与打包结果

- 新增 `check-20261111-followup3.js` **32/32**（双闸、右上角、浮层、导航修复、JS 语法、Java 括号与重复方法防回归）；新增 `settings-nav-probe.js` **15/15**：真实 81/82 设置页 + 模拟 `IcuNative`（带 `logoName=ICU_LOGO_2026.png`），4 个采样点跨 1.5s 定时刷新，菜单文字始终是设计稿标签、药丸不再被标成 LOGO 槽、LOGO 占位框仍正常。
- 旧探针断言同步更新：左上角→右上角（含 Chrome 实渲染相对卡片右边缘偏移）、浮层可见→不可见属性、版本 -3→-4、APK 内容校验。全量回归 **12 个探针合计 319 项全通过**（静态 40+38+39+53+32=202，运行时 8+7+21+18+17+15=86，APK 内容 31）。
- 真编译：本轮 PowerShell 会话需要先设 `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`（否则 `gradlew.bat` 报 `JAVA_HOME is not set`，退出码 9009）。设置后 `:app:assembleDebug` → **BUILD SUCCESSFUL in 45s**。
- 产物 `app\build\outputs\apk\debug\app-debug.apk` **4,901,181 字节**；`apksigner verify --verbose` → `Verifies`、v2=true、Number of signers=1；`verify-apk-content.js` **31/31**，确认 CSS 右上角定位、浮层删除、`isSettingsNavPill`、dex 内 `v4-diag-20261111-4`/`treatmentClockAllowed`/`hasCompleteTreatmentInfo` 都进了产物且不含旧 -2/-3 常量。
- **交付副本**：`C:\Users\jx\Desktop\icu-v4-debug-20261111-1425.apk`，SHA256 `86AF8606F0259910A938DAFFFB8ECD0F05A0C32CF5F84EDBC566E166B900C072`。旧包 `icu-v4-debug-20261111-1356.apk` 已过时（左上角 ICU + 右下角浮层），不要再用。
- 只打包，**未安装、未 commit/push**。

### 待真机 / 待确认

1. 确认六个样本槽位的 ICU 标识都在信息框右上角且位置一致、不与宠物名/操作区重叠；原生侧栏同样确认。
2. 确认页面右下角不再出现诊断信息框；连接状态页仍可读到 `诊断: v4-diag-20261111-4`。
3. 确认未连主机蓝牙时治疗时长显示 `--`；连上后从 0 开始；新建空白样本即使连着蓝牙也不计时，补齐三项后才起算。
4. 确认医院信息/仪器状态等设置页导航文字稳定，不再出现 LOGO 标识符或跳动。
5. **待用户决策（沿用）**：「全部关闭」协议值 / `Custom_mode` 全关方案；实时缓存是否改采样时刻快照；是否要 release 包、是否安装、是否 commit/push。

**Why:** 治疗时长是临床记录字段，把「病例创建时间」或「空样本」当治疗起点会直接污染病历；导航文字被定时器改写是公共模块用宽泛选择器误命中页面结构，属于会反复复发的类型，必须加语义过滤而不是只改文案。

**How to apply:** 后续只在该独立 v4 工程处理；保留资格门、绝对定位、不可见诊断属性与 LOGO 槽过滤。打包前记得设置 `JAVA_HOME`。会话恢复后先读本台账。

---

## 14:53 第六轮：动物样本页 LOGO 仍不显示 / 仪器状态文字仍波动 —— 完整链路定位 + 重新打包

用户反馈：`但动物样本页的logo还是不显示，点仪器状态还是有波动`（针对 1425 包，版本 -4）。诊断版本 `-4` → **`v4-diag-20261111-5`**；该版本此前从未进过任何 APK，真机看到 -5 即为新包。

### 完整链路诊断发现的两层根因

1. 第五轮 `isSettingsNavPill(slot)` 用**祖先整页 `textContent`** 判断设置页菜单；主页面内嵌 `<script>` 路由表里也含「医院信息/仪器状态/管理员设置」，动物样本页顶栏 LOGO 槽因此被误排除。`diagnose-settings-fullchain.js`（真实 view 目录临时 probe.html，让 bridge 完整注入全部模块，多次采样后删除）输出 `logoLast={"t":"ICU_LOGO_2026.png","slot":"","bg":""}`。
2. 修掉第 1 层后重跑诊断，`slot` 仍是 `""`：**`lanhu-first-phase.js` 的 `setPreferredText("[data-org-live='logo']", ".text-wrapper_1 > .text_5", logoText)` 宽泛兜底把顶栏静态 "LOGO" 占位文字改成了文件名**，而 `lanhu-account-brand.js` 的 `logoSlots()` 只认 `LOGO`/`+`/空/显式标记，之后每 1.5s 都认不出该槽 → 上传的 LOGO 图片永远不显示。设置页不加载 first-phase，所以第五轮的「医院信息变标识符」不受这层影响。

### 源码修复

- `lanhu-account-brand.js`：`isSettingsNavPill()` 改为只看 `slot.parentElement.children` 的**直接兄弟节点**，忽略 `SCRIPT/STYLE` 子节点，精确统计菜单标签且 `hits>=3`；`placeholderFor()` 收紧为只对已确认的 LOGO 槽用 `.text_5` 兜底；`thumbnail()` 在原生 `galleryThumbnail()` 返回空时回退原始 `uri`。
- `lanhu-settings-nav.js`：菜单节点打 `data-icu-nav-label`，新增 `restoreNavLabels()` + `MutationObserver` + 400ms 定时兜底，任何模块改写导航文字都立刻恢复。
- `lanhu-first-phase.js`：**不再用宽泛兜底改写顶栏 LOGO 占位文字**，改为只写显式 `[data-org-live='logo']` 节点（`setAllText`）；顶栏槽完整交给 `lanhu-account-brand.js` 渲染（含图片），无图时由 `restorePlaceholder()` 写 `logoName`，视觉不变。
- `BleManager.java`：版本常量升到 -5。

补丁脚本 `apply-20261111-followup4.js`（7 处精确命中，备份 `backup-20261111-pre-followup4\`）+ 一处 first-phase 补漏直改。

### 验证结果

- 新增 `fullchain-verify-probe.js` **19/19**：真实蓝湖页 + bridge 完整注入链路；探针版 bridge 只屏蔽整页跳转并加点击计数，跑完删除临时文件。动物样本页 mock 上传 1×1 PNG（模拟原生缩略图失败回退），断言顶栏槽被标 `common`、背景图真的出现、`__icuLogoState==='loaded'` 且占位文字 `opacity=0`；医院信息/仪器状态页点击后 12 个采样点菜单文字始终是 8 个设计稿标签、药丸从未被标成 LOGO 槽。
- 重跑 `diagnose-settings-fullchain.js`：动物样本页 `logoLast.slot` 从 `""` 变为 `"common"`；设置页 lateChanges 只剩时钟节点（每秒更新，属正常）。
- 全量回归 **13 个探针合计 341 项全通过**：静态 `check-20261111.js` 40、`check-20261111-feedback.js` 38、`check-20261111-treatment.js` 39、`check-20261111-patient.js` 53、`check-20261111-followup3.js` 38、`check-java-static.js` 28 = 236；运行时 `runtime-probe-20261111.js` 8、`runtime-panel-probe.js` 7、`treatment-table-probe.js` 17、`patient-sidebar-probe.js` 18、`chrome-diag-probe.js` 21、`settings-nav-probe.js` 15、`fullchain-verify-probe.js` 19 = 105。
- 探针自身修正 3 处（非源码问题）：`check-20261111-treatment.js` 的 `renderCommon`→`patientValue` 正则窗口 600→1200（LOGO 注释变长）；`fullchain-verify-probe.js` 的探针版 bridge 必须与原文件同目录（bridge 用 `currentScript.src` 推 assetBase，放 view 目录会让注入模块全 404）；bridge 点击计数改在 capture 阶段（导航兜底要 touch 坐标，纯 MouseEvent 不触发跳转）。

### 构建与交付

- 设 `JAVA_HOME` 后 `:app:assembleDebug` → **BUILD SUCCESSFUL in 38s**；产物 4,901,179 字节；`apksigner verify --verbose` → `Verifies`、v2=true、Number of signers=1。
- `verify-apk-content.js` **36/36**：确认 APK 内 `isSettingsNavPill` 用 `scope.children` / `hits>=3` / `SCRIPT` 过滤、`return uri;` 回退、`data-icu-nav-label` + `MutationObserver` + 400ms 守卫、first-phase 不再宽泛兜底、dex 含 `v4-diag-20261111-5` 且不含 -2/-3/-4。
- **交付副本**：`C:\Users\jx\Desktop\icu-v4-debug-20261111-1453.apk`，SHA256 `858510AD56FC5AC8535270B7FC48C426A7AC2B8B2264E866BD0F67CFA0DC8966`。`1356`/`1425` 两包均已过时。只打包，**未安装、未 commit/push**。

### 待真机

1. 上传医院 LOGO 后，动物样本页顶栏应显示图片（不再显示文件名文字）；设置页导航文字稳定。
2. 连接状态页可读到 `诊断: v4-diag-20261111-5`。
3. 沿用第五轮四项验收（ICU 右上角、右下角无浮层、治疗计时双闸、导航文字）。
4. **待用户决策（沿用）**：「全部关闭」协议值 / `Custom_mode` 全关方案；实时缓存是否改采样时刻快照；是否 release 包、是否安装、是否 commit/push。

**Why:** 用户实测证明第五轮 LOGO 修复只覆盖了症状的一层；必须用「真实页面 + bridge 完整注入链路」探针验证，而不是只看部分模块注入的局部探针，否则会漏掉 first-phase 改写占位文字这条竞争链路。

**How to apply:** 后续只在该独立 v4 工程处理；LOGO 显示统一由 `lanhu-account-brand.js` 负责，`first-phase` 不得再写顶栏占位文字；设置页菜单文字由 `lanhu-settings-nav.js` 守卫兜底。会话恢复后先读本台账。

---

## 15:11 第七轮：EM / AM 蓝牙信号置顶 + 删除连接状态页灰色提示

用户两条需求：① `把"EM"和"AM"名称开头的蓝牙信号放前面`；② `把连接状态页面的主机状态：未连接-5这段灰色提示文本删掉`。诊断版本 `-5` → **`v4-diag-20261111-6`**。

### 蓝牙列表链路判定

所有设备列表只有一个出口：`BleManager.getDevices()`（主机 EM 系列）、`Am4100Manager.getDevices()`（监护仪 AM 系列）、`ThermalBleManager.getDevices()`（红外测温桥）。下游 `MainActivity.buildHostDevicesJson()`/`buildMonitorDevicesJson()`、`IcuDashboardView` 两个原生 Canvas 列表、`ThermalBleManager` 的 listener 快照全部调用它；H5 `lanhu-ble-panel.js` 的 `bleOptions(host.devices)` 按 JSON 顺序渲染且**不自行排序**。因此只改三个 `getDevices()` 即可全域生效。

### 源码改动

- 新增 `DeviceNameOrder.java`：`isPreferred(name)` 用 `regionMatches(true, ...)` 判定名称以 EM / AM 开头，忽略大小写与前导空格；`rank()` 预留分组扩展。
- 三个 `getDevices()` 改为**稳定分组**：按发现顺序分别收进 `preferred` / `others`，再 `preferred.addAll(others)`。组内顺序完全不变、RSSI 不参与排序，保留原设计「信号抖动不引起列表来回跳」；只是把目标设备整体提到前面。底层 `appendDiscoveredDevice()` 的追加逻辑与 `indexOfDevice()` 不变。
- `lanhu-ble-panel.js`：删除整段 `.conn-status` 灰色汇总行（原内容为「主机状态/监护状态/WiFi状态 + 诊断: v4-diag-20261111-5」）及 `statusText()`；新增 `diagnosticHint()`，把数据通路异常、超时兜底与诊断版本写进面板**不可见**属性 `data-icu-conn-hint`。页面干净，排障能力不回退。
- `BleManager.java`：版本常量升到 -6。

### 验证结果

- 新增 `check-20261111-device-order.js` **26/26**：三个出口的分组结构、不比较 RSSI、所有消费端仍走 `getDevices()`、H5 不自行排序；并从真实 `DeviceNameOrder.java` 提取 `isPreferred` 方法体转译到 Node vm 执行，覆盖 14 个名称用例（大小写/前导空格/null/undefined/中文占位名）+ 分区稳定性 + RSSI 变化不重排；四个 Java 文件括号平衡与 public 方法签名查重。
- `runtime-panel-probe.js` 重写为 **7/7**（改为提取 `diagnosticHint()` 执行，并断言不再渲染 `conn-status`）；`check-20261111-feedback.js` 两条断言改为验证「结论仍在但只进不可见属性」。
- 版本 -6 同步到 `check-20261111-treatment.js`、`check-20261111-patient.js`、`check-20261111-followup3.js`、`chrome-diag-probe.js`、`verify-apk-content.js`（后者同时新增 `DeviceNameOrder`/`isPreferred` 与 `lanhu-ble-panel.js` 产物断言，并排除旧 `-5`）。
- 全量回归 **14 个探针 367 项全通过**：静态 269（26+40+38+39+53+38+7+28）、运行时 98（8+17+18+21+15+19）。
- 真编译两次：第一次 `BUILD SUCCESSFUL in 40s` 产出中间包（含排序与 -6，但不含后续的灰色提示删除，未交付）；用户追加需求后重编译 `BUILD SUCCESSFUL in 18s`，最终产物 4,901,558 字节。`apksigner verify --verbose` → `Verifies`、v2=true、Number of signers=1。`verify-apk-content.js` **41/41**：dex 含 `v4-diag-20261111-6`/`DeviceNameOrder`/`isPreferred` 且不含 -2~-5，assets 含 `data-icu-conn-hint`、不含 `conn-status`。
- **交付副本**：`C:\Users\jx\Desktop\icu-v4-debug-20261111-1511.apk`，SHA256 `15B46E0FD813069B5537D2E7533790063D822A72592C93299C3B412589323541`。`1356`/`1425`/`1453` 均已过时。只打包，**未安装、未 commit/push**。

### 待真机 / 注意

1. 扫描列表里 EM / AM 开头的设备应在最前，且刷新 RSSI 时顺序不跳；三类设备列表（主机、监护仪、红外）都验一下。
2. 连接状态页应不再出现那行灰色状态提示，也不再有可见的诊断版本号。
3. **界面上从此没有可见的诊断版本标记了**：判断新包请改用 APK 文件名 / SHA256；需要排障时读连接状态面板的 `data-icu-conn-hint` 属性。
4. 沿用前几轮待办：LOGO 显示、设置页导航文字、ICU 右上角、治疗计时双闸；待用户决策「全部关闭」协议值、实时缓存快照语义、是否 release/安装/提交。

**Why:** 设备列表按发现顺序稳定显示是既有设计，不能为了置顶改成按 RSSI 排序；用「稳定分组」既满足置顶又不引入抖动。连接状态页的灰色汇总行与上面 4 行设备信息重复，但其中承载了数据通路超时结论，所以删可见文本的同时把结论迁到不可见属性，避免回退第二轮的诊断能力。

**How to apply:** 后续只在该独立 v4 工程处理；任何新增设备列表出口都要复用 `DeviceNameOrder.isPreferred` 做稳定分组；连接状态页不要再拼 `.conn-status` 可见文本。会话恢复后先读本台账。

---

## 追加：侧栏连续局部刷新重叠 / 实时监护底栏高亮 + 上午事项只读复核

用户明确说明上午尚未完成的事项已经由其自己改完，**本轮只检查，不继续修改、不回退，也不执行旧计划**。新需求为：左侧新建样本信息区在不切页的连续刷新中不得重叠；实时监护底部“蓝牙/监护仪”只有真实连接成功才高亮，扫描中不得高亮；“报警音”只随自身开关高亮。

### 本轮界面源码修改（仅 2 个文件）

- `app/src/main/assets/lanhu/lanhu-first-phase.js` 的 `rowBounds()`：根因是 `getBoundingClientRect()` 给出可视区坐标，而绝对定位的选中背景和点击命中层使用滚动内容坐标；旧代码漏加 `section.scrollTop`，并用可视高度钳制，滚动后图层会错位，自动滚到底时还可能被钳到顶部。现已加回 `scrollTop`，并用 `max(可视高度, scrollHeight)` 作为内容坐标边界。静态六槽、动态卡片复用、样本顺序/搜索/选择、空字段“待录入”和新建后自动滚到底均保留。
- 同文件新增 `setMonitorFooterHighlight()`，`renderMonitor()` 每轮局部刷新统一更新底栏文字和状态 class：`connected === true` 才使 `.group_16/.group_17` 高亮；`alarmSound !== false` 才使 `.group_18` 高亮。未改监护按钮动作、BLE 协议或连接行为。
- `app/src/main/assets/lanhu/lanhu-fit.css` 新增底栏开关状态样式：文字在蓝色/灰色间切换，静态 PNG 图标同步切换 `grayscale/opacity`，避免只改文字而图标仍高亮。

### 本轮验证（不是 Android 真机验收）

- 独立外部探针 `C:\Users\jx\icu-v4-probes-20261111\sidebar-monitor-refresh-probe.js`，使用真实页面 HTML/CSS/JS 和明确标注的模拟 `IcuNative`/病例夹具，未使用 `--no-sandbox`。
- 基线实测滚动 240px 后背景与点击层错位 240px；追加第 9 条并自动滚到底 `scrollTop=508` 时，第二条期望内容坐标约 358，旧图层却被钳到 0。
- 修复后探针 **64/64**：覆盖初始、滚动后连续 10 次局部刷新、追加空白样本并自动滚到底、再滚回并连续 6 次刷新；无重复列表/图层、无卡片矩形相交，背景与点击层坐标误差均远小于 2px。底栏覆盖未连接/扫描/连接/断开/重连与报警音开关六种组合，class、文字颜色和图标计算样式全部符合预期。
- 既有 `sample-append-probe.js` 回归 **35/35**；既有 `check-java-static.js` **27/27**；`lanhu-first-phase.js`、`lanhu-bridge.js`、`lanhu-live-state.js` 的 `node --check` 均通过。
- 结果文件为 `sidebar-monitor-refresh-result-after.json` 与 `sidebar-monitor-refresh-summary-after.json`，另有 baseline 文件供对照。Chrome 只能证明 DOM、几何与模拟状态语义，不能证明 WebView 真机表现、BLE 或性能。

### 上午已完成事项的只读复核（未修改这些链路）

- `applyEnvironmentValueResponse()` 仅接受 `get_temp/get_o2/get_humidity/get_co2`；setter 的目标值或裸 `ret:ok` 不走该实测采纳入口。有效状态才调用 `noteValidHostState()`。
- `updateDataPathHealth()` 保留单舱 10 秒、全局 20 秒阈值；`isDataReady()` 在超时时为 false，`protocolReady` 仍单独表示协议通道，两者不再混同。`MainActivity` 与 `IcuDashboardView` 两套 host JSON 均挂接相同 `dataDiagVersion/dataDiag/controlConfirmations`。
- 湿度旧动作只提示只读，不再打开原设置路径；custom mode 数值数组和下发函数均不含湿度 setter。六页导航计时使用统一 URL 参数入口，目标页校验路由及专属首屏节点，并记录路由不符、节点缺失和超时。
- **治疗计时口径注意：** 快照保存、病例绑定、断链冻结和协议就绪后续算的入口均存在；但重启恢复时若尚未连接，`bindTreatmentSession()` 会把存档折算到 `updatedAt` 并清空原墙钟锚点，随后 `resumeTreatmentClockIfAllowed()` 在重连时以当前时刻重新落锚。因此当前实现是“保留重启前累计值，重连后续算”，**不累计重启到重连之间的墙钟间隔**。若要求连这段间隔也计入，当前实现尚未满足；本轮按要求只报告，未修改。真机仍需验证强停重开、手机重启、断链/重连、切舱/切设备及结束病例。

### 当前交付边界与待真机

- 本轮新界面改动**未编译、未打包、未安装、未 commit/push**；1511 及此前 APK 不包含本轮侧栏和底栏修复，等待用户另行通知后再打包。此前轮次已经存在的打包记录不因本轮约定被改写。
- 真机重点验证：不切页连续刷新、滚动和追加样本时不再覆盖；蓝牙/监护仪在未连接、扫描、连接、断开时高亮正确；报警音切换后文字与图标同步。
- 每日 durable cron `1c43306e` 创建未满 6 天，本轮无需重建；今天不是每月 25 日，不生成月报。

**Why:** 侧栏问题来自可视区坐标与滚动内容坐标混用，不能靠切页或整页刷新掩盖；底栏三种状态必须独立，不能把扫描或报警音开关误当监护连接。上午代码由用户完成，复核发现只能报告，不能擅自续改。

**How to apply:** 后续仅在独立 v4 工程处理；保留滚动内容坐标换算、动态卡片复用和底栏统一状态入口。等待用户打包通知及真机结果，Chrome/静态检查通过不宣称真机验收。关联 [[icu-project-log-2026-11-10]]。

---

## 追加：17:48 用户授权打包本轮界面修复

用户明确通知“打包apk”，本轮重新获得编译/打包授权；仅构建与交付，未安装、未 commit/push。

- 在独立 v4 工程设置 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` 后执行 `:app:assembleDebug`，**BUILD SUCCESSFUL in 28s**，33 tasks（3 executed / 30 up-to-date）。存在 Gradle 10 弃用提示，但不影响本次构建。
- 原生产物：`app\build\outputs\apk\debug\app-debug.apk`，**4,902,850 字节**，SHA-256 **`EC07ED70CA55F1DE7C6BCC8931D50940DCAE1A31F424C0ABF63983C0A32DCC5A`**。
- `apksigner verify --verbose`：**Verifies**，APK Signature Scheme v2=true，Number of signers=1；v1/v3/v4=false 不构成本次签名失败。
- 扩展外部 `verify-apk-content.js` 后直接读取 APK ZIP： **45/45 通过**。除既有检查外，明确确认产物内 `lanhu-first-phase.js` 含 `scrollTop/scrollHeight` 内容坐标换算及三组 `setMonitorFooterHighlight` 调用，`lanhu-fit.css` 含底栏 on/off 样式和三个静态图标的灰度处理；dex 含当前 `v4-diag-20261111-8`。
- 桌面交付副本：`C:\Users\jx\Desktop\icu-v4-debug-20261111-1748.apk`，大小与 SHA-256 同原生产物，逐字节哈希一致。1511 及此前 APK 均不包含本轮侧栏/底栏修复，不应再用于本轮验收。
- 未安装、未 commit/push；侧栏连续局部刷新、样本滚动/追加、监护连接与报警音高亮仍待真机验证，构建成功和产物检查不宣称真机验收通过。
