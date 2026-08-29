/**
 * 水卡生命周期固定场景验收（S1～S9，真实 HTTP 接口 + 验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已按 init SQL + migrations + acc-seed.sql 重建；
 * - 验收后端已开启 mini.test-login.enabled 与 mini.pay-sim.enabled，跑在 13340；
 * - tools/device-sim ACC-DEV-0001 已连验收 EMQX（S7 出水指令链依赖）。
 *
 * 安全闸（fail-closed）：
 * - 必须显式 CARD_ACC_MODE=full 才执行（与 run-d4/run-e1b 同一显式模式纪律）；
 * - 后端地址端口为 13330/8081（主环境）或 DB 端口为 3306/3308（主库）时拒绝执行；
 * - 开跑前核验验收种子哨兵（ws_user 9001/9002、ws_package 9501）与测试登录路由
 *  （/mini/test-login 仅在 mini.test-login.enabled=true 时注册——探它即证明被测实例是隔离验收环境）。
 *
 * 结果纪律：场景总数必须恰为 10（S1~S9 + S10 赠卡不可充值，禁止 0/0 PASS）；每场景输出 PASS/FAIL + DB 证据摘要；
 * 批次只落盘 docs/acceptance/card-lifecycle/runs/<批次>/result.json，人工摘要统一维护在
 * docs/acceptance/card-lifecycle/README.md，避免每轮生成重复 Markdown。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { sha256File } = require('./e1b-core')
const { newBatchId } = require('./report-core')

// ---------------------------------------------------------------------------
// 安全闸与环境
// ---------------------------------------------------------------------------
if (String(process.env.CARD_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 CARD_ACC_MODE=full（本脚本会真实写验收库）')
  process.exit(2)
}

const BASE = (process.env.ACC_BASE || 'http://127.0.0.1:13340/dakangApi').replace(/\/$/, '')
// 凭据只从环境变量取，**不设硬编码兜底**：兜底口令一旦与验收库口令重合，
// 就等于把「口令必须与主库不同」这道防触主库护栏写死在源码里，且真值会随源码公开。
// 取值方式：先 `set -a; . .env; set +a`（仓库根 .env，模板见 .env.example）再跑本脚本。
if (!String(process.env.ACC_DB_PASSWORD || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（请在仓库根 .env 配置后导出，参照 .env.example）')
  process.exit(2)
}
if (!String(process.env.ACC_OPS_PWD_CIPHER || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_OPS_PWD_CIPHER（acc-ops 登录口令密文，见 .env.example 说明）')
  process.exit(2)
}
const DB_CONFIG = {
  host: process.env.ACC_DB_HOST || '127.0.0.1',
  port: Number(process.env.ACC_DB_PORT || 3309),
  user: process.env.ACC_DB_USER || 'root',
  password: process.env.ACC_DB_PASSWORD,
  database: process.env.ACC_DB_NAME || 'dakang',
}
if (/:(?:13330|8081)(?:\/|$)/.test(BASE)) {
  console.error(`安全闸拒绝执行：ACC_BASE=${BASE} 指向主环境端口（13330/8081）`)
  process.exit(2)
}
if (DB_CONFIG.port === 3306 || DB_CONFIG.port === 3308) {
  console.error(`安全闸拒绝执行：ACC_DB_PORT=${DB_CONFIG.port} 指向主库端口`)
  process.exit(2)
}

const ROUND = String(process.env.ACC_ROUND || '').trim() || 'unlabeled'
const ACCEPT_DIR = path.resolve(__dirname, '../../docs/acceptance/card-lifecycle')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, 'runs', BATCH_ID)

// 种子固定身份（deploy/acceptance/acc-seed.sql）
const OWNER_PHONE = '13999990001'
const MEMBER_PHONE = '13999990002'
const OWNER_USER_ID = 9001
const MEMBER_USER_ID = 9002
const ACC_PACKAGE_ID = '9501'
const QR_STATION1 = 'ACC-QR-DEV1-O1'
const QR_STATION2 = 'ACC-QR-DEV2-O1'
const DEVICE1_ID = 9201
const WATER_TYPE_ID = 1
// 运营账号（acc-seed）：LOGIN_PWD 与底座 admin 同一 RSA 密文，登录时服务端两侧解密后比较明文。
// 密文随 RSA 密钥对与 admin 口令一起变，故不写死在脚本里，由 ACC_OPS_PWD_CIPHER 注入
// （上面已 fail-closed 校验；与 acc-seed.sql 里 acc-ops 的 LOGIN_PWD 必须是同一串）。
const OPS_LOGIN_NAME = 'acc-ops'
const OPS_LOGIN_PWD_CIPHER = process.env.ACC_OPS_PWD_CIPHER

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

class CheckError extends Error {}

function ok(cond, label, detail = '') {
  if (!cond) {
    throw new CheckError(`${label}${detail ? `（${detail}）` : ''}`)
  }
}

function eq(actual, expected, label) {
  // 宽松数值/字符串比对：Long 可能被序列化为字符串
  ok(String(actual) === String(expected), label, `期望 ${expected}，实际 ${actual}`)
}

/** Asia/Shanghai（固定 +8，无夏令时）业务时间工具，与服务端 yyyyMMddHHmmss 口径一致。 */
const SH_OFFSET_MS = 8 * 3600_000
function t14ToEpoch(t14) {
  ok(/^\d{14}$/.test(String(t14 || '')), '时间必须是 14 位 yyyyMMddHHmmss', String(t14))
  const s = String(t14)
  return Date.UTC(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8), +s.slice(8, 10), +s.slice(10, 12), +s.slice(12, 14)) - SH_OFFSET_MS
}
function epochToT14(ms) {
  const d = new Date(ms + SH_OFFSET_MS)
  const p = (n, l = 2) => String(n).padStart(l, '0')
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}`
}
const addMinutes14 = (t14, minutes) => epochToT14(t14ToEpoch(t14) + minutes * 60_000)

// ---------------------------------------------------------------------------
// HTTP / DB
// ---------------------------------------------------------------------------
async function api(pathName, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers[token.tokenName] = token.tokenValue
  }
  const resp = await fetch(`${BASE}${pathName}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body ?? {}),
  })
  const text = await resp.text()
  let parsed
  try {
    parsed = JSON.parse(text)
  }
  catch {
    throw new CheckError(`HTTP ${resp.status} ${pathName} 非 JSON 响应：${text.slice(0, 120)}`)
  }
  return parsed
}

