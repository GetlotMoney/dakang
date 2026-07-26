const assert = require('node:assert/strict')
const { test } = require('node:test')
const { DOMAIN_ORDER, formatRuntimeModes, parseRuntimeModes, checkRuntimeModes } = require('./runtime-modes')
const { EXPECTED_REAL_MODES, EXPECTED_REAL_MODE_MAP } = require('./e1b-core')
const { EXPECTED_BEHAVIOR_COUNT, countBusinessAssertions, evaluateBehaviorPass } = require('./d4-gate')

/**
 * 取证链安全闸的静态可运行性证明（链路一 S0-R1 / A-3）。
 * 不跑 D4/E1b full：只证明「正确构建放行、错误构建拦截」与 behaviorPass 计数口径正确。
 */

const ALL_MOCK_MAP = Object.fromEntries(DOMAIN_ORDER.map(d => [d, 'mock']))

test('段序单一来源：7 段且顺序固定', () => {
  assert.deepEqual(DOMAIN_ORDER, ['GLOBAL', 'DEVICE', 'ORDER', 'CARD', 'RECHARGE', 'AUTH', 'DELIVERY'])
})

test('D4 正确构建（全域 mock，7 段）→ 放行', () => {
  const actual = formatRuntimeModes(ALL_MOCK_MAP)
  assert.equal(actual, 'GLOBAL=mock;DEVICE=mock;ORDER=mock;CARD=mock;RECHARGE=mock;AUTH=mock;DELIVERY=mock')
  assert.deepEqual(checkRuntimeModes(actual, ALL_MOCK_MAP), [])
})

test('E1b 正确构建（device/order/card=real、recharge/delivery=mock、auth=real）→ 放行', () => {
  assert.equal(EXPECTED_REAL_MODES, 'GLOBAL=mock;DEVICE=real;ORDER=real;CARD=real;RECHARGE=mock;AUTH=real;DELIVERY=mock')
  assert.deepEqual(checkRuntimeModes(EXPECTED_REAL_MODES, EXPECTED_REAL_MODE_MAP), [])
})

test('缺 AUTH 段（旧 5 段构建）→ 拦截并报「缺少模式段 AUTH」', () => {
  const legacy = 'GLOBAL=mock;DEVICE=mock;ORDER=mock;CARD=mock;RECHARGE=mock'
  const reasons = checkRuntimeModes(legacy, ALL_MOCK_MAP)
  assert.ok(reasons.length > 0, '必须拦截')
  assert.ok(reasons.some(r => r.includes('缺少模式段 AUTH')), `原因应指明缺 AUTH，实际：${reasons.join('；')}`)
})

test('E1b 遇到 auth=mock 的构建 → 拦截（不得放行必然卡在入口页的包）', () => {
  const authMock = 'GLOBAL=mock;DEVICE=real;ORDER=real;CARD=real;RECHARGE=mock;AUTH=mock'
  const reasons = checkRuntimeModes(authMock, EXPECTED_REAL_MODE_MAP)
  assert.ok(reasons.some(r => r.includes('AUTH 期望 real 实际 mock')), reasons.join('；'))
})

test('某域值不符 → 拦截并给出精确原因', () => {
  const reasons = checkRuntimeModes('GLOBAL=mock;DEVICE=real;ORDER=mock;CARD=mock;RECHARGE=mock;AUTH=mock', ALL_MOCK_MAP)
  assert.ok(reasons.some(r => r.includes('DEVICE 期望 mock 实际 real')), reasons.join('；'))
})

test('出现未登记域 → 拦截并提示同步期望映射', () => {
  const extra = `${formatRuntimeModes(ALL_MOCK_MAP)};OWNER=real`
  const reasons = checkRuntimeModes(extra, ALL_MOCK_MAP)
  assert.ok(reasons.some(r => r.includes('未登记域 OWNER')), reasons.join('；'))
})

test('空串/畸形指纹 → 拦截', () => {
  assert.ok(checkRuntimeModes('', ALL_MOCK_MAP).length > 0)
  assert.ok(checkRuntimeModes('garbage', ALL_MOCK_MAP).length > 0)
})

test('parseRuntimeModes 解析为逐域映射', () => {
  assert.deepEqual(parseRuntimeModes('A=1;B=2'), { A: '1', B: '2' })
})

// ---- D4 behaviorPass 计数口径 ----

function businessResults(n, ok = true) {
  return Array.from({ length: n }, (_, i) => ({ step: `s${i}`, ok, viaStepError: false }))
}

test('behaviorPass：业务断言数等于常量且全过 → true', () => {
  const results = businessResults(EXPECTED_BEHAVIOR_COUNT)
  assert.equal(countBusinessAssertions(results), EXPECTED_BEHAVIOR_COUNT)
  assert.equal(evaluateBehaviorPass(results, ''), true)
})

test('behaviorPass：少一项 → false（完备性守卫生效）', () => {
  assert.equal(evaluateBehaviorPass(businessResults(EXPECTED_BEHAVIOR_COUNT - 1), ''), false)
})

test('behaviorPass：多一项 → false', () => {
  assert.equal(evaluateBehaviorPass(businessResults(EXPECTED_BEHAVIOR_COUNT + 1), ''), false)
})

test('behaviorPass：异常路径补记项不计入完备性，且因 ok=false 判 false', () => {
  const results = [
    ...businessResults(EXPECTED_BEHAVIOR_COUNT - 1),
    { step: '某步抛错', ok: false, viaStepError: true },
  ]
  // 异常项不计数 → 业务断言只有 25 项，缺项被识别
  assert.equal(countBusinessAssertions(results), EXPECTED_BEHAVIOR_COUNT - 1)
  assert.equal(evaluateBehaviorPass(results, ''), false)
})

test('behaviorPass：有 fatalError 一律 false', () => {
  assert.equal(evaluateBehaviorPass(businessResults(EXPECTED_BEHAVIOR_COUNT), 'WATCHDOG'), false)
})

test('behaviorPass：任一断言 ok=false → false', () => {
  const results = businessResults(EXPECTED_BEHAVIOR_COUNT)
  results[3].ok = false
  assert.equal(evaluateBehaviorPass(results, ''), false)
})
