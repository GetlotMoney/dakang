/**
 * E2E-05 设备运营与运维固定场景验收（S1～S16，真实 HTTP + MQTT 模拟器 + 验收库 DB 断言）。
 *
 * 运行前提（deploy/acceptance/acc-env.sh 编排）：
 * - 独立验收 MySQL(3309)/Redis(6381)/EMQX(1884) 已 rebuild（init + migrations + acc-seed）；
 * - 验收后端以监控开启 + 短凭据时效启动：
 *     ACC_MONITOR_ENABLED=true ACC_CONTROL_TICKET_TTL=8 bash deploy/acceptance/acc-env.sh backend-start
 * - 模拟器由本 runner 按场景自行启停（不要预先 sim-start）；两轮之间必须重新 rebuild。
 *
 * 安全闸（fail-closed）：
 * - 必须显式 DEVICE_ACC_MODE=full；
 * - 后端端口 13330/8081（主环境）或 DB 端口 3306/3308（主库）一律拒绝；
 * - 开跑前核验：验收种子哨兵、13340 端口进程与 acc-env pid 一致（防旧进程抢答）、
 *   服务端 JAR 构建指纹落盘、监控任务已开启（S2 依赖离线扫描）。
 *
 * 结果纪律：场景数必须恰为 16（禁止 0/0 PASS）；产物出口做污染扫描后落盘
 * docs/acceptance/device-ops/runs/<批次>/result.json；失败继续跑完但退出码非零。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn, execFile } = require('node:child_process')
const { sha256File } = require('./e1b-core')
const { newBatchId } = require('./report-core')
const { verifyAccBackendContainer } = require('./acc-backend-proof')

// ---------------------------------------------------------------------------
// 安全闸与环境
// ---------------------------------------------------------------------------
if (String(process.env.DEVICE_ACC_MODE || '').trim() !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 DEVICE_ACC_MODE=full（本脚本会真实写验收库并驱动模拟设备）')
  process.exit(2)
}
if (!String(process.env.ACC_DB_PASSWORD || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD（仓库根 .env，参照 .env.example）')
  process.exit(2)
}
if (!String(process.env.ACC_OPS_PWD_CIPHER || '').trim()) {
  console.error('安全闸拒绝执行：缺少 ACC_OPS_PWD_CIPHER（acc-ops 登录口令密文）')
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
const ACCEPT_DIR = path.resolve(REPO_ROOT, 'docs/acceptance/device-ops')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, 'runs', BATCH_ID)
const SIM = path.resolve(REPO_ROOT, 'tools/device-sim/sim.js')
const PUB = path.resolve(REPO_ROOT, 'tools/device-sim/pub.js')
const JAR_GLOB_DIR = path.resolve(REPO_ROOT, 'server/target')
const ACC_ENV_RUN_DIR = path.resolve(REPO_ROOT, 'deploy/acceptance/.run')
const BROKER = process.env.ACC_BROKER || 'tcp://127.0.0.1:1884'
const BACKEND_PORT = 13340

// 种子固定身份（deploy/acceptance/acc-seed.sql）
const OWNER_PHONE = '13999990001' // ws_user 9001：既是卡主又是设备 9201 的机主（E2E-05）
const OTHER_PHONE = '13999990002' // ws_user 9002：无任何设备归属（越权对照）
const DEV1 = { id: 9201, no: 'ACC-DEV-0001' }
const DEV2 = { id: 9202, no: 'ACC-DEV-0002' }
const QR_DEV1 = 'ACC-QR-DEV1-O1'
const ACC_PACKAGE_ID = '9501'
const WATER_TYPE_ID = 1
const OPS_LOGIN = 'acc-ops'
const OPS2_LOGIN = 'acc-ops2'
const OPS_PWD_CIPHER = process.env.ACC_OPS_PWD_CIPHER

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
    return JSON.parse(text)
  }
  catch {
    throw new CheckError(`HTTP ${resp.status} ${pathName} 非 JSON 响应：${text.slice(0, 120)}`)
  }
}

async function apiOk(pathName, body, token) {
  const r = await api(pathName, body, token)
  ok(r && r.code === 0, `${pathName} 应成功`, `code=${r && r.code} msg=${r && r.msg}`)
  return r.data
}

async function apiFail(pathName, body, token) {
  const r = await api(pathName, body, token)
  ok(r && r.code !== 0, `${pathName} 应被拒绝`, '却返回 code=0')
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

async function pollUntil(label, fn, timeoutMs, intervalMs = 2000) {
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
// 模拟器进程控制（协议逻辑全部在 tools/device-sim，runner 只管生命周期）
// ---------------------------------------------------------------------------
const simProcs = new Map()

async function simStart(deviceNo, ...modes) {
  await simStop(deviceNo)
  const proc = spawn('node', [SIM, deviceNo, ...modes], {
    env: { ...process.env, BROKER },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const logPath = path.join(RUN_DIR, `sim-${deviceNo}.log`)
  fs.mkdirSync(RUN_DIR, { recursive: true })
  const logStream = fs.createWriteStream(logPath, { flags: 'a' })
  logStream.write(`\n===== ${new Date().toISOString()} start modes=[${modes.join(',')}] =====\n`)
  proc.stdout.pipe(logStream)
  proc.stderr.pipe(logStream)
  simProcs.set(deviceNo, proc)
  await sleep(2500) // 等 MQTT 连接 + 订阅 + 首次心跳
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

/** 以设备身份发一条上行（一次性发布器；msgId/ts 缺省自动补齐） */
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

// ---------------------------------------------------------------------------
// 登录与常用查询
// ---------------------------------------------------------------------------
async function miniLogin(phone) {
  const data = await apiOk('/mini/test-login/by-phone', { phone })
  ok(data && data.tokenName && data.tokenValue, '测试登录应返回 token')
  return { tokenName: data.tokenName, tokenValue: data.tokenValue }
}

async function opsLogin(loginName) {
  const data = await apiOk('/api/auth/loginEmployee', { loginName, loginPwd: OPS_PWD_CIPHER })
  ok(data && data.tokenValue, `运营登录 ${loginName} 应成功`)
  return { tokenName: 'dakang-token', tokenValue: data.tokenValue }
}

const deviceRow = id => one('SELECT * FROM ws_device WHERE ID = ?', [id])
function activeAlarms(deviceId, type) {
  return q(
    'SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND ALARM_TYPE = ? AND ACTIVE_DEDUPE_KEY IS NOT NULL',
    [deviceId, type],
  )
}
const commandByNo = cmdNo => one('SELECT * FROM ws_command WHERE CMD_NO = ?', [cmdNo])
const commandById = id => one('SELECT * FROM ws_command WHERE ID = ?', [id])
const eventsByKey = key => q('SELECT * FROM ws_domain_event WHERE EVENT_KEY = ? ORDER BY ID', [key])

