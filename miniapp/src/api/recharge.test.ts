import { beforeEach, describe, expect, it } from 'vitest'
import {
  buildRechargeCreateBody,
  createRechargeRequestId,
  findLocalRechargeOrder,
  findLocalRechargeOrderForRoute,
  listLocalRechargeOrders,
  normalizePackage,
  normalizePayStatus,
  normalizeRechargeExpireDays,
  rechargeApi,
  resolveRechargePageMode,
  visiblePackagesForMode,
} from './recharge'
import { ContractError } from './common'
import { accountApi } from './account'
import { scenarioStore } from '@/scenario/store'

/**
 * recharge Mock 域契约测试（2026-07-20 收口轮复审 R5）：
 * U10 的“生成待支付原型单→U06 详情→U02 回看”Demo 由本域承载，
 * 最多生成待支付原型单，不伪造支付成功、到账或赠送发放。
 */
describe('recharge mock domain (L2 prototype)', () => {
  beforeEach(() => {
    scenarioStore.reset()
    scenarioStore.selectAccount('ACCOUNT-USER-001')
  })

  it('creates a pending prototype order and never a paid one', async () => {
    const result = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-a',
    })

    expect(result.payStatus).toBe(1)
    expect(result.orderNo).toMatch(/^MR/)
    // payParams 自 L2-T 起为可选（Pay-Sim 链路不产生），Mock 仍必须给出原型标记：
    // 断言写成可选链会让「Mock 忘了打标记」静默通过，那正是演示时最容易骗到人的一种缺失。
    expect(result.payParams).toBeDefined()
    expect(result.payParams!.mode).toBe('prototype')

    const detail = scenarioStore.orderDetails.find(item => item.order.orderNo === result.orderNo)!
    expect(detail.order.orderStatus).toBe(1)
    expect(detail.order.orderType).toBe(2)
  })

  it('is idempotent on the same requestId and issues a new order for a new requestId', async () => {
    const first = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-b',
    })
    const replay = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-b',
    })
    expect(replay.orderNo).toBe(first.orderNo)

    const fresh = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-c',
    })
    expect(fresh.orderNo).not.toBe(first.orderNo)
  })

  it('rejects missing card, frozen card and unknown package', async () => {
    await expect(
      rechargeApi.createRechargeOrder({ cardId: '999', packageId: '1', requestId: 'r1' }),
    ).rejects.toMatchObject({ code: 'CARD_NOT_FOUND' })
    await expect(
      rechargeApi.createRechargeOrder({ cardId: '2', packageId: '1', requestId: 'r2' }),
    ).rejects.toMatchObject({ code: 'CARD_NOT_USABLE' })
    await expect(
      rechargeApi.createRechargeOrder({ cardId: '1', packageId: '999', requestId: 'r3' }),
    ).rejects.toMatchObject({ code: 'PACKAGE_NOT_FOUND' })
  })

  it('rejects the same requestId with different card or package (parameter conflict)', async () => {
    await rechargeApi.createRechargeOrder({ cardId: '1', packageId: '1', requestId: 'req-x' })

    await expect(
      rechargeApi.createRechargeOrder({ cardId: '1', packageId: '2', requestId: 'req-x' }),
    ).rejects.toMatchObject({ code: 'RECHARGE_REQUEST_CONFLICT' })
    await expect(
      rechargeApi.createRechargeOrder({ cardId: '2', packageId: '1', requestId: 'req-x' }),
    ).rejects.toMatchObject({ code: 'RECHARGE_REQUEST_CONFLICT' })

    // 参数一致的重放仍幂等返回原单。
    const replay = await rechargeApi.createRechargeOrder({ cardId: '1', packageId: '1', requestId: 'req-x' })
    expect(replay.orderNo).toMatch(/^MR/)
  })

  it('reports pay status for an own prototype order', async () => {
    const created = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-d',
    })
    const status = await rechargeApi.getPayStatus(created.orderNo)
    expect(status).toMatchObject({ orderNo: created.orderNo, payStatus: 1, orderStatus: 1 })
  })

  it('generates deterministic sequential request ids in mock mode', () => {
    expect(createRechargeRequestId()).toBe('recharge-mock-0001')
    expect(createRechargeRequestId()).toBe('recharge-mock-0002')
    scenarioStore.reset()
    expect(createRechargeRequestId()).toBe('recharge-mock-0001')
  })

  it('replays the request id and scenario sequence together through the C01 reset entry', async () => {
    expect(createRechargeRequestId()).toBe('recharge-mock-0001')
    expect(createRechargeRequestId()).toBe('recharge-mock-0002')
    await accountApi.resetMockScenario()
    expect(createRechargeRequestId()).toBe('recharge-mock-0001')
    expect(scenarioStore.nextOrderSequence()).toBe('9001')
  })

  it('keeps package expiry facts exact for fixed-term and permanent packages', async () => {
    const packages = await rechargeApi.listPackages()
    expect(packages.find(item => item.id === '1')?.expireDays).toBe(365)
    expect(packages.find(item => item.id === '2')?.expireDays).toBeNull()
  })

  it('rejects missing or invalid expiry instead of treating it as permanent', () => {
    expect(normalizeRechargeExpireDays(null)).toBeNull()
    expect(normalizeRechargeExpireDays(365)).toBe(365)
    expect(() => normalizeRechargeExpireDays(undefined)).toThrowError(/有效期/)
    expect(() => normalizeRechargeExpireDays(0)).toThrowError(/有效期/)
    expect(() => normalizeRechargeExpireDays(12.5)).toThrowError(/有效期/)
    expect(() => normalizeRechargeExpireDays('365')).toThrowError(/有效期/)
  })

  it('u10→u06→u02 review: created mock order is readable locally and listed with mock source', async () => {
    const created = await rechargeApi.createRechargeOrder({
      cardId: '1',
      packageId: '1',
      requestId: 'req-review',
    })
    expect(created.orderNo).toMatch(/^MR/)

    // U06 只有显式 source=local-mock 才本地读取；无 source 的深链默认交给真实 order API。
    expect(findLocalRechargeOrderForRoute(created.orderNo)).toBeNull()
    const detail = findLocalRechargeOrderForRoute(created.orderNo, 'local-mock')
    expect(detail).not.toBeNull()
    expect(detail!.order.orderType).toBe(2)
    expect(detail!.order.mockMeta).toBeTruthy()
    expect(detail!.order.recharge).toMatchObject({
      snapshotValid: true,
      packageName: '100元500升卡（原型）',
      payAmountFen: 10000,
      waterMl: 500000,
      bonusAmountFen: 0,
      expireDays: 365,
    })
    const snapshot = JSON.parse(detail!.order.packageSnapshot ?? '{}') as { expireDays?: unknown }
    expect(snapshot.expireDays).toBe(365)

    // U02 回看：本地 mock 充值单在列表中，带 mockMeta 数据源标记
    const list = listLocalRechargeOrders()
    expect(list.some(item => item.order.orderNo === created.orderNo && !!item.order.mockMeta)).toBe(true)
  })

  it('opens both fixed WO recharge snapshots locally only when the route source says local-mock', () => {
    const completed = findLocalRechargeOrderForRoute('WO20260712091004', 'local-mock')
    const refunded = findLocalRechargeOrderForRoute('WO20260712091005', 'local-mock')
    expect(completed?.order.orderStatus).toBe(4)
    expect(refunded?.order.orderStatus).toBe(7)
    expect(completed?.order.recharge).toMatchObject({
      snapshotValid: true,
      packageName: '100元500升卡',
      payAmountFen: 10000,
      waterMl: 500000,
      expireDays: 365,
    })
    expect(refunded?.order.recharge).toMatchObject({
      snapshotValid: true,
      packageName: '50元充值(送5元)',
      payAmountFen: 5000,
      bonusAmountFen: 500,
      expireDays: null,
    })
    expect(JSON.parse(completed?.order.packageSnapshot ?? '{}').expireDays).toBe(365)
    expect(JSON.parse(refunded?.order.packageSnapshot ?? '{}').expireDays).toBeNull()
    expect(findLocalRechargeOrderForRoute('WO20260712091004')).toBeNull()
    expect(findLocalRechargeOrderForRoute('WO20260712091005')).toBeNull()
  })

  it('findLocalRechargeOrder returns null for a foreign or non-recharge orderNo', () => {
    expect(findLocalRechargeOrder('WO-not-exist')).toBeNull()
  })
})

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
