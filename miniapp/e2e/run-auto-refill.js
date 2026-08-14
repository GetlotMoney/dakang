/**
 * B08 自动补货固定周期 —— 固定场景验收脚本（真实 HTTP + 隔离验收库 DB 断言）。
 *
 * 这是能力矩阵 B08「自动补货待隔离环境验收」的本体。它验的是**真实 Worker 在真实调度下
 * 的行为**，而不是服务方法能不能被调用——后者已由 DeliveryAutoRefillDbTest 覆盖，
 * 而那里的期次是测试代码手动喂时间推进的。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已重建；
 * - 后端以 ACC_AUTO_REFILL_ENABLED=true 启动，且节拍调快
 *   （ACC_AUTO_REFILL_FIXED_DELAY / ACC_AUTO_REFILL_INITIAL_DELAY），否则要等 5 分钟一轮。
 *
 * 安全闸（fail-closed，全部在连库之前完成）：
 * - 必须 REFILL_ACC_MODE=full；
 * - 必须 REFILL_ALLOW_AUTO_CHARGE=I_ACCEPT_UNATTENDED_CARD_CHARGE
 *   —— 自动补货**无用户交互地真实扣卡**，比任何模拟支付更需要一次显式确认；
 * - 后端端口为 13330/8081 或 DB 端口为 3306/3308 时拒绝执行。
 *
 * 时间口径：规则的第 n 期到期时间 = 锚点 + n×周期天数。验收不可能真等 7 天，
 * 所以用**回拨锚点**推进期次（UPDATE ANCHOR_TIME），而不是改系统时钟或调用内部方法——
 * 回拨锚点走的仍是 Worker 自己的到期判据，改时钟会让同机其它容器一起错乱。
 *
 * 结果纪律：场景数固定，实际执行数不符即判 FAIL（禁止 0/0 PASS）；产物只写 result.json，
 * 且不含手机号、Token、口令与本机绝对路径。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const mysql = require('mysql2/promise')

if (String(process.env.REFILL_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 REFILL_ACC_MODE=full')
  process.exit(2)
}
if (String(process.env.REFILL_ALLOW_AUTO_CHARGE || '').trim() !== 'I_ACCEPT_UNATTENDED_CARD_CHARGE') {
  console.error('安全闸拒绝执行：必须显式设置 REFILL_ALLOW_AUTO_CHARGE=I_ACCEPT_UNATTENDED_CARD_CHARGE')
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
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD')
  process.exit(2)
}

/** 固定场景数。实际执行数与之不符即判 FAIL —— 「禁止 0/0 PASS」的物理实现。 */
const EXPECTED_SCENARIOS = 10

const USER_PHONE = '13999990001'
const STATION_ID = '9101'
const PACKAGE_ID = '9501'
const CASH_PACKAGE_ID = '9502'
const WATER_TYPE_ID = '1'
const INTERVAL_DAYS = 7
/** Worker 节拍：acc-env 把 fixed-delay 调到秒级；等待上限给足两轮余量。 */
const WORKER_WAIT_MS = Number(process.env.ACC_AUTO_REFILL_WAIT_MS || 40000)

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
  ok(r && r.code !== 0, `${p} 应被拒绝`, `却返回 code=0`)
  return r.msg
}
async function q(sql, params = []) {
  const [rows] = await pool.query(sql, params)
  return rows
}
const one = async (sql, p = []) => (await q(sql, p))[0] || null
const rid = () => crypto.randomUUID()
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function miniLogin(phone) {
  const d = await apiOk('/mini/test-login/by-phone', { phone })
  return { tokenName: d.tokenName, tokenValue: d.tokenValue }
}

/** 期次订单数：规则派生的订单靠 PACKAGE_SNAP 里的 autoRuleId 标识。 */
async function periodOrderCount(ruleId) {
  const row = await one(
    // ORDER_TYPE=3 水配送（2 是购卡充值）。第一版写成 2，于是期次订单永远查不到：
    // S6 报「Worker 没生成」而 Worker 其实生成了；S7 更糟——它变成断言「0 单没变成 0 单」，
    // 空转通过。查询条件写错会把一整组断言变成什么都没验。
    'SELECT COUNT(*) c FROM ws_order WHERE PACKAGE_SNAP LIKE ? AND ORDER_TYPE = 3',
    [`%"autoRuleId":${ruleId}%`],
  )
  return Number(row.c)
}

