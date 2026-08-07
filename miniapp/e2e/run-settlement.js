/**
 * E2E-08 支付、对账与分账固定场景验收（S1～S14，真实 HTTP + 验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已 rebuild（init + migrations + acc-seed）；
 * - 验收后端已 backend-start；两轮之间必须重新 rebuild（本链会新增比例版本/分账行，
 *   哨兵按「零分账行 + 恰两版售水配置」核验，脏库直接拒跑）。
 *
 * 口径依据（任务书三项已冻结裁量 + 四个甲方待确认扩展位）：
 * - 分账=内部账务计提（Pay-Sim 不动真实资金）；基数=消费订单实扣金额，充值单不分账；
 * - 水量支付(payWay3) ORDER_AMOUNT=0 不产分账（水费在充值环节结算，同 E2E-06 预付披露）；
 * - 比例万分比、订单创建时点版本快照、整除余数恒归平台（平台行快照=REMAINDER）；
 * - 赠卡带有效期、不可充值、不占一人一卡名额；提现只做冻结/驳回骨架，无真实出金。
 *
 * 安全闸（fail-closed）：必须显式 SETTLE_ACC_MODE=full；主环境端口/主库端口拒绝；
 * 种子哨兵（分账配置 9801~9805 + 干净分账面）；13340 pid 核验；JAR 指纹落盘；
 * 产物污染扫描；场景数必须恰为 14；失败落盘退出码非零。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { execFile, spawn } = require('node:child_process')
const { sha256File } = require('./e1b-core')
const { newBatchId } = require('./report-core')

if (String(process.env.SETTLE_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 SETTLE_ACC_MODE=full（本脚本会真实写验收库）')
  process.exit(2)
}
if (!String(process.env.ACC_DB_PASSWORD || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（仓库根 .env）')
  process.exit(2)
}
if (!String(process.env.ACC_OPS_PWD_CIPHER || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_OPS_PWD_CIPHER（PC 财务动作需要运营会话）')
  process.exit(2)
}

const BASE = (process.env.ACC_BASE || 'http://127.0.0.1:13340/dakangApi').replace(/\/$/, '')
const DB_CONFIG = {
  host: process.env.ACC_DB_HOST || '127.0.0.1',
  port: Number(process.env.ACC_DB_PORT || 3309),
  user: process.env.ACC_DB_USER || 'root',
  password: process.env.ACC_DB_PASSWORD,
  database: process.env.ACC_DB_NAME || 'dakang',
}
if (/:(?:13330|8081)(?:\/|$)/.test(BASE)) {
  console.error(`安全闸拒绝执行：ACC_BASE=${BASE} 指向主环境端口`)
  process.exit(2)
}
if (DB_CONFIG.port === 3306 || DB_CONFIG.port === 3308) {
  console.error(`安全闸拒绝执行：ACC_DB_PORT=${DB_CONFIG.port} 指向主库端口`)
  process.exit(2)
}

const ROUND = String(process.env.ACC_ROUND || '').trim() || 'unlabeled'
const REPO_ROOT = path.resolve(__dirname, '../..')
const ACCEPT_DIR = path.resolve(REPO_ROOT, 'docs/acceptance/settlement')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, 'runs', BATCH_ID)
const SIM = path.resolve(REPO_ROOT, 'tools/device-sim/sim.js')
const JAR_GLOB_DIR = path.resolve(REPO_ROOT, 'server/target')
const ACC_ENV_RUN_DIR = path.resolve(REPO_ROOT, 'deploy/acceptance/.run')
const BROKER = process.env.ACC_BROKER || 'tcp://127.0.0.1:1884'
const BACKEND_PORT = 13340

// 种子固定身份（acc-seed.sql；E2E-08 增量：分账配置 9801~9805）
const OWNER_PHONE = '13999990001' // 9001：站 9101 + 设备 9201 双轨机主 → 分账机主收款方
const MEMBER_PHONE = '13999990002' // 9002：无归属用户 → 邀请被绑定方
const COURIER_PHONE = '13999990003' // 9003：配送员 → 配送分账收款方 + 赠卡收卡人
const OWNER_ID = 9001
const MEMBER_ID = 9002
const COURIER_ID = 9003
const OPS_LOGIN = 'acc-ops'
const OPS_PWD_CIPHER = process.env.ACC_OPS_PWD_CIPHER
const STATION_ID = 9101
const DEV1 = { id: 9201, no: 'ACC-DEV-0001' }
const QR_DEV1 = 'ACC-QR-DEV1-O1'
const WATER_TYPE_ID = 1
const PACKAGE_ID = '9501' // 首购套餐（含水量权益，payWay3 场景依赖）
const CASH_PACKAGE_ID = '9502' // 现金充值套餐（指定卡追充）
// 种子分账比例（acc-seed.sql 9801~9805，演示值）：售水 机主7000/平台余数；配送 6000/3000/余数
const RATE = { waterOwner: 7000, deliveryOwner: 6000, deliveryCourier: 3000 }

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const rid = () => crypto.randomUUID()

/** yyyyMMddHHmmss 平移（分钟，向过去为正）；S12 构造真实超期用 */
function minusMinutes(ts, minutes) {
  const d = new Date(
    Number(ts.slice(0, 4)),
    Number(ts.slice(4, 6)) - 1,
    Number(ts.slice(6, 8)),
    Number(ts.slice(8, 10)),
    Number(ts.slice(10, 12)),
    Number(ts.slice(12, 14)),
  )
  d.setMinutes(d.getMinutes() - minutes)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}

class CheckError extends Error {}

function ok(cond, label, detail = '') {
  if (!cond) {
    throw new CheckError(`${label}${detail ? `（${detail}）` : ''}`)
  }
}

function eq(actual, expected, label) {
  ok(String(actual) === String(expected), label, `期望 ${expected}，实际 ${actual}`)
}

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
  try {
    return { parsed: JSON.parse(text), rawText: text, status: resp.status }
  }
  catch {
    throw new CheckError(`HTTP ${resp.status} ${pathName} 非 JSON 响应：${text.slice(0, 120)}`)
  }
}

async function apiOk(pathName, body, token) {
  const { parsed } = await api(pathName, body, token)
  ok(parsed && parsed.code === 0, `${pathName} 应成功`, `code=${parsed && parsed.code} msg=${parsed && parsed.msg}`)
  return parsed.data
}