async function scanEligibility(token, cardId) {
  const scan = await apiOk('/mini/device/scan/resolve', { rawCode: QR_DEV1 }, token)
  const body = { scanSessionId: scan.scanSessionId }
  if (cardId != null) {
    body.cardId = Number(cardId)
  }
  const elig = await apiOk('/mini/device/water/eligibility', body, token)
  return { scanSessionId: scan.scanSessionId, elig }
}

async function sendCommand(ops, deviceId, cmdType, cmdPayload) {
  const cmdId = await apiOk('/device/command/send', { deviceId, cmdType, cmdPayload }, ops)
  const command = await commandById(cmdId)
  ok(command, '指令下发后应可追溯')
  return command
}

// ---------------------------------------------------------------------------
// 场景注册
// ---------------------------------------------------------------------------
const scenarios = []
function scenario(id, name, fn) {
  scenarios.push({ id, name, fn })
}

const ctx = {}

// S1 心跳上线，PC 与机主端读取同一设备状态
scenario('S1', '心跳上线：PC 与机主端读取同一设备状态（共键 deviceNo）', async (ev) => {
  await simStart(DEV1.no, 'lock-model', 'hold-dispense')
  const online = await pollUntil('设备 9201 心跳转在线', async () => {
    const row = await deviceRow(DEV1.id)
    return row.ONLINE_STATUS === 1 ? row : null
  }, 30000)
  const pcDetail = await apiOk('/device/device/detail', { id: DEV1.id }, ctx.ops)
  eq(pcDetail.onlineStatus, 1, 'PC 设备详情应显示在线')
  const ownerList = await apiOk('/mini/owner/device/list', {}, ctx.owner)
  const mine = (ownerList || []).find(item => item.deviceNo === DEV1.no)
  ok(mine, '机主设备列表应含名下设备 ACC-DEV-0001')
  eq(mine.onlineStatus, 'ONLINE', '机主端在线状态应与 PC 同源')
  const ownerDetail = await apiOk('/mini/owner/device/detail', { deviceNo: DEV1.no }, ctx.owner)
  eq(ownerDetail.deviceNo, pcDetail.deviceNo, '三端设备编号共键')
  ev.push(`deviceNo=${DEV1.no} PC=在线 mini=ONLINE lastHeartbeat=${online.LAST_HEARTBEAT}`)
})

// S2 心跳超时离线：唯一告警 + 预检/创单双阻断
scenario('S2', '心跳超时离线：唯一离线告警，扫码预检与创单均阻断', async (ev) => {
  await simStop(DEV1.no)
  const offline = await pollUntil('离线扫描将设备翻离线（阈值90s+扫描30s）', async () => {
    const row = await deviceRow(DEV1.id)
    return row.ONLINE_STATUS === 2 ? row : null
  }, 180000, 5000)
  ok(offline, '设备应离线')
  const alarms = await activeAlarms(DEV1.id, 1)
  eq(alarms.length, 1, '离线告警必须恰好一条（多实例扫描不重复）')
  const { elig } = await scanEligibility(ctx.owner, ctx.cardId)
  eq(elig.availability, 'DEVICE_OFFLINE', '扫码预检应按离线阻断')
  const denied = await apiFail('/mini/order/water/create', {
    scanSessionId: (await apiOk('/mini/device/scan/resolve', { rawCode: QR_DEV1 }, ctx.owner)).scanSessionId,
    cardId: Number(ctx.cardId),
    waterTypeId: WATER_TYPE_ID,
    planMl: 5000,
    payWay: 3,
  }, ctx.owner)
  ev.push(`offline alarm#${alarms[0].ID} 预检=${elig.availability} 创单拒绝=${denied.msg}`)
  ctx.offlineAlarmId = alarms[0].ID
})

// S3 心跳恢复：告警自动恢复，设备重新可用
scenario('S3', '心跳恢复：离线告警自动恢复，设备重新可用', async (ev) => {
  await simStart(DEV1.no, 'lock-model', 'hold-dispense')
  await pollUntil('设备转回在线', async () => {
    const row = await deviceRow(DEV1.id)
    return row.ONLINE_STATUS === 1 ? row : null
  }, 40000)
  const alarm = await pollUntil('离线告警自动恢复', async () => {
    const row = await one('SELECT * FROM ws_alarm WHERE ID = ?', [ctx.offlineAlarmId])
    return row.ALARM_STATUS === 4 && row.ACTIVE_DEDUPE_KEY === null ? row : null
  }, 20000)
  ok(alarm.RECOVER_TIME, '恢复时间应回填')
  const { elig } = await scanEligibility(ctx.owner, ctx.cardId)
  eq(elig.availability, 'AVAILABLE', '恢复后设备重新可用')
  ev.push(`alarm#${alarm.ID} 状态=4自动恢复 recoverTime=${alarm.RECOVER_TIME}`)
})

