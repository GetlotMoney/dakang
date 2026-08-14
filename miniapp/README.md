# 六维达康微信小程序

技术基线：unibest 4.4.1、uni-app、Vue 3、TypeScript、Wot UI 1.14.0（`wot-design-uni`）。

本文只回答「装什么、跑什么、验什么」。**实现状态与业务链进度不在这里**：见
`../docs/demo-module-status.md` 与 `../docs/requirements/demo-business-chain-matrix.md`。
写代码前必读 `AGENTS.md` 与 `.agents/skills/wot-ui/SKILL.md`。

## 安装与运行

```bash
pnpm install --frozen-lockfile

pnpm dev:mp-weixin     # 微信开发 watch → dist/dev/mp-weixin（用微信开发者工具打开这个目录）
pnpm dev:h5            # H5 布局预览，不能替代微信验收

pnpm type-check && pnpm lint && pnpm test
pnpm build:mp-weixin   # 生产构建 → dist/build/mp-weixin

# 验收用一次性构建：产物同样落 dist/dev/mp-weixin，但编译完即退出，可核对进程归零
UNI_OUTPUT_DIR="$PWD/dist/dev/mp-weixin" pnpm exec uni build -p mp-weixin --mode development
```

要求 Node.js ≥20、pnpm ≥9（仓库钉 10.10.0）。

**验收产物必须走 development 模式**：`build:mp-weixin` 是 production，只读 `env/.env`
（AppID 是 `touristappid`），真实 AppID 只在不入库的 `env/.env.development.local` 里。
正式微信登录与手机号验证已接入（甲方 AppID 已固定）；微信支付与订阅消息尚未接入。

`--mode development` 只切换 env 加载，**不切换输出目录**：`uni build` 会把 `NODE_ENV` 无条件
置为 production（见 `@dcloudio/vite-plugin-uni/dist/cli/utils.js`，那里还留着 `// TODO 需要识别 mode`），
产物默认落 `dist/build/`。所以必须显式给 `UNI_OUTPUT_DIR`，否则去 `dist/dev/` 核验，看的是上一次
watch 留下的旧产物。

**构建纪律**（2026-08-01 产物污染事故）：`dev:mp-weixin` 是 watch 进程，杀 pnpm 包装进程不会
杀掉真正的编译器。验收用上面那条一次性构建，构建前后都要确认
`pgrep -f "vite-plugin-uni/bin/uni.js"` 为 0，有残留先 `pkill -f` 清掉；"Build complete" 只表示
编译完成、不代表落盘完毕。产物核验看内容（`build-fingerprint.json` 指纹是否变新、AppID、后端 IP），不看构建日志。

## 数据源

全部业务域**恒接真实后端**，无任何 mock 分流（口径见 `env/.env` 与 `src/api/runtime.ts` 头注释）。
`env/.env.development` 是已入库的公开安全档，本机联调请改 `env/.env.development.local`。
其中 `VITE_SERVER_BASEURL` 是构建期烧进产物的局域网地址：本机 IP 漂移后必须先改该文件再重建，
否则真机连不上后端（用 `ipconfig getifaddr en0` 核对；`pnpm dev/build` 的构建前置钩子会自动校正）。

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

代码注释与验收脚本引用的 `U01`/`D03`/`M01` 这类页面编号，定义源是 `src/router/routes.ts` 的
`RouteId` 联合类型与 `appRoutes`（`routes.test.ts` 常态断言路由总数与结构）；
路由文件 `src/pages.json` 由 `scripts/create-base-files.mjs` 生成。

## 验收与门禁

```bash
pnpm test:e2e-gate     # 常规门禁，CI 强制通过
```

GUI 走查与链路跑批脚本、以及它们的安全开关见 `e2e/README.md`。连接真实业务域的验收必须使用
显式运行模式和二次确认；不得把 H5 预览或历史截图当作当前微信验收结论。
