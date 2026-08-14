/**
 * E2E-09 L1 多渠道物流履约 —— 固定场景验收脚本（真实 HTTP + 隔离验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已按 init + migrations + acc-seed.sql 重建；
 * - 验收后端跑在 13340，且 mall.pay-sim.enabled 与 mall.logistics-sim.enabled 均为 true。
 *
 * 安全闸（fail-closed，全部在**连接数据库或创建任何业务数据之前**完成）：
 * - 必须 MALL_ACC_MODE=full；
 * - 必须 MALL_ALLOW_SIM_LOGISTICS=I_ACCEPT_ISOLATED_LOGISTICS_SIM
 *   —— 模拟物流事件会推进真实订单状态，要求一个不可能手滑打出的显式串；
 * - 后端端口为 13330/8081（主环境）或 DB 端口为 3306/3308（主库）时拒绝执行。
 *
 * 场景构成（L1~L10，只覆盖**多渠道物流**这一层，不重复 run-mall-chain 已验收的自营链）：
 * - L1     渠道未定时两条链都可走；
 * - L2~L3  渠道互斥双向（自营占位后拒第三方、第三方占位后拒自营），且拒绝路径零副作用；
 * - L4     出站动作由 Worker 外呼取号，运单号写回包裹；重放不产生第二张运单；
 * - L5     承运方事件按序推进包裹与履约（揽收→运输中→送达）；
 * - L6     乱序与迟到事件不把状态推回，事实仍留证；
 * - L7     同一承运方事件键重复投递只落一条事实；
 * - L8     验签失败一律拒绝且不落库；
 * - L9     承运方「已签收」只推到 6，7 恒由用户在小程序确认；
 * - L10    第三方任务对配送端完全不可见（列表与详情双向）。
 *
 * 本 runner 不覆盖：真实第三方物流厂商对接、运费与时效计费、跨仓调拨、逆向物流取件。
 * 这些在冻结边界外，不能把 10/10 表述成「多渠道物流已全部打通」。
 *
 * 结果纪律：
 * - 场景数量固定（见 EXPECTED_SCENARIOS），实际执行数不等于它即判 FAIL —— 禁止 0/0 PASS；
 * - 只输出结构化 result.json，不生成任何 Markdown；
 * - result.json 内**不写** Token、手机号原文、数据库口令、承运方原始报文与本机绝对路径。
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
if (String(process.env.MALL_ALLOW_SIM_LOGISTICS || '').trim() !== 'I_ACCEPT_ISOLATED_LOGISTICS_SIM') {
  console.error('安全闸拒绝执行：必须显式设置 MALL_ALLOW_SIM_LOGISTICS=I_ACCEPT_ISOLATED_LOGISTICS_SIM')
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
const EXPECTED_SCENARIOS = 10

const USER_PHONE = '13999990001'
const COURIER_PHONE = '13999990003'
const OPS_LOGIN = 'acc-ops'
const OPS_PWD = process.env.ACC_OPS_PWD_CIPHER
/** 与后端 mall.logistics-sim.secret 缺省值同源；仅用于隔离环境自校验。 */
const SIM_SECRET = process.env.ACC_LOGISTICS_SIM_SECRET || 'dakang-logistics-sim'

// 验收种子固定 ID（acc-seed.sql 的 99xx 段）
const ADDRESS_ID = '9971'
const SKU_BUCKET = '9921'
const COURIER_ID = '9601'
const PROVIDER = 'SIM'

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

/** 与后端 MallLogisticsSimController.sign 逐字同序：字段顺序一变签名就对不上。 */
function sign(evt) {
  const raw = [evt.providerCode, evt.providerEventKey, evt.waybillNo, evt.eventState, evt.eventTime, SIM_SECRET].join(':')
  return crypto.createHash('sha256').update(raw).digest('hex')
}
function simEvent(evt, token) {
  return api('/mall/logistics/sim-event', { ...evt, signature: sign(evt) }, token)
}
async function simEventOk(evt, token) {
  const r = await simEvent(evt, token)
  ok(r && r.code === 0, '模拟物流事件应被受理', `code=${r && r.code} msg=${r && r.msg}`)
  return r.data
}

