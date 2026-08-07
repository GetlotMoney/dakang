import { describe, expect, it } from 'vitest'
import {
  buildRechargeCreateBody,
  normalizePackage,
  normalizePayStatus,
  resolveRechargePageMode,
  visiblePackagesForMode,
} from './recharge'
import { ContractError } from './common'

/**
 * recharge Mock 域契约测试（2026-07-20 收口轮复审 R5）：
 * U10 的“生成待支付原型单→U06 详情→U02 回看”Demo 由本域承载，
 * 最多生成待支付原型单，不伪造支付成功、到账或赠送发放。
 */

describe('real recharge adapter normalization (L2-T)', () => {
  // 后端把所有 Long 序列化成字符串以避免 JS 53 位精度丢失。
  // 此前这里只认 number，套餐页拿到后端**正确**响应仍整页 fail-closed，
  // 报「packageId 缺失或非法」——接口通了但一个套餐都渲染不出来，真机上直接卡死在充值第一步。
  it('accepts Long fields serialized as decimal strings', () => {
    const pkg = normalizePackage({
      packageId: '1',
      packageName: '100元500升卡',
      payAmountFen: '10000',
      waterMl: '500000',
      bonusAmountFen: '0',
      unitPriceSnap: '20.00',
      expireDays: 365,
      purchasable: true,
    })
    expect(pkg.id).toBe('1')
    expect(pkg.payAmountFen).toBe(10000)
    expect(pkg.waterMl).toBe(500000)
    expect(pkg.bonusAmountFen).toBe(0)
    expect(pkg.expireDays).toBe(365)
  })

  // 真机实测抓到的：'500' 被「不是 number 就取 0」吞掉，
  // 「50元充值(送5元)」在页面上显示成「赠送 ¥0.00」——用户看到的赠送额与实际到账不符。
  it('parses a string bonusAmountFen instead of silently zeroing it', () => {
    const pkg = normalizePackage({
      packageId: '2',
      packageName: '50元充值(送5元)',
      payAmountFen: '5000',
      waterMl: '0',
      bonusAmountFen: '500',
      unitPriceSnap: '0',
      expireDays: null,
      purchasable: true,
    })
    expect(pkg.bonusAmountFen).toBe(500)
  })

  it('still accepts plain numbers (兼容两种线上格式)', () => {
    const pkg = normalizePackage({
      packageId: 2,
      packageName: '50元充值',
      payAmountFen: 5000,
      waterMl: 0,
      bonusAmountFen: 500,
      unitPriceSnap: '0',
      expireDays: null,
      purchasable: false,
    })
    expect(pkg.id).toBe('2')
    expect(pkg.payAmountFen).toBe(5000)
    expect(pkg.expireDays).toBeNull()
  })

  // 放宽解析不等于放弃校验：垃圾值仍必须拒绝，否则 0 元套餐会被渲染出来让用户点
  it('rejects malformed numeric fields instead of coercing them', () => {
    const base = {
      packageId: '1',
      packageName: 'x',
      payAmountFen: '10000',
      waterMl: '0',
      bonusAmountFen: '0',
      unitPriceSnap: '0',
      expireDays: null,
      purchasable: true,
    }
    expect(() => normalizePackage({ ...base, payAmountFen: '' })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, payAmountFen: '1.5' })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, payAmountFen: '1e4' })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, payAmountFen: '0100' })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, payAmountFen: null })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, packageId: '0' })).toThrow(ContractError)
    expect(() => normalizePackage({ ...base, packageId: 'abc' })).toThrow(ContractError)
  })

  // retryable 缺失必须取 false：默认可重试会让服务端已判 MISMATCH 的单子被无限轮询
  it('defaults retryable to false when absent', () => {
    const s = normalizePayStatus({ orderNo: 'RC1', payStatus: 1, orderStatus: 1 })
    expect(s.retryable).toBe(false)
    expect(normalizePayStatus({ orderNo: 'RC1', payStatus: 1, orderStatus: 1, retryable: true }).retryable).toBe(true)
    expect(normalizePayStatus({ orderNo: 'RC1', payStatus: 1, orderStatus: 1, retryable: 'true' }).retryable).toBe(false)
  })
})

describe('l2-a U10 首次购卡契约', () => {
  const card = {
    cardId: '9007199254740993',
    cardNo: 'VC001',
    cardStatus: 1 as const,
    balanceFen: 0,
    balanceMl: 0,
  }
  const pkg = (id: string, purchasable: boolean) => ({
    id,
    packageName: `套餐${id}`,
    payAmountFen: 100,
    waterMl: 1000,
    bonusAmountFen: 0,
    unitPriceSnap: '100.00',
    expireDays: 30,
    packageStatus: 1 as const,
    purchasable,
  })

  it('无卡进入 purchase 态，有卡保持 recharge 态', () => {
    expect(resolveRechargePageMode(null)).toBe('purchase')
    expect(resolveRechargePageMode(card)).toBe('recharge')
  })

  it('purchase 态只显示 purchasable=true 的套餐', () => {
    expect(visiblePackagesForMode([pkg('1', true), pkg('2', false)], 'purchase').map(item => item.id))
      .toEqual(['1'])
  })

  it('purchase 套餐全不可购时保持空态，recharge 态不改变原列表', () => {
    const rows = [pkg('1', false), pkg('2', false)]
    expect(visiblePackagesForMode(rows, 'purchase')).toEqual([])
    expect(visiblePackagesForMode(rows, 'recharge').map(item => item.id)).toEqual(['1', '2'])
  })

  it('首次购卡请求体彻底省略 cardId，已有卡充值保留字符串 Long', () => {
    expect(buildRechargeCreateBody({ packageId: '3', requestId: 'request-1' })).toEqual({
      packageId: '3',
      requestId: 'request-1',
    })
    expect(buildRechargeCreateBody({
      cardId: '9007199254740993',
      packageId: '3',
      requestId: 'request-2',
    })).toEqual({
      cardId: '9007199254740993',
      packageId: '3',
      requestId: 'request-2',
    })
  })

  it('畸形 cardId 不得被静默解释为首次购卡', () => {
    expect(() => buildRechargeCreateBody({ cardId: '', packageId: '3', requestId: 'request-3' }))
      .toThrow(ContractError)
  })

  it('套餐可购标识缺失或非布尔值时 fail-closed', () => {
    const raw = {
      packageId: '3',
      packageName: '新卡套餐',
      payAmountFen: '10000',
      waterMl: '500000',
      bonusAmountFen: '0',
      unitPriceSnap: '20.00',
      expireDays: 365,
    }
    expect(() => normalizePackage(raw)).toThrow(ContractError)
    expect(() => normalizePackage({ ...raw, purchasable: 'true' })).toThrow(ContractError)
    expect(normalizePackage({ ...raw, purchasable: false }).purchasable).toBe(false)
  })
})
