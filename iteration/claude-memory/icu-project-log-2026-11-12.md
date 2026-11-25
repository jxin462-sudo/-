---
name: icu-project-log-2026-11-12
description: 独立 v4 侧栏/编号包及续作：监护数值快速通道、真实治疗时长/PDF列线、按petId重启监护快照、断连报警与UI清理；续作已打包1537交付，真机待验
metadata:
  node_type: memory
  type: project
  originSessionId: 79bb776c-0fe0-4776-88a2-431f1b231d1a
  modified: 2026-11-12T07:42:31.300Z
---

# icu.apk.1 v4 独立项目台账 2026-11-12

## 范围

仅修改独立工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不与 Desktop 旧 ICU 工程混用源码、APK、测试或验收结论。本轮处理固定医院信息/LOGO/搜索头部、同页局部刷新、删除样本残留和病例编号；未顺带实施治疗重启间隔累计或治疗记录采样快照。未安装、未 commit、未 push，未读取或披露签名凭据。改前备份在 `C:\Users\jx\icu-v4-probes-20261111\backup-20261112-pre-header-case`。

## 已完成源码修改

### 1. 六页患者侧栏固定头部与同页局部刷新

- `lanhu-first-phase.js` 新增幂等 `ensureSidebarFixedHeader(container)`，在 `renderList()` 入口调用；以第一张静态样本卡为边界，把之前的医院信息、LOGO、机构切换和搜索原节点移入唯一直接子节点 `.icu-sidebar-fixed-header`。连续原生事件与 1.5 秒刷新复用 wrapper、输入框和 LOGO 节点，不重复包裹或复制。
- `lanhu-fit.css` 为固定头部增加 `position:sticky; top:0`、同色不透明背景及 `z-index:60`，高于搜索层 40、动态列表 25 和病例点击层 20。保留原侧栏滚动容器、自动滚到底、六槽/动态卡、搜索与选择逻辑，以及 `rowBounds()` 的 `scrollTop/scrollHeight` 内容坐标算法。
- `lanhu-account-brand.js` 修复相同 URI（尤其一直为空 URI）时早期返回挡住 `logoName` 更新；未加载图片时占位文字会随原生状态变化。保留 URI 图片加载幂等、旧回调失效、缩略图失败回退及设置页菜单排除。
- 六页冒烟时发现 `renderCommon()` 的旧样本行清理使用全局 `hide(staleRows)`，监护回放页最外层也叫 `.group_30`，导致整页及头部被设成 `visibility:hidden`。新增 `hideSidebarRows()` 并只在 `.icu-patient-sidebar` 内隐藏静态样本行，避免蓝湖 class 在外层页面复用时误伤整页。

### 2. 编号严格按“当前舱剩余最大有效数字编号 + 1”

- `IcuDashboardView.nextCaseNumber()` 只收集当前舱 `cases.caseNo`，委托纯计算 `nextCaseNumberFrom(Iterable<String>)`；忽略空、非法和非正数，至少六位格式化，不重排或改写现有病例，不补中间空号。
- 删除 `KEY_NEXT_CASE_SEQ`、`next_case_seq` 持久读写及账号前缀登记，旧高水位存档不再参与发号。
- 新 JUnit `app/src/test/java/com/example/icuapk/CaseNumberingTest.java` 覆盖：空舱 `000001`；唯一 `000001` 删除后仍为 `000001`；剩余 `000001/000003` 得 `000004`；删除末尾最高号后可重用该末尾号；非法号忽略；超过六位不截断；重复计算不改病例也无隐藏计数。

### 3. 删除样本不残留私有数据

