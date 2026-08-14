import { describe, expect, it } from 'vitest'
import {
  adjustResultOf,
  flowRowOf,
  normalizePage,
  optionalCount,
  orderDetailOf,
  orderLineOf,
  orderRowOf,
  productDetailOf,
  productRowOf,
  requiredCount,
  requiredDelta,
  skuRowOf,
  stockRowOf,
  strictNonNegInt,
  strictSignedInt
} from './mall-normalize'

/**
 * R1-P0-2：后端 JacksonConfig 对 Long/long 全局 ToStringSerializer——真实响应里
 * 分页 total、金额、库存数量、流水数值全是十进制字符串（"2400"），Integer 字段
 * （version/status/flowType）才是 number。夹具按真实形态书写；畸形值必须拒绝。
 */
describe('数值双形态归一', () => {
  it('接受非负安全整数 number 与规范十进制字符串', () => {
    expect(strictNonNegInt(2400)).toBe(2400)
    expect(strictNonNegInt('2400')).toBe(2400)
    expect(strictNonNegInt(0)).toBe(0)
    expect(strictNonNegInt('0')).toBe(0)
  })

  it('拒绝小数/负数/科学计数/前导零/正负号/空白/空串/超安全整数/非数值', () => {
    const bad = [
      24.5,
      -1,
      '24.5',
      '-1',
      '2e3',
      '0024',
      '+24',
      ' 24',
      '24 ',
      '',
      '9007199254740993',
      'abc',
      null,
      undefined,
      {},
      []
    ]
    for (const form of bad) {
      expect(strictNonNegInt(form), `应拒绝 ${JSON.stringify(form)}`).toBeUndefined()
    }
    expect(() => requiredCount('24.5', 'total')).toThrow('total')
  })

  it('带符号变体收流水负增减（"-3"），仍拒绝 "-0" 与前导零', () => {
    expect(strictSignedInt('-3')).toBe(-3)
    expect(strictSignedInt(-3)).toBe(-3)
    expect(strictSignedInt('0')).toBe(0)
    expect(strictSignedInt('-0')).toBeUndefined()
    expect(strictSignedInt('-03')).toBeUndefined()
    expect(() => requiredDelta('2e3', 'availableChange')).toThrow('availableChange')
  })

  it('可空数值：null/undefined 归 undefined，畸形仍抛错', () => {
    expect(optionalCount(null, 'marketPrice')).toBeUndefined()
    expect(optionalCount(undefined, 'marketPrice')).toBeUndefined()
    expect(optionalCount('2000', 'marketPrice')).toBe(2000)
    expect(() => optionalCount('20.5', 'marketPrice')).toThrow('marketPrice')
  })
})

