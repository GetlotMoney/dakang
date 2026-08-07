/**
 * E1b 扫码取水验收（微信开发者工具 mp-weixin 运行态，非物理真机）。
 *
 * 安全模式必须显式指定：
 * - diag：既有订单无写入回放；必须 E1B_ORDER。
 * - shots：既有订单无写入截图验证；必须 E1B_ORDER。
 * - full：真实下单并扣水；除 E1B_MODE=full 外还必须提供二次确认令牌。
 *
 * 未设置/拼错模式、full 缺确认令牌、前置步骤任一失败时，脚本均在真实下单前 fail-closed。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { checkRuntimeModes } = require('./runtime-modes')
const { cell, newBatchId, writeBatchFiles } = require('./report-core')
const {
  EXPECTED_REAL_MODES,
  EXPECTED_REAL_MODE_MAP,
  FULL_STEP_NAMES,
  REQUIRED_SHOTS,
  assertRunAuthorized,
  evaluateFullEvidence,
  inspectPng,
  resolveMode,
  sha256File,
} = require('./e1b-core')

/** E1b 固定样例码：ws_qrcode 种子数据里唯一绑定 DEV0001 一号口的真实码。 */
const SCAN_CODE = 'DK-QR-DEV0001-O1'

const VERIFY_INDEX = process.argv.indexOf('--verify-evidence')
const VERIFY_ONLY = VERIFY_INDEX >= 0
const WS_ENDPOINT = process.env.WX_AUTO_WS || 'ws://127.0.0.1:9420'
const EVIDENCE_ORDER = (process.env.E1B_ORDER || '').trim()

let MODE = 'verify'
if (!VERIFY_ONLY) {
  try {
    MODE = resolveMode(process.env.E1B_MODE)
    assertRunAuthorized({
      mode: MODE,
      orderNo: EVIDENCE_ORDER,
      confirmation: process.env.E1B_ALLOW_REAL_WRITE,
    })
  }
  catch (error) {
    console.error(`E1B 安全闸拒绝执行：${error.message}`)
    process.exit(2)
  }
}

const ACCEPT_DIR = path.resolve(__dirname, '../../docs/acceptance/miniapp-e1b')
const DIST_DIR = path.resolve(__dirname, '../dist/build/mp-weixin')
const BATCH_ID = newBatchId()
const RUN_DIR = path.join(ACCEPT_DIR, MODE === 'full' ? 'runs' : 'runs-diag', BATCH_ID)
const SHOTS_DIR = path.join(RUN_DIR, 'shots')
const STEP_TIMEOUT_MS = 15000
const GLOBAL_TIMEOUT_MS = 5 * 60 * 1000

const FULL_STEPS = FULL_STEP_NAMES
const DIAG_STEPS = [
  'D1 连接、首页文本与运行构建一致',
  'D2 订单列表含既有单且同卡片带状态',
  'D3 订单详情终态与实际水量',
]
const SHOTS_STEPS = ['S1 独立连接补拍三张已核目标截图（既有订单，不下单）']
const STEP_LABELS = MODE === 'diag' ? DIAG_STEPS : MODE === 'shots' ? SHOTS_STEPS : FULL_STEPS

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const results = STEP_LABELS.map(stepName => ({ step: stepName, ok: false, extra: '未执行' }))
const warnings = []
const shotEvidence = []
let fatalError = ''
let orderNo = MODE === 'diag' || MODE === 'shots' ? EVIDENCE_ORDER : ''
let pageActualMl = null
let diskBuildFingerprint = ''
let runtimeBuildFingerprint = ''
let runtimeApiModes = ''
let automator

function automatorClient() {
  automator ||= require('miniprogram-automator')
  return automator
}