async function apiFail(pathName, body, token) {
  const { parsed } = await api(pathName, body, token)
  ok(parsed && parsed.code !== 0, `${pathName} 应被拒绝`, '却返回 code=0')
  return parsed
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

/** 期望撞唯一键的直插（库层幂等闸验证专用）：非 ER_DUP_ENTRY 一律原样抛出。 */
async function expectDupKey(sql, params, label) {
  try {
    await pool.query(sql, params)
    throw new CheckError(`${label}：期望撞唯一键，实际插入成功`)
  }
  catch (e) {
    if (e instanceof CheckError) {
      throw e
    }
    eq(e.code, 'ER_DUP_ENTRY', label)
  }
}

async function pollUntil(label, fn, timeoutMs, intervalMs = 1500) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await fn()
    if (value) {
      return value
    }
    await sleep(intervalMs)
  }
  throw new CheckError(`${label}：${Math.round(timeoutMs / 1000)}s 内未达到期望状态`)
}

async function miniLogin(phone) {
  const data = await apiOk('/mini/test-login/by-phone', { phone })
  ok(data && data.tokenName && data.tokenValue, '测试登录应返回 token')
  return { tokenName: data.tokenName, tokenValue: data.tokenValue }
}

async function opsLogin(loginName) {
  const data = await apiOk('/api/auth/loginEmployee', { loginName, loginPwd: OPS_PWD_CIPHER })
  ok(data && data.tokenValue, `运营登录 ${loginName} 应成功`)
  // MANAGE 驱动实际读的请求头是 dakang-token（与 run-device-ops 同源），响应体 tokenName 不可用
  return { tokenName: 'dakang-token', tokenValue: data.tokenValue }
}

// 1x1 PNG（签收三照登记只校验 MIME 与大小）；媒体键内容寻址，随机尾巴保证可重复执行
const PNG_1X1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
const uniquePng = () => Buffer.concat([Buffer.from(PNG_1X1, 'base64'), crypto.randomBytes(16)]).toString('base64')

// 取证上下文提前定义（供各步骤记录原始报文）
const ctx = { rawParts: [] }

// ---------------------------------------------------------------------------
// 设备模拟器（S1/S3/S4 真实取水链用；固定回执 actualMl=4980）
// ---------------------------------------------------------------------------
const simProcs = new Map()

async function simStart(deviceNo, ...modes) {
  await simStop(deviceNo)
  const proc = spawn('node', [SIM, deviceNo, ...modes], {
    env: { ...process.env, BROKER },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  fs.mkdirSync(RUN_DIR, { recursive: true })
  const logStream = fs.createWriteStream(path.join(RUN_DIR, `sim-${deviceNo}.log`), { flags: 'a' })
  proc.stdout.pipe(logStream)
  proc.stderr.pipe(logStream)
  simProcs.set(deviceNo, proc)
  await sleep(2500)
  return proc
}

async function simStop(deviceNo) {
  const proc = simProcs.get(deviceNo)
  if (proc && !proc.killed) {
    proc.kill('SIGTERM')
    await sleep(300)
  }
  simProcs.delete(deviceNo)
}

async function simStopAll() {
  for (const deviceNo of [...simProcs.keys()]) {
    await simStop(deviceNo)
  }
}

// ---------------------------------------------------------------------------
// 业务链辅助
// ---------------------------------------------------------------------------

/** 机主永久卡（EXPIRE_TIME IS NULL，赠卡不算）；无卡走首购发卡，余额不足按套餐追充。 */
async function ensureOwnerCard(minBalanceFen) {
  let card = await one(
    'SELECT * FROM ws_card WHERE USER_ID=? AND DATA_STATUS=0 AND EXPIRE_TIME IS NULL ORDER BY ID DESC LIMIT 1',
    [OWNER_ID],
  )
  if (!card) {
    const created = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
    card = await one(
      'SELECT * FROM ws_card WHERE USER_ID=? AND DATA_STATUS=0 AND EXPIRE_TIME IS NULL ORDER BY ID DESC LIMIT 1',
      [OWNER_ID],
    )
    ok(card, '首购应发出永久水卡')
  }
  for (let i = 0; i < 5 && Number(card.BALANCE_AMOUNT) < minBalanceFen; i++) {
    const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(card.ID), requestId: rid() }, ctx.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, ctx.owner)
    card = await one('SELECT * FROM ws_card WHERE ID=?', [card.ID])
  }
  ok(Number(card.BALANCE_AMOUNT) >= minBalanceFen, '追充后余额仍不足', `${card.BALANCE_AMOUNT} < ${minBalanceFen}`)
  return card
}

/** 真实取水链：扫码 → 创单 → 模拟器回执 → 结算完成；返回订单行。 */
async function waterOrderThrough(cardId, payWay) {
  const scan = await apiOk('/mini/device/scan/resolve', { rawCode: QR_DEV1 }, ctx.owner)
  const detail = await apiOk('/mini/order/water/create', {
    scanSessionId: scan.scanSessionId,
    cardId: Number(cardId),
    waterTypeId: WATER_TYPE_ID,
    planMl: 10000,
    payWay,
  }, ctx.owner)
  const orderNo = detail.order.orderNo
  await pollUntil(`取水单 ${orderNo} 应结算完成`, async () => {
    const row = await one('SELECT ORDER_STATUS FROM ws_order WHERE ORDER_NO=?', [orderNo])
    return row && row.ORDER_STATUS === 4 ? row : null
  }, 30000)
  return one('SELECT * FROM ws_order WHERE ORDER_NO=?', [orderNo])
}

