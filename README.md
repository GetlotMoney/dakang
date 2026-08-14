# 六维达康共享健康水站平台

社区自助售水的多端业务平台：PC 运营后台、微信小程序、设备 MQTT 接入，以及 MySQL / Redis / EMQX 基础设施。

**上线前的主要阻塞**：正式微信支付、物理售水设备、生产基础设施三类外部能力**均未接入**，
当前全部运行在受控模拟环境上。业务链的逐条范围与完成状态见 `docs/requirements/demo-business-chain-matrix.md`。

## 系统范围

- **PC 运营后台**：按业务职责投影一级工作区，入口清单以 `client/src/config/businessNavigation.ts` 为准。
- **微信小程序**：统一账号入口，用水为基础能力，可叠加机主与配送能力。
- **设备接入**：基于 EMQX 的 MQTT 心跳、遥测、指令、ACK 与 result 通道。
- **技术栈**：Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis 7；前端 Vue 3 + Element Plus；小程序 unibest + Wot UI。

## 首次启动

```bash
cp .env.example .env        # 填入本机口令后再继续，否则容器会拒绝启动

cd client && pnpm install --frozen-lockfile
cd ../miniapp && pnpm install --frozen-lockfile
cd ../deploy && ./build-all.sh
```

本机要求：Docker、Java 17、Maven、Node.js ≥20.19、**pnpm ≥9**（小程序端要求 9+，仓库钉 10.10.0）。

`build-all.sh` 只构建后端与 PC 并重启容器。**小程序产物需单独构建**，且必须用 dev 模式
（生产模式读不到真实 AppID）：

```bash
cd miniapp && pnpm dev:mp-weixin      # 产物在 dist/dev/mp-weixin，用微信开发者工具打开
```

真机联调另需 `miniapp/env/.env.development.local`（填本机局域网 IP 与 AppID；模板与 `.local` 纪律见同目录 `.env.development.example`）。

启动后的访问地址与端口职责见 `docs/runtime-entrypoints.md`（唯一口径）。

## 安全须知

全部口令只存在于不入库的仓库根 `.env`（模板 `.env.example` 列出全部变量与用途），源码内不保留任何密钥。
以下模拟开关默认关闭，**误开会产生真实资金后果**，仅限本机演示与受控验收：

- `MINI_PAYSIM_ENABLED`：模拟支付。测试登录端点由**独立开关** `MINI_TEST_LOGIN_ENABLED` 门控——关掉 Pay-Sim 并不会关掉测试登录，反之亦然。
- `MINI_REFUNDSIM_ENABLED`：模拟退款——误开会把模拟退款按真实微信退款记账。

`MINI_PHONELESS_REGISTER_ENABLED` 不属于模拟开关：生产取 `true`（游客态建号，可自助补绑手机号），口径见 `.env.example` 的 D-426 说明。
但它是**单向门**——产生 NULL 手机号账号后库结构无法改回 NOT NULL（放开非空的迁移见
`deploy/mysql/migrations/2026-08-01-wechat-phoneless-register.sql`，无反向迁移），不要当成可来回翻的开关。

已知并已接受的风险、以及上线前必须完成的事项，见 `docs/demo-module-status.md` 的风险表。
生产部署须更换 RSA 密钥对、重置管理员口令、将端口改回回环并由反向代理承载 TLS。

## 文档去哪查

| 我要…… | 看这份 |
|---|---|
| 知道现在做到哪一步、还缺什么 | `docs/demo-module-status.md` |
| 查某条业务链/需求的范围与状态 | `docs/requirements/demo-business-chain-matrix.md` |
| 知道端口、入口地址与验收口径 | `docs/runtime-entrypoints.md` |
| 查产品决策为什么这么定 | `docs/requirements/decisions.md` |
| 查充值/异常取水/商城的接口契约 | `docs/contracts/L2-recharge-contract-v2.md`、`docs/contracts/H3-water-exception-contract.md`、`docs/contracts/E2E-09-mall-contract.md` |
| 查设备通信协议 | `docs/mqtt-topics.md` |
| 看还等谁答复才能推进 | `docs/requirements/pending-client-confirmations.md`（甲方）、`docs/requirements/pending-vendor-specs.md`（设备厂家） |

**参与开发前**必读 `AGENTS.md`（项目总则与安全铁律）与 `docs/development-workflow.md`（阶段与验收门），
以及对应端的 `.agents/skills/*/SKILL.md`。

## 源码交付

```bash
./tools/package-source.sh
```

自动排除 Git 元数据、依赖、构建产物、数据库备份、日志与本机私有配置，并在打包前后各跑一次基线检查。