function check(stepName, ok, extra = '') {
  const slot = results.find(item => item.step === stepName)
  if (slot) {
    slot.ok = ok
    slot.extra = extra
  }
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${stepName}${extra ? ` | ${extra}` : ''}`)
}

function T(promise, label, ms = STEP_TIMEOUT_MS) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`TIMEOUT:${label}`)), ms)),
  ])
}

async function step(stepName, fn) {
  try {
    await fn()
  }
  catch (error) {
    check(stepName, false, error && error.message ? error.message : String(error))
  }
}

function requirePassed(stepNames) {
  const failed = stepNames.filter(name => !results.find(item => item.step === name)?.ok)
  if (failed.length) {
    throw new Error(`PRECONDITION_FAILED：${failed.join('、')}；禁止执行真实下单步骤`)
  }
}

function safeSha256(file) {
  try {
    return sha256File(file)
  }
  catch {
    return '(不可读)'
  }
}

function distManifestHash() {
  const exts = new Set(['.js', '.json', '.wxml', '.wxss'])
  const files = []
  const walk = (dir) => {
    let entries = []
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
    }
    catch {
      return
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      }
      else if (exts.has(path.extname(entry.name))) {
        files.push(full)
      }
    }
  }
  walk(DIST_DIR)
  files.sort()
  const aggregate = crypto.createHash('sha256')
  for (const file of files) {
    aggregate
      .update(path.relative(DIST_DIR, file))
      .update('\0')
      .update(safeSha256(file))
      .update('\n')
  }
  return { count: files.length, digest: aggregate.digest('hex') }
}

function readDiskBuildFingerprint() {
  const file = path.join(DIST_DIR, 'build-fingerprint.json')
  let value
  try {
    value = JSON.parse(fs.readFileSync(file, 'utf8')).fingerprint
  }
  catch {
    throw new Error('构建产物缺少有效 build-fingerprint.json，请先重新构建 mp-weixin')
  }
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error('build-fingerprint.json 的 fingerprint 为空或类型错误')
  }
  const manifest = distManifestHash()
  if (manifest.count === 0) {
    throw new Error('构建产物 manifest 为空，禁止把未构建目录作为运行证据')
  }
  diskBuildFingerprint = value.trim()
  return diskBuildFingerprint
}

async function pageTextOf(mp) {
  const page = await T(mp.currentPage(), 'currentPage')
  const root = await T(page.$('.page-shell'), 'query .page-shell')
  const text = root ? (await T(root.text(), 'read .page-shell text')) || '' : ''
  return { page, text }
}

async function verifyRuntimeBuild(mp) {
  readDiskBuildFingerprint()
  const page = await T(mp.currentPage(), 'currentPage fingerprint')
  const marker = await T(page.$('.e2e-build-fingerprint'), 'query build fingerprint')
  const text = marker ? ((await T(marker.text(), 'read build fingerprint')) || '').trim() : ''
  if (!text) {
    throw new Error('运行页面未暴露构建 fingerprint，可能仍是旧缓存包')
  }
  const matched = /^BUILD=([^;]+);(.+)$/.exec(text)
  if (!matched) {
    throw new Error('运行页面构建标记缺少 fingerprint 或 API 模式矩阵')
  }
  runtimeBuildFingerprint = matched[1].trim()
  runtimeApiModes = matched[2].trim()
  if (runtimeBuildFingerprint !== diskBuildFingerprint) {
    throw new Error(`运行包 fingerprint(${runtimeBuildFingerprint}) 与磁盘构建(${diskBuildFingerprint}) 不一致`)
  }
  const modeReasons = checkRuntimeModes(runtimeApiModes, EXPECTED_REAL_MODE_MAP)
  if (modeReasons.length) {
    // 逐域断言（与 run-d4 一致）：报精确原因，不再整串全等比较。
    throw new Error(`E1b 仅允许 ${EXPECTED_REAL_MODES}，当前=${runtimeApiModes}；不符：${modeReasons.join('；')}`)
  }
  return `${runtimeBuildFingerprint};${runtimeApiModes}`
}

async function readOrderEvidence(page) {
  const marker = await T(page.$('.e2e-order-evidence'), 'query order evidence')
  const text = marker ? ((await T(marker.text(), 'read order evidence')) || '').trim() : ''
  const matched = /ORDER_STATUS=(\d+);ACTUAL_ML=(-?\d+|null)/.exec(text)
  if (!matched) {
    throw new Error('订单详情缺少结构化状态/实际水量证据')
  }
  const status = Number(matched[1])
  const actual = matched[2] === 'null' ? null : Number(matched[2])
  if (status !== 4) {
    throw new Error(`订单顶层状态不是已完成(4)，当前=${status}`)
  }
  if (!Number.isInteger(actual) || actual <= 0) {
    throw new Error(`实际水量必须为大于 0 的整数毫升，当前=${matched[2]}`)
  }
  return { status, actualMl: actual }
}

async function findOrderCard(mp, targetOrderNo) {
  const page = await T(mp.currentPage(), 'currentPage list')
  const cards = await T(page.$$('.order-item'), 'query .order-item list')
  for (const card of cards) {
    const text = ((await T(card.text(), 'read card text')) || '')
    if (text.includes(targetOrderNo)) {
      return text
    }
  }
  return ''
}

async function waitForPath(mp, fragment, attempts = 8) {
  let last
  for (let i = 0; i < attempts; i++) {
    await sleep(1000)
    last = await pageTextOf(mp)
    if (last.page.path.includes(fragment)) {
      return last
    }
  }
  return last
}

async function navigateToOrderDetail(mp) {
  await T(mp.navigateTo(`/pages/user/order/detail?orderNo=${encodeURIComponent(orderNo)}`), 'navigateTo detail', 20000)
    .catch((error) => {
      warnings.push(`navigateTo detail：${error.message}（以页面实际到达结果为准）`)
    })
  return waitForPath(mp, 'user/order/detail')
}

async function assertListAndDetail(mp, stepList, stepDetail) {
  await step(stepList, async () => {
    await T(mp.switchTab('/pages/user/order/index'), 'switchTab order', 20000)
    await sleep(2500)
    const cardText = await findOrderCard(mp, orderNo)
    const hasStatus = /已完成|出水中|已支付/.test(cardText)
    check(
      stepList,
      !!cardText && hasStatus,
      cardText ? `同卡片含单号，状态可见=${hasStatus}` : `列表卡片未见 ${orderNo || '(无 orderNo)'}`,
    )
  })

  await step(stepDetail, async () => {
    const reached = await navigateToOrderDetail(mp)
    if (!reached || !reached.page.path.includes('user/order/detail') || !reached.text.includes(orderNo)) {
      throw new Error(`详情页未到达或未显示同一订单 ${orderNo}`)
    }
    const evidence = await readOrderEvidence(reached.page)
    pageActualMl = evidence.actualMl
    check(stepDetail, true, `path=${reached.page.path} 同单=true ORDER_STATUS=4 ACTUAL_ML=${pageActualMl}`)
  })
}

async function runDiag(mp) {
  await step(DIAG_STEPS[0], async () => {
    await T(mp.reLaunch('/pages/user/home/index'), 'reLaunch home', 30000)
    await sleep(2500)
    const probes = []
    for (let i = 0; i < 3; i++) {
      const started = Date.now()
      const { page, text } = await pageTextOf(mp)
      probes.push(`${Date.now() - started}ms`)
      if (!page.path.includes('user/home/index') || !/扫码取水/.test(text)) {
        throw new Error('首页路径或文本不符合预期')
      }
      await sleep(500)
    }
    const fingerprint = await verifyRuntimeBuild(mp)
    check(DIAG_STEPS[0], true, `pageText×3=${probes.join('/')} fingerprint=${fingerprint}`)
  })
  await assertListAndDetail(mp, DIAG_STEPS[1], DIAG_STEPS[2])
}

async function runFull(mp) {
  await step(FULL_STEPS[0], async () => {
    await T(mp.reLaunch('/pages/user/home/index'), 'reLaunch home', 30000)
    await sleep(2500)
    const { page, text } = await pageTextOf(mp)
    if (!page.path.includes('user/home/index') || !/扫码取水/.test(text)) {
      throw new Error(`首页路径或文本异常：${page.path}`)
    }
    const fingerprint = await verifyRuntimeBuild(mp)
    check(FULL_STEPS[0], true, `path=${page.path} fingerprint=${fingerprint}`)
  })

  await step(FULL_STEPS[1], async () => {
    // 页面入口早已是 uni.scanCode（mock 选择器随 mock 基建一并退役）。
    // 继续 mock showActionSheet 只会让脚本"绿着"跑过一个真实不存在的动线。
    await T(mp.mockWxMethod('scanCode', {
      result: SCAN_CODE,
      scanType: 'QR_CODE',
      charSet: 'utf-8',
      errMsg: 'scanCode:ok',
    }), 'mock scanCode')
    check(FULL_STEPS[1], true, `scanCode → ${SCAN_CODE}`)
  })

  await step(FULL_STEPS[2], async () => {
    const page = await T(mp.currentPage(), 'currentPage before tap')
    const buttons = await T(page.$$('button'), 'list buttons')
    let tapped = false
    for (const button of buttons) {
      const text = ((await T(button.text(), 'button text')) || '').trim()
      if (text.includes('扫码取水')) {
        await T(button.tap(), 'tap 扫码取水')
        tapped = true
        break
      }
    }
    if (!tapped) {
      throw new Error('未找到「扫码取水」按钮')
    }
    await sleep(3500)
    const { page: confirmPage, text } = await pageTextOf(mp)
    const onConfirm = confirmPage.path.includes('user/water/confirm')
    const hasStation = /光谷/.test(text)
    const hasPrice = /[¥￥]0\.20\/升/.test(text)
    // 页面必须真的消费了 scanCode 的返回值：确认页的 scanSessionId 由该码解析而来，
    // 只断言"到了确认页"不够——mock 没生效时页面也可能因其它路径到达。
    const query = (confirmPage.query && confirmPage.query.scanSessionId) || ''
    const consumedScan = String(query).length > 0
    // 报价冻结提示必须在页（S2）：它证明展示的是扫码会话里的那一份价，而不是当前档案价
    const hasQuoteHint = /本次扫码报价有效至/.test(text)
    check(
      FULL_STEPS[2],
      onConfirm && hasStation && hasPrice && consumedScan && hasQuoteHint,
      `path=${confirmPage.path} 站点=${hasStation} 单价¥0.20/升=${hasPrice} `
      + `消费扫码会话=${consumedScan} 报价提示=${hasQuoteHint}`,
    )
  })

  await step(FULL_STEPS[3], async () => {
    const { page, text } = await pageTextOf(mp)
    const stillOnConfirm = page.path.includes('user/water/confirm')
    const balanceSection = text.includes('余额') ? text.slice(text.indexOf('余额')) : ''
    const amountMatch = /[¥￥]\s*(\d+(?:\.\d{1,2})?)/.exec(balanceSection)
    const waterMatch = /(\d+(?:\.\d+)?)\s*L/.exec(balanceSection)
    check(
      FULL_STEPS[3],
      stillOnConfirm && !!amountMatch && !!waterMatch,
      `仍在确认页=${stillOnConfirm} 余额=¥${amountMatch ? amountMatch[1] : '未见'} 水量=${waterMatch ? `${waterMatch[1]}L` : '未见'}`,
    )
  })

  // 真实副作用闸：01～04 任一失败，下面的点击永远不会执行。
  requirePassed(FULL_STEPS.slice(0, 4))

  await step(FULL_STEPS[4], async () => {
    const page = await T(mp.currentPage(), 'currentPage confirm')
    const buttons = await T(page.$$('button'), 'list buttons')
    let tapped = false
    for (const button of buttons) {
      const text = ((await T(button.text(), 'button text')) || '').trim()
      if (text.includes('确认并开始取水')) {
        await T(button.tap(), 'tap 下单')
        tapped = true
        break
      }
    }
    if (!tapped) {
      throw new Error('未找到「确认并开始取水」按钮')
    }
    let onProgress = false
    let text = ''
    for (let i = 0; i < 10; i++) {
      await sleep(1200)
      const reached = await pageTextOf(mp)
      if (reached.page.path.includes('user/water/progress')) {
        onProgress = true
        text = reached.text
        const matched = /(WO[0-9A-Z]{10,})/.exec(text)
        if (matched) {
          orderNo = matched[1]
        }
        if (orderNo && /已支付/.test(text)) {
          break
        }
      }
    }
    const paid = /已支付/.test(text)
    check(
      FULL_STEPS[4],
      onProgress && !!orderNo && paid,
      `进度页=${onProgress} orderNo=${orderNo || '未取得'} 已支付=${paid}`,
    )
  })
  requirePassed([FULL_STEPS[4]])

  await assertListAndDetail(mp, FULL_STEPS[5], FULL_STEPS[6])
}

async function captureShotsSeparately() {
  fs.mkdirSync(SHOTS_DIR, { recursive: true })
  let shotMp
  try {
    shotMp = await T(automatorClient().connect({ wsEndpoint: WS_ENDPOINT }), 'shots connect', 20000)
    const targets = [
      {
        name: '01-home.png',
        navigate: () => shotMp.reLaunch('/pages/user/home/index'),
        verify: async () => {
          const reached = await waitForPath(shotMp, 'user/home/index')
          if (!reached || !/扫码取水/.test(reached.text)) {
            throw new Error('首页截图前路径/文本断言失败')
          }
          await verifyRuntimeBuild(shotMp)
          return { targetPath: reached.page.path, orderNoPresent: false }
        },
      },
      {
        name: '04-order-list.png',
        navigate: () => shotMp.switchTab('/pages/user/order/index'),
        verify: async () => {
          const reached = await waitForPath(shotMp, 'user/order/index')
          const cardText = await findOrderCard(shotMp, orderNo)
          if (!reached || !cardText || !/已完成|出水中|已支付/.test(cardText)) {
            throw new Error('订单列表截图前同单号/状态断言失败')
          }
          return { targetPath: reached.page.path, orderNoPresent: true }
        },
      },
      {
        name: '05-order-detail.png',
        navigate: () => shotMp.navigateTo(`/pages/user/order/detail?orderNo=${encodeURIComponent(orderNo)}`),
        verify: async () => {
          const reached = await waitForPath(shotMp, 'user/order/detail')
          if (!reached || !reached.text.includes(orderNo)) {
            throw new Error('订单详情截图前同单号断言失败')
          }
          const evidence = await readOrderEvidence(reached.page)
          pageActualMl ??= evidence.actualMl
          return { targetPath: reached.page.path, orderNoPresent: true }
        },
      },
    ]

    for (const target of targets) {
      try {
        await T(target.navigate(), `shots navigate ${target.name}`, 20000).catch((error) => {
          warnings.push(`shots navigate ${target.name}：${error.message}（继续核验实际页面）`)
        })
        const verified = await target.verify()
        const file = path.join(SHOTS_DIR, target.name)
        await T(shotMp.screenshot({ path: file }), `shot ${target.name}`, 15000)
        const inspected = inspectPng(file)
        if (!inspected.valid) {
          throw new Error('截图文件不是有效 PNG')
        }
        shotEvidence.push({
          name: target.name,
          targetPath: verified.targetPath,
          targetVerified: true,
          orderNoPresent: verified.orderNoPresent,
          size: inspected.size,
          sha256: inspected.sha256,
        })
      }
      catch (error) {
        warnings.push(`shot:${target.name} ${error.message}`)
      }
    }
  }
  finally {
    if (shotMp) {
      await T(shotMp.disconnect(), 'shots disconnect', 5000).catch(() => {})
    }
  }
}

function behaviorSnapshot() {
  const passed = results.filter(item => item.ok).length
  return {
    pass: passed === STEP_LABELS.length && !fatalError,
    passed,
    total: STEP_LABELS.length,
    steps: results,
    fatalError,
  }
}

function buildResult() {
  const behavior = behaviorSnapshot()
  const manifest = distManifestHash()
  const result = {
    schemaVersion: 1,
    batchId: BATCH_ID,
    mode: MODE,
    orderNo,
    pageActualMl,
    createdAt: new Date().toISOString(),
    scriptSha256: safeSha256(__filename),
    coreSha256: safeSha256(path.join(__dirname, 'e1b-core.js')),
    build: {
      manifestCount: manifest.count,
      manifestSha256: manifest.digest,
      diskFingerprint: diskBuildFingerprint,
      runtimeFingerprint: runtimeBuildFingerprint,
      runtimeModes: runtimeApiModes,
    },
    behavior,
    shots: shotEvidence,
    warnings,
    evidence: { status: 'not-applicable', complete: null, reasons: [] },
    sealPass: null,
  }
  if (MODE === 'full') {
    result.evidence = evaluateFullEvidence(RUN_DIR, result)
    result.sealPass = behavior.pass && result.evidence.complete
  }
  return result
}

function reportText(result) {
  const modeTitle = result.mode === 'diag'
    ? '诊断（无业务写入）'
    : result.mode === 'shots'
      ? '截图路径验证（无业务写入）'
      : '扫码取水验收（真实写入模式）'
  const evidenceText = result.mode === 'full'
    ? `${result.evidence.complete ? 'COMPLETE' : 'INCOMPLETE'}；sealPass=${result.sealPass}`
    : 'N/A（诊断模式不参与封板证据判定）'
  const lines = [
    `# E1b ${modeTitle}`,
    '',
    '> 微信开发者工具 mp-weixin 运行态，非物理真机。diag/shots 不扫码、不下单。',
    '',
    `- 批次：${result.batchId}`,
    `- 模式：${result.mode}`,
    `- 订单号：${result.orderNo || '（未取得）'}`,
    `- 页面实际水量：${result.pageActualMl == null ? '（未取得）' : `${result.pageActualMl}ml`}`,
    `- 脚本 SHA-256：${result.scriptSha256}`,
    `- 证据核心 SHA-256：${result.coreSha256}`,
    `- 构建 manifest：${result.build.manifestSha256}（${result.build.manifestCount} 文件）`,
    `- 构建 fingerprint：disk=${result.build.diskFingerprint || '未取得'}；runtime=${result.build.runtimeFingerprint || '未取得'}`,
    `- 运行 API 模式：${result.build.runtimeModes || '未取得'}`,
    `- 行为：${result.behavior.passed}/${result.behavior.total} ${result.behavior.pass ? 'PASS' : 'FAIL'}`,
    `- 证据：${evidenceText}`,
    '',
    '| 步骤 | 结果 | 备注 |',
    '|---|---|---|',
    ...result.behavior.steps.map(item => `| ${cell(item.step)} | ${item.ok ? 'PASS' : 'FAIL'} | ${cell(item.extra)} |`),
    '',
    `- 截图证据：${result.shots.length ? result.shots.map(item => `${item.name}(${item.sha256.slice(0, 12)})`).join('、') : '无'}`,
    `- 警告：${result.warnings.length ? result.warnings.map(cell).join('；') : '无'}`,
  ]
  if (result.mode === 'full' && result.evidence.reasons.length) {
    lines.push('', '## 证据未闭合原因', '', ...result.evidence.reasons.map(reason => `- ${reason}`))
  }
  return `${lines.join('\n')}\n`
}

