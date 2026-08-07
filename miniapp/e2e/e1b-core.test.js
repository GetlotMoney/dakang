const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { Buffer } = require('node:buffer')
const zlib = require('node:zlib')
const {
  EXPECTED_REAL_MODES,
  FULL_CONFIRMATION,
  FULL_STEP_NAMES,
  REQUIRED_DB_ASSERTIONS,
  REQUIRED_SHOTS,
  assertRunAuthorized,
  evaluateFullEvidence,
  inspectPng,
  resolveMode,
  sha256File,
} = require('./e1b-core')

function crc32(data) {
  let crc = 0xFFFFFFFF
  for (const byte of data) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc & 1) ? (0xEDB88320 ^ (crc >>> 1)) : (crc >>> 1)
    }
  }
  return (crc ^ 0xFFFFFFFF) >>> 0
}

function pngChunk(type, payload) {
  const typeBytes = Buffer.from(type, 'ascii')
  const output = Buffer.alloc(payload.length + 12)
  output.writeUInt32BE(payload.length, 0)
  typeBytes.copy(output, 4)
  payload.copy(output, 8)
  output.writeUInt32BE(crc32(Buffer.concat([typeBytes, payload])), payload.length + 8)
  return output
}

function screenshotPng(width = 320, height = 640) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  const row = Buffer.alloc(1 + width * 4, 0)
  const raw = Buffer.concat(Array.from({ length: height }, () => row))
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

const VALID_SCREENSHOT_PNG = screenshotPng()
const SHOT_PATHS = {
  '01-home.png': 'pages/user/home/index',
  '04-order-list.png': 'pages/user/order/index',
  '05-order-detail.png': 'pages/user/order/detail',
}

function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dakang-e1b-'))
  const shotsDir = path.join(dir, 'shots')
  fs.mkdirSync(shotsDir)
  const shots = REQUIRED_SHOTS.map((name) => {
    const file = path.join(shotsDir, name)
    fs.writeFileSync(file, VALID_SCREENSHOT_PNG)
    const inspected = inspectPng(file)
    return {
      name,
      targetPath: SHOT_PATHS[name],
      targetVerified: true,
      orderNoPresent: name !== '01-home.png',
      size: inspected.size,
      sha256: inspected.sha256,
    }
  })
  const assertions = Object.fromEntries(REQUIRED_DB_ASSERTIONS.map(key => [key, true]))
  fs.writeFileSync(path.join(dir, 'db-verify.json'), JSON.stringify({
    schemaVersion: 1,
    orderNo: 'WO-TEST-1',
    actualMl: 4980,
    assertions,
  }))
  const result = {
    schemaVersion: 1,
    batchId: path.basename(dir),
    mode: 'full',
    orderNo: 'WO-TEST-1',
    pageActualMl: 4980,
    scriptSha256: sha256File(path.join(__dirname, 'run-e1b.js')),
    coreSha256: sha256File(path.join(__dirname, 'e1b-core.js')),
    behavior: {
      pass: true,
      passed: FULL_STEP_NAMES.length,
      total: FULL_STEP_NAMES.length,
      steps: FULL_STEP_NAMES.map(step => ({ step, ok: true, extra: '' })),
      fatalError: '',
    },
    build: {
      runtimeFingerprint: 'build-1',
      diskFingerprint: 'build-1',
      runtimeModes: EXPECTED_REAL_MODES,
      manifestCount: 1,
      manifestSha256: 'a'.repeat(64),
    },
    shots,
  }
  return { dir, result }
}

test('mode must be explicit and full requires a write confirmation', () => {
  assert.throws(() => resolveMode(undefined), /显式设置/)
  assert.throws(() => resolveMode('DIAG'), /拼写错误/)
  assert.equal(resolveMode('diag'), 'diag')
  assert.throws(() => assertRunAuthorized({ mode: 'diag', orderNo: '' }), /E1B_ORDER/)
  assert.throws(() => assertRunAuthorized({ mode: 'shots', orderNo: '   ' }), /E1B_ORDER/)
  assert.throws(() => assertRunAuthorized({ mode: 'full', confirmation: '' }), /真实扣水/)
  assert.doesNotThrow(() => assertRunAuthorized({
    mode: 'full',
    confirmation: FULL_CONFIRMATION,
  }))
})

