# 六维达康共享健康水站平台

六维达康是面向社区自助售水场景的多端业务平台，包含 PC 运营后台、微信小程序、设备接入服务以及 MySQL、Redis、EMQX 基础设施。

## 一期业务进度

一期规划为 8 条宏观业务链。当前已在受控模拟环境完成 3 条，另外 5 条待实施。详细需求、证据和边界统一维护在 `docs/requirements/demo-business-chain-matrix.md`。

| 编号 | 业务链 | 当前状态 |
|---|---|---|
| E2E-01 | 扫码取水 | 已完成（协议模拟器） |
| E2E-02 | 水卡生命周期：购卡、充值、成员授权 | 已完成（Pay-Sim） |
| E2E-03 | 水配送：下单、履约、签收、申诉、裁决 | 已完成（受控媒体与人工配送模拟） |
| E2E-04 | 售后退款与补偿 | 待开工，下一优先级 |
| E2E-05 | 设备运营与运维 | 待开工 |
| E2E-06 | 机主经营与服务 | 待开工 |
| E2E-07 | 消息、告警与工单 | 待开工 |
| E2E-08 | 支付、对账与分账 | 待开工 |

“已完成”指接口、状态机、数据库、内部流水和跨端回看在模拟环境中完成验证；正式微信支付、物理设备和生产基础设施按各链路的外部依赖单独验收。

## 系统范围

- PC 运营后台：运营总览、水站、设备、水种套餐、用户、订单中心和系统管理。
- 微信小程序：统一账号入口，支持用水、配送和机主能力叠加。
- 设备接入：基于 EMQX 的 MQTT 心跳、遥测、指令、ACK 和 result 通道。
- 数据服务：MySQL 8、Redis 7、Spring Boot 3.5、MyBatis-Plus。

## 安全说明

本仓库不含任何真实凭据：数据库、Redis、MQTT、JWT、RSA 与微信密钥全部经环境变量注入。

- 仓库根 `.env` 是全仓唯一的口令来源，**不入库**；模板见 `.env.example`。
- `deploy/docker-compose.yml` 里带 `:?` 的变量是强制项，缺失即拒绝启动，不会静默用空口令跑起来。
- 数据库、Redis、EMQX 只绑定 `127.0.0.1`，不暴露到局域网；后端 13330 与 PC 8081 保持全网卡是为了小程序真机联调，生产应改回回环并由反向代理承接 TLS。
- `deploy/mysql/init/01-base.sql` 与 `client/.env.demo` 中的 Demo 管理员口令（及其 RSA 密文）与源码内置的 Demo 密钥对配套，公开即等同明文，**只可用于本机演示**。任何对外可达的部署都必须重置管理员口令、更换 RSA 密钥对，并关闭 `client/.env.demo` 的自动填充。
- `MINI_PAYSIM_ENABLED` 默认关闭。它同时门控模拟支付与测试登录入口，生产环境不注册这两条路由。

## 首次启动

源码交付包不包含依赖和构建产物。首次解压后执行：

```bash
cp .env.example .env        # 填入本机口令后再继续，否则容器会拒绝启动

cd client
pnpm install --frozen-lockfile

cd ../miniapp
pnpm install --frozen-lockfile

cd ../deploy
./build-all.sh
```

本机要求：Docker、Java 17、Maven、Node.js 20.19+、pnpm 8.8+。

小程序真机联调另需 `miniapp/env/.env.development`（不入库，模板见同目录 `.env.development.example`）：填本机局域网 IP 与自己的小程序 AppID。

## 运行入口

| 服务 | 地址 |
|---|---|
| PC 运营后台 | http://localhost:8081/#/dashboard/console |
| 后端 API | http://localhost:13330/dakangApi |
| Knife4j | http://localhost:13330/dakangApi/doc.html |
| EMQX 管理台 | http://localhost:18083 |
| MySQL | 127.0.0.1:3308（仅本机） |
| Redis | 127.0.0.1:6380（仅本机） |

前端开发端口 `13321` 仅用于热更新，不作为验收入口。

## 开发文档

| 范围 | 文档 |
|---|---|
| 项目规则 | `AGENTS.md`、`docs/development-workflow.md` |
| 当前状态 | `docs/demo-module-status.md`、`docs/requirements/demo-business-chain-matrix.md` |
| 运行部署 | `docs/runtime-entrypoints.md` |
| 需求与决策 | `docs/requirements/README.md`、`docs/requirements/requirements-pool.csv`、`docs/requirements/decisions.md` |
| 业务契约 | `docs/contracts/L2-recharge-contract-v2.md`、`docs/contracts/H3-water-exception-contract.md` |
| 设备协议 | `docs/mqtt-topics.md` |
| 小程序 | `miniapp/AGENTS.md`、`miniapp/README.md`、`miniapp/e2e/README.md` |
| 验收证据 | `docs/acceptance/card-lifecycle/README.md` |

开发具体模块前还必须阅读对应端的 `.agents/skills/*/SKILL.md`。

## 源码交付

```bash
./tools/package-source.sh
```

脚本生成 `dakang-phase1-source-时间戳.zip`，并自动排除 Git 元数据、依赖、构建产物、备份、日志和本机私有配置。

## 构建模式

日常小程序构建默认使用 Mock 适配器，便于页面开发和演示。内部链路验收使用独立受控配置连接本地后端、Pay-Sim 或设备模拟器；相关开关不得用于生产环境。