// S4 严重故障与未知故障均阻断且各自只有一条活动告警
scenario('S4', '严重故障 E003 与未知故障 E999 均阻断取水且各自恰一条活动告警', async (ev) => {
  const inconsistentMsgId = `${BATCH_ID}-S4-INCONSISTENT`
  await pub(DEV1.no, 'status', {
    msgId: inconsistentMsgId,
    runStatus: 1,
    faultCode: 'E003',
  })
  const rejected = await pollUntil('空闲态携带故障码应处理失败', async () => {
    const row = await one('SELECT * FROM ws_device_msg WHERE MSG_ID = ?', [inconsistentMsgId])
    return row && row.HANDLE_STATUS === 3 ? row : null
  }, 15000)
  const projection = await deviceRow(DEV1.id)
  eq(projection.RUN_STATUS, 1, '矛盾报文不得改变运行态')
  ok(!projection.LAST_FAULT_CODE, '矛盾报文不得写入故障投影')
  ok(String(rejected.HANDLE_REMARK || '').includes('非故障运行状态'), '拒绝原因必须可追溯')

  await pub(DEV1.no, 'status', { runStatus: 3, faultCode: 'E003' })
  await pub(DEV1.no, 'status', { runStatus: 3, faultCode: 'E003' })
  await pollUntil('E003 故障投影落库', async () => {
    const row = await deviceRow(DEV1.id)
    return row.LAST_FAULT_CODE === 'E003' ? row : null
  }, 15000)
  let alarms = await pollUntil('E003 活动告警落库', async () => {
    const rows = await q(
      'SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND ALARM_TYPE = 2 AND SOURCE_REF = ? AND ACTIVE_DEDUPE_KEY IS NOT NULL',
      [DEV1.id, 'E003'],
    )
    return rows.length === 1 ? rows : null
  }, 15000)
  eq(alarms.length, 1, 'E003 活动告警必须恰一条（重复上报被唯一键收敛）')
  let { elig } = await scanEligibility(ctx.owner, ctx.cardId)
  eq(elig.availability, 'FAULT_E003', 'E003 属专属可用性码，应阻断')

  // 恢复 E003 后上报未登记故障码：fail-closed 一样阻断
  await pub(DEV1.no, 'status', { runStatus: 1, faultCode: '' })
  await pollUntil('E003 告警自动恢复', async () => {
    const rows = await q('SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND SOURCE_REF = ? AND ALARM_STATUS = 4', [DEV1.id, 'E003'])
    return rows.length === 1 ? rows : null
  }, 15000)
  await pub(DEV1.no, 'status', { runStatus: 3, faultCode: 'E999' })
  await pollUntil('E999 故障投影落库', async () => {
    const row = await deviceRow(DEV1.id)
    return row.LAST_FAULT_CODE === 'E999' ? row : null
  }, 15000)
  alarms = await pollUntil('E999 活动告警落库', async () => {
    const rows = await q(
      'SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND ALARM_TYPE = 2 AND SOURCE_REF = ? AND ACTIVE_DEDUPE_KEY IS NOT NULL',
      [DEV1.id, 'E999'],
    )
    return rows.length === 1 ? rows : null
  }, 15000)
  eq(alarms.length, 1, '未知故障 E999 活动告警恰一条')
  eq(alarms[0].ALARM_LEVEL, 3, '未知故障必须按最高等级 fail-closed')
  ;({ elig } = await scanEligibility(ctx.owner, ctx.cardId))
  eq(elig.availability, 'DEVICE_UNAVAILABLE', '未知故障码 fail-closed 阻断')
  ev.push(`E003 一条活动→恢复；E999 level=3 fail-closed 阻断（availability=${elig.availability}）`)
})

// S5 故障恢复后设备投影与告警状态一致
scenario('S5', '故障恢复：设备投影清故障码，对应告警恢复，取水重新可用', async (ev) => {
  await pub(DEV1.no, 'status', { runStatus: 1, faultCode: '' })
  const row = await pollUntil('故障投影清空', async () => {
    const device = await deviceRow(DEV1.id)
    return !device.LAST_FAULT_CODE && device.RUN_STATUS === 1 ? device : null
  }, 15000)
  const recovered = await pollUntil('E999 告警自动恢复', async () => {
    const rows = await q(
      'SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND SOURCE_REF = ? AND ALARM_STATUS = 4 AND ACTIVE_DEDUPE_KEY IS NULL',
      [DEV1.id, 'E999'],
    )
    return rows.length === 1 ? rows : null
  }, 15000)
  eq(recovered.length, 1, 'E999 告警应自动恢复且键清空')
  ok(row.LAST_STATUS_DEVICE_TIME, '状态投影必须保存设备时间乱序锚点')

  const staleMsgId = `${BATCH_ID}-S5-STALE`
  await pub(DEV1.no, 'replay', {
    msgId: staleMsgId,
    runStatus: 3,
    faultCode: 'E003',
    ts: '20000101000000',
  })
  await pollUntil('旧状态补传应被处理但不得回滚投影', async () => {
    const msg = await one('SELECT * FROM ws_device_msg WHERE MSG_ID = ?', [staleMsgId])
    return msg && msg.HANDLE_STATUS === 2 ? msg : null
  }, 15000)
  const afterStale = await deviceRow(DEV1.id)
  eq(afterStale.RUN_STATUS, 1, '旧状态补传不得把正常设备改回故障')
  ok(!afterStale.LAST_FAULT_CODE, '旧状态补传不得恢复历史故障码')
  eq(afterStale.LAST_STATUS_DEVICE_TIME, row.LAST_STATUS_DEVICE_TIME, '旧状态补传不得改写设备时间锚点')
  const { elig } = await scanEligibility(ctx.owner, ctx.cardId)
  eq(elig.availability, 'AVAILABLE', '恢复后可用')
  ev.push(`LAST_FAULT_CODE=空 runStatus=${row.RUN_STATUS} E999 告警=4自动恢复；旧 replay 未回滚投影`)
})

