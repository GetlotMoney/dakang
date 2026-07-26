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
