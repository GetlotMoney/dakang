# E2E-09 商城交易与履约｜可执行契约（S0 边界 / S1 商品与库存基座 / S2 交易与支付 / S3 前置仓履约 / S4 退换货与退款）

状态唯一源见 docs/requirements/demo-business-chain-matrix.md §6，本契约不维护进度。
需求锚：REQ-201（商城首页）、REQ-202（商城交易）、REQ-203（商品库存）、
REQ-205（前置仓管理）、REQ-204（配送履约，S3）、REQ-206（退换货，S4）。

## 一、冻结边界（本期不再自行扩张）

1. 平台自营实物商品；无第三方商家/入驻/佣金/多商户结算；不接健康档案与推荐。
2. SPU（展示主体）+ SKU（定价与库存主体）；金额恒正整数分；库存恒非负整数件。
3. 库存按（前置仓×SKU）独立管理，同一 SKU 可存在于多仓。
4. 小程序只见：已上架商品、启用 SKU、启用仓聚合的可售状态；不见：电话原文、库存流水、
   成本、删除标记、原始范围 JSON、内部数量明细（本期对端上只暴露有货/缺货布尔）。
5. 图片仅存已有合法 URL/相对路径，无上传能力。
6. 履约范围结构化：`{"scopeType":"districts","districtCodes":["420111"]}`，服务端唯一校验
   （六位行政区码、非空数组、无重复）；前端不解析不发明规则。
7. 库存动作类型 1~11（枚举权威 MallEnum.StockFlowType，明细见 §10.3/§12.6）。
   1~4 与 5~11 准入口互斥：人工端点写不出 5~11，售后链路也写不出 1~4。
8. 不复用 ws_package/水配送专用字段；不改一期 E2E-01~08 语义。

## 二、数据模型（六表；DDL 权威见 server/sql/ws_mall.sql 与同轮 init/迁移）

| 表 | 职责 | 唯一约束 |
|---|---|---|
| ws_mall_category | 商品分类 | uk_mall_category_code(CATEGORY_CODE) |
| ws_mall_product | SPU 主表 | uk_mall_product_no(PRODUCT_NO) |
| ws_mall_sku | SKU 定价/规格 | uk_mall_sku_no(SKU_NO) |
| ws_mall_warehouse | 前置仓档案 | uk_mall_warehouse_no(WAREHOUSE_NO) |
| ws_mall_stock | 仓×SKU 库存 | uk_mall_stock_wh_sku(WAREHOUSE_ID,SKU_ID) |
| ws_mall_stock_flow | 只增库存流水 | uk_mall_stock_flow_biz_key(BIZ_IDEMPOTENCY_KEY)，不含 DATA_STATUS |

要点：
- 业务编码（CATEGORY_CODE/PRODUCT_NO/SKU_NO/WAREHOUSE_NO）唯一键刻意不含 DATA_STATUS——
  逻辑删除后编码不得复用。
- SPEC_SNAP 为扁平规格对象（string→string，≤8 键，键≤20 字、值≤50 字、原文≤500 字），
  编解码唯一入口 MallSpecSnapshot，写读同约束（R1-P1-3）：读取严格 fail-closed——损坏
  JSON、嵌套、数组、数字/布尔/null 值、超键数、超长都按快照损坏抛出，管理端与小程序端
  整次详情失败；小程序/PC 适配层同样只接受 Record&lt;string,string&gt;，任一非法整行失败。
  禁任意 JSON 入库，禁把损坏快照静默渲染成正常商品。
- MARKET_PRICE 有值时 ≥ SALE_PRICE；WEIGHT_GRAM 非负整数克。
- ws_mall_stock.AVAILABLE_QTY/RESERVED_QTY 恒 ≥0，由命名 CHECK 库层硬闸兜底
  （chk_mall_stock_available_nonneg / chk_mall_stock_reserved_nonneg，四轨同置且有
  SchemaParityTest 钉住，R1-P2-1）——"负库存库层不可达"以此为实锚，不只靠应用层
  条件 UPDATE；一切扣减必须原子条件 UPDATE，禁止「SELECT→内存算→UPDATE 回写」。
- 流水恒只增：AVAILABLE_CHANGE/RESERVED_CHANGE 带符号，AFTER 双列=动作后终值；
  流水与库存更新同一事务，失败共同回滚。

## 三、字典（1388~1392 新增；1363 增值；均已核对 ApiEnum.DictType 未占用）

| type | 名称 | 值 |
|---|---|---|
| 1388 | 商城商品状态 | 1草稿 2已上架 3已下架 |
| 1389 | 商城SKU状态 | 1启用 2停用 |
| 1390 | 前置仓状态 | 1启用 2停用 |
| 1391 | 商城库存流水类型 | 1人工入库 2人工出库 3盘点调增 4盘点调减；5~11 已全部实装（订单/售后/换货，见 §10.3、§12.6） |
| 1392 | 商城分类状态 | 1启用 2停用 |
| 1363 增值 | 领域事件类型 | 11 商城动作 |

## 四、状态机与阻断

- 商品：草稿(1)→已上架(2)→已下架(3)→（可再上架 2）。上架/下架走 VERSION CAS 且校验影响行数。
- 上架三阻断（服务端唯一实现）：分类停用；无启用 SKU；无「启用仓 AVAILABLE_QTY>0」的可售库存。
- 分类停用不影响已上架商品展示，但阻断新上架。
- 仓库停用：不参与小程序可售聚合；不阻断 PC 对其库存的人工动作与流水查询。
- SKU 停用：端上不可见；不删除历史库存与流水；被库存/后续订单引用的 SKU 禁物理删除（仅状态停用）。
- 商品下架：不删 SKU/库存/流水。

## 五、库存动作契约（/mall/stock/adjust）

输入：requestId（规范 UUID）、warehouseId（string）、skuId（string）、
flowType（1入库/2出库/3盘点调增/4盘点调减）、quantity（正整数）、reason（必填 ≤200）。

SKU 候选（R2-P0，/mall/stock/sku-candidates）：数据源恒为 ws_mall_sku ⋈ ws_mall_product，
独立于库存行——新建 SKU 尚无 ws_mall_stock 行也必须可选中做首次入库（入库/盘点调增经
upsert 建行），否则"商品→库存→上架"纵向链在第一件新品上断裂；出库/盘点调减对无行
SKU 照旧按不足拒绝。停用 SKU 照常返回并带状态标识（动作既有语义只要求 SKU 存在，
不设"停用禁调整"规则）；关键字远程搜索（SKU 编号/名称），单页硬上限 50，禁无上限
全量拉取；身份 ID 出参恒 string。PC 库存动作候选必须调用本接口，禁止从库存列表派生。

返回（R1-P1-2）：动作结果 Vo（requestId/warehouseId/skuId/flowType/availableChange/
availableAfter/reservedAfter），首次与重放都从同一条幂等流水行构造——重放返回的是
原动作时刻的冻结后置值，绝不用"重放时刻当前库存"冒充（两动作夹一重放能区分）。

