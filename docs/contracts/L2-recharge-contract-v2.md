# L2 购卡充值链可执行契约 v2

> 日期：2026-07-21（有效期口径于 2026-07-25 被 D-213 修订，见下方失效声明）
> 状态：内部技术实现已完成；正式微信支付、退款和生产财务能力待接入
> 范围：仅 L2 购卡充值；H3 异常取水处理已拆出，不得在本契约中实施
> 核心需求：REQ-003、REQ-006、REQ-007、REQ-008、REQ-021、REQ-022、REQ-023、REQ-042、REQ-043、REQ-061
> 决策依据：用户已确认 D1～D8、G1 与 M1～M8

## 0. 失效声明：有效期口径已被 D-213 取代

**本文写于 2026-07-21，其中「有限卡 / 有限套餐」的口径已于 2026-07-25 被 `requirements/decisions.md`
的 D-213 整体修订。冲突时一律以 decisions.md 为准**（权威性顺序见 `requirements/README.md`）：

- **正规渠道付费购买的水卡一律永久有效**，`EXPIRE_DAYS`/`EXPIRE_TIME` 机制仅用于活动赠卡；
- **赠卡不可充值**（`canRecharge=false` + 创单拒绝）。

由此，本文第 2.2、4.2、6.4 等节描述的「有限卡买有限套餐」「有限卡续期
`max(currentExpireTime, paySuccessTime) + expireDays`」「有限卡 `PAY_EXPIRE_TIME` 取较早者」等分支，
**在当前产品口径下不再是可售形态**。相关代码与真库事务测试仍保留（D-205 明示「转为机制保留，
公式由测试钉住」），所以这些描述对**代码**仍然准确，只是对**可售业务路径**不再成立——
读本文推断「系统今天支持买有限期卡」会得出错误结论。

> **本节曾有一处错误断言，2026-08-06 更正**：此前写作「线上无可达路径」，但当时
> D-213 只写在 decisions.md 与本文里、**代码零守卫**——PC 建套餐时把售价设为正数、
> 同时填 `expireDays`，校验全部通过、套餐正常上架、小程序判为可购，用户买完就拿到
> 一张有限期付费卡。所谓"无可达路径"是推断，不是事实。现已在
> `RechargeLimits.validatePackage` 落 `rejectPaidLimitedExpiry` 硬拒绝，
> 由 `RechargeLimitsTest.paidPackageMustNotCarryExpiry` 变异验证。
> 守卫**只加在套餐创建/售卖路径**，不加在 `validateSnapshotValues`——
> 历史订单快照与权益入账要读旧数据，新规则不得让它们读不出来。



本节之外的内容（幂等合同、支付两阶段事务、状态机、范围规范化算法、流水与对账口径）未被修订，
仍然有效。

## 1. 目标、范围与完成口径

### 1.1 当前实施范围

当前内部实现覆盖首次购卡和本人已有水卡充值：

```text
合法 KH_USER
→ 读取本人目标水卡与真实在售套餐
→ 创建充值订单与一张支付单
→ 隔离环境接收受控支付事实
→ 持久化支付事件
→ 幂等、原子地增加指定水卡权益
→ 生成唯一充值流水
→ 小程序回看订单、支付状态、流水和最新余额
→ PC 按同一订单号追溯 payment/event/card/flow
```

首次购卡在待支付阶段不创建水卡；支付事实确认后，在同一事务内完成发卡、权益入账、唯一流水和订单回填。已有卡充值直接向指定本人水卡入账，两类模式共用支付事实、幂等和对账规则。

### 1.2 三种完成等级

1. **L2-T 内部技术闭环**：隔离 Pay-Sim 下的 Service/API/DB、事件收件箱、两阶段事务、原子入账、唯一流水和跨端查询合同通过。
2. **L2-WX 外部能力闭环**：正式微信登录、JSAPI/小程序下单、`uni.requestPayment`、APIv3 验签解密、主动查单、关单及对账通过。
3. **完整购卡充值真实闭环**：L2-A、L2-B、L2-WX、真实到账、小程序与 PC 回看及用户验收全部通过。

E2E-02 已完成模拟环境内部验收。正式微信支付和生产财务状态以 `docs/requirements/demo-business-chain-matrix.md` 为准。

### 1.3 当前实现范围之外

- 实体卡迁移和后台人工充值；
- 独立权益批次、批次消费归集和按批次退款；
- REQ-061 的完整退款折算与退款审批；
- H3 取水异常补偿；
- 正式微信支付、退款、分账和交易账单；
- 任何测试账号直通、万能密码、按 `userId` 换 Token 或鉴权降级。

### 1.4 G1 宏观旅程治理

本契约对应宏观旅程 `E2E-02 水卡生命周期`。业务链状态统一维护在 `docs/requirements/demo-business-chain-matrix.md`；Pay-Sim 只用于内部业务验证，不作为正式微信支付证据。

## 2. 已确认的业务决策

### 2.1 支付实施顺序

- 先完成隔离 L2-T，再单独评审并实现 L2-WX。
- Pay-Sim 仅能提升“内部技术闭环”，不能提升“外部能力闭环”。
- 持久测试库的余额或水量变化、主库迁移、Pay-Sim 执行和真实支付均需用户逐次授权。

### 2.2 有效期与权益模型

- 第一版仅允许相同有效期类型：有限卡仅买有限套餐，永久卡仅买永久套餐，交叉组合拒绝。
  **（已被 D-213 取代：付费卡一律永久、赠卡不可充值，故实际只剩「永久卡买永久套餐」一条可达路径；见第 0 节）**
- 新规则只约束新订单，不追溯否定历史快照。
- `EXPIRE_TIME IS NULL` 表示永久；迁移前将历史空字符串规范化为 `NULL`。
- `EXPIRE_DAYS IS NULL` 表示永久；有限套餐必须为正整数天数。
- 业务时区为 `Asia/Shanghai`。
- 创单时必须把目标卡资格冻结到 `PACKAGE_SNAP.targetCardEligibilitySnapshot`，并把唯一付款截止时间写入 `ws_payment.PAY_EXPIRE_TIME`；其他充值、卡有效期后续延长或套餐变化均不得追溯改变旧待支付订单的付款资格。
- 第一版支付窗口固定为 30 分钟：永久卡的 `PAY_EXPIRE_TIME=createTime+30min`；有限卡的 `PAY_EXPIRE_TIME=min(createTime+30min, expireTimeAtCreate)`。有限卡在创单时的剩余有效期不足 1 分钟则拒绝创建订单，不得收取一笔实际上无法在窗口内完成的款项。
- L2-T 与 L2-WX 使用同一个不可变 `PAY_EXPIRE_TIME`；L2-WX 下单的 `time_expire` 必须直接来自该字段，禁止重新计算或用当前卡有效期覆盖。
- 付款时点资格只按权威 `paySuccessTime <= PAY_EXPIRE_TIME` 判断。超过截止时间仍收到权威 SUCCESS 时必须保留 `payment=2`，订单进入 6，事件进入人工对账，严禁自动入账。
- 有限卡续期：`newExpireTime = max(currentExpireTime, paySuccessTime) + expireDays`。这里的 `currentExpireTime` 是事务 B 锁卡后读取的当前聚合有效期，只参与权益叠加计算，不参与重判或延长该订单已经冻结的付款资格。
- 异步 Worker 的 `processingTime` 只用于处理、有效期结果校验与审计。支付时资格通过且处理期间仅自然过期的卡可继续计算；若 `newExpireTime <= processingTime`，不得生成已过期权益或恢复卡状态，订单进入 6。
- 事务 B 锁卡后发现卡状态 2（冻结）时订单保持 2 并重试；状态 4（注销）、归属变化或范围变化时进入订单 6。只有状态 1（正常）或仅因自然过期形成的状态 3，且付款资格已经通过时，才允许继续计算与原子入账。
- 有限卡仅在 `newExpireTime > processingTime` 时，才允许把权益、有效期与 `CARD_STATUS=1` 在同一事务原子写入；永久卡与永久套餐始终保持 `EXPIRE_TIME=NULL`。
- L2-B 继续使用卡级聚合余额；当前契约不引入权益批次表。
- 目标卡既有 `SCOPE_JSON` 必须按当前授权范围模型可用；空值仍按数据库既有语义“未配置并默认拒绝”处理，不能通过充值变成可用。
- 套餐 `SCOPE_JSON` 为空时不增加新的范围约束；非空时必须与目标卡范围做规范化后的语义精确相等校验。不得取并集、交集或用字符串表面相等代替结构化比较。
- L2-B 成功后更新水卡最近一次 `PACKAGE_ID/PACKAGE_SNAP`，不覆盖既有 `SCOPE_JSON`。若未来要让充值改变、合并或分批继承范围，必须另行冻结产品决策和权益批次模型。

