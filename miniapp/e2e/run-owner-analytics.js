/**
 * E2E-06 机主经营与服务固定场景验收（S1～S12 含 S11B，真实 HTTP + 验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已 rebuild（init + migrations + acc-seed）；
 * - 验收后端已 backend-start（本链无监控/凭据时效要求，默认参数即可）；
 * - 设备模拟器由本 runner 按需启停（S2 设备指标场景）；两轮之间必须重新 rebuild。
 *
 * 口径依据（任务书 v1 第三节冻结）：
 * - 金额=订单毛额（分），排除待支付/已取消，退款不冲减、明细状态如实；充值单永不计入；
 * - 双轨归属：订单范围=(站∈名下站)OR(设备∈名下设备) 去重；配送单走站轨；
 * - 周期=varchar(14) 闭区间字符串比较；
 * - 明细零用户身份字段（D-212）。
 *
 * 安全闸（fail-closed）：必须显式 OWNER_ACC_MODE=full；主环境端口/主库端口拒绝；
 * 种子哨兵（含 E2E-06 增量：站 9101 归 9001）；13340 pid 核验；JAR 指纹落盘；
 * 产物污染扫描；场景数必须恰为 13；失败落盘退出码非零。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn, execFile } = require('node:child_process')
const { sha256File } = require('./e1b-core')
const { newBatchId } = require('./report-core')
const { verifyAccBackendContainer } = require('./acc-backend-proof')

if (String(process.env.OWNER_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 OWNER_ACC_MODE=full（本脚本会真实写验收库）')
  process.exit(2)
}
if (!String(process.env.ACC_DB_PASSWORD || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（仓库根 .env）')
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
const ACCEPT_DIR = path.resolve(REPO_ROOT, 'docs/acceptance/owner-analytics')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, 'runs', BATCH_ID)
const SIM = path.resolve(REPO_ROOT, 'tools/device-sim/sim.js')
const PUB = path.resolve(REPO_ROOT, 'tools/device-sim/pub.js')
const JAR_GLOB_DIR = path.resolve(REPO_ROOT, 'server/target')
const ACC_ENV_RUN_DIR = path.resolve(REPO_ROOT, 'deploy/acceptance/.run')
const BROKER = process.env.ACC_BROKER || 'tcp://127.0.0.1:1884'
const BACKEND_PORT = 13340

// 种子固定身份（acc-seed.sql；E2E-06 增量：站 9101 OWNER=9001 → 双轨机主）
const OWNER_PHONE = '13999990001' // 9001：站 9101 + 设备 9201（挂 9101，双轨重叠实例）
const OTHER_PHONE = '13999990002' // 9002：无任何归属（越权对照）
const STATION_MINE = 9101
const DEV1 = { id: 9201, no: 'ACC-DEV-0001' }
const QR_DEV1 = 'ACC-QR-DEV1-O1'
const ACC_PACKAGE_ID = '9501'
const WATER_TYPE_ID = 1

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

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
    return { parsed: JSON.parse(text), rawText: text }
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

async function pollUntil(label, fn, timeoutMs, intervalMs = 2000) {
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

// ---------------------------------------------------------------------------
// 模拟器（仅 S2 设备指标用）
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
    await sleep(600)
  }
  simProcs.delete(deviceNo)
}

async function simStopAll() {
  for (const deviceNo of [...simProcs.keys()]) {
    await simStop(deviceNo)
  }
}

function pub(deviceNo, kind, payload) {
  return new Promise((resolve, reject) => {
    execFile('node', [PUB, deviceNo, kind, JSON.stringify(payload)], {
      env: { ...process.env, BROKER },
      timeout: 15000,
    }, (error, stdout, stderr) => {
      if (error) {
        reject(new CheckError(`pub ${kind} 失败：${stderr || error.message}`))
        return
      }
      resolve(stdout)
    })
  })
}

async function miniLogin(phone) {
  const data = await apiOk('/mini/test-login/by-phone', { phone })
  ok(data && data.tokenName && data.tokenValue, '测试登录应返回 token')
  return { tokenName: data.tokenName, tokenValue: data.tokenValue, context: data.accountContext }
}

const overview = token => apiOk('/mini/owner/overview', {}, token)
const txPage = (token, body) => apiOk('/mini/owner/transaction/page', body ?? {}, token)

// ---------------------------------------------------------------------------
// 夹具播种（聚合口径素材；链本体已在 E2E-01/03/04 验收，这里按结果落库）
// ---------------------------------------------------------------------------
function FIXTURE_TIME() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

async function seedOrder(orderNo, type, stationId, deviceId, status, amountFen, actualMl, createTime) {
  await q(
    `INSERT INTO ws_order (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, ORDER_NO, ORDER_TYPE,
       USER_ID, STATION_ID, DEVICE_ID, ORDER_AMOUNT, PAY_WAY, ORDER_STATUS, ACTUAL_ML)
     VALUES (0, 1, ?, 1, ?, ?, ?, 9002, ?, ?, ?, 2, ?, ?)`,
    [createTime, createTime, orderNo, type, stationId, deviceId, amountFen, status, actualMl],
  )
}

// ---------------------------------------------------------------------------
// 场景注册
// ---------------------------------------------------------------------------
const scenarios = []
function scenario(id, name, fn) {
  scenarios.push({ id, name, fn })
}

const ctx = {}

scenario('S1', '无归属账号：不投影经营能力，概览与快照服务端拒绝', async (ev) => {
  const caps = ctx.other.context ? ctx.other.context.capabilities || [] : []
  ok(!caps.includes('OWNER_VIEW'), '无归属账号不得投影 OWNER_VIEW', `实际 ${caps}`)
  const deniedOverview = await apiFail('/mini/owner/overview', {}, ctx.other)
  const deniedTx = await apiFail('/mini/owner/transaction/page', {}, ctx.other)
  ev.push(`capabilities=${caps.join('/')}；overview 拒绝=${deniedOverview.msg}；快照拒绝=${deniedTx.msg}`)
})

scenario('S2', '设备指标与真实状态一致：在线→故障→恢复全程同源', async (ev) => {
  await simStart(DEV1.no)
  await pollUntil('设备心跳转在线', async () => {
    const row = await one('SELECT ONLINE_STATUS FROM ws_device WHERE ID = ?', [DEV1.id])
    return row.ONLINE_STATUS === 1 ? row : null
  }, 30000)
  let data = await overview(ctx.owner)
  eq(data.deviceCount, 1, '名下设备数')
  eq(data.onlineCount, 1, '在线数')
  eq(data.faultCount, 0, '无故障')

  await pub(DEV1.no, 'status', { runStatus: 3, faultCode: 'E003' })
  await pollUntil('故障投影落库', async () => {
    const row = await one('SELECT RUN_STATUS FROM ws_device WHERE ID = ?', [DEV1.id])
    return row.RUN_STATUS === 3 ? row : null
  }, 15000)
  data = await overview(ctx.owner)
  eq(data.faultCount, 1, '故障计数随真实状态')

  await pub(DEV1.no, 'status', { runStatus: 1, faultCode: '' })
  await pollUntil('故障恢复', async () => {
    const row = await one('SELECT RUN_STATUS FROM ws_device WHERE ID = ?', [DEV1.id])
    return row.RUN_STATUS === 1 ? row : null
  }, 15000)
  data = await overview(ctx.owner)
  eq(data.faultCount, 0, '恢复后故障归零')
  ev.push('在线 1/1，故障 0→1→0 全程与设备上行同源')
})

scenario('S3', '站轨可见配送单（DEVICE_ID=NULL 走站轨计入）', async (ev) => {
  await seedOrder('OA-DELIVERY-1', 3, STATION_MINE, null, 2, 700, null, FIXTURE_TIME())
  const page = await txPage(ctx.owner)
  const row = (page.list || []).find(item => item.orderNo === 'OA-DELIVERY-1')
  ok(row, '配送单应经站轨进入机主明细')
  eq(row.orderType, 3, '订单类型为配送')
  ok(row.deviceNo == null, '配送单无设备编号')
  ev.push(`OA-DELIVERY-1 站轨命中（station=${row.stationId}）`)
})

scenario('S4', '双轨去重：站+设备双命中的取水单只计一次', async (ev) => {
  const before = await overview(ctx.owner)
  await seedOrder('OA-DUAL-1', 1, STATION_MINE, DEV1.id, 4, 1000, 5000, FIXTURE_TIME())
  const after = await overview(ctx.owner)
  eq(after.orderCount - before.orderCount, 1, '双轨命中只增一单')
  eq(after.orderAmountFen - before.orderAmountFen, 1000, '金额只计一次')
  const page = await txPage(ctx.owner)
  const rows = (page.list || []).filter(item => item.orderNo === 'OA-DUAL-1')
  eq(rows.length, 1, '明细同单只出现一行')
  ev.push('orderCount +1、金额 +1000、明细 1 行——OR 双命中天然去重')
})

scenario('S5', '充值单永不计入任何机主', async (ev) => {
  const before = await overview(ctx.owner)
  // 充值单 STATION_ID 本为 NULL；显式带站播种验证 TYPE 排除同样生效（防未来带站）
  await seedOrder('OA-RECHARGE-1', 2, STATION_MINE, null, 4, 9999, null, FIXTURE_TIME())
  const after = await overview(ctx.owner)
  eq(after.orderCount, before.orderCount, '充值单不改订单数')
  eq(after.orderAmountFen, before.orderAmountFen, '充值单不改金额')
  const page = await txPage(ctx.owner)
  ok(!(page.list || []).some(item => item.orderNo === 'OA-RECHARGE-1'), '明细不得出现充值单')
  ev.push('TYPE=2 显式排除生效（即便带站也不计入）')
})

scenario('S6', '配送单金额=水费+配送费整额计入站轨', async (ev) => {
  const before = await overview(ctx.owner)
  await seedOrder('OA-DELIVERY-2', 3, STATION_MINE, null, 2, 1500, null, FIXTURE_TIME())
  const after = await overview(ctx.owner)
  eq(after.orderAmountFen - before.orderAmountFen, 1500, '配送单整额计入')
  ev.push('配送单 1500 分整额进入毛额')
})

scenario('S7', '真实取水链结算口径：实际水量计入；待支付/已取消不计入', async (ev) => {
  // 真实链：扫码→创单（payWay=3 卡水量）→ 模拟器回执 actualMl=4980 → 结算
  const scan = await apiOk('/mini/device/scan/resolve', { rawCode: QR_DEV1 }, ctx.owner)
  const detail = await apiOk('/mini/order/water/create', {
    scanSessionId: scan.scanSessionId,
    cardId: Number(ctx.cardId),
    waterTypeId: WATER_TYPE_ID,
    planMl: 10000,
    payWay: 3,
  }, ctx.owner)
  const orderNo = detail.order.orderNo
  await pollUntil('取水单应结算完成', async () => {
    const row = await one('SELECT ORDER_STATUS, ACTUAL_ML FROM ws_order WHERE ORDER_NO = ?', [orderNo])
    return row && row.ORDER_STATUS === 4 ? row : null
  }, 30000)
  const page = await txPage(ctx.owner)
  const row = (page.list || []).find(item => item.orderNo === orderNo)
  ok(row, '真实取水单进入明细')
  eq(row.actualVolumeMl, 4980, '实际水量按结算值')

  // 待支付/已取消对照（夹具）：均不计入
  const before = await overview(ctx.owner)
  await seedOrder('OA-UNPAID-1', 1, STATION_MINE, DEV1.id, 1, 500, null, FIXTURE_TIME())
  await seedOrder('OA-CANCEL-1', 1, STATION_MINE, DEV1.id, 5, 500, null, FIXTURE_TIME())
  const after = await overview(ctx.owner)
  eq(after.orderCount, before.orderCount, '待支付/已取消不改订单数')
  // 水量支付口径分列：真实链两单均为 payWay=3（水卡水量），金额恒 0、出水量单列披露
  const ovAfter = await overview(ctx.owner)
  ok(Number(ovAfter.prepaidVolumeMl) >= 4980, '水量支付出水量必须单列披露', `实际 ${ovAfter.prepaidVolumeMl}`)
  ev.push(`真实链 ${orderNo} actualMl=4980 计入；待支付/已取消零影响；水量支付单列 ${ovAfter.prepaidVolumeMl}mL`)
})

scenario('S8', '全额退款单按毛额计入且明细状态如实', async (ev) => {
  const before = await overview(ctx.owner)
  await seedOrder('OA-REFUND-1', 1, STATION_MINE, DEV1.id, 7, 400, 2000, FIXTURE_TIME())
  const after = await overview(ctx.owner)
  eq(after.orderCount - before.orderCount, 1, '退款单计入订单数')
  eq(after.orderAmountFen - before.orderAmountFen, 400, '退款单毛额不冲减')
  const page = await txPage(ctx.owner)
  const row = (page.list || []).find(item => item.orderNo === 'OA-REFUND-1')
  eq(row.orderStatus, 7, '明细状态如实=全额退款')
  ev.push('毛额口径：退款 400 分计入，状态 7 可见')
})

scenario('S9', '快照筛选与防护：时间窗/设备筛选/越权拒绝/非法分页拒绝', async (ev) => {
  const filtered = await txPage(ctx.owner, { deviceNo: DEV1.no, current: 1, size: 10 })
  ok((filtered.list || []).every(item => item.deviceNo === DEV1.no), '设备筛选只回该设备订单')
  const foreign = await apiFail('/mini/owner/transaction/page', { deviceNo: 'ACC-DEV-0002' }, ctx.owner)
  const missing = await apiFail('/mini/owner/transaction/page', { deviceNo: 'ACC-DEV-9999' }, ctx.owner)
  eq(foreign.msg, missing.msg, '越权与不存在同文案（存在性不泄露）')
  await apiFail('/mini/owner/transaction/page', { current: 0 }, ctx.owner)
  await apiFail('/mini/owner/transaction/page', { size: 101 }, ctx.owner)
  await apiFail('/mini/owner/transaction/page', { periodStart: '2026-07-31' }, ctx.owner)
  ev.push(`设备筛选 ${filtered.list.length} 行；越权/不存在同文案；0页/101条/坏时间全拒`)
})

scenario('S10', '明细零身份泄露：响应原文不含用户ID/手机号/openid', async (ev) => {
  const { rawText } = await api('/mini/owner/transaction/page', { size: 100 }, ctx.owner)
  ok(!/1[3-9]\d{9}/.test(rawText), '响应不得含手机号')
  ok(!/openid/i.test(rawText), '响应不得含 openid')
  ok(!/"userId"|"USER_ID"|"userPhone"/i.test(rawText), '响应不得含用户身份键名')
  ev.push(`原文扫描 ${rawText.length} 字节，零身份字段`)
})

scenario('S11', '周期边界与「全部」语义：闭区间精确 + 不传周期返回全量', async (ev) => {
  // 「全部」档必须真全量：曾把空周期补成近7日缺省，导致超窗订单在 UI 上无路可查
  await seedOrder('OA-ANCIENT-1', 1, STATION_MINE, DEV1.id, 4, 100, 500, '20200101000000')
  const unbounded = await txPage(ctx.owner, { current: 1, size: 100 })
  ok((unbounded.list || []).some(item => item.orderNo === 'OA-ANCIENT-1'), '不传周期必须返回全量（含 2020 年的历史单）')
  ev.push(`不传周期返回 ${unbounded.total} 单（含历史单 OA-ANCIENT-1）`)
})

scenario('S11B', '周期闭区间边界：恰好命中/差一秒不命中', async (ev) => {
  await seedOrder('OA-EDGE-1', 1, STATION_MINE, DEV1.id, 4, 100, 500, '20260725000000')
  const hit = await txPage(ctx.owner, { periodStart: '20260725000000', periodEnd: '20260725000000' })
  eq(hit.total, 1, '闭区间恰好命中')
  eq(hit.list[0].orderNo, 'OA-EDGE-1', '命中的正是边界单')
  const miss = await txPage(ctx.owner, { periodStart: '20260725000001', periodEnd: '20260726000000' })
  eq(miss.total, 0, '差一秒不命中')
  ev.push('varchar(14) 闭区间字符串比较边界正确')
})

scenario('S12', '三方一致 + 范围投影：概览 == SQL 直查 == 快照合计；ownerScope 下发', async (ev) => {
  const data = await overview(ctx.owner)
  // SQL 直查（与服务端同口径重写一遍，独立验证而非同源复读）
  const agg = await one(
    `SELECT COUNT(*) AS cnt, IFNULL(SUM(ORDER_AMOUNT),0) AS amt, IFNULL(SUM(ACTUAL_ML),0) AS ml
     FROM ws_order
     WHERE ORDER_TYPE <> 2 AND ORDER_STATUS NOT IN (1,5) AND DATA_STATUS = 0
       AND CREATE_TIME BETWEEN ? AND ?
       AND (STATION_ID = ? OR DEVICE_ID = ?)`,
    [data.periodStart, data.periodEnd, STATION_MINE, DEV1.id],
  )
  eq(data.orderCount, agg.cnt, '概览订单数 == SQL 直查')
  eq(data.orderAmountFen, agg.amt, '概览金额 == SQL 直查')
  eq(data.actualVolumeMl, agg.ml, '概览水量 == SQL 直查')
  // 快照全量合计（size 上限内翻页）
  let total = 0
  let amount = 0
  let current = 1
  for (;;) {
    const page = await txPage(ctx.owner, { periodStart: data.periodStart, periodEnd: data.periodEnd, current, size: 100 })
    for (const row of page.list || []) {
      total += 1
      amount += Number(row.orderAmountFen || 0)
    }
    if ((page.list || []).length < 100) {
      break
    }
    current += 1
  }
  eq(total, data.orderCount, '快照行数合计 == 概览订单数')
  eq(amount, data.orderAmountFen, '快照金额合计 == 概览金额')
  // ownerScope 投影：登录上下文携带站/设备
  const scope = ctx.owner.context && ctx.owner.context.ownerScope
  ok(scope && scope.stationIds.includes(String(STATION_MINE)) && scope.deviceNos.includes(DEV1.no), 'ownerScope 应含名下站与设备', JSON.stringify(scope))
  ev.push(`三方一致：${data.orderCount} 单 / ${data.orderAmountFen} 分 / ${data.actualVolumeMl} mL；scope=${JSON.stringify(scope)}`)
})

// ---------------------------------------------------------------------------
// 环境指纹核验
// ---------------------------------------------------------------------------
function lsofPort(port) {
  return new Promise((resolve) => {
    execFile('lsof', ['-ti', `:${port}`], (error, stdout) => {
      // lsof 无匹配时以非零退出：这不是故障，等价于"端口无监听"，返回空列表由调用方判定
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
  const owner = await one('SELECT ID, USER_PHONE FROM ws_user WHERE ID = 9001')
  const station = await one('SELECT OWNER_USER_ID FROM ws_station WHERE ID = ?', [STATION_MINE])
  const device = await one('SELECT OWNER_USER_ID FROM ws_device WHERE ID = ?', [DEV1.id])
  const idx = await one(
    'SELECT COUNT(DISTINCT INDEX_NAME) AS c FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = \'ws_order\' AND INDEX_NAME = \'idx_order_station_time\'',
  )
  ok(owner && owner.USER_PHONE === OWNER_PHONE, '种子哨兵缺失（先 acc-env.sh rebuild）')
  ok(station && String(station.OWNER_USER_ID) === '9001', '站 9101 必须归属 9001（E2E-06 种子）')
  ok(device && String(device.OWNER_USER_ID) === '9001', '设备 9201 必须归属 9001')
  ok(idx && Number(idx.c) === 1, 'idx_order_station_time 缺失（迁移未跑）')
  fingerprint.seedSentinel = true

  if (process.env.ACC_BACKEND_CONTAINER) {
    fingerprint.backendContainerVerified = verifyAccBackendContainer(process.env.ACC_BACKEND_CONTAINER, BACKEND_PORT)
  }
  else {
    const pids = await lsofPort(BACKEND_PORT)
    ok(pids.length > 0, `端口 ${BACKEND_PORT} 无监听进程`)
    const pidFile = path.join(ACC_ENV_RUN_DIR, 'backend.pid')
    ok(fs.existsSync(pidFile), 'acc-env backend.pid 缺失')
    const recordedPid = fs.readFileSync(pidFile, 'utf8').trim()
    ok(pids.includes(recordedPid), `端口被非 acc-env 进程占用（${pids.join(',')} vs ${recordedPid}）`)
    fingerprint.backendPidVerified = true
  }

  const jar = fs.readdirSync(JAR_GLOB_DIR).find(f => f.endsWith('.jar') && !f.endsWith('.jar.original'))
  ok(jar, 'server/target 缺少构建产物')
  fingerprint.jarSha256 = sha256File(path.join(JAR_GLOB_DIR, jar))

  const { parsed } = await api('/mini/test-login/by-phone', { phone: OWNER_PHONE })
  ok(parsed && parsed.code === 0, '测试登录不可用——mini.test-login.enabled 未开或环境错误')
  return fingerprint
}

async function ensureCard() {
  const usable = await apiOk('/mini/card/usable-list', {}, ctx.owner)
  const existing = (usable || []).find(c => c.canRecharge)
  if (existing) {
    ctx.cardId = existing.cardId
    return
  }
  const created = await apiOk('/mini/order/recharge/create', { packageId: ACC_PACKAGE_ID, requestId: crypto.randomUUID() }, ctx.owner)
  await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
  const card = await pollUntil('充值到账应发卡', async () => {
    const list = await apiOk('/mini/card/usable-list', {}, ctx.owner)
    return (list || []).find(c => c.canRecharge) || null
  }, 30000)
  ctx.cardId = card.cardId
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  if (scenarios.length !== 13) {
    console.error(`场景登记数=${scenarios.length}，必须恰为 13；拒绝执行`)
    process.exit(2)
  }
  const mysql = require('mysql2/promise')
  pool = await mysql.createPool({ ...DB_CONFIG, connectionLimit: 4 })

  const envFingerprint = await verifyEnvironment()
  console.log(`环境指纹：base=${envFingerprint.base} db=${envFingerprint.dbHost}:${envFingerprint.dbPort} jar=${(envFingerprint.jarSha256 || '').slice(0, 12)} round=${ROUND}`)

  const ownerLogin = await miniLogin(OWNER_PHONE)
  const otherLogin = await miniLogin(OTHER_PHONE)
  ctx.owner = ownerLogin
  ctx.other = otherLogin
  await ensureCard()

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
    chain: 'E2E-06',
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
    { name: '手机号原文', re: /1[3-9]\d{9}/ },
    { name: '本机绝对路径', re: /\/(?:Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
  ]
  const runtimeSecrets = [
    { name: '机主 Token', value: ctx.owner && ctx.owner.tokenValue },
    { name: '对照 Token', value: ctx.other && ctx.other.tokenValue },
    { name: '数据库口令', value: DB_CONFIG.password },
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
  console.log(`BATCH_DIR=docs/acceptance/owner-analytics/runs/${BATCH_ID}`)
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
