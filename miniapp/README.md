# 六维达康微信小程序

技术基线：unibest 4.4.1、uni-app、Vue 3、TypeScript、Wot UI 1.14.0（`wot-design-uni`）。当前实现状态见 `../docs/demo-module-status.md`，宏观业务链状态见 `../docs/requirements/demo-business-chain-matrix.md`。

日常页面开发可使用全域 Mock；受控验收构建按业务域连接本地后端、Pay-Sim 或设备模拟器。E2E-01 扫码取水、E2E-02 水卡生命周期和 E2E-03 水配送已完成模拟环境验收；正式微信支付、物理设备和生产基础设施尚未接入。

## 1. 安装、构建与验收

```bash
# 首次安装
pnpm install --frozen-lockfile

# H5 布局预览，不能替代微信验收
pnpm dev:h5

# 微信小程序开发构建
pnpm dev:mp-weixin

# 类型、代码规范和单元测试
pnpm type-check
pnpm lint
pnpm test

# 生产构建
pnpm build:mp-weixin
pnpm build:h5
```

微信开发者工具导入 `dist/dev/mp-weixin`。当前 AppID 为 `touristappid`；接入正式微信登录、手机号、隐私、支付和订阅消息前，必须替换为甲方 AppID 并完成对应契约评审。

自动化脚本和安全开关见 `e2e/README.md`。D4 只运行全域 Mock；连接真实业务域的验收必须使用显式运行模式和二次确认，不得将 H5 预览或历史截图作为当前微信验收结论。

## 2. 账号与导航

`ws_user` 是唯一账号主体，同一账号按授权叠加能力，不建立角色选择页或互斥身份：

| 能力 | 来源 | 主要功能 |
|---|---|---|
| `USER_BASE` | 有效用户账号 | 扫码取水、水卡、充值、配送下单、订单和申诉 |
| `COURIER_APPLY` | 有效用户账号 | 提交配送员准入申请 |
| `COURIER_WORK` | 已启用的配送员记录 | 可接任务、本人任务、履约、三照和举证 |
| `OWNER_VIEW` | 水站或设备机主归属 | 经营概览、设备和交易视图 |
| `OWNER_SERVICE` | 机主服务授权 | 报修和配件申请 |

前端能力只负责入口展示，后端按用户、机主归属、配送准入和任务归属执行最终授权。同一账号不能配送自己的订单，列表与接单接口均必须校验。

全应用固定三项 Tabbar：

- 首页：用水服务、配送工作和经营管理入口。
- 订单：取水、充值和配送订单。
- 我的：账号、水卡、成员、地址、配送准入和退出登录。

首页可以按账号能力和待办调整内容顺序，但不得改变登录身份、权限或 Tabbar。

## 3. 页面与路由

### 公共与一级页面

| ID | 路由 | 职责 |
|---|---|---|
| C01 | `/pages/entry/index` | 登录、绑定和受控开发入口 |
| C02 | `/pages/message/index` | 消息列表 |
| C03 | `/pages/message/detail` | 消息详情 |
| U01 | `/pages/user/home/index` | 首页和能力入口 |
| U02 | `/pages/user/order/index` | 订单列表 |
| U03 | `/pages/user/profile/index` | 账号与服务管理 |

### 用户业务页面

| ID | 路由 | 职责 |
|---|---|---|
| U04 | `/pages/user/water/confirm` | 扫码结果、设备、价格、水量和水卡确认 |
| U05 | `/pages/user/water/progress` | 取水指令和结算进度 |
| U06 | `/pages/user/order/detail` | 三类订单详情和业务轨迹 |
| U07 | `/pages/user/station/index` | 水站目录与配送选站 |
| U08 | `/pages/user/delivery/create` | 配送下单 |
| U09 | `/pages/user/appeal/create` | 配送申诉 |
| U10 | `/pages/user/recharge/index` | 首次购卡和已有卡充值 |
| U11 | `/pages/user/card/detail` | 水卡详情和成员列表 |
| U12 | `/pages/user/card/member-form` | 成员授权维护 |
| U13 | `/pages/user/family/index` | 家庭资料和奖励信息 |
| U14 | `/pages/user/address/index` | 配送地址列表 |
| U15 | `/pages/user/address/edit` | 配送地址维护 |

### 配送与机主页面