// S6 遥测合法值落库，非法值零污染，不支持字段如实降级
scenario('S6', '遥测：合法值落库，非法值置空零污染，机主端如实降级展示', async (ev) => {
  // /device/device/update 走编辑校验组：编号/名称/型号/水站必填（编号同值通过不可改校验），
  // 全部取 acc-seed 原值，本步只变更 SIM 档案字段
  const simArchive = {
    id: DEV1.id,
    deviceNo: DEV1.no,
    deviceName: '验收1号机',
    deviceModel: 'DK-W800',
    stationId: 9101,
    ownerUserId: 9001,
    simIccid: 'ACCICCID000000000001',
    simCarrier: '模拟运营商',
    simExpireTime: '29991231235959',
  }
  await apiOk('/device/device/update', { ...simArchive, simStatus: 3 }, ctx.ops)
  const pcSimBlocked = await apiOk('/device/device/detail', { id: DEV1.id }, ctx.ops)
  eq(pcSimBlocked.simStatus, 3, 'PC 详情应回显 SIM 欠费')
  eq(pcSimBlocked.orderAvailable, false, 'SIM 欠费时后端可用性必须阻断')
  const miniSimBlocked = await apiOk('/mini/owner/device/detail', { deviceNo: DEV1.no }, ctx.owner)
  eq(miniSimBlocked.simStatus, 3, '机主端应看到同一 SIM 欠费状态')
  const simEligibility = await scanEligibility(ctx.owner, ctx.cardId)
  eq(simEligibility.elig.availability, 'DEVICE_UNAVAILABLE', 'SIM 欠费必须阻断扫码预检')
  const simAlarm = await pollUntil('SIM 欠费应形成活动告警', async () => {
    const rows = await activeAlarms(DEV1.id, 5)
    return rows.length === 1 ? rows[0] : null
  }, 75000, 3000)
  await apiOk('/device/device/update', { ...simArchive, simStatus: 1 }, ctx.ops)
  await pollUntil('SIM 档案恢复后告警应自动恢复', async () => {
    const alarm = await one('SELECT * FROM ws_alarm WHERE ID = ?', [simAlarm.ID])
    return alarm && alarm.ALARM_STATUS === 4 && alarm.ACTIVE_DEDUPE_KEY === null ? alarm : null
  }, 75000, 3000)

  await pub(DEV1.no, 'telemetry', {
    tds: 40,
    filterLife: [{ code: 'F1', status: 3, remainingPercent: 0 }],
  })
  const filterAlarm = await pollUntil('滤芯超期告警应形成活动键', async () => {
    const rows = await activeAlarms(DEV1.id, 4)
    return rows.length === 1 ? rows[0] : null
  }, 15000)
  await pub(DEV1.no, 'telemetry', {
    tds: 40,
    filterLife: [{ code: 'F1', status: 1, remainingPercent: 80 }],
  })
  await pollUntil('滤芯恢复后告警应自动恢复并清活动键', async () => {
    const alarm = await one('SELECT * FROM ws_alarm WHERE ID = ?', [filterAlarm.ID])
    return alarm && alarm.ALARM_STATUS === 4 && alarm.ACTIVE_DEDUPE_KEY === null ? alarm : null
  }, 15000)

  // 哨兵值取模拟器随机域（tds 30-49 / rawTds 200-299）之外：周期遥测撞值会让取行断言选错行
  await pub(DEV1.no, 'telemetry', { tds: 777, rawTds: 888, waterTemp: 21, signal: -66 })
  const valid = await pollUntil('合法遥测落库', async () => {
    const row = await one('SELECT * FROM ws_device_telemetry WHERE DEVICE_ID = ? AND TDS_VALUE = 777 ORDER BY ID DESC LIMIT 1', [DEV1.id])
    return row || null
  }, 15000)
  eq(valid.WATER_TEMP, 21, '合法水温应原样落库')

  // 非法行以合法 rawTds=999 作定位锚（越界字段置空后行仍可识别）
  await pub(DEV1.no, 'telemetry', { tds: -5, rawTds: 999, waterTemp: 200, signal: 50 })
  const sanitized = await pollUntil('非法遥测行落库（字段应被置空）', async () => {
    const row = await one('SELECT * FROM ws_device_telemetry WHERE DEVICE_ID = ? AND RAW_TDS_VALUE = 999 ORDER BY ID DESC LIMIT 1', [DEV1.id])
    return row || null
  }, 15000)
  ok(sanitized.TDS_VALUE === null, '负 TDS 必须被置空而不是当读数', `实际 ${sanitized.TDS_VALUE}`)
  ok(sanitized.WATER_TEMP === null, '越界水温必须被置空', `实际 ${sanitized.WATER_TEMP}`)
  ok(sanitized.SIGNAL_STRENGTH === null, '正值信号必须被置空', `实际 ${sanitized.SIGNAL_STRENGTH}`)
  const detail = await apiOk('/mini/owner/device/detail', { deviceNo: DEV1.no }, ctx.owner)
  ok(detail.tds == null, '机主端最新遥测应如实为空（暂无数据），不得伪造')
  ev.push(`SIM 欠费三端阻断并自动恢复；滤芯告警恢复；合法行#${valid.ID}、非法行#${sanitized.ID} 降级为空`)
})

// S7 单设备命令 accepted→success 全链闭合
scenario('S7', '单设备查询指令：下发→ACK→result 成功，全链审计闭合', async (ev) => {
  const command = await sendCommand(ctx.ops, DEV1.id, 3)
  const done = await pollUntil('指令应到达成功终态', async () => {
    const row = await commandByNo(command.CMD_NO)
    return row.CMD_STATUS === 4 ? row : null
  }, 30000)
  ok(done.SENT_TIME && done.ACK_TIME && done.FINISH_TIME, '下发/回执/终态时间应齐备')
  const events = await eventsByKey(command.CMD_NO)
  ok(events.length >= 3, '指令状态审计应覆盖每次推进', `实际 ${events.length} 条`)
  ev.push(`cmdNo=${command.CMD_NO} 1→2→3→4 events=${events.length}`)
})

// S8 rejected/busy/failure/partial/timeout 不得显示为成功
scenario('S8', '异常回执族：rejected/busy/failure/partial/timeout 全部不得成功', async (ev) => {
  const cases = [
    { mode: 'reject-ack', expect: 5, label: 'rejected' },
    { mode: 'busy-ack', expect: 5, label: 'busy' },
    { mode: 'fail-result', expect: 5, label: 'failure' },
    { mode: 'partial-result', expect: 7, label: 'partial' },
    { mode: 'silent', expect: 6, label: 'timeout(30s+扫描)' },
  ]
  for (const item of cases) {
    await simStart(DEV1.no, item.mode)
    await sleep(5500) // 同参 send 命中 @RepeatSubmit（url+身份+参数 5s TTL），逐例留窗
    const command = await sendCommand(ctx.ops, DEV1.id, 3)
    const timeout = item.expect === 6 ? 75000 : 30000
    const done = await pollUntil(`${item.label} 应落终态 ${item.expect}`, async () => {
      const row = await commandByNo(command.CMD_NO)
      return row.CMD_STATUS === item.expect ? row : null
    }, timeout, 3000)
    ok(done.CMD_STATUS !== 4, `${item.label} 不得显示为成功`)
    ev.push(`${item.label}→${done.CMD_STATUS}`)
  }
})

// S9 重复/乱序/迟到/补传不重复推进
scenario('S9', 'ACK/result 重复、乱序、迟到与补传：均不重复推进状态', async (ev) => {
  // 重复 ACK + 补传 result：终态一次成功，重复消息只审计
  await simStart(DEV1.no, 'dup-ack', 'replay')
  const c1 = await sendCommand(ctx.ops, DEV1.id, 3)
  const done1 = await pollUntil('dup-ack+replay 应一次成功', async () => {
    const row = await commandByNo(c1.CMD_NO)
    return row.CMD_STATUS === 4 ? row : null
  }, 30000)
  await sleep(6000) // 等重复 ACK 与补传 result 到达
  const after1 = await commandByNo(c1.CMD_NO)
  eq(after1.CMD_STATUS, 4, '重复 ACK/补传后状态不得变化')
  eq(after1.FINISH_TIME, done1.FINISH_TIME, '补传不得改写终态时间')

  // 乱序：result 先于 ACK——终态后迟到 ACK 不得回退
  await simStart(DEV1.no, 'result-before-ack')
  const c2 = await sendCommand(ctx.ops, DEV1.id, 3)
  const done2 = await pollUntil('先 result 应直达终态', async () => {
    const row = await commandByNo(c2.CMD_NO)
    return row.CMD_STATUS === 4 ? row : null
  }, 30000)
  await sleep(4000) // 迟到 ACK 到达
  const after2 = await commandByNo(c2.CMD_NO)
  eq(after2.CMD_STATUS, 4, '迟到 ACK 不得回退终态')
  ok(done2, '乱序场景终态成立')

  // 迟到 result：超时判 6 后 result 才到——不得翻成功
  await simStart(DEV1.no, 'silent')
  const c3 = await sendCommand(ctx.ops, DEV1.id, 3)
  const timedOut = await pollUntil('silent 应超时判 6', async () => {
    const row = await commandByNo(c3.CMD_NO)
    return row.CMD_STATUS === 6 ? row : null
  }, 75000, 3000)
  await pub(DEV1.no, 'result', { cmdNo: c3.CMD_NO, success: true, finishTs: '20260730235959' })
  await sleep(4000)
  const after3 = await commandByNo(c3.CMD_NO)
  eq(after3.CMD_STATUS, 6, '超时后的迟到成功 result 不得改写终态')
  ok(timedOut, '超时终态成立')
  ev.push(`dup/replay 终态钉住；乱序 result→ack 成立；迟到 result 不翻案（cmd=${c3.CMD_NO}）`)
})

