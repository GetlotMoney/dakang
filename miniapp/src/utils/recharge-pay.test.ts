import type { RechargePayStatus } from '@/api/recharge'
import { describe, expect, it } from 'vitest'
import { continuePayGate } from '@/utils/recharge-pay'

/**
 * 「继续支付」按钮显隐的纯函数判定。
 *
 * 这层守卫只负责不把一个必然被服务端拒绝的按钮摆在用户面前；
 * 真正能否支付由服务端（Pay-Sim 前置守卫）判定。守卫失效的后果是：
 * 用户在已完成/已过期的单上反复点击并以为还能付。
 */
const NOW = '20260722120000'

function status(overrides: Partial<RechargePayStatus>): RechargePayStatus {
  return {
    orderNo: 'RC0000000000000000000000000001',
    payStatus: 1,
    orderStatus: 1,
    payStatusCode: 'WAITING_PAYMENT',
    payExpireTime: '20260722123000',
    retryable: true,
    ...overrides,
  }
}

describe('continuePayGate', () => {
  it('待支付且未过截止时间：显示按钮，不额外说明', () => {
    const gate = continuePayGate(status({}), NOW)
    expect(gate.visible).toBe(true)
    expect(gate.reason).toBeUndefined()
  })

  it('恰好等于截止时间仍可支付（与服务端 paySuccessTime <= PAY_EXPIRE_TIME 同口径）', () => {
    expect(continuePayGate(status({ payExpireTime: NOW }), NOW).visible).toBe(true)
  })

  it('已过付款截止时间：不显示按钮，并如实说明该单不可支付', () => {
    const gate = continuePayGate(status({ payExpireTime: '20260722115959' }), NOW)
    expect(gate.visible).toBe(false)
    expect(gate.reason).toContain('付款截止时间')
    expect(gate.reason).toContain('2026-07-22 11:59:59')
  })

  it('已完成订单：不显示按钮，说明交由既有状态提示', () => {
    const gate = continuePayGate(
      status({ payStatusCode: 'COMPLETED', payStatus: 2, orderStatus: 4, retryable: false }),
      NOW,
    )
    expect(gate.visible).toBe(false)
    expect(gate.reason).toBeUndefined()
  })

  it('数据不一致（MISMATCH）时绝不引导再付一次', () => {
    expect(continuePayGate(status({ payStatusCode: 'MISMATCH' }), NOW).visible).toBe(false)
  })

  it('缺少服务端结论（未取到 pay-status）时不显示按钮', () => {
    expect(continuePayGate(null, NOW).visible).toBe(false)
    expect(continuePayGate(status({ payStatusCode: undefined }), NOW).visible).toBe(false)
  })

  it('截止时间缺失或格式非法一律 fail-closed', () => {
    expect(continuePayGate(status({ payExpireTime: undefined }), NOW)).toEqual({
      visible: false,
      reason: '无法继续支付，请联系客服核对。',
    })
    expect(continuePayGate(status({ payExpireTime: '2026072212' }), NOW).visible).toBe(false)
  })
})
