# 小程序端自动化验收脚本

这里有两类脚本，读者与用途完全不同：

| 类别 | 脚本 | 驱动对象 | 产物 |
|---|---|---|---|
| **GUI 走查** | `run-e1b.js` | 微信开发者工具（miniprogram-automator） | `docs/acceptance/miniapp-e1b/` |
| **链路跑批** | 本目录其余全部 `run-*.js`（清单以目录与 `e2e/package.json` 为准） | 隔离验收环境的 HTTP + MySQL（不开 GUI） | `docs/acceptance/<链名>/` |

## 证据能力边界

- PNG 结构、尺寸、CRC、哈希、目标路径、关键文本、构建 fingerprint 与脚本版本检查，只是**防空文件、占位小图、旧构建和意外篡改的证据完整性校验**，不构成截图内容真实且不可伪造的证明。
- 截图内容仍需验收人员视觉复核，或在受控环境重新执行 GUI 流程。自动化返回 `COMPLETE` 只表示约定的机器可检条件完整，不能替代人工复核。
- `db-verify.json` 是结构化核对记录，不是不可伪造证明；其中断言仍需结合订单共键、只读 SQL 或可信日志独立复核。
- 当前 E1b full 在当前交付代码上未运行；每次 full 均必须取得用户对当次真实扣水的明确授权。历史批次仅作历史记录。

## 门禁（必须随代码一起跑）

`run-e1b.js` 与各跑批脚本的**安全闸与计数口径**已抽为可测模块，纳入常规门禁：

```bash
cd miniapp && pnpm test:e2e-gate      # 等价于 node --test e2e/*.test.js
```

覆盖以 `e2e/*.test.js` 的实际用例为准（行为断言完备性计数、批次落盘原语、各脚本安全闸）。CI 的 `miniapp` 作业强制该门禁通过。

## 链路跑批（无 GUI）

各链路跑批脚本都打隔离验收环境（后端 13340 / MySQL 3309），凭据从仓库根 `.env` 读取，
物理上到不了主库。前置与运行方式见 `deploy/acceptance/`：

```bash
cd deploy/acceptance && ./acc-env.sh rebuild && ./acc-env.sh backend-start
cd ../../miniapp/e2e && node run-card-lifecycle.js      # 其余链同理
```

各脚本以 `ACC_*` 环境变量接收地址与凭据，并写批次结果到 `docs/acceptance/<链名>/runs/`。

## E1b（扫码取水 GUI 走查）安全模式

E1b 不是日常命令。全部业务域恒接真实后端（按域 mock 分流基建已整体退役），E1b 要求由正式登录流程
建立的 KH_USER 会话；项目已删除按用户 ID 换 token 的 dev-auth，**不得为跑 E1b 恢复任何鉴权后门**。
脚本无默认模式：未设置模式、模式拼错或 full 缺二次确认时，会在连接工具及创建批次目录前以退出码 2 拒绝。

```bash
cd miniapp
pnpm build:mp-weixin

cd e2e && npm install
"/Applications/wechatwebdevtools.app/Contents/MacOS/cli" auto \
  --project "$(cd .. && pwd)/dist/build/mp-weixin" --auto-port 9420
```

| 模式 | 命令 | 业务副作用 | 产物与判定 |
|---|---|---|---|
| `diag` | `E1B_MODE=diag E1B_ORDER=<既有已完成单号> node run-e1b.js` | 无：只回放首页、订单列表和详情 | 写 `runs-diag/<批次>/`；只判 behavior，evidence=`N/A` |
| `shots` | `E1B_MODE=shots E1B_ORDER=<既有已完成单号> node run-e1b.js` | 无：只核验目标并截图 | 写 `runs-diag/<批次>/`；必须得到 3 张有效 PNG，evidence=`N/A` |
| `full` | `E1B_MODE=full E1B_ALLOW_REAL_WRITE=I_ACCEPT_REAL_WATER_DEDUCTION node run-e1b.js` | **有：真实建单、扣水并按实际水量退差** | 写 `runs/<批次>/`；前四步全过后才允许点击下单；初跑即使 7/7 也因尚缺同批 DB JSON 返回 2 |

`full` 只能在用户明确授权本次真实扣水、设备模拟器在线、数据库已备份并确认卡余额后执行。
`diag`/`shots` 必须使用既有订单号，不能通过模式名或缺省值退化为 full。

`scanCode`/`showModal` 通过 automator 的 `mockWxMethod` 驱动，扫码取水入口消费的是 `uni.scanCode`
的返回值。选图类交互（三照 chooseImage）不在自动化范围，需人工在开发者工具补查。

安全闸单测：`cd miniapp/e2e && npm run e1b:test`

## E1b full 证据闭合

full 行为走查完成后，在同一批次目录人工/脚本只读核对数据库，并写入非空 `db-verify.json`：

```json
{
  "schemaVersion": 1,
  "orderNo": "WO...",
  "actualMl": 4980,
  "assertions": {
    "orderExists": true,
    "orderStatusFinished": true,
    "commandLinked": true,
    "commandTerminal": true,
    "walletFlowContinuous": true,
    "cardBalanceMatchesLatestFlow": true,
    "actualMlMatchesOrder": true
  }
}
```

随后只对该批次重新计算证据：

```bash
node run-e1b.js --verify-evidence ../../docs/acceptance/miniapp-e1b/runs/<批次>
```

自动化候选通过的机器判据以 `e1b-core.js` 的 `evaluateFullEvidence` 为准；验证器写唯一的
`evidence-verification.json`，不以目录名、Markdown 文案、1×1 占位图或"文件存在"代替结构化记录。
该结果仍须按「证据能力边界」完成截图视觉复核和数据库独立核对，之后才能形成验收结论。