async function apiOk(pathName, body, token) {
  const r = await api(pathName, body, token)
  ok(r && r.code === 0, `${pathName} 应成功`, `code=${r && r.code} msg=${r && r.msg}`)
  return r.data
}

async function apiFail(pathName, body, token) {
  const r = await api(pathName, body, token)
  ok(r && r.code !== 0, `${pathName} 应被拒绝`, `却返回 code=0`)
  return r
}

let pool
async function q(sql, params = []) {
  const [rows] = await pool.query(sql, params)
  return rows
}
async function one(sql, params = []) {
  const rows = await q(sql, params)
  ok(rows.length <= 1, 'SQL 应至多一行', sql)
  return rows[0] || null
}

const orderByNo = orderNo => one('SELECT * FROM ws_order WHERE ORDER_NO = ?', [orderNo])
const paymentByOrderId = orderId => one('SELECT * FROM ws_payment WHERE ORDER_ID = ?', [orderId])
const flowsByOrderId = orderId => q('SELECT * FROM ws_wallet_flow WHERE ORDER_ID = ? ORDER BY ID', [orderId])
const eventsByOrderNo = orderNo => q('SELECT * FROM ws_payment_event WHERE ORDER_NO = ? ORDER BY ID', [orderNo])
const cardById = cardId => one('SELECT * FROM ws_card WHERE ID = ?', [cardId])
const cardsByUser = userId => q('SELECT * FROM ws_card WHERE USER_ID = ? AND DATA_STATUS = 0', [userId])
const commandsByOrderId = orderId => q('SELECT * FROM ws_command WHERE ORDER_ID = ? ORDER BY ID', [orderId])

async function pollUntil(label, fn, timeoutMs, intervalMs = 3000) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) {
      return last
    }
    await sleep(intervalMs)
  }
  throw new CheckError(`${label}：${Math.round(timeoutMs / 1000)}s 内未达到期望状态`)
}

// ---------------------------------------------------------------------------
// 业务子流程
// ---------------------------------------------------------------------------
async function miniLogin(phone) {
  const data = await apiOk('/mini/test-login/by-phone', { phone })
  ok(data && data.tokenName && data.tokenValue, '测试登录应返回 tokenName/tokenValue')
  return { tokenName: data.tokenName, tokenValue: data.tokenValue }
}

async function opsLogin() {
  const data = await apiOk('/api/auth/loginEmployee', { loginName: OPS_LOGIN_NAME, loginPwd: OPS_LOGIN_PWD_CIPHER })
  ok(data && data.tokenValue, '运营登录应返回 tokenValue')
  // PC 端 Sa-Token 头名固定 dakang-token（application.yml sa-token.token-name）
  return { tokenName: 'dakang-token', tokenValue: data.tokenValue }
}

async function rechargeCreate(token, { cardId, requestId, packageId = ACC_PACKAGE_ID }) {
  const body = { packageId, requestId }
  if (cardId != null) {
    body.cardId = String(cardId)
  }
  const created = await apiOk('/mini/order/recharge/create', body, token)
  const order = await orderByNo(created.orderNo)
  ok(order, '充值创单后订单应立即可追溯')
  const snapshot = JSON.parse(order.PACKAGE_SNAP)
  eq(snapshot.targetCardEligibilitySnapshot?.capturedTime, order.CREATE_TIME, '订单创建时间与资格快照采集时间必须同源')
  const payment = await paymentByOrderId(order.ID)
  ok(payment, '充值创单后支付单应立即可追溯')
  eq(payment.CREATE_TIME, order.CREATE_TIME, '支付单创建时间必须与订单同源')
  return created
}

const paySim = (token, orderNo) => apiOk('/mini/pay-sim/pay', { orderNo }, token)
const paySimQuery = (token, orderNo) => apiOk('/mini/pay-sim/query', { orderNo }, token)

async function changeCardStatus(opsToken, cardId, targetStatus) {
  // @RepeatSubmit 以 url+身份+参数哈希 为键、TTL 5s（RedisExpire.SEC_FIVE_EXPIRE）：
  // 同参状态切换之间必须留出 >5s，否则命中防重复提交闸（625）
  await sleep(6000)
  const data = await apiOk('/user/card/changeStatus', { id: String(cardId), targetStatus }, opsToken)
  ok(data === true, '卡状态变更应返回 true')
}

async function scanTo(token, rawCode) {
  const scan = await apiOk('/mini/device/scan/resolve', { rawCode }, token)
  ok(scan && scan.scanSessionId, '扫码应返回 scanSessionId')
  return scan.scanSessionId
}

function eligibility(token, scanSessionId, cardId) {
  return apiOk('/mini/device/water/eligibility', { scanSessionId, cardId: Number(cardId) }, token)
}

/** 卡资金快照（余额/水量/有效期/状态 + 全卡流水条数），用于「零变化」断言。 */
async function cardSnapshot(cardId) {
  const card = await cardById(cardId)
  ok(card, '快照时卡应存在')
  const flows = await q('SELECT COUNT(*) AS n FROM ws_wallet_flow WHERE CARD_ID = ?', [cardId])
  return {
    balanceAmount: String(card.BALANCE_AMOUNT),
    balanceMl: String(card.BALANCE_ML),
    expireTime: card.EXPIRE_TIME,
    cardStatus: card.CARD_STATUS,
    flowCount: Number(flows[0].n),
  }
}