事务内步骤（唯一实现；SQL 正文与 S+S→X 死锁理由见 WsMallStockMapper.java:31-54 注释）：
1. 幂等预读：按 BIZ_IDEMPOTENCY_KEY=`MALLADJ:<requestId>` 查流水——命中则逐字核
   （仓/SKU/类型/数量）：一致→按命中流水行构造冻结结果返回、零副作用；
   不一致→拒绝「同请求参数不一致」。
   正确性锚是流水唯一键（并发同键第二写入撞键回滚），预读只是快路径。
2. 原子更新（禁止「先盲 INSERT 撞键再 UPDATE」）：入库/盘点调增走单条 upsert 建行或累加；
   出库/盘点调减走条件 UPDATE，行不存在或可售不足都=0 行，一律按不足拒绝 → 抛出，
   整事务回滚，零流水零库存变化。
3. 事务内重读 AFTER 终值 → 插入流水（唯一键兜底并发）。
4. 审计：recordReliableInTx（EventType.MALL(11) 商城动作），随事务共同提交/回滚。

锁序：多条库存时按（WAREHOUSE_ID,SKU_ID）升序处理；本期单条为主，服务结构可承接 S2 多 SKU 预占。

## 六、后端接口（全部 POST，成功码 0；Long ID 出参恒 string）

PC：登录 StpKit.DRIVER_MANAGE，写操作叠加对应 mall:* 权限码（权限码见 §七）；
端点集合权威在 MallCategory/MallProduct/MallWarehouse/MallStockController 的 @RequestMapping。

小程序（登录 StpKit.DRIVER_KH_USER，只读）：
- /mini/mall/home：启用分类 + 已上架商品卡（productId/name/subtitle/coverUrl/minSalePriceFen/
  categoryId/inStock）；可选 categoryId 过滤。
- /mini/mall/product/detail：SPU + 启用 SKU 列表（skuId/skuName/specs（扁平键值）/salePriceFen/
  marketPriceFen?/weightGram/inStock）+ 商品级 inStock。
- 白名单出参之外一律不下发；任一数据源异常 fail-closed 返回错误，端上不回退 Mock。

仓库电话：PC 列表/详情脱敏（PhoneMask），原文不进任何小程序响应；范围 JSON 仅 PC 编辑面回显结构化字段。

## 七、菜单/权限（IDs 已核对空闲；PC 路由、Controller 注解、SQL 三处逐字一致）

- 目录 1005 商城管理 /mall（sort 82）
- 菜单 1036 商品管理 /mall/product；1037 分类管理 /mall/category；
  1038 前置仓管理 /mall/warehouse；1039 库存管理 /mall/stock
- 功能点 1151 mall:category:edit；1152 mall:product:edit；1153 mall:product:shelf；
  1154 mall:warehouse:edit；1155 mall:stock:adjust
- 菜单基线唯一落 deploy/mysql/init/03-demo-baseline.sql（超管绑定 SELECT 之前）；
  迁移携带同 ID 同码副本供既有库升级。

## 八、前端契约

- PC/小程序 ID 全链 string（后端 Vo ID 字段即 String；入参 string 经 Spring 转 Long）。
- PC 一级工作区与商城下挂页面清单以 client/src/config/businessNavigation.ts 为唯一来源；
  字典下拉全部走字典接口；
  金额分↔元仅展示层换算。
- 小程序固定 Tabbar 三项不变，商城新增页均非 tab；页面清单权威见 miniapp/src/pages.json
  与 routes.ts（M01~M11 编号在 §11.9、§12.8）。商品详情页提供 SKU 选择、数量、
  加入购物车与立即购买（立即购买直跳结算确认页不经购物车）。
- 结算页对"地址未选区县"做本地拦截并提供补选入口；该拦截依赖后端 MiniAddressVo.districtCode
  已上线，故**小程序不得先于后端部署**，否则所有地址都会被判为未填区县（方向 fail-closed，
  不会错放订单，但联调会卡住）。

## 九、种子数据（init 与迁移同源；仅演示态）

3 分类（含 1 停用）；5 商品（覆盖草稿/上架/下架）；8 SKU（含停用、划线价、多规格）；
2 前置仓（1 启用+1 停用，武汉行政区码，假手机号）；库存覆盖有货/缺货/停用仓有货
（用于验证「停用仓不计可售」）；流水与库存终值自洽。

## 十、S2 购物车、订单、库存预占与 Pay-Sim

### 10.1 新增五表（三轨 + 测试轨，SchemaParityTest 看守）

| 表 | 职责 | 唯一约束 |
|---|---|---|
| ws_mall_cart_item | 购物车行 | uk_mall_cart_user_sku(USER_ID,SKU_ID) |
| ws_mall_order | 商城订单 | uk_mall_order_no(ORDER_NO)、uk_mall_order_user_request(USER_ID,REQUEST_ID) |
| ws_mall_order_item | 订单明细快照 | uk_mall_order_item_order_sku(ORDER_ID,SKU_ID) |
| ws_mall_payment | 商城支付单 | TRANSACTION_ID / ORDER_NO / ORDER_ID 各一条 |
| ws_mall_payment_fact | 支付事实收件箱 | uk_mall_payment_fact_key(PAY_SOURCE,FACT_CHANNEL,PROVIDER_EVENT_KEY) |

要点：
- 与一期 ws_order/ws_payment/ws_payment_event **完全分域**：商城订单 ID 与 ws_order.ID
  存在碰撞可能，复用一期支付单会让两域订单混指同一行。
- 全部唯一键刻意不含 DATA_STATUS（创单幂等锚与支付事实键不能因误删而解锁重放）。
- 购物车移除=逻辑删除，再次加购由 upsert 复活同一行（唯一键不含 DATA_STATUS 的必然要求）。
- 金额与数量恒等式由库层 CHECK 兜底：`ORDER_AMOUNT_FEN = PRODUCT_AMOUNT_FEN + DELIVERY_FEE_FEN`、
  `ITEM_AMOUNT_FEN = UNIT_PRICE_FEN × QUANTITY`、数量 > 0。三条实测拦得住篡改写入。
- ws_user_address 增 `DISTRICT_CODE varchar(6) NULL`：**可空是刻意的**，存量地址没有区县码，
  禁止按 REGION 文本猜测回填（猜错=把订单派给不覆盖该地址的仓）。地址保存链（
  MiniAddressSaveBo/MiniAddressVo/MiniFamilyServiceImpl）同轮补齐写入与回显——列、PO、
  选仓逻辑三者齐备但保存入参缺字段时，用户永远无从选区县，整条下单链断在第一步。

### 10.2 字典（1393~1395 新增；1391 增值 5~8）

