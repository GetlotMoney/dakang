/**
 * D-430 身份血缘无界面验收：区域→渠道→机主→演示支付/设备→D-428 分润来源。
 * 仅允许写 13340/3309 隔离环境；不连接微信开发者工具。
 */
const process = require('node:process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const mysql = require('mysql2/promise')

if (String(process.env.IDENTITY_ACC_MODE || '') !== 'full') {
  console.error('安全闸拒绝执行：必须显式设置 IDENTITY_ACC_MODE=full')
  process.exit(2)
}
if (!process.env.ACC_DB_PASSWORD) {
  console.error('安全闸拒绝执行：缺少 ACC_DB_PASSWORD')
  process.exit(2)
}
const BASE = (process.env.ACC_BASE || 'http://127.0.0.1:13340/dakangApi').replace(/\/$/, '')
const DB_PORT = Number(process.env.ACC_DB_PORT || 3309)
if (/:(?:13330|8081)(?:\/|$)/.test(BASE) || [3306, 3308].includes(DB_PORT)) {
  console.error(`安全闸拒绝执行：目标不是隔离环境 base=${BASE} dbPort=${DB_PORT}`)
  process.exit(2)
}

const db = mysql.createPool({
  host: process.env.ACC_DB_HOST || '127.0.0.1',
  port: DB_PORT,
  user: process.env.ACC_DB_USER || 'root',
  password: process.env.ACC_DB_PASSWORD,
  database: process.env.ACC_DB_NAME || 'dakang',
  decimalNumbers: true,
})
const phones = { region: '13999990001', channel: '13999990002', owner: '13999990003' }
const users = { region: 9001, channel: 9002, owner: 9003 }
const results = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
function ok(condition, message) {
  if (!condition)
    throw new Error(message)
}
const eq = (actual, expected, message) => ok(String(actual) === String(expected), `${message}（期望 ${expected}，实际 ${actual}）`)
const one = async (sql, params = []) => (await db.query(sql, params))[0][0] || null
const rows = async (sql, params = []) => (await db.query(sql, params))[0]

async function request(url, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token)
    headers[token.tokenName] = token.tokenValue
  const response = await fetch(`${BASE}${url}`, { method: 'POST', headers, body: JSON.stringify(body || {}) })
  return response.json()
}
async function apiOk(url, body, token) {
  const result = await request(url, body, token)
  ok(result.code === 0, `${url} 失败：${result.code} ${result.msg}`)
  return result.data
}
async function apiFail(url, body, token, expectedText) {
  const result = await request(url, body, token)
  ok(result.code !== 0, `${url} 应失败但返回成功`)
  if (expectedText)
    ok(String(result.msg).includes(expectedText), `${url} 失败文案不含“${expectedText}”：${result.msg}`)
  return result
}
async function login(phone) {
  const data = await apiOk('/mini/test-login/by-phone', { phone })
  ok(data.tokenName && data.tokenValue, `手机号 ${phone} 测试登录未返回会话`)
  return data
}
async function apply(token, input) {
  return apiOk('/mini/identity/apply', {
    subjectType: 1,
    applicantName: input.name,
    regionName: '验收专区',
    capabilityType: input.type,
    agentLevel: input.level,
    inviteCode: input.invite,
    stationName: input.stationName,
    stationAddress: input.stationAddress,
    requestId: crypto.randomUUID(),
    declarationAccepted: true,
  }, token)
}
async function poll(label, fn, timeout = 30000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    const value = await fn()
    if (value)
      return value
    await sleep(1000)
  }
  throw new Error(`${label} 超时`)
}
function pass(name, evidence) {
  results.push({ name, status: 'PASS', evidence })
  console.log(`PASS | ${name} | ${evidence}`)
}