范围规范化算法固定为：

1. 输入必须是 JSON object；`scopeType` 只允许 `all` 或 `specified`，畸形 JSON、未知 `scopeType`、未知顶层字段一律拒绝。
2. 权威字段只允许 `scopeType/stationIds/deviceIds/outletIds`；兼容现有快照的 `stationNames/deviceNames/outletLabels` 仅作展示，若存在则必须为字符串数组且与对应 ID 数组等长，不参与授权相等判断。
3. ID 数组元素只允许正十进制整数：兼容历史 JSON 整数，但服务端须在 Long 范围校验后转为十进制 string；新快照一律写 string。任一重复、0、负数、小数、指数、空串或越界值均拒绝。
4. `scopeType=all` 时三个 ID 数组必须缺省或为空；`scopeType=specified` 时至少一个 ID 数组非空。各数组按整数值升序规范化，缺省统一为空数组。
5. 授权语义相等仅比较规范化后的 `scopeType + 三个 ID 集合`；展示名称不参与比较，但原始展示快照仍可在通过校验后留存。该规则不推导站点—设备—出水口层级关系，层级模型确认前任何新增字段均 fail-closed。

### 2.3 credit 规则

| 套餐类型 | `ML_CHANGE` | `AMOUNT_CHANGE` |
|---|---:|---:|
| `waterMl > 0` 水量套餐 | `+waterMl` | `+bonusAmount` |
| `waterMl = 0` 纯金额套餐 | `0` | `+(payAmount + bonusAmount)` |

禁止把水量套餐的 `payAmount` 再计入卡余额。一次充值只生成一条 `FLOW_TYPE=1` 流水，该流水同时记录金额、水量两个维度及两个 AFTER。

## 3. 正式 AUTH 前置合同

L2 Real 的任何接口都必须运行在合法 `KH_USER` 会话下。正式会话未闭合前，只能进行隔离的 Service/DB 测试，正常小程序构建不得把 recharge 域切 Real。

### 3.1 登录接口

`POST /mini/auth/login`

请求：

```json
{"code":"uni.login 返回的一次性 code"}
```

响应为判别联合：

```text
BOUND:
  stage = BOUND
  tokenName
  tokenValue
  context = AccountContext

UNBOUND:
  stage = UNBOUND
  bindTicket
  expiresInSeconds
```

要求：

- 服务端调用 `code2session`，不向前端返回 `openid` 或 `session_key`。
- `BOUND` 用户必须同时满足 `DATA_STATUS=0`、`DISABLED_FLAG=1`、`USER_STATUS=1`。
- 登录成功后显式创建 `StpKit.KH_USER` Token；`tokenName` 要么固定为 `dakang-token`，要么由客户端严格按响应存储，禁止两端口径不一致。
- 401 清除正式 Token 并返回 C01；禁止回退 Mock 账号。

### 3.2 手机号绑定

`POST /mini/auth/bind-phone`

请求：

```json
{"bindTicket":"短期一次性凭证","phoneCode":"手机号授权 code"}
```

安全语义：

- 使用 `bindTicket` 或等价的服务端一次性绑定会话；一期推荐至少 128 bit 随机、TTL 约 5 分钟、`purpose=PHONE_BIND`。
- 服务端凭证绑定 `appid/openid/purpose/issuedTime`，前端不能读取其中的身份事实。
- Redis 使用 `GETDEL` 或等价原子 claim；领取、成功或过期后不可重放。数据库事务失败后旧 ticket 作废，客户端重新 `uni.login`。
- 手机号和 openid 由数据库唯一键抵御并发绑定；冲突拒绝并进入人工处理，禁止静默覆盖。
- 手机号命中正常且尚未冲突的既有账号时完成安全绑定；命中其他 openid、禁用、注销或逻辑删除账号时拒绝。
- 手机号不存在时允许建立最小用户主体：`USER_GENDER=NULL` 表示未知，不伪造男女；`DISABLED_FLAG=1`、`USER_STATUS=1`、`POINTS=0`，中性名称可后续修改，审计字段由系统主体和服务端时间写入。

### 3.3 身份数据迁移约束

- 新增 `uk_user_phone(USER_PHONE)`。
- `WECHAT_XCX_OPENID` 扩展到能覆盖微信字段上限，并使用区分大小写的精确比较；新增 `uk_user_wechat_xcx_openid(WECHAT_XCX_OPENID)`。
- 唯一键不包含 `DATA_STATUS`；注销/删除账号仍占用原身份，只能恢复或人工处理。
- 迁移前检查重复手机号、非空 openid 重复、异常手机号，并将空字符串 openid 规范成 `NULL`。
- 为支持最小用户主体，`USER_GENDER` 改为可空；后台与 VO 将 `NULL` 显示为“未知”。

## 4. API 与请求幂等合同

### 4.1 真实套餐

`POST /mini/package/list`

- 仅返回 `DATA_STATUS=0 AND PACKAGE_STATUS=1` 的套餐。
- 返回 `id`、`packageName`、`payAmountFen`、`waterMl`、`bonusAmountFen`、`unitPriceSnap`、`expireDays`、`packageStatus`。
- 身份 Long ID 序列化为 string；金额、水量在前端经过安全范围校验后才转 number。
- U10 可依据目标卡有效期类型隐藏/禁用不兼容套餐，创建接口必须再次校验。
- PC 套餐页接真实 `ws_package` 数据；套餐新增/修改/上下架为 Demo 受控运营配置入口（2026-07-23 用户授权），服务端以 `RechargeLimits`+`WaterCardScope` 终审；审批流/财务菜单/后台充值仍未授权。

### 4.2 充值订单创建

`POST /mini/order/recharge/create`

请求：

```json
{
  "cardId":"1",
  "packageId":"1",
  "requestId":"550e8400-e29b-41d4-a716-446655440000"
}
```

- `cardId/packageId` 为正十进制 Long 字符串；禁止用 JS number 承载身份 ID。
- `requestId` 必须是规范的小写、带连字符 RFC 4122 UUID；同次网络重试复用，不同购买动作重新生成。
- 不接受自定义金额、前端金额、前端水量、`userId` 或可信 `PAY_SOURCE`。

服务端订单号：

```text
canonical = decimalUserId + ":" + lowercaseCanonicalUuid
digest = SHA-256(canonical)
ORDER_NO = "RC" + digest 前 30 位大写十六进制
```

总长 32，不含日期、进程序列、缓存状态等不稳定因素；同用户同 requestId 在跨日期、重启和缓存丢失后仍得到同一订单号，不同用户相同 requestId 不冲突。

服务端顺序固定为：

1. 先规范化输入、按会话用户计算订单号，并跨全部 `DATA_STATUS` 查询既有订单；命中后直接进入下述幂等核验，不因卡/套餐后来冻结、下架或改价而创建替代订单。
2. 仅在确定没有既有订单时读取目标卡和套餐：卡必须 `DATA_STATUS=0`、属于会话用户、`CARD_STATUS=1`、创建时未过期且范围通过第 2.2 节；有限卡从服务端当前时间到 `EXPIRE_TIME` 的剩余有效期必须不少于 1 分钟。套餐必须 `DATA_STATUS=0/PACKAGE_STATUS=1`，全部数值通过第 4.4 节，并与卡的有效期类型和范围兼容。
3. 以同一个服务端 `createTime` 冻结完整套餐、范围与目标卡资格快照，并按第 2.2 节计算不可变 `PAY_EXPIRE_TIME`，再进入 order+payment 同事务创建。并发插入命中唯一键的一方必须重新读取并走同一幂等核验，禁止把 DuplicateKey 直接当成功。

创建事务必须同时写入：

