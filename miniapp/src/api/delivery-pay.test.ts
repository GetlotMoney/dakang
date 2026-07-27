import { describe, expect, it } from 'vitest'
import {
  CONTAINER_WATER_ML,
  deliveryPayWayOptions,
  deliveryPriceLines,
  deliveryTotalText,
  deliveryWaterMl,
} from './delivery'

/**
 * D-214 换算与展示纯函数测试：
 * - 水量换算表与后端 DeliveryPricing.CONTAINER_WATER_ML 同值（3L袋=3000…20L桶=20000，×数量）；
 * - 价格快照展示分流：payWay=3 呈现「水量抵扣」而不是 0 元水费；
 * - 支付方式可用性：拒因文案与服务端扣减事务一致。
 */
describe('deliveryWaterMl (D-214 conversion table)', () => {
  it('matches the backend conversion table per spec and scales by count', () => {
    expect(CONTAINER_WATER_ML).toEqual({
      '3L袋': 3000,
      '5L桶': 5000,
      '10L桶': 10000,
      '20L桶': 20000,
    })
    expect(deliveryWaterMl('3L袋', 1)).toBe(3000)
    expect(deliveryWaterMl('5L桶', 2)).toBe(10000)
    expect(deliveryWaterMl('10L桶', 3)).toBe(30000)
    expect(deliveryWaterMl('20L桶', 99)).toBe(1_980_000)
  })

  it('fails closed on unknown spec and out-of-bound counts', () => {
    expect(() => deliveryWaterMl('50L桶' as never, 1)).toThrowError(/容器规格不合法/)
    expect(() => deliveryWaterMl('20L桶', 0)).toThrowError(/配送数量不合法/)
    expect(() => deliveryWaterMl('20L桶', -1)).toThrowError(/配送数量不合法/)
    expect(() => deliveryWaterMl('20L桶', 100)).toThrowError(/配送数量不合法/)
    expect(() => deliveryWaterMl('20L桶', 1.5)).toThrowError(/配送数量不合法/)
  })
})

describe('deliveryPriceLines / deliveryTotalText (D-214 display split)', () => {
  const balanceTask = {
    payWay: 2 as const,
    containerSpec: '20L桶' as const,
    plannedDeliveryCount: 2,
    priceSnapshot: { waterAmountFen: 2600, deliveryFeeFen: 400, totalAmountFen: 3000 },
  }
  const mlTask = {
    payWay: 3 as const,
    containerSpec: '20L桶' as const,
    plannedDeliveryCount: 2,
    priceSnapshot: { waterAmountFen: 0, deliveryFeeFen: 400, totalAmountFen: 400 },
  }

  it('renders the legacy three rows for balance pay (and for legacy tasks without payWay)', () => {
    const expected = [
      { label: '水费', value: '¥26.00' },
      { label: '配送费', value: '¥4.00' },
      { label: '合计', value: '¥30.00', total: true },
    ]
    expect(deliveryPriceLines(balanceTask)).toEqual(expected)
    // 旧 Mock 夹具/后端旧单没有 payWay：缺省按余额口径展示
    expect(deliveryPriceLines({ ...balanceTask, payWay: undefined })).toEqual(expected)
    expect(deliveryTotalText(balanceTask)).toBe('¥30.00')
  })

  it('renders ml deduction instead of a zero water fee for payWay=3', () => {
    const lines = deliveryPriceLines(mlTask)
    expect(lines).toEqual([
      { label: '水量抵扣', value: '40L' },
      { label: '配送费', value: '¥4.00' },
      { label: '应扣合计', value: '¥4.00（另抵扣 40L）', total: true },
    ])
    // 不得把 0 元水费渲染成「水费 ¥0.00」（会被读成免费）
    expect(lines.some(line => line.label === '水费')).toBe(false)
    expect(lines.some(line => line.value.includes('¥0.00'))).toBe(false)
    expect(deliveryTotalText(mlTask)).toBe('¥4.00+40L')
  })
})

describe('deliveryPayWayOptions (D-214 availability with server-caliber reasons)', () => {
  const quote = { totalFen: 3000, deliveryFeeFen: 400, waterMl: 40000 }

  it('disables both options without a card', () => {
    const options = deliveryPayWayOptions(null, quote)
    expect(options).toHaveLength(2)
    for (const option of options) {
      expect(option.disabled).toBe(true)
      expect(option.reason).toContain('暂无水卡')
    }
  })

  it('enables both options when balance and ml are sufficient', () => {
    expect(deliveryPayWayOptions({ balanceFen: 3000, balanceMl: 40000 }, quote)).toEqual([
      { payWay: 2, disabled: false },
      { payWay: 3, disabled: false },
    ])
  })

  it('disables balance pay when balance cannot cover water fee plus delivery fee', () => {
    const [balance, ml] = deliveryPayWayOptions({ balanceFen: 2999, balanceMl: 40000 }, quote)
    expect(balance).toEqual({
      payWay: 2,
      disabled: true,
      reason: '水卡余额不足以支付本单水费与配送费',
    })
    // 余额付不起全额但付得起配送费：水量抵扣仍可用（这正是双方式的意义）
    expect(ml.disabled).toBe(false)
  })

  it('disables ml pay with the ml reason first, then the fee-balance reason', () => {
    expect(deliveryPayWayOptions({ balanceFen: 3000, balanceMl: 39999 }, quote)[1]).toEqual({
      payWay: 3,
      disabled: true,
      reason: '水卡水量不足以抵扣本单水量',
    })
    expect(deliveryPayWayOptions({ balanceFen: 399, balanceMl: 40000 }, quote)[1]).toEqual({
      payWay: 3,
      disabled: true,
      reason: '水卡余额不足以支付配送费',
    })
  })

  it('disables ml pay under auto-refill regardless of balances (rule table has no payWay column)', () => {
    const [balance, ml] = deliveryPayWayOptions(
      { balanceFen: 999999, balanceMl: 999999 },
      quote,
      { autoRefill: true },
    )
    expect(balance.disabled).toBe(false)
    expect(ml).toEqual({ payWay: 3, disabled: true, reason: '自动补货暂仅支持水卡余额支付' })
  })
})