- 保留 `purgePetScopedData(petId)` 清除该宠物 SharedPreferences 私有键和 `monitorSnapshots` 留档、病例保存、H5 通知与原生画布刷新。
- 删除当前病例后调用 `restoreMonitorSnapshot()`：恢复新选中病例自己的完整监护缓冲；没有病例或没有存档时由 `Am4100Manager.restoreBuffers(null)` 清空实时、会话、历史和测量缓冲，不再只清报告会话波形。
- 新建空白病例和同舱切换病例均先归档旧宠物的完整缓冲，再恢复新宠物自己的缓冲；新 `petId` 无存档则全清。摄像头加载入口先复位 `selectedCameraSnapshotIndex=-1`，空存档或解析失败不会留下上一病例截图索引。
- 修正删除列表前部病例时的选择下标：只递减下标以保持原选中宠物，不让实际选择悄悄移到下一只。
- `BleManager.purgeTreatmentSession(recordNo)` 在删除病例时清除当前舱匹配的运行时治疗身份、分钟数/锚点和持久存档。因为末尾编号允许重用，不能只按相同 `recordNo` 恢复，否则新同号病例可能继承旧治疗时长。其他舱和其他病例的存档不按号盲删。

## 最终验证（模拟/静态/构建，不等于 Android 真机验收）

- `:app:testDebugUnitTest :app:assembleDebug` 成功；编号 JUnit **7/7**，XML 显示 `tests=7 failures=0 errors=0 skipped=0`。
- Chrome：固定头部+连续局部医院/LOGO刷新+原侧栏/底栏回归 **116/116**；六个患者侧栏页面滚动/可见/点击/头部几何 **48/48**；样本追加 **35/35**；患者侧栏交互 **18/18**；LOGO 加号 **30/30**；设置导航 **15/15**。
- 静态：本轮患者/编号/残留/固定头部 **65/65**；治疗 **39/39**；基础 **40/40**；设备顺序 **26/26**；反馈 **38/38**；`check-java-static.js` 全项 `pass:true`。相关 JS 与探针 `node --check` 通过。
- 最终 APK 内容检查 **59/59**：包含固定头部、侧栏作用域隐藏、LOGO 局部刷新、新编号和删除清理标记；dex 不含 `next_case_seq/KEY_NEXT_CASE_SEQ`；打包的三份本轮 H5 源文件与工作源码逐字节一致。
- `apksigner verify --verbose`：`Verifies`，v2=true，signers=1；v1/v3/v4=false。Java 的 Conscrypt restricted-method 警告不影响验签结果。

## 最终交付物

- 桌面：`C:\Users\jx\Desktop\icu-v4-debug-20261112-0946.apk`
- 大小：**4,903,603 字节**
- SHA-256：**`A874B6CA95ACFD546B6745D9A6BE6F0A5089B7EBAADED3C879A6B42CD608EC07`**
- 原生产物 `app/build/outputs/apk/debug/app-debug.apk` 与桌面副本大小、SHA-256 完全一致。该最终包已覆盖同文件名早些时候未含六页可见性修复的中间包；昨日 `icu-v4-debug-20261111-1748.apk` 不含本轮修复，不能用于本轮验收。

## 待真机复核

1. Android WebView 中六页连续局部刷新、手动滚动和新建自动滚到底时，医院信息/LOGO/机构切换/搜索始终完整可见且可点击，选中背景与点击层仍对齐。
2. 实际上传 LOGO 后图片真机显示；医院名称、地址和空 URI 的 LOGO 文字同页更新，设置页菜单不被改写。
3. 删除唯一 `000001` 后新建仍为 `000001`；删除 `000002` 而剩余 `000001/000003` 时新建为 `000004`；删除末尾最高号后允许重用末尾号；现有病例不改号、不补中间空号，左右舱隔离。
4. 删除当前/非当前/前部病例后，新选中或新建病例不显示被删病例的门限、护理模式、治疗时段、主机模式、摄像头截图、治疗计时、实时/历史/会话波形；重启后复核持久残留。
5. Chrome、静态检查、JUnit、编译及 APK 内容检查均不能替代以上真机验收。

## 同日续作（源码修改、模拟/JVM验证与最终打包）

### 监护数值、真实治疗记录与 PDF

