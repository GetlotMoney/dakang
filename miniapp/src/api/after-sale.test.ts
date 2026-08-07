import { describe, expect, it } from 'vitest'
import {
  AFTER_SALE_STATUS_LABELS,
  afterSaleAmountRows,
  afterSaleResultText,
  canShowCancelEntry,
  isResendOrderNo,
  isResendTaskNo,
  normalizeAfterSaleProgress,
  normalizeCancelEligibility,
  REFUND_SOURCE_LABELS,
  refundSourceText,
} from './after-sale'
import type { AfterSaleProgress } from './after-sale'

/**
 * E2E-04 包E：小程序售后只读契约。
 *
 * 这一层钉死四件事，每一件都对应一次真实事故的形状：
 * 1. 未回签/未落账前不得出现"完成/成功"——补送单生成 ≠ 补送完成；
 * 2. 金额与水量只格式化服务端原值，合计取服务端字段，**前端不求和**；
 * 3. 退款来源照实展示（当前为 Refund-Sim），不写成"微信退款"；
 * 4. 取消资格与补送标识都只认服务端的显式值，缺省一律隐藏。
 */

function progress(over: Partial<AfterSaleProgress> = {}): AfterSaleProgress {
  return {
    afterSaleNo: 'AS20260729000001',
    sourceType: 2,
    actionType: 1,
    actionStatus: 1,
    ...over,
  }
}

describe('normalizeAfterSaleProgress', () => {
  it('接受 number 与十进制字符串双形态的 Long，产出契约形状', () => {
    const result = normalizeAfterSaleProgress({
      afterSaleNo: 'AS20260729000001',
      sourceType: 2,
      actionType: 2,
      actionStatus: 3,
      approvedCount: '2',
      refundProductFen: '1300',
      refundServiceFen: 0,
      refundProductMl: null,
      refundAmount: 1300,
      refundSource: null,
      finishTime: '20260729103000',
    })
    expect(result).toEqual({
      afterSaleNo: 'AS20260729000001',
      sourceType: 2,
      actionType: 2,
      actionStatus: 3,
      approvedCount: 2,
      refundProductFen: 1300,
      refundServiceFen: 0,
      refundProductMl: undefined,
      refundAmount: 1300,
      refundSource: undefined,
      resendOrderNo: undefined,
      resendTaskNo: undefined,
      finishTime: '20260729103000',
    })
  })

  it('缺售后号或枚举越界时整块隐藏，不渲染半截售后结论', () => {
    expect(normalizeAfterSaleProgress(undefined)).toBeUndefined()
    expect(normalizeAfterSaleProgress({ sourceType: 1, actionType: 1, actionStatus: 1 })).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ actionStatus: 7 }))).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ actionType: 0 }))).toBeUndefined()
    // 包D-5 把售后来源上界放到 4充值退款，故越界样本改用 5；
    // 4 现在是合法来源，继续要求它被隐藏等于要求充值退款的进度在用户侧不可见
    expect(normalizeAfterSaleProgress(progressRaw({ sourceType: 5 }))).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ sourceType: 4 }))).toBeDefined()
    expect(normalizeAfterSaleProgress(progressRaw({ refundSource: 3 }))).toBeUndefined()
  })

  it('额度畸形（小数/负数/非数字）整块隐藏，绝不当作 0 展示', () => {
    expect(normalizeAfterSaleProgress(progressRaw({ refundAmount: 12.5 }))).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ refundProductFen: -1 }))).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ refundProductMl: '1e3' }))).toBeUndefined()
    expect(normalizeAfterSaleProgress(progressRaw({ refundServiceFen: '007' }))).toBeUndefined()
  })

  function progressRaw(over: Record<string, unknown> = {}) {
    return {
      afterSaleNo: 'AS20260729000001',
      sourceType: 2,
      actionType: 1,
      actionStatus: 1,
      ...over,
    }
  }
})

