# 水卡生命周期验收结果

验收环境与主部署物理隔离，每轮由初始化 SQL、全部迁移和固定种子重建。

| 批次 | 场景数 | 结果 | 结构化证据 |
|---|---:|---:|---|
| 2026-07-23T14-50-06-671Z | 9 | 9/9 PASS | [result.json](runs/2026-07-23T14-50-06-671Z/result.json) |
| 2026-07-23T14-53-02-610Z | 9 | 9/9 PASS | [result.json](runs/2026-07-23T14-53-02-610Z/result.json) |
| 2026-07-25T09-01-20-106Z | 10 | 8/10 FAIL（环境原因，见下） | [result.json](runs/2026-07-25T09-01-20-106Z/result.json) |
| 2026-07-25T09-06-55-198Z | 10 | 10/10 PASS | [result.json](runs/2026-07-25T09-06-55-198Z/result.json) |

07-25 两轮为 D-213（付费卡永久、赠卡不可充值）落地后的复跑，场景由 9 增至 10。

失败批次不覆盖、不删除，如实记录成因：09-01-20-106Z 的 S7（取水退差）与 S9
（成员限额）超时失败，根因是 `tools/device-sim/node_modules` 缺失导致设备模拟器
启动即 `ERR_MODULE_NOT_FOUND`，出水指令没有 ACK/result 回执，与被测业务逻辑无关；
装回依赖后同一份代码在 09-06-55-198Z 全绿。

覆盖场景清单以 `miniapp/e2e/run-card-lifecycle.js` 的 scenario 标题与各批次
`result.json` 的 `scenarios[].name`（含逐条 evidence 与耗时）为准。

结果仅证明 Pay-Sim、设备模拟器和独立数据库环境中的内部技术闭环，不代表正式微信支付或物理设备已经验收。详细订单号、流水、时间和断言以各批次 `result.json` 为准。
