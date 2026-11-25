---
name: icu-project-log-2026-11-10
description: 11月10日仅修改独立 icu.apk.1 v4 源码；双舱串行轮询、数据通路/控制回包超时诊断、舱内温度文案，明确不打包
metadata:
  node_type: memory
  type: project
  originSessionId: 55804543-ca67-4bf7-8f91-c30e4aade866
  modified: 2026-11-10T09:56:25.605Z
---

2026-11-10 继续处理独立 v4 工程 `C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不得与任何旧 ICU 项目比较或混用版本、APK、提交及验收结论。关联 [[icu-v4-standalone-2026-11-07]]，但本日修改未构建，11月7日 APK 不是本日修复版。

用户两次明确要求：只改源码，不打包 APK，何时打包由用户通知。本日未执行 Gradle、编译、测试、APK 生成/复制/安装，也未 commit/push。轮询修改前备份为 `C:\Users\jx\icu-v4-zone-poll-pre-fix-20261110.tar.gz`。

已改：`BleManager` 保留左右舱各一条 `get_all_status` 的 3 秒轮询及按舱独立缓存，固定本轮舱位快照，后台舱先查、界面当前舱最后查；`lastSent` 与“发送”日志从入队时移到 `writeCharacteristic` 成功启动后，避免后入队报文冒充实际发送报文。此项修的是诊断语义和轮询顺序，无双舱抓包不能宣称物理层曾只发 right。顶栏切舱、机构卡片和切换机构代码未改，未真机回归。

三处源码文案已由「仓内温度」改为「舱内温度」：`lanhu_1icuzhuangtaikaobei/index.html`、`lanhu_2zhujikongzhi/index.html`、`lanhu-bridge.js`；资产搜索无「仓内温度」残留。未构建，所以旧 APK 内文案不会因源码修改自动变化。

实测四项环境均为 `--`、主机状态未回传：检查分片拼接、完整帧解析、总表/分项回包采纳、按舱缓存和 H5 渲染后，没有原始回包不足以认定固件未回复或 App 漏解析，未虚构读数、未猜新字段/量纲。新增只读诊断并暴露到 `bleState().host`：`lastNotifyChunk`、`lastNotifyChunkAt`、`lastCompleteFrameAt`、`notifyBufferChars`；按新物理连接清零，保持原 `zones.*.updatedAt` 环境时间语义。下一次可据快照区分完全无 Notify、只有分片/无效帧、已有完整 JSON 但未采纳环境值。未宣布四项读数已修复；需后续真实通讯记录或新诊断快照继续定位。每日 durable cron 已建 `1c43306e`（21:53，7天自动到期）。

用户随后实测退化：设置舱内温度弹窗可输入 28/27，确定后不崩溃、无 crash 缓存，但 `lastSent` 停留 `get_all_status`，未观察到 `set_temp`，界面仍 `--`；上一轮曾实际下发并生效。对照 `icu-v4-zone-poll-pre-fix-20261110.tar.gz` 后确认本轮 diff 未改 `setTemperature`、控制入队或 GATT 写入算法，只改轮询顺序、lastSent 记录位置和诊断。发现现有裸 `{"cmd":"error"}` 可能错归因并两次后把 `set_temp` 加入不支持黑名单的拦截路径，已修：不支持缓存只用于状态查询，所有 `set_*`/`status_light` 用户主动控制不因历史裸 error 被跳过后续点击；每次仍只由用户明确操作触发，无自动物理重发。新增 `host.lastControlRequest/At`、`lastControlSent/At`、`currentWrite`、`writeQueueSize`，实际 GATT 控制写入不会被轮询覆盖。`lastSent` 单独不能证明 set_temp 未写入。此次仍未编译测试/打包，实际下发及生效待后续实测，不宣称根治。

最新实测四项真实值能显示并从 29.3/21/35.1/485 变为 27.6/21/35.7/478，但 `zones.left.updatedAt` 三窗冻结 262s/530s/589s，60 秒 0 更新，不满足≤2秒。发现可造成此现象的代码缺陷：左右舱同名总表查询同时在途时，若仅一舱回复且省略 zone，旧 FIFO 会把后回复的一舱归给先查询的另一舱。已改为 800ms 双舱交替、任意时刻最多一条总表查询，正常目标每舱约1.6秒；保留队列去重、控制优先和2.5秒回包等待，不堆积查询。总表回包带 zone 精确匹配，无 zone 只接受唯一舱位等待项，无归属证据时不再按当前舱猜测；无 cmd 主动环境帧兼容保留。每舱新增 `querySentAt`（实际GATT写入）、`replyAt`（已归属状态帧，不要求含有效环境值）、`lastFrame`（原始帧）到 `bleState().host.zones.*`，`updatedAt` 仍只在真实环境值被采纳时更新。此修复未编译测试/打包，实际≤2秒和双舱缓存待真机，不能宣称已验收。

全工程文案核查：`app/src/main/assets` 三处均为「舱内温度」且无「仓内温度」；旧字仍存在于 `app/build/intermediates/assets/debug|release/...` 的已生成合并资产，解释旧构建产物检查仍见错字。本轮遵守只改源码，不手改生成文件、不清理构建、不打包；后续获准构建时应由源资产重新合并并核对新APK，不拿旧产物验收。

最新实测：`set_temp(27.6)` 已被 `lastSent` 捕获且 `onCharacteristicWrite Status=0`，只能证明应用层/GATT写入流程成功；主机无 ack/回读，不能据此宣称执行成功。数据停滞5–10分钟时仍显示「已连接/就绪」，确认原 watchdog 以任意 Notify 作为存活依据，无法识别“GATT仍连接、有效数据通路失效”。

已继续只改源码完成数据通路健康方案（尚未编译/测试/打包）：`BleManager` 每舱在实际总表写入时记录第一条未回答查询起点，后续轮询不向后刷新；只有可解析的真实环境值、有效开关对象/字段或运行状态才更新逐舱 `validStateAtMs` 和全局 `lastValidHostStateAtMs`。单舱查询后10秒无有效状态或全局20秒无有效状态，标记 `dataPathState=timeout`，显示「已连接 · 数据回包超时」，`connected` 与 `protocolReady` 不伪报断开，`ready=isDataReady()` 变为 false；保留最后真实缓存与时间戳，不自动断开、不伪造 `--`、不自动物理重发。只有有效状态可恢复，任意 Notify/裸ok/版本回声不再清除独立 `dataPathError`。

控制诊断新增 `lastControlSentCmd` 分代与 `lastControlReply/At/Cmd`：匹配实际等待项、命令名和舱位后才记录；控制等待项保留至10秒用于迟到回包识别，但5秒无匹配回包即 `controlReplyState=timeout`，提示“执行结果未确认；未自动重发”。裸 `{"cmd":"error"}` 不冒充某个控制的匹配回包；控制回包可越过舱位不符的队头按命令名+舱位归属，`status_light` 也按用户控制处理。`MainActivity`、`IcuDashboardView` 两套 host JSON 已同步 `ready/protocolReady/dataState/dataError/lastValidStateAt`、控制请求/实发/回包/超时字段及逐舱 `querySentAt/replyAt/validStateAt/lastFrame`，避免页面间诊断不一致。仅做源码静态检索核对；全部修改未运行 Gradle、编译、JUnit、JS检查或真机验证，不能宣称修复验收，等待用户明确通知后再构建。