| type | 名称 | 值 |
|---|---|---|
| 1393 | 商城订单状态 | 1待支付 2已支付待履约 3履约中 4已完成 5已取消 6已全额退款 |
| 1394 | 商城支付状态 | 1待支付 2支付成功 3支付失败 4已关闭 |
| 1395 | 商城支付事实处理状态 | 1待处理 2处理中 3已处理 4待重试 5需对账 |
| 1391 增值 | 库存流水类型 | 5下单预占 6预占释放 7支付实销 8退货回库 |

1391 的 5~8 标签与 `MallEnum.StockFlowType` 的 desc 由 SchemaParityTest 逐条比对：
字典用 NOT EXISTS 幂等写法，标签写错并上线后再改源文件也不会更新既有行。

### 10.3 库存动作与幂等键

| 动作 | 流水类型 | 原子语句 | 幂等键 |
|---|---|---|---|
| 预占 | 5 | `AVAILABLE-=q, RESERVED+=q WHERE AVAILABLE>=q` | MALLRSV:&lt;orderNo&gt;:&lt;skuId&gt; |
| 释放 | 6 | `AVAILABLE+=q, RESERVED-=q WHERE RESERVED>=q` | MALLREL:&lt;orderNo&gt;:&lt;skuId&gt; |
| 实销 | 7 | `RESERVED-=q WHERE RESERVED>=q`（AVAILABLE 不变） | MALLSALE:&lt;orderNo&gt;:&lt;skuId&gt; |

S4 另加退货回库（8，`AVAILABLE+=q`、RESERVED 不变）与换货三动作（9 预占 / 10 出库 /
11 释放，语句与 5~7 同形），幂等键唯一落点见 12.6。

影响行数即裁决；多 SKU 按 (WAREHOUSE_ID, SKU_ID) 升序处理（一单一仓，故等价于 SKU 升序）。
5~11 只能由订单与售后链路在服务内部写入：人工端点由 `StockFlowType.isManual()` 显式挡住，
放行等于绕过订单凭空制造预占或实销。

### 10.4 创单事务（唯一实现）

顺序固定：幂等预读 → 地址归属解引用（requireOwnAddress）→ 区县码非空校验 →
SKU/商品权威读 + 上架与启用校验 + 规格快照可解码校验 → 选仓 → 服务端按 SKU 现价重算金额 →
写订单（并发同请求号在此撞唯一键，尚未动库存）→ 写明细 → 按锁序原子预占 → 写支付单 →
清理本次已下单的购物车行 → 审计。任一 SKU 预占失败即抛出、整事务回滚，**不允许部分预占落地**。

- 下单行由请求显式携带（skuId+quantity），不隐式读购物车：一是用户确认的就是结算页那几行，
  二是 requestId 重放必须能逐字核对"SKU/数量/地址有没有变"，而购物车在首次创单成功后已清理。
- 重放等价判据：地址 ID 相同 + 全部行（SKU 与数量，按 SKU 升序）逐字相同；任一不符即拒绝。
- 配送费固定 0 但落快照，不是正式商业运费规则；不支持货到付款。

### 10.5 选仓

候选=启用 + 履约区县包含收货区县码；再筛能整单满足的；多个满足时**按仓库 ID 升序取第一个**
并冻结进订单。刻意不做"最近仓/最优仓"——没有距离数据时任何加权都是编造的规则，
确定性顺序至少可复现可解释。无仓可整单履约时 fail-closed 提示"当前地址暂无可履约库存"。

### 10.6 支付两段式

- 事务A（`IMallPayFactService.recordFact`）：落支付事实 + 支付单置成功。重复事实复用原行，
  绝不改写已存档的外部证据。
- 事务B（`IMallPayApplyTx.apply`，REQUIRES_NEW）：重读并重验共键 → 订单 CAS 1→2 →
  按锁序逐 SKU 实销。入参只有事实主键，其余全部锁内重读。
- B 失败不回滚 A：事实丢了补不回来，推进可以重试。`MallPayFactRetryWorker` 按
  claim 租约（300s）重放，租约到期可被重新认领（进程崩溃不会让事实永久卡在处理中）。
- 幂等三保险：实销流水唯一键、订单状态 CAS、事实处理状态 claim。
- 订单已取消却收到成功事实 → 转人工对账（PROCESSING_STATUS=5），**不重扣库存、不拉回订单**。
  金额或币种不符同样转人工——重试多少次都不会自愈。

**严格共键准入（`MallPayFactGate`，事务A/B 共用一份判据）**

两段刻意分处两个事务、中间隔着一次提交，所以**事务B 不得信任事务A 的旧结论**。判据只有一份，
两段都必须完整跑一遍：

| 判据 | 内容 | 不满足时 |
| --- | --- | --- |
| 基础关联 | 订单与支付单均存在、`DATA_STATUS` 均为 0、`ORDER_NO`/`ORDER_ID`/`PAYMENT_ID` 完全一致 | 事实置 5 待对账 |
| 金额来源 | 金额三方一致、币种一致、`PAY_SOURCE` 一致、交易号非空 | 同上 |
| 业务时间 | `DateUtils.isCanonicalBusinessTime`：14 位数字 + 真实日期解析 + 格式化往返逐字相同 | 同上 |
| 付款窗资格 | `order.CREATE_TIME <= PAY_SUCCESS_TIME <= order.PAY_EXPIRE_TIME`；且两处 `PAY_EXPIRE_TIME` 均合法并逐字相同 | 同上 |
| 事务B 追加 | 支付单精确处于**本事实**推成的成功态：`PAY_STATUS=2` 且交易号/成功时间/来源三者与事实相符 | 同上 |

任一不满足：事实转人工，订单保持待支付，预占既不释放也不实销，零新增实销流水。

订单与支付单一律用 `selectByOrderNoIncludingDeleted` 原样读，是为了让"被删了"成为可判定事实
而非"查不到"——代价是准入必须自己显式核 `DATA_STATUS`。

**订单 CAS 影响 0 行 ≠ 幂等完成**：判定前按订单号**重读**订单（首读到此处之间夹着数次库查询，
陈旧快照判"是否已完成"等于没判），必须同时满足订单处于已支付及其下游状态、未被逻辑删除、
且每个明细都有一条对应 `MALLSALE:` 实销流水——该流水须未被逻辑删除、类型为 7、仓与 SKU 相符、
预占扣减量等于明细数量——才判 ALREADY；逻辑删除、取消、状态错位、证据缺失一律转人工。
只看状态不看证据，恰好会放过最需要人工介入的那一类。

流水按幂等键原样读（键不含 DATA_STATUS），因此**证据判定必须自己排除已删除行**：一条被删掉的
流水足以挡住重复插入，却不足以证明货真的卖出去过。