function assertSnapshotUnchanged(before, after, label) {
  eq(after.balanceAmount, before.balanceAmount, `${label}：余额零变化`)
  eq(after.balanceMl, before.balanceMl, `${label}：水量零变化`)
  eq(after.expireTime, before.expireTime, `${label}：有效期零变化`)
  eq(after.flowCount, before.flowCount, `${label}：流水零新增`)
}

/** 充值单入账完备断言（S1/S2/S3 共用）：order4/payment2/唯一流水+幂等键/事实已处理。 */
async function assertCredited(orderNo, ev) {
  const order = await orderByNo(orderNo)
  ok(order, '订单应存在')
  eq(order.ORDER_STATUS, 4, '订单应为已完成(4)')
  ok(order.FINISH_TIME, '已完成订单应有完成时间')
  const payment = await paymentByOrderId(order.ID)
  ok(payment, '支付单应恰一条')
  eq(payment.PAY_STATUS, 2, '支付单应为成功(2)')
  eq(payment.PAY_SOURCE, 2, '支付来源应为 Pay-Sim(2)')
  eq(payment.TRANSACTION_ID, `SIMTX${orderNo}`, '交易号应为 Pay-Sim 命名空间')
  const flows = await flowsByOrderId(order.ID)
  eq(flows.length, 1, '订单流水应恰一条')
  eq(flows[0].BIZ_IDEMPOTENCY_KEY, `RECHARGE:${orderNo}`, '流水幂等键')
  eq(flows[0].FLOW_TYPE, 1, '流水类型应为充值入账(1)')
  const events = await eventsByOrderNo(orderNo)
  const success = events.filter(e => e.TRADE_STATE === 'SUCCESS')
  eq(success.length, 1, 'SUCCESS 支付事实应恰一条')
  eq(success[0].PROCESSING_STATUS, 3, '成功事实应已处理(3)')
  ev.push(`order4/payment2 tx=${payment.TRANSACTION_ID} flow#${flows[0].ID} biz=RECHARGE:${orderNo} event3`)
  return { order, payment, flow: flows[0] }
}

// ---------------------------------------------------------------------------
// 场景框架
// ---------------------------------------------------------------------------
const scenarios = []
const scenario = (id, name, fn) => scenarios.push({ id, name, fn })
const ctx = {} // 跨场景共享：tokens、cardId 等（场景按序执行）

// ---------------------------------------------------------------------------
// S1 首次购卡
// ---------------------------------------------------------------------------
scenario('S1', '首次购卡：无卡创单(CARD_ID=NULL)→Pay-Sim→原子发卡入账', async (ev) => {
  ctx.owner = await miniLogin(OWNER_PHONE)
  const preCards = await cardsByUser(OWNER_USER_ID)
  eq(preCards.length, 0, 'S1 前用户应无卡')

  const packages = await apiOk('/mini/package/list', {}, ctx.owner)
  const accPkg = (packages || []).find(p => String(p.packageId) === ACC_PACKAGE_ID)
  ok(accPkg, '套餐列表应含验收套餐(9501)')
  ev.push(`套餐 ${accPkg.packageName} 售价${accPkg.payAmountFen}分/${accPkg.waterMl}ml/${accPkg.expireDays}天`)

  const requestId = crypto.randomUUID()
  const created = await rechargeCreate(ctx.owner, { requestId })
  const orderNo = created.orderNo
  ok(created.cardId == null, '首次购卡创单响应 CARD_ID 应为空')
  let order = await orderByNo(orderNo)
  eq(order.ORDER_STATUS, 1, '创单后订单应待支付(1)')
  ok(order.CARD_ID == null, '创单后 ws_order.CARD_ID 应为 NULL')
  eq(order.ORDER_AMOUNT, 10000, '订单金额应等于套餐售价')
  let payment = await paymentByOrderId(order.ID)
  eq(payment.PAY_STATUS, 1, '创单后支付单应待支付(1)')
  eq(payment.PAY_EXPIRE_TIME, addMinutes14(order.CREATE_TIME, 30), '首次购卡付款截止应=创建+30min（独立复算）')

  const pay = await paySim(ctx.owner, orderNo)
  eq(pay.resultCode, 'CREDITED', 'Pay-Sim 首付应入账')

  const credited = await assertCredited(orderNo, ev)
  order = credited.order
  payment = credited.payment
  const cards = await cardsByUser(OWNER_USER_ID)
  eq(cards.length, 1, '发卡后用户应恰一张卡')
  const card = cards[0]
  eq(card.ISSUE_ORDER_ID, order.ID, 'card.ISSUE_ORDER_ID 应=order.ID（唯一发行锚点）')
  eq(order.CARD_ID, card.ID, 'order.CARD_ID 应回填=card.ID')
  eq(card.CARD_STATUS, 1, '新卡应正常(1)')
  eq(card.CARD_TYPE, 1, '新卡应为虚拟卡(1)')
  eq(card.BALANCE_ML, 500000, '新卡水量应=套餐 500000ml')
  eq(card.BALANCE_AMOUNT, 0, '新卡余额应=赠送 0 分')
  eq(credited.flow.ML_CHANGE, 500000, '发卡流水水量变动')
  eq(credited.flow.ML_AFTER, 500000, '发卡流水 AFTER 水量')
  eq(credited.flow.AMOUNT_AFTER, 0, '发卡流水 AFTER 余额')
  ok(card.EXPIRE_TIME == null, '付费新卡应永久（EXPIRE_TIME=NULL，付费权益不过期，D-213）', String(card.EXPIRE_TIME))

  // 范围与快照一致：卡范围 = 快照 targetCardScopeSnapshot = 仅水站 9101
  const cardScope = JSON.parse(card.SCOPE_JSON)
  eq(cardScope.scopeType, 'specified', '卡范围类型')
  eq(JSON.stringify(cardScope.stationIds), JSON.stringify(['9101']), '卡范围仅水站9101')
  const snap = JSON.parse(order.PACKAGE_SNAP)
  eq(JSON.stringify(snap.targetCardScopeSnapshot.stationIds), JSON.stringify(['9101']), '快照新卡范围仅水站9101')
  ok(snap.purchaseMode === 'FIRST_CARD', '快照应为首次购卡模式')

  ctx.cardId = card.ID
  ctx.s1OrderNo = orderNo
  ev.push(`card#${card.ID} issueOrder=${card.ISSUE_ORDER_ID} ml=500000 expire=永久(NULL) scope=[9101]`)
})

