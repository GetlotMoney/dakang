import type { OrderStatus, PayWay, RechargeDetailBlock } from '@/api/order'

export type RechargeNoticeTone = 'info' | 'warning' | 'danger'

export interface RechargeNotice {
  text: string
  tone: RechargeNoticeTone
}

function hasCompleteFlowEvidence(block: RechargeDetailBlock): boolean {
  const values = [
    block.flowAmountChange,
    block.flowMlChange,
    block.flowAmountAfter,
    block.flowMlAfter,
  ]
  return values.every(value => Number.isSafeInteger(value) && value! >= 0)
}

/**
 * 充值到账只由「完成态 + 完整双维流水 + 快照权益与 CHANGE 一致」共同证明。
 * 任一字段缺失或矛盾都 fail-closed，不能因为某一个 AFTER 恰好存在就宣称到账。
 */
export function isRechargeSettled(orderStatus: OrderStatus, block?: RechargeDetailBlock): boolean {
  if (orderStatus !== 4
    || !block?.snapshotValid
    || block.payStatus !== 2
    || (block.paySource !== 1 && block.paySource !== 2)
    || block.processingStatus !== 'PROCESSED'
    || !hasCompleteFlowEvidence(block)) {
    return false
  }
  const payAmount = block.payAmountFen
  const waterMl = block.waterMl
  const bonusAmount = block.bonusAmountFen
  if (payAmount == null || waterMl == null || bonusAmount == null) {
    return false
  }
  const expectedMl = waterMl
  const expectedAmount = waterMl > 0 ? bonusAmount : payAmount + bonusAmount
  return block.flowMlChange === expectedMl
    && block.flowAmountChange === expectedAmount
    && block.flowMlAfter! >= block.flowMlChange
    && block.flowAmountAfter! >= block.flowAmountChange
}

/** 按订单真实状态生成说明；完成/退款历史快照绝不复用待支付文案。 */
export function rechargeNotice(
  orderStatus: OrderStatus,
  settled: boolean,
): RechargeNotice | null {
  switch (orderStatus) {
    case 1:
      return {
        tone: 'info',
        text: '请在付款截止时间前完成支付，逾期不可再支付。',
      }
    case 2:
      return {
        tone: 'info',
        text: '支付成功，权益处理中，请稍后刷新查看。',
      }
    case 3:
      return {
        tone: 'danger',
        text: '订单状态异常，请联系客服核对。',
      }
    case 4:
      // 钱已收、权益没落实——这是 danger，不是 warning
      return settled
        ? null
        : {
            tone: 'danger',
            text: '到账结果待核实，请联系客服核对。',
          }
    case 5:
      return null
    case 6:
      return {
        tone: 'danger',
        text: '支付成功，权益待人工处理，请联系客服。',
      }
    case 7:
    case 8:
      return null
  }
}

export function rechargePaySourceLabel(
  paySource: number | undefined,
  payWay: PayWay,
): string {
  if (paySource === 1) {
    return '微信支付'
  }
  if (paySource === 2) {
    return '模拟支付'
  }
  if (payWay === 2) {
    return '水卡余额'
  }
  if (payWay === 3) {
    return '水卡水量'
  }
  // 兜底不猜：来源不明的支付不得写成「微信支付」，用户拿去对账单会对不上
  return '支付来源待核对'
}

const PROCESSING_STATUS_LABELS: Record<string, string> = {
  WAITING_PAYMENT: '等待支付',
  PENDING: '待处理',
  PROCESSING: '处理中',
  PROCESSED: '已处理',
  RETRY_WAIT: '等待重试',
  RECONCILIATION_REQUIRED: '待人工对账',
}

export function rechargeProcessingStatusLabel(status?: string): string | undefined {
  if (!status) {
    return undefined
  }
  return PROCESSING_STATUS_LABELS[status] ?? '未知状态'
}
