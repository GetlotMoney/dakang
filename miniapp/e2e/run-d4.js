/**
 * 六维达康小程序 D4 自动化走查（整合版，可复现，防挂死）。
 * 每个交互步骤带超时：任何一步卡住都会记 FAIL（带步骤名）并继续，绝不静默挂起；
 * 全局看门狗兜底强制退出。输出：逐项 PASS/FAIL + 截图 + report.md 自动段落。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const automator = require('miniprogram-automator')
const { inspectPng } = require('./e1b-core')
const { cell } = require('./report-core')
const { checkRuntimeModes, formatRuntimeModes } = require('./runtime-modes')
const { EXPECTED_BEHAVIOR_COUNT, countBusinessAssertions, evaluateBehaviorPass } = require('./d4-gate')

const D4_MODE = String(process.env.D4_MODE || '').trim()
if (D4_MODE !== 'behavior' && D4_MODE !== 'full') {
  console.error('D4 安全闸拒绝执行：必须显式设置 D4_MODE=behavior 或 full')
  process.exit(2)
}

const WS_ENDPOINT = process.env.WX_AUTO_WS || 'ws://127.0.0.1:9420'
const ACCEPT_DIR = path.resolve(__dirname, '../../docs/acceptance/miniapp-d4')
const SHOTS_DIR = path.join(ACCEPT_DIR, 'shots')
const STEP_TIMEOUT_MS = 15000
const GLOBAL_TIMEOUT_MS = 6 * 60 * 1000
// D4 走查只允许全域 Mock（含 auth——auth 接真即进入正式登录入口，不再提供原型账号面板）。
const EXPECTED_MOCK_MODE_MAP = {
  GLOBAL: 'mock',
  DEVICE: 'mock',
  ORDER: 'mock',
  CARD: 'mock',
  RECHARGE: 'mock',
  AUTH: 'mock',
  DELIVERY: 'mock',
}
const EXPECTED_MOCK_MODES = formatRuntimeModes(EXPECTED_MOCK_MODE_MAP)
const EXPECTED_SHOT_COUNT = 12

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const results = []
const shotResults = []
let fatalError = ''

function check(step, ok, extra = '', viaStepError = false) {
  // viaStepError=true 表示该步整体抛错后的补记项，不计入业务断言完备性（见 d4-gate.js 口径）。
  results.push({ step, ok, extra, viaStepError })
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${step}${extra ? ` | ${extra}` : ''}`)
}

/** 任何 automator 调用都必须包超时：IDE 弹窗/桥失联时快速失败并暴露步骤名。 */
function T(promise, label, ms = STEP_TIMEOUT_MS) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`TIMEOUT:${label}`)), ms)),
  ])
}

/** 单步执行器：失败/超时记 FAIL 后继续走后续步骤。 */
async function step(label, fn) {
  try {
    await fn()
  }
  catch (error) {
    check(label, false, error && error.message ? error.message : String(error), true)
  }
}