// ---------------------------------------------------------------------------
// S2 同卡再充
// ---------------------------------------------------------------------------
scenario('S2', '同卡再充：原卡加权益不建二卡，永久有效期保持 NULL', async (ev) => {
  const before = await cardById(ctx.cardId)
  const created = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId: crypto.randomUUID() })
  const pay = await paySim(ctx.owner, created.orderNo)
  eq(pay.resultCode, 'CREDITED', 'Pay-Sim 再充应入账')

  await assertCredited(created.orderNo, ev)
  const cards = await cardsByUser(OWNER_USER_ID)
  eq(cards.length, 1, '再充后仍应只有一张卡（不建二卡）')
  const after = cards[0]
  eq(after.ID, ctx.cardId, '再充应落在原卡')
  eq(Number(after.BALANCE_ML), Number(before.BALANCE_ML) + 500000, '原卡水量应+500000')
  ok(before.EXPIRE_TIME == null && after.EXPIRE_TIME == null, '永久卡再充后有效期必须保持 NULL（充值绝不引入到期日，D-213）', `before=${before.EXPIRE_TIME} after=${after.EXPIRE_TIME}`)
  ev.push(`同卡#${after.ID} ml ${before.BALANCE_ML}→${after.BALANCE_ML} expire 永久保持NULL`)
})

// ---------------------------------------------------------------------------
// S3 待支付恢复
// ---------------------------------------------------------------------------
scenario('S3', '待支付恢复：创单不付→order1/payment1 无流水→继续支付→完成入账', async (ev) => {
  const created = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId: crypto.randomUUID() })
  const order = await orderByNo(created.orderNo)
  eq(order.ORDER_STATUS, 1, '未支付订单应为待支付(1)')
  const payment = await paymentByOrderId(order.ID)
  eq(payment.PAY_STATUS, 1, '未支付支付单应为待支付(1)')
  eq((await flowsByOrderId(order.ID)).length, 0, '未支付订单不得有流水')
  ev.push(`创单未付 order1/payment1 flow=0（${created.orderNo}）`)

  const pay = await paySim(ctx.owner, created.orderNo)
  eq(pay.resultCode, 'CREDITED', '继续支付应完成入账')
  await assertCredited(created.orderNo, ev)
})

// ---------------------------------------------------------------------------
// S4 幂等与超时
// ---------------------------------------------------------------------------
scenario('S4', '幂等与超时：同参返原单/改参拒绝/重复支付一次入账/NOTPAY/改截止后 CLOSED', async (ev) => {
  // a) 同 requestId 同参返原单
  const requestId = crypto.randomUUID()
  const c1 = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId })
  const c2 = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId })
  eq(c2.orderNo, c1.orderNo, '同 requestId 同参应返回原单')
  ok(c2.idempotentHit === true, '第二次创建应标记幂等命中')
  const sameNoCount = await q('SELECT COUNT(*) AS n FROM ws_order WHERE ORDER_NO = ?', [c1.orderNo])
  eq(sameNoCount[0].n, 1, '同单号应只有一条订单')
  ev.push(`同参重放幂等命中（${c1.orderNo}）`)

  // b) 同 requestId 改参数拒绝（换套餐）
  const conflict = await apiFail('/mini/order/recharge/create', { cardId: String(ctx.cardId), packageId: '1', requestId }, ctx.owner)
  ok(String(conflict.msg || '').includes('不可更换'), '改参应拒绝且提示不可更换', conflict.msg)
  ev.push(`改参拒绝：${conflict.msg}`)

  // c) 同单 Pay-Sim×3 只入账一次。
  // 第 2/3 次的合法回应有两种：入口可支付性预检拒绝（订单已完成，无需重复支付——造事实之前就拦），
  // 或事实幂等 ALREADY（事实已存在被重放时）。两种都满足「只入账一次」，据 DB 断言收口。
  const beforePay = await cardSnapshot(ctx.cardId)
  const p1 = await paySim(ctx.owner, c1.orderNo)
  eq(p1.resultCode, 'CREDITED', '第一次支付应入账')
  const repeats = [await api('/mini/pay-sim/pay', { orderNo: c1.orderNo }, ctx.owner), await api('/mini/pay-sim/pay', { orderNo: c1.orderNo }, ctx.owner)]
  for (const [i, r] of repeats.entries()) {
    const okRepeat = r.code === 0
      ? r.data && r.data.resultCode === 'ALREADY'
      : /已完成|无需重复|无需再次/.test(String(r.msg || ''))
    ok(okRepeat, `第${i + 2}次支付应幂等或被预检拒绝`, `code=${r.code} msg=${r.msg} resultCode=${r.data && r.data.resultCode}`)
  }
  const afterPay = await cardSnapshot(ctx.cardId)
  eq(Number(afterPay.balanceMl), Number(beforePay.balanceMl) + 500000, '三次支付只应入账一次(+500000ml)')
  eq(afterPay.flowCount, beforePay.flowCount + 1, '三次支付只应产生一条流水')
  const flowKey = await q('SELECT COUNT(*) AS n FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY = ?', [`RECHARGE:${c1.orderNo}`])
  eq(flowKey[0].n, 1, '幂等键流水应恰一条')
  ev.push(`Pay-Sim×3 → CREDITED + 2次拒绝/幂等（${repeats.map(r => r.code === 0 ? r.data.resultCode : `拒:${r.msg}`).join('、')}），流水恰1条`)

  // d) 截止前 query = NOTPAY（新待支付单）
  const c4 = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId: crypto.randomUUID() })
  const notpay = await paySimQuery(ctx.owner, c4.orderNo)
  eq(notpay.tradeState, 'NOTPAY', '截止前查单应 NOTPAY')
  let order4 = await orderByNo(c4.orderNo)
  eq(order4.ORDER_STATUS, 1, 'NOTPAY 后订单应保持待支付(1)')
  eq((await paymentByOrderId(order4.ID)).PAY_STATUS, 1, 'NOTPAY 后支付单应保持待支付(1)')
  ev.push(`截止前 query=NOTPAY，payment1/order1 保持`)

  // e) 验收库直改截止为过去 → query = CLOSED → payment4/order5，卡与流水零变化。
  // PAY_EXPIRE_TIME 受「快照采集时间重算」保护，单改一列会被判定篡改转人工；
  // 因此把这笔单整体前移 2 小时（CREATE_TIME/快照 capturedTime/支付单 CREATE_TIME/截止时间一致前移），
  // 使「截止已过」成为自洽事实——这正是对不可变截止时间机制的验收方式。
  const shifted = addMinutes14(order4.CREATE_TIME, -120)
  const shiftedExpire = addMinutes14(shifted, 30)
  await q('UPDATE ws_order SET CREATE_TIME = ?, UPDATE_TIME = ?, PACKAGE_SNAP = REPLACE(PACKAGE_SNAP, ?, ?) WHERE ID = ?', [shifted, shifted, `"capturedTime":"${order4.CREATE_TIME}"`, `"capturedTime":"${shifted}"`, order4.ID])
  await q('UPDATE ws_payment SET CREATE_TIME = ?, UPDATE_TIME = ?, PAY_EXPIRE_TIME = ? WHERE ORDER_ID = ?', [shifted, shifted, shiftedExpire, order4.ID])
  const tampered = await orderByNo(c4.orderNo)
  ok(String(tampered.PACKAGE_SNAP).includes(`"capturedTime":"${shifted}"`), '快照采集时间应已前移')

  const beforeClose = await cardSnapshot(ctx.cardId)
  const closed = await paySimQuery(ctx.owner, c4.orderNo)
  eq(closed.tradeState, 'CLOSED', '截止后查单应 CLOSED')
  eq(closed.resultCode, 'CLOSED', '关单处理应成功')
  order4 = await orderByNo(c4.orderNo)
  eq(order4.ORDER_STATUS, 5, '关单后订单应已取消/关闭(5)')
  eq((await paymentByOrderId(order4.ID)).PAY_STATUS, 4, '关单后支付单应已关闭(4)')
  eq((await flowsByOrderId(order4.ID)).length, 0, '关单订单不得有流水')
  assertSnapshotUnchanged(beforeClose, await cardSnapshot(ctx.cardId), '关单')
  ev.push(`改截止(${shiftedExpire})后 query=CLOSED → payment4/order5，卡与流水零变化`)
})

