/**
 * 微信支付 requestPayment 封装（WX-ECO S3）。端上 success/fail/cancel 只描述收银台弹窗如何关闭，
 * 不是支付结果：无论弹窗结果都强制回查服务端，以服务端 pay-status 为唯一业务事实（任务书边界：
 * 禁止用接口调用成功替代支付通知与查单）。支付五参数只从服务端来、原样透传，改任何字段验签必失败。
 */
import { post } from '@/api/request'
import { normalizePayStatus, rechargeEndpoints } from '@/api/recharge'
import type { RechargePayStatus } from '@/api/recharge'
import type { WechatPayParams } from '@/api/wxpay'

export type { WechatPayParams } from '@/api/wxpay'
export { prepayWechat } from '@/api/wxpay'

/** 收银台弹窗的关闭方式。仅供展示层决定提示文案，不参与业务判定。 */
export type PaySheetOutcome = 'completed' | 'cancelled' | 'failed'

export interface WechatPayResult {
  /** 弹窗怎么关掉的（展示用） */
  sheet: PaySheetOutcome
  /** 服务端权威支付状态（业务判定只看这个） */
  status: RechargePayStatus
}

/**
 * 拉起微信收银台并强制回查服务端。`status` 是唯一业务事实；`sheet` 只用于提示文案，二者矛盾时以 status 为准。
 */
export async function requestWechatPayment(
  orderNo: string,
  params: WechatPayParams,
): Promise<WechatPayResult> {
  const sheet = await new Promise<PaySheetOutcome>((resolve) => {
    uni.requestPayment({
      provider: 'wxpay',
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.packageValue,
      signType: params.signType as 'RSA',
      paySign: params.paySign,
      success: () => resolve('completed'),
      fail: (err: { errMsg?: string }) => {
        // 微信把用户取消也归进 fail，用 errMsg 区分；识别不了的一律按 failed 提示
        resolve(err?.errMsg?.includes('cancel') ? 'cancelled' : 'failed')
      },
    } as UniApp.RequestPaymentOptions)
  })

  // 无条件回查：这一步没有任何跳过路径
  const status = await queryAuthoritativeStatus(orderNo)
  return { sheet, status }
}

/** 回查服务端权威支付状态。独立导出：轮询/页面恢复时也用它，不再造第二条查询路径。 */
export async function queryAuthoritativeStatus(orderNo: string): Promise<RechargePayStatus> {
  const raw = await post<Record<string, unknown>>(rechargeEndpoints.payStatus, { orderNo })
  return normalizePayStatus(raw)
}