| ID | 路由 | 职责 |
|---|---|---|
| D01 | `/pages/courier/task/index` | 可接、进行中和历史任务 |
| D02 | `/pages/courier/admission/index` | 配送员准入申请 |
| D03 | `/pages/courier/task/detail` | 任务详情和履约操作 |
| D04 | `/pages/courier/task/sign` | 三照签收和数量确认 |
| D05 | `/pages/courier/task/exception` | 配送异常和举证 |
| O01 | `/pages/owner/overview/index` | 经营概览 |
| O02 | `/pages/owner/device/index` | 机主设备列表 |
| O03 | `/pages/owner/device/detail` | 设备状态详情 |
| O04 | `/pages/owner/transaction/index` | 交易和收益视图 |
| O05 | `/pages/owner/service/index` | 报修和配件申请 |

## 4. 已完成的模拟业务链

### E2E-01 扫码取水

```text
U01 扫码 → U04 设备、出水口、水种、水量和权益确认
→ 创建订单并扣减权益 → MQTT 指令 → 设备模拟器 ACK/result
→ 按实际水量结算和退差 → U05/U06/PC 追溯
```

### E2E-02 水卡生命周期

```text
U10 首次购卡或已有卡充值 → 创建订单和支付单 → Pay-Sim 支付事实
→ 发卡或向指定水卡入账 → 唯一流水、有效期和范围更新
→ U06/U11/PC 回看 → U12 成员授权、限额和撤销
```

### E2E-03 水配送

```text
U08 下单并扣减余额 → D01/D03 接单、离站、送达 → D04 三照签收
→ U06 查看轨迹 → U09 申诉 → D03 配送员举证
→ PC 裁决 → U06 查看结果
```

上述完成状态只覆盖模拟环境内部闭环，不能表述为正式微信能力或物理设备已验收。

## 5. 状态与数据契约

订单状态：

| 值 | 含义 |
|---:|---|
| 1 | 待支付 |
| 2 | 已支付，待业务处理 |
| 3 | 执行中 |
| 4 | 已完成 |
| 5 | 已关闭或取消 |
| 6 | 异常待处理 |
| 7 | 已退款 |
| 8 | 部分退款 |

配送任务状态：

| 值 | 含义 |
|---:|---|
| 1 | 待接单 |
| 2 | 已接单 |
| 3 | 已离站 |
| 4 | 已送达 |
| 5 | 已签收 |
| 6 | 已取消 |
| 7 | 申诉处理中 |

配送签收照片固定为门牌、水品、摆放三类；服务端统一记录签收时间，实际配送数量和回收数量使用整数结构化字段。

接口统一使用 POST + JSON，成功码为 `0`。Long ID 在小程序边界保持字符串，金额使用整数分，水量使用整数毫升，业务时间使用 `yyyyMMddHHmmss`。订单、支付、卡、任务、流水和事件通过稳定业务编号关联；关键关联缺失或不一致时停止推进，不得回退 Mock。

小程序按 `auth`、`device`、`order`、`card`、`recharge`、`delivery` 选择 Mock 或 Real 适配器。日常构建默认使用 Mock，内部验收构建按业务链显式启用 Real。

## 6. 页面规范

- 使用 Wot UI 和项目主题变量，不复制组件内部样式。
- 一级页面适配微信胶囊和安全区。
- 搜索输入采用自动查询时不保留重复查询按钮。
- 页面必须提供加载、空数据、失败和无权限状态。
- 状态文案由统一字典或格式化函数提供，不在页面重复定义。
- 订单、任务和水卡详情展示服务端结构化数据，不解析数据库原始 JSON。
- Mock 与 Real 构建显示与实际数据源一致的运行说明。

## 7. 后续范围与外部依赖

| 业务链 | 小程序范围 |
|---|---|
| E2E-04 售后退款与补偿 | 退款进度、补偿结果和补送回签 |
| E2E-05 设备运营与运维 | 用户侧设备不可用提示；主流程由 PC 和设备端承担 |
| E2E-06 机主经营与服务 | O01～O05 接入真实设备、收益和服务数据 |
| E2E-07 消息、告警与工单 | C02/C03、订阅通知和处理状态 |
| E2E-08 支付、对账与分账 | 正式支付结果、退款和收益查询 |

外部接入项包括正式微信 code2session、手机号授权、JSAPI 支付、支付与退款通知、账单、分账、物理设备、最终 MQTT 协议、对象存储、订阅消息、域名、TLS 和生产监控。