// ---------------------------------------------------------------------------
// S5 可恢复 / 不可恢复
// ---------------------------------------------------------------------------
scenario('S5', '可恢复/不可恢复：冻结→RETRY_WAIT→解冻 Worker 自动入账；改归属→事实5转人工', async (ev) => {
  ctx.ops = await opsLogin()

  // a) 可恢复：创单在前（创单要求卡正常），支付成功前冻结目标卡
  const cA = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId: crypto.randomUUID() })
  await changeCardStatus(ctx.ops, ctx.cardId, 2)
  eq((await cardById(ctx.cardId)).CARD_STATUS, 2, '卡应已冻结')
  const payA = await paySim(ctx.owner, cA.orderNo)
  eq(payA.resultCode, 'RETRY', '冻结卡支付应转待重试')
  const orderA = await orderByNo(cA.orderNo)
  eq(orderA.ORDER_STATUS, 2, '冻结期订单应保持已支付(2)')
  eq((await paymentByOrderId(orderA.ID)).PAY_STATUS, 2, '冻结期支付单应成功(2)')
  const retryEvents = (await eventsByOrderNo(cA.orderNo)).filter(e => e.TRADE_STATE === 'SUCCESS')
  eq(retryEvents.length, 1, '成功事实应恰一条')
  eq(retryEvents[0].PROCESSING_STATUS, 4, '事实应为 RETRY_WAIT(4)')
  eq((await flowsByOrderId(orderA.ID)).length, 0, '冻结期不得产生流水')
  ev.push(`冻结后支付 → payment2/order2/事实RETRY_WAIT(4)，零流水`)

  // 解冻 → Worker（30s 周期 + 60s 重试退避）自动完成入账
  await changeCardStatus(ctx.ops, ctx.cardId, 1)
  await pollUntil('解冻后 Worker 自动入账', async () => {
    const fresh = await orderByNo(cA.orderNo)
    return Number(fresh.ORDER_STATUS) === 4 ? fresh : null
  }, 180_000, 5000)
  const creditedA = await assertCredited(cA.orderNo, ev)
  eq(creditedA.flow.ML_CHANGE, 500000, 'Worker 入账水量')
  ev.push(`解冻后 Worker 自动 order4，唯一流水#${creditedA.flow.ID}`)

  // b) 不可恢复：创单后改卡归属
  const cB = await rechargeCreate(ctx.owner, { cardId: ctx.cardId, requestId: crypto.randomUUID() })
  await q('UPDATE ws_card SET USER_ID = ? WHERE ID = ?', [MEMBER_USER_ID, ctx.cardId])
  const beforeB = await cardSnapshot(ctx.cardId)
  const payB = await paySim(ctx.owner, cB.orderNo)
  eq(payB.resultCode, 'RECONCILIATION', '改归属后支付应转人工对账')
  const orderB = await orderByNo(cB.orderNo)
  eq(orderB.ORDER_STATUS, 6, '不可恢复失败订单应异常待补偿(6)')
  eq((await paymentByOrderId(orderB.ID)).PAY_STATUS, 2, '支付单应保持成功(2)——钱已收')
  const reconEvents = (await eventsByOrderNo(cB.orderNo)).filter(e => e.TRADE_STATE === 'SUCCESS')
  eq(reconEvents.length, 1, '成功事实应恰一条')
  eq(reconEvents[0].PROCESSING_STATUS, 5, '事实应为待对账(5)')
  assertSnapshotUnchanged(beforeB, await cardSnapshot(ctx.cardId), '改归属')
  await q('UPDATE ws_card SET USER_ID = ? WHERE ID = ?', [OWNER_USER_ID, ctx.cardId])
  ev.push(`改归属后支付 → payment2/order6/事实5，卡与流水不变（已还原归属）`)
})