test('complete evidence requires behavior, exact order, DB assertions and captured PNG hashes', () => {
  const { dir, result } = fixture()
  try {
    const evaluated = evaluateFullEvidence(dir, result)
    assert.equal(evaluated.complete, true)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('behavior failure cannot be converted into complete evidence', () => {
  const { dir, result } = fixture()
  try {
    result.behavior.pass = false
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('behavior pass flag cannot replace the fixed seven successful steps', () => {
  const { dir, result } = fixture()
  try {
    result.behavior = { pass: true }
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('runtime and disk build fingerprints must be identical and nonempty', () => {
  const { dir, result } = fixture()
  try {
    result.build.runtimeFingerprint = 'stale-build'
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
    result.build.runtimeFingerprint = ''
    result.build.diskFingerprint = ''
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('full evidence requires the exact real-domain runtime mode matrix', () => {
  const { dir, result } = fixture()
  try {
    result.build.runtimeModes = 'GLOBAL=mock;DEVICE=mock;ORDER=mock;CARD=mock;RECHARGE=mock'
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('one-pixel placeholder PNG is rejected as screenshot evidence', () => {
  const { dir, result } = fixture()
  try {
    const file = path.join(dir, 'shots', REQUIRED_SHOTS[0])
    fs.writeFileSync(file, screenshotPng(1, 1))
    assert.equal(inspectPng(file).valid, false)
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('screenshot metadata must prove the target and order before capture', () => {
  const { dir, result } = fixture()
  try {
    result.shots[0].targetVerified = false
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
    result.shots[0].targetVerified = true
    result.shots[1].orderNoPresent = false
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('empty PNG files are rejected even when filenames exist', () => {
  const { dir, result } = fixture()
  try {
    fs.writeFileSync(path.join(dir, 'shots', REQUIRED_SHOTS[0]), '')
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('empty DB evidence is rejected', () => {
  const { dir, result } = fixture()
  try {
    fs.writeFileSync(path.join(dir, 'db-verify.json'), '')
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('DB evidence for another order is rejected', () => {
  const { dir, result } = fixture()
  try {
    const dbPath = path.join(dir, 'db-verify.json')
    const db = JSON.parse(fs.readFileSync(dbPath, 'utf8'))
    db.orderNo = 'WO-OTHER'
    fs.writeFileSync(dbPath, JSON.stringify(db))
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('DB assertions and actual water volume must match the page evidence', () => {
  const { dir, result } = fixture()
  try {
    const dbPath = path.join(dir, 'db-verify.json')
    const db = JSON.parse(fs.readFileSync(dbPath, 'utf8'))
    db.assertions.walletFlowContinuous = false
    fs.writeFileSync(dbPath, JSON.stringify(db))
    assert.equal(evaluateFullEvidence(dir, result).complete, false)

    db.assertions.walletFlowContinuous = true
    db.actualMl = result.pageActualMl + 1
    fs.writeFileSync(dbPath, JSON.stringify(db))
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('a replaced screenshot is rejected by its captured hash', () => {
  const { dir, result } = fixture()
  try {
    const file = path.join(dir, 'shots', REQUIRED_SHOTS[1])
    fs.writeFileSync(file, Buffer.concat([VALID_SCREENSHOT_PNG, Buffer.from('changed')]))
    assert.notEqual(sha256File(file), result.shots[1].sha256)
    assert.equal(evaluateFullEvidence(dir, result).complete, false)
  }
  finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

// S2：扫码入口回归。页面早已改用 uni.scanCode，脚本若还 mock showActionSheet 选码，
// 就会绿着跑过一个真实不存在的动线——这正是本轮要消灭的「自动化与入口脱节」。
test('E1b 扫码步骤使用 scanCode，且仓库内不再残留旧的 showActionSheet 选码入口', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const runner = fs.readFileSync(path.join(__dirname, 'run-e1b.js'), 'utf8')

  assert.match(runner, /mockWxMethod\('scanCode'/, '必须 mock 微信 scanCode')
  assert.match(runner, /scanType: 'QR_CODE'/, 'scanCode 返回必须带 scanType')
  assert.match(runner, /errMsg: 'scanCode:ok'/, 'scanCode 返回必须带成功 errMsg')
  assert.match(runner, /const SCAN_CODE = 'DK-QR-DEV0001-O1'/, '样例码必须是种子里的真实码')
  // 旧入口只允许出现在解释性注释里，绝不能再有可执行的 mock 调用
  assert.ok(!/mockWxMethod\('showActionSheet'/.test(runner), '不得再 mock showActionSheet 选码')
  // 步骤三必须证明页面真的消费了扫码返回值，而不只是"到了确认页"。
  // 断言必须锚定到可执行构造：裸 /scanSessionId/ 会匹配上方的解释性注释，
  // 把真正的取值与判定整段删掉也依旧全绿。
  assert.match(
    runner,
    /confirmPage\.query\.scanSessionId/,
    '确认页断言须从 query 取出 scanSessionId',
  )
  assert.match(runner, /consumedScan\s*=\s*String\(query\)\.length > 0/, 'scanSessionId 必须参与判定而非仅取值')
  assert.match(runner, /consumedScan\s*&&/, 'consumedScan 必须计入步骤三的 check 结论')
  // S2 报价冻结提示同样要真参与判定，不能只出现在注释里
  assert.match(runner, /hasQuoteHint\s*=\s*\/本次扫码报价有效至\//, '须断言报价有效期提示在页')
  assert.match(runner, /hasQuoteHint,/, 'hasQuoteHint 必须计入步骤三的 check 结论')
})

test('E1b 固定 7 步与步骤名同步更新为 scanCode', () => {
  assert.equal(FULL_STEP_NAMES.length, 7)
  assert.equal(FULL_STEP_NAMES[1], '02 mock 微信 scanCode 返回真码')
  assert.ok(!FULL_STEP_NAMES.some(name => name.includes('showActionSheet')))
})