function shipmentOf(orderNo) {
  return one('SELECT * FROM ws_mall_shipment WHERE ORDER_NO=? AND DIRECTION=1', [orderNo])
}
function fulfillOf(orderNo) {
  return one('SELECT * FROM ws_mall_fulfillment WHERE ORDER_NO=?', [orderNo])
}
async function orderStatusOf(orderNo) {
  const row = await one('SELECT ORDER_STATUS FROM ws_mall_order WHERE ORDER_NO=?', [orderNo])
  return row && row.ORDER_STATUS
}

/**
 * 等履约任务被派单 Worker 幂等补齐。
 *
 * 任务刻意不挂在支付事务里，所以支付成功后任务并不立刻存在；这里轮询数据库而不是
 * 自己造一条——造任务的动作只有 Worker 一个入口，脚本自己建就等于绕开了要验的东西。
 */
async function waitForTask(orderNo, timeoutMs = 150000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    if (await fulfillOf(orderNo)) {
      return
    }
    if (Date.now() > deadline) {
      throw new Fail(`履约任务在 ${timeoutMs}ms 内未被派单 Worker 补齐：${orderNo}`)
    }
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
}

/**
 * 等 outbox Worker 把运单号写回包裹。
 *
 * 外呼在事务外，所以创建运单请求返回时运单号必然还没有；轮询的是**包裹上的运单号**
 * 而不是 outbox 的处理状态——运单号写回才是这条链真正完成的那一步。
 */
async function waitForWaybill(orderNo, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const ship = await shipmentOf(orderNo)
    if (ship && ship.WAYBILL_NO) {
      return ship.WAYBILL_NO
    }
    if (Date.now() > deadline) {
      throw new Fail(`运单号在 ${timeoutMs}ms 内未被出站 Worker 写回：${orderNo}`)
    }
    await new Promise(resolve => setTimeout(resolve, 3000))
  }
}

/** 下单 → 支付 → 拣货 → 打包，停在「待安排发运」（渠道仍未定）。 */
async function orderReadyToArrange(ctx, quantity = 1) {
  const created = await apiOk('/mini/mall/order/create', {
    addressId: ADDRESS_ID,
    requestId: rid(),
    lines: [{ skuId: SKU_BUCKET, quantity }],
  }, ctx.user)
  const orderNo = created.orderNo
  await apiOk('/mini/mall/pay-sim/pay', { orderNo }, ctx.user)
  await waitForTask(orderNo)
  await apiOk('/mall/fulfillment/pick', { orderNo }, ctx.ops)
  await apiOk('/mall/fulfillment/pack', { orderNo }, ctx.ops)
  return orderNo
}

/** 走到「第三方运单已取号」。 */
async function orderWithWaybill(ctx) {
  const orderNo = await orderReadyToArrange(ctx)
  await apiOk('/mall/fulfillment/shipment/create', { orderNo, providerCode: PROVIDER }, ctx.ops)
  const waybill = await waitForWaybill(orderNo)
  return { orderNo, waybill }
}