describe('分页壳与行归一（真实 Jackson 形态）', () => {
  it('total 是 Long 字符串，归一为 number；行经 mapRow', () => {
    const page = normalizePage({ total: '135', list: [{ id: '1' }] }, (row) => row)
    expect(page.total).toBe(135)
    expect(page.list).toHaveLength(1)
  })

  it('total 畸形整页失败；分页壳缺失也失败', () => {
    expect(() => normalizePage({ total: '13.5', list: [] }, (row) => row)).toThrow('total')
    expect(() => normalizePage(null, (row) => row)).toThrow()
  })

  it('商品行 skuCount、SKU 行价格/重量、库存行数量都从字符串归一', () => {
    const product = productRowOf({ id: '5', productNo: 'P001', skuCount: '3', version: 2 })
    expect(product.skuCount).toBe(3)
    expect(product.version).toBe(2)

    const sku = skuRowOf({
      id: '7',
      skuName: '单桶',
      salePrice: '1800',
      marketPrice: '2000',
      weightGram: '19500',
      skuStatus: 1,
      version: 1
    })
    expect(sku.salePrice).toBe(1800)
    expect(sku.marketPrice).toBe(2000)
    expect(sku.weightGram).toBe(19500)

    const stock = stockRowOf({ id: '9', availableQty: '120', reservedQty: '0', version: 3 })
    expect(stock.availableQty).toBe(120)
    expect(stock.reservedQty).toBe(0)
  })

  it('流水行：增减列带符号（出库为负），后置值非负', () => {
    const flow = flowRowOf({
      id: '11',
      flowType: 2,
      availableChange: '-3',
      reservedChange: '0',
      availableAfter: '117',
      reservedAfter: '0'
    })
    expect(flow.availableChange).toBe(-3)
    expect(flow.availableAfter).toBe(117)
    expect(() =>
      flowRowOf({
        availableChange: '-3',
        reservedChange: '0',
        availableAfter: '-1',
        reservedAfter: '0'
      })
    ).toThrow('availableAfter')
  })

  it('订单行：三个金额列从 Long 字符串归一，状态/种类数保持数字，无支付单时 payStatus 缺省', () => {
    const order = orderRowOf({
      id: '9007199254740991',
      orderNo: 'MO20260808000001',
      userId: '10086',
      orderStatus: 2,
      payStatus: 2,
      productAmountFen: '2400',
      deliveryFeeFen: '500',
      orderAmountFen: '2900',
      itemKindCount: 2,
      firstProductName: '大康山泉',
      maskedPhone: '138****8000'
    })
    expect(order.productAmountFen).toBe(2400)
    expect(order.deliveryFeeFen).toBe(500)
    expect(order.orderAmountFen).toBe(2900)
    expect(order.itemKindCount).toBe(2)
    expect(order.orderStatus).toBe(2)
    // 身份 ID 全程保持字符串，绝不经 Number 舍入
    expect(order.id).toBe('9007199254740991')

    const noPayment = orderRowOf({
      id: '2',
      orderNo: 'MO20260808000002',
      userId: '10086',
      orderStatus: 1,
      payStatus: null,
      productAmountFen: '0',
      deliveryFeeFen: '0',
      orderAmountFen: '0'
    })
    expect(noPayment.payStatus).toBeNull()
    expect(noPayment.orderAmountFen).toBe(0)
    expect(noPayment.itemKindCount).toBeUndefined()

    expect(() =>
      orderRowOf({ productAmountFen: '24.5', deliveryFeeFen: '0', orderAmountFen: '0' })
    ).toThrow('productAmountFen')
  })

  it('订单明细行：单价/行金额/重量为 Long 字符串，数量为 Integer', () => {
    const line = orderLineOf({
      productId: '5',
      skuId: '7',
      productName: '大康山泉',
      skuName: '18.9L 单桶',
      specs: { 规格: '18.9L' },
      unitPriceFen: '1800',
      quantity: 2,
      itemAmountFen: '3600',
      weightGram: '19500'
    })
    expect(line.unitPriceFen).toBe(1800)
    expect(line.itemAmountFen).toBe(3600)
    expect(line.weightGram).toBe(19500)
    expect(line.quantity).toBe(2)
    expect(line.specs).toEqual({ 规格: '18.9L' })

    expect(() =>
      orderLineOf({ unitPriceFen: '1800', quantity: 2, itemAmountFen: '-1', weightGram: '1' })
    ).toThrow('itemAmountFen')
  })

  it('订单详情：概要与明细逐行归一；概要缺失或明细畸形整单失败', () => {
    const detail = orderDetailOf({
      summary: {
        id: '1',
        orderNo: 'MO20260808000001',
        userId: '10086',
        orderStatus: 4,
        payStatus: 2,
        productAmountFen: '3600',
        deliveryFeeFen: '500',
        orderAmountFen: '4100',
        itemKindCount: 1
      },
      receiverRegion: '浙江省 杭州市 西湖区',
      receiverAddress: '文一西路 1 号',
      paySource: 2,
      transactionId: 'SIM202608080001',
      items: [
        {
          productId: '5',
          skuId: '7',
          unitPriceFen: '1800',
          quantity: 2,
          itemAmountFen: '3600',
          weightGram: '19500'
        }
      ]
    })
    expect(detail.summary.orderAmountFen).toBe(4100)
    expect(detail.items[0].itemAmountFen).toBe(3600)
    expect(detail.receiverRegion).toBe('浙江省 杭州市 西湖区')

    expect(() => orderDetailOf(null)).toThrow()
    expect(() => orderDetailOf({ items: [] })).toThrow('订单概要')
    expect(() =>
      orderDetailOf({
        summary: {
          id: '1',
          orderNo: 'MO1',
          userId: '1',
          orderStatus: 1,
          productAmountFen: '0',
          deliveryFeeFen: '0',
          orderAmountFen: '0'
        },
        items: [{ unitPriceFen: '1800', quantity: 2, itemAmountFen: '2e3', weightGram: '1' }]
      })
    ).toThrow('itemAmountFen')
  })

  it('订单分页壳：total 是 Long 字符串，行经 orderRowOf 归一', () => {
    const page = normalizePage(
      {
        total: '135',
        list: [
          {
            id: '1',
            orderNo: 'MO20260808000001',
            userId: '10086',
            orderStatus: 1,
            productAmountFen: '2400',
            deliveryFeeFen: '500',
            orderAmountFen: '2900'
          }
        ]
      },
      orderRowOf
    )
    expect(page.total).toBe(135)
    expect(page.list[0].orderAmountFen).toBe(2900)
  })

  // 库存动作回执是本模块唯一挂在写端点（/mall/stock/adjust）上的出口：
  // 它的值直接出现在动作结果上，操作员据此判断这次调整是否成功、要不要重试。
  // 读侧 13 个函数覆盖得再密，也守不住这一个——缺口恰好落在风险最高处。
  it('库存动作回执：增减带符号、结果值非负，全部从 Long 字符串归一', () => {
    const result = adjustResultOf({
      requestId: '8c4f1f3e-0a1e-4a1a-9a2e-2a1b3c4d5e6f',
      warehouseId: '9007199254740991',
      skuId: '9007199254740993',
      flowType: 2,
      availableChange: '-3',
      availableAfter: '1197',
      reservedAfter: '0'
    })
    expect(result.availableChange).toBe(-3)
    expect(result.availableAfter).toBe(1197)
    expect(result.reservedAfter).toBe(0)
    expect(result.flowType).toBe(2)
    // 仓库/SKU 编号是身份类 Long，逐字保持字符串：数值化后超 2^53 会静默改成相邻仓
    expect(result.warehouseId).toBe('9007199254740991')
    expect(result.skuId).toBe('9007199254740993')
  })

  it('库存动作回执：结果缺失或任一数值畸形抛错，不把字符串形态交给页面参与运算', () => {
    expect(() => adjustResultOf(null)).toThrow()
    expect(() => adjustResultOf(undefined)).toThrow()
    expect(() => adjustResultOf('ok')).toThrow()
    expect(() =>
      adjustResultOf({ availableChange: '-3', availableAfter: '-1', reservedAfter: '0' })
    ).toThrow('availableAfter')
    expect(() =>
      adjustResultOf({ availableChange: '2e3', availableAfter: '1', reservedAfter: '0' })
    ).toThrow('availableChange')
    expect(() =>
      adjustResultOf({ availableChange: '-3', availableAfter: '1', reservedAfter: '0.5' })
    ).toThrow('reservedAfter')
  })

  it('商品详情：SKU 与库存子表逐行归一，任一行畸形整体失败', () => {
    const detail = productDetailOf({
      id: '5',
      productNo: 'P001',
      skus: [{ id: '7', salePrice: '1800', weightGram: '100', version: 1 }],
      stocks: [{ id: '9', availableQty: '10', reservedQty: '0', version: 1 }]
    })
    expect(detail.skus[0].salePrice).toBe(1800)
    expect(detail.stocks[0].availableQty).toBe(10)

    expect(() =>
      productDetailOf({
        id: '5',
        skus: [{ id: '7', salePrice: '18.5', weightGram: '100' }],
        stocks: []
      })
    ).toThrow('salePrice')
  })
})
