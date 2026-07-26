# 技术演进记录

## 版本类型说明

| 前缀 | 对象 | 说明 |
|---|---|---|
| `SYS-*` | 系统与运行配置 | 后端、前端、小程序、部署和安全配置 |
| `UI-*` | 页面与视觉 | PC 与小程序页面视觉版本 |
| `DATA-*` | 数据与数据库 | 表结构、初始化数据和迁移版本 |
| `DOC-*` | 交付文档 | 分析、说明和其他文档版本 |

不同前缀只描述各自对象，不能互相比较大小。

## 当前版本一览

| 对象 | 当前版本 | 当前能力或内容 |
|---|---|---|
| 一期系统 | `SYS-V1.0`（原项目“一期 V1.0”） | PC、小程序、后端、MQTT 与本地五容器骨架 |
| 公开仓库配置 | `SYS-CONFIG-V1` | 凭据由环境变量注入，端口默认仅绑定本机，普通小程序开发全域 Mock |
| 项目分析 | `DOC-PROJECT-ANALYSIS-V1` | 2026-07-25 的源码、需求、运行与风险分析 |

## SYS-CONFIG-V1

- 日期：2026-07-26
- 状态：已完成
- 本次改的是哪个对象：公开仓库的运行与敏感配置
- 目标问题：避免将数据库、Redis、MQTT、JWT、RSA 和测试账号凭据永久写入公开 Git 历史
- 采用技术：Spring 环境变量占位符、Docker Compose 必填变量、根目录 `.env.example`、回环地址端口绑定、Mock 默认值
- 替换了什么：替换源码包中的固定凭据、RSA 密钥、Demo 自动填充账号和普通开发环境 Real 默认值
- 实际可见效果：克隆后必须先创建本机 `.env`；未配置凭据时 Compose 明确失败；普通小程序开发不会连接真实业务接口
- 选择原因：公开仓库的提交历史无法可靠收回，首个提交必须从源头排除敏感值
- 已知限制：当前交付包缺少 `server/src/main/java/com/jbk/tool/data/`，后端仍无法独立编译；员工密码仍是可逆加密架构，生产前应迁移为单向哈希
- 素材位置：`.env.example`、`server/src/main/resources/application*.yml`、`deploy/docker-compose.yml`
- 验证命令与结果：
  - 本地敏感信息规则扫描：通过，0 个命中
  - `python tools/check-demo-baseline.py`：通过
  - `docker compose -f deploy/docker-compose.yml config --quiet`：通过
  - `docker compose -f deploy/acceptance/docker-compose.acc.yml config --quiet`：通过
  - `node --test miniapp/e2e/*.test.js`：31/31 通过
  - `node --check tools/device-sim/sim.js tools/device-sim/pub-result.js miniapp/e2e/run-card-lifecycle.js`：通过
- 回退方式：保留原始非 Git 工作副本；如配置修改影响本地联调，从原始副本恢复到私有环境，但不得把旧密钥重新提交到 Git
