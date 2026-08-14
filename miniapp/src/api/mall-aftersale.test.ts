import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as request from './request'
import { ContractError } from './common'
import { mallAfterSaleApi, mallAfterSaleEndpoints, normalizeAfterSale } from './mall-aftersale'
import { normalizeFulfill } from './mall-fulfillment'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * 夹具按 JacksonConfig 的真实输出形态书写：Long 走 ToStringSerializer 到达前端是十进制
 * 字符串（"3600"），Integer（状态、类型、数量）才是裸数字。
 */
const AFTER_SALE = {
  afterSaleNo: 'MAS20260810ABCDEF',
  orderNo: 'MO-20260808-0001',
  afterSaleType: 1,
  afterSaleTypeName: '退货退款',
  afterSaleStatus: 6,
  afterSaleStatusName: '已完成',
  refundAmountFen: '3600',
  applyReason: '漏水',
  applyTime: '20260810103000',
  refundStatus: 2,
  refundSuccessTime: '20260810110000',
  lines: [{
    orderItemId: '901',
    skuId: '77',
    productName: '桶装水',
    skuName: '单桶',
    unitPriceFen: '1800',
    quantity: 2,
    itemAmountFen: '3600',
  }],
  timeline: [{
    traceNode: 1,
    traceNodeName: '待审核',
    actorType: 2,
    actorTypeName: '用户',
    traceTime: '20260810103000',
    traceText: '用户提交售后申请',
  }],
}

const FULFILL = {
  orderNo: 'MO-20260810-0009',
  fulfillStatus: 1,
  fulfillStatusName: '待取货',
  warehouseId: '3',
  receiverName: '林女士',
  receiverPhone: '138****8888',
  receiverRegion: '湖北省武汉市洪山区',
  receiverAddress: '光谷大道 1 号',
  timeline: [],
}

beforeEach(() => {
  post.mockReset()
})

describe('mall after-sale api contracts', () => {
  it('normalizes a completed after-sale with string money and timeline', () => {
    const row = normalizeAfterSale(AFTER_SALE)
    expect(row.refundAmountFen).toBe(3600)
    expect(row.lines[0].unitPriceFen).toBe(1800)
    expect(row.lines[0].orderItemId).toBe('901')
    expect(row.timeline).toHaveLength(1)
    expect(row.timeline[0].traceNodeName).toBe('待审核')
  })

  it('rejects unknown status and type instead of rendering them as pending', () => {
    // 未知状态渲染成「处理中」的代价：用户对着一张永远不会推进的单子一直等
    expect(() => normalizeAfterSale({ ...AFTER_SALE, afterSaleStatus: 99 }))
      .toThrowError(ContractError)
    expect(() => normalizeAfterSale({ ...AFTER_SALE, afterSaleType: 0 }))
      .toThrowError(ContractError)
    // 数字字符串是 Jackson 的合法形态（与全域 Long 归一同源），按值判白名单而不是按类型拒收
    expect(normalizeAfterSale({ ...AFTER_SALE, afterSaleStatus: '6' }).afterSaleStatus).toBe(6)
  })

  it('rejects malformed money and quantity rather than showing 0', () => {
    expect(() => normalizeAfterSale({ ...AFTER_SALE, refundAmountFen: '3600.5' }))
      .toThrowError(ContractError)
    expect(() => normalizeAfterSale({
      ...AFTER_SALE,
      lines: [{ ...AFTER_SALE.lines[0], quantity: 0 }],
    })).toThrowError(ContractError)
  })

  it('applies without ever sending an amount field', async () => {
    post.mockResolvedValue(AFTER_SALE)
    await mallAfterSaleApi.apply({
      requestId: '5c2f2f6f-1f2a-4f1e-9a5e-6f3a1b2c3d4e',
      orderNo: 'MO-20260808-0001',
      afterSaleType: 1,
      applyReason: '漏水',
      lines: [{ orderItemId: '901', quantity: 2 }],
    })
    const [url, body] = post.mock.calls[0]
    expect(url).toBe(mallAfterSaleEndpoints.apply)
    // 金额只有一个来源：服务端按原订单不可变明细算。前端多传一个字段就多一个可被伪造的入口
    expect(JSON.stringify(body)).not.toMatch(/amount|Fen/i)
    expect(body).toMatchObject({ orderNo: 'MO-20260808-0001', afterSaleType: 1 })
  })

  it('lists and reads details through the session-guarded endpoints', async () => {
    post.mockResolvedValue([AFTER_SALE])
    const rows = await mallAfterSaleApi.list()
    expect(post.mock.calls[0][0]).toBe(mallAfterSaleEndpoints.list)
    expect(rows).toHaveLength(1)

    post.mockResolvedValue(AFTER_SALE)
    const detail = await mallAfterSaleApi.detail('MAS20260810ABCDEF')
    expect(post.mock.calls[1][0]).toBe(mallAfterSaleEndpoints.detail)
    expect(detail.afterSaleNo).toBe('MAS20260810ABCDEF')
  })
})

describe('mall fulfillment exchange marking', () => {
  it('marks a reshipment task only on an explicit true', () => {
    expect(normalizeFulfill({ ...FULFILL, exchangeReshipment: true }).exchangeReshipment).toBe(true)
    // 缺字段或形态不对一律按「不是换货」：错标成换货会让配送员当着用户的面免掉本该收的款
    expect(normalizeFulfill(FULFILL).exchangeReshipment).toBe(false)
    expect(normalizeFulfill({ ...FULFILL, exchangeReshipment: 'true' }).exchangeReshipment)
      .toBe(false)
  })
})
