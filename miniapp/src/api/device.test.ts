import { beforeEach, describe, expect, it } from 'vitest'
import { deviceApi } from './device'
import { scenarioStore } from '@/scenario/store'

describe('owner device data scope', () => {
  beforeEach(() => {
    scenarioStore.reset()
  })

  it('denies owner detail access to a base user', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')

    await expect(deviceApi.getOwnerDeviceDetail('DK-DEV-0001')).rejects.toMatchObject({
      code: 'CAPABILITY_DENIED',
    })
  })

  it('allows an owner to read only devices in the account scope', async () => {
    scenarioStore.selectAccount('ACCOUNT-OWNER-004')

    await expect(deviceApi.getOwnerDeviceDetail('DK-DEV-0001')).resolves.toMatchObject({
      deviceNo: 'DK-DEV-0001',
    })
    await expect(deviceApi.getOwnerDeviceDetail('DK-DEV-9999')).rejects.toMatchObject({
      code: 'DEVICE_ACCESS_DENIED',
    })
  })

  it('denies owner data to a pure courier account (PC ground truth: devices belong to user 4)', async () => {
    scenarioStore.selectAccount('ACCOUNT-WORKER-002')

    await expect(deviceApi.getOwnerDeviceDetail('DK-DEV-0001')).rejects.toMatchObject({
      code: 'CAPABILITY_DENIED',
    })
  })

  it('uses a short scan session instead of a raw device number for water context', async () => {
    const context = await deviceApi.getWaterDeviceContext('SCAN-SESSION-0001')

    expect(context).toMatchObject({
      deviceNo: 'DK-DEV-0001',
      outlet: { outletId: '1' },
    })
  })
})

describe('water eligibility with an explicit cardId (CARD-SCOPE: precheck card = order card)', () => {
  beforeEach(() => {
    scenarioStore.reset()
    scenarioStore.selectAccount('ACCOUNT-USER-001')
  })

  it('checks exactly the requested card: the frozen physical card blocks even though the primary card is fine', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    const eligibility = await deviceApi.checkWaterEligibility(session.scanSessionId, '2')

    expect(eligibility.cardBlock).toMatchObject({ code: 'CARD_FROZEN' })
  })

  it('keeps the healthy primary card unblocked when its id is passed', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')

    const eligibility = await deviceApi.checkWaterEligibility(session.scanSessionId, '1')

    expect(eligibility.cardBlock).toBeUndefined()
  })

  it('answers CARD_NOT_ACCESSIBLE for absent and foreign cards with identical payloads (no existence leak)', async () => {
    const session = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')
    const absent = await deviceApi.checkWaterEligibility(session.scanSessionId, '999')

    // 钱女士（无卡账号）拿张女士的 cardId '1' 预检：必须与「不存在」完全同码同文案
    scenarioStore.selectAccount('ACCOUNT-USER-003')
    const foreignSession = await deviceApi.resolveScanCode('DK-QR-DEV0001-O1')
    const foreign = await deviceApi.checkWaterEligibility(foreignSession.scanSessionId, '1')

    expect(absent.cardBlock).toMatchObject({ code: 'CARD_NOT_ACCESSIBLE' })
    expect(foreign.cardBlock).toEqual(absent.cardBlock)
  })
})