async function main() {
  const watchdog = setTimeout(() => {
    fatalError = 'WATCHDOG: 超过全局时限，强制收尾'
    console.error(fatalError)
    writeReport()
    process.exit(1)
  }, GLOBAL_TIMEOUT_MS)

  console.log('connecting', WS_ENDPOINT)
  let mp = await T(automator.connect({ wsEndpoint: WS_ENDPOINT }), 'connect', 20000)
  console.log('connected')

  const reconnect = async (label) => {
    let lastError
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        return await T(automator.connect({ wsEndpoint: WS_ENDPOINT }), `${label}:${attempt}`, 20000)
      }
      catch (error) {
        lastError = error
        await sleep(500)
      }
    }
    throw lastError || new Error(`automator 重连失败：${label}`)
  }
  const renewConnection = async (label) => {
    await T(mp.disconnect(), `renew-disconnect:${label}`, 5000).catch(() => null)
    await sleep(300)
    mp = await reconnect(`renew-connect:${label}`)
  }
  const currentPage = () => T(mp.currentPage(), 'currentPage')
  const pageText = async (page) => {
    const root = await T(page.$('.page-shell'), 'query .page-shell')
      || await T(page.$('.entry-page'), 'query .entry-page')
    return root ? (await T(root.text(), 'read page text')) || '' : ''
  }

  /** 每次截图后重建唯一 automator 连接；避免并行连接互扰，也避免截图超时污染后续业务断言。 */
  const shot = async (name, target) => {
    if (D4_MODE === 'behavior') {
      return true
    }
    const file = path.join(SHOTS_DIR, `${name}.png`)
    let lastError
    let captured = false
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        fs.rmSync(file, { force: true })
        const targetPage = await currentPage()
        const targetText = await pageText(targetPage)
        const targetOk = targetPage.path === target.path
          && (target.includes || []).every(text => targetText.includes(text))
        if (!targetOk) {
          throw new Error(`截图目标核验失败：期望 ${target.path} / ${(target.includes || []).join('、')}`)
        }
        await T(mp.screenshot({ path: file }), `shot:${name}`, 15000)
        const inspected = inspectPng(file)
        if (!inspected.valid) {
          throw new Error(`截图文件不是有效 PNG：${name}`)
        }
        shotResults.push({
          name,
          ok: true,
          extra: `${inspected.width}×${inspected.height} sha256=${inspected.sha256}`,
          targetPath: targetPage.path,
          targetVerified: true,
          size: inspected.size,
          width: inspected.width,
          height: inspected.height,
          sha256: inspected.sha256,
        })
        captured = true
      }
      catch (error) {
        lastError = error
      }
      finally {
        await T(mp.disconnect(), `shot-disconnect:${name}`, 5000).catch(() => null)
        await sleep(500)
        mp = await reconnect(`shot-reconnect:${name}`)
      }
      if (captured) {
        break
      }
    }
    const extra = captured ? '有效 PNG' : (lastError?.message || `截图失败：${name}`)
    if (!captured) {
      shotResults.push({ name, ok: false, extra, targetVerified: false })
    }
    console.log(`${captured ? 'PASS' : 'FAIL'} | 截图 ${name} | ${extra}`)
    return captured
  }
  const tapByClassAndText = async (page, cls, text) => {
    const nodes = await T(page.$$(cls), `query ${cls}`)
    for (const node of nodes) {
      if ((((await T(node.text(), `text ${cls}`)) || '')).includes(text)) {
        await T(node.tap(), `tap ${cls}:${text}`)
        return true
      }
    }
    return false
  }
  const pills = async (page) => {
    const out = []
    for (const el of await T(page.$$('.face-pill'), 'query .face-pill')) {
      out.push((((await T(el.text(), 'pill text')) || '')).trim())
    }
    return out
  }
  /** 轮询点击：等待目标元素出现再点，消除固定 sleep 的时序抖动。 */
  const waitTap = async (cls, text, ms = 8000) => {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      const page = await currentPage()
      if (await tapByClassAndText(page, cls, text)) {
        return true
      }
      await sleep(500)
    }
    return false
  }
  /** 轮询等待页面文本出现。 */
  const waitText = async (needle, ms = 8000) => {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      if ((await pageText(await currentPage())).includes(needle)) {
        return true
      }
      await sleep(500)
    }
    return false
  }
  const waitPath = async (pathFragment, ms = 8000) => {
    const deadline = Date.now() + ms
    let page
    while (Date.now() < deadline) {
      page = await currentPage()
      if (page.path.includes(pathFragment)) {
        return page
      }
      await sleep(500)
    }
    return page
  }
  const gotoEntryAndPick = async (name) => {
    await T(mp.reLaunch('/pages/entry/index'), 'reLaunch entry')
    await sleep(2000)
    const page = await currentPage()
    await tapByClassAndText(page, '.wd-cell', name)
    await sleep(2800)
    return currentPage()
  }

  try {
    await T(mp.reLaunch('/pages/entry/index'), 'preflight reLaunch entry', 30000)
    await sleep(1800)
    const preflightPage = await currentPage()
    const runtimeMarker = await T(preflightPage.$('.e2e-runtime-contract'), 'query runtime contract')
    const runtimeContract = runtimeMarker
      ? ((await T(runtimeMarker.text(), 'read runtime contract')) || '').trim()
      : ''
    const diskFingerprint = (() => {
      try {
        return JSON.parse(
          fs.readFileSync(path.resolve(__dirname, '../dist/build/mp-weixin/build-fingerprint.json'), 'utf8'),
        ).fingerprint
      }
      catch {
        return ''
      }
    })()
    const runtimeFingerprint = /^BUILD=([^;]+);/.exec(runtimeContract)?.[1] || ''
    const runtimeModes = runtimeContract.replace(/^BUILD=[^;]+;/, '')
    const modeReasons = checkRuntimeModes(runtimeModes, EXPECTED_MOCK_MODE_MAP)
    const safeBuild = !!diskFingerprint
      && runtimeFingerprint === diskFingerprint
      && modeReasons.length === 0
    check(
      'D4 运行包为当前全域 Mock 构建',
      safeBuild,
      `fingerprint=${runtimeFingerprint === diskFingerprint && !!diskFingerprint ? '一致' : '不一致'} modes=${runtimeModes || '未取得'}`
      + `${modeReasons.length ? ` 期望=${EXPECTED_MOCK_MODES} 不符：${modeReasons.join('；')}` : ''}`,
    )
    if (!safeBuild) {
      throw new Error('D4_SAFE_BUILD_REQUIRED：禁止在混合/Real 或旧缓存构建上执行 D4 业务步骤')
    }
    if (D4_MODE === 'full') {
      fs.rmSync(SHOTS_DIR, { recursive: true, force: true })
      fs.mkdirSync(SHOTS_DIR, { recursive: true })
    }

    await step('清理本地偏好并重置原型数据（幂等前提）', async () => {
      await T(mp.callWxMethod('clearStorageSync'), 'clearStorageSync')
      await T(mp.reLaunch('/pages/entry/index'), 'reLaunch entry')
      await sleep(2000)
      const resetTapped = await waitTap('.wd-button', '重置原型数据')
      check('原型数据已重置', resetTapped)
      await sleep(1200)
    })

    await step('C01 启动页', async () => {
      await renewConnection('entry')
      await T(mp.reLaunch('/pages/entry/index'), 'reLaunch entry')
      await sleep(2200)
      const page = await currentPage()
      check('C01 启动页路径', page.path === 'pages/entry/index', page.path)
      await shot('01-entry', { path: 'pages/entry/index', includes: ['六维达康智慧水站'] })
    })

    let page
    await step('张女士生活态', async () => {
      await renewConnection('consumer-home')
      page = await gotoEntryAndPick('张女士')
      const text = await pageText(page)
      check('张女士生活态（扫码取水+成为配送员引导）', text.includes('扫码取水') && text.includes('成为配送员'))
      check('张女士无视角胶囊', (await pills(page)).length === 0)
      check('无英文契约代号', !text.includes('COURIER_WORK') && !text.includes('OWNER_VIEW'))
      await shot('02-home-life', { path: 'pages/user/home/index', includes: ['扫码取水'] })
    })

    await step('底部 Tab 真实点击进我的', async () => {
      await renewConnection('profile')
      page = await currentPage()
      const tabTapped = await tapByClassAndText(page, '.wd-tabbar-item', '我的')
      await sleep(2200)
      page = await currentPage()
      check('真实点击底部 Tab 进入我的', tabTapped && page.path === 'pages/user/profile/index', page.path)
      const text = await pageText(page)
      check('我的页含退出登录与能力摘要', text.includes('退出登录') && text.includes('配送员'))
      await shot('03-profile', { path: 'pages/user/profile/index', includes: ['退出登录'] })
    })

    await step('扫码取水链（新价格口径）', async () => {
      await renewConnection('water-flow')
      await T(mp.switchTab('/pages/user/home/index'), 'switchTab home')
      await sleep(2000)
      page = await currentPage()
      await T(mp.mockWxMethod('showActionSheet', { tapIndex: 0, errMsg: 'showActionSheet:ok' }), 'mock actionsheet')
      await tapByClassAndText(page, '.wd-button', '扫码取水')
      await sleep(2400)
      await T(mp.restoreWxMethod('showActionSheet'), 'restore actionsheet')
      page = await currentPage()
      const text = await pageText(page)
      check('U04 取水确认（单价 ¥0.20/升）', page.path === 'pages/user/water/confirm' && text.includes('0.20'), page.path)
      check('U04 预计金额 ¥2.00（10L）', text.includes('2.00'))
      await shot('04-water-confirm', { path: 'pages/user/water/confirm', includes: ['取水确认', '0.20'] })
      page = await currentPage()
      const started = await tapByClassAndText(page, 'button', '确认并开始取水')
      if (!started) {
        throw new Error('未找到「确认并开始取水」按钮')
      }
      page = await waitPath('pages/user/water/progress', 10000)
      check('U05 取水进度路径', page.path === 'pages/user/water/progress', page.path)
      const done = await waitText('出水完成', 10000)
      const doneText = await pageText(await currentPage())
      check('U05 播放到终态（出水完成）', done && doneText.includes('已完成'))
      await shot('05-water-progress-done', { path: 'pages/user/water/progress', includes: ['已完成'] })
    })

    await step('订单列表共键', async () => {
      await renewConnection('orders')
      await T(mp.switchTab('/pages/user/order/index'), 'switchTab order')
      await sleep(2200)
      page = await currentPage()
      const text = await pageText(page)
      check('U02 含充值退款共键 091005', text.includes('WO20260712091005'))
      check('U02 取水单金额 ¥2.00', text.includes('2.00'))
      await shot('06-orders', { path: 'pages/user/order/index', includes: ['我的订单', 'WO20260712091005'] })
    })

    await step('李配送配送态', async () => {
      await renewConnection('courier-home')
      page = await gotoEntryAndPick('李配送')
      const text = await pageText(page)
      check('李配送默认配送态', text.includes('工作概览') && text.includes('进入任务中心'))
      check('李配送任务条直达（DT-2007 去送达）', text.includes('DT-2007') && text.includes('去送达'))
      check('李配送胶囊仅 配送/生活', (await pills(page)).join(',') === '配送,生活', (await pills(page)).join(','))
      await shot('07-home-courier', { path: 'pages/user/home/index', includes: ['进入任务中心'] })
    })

    await step('P0 闭环：接单并同步订单轨迹', async () => {
      await renewConnection('delivery-loop')
      // 直达 D03（带参路由），避开列表宽选择器
      await T(mp.navigateTo('/pages/courier/task/detail?taskNo=DT-2008'), 'navigateTo D03')
      await sleep(2400)
      page = await currentPage()
      check('D03 任务详情路径', page.path === 'pages/courier/task/detail', page.path)
      // D03 的确认框是页内 wd-message-box（非微信原生 showModal），需真实点击"确定"；全程轮询防时序抖动。
      const accepted = await waitTap('.wd-button', '接单')
      const confirmed = accepted && (await waitTap('.wd-button', '确定'))
      const stateOk = confirmed && (await waitText('已接单'))
      check('D03 接单成功（状态已接单）', stateOk, `accepted=${accepted} confirmed=${confirmed}`)
      await sleep(1500) // 等确认弹层完全关闭，保证截图干净（审计意见）
      await shot('08-task-accepted', { path: 'pages/courier/task/detail', includes: ['已接单'] })

      page = await gotoEntryAndPick('张女士')
      await T(mp.navigateTo('/pages/user/order/detail?orderNo=WO20260712091008&focus=delivery'), 'navigateTo U06')
      await sleep(2400)
      page = await currentPage()
      const orderText = await pageText(page)
      check(
        'P0 闭环：用户端轨迹含"配送员已接单"且订单仍为已支付',
        page.path === 'pages/user/order/detail' && orderText.includes('配送员已接单') && orderText.includes('已支付'),
        page.path,
      )
      await shot('09-order-trace-synced', { path: 'pages/user/order/detail', includes: ['WO20260712091008', '配送员已接单'] })
    })

    await step('越权拦截', async () => {
      await renewConnection('unauthorized')
      await gotoEntryAndPick('李配送')
      await T(mp.navigateTo('/pages/owner/overview/index'), 'navigateTo O01')
      await sleep(2200)
      page = await currentPage()
      const text = await pageText(page)
      check('李配送直闯 O01 被数据层拒绝', text.includes('未开通') || text.includes('无权') || text.includes('范围为空'), text.slice(0, 30))
      check('越权文案不泄露内部契约代号', !text.includes('OWNER_VIEW') && !text.includes('COURIER_WORK'))
      await shot('10-owner-denied', { path: 'pages/owner/overview/index' })
    })

    await step('赵先生经营态', async () => {
      await renewConnection('owner-home')
      page = await gotoEntryAndPick('赵先生')
      const text = await pageText(page)
      check('赵先生默认经营态（含 DK-DEV-0002 待关注）', text.includes('经营概览') && text.includes('DK-DEV-0002'))
      check('赵先生胶囊仅 经营/生活', (await pills(page)).join(',') === '经营,生活', (await pills(page)).join(','))
      await shot('11-home-owner', { path: 'pages/user/home/index', includes: ['经营概览'] })
    })

    await step('能力停用回退与恢复', async () => {
      await renewConnection('capability-revoke')
      await gotoEntryAndPick('李配送')
      await T(mp.reLaunch('/pages/entry/index'), 'reLaunch entry')
      await sleep(2000)
      page = await currentPage()
      await tapByClassAndText(page, '.wd-button', '模拟停用配送能力')
      await sleep(1500)
      await tapByClassAndText(page, '.wd-button', '进入首页')
      await sleep(2400)
      page = await currentPage()
      const text = await pageText(page)
      check('停用配送后首页回退（无配送态）', !text.includes('进入任务中心'))
      await shot('12-courier-revoked', { path: 'pages/user/home/index', includes: ['扫码取水'] })
      await T(mp.reLaunch('/pages/entry/index'), 'reLaunch entry')
      await sleep(2000)
      page = await currentPage()
      await tapByClassAndText(page, '.wd-button', '模拟恢复配送能力')
      await sleep(1200)
    })

    await step('退出登录', async () => {
      await renewConnection('logout')
      page = await currentPage()
      await tapByClassAndText(page, '.wd-button', '进入首页')
      await sleep(2200)
      page = await currentPage()
      await tapByClassAndText(page, '.wd-tabbar-item', '我的')
      await sleep(2000)
      page = await currentPage()
      await T(mp.mockWxMethod('showModal', { confirm: true, cancel: false, errMsg: 'showModal:ok' }), 'mock modal')
      await tapByClassAndText(page, '.wd-cell', '退出登录')
      await sleep(2200)
      await T(mp.restoreWxMethod('showModal'), 'restore modal')
      page = await currentPage()
      check('退出登录回统一入口', page.path === 'pages/entry/index', page.path)
      // C01 已留入口截图；退出后的同路径由本断言证明，避免重复截图消耗不稳定的 IDE 截图服务配额。
    })
  }
  finally {
    clearTimeout(watchdog)
    const pass = results.filter(item => item.ok).length
    const shotPass = shotResults.filter(item => item.ok).length
    const behaviorPass = evaluateBehaviorPass(results, fatalError)
    const evidenceComplete = D4_MODE === 'full'
      && shotResults.length === EXPECTED_SHOT_COUNT
      && shotResults.every((item) => {
        const inspected = inspectPng(path.join(SHOTS_DIR, `${item.name}.png`))
        return item.ok && item.targetVerified && inspected.valid
          && inspected.size === item.size && inspected.sha256 === item.sha256
      })
    console.log(`\nSUMMARY mode=${D4_MODE} behavior=${pass}/${countBusinessAssertions(results)}(期望${EXPECTED_BEHAVIOR_COUNT}) screenshots=${D4_MODE === 'full' ? `${shotPass}/${EXPECTED_SHOT_COUNT}` : 'N/A'}`)
    writeReport()
    await Promise.race([mp.disconnect(), sleep(3000)]).catch(() => null)
    if (D4_MODE === 'behavior') {
      process.exit(behaviorPass ? 0 : 1)
    }
    process.exit(behaviorPass && evidenceComplete ? 0 : behaviorPass ? 2 : 1)
  }
}

