import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as request from './request'
import { ContractError } from './common'
import {
  createMallRequestId,
  decodeCheckoutLines,
  encodeCheckoutLines,
  mallTradeApi,
  mallTradeEndpoints,
} from './mall-trade'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * 夹具一律按 JacksonConfig 的真实输出形态书写：Long/long 走 ToStringSerializer，
 * 所以金额、总数到达前端是十进制字符串（"2400"）；Integer（状态、件数、数量）才是裸数字。
 * 这份形态是 S1 踩过的坑——当时只收 number，后端返回的 "2400" 把整页拒收了。
 */
const CART_LINE = {
  skuId: '77',
  productId: '12',
  productName: '桶装水',
  skuName: '单桶',
  specs: { 规格: '18.9L' },
  coverUrl: 'https://img.example/1.png',
  salePriceFen: '1800',
  quantity: 2,
  itemAmountFen: '3600',
  purchasable: true,
}

const ORDER_SUMMARY = {
  id: '9007199254740993',
  orderNo: 'MO-20260808-0001',
  userId: '31',
  orderStatus: 1,
  payStatus: 1,
  productAmountFen: '3600',
  deliveryFeeFen: '0',
  orderAmountFen: '3600',
  itemKindCount: 1,
  firstProductName: '桶装水',
  warehouseName: '洪山前置仓',
  receiverName: '张女士',
  maskedPhone: '139****1111',
  payExpireTime: '20260808120000',
  createTime: '20260808113000',
}