- `ws_order`：`ORDER_TYPE=2`、`USER_ID=session`、目标 `CARD_ID`、`PACKAGE_ID`、完整 `PACKAGE_SNAP`、`ORDER_AMOUNT=payAmount`、`PAY_WAY=1`、`ORDER_STATUS=1`。
- `ws_payment`：`ORDER_ID/ORDER_NO/PAY_AMOUNT` 与订单一致、`PAY_STATUS=1`、`PAY_SOURCE` 由受信任服务端适配器决定、`PAY_EXPIRE_TIME` 为本订单不可变付款截止时间。

订单和支付单必须处于同一 `@Transactional(rollbackFor=Exception.class)` 事务；任一插入失败整体回滚。

幂等命中时必须重新读取并核对：

- `order.USER_ID/cardId/packageId/requestId`；
- 订单内已保存的完整快照结构、requestId、packageId 及金额必须自洽；幂等命中后不得用套餐当前价格重建或改写历史快照；
- payment 恰好一条；其 `ORDER_ID/ORDER_NO/PAY_AMOUNT` 与订单一致，`PAY_SOURCE` 与创建该支付单的服务端适配器一致，`PAY_EXPIRE_TIME` 与资格快照按冻结算法计算出的结果精确相等。

全部一致才返回原订单；即使套餐随后改价、下架或删除，同一 requestId 与同一 cardId/packageId 也返回原订单，不创建新单、不重写快照。改 `cardId/packageId`、快照自身错位、订单缺 payment 或 payment 多条均拒绝并零副作用。哈希截断碰撞到其他用户/请求时必须告警并拒绝，不能当作幂等成功。

### 4.3 完整套餐快照

`PACKAGE_SNAP` 使用版本化 JSON，至少包含：

```json
{
  "schemaVersion":"L2_V2",
  "requestId":"550e8400-e29b-41d4-a716-446655440000",
  "packageId":"1",
  "packageName":"100元500升卡",
  "payAmount":10000,
  "waterMl":500000,
  "bonusAmount":0,
  "unitPriceSnap":"20.00",
  "expireDays":365,
  "packageScopeSnapshot":null,
  "targetCardScopeSnapshot":{"scopeType":"specified","stationIds":["1"],"deviceIds":["1"],"outletIds":["1","2"]},
  "targetCardEligibilitySnapshot":{
    "cardStatusAtCreate":1,
    "expireTimeAtCreate":"20260731120000",
    "capturedTime":"20260721120000"
  }
}
```

套餐字段全部来自创建时的服务端 `ws_package`，`targetCardScopeSnapshot` 与 `targetCardEligibilitySnapshot` 来自同一次服务端锁内读取的目标卡；任何值均不得来自前端或支付报文。`cardStatusAtCreate` 固定为通过创单校验的 1；`capturedTime` 必须等于订单 `createTime`；有限卡的 `expireTimeAtCreate` 是创建时卡有效期，永久卡为 `null`。创建时必须按第 2.2 节验证范围和资格，幂等命中时必须逐字段复核套餐、范围、资格快照及 `PAY_EXPIRE_TIME`。到账只读取订单快照，并重新确认目标卡当前范围仍与 `targetCardScopeSnapshot` 语义精确相等；套餐后续改价、下架或删除不改变历史订单，卡范围在支付期间发生变化则不得自动入账。

### 4.4 输入上限与溢出

创建时校验：

- `packageName`：去除首尾空白后长度 `1..50`；
- `payAmount`：正整数分，`1..1000000`（最高 10,000 元）；
- `waterMl`：非负整数毫升，`0..50000000`（最高 50,000 升）；
- `bonusAmount`：非负整数分，`0..1000000`（最高 10,000 元）；
- `unitPriceSnap`：必须能无损解析为非负 `BigDecimal`，最多 2 位小数且不高于 100000 分/升；水量套餐必须大于 0，纯金额套餐固定为 0，快照统一使用 `toPlainString`，禁止浮点数计算或科学计数法；
- `expireDays`：仅 `NULL` 或正整数，有限套餐上限 3650 天；
- `payAmount + bonusAmount`、卡当前值 + credit 均使用 `Math.addExact` 或等价检查，Long 溢出必须在写库前拒绝；
- 水量套餐、纯金额套餐按第 2.3 节计算后，两个 credit 不得同时为 0。

这些上限属于 v2 合同值；调整必须重新评审契约和测试，不能只改前端。

## 5. 数据库模型与唯一性

### 5.1 保留与新增唯一键

保留：

- `ws_order.uk_order_no(ORDER_NO)`；
- `ws_payment.uk_transaction_id(TRANSACTION_ID)`。

新增：

- `ws_payment.uk_payment_order_no(ORDER_NO)`；
- `ws_payment.uk_payment_order_id(ORDER_ID)`；
- `ws_wallet_flow.BIZ_IDEMPOTENCY_KEY varchar(64) NULL`；
- `ws_wallet_flow.uk_wallet_flow_biz_key(BIZ_IDEMPOTENCY_KEY)`；
- `ws_payment_event.uk_payment_event_source_channel_key(PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY)`；
- `ws_domain_event.BIZ_IDEMPOTENCY_KEY varchar(64) NULL` 与 `uk_domain_event_biz_key(BIZ_IDEMPOTENCY_KEY)`，仅为需要数据库级幂等的领域事件提供稳定业务键；
- 第 3.3 节的手机号与 openid 唯一键。

充值流水业务键固定为：

```text
RECHARGE:<orderNo>
```

唯一键均不包含 `DATA_STATUS`。财务流水即使被错误标记删除，其业务幂等键也不得复用。

### 5.2 `ws_payment` 增量字段

在现有订单共键、交易号、金额、状态、预支付号和回调字段基础上，至少新增：

- `PAY_SOURCE tinyint NOT NULL`：1 WECHAT、2 PAY_SIM；
- `CURRENCY varchar(16) NOT NULL`：当前固定 CNY；
- `PAY_EXPIRE_TIME varchar(14) NOT NULL`：创单时冻结的唯一支付截止时间，创建后不可修改；
- `PAY_SUCCESS_TIME varchar(14) NULL`：权威支付成功时间。

`PAY_SOURCE` 是支付单权威来源，由服务端支付适配器在创建支付单时写入，一旦创建不可改变。微信适配器只能写 WECHAT，Pay-Sim 适配器只能写 PAY_SIM；前端、普通请求或支付报文中的来源字段均不得决定该值。`PAY_EXPIRE_TIME` 必须由第 2.2 节算法生成，L2-T 与 L2-WX 共用；微信 `time_expire` 只能映射该字段，禁止在请求微信支付时重新计算。

### 5.3 `ws_payment_event`

新表遵循项目通用字段顺序，并至少包含：