// S10 错设备 ACK/result 拒绝且目标命令零变化
scenario('S10', '错设备回执：以他设备身份回执被拒，目标命令零变化', async (ev) => {
  await simStart(DEV1.no, 'wrong-device') // 回执走 DK-DEV-0002 主题（非本验收设备）
  const command = await sendCommand(ctx.ops, DEV1.id, 3)
  await pollUntil('指令应停留在已下发', async () => {
    const row = await commandByNo(command.CMD_NO)
    return row.CMD_STATUS === 2 ? row : null
  }, 15000)
  await sleep(8000) // 错设备 ack/result 已被处理完
  const after = await commandByNo(command.CMD_NO)
  eq(after.CMD_STATUS, 2, '错设备回执后指令状态必须零变化（等超时兜底）')
  ok(after.ACK_TIME == null, '错设备 ACK 不得写入回执时间')
  ev.push(`cmdNo=${command.CMD_NO} 保持 2已下发，ACK_TIME 空`)
  await simStart(DEV1.no, 'lock-model', 'hold-dispense') // 恢复正常模式供后续场景
})

// S11 批量命令一设备一子命令，聚合准确，部分失败不显示全成功
scenario('S11', '批量锁机：一设备一子指令，聚合=1成功+1超时=部分成功', async (ev) => {
  // 场景自备模拟器：DEV1 lock-model（锁机成功后状态建模上报）；DEV2 无模拟器（子指令超时）
  await simStart(DEV1.no, 'lock-model', 'hold-dispense')
  const preview = await apiOk('/device/batch/preview', {
    scopeType: 1,
    deviceIds: [DEV1.id, DEV2.id],
    cmdType: 4,
  }, ctx.ops)
  eq(preview.targetCount, 2, '预览目标数应为服务端解析的 2 台')
  const batchId = await apiOk('/device/batch/confirm', {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: preview.paramDigest,
  }, ctx.ops)
  const children = await q('SELECT * FROM ws_command WHERE BATCH_ID = ? ORDER BY DEVICE_ID', [batchId])
  eq(children.length, 2, '一台设备一条子指令')
  eq(new Set(children.map(c => String(c.DEVICE_ID))).size, 2, '子指令设备互不重复（uk_cmd_batch_device）')
  const settled = await pollUntil('批次聚合应收敛（成功1/超时1）', async () => {
    const row = await one('SELECT * FROM ws_command_batch WHERE ID = ?', [batchId])
    return row.BATCH_STATUS !== 1 ? row : null
  }, 90000, 5000)
  eq(settled.BATCH_STATUS, 3, '有成有败必须是部分成功，绝不显示全部成功')
  eq(settled.SUCCESS_COUNT, 1, '成功数=1（DEV1）')
  eq(settled.SUCCESS_COUNT + settled.FAIL_COUNT + settled.TIMEOUT_COUNT, 2, '聚合计数必须等于总数')
  // 锁机建模：DEV1 状态上报 runStatus=5，投影同步
  await pollUntil('DEV1 运行状态应翻锁机', async () => {
    const row = await deviceRow(DEV1.id)
    return row.RUN_STATUS === 5 ? row : null
  }, 15000)
  // 解锁复原（单发，供后续场景）
  const unlock = await sendCommand(ctx.ops, DEV1.id, 5)
  await pollUntil('解锁成功且状态复原', async () => {
    const cmd = await commandByNo(unlock.CMD_NO)
    const row = await deviceRow(DEV1.id)
    return cmd.CMD_STATUS === 4 && row.RUN_STATUS === 1 ? row : null
  }, 30000)
  ev.push(`batch#${batchId} ${settled.SUCCESS_COUNT}成/${settled.FAIL_COUNT}败/${settled.TIMEOUT_COUNT}超 → 3部分成功；锁机建模状态同步`)
  ctx.batchId = batchId
})

// S12 高风险 ticket 只可领取一次，换人、过期、改参数均拒绝
scenario('S12', '操作凭据：GETDEL 单次领取，换人/过期/改参数全部拒绝', async (ev) => {
  // 改参数拒绝：回显摘要被篡改
  let preview = await apiOk('/device/batch/preview', { scopeType: 1, deviceIds: [DEV1.id], cmdType: 3 }, ctx.ops)
  const tampered = await apiFail('/device/batch/confirm', {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: 'f'.repeat(64),
  }, ctx.ops)
  // 篡改被拒时凭据已被 GETDEL 销毁——同一凭据再次使用（原参数）也必须拒绝（单次领取）
  const reused = await apiFail('/device/batch/confirm', {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: preview.paramDigest,
  }, ctx.ops)

  // 换人拒绝：acc-ops 预览、acc-ops2 领取
  preview = await apiOk('/device/batch/preview', { scopeType: 1, deviceIds: [DEV1.id], cmdType: 3 }, ctx.ops)
  const hijacked = await apiFail('/device/batch/confirm', {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: preview.paramDigest,
  }, ctx.ops2)

  // 过期拒绝：验收后端 ticket TTL=8s
  preview = await apiOk('/device/batch/preview', { scopeType: 1, deviceIds: [DEV1.id], cmdType: 3 }, ctx.ops)
  await sleep(9500)
  const expired = await apiFail('/device/batch/confirm', {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: preview.paramDigest,
  }, ctx.ops)
  ev.push(`改参数=${tampered.msg}；重复=${reused.msg}；换人=${hijacked.msg}；过期=${expired.msg}`)
})