/** 配送真实链：接单 → 出发 → 到达 → 三照签收（配送员 9003）。 */
async function deliverAndSign(task) {
  const taskNo = task.TASK_NO
  let v = task.VERSION
  const accepted = await apiOk('/mini/delivery/task/accept', { taskNo, expectedVersion: v }, ctx.courier)
  v = accepted.version
  const left = await apiOk('/mini/delivery/task/advance', { taskNo, targetStatus: 3, expectedVersion: v }, ctx.courier)
  v = left.version
  const arrived = await apiOk('/mini/delivery/task/advance', { taskNo, targetStatus: 4, expectedVersion: v }, ctx.courier)
  v = arrived.version
  const keys = []
  for (let i = 0; i < 3; i++) {
    const m = await apiOk('/mini/delivery/media/upload', {
      purpose: 1,
      mimeType: 'image/png',
      contentBase64: uniquePng(),
    }, ctx.courier)
    keys.push(m.mediaKey)
  }
  await apiOk('/mini/delivery/task/sign', {
    taskNo,
    expectedVersion: v,
    actualDeliveryCount: 2,
    actualReturnCount: 0,
    locationStatus: 2,
    photos: [
      { type: 1, mediaKey: keys[0] },
      { type: 2, mediaKey: keys[1] },
      { type: 3, mediaKey: keys[2] },
    ],
  }, ctx.courier)
}

const splitRowsOf = orderId => q('SELECT * FROM ws_split_record WHERE ORDER_ID=? ORDER BY RECEIVER_TYPE', [orderId])
const walletOf = token => api('/mini/owner/wallet', {}, token)

// ---------------------------------------------------------------------------
// 场景注册
// ---------------------------------------------------------------------------
const scenarios = []
function scenario(id, name, fn) {
  scenarios.push({ id, name, fn })
}

scenario('S1', '水线真实链分账：余额取水完成→机主70%快照+平台余数→Worker结算入账', async (ev) => {
  await simStart(DEV1.no)
  const card = await ensureOwnerCard(8000)
  ctx.cardId = card.ID
  const order = await waterOrderThrough(card.ID, 2)
  ctx.waterOrder = order
  ctx.bizDate = String(order.CREATE_TIME).slice(0, 8)

  const rows = await splitRowsOf(order.ID)
  eq(rows.length, 2, '水线恰两行：机主+平台')
  const owner = rows.find(r => r.RECEIVER_TYPE === 1)
  const platform = rows.find(r => r.RECEIVER_TYPE === 3)
  ok(owner && platform, '机主与平台行都在')
  eq(owner.RECEIVER_USER_ID, OWNER_ID, '机主收款人=9001')
  eq(platform.RECEIVER_USER_ID, 0, '平台行恒 0 哨兵（NULL 不参与唯一约束）')
  eq(owner.SPLIT_RATE_SNAP, String(RATE.waterOwner), '机主比例快照=7000')
  eq(platform.SPLIT_RATE_SNAP, 'REMAINDER', '平台行快照=REMAINDER')
  const base = Number(owner.SPLIT_AMOUNT) + Number(platform.SPLIT_AMOUNT)
  ok(base > 0, '基数为正')
  ok(base < Number(order.ORDER_AMOUNT), '基数=实扣（预扣-退差）应小于订单金额', `${base} vs ${order.ORDER_AMOUNT}`)
  eq(owner.SPLIT_AMOUNT, Math.floor(base * RATE.waterOwner / 10000), '机主=基数×70% 向下取整')

  await pollUntil('分账 Worker 应把两行推到已分账', async () => {
    const settled = await one('SELECT COUNT(*) AS c FROM ws_split_record WHERE ORDER_ID=? AND SPLIT_STATUS=2 AND SPLIT_TIME IS NOT NULL', [order.ID])
    return Number(settled.c) === 2 ? settled : null
  }, 75000, 3000)
  const flow = await one('SELECT * FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`INCOME:${owner.ID}`])
  ok(flow, '机主入账流水在位（INCOME:<splitId>）')
  eq(flow.USER_ID, OWNER_ID, '入账人=机主')
  eq(flow.AMOUNT_FEN, owner.SPLIT_AMOUNT, '入账金额=机主分账额')
  eq(flow.ORDER_NO, order.ORDER_NO, '流水回溯到原始订单号')
  const platFlow = await one('SELECT COUNT(*) AS c FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`INCOME:${platform.ID}`])
  eq(platFlow.c, 0, '平台行不入个人钱包')
  const account = await one('SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  ok(Number(account.BALANCE_FEN) >= Number(owner.SPLIT_AMOUNT), '机主收益账户已入账')
  ctx.waterOwnerSplit = owner
  ev.push(`基数 ${base}（订单 ${order.ORDER_AMOUNT} 扣退差），机主 ${owner.SPLIT_AMOUNT} + 平台 ${platform.SPLIT_AMOUNT}`)
})

scenario('S2', '配送链三方分账（D-419 分线）：签收即完成→水费按售水线70%、配送费按配送线60%/30%各自计算，平台吃两线余数', async (ev) => {
  const card = await ensureOwnerCard(8000)
  const created = await apiOk('/mini/delivery/order/create', {
    requestId: rid(),
    cardId: String(card.ID),
    stationId: STATION_ID,
    waterTypeId: '1',
    containerSpec: '20L桶',
    deliveryCount: 2,
    planReturnCount: 0,
    receiveAddress: '验收专区1号',
    receivePhone: OWNER_PHONE,
    deliveryMode: 1,
    payWay: 2,
  }, ctx.owner)
  const head = created && created.order && created.order.order
  ok(head && head.orderNo, '配送创单应返回订单详情')
  const order = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [head.orderNo])
  const task = await pollUntil('配送任务应生成', () =>
    one('SELECT TASK_NO, VERSION FROM ws_delivery_task WHERE ORDER_ID=?', [order.ID]), 15000)
  await deliverAndSign(task)

  const rows = await splitRowsOf(order.ID)
  eq(rows.length, 3, '配送恰三行：机主+配送员+平台')
  const owner = rows.find(r => r.RECEIVER_TYPE === 1)
  const courier = rows.find(r => r.RECEIVER_TYPE === 2)
  const platform = rows.find(r => r.RECEIVER_TYPE === 3)
  eq(owner.RECEIVER_USER_ID, OWNER_ID, '机主=站归属 9001')
  eq(courier.RECEIVER_USER_ID, COURIER_ID, '配送员=签收人 9003')
  const base = Number(order.ORDER_AMOUNT)
  // D-419：配送费与水费分线——水费按售水线机主比例、配送费按配送线机主/配送员比例
  const taskAmounts = await one('SELECT WATER_AMOUNT, DELIVERY_FEE FROM ws_delivery_task WHERE ORDER_ID=?', [order.ID])
  const waterFen = Number(taskAmounts.WATER_AMOUNT)
  const feeFen = Number(taskAmounts.DELIVERY_FEE)
  eq(waterFen + feeFen, base, '任务行水费+配送费=整单金额（创单冻结恒等式）')
  eq(Number(owner.SPLIT_AMOUNT) + Number(courier.SPLIT_AMOUNT) + Number(platform.SPLIT_AMOUNT), base, '三行合计=整单金额')
  eq(Number(owner.SPLIT_AMOUNT), Math.floor(waterFen * RATE.waterOwner / 10000) + Math.floor(feeFen * RATE.deliveryOwner / 10000), '机主=水费线70%+配送费线60% 各自向下取整')
  eq(Number(courier.SPLIT_AMOUNT), Math.floor(feeFen * RATE.deliveryCourier / 10000), '配送员=配送费线30% 向下取整')
  eq(owner.SPLIT_RATE_SNAP, `W${RATE.waterOwner}+D${RATE.deliveryOwner}`, '机主快照记两线比例')
  eq(courier.SPLIT_RATE_SNAP, `D${RATE.deliveryCourier}`, '配送员快照记配送费线比例')
  eq(platform.SPLIT_RATE_SNAP, 'REMAINDER', '平台吃余数')

  await pollUntil('配送分账应结算并给配送员入账', async () => {
    const flow = await one('SELECT ID FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`INCOME:${courier.ID}`])
    return flow || null
  }, 75000, 3000)
  const courierAccount = await one('SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?', [COURIER_ID])
  ok(Number(courierAccount.BALANCE_FEN) >= Number(courier.SPLIT_AMOUNT), '配送员收益账户已入账')
  ctx.deliveryOrder = order
  ctx.deliverySplits = { owner, courier, platform }
  ev.push(`基数 ${base}：机主 ${owner.SPLIT_AMOUNT} / 配送员 ${courier.SPLIT_AMOUNT} / 平台 ${platform.SPLIT_AMOUNT}`)
})

scenario('S3', '口径边界：充值单不分账；水量支付(payWay3)零金额不分账', async (ev) => {
  const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(ctx.cardId), requestId: rid() }, ctx.owner)
  await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, ctx.owner)
  const rechargeOrder = await one('SELECT ID, ORDER_STATUS FROM ws_order WHERE ORDER_NO=?', [top.orderNo])
  ok(Number(rechargeOrder.ORDER_STATUS) >= 2, '充值单应完成')
  const rechargeSplits = await splitRowsOf(rechargeOrder.ID)
  eq(rechargeSplits.length, 0, '充值单零分账行（基数=消费订单，充值在计提口径之外）')

  const mlOrder = await waterOrderThrough(ctx.cardId, 3)
  eq(mlOrder.ORDER_AMOUNT, 0, '水量支付订单金额恒 0')
  const mlSplits = await splitRowsOf(mlOrder.ID)
  eq(mlSplits.length, 0, '水量支付零分账行（水费已在充值环节结算）')
  ev.push(`充值单 ${top.orderNo} 与水量单 ${mlOrder.ORDER_NO} 均零分账`)
})