function writeResult(result, dir = RUN_DIR) {
  writeBatchFiles(dir, result, reportText(result))
  if (result.mode === 'full') {
    fs.writeFileSync(
      path.join(ACCEPT_DIR, 'latest.md'),
      `# E1b 最新批次指针\n\n- 批次：runs/${result.batchId}/\n- 行为：${result.behavior.passed}/${result.behavior.total} ${result.behavior.pass ? 'PASS' : 'FAIL'}\n- 证据：${result.evidence.complete ? 'COMPLETE' : 'INCOMPLETE'}\n- 封板：${result.sealPass === true ? '通过' : '不通过'}\n- 订单号：${result.orderNo || '（未取得）'}\n\n> 当前安全脚本要求 behaviorPass 与结构化证据同时成立；加固版 full 仍须用户明确授权。\n`,
    )
  }
}

function verifyEvidence(batchDir) {
  const dir = path.resolve(batchDir)
  const resultPath = path.join(dir, 'result.json')
  let result
  try {
    result = JSON.parse(fs.readFileSync(resultPath, 'utf8'))
  }
  catch {
    console.error(`找不到或无法解析 ${resultPath}`)
    process.exit(2)
  }
  const evidence = evaluateFullEvidence(dir, result)
  result.evidence = evidence
  result.sealPass = result.behavior?.pass === true && evidence.complete
  result.evidenceVerifiedAt = new Date().toISOString()
  const verification = {
    schemaVersion: 1,
    batchId: result.batchId,
    orderNo: result.orderNo,
    behaviorPass: result.behavior?.pass === true,
    evidence,
    sealPass: result.sealPass,
    verifiedAt: result.evidenceVerifiedAt,
  }
  fs.writeFileSync(path.join(dir, 'evidence-verification.json'), `${JSON.stringify(verification, null, 2)}\n`)
  writeResult(result, dir)
  console.log(`behaviorPass=${verification.behaviorPass} evidenceComplete=${evidence.complete} sealPass=${result.sealPass}`)
  process.exit(result.sealPass ? 0 : 1)
}

