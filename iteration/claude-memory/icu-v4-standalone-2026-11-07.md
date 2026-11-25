---
name: icu-v4-standalone-2026-11-07
description: icu.apk.1 v4 独立项目已修复环境读取、物理命令不明重发和四护理模式逻辑；不得与任何旧 ICU 项目比较或混记
metadata:
  node_type: memory
  type: project
  originSessionId: 603b38be-d80a-45b8-8fb2-4d137db4eb12
  modified: 2026-11-07T04:46:20.351Z
---

2026-11-07 用户提供独立目录 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，明确要求不要与上一个项目对比或混在一起；后续必须单独命名、单独记录，不沿用旧项目版本号、提交、APK或验收结论。该目录无 `.git`、无更新日志，只能描述本次实际修改，不能伪造历史 diff。未 commit、未 push、未安装真机。

用户授权“你修复吧”后已修改源码。修改前备份为 `C:\Users\jx\icu-apk-v4-pre-fix-20261107.tar.gz`，含 app/build.gradle、AndroidManifest、BleManager、IcuDashboardView、lanhu-first-phase.js。未读取或展示 keystore.properties、签名密码或密钥内容。

**版本与产物**：Gradle 现为唯一版本来源 `versionCode 20`、`versionName '2.6.1-env-care-fix'`；删除 Gradle 重复 versionCode 及 Manifest 冲突版本。包内原有 `app-debug_20261106_2342.apk`（4,880,975 bytes，SHA-256 `f16460558e7968e012d6e47661935e49c69ea22d4f3159ec869aee959ea777d9`）仍是修改前旧产物，不要当作本次修复版。

**2026-11-07 用户明确要求打包后已实际构建**：在独立工程目录执行 `testDebugUnitTest assembleDebug`，Gradle `BUILD SUCCESSFUL in 1m 4s`，38 tasks executed，Android compileDebugJavaWithJavac 与 testDebugUnitTest 均成功；两个 XML 报告合计 9 tests、0 skipped、0 failures、0 errors。新 APK 已复制到 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu-v4-2.6.1-env-care-fix-20261107.apk`，**4,584,280 bytes**，SHA-256 **`009b805ce012aab769d77f3f88538e00d42c51681680def944493128522d5141`**；与 app/build/outputs/apk/debug/app-debug.apk `cmp` 逐字节一致。aapt 核验 package=`com.example.icuapk`、versionCode=20、versionName=`2.6.1-env-care-fix`、minSdk=24、targetSdk=34、launchable MainActivity；apksigner verify 通过， signer DN=`C=US, O=Android, CN=Android Debug`（Debug 签名，设备已有不同签名版本时可能不能直接覆盖安装）。构建时 local.properties 的 sdk.dir 有目录不存在警告，但环境 ANDROID_HOME=`D:\AndroidSdk` 使构建实际成功；未改该文件。未安装、未 commit、未 push，真机功能仍待验收。

**四项环境读取修复**：新增纯 Java `HostEnvironmentParser.java`，统一读取 temp/temperature、o2/oxygen、humidity、co2，支持标量、数字字符串、current/value 等对象、data 包裹及 0 值；保留原“绝对值大于100才除以10”启发式，真机量纲未确认。BleManager 接受无 cmd 完整 JSON 状态帧、无 ret/data 的顶层总表以及分项 value/current/data.value；按物理舱缓存，旧舱迟到回包不覆盖当前舱开关。保留左右舱各一条 get_all_status 的 3 秒轮询，当前舱任一值缺失或 15 秒无有效更新才补 get_temp/get_o2/get_humidity/get_co2，同舱回退最小间隔 15 秒；主机拒绝当前舱总表时强制补一轮（1 秒防重入）。无 cmd 状态帧只更新状态，不擅自弹 awaiting 队列，避免主动推送与后续裸 ok 错配。回包省略 zone 时优先采用 awaiting 中实际命令的舱位，避免切舱后迟到环境/模式回执写到另一舱。真实主机是否回值、字段形态和量纲仍须原始回包验证，未宣布唯一根因或真机根治。

**重复鸣响修复**：移除 DEFAULT 写回执超时/非成功回执的物理命令重发，移除开关连续矛盾帧后的自动重发，删除 WRITE_TIMEOUT_MAX_RETRY、SWITCH_MAX_RETRY、resendSwitchCommand 及相关计数。只有 writeCharacteristic 两种写法都明确返回 false 才 requeue；异常或回执不明按“可能已发出”推进并提示，不重发。tryWrite 仅负责启动，noteWriteStarted 处理启动后的记录/计时；已启动且本应等待写回执的路径发生异常时，会预留一条迟到写回执，避免误推进下一条。数值/time setter 仅在 sendCommand 成功时返回 true/更新本地值，定时开启必须先成功入队新时长。护理模式仍 quietBatch 连续下发并结束统一回读。多条不同命令各鸣一声不等于同一命令重发；整套模式只响一声需固件批量/提示音协议，App 无静默标志可替代。物理鸣响次数未真机验收。

**单独开关“物理已开/App 显示关”加固**：用户追问该边界后，确认 setControlEnabled 入队成功会先本地回显开启，ok 只记账、真实状态由 get_all_status 确认，无有效回读时保持用户操作并提示未确认，不会自行改关。新增 PendingSwitch.lastHostValueAtMs：保护期前收到的矛盾旧 off 只记录，不能拖到超时后充当新证据；未收到回执需过了 2 秒发送保护期、已收到回执需过了 3 秒回执保护期，保护期后的新鲜矛盾回读才允许按主机值回滚。这样避免主机已开但连续补发旧快照时 App 过早弹回关，同时不增加任何物理重发。若主机在保护期后仍持续错误上报 off，App 最终仍会按主机状态显示，需要原始回包和固件排查；断链清实时状态/切舱也不是本次命令失败。最终四个 Java 源文件 parse syntax OK；随后 Android 全工程编译和 APK 构建已成功，真机验证仍未做。

**四护理模式逻辑修复**：仅保存只落本地；保存并开启先保存，再统一按“数值→功能时长→功能开关→set_care_mode”下发。新增 `HostModeBatchProgress`，任一前置命令未入队就不发模式标识；某功能新时长失败会跳过其开启但允许关闭；toast 区分全部/部分/零入队，不再把入队称为已开启。弹窗按钮 show 后接管，输入错误保留弹窗与内容。active 只来自主机对 set_care_mode 的按舱确认，pending 仅表示等待确认；默认无 active，断链清空，连续 A/B 回执按 AwaitingCommand 实际 careModeValue 确认并选择性清 pending，不拿最新 pending 猜迟到回执。H5 active/pending 文案明确点击是重新下发，不伪称可物理关闭。

**验证**：独立 JUnit 4 测试曾重跑 OK（9 tests，Time 0.027s），随后 Gradle `testDebugUnitTest` 也实际通过且 XML 确认 9 tests / 0 failures / 0 errors；Android 全工程编译和 assembleDebug 已成功，不再只是纯 Java/语法检查。BleManager、IcuDashboardView、HostEnvironmentParser、HostModeBatchProgress 四个 Java 源文件经 JDK 编译器 parse 检查 syntax OK；`lanhu-first-phase.js` 的 `node --check` 通过。静态复核 requeue 仅剩明确 false 启动失败一处，旧自动重发符号均不存在，四模式 confirmed/pending 映射和下发顺序一致。APK 已完成 aapt 版本核验、apksigner 签名验证、大小/SHA-256 和复制件 cmp 核验；未安装。真机四项读数/量纲、红外及模式鸣响次数、物理开关显示一致性、set_care_mode 实际回包和物理 active 仍待验收。
