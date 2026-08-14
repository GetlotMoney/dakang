import { describe, expect, it } from 'vitest'
import { normalizeRechargeBlock, rechargeBlockFromLegacySnapshot } from './order'
import {
  isRechargeSettled,
  rechargeNotice,
  rechargePaySourceLabel,
  rechargeProcessingStatusLabel,
} from '@/utils/recharge-presentation'

/**
 * 充值订单展示归一化（L2 契约 v2 §9.2）。
 *
 * 这一层的职责是「让页面永远拿不到残缺数据」：要么是完整可信的结构化区块，
 * 要么是明确的 snapshotValid=false。历史上这里出过两类事故——
 * 后端 Long 序列化成字符串被判成非法导致整页 fail-closed，
 * 以及历史快照字段名不同被静默读成 undefined 后渲染出 ¥NaN。两类都在下面钉死。
 */
describe('充值展示归一化', () => {
  const v2 = (over: Record<string, unknown> = {}) => JSON.stringify({
    schemaVersion: 'L2_V2',
    packageId: '1',
    packageName: '100元500升卡',
    payAmount: 10000,
    waterMl: 500000,
    bonusAmount: 0,
    expireDays: 365,
    ...over,
  })

  // 1) 正常 L2_V2 完成单
  it('解析正常 v2 快照', () => {
    const b = rechargeBlockFromLegacySnapshot(v2(), 2)!
    expect(b.snapshotValid).toBe(true)
    expect(b.packageName).toBe('100元500升卡')
    expect(b.payAmountFen).toBe(10000)
    expect(b.waterMl).toBe(500000)
    expect(b.expireDays).toBe(365)
  })

  // 2) 后端结构化区块（接真路径），含 Long 序列化为字符串
  it('解析后端结构化区块，接受字符串形态的 Long', () => {
    const b = normalizeRechargeBlock({
      recharge: {
        snapshotValid: true,
        packageName: '100元500升卡',
        payAmountFen: '10000',
        waterMl: '500000',
        bonusAmountFen: '0',
        expireDays: 365,
        flowMlChange: '500000',
        flowMlAfter: '1470120',
        cardExpireTime: '20280709120000',
        purchaseMode: 'FIRST_CARD',
        cardId: '9007199254740993',
        cardNo: 'VC1234567890ABCD',
      },
    } as never)!
    expect(b.payAmountFen).toBe(10000)
    expect(b.flowMlAfter).toBe(1470120)
    expect(b.cardExpireTime).toBe('20280709120000')
    expect(b.purchaseMode).toBe('FIRST_CARD')
    expect(b.cardId).toBe('9007199254740993')
    expect(b.cardNo).toBe('VC1234567890ABCD')
  })

  it('后端声称 snapshotValid=true 也必须逐字段严格校验', () => {
    const valid: Record<string, unknown> = {
      snapshotValid: true,
      packageName: '100元500升卡',
      payAmountFen: '10000',
      waterMl: '500000',
      bonusAmountFen: '0',
      expireDays: 365,
    }
    const normalize = (recharge: Record<string, unknown>) => normalizeRechargeBlock({ recharge } as never)!
    for (const patch of [
      { packageName: '' },
      { packageName: 'x'.repeat(51) },
      { payAmountFen: 0 },
      { payAmountFen: 1_000_001 },
      { waterMl: -1 },
      { waterMl: 50_000_001 },
      { bonusAmountFen: -1 },
      { bonusAmountFen: 1_000_001 },
      { expireDays: 0 },
      { expireDays: 3651 },
      { expireDays: 1.5 },
      { flowMlAfter: -1 },
      { cardExpireTime: '2028-07-09' },
    ]) {
      expect(normalize({ ...valid, ...patch }).snapshotValid).toBe(false)
    }
    const missingExpire = { ...valid }
    delete missingExpire.expireDays
    expect(normalize(missingExpire).snapshotValid).toBe(false)
    expect(normalizeRechargeBlock({ recharge: [] } as never)).toBeUndefined()
  })

  it('首次购卡新卡字段必须成对且严格，不能拿残缺证据展示发卡成功', () => {
    const valid = {
      snapshotValid: true,
      packageName: '首次购卡套餐',
      payAmountFen: '10000',
      waterMl: '500000',
      bonusAmountFen: '0',
      expireDays: 365,
      purchaseMode: 'FIRST_CARD',
      cardId: '9007199254740993',
      cardNo: 'VC1234567890ABCD',
    }
    const normalize = (recharge: Record<string, unknown>) => normalizeRechargeBlock({ recharge } as never)!
    expect(normalize(valid).snapshotValid).toBe(true)
    expect(normalize({ ...valid, cardId: undefined }).snapshotValid).toBe(false)
    expect(normalize({ ...valid, cardNo: undefined }).snapshotValid).toBe(false)
    expect(normalize({ ...valid, cardId: '01' }).snapshotValid).toBe(false)
    expect(normalize({ ...valid, purchaseMode: 'TOP_UP' }).snapshotValid).toBe(false)
  })

  // 3/4) 历史 Mock 快照用 payAmountFen/bonusAmountFen —— 曾导致 ¥NaN 与赠送额丢失
  it('历史快照的 payAmountFen/bonusAmountFen 必须被识别，不得渲染成 NaN', () => {
    const legacy = JSON.stringify({
      packageId: '1',
      packageName: '50元充值(送5元)',
      payAmountFen: 5000,
      waterMl: 0,
      bonusAmountFen: 500,
      expireDays: null,
    })
    const b = rechargeBlockFromLegacySnapshot(legacy, 2)!
    expect(b.snapshotValid).toBe(true)
    expect(b.payAmountFen).toBe(5000)
    expect(b.bonusAmountFen).toBe(500)
    expect(Number.isNaN(b.payAmountFen)).toBe(false)
    expect(b.expireDays).toBeNull()
  })

  // 5) 非法 JSON
  it('非法 JSON 判为数据异常', () => {
    expect(rechargeBlockFromLegacySnapshot('not-json', 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot('[1,2]', 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot('"x"', 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(undefined, 2)!.snapshotValid).toBe(false)
  })

  // 6) 合法 JSON 但缺字段 —— 曾显示 ¥NaN 并被误判为永久有效
  it('缺必填字段判为数据异常，绝不显示 ¥NaN 或误判永久', () => {
    expect(rechargeBlockFromLegacySnapshot('{}', 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ packageName: undefined }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ payAmount: undefined }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ waterMl: undefined }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ bonusAmount: undefined }), 2)!.snapshotValid).toBe(false)
  })

  // 7) 非法数值 / 负数 / 越界
  it('非法数值一律判为数据异常，不做宽松转换', () => {
    for (const bad of ['abc', '', '1.5', '1e4', '0100', true, {}, [], null]) {
      expect(rechargeBlockFromLegacySnapshot(v2({ payAmount: bad }), 2)!.snapshotValid).toBe(false)
    }
    expect(rechargeBlockFromLegacySnapshot(v2({ payAmount: -1 }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ waterMl: -1 }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ bonusAmount: -1 }), 2)!.snapshotValid).toBe(false)
    // 超出安全整数范围
    expect(rechargeBlockFromLegacySnapshot(v2({ payAmount: '99999999999999999999' }), 2)!.snapshotValid).toBe(false)
  })

  // 8/9) 有效期两种形态
  it('expireDays=null 为永久，正整数为有限，非法值判为异常', () => {
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: null }), 2)!.expireDays).toBeNull()
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: 365 }), 2)!.expireDays).toBe(365)
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: 0 }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: -1 }), 2)!.snapshotValid).toBe(false)
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: '365' }), 2)!.expireDays).toBe(365)
    expect(rechargeBlockFromLegacySnapshot(v2({ expireDays: 1.5 }), 2)!.snapshotValid).toBe(false)
  })

  // 10) bonusAmount=0 是"确实没有赠送"，与"字段丢失"必须区分
  it('赠送额为 0 是合法值，不得与字段缺失混为一谈', () => {
    const zero = rechargeBlockFromLegacySnapshot(v2({ bonusAmount: 0 }), 2)!
    expect(zero.snapshotValid).toBe(true)
    expect(zero.bonusAmountFen).toBe(0)
    // 而字段缺失必须是异常，不能也当成 0
    expect(rechargeBlockFromLegacySnapshot(v2({ bonusAmount: undefined }), 2)!.snapshotValid).toBe(false)
  })

  // 非充值单不产生该区块，避免取水单被塞进充值展示
  it('非充值单不生成充值区块', () => {
    expect(rechargeBlockFromLegacySnapshot(v2(), 1)).toBeUndefined()
  })

  // 后端明确报异常时，前端不得自行"抢救"
  it('后端 snapshotValid=false 时不得回落到本地解析', () => {
    const b = normalizeRechargeBlock({ recharge: { snapshotValid: false, packageName: 'x' } } as never)!
    expect(b.snapshotValid).toBe(false)
    expect(b.packageName).toBeUndefined()
  })

  it('只有完成态且双维流水与快照权益一致才算到账', () => {
    const block = normalizeRechargeBlock({
      recharge: {
        snapshotValid: true,
        packageName: '100元500升卡',
        payAmountFen: 10000,
        waterMl: 500000,
        bonusAmountFen: 0,
        expireDays: 365,
        payStatus: 2,
        paySource: 2,
        processingStatus: 'PROCESSED',
        flowAmountChange: 0,
        flowMlChange: 500000,
        flowAmountAfter: 5500,
        flowMlAfter: 1970120,
      },
    } as never)!
    expect(isRechargeSettled(4, block)).toBe(true)
    expect(isRechargeSettled(2, block)).toBe(false)
    expect(isRechargeSettled(4, { ...block, flowAmountAfter: undefined })).toBe(false)
    expect(isRechargeSettled(4, { ...block, flowMlChange: 499999 })).toBe(false)
    expect(isRechargeSettled(4, { ...block, flowMlAfter: 1 })).toBe(false)
  })

  it('纯余额套餐的 CHANGE 必须等于支付金额加赠送金额', () => {
    const block = normalizeRechargeBlock({
      recharge: {
        snapshotValid: true,
        packageName: '50元充值(送5元)',
        payAmountFen: 5000,
        waterMl: 0,
        bonusAmountFen: 500,
        expireDays: null,
        payStatus: 2,
        paySource: 2,
        processingStatus: 'PROCESSED',
        flowAmountChange: 5500,
        flowMlChange: 0,
        flowAmountAfter: 11000,
        flowMlAfter: 1000,
      },
    } as never)!
    expect(isRechargeSettled(4, block)).toBe(true)
    expect(isRechargeSettled(4, { ...block, flowAmountChange: 5000 })).toBe(false)
    expect(isRechargeSettled(4, { ...block, processingStatus: 'PENDING' })).toBe(false)
  })

  it.each([
    [1, '付款截止时间'],
    [2, '权益处理中'],
    [4, '请联系客服核对'],
    [6, '权益待人工处理'],
  ] as const)('状态%s使用准确文案', (status, text) => {
    expect(rechargeNotice(status, false)?.text).toContain(text)
  })

  it('有完整证据的完成单不再显示提示', () => {
    expect(rechargeNotice(4, true)).toBeNull()
  })

  // 取消/退款/部分退款：订单状态本身已说明结果，不再叠加提示条
  it.each([[5], [7], [8]] as const)('状态%s不显示额外提示', (status) => {
    expect(rechargeNotice(status, false)).toBeNull()
  })

  // 钱收了、权益没落实是资金差错，必须按 danger 呈现。
  // 这一维原本挂在 isRealRecharge 上（非 real 时降级 warning），随 mock 退役一并去掉：
  // 参数没了，但「这一格必须是 danger」的判据得留着，否则改回 warning 不会有任何测试变红。
  it('完成态但未落实权益：语义色必须是 danger', () => {
    expect(rechargeNotice(4, false)?.tone).toBe('danger')
  })

  it('支付来源与事实处理态按本单证据展示', () => {
    expect(rechargePaySourceLabel(1, 1)).toBe('微信支付')
    expect(rechargePaySourceLabel(2, 1)).toBe('模拟支付')
    expect(rechargeProcessingStatusLabel('PROCESSED')).toBe('已处理')
    expect(rechargeProcessingStatusLabel('RECONCILIATION_REQUIRED')).toBe('待人工对账')
  })

  // 来源不明时不许替后端猜一个具体渠道：旧代码在非 real 构建下写死「微信支付」，
  // 用户拿这句去对微信账单会对不上，而页面看起来毫无异常。
  it('来源缺失且非卡支付：只说待核对，不猜渠道', () => {
    expect(rechargePaySourceLabel(undefined, 1)).toBe('支付来源待核对')
    expect(rechargePaySourceLabel(undefined, 1)).not.toBe('微信支付')
  })
})
