/**
 * E2E-04 售后退款与补偿 —— 固定场景验收脚本（真实 HTTP + 隔离验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已按 init + migrations + acc-seed.sql 重建；
 * - 验收后端已以 Pay-Sim 与 Refund-Sim 双开启跑在 13340。
 *
 * 安全闸（fail-closed，全部在**连接数据库或创建任何业务数据之前**完成）：
 * - 必须 AFTERSALE_ACC_MODE=full；
 * - 必须 AFTERSALE_ALLOW_SIM_REFUND=I_ACCEPT_ISOLATED_REFUND_SIM
 *   —— 模拟退款是出账动作，比模拟收款危险一个量级，故要求一个不可能手滑打出的显式串；
 * - 后端端口为 13330/8081（主环境）或 DB 端口为 3306/3308（主库）时拒绝执行。
 *
 * 场景构成：
 * - S0～S10：配送申诉的卡内返还闭环（下单→签收→申诉→裁决→返还→封顶）；
 * - S11～S14：包D-5 权益批次退款（任务书场景 15 未使用全额退、16 部分使用按快照折算、
 *   17 首购退款后的卡状态、21 退款锁定期间消费不得穿透）。
 *
 * 本 runner 不覆盖待接单取消、取水异常核账、补送签收和退款失败/乱序恢复；这些能力由
 * 对应真库事务测试覆盖，不能把本文件的 15/15 单独表述为 E2E-04 全业务形态验收。
 *
 * 结果纪律：
 * - 场景数量固定（见 EXPECTED_SCENARIOS），实际执行数不等于它即判 FAIL —— 禁止 0/0 PASS；
 * - 只输出结构化 result.json，不生成任何 Markdown；
 * - result.json 内**不写** Token、手机号原文、数据库口令、退款原始报文与本机绝对路径。
 */
const process = require('node:process')
const { Buffer } = require('node:buffer')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const mysql = require('mysql2/promise')