| 字段 | 类型/可空 | 来源与语义 |
|---|---|---|
| `ID` | bigint PK | 自增主键 |
| 通用审计字段 | 项目标准 | `DATA_STATUS` 必须保持 0，支付事件不做业务逻辑删除 |
| `PAY_SOURCE` | tinyint NOT NULL | 服务端适配器常量：1 WECHAT、2 PAY_SIM |
| `FACT_CHANNEL` | tinyint NOT NULL | 1 NOTIFY、2 QUERY、3 PAY_SIM |
| `PROVIDER_EVENT_KEY` | varchar(100) NOT NULL | 微信通知 id、稳定查询事实键或稳定模拟事件键 |
| `PAYMENT_ID` | bigint NULL | 可信共键关联后的支付单 ID，未知/错位时允许空 |
| `ORDER_ID` | bigint NULL | 可信共键关联后的订单 ID，未知/错位时允许空 |
| `ORDER_NO` | varchar(32) NOT NULL | 已验证/解密的外部商户订单号 |
| `TRADE_STATE` | varchar(32) NOT NULL | 规范化支付事实状态；一期至少支持 SUCCESS / NOTPAY / CLOSED，其他状态只留证并进入人工核查 |
| `TRANSACTION_ID` | varchar(64) NULL | SUCCESS 必填；非成功查询事实允许空；Pay-Sim 成功事实使用与微信不重叠的 SIM 命名空间 |
| `PAY_AMOUNT` | bigint NULL | 支付方实际返回的金额，单位分；SUCCESS 必填，支付方未返回时必须为空，禁止用内部订单金额补造外部事实 |
| `CURRENCY` | varchar(16) NULL | 支付方实际返回的币种；SUCCESS 必填且为 CNY，未返回时必须为空 |
| `PAY_SUCCESS_TIME` | varchar(14) NULL | SUCCESS 必填并统一到 Asia/Shanghai；非成功查询事实允许空 |
| `RAW_BODY` | mediumtext NULL | 原始签名正文或受保护查询证据，受 M5 保护 |
| `RAW_BODY_SHA256` | char(64) NOT NULL | 完整性摘要，不是不可伪造或不可抵赖证明 |
| `VERIFY_METHOD` | tinyint NOT NULL | WECHAT_SIGNATURE / WECHAT_QUERY / PAY_SIM_HMAC |
| `SIGNATURE_SERIAL/TIMESTAMP/NONCE/VALUE` | 可空 | 通知重新核验材料；查询渠道允许空 |
| `PROCESSING_STATUS` | tinyint NOT NULL | PENDING / PROCESSING / PROCESSED / RETRY_WAIT / RECONCILIATION_REQUIRED |
| `RETRY_COUNT` | int NOT NULL | 重试次数，初始 0 |
| `NEXT_RETRY_TIME` | varchar(14) NULL | 下次可 claim 时间 |
| `CLAIM_TIME/LEASE_UNTIL` | varchar(14) NULL | Worker claim 与崩溃恢复租约 |
| `RECOVERY_APPROVAL_GROUP_KEY` | varchar(64) NULL | 订单 6 同一支付事实组的恢复授权键；同组事件取值必须一致 |
| `RECOVERY_APPROVED_BY` | bigint NULL | 订单 6 人工恢复授权人；仅受权对账动作可写 |
| `RECOVERY_APPROVED_TIME` | varchar(14) NULL | 人工恢复授权时间 |
| `RECOVERY_APPROVAL_REASON` | varchar(500) NULL | 人工恢复依据，必填且不得含敏感原文 |
| `LAST_ERROR` | varchar(500) NULL | 最近一次结构化失败原因，不写密钥或原始敏感正文 |
| `RECEIVED_TIME` | varchar(14) NOT NULL | 服务端接收时间 |
| `PROCESSED_TIME` | varchar(14) NULL | 该事实完成业务处理的时间；SUCCESS 可为权益完成时间，NOTPAY/CLOSED 为事务 A 完成时间 |
| `RAW_PURGED_TIME` | varchar(14) NULL | 原始证据清理时间 |

状态字段约束：SUCCESS 必须同时具备交易号、正整数金额、CNY 和成功时间；NOTPAY/CLOSED 不得被解释为付款成功，缺失的外部字段保持 NULL。QUERY 的 `PROVIDER_EVENT_KEY` 固定为 `Q:` + 下列 UTF-8 规范串的 SHA-256 小写十六进制：`PAY_SOURCE|ORDER_NO|TRADE_STATE|TRANSACTION_ID-or-empty|PAY_SUCCESS_TIME-or-empty|PAY_AMOUNT-or-empty|CURRENCY-or-empty`；字段值字符集先行校验且分隔符不得进入值域。这样同一事实重复查询稳定命中，而同一订单从 NOTPAY 演进到 SUCCESS 或 CLOSED 时形成不同事件，不能被首个查询结果永久占位。NOTIFY 使用支付方通知 id，PAY_SIM 使用测试驱动生成的规范 UUID，三者不得互相复用命名空间。

同一事件键重复到达必须比对 `RAW_BODY_SHA256` 和全部结构化事实；同键不同正文或事实进入人工核查。非法签名/HMAC 请求不占用可信事件唯一键，可进入独立限流安全日志。

数据库实施前重新检查 `ApiEnum.DictType`，再为 `PAY_SOURCE/FACT_CHANNEL/TRADE_STATE/PROCESSING_STATUS/VERIFY_METHOD` 注册不冲突的编号并同步双源 SQL；本契约不提前占用编号。

## 6. 支付事实与两阶段事务

### 6.1 Pay-Sim 安全边界

- 仅 `dev/test profile` 与 `mini.pay-sim.enabled=true` 双门控；production Bean 不注册，接口物理 404。
- HMAC 密钥只从环境变量读取；空密钥拒绝启动；签名常量时间比较；默认只允许 localhost 或隔离测试网络。
- 小程序正常路由没有“模拟支付成功”按钮，不保存密钥，也不直接调用 Pay-Sim。
- 仅 `tools/pay-sim` 或隔离测试驱动页面外触发；HMAC 验证通过后进入与微信相同的事件事务 A 和权益事务 B。
- Pay-Sim 不得绕过 `ws_payment_event` 直接调用权益入账 Service。
- 持久测试库副作用每次均需用户单独授权。

### 6.2 事务 A：保存支付事实

事务 A 使用独立事务 Bean，完成：

1. 在事务外完成微信验签/解密或 Pay-Sim HMAC 验证；按 `TRADE_STATE` 校验订单号及支付方实际返回的金额、币种、交易号和成功时间。SUCCESS 四项事实必须齐全；非成功事实不得用内部数据补齐。
2. 插入或幂等命中 `ws_payment_event`；重复键必须核对正文摘要和全部结构化事实。
3. 尝试关联 payment/order：校验 payment 的 `ORDER_ID/ORDER_NO/PAY_AMOUNT` 与订单一致，并校验 event/payment 的 `PAY_SOURCE` 一致；同时验证 `PAY_EXPIRE_TIME` 非空、创建后未被修改，且与 `PACKAGE_SNAP.targetCardEligibilitySnapshot` 按第 2.2 节计算的结果精确相等。外部事实返回金额/币种时还必须与内部订单精确相等。SUCCESS 首次落单时条件写入 payment.TRANSACTION_ID 与权威 PAY_SUCCESS_TIME；payment 已有交易号或成功时间时必须与事件相等，跨单交易号、`uk_transaction_id` 冲突或成功时间错位一律置 RECONCILIATION_REQUIRED，不得入账。
4. SUCCESS 且共键正确时按精确状态处理：
   - `payment 1/order 1` 且 `paySuccessTime <= PAY_EXPIRE_TIME`：条件推进为 `payment 2/order 2`，事件置 PENDING；
   - `payment 1/order 1` 且 `paySuccessTime > PAY_EXPIRE_TIME`：必须保留权威付款事实并条件推进为 `payment 2/order 6`，事件置 RECONCILIATION_REQUIRED，严禁自动 Worker 入账；
   - 已处于 `payment 2/order 2` 的同交易、同成功时间重复事实：只有冻结付款资格仍完全自洽时才置 PENDING，由事务 B 的唯一流水闸统一收敛；
   - `payment 4/order 5` 的迟到成功事实：条件推进为 `payment 2/order 6` 并置 RECONCILIATION_REQUIRED，严禁自动入账；
   - `payment 2/order 4`：新 SUCCESS 事件只允许先持久化为 PENDING。事务 A 禁止基于未锁定的卡或流水读取直接把它置 PROCESSED；必须由 Worker 进入事务 B 的只读幂等核验分支后收敛；
   - `payment 2/order 6`：默认置 RECONCILIATION_REQUIRED。只有同支付事实组已存在一致、仍处于活动 RETRY_WAIT/PROCESSING 的恢复授权组且 canonical 授权审计可核时，才继承同一 `RECOVERY_APPROVAL_GROUP_KEY/RECOVERY_APPROVED_*` 并置 RETRY_WAIT；
   - `payment 2/order 7`：当前退款合同未启用，新 SUCCESS 一律置 RECONCILIATION_REQUIRED，不得自行宣称完成或进入权益 Worker。
   禁止任何分支回退支付事实或重复入账；所有 payment/order 状态变化必须使用精确前态的条件 UPDATE 并校验影响行数。