scenario('S4', '比例版本生效语义：新版本只影响此后新单，历史快照不追溯，过去时点拒绝', async (ev) => {
  const newId = await apiOk('/finance/config/create', { productLine: 1, receiverType: 1, splitRate: 6500, remark: '验收演练版本' }, ctx.ops)
  ok(newId, '新版本应写入')
  const order = await waterOrderThrough(ctx.cardId, 2)
  const owner = await one('SELECT * FROM ws_split_record WHERE ORDER_ID=? AND RECEIVER_TYPE=1', [order.ID])
  eq(owner.SPLIT_RATE_SNAP, '6500', '新单吃新版本快照')
  const oldOwner = await one('SELECT SPLIT_RATE_SNAP FROM ws_split_record WHERE ID=?', [ctx.waterOwnerSplit.ID])
  eq(oldOwner.SPLIT_RATE_SNAP, '7000', '历史行快照不追溯')

  await apiFail('/finance/config/create', { productLine: 1, receiverType: 1, splitRate: 7000, effectTime: '20200101000000' }, ctx.ops)
  await apiFail('/finance/config/create', { productLine: 1, receiverType: 1, splitRate: 10001 }, ctx.ops)
  // 复位到演示值 7000：同秒撞 uk_split_config_version，等 1.2s 保证生效时点不同
  await sleep(1200)
  await apiOk('/finance/config/create', { productLine: 1, receiverType: 1, splitRate: 7000, remark: '验收复位' }, ctx.ops)
  const page = await apiOk('/finance/config/page', { productLine: 1, current: 1, size: 100 }, ctx.ops)
  ok((page.list || []).filter(item => item.receiverType === 1).length >= 3, '售水机主线含历史版本可审计')
  ctx.rawParts.push(JSON.stringify(page))
  ev.push(`版本 ${newId} 生效于新单（6500），历史行保持 7000；过去时点/超万分比双拒绝`)
})

scenario('S5', '库层资金铁闸：分账行/入账流水唯一键防重放，结算恰一次', async (ev) => {
  const s = ctx.waterOwnerSplit
  await expectDupKey(
    `INSERT INTO ws_split_record (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, RECEIVER_TYPE, RECEIVER_USER_ID, SPLIT_AMOUNT, SPLIT_RATE_SNAP, SPLIT_STATUS)
     VALUES (0,1,'20260101000000',1,'20260101000000',?,?,?,1,'7000',1)`,
    [s.ORDER_ID, 1, OWNER_ID],
    '同单同收款方重放插入应撞 uk_split_order_receiver',
  )
  await expectDupKey(
    `INSERT INTO ws_income_flow (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, USER_ID, FLOW_TYPE, AMOUNT_FEN, AFTER_FEN, BIZ_IDEMPOTENCY_KEY)
     VALUES (0,1,'20260101000000',1,'20260101000000',?,1,1,1,?)`,
    [OWNER_ID, `INCOME:${s.ID}`],
    '同分账行重复入账应撞 uk_income_flow_biz_key',
  )
  const dup = await q(
    `SELECT SPLIT_ID FROM ws_income_flow WHERE FLOW_TYPE=1 GROUP BY SPLIT_ID HAVING COUNT(*) > 1`,
  )
  eq(dup.length, 0, '全库无一分账行被入账两次')
  const settled = await one('SELECT SPLIT_STATUS FROM ws_split_record WHERE ID=?', [s.ID])
  eq(settled.SPLIT_STATUS, 2, '已分账态不回流')
  ev.push('两把唯一键闸 + 结算恰一次全库扫描通过')
})

