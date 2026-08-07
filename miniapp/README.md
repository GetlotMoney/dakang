# 六维达康微信小程序

技术基线：unibest 4.4.1、uni-app、Vue 3、TypeScript、Wot UI 1.14.0（`wot-design-uni`）。

本文只回答「装什么、跑什么、验什么」。**实现状态与业务链进度不在这里**：见
`../docs/demo-module-status.md` 与 `../docs/requirements/demo-business-chain-matrix.md`。
写代码前必读 `AGENTS.md` 与 `.agents/skills/wot-ui/SKILL.md`。

## 安装与运行

```bash
pnpm install --frozen-lockfile

pnpm dev:mp-weixin     # 微信开发构建 → dist/dev/mp-weixin（用微信开发者工具打开这个目录）
pnpm dev:h5            # H5 布局预览，不能替代微信验收

pnpm type-check && pnpm lint && pnpm test
pnpm build:mp-weixin   # 生产构建 → dist/build/mp-weixin
```

要求 Node.js ≥20、pnpm ≥9（仓库钉 10.10.0）。

**验收产物必须用 `dev:mp-weixin` 构建**：`build:mp-weixin` 走 production 模式只读 `env/.env`
（AppID 是 `touristappid`），真实 AppID 只在不入库的 `env/.env.development.local` 里。
接入正式微信登录、手机号、隐私、支付与订阅消息前，需替换为甲方 AppID 并完成契约评审。

**构建纪律**（2026-08-01 产物污染事故）：`dev:mp-weixin` 是 watch 进程，杀 pnpm 包装进程不会
杀掉真正的编译器。构建前后都要确认 `pgrep -f "vite-plugin-uni/bin/uni.js"` 为 0，有残留先
`pkill -f` 清掉；"Build complete" 只表示编译完成、不代表落盘完毕。产物核验看内容
（`build-fingerprint.json`、`api/runtime.js` 的模式值、AppID、后端 IP），不看构建日志。

## 数据源

全部业务域**恒接真实后端**。Mock 基建已于 2026-08-02 整体退役——mock 适配器与场景库均已删除，
把任何域改回 `mock` 不会有实现兜底，只会得到构建期错误或空能力。
`env/.env.development` 是已入库的公开安全档，本机联调请改 `env/.env.development.local`。

## 账号与导航

`ws_user` 是唯一账号主体，同一账号按授权叠加能力，不建立角色选择页或互斥身份：

| 能力 | 来源 | 主要功能 |
|---|---|---|
| `USER_BASE` | 有效用户账号 | 扫码取水、水卡、充值、配送下单、订单和申诉 |
| `COURIER_APPLY` | 有效用户账号 | 提交配送员准入申请 |
| `COURIER_WORK` | 已启用的配送员记录 | 可接任务、本人任务、履约、三照和举证 |
| `OWNER_VIEW` | 水站或设备机主归属 | 经营概览、设备和交易视图 |
| `OWNER_SERVICE` | 机主服务授权 | 报修和配件申请 |

前端能力只负责入口展示，后端按用户、机主归属、配送准入和任务归属执行最终授权。
同一账号不能配送自己的订单，列表与接单接口均须校验。

固定三项 Tabbar：**首页**（用水/配送/经营入口）、**订单**（取水/充值/配送）、
**我的**（账号、水卡、成员、地址、配送准入、退出）。首页可按能力与待办调整内容顺序，
但不得改变登录身份、权限或 Tabbar。

## 页面编号

代码注释与验收脚本大量引用 `U01`/`D03`/`O02` 这类页面编号，**本表是它们唯一的定义源**。
路由本身以 `src/pages.json`（由 `scripts/create-base-files.mjs` 生成）为准。

| ID | 路由 | ID | 路由 |
|---|---|---|---|
| C01 | `pages/entry/index` 登录与入口 | U09 | `pages/user/appeal/create` 配送申诉 |
| C02 | `pages/message/index` 消息列表 | U10 | `pages/user/recharge/index` 购卡与充值 |
| C03 | `pages/message/detail` 消息详情 | U11 | `pages/user/card/detail` 水卡详情 |
| U01 | `pages/user/home/index` 首页 | U12 | `pages/user/card/member-form` 成员授权 |
| U02 | `pages/user/order/index` 订单列表 | U13 | `pages/user/family/index` 家庭资料 |
| U03 | `pages/user/profile/index` 我的 | U14 | `pages/user/address/index` 地址列表 |
| U04 | `pages/user/water/confirm` 取水确认 | U15 | `pages/user/address/edit` 地址维护 |
| U05 | `pages/user/water/progress` 取水进度 | D01 | `pages/courier/task/index` 任务列表 |
| U06 | `pages/user/order/detail` 订单详情 | D02 | `pages/courier/admission/index` 配送员准入 |
| U07 | `pages/user/station/index` 水站目录 | D03 | `pages/courier/task/detail` 任务详情 |
| U08 | `pages/user/delivery/create` 配送下单 | D04 | `pages/courier/task/sign` 三照签收 |
| O01 | `pages/owner/overview/index` 经营概览 | D05 | `pages/courier/task/exception` 配送异常 |
| O02 | `pages/owner/device/index` 机主设备 | O04 | `pages/owner/transaction/index` 交易收益 |
| O03 | `pages/owner/device/detail` 设备详情 | O05 | `pages/owner/service/index` 报修配件 |

## 验收与门禁

```bash
pnpm test:e2e-gate     # 常规门禁，CI 强制通过
```

GUI 走查与链路跑批脚本、以及它们的安全开关见 `e2e/README.md`。连接真实业务域的验收必须使用
显式运行模式和二次确认；不得把 H5 预览或历史截图当作当前微信验收结论。