5. NOTPAY 且共键正确时：仅允许 `payment 1/order 1` 保持不变并将该查询事件置 PROCESSED；若内部已进入其他状态则置 RECONCILIATION_REQUIRED，不得回退。
6. CLOSED 且共键正确时：仅允许支付方权威查单驱动 `payment 1/order 1 → payment 4/order 5` 并把事件置 PROCESSED；重复 CLOSED 只能在精确 `payment 4/order 5` 下幂等置 PROCESSED。与任何成功态冲突时进入人工对账。
7. 其他支付方状态只保存可信事实并标记 `RECONCILIATION_REQUIRED`，不得修改 payment、order、card 或 flow。
8. 共键失败、内部对象缺失或来源错位时保留事件，标记 `RECONCILIATION_REQUIRED`，不得拼接到错误订单。
9. 通知渠道只有在本地事务提交后才向支付方返回成功，持久化失败返回非 2xx 允许重试；主动查单和 Pay-Sim 调用方也只能在提交后收到处理结果。

`paymentStatus=2` 是外部付款事实，不得因后续权益事务失败回退。

### 6.3 Worker claim

- Worker 只能 claim `TRADE_STATE=SUCCESS`、已精确关联 payment/order 且处于 PENDING/到期 RETRY_WAIT 的事件。允许的精确分支只有：正常权益路径 `payment 2/order 2`；带有同组授权键、三项审批字段和 canonical 授权审计的恢复路径 `payment 2/order 6`；以及不修改任何权益的 `payment 2/order 4` 只读幂等核验路径。`order 4` 分支即使事件处于 RETRY_WAIT 也只能核验并收敛，绝不能再次入账。NOTPAY/CLOSED/其他状态、`order 7` 及事务 A 已完成的事件永不进入权益 Worker。
- Worker 通过条件 UPDATE 将符合条件的事件原子 claim 为 PROCESSING，并写 `CLAIM_TIME/LEASE_UNTIL`；影响行必须为 1。
- 其他 Worker 不能处理未到期 PROCESSING 事件；Worker 崩溃后仅在租约到期后允许重新 claim。
- 机械重试次数本身不能把资金异常判为不可恢复；错误分类必须区分可恢复、不可恢复和人工对账。
- 订单 6 的“显式人工恢复授权”属于订单/支付事实组，而不是单个事件。独立权限 `order:payment:reconcile` 的动作必须接收规范 UUID `requestId`，在严格事务中锁定 payment/order，查询该订单全部 `DATA_STATUS` 的事件，先确认 `PAY_EXPIRE_TIME` 与资格快照自洽，且所有 SUCCESS 均与 payment 的 `TRANSACTION_ID/PAY_AMOUNT/CURRENCY/PAY_SUCCESS_TIME/PAY_SOURCE` 及 order 共键精确一致；授权组键固定为 `L2:RECOVERY:` + `SHA-256(orderNo + ":" + transactionId + ":" + requestId)` 前 48 位小写十六进制。事务须先以该键在 `ws_domain_event.BIZ_IDEMPOTENCY_KEY` 写一条 `EVENT_TYPE=6` canonical 授权审计，再对事实组内全部 RECONCILIATION_REQUIRED 事件写入相同的授权组键和三项 `RECOVERY_APPROVED_*` 字段并条件更新为 RETRY_WAIT，审计 payload 包含全部事件 ID。存在任何错位 SUCCESS、同 requestId 参数冲突、部分更新、审批字段不全或状态影响行数不等于待批准事件数时整体回滚，均不可 claim。新到达的同事实重复事件只在该授权组仍有活动 RETRY_WAIT/PROCESSING 且审计精确一致时继承；若一次不可恢复失败已把全组退回 RECONCILIATION_REQUIRED，则旧授权不再自动生效，必须重新审批。该动作属于 E2E-08 财务实施阶段，仍需另行授权。

### 6.4 事务 B：权益入账

事务 B 使用另一独立事务 Bean，并按固定顺序锁定可读取全部 `DATA_STATUS` 的对象：

```text
payment → order → card → 同支付事实组事件 → 目标卡流水
```

随后：

1. 核验 payment/order/event 来源、ID、订单号、金额、状态和用户共键；重新验证 `PAY_EXPIRE_TIME` 与不可变资格快照，并要求权威 `paySuccessTime <= PAY_EXPIRE_TIME`。截止时间不自洽或超期成功事实均不得进入正常权益分支。
2. 正常变更路径仅允许 `ORDER_TYPE=2`、`PAY_WAY=1`、`payment=2`、`order=2` 或获准恢复的 `order=6`。若取得锁时发现 `payment=2/order=4`，只允许进入只读幂等核验分支：核验同一支付事实、跨全部状态恰好一条充值流水、credit、双维 AFTER、目标卡终值及其后续账本连续性；全部一致后，才把同事实组尚未完成的匹配事件条件收敛为 PROCESSED。该分支不得更新卡、插入流水或修改订单；任一不一致只进入人工对账，不得覆盖已完成订单。
3. 从 `order.PACKAGE_SNAP` 解析 v2 套餐、范围和 `targetCardEligibilitySnapshot`，按第 2.3 节计算 credit；不读取当前套餐值或事件/前端金额，也不得用卡后来延长的有效期重判付款资格。
4. 锁卡后按处理时事实精确分类：
   - `CARD_STATUS=2`（冻结）为可恢复错误，事务 B 不写权益；正常路径订单保持 2 并重试，已获授权的订单 6 仍保持 6，不能借重试回退状态；
   - `CARD_STATUS=4`（注销）、`DATA_STATUS<>0`、归属变化或当前范围与 `targetCardScopeSnapshot` 语义不一致为不可恢复错误，事务 B 不写权益，交由第 6.5 节把订单 2 条件推进到 6；
   - 只有 `CARD_STATUS=1`，或仅因自然过期形成、`currentExpireTime` 非空且不晚于 `processingTime`、其他资格仍一致的 `CARD_STATUS=3`，才允许继续计算；其他状态 fail-closed。
5. 读取锁内旧余额、水量与 `currentExpireTime`，使用 `Math.addExact` 计算预期 AFTER。有限卡的 `newExpireTime=max(currentExpireTime,paySuccessTime)+expireDays`；永久卡与永久套餐必须同时保持 `EXPIRE_TIME=NULL`。若有限卡算得 `newExpireTime <= processingTime`，这是不可恢复错误：不得生成已过期权益、不得恢复 `CARD_STATUS=1`，交由第 6.5 节进入订单 6。
6. 单条条件 UPDATE 必须带上锁内读取的卡 ID、用户、`DATA_STATUS`、允许的状态 1/3、范围语义锚点、旧余额/水量与旧有效期，同时增加 `BALANCE_AMOUNT/BALANCE_ML`，写最近 `PACKAGE_ID/PACKAGE_SNAP`。有限卡还须在同一 UPDATE 写 `EXPIRE_TIME=newExpireTime` 和 `CARD_STATUS=1`；永久卡保持 `EXPIRE_TIME=NULL`。拒绝负数、双零、Long 溢出或任一前态变化，影响行必须为 1。
7. 插入一条 `FLOW_TYPE=1` 流水，业务键 `RECHARGE:<orderNo>`，两个 CHANGE 和两个 AFTER 必须等于预期值。
8. 将订单 2 或允许恢复的 6 条件更新为 4，写 `FINISH_TIME=processingTime`；影响行必须为 1。
9. 将该订单下与 payment 的交易号、金额、币种、成功时间和来源全部精确一致的 SUCCESS 事件组条件更新为 PROCESSED，统一写本次 `PROCESSED_TIME`；正常 order 2 与人工恢复 order 6 均不得只完成当前事件。若发现同订单存在错位 SUCCESS，或更新行数与锁内待收敛事件数不一致，则整体回滚。其他已 claim Worker 取得锁后必须重读事件状态，发现已 PROCESSED 时只走上述幂等核验并退出；全部提交。

任一步失败，卡、流水、订单终态和事件完成态整体回滚。

### 6.5 事务 B 失败后的独立落痕

编排层必须在事务 B 回滚后调用独立严格事务。该事务不能沿用失败事务中的对象、状态判断或无锁读结果，必须重新按统一顺序锁定并读取跨全部 `DATA_STATUS` 的数据：

```text
payment → order → card → 同支付事实组事件 → 目标卡流水
```

重新读取后只允许以下精确分支：

