/**
 * E2E-09 商城内部模拟全链 —— 固定场景验收脚本（真实 HTTP + 隔离验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已按 init + migrations + acc-seed.sql 重建；
 * - 验收后端跑在 13340，且 mall.pay-sim.enabled 与 mall.refund-sim.enabled 均为 true。
 *
 * 安全闸（fail-closed，全部在**连接数据库或创建任何业务数据之前**完成）：
 * - 必须 MALL_ACC_MODE=full；
 * - 必须 MALL_ALLOW_SIM_REFUND=I_ACCEPT_ISOLATED_REFUND_SIM
 *   —— 模拟退款是出账动作，比模拟收款危险一个量级，故要求一个不可能手滑打出的显式串；
 * - 后端端口为 13330/8081（主环境）或 DB 端口为 3306/3308（主库）时拒绝执行。
 *
 * 场景构成（S1~S16，覆盖 S1 基座 → S2 交易 → S3 履约 → S4 售后的完整内部模拟链）：
 * - S1~S2   身份与商品底座；
 * - S3~S5   下单预占、模拟支付实销、支付重放幂等；
 * - S6~S8   前置仓履约、配送归属与越权、用户签收与七节点证据；
 * - S9~S11  退货退款：申请→审核→收货→质检回库→模拟退款→事务B 推进→退款事实重放；
 * - S12~S13 换货：补发单生成（零价 + 来源售后单）→ 补发单履约签收 → 换货出库；
 * - S14     未拣货整单取消退款；
 * - S15     越权与开关（他人售后、非本仓运营、前端不可指定金额）；
 * - S16     证据总账（幂等键唯一、金额守恒、三端同一 mallOrderNo）。
 *
 * 本 runner 不覆盖：真实微信支付/退款、跨 SKU 换货、退运费差价、商城分润。
 * 这些在冻结边界外，不能把 16/16 表述成「商城全部业务形态验收」。
 *
 * 结果纪律：
 * - 场景数量固定（见 EXPECTED_SCENARIOS），实际执行数不等于它即判 FAIL —— 禁止 0/0 PASS；
 * - 只输出结构化 result.json，不生成任何 Markdown；
 * - result.json 内**不写** Token、手机号原文、数据库口令、支付/退款原始报文与本机绝对路径。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const mysql = require('mysql2/promise')

// ─────────────────────────────────────────────────────────────
// 安全闸：任何一条不满足都必须在连库之前退出
// ─────────────────────────────────────────────────────────────
if (String(process.env.MALL_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 MALL_ACC_MODE=full')
  process.exit(2)
}
if (String(process.env.MALL_ALLOW_SIM_REFUND || '').trim() !== 'I_ACCEPT_ISOLATED_REFUND_SIM') {
  console.error('安全闸拒绝执行：必须显式设置 MALL_ALLOW_SIM_REFUND=I_ACCEPT_ISOLATED_REFUND_SIM')
  process.exit(2)
}

const BASE = (process.env.ACC_BASE || 'http://127.0.0.1:13340/dakangApi').replace(/\/$/, '')
if (/:(?:13330|8081)(?:\/|$)/.test(BASE)) {
  console.error('安全闸拒绝执行：ACC_BASE 指向主环境端口（13330/8081）')
  process.exit(2)
}
const DB = {
  host: '127.0.0.1',
  port: Number(process.env.ACC_DB_PORT || 3309),
  user: 'root',
  password: process.env.ACC_DB_PASSWORD,
  database: 'dakang',
}
if (DB.port === 3306 || DB.port === 3308) {
  console.error(`安全闸拒绝执行：ACC_DB_PORT=${DB.port} 指向主库端口`)
  process.exit(2)
}
if (!DB.password) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（请从仓库根 .env 导出）')
  process.exit(2)
}

/** 固定场景数。实际执行数与之不符即判 FAIL —— 这是「禁止 0/0 PASS」的物理实现。 */
const EXPECTED_SCENARIOS = 16

const USER_PHONE = '13999990001'
const COURIER_PHONE = '13999990003'
const OTHER_PHONE = '13999990002'
const OPS_LOGIN = 'acc-ops'
const OPS2_LOGIN = 'acc-ops2'
const OPS_PWD = process.env.ACC_OPS_PWD_CIPHER

// 验收种子固定 ID（acc-seed.sql 的 99xx 段）
const ADDRESS_ID = '9971'
const SKU_BUCKET = '9921'
const SKU_FILTER = '9923'
const WAREHOUSE_ID = 9931
const COURIER_ID = '9601'
const UNIT_PRICE_FEN = 1800

let pool
const results = []
class Fail extends Error {}
function ok(cond, label, detail) {
  if (!cond) {
    throw new Fail(`${label}${detail ? ` | ${detail}` : ''}`)
  }
}
async function scene(name, fn) {
  try {
    const evidence = await fn()
    results.push({ name, pass: true, evidence })
    console.log(`PASS  ${name}${evidence ? `\n      ${evidence}` : ''}`)
  }
  catch (e) {
    results.push({ name, pass: false, evidence: e.message })
    console.log(`FAIL  ${name}\n      ${e.message}`)
  }
}

async function api(p, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers[token.tokenName] = token.tokenValue
  }
  const resp = await fetch(`${BASE}${p}`, { method: 'POST', headers, body: JSON.stringify(body ?? {}) })
  const text = await resp.text()
  try {
    return JSON.parse(text)
  }
  catch {
    throw new Fail(`HTTP ${resp.status} ${p} 非 JSON：${text.slice(0, 150)}`)
  }
}
async function apiOk(p, body, token) {
  const r = await api(p, body, token)
  ok(r && r.code === 0, `${p} 应成功`, `code=${r && r.code} msg=${r && r.msg}`)
  return r.data
}
async function apiFail(p, body, token) {
  const r = await api(p, body, token)
  ok(r && r.code !== 0, `${p} 应被拒绝`, `却返回 code=0 data=${JSON.stringify(r && r.data)}`)
  return r.msg
}
async function q(sql, params = []) {
  const [rows] = await pool.query(sql, params)
  return rows
}
const one = async (sql, p = []) => (await q(sql, p))[0] || null
const rid = () => crypto.randomUUID()

async function miniLogin(phone) {
  const d = await apiOk('/mini/test-login/by-phone', { phone })
  return { tokenName: d.tokenName, tokenValue: d.tokenValue }
}
async function opsLogin(loginName) {
  const d = await apiOk('/api/auth/loginEmployee', { loginName, loginPwd: OPS_PWD })
  return { tokenName: 'dakang-token', tokenValue: d.tokenValue }
}

