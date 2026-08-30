import type { BusinessTime } from '@/api/common'
import type { RechargePayStatus } from '@/api/recharge'
import { rechargeApi } from '@/api/recharge'
import { isDemoMode } from '@/api/runtime'
import { formatBizTime, formatFen } from '@/utils/format'
import { prepayWechat, requestWechatPayment } from '@/utils/wechat-pay'

/**
 * 充值付款与到账确认的唯一实现：充值页（U05）与订单详情页（U06 继续支付）共用，
 * 防止「什么算到账」口径分叉。
 */

/** 由页面注入的交互回调：模块只决定「问什么、说什么」，不关心用哪个组件弹。 */
export interface RechargePayPrompts {
  /** 返回 true 表示用户确认支付。 */
  confirm: (message: string) => Promise<boolean>
  notify: (kind: 'success' | 'info', message: string) => void
  /** 完成态提示由业务页传入；支付状态机本身不猜这是购卡还是已有卡充值。 */
  completedMessage?: string
}

/** 继续支付按钮的显隐判定结果。 */
export interface ContinuePayGate {
  visible: boolean
  /** 不可支付且需要如实告知用户时的说明；为空表示由页面既有状态提示负责。 */
  reason?: string
}

/**
 * 「还能不能付」的展示层判定——纯函数，只读服务端 pay-status；最终判定在服务端（Pay-Sim 前置守卫）。
 * 只有服务端明确判为 `WAITING_PAYMENT` 才可能显示按钮，其余状态码（含 MISMATCH）一律不显示。
 * @param status 服务端 pay-status 结论；缺失表示未取到，一律不显示按钮
 * @param now 业务时区当前时间 yyyyMMddHHmmss（由调用方给出，便于测试）
 */
export function continuePayGate(
  status: RechargePayStatus | null | undefined,
  now: BusinessTime,
): ContinuePayGate {
  if (!status || status.payStatusCode !== 'WAITING_PAYMENT') {
    return { visible: false }
  }
  const expire = status.payExpireTime
  if (!expire || expire.length !== 14) {
    // 截止时间是付款资格的唯一依据，缺了就无从判断按时与否：fail-closed，不显示按钮
    return { visible: false, reason: '无法继续支付，请联系客服核对。' }
  }
  // 同为定长 yyyyMMddHHmmss，字典序即时间序
  if (now > expire) {
    return {
      visible: false,
      reason: `已过付款截止时间（${formatBizTime(expire)}），请重新下单。`,
    }
  }
  return { visible: true }
}

/**
 * 业务时区（Asia/Shanghai，固定 +08:00）当前时间。只用于按钮显隐：设备时钟不可信，
 * 服务端仍按不可变 PAY_EXPIRE_TIME 拒绝——本函数绝不能当付款资格判据。
 */
export function nowBusinessTime(): BusinessTime {
  const shifted = new Date(Date.now() + 8 * 60 * 60 * 1000)
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${shifted.getUTCFullYear()}${pad(shifted.getUTCMonth() + 1)}${pad(shifted.getUTCDate())}`
    + `${pad(shifted.getUTCHours())}${pad(shifted.getUTCMinutes())}${pad(shifted.getUTCSeconds())}`
  )
}

/**
 * 接真链路的付款与到账确认。到账与否只认服务端 pay-status 的结构化状态码——支付接口返回 200
 * 只代表支付事实收到了，不代表权益已入账。
 * @returns 用户取消确认时返回 null；否则返回最后一次读到的服务端状态
 */
export async function payAndSettle(
  orderNo: string,
  amountFen: number,
  prompts: RechargePayPrompts,
): Promise<RechargePayStatus | null> {
  const confirmed = await prompts.confirm(`即将支付 ${formatFen(amountFen)}`)
  if (!confirmed) {
    return null
  }
  let settled: RechargePayStatus
  if (isDemoMode()) {
    // 测试包仅替换外部收银台：Pay-Sim 仍写支付事实、流水并推进真实充值事务。
    await rechargeApi.simulatePay(orderNo)
    settled = await pollUntilSettled(orderNo)
  }
  else {
    const params = await prepayWechat(orderNo)
    const result = await requestWechatPayment(orderNo, params)
    settled = result.status.retryable ? await pollUntilSettled(orderNo) : result.status
    if (result.sheet === 'cancelled' && settled.payStatusCode !== 'COMPLETED') {
      prompts.notify('info', '已取消支付，可稍后在订单详情继续支付')
      return settled
    }
  }
  if (settled.payStatusCode === 'COMPLETED') {
    prompts.notify('success', prompts.completedMessage ?? '充值已到账')
  }
  else {
    // 未到终态一律如实说，不粉饰成成功；用户可在订单详情看到真实状态
    prompts.notify('info', settled.statusMessage || '支付结果处理中，请稍后在订单详情查看')
  }
  return settled
}

/** 轮询到终态：是否继续<b>只由服务端 retryable 决定</b>，前端不自行判断该不该再试。 */
export async function pollUntilSettled(orderNo: string): Promise<RechargePayStatus> {
  const maxAttempts = 8
  let latest = await rechargeApi.getPayStatus(orderNo)
  for (let i = 0; i < maxAttempts && latest.retryable; i++) {
    await new Promise(resolve => setTimeout(resolve, 700))
    latest = await rechargeApi.getPayStatus(orderNo)
  }
  return latest
}