**付款窗资格**：比较用**事实自带的** `PAY_SUCCESS_TIME` 而非当前时间（迟到重放不改变
窗内事实）；三串均为 14 位定宽规范形态，直接字典序比较。Pay-Sim 入口另有一道闸：已过
`PAY_EXPIRE_TIME` 时不允许再造出新的成功事实（闸只加在 `fact == null` 分支，已存在事实的
重放不受影响；闸不改写状态，终态仍由 `casSuccess`/`casClose` 决定）。论证全文见
decisions.md D-422（S2-R3）。

**交易状态白名单**（`MallEnum.TradeState.isKnown`，准入与推进段共用）：只认
`SUCCESS`/`NOTPAY`/`CLOSED`，绝不按前缀、大小写或语义近似猜测。

| 状态 | 行为 |
| --- | --- |
| SUCCESS | 进入严格共键与付款窗准入 |
| NOTPAY | 只留查询证据，不动订单、支付单与库存 |
| CLOSED | 只供权威关单链消费，不进入实销 |
| 其他非空值 | 事实置 5 待对账；事务B 再拦一次，绝不判 ALREADY |
| null / 纯空白 | `recordFact` 整笔抛错拒绝，连事实都不落 |

为什么未知状态必须转人工而非视为「无事」：见 `MallEnum.TradeState.isKnown` javadoc。

**审计人**：支付回调与 Pay-Sim 推进属系统动作，`casSuccess` 的 `UPDATE_BY` 写 0；
订单主键不是操作人。

### 10.7 取消与超时关单

- 用户取消仅允许待支付；先 CAS 订单状态（带 USER_ID 归属谓词）命中后再释放预占——
  顺序反过来的话两个并发取消会各释放一次，库存凭空多出一份。
- 超时关单 Worker **必须先取得支付方查单 CLOSED**：本地到期只说明"该催了"，不说明对方
  没收到钱。无查单适配器时关单链路整体停摆（生产未接真实支付即此状态），刻意 fail-closed。
- 关闭前把 CLOSED 事实落库（查单渠道），让"为什么关的"有据可查。

### 10.8 接口（POST，成功码 0；Long 出参恒 string）

小程序（StpKit.DRIVER_KH_USER，归属恒取会话）：
- /mini/mall/cart/list | save（increment 累加/覆盖）| delete
- /mini/mall/checkout/preview（只读试算，不预占）
- /mini/mall/order/create | page | detail | cancel | pay-status
- /mini/mall/pay-sim/pay（`mall.pay-sim.enabled=true` 才注册）

PC（StpKit.DRIVER_MANAGE，**只读**）：
- /mall/order/page | detail

PC 侧不提供任何写入端点：后台改单会绕过库存动作与支付事实，让订单、库存、资金各说各话。
需要人工干预时走对账流程，不走改单。

### 10.9 配置开关

`mall.pay-sim.enabled` 与一期 `mini.pay-sim.enabled` **独立**：商城收款与取水/充值收款是
两条资金链，开一条不等于该开另一条。生产默认 false 且写显式。
`mall.pay-expire-minutes` 默认 30，创单时冻结进订单与支付单。

### 10.10 菜单

菜单 **1043** 商城订单 `/mall/order`（只读台账，无写权限点）。

**取新菜单号必须查实际占用集合，不能从本模块最后一个 ID 往后推**
（重复 ID 由 `SchemaParityTest.demoBaselineMenuIdsAreUnique` 常态看守）。

## 十一、S3 前置仓履约与配送签收（REQ-204）

### 11.1 状态机

商城订单 2「已支付待履约」→ 3「履约中」→ 4「已完成」；履约任务七态（字典 1396）：

| 值 | 状态 | 谁推进 | 订单副作用 |
| --- | --- | --- | --- |
| 1 | 待拣货 | 系统（支付后生成） | 无 |
| 2 | 待打包 | 前置仓拣货 | **订单 2→3** |
| 3 | 待安排发运 | 前置仓打包完成 | 无 |
| 4 | 待承运方揽收 | 自营=PC 分配配送员；第三方=创建运单 | 无 |
| 5 | 运输中 | 自营=配送员取货；第三方=承运方揽收事实 | 无 |
| 6 | 已送达待确认 | 自营=配送员送达；第三方=承运方送达事实 | 无 |
| 7 | 已签收 | **用户**签收 | **订单 3→4** |

顺序不可跳。每次推进都是「精确前态 + 版本号」CAS，影响 0 行一律拒绝——先查一遍再更新
会让两个并发操作各自看到同一个前态、各推进一次，重复轨迹、重复消息、重复审计都由此而来。

**任务—订单关联校验器是唯一出处**：除共键（orderId/orderNo/userId/warehouseId）外还逐字核
收货四要素与区县码。任务复制的是下单时的冻结快照，一旦被改写而无人校验，拣货、配送、签收
都会照常进行——订单上写着一个地址，货送到另一个地址，事后从任何一端都看不出来。
所有动作与所有角色的读取出口（用户/配送员/PC/候选/ensureTask 命中既有任务与并发赢家两个
分支）都必须过它；组装 Vo 前先校验，未经校验的任务不得进入组装。

**轨迹键被占用一律 fail-closed**：writeTrace 只在状态 CAS 命中后调用，而 CAS 命中者唯一，
所以「合法的重复」不存在。键已被占用只能说明有人预先塞了伪造轨迹、或把真轨迹逻辑删除后
让键悬空——两种情况下都必须整事务回滚，而不是静默跳过让状态照常推进。

**七个节点各一条可靠审计**：`recordReliableOnceAs` + 幂等键 `MALL_FULFILL:<orderNo>:<node>`，
身份显式声明（生成 SYSTEM/0、拣货打包分配 MANAGE/operatorId、取货送达 COURIER/courierUserId、
签收 USER/userId）。配送员与用户共用 KH_USER 会话，不显式声明就会被记成用户。
审计或消息任一写入失败，任务、订单、轨迹与其余证据全部回滚。

**任务生成不在 PC 开放**：只由 Worker 幂等补齐。人工生成端点会成为绕过仓库归属的详情读取
出口——凭订单号即可拿到任务信息。

签收刻意只开放给用户：后台代签会让「用户已确认收货」这句话失去意义，故 PC 无签收端点。

### 11.2 四张表与四把唯一键

| 表 | 唯一键（均不含 DATA_STATUS） | 作用 |
| --- | --- | --- |
| ws_mall_fulfillment | uk(ORDER_ID) | 一单一任务，并发建任务由库层挡住 |
| ws_mall_fulfillment_trace | uk(BIZ_IDEMPOTENCY_KEY) = `MFT:<orderNo>:<node>` | 重复点击不得重复写轨迹 |
| ws_mall_courier_scope | uk(WAREHOUSE_ID, COURIER_ID) | 让「范围外」成为可判定事实 |
| ws_mall_warehouse_operator | uk(WAREHOUSE_ID, OPERATOR_ID) | 让「跨仓操作」成为可判定拒绝 |