function writeReport() {
  const pass = results.filter(item => item.ok).length
  const shotPass = shotResults.filter(item => item.ok).length
  const behaviorPass = evaluateBehaviorPass(results, fatalError)
  const evidenceComplete = D4_MODE === 'full'
    && shotResults.length === EXPECTED_SHOT_COUNT
    && shotResults.every((item) => {
      const inspected = inspectPng(path.join(SHOTS_DIR, `${item.name}.png`))
      return item.ok && item.targetVerified && inspected.valid
        && inspected.size === item.size && inspected.sha256 === item.sha256
    })
  // Markdown 表格单元不允许换行（转义收敛在 report-core.cell，三个跑批共用）。
  const lines = results.map(item => `| ${item.ok ? '✅' : '❌'} | ${cell(item.step)} | ${cell(item.extra)} |`)
  const shotLines = shotResults.map(item => `| ${item.ok ? '✅' : '❌'} | ${cell(item.name)} | ${cell(item.extra)} |`)
  const block = [
    '<!-- AUTO-GENERATED: run-d4.js 每次运行覆盖本段之后内容 -->',
    '## 最近一次自动化走查结果',
    '',
    `- 模式：${D4_MODE}`,
    `- 业务断言：通过 ${pass}/${EXPECTED_BEHAVIOR_COUNT}，整体 ${behaviorPass ? 'PASS' : 'FAIL'}${fatalError ? `（fatal=${cell(fatalError)}）` : ''}`,
    `- 截图证据：${D4_MODE === 'full' ? `${shotPass}/${EXPECTED_SHOT_COUNT} ${evidenceComplete ? 'COMPLETE' : 'INCOMPLETE'}` : 'N/A（behavior 模式不截图）'}`,
    `- D4 封板判定：${D4_MODE === 'full' ? (behaviorPass && evidenceComplete ? 'PASS' : 'FAIL') : 'N/A（仅验证业务行为）'}`,
    '',
    '### 业务断言',
    '',
    '| 结果 | 走查项 | 备注 |',
    '|---|---|---|',
    ...lines,
    '',
    ...(D4_MODE === 'full'
      ? [
          '### 截图证据',
          '',
          '| 结果 | 文件 | 备注 |',
          '|---|---|---|',
          ...shotLines,
          '',
          `截图目录仅保留当前批次安全预检通过后生成的文件；要求 ${EXPECTED_SHOT_COUNT} 张全部有效。`,
        ]
      : []),
    '',
    '运行方式见 `miniapp/e2e/README.md`。',
    '',
  ].join('\n')
  const reportPath = path.join(ACCEPT_DIR, D4_MODE === 'full' ? 'report.md' : 'behavior-report.md')
  let head = [
    `# 小程序 D4 ${D4_MODE === 'full' ? '完整取证' : '业务行为'}走查报告`,
    '',
    '- 驱动方式：微信开发者工具 `cli auto` + `miniprogram-automator`（touristappid 游客模式，Mock 适配器）。',
    '- 人工补查范围：三照选图（chooseImage）、微信胶囊真机观感、S05/S08 表单细节。',
    '',
  ].join('\n')
  if (fs.existsSync(reportPath)) {
    const existing = fs.readFileSync(reportPath, 'utf8')
    const marker = existing.indexOf('<!-- AUTO-GENERATED')
    if (marker >= 0) {
      head = existing.slice(0, marker)
    }
  }
  fs.writeFileSync(reportPath, head + block)
}

process.on('unhandledRejection', (reason) => {
  fatalError = `UNHANDLED: ${reason && reason.message ? reason.message : reason}`
  console.error('UNHANDLED:', reason && reason.message ? reason.message : reason)
})

main().catch((error) => {
  fatalError = error && error.message ? error.message : String(error)
  console.error('AUTOMATION_ERROR:', error && error.message ? error.message : error)
  writeReport()
  process.exit(1)
})
