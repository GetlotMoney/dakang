# 六维达康本地运行入口（唯一口径）

> 本文件统一说明 PC、开发服务和后端 API 的端口职责，避免把不同入口误认为多个后台项目。

## 微信小程序入口状态

`miniapp/` 已完成一期页面基座。**全部业务域恒接真实后端**（Mock 基建已于 2026-08-02 整体退役，无 mock 兜底）；链路验收按域连接隔离验收环境、Pay-Sim 或设备模拟器。正式微信能力和物理设备尚未接入，测试账号能力只允许在受控验收开关下使用。业务链进度见 `requirements/demo-business-chain-matrix.md`，本文不复制状态。小程序不复用 PC 的 `8081`、`13321` 作为页面入口；`13330` 仅作为联调 API 后端。

| 用途 | 唯一命令 / 目录 | 验收边界 |
|---|---|---|
| 首次安装 | `cd miniapp && pnpm install --frozen-lockfile` | Node.js ≥20、pnpm ≥9 |
| H5 辅助预览 | `pnpm dev:h5` → `http://localhost:9000` | 只检查布局，不代表微信小程序验收 |
| 微信开发构建 | `pnpm dev:mp-weixin` → `dist/dev/mp-weixin` | 导入微信开发者工具进行开发调试 |
| 类型检查 | `pnpm type-check` | 必须无 TypeScript 错误 |
| 代码规范 | `pnpm lint` | 必须无 ESLint 错误 |
| 契约测试 | `pnpm test` | 路由、能力与运行态模式规则回归 |
| H5 生产构建 | `pnpm build:h5` | 仅作为补充构建检查 |
| 微信生产构建 | `pnpm build:mp-weixin` → `dist/build/mp-weixin` | production 模式只读 `env/.env`（AppID=touristappid）；**验收产物须用 `dev:mp-weixin`**，真实 AppID 只在不入库的 `.env.development.local` |
| E1b GUI 走查构建 | 见 `miniapp/e2e/README.md`；须显式传逐域模式变量（`device/order/card=real、recharge/delivery=mock、auth=real`） | 运行态再次校验 fingerprint 与逐域模式断言；行为/截图双结论。**注**：旧「D4 全域 Mock 走查」已随 Mock 基建退役删除 |

当前微信 AppID 为 `touristappid`。接真实微信登录、手机号、隐私和订阅消息前必须替换为甲方 AppID 并重新评审契约。小程序最终 D4 仍以微信开发者工具中的实际编译、页面栈和交互为准。

## 入口判定

| 地址                                         | 角色                     | 是否用于本地验收 | 说明                                                                                |
| -------------------------------------------- | ------------------------ | ---------------- | ----------------------------------------------------------------------------------- |
| `http://localhost:8081/#/dashboard/console`  | **PC 一期本地验收入口**  | **是，唯一入口** | Docker Nginx 承载已构建静态文件，并同源反代后端；所有最终页面检查和截图以这里为准   |
| `http://localhost:13321/#/dashboard/console` | Vite 开发预览        | 否                 | 仅在执行 `cd client && pnpm dev`、需要 HMR 时使用；页面顶部会显示“开发预览 · 13321” |
| `http://localhost:13330/dakangApi/`          | Spring Boot API      | 否                 | 机器接口与 Knife4j 文档，不是 PC 页面                                               |

因此，`8081` 与 `13321` 不是两套业务后台：它们读取同一份 `client/` 源码的不同产物。前者是已经构建并装入 Nginx 的验收基线，后者是开发进程即时转换的源码预览。`13330` 则完全是后端 API。

## 使用流程

1. 了解项目、核对现状、复现用户页面问题时，先打开 `http://localhost:8081/#/dashboard/console`。
2. 只有正在修改前端并需要热更新时，才使用 `13321`；不得用它代表已部署结果。
3. 前端修改完成后执行构建并同步到 `deploy/dist/client/`，最终 D4 验收必须回到 `8081`。
4. PC 端使用 Hash 路由，页面地址必须含 `/#/`。`13330` 的任何地址都不能作为页面地址。
5. 统一使用 `localhost`，不与 `127.0.0.1` 混用。
6. 可运行 `./tools/runtime-status.sh` 核对监听者和响应头。`X-Dakang-Entry: pc-demo-canonical` 才表示进入了基线页面；`vite-dev-only` 表示只是开发预览。
7. `build-all.sh` 会替换带内容哈希的静态分包。若浏览器在部署前已经打开，部署完成后必须先强制刷新再验证深链；旧页面请求旧哈希文件出现 404 不代表业务路由缺失。

## 为什么菜单状态可能只在一个端口出现

浏览器把 localStorage 按 origin 隔离，端口也是 origin 的一部分。因此 `localhost:8081` 与 `localhost:13321` 各有一份登录态和后端菜单缓存。菜单契约更新后，某个端口仍可能保存与当前组件不一致的旧会话。

当前采用两层一致性保障机制：

- 后端显式过滤已软删除的角色和菜单；
- 前端给持久化登录态绑定导航契约版本。契约升级时旧端口缓存会自动失效并要求重新登录，无需用户手工清理。

## 8081 固定快照与设备监控 Worker

`8081` 用于页面定型验收，需要在线/离线、正常/故障样例在演示期间保持稳定。因此容器版后端显式设置 `DAKANG_DEVICE_MONITOR_ENABLED=false`，不会在没有设备模拟器持续心跳时把固定在线样例自动改成离线。

这不是取消真实规则：开发或真实环境未设置该变量时默认启用监控，按 `docs/mqtt-topics.md` 的 90 秒心跳阈值执行离线判定。验证真实在线状态机时，应启动 `tools/device-sim/` 持续上报，再在开发/联调环境开启 Worker；不得用 8081 的固定快照证明 MQTT 心跳真实闭环。

## 常用命令

```bash
# 无产物源码包首次启动（交付包不含 node_modules、dist 或 JAR）
cd client && pnpm install --frozen-lockfile
cd ../deploy && ./build-all.sh

# 查看三端口的职责、监听状态和响应标识
./tools/runtime-status.sh

# 前端开发预览（仅开发期间）
cd client && pnpm dev

# 完整构建并更新 8081 基线
cd deploy && ./build-all.sh
```

`build-all.sh` 会先生成后端 JAR 与 PC 静态文件，再创建或重建对应容器。无产物目录不要先执行全量 `docker compose up -d`；若只需要提前启动基础设施，应使用 `docker compose up -d mysql redis emqx`。