1. **`payment=2/order=2`**：
   - 可恢复错误：订单保持 2；仅把本次锁内确认属于同一支付事实组、尚未完成且处于允许前态的事件条件更新为 RETRY_WAIT，写下一次重试时间和结构化错误摘要；
   - 不可恢复错误：使用 `WHERE payment=2 AND order=2` 等精确前态做 CAS，将订单条件更新 `2→6`；影响行必须为 1。随后把同一支付事实组内全部尚未完成且处于允许前态的 SUCCESS 事件条件更新为 RECONCILIATION_REQUIRED，并校验更新行数等于锁内目标数；
   - 任一 CAS 或事件组更新行数不符，整笔落痕事务回滚并触发严格告警，禁止用后写结果覆盖并发成功。
2. **`payment=2/order=4`**：禁止把订单降级为 6。按事务 B 的同一只读规则核验唯一充值流水、credit、两个 AFTER、卡终值和完整账本连续性；全部一致时，将同支付事实组尚未完成的匹配事件条件收敛为 PROCESSED，不修改 payment/order/card/flow。若不一致，只把尚未完成的匹配事件条件置 RECONCILIATION_REQUIRED，并记录严格告警与人工对账审计；不得修改完成订单、已完成事件、卡或流水。
3. **其他 payment/order 组合**：不得套用失败分类写状态；只记录精确现状与严格告警，交人工对账，禁止覆盖并发结果。

上述每一条状态 UPDATE 都必须包含精确旧状态、共键与事件处理前态，并校验影响行数；禁止无条件覆盖。严格审计写入失败必须向监控暴露，不能用现有 `saveQuietly` 声称已留证。

事务 A 已提交的付款事实永不随事务 B 回滚。

## 7. 状态机

### 7.1 主链与分支

| payment | order | 充值语义 |
|---:|---:|---|
| 1 | 1 | 待支付 |
| 2 | 2 | 外部支付成功、权益待入账或可恢复重试 |
| 2 | 4 | 权益和唯一流水全部完成 |
| 4 | 5 | 支付方确认未支付并关闭 |
| 2 | 6 | 已付款但权益不可自动完成，人工对账 |
| 2 | 7 | 异常付款已完成真实退款 |

规则：

- 客户端 `cancel/fail` 不直接修改终态，继续服务端查单。
- 支付方返回 NOTPAY 时保持 1；仅支付方确认 CLOSED 后才进入 payment 4/order 5。
- `paySuccessTime <= PAY_EXPIRE_TIME` 的权威成功事实才允许进入正常 `payment 2/order 2`；超过不可变截止时间的成功事实必须进入 `payment 2/order 6`，不得因卡被其他充值延长而追溯恢复自动入账资格。
- 可恢复入账失败保持 order 2；不可恢复才进入 6。
- order 6 修复后允许 6→4；6→7 只为后续真实退款合同预留，必须另行完成 REQ-044 的退款请求、回调幂等和退款证据合同并取得实施授权，L2-T 不得模拟该迁移。
- order 5 收到权威迟到支付时 payment 4→2、order 5→6，不自动入账，由人工决定 6→4 或退款后 6→7。
- order 4 收到同一支付事实的新 SUCCESS 时先保存 PENDING，只能由事务 B 在锁内完成只读幂等核验后收敛；order 7 收到新 SUCCESS 时在当前退款合同未启用的情况下进入人工对账，不得自行宣称完成。
- orderType=2 的状态 6 在 PC/小程序显示“支付成功、权益待处理/人工对账”，不得显示“出水异常待补偿”。

## 8. 流水与对账

- `ws_wallet_flow` 继续通过 `ORDER_ID` 关联订单，不虚构 `ORDER_NO` 列。
- 财务流水只插入，不提供业务删除接口。
- 普通 BaseMapper 会受 `@TableLogic` 影响；幂等与对账必须使用专门 Mapper/XML 查询全部 `DATA_STATUS`。
- 同业务幂等键存在任何状态的流水都表示键已占用；`DATA_STATUS<>0` 的资金流水本身是 mismatch，不能当作不存在后再次入账。
- 幂等重复成功必须核验唯一流水的 `CARD_ID/USER_ID/ORDER_ID/FLOW_TYPE/BIZ_IDEMPOTENCY_KEY`、两个 CHANGE、两个 AFTER 与快照派生值完全一致。
- 同一卡所有有效后续流水必须逐笔连续，卡当前 `BALANCE_AMOUNT/BALANCE_ML` 等于最后一笔有效流水的两个 AFTER；断裂时不得返回幂等成功。

## 9. pay-status 与查询合同

### 9.1 支付状态

`POST /mini/order/pay-status`

请求：

```json
{"orderNo":"RC..."}
```

Service 必须以 `KH_USER` 会话强制过滤本人，并核验：

- order 存在、`DATA_STATUS=0`、`USER_ID=session`、`ORDER_TYPE=2`；
- payment、event、充值 flow 均使用可读取全部 `DATA_STATUS` 的专用查询；payment 跨全部状态恰好一条，任何被逻辑删除的 payment/event/资金流水都视为污染并整体 mismatch，不能被 BaseMapper 隐藏；
- payment 的 `ORDER_ID/ORDER_NO/PAY_AMOUNT` 与 order 的 `ID/ORDER_NO/ORDER_AMOUNT` 一致，order 的 `PAY_WAY=1`；
- payment 的 `PAY_EXPIRE_TIME` 非空、与订单资格快照按冻结算法精确一致且创建后未变化；SUCCESS 的 `PAY_SUCCESS_TIME` 必须来自权威事实，不能由当前卡有效期或处理时间替代；
- payment/event 的 `PAY_SOURCE` 一致，且来源由服务端适配器建立；
- 以订单号查询到的每一条可信事件均须校验 `PAYMENT_ID/ORDER_ID/ORDER_NO/PAY_SOURCE` 与当前 payment/order 精确一致；SUCCESS 还须校验交易号、金额、币种、成功时间，NOTPAY/CLOSED 在支付方返回金额或币种时同样必须一致。任一事件共键错位即整体 mismatch，不能忽略坏事件后拼接好事件；
- 状态组合只能按以下精确矩阵解释，禁止使用 `orderStatus >= n` 一类数值序比较：
  - `payment 1 / order 1`：待支付；充值流水为 0，只允许没有事件或存在 PROCESSED 的 NOTPAY，任何 SUCCESS/CLOSED/其他状态或 RECONCILIATION_REQUIRED 均 mismatch；
  - `payment 4 / order 5`：充值流水为 0，必须至少一条 PROCESSED 的 CLOSED，可保留更早的 PROCESSED NOTPAY，但不得存在任何 SUCCESS 或其他冲突事实；
  - `payment 2 / order 2`：充值流水为 0，必须至少一条 `PAY_SUCCESS_TIME <= PAY_EXPIRE_TIME` 的 SUCCESS，且处理态只允许 PENDING/PROCESSING/RETRY_WAIT；不得存在 PROCESSED/RECONCILIATION_REQUIRED 的 SUCCESS 或任何 CLOSED；
  - `payment 2 / order 4`：必须至少一条 `PAY_SUCCESS_TIME <= PAY_EXPIRE_TIME` 且 PROCESSED 的 SUCCESS，充值流水恰好一条并与目标卡、快照和 AFTER 一致；所有 SUCCESS 必须属于同一支付事实组，处理态只允许 PROCESSED，或处于事务 B 只读幂等收敛中的 PENDING/PROCESSING/RETRY_WAIT，任何 RECONCILIATION_REQUIRED/CLOSED 均 mismatch；
  - `payment 2 / order 6`：不得存在已完成充值流水，可保留迟到支付前的 PROCESSED CLOSED；全部 SUCCESS 必须属于同一支付事实组，并且要么全部为 RECONCILIATION_REQUIRED，要么全部带有同一非空 `RECOVERY_APPROVAL_GROUP_KEY`、审批字段与 canonical 审计且只处于 RETRY_WAIT/PROCESSING，禁止混用组键或留有未批准的 PENDING；
  - `payment 2 / order 7/8`：原支付事实、原充值流水和账本证据须继续满足已完成订单口径；读取端还必须通过 E2E-04 售后终态证据校验，确认充值退款动作与订单、用户、金额和终态时间共键一致。`order 7` 返回 `REFUNDED`，`order 8` 返回 `PART_REFUNDED`；任一证据缺失、重复或错位均 mismatch，不得只凭订单状态拼接退款成功；
  - 其他未列组合，包括充值单 `order 3`，全部为 mismatch。

