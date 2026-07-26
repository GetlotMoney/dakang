import { beforeEach, describe, expect, it } from 'vitest'
import { deviceApi } from './device'
import { orderApi } from './order'
import { scenarioStore } from '@/scenario/store'

describe('water order contract', () => {
  beforeEach(() => {
    scenarioStore.reset()
    scenarioStore.selectAccount('ACCOUNT-USER-001')
  })

  it('creates a completed prototype order with a fixed playback trace and consumes the scan session', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    const detail = await orderApi.createWaterOrder({
      scanSessionId: session.scanSessionId,
      cardId: '1',
      waterTypeId: '1',
      planMl: 10000,
      payWay: 3,
    })

    expect(detail.order.orderStatus).toBe(4)
    expect(detail.order.actualMl).toBe(10000)
    expect(detail.commandStatus).toBe(4)
    expect(detail.trace.map(node => node.node)).toEqual(['created', 'dispatch', 'ack', 'result'])
    expect(detail.order.mockMeta).toMatchObject({
      settlementEffect: 'none',
      deviceEffect: 'none',
      syncedToPc: false,
    })

    await expect(
      orderApi.createWaterOrder({
        scanSessionId: session.scanSessionId,
        cardId: '1',
        waterTypeId: '1',
        planMl: 5000,
        payWay: 3,
      }),
    ).rejects.toMatchObject({ code: 'SCAN_SESSION_EXPIRED' })
  })

  it('rejects volumes above the day limit', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    await expect(
      orderApi.createWaterOrder({
        scanSessionId: session.scanSessionId,
        cardId: '1',
        waterTypeId: '1',
        planMl: 25000,
        payWay: 3,
      }),
    ).rejects.toMatchObject({ code: 'DAY_LIMIT_EXCEEDED' })
  })

  it('blocks ordering on an offline device even with a valid session', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0002-O1')

    const eligibility = await deviceApi.checkWaterEligibility(session.scanSessionId)
    expect(eligibility.availability).toBe('DEVICE_OFFLINE')

    await expect(
      orderApi.createWaterOrder({
        scanSessionId: session.scanSessionId,
        cardId: '1',
        waterTypeId: '1',
        planMl: 10000,
        payWay: 3,
      }),
    ).rejects.toMatchObject({ code: 'DEVICE_BLOCKED' })
  })

  it('reports a missing-card block for the cardless account and rejects order creation', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-003')
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    const eligibility = await deviceApi.checkWaterEligibility(session.scanSessionId)
    expect(eligibility.availability).toBe('AVAILABLE')
    expect(eligibility.cardBlock).toMatchObject({ code: 'CARD_MISSING' })

    await expect(
      orderApi.createWaterOrder({
        scanSessionId: session.scanSessionId,
        cardId: '3',
        waterTypeId: '1',
        planMl: 10000,
        payWay: 3,
      }),
    ).rejects.toMatchObject({ code: 'CARD_NOT_FOUND' })
  })

  it('rejects water orders against the frozen physical card (PC shared key PC-8800001)', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    await expect(
      orderApi.createWaterOrder({
        scanSessionId: session.scanSessionId,
        cardId: '2',
        waterTypeId: '1',
        planMl: 5000,
        payWay: 3,
      }),
    ).rejects.toMatchObject({ code: 'CARD_NOT_USABLE' })
  })

  it('lists my orders newest first and keeps the blueprint scenario keys reachable', async () => {
    const result = await orderApi.listMyOrders({ size: 50 })

    const orderNos = result.list.map(item => item.orderNo)
    const sorted = result.list.every(
      (item, index, list) => index === 0 || list[index - 1].createTime >= item.createTime,
    )
    expect(sorted).toBe(true)
    for (const key of [
      'WO20260712091001',
      'WO20260712091002',
      'WO20260712091003',
      'WO20260712091009',
      'WO20260712091010',
      'WO20260711091011',
      'WO20260711091012',
      'WO20260712091006',
      'WO20260712091007',
      'WO20260712091008',
      'WO20260712091004',
      'WO20260712091005',
    ]) {
      expect(orderNos).toContain(key)
    }
  })

  it('rejects recharge orders on a frozen card and enriches the package snapshot', async () => {
    await expect(
      orderApi.createRechargeOrder({ cardId: '2', packageId: '1', amountFen: 10000 }),
    ).rejects.toMatchObject({ code: 'CARD_NOT_USABLE' })

    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const detail = await orderApi.createRechargeOrder({ cardId: '1', packageId: '1', amountFen: 10000 })
    expect(detail.order.orderStatus).toBe(1)
    expect(JSON.parse(detail.order.packageSnapshot ?? '{}')).toMatchObject({
      packageId: '1',
      payAmountFen: 10000,
      waterMl: 500000,
      expireDays: 365,
    })
  })

  it('rejects an expired sample code with a distinct reason', async () => {
    await expect(deviceApi.resolveScanCode('DK-QR-EXPIRED')).rejects.toMatchObject({
      code: 'QR_EXPIRED',
    })
    await expect(deviceApi.resolveScanCode('RANDOM-STRING')).rejects.toMatchObject({
      code: 'INVALID_QR_CODE',
    })
  })
})
