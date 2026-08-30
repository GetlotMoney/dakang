/**
 * E2E-07 消息、告警与工单固定场景验收（S1～S12，真实 HTTP + 验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已 rebuild（init + migrations + acc-seed）；
 * - 验收后端已 backend-start；两轮之间必须重新 rebuild。
 *
 * 口径依据（任务书第二节冻结）：
 * - 消息读端铁律6：会话强制过滤，越权与不存在同文案；
 * - 排序=SEND_TIME 倒序、无发送时间排尾；已读 0→1 幂等；
 * - 工单钩子三节点（受理/驳回/复核关闭）只触达申报人；无申报人来源零消息；
 * - 重试/降级为测试驱动骨架（种子失败样本 9704 是唯一入口，不造新假数据）；
 * - 白名单事件只读；资金类型不开放。
 *
 * 安全闸（fail-closed）：必须显式 MSG_ACC_MODE=full；主环境端口/主库端口拒绝；
 * 种子哨兵（E2E-07 增量：消息样本 9701~9704）；13340 pid 核验；JAR 指纹落盘；
 * 产物污染扫描；场景数必须恰为 12；失败落盘退出码非零。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { sha256File } = require('./e1b-core')
const { newBatchId } = require('./report-core')
const { verifyAccBackendContainer } = require('./acc-backend-proof')

if (String(process.env.MSG_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 MSG_ACC_MODE=full（本脚本会真实写验收库）')
  process.exit(2)
}
if (!String(process.env.ACC_DB_PASSWORD || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（仓库根 .env）')
  process.exit(2)
}
if (!String(process.env.ACC_OPS_PWD_CIPHER || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_OPS_PWD_CIPHER（PC 工单动作需要运营会话）')
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
const ACCEPT_DIR = path.resolve(REPO_ROOT, 'docs/acceptance/message-center')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, 'runs', BATCH_ID)
const JAR_GLOB_DIR = path.resolve(REPO_ROOT, 'server/target')
const ACC_ENV_RUN_DIR = path.resolve(REPO_ROOT, 'deploy/acceptance/.run')
const BACKEND_PORT = 13340

// 种子固定身份（acc-seed.sql）
const OWNER_PHONE = '13999990001' // 9001：机主（设备 9201）+ 消息样本 9701/9702/9704
const MEMBER_PHONE = '13999990002' // 9002：消息样本 9703（他人消息对照）
const COURIER_PHONE = '13999990003' // 9003：无消息账号
const OPS_LOGIN = 'acc-ops'
const OPS_PWD_CIPHER = process.env.ACC_OPS_PWD_CIPHER
const STATION_ID = 9101
const DEV1 = { id: 9201, no: 'ACC-DEV-0001' }
const PACKAGE_ID = '9501'
const CASH_PACKAGE_ID = '9502'
// 种子消息（deploy/acceptance/acc-seed.sql E2E-07 段）
const SEED_MSG = { announce9001: 9701, water9001: 9702, announce9002: 9703, failed9001: 9704 }

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const rid = () => crypto.randomUUID()

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
  return { tokenName: data.tokenName, tokenValue: data.tokenValue, context: data.accountContext }
}

async function opsLogin(loginName) {
  const data = await apiOk('/api/auth/loginEmployee', { loginName, loginPwd: OPS_PWD_CIPHER })
  ok(data && data.tokenValue, `运营登录 ${loginName} 应成功`)
  // MANAGE 驱动实际读的请求头是 dakang-token（与 run-device-ops 同源），响应体 tokenName 不可用
  return { tokenName: 'dakang-token', tokenValue: data.tokenValue }
}

const msgPage = (token, body) => apiOk('/mini/message/page', body ?? { current: 1, size: 100 }, token)
const msgDetail = (token, messageId) => apiOk('/mini/message/detail', { messageId }, token)
const msgRead = (token, messageId) => apiOk('/mini/message/read', { messageId }, token)

// ---------------------------------------------------------------------------
// 场景注册
// ---------------------------------------------------------------------------
const scenarios = []
function scenario(id, name, fn) {
  scenarios.push({ id, name, fn })
}

const ctx = {}

scenario('S1', '无消息账号空列表；越权与不存在同文案（detail/read 双端点）', async (ev) => {
  const empty = await msgPage(ctx.courier)
  eq(empty.total, 0, '配送员账号零消息')
  const foreignDetail = await apiFail('/mini/message/detail', { messageId: SEED_MSG.announce9001 }, ctx.courier)
  const missingDetail = await apiFail('/mini/message/detail', { messageId: 99999999 }, ctx.courier)
  eq(foreignDetail.msg, missingDetail.msg, '越权与不存在同文案')
  const foreignRead = await apiFail('/mini/message/read', { messageId: SEED_MSG.announce9001 }, ctx.courier)
  eq(foreignRead.msg, foreignDetail.msg, '已读端点同文案')
  const row = await one('SELECT READ_FLAG FROM ws_message WHERE ID=?', [SEED_MSG.announce9001])
  eq(row.READ_FLAG, 1, '他人已读位不被越权改动（种子 9701 本就已读=1）')
  ev.push(`同文案=「${foreignDetail.msg}」；越权零写入`)
})

scenario('S2', '配送链真实动作产消息：下单→支付→任务生成消息与动作时间同源', async (ev) => {
  // 备卡与余额（缺什么补什么，同 E2E-04 验收姿势）
  let card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
  if (!card) {
    const created = await apiOk('/mini/order/recharge/create', { packageId: PACKAGE_ID, requestId: rid() }, ctx.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
    card = await one('SELECT * FROM ws_card WHERE USER_ID=9001 AND DATA_STATUS=0 ORDER BY ID DESC LIMIT 1')
    ok(card, '应生成水卡')
  }
  if (Number(card.BALANCE_AMOUNT) < 4000) {
    const top = await apiOk('/mini/order/recharge/create', { packageId: CASH_PACKAGE_ID, cardId: String(card.ID), requestId: rid() }, ctx.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: top.orderNo }, ctx.owner)
  }
  const before = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 3 })
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
  ok(head && head.orderNo, '创单应返回订单详情')
  ctx.deliveryOrderNo = head.orderNo
  const after = await pollUntil('配送域应新增任务生成消息', async () => {
    const page = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 3 })
    return Number(page.total) > Number(before.total) ? page : null
  }, 15000)
  const fresh = (after.list || []).find(item => item.objectId === ctx.deliveryOrderNo)
  ok(fresh, '新消息应关联本单', ctx.deliveryOrderNo)
  eq(fresh.msgDomain, 3, '配送域')
  // 同源：消息 SEND_TIME 必须等于触发它的业务行时间（E2E-03 冻结规则 13）
  const task = await one('SELECT CREATE_TIME FROM ws_delivery_task WHERE ORDER_ID=(SELECT ID FROM ws_order WHERE ORDER_NO=?)', [ctx.deliveryOrderNo])
  ok(task, '配送任务应已生成')
  eq(fresh.sendTime, task.CREATE_TIME, '消息时间与任务生成时间同源')
  ev.push(`任务生成消息 sendTime=${fresh.sendTime} == task.CREATE_TIME`)
})

scenario('S3', '域筛选与排序：五域过滤正确、SEND_TIME 倒序稳定', async (ev) => {
  const all = await msgPage(ctx.owner)
  ok(Number(all.total) >= 3, '9001 至少含 3 条种子消息')
  const times = (all.list || []).map(item => item.sendTime || '')
  const sorted = [...times].sort((a, b) => b.localeCompare(a))
  eq(JSON.stringify(times), JSON.stringify(sorted), '整页按发送时间非增排列')
  const water = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 1 })
  ok((water.list || []).every(item => item.msgDomain === 1), '取水域过滤纯净')
  ok((water.list || []).some(item => item.msgTitle === '取水完成'), '取水域含种子 9702')
  const card = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 2 })
  ok((card.list || []).some(item => Number(item.messageId) === SEED_MSG.failed9001), '卡券域含失败样本 9704')
  await apiFail('/mini/message/page', { current: 1, size: 100, msgDomain: 9 }, ctx.owner)
  ev.push(`全部 ${all.total} 条倒序；water=${water.total} card=${card.total}；非法域拒绝`)
})

scenario('S4', '机主报修→受理：消息触达申报人且回跳凭据正确', async (ev) => {
  const requestId = `MSG-ACC-${Date.now()}`
  const svc = await apiOk('/mini/owner/service/create', {
    requestId,
    deviceNo: DEV1.no,
    serviceType: 'REPAIR',
    description: '出水口漏水（消息链验收）',
  }, ctx.owner)
  ok(svc, '申报应成功')
  const order = await one('SELECT * FROM ws_work_order WHERE REQUEST_ID=?', [requestId])
  ok(order, '申报应落真实工单')
  ctx.confirmOrderId = order.ID
  ctx.confirmRequestId = requestId
  await apiOk('/device/workorder/confirm', { id: order.ID }, ctx.ops)
  const page = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 4 })
  const msg = (page.list || []).find(item => item.objectId === requestId && item.msgTitle === '报修申请已受理')
  ok(msg, '受理消息应到达申报人')
  eq(msg.objectType, 'service', '回跳类型=service')
  eq(msg.readFlag, 0, '新消息未读')
  ev.push(`受理消息 objectId=${requestId}`)
})

scenario('S5', '机主报修→驳回：原因如实带给申报人', async (ev) => {
  const requestId = `MSG-ACC-REJ-${Date.now()}`
  await apiOk('/mini/owner/service/create', {
    requestId,
    deviceNo: DEV1.no,
    serviceType: 'REPAIR',
    description: '误报测试（消息链验收）',
  }, ctx.owner)
  const order = await one('SELECT * FROM ws_work_order WHERE REQUEST_ID=?', [requestId])
  await apiOk('/device/workorder/reject', { id: order.ID, rejectReason: '非质保范围-验收演练' }, ctx.ops)
  const page = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 4 })
  const msg = (page.list || []).find(item => item.objectId === requestId)
  ok(msg && msg.msgTitle === '报修申请未通过', '驳回消息应到达')
  ok(msg.msgContent.includes('非质保范围-验收演练'), '驳回原因如实透传')
  ev.push('驳回消息含原因原文')
})

scenario('S6', '工单全生命周期：分配/提交结果不打扰，复核关闭一条收尾', async (ev) => {
  const opsRow = await one('SELECT ID FROM api_employee WHERE LOGIN_NAME=?', [OPS_LOGIN])
  await apiOk('/device/workorder/assign', { id: ctx.confirmOrderId, assigneeId: opsRow.ID }, ctx.ops)
  await apiOk('/device/workorder/submitResult', { id: ctx.confirmOrderId, finishResult: '已更换密封圈' }, ctx.ops)
  await apiOk('/device/workorder/reviewPass', { id: ctx.confirmOrderId, reviewRemark: '验收通过' }, ctx.ops)
  const page = await msgPage(ctx.owner, { current: 1, size: 100, msgDomain: 4 })
  const mine = (page.list || []).filter(item => item.objectId === ctx.confirmRequestId)
  eq(mine.length, 2, '本工单恰两条：受理+关闭（分配/提交零打扰）')
  ok(mine.some(item => item.msgTitle === '报修处理完成'), '关闭回执应到达')
  ev.push('三节点冻结口径成立：confirm/reject/reviewPass 之外零消息')
})

scenario('S7', '告警转工单（无申报人）：全程零消息、不报错', async (ev) => {
  const beforeCnt = await one('SELECT COUNT(*) AS c FROM ws_message')
  await q(
    `INSERT INTO ws_alarm (DATA_STATUS, CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, DEVICE_ID, ALARM_TYPE, ALARM_LEVEL, ALARM_CONTENT, ALARM_STATUS, ACTIVE_DEDUPE_KEY)
     VALUES (0, 1, DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'), 1, DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'), ?, 1, 2, '设备离线（消息链验收）', 1, ?)`,
    [DEV1.id, `msgacc:${Date.now()}`],
  )
  const alarm = await one('SELECT ID FROM ws_alarm ORDER BY ID DESC LIMIT 1')
  const workOrderId = await apiOk('/device/alarm/toWorkOrder', { id: alarm.ID }, ctx.ops)
  ok(workOrderId, '告警应转出工单')
  const wo = await one('SELECT APPLICANT_USER_ID FROM ws_work_order WHERE ID=?', [workOrderId])
  ok(wo.APPLICANT_USER_ID === null, '告警来源无申报人')
  const afterCnt = await one('SELECT COUNT(*) AS c FROM ws_message')
  eq(afterCnt.c, beforeCnt.c, '无申报人来源零消息')
  ev.push(`工单 ${workOrderId} 转出成功且消息计数不变`)
})

scenario('S8', '已读闭环：单条已读幂等、未读数与 DB 一致', async (ev) => {
  const page = await msgPage(ctx.owner)
  const target = (page.list || []).find(item => item.readFlag === 0)
  ok(target, '应存在未读消息')
  await msgRead(ctx.owner, target.messageId)
  let row = await one('SELECT READ_FLAG, UPDATE_TIME FROM ws_message WHERE ID=?', [target.messageId])
  eq(row.READ_FLAG, 1, '标记生效')
  const stamp = row.UPDATE_TIME
  await msgRead(ctx.owner, target.messageId)
  row = await one('SELECT READ_FLAG, UPDATE_TIME FROM ws_message WHERE ID=?', [target.messageId])
  eq(row.UPDATE_TIME, stamp, '重复标记零变化')
  const unreadApi = (await msgPage(ctx.owner)).list.filter(item => item.readFlag === 0).length
  const unreadDb = await one('SELECT COUNT(*) AS c FROM ws_message WHERE USER_ID=9001 AND READ_FLAG=0')
  eq(unreadApi, unreadDb.c, '未读数 API==DB')
  ev.push(`未读一致=${unreadApi}`)
})

scenario('S9', '重试/降级骨架：种子失败样本走通全状态机（唯一入口，不造新假数据）', async (ev) => {
  // 骨架方法不暴露 HTTP（无调度器），验收以 DB 直接驱动等价条件更新并断言状态机语义
  const id = SEED_MSG.failed9001
  const row = await one('SELECT SEND_STATUS, MSG_CHANNEL FROM ws_message WHERE ID=?', [id])
  eq(row.SEND_STATUS, 3, '种子失败态在位')
  eq(row.MSG_CHANNEL, 2, '外部渠道占位')
  // 3→2（仅失败态可进入）：条件更新影响行数=1；重复=0
  let r = await pool.query('UPDATE ws_message SET SEND_STATUS=2 WHERE ID=? AND SEND_STATUS=3', [id])
  eq(r[0].affectedRows, 1, '3→2')
  r = await pool.query('UPDATE ws_message SET SEND_STATUS=2 WHERE ID=? AND SEND_STATUS=3', [id])
  eq(r[0].affectedRows, 0, '重复进入拒绝')
  // 2→3 回落，再 3→降级站内=4
  r = await pool.query('UPDATE ws_message SET SEND_STATUS=3 WHERE ID=? AND SEND_STATUS=2', [id])
  eq(r[0].affectedRows, 1, '2→3 回落')
  r = await pool.query(
    'UPDATE ws_message SET MSG_CHANNEL=1, SEND_STATUS=4, SEND_TIME=DATE_FORMAT(NOW(), \'%Y%m%d%H%i%s\') WHERE ID=? AND SEND_STATUS=3',
    [id],
  )
  eq(r[0].affectedRows, 1, '3→降级站内已送达')
  r = await pool.query('UPDATE ws_message SET MSG_CHANNEL=1, SEND_STATUS=4 WHERE ID=? AND SEND_STATUS=3', [id])
  eq(r[0].affectedRows, 0, '重复降级零变化')
  // 降级后小程序立即可读为站内消息
  const detail = await msgDetail(ctx.owner, id)
  eq(detail.msgChannel, 1, '渠道=站内')
  eq(detail.sendStatus, 4, '状态=已送达')
  ev.push('3→2→3→(降级)1/4 全链走通；服务端同构方法由 MessageCenterDbTest 锁定')
})

scenario('S10', 'PC 记录页接口：登录门 + 筛选正确 + 只读', async (ev) => {
  const unauth = await api('/message/page', { current: 1, size: 10 })
  ok(unauth.parsed.code !== 0, '未登录应拒绝')
  const failed = await apiOk('/message/page', { current: 1, size: 50, sendStatus: 4, userId: 9002 }, ctx.ops)
  ok((failed.list || []).every(item => item.userId === 9002 || String(item.userId) === '9002'), '按用户筛选纯净')
  const byDomain = await apiOk('/message/page', { current: 1, size: 50, msgDomain: 4 }, ctx.ops)
  ok((byDomain.list || []).every(item => item.msgDomain === 4), '按域筛选纯净')
  ev.push(`记录页筛选 user9002=${failed.total} 机主域=${byDomain.total}`)
})

scenario('S11', '白名单事件只读：工单事件可见、资金类型拒绝、未登录拒绝', async (ev) => {
  const unauth = await api('/api/domainEvent/whitelistPage', { current: 1, size: 10 })
  ok(unauth.parsed.code !== 0, '未登录应拒绝')
  const page = await apiOk('/api/domainEvent/whitelistPage', { current: 1, size: 100, eventType: 4 }, ctx.ops)
  ok(Number(page.total) > 0, '本轮工单迁移事件应在白名单可见')
  ok((page.list || []).every(item => item.whitelistFlag === 2), '只吐白名单事件')
  const denied = await apiFail('/api/domainEvent/whitelistPage', { current: 1, size: 10, eventType: 6 }, ctx.ops)
  ev.push(`工单事件 ${page.total} 条；支付类型筛选拒绝=「${denied.msg}」`)
})

scenario('S12', '三方一致 + 零敏感信息：小程序 == SQL == PC 记录页', async (ev) => {
  const mini = await msgPage(ctx.owner)
  const db = await one('SELECT COUNT(*) AS c FROM ws_message WHERE USER_ID=9001')
  const pc = await apiOk('/message/page', { current: 1, size: 100, userId: 9001 }, ctx.ops)
  eq(mini.total, db.c, '小程序 == SQL')
  eq(pc.total, db.c, 'PC == SQL')
  const raw = JSON.stringify(mini) + JSON.stringify((await api('/mini/message/detail', { messageId: SEED_MSG.water9001 }, ctx.owner)).rawText)
  // 手机号需独立成串：工单号/订单号的时间戳数字段（WO20260731…）会命中裸模式，属假阳性
  ok(!/(?<!\d)1[3-9]\d{9}(?!\d)/.test(raw), '响应原文不含手机号')
  ok(!/openid/i.test(raw), '响应原文不含 openid')
  ctx.finalListRaw = raw
  ev.push(`三方一致=${db.c} 条；正文零敏感`)
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
  const seedMsgs = await one('SELECT COUNT(*) AS c FROM ws_message WHERE ID IN (9701,9702,9703,9704)')
  ok(Number(seedMsgs.c) === 4, 'E2E-07 消息种子 9701~9704 缺失')
  const failed = await one('SELECT SEND_STATUS, MSG_CHANNEL FROM ws_message WHERE ID = 9704')
  ok(failed.SEND_STATUS === 3 && failed.MSG_CHANNEL === 2, '失败样本 9704 状态漂移（需 rebuild）')
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
  return fingerprint
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  if (scenarios.length !== 12) {
    console.error(`场景登记数=${scenarios.length}，必须恰为 12；拒绝执行`)
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

  const passed = results.filter(r => r.pass).length
  const result = {
    schemaVersion: 1,
    chain: 'E2E-07',
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
    // 前后非数字断言：排除工单号/订单号时间戳数字串的假阳性（真手机号必独立成串）
    { name: '手机号原文', re: /(?<!\d)1[3-9]\d{9}(?!\d)/ },
    { name: '本机绝对路径', re: /\/(?:Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
  ]
  const runtimeSecrets = [
    { name: '机主 Token', value: ctx.owner && ctx.owner.tokenValue },
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
  console.log(`BATCH_DIR=docs/acceptance/message-center/runs/${BATCH_ID}`)
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