// S13 同一告警 20 并发触发恰一条活动告警和最多一张工单
scenario('S13', '并发幂等：同故障 20 并发上报恰一条活动告警，双确认转单恰一张工单', async (ev) => {
  await Promise.all(Array.from({ length: 20 }, () =>
    pub(DEV1.no, 'status', { runStatus: 3, faultCode: 'E003' })))
  await pollUntil('E003 告警产生', async () => {
    const rows = await activeAlarms(DEV1.id, 2)
    return rows.length > 0 ? rows : null
  }, 20000)
  await sleep(3000) // 等 20 条全部处理完
  const alarms = await q(
    'SELECT * FROM ws_alarm WHERE DEVICE_ID = ? AND ALARM_TYPE = 2 AND SOURCE_REF = ? AND ACTIVE_DEDUPE_KEY IS NOT NULL',
    [DEV1.id, 'E003'],
  )
  eq(alarms.length, 1, '20 并发触发后活动告警必须恰一条')
  const alarmId = alarms[0].ID

  const [first, second] = await Promise.all([
    api('/device/alarm/toWorkOrder', { id: alarmId }, ctx.ops),
    api('/device/alarm/toWorkOrder', { id: alarmId }, ctx.ops),
  ])
  const orders = await q('SELECT * FROM ws_work_order WHERE ALARM_ID = ?', [alarmId])
  eq(orders.length, 1, '并发转单必须收敛到一张工单')
  ok([first, second].some(r => r && r.code === 0), '至少一次转单成功')
  // 收尾：设备恢复 + 工单走到关闭（告警键随关单释放，不影响后续场景）
  await pub(DEV1.no, 'status', { runStatus: 1, faultCode: '' })
  await pollUntil('设备故障投影应清空（防迟到 E003 污染后续场景）', async () => {
    const row = await deviceRow(DEV1.id)
    return !row.LAST_FAULT_CODE && row.RUN_STATUS === 1 ? row : null
  }, 20000)
  const workOrderId = orders[0].ID
  await apiOk('/device/workorder/assign', { id: workOrderId, assigneeId: 9001 }, ctx.ops)
  await apiOk('/device/workorder/submitResult', { id: workOrderId, finishResult: 'S13 收尾修复' }, ctx.ops)
  await apiOk('/device/workorder/reviewPass', { id: workOrderId }, ctx.ops)
  ev.push(`20并发→1活动告警(#${alarmId})；双确认→1工单(#${workOrderId})，已闭环`)
})

// S14 机主报修 → PC 全链处置 → 三端同一工单号
scenario('S14', '机主报修全链：申报→确认→分配→处理→复核关闭，三端同一工单号', async (ev) => {
  const requestId = crypto.randomUUID()
  const created = await apiOk('/mini/owner/service/create', {
    requestId,
    deviceNo: DEV1.no,
    serviceType: 'REPAIR',
    description: 'S14 出水口漏水报修',
    contactPhone: '13999990001',
  }, ctx.owner)
  eq(created.status, 'PENDING_ACCEPTANCE', '机主申报入口应为待确认')
  ok(created.workOrderNo, '申报应返回真实工单号')
  // requestId 幂等：重复提交回原单（先越过 @RepeatSubmit 的 5s 同参防抖窗，验证的是库层幂等而非防抖）
  await sleep(6000)
  const replay = await apiOk('/mini/owner/service/create', {
    requestId,
    deviceNo: DEV1.no,
    serviceType: 'REPAIR',
    description: 'S14 出水口漏水报修',
    contactPhone: '13999990001',
  }, ctx.owner)
  eq(replay.workOrderNo, created.workOrderNo, '同 requestId 重复提交必须返回原工单')
  await sleep(6000)
  const mismatch = await apiFail('/mini/owner/service/create', {
    requestId,
    deviceNo: DEV1.no,
    serviceType: 'PART',
    description: '复用 requestId 改成配件申请',
    contactPhone: '13999990001',
  }, ctx.owner)
  ok(String(mismatch.msg || '').includes('不可更换申报参数'), '同 requestId 改参数必须明确拒绝')

  const row = await one('SELECT * FROM ws_work_order WHERE ORDER_NO = ?', [created.workOrderNo])
  const id = row.ID
  await apiOk('/device/workorder/confirm', { id }, ctx.ops)
  await apiOk('/device/workorder/assign', { id, assigneeId: 9001 }, ctx.ops)
  await apiOk('/device/workorder/submitResult', { id, finishResult: '更换出水口密封圈' }, ctx.ops)
  await apiOk('/device/workorder/reviewPass', { id, reviewRemark: '验收通过' }, ctx.ops)

  const pcDetail = await apiOk('/device/workorder/detail', { id }, ctx.ops)
  eq(pcDetail.orderStatus, 5, 'PC 侧工单应已关闭')
  eq(pcDetail.orderNo, created.workOrderNo, 'PC 与机主端同一工单号')
  ok((pcDetail.trace || []).length >= 5, 'PC 轨迹应覆盖全部动作', `实际 ${(pcDetail.trace || []).length}`)
  const miniDetail = await apiOk('/mini/owner/service/detail', { requestId }, ctx.owner)
  eq(miniDetail.status, 'COMPLETED', '机主端状态应为完成')
  eq(miniDetail.workOrderNo, created.workOrderNo, '机主端同一工单号')
  ok((miniDetail.trace || []).length >= 5, '机主端轨迹应完整')
  ev.push(`workOrderNo=${created.workOrderNo} mini申报→PC关闭 双端轨迹 ${pcDetail.trace.length}/${miniDetail.trace.length}`)
})

// S15 PC 巡检工单 + 复核退回路径；越权账号不可读不可写
scenario('S15', 'PC 巡检工单：复核退回→再提交→关闭；越权账号不可读不可写', async (ev) => {
  const id = await apiOk('/device/workorder/create', {
    workType: 3,
    deviceId: DEV1.id,
    orderTitle: 'S15 季度巡检',
    orderContent: '滤芯/管路巡检',
  }, ctx.ops)
  const created = await one('SELECT * FROM ws_work_order WHERE ID = ?', [id])
  eq(created.ORDER_STATUS, 2, '巡检建单直接进待分配')
  await apiOk('/device/workorder/assign', { id, assigneeId: 9001 }, ctx.ops)
  await apiOk('/device/workorder/submitResult', { id, finishResult: '初检完成' }, ctx.ops)
  await apiOk('/device/workorder/reviewReturn', { id, reviewRemark: '缺少滤芯照片，退回补充' }, ctx.ops)
  const returned = await one('SELECT * FROM ws_work_order WHERE ID = ?', [id])
  eq(returned.ORDER_STATUS, 3, '复核退回应回到处理中')
  await apiOk('/device/workorder/submitResult', { id, finishResult: '补充证据后复检完成' }, ctx.ops)
  await apiOk('/device/workorder/reviewPass', { id }, ctx.ops)
  const closed = await one('SELECT * FROM ws_work_order WHERE ID = ?', [id])
  eq(closed.ORDER_STATUS, 5, '复核通过应关闭')

  // 越权：无归属用户 9002——设备列表为空、不可申报他人设备、不可读他人申请
  const foreignList = await apiOk('/mini/owner/device/list', {}, ctx.other)
  eq((foreignList || []).length, 0, '无归属账号设备列表必须为空')
  await apiFail('/mini/owner/device/detail', { deviceNo: DEV1.no }, ctx.other)
  await apiFail('/mini/owner/service/create', {
    requestId: crypto.randomUUID(),
    deviceNo: DEV1.no,
    serviceType: 'REPAIR',
    description: '越权申报',
  }, ctx.other)
  const s14Order = await one('SELECT * FROM ws_work_order WHERE SOURCE_TYPE = 2 ORDER BY ID DESC LIMIT 1')
  await apiFail('/mini/owner/service/detail', { requestId: s14Order.REQUEST_ID }, ctx.other)
  ev.push(`巡检#${id} 2→3→4→(退回)3→4→5；越权读写全部拒绝`)
})