- 原生 AM4100 有效帧仍更新缓存后直接 `emit()`，协议、有效值、体温来源优先级及断连标量规则未改。H5 `schedule()` 改最早截止时间 + 单 pending；新增 `MainActivity.buildVitalsStateJson()` / `LanhuNativeBridge.vitalsState()` 和 H5 120ms `refreshVitals`，与全量 monitor JSON 共用 `putCurrentMonitorVitalsJson()`。数值不变不写 DOM，数值单独变化不重绘波形，1500ms 全量兜底保留。
- 治疗开关沿移到 60 秒数值采样门限之前；`TreatmentEntry.totalOnMs/featureOn/featureOnSince` 记录分段真实使用，结束/翻篇先强制采样和结算。PDF 治疗勾选与时长按病例真实记录，不读另一病例当前设备开关或主机预设；未使用项目仍不勾、不伪造非零时长。底部治疗结论/医院地址/主管护士共享 `PDF_CONCLUSION_COLUMN_X=150f`。

### 治疗中清后台重启后监护数据按病例恢复

- 新增 `MonitorSnapshotCodec.java`（version 1）：三路会话波形各最多 4096、八组趋势各最多 720、带时间戳测量历史最多 3600；实时三窗口解码为空，损坏/未知版本/无效测量不造演示数据。新增 `MonitorSnapshotCodecTest` 4 项。
- `IcuDashboardView` 以账号 prefs 内 `monitor_snapshot_v1_<zone>_pet_<petId>` 存档，内存 Map 负责快速切换。进行中每 60 秒检查点 `apply()`；`MainActivity.onPause()` → `flushTreatmentStateForBackground()` 强制采样并同步 `commit()` 快照、病例、选中 petId 与主机计时锚点；无生命周期回调的硬杀最多仍可能损失一个检查点间隔，不能宣称零丢失。
- 冷启动先病例后快照，选择顺序为持久 `petId` → 进行中 → 下标钳制；全部已结束不重开。切换/新建先保存旧 `currentCase()` 快照，新病例无档 `restoreBuffers(null)` 全清；删除沿用 `_pet_<petId>` 同时清磁盘和 Map。
- 自动重连 `clearVitals()` 只清实时窗口/标量，不清会话、趋势、测量历史。结束先采样、结算、同步冻结，再 `currentTreatment=false`，不在结束动作 `resetSessionWaves()`。
- 实际 `generatePdfReport()/drawTemplatePdf()` 显式消费该病例 `MonitorSnapshot`：生命体征表读其 measurementHistory，三图读 sessionEcg/Spo2/Resp 与对应趋势；无档保持空，不回退全局实时值补造历史。

### 弹窗、冗余入口、蓝牙图标与断连报警

- 编辑治疗记录共用弹窗三个可见“手工”改“手动”，删除“手工编辑值/医生修订”行和赋值，后续输入自然上移；旧 manualValue 只留模型/存档兼容。
- 自动、结束及 PDF 前强制采样已足够，完整删除原生 Canvas/H5/静态页/action/bridge 的“写入一次采样、手动编排、手动维护、treatment_sample”入口，底层自动采样函数保留。
- 监护蓝牙 PNG 改内联 SVG，`currentColor` 连接蓝、断开灰。原生报警统一 `connected && protocolReady` 门禁，断连回调与巡检立即清 signature/时间戳并逐个 `cancel()` 已 show/排队体征 Toast；普通断连提示保留，连接真实超限的 1 秒巡检、30 秒重复、报警音与 H5 红色提示不变。H5 对 connected=false 的陈旧 alarms 返回空。

### 续作实际验证与边界