async function main() {
  fs.mkdirSync(RUN_DIR, { recursive: true })
  console.log(`MODE=${MODE} BATCH_DIR=${RUN_DIR}`)
  const watchdog = setTimeout(() => {
    fatalError = 'WATCHDOG：超过全局时限，强制收尾'
    const result = buildResult()
    writeResult(result)
    process.exit(1)
  }, GLOBAL_TIMEOUT_MS)

  try {
    if (MODE === 'shots') {
      await step(SHOTS_STEPS[0], async () => {
        await captureShotsSeparately()
        const complete = REQUIRED_SHOTS.every(name => shotEvidence.some(item => item.name === name))
        check(
          SHOTS_STEPS[0],
          complete,
          `有效截图 ${shotEvidence.length}/${REQUIRED_SHOTS.length}（${shotEvidence.map(item => item.name).join('、')}）`,
        )
      })
    }
    else {
      console.log('connecting', WS_ENDPOINT)
      const mp = await T(automatorClient().connect({ wsEndpoint: WS_ENDPOINT }), 'connect', 20000)
      try {
        if (MODE === 'diag') {
          await runDiag(mp)
        }
        else {
          await runFull(mp)
        }
      }
      finally {
        await T(mp.disconnect(), 'disconnect', 5000).catch(() => {})
      }
      if (MODE === 'full') {
        await captureShotsSeparately()
      }
    }
  }
  finally {
    clearTimeout(watchdog)
  }

  const result = buildResult()
  writeResult(result)
  console.log(`ORDER_NO=${orderNo}`)
  console.log(`SUMMARY: behavior ${result.behavior.passed}/${result.behavior.total} ${result.behavior.pass ? 'PASS' : 'FAIL'} evidence=${result.evidence.status}`)
  if (MODE === 'full') {
    // full 初次运行尚无同批 DB 结构化证据时返回 2；补证后只能由 --verify-evidence 返回最终 0。
    process.exit(result.sealPass ? 0 : result.behavior.pass ? 2 : 1)
  }
  process.exit(result.behavior.pass ? 0 : 1)
}

if (VERIFY_ONLY) {
  const dir = process.argv[VERIFY_INDEX + 1]
  if (!dir) {
    console.error('用法：node run-e1b.js --verify-evidence <批次目录>')
    process.exit(2)
  }
  verifyEvidence(dir)
}
else {
  main().catch((error) => {
    fatalError = error && error.message ? error.message : String(error)
    console.error('FATAL:', error)
    const result = buildResult()
    writeResult(result)
    console.log(`SUMMARY: ${result.behavior.passed}/${result.behavior.total} FAIL`)
    process.exit(1)
  })
}
