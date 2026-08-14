# 微信小程序开发规则

## 适用范围

本文件适用于 `miniapp/` 及其子目录，补充根目录 `AGENTS.md` 和 `docs/development-workflow.md`。发生冲突时，以资金与设备安全铁律、用户当前明确指令和主工作流验收门为准。任务分类、业务链状态、授权边界与账号能力模型均以根 `AGENTS.md`、`docs/requirements/demo-business-chain-matrix.md` 与 `miniapp/README.md` 为准，本文件不复制。

技术基线固定为：unibest（uni-app + Vue 3 + TypeScript）+ Wot UI。接口契约以 `miniapp/src/api/common.ts`（`ApiEnvelope`、`EntityId` 为 string、金额整数分、水量整数毫升、业务时间 `yyyyMMddHHmmss`）与各 `miniapp/src/api/<domain>.ts` 为唯一可执行来源。

## 开发前必读

1. 根目录 `AGENTS.md`、`docs/development-workflow.md` 与 `docs/demo-module-status.md`。
2. 需求基线、对应 REQ ID 和 `docs/requirements/demo-business-chain-matrix.md` 中的业务链状态。
3. `miniapp/README.md` 中已经确认的信息架构、账号能力模型、页面职责、状态机和外部边界。
4. `miniapp/.agents/skills/wot-ui/SKILL.md` 全文。
5. 实际使用组件对应的 `miniapp/.agents/skills/wot-ui/references/*.md`。
6. 涉及设备交互时读取 `docs/mqtt-topics.md`，但小程序不得直接连接 MQTT 或代替服务端下发设备指令。

不得凭其他组件库经验猜测 Wot UI 的 props、events、slots、类型或平台兼容性。组件 API 以本地 Skill 参考为准；初始化命令、运行时版本和平台能力以实施时核验的官方资料为准。

## 账号、能力与数据范围

- 同一个 `ws_user/accountId` 是小程序**唯一账号主体**：用户、机主、配送三项能力可在同一账号并存，不建立互斥登录身份、不使用全局 `currentRole` 或身份选择页；固定一套 `首页 / 订单 / 我的` Tabbar，首页按 capability registry 分组投影入口。
- **同一账号不得配送自己的订单**：可接任务列表必须排除下单用户等于当前用户的任务，**接单接口还要重复校验**——只靠页面过滤等于没做。
- 前端能力投影只改善导航、不构成授权。接口侧过滤字段：用户按本人、机主按 `OWNER_USER_ID`、配送按准入状态 + 服务范围 + 任务版本 + `courierId`。页面参数、入口显隐与消息对象都不得扩大 Service 数据范围（根 `AGENTS.md` 铁律 6 的小程序落点）。

## 契约与鉴权底线

- 禁止在后端加入小程序测试账号直通、万能验证码、固定 token 或鉴权绕过。唯一例外是 `/mini/test-login/by-phone`，由独立开关 `mini.test-login.enabled` 门控（dev/prod 均缺省 false，缺省不注册路由，与 Pay-Sim 开关互相独立，见 D-425 与 `MiniTestLoginController`）；不得新增第二条这样的路。
- 页面必须清楚表达模拟边界，不得伪造真实扣款、退款、分账、设备执行、微信消息或服务端授权成功。

## Wot UI 使用规则

- 优先使用 Wot UI 完成基础组件、表单、反馈、导航和状态展示，不重复封装等价基础组件。
- 全局主题通过 Wot UI ConfigProvider 和主题变量统一管理；具体实现前读取 `config-provider.md` 与 `custom-theme.md`。
- 表单校验、上传、弹层、Toast、Tabbar 等组件必须读取各自参考文档后实现。
- 业务组件可以封装，但不得隐藏资金、设备、身份或履约状态的关键边界。

## D4 验收补充

通用 D4 判据见 `docs/development-workflow.md` D4，小程序另加两条（无其他文件承载）：

- 扫码、定位、上传、订阅消息等微信能力未接真时使用明确的契约状态，**不伪造平台成功结果**。
- 多能力可同时存在；能力撤销后入口和页面动作立即失效，**数据范围不串线**。
