/**
 * 微信支付 API（WX-ECO S3）。服务端调用与端点声明collected在 api 层，
 * 与其余 api 模块同构；收银台编排在 utils/wechat-pay.ts。
 */
import { post } from './request'

export const wxpayEndpoints = {
  prepay: '/mini/wxpay/prepay',
} as const

/** 服务端签发的 requestPayment 五参数，字段名与微信 API 一字不差。 */
export interface WechatPayParams {
  timeStamp: string
  nonceStr: string
  packageValue: string
  signType: string
  paySign: string
}

const REQUIRED_KEYS = ['timeStamp', 'nonceStr', 'packageValue', 'signType', 'paySign'] as const

/** 向服务端发起 JSAPI 下单，换取 requestPayment 五参数。 */
export async function prepayWechat(orderNo: string): Promise<WechatPayParams> {
  const raw = await post<Record<string, unknown>>(wxpayEndpoints.prepay, { orderNo })
  // 五个字段缺一不可：缺着调 requestPayment 会得到一个与真实原因无关的报错
  for (const key of REQUIRED_KEYS) {
    if (typeof raw[key] !== 'string' || (raw[key] as string).length === 0) {
      throw new Error(`支付参数不完整（${key}）`)
    }
  }
  return raw as unknown as WechatPayParams
}