// S16 紧急停止只作用于服务器确认的活动出水链
scenario('S16', '紧急停止：无活动链拒绝；活动链锚定订单，设备/订单/指令/审计共键一致', async (ev) => {
  // 无活动出水链：预览即拒绝
  const denied = await apiFail('/device/batch/preview', { scopeType: 1, deviceIds: [DEV1.id], cmdType: 2 }, ctx.ops)
  ok(String(denied.msg || '').includes('不存在活动出水链'), '无活动链必须拒绝', `msg=${denied.msg}`)

  // 建活动链：真实取水订单（sim hold-dispense 扣住出水 result，订单停在出水中）
  const scan = await apiOk('/mini/device/scan/resolve', { rawCode: QR_DEV1 }, ctx.owner)
  const detail = await apiOk('/mini/order/water/create', {
    scanSessionId: scan.scanSessionId,
    cardId: Number(ctx.cardId),
    waterTypeId: WATER_TYPE_ID,
    planMl: 10000,
    payWay: 3,
  }, ctx.owner)
  // 创单响应是订单详情投影：订单号在 detail.order.orderNo（与卡链 S7 同源读法）
  const order = { orderNo: detail.order.orderNo }
  const dispensing = await pollUntil('订单应进入出水中（ACK 驱动）', async () => {
    const row = await one('SELECT * FROM ws_order WHERE ORDER_NO = ?', [order.orderNo])
    return row && row.ORDER_STATUS === 3 ? row : null
  }, 30000)

  const preview = await apiOk('/device/batch/preview', { scopeType: 1, deviceIds: [DEV1.id], cmdType: 2 }, ctx.ops)
  eq(preview.activeOrderNo, order.orderNo, '预览必须锚定服务端确认的活动订单')
  eq(preview.targetCount, 1, '紧急停止只允许单台设备')
  // D-423 二次验证：紧急停机属恒加闸档，预览必须提前告知，确认必须先要求安全期
  ok(preview.requireSafe === true, '紧急停机预览必须回显需要二次认证', `requireSafe=${preview.requireSafe}`)
  const confirmBody = {
    operationTicket: preview.operationTicket,
    targetDigest: preview.targetDigest,
    paramDigest: preview.paramDigest,
  }
  // ① 未开安全期：必须被拒，且拒因码可判别（前端据此就地弹口令，而不是当成普通失败）
  const gated = await api('/device/batch/confirm', confirmBody, ctx.ops)
  ok(gated && gated.code === 1440, '未二次认证的确认必须回 1440', `code=${gated && gated.code}`)
  // ② 闸在凭据领取之前：被拒不得销毁 ticket，否则运营每次都得重走预览
  const stillAlive = await api('/device/batch/confirm', confirmBody, ctx.ops)
  ok(stillAlive && stillAlive.code === 1440, '凭据不得被未认证的确认销毁', `第二次 code=${stillAlive && stillAlive.code} msg=${stillAlive && stillAlive.msg}`)
  // ③ 开安全期后用同一张凭据原样重放
  await apiOk('/api/auth/openSafe', { loginPwd: OPS_PWD_CIPHER }, ctx.ops)
  const batchId = await apiOk('/device/batch/confirm', confirmBody, ctx.ops)
  const stopCmd = await pollUntil('停止指令应成功终态', async () => {
    const row = await one('SELECT * FROM ws_command WHERE BATCH_ID = ? AND CMD_TYPE = 2', [batchId])
    return row && row.CMD_STATUS === 4 ? row : null
  }, 30000)
  // 共键一致：指令←→设备/订单/批次；审计按指令号可回放
  eq(stopCmd.DEVICE_ID, DEV1.id, '停止指令设备共键')
  eq(stopCmd.ORDER_ID, dispensing.ID, '停止指令必须携带活动订单共键')
  ok(stopCmd.ACK_TIME && stopCmd.FINISH_TIME, '停止指令 ACK/result 齐备')
  const payload = JSON.parse(stopCmd.CMD_PAYLOAD)
  eq(payload.orderNo, order.orderNo, '下行报文锚定同一订单号')
  const events = await eventsByKey(stopCmd.CMD_NO)
  ok(events.length >= 3, '停止指令审计完整')
  ev.push(`无链拒绝→建链 order=${order.orderNo}→stop cmd=${stopCmd.CMD_NO}（device/order/batch/audit 共键一致）`)
})

