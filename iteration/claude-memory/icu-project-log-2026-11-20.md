---
name: icu-project-log-2026-11-20
description: 独立 v4 护理模式四选一续作、桌面红蓝光 SVG，以及 JUnit/Chrome/BLE 仿真最终验证
metadata:
  node_type: memory
  type: project
  originSessionId: 0d0c0cb1-49cd-4017-933f-5f02fddd3e47
  modified: 2026-11-20T01:55:41.516Z
---

# icu.apk.1 v4 独立项目台账 2026-11-20

## 工程与边界

- 仅使用独立 v4：`C:\Users\jx\Documents\xwechat_files\wxid_47aul47fkzzz22_cc78\msg\file\2026-11\icu.apk.1 v4\icu.apk`，不与旧工程或 v5 混用。
- 本轮未执行 assembleDebug、未生成或安装 APK、未 commit、未 push；桌面既有 `icu-v4-debug-20261116-170315.apk` 不包含本轮续作。
- 本轮保留 11 月 19 日主机同步/置灰/控制回读修复，未回退 `BleManager.java`、`HostNotifyReassembler.java` 或 AM4100 协议。

## 护理模式四选一

- 按用户最新要求，将母幼、术后、心肺、自定义改为一次最多一个本地开启模式，不再保留四个独立同时开启的交互。
- `IcuDashboardView.java` 新增统一可见模式索引及一次性四键写入；旧版 SharedPreferences 若有多个 `enabled=true`，按“主机确认且本地 enabled → 最近选择 → 首个 enabled”只解析一个可见模式，启动时不自动发送物理命令。
- 新增 `HostModeSelectionPolicy.java` 与 4 项 `HostModeSelectionPolicyTest`：完整入队才保留开启选择；失败或关闭清除本地开启；这里只表示 App 队列/选择，不冒充主机已执行。
- 开启或“保存并开启”最后真实调用 `set_care_mode`；只有该命令确实入队才更新最近模式。切换时新模式同键配置优先，旧开启模式独有且新模式未声明的开关合并为 `false`，避免共享开关先开后关。
- 关闭路径也合并旧开启配置：正常四选一等价于关闭当前模式；若升级前遗留多个 enabled，则一次生成全部旧模式功能关闭项，避免只关可见模式而留下其它物理功能。
- `hostModesToJson()` 对 H5 最多输出一个 `enabled=true`；`active/pending` 仍分别表达主机确认/等待，不用 active 冒充本地选择。`lanhu-live-state.js`、`lanhu-first-phase.js` 的渲染、标题和注释已同步，action 名及 DOM selector 未改。
- 空批量单独提示“没有可下发的配置/没有需要关闭的功能”，不再读取历史 `cold_light` 等 `getLastError()` 冒充本次失败。真实主机若仍回报开关未确认，需结合新日志判断，不能宣称心肺护理真机问题已根治。
- 修改前备份：`C:\Users\jx\icu-v4-probes-20261111\backup-20261120-pre-care-exclusive\`。

## 最终验证

- 两份 H5 与 `host-mode-control-probe.js`：`node --check` 全部通过。
- 护理真实 H5 Chrome/mock：**32/32**，覆盖连续快照恰好一个开启、active 不造成多开、action 不变、无重复 fake、治疗时长只读等；报告 `C:\Users\jx\icu-v4-probes-20261111\host-mode-control-20261113\summary.json`。
- 完整 `testDebugUnitTest`：**19 类、95 项，0 失败、0 跳过**，BUILD SUCCESSFUL；包含新增四选一策略测试。
- 真实编译 `BleManager` + 外部 JVM Android 桩主机协议仿真：**116/116**，保留四路传感器、分片重组、跨舱新鲜度、250ms 当前舱轮询、背景舱退避、控制回读、裸 ok、看门狗等回归；报告 `C:\Users\jx\icu-v4-probes-20261111\host-ble-simulation-20261116\care-exclusive-20261120.json`。
- 汇总：`C:\Users\jx\icu-v4-probes-20261111\host-ble-simulation-20261116\care-exclusive-verification-20261120.json`。
- JVM/Chrome 不等于真实 Android GATT、射频、WebView 或主机物理执行。真机仍需验证四选一下发与回读、旧模式功能物理关闭、心肺护理提示、四路传感器同步速度及灰显。

## 红光/蓝光理疗 SVG

- 已交付并再次核验桌面 `C:\Users\jx\Desktop\红光理疗.svg`：128×128、1,267B，SHA-256 `1a079e4b9923255e23dea0aa8bdd28e07d04edfe8be01c5739b5d3f1a24d55d2`。
- 已交付并再次核验桌面 `C:\Users\jx\Desktop\蓝光理疗.svg`：128×128、1,267B，SHA-256 `0ea9f704705c217b2a31673e0f1a31b35c8fec53590aead6a55c7e43575ce0e7`。
- 两者采用项目医疗设备的深灰蓝圆角描边、圆头线条、浅色底卡，分别为红光 `#EE635D`、蓝光 `#4D9DFF`；纯矢量、无脚本/外链/位图，未集成进 App。

## 遗留

- 用户怀疑的 `get_all_status` 非法 JSON 尚缺完整、未改写的 C305 原始帧；未做全局括号替换或模糊字段归属，不能宣称已修。
- 每日 durable cron `870da109` 仍存在，CronList 显示下一次运行为 2026-11-20 22:23。
