const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')

const { checkRuntimeModes, formatRuntimeModes } = require('./runtime-modes')

const VALID_MODES = new Set(['diag', 'shots', 'full'])
const FULL_CONFIRMATION = 'I_ACCEPT_REAL_WATER_DEDUCTION'
/**
 * E1b 验收构建：device/order/card 接真、recharge 代码层锁 mock、auth 为正式微信登录（real）。
 * AUTH=real 的原因：E1b 要求「由正式登录流程建立的 KH_USER 会话」；而入口页在
 * 「有业务域接真但 auth=mock」时会拒绝以 Mock 原型账号进入并停在登录入口，
 * 因此只有 auth=real 才是能真正走到业务步骤且不借用原型身份的验收组合。
 */
const EXPECTED_REAL_MODE_MAP = {
  GLOBAL: 'mock',
  DEVICE: 'real',
  ORDER: 'real',
  CARD: 'real',
  RECHARGE: 'mock',
  AUTH: 'real',
  // E1b 是扫码取水链验收：配送域必须保持 mock，验收构建不得顺带打开配送真实写链
  DELIVERY: 'mock',
}
const EXPECTED_REAL_MODES = formatRuntimeModes(EXPECTED_REAL_MODE_MAP)
const FULL_STEP_NAMES = [
  '01 首页可编译进入',
  '02 mock showActionSheet 选真码',
  '03 扫码解析并进入取水确认',
  '04 确认页真卡余额',
  '05 下单进入取水进度',
  '06 订单列表可查同单',
  '07 订单详情可打开',
]
const REQUIRED_SHOTS = ['01-home.png', '04-order-list.png', '05-order-detail.png']
const REQUIRED_SHOT_PATHS = {
  '01-home.png': 'pages/user/home/index',
  '04-order-list.png': 'pages/user/order/index',
  '05-order-detail.png': 'pages/user/order/detail',
}
const REQUIRED_DB_ASSERTIONS = [
  'orderExists',
  'orderStatusFinished',
  'commandLinked',
  'commandTerminal',
  'walletFlowContinuous',
  'cardBalanceMatchesLatestFlow',
  'actualMlMatchesOrder',
]

function resolveMode(rawMode) {
  const mode = String(rawMode || '').trim()
  if (!VALID_MODES.has(mode)) {
    throw new Error('必须显式设置 E1B_MODE=diag、shots 或 full；未设置或拼写错误时禁止执行')
  }
  return mode
}

function assertRunAuthorized({ mode, orderNo, confirmation }) {
  if ((mode === 'diag' || mode === 'shots') && !String(orderNo || '').trim()) {
    throw new Error(`${mode} 模式必须提供 E1B_ORDER=<既有订单号>（无业务写入）`)
  }
  if (mode === 'full' && confirmation !== FULL_CONFIRMATION) {
    throw new Error(`full 会真实扣水；必须显式设置 E1B_ALLOW_REAL_WRITE=${FULL_CONFIRMATION}`)
  }
}

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

let crcTable
function crc32(data) {
  if (!crcTable) {
    crcTable = Array.from({ length: 256 }, (_, index) => {
      let value = index
      for (let bit = 0; bit < 8; bit++) {
        value = (value & 1) ? (0xEDB88320 ^ (value >>> 1)) : (value >>> 1)
      }
      return value >>> 0
    })
  }
  let crc = 0xFFFFFFFF
  for (const byte of data) {
    crc = crcTable[(crc ^ byte) & 0xFF] ^ (crc >>> 8)
  }
  return (crc ^ 0xFFFFFFFF) >>> 0
}