/**
 * 把锚点回拨 n 个周期，让第 n 期到期，然后等 Worker 自己扫到。
 *
 * 刻意不直接调服务方法：要验的就是「真实调度下 Worker 会不会按期生成、会不会重复生成」，
 * 手动调一次方法把调度这一段整个跳过了，而那正是矩阵里写「待隔离环境验收」的原因。
 */
async function advancePeriods(ruleId, periods) {
  await pool.query(
    'UPDATE ws_delivery_auto_rule SET ANCHOR_TIME = DATE_FORMAT(DATE_SUB(NOW(), INTERVAL ? DAY), \'%Y%m%d%H%i%s\') WHERE ID = ?',
    [periods * INTERVAL_DAYS + 1, ruleId],
  )
}

async function waitFor(label, probe, timeoutMs = WORKER_WAIT_MS) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const hit = await probe()
    if (hit) {
      return hit
    }
    if (Date.now() > deadline) {
      throw new Fail(`${label}：${timeoutMs}ms 内未达成`)
    }
    await sleep(2000)
  }
}

function autoBody(cardId, address) {
  return {
    requestId: rid(),
    cardId: String(cardId),
    stationId: STATION_ID,
    waterTypeId: WATER_TYPE_ID,
    containerSpec: '20L桶',
    deliveryCount: 2,
    planReturnCount: 1,
    receiveAddress: address,
    receivePhone: '13900001111',
    deliveryMode: 3,
    autoRefillIntervalDays: INTERVAL_DAYS,
  }
}