// ─────────────────────────────────────────────────────────────
// 安全闸：任何一条不满足都必须在连库之前退出
// ─────────────────────────────────────────────────────────────
if (String(process.env.AFTERSALE_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 AFTERSALE_ACC_MODE=full')
  process.exit(2)
}
if (String(process.env.AFTERSALE_ALLOW_SIM_REFUND || '').trim() !== 'I_ACCEPT_ISOLATED_REFUND_SIM') {
  console.error('安全闸拒绝执行：必须显式设置 AFTERSALE_ALLOW_SIM_REFUND=I_ACCEPT_ISOLATED_REFUND_SIM')
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
const EXPECTED_SCENARIOS = 15

const OWNER_PHONE = '13999990001'
const MEMBER_PHONE = '13999990002'
const COURIER_PHONE = '13999990003'
const OPS_LOGIN = 'acc-ops'
const OPS_PWD = process.env.ACC_OPS_PWD_CIPHER
const STATION_ID = '9101'
const PACKAGE_ID = '9501'
const CASH_PACKAGE_ID = '9502'

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

async function api(path, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers[token.tokenName] = token.tokenValue
  }
  const resp = await fetch(`${BASE}${path}`, { method: 'POST', headers, body: JSON.stringify(body ?? {}) })
  const text = await resp.text()
  try {
    return JSON.parse(text)
  }
  catch {
    throw new Fail(`HTTP ${resp.status} ${path} 非 JSON：${text.slice(0, 150)}`)
  }
}
async function apiOk(path, body, token) {
  const r = await api(path, body, token)
  ok(r && r.code === 0, `${path} 应成功`, `code=${r && r.code} msg=${r && r.msg}`)
  return r.data
}
async function apiFail(path, body, token) {
  const r = await api(path, body, token)
  ok(r && r.code !== 0, `${path} 应被拒绝`, `却返回 code=0 data=${JSON.stringify(r && r.data)}`)
  return r.msg
}
async function q(sql, params = []) {
  const [rows] = await pool.query(sql, params)
  return rows
}
const one = async (sql, p = []) => (await q(sql, p))[0] || null

async function miniLogin(phone) {
  const d = await apiOk('/mini/test-login/by-phone', { phone })
  return { tokenName: d.tokenName, tokenValue: d.tokenValue }
}
async function opsLogin() {
  const d = await apiOk('/api/auth/loginEmployee', { loginName: OPS_LOGIN, loginPwd: OPS_PWD })
  return { tokenName: 'dakang-token', tokenValue: d.tokenValue }
}

// 1x1 PNG（受控媒体登记只校验 MIME 与大小，内容真伪不在本次射程内）
const PNG_1X1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
// 媒体键是内容寻址的（sha256(owner:content:purpose)），同内容重复上传会拿到上轮已被签收占用的键。
// 每轮追加随机尾巴，让本脚本可重复执行而不撞"已被占用"。
const uniquePng = () => Buffer.concat([Buffer.from(PNG_1X1, 'base64'), crypto.randomBytes(16)]).toString('base64')
const rid = () => crypto.randomUUID()

/**
 * 配送员把一个待接单任务推到三照签收（接单→离站→送达→签收）。
 *
 * <p>抽成函数而不是在两处各写一遍：这条链有四个 expectedVersion 传递点，
 * 复制第二份的代价是「一处改了版本传递、另一处没改」时脚本仍然全绿。</p>
 */
async function deliverAndSign(ctx, task, actualDeliveryCount) {
  const taskNo = task.TASK_NO
  let v = task.VERSION
  const accepted = await apiOk('/mini/delivery/task/accept', { taskNo, expectedVersion: v }, ctx.courier)
  v = accepted.version
  const left = await apiOk('/mini/delivery/task/advance', { taskNo, targetStatus: 3, expectedVersion: v }, ctx.courier)
  v = left.version
  const arrived = await apiOk('/mini/delivery/task/advance', { taskNo, targetStatus: 4, expectedVersion: v }, ctx.courier)
  v = arrived.version
  // 三张都必须登记为 SIGN_PHOTO(1)：照片 type（门牌/水品/摆放）是签收语义，
  // 与媒体 purpose（签收三照/申诉举证/异常举证）是两套枚举，混用会被用途闸拒绝
  const keys = []
  for (let i = 0; i < 3; i++) {
    const m = await apiOk('/mini/delivery/media/upload', {
      purpose: 1,
      mimeType: 'image/png',
      contentBase64: uniquePng(),
    }, ctx.courier)
    keys.push(m.mediaKey)
  }
  ok(new Set(keys).size === 3, '三照应是三个互不相同的媒体键', keys.join(','))
  await apiOk('/mini/delivery/task/sign', {
    taskNo,
    expectedVersion: v,
    actualDeliveryCount,
    actualReturnCount: 0,
    locationStatus: 2,
    photos: [
      { type: 1, mediaKey: keys[0] },
      { type: 2, mediaKey: keys[1] },
      { type: 3, mediaKey: keys[2] },
    ],
  }, ctx.courier)
  return keys
}

async function main() {
  pool = await mysql.createPool({ ...DB, waitForConnections: true, connectionLimit: 4 })
  const ctx = {}

  await scene('S0 登录三方身份（卡主/配送员/运营）', async () => {
    ctx.owner = await miniLogin(OWNER_PHONE)
    ctx.courier = await miniLogin(COURIER_PHONE)
    ctx.ops = await opsLogin()
    return `owner/courier/ops token 均已取得`
  })

  await scene('S1 备好一张有余额的永久卡（复用已有卡，缺余额则充纯金额套餐）', async () => {
    // 本脚本可重复执行：卡与余额都按"缺什么补什么"处理，不假定验收库刚 rebuild 过
    let card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
    if (!card) {
      const created = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.owner)
      await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
      const order = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [created.orderNo])
      ok(order && order.ORDER_STATUS === 4, '购卡单应已完成(FINISHED=4)', `status=${order && order.ORDER_STATUS}`)
      card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
      ok(card, '应生成水卡')
    }
    ok(card.EXPIRE_TIME === null, 'D-213：正规付费卡应永久有效', `EXPIRE_TIME=${card.EXPIRE_TIME}`)
    if (Number(card.BALANCE_AMOUNT) < 4000) {
      const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(card.ID), requestId: rid() }, ctx.owner)
      await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, ctx.owner)
      card = await one('SELECT * FROM ws_card WHERE ID=?', [card.ID])
    }
    ok(Number(card.BALANCE_AMOUNT) >= 4000, '余额应够本单 3000 分', `实际 ${card.BALANCE_AMOUNT}`)
    ctx.cardId = card.ID
    ctx.card0 = card
    return `card=${card.ID} 余额=${card.BALANCE_AMOUNT}分 水量=${card.BALANCE_ML}ml 有效期=永久`
  })

  await scene('S2 配送下单 payWay=2（全余额）并冻结快照', async () => {
    const created = await apiOk('/mini/delivery/order/create', {
      requestId: rid(),
      cardId: String(ctx.cardId),
      stationId: STATION_ID,
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 2,
      planReturnCount: 0,
      receiveAddress: '验收专区1号',
      receivePhone: '13999990001',
      deliveryMode: 1,
      payWay: 2,
    }, ctx.owner)
    // OrderDetailVo 自身还包一层 order（复用 mini 订单读模型），故是 order.order.orderNo
    const head = created && created.order && created.order.order
    ok(head && head.orderNo, '创单应返回订单详情', JSON.stringify(created).slice(0, 160))
    ctx.orderNo = head.orderNo
    ctx.taskNoFromCreate = created.task && created.task.taskNo
    const order = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [ctx.orderNo])
    ok(order, '订单应已落库', ctx.orderNo)
    ctx.orderId = order.ID
    const card = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    // 20L桶：水费 1300分/桶 × 2 = 2600；配送费 200分/桶 × 2 = 400；合计 3000 分
    const spent = ctx.card0.BALANCE_AMOUNT - card.BALANCE_AMOUNT
    ok(spent === 3000, '余额扣款应为 2600 水费 + 400 配送费', `实扣 ${spent}`)
    ok(card.BALANCE_ML === ctx.card0.BALANCE_ML, 'payWay=2 不应动水量', `水量 ${ctx.card0.BALANCE_ML}→${card.BALANCE_ML}`)
    const flow = await one('SELECT * FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`DELIVERY:${ctx.orderNo}`])
    ok(flow, '应有 DELIVERY: 前缀的扣款流水')
    ok(Number(flow.AMOUNT_CHANGE) === -3000, '流水扣款额应与卡差额一致', `AMOUNT_CHANGE=${flow.AMOUNT_CHANGE}`)
    ctx.cardAfterOrder = card
    return `order=${ctx.orderNo} 扣款=${spent}分 流水键=DELIVERY:${ctx.orderNo}`
  })

  await scene('S3 配送员接单→离站→送达→三照签收（实送1桶）', async () => {
    const task = await one('SELECT * FROM ws_delivery_task WHERE ORDER_ID=?', [ctx.orderId])
    ok(task, '下单应生成配送任务')
    ctx.taskNo = task.TASK_NO
    await deliverAndSign(ctx, task, 1)
    const done = await one('SELECT * FROM ws_delivery_task WHERE TASK_NO=?', [ctx.taskNo])
    ok(done.TASK_STATUS === 5, '签收后任务应为已完成', `status=${done.TASK_STATUS}`)
    return `task=${ctx.taskNo} 应送2实送1，状态=${done.TASK_STATUS}`
  })

  await scene('S4 卡主申诉（实收1桶）', async () => {
    const created = await apiOk('/mini/order/appeal/create', {
      orderNo: ctx.orderNo,
      taskNo: ctx.taskNo,
      reason: 'QUANTITY',
      description: '只收到1桶，少送1桶',
      receivedCount: 1,
    }, ctx.owner)
    const appeal = await one('SELECT * FROM ws_delivery_appeal WHERE ORDER_ID=? ORDER BY ID DESC LIMIT 1', [ctx.orderId])
    ok(appeal, '应生成申诉')
    ctx.appealId = appeal.ID
    return `appeal=${appeal.ID} status=${appeal.APPEAL_STATUS} created=${JSON.stringify(created).slice(0, 60)}`
  })

  await scene('S5 运营裁决 PRODUCT_ONLY（仅退水费1桶）→ 生成待执行售后动作', async () => {
    await apiOk('/order/appeal/decide', {
      appealId: String(ctx.appealId),
      strategyCode: 'PRODUCT_ONLY',
      approvedCount: 1,
      handleResult: '核实少送1桶，退还该桶水费',
    }, ctx.ops)
    const action = await one('SELECT * FROM ws_after_sale_action WHERE ORDER_ID=? ORDER BY ID DESC LIMIT 1', [ctx.orderId])
    ok(action, '裁决应生成售后动作')
    ctx.actionId = action.ID
    ctx.afterSaleNo = action.AFTER_SALE_NO
    ok(action.ACTION_STATUS === 1, '售后动作应为待执行', `status=${action.ACTION_STATUS}`)
    ok(Number(action.REFUND_PRODUCT_FEN) === 1300, '水费额度应为单桶 1300 分', `=${action.REFUND_PRODUCT_FEN}`)
    ok(Number(action.REFUND_SERVICE_FEN) === 0, 'PRODUCT_ONLY 不退配送费', `=${action.REFUND_SERVICE_FEN}`)
    ok(Number(action.REFUND_PRODUCT_ML) === 0, 'payWay=2 不退水量', `=${action.REFUND_PRODUCT_ML}`)
    ok(Number(action.REFUND_AMOUNT) === 1300, '合计额度应为 1300', `=${action.REFUND_AMOUNT}`)
    return `action=${action.ID} no=${action.AFTER_SALE_NO} 四元额度=(${action.REFUND_PRODUCT_FEN},${action.REFUND_SERVICE_FEN},${action.REFUND_PRODUCT_ML})/${action.REFUND_AMOUNT}`
  })

  await scene('S6 售后台账列表与详情可见且手机号脱敏', async () => {
    const page = await apiOk('/order/after-sale/page', { current: 1, size: 20 }, ctx.ops)
    const row = (page.records || page.list || []).find(r => String(r.id) === String(ctx.actionId))
    ok(row, '台账应能查到该动作', `返回 ${JSON.stringify(page).slice(0, 120)}`)
    const detail = await apiOk('/order/after-sale/detail', { id: Number(ctx.actionId) }, ctx.ops)
    const raw = JSON.stringify(detail)
    ok(!/13999990001/.test(raw), '详情不得返回明文手机号', raw.slice(0, 160))
    ok(/\*{2,}/.test(raw), '详情应含脱敏手机号', raw.slice(0, 160))
    return `列表命中 + 详情已脱敏（响应中不含卡主手机号原文）`
  })

  await scene('S7 执行返还：余额回补 1300 分，动作落 SUCCESS', async () => {
    const before = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    await apiOk('/order/after-sale/execute', { id: Number(ctx.actionId), afterSaleNo: ctx.afterSaleNo }, ctx.ops)
    const after = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    const delta = after.BALANCE_AMOUNT - before.BALANCE_AMOUNT
    ok(delta === 1300, '余额应回补 1300 分', `实补 ${delta}`)
    ok(after.BALANCE_ML === before.BALANCE_ML, '不应动水量', `${before.BALANCE_ML}→${after.BALANCE_ML}`)
    const action = await one('SELECT * FROM ws_after_sale_action WHERE ID=?', [ctx.actionId])
    ok(action.ACTION_STATUS === 3, '动作应为成功', `status=${action.ACTION_STATUS}`)
    const flows = await q('SELECT * FROM ws_wallet_flow WHERE ORDER_ID=? ORDER BY ID', [ctx.orderId])
    const back = flows.filter(f => Number(f.AMOUNT_CHANGE) > 0)
    ok(back.length === 1, '应恰有一条入账流水', `实有 ${back.length}`)
    ok(Number(back[0].AMOUNT_AFTER) === after.BALANCE_AMOUNT, '流水 AFTER 值应与卡当前余额一致', `flow=${back[0].AMOUNT_AFTER} card=${after.BALANCE_AMOUNT}`)
    ctx.cardAfterRefund = after
    return `余额 ${before.BALANCE_AMOUNT}→${after.BALANCE_AMOUNT}（+${delta}），动作=SUCCESS，入账流水 AFTER=${back[0].AMOUNT_AFTER}`
  })

  await scene('S8 重复执行被拒（终态不可再执行，且不再动钱）', async () => {
    const before = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    // 防重放会先于业务闸拦下同参重放（键=url+用户+请求体哈希，窗口由切面硬编码为 5s，
    // 注解上的 expireTime 并不生效），等它过期，否则本场景验的是拦截器而不是售后状态机
    await new Promise(r => setTimeout(r, 6000))
    const msg = await apiFail('/order/after-sale/execute', { id: Number(ctx.actionId), afterSaleNo: ctx.afterSaleNo }, ctx.ops)
    // 必须钉住"状态不可执行"：参数校验或防重放之类的 4xx 也会让 apiFail 通过，那是假绿
    ok(/当前状态不可执行|已被他人认领/.test(msg), '拒绝原因应来自状态机而非参数校验/防重放', `实际：${msg}`)
    const after = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    ok(after.BALANCE_AMOUNT === before.BALANCE_AMOUNT, '重复执行不得改变余额', `${before.BALANCE_AMOUNT}→${after.BALANCE_AMOUNT}`)
    return `拒绝原因：${msg}；余额未变`
  })

  await scene('S9 售后号与动作ID不匹配被拒', async () => {
    const msg = await apiFail('/order/after-sale/execute', { id: Number(ctx.actionId), afterSaleNo: 'AS0000000000000000000000000000' }, ctx.ops)
    // 必须钉住这一条拒绝原因：任何其它 4xx（参数绑定、权限）都会让本场景假绿
    ok(/售后号与动作ID不匹配/.test(msg), '拒绝原因应是售后号不匹配', `实际：${msg}`)
    return `拒绝原因：${msg}`
  })

  await scene('S10 封顶：同订单再申诉退 2 桶应被拒（单桶已退，余额仅剩 1 桶额度）', async () => {
    // 第二次申诉：声明一桶都没收到，请求退 2 桶水费
    await apiOk('/mini/order/appeal/create', {
      orderNo: ctx.orderNo,
      taskNo: ctx.taskNo,
      reason: 'QUANTITY',
      description: '复核后发现两桶都有问题',
      receivedCount: 0,
    }, ctx.owner)
    const appeal2 = await one('SELECT * FROM ws_delivery_appeal WHERE ORDER_ID=? ORDER BY ID DESC LIMIT 1', [ctx.orderId])
    ok(String(appeal2.ID) !== String(ctx.appealId), '应是新申诉', `id=${appeal2.ID}`)
    await apiOk('/order/appeal/decide', {
      appealId: String(appeal2.ID),
      strategyCode: 'PRODUCT_ONLY',
      approvedCount: 2,
      handleResult: '二次裁决：退两桶水费',
    }, ctx.ops)
    const action2 = await one('SELECT * FROM ws_after_sale_action WHERE ORDER_ID=? ORDER BY ID DESC LIMIT 1', [ctx.orderId])
    ok(String(action2.ID) !== String(ctx.actionId), '应是新动作', `id=${action2.ID}`)
    const before = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    const msg = await apiFail('/order/after-sale/execute', { id: Number(action2.ID), afterSaleNo: action2.AFTER_SALE_NO }, ctx.ops)
    const after = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    ok(after.BALANCE_AMOUNT === before.BALANCE_AMOUNT, '超额执行不得动钱', `${before.BALANCE_AMOUNT}→${after.BALANCE_AMOUNT}`)
    const reloaded = await one('SELECT * FROM ws_after_sale_action WHERE ID=?', [action2.ID])
    ok(reloaded.ACTION_STATUS !== 3, '超额动作不得落成功', `status=${reloaded.ACTION_STATUS}`)
    return `二次动作=${action2.ID} 请求 ${action2.REFUND_PRODUCT_FEN} 分被拒：${msg}；终态=${reloaded.ACTION_STATUS}，余额未变`
  })

  // ═══════════════════════════════════════════════════════════
  // 包D-5 权益批次退款（任务书场景 15/16/17/21）
  // ═══════════════════════════════════════════════════════════

  await scene('S11 未使用充值批次全额退款：可退=实付，卡按发放量冲减，订单落7', async () => {
    // 新充一笔纯金额套餐 → 建出一个「分文未动」的可退批次。
    // 卡上已有别的批次（S1/S2 用掉的那些），故本场景同时证明「按批次退而不是按卡聚合值退」
    const created = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(ctx.cardId), requestId: rid() }, ctx.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
    const order = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [created.orderNo])
    ok(order && order.ORDER_STATUS === 4, '充值单应已完成', `status=${order && order.ORDER_STATUS}`)
    const batch = await one('SELECT * FROM ws_card_entitlement_batch WHERE ORDER_ID=?', [order.ID])
    ok(batch, '入账必须同事务建出权益批次（包D-3）')
    ok(Number(batch.PAY_AMOUNT_FEN) === 5000 && Number(batch.REMAIN_AMOUNT_FEN) === 5000, '新批次实付与剩余都应是 5000 分', `pay=${batch.PAY_AMOUNT_FEN} remain=${batch.REMAIN_AMOUNT_FEN}`)
    const before = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])

    // 预览是运营点下去之前唯一的依据：它给出的金额必须与受理时算定的完全一致，
    // 不一致意味着「看到的」和「退掉的」是两回事
    const pre = await apiOk('/order/after-sale/refund/recharge/preview', { orderId: String(order.ID) }, ctx.ops)
    ok(pre && pre.refundable === true, '未使用批次应可退', JSON.stringify(pre).slice(0, 160))
    ok(Number(pre.refundableFen) === 5000 && Number(pre.reverseFen) === 5000, '预览：可退 5000 分、冲减 5000 分', `refundable=${pre.refundableFen} reverse=${pre.reverseFen}`)
    ok(pre.cardWillClose === false, '9001 卡上还有别的批次，退款不应注销卡', `cardWillClose=${pre.cardWillClose}`)

    const refundId = await apiOk('/order/after-sale/refund/recharge', { orderId: String(order.ID), handleRemark: '验收：未使用充值全额退款（场景15）' }, ctx.ops)
    const refund = await one('SELECT * FROM ws_refund WHERE ID=?', [refundId])
    ok(refund && Number(refund.REFUND_AMOUNT) === 5000, '未使用 ⇒ 可退恰等于实付', `refundAmount=${refund && refund.REFUND_AMOUNT}`)
    const locked = await one('SELECT * FROM ws_card_entitlement_batch WHERE ID=?', [batch.ID])
    ok(locked.BATCH_STATUS === 2, '受理后批次必须立刻退款锁定', `status=${locked.BATCH_STATUS}`)

    const outcome = await apiOk('/mini/refund-sim/notify', { refundNo: refund.REFUND_NO }, ctx.ops)
    ok(outcome === 'PROCESSED', '退款事实应被正常消费', `outcome=${outcome}`)

    const settled = await one('SELECT * FROM ws_card_entitlement_batch WHERE ID=?', [batch.ID])
    ok(settled.BATCH_STATUS === 3 && Number(settled.REMAIN_AMOUNT_FEN) === 0, '批次应落已退款且剩余清零', `status=${settled.BATCH_STATUS} remain=${settled.REMAIN_AMOUNT_FEN}`)
    const after = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.cardId])
    const drop = Number(before.BALANCE_AMOUNT) - Number(after.BALANCE_AMOUNT)
    ok(drop === 5000, '卡应按批次剩余冲减 5000 分', `实际冲减 ${drop}`)
    const paid = await one('SELECT * FROM ws_order WHERE ID=?', [order.ID])
    ok(paid.ORDER_STATUS === 7, '整批未用 ⇒ 订单 7已退款', `status=${paid.ORDER_STATUS}`)
    const action = await one('SELECT * FROM ws_after_sale_action WHERE ORDER_ID=?', [order.ID])
    ok(action.ACTION_STATUS === 3, '售后动作应落已完成', `status=${action.ACTION_STATUS}`)
    const flow = await one('SELECT * FROM ws_wallet_flow WHERE BIZ_IDEMPOTENCY_KEY=?', [`AFTERSALE:${action.AFTER_SALE_NO}`])
    ok(flow && Number(flow.AMOUNT_CHANGE) === -5000, '冲减流水必须是负向且恰一条', `change=${flow && flow.AMOUNT_CHANGE}`)
    ok(Number(flow.AMOUNT_AFTER) === Number(after.BALANCE_AMOUNT), '流水 AFTER 必须等于卡终值', `${flow.AMOUNT_AFTER} vs ${after.BALANCE_AMOUNT}`)
    return `order=${created.orderNo} 退款=5000分 冲减=5000分 订单=7 批次=3`
  })

  await scene('S12 备好一张单批次可辨的新卡：首购水量套餐 + 充纯金额包 + 配送消费一部分', async () => {
    // 换用 9002（种子里无卡）作为卡主：这张卡上只有本场景建的两个批次，
    // 「先扣谁、退多少」才有确定答案。9001 的卡上批次太多，折算断言会依赖历史。
    const member = await miniLogin(MEMBER_PHONE)
    ctx.member = member
    let card = await one('SELECT * FROM ws_card WHERE USER_ID=9002 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
    if (!card) {
      const first = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, member)
      await apiOk('/mini/pay-sim/pay', { orderNo: first.orderNo }, member)
      ctx.firstOrderNo = first.orderNo
      card = await one('SELECT * FROM ws_card WHERE USER_ID=9002 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
      ok(card, '首次购卡应发出水卡')
    }
    else {
      const issue = await one('SELECT * FROM ws_order WHERE ID=?', [card.ISSUE_ORDER_ID])
      ctx.firstOrderNo = issue && issue.ORDER_NO
    }
    ctx.memberCardId = card.ID
    const firstOrder = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [ctx.firstOrderNo])
    ctx.firstOrderId = firstOrder.ID
    const waterBatch = await one('SELECT * FROM ws_card_entitlement_batch WHERE ORDER_ID=?', [firstOrder.ID])
    ok(waterBatch && waterBatch.SOURCE_TYPE === 1, '首购批次来源应为 1首次购卡', `source=${waterBatch && waterBatch.SOURCE_TYPE}`)
    ctx.waterBatchId = waterBatch.ID

    // 纯金额包为配送费提供余额：水量套餐到账余额恒 0，缺它连配送单都下不了
    if (Number(card.BALANCE_AMOUNT) < 400) {
      const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(card.ID), requestId: rid() }, member)
      await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, member)
      ctx.cashOrderNo = top.orderNo
    }
    else {
      const exist = await one(
        'SELECT o.* FROM ws_order o JOIN ws_card_entitlement_batch b ON b.ORDER_ID=o.ID '
        + 'WHERE b.CARD_ID=? AND b.SOURCE_TYPE=2 AND b.BATCH_STATUS=1 ORDER BY o.ID DESC LIMIT 1',
        [card.ID],
      )
      ok(exist, '应能定位到一笔可退的充值单')
      ctx.cashOrderNo = exist.ORDER_NO
    }
    const cashOrder = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [ctx.cashOrderNo])
    ctx.cashOrderId = cashOrder.ID
    ctx.cashBatchId = (await one('SELECT * FROM ws_card_entitlement_batch WHERE ORDER_ID=?', [cashOrder.ID])).ID

    // payWay=3 混合结算：水费扣 BALANCE_ML（只有水量批次有）、配送费扣 BALANCE_AMOUNT（只有金额批次有）
    const created = await apiOk('/mini/delivery/order/create', {
      requestId: rid(),
      cardId: String(card.ID),
      stationId: STATION_ID,
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 2,
      planReturnCount: 0,
      receiveAddress: '验收专区2号',
      receivePhone: MEMBER_PHONE,
      deliveryMode: 1,
      payWay: 3,
    }, member)
    const head = created && created.order && created.order.order
    ok(head && head.orderNo, 'payWay=3 创单应返回订单详情', JSON.stringify(created).slice(0, 160))
    const dOrder = await one('SELECT * FROM ws_order WHERE ORDER_NO=?', [head.orderNo])
    const task = await one('SELECT * FROM ws_delivery_task WHERE ORDER_ID=?', [dOrder.ID])
    await deliverAndSign(ctx, task, 2)
    const signed = await one('SELECT * FROM ws_order WHERE ID=?', [dOrder.ID])
    ok(signed.ORDER_STATUS === 4, '签收后配送单应已完成（否则它会作为在途单阻止卡注销）', `status=${signed.ORDER_STATUS}`)

    const allocs = await q('SELECT * FROM ws_entitlement_allocation WHERE BIZ_KEY=? ORDER BY ALLOC_SEQ', [`DELIVERY:${head.orderNo}`])
    ok(allocs.length === 2, '两维应各自摊到自己的批次上：水量→首购批次，配送费→金额批次', `分摊行数=${allocs.length}`)
    const mlAlloc = allocs.find(a => Number(a.ALLOC_WATER_ML) > 0)
    const fenAlloc = allocs.find(a => Number(a.ALLOC_AMOUNT_FEN) > 0)
    ok(mlAlloc && String(mlAlloc.BATCH_ID) === String(ctx.waterBatchId) && Number(mlAlloc.ALLOC_WATER_ML) === 40000, '水量 40000mL 应摊在首购水量批次上', JSON.stringify(mlAlloc))
    ok(fenAlloc && String(fenAlloc.BATCH_ID) === String(ctx.cashBatchId) && Number(fenAlloc.ALLOC_AMOUNT_FEN) === 400, '配送费 400 分应摊在纯金额批次上', JSON.stringify(fenAlloc))
    const cardNow = await one('SELECT * FROM ws_card WHERE ID=?', [card.ID])
    const sumFen = await one('SELECT IFNULL(SUM(REMAIN_AMOUNT_FEN),0) AS s FROM ws_card_entitlement_batch WHERE CARD_ID=? AND DATA_STATUS=0', [card.ID])
    const sumMl = await one('SELECT IFNULL(SUM(REMAIN_WATER_ML),0) AS s FROM ws_card_entitlement_batch WHERE CARD_ID=? AND DATA_STATUS=0', [card.ID])
    ok(Number(sumFen.s) === Number(cardNow.BALANCE_AMOUNT) && Number(sumMl.s) === Number(cardNow.BALANCE_ML), '不变式：逐卡批次剩余合计恒等于卡聚合值', `批次 ${sumFen.s}/${sumMl.s} vs 卡 ${cardNow.BALANCE_AMOUNT}/${cardNow.BALANCE_ML}`)
    return `card=${card.ID} 首购批次=${ctx.waterBatchId} 金额批次=${ctx.cashBatchId} `
      + `消费 40000mL+400分 后 卡=${cardNow.BALANCE_AMOUNT}分/${cardNow.BALANCE_ML}mL`
  })

  await scene('S13 退款锁定期间消费不得穿透：卡上有余额也扣不动，且分文未动', async () => {
    const refundId = await apiOk('/order/after-sale/refund/recharge', { orderId: String(ctx.cashOrderId), handleRemark: '验收：部分使用后按折算退款（场景16/21）' }, ctx.ops)
    const refund = await one('SELECT * FROM ws_refund WHERE ID=?', [refundId])
    ok(Number(refund.REFUND_AMOUNT) === 4600, '纯金额套餐：可退 = 实付 5000 − 已消费 400', `refundAmount=${refund.REFUND_AMOUNT}`)
    const locked = await one('SELECT * FROM ws_card_entitlement_batch WHERE ID=?', [ctx.cashBatchId])
    ok(locked.BATCH_STATUS === 2 && Number(locked.REMAIN_AMOUNT_FEN) === 4600, '批次锁定但剩余不动（锁定 ≠ 清零）', `status=${locked.BATCH_STATUS} remain=${locked.REMAIN_AMOUNT_FEN}`)

    const before = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.memberCardId])
    ok(Number(before.BALANCE_AMOUNT) >= 400, '卡面余额此刻仍够付配送费', `余额=${before.BALANCE_AMOUNT}`)
    const msg = await apiFail('/mini/delivery/order/create', {
      requestId: rid(),
      cardId: String(ctx.memberCardId),
      stationId: STATION_ID,
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 1,
      planReturnCount: 0,
      receiveAddress: '验收专区2号',
      receivePhone: MEMBER_PHONE,
      deliveryMode: 1,
      payWay: 3,
    }, ctx.member)
    // 必须钉住这条拒因：余额不足/参数错误之类的其它 4xx 都会让本场景假绿
    ok(/批次剩余不足/.test(msg), '拒绝原因应来自权益批次锁定而非卡余额', `实际：${msg}`)
    const after = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.memberCardId])
    ok(Number(after.BALANCE_AMOUNT) === Number(before.BALANCE_AMOUNT)
      && Number(after.BALANCE_ML) === Number(before.BALANCE_ML), '被拒的消费必须零副作用', `${before.BALANCE_AMOUNT}/${before.BALANCE_ML} → ${after.BALANCE_AMOUNT}/${after.BALANCE_ML}`)

    const outcome = await apiOk('/mini/refund-sim/notify', { refundNo: refund.REFUND_NO }, ctx.ops)
    ok(outcome === 'PROCESSED', '退款事实应被正常消费', `outcome=${outcome}`)
    const settled = await one('SELECT * FROM ws_card_entitlement_batch WHERE ID=?', [ctx.cashBatchId])
    ok(settled.BATCH_STATUS === 3 && Number(settled.REMAIN_AMOUNT_FEN) === 0, '结算后批次落已退款且清零', `status=${settled.BATCH_STATUS} remain=${settled.REMAIN_AMOUNT_FEN}`)
    const cashOrder = await one('SELECT * FROM ws_order WHERE ID=?', [ctx.cashOrderId])
    ok(cashOrder.ORDER_STATUS === 8, '用过一部分 ⇒ 订单 8部分退款', `status=${cashOrder.ORDER_STATUS}`)
    const cardAfter = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.memberCardId])
    ok(Number(cardAfter.BALANCE_AMOUNT) === 0, '余额权益应被全额冲减', `余额=${cardAfter.BALANCE_AMOUNT}`)
    ok(cardAfter.CARD_STATUS === 1, '非首购退款不注销卡（卡上还有水量权益）', `status=${cardAfter.CARD_STATUS}`)
    return `锁定期间下单被拒：${msg}；结算后 批次=3 订单=8 卡余额=0 卡状态=1`
  })

  await scene('S14 首购水量套餐按 ceil 折算退款 → 权益归零后卡转注销', async () => {
    const pre = await apiOk('/order/after-sale/refund/recharge/preview', { orderId: String(ctx.firstOrderId) }, ctx.ops)
    ok(pre.refundable === true && Number(pre.refundableFen) === 9200, '预览应与受理同口径：可退 9200 分', `refundable=${pre.refundable} fen=${pre.refundableFen}`)
    ok(pre.cardWillClose === true, '预览应预告「退完这笔卡会注销」——运营点之前必须看得到', `cardWillClose=${pre.cardWillClose}`)

    const refundId = await apiOk('/order/after-sale/refund/recharge', { orderId: String(ctx.firstOrderId), handleRemark: '验收：首购水量套餐折算退款（场景16/17）' }, ctx.ops)
    const refund = await one('SELECT * FROM ws_refund WHERE ID=?', [refundId])
    // usedPrincipal = ceil(10000 × 40000 ÷ 500000) = 800；可退 = 10000 − 800 = 9200
    ok(Number(refund.REFUND_AMOUNT) === 9200, '水量套餐折算：ceil 让已用本金向上走、退款额向下走', `refundAmount=${refund.REFUND_AMOUNT}`)
    const outcome = await apiOk('/mini/refund-sim/notify', { refundNo: refund.REFUND_NO }, ctx.ops)
    ok(outcome === 'PROCESSED', '退款事实应被正常消费', `outcome=${outcome}`)

    const settled = await one('SELECT * FROM ws_card_entitlement_batch WHERE ID=?', [ctx.waterBatchId])
    ok(settled.BATCH_STATUS === 3 && Number(settled.REMAIN_WATER_ML) === 0, '批次应落已退款且水量清零', `status=${settled.BATCH_STATUS} remain=${settled.REMAIN_WATER_ML}`)
    const firstOrder = await one('SELECT * FROM ws_order WHERE ID=?', [ctx.firstOrderId])
    ok(firstOrder.ORDER_STATUS === 8, '用过一部分 ⇒ 订单 8部分退款', `status=${firstOrder.ORDER_STATUS}`)
    const card = await one('SELECT * FROM ws_card WHERE ID=?', [ctx.memberCardId])
    ok(Number(card.BALANCE_AMOUNT) === 0 && Number(card.BALANCE_ML) === 0, '卡权益应归零', `${card.BALANCE_AMOUNT}分/${card.BALANCE_ML}mL`)
    ok(card.CARD_STATUS === 4, '首购退款且无其他批次/成员/在途单 ⇒ 卡转注销', `status=${card.CARD_STATUS}`)
    const stillThere = await one('SELECT COUNT(*) AS c FROM ws_card WHERE ID=?', [ctx.memberCardId])
    ok(Number(stillThere.c) === 1, '注销只改状态，绝不删卡')
    const alloc = await one('SELECT * FROM ws_entitlement_allocation WHERE BATCH_ID=?', [ctx.waterBatchId])
    ok(alloc && alloc.REVERSED_FLAG === 1, '该批次上的消费分摊应被标记为已冲正', `reversed=${alloc && alloc.REVERSED_FLAG}`)
    return `首购退款 9200 分（已用 40000mL 折 800 分）→ 批次=3 订单=8 卡权益归零 卡状态=4注销`
  })

  await pool.end()
  const pass = results.filter(r => r.pass).length
  // 变量名刻意不叫 ok：脚本里 ok() 是断言函数，同名会在同一作用域造成暂时性死区，
  // 表现是全部场景报「Cannot access 'ok' before initialization」，而真正的失败原因被淹没。
  const allPassed = pass === results.length && results.length === EXPECTED_SCENARIOS

  // result.json 只写结论与证据摘要。刻意不写 token、手机号原文、口令、
  // 退款原始报文与本机绝对路径 —— 验收产物会被四处传阅。
  const outDir = path.resolve(__dirname, '../../docs/acceptance/after-sale/runs')
  fs.mkdirSync(outDir, { recursive: true })
  const batch = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
  const report = {
    chain: 'E2E-04',
    batch,
    expectedScenarios: EXPECTED_SCENARIOS,
    executedScenarios: results.length,
    passed: pass,
    pass: allPassed,
    scenarios: results.map(r => ({ name: r.name, pass: r.pass, evidence: r.evidence })),
  }
  // 出口扫描：写盘前统一体检。第一版就漏过一次——「无明文 13999990001」这句
  // 用来证明脱敏生效的证据文本本身把手机号写进了验收产物。人眼靠不住，改用机器闸。
  const serialized = JSON.stringify(report, null, 2)
  const forbidden = [
    { name: '手机号原文', re: /1[3-9]\d{9}/ },
    { name: '本机绝对路径', re: /\/(Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
    { name: '退款原始报文字段', re: /rawBody|RAW_BODY/ },
  ]
  // Token 与凭据没有稳定格式，不能用固定头名代替扫描。直接比对本轮运行时真实值，
  // 即使失败响应意外回显动态 Token 或登录凭据，也会在落盘前被拦下。
  const runtimeSecrets = [
    { name: '卡主 Token', value: ctx.owner && ctx.owner.tokenValue },
    { name: '配送员 Token', value: ctx.courier && ctx.courier.tokenValue },
    { name: '运营 Token', value: ctx.ops && ctx.ops.tokenValue },
    { name: '运营登录凭据', value: OPS_PWD },
    { name: '数据库口令', value: DB.password },
  ].filter(item => typeof item.value === 'string' && item.value.length > 0)
  const leaked = [
    ...forbidden.filter(f => f.re.test(serialized)),
    ...runtimeSecrets.filter(secret => serialized.includes(secret.value)),
  ]
  if (leaked.length) {
    console.error(`验收产物含禁止信息，拒绝落盘：${leaked.map(f => f.name).join('、')}`)
    process.exit(3)
  }
  fs.writeFileSync(path.join(outDir, `${batch}-result.json`), serialized)

  if (results.length !== EXPECTED_SCENARIOS) {
    console.error(`\n场景数不符：期望 ${EXPECTED_SCENARIOS}，实际执行 ${results.length} —— 判 FAIL（禁止 0/0 PASS）`)
  }
  console.log(`\n=== E2E-04 售后验收：${pass}/${results.length} PASS（期望场景数 ${EXPECTED_SCENARIOS}）===`)
  console.log(`结果已写入 docs/acceptance/after-sale/runs/${batch}-result.json`)
  process.exit(allPassed ? 0 : 1)
}

main().catch(async (e) => {
  console.error('验收中断：', e.message)
  if (pool) {
    await pool.end()
  }
  process.exit(1)
})
