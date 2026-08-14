import { beforeEach, describe, expect, it, vi } from 'vitest'
import { prepayWechat, requestWechatPayment } from '@/utils/wechat-pay'

/**
 * 微信支付封装（WX-ECO S3）：**无论收银台弹窗怎么关掉都必须回查服务端**。
 *
 * 这条性质是任务书边界的端上对应物：禁止用「接口调用成功」替代支付通知、
 * 查单与账本共键核验。弱网下付成功也可能回 fail、双开下 cancel 后另一处
 * 可能已付——端上回调只配决定提示文案，配不上决定业务事实。
 */

const post = vi.hoisted(() => vi.fn())
vi.mock('@/api/request', () => ({ post }))

const PARAMS = {
  timeStamp: '1755050000',
  nonceStr: 'NONCE',
  packageValue: 'prepay_id=wx20260813prepay01',
  signType: 'RSA',
  paySign: 'SIG',
}

const SERVER_STATUS = {
  orderNo: 'RC0000000000000000000000000001',
  payStatus: 2,
  orderStatus: 2,
  payStatusCode: 'COMPLETED',
}

interface UniPayOptions {
  success: () => void
  fail: (err: { errMsg?: string }) => void
}

/** 安一个可编程的 uni.requestPayment；返回捕获到的调用参数。 */
function stubRequestPayment(behavior: (opts: UniPayOptions) => void): { captured: Record<string, unknown>[] } {
  const captured: Record<string, unknown>[] = []
  ;(globalThis as Record<string, unknown>).uni = {
    requestPayment: (opts: Record<string, unknown>) => {
      captured.push(opts)
      behavior(opts as unknown as UniPayOptions)
    },
  }
  return { captured }
}

beforeEach(() => {
  post.mockReset()
})

describe('prepayWechat', () => {
  it('五参数齐备时原样返回', async () => {
    post.mockResolvedValueOnce({ ...PARAMS })
    const p = await prepayWechat('RC1')
    expect(p).toEqual(PARAMS)
    expect(post).toHaveBeenCalledWith('/mini/wxpay/prepay', { orderNo: 'RC1' })
  })

  it.each(['timeStamp', 'nonceStr', 'packageValue', 'signType', 'paySign'] as const)(
    '缺 %s 拒绝：缺着调 requestPayment 会得到与真实原因无关的报错',
    async (key) => {
      post.mockResolvedValueOnce({ ...PARAMS, [key]: '' })
      await expect(prepayWechat('RC1')).rejects.toThrow(key)
    },
  )
})

describe('requestWechatPayment：弹窗结果与服务端事实', () => {
  it('success 回调后仍回查服务端，status 来自服务端而非弹窗', async () => {
    stubRequestPayment(o => o.success())
    post.mockResolvedValueOnce(SERVER_STATUS)
    const r = await requestWechatPayment('RC1', PARAMS)
    expect(r.sheet).toBe('completed')
    expect(r.status.payStatusCode).toBe('COMPLETED')
    expect(post).toHaveBeenCalledWith('/mini/order/pay-status', { orderNo: 'RC1' })
  })

  it('fail 回调也必须回查——弱网下付成功也可能回 fail，这正是回查存在的理由', async () => {
    stubRequestPayment(o => o.fail({ errMsg: 'requestPayment:fail (detail message)' }))
    post.mockResolvedValueOnce(SERVER_STATUS)
    const r = await requestWechatPayment('RC1', PARAMS)
    expect(r.sheet).toBe('failed')
    // 弹窗说没付成，服务端说已到账：以服务端为准
    expect(r.status.payStatus).toBe(2)
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('用户取消（errMsg 带 cancel）归为 cancelled，且同样回查', async () => {
    stubRequestPayment(o => o.fail({ errMsg: 'requestPayment:fail cancel' }))
    post.mockResolvedValueOnce({ ...SERVER_STATUS, payStatus: 1, orderStatus: 1, payStatusCode: 'WAITING_PAYMENT' })
    const r = await requestWechatPayment('RC1', PARAMS)
    expect(r.sheet).toBe('cancelled')
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('五参数原样透传给 requestPayment，不拼不改（改任何字段验签必失败）', async () => {
    const { captured } = stubRequestPayment(o => o.success())
    post.mockResolvedValueOnce(SERVER_STATUS)
    await requestWechatPayment('RC1', PARAMS)
    expect(captured[0]).toMatchObject({
      provider: 'wxpay',
      timeStamp: PARAMS.timeStamp,
      nonceStr: PARAMS.nonceStr,
      package: PARAMS.packageValue,
      signType: 'RSA',
      paySign: PARAMS.paySign,
    })
  })
})
