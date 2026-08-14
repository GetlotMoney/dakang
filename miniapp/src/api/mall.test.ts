import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as request from './request'
import { ContractError } from './common'
import { mallApi } from './mall'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * E2E-09 商城只读契约（R1-P0-2/P1-3 整改后）：
 * 后端 JacksonConfig 对 Long/long 全局 ToStringSerializer——真实响应里金额、重量
 * 全是十进制字符串（"2400"），Integer 字段才是 number。归一化层两种形态都要收，
 * 畸形值（小数/负数/科学计数/前导零/空串/超安全整数）一律 fail-closed 抛错；
 * 规格快照只接受 Record<string,string>，任何强转或静默丢弃都是契约破坏。
 */
describe('商城首页归一化', () => {
  beforeEach(() => post.mockReset())

  it('后端真实形态：Long 金额是字符串，逐字归一为整数分', async () => {
    // 夹具按 JacksonConfig 实际输出形态书写：minSalePriceFen 为 "2400" 而非 2400
    post.mockResolvedValueOnce({
      categories: [{ categoryId: '9007199254740993', categoryName: '饮用水' }],
      products: [{
        productId: '12',
        productName: '整箱水',
        categoryId: '9007199254740993',
        minSalePriceFen: '2400',
        inStock: true,
      }],
    })
    const home = await mallApi.home()
    expect(home.categories[0].categoryId).toBe('9007199254740993')
    expect(home.products[0].productId).toBe('12')
    expect(home.products[0].minSalePriceFen).toBe(2400)
    expect(home.products[0].inStock).toBe(true)
  })

  it('number 与字符串两种形态同值等价；"0" 与 0 都合法', async () => {
    post.mockResolvedValueOnce({
      categories: [],
      products: [
        { productId: '1', productName: 'a', categoryId: '2', minSalePriceFen: 2400, inStock: true },
        { productId: '3', productName: 'b', categoryId: '2', minSalePriceFen: '2400', inStock: true },
        { productId: '4', productName: 'c', categoryId: '2', minSalePriceFen: '0', inStock: false },
      ],
    })
    const home = await mallApi.home()
    expect(home.products.map(p => p.minSalePriceFen)).toEqual([2400, 2400, 0])
  })

  it('畸形金额 fail-closed：小数/负数/科学计数/前导零/正号/空串/超安全整数/非数值', async () => {
    const badForms = [
      24.5,
      -1, // number 形态
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
    ]
    for (const bad of badForms) {
      post.mockResolvedValueOnce({
        categories: [],
        products: [{ productId: '1', productName: 'x', categoryId: '2', minSalePriceFen: bad, inStock: true }],
      })
      await expect(mallApi.home(), `应拒绝形态 ${JSON.stringify(bad)}`).rejects.toBeInstanceOf(ContractError)
    }
  })

  it('接口异常直接失败——无任何 Mock 商品兜底路径', async () => {
    post.mockRejectedValueOnce(new Error('network down'))
    await expect(mallApi.home()).rejects.toThrow()
  })

  it('缺货布尔严格判定：非 true 一律按缺货', async () => {
    post.mockResolvedValueOnce({
      categories: [],
      products: [
        { productId: '1', productName: 'a', categoryId: '2', inStock: false },
        { productId: '3', productName: 'b', categoryId: '2', inStock: 'yes' },
      ],
    })
    const home = await mallApi.home()
    expect(home.products.map(p => p.inStock)).toEqual([false, false])
  })
})

describe('商品详情归一化', () => {
  beforeEach(() => post.mockReset())

  it('后端真实形态：价格与重量都是 Long 字符串，结构化归一', async () => {
    post.mockResolvedValueOnce({
      productId: '9007199254740995',
      productName: '桶装水',
      inStock: true,
      skus: [{
        skuId: '77',
        skuName: '单桶',
        specs: { 规格: '18.9L' },
        salePriceFen: '1800',
        marketPriceFen: '2000',
        weightGram: '19500',
        inStock: true,
      }, {
        skuId: '78',
        skuName: '套票',
        specs: {},
        salePriceFen: 8500,
        weightGram: 0,
        inStock: false,
      }],
    })
    const detail = await mallApi.productDetail('9007199254740995')
    expect(detail.productId).toBe('9007199254740995')
    expect(detail.skus[0].specs).toEqual({ 规格: '18.9L' })
    expect(detail.skus[0].salePriceFen).toBe(1800)
    expect(detail.skus[0].marketPriceFen).toBe(2000)
    expect(detail.skus[0].weightGram).toBe(19500)
    expect(detail.skus[1].skuId).toBe('78')
    expect(detail.skus[1].specs).toEqual({})
    expect(detail.skus[1].inStock).toBe(false)
  })

  it('重量非法 fail-closed：不再静默降 0 渲染假重量', async () => {
    for (const bad of ['bad', -5, 24.5, '', undefined]) {
      post.mockResolvedValueOnce({
        productId: '1',
        productName: 'x',
        inStock: true,
        skus: [{ skuId: '2', skuName: 's', specs: {}, salePriceFen: '1800', weightGram: bad, inStock: true }],
      })
      await expect(mallApi.productDetail('1'), `应拒绝重量 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('规格快照 fail-closed：null/数组/数字值/布尔值/嵌套对象/缺失全部整次拒绝', async () => {
    const badSpecs = [
      null, // 损坏快照被后端旧逻辑放行时的形态
      ['18.9L'], // 数组
      { 规格: 18.9 }, // 数字值强转陷阱
      { 规格: true }, // 布尔值
      { 规格: { 嵌套: 'x' } }, // 嵌套对象
      undefined, // 字段缺失
    ]
    for (const bad of badSpecs) {
      post.mockResolvedValueOnce({
        productId: '1',
        productName: 'x',
        inStock: true,
        skus: [{ skuId: '2', skuName: 's', specs: bad, salePriceFen: '1800', weightGram: '100', inStock: true }],
      })
      await expect(mallApi.productDetail('1'), `应拒绝规格 ${JSON.stringify(bad)}`)
        .rejects
        .toBeInstanceOf(ContractError)
    }
  })

  it('金额非法整体拒绝（宁可失败态不渲染错价）', async () => {
    post.mockResolvedValueOnce({
      productId: '1',
      productName: 'x',
      inStock: true,
      skus: [{ skuId: '2', skuName: 's', specs: {}, salePriceFen: 18.5, weightGram: '100', inStock: true }],
    })
    await expect(mallApi.productDetail('1')).rejects.toBeInstanceOf(ContractError)
  })
})