// ---------------------------------------------------------------------------
// S6 范围外拒绝
// ---------------------------------------------------------------------------
scenario('S6', '范围外拒绝：扫水站2码→预检 CARD_SCOPE_DENIED→强行提交被拒零变化、会话不消费', async (ev) => {
  const sessionId = await scanTo(ctx.owner, QR_STATION2)
  const elig = await eligibility(ctx.owner, sessionId, ctx.cardId)
  ok(elig.cardBlock, '范围外应有卡阻断')
  eq(elig.cardBlock.code, 'CARD_SCOPE_DENIED', '预检应给出 CARD_SCOPE_DENIED')

  const before = await cardSnapshot(ctx.cardId)
  const ordersBefore = await q('SELECT COUNT(*) AS n FROM ws_order WHERE USER_ID = ? AND ORDER_TYPE = 1', [OWNER_USER_ID])
  const cmdsBefore = await q('SELECT COUNT(*) AS n FROM ws_command')
  const forced = await apiFail('/mini/order/water/create', { scanSessionId: sessionId, cardId: Number(ctx.cardId), waterTypeId: WATER_TYPE_ID, planMl: 10000, payWay: 3 }, ctx.owner)
  ev.push(`强行提交被拒：${forced.msg}`)

  const ordersAfter = await q('SELECT COUNT(*) AS n FROM ws_order WHERE USER_ID = ? AND ORDER_TYPE = 1', [OWNER_USER_ID])
  const cmdsAfter = await q('SELECT COUNT(*) AS n FROM ws_command')
  eq(ordersAfter[0].n, ordersBefore[0].n, '取水订单零新增')
  eq(cmdsAfter[0].n, cmdsBefore[0].n, '设备指令零新增')
  assertSnapshotUnchanged(before, await cardSnapshot(ctx.cardId), '范围外拒绝')

  // 会话不消费：拒绝后同会话仍可读取上下文
  const context = await apiOk('/mini/device/water/context', { scanSessionId: sessionId }, ctx.owner)
  ok(context && context.scanSessionId === sessionId, '拒绝后扫码会话应仍存活（未被消费）')
  ev.push('CARD_SCOPE_DENIED；订单/流水/指令零变化；会话未消费')
})

// ---------------------------------------------------------------------------
// S7 范围内取水退差
// ---------------------------------------------------------------------------
scenario('S7', '范围内取水退差：计划10000ml 预扣→ACK/result 4980→补偿5020→净扣4980', async (ev) => {
  const sessionId = await scanTo(ctx.owner, QR_STATION1)
  const elig = await eligibility(ctx.owner, sessionId, ctx.cardId)
  eq(elig.availability, 'AVAILABLE', '设备应可用')
  ok(!elig.cardBlock, '范围内不应有卡阻断')

  const before = await cardById(ctx.cardId)
  const detail = await apiOk('/mini/order/water/create', { scanSessionId: sessionId, cardId: Number(ctx.cardId), waterTypeId: WATER_TYPE_ID, planMl: 10000, payWay: 3 }, ctx.owner)
  const orderNo = detail.order.orderNo
  let order = await orderByNo(orderNo)
  eq(order.PLAN_ML, 10000, '计划水量')
  eq(order.PAY_WAY, 3, '支付方式应为水卡水量(3)')

  order = await pollUntil('设备回执后订单完成', async () => {
    const fresh = await orderByNo(orderNo)
    return Number(fresh.ORDER_STATUS) === 4 && fresh.ACTUAL_ML != null ? fresh : null
  }, 60_000, 3000)
  eq(order.ACTUAL_ML, 4980, '实际水量应=模拟器回传 4980')

  const commands = await commandsByOrderId(order.ID)
  eq(commands.length, 1, '出水指令应恰一条')
  const command = commands[0]
  eq(order.CMD_ID, command.ID, '订单应绑定该指令')
  eq(command.CMD_STATUS, 4, '指令应执行成功(4)')
  ok(String(command.RESULT_PAYLOAD || '').includes('4980'), '指令结果应含 actualMl=4980')

  const flows = await flowsByOrderId(order.ID)
  eq(flows.length, 2, '应有预扣+补偿两条流水')
  eq(flows[0].ML_CHANGE, -10000, '预扣流水 -10000ml')
  eq(flows[0].ML_AFTER, Number(before.BALANCE_ML) - 10000, '预扣 AFTER 连续')
  eq(flows[1].FLOW_TYPE, 4, '第二条应为补偿入账(4)')
  eq(flows[1].ML_CHANGE, 5020, '补偿流水 +5020ml')
  eq(flows[1].ML_AFTER, Number(flows[0].ML_AFTER) + 5020, '补偿 AFTER 连续')
  const after = await cardById(ctx.cardId)
  eq(after.BALANCE_ML, Number(before.BALANCE_ML) - 4980, '净扣应=4980ml')
  eq(after.BALANCE_ML, flows[1].ML_AFTER, '卡终值应=末条流水 AFTER（账实连续）')

  const msgs = await q('SELECT COUNT(*) AS n FROM ws_device_msg WHERE DEVICE_ID = ?', [DEVICE1_ID])
  ok(Number(msgs[0].n) >= 2, '设备上行消息（ack/result）应已落库去重表')
  ev.push(`order4 actual=4980 cmd#${command.CMD_NO}(4) flows[-10000,+5020] 卡 ${before.BALANCE_ML}→${after.BALANCE_ML}（净-4980）`)
})