多事件 `processingStatus` 聚合优先级固定为 `RECONCILIATION_REQUIRED > PROCESSING > RETRY_WAIT > PENDING > PROCESSED`；待支付且无事件时返回 `WAITING_PAYMENT`。聚合只用于展示，不能替代上面的逐事件共键和合法组合校验。

任一关键对象缺失、重复、错位或来源不一致均 fail-closed，返回结构化 mismatch/error，不回退 Mock。

响应至少包含：

```text
orderNo
payStatus
orderStatus
paySource
processingStatus
retryable
statusMessage
payExpireTime
finishTime?
```

### 9.2 小程序订单详情

`POST /mini/order/detail` 对 orderType=2 返回结构化 recharge 区块：

- 套餐名称、支付金额、到账水量、赠送余额、有效期；
- payStatus、orderStatus、paySource、事件处理状态；
- 本订单唯一充值流水的两个 CHANGE 与两个 AFTER；
- 目标卡最新余额/水量；
- 类型感知轨迹：创建、支付事实、入账完成或人工对账。

禁止直接向页面输出 `PACKAGE_SNAP` 原始 JSON，也不得生成“扫码取水下单/取水完成”轨迹。

## 10. PC fail-closed 追溯合同

现有 `POST /order/order/trace` 对充值订单增量聚合：

- order：orderId/orderNo/userId/cardId/packageId/packageSnap/orderAmount/orderStatus；
- payment：paymentId/orderId/orderNo/payStatus/payAmount/paySource、脱敏 transactionId、payExpireTime、callback/paySuccessTime；
- event：factChannel、processingStatus、retryCount、received/processedTime、结构化失败摘要；
- flow：flowId/cardId/userId/orderId/bizKey/flowType、amountChange、mlChange、amountAfter、mlAfter；
- card：cardId/ownerUserId、当前 balanceAmount/balanceMl、最后流水 AFTER；
- 支付结果领域事件；
- 整体 `linkStatus=ok|mismatch` 与 `linkReason`。

`linkStatus=ok` 必须同时满足 payment/order/card/event/flow 全部共键、状态、金额、来源、credit 和账本连续性；任一不符显示 mismatch，不得拼成“充值到账成功”。

普通 PC 页面不得返回或展示原始报文、openid、完整 transactionId、签名、密钥或 bindTicket。

## 11. 小程序 API 唯一来源与页面行为

- `miniapp/src/api/recharge.ts`：套餐列表、充值订单创建、pay-status、支付意图恢复，是充值域唯一合同。
- `miniapp/src/api/order.ts`：三类订单通用列表和详情；退役旧 `OrderApi.createRechargeOrder`、旧自定义金额 DTO/Mock/Real pending 及依赖测试。
- `miniapp/src/api/card.ts`：本人主卡、卡详情和到账后余额刷新。
- L2 Real 下 AUTH、card、order、recharge 任一关键依赖失败或数据源不一致时明确报错，不读取 `scenarioStore`，不回退 Mock。

页面：

- C01 完成正式登录、手机号绑定和会话恢复；
- U10 选择本人卡与兼容套餐、创建订单；L2-T 只轮询，正常路由不出现模拟成功按钮；L2-WX 后续才接 `uni.requestPayment`；
- U06 展示支付/入账状态和本订单充值流水；
- U02 按同一订单号回看；
- U01 生活用水视角与 U11 在 `onShow`/进入时刷新真实余额；
- 所有身份 Long ID 全程 string；仅金额/水量经边界检查后转 number。

## 12. 历史数据与迁移策略

### 12.1 当前已知历史

- 充值订单 #2 为待支付样例，`CARD_ID` 为空、无 payment、快照不完整，不得伪装正常支付。
- 开户流水 #1 的金额和水量是历史组合基线，`ORDER_ID` 为空，不得随意挂到待支付订单 #2。
- 历史有限/永久交叉快照仅作证据保留，不追溯修改。

### 12.2 精确策略

- 对现有运行库，订单 #2 迁移必须具备三分支且以 canonical 归档事件业务键保证幂等：
  1. 在同一事务中先以 `SELECT ... FOR UPDATE` 锁定订单 #2；ID、订单号、类型、用户、空 `CARD_ID`、套餐、金额、支付方式、待支付状态、空完成时间和不完整快照全部与冻结旧种子一致，且跨全部 `DATA_STATUS` 查询确认 payment、充值流水、canonical 归档事件均为 0 条时，条件更新为 `ORDER_STATUS=5`，`CANCEL_REASON` 写“历史 Demo 不完整充值种子归档：未发生真实支付”，保持 `DATA_STATUS=0` 和 `FINISH_TIME=NULL`，并在同一事务插入恰好一条 canonical 订单状态归档事件；
  2. 已处于上述精确归档终态、跨全部 `DATA_STATUS` 的 payment/充值流水仍为 0 条且 canonical 归档事件恰好一条并全字段一致时，幂等 no-op；
  3. 任何部分归档、事件缺失/重复、身份/金额/状态/快照/业务键错位均 `SIGNAL` 并全事务回滚。
  不得补造 payment、卡、交易号或成功流水；fresh init 直接落分支 2 的完整终态和 canonical 事件。
- canonical 事件固定写入 `ws_domain_event`：`EVENT_TYPE=1`、`EVENT_KEY=<orderNo>`、`BIZ_IDEMPOTENCY_KEY=L2:ARCHIVE:ORDER:<orderNo>`、`DATA_STATUS=0`、`WHITELIST_FLAG=1`、`CONSUMED_FLAG=1`；`EVENT_PAYLOAD` 使用 `schemaVersion=L2_ARCHIVE_V1`，并完整保存 string 型 orderId/orderNo、fromStatus=1、toStatus=5、固定归档原因、paymentCount=0、rechargeFlowCount=0。第 5.1 节可空唯一键从数据库层保证并发只会留下一个 canonical 事件，任何删除态同键事件仍占用该键。
- 开户流水 #1 保持原有金额、水量、空 `ORDER_ID` 和空充值业务幂等键，不挂订单 #2、不改写成单次套餐支付；fresh init 注释明确它是历史组合开户基线，运行库迁移不凭推测改写该资金流水。
- 对 fresh init：订单 #2 直接以同一“历史不完整样例已关闭”口径初始化；新的成功充值种子只有在 order/payment/event/flow/card 全部共键和账本连续时才允许加入。
- 任一身份、金额、状态或快照不符合预期时迁移 `SIGNAL` 并全事务回滚，禁止按固定 ID 覆盖未知业务数据。
- 迁移前检查支付单重复/孤儿/共键错位、充值流水重复/孤儿/断裂、用户身份重复、非法套餐和卡终值。
- 新增/修改交易表同步 `server/sql/ws_trade.sql` 与 `deploy/mysql/init/02-ws-business.sql`；用户身份表同步权威 init 与增量迁移；字典同步 SQL 与 `ApiEnum.DictType`。
- 增量迁移只先在无宿主端口、无主库挂载的独立临时 MySQL 运行 fresh、旧态、冲突、幂等和快照零变化测试。
- 主库迁移必须另行取得用户授权、先备份并在维护窗口执行。

## 13. 支付证据数据保护（M5）

- `RAW_BODY` 只用于支付核验、受控重放和审计，不作为业务页面数据源。
- 普通 PC、小程序、日志、异常信息和证据报告均不得展示原文、openid、签名值、密钥或完整 transactionId。
- transactionId 默认仅显示末 4 位；orderNo 可作为业务追溯键展示。
- 原始证据访问只允许独立权限 `order:payment:evidence`，访问必须记录操作人、原因、时间和事件 ID；默认业务角色不授予。
- `RAW_BODY` 与签名材料的当前合同默认保存期为 180 天，实施前必须经过隐私/财务验收；配置项变更同样必须重新评审。到期清理仅清空原始正文和可重放签名值并写 `RAW_PURGED_TIME`，不得删除事件行、结构化支付事实、摘要或业务共键。
- 主动查单响应若含 openid 等身份信息，落库前采用受控加密或结构化脱敏；解密密钥仅由环境变量/密钥管理提供，不进入代码、SQL、日志或交付包。
- `RAW_BODY_SHA256` 仅为完整性摘要，不能称为不可伪造、不可抵赖或付款成功证明；付款成功必须由验签/查单证据和结构化共键共同证明。
- `DATA_STATUS` 不得用于逻辑删除财务流水或支付事件；任何异常状态必须保留并进入人工对账。