function flowsOf(orderOrAfterSaleKey, type) {
  return q(
    'SELECT * FROM ws_mall_stock_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE ? AND FLOW_TYPE=?',
    [`%${orderOrAfterSaleKey}%`, type],
  )
}
function stockOf(skuId) {
  return one(
    'SELECT AVAILABLE_QTY, RESERVED_QTY FROM ws_mall_stock WHERE WAREHOUSE_ID=? AND SKU_ID=?',
    [WAREHOUSE_ID, skuId],
  )
}

/**
 * 等履约任务被派单 Worker 幂等补齐。
 *
 * 任务刻意不挂在支付事务里（见契约 11.3），所以支付成功后任务并不立刻存在；
 * 这里轮询数据库而不是直接建任务——建任务的动作只有 Worker 一个入口，
 * 验收脚本自己造一条任务就等于绕开了要验的东西。
 */
async function waitForTask(orderNo, timeoutMs = 150000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const task = await one('SELECT ID FROM ws_mall_fulfillment WHERE ORDER_NO=?', [orderNo])
    if (task) {
      return
    }
    if (Date.now() > deadline) {
      throw new Fail(`履约任务在 ${timeoutMs}ms 内未被派单 Worker 补齐：${orderNo}`)
    }
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
}

/**
 * 下单 → 支付 → 履约到签收，返回订单号。
 *
 * 抽成函数而不是在四个场景里各写一遍：这条链有六个状态跃迁点，复制第二份的代价是
 * 「一处改了推进顺序、另一处没改」时脚本仍然全绿。
 */
async function orderPaidAndSigned(ctx, quantity, skuId = SKU_BUCKET) {
  const created = await apiOk('/mini/mall/order/create', {
    addressId: ADDRESS_ID,
    requestId: rid(),
    lines: [{ skuId, quantity }],
  }, ctx.user)
  const orderNo = created.orderNo
  await apiOk('/mini/mall/pay-sim/pay', { orderNo }, ctx.user)
  await waitForTask(orderNo)
  await apiOk('/mall/fulfillment/pick', { orderNo }, ctx.ops)
  await apiOk('/mall/fulfillment/pack', { orderNo }, ctx.ops)
  await apiOk('/mall/fulfillment/assign', { orderNo, courierId: COURIER_ID }, ctx.ops)
  await apiOk('/mini/mall/fulfillment/courier/fetch', { orderNo }, ctx.courier)
  await apiOk('/mini/mall/fulfillment/courier/arrive', { orderNo }, ctx.courier)
  await apiOk('/mini/mall/fulfillment/sign', { orderNo, signMethod: 1 }, ctx.user)
  return orderNo
}

/** 售后推进到「待质检」：申请 → 审核通过 → 确认收货。 */
async function afterSaleToInspect(ctx, orderNo, type, orderItemId, quantity) {
  const applied = await apiOk('/mini/mall/aftersale/apply', {
    requestId: rid(),
    orderNo,
    afterSaleType: type,
    applyReason: '验收场景：商品外观破损',
    lines: [{ orderItemId, quantity }],
  }, ctx.user)
  const afterSaleNo = applied.afterSaleNo
  await apiOk('/mall/aftersale/audit', { afterSaleNo, approved: true, remark: '同意' }, ctx.ops)
  await apiOk('/mall/aftersale/receive', { afterSaleNo }, ctx.ops)
  return afterSaleNo
}