// ---------------------------------------------------------------------------
// S8 冻结解冻
// ---------------------------------------------------------------------------
scenario('S8', '冻结解冻：PC 冻结→预检 CARD_FROZEN→解冻→重新预检可取', async (ev) => {
  await changeCardStatus(ctx.ops, ctx.cardId, 2)
  const s1 = await scanTo(ctx.owner, QR_STATION1)
  const frozen = await eligibility(ctx.owner, s1, ctx.cardId)
  ok(frozen.cardBlock, '冻结后应有卡阻断')
  eq(frozen.cardBlock.code, 'CARD_FROZEN', '预检应给出 CARD_FROZEN')

  await changeCardStatus(ctx.ops, ctx.cardId, 1)
  const s2 = await scanTo(ctx.owner, QR_STATION1)
  const thawed = await eligibility(ctx.owner, s2, ctx.cardId)
  ok(!thawed.cardBlock, '解冻后不应有卡阻断')
  eq(thawed.availability, 'AVAILABLE', '解冻后设备可用')
  ev.push('冻结→CARD_FROZEN；解冻→AVAILABLE 且无阻断')
})

// ---------------------------------------------------------------------------
// S9 成员授权限额
// ---------------------------------------------------------------------------
scenario('S9', '成员授权限额：日限6000 取5000成功→再取2000拒→撤销后拒', async (ev) => {
  const saved = await apiOk('/mini/card/member/save', { cardId: Number(ctx.cardId), memberName: '验收成员', phone: MEMBER_PHONE, dayLimitMl: 6000 }, ctx.owner)
  ok(saved && saved.memberId, '授权应返回 memberId')
  const memberId = saved.memberId

  ctx.member = await miniLogin(MEMBER_PHONE)
  const before = await cardById(ctx.cardId)
  const s1 = await scanTo(ctx.member, QR_STATION1)
  const elig1 = await eligibility(ctx.member, s1, ctx.cardId)
  ok(!elig1.cardBlock, '有效成员应可用卡')
  eq(elig1.remainingDailyLimitMl, 6000, '当日剩余限额应=6000')

  const detail = await apiOk('/mini/order/water/create', { scanSessionId: s1, cardId: Number(ctx.cardId), waterTypeId: WATER_TYPE_ID, planMl: 5000, payWay: 3 }, ctx.member)
  const orderNo = detail.order.orderNo
  let order = await orderByNo(orderNo)
  eq(order.USER_ID, MEMBER_USER_ID, '订单归属应为成员')
  eq(order.CARD_ID, ctx.cardId, '扣的应是卡主的卡')
  order = await pollUntil('成员取水完成', async () => {
    const fresh = await orderByNo(orderNo)
    return Number(fresh.ORDER_STATUS) === 4 ? fresh : null
  }, 60_000, 3000)
  const flows = await flowsByOrderId(order.ID)
  ok(flows.length >= 1, '成员取水应有流水')
  eq(flows[0].USER_ID, MEMBER_USER_ID, '流水操作人应为成员')
  eq(flows[0].ML_CHANGE, -5000, '成员预扣 -5000ml')
  const afterTake = await cardById(ctx.cardId)
  ev.push(`成员取水 order4 actual=${order.ACTUAL_ML} 卡 ${before.BALANCE_ML}→${afterTake.BALANCE_ML}（订单/流水 USER_ID=9002）`)

  // 同日再取 2000 → 超限拒绝
  const s2 = await scanTo(ctx.member, QR_STATION1)
  const denied = await apiFail('/mini/order/water/create', { scanSessionId: s2, cardId: Number(ctx.cardId), waterTypeId: WATER_TYPE_ID, planMl: 2000, payWay: 3 }, ctx.member)
  ok(String(denied.msg || '').includes('限额'), '再取应因日限额被拒', denied.msg)
  const memberOrders = await q(
    'SELECT COUNT(*) AS n FROM ws_order WHERE USER_ID = ? AND ORDER_TYPE = 1',
    [MEMBER_USER_ID],
  )
  eq(memberOrders[0].n, 1, '成员取水订单应仍为 1 条（超限未建单）')
  ev.push(`同日再取2000 → ${denied.msg}`)

  // 撤销授权后再取 → 拒绝
  const revoked = await apiOk('/mini/card/member/revoke', { cardId: Number(ctx.cardId), memberId: Number(memberId) }, ctx.owner)
  ok(revoked && revoked.enabled === false, '撤销后授权应失效')
  const s3 = await scanTo(ctx.member, QR_STATION1)
  const elig3 = await eligibility(ctx.member, s3, ctx.cardId)
  ok(elig3.cardBlock, '撤销后应有卡阻断')
  eq(elig3.cardBlock.code, 'CARD_NOT_ACCESSIBLE', '撤销后预检应 CARD_NOT_ACCESSIBLE')
  const deniedAfterRevoke = await apiFail('/mini/order/water/create', { scanSessionId: s3, cardId: Number(ctx.cardId), waterTypeId: WATER_TYPE_ID, planMl: 1000, payWay: 3 }, ctx.member)
  const memberOrders2 = await q(
    'SELECT COUNT(*) AS n FROM ws_order WHERE USER_ID = ? AND ORDER_TYPE = 1',
    [MEMBER_USER_ID],
  )
  eq(memberOrders2[0].n, 1, '撤销后成员订单零新增')
  ev.push(`撤销授权后 → CARD_NOT_ACCESSIBLE；下单拒绝：${deniedAfterRevoke.msg}`)
})