**为什么另立配送员范围表**：一期 `ws_courier` 的范围是水站集（`STATION_IDS`）与自由文本
`SERVICE_REGION`，与商城前置仓不是同一套坐标系。按文本猜区域正是地址区县码那次的教训，
所以商城域自持这张绑定表——「该配送员服务哪些前置仓」因此可判定、可拒绝、也可被测试。

收货四要素（姓名/电话/区域/详址）是**下单时的冻结快照**，履约期间不回查地址表：
用户改了默认地址，不能改变已经在配送途中的这一单该送到哪里。

### 11.3 任务生成不挂在支付事务里

支付事务B 是资金链（订单 CAS + 库存实销）。把履约建表塞进去会让两条链共享失败面——
履约表一个约束冲突就能把已经收到的钱回滚掉。改由 `MallFulfillmentDispatchWorker` 每分钟
补齐；`ensureTask` 幂等（撞 uk 后重读赢家原样返回），扫多少次都只会有一个任务。
扫描面是「订单状态精确为 2 且尚无任务」，任务一旦生成即自然收敛。

### 11.4 分配资格四条

准入启用 + 未逻辑删除 + 绑定了本单前置仓 + 不是下单人本人。四条都在 Service 层重判，
不信任候选列表——候选是给人看的，不是授权。分配用 `COURIER_ID IS NULL` 为前置的 CAS，
分配与状态推进同一条语句完成；分两步会让中间态被另一次分配覆盖。

### 11.5 签收事务：一个时间贯穿四处

任务签收时间、订单完成时间、轨迹时间、站内消息时间同源，服务端在事务内取一次。
分开取会让同一次签收出现四个时刻，对账时无法判断先后。
审计事件时间**不在同源之列**：领域事件服务未接收本次业务时间，因此审计时间不属于签收业务
时间同源集合；若需审计与业务动作严格同刻，须先给该服务加显式时间入参，属跨域改动，S3 不做。

**配送归属结构化证据**：`ws_mall_fulfillment.COURIER_ID` 是可变字段，只比「当前 COURIER_ID ==
当前配送员」证明不了归属——把它改指向另一个已启用配送员 B，B 就能凭这一改读到收货地址与电话、
执行取货与送达，且不必重新满足原分配时的前置仓服务范围。故分配节点轨迹落 `SUBJECT_ID`
（被分配配送员 ID；`ACTOR_ID` 仍是执行分配的仓库操作员，两者刻意分列），配送端四个出口
（详情/列表/取货/送达）先过 `requireAssignedCourierEvidence`：分配阶段前 COURIER_ID 必须为空，
分配阶段后必须存在**恰一条**未删除的分配轨迹且 FULFILL_ID/ORDER_NO/TRACE_NODE/SUBJECT_ID
与任务全部相符。证据缺失、逻辑删除、重复、错位一律拒绝，既不降级为只看 COURIER_ID，
也不顺手补轨迹把证据「修好」——那等于自证。禁止从 TRACE_TEXT 或审计详情字符串反解配送员 ID。任务 CAS 与订单 CAS 任一失败即整
事务回滚，绝不留下「任务已签收而订单未完成」的半截终态。

### 11.6 S3 不碰库存

S2 支付成功时已完成实销（流水类型 7）。S3 全程零库存扣减、零释放、零回库；
回库（类型 8）只能由 S4 的合法售后事实驱动。常驻用例 `S3⑪` 以支付实销后的库存与流水
条数为基线逐列比对看守这条。

### 11.7 消息分域

商城消息落新增的 `MsgDomain.MALL(6)`，不塞进「配送(3)」域（那是水配送链的语义），
也不塞进「系统(5)」域含糊了事——S4 售后按域检索时，两条链的消息必须能分开。

### 11.8 接口（POST，成功码 0；Long 出参恒 string）

PC 走 StpKit.DRIVER_MANAGE，小程序走 StpKit.DRIVER_KH_USER（归属恒取会话）；端点集合
权威在 MallFulfillmentController（含 L1 的 shipment/providers|create|list）、
MiniMallFulfillmentController 与 MallLogisticsSimController（/mall/logistics/sim-event，
`mall.logistics-sim.enabled` 才注册）的 @RequestMapping。任务生成与 PC 不代签见 11.1。

三端共用 `mallOrderNo`，履约刻意不另发展示编号——否则用户、仓库与配送员各报一个号，对不上。

### 11.9 三端接入（S3-B）

| 端 | 落点 | 说明 |
| --- | --- | --- |
| PC | 菜单 1044 `/mall/fulfillment` | 以商城订单为入口（履约任务没有独立列表端点——任务由 Worker 按已支付订单幂等补齐，订单本身就是它的索引），默认只看已支付/履约中/已完成；抽屉内含七节点时间线与拣货/打包/分配 |
| 小程序用户端 | M06 订单详情 | 物流状态、配送员、七节点时间线；`已送达待确认` 才出签收入口，签收方式必须显式选择，不默认成本人签收 |
| 小程序配送端 | M07 商城配送任务 / M08 任务详情 | 与一期水配送任务（D01~D05）分路由分页面，入口挂在配送任务中心；只有取货与送达两个动作 |

**PC 不代签**：签收端点不在 `/mall/fulfillment/*` 下，`check-mall-pages.mjs` 已把
「履约页出现签收/取货/送达入口或调用」钉成静态禁用模式。

**候选不是授权**：分配对话框只列出可为本仓配送且已准入的配送员，后端在分配时仍会重新
校验准入、停用、前置仓范围与自配送——前端少列或多列都不改变最终判定。

导航契约版本升至 `pc-demo-navigation-20260809-v7`：旧会话的菜单快照里没有 1044，
不升版本会让已登录用户看不到新页。

**两张绑定表仍无维护界面**：`ws_mall_courier_scope` 与 `ws_mall_warehouse_operator`
目前只有服务端判据，数据需由运维按环境写入；S3 定义的 S3-B 范围只含三端履约页面，
维护界面属后续切片。

### 11.10 S3 未做（属 S4/S5）

真实第三方物流、退货退款换货、库存回库、配送异常资金补偿、自动派单与路线优化、
用户评价、商城分润。

## 十二、S4 退货退款与换货（REQ-206）

### 12.1 固定范围

只做三种售后：**退货退款**、**同 SKU 等量换货**、**未拣货整单取消退款**。
跨 SKU 换货、部分金额协商、运费与差价、第三方商家退款、商城分润一律不做——这些都需要
甲方定价与责任口径，先做出来只会做成一份要推翻的实现。

### 12.2 数据模型（五表 + 订单增一列）