async function main() {
  pool = await mysql.createPool({ ...DB, waitForConnections: true, connectionLimit: 4 })
  const ctx = {}

  await scene('S1 登录三方身份（用户/配送员/前置仓运营）并核对验收底座', async () => {
    ctx.user = await miniLogin(USER_PHONE)
    ctx.courier = await miniLogin(COURIER_PHONE)
    ctx.other = await miniLogin(OTHER_PHONE)
    ctx.ops = await opsLogin(OPS_LOGIN)
    ctx.ops2 = await opsLogin(OPS2_LOGIN)
    const wh = await one('SELECT * FROM ws_mall_warehouse WHERE ID=?', [WAREHOUSE_ID])
    ok(wh && wh.WAREHOUSE_STATUS === 1, '验收前置仓A 应启用')
    // 验收仓必须独占收货区县码：与演示仓共用会让「按仓库ID升序取第一个」把单派到演示仓
    const rivals = await q(
      'SELECT ID FROM ws_mall_warehouse WHERE WAREHOUSE_STATUS=1 AND ID<>? AND SERVICE_SCOPE_JSON LIKE \'%420117%\'',
      [WAREHOUSE_ID],
    )
    ok(rivals.length === 0, '收货区县 420117 应只由验收仓服务', `另有 ${rivals.length} 个仓也服务该区县`)
    const opBind = await one(
      'SELECT * FROM ws_mall_warehouse_operator WHERE WAREHOUSE_ID=? AND OPERATOR_ID=9001 AND DATA_STATUS=0',
      [WAREHOUSE_ID],
    )
    ok(opBind, '运营 9001 应绑定验收仓')
    const op2Bind = await one(
      'SELECT * FROM ws_mall_warehouse_operator WHERE OPERATOR_ID=9002 AND DATA_STATUS=0',
    )
    ok(!op2Bind, 'acc-ops2 不得绑定任何前置仓（S15 越权对照的前提）')
    return '五方身份就绪；验收仓独占区县 420117，acc-ops2 无任何仓绑定'
  })

  await scene('S2 商城首页与商品详情读到真实可售 SKU（S1 基座）', async () => {
    const home = await apiOk('/mini/mall/home', {}, ctx.user)
    const product = home.products.find(p => p.productId === '9911')
    ok(product, '首页应含验收桶装水')
    ok(product.inStock === true, '验收桶装水应有货')
    ok(product.minSalePriceFen === String(UNIT_PRICE_FEN), '最低价应为服务端快照价', `实际 ${product.minSalePriceFen}`)
    const detail = await apiOk('/mini/mall/product/detail', { id: '9911' }, ctx.user)
    const sku = detail.skus.find(s => s.skuId === SKU_BUCKET)
    ok(sku, '详情应含单桶 SKU')
    ok(sku.inStock === true, '单桶 SKU 应有货')
    // 端上只该看到有货/缺货布尔，不该拿到库存件数——件数是经营信息
    ok(!('availableQty' in sku) && !('stockQty' in sku), '端上不得暴露库存件数', JSON.stringify(Object.keys(sku)))
    return `首页 ${home.products.length} 个商品；单桶 SKU 单价 ${sku.salePriceFen} 分，仅暴露有货布尔`
  })

  await scene('S3 加购 → 结算预览 → 创建订单：预占流水 5 与可售减少同时成立', async () => {
    const before = await stockOf(SKU_BUCKET)
    await apiOk('/mini/mall/cart/save', { skuId: SKU_BUCKET, quantity: 2, increment: false }, ctx.user)
    const cart = await apiOk('/mini/mall/cart/list', {}, ctx.user)
    ok(cart.lines.some(l => l.skuId === SKU_BUCKET), '购物车应含单桶')
    const preview = await apiOk('/mini/mall/checkout/preview', {
      addressId: ADDRESS_ID,
      lines: [{ skuId: SKU_BUCKET, quantity: 2 }],
    }, ctx.user)
    ok(preview.submittable === true, '预览应可提交', preview.blockReason)
    ok(preview.warehouseName === '验收前置仓A', '应选中验收仓', preview.warehouseName)
    ok(preview.orderAmountFen === String(UNIT_PRICE_FEN * 2), '金额应由服务端重算', preview.orderAmountFen)

    const created = await apiOk('/mini/mall/order/create', {
      addressId: ADDRESS_ID,
      requestId: rid(),
      lines: [{ skuId: SKU_BUCKET, quantity: 2 }],
    }, ctx.user)
    ctx.orderA = created.orderNo
    ok(created.orderStatus === 1 && created.payStatus === 1, '新单应为待支付')
    const after = await stockOf(SKU_BUCKET)
    ok(Number(before.AVAILABLE_QTY) - Number(after.AVAILABLE_QTY) === 2, '可售应减 2', `${before.AVAILABLE_QTY}→${after.AVAILABLE_QTY}`)
    ok(Number(after.RESERVED_QTY) - Number(before.RESERVED_QTY) === 2, '预占应增 2', `${before.RESERVED_QTY}→${after.RESERVED_QTY}`)
    const reserve = await flowsOf(`MALLRSV:${ctx.orderA}:`, 5)
    ok(reserve.length === 1, '预占流水应恰一条', `实际 ${reserve.length}`)
    return `订单 ${ctx.orderA} 已创建；可售 ${before.AVAILABLE_QTY}→${after.AVAILABLE_QTY}，预占 +2，流水5 一条`
  })

  await scene('S4 Pay-Sim 支付：订单转 2、支付单成功、实销流水 7、预占归零', async () => {
    const before = await stockOf(SKU_BUCKET)
    await apiOk('/mini/mall/pay-sim/pay', { orderNo: ctx.orderA }, ctx.user)
    const detail = await apiOk('/mini/mall/order/pay-status', { orderNo: ctx.orderA }, ctx.user)
    ok(detail.summary.orderStatus === 2, '订单应为已支付待履约', `status=${detail.summary.orderStatus}`)
    ok(detail.summary.payStatus === 2, '支付状态应为成功')
    const payment = await one('SELECT * FROM ws_mall_payment WHERE ORDER_NO=?', [ctx.orderA])
    ok(payment && payment.PAY_STATUS === 2, '支付单应成功')
    ok(/^\d{14}$/.test(payment.PAY_SUCCESS_TIME || ''), '支付成功时间应是 14 位业务时间', payment.PAY_SUCCESS_TIME)
    const sell = await flowsOf(`MALLSALE:${ctx.orderA}:`, 7)
    ok(sell.length === 1, '实销流水应恰一条', `实际 ${sell.length}`)
    const after = await stockOf(SKU_BUCKET)
    ok(Number(before.RESERVED_QTY) - Number(after.RESERVED_QTY) === 2, '预占应扣 2', `${before.RESERVED_QTY}→${after.RESERVED_QTY}`)
    ok(Number(before.AVAILABLE_QTY) === Number(after.AVAILABLE_QTY), '实销不动可售')
    return `订单 2/支付 2；实销流水一条；预占 ${before.RESERVED_QTY}→${after.RESERVED_QTY}，可售不变`
  })

  await scene('S5 支付重放幂等：同订单再付一次不产生第二条实销、不重复推进', async () => {
    const factsBefore = await q('SELECT COUNT(*) c FROM ws_mall_payment_fact WHERE ORDER_NO=?', [ctx.orderA])
    await apiOk('/mini/mall/pay-sim/pay', { orderNo: ctx.orderA }, ctx.user)
    const sell = await flowsOf(`MALLSALE:${ctx.orderA}:`, 7)
    ok(sell.length === 1, '重放后实销流水仍应恰一条', `实际 ${sell.length}`)
    const factsAfter = await q('SELECT COUNT(*) c FROM ws_mall_payment_fact WHERE ORDER_NO=?', [ctx.orderA])
    ok(Number(factsAfter[0].c) === Number(factsBefore[0].c), '同键事实不得增生', `${factsBefore[0].c}→${factsAfter[0].c}`)
    const payments = await q('SELECT COUNT(*) c FROM ws_mall_payment WHERE ORDER_NO=?', [ctx.orderA])
    ok(Number(payments[0].c) === 1, '支付单应恰一张')
    return `重放后：事实 ${factsAfter[0].c} 条（未增生）、实销流水 1 条、支付单 1 张`
  })

  await scene('S6 前置仓履约：拣货/打包/分配，分配轨迹落 SUBJECT_ID 结构化归属证据', async () => {
    await waitForTask(ctx.orderA)
    const born = await one(
      'SELECT * FROM ws_mall_fulfillment_trace WHERE BIZ_IDEMPOTENCY_KEY=?',
      [`MFT:${ctx.orderA}:1`],
    )
    ok(born, '派单 Worker 应已补齐任务并落下待取货节点轨迹')
    await apiOk('/mall/fulfillment/pick', { orderNo: ctx.orderA }, ctx.ops)
    await apiOk('/mall/fulfillment/pack', { orderNo: ctx.orderA }, ctx.ops)
    const candidates = await apiOk('/mall/fulfillment/courier-candidates', { orderNo: ctx.orderA }, ctx.ops)
    ok(candidates.some(c => c.courierId === COURIER_ID), '候选应含验收配送员')
    await apiOk('/mall/fulfillment/assign', { orderNo: ctx.orderA, courierId: COURIER_ID }, ctx.ops)
    const task = await one('SELECT * FROM ws_mall_fulfillment WHERE ORDER_NO=?', [ctx.orderA])
    ok(task.FULFILL_STATUS === 4, '任务应为待取货', `status=${task.FULFILL_STATUS}`)
    const assignTrace = await one(
      'SELECT * FROM ws_mall_fulfillment_trace WHERE BIZ_IDEMPOTENCY_KEY=?',
      [`MFT:${ctx.orderA}:4`],
    )
    ok(assignTrace, '分配节点轨迹应存在')
    ok(String(assignTrace.SUBJECT_ID) === COURIER_ID, 'SUBJECT_ID 应记录被分配的配送员', `实际 ${assignTrace.SUBJECT_ID}`)
    ok(String(assignTrace.ACTOR_ID) === '9001', 'ACTOR_ID 应记录分配人（仓库操作员）', `实际 ${assignTrace.ACTOR_ID}`)
    // 订单状态在拣货时同事务推进，任务已开始而订单还停在待履约就是两边各说各话
    const order = await one('SELECT ORDER_STATUS FROM ws_mall_order WHERE ORDER_NO=?', [ctx.orderA])
    ok(order.ORDER_STATUS === 3, '订单应转履约中', `status=${order.ORDER_STATUS}`)
    return `任务 4 待取货；分配轨迹 SUBJECT_ID=${assignTrace.SUBJECT_ID}、ACTOR_ID=${assignTrace.ACTOR_ID}；订单 3`
  })

  await scene('S7 配送归属：非指派配送员读不到也推不动，指派人可取货送达', async () => {
    // 用户身份不是配送员，配送端出口必须拒绝——归属恒取会话，不看入参
    await apiFail('/mini/mall/fulfillment/courier/detail', { orderNo: ctx.orderA }, ctx.other)
    await apiFail('/mini/mall/fulfillment/courier/fetch', { orderNo: ctx.orderA }, ctx.other)
    const mine = await apiOk('/mini/mall/fulfillment/courier/detail', { orderNo: ctx.orderA }, ctx.courier)
    ok(mine.orderNo === ctx.orderA, '指派配送员应读到本单')
    await apiOk('/mini/mall/fulfillment/courier/fetch', { orderNo: ctx.orderA }, ctx.courier)
    await apiOk('/mini/mall/fulfillment/courier/arrive', { orderNo: ctx.orderA }, ctx.courier)
    const task = await one('SELECT FULFILL_STATUS FROM ws_mall_fulfillment WHERE ORDER_NO=?', [ctx.orderA])
    ok(task.FULFILL_STATUS === 6, '任务应为已送达待签收', `status=${task.FULFILL_STATUS}`)
    return '非指派方读取与推进均被拒；指派方取货、送达成功，任务 6'
  })

  await scene('S8 用户签收：订单转 4，七节点轨迹与七条可靠审计逐节点唯一，时间同源', async () => {
    await apiOk('/mini/mall/fulfillment/sign', { orderNo: ctx.orderA, signMethod: 1 }, ctx.user)
    const task = await one('SELECT * FROM ws_mall_fulfillment WHERE ORDER_NO=?', [ctx.orderA])
    ok(task.FULFILL_STATUS === 7, '任务应为已签收')
    const order = await one('SELECT * FROM ws_mall_order WHERE ORDER_NO=?', [ctx.orderA])
    ok(order.ORDER_STATUS === 4, '订单应为已完成', `status=${order.ORDER_STATUS}`)
    const traces = await q(
      'SELECT TRACE_NODE, TRACE_TIME, BIZ_IDEMPOTENCY_KEY FROM ws_mall_fulfillment_trace WHERE ORDER_NO=? ORDER BY TRACE_NODE',
      [ctx.orderA],
    )
    ok(traces.length === 7, '应恰七条轨迹', `实际 ${traces.length}`)
    ok(new Set(traces.map(t => t.BIZ_IDEMPOTENCY_KEY)).size === 7, '七条轨迹幂等键应互不相同')
    const audits = await q(
      'SELECT BIZ_IDEMPOTENCY_KEY FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY LIKE ?',
      [`MALL_FULFILL:${ctx.orderA}:%`],
    )
    ok(audits.length === 7, '应恰七条可靠审计', `实际 ${audits.length}`)
    ok(new Set(audits.map(a => a.BIZ_IDEMPOTENCY_KEY)).size === 7, '七条审计幂等键应互不相同')
    const signTrace = traces.find(t => t.TRACE_NODE === 7)
    ok(signTrace.TRACE_TIME === task.SIGN_TIME, '签收轨迹时间应与任务签收时间同源', `${signTrace.TRACE_TIME} vs ${task.SIGN_TIME}`)
    ok(order.UPDATE_TIME === task.SIGN_TIME, '订单在签收同事务内被更新，时间应同源', `${order.UPDATE_TIME} vs ${task.SIGN_TIME}`)
    // 履约全程不得动库存：实销在支付时已发生
    const fulfillFlows = await q(
      'SELECT COUNT(*) c FROM ws_mall_stock_flow WHERE BIZ_IDEMPOTENCY_KEY LIKE ? AND FLOW_TYPE NOT IN (5,7)',
      [`%${ctx.orderA}%`],
    )
    ok(Number(fulfillFlows[0].c) === 0, '履约阶段不得新增库存动作', `实际 ${fulfillFlows[0].c} 条`)
    return `订单 4；轨迹 7/审计 7 逐节点唯一；签收时间 ${task.SIGN_TIME} 贯穿任务、轨迹与订单；履约零库存动作`
  })

  await scene('S9 退货退款申请→审核→收货→质检可重新销售：回库流水 8 且退款单待退款', async () => {
    const item = await one(
      'SELECT i.ID, i.QUANTITY FROM ws_mall_order_item i JOIN ws_mall_order o ON o.ID=i.ORDER_ID WHERE o.ORDER_NO=?',
      [ctx.orderA],
    )
    ctx.orderAItemId = String(item.ID)
    const before = await stockOf(SKU_BUCKET)
    const afterSaleNo = await afterSaleToInspect(ctx, ctx.orderA, 1, ctx.orderAItemId, 1)
    ctx.afterSaleReturn = afterSaleNo
    const pending = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [afterSaleNo])
    ok(pending.AFTER_SALE_STATUS === 3, '应为待质检', `status=${pending.AFTER_SALE_STATUS}`)
    await apiOk('/mall/aftersale/inspect', {
      afterSaleNo,
      inspectResult: 1,
      inspectRemark: '外观完好，可重新销售',
    }, ctx.ops)
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [afterSaleNo])
    ok(row.AFTER_SALE_STATUS === 4, '应转退款处理中', `status=${row.AFTER_SALE_STATUS}`)
    // 金额只能来自原订单明细单价×批准数量，页面与审核人都没有输入口
    ok(Number(row.REFUND_AMOUNT_FEN) === UNIT_PRICE_FEN, '应退金额应为服务端按单价×数量算出', `实际 ${row.REFUND_AMOUNT_FEN}`)
    const restock = await flowsOf(`MALLRET:${afterSaleNo}:`, 8)
    ok(restock.length === 1, '回库流水应恰一条', `实际 ${restock.length}`)
    const after = await stockOf(SKU_BUCKET)
    ok(Number(after.AVAILABLE_QTY) - Number(before.AVAILABLE_QTY) === 1, '可售应加 1', `${before.AVAILABLE_QTY}→${after.AVAILABLE_QTY}`)
    ok(Number(after.RESERVED_QTY) === Number(before.RESERVED_QTY), '回库不动预占')
    const refund = await one('SELECT * FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [row.ID])
    ok(refund && refund.REFUND_STATUS === 1, '应生成待退款的退款单')
    ok(Number(refund.REFUND_AMOUNT_FEN) === UNIT_PRICE_FEN, '退款单金额应与售后单一致')
    return `售后 ${afterSaleNo} 转 4；回库一条、可售 +1、预占不变；退款单 ${refund.REFUND_NO} 待退款 ${refund.REFUND_AMOUNT_FEN} 分`
  })

  await scene('S10 Refund-Sim 落事实 → 事务B 推进：退款单成功、售后完成、部分退款不转全额', async () => {
    await apiOk('/mall/aftersale/refund-sim', { afterSaleNo: ctx.afterSaleReturn }, ctx.ops)
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [ctx.afterSaleReturn])
    ok(row.AFTER_SALE_STATUS === 6, '售后应完成', `status=${row.AFTER_SALE_STATUS}`)
    const refund = await one('SELECT * FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [row.ID])
    ok(refund.REFUND_STATUS === 2, '退款单应成功', `status=${refund.REFUND_STATUS}`)
    ok(/^\d{14}$/.test(refund.REFUND_SUCCESS_TIME || ''), '退款成功时间应是 14 位业务时间', refund.REFUND_SUCCESS_TIME)
    ok(refund.REFUND_TRANSACTION_ID, '退款单应记录渠道交易号')
    const fact = await one('SELECT * FROM ws_mall_refund_fact WHERE REFUND_NO=?', [refund.REFUND_NO])
    ok(fact && fact.PROCESSING_STATUS === 3, '退款事实应为已处理', `status=${fact && fact.PROCESSING_STATUS}`)
    // 只退了 1 件（1800 分），商品实付 3600 分——订单不得被判成全额退款
    const order = await one('SELECT ORDER_STATUS, PRODUCT_AMOUNT_FEN FROM ws_mall_order WHERE ORDER_NO=?', [ctx.orderA])
    ok(order.ORDER_STATUS === 4, '部分退款不得把订单改成已全额退款', `status=${order.ORDER_STATUS}`)
    const trace = await one('SELECT * FROM ws_mall_after_sale_trace WHERE BIZ_IDEMPOTENCY_KEY=?', [`MAT:${ctx.afterSaleReturn}:6`])
    ok(trace, '完成节点轨迹应存在')
    return `退款 ${refund.REFUND_NO} 成功 ${refund.REFUND_AMOUNT_FEN} 分；售后 6；订单实付 ${order.PRODUCT_AMOUNT_FEN} 分仍为 4（未误判全额退）`
  })

  await scene('S11 退款事实重放：同 providerEventKey 再发一次不产生第二笔退款', async () => {
    const before = await q('SELECT COUNT(*) c FROM ws_mall_refund_fact')
    const refundsBefore = await q('SELECT COUNT(*) c FROM ws_mall_refund')
    await apiOk('/mall/aftersale/refund-sim', { afterSaleNo: ctx.afterSaleReturn }, ctx.ops)
    const after = await q('SELECT COUNT(*) c FROM ws_mall_refund_fact')
    const refundsAfter = await q('SELECT COUNT(*) c FROM ws_mall_refund')
    ok(Number(after[0].c) === Number(before[0].c), '同键事实不得增生', `${before[0].c}→${after[0].c}`)
    ok(Number(refundsAfter[0].c) === Number(refundsBefore[0].c), '退款单不得增生', `${refundsBefore[0].c}→${refundsAfter[0].c}`)
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [ctx.afterSaleReturn])
    const refund = await one('SELECT * FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [row.ID])
    ok(Number(refund.REFUND_AMOUNT_FEN) === UNIT_PRICE_FEN, '重放不得改变退款金额')
    const traces = await q('SELECT COUNT(*) c FROM ws_mall_after_sale_trace WHERE AFTER_SALE_NO=? AND TRACE_NODE=6', [ctx.afterSaleReturn])
    ok(Number(traces[0].c) === 1, '完成节点轨迹应仍恰一条', `实际 ${traces[0].c}`)
    return `重放后事实 ${after[0].c} 条、退款单 ${refundsAfter[0].c} 张、完成轨迹 1 条，金额未变`
  })

  await scene('S12 换货：质检通过生成零价补发单，来源售后单唯一，换货预占流水 9', async () => {
    const orderNo = await orderPaidAndSigned(ctx, 2)
    ctx.orderB = orderNo
    const item = await one(
      'SELECT i.ID FROM ws_mall_order_item i JOIN ws_mall_order o ON o.ID=i.ORDER_ID WHERE o.ORDER_NO=?',
      [orderNo],
    )
    const before = await stockOf(SKU_BUCKET)
    const afterSaleNo = await afterSaleToInspect(ctx, orderNo, 2, String(item.ID), 1)
    ctx.afterSaleExchange = afterSaleNo
    await apiOk('/mall/aftersale/inspect', {
      afterSaleNo,
      inspectResult: 1,
      inspectRemark: '可换新',
    }, ctx.ops)
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [afterSaleNo])
    ok(row.AFTER_SALE_STATUS === 5, '应转换货补发中', `status=${row.AFTER_SALE_STATUS}`)
    const reship = await one('SELECT * FROM ws_mall_order WHERE SOURCE_AFTER_SALE_ID=?', [row.ID])
    ok(reship, '应生成补发单')
    ctx.reshipNo = reship.ORDER_NO
    ok(Number(reship.ORDER_AMOUNT_FEN) === 0, '补发单应零价', `实际 ${reship.ORDER_AMOUNT_FEN}`)
    ok(reship.ORDER_STATUS === 2, '补发单应直接进入已支付待履约', `status=${reship.ORDER_STATUS}`)
    const reserve = await flowsOf(`MALLEXR:${afterSaleNo}:`, 9)
    ok(reserve.length === 1, '换货预占流水应恰一条', `实际 ${reserve.length}`)
    const restock = await flowsOf(`MALLRET:${afterSaleNo}:`, 8)
    ok(restock.length === 1, '可重新销售的退回件应回库一条', `实际 ${restock.length}`)
    const after = await stockOf(SKU_BUCKET)
    // 回库 +1 与换货预占 -1 同事务发生，可售净变化为 0；两笔流水必须各自可见，
    // 只看净额会把「两笔都没发生」误判成正常
    ok(Number(after.AVAILABLE_QTY) === Number(before.AVAILABLE_QTY), '回库与换货预占抵消后可售应不变', `${before.AVAILABLE_QTY}→${after.AVAILABLE_QTY}`)
    ok(Number(after.RESERVED_QTY) - Number(before.RESERVED_QTY) === 1, '换货预占应增预占 1', `${before.RESERVED_QTY}→${after.RESERVED_QTY}`)
    // 补发单不得再申请售后：否则一件货可以无限换下去
    const msg = await apiFail('/mini/mall/aftersale/apply', {
      requestId: rid(),
      orderNo: reship.ORDER_NO,
      afterSaleType: 1,
      applyReason: '再申请一次',
      lines: [{ orderItemId: '1', quantity: 1 }],
    }, ctx.user)
    ok(/补发/.test(msg || ''), '拒绝理由应指向补发单', msg)
    return `补发单 ${reship.ORDER_NO} 零价/状态2；换货预占一条、可售 -1 预占 +1；补发单再申请售后被拒`
  })

  await scene('S13 补发单履约签收：换货出库流水 10、售后完成，用户零支付', async () => {
    const before = await stockOf(SKU_BUCKET)
    await apiOk('/mall/fulfillment/pick', { orderNo: ctx.reshipNo }, ctx.ops)
    await apiOk('/mall/fulfillment/pack', { orderNo: ctx.reshipNo }, ctx.ops)
    await apiOk('/mall/fulfillment/assign', { orderNo: ctx.reshipNo, courierId: COURIER_ID }, ctx.ops)
    // 配送端必须能看出这是补发单：标记来自订单的来源售后单，不按金额为零猜
    const courierView = await apiOk('/mini/mall/fulfillment/courier/detail', { orderNo: ctx.reshipNo }, ctx.courier)
    ok(courierView.exchangeReshipment === true, '配送端应标记为换货补发单')
    await apiOk('/mini/mall/fulfillment/courier/fetch', { orderNo: ctx.reshipNo }, ctx.courier)
    await apiOk('/mini/mall/fulfillment/courier/arrive', { orderNo: ctx.reshipNo }, ctx.courier)
    await apiOk('/mini/mall/fulfillment/sign', { orderNo: ctx.reshipNo, signMethod: 1 }, ctx.user)

    const out = await flowsOf(`MALLEXO:${ctx.afterSaleExchange}:`, 10)
    ok(out.length === 1, '换货出库流水应恰一条', `实际 ${out.length}`)
    const after = await stockOf(SKU_BUCKET)
    ok(Number(before.RESERVED_QTY) - Number(after.RESERVED_QTY) === 1, '出库应扣预占 1', `${before.RESERVED_QTY}→${after.RESERVED_QTY}`)
    ok(Number(before.AVAILABLE_QTY) === Number(after.AVAILABLE_QTY), '换货出库不动可售')
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [ctx.afterSaleExchange])
    ok(row.AFTER_SALE_STATUS === 6, '售后应完成', `status=${row.AFTER_SALE_STATUS}`)
    // 换货不退钱：这张售后单不该有任何退款单
    const refunds = await q('SELECT COUNT(*) c FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [row.ID])
    ok(Number(refunds[0].c) === 0, '换货售后不得产生退款单', `实际 ${refunds[0].c} 张`)
    const payments = await q('SELECT COUNT(*) c FROM ws_mall_payment WHERE ORDER_NO=?', [ctx.reshipNo])
    ok(Number(payments[0].c) === 0, '补发单不得向用户产生支付单', `实际 ${payments[0].c} 张`)
    return `补发单已签收；换货出库一条、预占 -1 可售不变；售后 6；补发单零支付单、零退款单`
  })

  await scene('S14 未拣货整单取消退款：审核通过即回库并退款，订单转 6 已全额退款', async () => {
    const created = await apiOk('/mini/mall/order/create', {
      addressId: ADDRESS_ID,
      requestId: rid(),
      lines: [{ skuId: SKU_FILTER, quantity: 3 }],
    }, ctx.user)
    const orderNo = created.orderNo
    ctx.orderC = orderNo
    await apiOk('/mini/mall/pay-sim/pay', { orderNo }, ctx.user)
    const before = await stockOf(SKU_FILTER)
    const applied = await apiOk('/mini/mall/aftersale/apply', {
      requestId: rid(),
      orderNo,
      afterSaleType: 3,
      applyReason: '临时不需要了',
    }, ctx.user)
    const afterSaleNo = applied.afterSaleNo
    ctx.afterSaleCancel = afterSaleNo
    await apiOk('/mall/aftersale/audit', { afterSaleNo, approved: true, remark: '未拣货，同意取消' }, ctx.ops)
    const restock = await flowsOf(`MALLRET:${afterSaleNo}:`, 8)
    ok(restock.length === 1, '审核通过即应回库一条', `实际 ${restock.length}`)
    const mid = await stockOf(SKU_FILTER)
    ok(Number(mid.AVAILABLE_QTY) - Number(before.AVAILABLE_QTY) === 3, '整单数量应全部回库', `${before.AVAILABLE_QTY}→${mid.AVAILABLE_QTY}`)
    await apiOk('/mall/aftersale/refund-sim', { afterSaleNo }, ctx.ops)
    const row = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [afterSaleNo])
    ok(row.AFTER_SALE_STATUS === 6, '售后应完成')
    const order = await one('SELECT * FROM ws_mall_order WHERE ORDER_NO=?', [orderNo])
    ok(order.ORDER_STATUS === 6, '整单退完应转已全额退款', `status=${order.ORDER_STATUS}`)
    ok(Number(row.REFUND_AMOUNT_FEN) === Number(order.PRODUCT_AMOUNT_FEN), '退款额应等于商品实付', `${row.REFUND_AMOUNT_FEN} vs ${order.PRODUCT_AMOUNT_FEN}`)
    return `整单取消：回库 3 件、退款 ${row.REFUND_AMOUNT_FEN} 分、订单转 6`
  })

  await scene('S15 越权与开关：他人售后按不存在、非本仓运营不可执行、金额无输入口', async () => {
    // 他人售后：按不存在处理，不泄露「存在但不是你的」
    const msg = await apiFail('/mini/mall/aftersale/detail', { afterSaleNo: ctx.afterSaleReturn }, ctx.other)
    ok(!/不属于|无权/.test(msg || ''), '拒绝理由不得暴露归属关系', msg)
    const list = await apiOk('/mini/mall/aftersale/list', {}, ctx.other)
    ok(Array.isArray(list) && list.length === 0, '他人售后列表应为空', `实际 ${list && list.length} 条`)

    // 非本仓运营：读、审核、收货、质检、模拟退款全线拒绝
    await apiFail('/mall/aftersale/detail', { afterSaleNo: ctx.afterSaleReturn }, ctx.ops2)
    await apiFail('/mall/aftersale/audit', { afterSaleNo: ctx.afterSaleReturn, approved: true, remark: '越权' }, ctx.ops2)
    await apiFail('/mall/aftersale/receive', { afterSaleNo: ctx.afterSaleReturn }, ctx.ops2)
    await apiFail('/mall/aftersale/inspect', { afterSaleNo: ctx.afterSaleReturn, inspectResult: 1, inspectRemark: '越权' }, ctx.ops2)
    // 履约端点同样按仓归属：非本仓不得拣货
    await apiFail('/mall/fulfillment/pick', { orderNo: ctx.orderA }, ctx.ops2)

    // refund-sim 的越权必须看资金事实而不是返回码：这个出口一度先把钱退掉、
    // 再因为读详情失败而返回错误，只断言「返回非 0」会给出一片虚假的绿。
    // 独立备一张已签收订单：orderB 的 2 件里已有 1 件被 S12 换货占用，
    // 在它上面再开两笔售后会撞上数量上限，把「越权被拒」和「额度用尽」混成同一个红。
    const authzOrder = await orderPaidAndSigned(ctx, 1)
    const authzItem = await one(
      'SELECT i.ID FROM ws_mall_order_item i JOIN ws_mall_order o ON o.ID=i.ORDER_ID WHERE o.ORDER_NO=?',
      [authzOrder],
    )
    const factsBefore = await q('SELECT COUNT(*) c FROM ws_mall_refund_fact')
    const pendingNo = await afterSaleToInspect(ctx, authzOrder, 1, String(authzItem.ID), 1)
    await apiOk('/mall/aftersale/inspect', {
      afterSaleNo: pendingNo,
      inspectResult: 1,
      inspectRemark: '可重新销售',
    }, ctx.ops)
    await apiFail('/mall/aftersale/refund-sim', { afterSaleNo: pendingNo }, ctx.ops2)
    const factsAfter = await q('SELECT COUNT(*) c FROM ws_mall_refund_fact')
    ok(Number(factsAfter[0].c) === Number(factsBefore[0].c), '越权被拒不得留下任何退款事实', `${factsBefore[0].c}→${factsAfter[0].c}`)
    const pendingRow = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [pendingNo])
    const pendingRefund = await one('SELECT * FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [pendingRow.ID])
    ok(pendingRefund.REFUND_STATUS === 1, '越权被拒后退款单必须仍是待退款', `status=${pendingRefund.REFUND_STATUS}`)
    // 本仓运营做同一件事必须成功：证明上面的拒绝来自归属判据，而不是这笔单本身不可退
    await apiOk('/mall/aftersale/refund-sim', { afterSaleNo: pendingNo }, ctx.ops)

    // 前端塞金额不改变结果：应退金额恒由服务端按原订单明细算
    const item = await one(
      'SELECT i.ID FROM ws_mall_order_item i JOIN ws_mall_order o ON o.ID=i.ORDER_ID WHERE o.ORDER_NO=?',
      [ctx.orderB],
    )
    const injected = await apiOk('/mini/mall/aftersale/apply', {
      requestId: rid(),
      orderNo: ctx.orderB,
      afterSaleType: 1,
      applyReason: '尝试自带金额',
      refundAmountFen: 999999,
      amountFen: 999999,
      lines: [{ orderItemId: String(item.ID), quantity: 1 }],
    }, ctx.user)
    const injectedRow = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [injected.afterSaleNo])
    // 应退金额恒由服务端按原订单不可变明细算（单价 × 申请数量），注入值一律不参与
    ok(Number(injectedRow.REFUND_AMOUNT_FEN) === UNIT_PRICE_FEN, '应退金额应等于服务端按单价×数量算出的值', `实际 ${injectedRow.REFUND_AMOUNT_FEN}`)
    await apiOk('/mall/aftersale/audit', { afterSaleNo: injected.afterSaleNo, approved: false, remark: '验收对照单，驳回' }, ctx.ops)
    const rejected = await one('SELECT * FROM ws_mall_after_sale WHERE AFTER_SALE_NO=?', [injected.afterSaleNo])
    ok(rejected.AFTER_SALE_STATUS === 7, '驳回应为终态 7', `status=${rejected.AFTER_SALE_STATUS}`)
    const rejectedRefunds = await q('SELECT COUNT(*) c FROM ws_mall_refund WHERE AFTER_SALE_ID=?', [rejected.ID])
    ok(Number(rejectedRefunds[0].c) === 0, '驳回的售后不得生成退款单', `实际 ${rejectedRefunds[0].c} 张`)
    return '他人售后不可见且不泄露归属；非本仓运营全线被拒且零退款事实、本仓同操作成功；注入金额被忽略，驳回不生成退款单'
  })

  await scene('S16 证据总账：幂等键唯一、金额守恒、三端同一 mallOrderNo', async () => {
    const dupFlow = await q(
      'SELECT BIZ_IDEMPOTENCY_KEY k, COUNT(*) c FROM ws_mall_stock_flow GROUP BY BIZ_IDEMPOTENCY_KEY HAVING c > 1',
    )
    ok(dupFlow.length === 0, '库存流水幂等键应全局唯一', `重复 ${dupFlow.length} 个`)
    const dupTrace = await q(
      'SELECT BIZ_IDEMPOTENCY_KEY k, COUNT(*) c FROM ws_mall_after_sale_trace GROUP BY BIZ_IDEMPOTENCY_KEY HAVING c > 1',
    )
    ok(dupTrace.length === 0, '售后轨迹幂等键应全局唯一', `重复 ${dupTrace.length} 个`)
    const dupFTrace = await q(
      'SELECT BIZ_IDEMPOTENCY_KEY k, COUNT(*) c FROM ws_mall_fulfillment_trace GROUP BY BIZ_IDEMPOTENCY_KEY HAVING c > 1',
    )
    ok(dupFTrace.length === 0, '履约轨迹幂等键应全局唯一', `重复 ${dupFTrace.length} 个`)

    // 每张订单至多一个履约任务
    const dupTask = await q(
      'SELECT ORDER_ID, COUNT(*) c FROM ws_mall_fulfillment GROUP BY ORDER_ID HAVING c > 1',
    )
    ok(dupTask.length === 0, '一单一任务应成立', `违例 ${dupTask.length} 单`)

    // 金额守恒：每张订单的成功退款累计不得超过商品实付
    const overRefund = await q(`
      SELECT o.ORDER_NO, o.PRODUCT_AMOUNT_FEN, SUM(r.REFUND_AMOUNT_FEN) refunded
      FROM ws_mall_order o
      JOIN ws_mall_after_sale a ON a.ORDER_ID = o.ID AND a.DATA_STATUS = 0
      JOIN ws_mall_refund r ON r.AFTER_SALE_ID = a.ID AND r.DATA_STATUS = 0 AND r.REFUND_STATUS = 2
      WHERE o.DATA_STATUS = 0
      GROUP BY o.ID
      HAVING refunded > o.PRODUCT_AMOUNT_FEN`)
    ok(overRefund.length === 0, '累计成功退款不得超过商品实付', `超退 ${overRefund.length} 单`)

    // 退款事实无悬空：每条成功事实都要能连回退款单
    const orphanFact = await q(`
      SELECT f.ID FROM ws_mall_refund_fact f
      LEFT JOIN ws_mall_refund r ON r.REFUND_NO = f.REFUND_NO
      WHERE f.REFUND_STATE = 'SUCCESS' AND r.ID IS NULL`)
    ok(orphanFact.length === 0, '成功退款事实不得悬空', `悬空 ${orphanFact.length} 条`)

    // 三端同一 mallOrderNo：用户订单详情、PC 履约详情、配送端任务详情三处订单号一致
    const userView = await apiOk('/mini/mall/order/detail', { orderNo: ctx.orderA }, ctx.user)
    const manageView = await apiOk('/mall/fulfillment/detail', { orderNo: ctx.orderA }, ctx.ops)
    const courierView = await apiOk('/mini/mall/fulfillment/courier/detail', { orderNo: ctx.orderA }, ctx.courier)
    ok(userView.summary.orderNo === ctx.orderA
      && manageView.orderNo === ctx.orderA
      && courierView.orderNo === ctx.orderA, '三端应共用同一 mallOrderNo')

    // 消息与审计落在商城域（6），不得塞进系统域含糊了事
    const msgs = await q(
      'SELECT COUNT(*) c FROM ws_message WHERE MSG_DOMAIN = 6 AND OBJECT_ID IN (?, ?)',
      [ctx.orderA, ctx.afterSaleReturn],
    )
    ok(Number(msgs[0].c) > 0, '商城消息应落在商城域', `实际 ${msgs[0].c} 条`)
    return `幂等键三表零重复；一单一任务成立；零超退、零悬空事实；三端同号；商城域消息 ${msgs[0].c} 条`
  })

  const pass = results.filter(r => r.pass).length
  const allPassed = pass === results.length && results.length === EXPECTED_SCENARIOS

  const outDir = path.resolve(__dirname, '../../docs/acceptance/mall-chain/runs')
  fs.mkdirSync(outDir, { recursive: true })
  const batch = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
  const report = {
    chain: 'E2E-09',
    batch,
    expectedScenarios: EXPECTED_SCENARIOS,
    executedScenarios: results.length,
    passed: pass,
    pass: allPassed,
    scenarios: results.map(r => ({ name: r.name, pass: r.pass, evidence: r.evidence })),
  }
  const serialized = JSON.stringify(report, null, 2)
  const forbidden = [
    { name: '手机号原文', re: /1[3-9]\d{9}/ },
    { name: '本机绝对路径', re: /\/(Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
    { name: '支付/退款原始报文字段', re: /rawBody|RAW_BODY/ },
  ]
  const runtimeSecrets = [
    { name: '用户 Token', value: ctx.user && ctx.user.tokenValue },
    { name: '配送员 Token', value: ctx.courier && ctx.courier.tokenValue },
    { name: '运营 Token', value: ctx.ops && ctx.ops.tokenValue },
    { name: '运营乙 Token', value: ctx.ops2 && ctx.ops2.tokenValue },
    { name: '运营登录凭据', value: OPS_PWD },
    { name: '数据库口令', value: DB.password },
  ].filter(item => typeof item.value === 'string' && item.value.length > 0)
  const leaked = [
    ...forbidden.filter(f => f.re.test(serialized)),
    ...runtimeSecrets.filter(secret => serialized.includes(secret.value)),
  ]
  if (leaked.length) {
    console.error(`验收产物含禁止信息，拒绝落盘：${leaked.map(f => f.name).join('、')}`)
    await pool.end()
    process.exit(3)
  }
  fs.writeFileSync(path.join(outDir, `${batch}-result.json`), serialized)
  await pool.end()

  if (results.length !== EXPECTED_SCENARIOS) {
    console.error(`\n场景数不符：期望 ${EXPECTED_SCENARIOS}，实际执行 ${results.length} —— 判 FAIL（禁止 0/0 PASS）`)
  }
  console.log(`\n=== E2E-09 商城全链验收：${pass}/${results.length} PASS（期望场景数 ${EXPECTED_SCENARIOS}）===`)
  console.log(`结果已写入 docs/acceptance/mall-chain/runs/${batch}-result.json`)
  process.exit(allPassed ? 0 : 1)
}

main().catch(async (e) => {
  console.error('验收中断：', e.message)
  if (pool) {
    await pool.end()
  }
  process.exit(1)
})