async function main() {
  let simulator
  try {
    const token = {
      region: await login(phones.region),
      channel: await login(phones.channel),
      owner: await login(phones.owner),
    }
    const opsLogin = await apiOk('/api/auth/loginEmployee', {
      loginName: 'acc-ops',
      loginPwd: process.env.ACC_OPS_PWD_CIPHER,
    })
    const ops = { tokenName: 'dakang-token', tokenValue: opsLogin.tokenValue }
    pass('S1 三账号从普通用户会话进入', '9001/9002/9003 均由隔离 test-login 签发')

    const regionOverview = await apply(token.region, {
      type: 4,
      level: 2,
      invite: 'DK-DEMO-REGION-C',
      name: '验收市级运营中心',
    })
    eq(regionOverview.roles.find(r => r.capabilityType === 4)?.status, 2, '区域代理应自动审核通过')
    const regionDash = await apiOk('/mini/identity/region/overview', {}, token.region)
    const channelInvite = regionDash.inviteCodes.find(i => i.targetCapabilityType === 3)?.code
    ok(channelInvite, '区域工作台应生成渠道邀请码')
    pass('S2 区域代理申请与邀请码', `区域通过，渠道码=${channelInvite}`)

    const channelOverview = await apply(token.channel, {
      type: 3,
      invite: channelInvite,
      name: '验收渠道推广',
    })
    eq(channelOverview.roles.find(r => r.capabilityType === 3)?.status, 2, '渠道应自动审核通过')
    const channelDash = await apiOk('/mini/identity/channel/overview', {}, token.channel)
    const ownerInvite = channelDash.inviteCodes.find(i => i.targetCapabilityType === 2)?.code
    ok(ownerInvite, '渠道工作台应生成机主邀请码')
    pass('S3 渠道申请并继承区域血缘', `渠道通过，机主码=${ownerInvite}`)

    const ownerOverview = await apply(token.owner, {
      type: 2,
      invite: ownerInvite,
      name: '验收机主',
      stationName: '验收新水站',
      stationAddress: '验收路3号',
    })
    eq(ownerOverview.roles.find(r => r.capabilityType === 2)?.status, 2, '机主应自动审核通过')
    const relation = await one('SELECT REFERRER_USER_ID FROM ws_owner_referrer WHERE OWNER_USER_ID=?', [users.owner])
    const attribution = await one('SELECT CITY_AGENT_USER_ID,ATTRIBUTION_SOURCE FROM ws_owner_attribution WHERE OWNER_USER_ID=?', [users.owner])
    eq(relation.REFERRER_USER_ID, users.channel, '机主直接推荐人')
    eq(attribution.CITY_AGENT_USER_ID, users.region, '机主市级区域归属')
    eq(attribution.ATTRIBUTION_SOURCE, 'PRIVATE_REFERRAL', '机主归属来源')
    pass('S4 机主申请建立完整血缘', '市级9001→渠道9002→机主9003')

    const channelAfter = await apiOk('/mini/identity/channel/overview', {}, token.channel)
    const regionAfter = await apiOk('/mini/identity/region/overview', {}, token.region)
    eq(channelAfter.directOwnerCount, 1, '渠道直属机主数')
    eq(regionAfter.directOwnerCount, 1, '区域血缘机主数')
    eq(channelAfter.stationCount, 1, '渠道关联水站数')
    eq(regionAfter.stationCount, 1, '区域关联水站数')
    pass('S5 渠道与区域工作台联动', '两端均看到1机主/1水站，数据由血缘过滤')

    await apiOk('/mini/identity/demo/control/update', {
      nextPayResult: 'CANCEL',
      nextDeviceResult: 'NORMAL',
      deliveryAuto: 2,
    }, token.owner)
    const recharge = await apiOk('/mini/order/recharge/create', { packageId: '9501', requestId: crypto.randomUUID() }, token.owner)
    await apiFail('/mini/pay-sim/pay', { orderNo: recharge.orderNo }, token.owner, '取消支付')
    const resetPay = await apiOk('/mini/identity/demo/control', {}, token.owner)
    eq(resetPay.nextPayResult, 'SUCCESS', '取消结果应一次消费后复位')
    await apiOk('/mini/pay-sim/pay', { orderNo: recharge.orderNo }, token.owner)
    const card = await poll('机主购卡入账', () => one('SELECT ID FROM ws_card WHERE USER_ID=? AND CARD_STATUS=1 ORDER BY ID DESC LIMIT 1', [users.owner]))
    const topup = await apiOk('/mini/order/recharge/create', { packageId: '9502', cardId: String(card.ID), requestId: crypto.randomUUID() }, token.owner)
    await apiOk('/mini/pay-sim/pay', { orderNo: topup.orderNo }, token.owner)
    pass('S6 演示支付一次性结果', `取消不入账→复位成功→购卡/充值到账 card=${card.ID}`)

    const station = await one('SELECT ID FROM ws_station WHERE OWNER_USER_ID=? AND STATION_CODE=?', [users.owner, `DEMO-ST-${users.owner}`])
    const cardScope = await one('SELECT SCOPE_JSON FROM ws_card WHERE ID=?', [card.ID])
    await apiOk('/user/card/updateScope', {
      cardId: String(card.ID),
      scopeType: 'specified',
      stationIds: [String(station.ID)],
      reason: 'D-430 隔离验收：授权新申请机主水站',
      expectedScopeJson: cardScope.SCOPE_JSON,
    }, ops)

    await apiOk('/mini/identity/demo/control/update', {
      nextPayResult: 'SUCCESS',
      nextDeviceResult: 'SHORT',
      deliveryAuto: 1,
    }, token.owner)
    simulator = spawn('node', [path.resolve(__dirname, '../../tools/device-sim/sim.js'), 'DEMO-DEV-9003'], {
      env: { ...process.env, BROKER: process.env.ACC_BROKER || 'tcp://127.0.0.1:1884' },
      stdio: 'ignore',
    })
    await sleep(2500)
    const scan = await apiOk('/mini/device/scan/resolve', { rawCode: 'DK-DEMO-OWNER-9003-O1' }, token.owner)
    const context = await apiOk('/mini/device/water/context', { scanSessionId: scan.scanSessionId }, token.owner)
    await apiOk('/mini/device/water/eligibility', { scanSessionId: scan.scanSessionId, cardId: Number(card.ID) }, token.owner)
    const water = await apiOk('/mini/order/water/create', {
      scanSessionId: scan.scanSessionId,
      cardId: Number(card.ID),
      waterTypeId: context.outlet.waterTypeId,
      planMl: 5000,
      payWay: 2,
    }, token.owner)
    const finished = await poll('少出水订单完成', () => one('SELECT ID,ORDER_NO,ACTUAL_ML,ORDER_AMOUNT,ORDER_STATUS,CMD_ID FROM ws_order WHERE ORDER_NO=? AND ORDER_STATUS=4', [water.order.orderNo]), 40000)
    eq(finished.ACTUAL_ML, 4000, 'SHORT 应回传计划量80%')
    const command = await one('SELECT CMD_PAYLOAD,CMD_STATUS FROM ws_command WHERE ID=?', [finished.CMD_ID])
    ok(String(command.CMD_PAYLOAD).includes('"demoScenario":"SHORT"'), '指令快照应保留SHORT证据')
    eq(command.CMD_STATUS, 4, '少出水仍应形成成功终态并退差')
    const resetDevice = await apiOk('/mini/identity/demo/control', {}, token.owner)
    eq(resetDevice.nextDeviceResult, 'NORMAL', '设备结果应一次消费后复位')
    pass('S7 新水站授权与演示设备少出水', `后台正式范围接口授权站${station.ID}；计划5000ml→实际4000ml`)

    const splits = await poll('D-428 分润行生成', async () => {
      const list = await rows('SELECT RECEIVER_TYPE,RECEIVER_USER_ID,SPLIT_AMOUNT,SPLIT_RATE_SNAP FROM ws_split_record WHERE ORDER_ID=? ORDER BY RECEIVER_TYPE,RECEIVER_USER_ID', [finished.ID])
      return list.length >= 4 ? list : null
    })
    const by = key => splits.find(r => `${r.RECEIVER_TYPE}:${r.RECEIVER_USER_ID}` === key)
    ok(by(`1:${users.owner}`), '机主50%分润缺失')
    ok(by(`5:${users.channel}`), '渠道推广5%分润缺失')
    ok(by(`6:${users.region}`), '市级区域3%分润缺失')
    ok(by('3:0'), '平台余数行缺失')
    const splitTotal = splits.reduce((sum, r) => sum + Number(r.SPLIT_AMOUNT), 0)
    eq(splitTotal, 80, '少出水退差后分润基数应为实扣80分')
    ok(splitTotal < Number(finished.ORDER_AMOUNT), '分润按实扣金额，不按计划订单毛额')
    const channelWallet = await apiOk('/mini/identity/channel/overview', {}, token.channel)
    const regionWallet = await apiOk('/mini/identity/region/overview', {}, token.region)
    ok(channelWallet.wallet.roleSummaries.some(r => r.receiverType === 5), '渠道钱包缺少推广来源分类')
    ok(regionWallet.wallet.roleSummaries.some(r => r.receiverType === 6), '区域钱包缺少区域来源分类')
    pass('S8 六方分润与身份收益分类', `订单${finished.ORDER_NO}四类收款行恒等，渠道/区域钱包来源可辨`)

    const outDir = path.resolve(__dirname, '../../docs/acceptance/identity-workspaces/runs')
    fs.mkdirSync(outDir, { recursive: true })
    const output = path.join(outDir, `${new Date().toISOString().replace(/[:.]/g, '')}-result.json`)
    fs.writeFileSync(output, JSON.stringify({ status: 'PASS', scenarios: results, orderNo: finished.ORDER_NO }, null, 2))
    console.log(`SUMMARY scenarios=${results.length}/${results.length} PASS`)
    console.log(`RESULT=${output}`)
  }
  catch (error) {
    console.error(`FAIL | ${error.stack || error.message || error}`)
    process.exitCode = 1
  }
  finally {
    if (simulator && !simulator.killed)
      simulator.kill()
    await db.end()
  }
}

main()