| 表 | 职责 | 唯一约束 |
|---|---|---|
| ws_mall_after_sale | 售后单主表 | uk_mall_as_no(AFTER_SALE_NO)、uk_mall_as_user_request(USER_ID,REQUEST_ID) |
| ws_mall_after_sale_item | 售后明细（按原订单明细） | uk_mall_as_item(AFTER_SALE_ID,ORDER_ITEM_ID) |
| ws_mall_after_sale_trace | 售后轨迹 | uk_mall_as_trace_key(BIZ_IDEMPOTENCY_KEY) |
| ws_mall_refund | 退款单 | uk_mall_refund_no、uk_mall_refund_after_sale(AFTER_SALE_ID)、uk_mall_refund_transaction |
| ws_mall_refund_fact | 退款事实收件箱 | uk_mall_refund_fact_key(REFUND_SOURCE,FACT_CHANNEL,PROVIDER_EVENT_KEY) |

`ws_mall_order` 增列 `SOURCE_AFTER_SALE_ID` + `uk_mall_order_source_after_sale`：
换货补发单是一张真订单（零价、走同一套 S3 七态履约），来源售后单唯一——一张售后单补发
两次货，库层直接挡住。补发单不得再申请售后。

与 S2/S3 一致：唯一键刻意不含 `DATA_STATUS`，逻辑删不放开重复占位。
四轨同步（server/sql/ws_mall.sql、deploy/mysql/init/02-ws-business.sql、
deploy/mysql/migrations/2026-08-10-mall-s4.sql、MallDbSchema.java）由 `SchemaParityTest` 守。

### 12.3 状态机（字典 1399）

`1 待审核 → 2 待退货 → 3 待质检 → (4 退款处理中 | 5 换货补发中) → 6 已完成`；
旁支 `7 已驳回`、`8 待人工`、`9 已取消（仅待审核可撤）`。轨迹表即状态到达史，
节点幂等键 `MAT:<afterSaleNo>:<节点值>`——同一节点写第二次即证据冲突，整事务回滚。
轨迹只有一个写入口 `MallAfterSaleTraceWriter`：售后服务、退款推进段与换货签收结算分处
三个事务边界，各写一份的结果就是换货链一度整个缺了完成节点，而建表口径写的是
「轨迹即状态到达史」。

准入与服务端判据同形（前端的可选项也按这套收敛）：

| 类型（字典 1400） | 订单前置条件 | 备注 |
| --- | --- | --- |
| 1 退货退款 | 订单 4 已完成 且 签收后 `mall.after-sale-window-days`（默认 7）天内 | 履约证据缺失即拒 |
| 2 同 SKU 换货 | 同上 | 质检通过才补发 |
| 3 未拣货整单取消退款 | 订单 2 已支付待履约 且 履约任务未越过「待承运方揽收」 | 已开始拣货只能走签收后退货 |

**申请入参没有金额字段**：应退金额恒由服务端按原订单不可变明细 `单价×批准数量` 算出。
页面不提交金额、审核人也改不了金额——一旦允许人工输入，退多少就取决于谁在操作。

**数量累计上限靠行锁**：同一订单的累计申请量没有单条唯一键可表达，申请事务先
`selectByOrderNoForUpdate` 取订单行锁再判上限，锁序恒为「订单 → 明细」。

**审核时刻必须重验状态（R1-P0）**：申请时那把订单行锁在申请事务提交时就释放了，
申请与审核之间订单可以一路被拣货、发货、签收。`audit` 因此在锁内重读订单并复核
`requireStillApplicable`（整单取消要求订单仍 =2 且履约 ≤ 待拣货；退货/换货要求订单仍 =4），
**但不重验时间窗**——窗口约束的是「什么时候可以提出申请」，审核人第八天才点通过不该反悔。
整单取消审核通过后同事务把订单 CAS 2→5 已取消：拣货的唯一前置是订单精确处于 2，
不推走就等于审核通过之后这张单仍能被正常发货，钱退了货也走了。退款成功后事务B 再 5→6。

### 12.4 质检结论决定两件事，且互不代替（字典 1401）

| 结论 | 退款 | 回库 |
| --- | --- | --- |
| 1 通过可重新销售 | 是 | 是（流水 8） |
| 2 通过不可重新销售 | 是 | **否** |
| 3 不通过 | 否（驳回） | 否 |

把退款与回库绑成一个动作，就会出现「为了退钱把坏货放回可售」的账实不符。

### 12.5 退款两段式（与 S2 支付同形）

事务A `MallRefundFactServiceImpl.recordFact` 只落外部事实并做准入判据（退款单存在、
来源一致、交易号非空、金额相符、`isCanonicalBusinessTime` 严格业务时间）；不合格直接
落 `需对账` 并留证。事务B `MallRefundApplyTxImpl.apply`（REQUIRES_NEW）在锁内重读
退款单、售后单、订单、支付单，逐项重验共键后才 CAS 推进——两段之间隔着一次提交，
事务B 不信任事务A 的结论。任一错位一律转人工，绝不"接着往下走"。

退款状态白名单（`RefundState.isKnown`）第二道设在事务B：读不懂的状态不得当成「反正不是
成功那就没事」。失败分类：确定性失败（JbkException / 数据完整性）直接转人工，只有瞬时
失败排重试，上限 5 次到顶转人工。

累计成功退款达到商品实付时订单转 6 已全额退款；未达到则主订单保持原终态。
该判据是跨行汇总，没有单条唯一键可表达，故事务B **先取订单行锁再读**（R1-P1）：
两笔部分退款并发推进时，非锁定读会让双方都只看见自己那一半、都判「还没退完」，
订单永久停在原终态且无任何重算路径——这是写偏斜，不是慢。

### 12.6 库存四个幂等动作

| 动作 | 流水类型 | 幂等键 |
| --- | --- | --- |
| 退货回库 | 8 | `MALLRET:<afterSaleNo>:<skuId>` |
| 换货预占 | 9 | `MALLEXR:<afterSaleNo>:<skuId>` |
| 换货出库 | 10 | `MALLEXO:<afterSaleNo>:<skuId>` |
| 换货释放 | 11 | `MALLEXC:<afterSaleNo>:<skuId>` |

换货出库发生在用户**签收补发单**时（S3 签收事务的 `settleOnSign` 钩子），不是补发单
生成时——货还没到用户手里就记实销，账上会比现实早一步。补发库存不足时整事务回滚，
绝不留半张补发单：半张单会让用户既没退钱也等不到货。

### 12.7 端点与受控模拟

PC 走 StpKit.MANAGE（可见范围由操作员的前置仓归属在服务端决定），小程序走 StpKit.KH_USER
（归属恒取会话）；端点集合权威在 MallAfterSaleController / MiniMallAfterSaleController
的 @RequestMapping。

`/mall/aftersale/refund-sim` 由 `mall.refund-sim.enabled` 开关，默认 **false**，
生产走 `${MALL_REFUNDSIM_ENABLED:false}`；未启用时 Bean 不存在，控制器 fail-closed 拒绝。
它只产生一条**退款事实**，推不推得动仍由事务B 判——模拟器不是"直接成功"按钮。