describe('购物车归一化', () => {
  beforeEach(() => post.mockReset())

  it('后端真实形态：金额是 Long 字符串，逐字归一为整数分', async () => {
    post.mockResolvedValueOnce({
      lines: [CART_LINE],
      totalQuantity: 2,
      totalAmountFen: '3600',
    })
    const cart = await mallTradeApi.cartList()
    expect(cart.lines[0].skuId).toBe('77')
    expect(cart.lines[0].salePriceFen).toBe(1800)
    expect(cart.lines[0].itemAmountFen).toBe(3600)
    expect(cart.lines[0].specs).toEqual({ 规格: '18.9L' })
    expect(cart.totalQuantity).toBe(2)
    expect(cart.totalAmountFen).toBe(3600)
  })

  it('number 与十进制字符串两种形态同值等价', async () => {
    post.mockResolvedValueOnce({
      lines: [
        { ...CART_LINE, skuId: '77', salePriceFen: 1800, itemAmountFen: 3600 },
        { ...CART_LINE, skuId: '78', salePriceFen: '1800', itemAmountFen: '3600' },
      ],
      totalQuantity: 4,
      totalAmountFen: 7200,
    })
    const cart = await mallTradeApi.cartList()
    expect(cart.lines.map(line => line.salePriceFen)).toEqual([1800, 1800])
    expect(cart.lines.map(line => line.itemAmountFen)).toEqual([3600, 3600])
    expect(cart.totalAmountFen).toBe(7200)
  })

  it('失效行保留并带原因，金额恒 0 且不可结算', async () => {
    post.mockResolvedValueOnce({
      lines: [{
        skuId: '90',
        productId: '13',
        productName: '停售水',
        skuName: '旧规格',
        specs: {},
        salePriceFen: '1800',
        quantity: 1,
        itemAmountFen: '0',
        purchasable: false,
        unavailableReason: '该规格已停售',
      }],
      totalQuantity: 0,
      totalAmountFen: '0',
    })
    const cart = await mallTradeApi.cartList()
    expect(cart.lines[0].purchasable).toBe(false)
    expect(cart.lines[0].unavailableReason).toBe('该规格已停售')
    expect(cart.lines[0].itemAmountFen).toBe(0)
    expect(cart.totalAmountFen).toBe(0)
  })

  it('purchasable 非显式 true 一律按失效（fail-closed）', async () => {
    post.mockResolvedValueOnce({
      lines: [
        { ...CART_LINE, skuId: '1', purchasable: 'yes' },
        { ...CART_LINE, skuId: '2', purchasable: undefined },
      ],
      totalQuantity: 0,
      totalAmountFen: '0',
    })
    const cart = await mallTradeApi.cartList()
    expect(cart.lines.map(line => line.purchasable)).toEqual([false, false])
  })

  it('畸形金额/数量 fail-closed：小数、负数、科学计数、前导零、空串、超安全整数', async () => {
    const badAmounts = [18.5, -1, '18.5', '-1', '2e3', '0024', '+24', ' 24', '', '9007199254740993', 'abc']
    for (const bad of badAmounts) {
      post.mockResolvedValueOnce({
        lines: [{ ...CART_LINE, itemAmountFen: bad }],
        totalQuantity: 1,
        totalAmountFen: '0',
      })
      await expect(mallTradeApi.cartList(), `应拒绝金额 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
    for (const bad of [0, '0', -1, 1.5, '', null]) {
      post.mockResolvedValueOnce({
        lines: [{ ...CART_LINE, quantity: bad }],
        totalQuantity: 1,
        totalAmountFen: '0',
      })
      await expect(mallTradeApi.cartList(), `应拒绝数量 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('规格快照 fail-closed：null/数组/数字值都是契约破坏', async () => {
    for (const bad of [null, ['18.9L'], { 规格: 18.9 }, undefined]) {
      post.mockResolvedValueOnce({
        lines: [{ ...CART_LINE, specs: bad }],
        totalQuantity: 1,
        totalAmountFen: '3600',
      })
      await expect(mallTradeApi.cartList(), `应拒绝规格 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('加购与改量的累加语义原样上送，数量越界在发请求前就拒绝', async () => {
    post.mockResolvedValueOnce({ lines: [], totalQuantity: 0, totalAmountFen: '0' })
    await mallTradeApi.cartSave({ skuId: '77', quantity: 2, increment: true })
    expect(post).toHaveBeenCalledWith(mallTradeEndpoints.cartSave, {
      skuId: '77',
      quantity: 2,
      increment: true,
    })
    await expect(mallTradeApi.cartSave({ skuId: '77', quantity: 1000, increment: false }))
      .rejects
      .toBeInstanceOf(ContractError)
    await expect(mallTradeApi.cartSave({ skuId: '0', quantity: 1, increment: false }))
      .rejects
      .toBeInstanceOf(ContractError)
  })

  it('接口异常直接失败——没有任何 Mock 购物车兜底', async () => {
    post.mockRejectedValueOnce(new Error('network down'))
    await expect(mallTradeApi.cartList()).rejects.toThrow()
  })
})

describe('结算预览归一化', () => {
  beforeEach(() => post.mockReset())

  it('金额三项与履约仓逐字归一，可提交结论取服务端', async () => {
    post.mockResolvedValueOnce({
      lines: [CART_LINE],
      productAmountFen: '3600',
      deliveryFeeFen: '0',
      orderAmountFen: '3600',
      receiverName: '张女士',
      maskedPhone: '139****1111',
      receiverRegion: '湖北省武汉市洪山区',
      receiverAddress: '光谷大道 1 号',
      warehouseName: '洪山前置仓',
      submittable: true,
    })
    const preview = await mallTradeApi.checkoutPreview({
      addressId: '5',
      lines: [{ skuId: '77', quantity: 2 }],
    })
    expect(preview.productAmountFen).toBe(3600)
    expect(preview.deliveryFeeFen).toBe(0)
    expect(preview.orderAmountFen).toBe(3600)
    expect(preview.warehouseName).toBe('洪山前置仓')
    expect(preview.submittable).toBe(true)
    expect(post).toHaveBeenCalledWith(mallTradeEndpoints.checkoutPreview, {
      addressId: '5',
      lines: [{ skuId: '77', quantity: 2 }],
    })
  })

  it('不可提交时保留服务端理由；submittable 非 true 一律不可提交', async () => {
    post.mockResolvedValueOnce({
      lines: [],
      productAmountFen: '0',
      deliveryFeeFen: '0',
      orderAmountFen: '0',
      receiverName: '张女士',
      maskedPhone: '139****1111',
      receiverRegion: '湖北省武汉市洪山区',
      receiverAddress: '光谷大道 1 号',
      submittable: 'true',
      blockReason: '该地址还没有选择所在区县，请编辑地址后重试',
    })
    const preview = await mallTradeApi.checkoutPreview({
      addressId: '5',
      lines: [{ skuId: '77', quantity: 2 }],
    })
    expect(preview.submittable).toBe(false)
    expect(preview.blockReason).toBe('该地址还没有选择所在区县，请编辑地址后重试')
    expect(preview.warehouseName).toBeUndefined()
  })
})

describe('商城订单归一化', () => {
  beforeEach(() => post.mockReset())

  it('分页 total 归一：Long 字符串转 number，空列表按 0 条', async () => {
    post.mockResolvedValueOnce({ list: [ORDER_SUMMARY], total: '37' })
    const page = await mallTradeApi.listOrders({ current: 2, size: 10 })
    expect(page.total).toBe(37)
    expect(page.list[0].orderId).toBe('9007199254740993')
    expect(page.list[0].orderAmountFen).toBe(3600)
    expect(post).toHaveBeenCalledWith(mallTradeEndpoints.orderPage, {
      current: 2,
      size: 10,
      orderStatus: undefined,
    })

    post.mockResolvedValueOnce({ list: null, total: 0 })
    const empty = await mallTradeApi.listOrders()
    expect(empty).toEqual({ list: [], total: 0 })
  })

  it('畸形 total fail-closed：不许把坏总数当 0 蒙混成「没有更多」', async () => {
    for (const bad of ['', '-1', '1.5', 'abc', null, undefined]) {
      post.mockResolvedValueOnce({ list: [], total: bad })
      await expect(mallTradeApi.listOrders(), `应拒绝 total ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('订单状态映射：1~6 全部接受，越界或非法一律拒绝', async () => {
    for (const status of [1, 2, 3, 4, 5, 6]) {
      post.mockResolvedValueOnce({ list: [{ ...ORDER_SUMMARY, orderStatus: status }], total: '1' })
      const page = await mallTradeApi.listOrders()
      expect(page.list[0].orderStatus).toBe(status)
    }
    for (const bad of [0, 7, -1, '2待支付', null, undefined]) {
      post.mockResolvedValueOnce({ list: [{ ...ORDER_SUMMARY, orderStatus: bad }], total: '1' })
      await expect(mallTradeApi.listOrders(), `应拒绝订单状态 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('支付状态缺省即缺省（未生成支付单），越界值拒绝', async () => {
    post.mockResolvedValueOnce({ list: [{ ...ORDER_SUMMARY, payStatus: null }], total: '1' })
    expect((await mallTradeApi.listOrders()).list[0].payStatus).toBeUndefined()

    post.mockResolvedValueOnce({ list: [{ ...ORDER_SUMMARY, payStatus: 5 }], total: '1' })
    await expect(mallTradeApi.listOrders()).rejects.toBeInstanceOf(ContractError)
  })

  it('详情明细为下单快照，单价与重量同样按整数分/克归一', async () => {
    post.mockResolvedValueOnce({
      summary: { ...ORDER_SUMMARY, orderStatus: 2, payStatus: 2 },
      receiverRegion: '湖北省武汉市洪山区',
      receiverAddress: '光谷大道 1 号',
      paySource: 2,
      paySuccessTime: '20260808114500',
      items: [{
        orderItemId: '901',
        productId: '12',
        skuId: '77',
        productName: '桶装水',
        skuName: '单桶',
        specs: { 规格: '18.9L' },
        unitPriceFen: '1800',
        quantity: 2,
        itemAmountFen: '3600',
        weightGram: '19500',
      }],
    })
    const detail = await mallTradeApi.orderDetail('MO-20260808-0001')
    expect(detail.summary.orderStatus).toBe(2)
    expect(detail.summary.payStatus).toBe(2)
    expect(detail.items[0].orderItemId).toBe('901')
    expect(detail.items[0].unitPriceFen).toBe(1800)
    expect(detail.items[0].itemAmountFen).toBe(3600)
    expect(detail.items[0].weightGram).toBe(19500)
    expect(detail.paySource).toBe(2)
  })

  it('创单请求号必须是规范小写 UUID，其余形状发都不发', async () => {
    const requestId = createMallRequestId()
    expect(requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)

    post.mockResolvedValueOnce(ORDER_SUMMARY)
    await mallTradeApi.createOrder({
      addressId: '5',
      lines: [{ skuId: '77', quantity: 2 }],
      requestId,
    })
    expect(post).toHaveBeenCalledWith(mallTradeEndpoints.orderCreate, {
      addressId: '5',
      lines: [{ skuId: '77', quantity: 2 }],
      requestId,
    })

    for (const bad of ['mall-mock-0001', '', requestId.toUpperCase(), requestId.replace(/-/g, '')]) {
      await expect(
        mallTradeApi.createOrder({ addressId: '5', lines: [{ skuId: '77', quantity: 2 }], requestId: bad }),
        `应拒绝请求号 ${JSON.stringify(bad)}`,
      ).rejects.toBeInstanceOf(ContractError)
    }
  })

  it('同一次结算的请求号不随重试改变，两次结算之间必须不同', () => {
    expect(createMallRequestId()).not.toBe(createMallRequestId())
  })
})

/**
 * 结算行的页面间编码：购物车勾选与「立即购买」共用一条通道。
 * 任何畸形片段整次拒绝——带着半份行进结算页，用户会对着一张少了商品的订单付款。
 */
describe('结算行编码', () => {
  it('往返等值，顺序原样保留', () => {
    const lines = [{ skuId: '77', quantity: 2 }, { skuId: '9007199254740993', quantity: 1 }]
    const encoded = encodeCheckoutLines(lines)
    expect(encoded).toBe('77:2,9007199254740993:1')
    expect(decodeCheckoutLines(encoded)).toEqual(lines)
    // buildRouteUrl 会编码查询值；微信 onLoad 在实点环境中会把该编码形态原样交给页面。
    expect(decodeCheckoutLines(encodeURIComponent(encoded))).toEqual(lines)
  })

  it('畸形片段整次拒绝', () => {
    for (const bad of ['', '%', '77', '77:0', '0:1', '77:2,', '77:2,77:1', '77:-1', '77:2.5', 'a:1', '77:1000']) {
      expect(() => decodeCheckoutLines(bad), `应拒绝 ${JSON.stringify(bad)}`)
        .toThrowError(ContractError)
    }
  })

  it('编码同样拦截越界：0 件、超 999 件、重复规格、超 50 个规格', () => {
    expect(() => encodeCheckoutLines([])).toThrowError(ContractError)
    expect(() => encodeCheckoutLines([{ skuId: '77', quantity: 0 }])).toThrowError(ContractError)
    expect(() => encodeCheckoutLines([{ skuId: '77', quantity: 1000 }])).toThrowError(ContractError)
    expect(() => encodeCheckoutLines([
      { skuId: '77', quantity: 1 },
      { skuId: '77', quantity: 2 },
    ])).toThrowError(ContractError)
    expect(() => encodeCheckoutLines(
      Array.from({ length: 51 }, (_, index) => ({ skuId: String(index + 1), quantity: 1 })),
    )).toThrowError(ContractError)
  })
})