// ---------------------------------------------------------------------------
// S10 活动赠卡不可充值（D-213）
// ---------------------------------------------------------------------------
scenario('S10', '活动赠卡不可充值：赠卡 canRecharge=false，充值创单被拒零写入', async (ev) => {
  // 赠卡只能由运营发放，一期无发放入口：按发放结果直接落库一张有限期赠卡
  await q(
    `INSERT INTO ws_card (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, CARD_NO, CARD_TYPE,
       USER_ID, BALANCE_AMOUNT, BALANCE_ML, SCOPE_JSON, EXPIRE_TIME, CARD_STATUS, CARD_REMARK)
     VALUES (0, 1, '20260725000000', 1, '20260725000000', 'GIFT-ACC-0001', 1, ?, 0, 20000,
       '{"scopeType":"specified","stationIds":[9101]}', '20301231235959', 1, 'S10 活动赠卡夹具')`,
    [OWNER_USER_ID],
  )
  const gift = await one('SELECT * FROM ws_card WHERE CARD_NO = ?', ['GIFT-ACC-0001'])
  ok(gift, '赠卡夹具应落库成功')

  const usable = await apiOk('/mini/card/usable-list', {}, ctx.owner)
  const giftVo = (usable || []).find(c => String(c.cardId) === String(gift.ID))
  const mainVo = (usable || []).find(c => String(c.cardId) === String(ctx.cardId))
  ok(giftVo && mainVo, 'usable-list 应同时包含付费卡与赠卡')
  eq(giftVo.canRecharge, false, '赠卡 canRecharge 必须为 false（页面不得出现充值入口）')
  eq(mainVo.canRecharge, true, '付费永久卡保持可充值')

  const before = await q('SELECT ID FROM ws_order WHERE CARD_ID = ?', [gift.ID])
  const denied = await apiFail('/mini/order/recharge/create', { cardId: String(gift.ID), packageId: ACC_PACKAGE_ID, requestId: crypto.randomUUID() }, ctx.owner)
  const deniedMessage = String(denied.msg || '')
  ok(deniedMessage.includes('活动赠卡') && deniedMessage.includes('暂不支持充值')
    && deniedMessage.includes('转为正式水卡'), '拒绝话术应符合 D-213/D-416 现行口径', `msg=${denied.msg}`)
  const after = await q('SELECT ID FROM ws_order WHERE CARD_ID = ?', [gift.ID])
  eq(after.length, before.length, '拒绝必须零写入（不产生订单）')
  ev.push(`gift#${gift.ID} canRecharge=false；创单拒绝：${denied.msg}`)
})

// ---------------------------------------------------------------------------
// 环境指纹核验（开跑前 fail-closed）
// ---------------------------------------------------------------------------
async function verifyEnvironment() {
  const fingerprint = {
    base: BASE,
    dbHost: DB_CONFIG.host,
    dbPort: DB_CONFIG.port,
    dbName: DB_CONFIG.database,
    round: ROUND,
    seedSentinel: false,
    paySimGateOpen: false,
  }
  const owner = await one('SELECT ID, USER_PHONE FROM ws_user WHERE ID = ?', [OWNER_USER_ID])
  const member = await one('SELECT ID FROM ws_user WHERE ID = ?', [MEMBER_USER_ID])
  const pkg = await one('SELECT ID, SCOPE_JSON FROM ws_package WHERE ID = ?', [ACC_PACKAGE_ID])
  ok(owner && owner.USER_PHONE === OWNER_PHONE && member && pkg, '验收种子哨兵缺失（先跑 acc-env.sh rebuild）')
  fingerprint.seedSentinel = true
  // 测试登录路由仅在 mini.test-login.enabled=true 时注册：404/白页即视为该开关未开
  const probe = await api('/mini/test-login/by-phone', { phone: OWNER_PHONE })
  ok(probe && probe.code === 0, '测试登录不可用——mini.test-login.enabled 未开或指向了错误环境')
  fingerprint.paySimGateOpen = true
  return fingerprint
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  // 结构化结果闸：场景总数必须恰为 10，杜绝 0/0 或缺场景「全过」
  if (scenarios.length !== 10) {
    console.error(`场景登记数=${scenarios.length}，必须恰为 10；拒绝执行`)
    process.exit(2)
  }
  const mysql = require('mysql2/promise')
  pool = await mysql.createPool({ ...DB_CONFIG, connectionLimit: 4 })

  const envFingerprint = await verifyEnvironment()
  console.log(`环境指纹：base=${envFingerprint.base} db=${envFingerprint.dbHost}:${envFingerprint.dbPort} round=${ROUND}`)

  const results = []
  for (const item of scenarios) {
    const evidence = []
    let pass = false
    let error = ''
    const startedAt = Date.now()
    try {
      await item.fn(evidence)
      pass = true
    }
    catch (e) {
      error = e && e.message ? e.message : String(e)
    }
    results.push({ id: item.id, name: item.name, pass, evidence, error, durationMs: Date.now() - startedAt })
    console.log(`${pass ? 'PASS' : 'FAIL'} | ${item.id} ${item.name}${error ? ` | ${error}` : ''}`)
  }

  const passed = results.filter(r => r.pass).length
  const result = {
    schemaVersion: 1,
    batchId: BATCH_ID,
    round: ROUND,
    createdAt: new Date().toISOString(),
    envFingerprint,
    scriptSha256: sha256File(__filename),
    scenarios: results,
    passed,
    total: scenarios.length,
    pass: passed === scenarios.length,
  }
  fs.mkdirSync(RUN_DIR, { recursive: true })
  fs.writeFileSync(path.join(RUN_DIR, 'result.json'), `${JSON.stringify(result, null, 2)}\n`)

  console.log(`\nSUMMARY round=${ROUND} scenarios=${passed}/${result.total} ${result.pass ? 'PASS' : 'FAIL'}`)
  console.log(`BATCH_DIR=${RUN_DIR}`)
  await pool.end()
  process.exit(result.pass ? 0 : 1)
}

main().catch(async (error) => {
  console.error('FATAL:', error)
  if (pool) {
    await pool.end().catch(() => {})
  }
  process.exit(1)
})