**归属闸在资金动作之前（R1-P0）**：refund-sim 是 PC 出口中唯一会把钱推出去的一个，
它在落任何事实之前先过一次 `detailForManage(operatorId, ...)`。此前这道闸只由控制器在
退款执行**之后**那次读详情间接提供——非本仓账号会看到一条错误提示，而账面上钱已经退掉。
同时它以真实操作人落一条 `MALL_REFUND_SIM:<refundNo>` 审计：事务B 的推进审计记的是
SYSTEM（推进由渠道事实驱动），「谁按下了模拟退款」必须另有证据。

`/mall/aftersale/exchange-abort`（R1-P1）：补发送不出去时中止换货——释放换货预占（类型 11）、
把补发单 CAS 成已取消、售后单转 8 待人工。此前「换货补发中」只有补发单被签收一个出口，
补发一旦送不出去（配送员停用、地址失效、货损），售后单永久停在 5，预占也永不释放。
补发单已签收的不许中止：那不是「送不出去」而是「已经送到了」。

### 12.8 三端接入

| 端 | 落点 | 说明 |
| --- | --- | --- |
| PC | 菜单 1045 `/mall/aftersale` | 售后台账 + 审核/确认收货/质检；抽屉内含明细、退款单与轨迹。`check-mall-pages.mjs` 已把「金额/库存可编辑输入」与「直接完成/直接退款」端点钉成静态禁用模式 |
| 小程序用户端 | M09 申请售后 / M10 我的售后 / M11 售后详情 | 申请页只报「哪几条明细、各几件、什么原因」；详情含状态、退款结果与换货补发单跳转；只有待审核可自行撤销 |
| 小程序配送端 | M07/M08 标注「换货补发」 | 标记来自订单的 `SOURCE_AFTER_SALE_ID`，不按金额为零猜；配送员据此知道不再向用户收款 |

订单详情（M06）新增 `orderItemId` 下发：申请售后要按明细 ID 定位原行，只给 skuId 时
一单两行同 SKU 就必错。导航契约版本升至 `pc-demo-navigation-20260810-v8`。

### 12.9 S4 未做（属 S5 或后续）

跨 SKU 换货、退运费与差价、真实微信退款、售后超时自动关单、售后满意度、商城分润。

## 十三、S5 内部模拟全链验收（隔离环境）

### 13.1 环境

复用既有隔离验收编排 `deploy/acceptance/acc-env.sh`：独立 MySQL(3309)/Redis(6381)/EMQX(1884)
与主环境物理隔离，每轮 `rebuild` 从 `deploy/mysql/init` + 全部 migrations + `acc-seed.sql`
重建。商城种子在 `acc-seed.sql` 的 99xx 段：品类/两商品/三 SKU/两前置仓/库存/配送归属/
仓操作员绑定/收货地址。

验收仓 **独占区县码 420117**：选仓规则是「候选中按仓库 ID 升序取第一个」，与演示仓
共用区县码会让验收单落到 ID 更小的演示仓，而操作员与配送员只绑定了验收仓，整条链会
因为一个种子巧合而全红。`acc-ops2` 刻意不绑定任何前置仓——越权对照必须由归属判据给出。

后端以 `--mall.pay-sim.enabled=true --mall.refund-sim.enabled=true` 启动。这两个开关与
一期水业务的 `mini.*` 分开配置，好让只跑商城链的环境不必把水业务的资金模拟一起打开。

### 13.2 Runner

`miniapp/e2e/run-mall-chain.js`，真实 HTTP + 隔离库 DB 断言，固定 16 场景：

| 场景 | 覆盖 |
| --- | --- |
| S1~S2 | 身份与商品底座、端上只见有货布尔 |
| S3~S5 | 下单预占、Pay-Sim 实销、支付重放幂等 |
| S6~S8 | 履约七态、SUBJECT_ID 归属证据、配送越权、签收同源时间与七轨七审计 |
| S9~S11 | 退货退款全链、部分退款不误判全额、退款事实重放 |
| S12~S13 | 换货补发单生成与签收出库、补发单不可再申请售后 |
| S14 | 未拣货整单取消退款 |
| S15 | 他人售后不可见、非本仓运营全线被拒（**看资金事实不看返回码**）、注入金额被忽略 |
| S16 | 幂等键全局唯一、一单一任务、零超退、零悬空事实、三端同号 |

安全闸（连库之前完成）：`MALL_ACC_MODE=full` + `MALL_ALLOW_SIM_REFUND=I_ACCEPT_ISOLATED_REFUND_SIM`；
后端端口为 13330/8081 或 DB 端口为 3306/3308 时拒绝执行。

**禁止 0/0 PASS**：`EXPECTED_SCENARIOS=16` 与实际执行数不符即判 FAIL（判定与负向对照
见 run-mall-chain.js 头注释）。

产物 `docs/acceptance/mall-chain/runs/<批次>-result.json`，落盘前扫描手机号、Token、
运营凭据、数据库口令、原始报文字段与本机绝对路径，命中即拒绝落盘。

### 13.3 S5 未覆盖

真实微信支付与退款、跨 SKU 换货、退运费差价、商城分润、PC 与小程序 GUI 实点。
**16/16 只代表内部模拟链在隔离环境的服务端行为，不等于"商城全部业务形态验收"。**
不得据此写"E2E-09 内部模拟全链路已闭环"；主环境迁移与 GUI 实点的进度唯一源见业务链矩阵 §6。

## 十四、L1 多渠道物流履约与 Logistics-Sim

### 14.1 为什么加一层「承运渠道」而不是给第三方另开一套表

自营配送与第三方物流在平台侧要回答的是同一批问题：这单发了没有、发到哪了、什么时候
到、谁该为它负责。两套表意味着两套状态机、两套幂等键、两套三端读法——它们会各自漂移，
而漂移的第一现场永远是「这单在 A 表已完成、在 B 表还在途」。

因此本轮只做两件事：给履约总单加一个**承运渠道**（`FULFILL_MODE`），再把「出库物」
从履约总单里独立成**包裹**（`ws_mall_shipment`）。渠道无关的状态机原语抽进
`MallFulfillCore`，自营与第三方共用同一份；渠道特有的动作分别落在
`IMallSelfDeliveryService` 与 `IMallLogisticsService`。

### 14.2 履约七态改名（值域不变）

3~6 由「待分配/待取货/配送中/已送达」改为「待安排发运/待承运方揽收/运输中/已送达待确认」。
**值不变、迁移不改数据**，只改字典标签与列注释：原名把自营的动作写进了平台状态里，
第三方单上没有「取货的人」，界面会一直显示一个永远不会发生的下一步。

### 14.3 承运渠道冻结（字典 1404）

| 值 | 含义 |
| --- | --- |
| 0 | 未确定（下单到打包完成期间） |
| 1 | 自营配送 |
| 2 | 第三方物流 |