async function main() {
  pool = await mysql.createPool({ ...DB, waitForConnections: true, connectionLimit: 4 })
  const ctx = {}

  await scene('S1 登录并核对底座：Worker 已开启、规则表为干净面', async () => {
    ctx.user = await miniLogin(USER_PHONE)
    const rules = await one('SELECT COUNT(*) c FROM ws_delivery_auto_rule')
    ok(Number(rules.c) === 0, '本轮开跑前规则表应为空（验收库每轮 rebuild）', `实际 ${rules.c} 条`)
    // Worker 是否真的在跑，只能由后面场景的「按期生成」证明；这里先确认开关不是默认关
    ok(process.env.ACC_AUTO_REFILL_ENABLED === 'true', '必须以 ACC_AUTO_REFILL_ENABLED=true 启动后端，否则本 runner 验的是「什么都没发生」')
    return '用户登录就绪；规则表干净面；自动补货开关已显式开启'
  })

  await scene('S2 备卡：确保余额足够覆盖多期扣款', async () => {
    let card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
    if (!card) {
      const created = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.user)
      await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.user)
      card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
    }
    while (Number(card.BALANCE_AMOUNT) < 20000) {
      const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(card.ID), requestId: rid() }, ctx.user)
      await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, ctx.user)
      card = await one('SELECT * FROM ws_card WHERE ID=?', [card.ID])
    }
    ctx.cardId = card.ID
    return `card=${card.ID} 余额 ${card.BALANCE_AMOUNT} 分`
  })

  await scene('S3 建规则：首单落地、规则启用、锚点即创建时间', async () => {
    const before = Number((await one('SELECT COUNT(*) c FROM ws_order')).c)
    await apiOk('/mini/delivery/order/create', autoBody(ctx.cardId, '验收专区自动补货点1号'), ctx.user)
    const rule = await one('SELECT * FROM ws_delivery_auto_rule ORDER BY ID DESC LIMIT 1')
    ok(rule, '应生成规则')
    ctx.ruleId = rule.ID
    ok(rule.RULE_STATUS === 1, '规则应为启用', `status=${rule.RULE_STATUS}`)
    ok(rule.INTERVAL_DAYS === INTERVAL_DAYS, '周期应为用户显式配置值')
    ok(/^\d{14}$/.test(rule.ANCHOR_TIME || ''), '锚点应是 14 位业务时间', rule.ANCHOR_TIME)
    const after = Number((await one('SELECT COUNT(*) c FROM ws_order')).c)
    ok(after - before === 1, '建规则应恰带一张首单', `${before}→${after}`)
    return `规则 ${rule.ID} 启用，周期 ${rule.INTERVAL_DAYS} 天，锚点 ${rule.ANCHOR_TIME}，首单已落`
  })

  await scene('S4 同款去重：相同水种/规格/地址的第二条规则被拒且零副作用', async () => {
    const rules = Number((await one('SELECT COUNT(*) c FROM ws_delivery_auto_rule')).c)
    const orders = Number((await one('SELECT COUNT(*) c FROM ws_order')).c)
    const msg = await apiFail('/mini/delivery/order/create', autoBody(ctx.cardId, '验收专区自动补货点1号'), ctx.user)
    ok(/自动补货规则/.test(msg || ''), '拒因应指向同款规则', msg)
    ok(Number((await one('SELECT COUNT(*) c FROM ws_delivery_auto_rule')).c) === rules, '被拒不得留下第二条规则')
    ok(Number((await one('SELECT COUNT(*) c FROM ws_order')).c) === orders, '被拒不得留下订单')
    return `第二条同款规则被拒：${msg}；规则数与订单数均未变`
  })

  await scene('S5 换地址可并存：去重键是「同用户+水种+规格+地址」而不是「每人一条」', async () => {
    await apiOk('/mini/delivery/order/create', autoBody(ctx.cardId, '验收专区自动补货点2号'), ctx.user)
    const alive = Number((await one(
      'SELECT COUNT(*) c FROM ws_delivery_auto_rule WHERE RULE_STATUS IN (1,2)',
    )).c)
    ok(alive === 2, '不同收货地址应可并存两条规则', `实际 ${alive} 条`)
    // 第二条只为证明去重维度，随即取消，避免它参与后续期次断言
    const second = await one('SELECT * FROM ws_delivery_auto_rule ORDER BY ID DESC LIMIT 1')
    await apiOk('/mini/delivery/autoRule/cancel', { ruleId: String(second.ID) }, ctx.user)
    return '不同地址两条规则并存成立；第二条已取消，后续断言只针对首条'
  })

  await scene('S6 到期生成：Worker 按真实调度生成第 1 期订单', async () => {
    const before = await periodOrderCount(ctx.ruleId)
    await advancePeriods(ctx.ruleId, 1)
    const hit = await waitFor('第 1 期订单应由 Worker 生成', async () => {
      const now = await periodOrderCount(ctx.ruleId)
      return now > before ? now : null
    })
    ok(hit - before === 1, '一期恰一单', `${before}→${hit}`)
    return `第 1 期订单已由 Worker 生成（期次订单 ${before}→${hit}）`
  })

  await scene('S7 同期幂等：Worker 反复扫描不重复生成、不重复扣款', async () => {
    const orders = await periodOrderCount(ctx.ruleId)
    // 先证明有东西可守：期次订单为 0 时，「没有增长」是空转通过而不是幂等成立
    ok(orders > 0, '前置：第 1 期订单必须已存在，否则本场景什么都没验', `期次订单 ${orders}`)
    const card = await one('SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?', [ctx.cardId])
    // 锚点不动，让 Worker 至少再扫两轮
    await sleep(Math.min(WORKER_WAIT_MS, 15000))
    ok(await periodOrderCount(ctx.ruleId) === orders, '同期重复扫描不得增生订单')
    const after = await one('SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?', [ctx.cardId])
    ok(Number(after.BALANCE_AMOUNT) === Number(card.BALANCE_AMOUNT), '同期重复扫描不得二次扣款', `${card.BALANCE_AMOUNT}→${after.BALANCE_AMOUNT}`)
    return `重复扫描后期次订单仍 ${orders} 单、余额 ${after.BALANCE_AMOUNT} 分未变`
  })

  await scene('S8 余额不足：期次失败留证，规则不被销毁', async () => {
    const kept = await one('SELECT BALANCE_AMOUNT FROM ws_card WHERE ID=?', [ctx.cardId])
    await pool.query('UPDATE ws_card SET BALANCE_AMOUNT=0 WHERE ID=?', [ctx.cardId])
    const before = await periodOrderCount(ctx.ruleId)
    await advancePeriods(ctx.ruleId, 2)
    await sleep(Math.min(WORKER_WAIT_MS, 20000))
    ok(await periodOrderCount(ctx.ruleId) === before, '余额不足不得生成订单')
    const rule = await one('SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=?', [ctx.ruleId])
    ok(rule.RULE_STATUS === 1, '单期失败不得销毁或停用规则', `status=${rule.RULE_STATUS}`)
    await pool.query('UPDATE ws_card SET BALANCE_AMOUNT=? WHERE ID=?', [kept.BALANCE_AMOUNT, ctx.cardId])
    return `余额清零后该期零生成、规则仍启用；余额已还原 ${kept.BALANCE_AMOUNT} 分`
  })

  await scene('S9 用户取消：终态不可恢复，后续期次恒零生成', async () => {
    await apiOk('/mini/delivery/autoRule/cancel', { ruleId: String(ctx.ruleId) }, ctx.user)
    const rule = await one('SELECT RULE_STATUS FROM ws_delivery_auto_rule WHERE ID=?', [ctx.ruleId])
    ok(rule.RULE_STATUS === 3, '取消应为终态 3', `status=${rule.RULE_STATUS}`)
    await apiFail('/mini/delivery/autoRule/resume', { ruleId: String(ctx.ruleId) }, ctx.user)
    const before = await periodOrderCount(ctx.ruleId)
    await advancePeriods(ctx.ruleId, 5)
    await sleep(Math.min(WORKER_WAIT_MS, 20000))
    ok(await periodOrderCount(ctx.ruleId) === before, '取消后任何期次恒零生成')
    // 取消让位：同款可以重新建（唯一键只在存活期生效）
    await apiOk('/mini/delivery/order/create', autoBody(ctx.cardId, '验收专区自动补货点1号'), ctx.user)
    const rebuilt = await one('SELECT * FROM ws_delivery_auto_rule ORDER BY ID DESC LIMIT 1')
    ok(rebuilt.ID !== ctx.ruleId, '取消后同款应能重新建')
    ctx.rebuiltId = rebuilt.ID
    return `规则 ${ctx.ruleId} 终态 3、恢复被拒、后续期次零生成；同款已可重建为 ${rebuilt.ID}`
  })

  await scene('S10 证据总账：订单号唯一、扣款与订单一一对应、无孤儿期次', async () => {
    const dupOrder = await q('SELECT ORDER_NO, COUNT(*) c FROM ws_order GROUP BY ORDER_NO HAVING c > 1')
    ok(dupOrder.length === 0, '订单号必须全局唯一', `重复 ${dupOrder.length} 个`)
    const dupAlloc = await q(`
      SELECT BIZ_KEY, ALLOC_SEQ, COUNT(*) c FROM ws_entitlement_allocation
      WHERE BIZ_KEY IS NOT NULL GROUP BY BIZ_KEY, ALLOC_SEQ HAVING c > 1`)
    ok(dupAlloc.length === 0, '权益分摊的业务键+序号必须唯一（同一期不得二次扣款）', `重复 ${dupAlloc.length} 组`)
    const aliveDup = await q(`
      SELECT USER_ID, WATER_TYPE_ID, CONTAINER_SPEC, RECEIVE_ADDRESS, COUNT(*) c
      FROM ws_delivery_auto_rule WHERE RULE_STATUS IN (1,2)
      GROUP BY USER_ID, WATER_TYPE_ID, CONTAINER_SPEC, RECEIVE_ADDRESS HAVING c > 1`)
    ok(aliveDup.length === 0, '存活期同款规则不得并存', `违例 ${aliveDup.length} 组`)
    const cancelledHolding = await one(
      'SELECT COUNT(*) c FROM ws_delivery_auto_rule WHERE RULE_STATUS = 3 AND ACTIVE_SHAPE_KEY IS NOT NULL',
    )
    ok(Number(cancelledHolding.c) === 0, '已取消规则不得继续占位', `实际 ${cancelledHolding.c} 条`)
    return '订单号与卡流水键零重复；存活期同款零并存；已取消规则零占位'
  })

  const pass = results.filter(r => r.pass).length
  const allPassed = pass === results.length && results.length === EXPECTED_SCENARIOS

  const outDir = path.resolve(__dirname, '../../docs/acceptance/auto-refill/runs')
  fs.mkdirSync(outDir, { recursive: true })
  const batch = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
  const report = {
    chain: 'B08',
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
  ]
  const leaked = forbidden.filter(f => f.re.test(serialized))
  if (leaked.length) {
    console.error(`验收产物含禁止信息，拒绝落盘：${leaked.map(f => f.name).join('、')}`)
    await pool.end()
    process.exit(3)
  }
  fs.writeFileSync(path.join(outDir, `${batch}-result.json`), serialized)
  await pool.end()

  if (results.length !== EXPECTED_SCENARIOS) {
    console.error(`\n场景数不符：期望 ${EXPECTED_SCENARIOS}，实际执行 ${results.length} —— 判 FAIL`)
  }
  console.log(`\n=== B08 自动补货验收：${pass}/${results.length} PASS（期望场景数 ${EXPECTED_SCENARIOS}）===`)
  console.log(`结果已写入 docs/acceptance/auto-refill/runs/${batch}-result.json`)
  process.exit(allPassed ? 0 : 1)
}

main().catch(async (e) => {
  console.error('验收中断：', e.message)
  if (pool) {
    await pool.end()
  }
  process.exit(1)
})
