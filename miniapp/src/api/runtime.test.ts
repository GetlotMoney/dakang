import { describe, expect, it } from 'vitest'
import { resolveApiMode } from './runtime'

/**
 * 模式解析纯函数矩阵（2026-07-20 最终收口轮）：
 * 关键组合「全局 real + recharge mock」必须真实成立——recharge 域显式 mock 时，
 * 即使全局与其它域全部翻 real，充值也严格停留在 mock，杜绝未实现后端却接真的半接真事故。
 */
describe('resolveApiMode', () => {
  it('keeps recharge on mock while order is real (order=real + recharge=mock)', () => {
    const env = { globalMode: 'mock', orderMode: 'real', rechargeMode: 'mock' }
    expect(resolveApiMode('order', env)).toBe('real')
    expect(resolveApiMode('recharge', env)).toBe('mock')
  })

  it('keeps recharge on mock even when the GLOBAL mode is real', () => {
    const env = {
      globalMode: 'real',
      deviceMode: 'real',
      orderMode: 'real',
      cardMode: 'real',
      rechargeMode: 'mock',
    }
    expect(resolveApiMode(undefined, env)).toBe('real')
    expect(resolveApiMode('device', env)).toBe('real')
    expect(resolveApiMode('card', env)).toBe('real')
    expect(resolveApiMode('recharge', env)).toBe('mock')
  })

  // L2-T 起充值链后端已实现，代码层硬锁解除。这里改为钉死解锁后的两条不变量：
  // ① rechargeMode='real' 必须真的生效——否则接真构建会静默退回 Mock，
  //    用户以为在充真卡，其实点了个原型，真机验收全是假绿。
  // ② 漏配时必须回落 mock——绝不能因为全局是 real 就默认对钱动手。
  it('honours an explicit recharge override now that the L2 backend exists', () => {
    expect(resolveApiMode('recharge', { globalMode: 'mock', rechargeMode: 'real' })).toBe('real')
    expect(resolveApiMode('recharge', { globalMode: 'real', rechargeMode: 'real' })).toBe('real')
  })

  it('still falls back to mock when the recharge override is missing or not exactly real', () => {
    expect(resolveApiMode('recharge', { globalMode: 'mock' })).toBe('mock')
    expect(resolveApiMode('recharge', { globalMode: 'mock', rechargeMode: 'REAL' })).toBe('mock')
    expect(resolveApiMode('recharge', { globalMode: 'mock', rechargeMode: 'true' })).toBe('mock')
    expect(resolveApiMode('recharge', { globalMode: 'mock', rechargeMode: '' })).toBe('mock')
  })

  // E2E-03 包B：delivery 域按 recharge 先例解锁，钉死同样两条不变量：
  // ① deliveryMode='real' 必须真的生效——否则接真构建静默退回 Mock，真机验收全是假绿；
  // ② 漏配/拼错时必须回落 mock——绝不能因为全局是 real 就默认打开真实配送写链。
  it('honours an explicit delivery override now that the E2E-03 backend exists', () => {
    expect(resolveApiMode('delivery', { globalMode: 'mock', deliveryMode: 'real' })).toBe('real')
    expect(resolveApiMode('delivery', { globalMode: 'real', deliveryMode: 'real' })).toBe('real')
    expect(resolveApiMode('delivery', { globalMode: 'real', deliveryMode: 'mock' })).toBe('mock')
  })

  it('falls back to mock when the delivery override is missing or not exactly real', () => {
    expect(resolveApiMode('delivery', { globalMode: 'mock' })).toBe('mock')
    expect(resolveApiMode('delivery', { globalMode: 'mock', deliveryMode: 'REAL' })).toBe('mock')
    expect(resolveApiMode('delivery', { globalMode: 'mock', deliveryMode: 'true' })).toBe('mock')
    expect(resolveApiMode('delivery', { globalMode: 'mock', deliveryMode: '' })).toBe('mock')
  })

  it('falls back to the global mode when a domain has no override', () => {
    expect(resolveApiMode('device', { globalMode: 'real' })).toBe('real')
    expect(resolveApiMode('device', { globalMode: 'mock' })).toBe('mock')
    expect(resolveApiMode('device', { globalMode: 'real', deviceMode: '' })).toBe('real')
  })

  it('treats anything other than explicit real as mock (fail-safe default)', () => {
    expect(resolveApiMode('recharge', {})).toBe('mock')
    expect(resolveApiMode('order', { globalMode: 'REAL' })).toBe('mock')
    expect(resolveApiMode(undefined, { globalMode: undefined })).toBe('mock')
  })
})

/**
 * 复审 B：401 后绝不落 Mock 账号——任一业务域接真时，入口页不得用 Mock 原型账号恢复会话。
 * 关键组合是「auth=mock 且 device/order/card=real」：这是唯一能真正产生 140x 的构建。
 */