- 打包前 `./gradlew.bat :app:testDebugUnitTest --no-daemon`：Java 编译与测试成功，6 类共 **39 tests，0 failure/error/skipped**；其中新 codec 4 项。随后按用户“打包apk”授权执行 `assembleDebug`，结果见下节。
- 最终通过：源码静态 **43/43、88/88、65/65、39/39、71/71**；Chrome 侧栏/监护/数值/断连报警 **157/157**、治疗记录 **19/19**、六页 **48/48**、样本追加 **40/40**、患者交互 **18/18**；相关 JS 语法通过。探针在 `C:\Users\jx\icu-v4-probes-20261111`，本轮改前备份 `backup-20261112-pre-restart-vitals`。
- 初跑失败均为探针自身/旧断言：disconnected 含 connected 子串、body.textContent 包含探针脚本、CRLF、数值辅助方法已提取、断连清理已提取，以及本轮有意改掉的最新病例/下标选择与 PDF 实时兜底；修正探针后复跑全绿，未用修改业务源码迎合错误探针。
- **打包前未生成/复制 APK；最终交付见下节。未安装、未 commit、未 push。上一阶段桌面 `icu-v4-debug-20261112-0946.apk`（SHA-256 A874…EC07）不包含上述续作，不可用于本轮验收。**
- 真机待验：治疗中最近任务清掉再重启后结束，历史体征表与 ECG/PLETH/RESP 仍有重启前真实数据；另一病例不覆盖、自动重连不清历史；原生弹窗收紧、SVG 视觉、断连无旧体征 Toast 且连接真实超限仍报警；PDF 预览/下载/打印一致及底部列线。JVM/Chrome/静态成功不等于 Android 生命周期、真实 BLE 或打印验收。

## 续作最终 APK 交付（2026-11-12 15:37）

- 用户明确“打包apk”后，配置 JDK 21 执行 `:app:assembleDebug --no-daemon`，`BUILD SUCCESSFUL`，33 actionable tasks（5 executed、28 up-to-date）。首次仅因新 shell 未设 `JAVA_HOME` 而未开始编译，配置后构建成功。
- 桌面新文件：`C:\Users\jx\Desktop\icu-v4-debug-20261112-1537.apk`；大小 **4,908,317 字节**；SHA-256 **`d0bee2c9db2696eb9afe482a4a826bf7c90cd0df0589286192c5b63e06ce7d0f`**。与 `app/build/outputs/apk/debug/app-debug.apk` 经 `cmp`、大小和 SHA-256 核对完全一致，未覆盖旧包。
- 原生产物及桌面副本 `apksigner verify --verbose` 均 `Verifies`，v2=true、signers=1，v1/v3/v3.1/v4=false，Android Debug 签名。aapt：package=`com.example.icuapk`、versionCode=20、versionName=`2.6.1-env-care-fix`、minSdk=24、targetSdk=34、compileSdk=36。
- 更新后的实际 APK 内容检查 **125/125**：六份本轮相关 H5 与源码逐字节一致；dex 正向含 `MonitorSnapshotCodec`、`monitor_snapshot_v1`、`selected_case_pet_id`、病例快照 PDF、断连清理等实现，反向不含 `pdfVitalsLiveRow`、`pdfTimeOnly`、`treatment_sample`。
- 仅打包和核验，**未安装、未 commit、未 push，未读取或披露签名口令、keystore 内容或独立凭据文件**。无生命周期回调的直接硬杀仍可能损失最近一个 60 秒检查点间隔；真机重启、真实 AM4100/BLE 和实际 PDF 打印仍待验。

**Why:** 用户明确要求固定头部不随滚动被顶走、同页数据局部刷新、删除样本无数据残留，编号按剩余最大值 + 1；续作进一步要求监护数值及时显示、治疗真实记录与时长、重启不丢历史监护报告、弹窗和入口精简、蓝牙连接高亮及断连后无陈旧体征提醒。

**How to apply:** 后续继续只用该独立 v4 工程；保留固定头部幂等包裹、按 petId 的病例/快照隔离、同步后台与结束冻结、历史 PDF 显式快照数据源、轻量数值桥和断连报警门禁。0946 包只用于上一阶段侧栏/编号验收；1537 包才是包含续作的当前交付包，真机结果返回前不宣称验收通过。关联 [[icu-project-log-2026-11-11]]。
