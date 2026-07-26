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
  isRealRecharge: boolean,
  settled: boolean,
): RechargeNotice | null {
  switch (orderStatus) {
    case 1:
      return {
        tone: 'info',
        text: isRealRecharge
          ? '本订单仍待支付。可在付款截止时间前从本页继续支付；超过截止时间后该订单不再可支付，也不会自动扣款或到账。'
          : '本地原型订单停留在待支付，不会发起真实扣款或到账。',
      }
    case 2:
      return {
        tone: 'info',
        text: '支付成功，权益处理中：到账结果以本页入账信息为准，稍后刷新查看。',
      }
    case 3:
      return {
        tone: 'danger',
        text: '充值订单出现不适用的“出水中”状态，请联系客服核对订单。',
      }
    case 4:
      return settled
        ? null
        : {
            tone: isRealRecharge ? 'danger' : 'warning',
            text: isRealRecharge
              ? '订单标记为已完成，但缺少完整一致的双维入账流水证据，请联系客服核对。'
              : '固定历史快照显示订单已完成；本地 Mock 不包含可证明本次真实入账的完整流水。',
          }
    case 5:
      return {
        tone: 'warning',
        text: '订单已取消，不会继续支付或自动发放充值权益。',
      }
    case 6:
      return {
        tone: 'danger',
        text: '支付成功，权益待人工处理：本单已转人工对账，请联系客服，不会自动重复扣款。',
      }
    case 7:
      return {
        tone: 'warning',
        text: '订单已退款；退款结果及权益回收以订单和流水证据为准。',
      }
    case 8:
      return {
        tone: 'warning',
        text: '订单已部分退款；保留权益和退款金额以订单及流水证据为准。',
      }
  }
}

export function rechargePaySourceLabel(
  paySource: number | undefined,
  payWay: PayWay,
  isRealRecharge: boolean,
): string {
  if (paySource === 1) {
    return '微信支付'
  }
  if (paySource === 2) {
    return 'Pay-Sim 模拟支付'
  }
  if (payWay === 2) {
    return '水卡余额'
  }
  if (payWay === 3) {
    return '水卡水量'
  }
  return isRealRecharge ? '支付来源待核对' : '微信支付（Mock 未接入）'
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
  return PROCESSING_STATUS_LABELS[status] ?? `未知处理态（${status}）`
}