/** 业务时间戳：14 位，取自验收机当前时间——后端会按规范时间校验。 */
function bizTime(offsetMinutes = 0) {
  const d = new Date(Date.now() + offsetMinutes * 60000)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
    + `${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

async function main() {
  pool = mysql.createPool({ ...DB, connectionLimit: 4, timezone: 'Z' })
  const ctx = {}
  ctx.user = await miniLogin(USER_PHONE)
  ctx.courier = await miniLogin(COURIER_PHONE)
  ctx.ops = await opsLogin(OPS_LOGIN)

  await scene('L1 渠道未定时两条链都可选，承运商列表取自已装配适配器', async () => {
    const providers = await apiOk('/mall/fulfillment/shipment/providers', {}, ctx.ops)
    ok(Array.isArray(providers) && providers.length > 0, '承运商列表不得为空')
    ok(providers.some(p => p.providerCode === PROVIDER), `承运商列表应含 ${PROVIDER}`, JSON.stringify(providers))
    ok(!providers.some(p => p.providerCode === 'SELF'), '自营不得出现在第三方承运商列表')

    const orderNo = await orderReadyToArrange(ctx)
    const task = await fulfillOf(orderNo)
    ok(task.FULFILL_MODE === 0, '待安排发运时渠道必须仍是未定', `实际 ${task.FULFILL_MODE}`)
    ok(task.FULFILL_STATUS === 3, '状态应为待安排发运', `实际 ${task.FULFILL_STATUS}`)
    return `承运商 ${providers.length} 家；${orderNo} 停在 3/渠道 0`
  })

  await scene('L2 自营占位后第三方被拒，且不留包裹与出站动作', async () => {
    const orderNo = await orderReadyToArrange(ctx)
    await apiOk('/mall/fulfillment/assign', { orderNo, courierId: COURIER_ID }, ctx.ops)
    const before = await one(
      'SELECT COUNT(*) c FROM ws_mall_logistics_outbox o JOIN ws_mall_shipment s ON s.ID=o.SHIPMENT_ID'
      + ' WHERE s.ORDER_NO=?',
      [orderNo],
    )

    const msg = await apiFail('/mall/fulfillment/shipment/create', { orderNo, providerCode: PROVIDER }, ctx.ops)

    const task = await fulfillOf(orderNo)
    ok(task.FULFILL_MODE === 1, '拒绝路径不得改渠道', `实际 ${task.FULFILL_MODE}`)
    const ships = await q('SELECT * FROM ws_mall_shipment WHERE ORDER_NO=?', [orderNo])
    ok(ships.length === 1 && ships[0].FULFILL_MODE === 1, '自营包裹应恰好一个且渠道为自营')
    const after = await one(
      'SELECT COUNT(*) c FROM ws_mall_logistics_outbox o JOIN ws_mall_shipment s ON s.ID=o.SHIPMENT_ID'
      + ' WHERE s.ORDER_NO=?',
      [orderNo],
    )
    ok(after.c === before.c && after.c === 0, '拒绝路径不得登记出站动作', `${before.c}→${after.c}`)
    return `拒绝口径：${msg}；渠道仍为自营，出站动作 0 条`
  })

  await scene('L3 第三方占位后自营被拒，配送员不得被写入', async () => {
    const orderNo = await orderReadyToArrange(ctx)
    await apiOk('/mall/fulfillment/shipment/create', { orderNo, providerCode: PROVIDER }, ctx.ops)

    const msg = await apiFail('/mall/fulfillment/assign', { orderNo, courierId: COURIER_ID }, ctx.ops)

    const task = await fulfillOf(orderNo)
    ok(task.FULFILL_MODE === 2, '渠道应为第三方', `实际 ${task.FULFILL_MODE}`)
    ok(task.COURIER_ID === null, '第三方单不得被写入配送员', `实际 ${task.COURIER_ID}`)
    ok(task.FULFILL_STATUS === 4, '应已推进到待承运方揽收', `实际 ${task.FULFILL_STATUS}`)
    const ships = await q('SELECT * FROM ws_mall_shipment WHERE ORDER_NO=?', [orderNo])
    ok(ships.length === 1, '拒绝路径不得建第二个包裹', `实际 ${ships.length}`)
    return `拒绝口径：${msg}；渠道锁定第三方，配送员为空`
  })

  await scene('L4 出站 Worker 取号写回包裹；重复提交不产生第二张运单', async () => {
    const orderNo = await orderReadyToArrange(ctx)
    await apiOk('/mall/fulfillment/shipment/create', { orderNo, providerCode: PROVIDER }, ctx.ops)
    const ship0 = await shipmentOf(orderNo)
    ok(!ship0.WAYBILL_NO, '外呼在事务外：创建请求返回时不该已有运单号')

    const waybill = await waitForWaybill(orderNo)
    ok(/^SIMWB[0-9A-F]{16}$/.test(waybill), '运单号应为模拟承运方的确定性派生号', waybill)
    const ship1 = await shipmentOf(orderNo)
    ok(ship1.SHIPMENT_STATUS === 2, '取号后包裹应为已受理', `实际 ${ship1.SHIPMENT_STATUS}`)

    // 重复提交：幂等键兜住，包裹与动作都不得增加
    await apiOk('/mall/fulfillment/shipment/create', { orderNo, providerCode: PROVIDER }, ctx.ops)
    const ships = await q('SELECT * FROM ws_mall_shipment WHERE ORDER_NO=?', [orderNo])
    const actions = await q(
      'SELECT o.* FROM ws_mall_logistics_outbox o JOIN ws_mall_shipment s ON s.ID=o.SHIPMENT_ID'
      + ' WHERE s.ORDER_NO=?',
      [orderNo],
    )
    ok(ships.length === 1, '重复提交不得建第二个包裹', `实际 ${ships.length}`)
    ok(actions.length === 1, '重复提交不得登记第二条出站动作', `实际 ${actions.length}`)
    ok(ships[0].WAYBILL_NO === waybill, '运单号不得被第二次覆盖')
    return `运单 ${waybill.slice(0, 9)}***；包裹 1 个、出站动作 1 条`
  })

  await scene('L5 承运方事件按序推进包裹与履约', async () => {
    const { orderNo, waybill } = await orderWithWaybill(ctx)
    await simEventOk({
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-PICK`,
      waybillNo: waybill,
      eventState: 'PICKED_UP',
      eventTime: bizTime(),
      eventDesc: '承运方已揽收',
    }, ctx.ops)
    let ship = await shipmentOf(orderNo)
    let task = await fulfillOf(orderNo)
    ok(ship.SHIPMENT_STATUS === 3, '揽收后包裹应为已揽收', `实际 ${ship.SHIPMENT_STATUS}`)
    ok(task.FULFILL_STATUS === 5, '揽收后履约应为运输中', `实际 ${task.FULFILL_STATUS}`)

    await simEventOk({
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-DELIV`,
      waybillNo: waybill,
      eventState: 'DELIVERED',
      eventTime: bizTime(1),
      eventDesc: '已送达代收点',
    }, ctx.ops)
    ship = await shipmentOf(orderNo)
    task = await fulfillOf(orderNo)
    ok(ship.SHIPMENT_STATUS === 5, '送达后包裹应为已送达', `实际 ${ship.SHIPMENT_STATUS}`)
    ok(task.FULFILL_STATUS === 6, '送达后履约应为已送达待确认', `实际 ${task.FULFILL_STATUS}`)
    return `${orderNo}：包裹 2→3→5，履约 4→5→6`
  })

  await scene('L6 乱序与迟到事件不把状态推回，事实仍留证', async () => {
    const { orderNo, waybill } = await orderWithWaybill(ctx)
    await simEventOk({
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-D`,
      waybillNo: waybill,
      eventState: 'DELIVERED',
      eventTime: bizTime(),
      eventDesc: '已送达',
    }, ctx.ops)
    const before = await shipmentOf(orderNo)

    await simEventOk({
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-LATE`,
      waybillNo: waybill,
      eventState: 'IN_TRANSIT',
      eventTime: bizTime(-30),
      eventDesc: '迟到的运输中',
    }, ctx.ops)

    const after = await shipmentOf(orderNo)
    ok(after.SHIPMENT_STATUS === before.SHIPMENT_STATUS && after.SHIPMENT_STATUS === 5, '迟到事件不得把已送达推回运输中', `${before.SHIPMENT_STATUS}→${after.SHIPMENT_STATUS}`)
    const task = await fulfillOf(orderNo)
    ok(task.FULFILL_STATUS === 6, '履约状态同样不得回退', `实际 ${task.FULFILL_STATUS}`)
    const facts = await q(
      'SELECT * FROM ws_mall_logistics_event WHERE SHIPMENT_ID=?',
      [after.ID],
    )
    ok(facts.length === 2, '迟到事件仍要留证', `实际 ${facts.length} 条`)
    return `包裹停在 5，事实 2 条（含迟到的那条）`
  })

  await scene('L7 同一承运方事件键重复投递只落一条事实，改参一律拒绝', async () => {
    const { orderNo, waybill } = await orderWithWaybill(ctx)
    const key = `${orderNo}-DUP`
    const evt = {
      providerCode: PROVIDER,
      providerEventKey: key,
      waybillNo: waybill,
      eventState: 'PICKED_UP',
      eventTime: bizTime(),
      eventDesc: '首投',
    }
    await simEventOk(evt, ctx.ops)
    await simEventOk(evt, ctx.ops)
    const ship = await shipmentOf(orderNo)
    let facts = await q('SELECT * FROM ws_mall_logistics_event WHERE SHIPMENT_ID=?', [ship.ID])
    ok(facts.length === 1, '同键重投不得落第二条事实', `实际 ${facts.length} 条`)

    // 同键换正文：静默复用旧行等于替篡改盖章
    const drifted = await simEvent({ ...evt, eventState: 'DELIVERED' }, ctx.ops)
    ok(drifted && drifted.code !== 0, '同键携带不一致正文必须被拒', `code=${drifted && drifted.code}`)
    facts = await q('SELECT * FROM ws_mall_logistics_event WHERE SHIPMENT_ID=?', [ship.ID])
    ok(facts.length === 1, '被拒的改参重投不得落库', `实际 ${facts.length} 条`)
    const fresh = await shipmentOf(orderNo)
    ok(fresh.SHIPMENT_STATUS === 3, '被拒的改参重投不得推进包裹', `实际 ${fresh.SHIPMENT_STATUS}`)
    return `同键 1 条事实；改参被拒且零副作用`
  })

  await scene('L8 验签失败一律拒绝且不落库', async () => {
    const { orderNo, waybill } = await orderWithWaybill(ctx)
    const ship = await shipmentOf(orderNo)
    const before = await q('SELECT * FROM ws_mall_logistics_event WHERE SHIPMENT_ID=?', [ship.ID])

    const r = await api('/mall/logistics/sim-event', {
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-FORGED`,
      waybillNo: waybill,
      eventState: 'DELIVERED',
      eventTime: bizTime(),
      eventDesc: '伪造报文',
      signature: 'deadbeef',
    }, ctx.ops)
    ok(r && r.code !== 0, '验签失败必须返回错误', `code=${r && r.code}`)

    const forged = await one(
      'SELECT * FROM ws_mall_logistics_event WHERE PROVIDER_EVENT_KEY=?',
      [`${orderNo}-FORGED`],
    )
    ok(!forged, '验签失败连一条事实都不得落库')
    const after = await q('SELECT * FROM ws_mall_logistics_event WHERE SHIPMENT_ID=?', [ship.ID])
    ok(after.length === before.length, '事实数不得变化', `${before.length}→${after.length}`)
    const fresh = await shipmentOf(orderNo)
    ok(fresh.SHIPMENT_STATUS === 2, '伪造报文不得推进包裹', `实际 ${fresh.SHIPMENT_STATUS}`)
    return `伪造报文被拒；事实表零新增，包裹停在 2`
  })

  await scene('L9 承运方「已签收」只推到 6，7 恒由用户确认', async () => {
    const { orderNo, waybill } = await orderWithWaybill(ctx)
    await simEventOk({
      providerCode: PROVIDER,
      providerEventKey: `${orderNo}-SIGNED`,
      waybillNo: waybill,
      eventState: 'SIGNED',
      eventTime: bizTime(),
      eventDesc: '承运方称已签收',
    }, ctx.ops)

    const task = await fulfillOf(orderNo)
    ok(task.FULFILL_STATUS === 6, '承运方签收只到 6，绝不写 7', `实际 ${task.FULFILL_STATUS}`)
    ok(!task.SIGN_TIME, '签收时间只能由用户确认时落', `实际 ${task.SIGN_TIME}`)
    ok(await orderStatusOf(orderNo) === 3, '订单不得被承运方事件推成已完成')

    await apiOk('/mini/mall/fulfillment/sign', { orderNo, signMethod: 1 }, ctx.user)
    const signed = await fulfillOf(orderNo)
    ok(signed.FULFILL_STATUS === 7, '用户确认后才是 7', `实际 ${signed.FULFILL_STATUS}`)
    ok(await orderStatusOf(orderNo) === 4, '用户确认后订单才完成')
    return `承运方签收→6/订单3；用户确认→7/订单4`
  })

  await scene('L10 第三方任务对配送端完全不可见（列表与详情双向）', async () => {
    const selfNo = await orderReadyToArrange(ctx)
    await apiOk('/mall/fulfillment/assign', { orderNo: selfNo, courierId: COURIER_ID }, ctx.ops)
    const { orderNo: thirdNo } = await orderWithWaybill(ctx)

    const list = await apiOk('/mini/mall/fulfillment/courier/list', {}, ctx.courier)
    const orderNos = (Array.isArray(list) ? list : []).map(item => item.orderNo)
    ok(orderNos.includes(selfNo), '配送端应能看到自营任务')
    ok(!orderNos.includes(thirdNo), '配送端不得看到第三方任务', JSON.stringify(orderNos))

    // 列表过滤不够：能按单号直查等于过滤形同虚设
    await apiFail('/mini/mall/fulfillment/courier/detail', { orderNo: thirdNo }, ctx.courier)
    await apiFail('/mini/mall/fulfillment/courier/fetch', { orderNo: thirdNo }, ctx.courier)
    const task = await fulfillOf(thirdNo)
    ok(task.FULFILL_STATUS === 4, '被拒的取货不得推进第三方单', `实际 ${task.FULFILL_STATUS}`)

    // 用户侧反过来必须看得到运单与承运商
    const ships = await apiOk('/mini/mall/fulfillment/shipments', { orderNo: thirdNo }, ctx.user)
    ok(Array.isArray(ships) && ships.length === 1, '用户应能看到本单包裹')
    ok(ships[0].providerCode === PROVIDER && !!ships[0].waybillNo, '用户应能看到承运商与运单号')
    return `配送端列表 ${orderNos.length} 条不含第三方；用户侧可见运单`
  })

  const pass = results.filter(r => r.pass).length
  const allPassed = pass === results.length && results.length === EXPECTED_SCENARIOS

  const outDir = path.resolve(__dirname, '../../docs/acceptance/mall-logistics/runs')
  fs.mkdirSync(outDir, { recursive: true })
  const batch = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
  const report = {
    chain: 'E2E-09-L1',
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
    { name: '承运方原始报文字段', re: /rawBody|RAW_BODY/ },
    { name: '模拟承运方密钥', re: /dakang-logistics-sim/ },
  ]
  const runtimeSecrets = [
    { name: '用户 Token', value: ctx.user && ctx.user.tokenValue },
    { name: '配送员 Token', value: ctx.courier && ctx.courier.tokenValue },
    { name: '运营 Token', value: ctx.ops && ctx.ops.tokenValue },
    { name: '运营登录凭据', value: OPS_PWD },
    { name: '数据库口令', value: DB.password },
    { name: '模拟承运方密钥', value: SIM_SECRET },
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
  console.log(`\n=== E2E-09 L1 多渠道物流验收：${pass}/${results.length} PASS（期望场景数 ${EXPECTED_SCENARIOS}）===`)
  console.log(`结果已写入 docs/acceptance/mall-logistics/runs/${batch}-result.json`)
  process.exit(allPassed ? 0 : 1)
}

main().catch(async (e) => {
  console.error('验收中断：', e.message)
  if (pool) {
    await pool.end()
  }
  process.exit(1)
})
