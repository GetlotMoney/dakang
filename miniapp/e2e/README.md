# 小程序 D4 自动化走查（可复现）

对微信开发者工具做真实驱动的自动化验收走查，产出结果与截图到 `docs/acceptance/miniapp-d4/`。

## 证据能力边界

- PNG 结构、尺寸、CRC、哈希、目标路径、关键文本、构建 fingerprint 与脚本版本检查，只是**防空文件、占位小图、旧构建和意外篡改的证据完整性校验**，不构成截图内容真实且不可伪造的证明。
- E1b/D4 截图内容仍需验收人员视觉复核，或在受控环境重新执行 GUI 流程。自动化返回 `COMPLETE` 只表示约定的机器可检条件完整，不能替代人工复核。
- `db-verify.json` 是结构化核对记录，不是不可伪造证明；其中断言仍需结合订单共键、只读 SQL 或可信日志独立复核。
- 当前 D4 GUI 证据尚未闭合。当前交付代码上的 E1b full 未运行，且正式微信登录尚未接入；未来即使会话就绪，每次 full 仍必须取得用户对当次真实扣水的明确授权。历史批次仅作历史记录。

## 前置条件

1. 微信开发者工具已安装并开启服务端口（设置 → 安全设置 → 服务端口）。
2. D4 必须使用**全域 Mock 构建**。当前日常 `pnpm build:mp-weixin` 已是全域 Mock；下面仍显式写出七项变量（含 auth/delivery），防本地 shell 残留覆盖。

## 运行

```bash
cd miniapp
VITE_API_MODE=mock \
VITE_API_MODE_DEVICE=mock \
VITE_API_MODE_ORDER=mock \
VITE_API_MODE_CARD=mock \
VITE_API_MODE_RECHARGE=mock \
VITE_API_MODE_AUTH=mock \
VITE_API_MODE_DELIVERY=mock \
pnpm build:mp-weixin

cd miniapp/e2e
npm install
"/Applications/wechatwebdevtools.app/Contents/MacOS/cli" auto \
  --project "$(cd .. && pwd)/dist/build/mp-weixin" --auto-port 9420
npm run d4:behavior   # 业务断言，不截图
npm run d4            # 完整取证：业务断言 + 12 张截图
```

`d4:behavior` 写 `docs/acceptance/miniapp-d4/behavior-report.md`，只判业务行为；`d4` 写 `report.md` 与 `shots/`，只有固定 23 项业务断言全部通过、12 张目标路径/关键文本核验后的有效 PNG 全部取得且文件哈希未变化才退出 0。业务全过但截图不完整时退出 2，不能写成 D4 封板通过。

## 门禁（必须随代码一起跑）

`run-d4.js` / `run-e1b.js` 的**安全闸与计数口径**已抽为可测模块，纳入常规门禁——本仓曾两次因 e2e 脚本不在任何门禁内而让回归（指纹段数、行为断言计数）无人发现：

```bash
cd miniapp && pnpm test:e2e-gate      # 等价于 node --test e2e/*.test.js
# 或在 e2e 目录：npm test
```

覆盖：模式指纹逐域断言（正确 7 段放行 / 缺 AUTH 段拦截 / 未登记域拦截 / 值不符拦截）、D4 `behaviorPass` 完备性计数（等于常量 true、少一项或多一项 false、异常路径补记项不计入）。入口页新增业务域或 D4 增删断言时，本门禁会先变红。

## 边界

- `run-d4.js`（Mock Demo 走查）要求显式 `D4_MODE=behavior|full`，且只允许全域 Mock 适配器与固定原型数据（`touristappid` 游客模式）。脚本在任何业务步骤前同时核对运行包/磁盘构建 fingerprint 与七项 API 模式（GLOBAL/DEVICE/ORDER/CARD/RECHARGE/AUTH/DELIVERY，逐域断言）；任一域为 real、期望段缺失、出现未登记新域或 IDE 缓存旧包即 0/1 FAIL 并退出，不触达真实后端、不点击真实下单。
- `run-e1b.js`（扫码取水验收）只允许 `device/order/card=real、recharge/delivery=mock、auth=real` 的显式验收构建。`auth=real` 是必需项：E1b 要求由正式登录流程建立的 KH_USER 会话，而入口页在「有业务域接真但 auth=mock」时会拒绝以 Mock 原型账号进入并停在登录入口，因此只有 auth=real 才能既走到业务步骤又不借用原型身份。项目已删除按用户 ID 换 token 的 dev-auth，不得为跑 E1b 恢复任何鉴权后门；真实微信凭据未配置前 E1b 仍不可执行。脚本无默认模式：未设置模式、模式拼错或 full 缺二次确认时会在连接工具及创建批次目录前以退出码 2 拒绝。
- `showActionSheet/showModal` 通过 automator 的 mockWxMethod 驱动，选图类交互（三照 chooseImage）不在自动化范围，需人工在开发者工具补查。

## E1b 安全模式

E1b 不是日常 Demo 命令。只有正式会话已经建立、用户明确授权当次真实写入时，才先构建验收包：

```bash
cd miniapp
VITE_API_MODE=mock \
VITE_API_MODE_DEVICE=real \
VITE_API_MODE_ORDER=real \
VITE_API_MODE_CARD=real \
VITE_API_MODE_RECHARGE=mock \
VITE_API_MODE_AUTH=real \
VITE_API_MODE_DELIVERY=mock \
pnpm build:mp-weixin
```

E1b 完成后必须重新运行普通 `pnpm build:mp-weixin`，恢复全域 Mock 日常交付包。

| 模式 | 命令 | 业务副作用 | 产物与判定 |
|---|---|---|---|
| `diag` | `E1B_MODE=diag E1B_ORDER=<既有已完成单号> node run-e1b.js` | 无：只回放首页、订单列表和详情 | 写 `runs-diag/<批次>/`；只判 behavior，evidence=`N/A` |
| `shots` | `E1B_MODE=shots E1B_ORDER=<既有已完成单号> node run-e1b.js` | 无：只核验目标并截图 | 写 `runs-diag/<批次>/`；必须得到 3 张有效 PNG，evidence=`N/A` |
| `full` | `E1B_MODE=full E1B_ALLOW_REAL_WRITE=I_ACCEPT_REAL_WATER_DEDUCTION node run-e1b.js` | **有：真实建单、扣水并按实际水量退差** | 写 `runs/<批次>/`；前四步全过后才允许点击下单；初跑即使 7/7 也因尚缺同批 DB JSON 返回 2 |

`full` 只能在用户明确授权本次真实扣水、设备模拟器在线、数据库已备份并确认卡余额后执行。`diag`/`shots` 必须使用既有订单号，不能通过模式名或缺省值退化为 full。

安全闸单测：

```bash
cd miniapp/e2e
npm run e1b:test
```

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

自动化候选通过必须同时满足：固定步骤逐项 7/7 且无 fatal、运行页面 fingerprint 等于磁盘构建 fingerprint、API 模式逐域精确为 device/order/card=real、recharge/delivery=mock、auth=real、脚本/core SHA 与当前验证器一致、三张截图为尺寸不小于 200×300 且 CRC/目标路径/关键文本/哈希均有效的 PNG、`db-verify.json` 七项断言全部为 true、DB `actualMl` 与页面结构化水量一致。验证器写唯一的 `evidence-verification.json`，不以目录名、Markdown 文案、1×1 占位图或“文件存在”代替结构化记录。该结果仍须按“证据能力边界”完成截图视觉复核和数据库独立核对，之后才能形成验收结论。