function inspectPng(file) {
  try {
    const data = fs.readFileSync(file)
    const signature = Buffer.from('89504e470d0a1a0a', 'hex')
    if (data.length < 45 || !data.subarray(0, 8).equals(signature)) {
      return { valid: false, size: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex') }
    }
    let offset = 8
    let width = 0
    let height = 0
    let chunkIndex = 0
    let hasIdat = false
    let ended = false
    while (offset + 12 <= data.length) {
      const length = data.readUInt32BE(offset)
      const end = offset + 12 + length
      if (length > data.length || end > data.length) {
        break
      }
      const type = data.subarray(offset + 4, offset + 8).toString('ascii')
      const payload = data.subarray(offset + 8, offset + 8 + length)
      const expectedCrc = data.readUInt32BE(offset + 8 + length)
      const actualCrc = crc32(data.subarray(offset + 4, offset + 8 + length))
      if (expectedCrc !== actualCrc) {
        break
      }
      if (chunkIndex === 0) {
        if (type !== 'IHDR' || length !== 13) {
          break
        }
        width = payload.readUInt32BE(0)
        height = payload.readUInt32BE(4)
      }
      if (type === 'IDAT') {
        hasIdat = true
      }
      offset = end
      chunkIndex += 1
      if (type === 'IEND') {
        ended = length === 0 && offset === data.length
        break
      }
    }
    // 开发者工具页面截图不可能是 1×1 占位图；尺寸门同时阻断伪造的极小 PNG。
    const valid = ended && hasIdat && width >= 200 && height >= 300
    return {
      valid,
      size: data.length,
      sha256: crypto.createHash('sha256').update(data).digest('hex'),
      width,
      height,
    }
  }
  catch {
    return { valid: false, size: 0, sha256: '', width: 0, height: 0 }
  }
}

function readJson(file) {
  try {
    const text = fs.readFileSync(file, 'utf8').trim()
    return text ? JSON.parse(text) : null
  }
  catch {
    return null
  }
}

function evaluateFullEvidence(batchDir, result) {
  const reasons = []
  const orderNo = String(result?.orderNo || '').trim()

  if (result?.schemaVersion !== 1) {
    reasons.push('result.json schemaVersion 不是 1')
  }
  if (result?.batchId !== path.basename(path.resolve(batchDir))) {
    reasons.push('批次 ID 与批次目录名不一致')
  }
  if (result?.mode !== 'full') {
    reasons.push('批次 mode 不是 full')
  }
  const behavior = result?.behavior
  const behaviorSteps = Array.isArray(behavior?.steps) ? behavior.steps : []
  const exactSteps = behaviorSteps.length === FULL_STEP_NAMES.length
    && behaviorSteps.every((item, index) => item?.step === FULL_STEP_NAMES[index] && item?.ok === true)
  if (behavior?.pass !== true
    || behavior?.passed !== FULL_STEP_NAMES.length
    || behavior?.total !== FULL_STEP_NAMES.length
    || String(behavior?.fatalError || '').trim()
    || !exactSteps) {
    reasons.push('行为证据不是固定 7/7、步骤名/逐步结果不完整或存在 fatalError')
  }
  if (!orderNo) {
    reasons.push('批次订单号为空')
  }
  if (!result?.build?.runtimeFingerprint
    || result.build.runtimeFingerprint !== result.build.diskFingerprint) {
    reasons.push('运行包 fingerprint 与磁盘构建 fingerprint 不一致')
  }
  const modeReasons = checkRuntimeModes(result?.build?.runtimeModes, EXPECTED_REAL_MODE_MAP)
  if (modeReasons.length) {
    reasons.push(`运行包 API 模式不是 E1b 验收组合（${EXPECTED_REAL_MODES}）：${modeReasons.join('；')}`)
  }
  if (!Number.isInteger(result?.build?.manifestCount) || result.build.manifestCount <= 0
    || !/^[a-f0-9]{64}$/.test(String(result?.build?.manifestSha256 || ''))) {
    reasons.push('构建 manifest 证据缺失或格式错误')
  }
  if (result?.scriptSha256 !== sha256File(path.join(__dirname, 'run-e1b.js'))
    || result?.coreSha256 !== sha256File(__filename)) {
    reasons.push('批次脚本/证据核心 SHA 与当前验证器不一致')
  }
  if (!Number.isInteger(result?.pageActualMl) || result.pageActualMl <= 0) {
    reasons.push('页面实际水量缺失或不大于 0')
  }

  const shotChecks = []
  for (const name of REQUIRED_SHOTS) {
    const metadata = Array.isArray(result?.shots)
      ? result.shots.find(item => item?.name === name)
      : null
    const file = path.join(batchDir, 'shots', name)
    const inspected = inspectPng(file)
    const needsOrder = name !== '01-home.png'
    const ok = !!metadata
      && metadata.targetVerified === true
      && metadata.targetPath === REQUIRED_SHOT_PATHS[name]
      && (!needsOrder || metadata.orderNoPresent === true)
      && inspected.valid
      && inspected.size === metadata.size
      && inspected.sha256 === metadata.sha256
    shotChecks.push({
      name,
      ok,
      size: inspected.size,
      width: inspected.width,
      height: inspected.height,
      sha256: inspected.sha256,
    })
    if (!ok) {
      reasons.push(`截图证据无效：${name}`)
    }
  }

  const dbPath = path.join(batchDir, 'db-verify.json')
  const db = readJson(dbPath)
  let dbOk = true
  if (!db || db.schemaVersion !== 1) {
    reasons.push('db-verify.json 缺失、为空、格式错误或版本不符')
    dbOk = false
  }
  if (dbOk && db.orderNo !== orderNo) {
    reasons.push('DB 证据订单号与批次订单号不一致')
    dbOk = false
  }
  if (dbOk) {
    for (const key of REQUIRED_DB_ASSERTIONS) {
      if (db.assertions?.[key] !== true) {
        reasons.push(`DB 断言未通过：${key}`)
        dbOk = false
      }
    }
  }
  if (dbOk && (!Number.isInteger(db.actualMl) || db.actualMl !== result.pageActualMl)) {
    reasons.push('DB ACTUAL_ML 与页面实际水量不一致')
    dbOk = false
  }

  return {
    status: reasons.length === 0 ? 'complete' : 'incomplete',
    complete: reasons.length === 0,
    reasons,
    shotChecks,
    db: {
      file: 'db-verify.json',
      valid: dbOk,
      orderNo: db?.orderNo || '',
      actualMl: Number.isInteger(db?.actualMl) ? db.actualMl : null,
    },
  }
}

module.exports = {
  FULL_CONFIRMATION,
  EXPECTED_REAL_MODES,
  EXPECTED_REAL_MODE_MAP,
  FULL_STEP_NAMES,
  REQUIRED_DB_ASSERTIONS,
  REQUIRED_SHOTS,
  assertRunAuthorized,
  evaluateFullEvidence,
  inspectPng,
  resolveMode,
  sha256File,
}