scenario('S6', '收益钱包三方一致：API==账户==逐笔流水，非收益人零数据，响应零敏感', async (ev) => {
  const { parsed, rawText } = await walletOf(ctx.owner)
  eq(parsed.code, 0, '机主钱包应可读')
  const wallet = parsed.data
  const account = await one('SELECT BALANCE_FEN, FROZEN_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  eq(wallet.balanceFen, account.BALANCE_FEN, '钱包余额==账户表')
  eq(wallet.frozenFen, account.FROZEN_FEN, '冻结==账户表')
  const sum = await one('SELECT COALESCE(SUM(AMOUNT_FEN),0) AS s, COUNT(*) AS c FROM ws_income_flow WHERE USER_ID=?', [OWNER_ID])
  eq(Number(account.BALANCE_FEN) + Number(account.FROZEN_FEN), Number(sum.s), '账户总额==流水合计（逐笔连续）')
  eq((wallet.flows || []).length, Math.min(50, Number(sum.c)), '流水条数==DB（近50条口径）')
  eq(wallet.evidenceMode, 'real', '证据模式=真实库账务')

  const member = await walletOf(ctx.member)
  eq(member.parsed.code, 0, '无收益用户钱包可读')
  eq(member.parsed.data.balanceFen, 0, '无收益账户呈现零')
  eq((member.parsed.data.flows || []).length, 0, '无流水')
  ok(!/(?<![\da-f])1[3-9]\d{9}(?![\da-f])/i.test(rawText), '钱包响应不含手机号')
  ok(!/openid/i.test(rawText), '钱包响应不含 openid')
  ctx.rawParts.push(rawText, member.rawText)
  ev.push(`机主余额 ${wallet.balanceFen} 三方一致；流水 ${sum.c} 条`)
})

scenario('S7', '提现骨架：冻结→驳回解冻全链幂等，余额不足拒绝，无真实出金', async (ev) => {
  const before = await one('SELECT BALANCE_FEN, FROZEN_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  const wid = rid()
  const amount = 300
  ok(Number(before.BALANCE_FEN) >= amount, '前置：机主余额足够发起提现')
  await apiOk('/mini/owner/wallet/withdraw', { requestId: wid, amountFen: amount }, ctx.owner)
  let account = await one('SELECT BALANCE_FEN, FROZEN_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  eq(account.BALANCE_FEN, Number(before.BALANCE_FEN) - amount, '余额转出')
  eq(account.FROZEN_FEN, Number(before.FROZEN_FEN) + amount, '冻结转入')
  // 同体重放需越过 @RepeatSubmit 5 秒防重窗（节流是节流，幂等是幂等，两层都要验）
  await sleep(5600)
  await apiOk('/mini/owner/wallet/withdraw', { requestId: wid, amountFen: amount }, ctx.owner)
  account = await one('SELECT BALANCE_FEN, FROZEN_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  eq(account.FROZEN_FEN, Number(before.FROZEN_FEN) + amount, '同请求号重放零重复冻结')
  const freezeCnt = await one('SELECT COUNT(*) AS c FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`WITHDRAW:${wid}`])
  eq(freezeCnt.c, 1, '冻结流水恰一条')
  await apiFail('/mini/owner/wallet/withdraw', { requestId: rid(), amountFen: Number(account.BALANCE_FEN) + 999999 }, ctx.owner)

  await apiOk('/finance/withdraw/reject', { userId: OWNER_ID, amountFen: amount, requestId: wid }, ctx.ops)
  account = await one('SELECT BALANCE_FEN, FROZEN_FEN FROM ws_income_account WHERE USER_ID=?', [OWNER_ID])
  eq(account.BALANCE_FEN, before.BALANCE_FEN, '驳回后余额复原')
  eq(account.FROZEN_FEN, before.FROZEN_FEN, '冻结清零')
  await sleep(5600)
  await apiOk('/finance/withdraw/reject', { userId: OWNER_ID, amountFen: amount, requestId: wid }, ctx.ops)
  const rejCnt = await one('SELECT COUNT(*) AS c FROM ws_income_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`WITHDRAW-REJ:${wid}`])
  eq(rejCnt.c, 1, '驳回流水恰一条（重放幂等）')
  const wallet = await walletOf(ctx.owner)
  eq(wallet.parsed.data.frozenFen, 0, '小程序侧冻结归零')
  ev.push(`冻结 ${amount} → 驳回解冻，四次调用两条流水，余额分毫不差`)
})

scenario('S8', '日对账平账：真实链当日五维核对零差异', async (ev) => {
  const task = await apiOk('/finance/reconcile/run', { bizDate: ctx.bizDate }, ctx.ops)
  eq(task.taskStatus, 2, '当日应平账')
  eq(task.diffTotal, 0, '差异数=0')
  ok(Number(task.checkTotal) > 0, '核对项应覆盖真实链数据', `checkTotal=${task.checkTotal}`)
  const diffPage = await apiOk('/finance/reconcile/diffPage', { bizDate: ctx.bizDate, current: 1, size: 10 }, ctx.ops)
  eq(diffPage.total, 0, '差异台账空')
  ev.push(`账期 ${ctx.bizDate} 五维 ${task.checkTotal} 项核对全平`)
})

scenario('S9', '人为差错检出：单边账+金额篡改被抓，重跑整批替换，修复后回归平账', async (ev) => {
  const loneNo = `ACC-SET-LONE-${Date.now()}`
  await q(
    `INSERT INTO ws_payment (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_ID, ORDER_NO, PAY_AMOUNT, PAY_STATUS, PAY_SOURCE, PAY_EXPIRE_TIME)
     VALUES (0,1,?,1,?,0,?,500,2,2,?)`,
    [`${ctx.bizDate}120000`, `${ctx.bizDate}120000`, loneNo, `${ctx.bizDate}120000`],
  )
  const platId = ctx.deliverySplits.platform.ID
  await q('UPDATE ws_split_record SET SPLIT_AMOUNT = SPLIT_AMOUNT + 1 WHERE ID=?', [platId])

  await sleep(5600)
  const task = await apiOk('/finance/reconcile/run', { bizDate: ctx.bizDate }, ctx.ops)
  eq(task.taskStatus, 3, '应判有差异')
  const diffs = await apiOk('/finance/reconcile/diffPage', { bizDate: ctx.bizDate, current: 1, size: 100 }, ctx.ops)
  const lone = (diffs.list || []).find(d => d.checkDimension === 'payment-fact' && String(d.bizKey).includes(loneNo))
  ok(lone && lone.diffType === 1, '单边账（支付无订单）被抓，分类=1')
  const tampered = (diffs.list || []).find(d => d.checkDimension === 'split-sum' && d.bizKey === ctx.deliveryOrder.ORDER_NO)
  ok(tampered && tampered.diffType === 2, '分账合计≠基数被抓，分类=2')

  const firstBatch = await one('SELECT COUNT(*) AS c, MAX(ID) AS m FROM ws_reconcile_diff WHERE BIZ_DATE=?', [ctx.bizDate])
  await sleep(5600)
  await apiOk('/finance/reconcile/run', { bizDate: ctx.bizDate }, ctx.ops)
  const secondBatch = await one('SELECT COUNT(*) AS c, MIN(ID) AS m FROM ws_reconcile_diff WHERE BIZ_DATE=?', [ctx.bizDate])
  eq(secondBatch.c, firstBatch.c, '重跑差异数不翻倍（整批替换非追加）')
  ok(Number(secondBatch.m) > Number(firstBatch.m), '重跑后是新一批差异行')

  await q('DELETE FROM ws_payment WHERE ORDER_NO=?', [loneNo])
  await q('UPDATE ws_split_record SET SPLIT_AMOUNT = SPLIT_AMOUNT - 1 WHERE ID=?', [platId])
  await sleep(5600)
  const repaired = await apiOk('/finance/reconcile/run', { bizDate: ctx.bizDate }, ctx.ops)
  eq(repaired.taskStatus, 2, '修复后回归平账')
  eq(repaired.diffTotal, 0, '差异清零')
  ev.push(`注入 2 类差错均检出（${firstBatch.c} 行），重跑替换，修复回平`)
})

scenario('S10', '邀请码与归因：确定性派生、一次性绑定、防自邀，新订单快照归因', async (ev) => {
  const code = await apiOk('/mini/invite/my-code', {}, ctx.owner)
  ok(/^IV[0-9A-F]{8}$/.test(code), '邀请码 IV+8 位十六进制', code)
  const again = await apiOk('/mini/invite/my-code', {}, ctx.owner)
  eq(again, code, '重复获取恒同码（确定性派生）')

  await apiOk('/mini/invite/bind', { inviteCode: ` ${code.toLowerCase()} `, requestId: rid() }, ctx.member)
  const member = await one('SELECT REFERRER_USER_ID, PROMO_CODE FROM ws_user WHERE ID=?', [MEMBER_ID])
  eq(member.REFERRER_USER_ID, OWNER_ID, '绑定成功（trim+大小写归一）')
  eq(member.PROMO_CODE, code, '来源码留痕')
  await apiFail('/mini/invite/bind', { inviteCode: code, requestId: rid() }, ctx.owner)
  await apiFail('/mini/invite/bind', { inviteCode: code, requestId: rid() }, ctx.member)
  await apiFail('/mini/invite/bind', { inviteCode: 'IVZZZZZZZZ', requestId: rid() }, ctx.courier)
  const courier = await one('SELECT REFERRER_USER_ID FROM ws_user WHERE ID=?', [COURIER_ID])
  ok(courier.REFERRER_USER_ID === null, '无效码不产生绑定')

  const created = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.member)
  const memberOrder = await one('SELECT REFERRER_USER_ID FROM ws_order WHERE ORDER_NO=?', [created.orderNo])
  eq(memberOrder.REFERRER_USER_ID, OWNER_ID, '绑定后的新订单快照归因到推荐人')
  const ownerOrder = await one('SELECT REFERRER_USER_ID FROM ws_order WHERE ID=?', [ctx.waterOrder.ID])
  ok(ownerOrder.REFERRER_USER_ID === null, '未绑定用户订单归因为空（不回溯不虚构）')
  ctx.rawParts.push(JSON.stringify(code))
  ev.push(`码 ${code} 绑定一次性成立；9002 新单归因 9001，自邀/重绑/无效码三拒绝`)
})

scenario('S11', '赠卡发放：幂等恰一张、必带有效期不可退、不占名额、不可充值', async (ev) => {
  const requestId = rid()
  const cardId = await apiOk('/user/card/issueGift', {
    requestId,
    userId: COURIER_ID,
    grantFen: 500,
    grantMl: 10000,
    expireDays: 30,
    remark: '验收演练赠卡',
  }, ctx.ops)
  const card = await one('SELECT * FROM ws_card WHERE ID=?', [cardId])
  ok(/^GC[0-9A-F]{18}$/.test(card.CARD_NO), '卡号 GC+18 位确定性派生', card.CARD_NO)
  eq(card.USER_ID, COURIER_ID, '收卡人正确')
  ok(card.EXPIRE_TIME, '赠卡必带有效期（D-213）')
  eq(`${card.BALANCE_AMOUNT}|${card.BALANCE_ML}`, '500|10000', '权益如实入卡')
  const flow = await one('SELECT COUNT(*) AS c FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`GIFT:${requestId}`])
  eq(flow.c, 1, '发放流水恰一条')
  const batch = await one('SELECT CONCAT(SOURCE_TYPE,"|",BATCH_STATUS,"|",PAY_AMOUNT_FEN) AS b FROM ws_card_entitlement_batch WHERE CARD_ID=?', [cardId])
  eq(batch.b, '4|6|0', '权益批次：来源=运营赠卡、恒不可退、实付 0')
  const audit = await one('SELECT COUNT(*) AS c FROM ws_domain_event WHERE BIZ_IDEMPOTENCY_KEY=?', [`GIFT_ISSUE:${requestId}`])
  eq(audit.c, 1, '关键动作审计留痕')

  const replay = await apiOk('/user/card/issueGift', { requestId, userId: COURIER_ID, grantFen: 500, grantMl: 10000, expireDays: 30 }, ctx.ops)
  eq(replay, cardId, '同请求号重放返回同一张卡')
  const cnt = await one('SELECT COUNT(*) AS c FROM ws_card WHERE USER_ID=? AND CARD_NO LIKE "GC%"', [COURIER_ID])
  eq(cnt.c, 1, '绝不发第二张')
  await apiFail('/user/card/issueGift', { requestId: rid(), userId: COURIER_ID, expireDays: 30 }, ctx.ops)
  await apiFail('/user/card/issueGift', { requestId: rid(), userId: COURIER_ID, grantFen: 100, expireDays: 4000 }, ctx.ops)

  const live = await one('SELECT COUNT(*) AS c FROM ws_card WHERE USER_ID=? AND DATA_STATUS=0 AND EXPIRE_TIME IS NULL', [COURIER_ID])
  eq(live.c, 0, '赠卡不计入一人一卡名额口径')
  const first = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.courier)
  ok(first.orderNo, '持赠卡用户仍可首购正式卡（名额未被占用）')
  const gate = await apiFail('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(cardId), requestId: rid() }, ctx.courier)
  ok(String(gate.msg).includes('赠卡'), '赠卡充值闸门关闭（甲方确认前 fail-closed）', gate.msg)
  ev.push(`赠卡 ${card.CARD_NO} 恰一张；名额未占（首购放行）；充值闸拒绝=「${gate.msg}」`)
})

scenario('S12', '主动查单：超期未付单由权威查单事实驱动关闭，不本地改状态', async (ev) => {
  const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(ctx.cardId), requestId: rid() }, ctx.owner)
  const before = await one('SELECT PAY_STATUS FROM ws_payment WHERE ORDER_NO=?', [top.orderNo])
  eq(before.PAY_STATUS, 1, '前置：待支付')
  // 构造真实超期：孤立篡改 PAY_EXPIRE 会被关单事务的快照一致性校验拒绝并转人工
  // （那是资金铁律的正确 fail-closed，round1 实抓）。此处把创建时间/快照采集时间/
  // 付款截止三者按同一位移前移 60 分钟——窗口关系保持冻结算法原样，只是发生在过去
  const shifted = minusMinutes(top.createTime, 60)
  const expiredAt = minusMinutes(top.createTime, 30)
  await q('UPDATE ws_order SET CREATE_TIME=?, PACKAGE_SNAP=REPLACE(PACKAGE_SNAP, ?, ?) WHERE ORDER_NO=?', [shifted, `"capturedTime":"${top.createTime}"`, `"capturedTime":"${shifted}"`, top.orderNo])
  await q('UPDATE ws_payment SET CREATE_TIME=?, PAY_EXPIRE_TIME=? WHERE ORDER_NO=?', [shifted, expiredAt, top.orderNo])
  const closed = await pollUntil('查单 Worker 应关闭超期单（60s 周期）', async () => {
    const row = await one(
      `SELECT p.PAY_STATUS, o.ORDER_STATUS FROM ws_payment p JOIN ws_order o ON o.ORDER_NO=p.ORDER_NO WHERE p.ORDER_NO=?`,
      [top.orderNo],
    )
    return row && row.PAY_STATUS !== 1 ? row : null
  }, 100000, 5000)
  eq(closed.PAY_STATUS, 4, '支付单已关闭')
  eq(closed.ORDER_STATUS, 5, '订单已取消')
  ev.push(`超期单 ${top.orderNo} 由查单事实驱动关至 4/5`)
})

scenario('S13', '鉴权门：财务面未登录/错身份拒绝，高风险动作参数闸有效', async (ev) => {
  const unauthFinance = await api('/finance/split/page', { current: 1, size: 10 })
  ok(unauthFinance.parsed.code !== 0, '/finance 未登录拒绝')
  const unauthWallet = await api('/mini/owner/wallet', {})
  ok(unauthWallet.parsed.code !== 0, '钱包未登录拒绝')
  const miniOnFinance = await api('/finance/split/page', { current: 1, size: 10 }, ctx.owner)
  ok(miniOnFinance.parsed.code !== 0, '小程序会话不得进财务面')
  const unauthInvite = await api('/mini/invite/bind', { inviteCode: 'IVAAAAAAAA' })
  ok(unauthInvite.parsed.code !== 0, '邀请绑定未登录拒绝')
  await apiFail('/finance/reconcile/run', {}, ctx.ops)
  await apiFail('/finance/config/create', { productLine: 1 }, ctx.ops)
  await apiFail('/finance/withdraw/reject', { amountFen: 100, requestId: rid() }, ctx.ops)
  ev.push('三面未登录/错身份全拒；缺账期/缺字段/缺收益人三闸生效')
})

scenario('S14', '三方一致 + 零敏感：PC 分账面 == SQL == 钱包，全量产物无手机号/openid', async (ev) => {
  const page = await api('/finance/split/page', { current: 1, size: 100 }, ctx.ops)
  eq(page.parsed.code, 0, '分账明细可读')
  const dbCnt = await one('SELECT COUNT(*) AS c FROM ws_split_record')
  eq(page.parsed.data.total, dbCnt.c, 'PC 分账总数 == SQL')
  const byOrder = await apiOk('/finance/split/page', { orderId: ctx.waterOrder.ID, current: 1, size: 10 }, ctx.ops)
  eq(byOrder.total, 2, '按订单过滤纯净（S1 水单恰两行）')
  const taskPage = await apiOk('/finance/reconcile/taskPage', { current: 1, size: 10 }, ctx.ops)
  ok(Number(taskPage.total) >= 1, '对账批任务在列')

  for (const [label, userId, token] of [['机主', OWNER_ID, ctx.owner], ['配送员', COURIER_ID, ctx.courier]]) {
    const wallet = await walletOf(token)
    const account = await one('SELECT BALANCE_FEN FROM ws_income_account WHERE USER_ID=?', [userId])
    const last = await one('SELECT AFTER_FEN FROM ws_income_flow WHERE USER_ID=? ORDER BY ID DESC LIMIT 1', [userId])
    eq(wallet.parsed.data.balanceFen, account.BALANCE_FEN, `${label}钱包==账户`)
    eq(account.BALANCE_FEN, last.AFTER_FEN, `${label}账户==末笔流水 AFTER`)
    ctx.rawParts.push(wallet.rawText)
  }
  ctx.rawParts.push(page.rawText, JSON.stringify(taskPage))
  const raw = ctx.rawParts.join('\n')
  ok(!/(?<![\da-f])1[3-9]\d{9}(?![\da-f])/i.test(raw), '全量采样响应不含手机号')
  ok(!/openid/i.test(raw), '全量采样响应不含 openid')
  ev.push(`分账 ${dbCnt.c} 行三方一致；两收款方账本连续；采样 ${ctx.rawParts.length} 段零敏感`)
})

// ---------------------------------------------------------------------------
// 环境指纹核验
// ---------------------------------------------------------------------------
function lsofPort(port) {
  return new Promise((resolve) => {
    execFile('lsof', ['-ti', `:${port}`], (error, stdout) => {
      if (error && !String(error.message || '').includes('Command failed')) {
        console.warn(`lsof 查询异常（按无监听处理）：${error.message}`)
      }
      resolve(String(stdout || '').trim().split('\n').filter(Boolean))
    })
  })
}

async function verifyEnvironment() {
  const fingerprint = {
    base: BASE,
    dbHost: DB_CONFIG.host,
    dbPort: DB_CONFIG.port,
    dbName: DB_CONFIG.database,
    round: ROUND,
    seedSentinel: false,
    backendPidVerified: false,
    jarSha256: null,
  }
  const owner = await one('SELECT USER_PHONE FROM ws_user WHERE ID = 9001')
  ok(owner && owner.USER_PHONE === OWNER_PHONE, '种子哨兵缺失（先 acc-env.sh rebuild）')
  const configs = await one('SELECT COUNT(*) AS c FROM ws_split_config WHERE ID BETWEEN 9801 AND 9805')
  eq(configs.c, 5, 'E2E-08 分账配置种子 9801~9805 缺失')
  // 脏库拒跑：上一轮会留下 S4 的新比例版本与分账行，直接跑会让 S1 的 7000 期望失真
  const waterVersions = await one('SELECT COUNT(*) AS c FROM ws_split_config WHERE PRODUCT_LINE=1')
  eq(waterVersions.c, 2, '售水线必须恰两版种子配置（有多余版本=脏库，需 rebuild）')
  const splitRows = await one('SELECT COUNT(*) AS c FROM ws_split_record')
  eq(splitRows.c, 0, '分账面必须干净（有历史行=脏库，需 rebuild）')
  fingerprint.seedSentinel = true

  const pids = await lsofPort(BACKEND_PORT)
  ok(pids.length > 0, `端口 ${BACKEND_PORT} 无监听进程`)
  const pidFile = path.join(ACC_ENV_RUN_DIR, 'backend.pid')
  ok(fs.existsSync(pidFile), 'acc-env backend.pid 缺失')
  const recordedPid = fs.readFileSync(pidFile, 'utf8').trim()
  ok(pids.includes(recordedPid), `端口被非 acc-env 进程占用（${pids.join(',')} vs ${recordedPid}）`)
  fingerprint.backendPidVerified = true

  const jar = fs.readdirSync(JAR_GLOB_DIR).find(f => f.endsWith('.jar') && !f.endsWith('.jar.original'))
  ok(jar, 'server/target 缺少构建产物')
  fingerprint.jarSha256 = sha256File(path.join(JAR_GLOB_DIR, jar))
  return fingerprint
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  if (scenarios.length !== 14) {
    console.error(`场景登记数=${scenarios.length}，必须恰为 14；拒绝执行`)
    process.exit(2)
  }
  const mysql = require('mysql2/promise')
  pool = await mysql.createPool({ ...DB_CONFIG, connectionLimit: 4 })

  const envFingerprint = await verifyEnvironment()
  console.log(`环境指纹：base=${envFingerprint.base} db=${envFingerprint.dbHost}:${envFingerprint.dbPort} jar=${(envFingerprint.jarSha256 || '').slice(0, 12)} round=${ROUND}`)

  ctx.owner = await miniLogin(OWNER_PHONE)
  ctx.member = await miniLogin(MEMBER_PHONE)
  ctx.courier = await miniLogin(COURIER_PHONE)
  ctx.ops = await opsLogin(OPS_LOGIN)

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
  await simStopAll()

  const passed = results.filter(r => r.pass).length
  const result = {
    schemaVersion: 1,
    chain: 'E2E-08',
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

  const serialized = JSON.stringify(result, null, 2)
  const forbidden = [
    // 前后非 hex 断言：排除订单号时间戳与 sha256/媒体键 hex 串的假阳性
    // （jar 指纹曾撞出 16591484817，真手机号在文案里恒被非 hex 字符包围）
    { name: '手机号原文', re: /(?<![\da-f])1[3-9]\d{9}(?![\da-f])/i },
    { name: '本机绝对路径', re: /\/(?:Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
  ]
  const runtimeSecrets = [
    { name: '机主 Token', value: ctx.owner && ctx.owner.tokenValue },
    { name: '配送员 Token', value: ctx.courier && ctx.courier.tokenValue },
    { name: '运营 Token', value: ctx.ops && ctx.ops.tokenValue },
    { name: '数据库口令', value: DB_CONFIG.password },
    { name: '运营口令密文', value: OPS_PWD_CIPHER },
  ].filter(item => typeof item.value === 'string' && item.value.length > 0)
  const leaked = [
    ...forbidden.filter(f => f.re.test(serialized)),
    ...runtimeSecrets.filter(secret => serialized.includes(secret.value)),
  ]
  if (leaked.length) {
    console.error(`验收产物含禁止信息，拒绝落盘：${leaked.map(f => f.name).join('、')}`)
    process.exit(3)
  }
  fs.mkdirSync(RUN_DIR, { recursive: true })
  fs.writeFileSync(path.join(RUN_DIR, 'result.json'), `${serialized}\n`)

  console.log(`\nSUMMARY round=${ROUND} scenarios=${passed}/${result.total} ${result.pass ? 'PASS' : 'FAIL'}`)
  console.log(`BATCH_DIR=docs/acceptance/settlement/runs/${BATCH_ID}`)
  await pool.end()
  process.exit(result.pass ? 0 : 1)
}

main().catch(async (error) => {
  console.error('FATAL:', error)
  await simStopAll().catch(() => {})
  if (pool) {
    await pool.end().catch(() => {})
  }
  process.exit(1)
})