describe('afterSaleResultText', () => {
  it('补送未回签时只说待补送/处理中，不出现完成或成功', () => {
    for (const actionStatus of [1, 2, 4] as const) {
      const text = afterSaleResultText(progress({ actionType: 4, actionStatus })).text
      expect(text).not.toContain('完成')
      expect(text).not.toContain('成功')
    }
    expect(afterSaleResultText(progress({ actionType: 4, actionStatus: 1 })).text).toContain('待补送')
    // 补送单已生成同样不算完成：回签才是完成条件（包C completeOnSigned）
    expect(
      afterSaleResultText(progress({ actionType: 4, actionStatus: 2, resendOrderNo: 'WD0001' })).text,
    ).toContain('等待配送签收')
  })

  it('只有 actionStatus=3 才允许出现完成文案', () => {
    expect(afterSaleResultText(progress({ actionType: 4, actionStatus: 3 })).text).toContain('补送已完成')
    expect(afterSaleResultText(progress({ actionType: 3, actionStatus: 3 })).text).toContain('返还已完成')
    expect(afterSaleResultText(progress({ actionType: 3, actionStatus: 2 })).text).toContain('处理中')
    expect(afterSaleResultText(progress({ actionType: 1, actionStatus: 5 })).text).toContain('人工对账')
    expect(AFTER_SALE_STATUS_LABELS[3]).toBe('已完成')
  })
})

describe('afterSaleAmountRows', () => {
  it('逐项格式化服务端原值，合计取服务端字段而不是三项求和', () => {
    // 故意让 refundAmount 与三项之和不等：页面必须原样展示服务端合计，
    // 一旦这里改成前端求和，本用例立刻变红。
    const rows = afterSaleAmountRows(progress({
      refundProductFen: 1300,
      refundServiceFen: 200,
      refundAmount: 1300,
    }))
    expect(rows).toEqual([
      { label: '水品返还金额', value: '¥13.00' },
      { label: '配送费返还', value: '¥2.00' },
      { label: '返还合计', value: '¥13.00' },
    ])
  })

  it('未下发的额度不补零占位；水量按毫升原值格式化', () => {
    expect(afterSaleAmountRows(progress())).toEqual([])
    expect(afterSaleAmountRows(progress({ refundProductMl: 10000 }))).toEqual([
      { label: '水品返还水量', value: '10L' },
    ])
  })
})

describe('退款来源（R0-8）', () => {
  it('模拟退款通道必须照实展示为模拟通道，不得写成微信退款', () => {
    expect(REFUND_SOURCE_LABELS[2]).toContain('模拟退款通道')
    expect(REFUND_SOURCE_LABELS[2]).not.toContain('微信')
    // 内部通道代号不进用户界面
    expect(REFUND_SOURCE_LABELS[2]).not.toContain('Refund-Sim')
    expect(refundSourceText(progress({ actionType: 3, refundSource: 2 }))).toContain('模拟退款通道')
  })

  it('未下发来源时不展示来源行，也不默认成任一通道', () => {
    expect(refundSourceText(progress({ actionType: 3 }))).toBeUndefined()
    expect(refundSourceText(progress({ actionType: 3, refundSource: 1 }))).toBe(REFUND_SOURCE_LABELS[1])
  })
})

describe('取消资格与补送标识', () => {
  it('只有服务端显式 true 才显示取消入口', () => {
    expect(canShowCancelEntry(normalizeCancelEligibility(true))).toBe(true)
    expect(canShowCancelEntry(normalizeCancelEligibility({ allowed: true }))).toBe(true)
    expect(canShowCancelEntry(normalizeCancelEligibility({ allowed: false, reason: '任务已被接单' }))).toBe(false)
    // 未下发 / 真值伪装一律不显示入口
    expect(normalizeCancelEligibility(undefined)).toBeUndefined()
    expect(normalizeCancelEligibility({})).toBeUndefined()
    expect(normalizeCancelEligibility('true')).toBeUndefined()
    expect(normalizeCancelEligibility(1)).toBeUndefined()
    expect(canShowCancelEntry(undefined)).toBe(false)
  })

  it('保留服务端拒绝说明，页面不另造文案', () => {
    expect(normalizeCancelEligibility({ allowed: false, reason: '任务已被接单' }))
      .toEqual({ allowed: false, reason: '任务已被接单' })
  })

  it('补送标识只在两个服务端值相等时成立', () => {
    const resend = progress({ actionType: 4, resendOrderNo: 'WD0002', resendTaskNo: 'DT0002' })
    expect(isResendOrderNo(resend, 'WD0002')).toBe(true)
    expect(isResendOrderNo(resend, 'WD0001')).toBe(false)
    expect(isResendTaskNo(resend, 'DT0002')).toBe(true)
    // 无售后区块或空业务号时不得误标
    expect(isResendOrderNo(undefined, 'WD0002')).toBe(false)
    expect(isResendOrderNo(progress(), '')).toBe(false)
    expect(isResendTaskNo(progress(), '')).toBe(false)
  })
})