冻结由 `WsMallFulfillmentMapper.casFreezeMode` 的 `FULFILL_MODE = 0` 前态给出，
**只允许从未确定态迁移一次**。应用层那两句 `if` 只是提前给出好错误信息；真正的互斥
必须由这条 UPDATE 兜住——两个请求各自查到 0 然后各自冻结成不同渠道，一个包裹就同时
挂在两条承运链上，而库里没有任何东西能事后判出哪条才算数。

冻结语句也 `VERSION + 1`。调用方必须同步内存副本，否则紧随其后的状态 CAS 会拿着旧
版本号撞空，报成「任务已被分配」——而实际上是被自己刚才那次冻结改的。

### 14.4 包裹与出站动作四表

| 表 | 职责 | 幂等键 |
| --- | --- | --- |
| `ws_mall_shipment` | 出库物（正向/逆向、承运商、运单号、包裹状态） | `MSHIP:<订单号>:<方向>:<序号>` |
| `ws_mall_shipment_item` | 包裹明细 | uk(包裹, 订单明细, 售后明细) |
| `ws_mall_logistics_event` | 承运方事实（收件箱） | uk(承运商, 事实渠道, 承运方事件键) |
| `ws_mall_logistics_outbox` | 出站动作（发件箱） | `MLOG:<shipmentId>:<actionType>`（MallShipmentGate.ACTION_KEY_PREFIX） |

自营也建包裹：三端展示、售后与对账都按包裹口径读，两条链不能只有一条有出库物。
包裹的渠道值恒取自履约总单，不接受入参——让调用方传渠道，就等于允许建出一个与总单
渠道分叉的包裹。

### 14.5 外呼只在 Worker 里

创建运单的业务事务只做四件本地事：冻结渠道 → 建包裹 → 登记出站动作 → 履约 3→4，
然后返回。真正的外呼由 `MallLogisticsOutboxWorker` 领取 outbox 后执行。

事务里外呼有两个代价：网络慢一秒锁就多持有一秒；对方超时时本地事务回滚，而对方可能
已经把运单建好了——重试会建出第二张运单，而第一张没人知道。挪到事务外之后，「重试只
产生一个运单」由两道保证兜住：适配器对同一 `bizActionKey` 确定性派生同一运单号，
且写回包裹用「前态精确是待发运」的 CAS，第二次影响 0 行。影响 0 行时仍要核对已写入的
号与本次回执一致，不一致说明有第二张运单混进来了，转人工。

### 14.6 承运方事实两段式

事务A（`recordFact`）只落事实并判准入；事务B（`MallLogisticsApplyTxImpl`，`REQUIRES_NEW`）
在锁内重读全部共键后才决定推进。两段之间隔着一次提交，所以事务B 不信任事务A 的任何结论。

- 未知事件状态：**两道白名单**（落库时判准入、推进时再判一次），一律转人工，不做语义猜测；
- 乱序与迟到：`MallShipmentGate.isForward` 只许前进，迟到事件仍落库留证但不改状态；
- 同键重投：复用原行并逐字核对正文，改参一律拒绝——静默返回旧行等于替篡改盖章；
- 异常与取消：包裹转人工 + 留审计，**不自动改状态**，它们要人看，自动处理只会让人看不见。

### 14.7 用户确认闸（本轮最关键的一条）

`MallShipmentGate.fulfillStatusOf` **永远不返回 7**。承运方说「已签收」也只把包裹推到
已送达、履约推到 6。平台的 7 已签收恒由用户在小程序确认收货落定，订单完成同理。

自动确认期限（例如「送达 7 天后自动完成」）未获业务决策，本轮不发明。

### 14.8 承运商适配器

`ILogisticsProviderAdapter` 是厂商差异的唯一落点：`ws_mall_shipment` 上只有
PROVIDER_CODE / SERVICE_CODE / PROVIDER_ORDER_NO / WAYBILL_NO 四个通用列。给履约总单堆
某厂商专用字段，换一家承运商时那些列就变成永远没人敢删的死列。

`LogisticsSimAdapter` 按 `mall.logistics-sim.enabled` 条件装配，运单号为
`SIMWB + sha256(bizActionKey)[0:16]`。PC 的承运商下拉取自**实际装配的适配器**而不是字典表：
字典能被随便加一行，适配器不能；让运营从字典里选，就会选出一个没有适配器的编码，
然后建出一张永远发不出去的运单。

### 14.9 三端

| 端 | 变化 |
| --- | --- |
| PC | 履约抽屉在「待安排发运」并列两个入口（分配自营配送员 / 创建第三方运单），渠道一经冻结另一个消失；新增出库包裹区块（承运商、运单号、包裹状态、承运方轨迹） |
| 小程序 | 订单详情显示承运方式；自营显配送员，第三方显承运商 + 运单号 + 承运方轨迹；运单号未取得时显示「待承运方受理」而不是「-」 |
| 配送端 | 第三方任务**完全不可见**：列表在 SQL 层按渠道过滤，详情/取货/送达四个出口各自 `requireSelfMode`，按单号直查一律当作不存在 |

### 14.10 测试与门禁

真库对抗 22 条（`MallTradeTxDbTest` L1①~㉒，用例名权威在 @DisplayName，判据理由在被测类
javadoc）+ Logistics-Sim 入口合同 4 条（`MallLogisticsSimControllerContractTest`）；
反向变异清单与实现后四维审查结论见 decisions.md D-424。后端 `MallEnum` 与 PC/小程序常量
逐值一致由 `tools/check-enum-parity.py` 常态看守（覆盖面与理由见其 docstring）。

### 14.11 验收 Runner

`miniapp/e2e/run-mall-logistics.js`，固定 10 场景，只覆盖多渠道物流这一层，不重复
`run-mall-chain.js` 已覆盖的自营链。安全闸：`MALL_ACC_MODE=full` +
`MALL_ALLOW_SIM_LOGISTICS=I_ACCEPT_ISOLATED_LOGISTICS_SIM`；主环境端口/主库端口拒绝执行。
禁止 0/0 PASS：`EXPECTED_SCENARIOS=10` 与实际执行数不符即判 FAIL（判定与负向对照见 runner 头注释）。
产物落 `docs/acceptance/mall-logistics/runs/`；实跑结论唯一源见业务链矩阵 §6。

### 14.12 L1 未覆盖

真实第三方物流厂商对接、运费与时效计费、跨仓调拨、逆向物流取件、承运方取消运单
（`ws_mall_logistics_outbox` 已建模 `CANCEL_ORDER` 但**本轮不实现**：承运方取消口径未定，
凭空实现会造出一个平台以为取消了、承运方仍在送的包裹）、自动确认收货期限。

**不得表述为「真实第三方物流已接入」或「外部能力已闭环」。** 进度口径唯一源见
docs/requirements/demo-business-chain-matrix.md §6。