## 14. 最低常驻对抗测试

### 14.1 AUTH

- code2session 错误、过期 code、禁用/注销/逻辑删除用户拒绝；
- 不返回 openid/session_key，明确返回 tokenName/tokenValue；
- bindTicket 过期、重放、purpose/appid 错位拒绝；
- 手机号/openid 20 并发绑定只允许一个主体成功；
- 冲突不覆盖已有账号；自动建用户不伪造性别；
- 不存在 dev-auth、万能密码或 userId 换 Token。

### 14.2 创建幂等与输入

- 同用户同 requestId 同参数 20 并发只生成一订单、一支付单；
- 跨日期、重启、Redis 丢失仍得到相同订单号；
- 永久卡 `PAY_EXPIRE_TIME=createTime+30min`；有限卡取该值与 `expireTimeAtCreate` 的较早者；有限卡剩余有效期不足 1 分钟拒绝创建；
- 同 requestId 幂等重试必须保留原资格快照和 PAY_EXPIRE_TIME；后续其他充值、卡有效期延长或套餐变化均不得改写旧订单截止时间；
- 同 requestId 改 cardId/packageId/快照拒绝且零副作用；
- 不同用户相同 UUID 不冲突；哈希碰撞分支拒绝；
- 订单或 payment 写入失败整体回滚；既有订单缺 payment 不自动修复；
- 无卡、他人卡、冻结、注销、过期卡拒绝；
- 卡范围为空/非法时拒绝；套餐范围为空时保持卡范围，非空范围仅在与卡范围规范化语义精确相等时允许，错位时零副作用拒绝；
- 下架/删除套餐、空名称、非法金额/水量/bonus/unitPriceSnap/expireDays、业务上限和 Long 溢出拒绝；
- 有限/永久交叉前后端均拒绝，历史快照不被追溯修改。

### 14.3 事件与两阶段事务

- 非法微信签名/HMAC 不占用可信事件键；空 Pay-Sim 密钥拒绝启动；production 接口 404；
- 同一事件键重复正文幂等，同键不同正文报警；
- 通知与主动查单指向同一 transactionId 只入账一次；并发 NOTIFY/QUERY 成功事件最终都应可处理完成，但全局仍只有一次卡变更和一条充值流水；
- QUERY NOTPAY 留证但保持 payment 1/order 1，后续 SUCCESS 使用不同事件键并可正常推进；权威 CLOSED 精确推进 payment 1/order 1→payment 4/order 5，已支付态收到 CLOSED 不得回退；
- NOTPAY→SUCCESS、NOTPAY→CLOSED 的事件键演进均可保存；非成功查询缺交易号/成功时间时不得补造，SUCCESS 缺任一必填事实则不生成可信事件；
- 先 CLOSED，再由 NOTIFY/QUERY 并发写入同一迟到 SUCCESS：两条事件都进入 RECONCILIATION_REQUIRED；一次事实组级人工授权后只生成一条充值流水、订单 6→4、卡只增加一次，全部同事实 SUCCESS 最终均为 PROCESSED，pay-status 不得 mismatch；
- 权威 SUCCESS 恰好等于 PAY_EXPIRE_TIME 时可进入正常链；晚 1 秒时必须保留 `payment=2`、订单进入 6、事件进入人工对账且无权益副作用；
- `payment 2/order 4` 收到新的同事实 SUCCESS 时，事务 A 只写 PENDING；Worker 在事务 B 锁内核验唯一流水、credit、两个 AFTER、卡终值和账本连续性后才置 PROCESSED，且卡、流水、订单均零写入；
- `payment 2/order 7` 收到新 SUCCESS 时必须进入 RECONCILIATION_REQUIRED，不得在当前未启用退款合同下自行完成；
- PAY_SIM 不能写成 WECHAT，payment/event 来源错位拒绝；
- 内部 order/payment 缺失或共键错位时外部事实仍保存但不入账；
- 事务 B 强制失败不回滚事务 A；
- Worker A 的事务 B 失败并回滚，Worker B 随后成功入账，A 再执行独立失败落痕：最终必须保持 `payment=2/order=4`、卡只增加一次、充值流水恰好一条；A 的落痕只能在锁内核验后收敛匹配事件，绝不能把订单降为 6；
- 20 并发 Worker 只有一个 claim，租约到期可恢复；
- 错金额、错币种、错订单、错卡、跨单 transactionId 全部零权益副作用。

### 14.4 credit、流水与状态机

- 水量、纯金额、水量+bonus 三类 credit 精确；负数、双零拒绝；
- 同一订单 20 并发处理只原子入账一次且只有一条业务键流水；
- 同一卡不同订单并发到账后两个 AFTER 逐笔连续并等于卡终值；
- 被 `@TableLogic` 隐藏的旧流水仍阻断重复入账；重复/孤儿/断裂流水不得幂等成功；
- 创单资格快照状态必须为 1；付款资格只按不可变 PAY_EXPIRE_TIME 判断，其他充值不得追溯延长旧订单资格；
- 付款截止前成功、处理时卡仅自然过期为状态 3 时可继续计算；状态 2 冻结保持订单 2 重试，状态 4 注销、归属变化或 scope 变化进入订单 6；
- 有限卡按 `max(currentExpireTime,paySuccessTime)+expireDays` 续期；结果不晚于 processingTime 时不生成已过期权益并进入订单 6，结果晚于处理时间时权益、有效期和状态 1 必须原子写入；永久卡与永久套餐始终保持 `EXPIRE_TIME=NULL`；
- 客户端取消不直接关闭；仅支付方 CLOSED 进入 5；
- 订单 5 迟到支付进入 6且付款事实保留；6→4 只产生原唯一流水；6→7 必须由另行授权的真实退款合同驱动，L2-T 不得模拟；
- 验签/HMAC 未通过或请求事实不完整时，不生成可信支付事件，order/payment/card/flow 零变化；
- 验签、解密或权威查单已证明外部事实但内部对象缺失/错位时，可信事件必须保留，card/flow 零变化，payment/order 只能按第 6.2 节的精确状态分支变化，禁止用“拒绝路径零变化”掩盖真实外部事实。

### 14.5 查询与跨端

- pay-status 水平越权、非充值单、缺/重复 payment、来源错位均拒绝；
- pay-status 对 payment/order 合法组合逐项测试；充值单状态 3/8 以及其他非法组合必须 mismatch，禁止用数值大小推断生命周期；
- Long 身份 ID 全程 string；
- U06/PC 同一订单号的 payment/event/flow/card/status 一致；
- PC 坏关联只显示 mismatch，不显示充值成功；
- L2 Real 任一关键依赖失败不读取 scenarioStore、不回退 Mock；
- 普通页面和日志不泄露原始报文、openid、签名和完整 transactionId。

### 14.6 历史迁移

- 精确旧态订单 #2 只归档一次并生成一条 canonical 事件；
- 已归档精确终态重复运行幂等 no-op；
- 部分归档、canonical 事件缺失/重复、payment/充值流水意外存在、身份/金额/快照任一错位时 `SIGNAL` 且整事务回滚；
- 独立临时 MySQL 中验证 fresh、旧态、幂等和冲突场景，主库保持零写入。

## 15. 实施状态

已完成：

- 正式登录和手机号绑定代码；
- 本人水卡、套餐和范围读取；
- 首次购卡与已有卡充值订单；
- Pay-Sim 支付事实、两阶段事务、原子入账和唯一流水；
- 小程序支付状态、订单详情和余额回读；
- PC 支付、事件、水卡和流水追溯；
- 首次发卡、有效期、范围继承和并发幂等保护。

待实施：

- 正式微信 JSAPI 支付、通知验签、主动查单和关单；
- 退款、退款回调、日对账和差错处理；
- 实体卡、后台人工充值和生产财务审批。

## 16. 生产接入条件

正式微信支付接入前需要准备 AppID、商户号、APIv3 Key、平台证书、回调域名、退款权限和对账规则。生产迁移和真实资金操作按照项目资金安全规范执行。