// ---------------------------------------------------------------------------
// 环境指纹核验（开跑前 fail-closed）
// ---------------------------------------------------------------------------
function lsofPort(port) {
  return new Promise((resolve, reject) => {
    execFile('lsof', ['-ti', `:${port}`], (error, stdout) => {
      // lsof 退出码 1 表示没有匹配进程；其它错误代表取证工具不可用，验收必须停止。
      if (error && error.code !== 1) {
        reject(error)
        return
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
    monitorEnabled: false,
    jarSha256: null,
  }
  // 种子哨兵：E2E-05 增量（设备归属、第二运营账号）也在核验之列
  const owner = await one('SELECT ID, USER_PHONE FROM ws_user WHERE ID = 9001')
  const dev1 = await one('SELECT ID, OWNER_USER_ID FROM ws_device WHERE ID = ?', [DEV1.id])
  const dev2 = await one('SELECT ID FROM ws_device WHERE ID = ?', [DEV2.id])
  const ops2 = await one('SELECT ID FROM api_employee WHERE LOGIN_NAME = \'acc-ops2\'')
  ok(owner && owner.USER_PHONE === OWNER_PHONE && dev2 && ops2, '验收种子哨兵缺失（先 acc-env.sh rebuild）')
  ok(dev1 && String(dev1.OWNER_USER_ID) === '9001', '设备 9201 必须归属机主 9001（E2E-05 种子）')
  fingerprint.seedSentinel = true

  // 13340 残留进程核验：端口占用者必须与 acc-env 记录的 pid 一致（防旧进程抢答健康检查）
  let cmdline = ''
  let monitorConfigured = false
  let ticketConfigured = false
  if (process.env.ACC_BACKEND_CONTAINER) {
    fingerprint.backendContainerVerified = verifyAccBackendContainer(process.env.ACC_BACKEND_CONTAINER, BACKEND_PORT)
    monitorConfigured = fingerprint.backendContainerVerified.monitorEnabled
    ticketConfigured = fingerprint.backendContainerVerified.shortControlTicket
  }
  else {
    const pids = await lsofPort(BACKEND_PORT)
    ok(pids.length > 0, `端口 ${BACKEND_PORT} 无监听进程（先 backend-start）`)
    const pidFile = path.join(ACC_ENV_RUN_DIR, 'backend.pid')
    ok(fs.existsSync(pidFile), 'acc-env backend.pid 缺失，无法核验进程归属')
    const recordedPid = fs.readFileSync(pidFile, 'utf8').trim()
    ok(pids.includes(recordedPid), `端口 ${BACKEND_PORT} 被非 acc-env 进程占用（监听 pid=${pids.join(',')}，记录 pid=${recordedPid}）`)
    fingerprint.backendPidVerified = true
    cmdline = await new Promise((resolve, reject) => {
      execFile('ps', ['-p', recordedPid, '-o', 'command='], (error, stdout) => {
        if (error) {
          reject(error)
          return
        }
        resolve(String(stdout || ''))
      })
    })
    monitorConfigured = cmdline.includes('--dakang.device.monitor-enabled=true')
    ticketConfigured = cmdline.includes('--dakang.device.control-ticket-ttl-seconds=8')
  }

  // 构建指纹：验收后端运行的 JAR 摘要（与本仓库当前构建物一致即证明跑的是当前代码）
  const jar = fs.readdirSync(JAR_GLOB_DIR).find(f => f.endsWith('.jar') && !f.endsWith('.jar.original'))
  ok(jar, 'server/target 缺少构建产物（先 mvn package）')
  fingerprint.jarSha256 = sha256File(path.join(JAR_GLOB_DIR, jar))

  // 测试登录可用性 + 监控开关（S2 依赖离线扫描；扫描关闭时设备永不翻离线）
  const probe = await api('/mini/test-login/by-phone', { phone: OWNER_PHONE })
  ok(probe && probe.code === 0, '测试登录不可用——mini.test-login.enabled 未开或环境错误')
  ok(monitorConfigured, '验收后端未开启设备监控（ACC_MONITOR_ENABLED=true 重启 backend-start，S2 依赖离线扫描）')
  ok(ticketConfigured, '验收后端未按短凭据时效启动（ACC_CONTROL_TICKET_TTL=8，S12 过期拒绝依赖它）')
  fingerprint.monitorEnabled = true
  return fingerprint
}

// ---------------------------------------------------------------------------
// 夹具：机主水卡（S2/S16 取水链依赖）——走真实充值+支付模拟，不直插余额
// ---------------------------------------------------------------------------
async function ensureCard() {
  const usable = await apiOk('/mini/card/usable-list', {}, ctx.owner)
  const existing = (usable || []).find(c => c.canRecharge)
  if (existing) {
    ctx.cardId = existing.cardId
    return
  }
  const requestId = crypto.randomUUID()
  const created = await apiOk('/mini/order/recharge/create', { packageId: ACC_PACKAGE_ID, requestId }, ctx.owner)
  await apiOk('/mini/pay-sim/pay', { orderNo: created.orderNo }, ctx.owner)
  const card = await pollUntil('充值到账应发卡', async () => {
    const list = await apiOk('/mini/card/usable-list', {}, ctx.owner)
    const hit = (list || []).find(c => c.canRecharge)
    return hit || null
  }, 30000)
  ctx.cardId = card.cardId
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  if (scenarios.length !== 16) {
    console.error(`场景登记数=${scenarios.length}，必须恰为 16；拒绝执行`)
    process.exit(2)
  }
  const mysql = require('mysql2/promise')
  pool = await mysql.createPool({ ...DB_CONFIG, connectionLimit: 6 })

  const envFingerprint = await verifyEnvironment()
  console.log(`环境指纹：base=${envFingerprint.base} db=${envFingerprint.dbHost}:${envFingerprint.dbPort} jar=${(envFingerprint.jarSha256 || '').slice(0, 12)} round=${ROUND}`)

  ctx.ops = await opsLogin(OPS_LOGIN)
  ctx.ops2 = await opsLogin(OPS2_LOGIN)
  ctx.owner = await miniLogin(OWNER_PHONE)
  ctx.other = await miniLogin(OTHER_PHONE)
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
    chain: 'E2E-05',
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

  // 出口污染扫描：手机号/本机路径/令牌头 + 本轮运行态真实秘密，命中即拒绝落盘
  const serialized = JSON.stringify(result, null, 2)
  const forbidden = [
    // 前后数字边界：指令号/批次号/时间戳是 14~20 位连续数字，任意 11 位窗口都会误中裸手机号正则
    { name: '手机号原文', re: /(?<!\d)1[3-9]\d{9}(?!\d)/ },
    { name: '本机绝对路径', re: /\/(?:Users|home)\// },
    { name: 'Sa-Token 令牌头', re: /dakang-token/ },
  ]
  const runtimeSecrets = [
    { name: '机主 Token', value: ctx.owner && ctx.owner.tokenValue },
    { name: '对照账号 Token', value: ctx.other && ctx.other.tokenValue },
    { name: '运营 Token', value: ctx.ops && ctx.ops.tokenValue },
    { name: '运营乙 Token', value: ctx.ops2 && ctx.ops2.tokenValue },
    { name: '运营登录凭据', value: OPS_PWD_CIPHER },
    { name: '数据库口令', value: DB_CONFIG.password },
  ].filter(item => typeof item.value === 'string' && item.value.length > 0)
  const leaked = [
    ...forbidden.filter(f => f.re.test(serialized)),
    ...runtimeSecrets.filter(secret => serialized.includes(secret.value)),
  ]
  if (leaked.length) {
    for (const item of leaked) {
      if (item.re) {
        const m = serialized.match(item.re)
        console.error(`  命中【${item.name}】上下文：…${serialized.slice(Math.max(0, m.index - 40), m.index + 50)}…`)
      }
    }
    console.error(`验收产物含禁止信息，拒绝落盘：${leaked.map(f => f.name).join('、')}`)
    process.exit(3)
  }
  fs.mkdirSync(RUN_DIR, { recursive: true })
  fs.writeFileSync(path.join(RUN_DIR, 'result.json'), `${serialized}\n`)

  console.log(`\nSUMMARY round=${ROUND} scenarios=${passed}/${result.total} ${result.pass ? 'PASS' : 'FAIL'}`)
  console.log(`BATCH_DIR=docs/acceptance/device-ops/runs/${BATCH_ID}`)
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
