import { beforeEach, describe, expect, it, vi } from 'vitest'
import { payAndSettle } from './recharge-pay'

const simulatePay = vi.hoisted(() => vi.fn())
const getPayStatus = vi.hoisted(() => vi.fn())
const isDemoMode = vi.hoisted(() => vi.fn())
const prepayWechat = vi.hoisted(() => vi.fn())
const requestWechatPayment = vi.hoisted(() => vi.fn())

vi.mock('@/api/recharge', () => ({ rechargeApi: { simulatePay, getPayStatus } }))
vi.mock('@/api/runtime', () => ({ isDemoMode }))
vi.mock('@/utils/wechat-pay', () => ({ prepayWechat, requestWechatPayment }))

const completed = {
  orderNo: 'RC-DEMO-1',
  payStatus: 2,
  orderStatus: 4,
  payStatusCode: 'COMPLETED',
  retryable: false,
}

const prompts = {
  confirm: vi.fn(async () => true),
  notify: vi.fn(),
}

beforeEach(() => {
  vi.clearAllMocks()
  prompts.confirm.mockResolvedValue(true)
})

describe('充值支付模式隔离', () => {
  it('老板测试包只调用 Pay-Sim 并按服务端状态确认到账', async () => {
    isDemoMode.mockReturnValue(true)
    simulatePay.mockResolvedValue({ resultCode: 'SUCCESS' })
    getPayStatus.mockResolvedValue(completed)

    await expect(payAndSettle('RC-DEMO-1', 5000, prompts)).resolves.toMatchObject(completed)

    expect(simulatePay).toHaveBeenCalledWith('RC-DEMO-1')
    expect(prepayWechat).not.toHaveBeenCalled()
    expect(prompts.notify).toHaveBeenCalledWith('success', '充值已到账')
  })

  it('正式包只拉起微信支付，绝不回落 Pay-Sim', async () => {
    isDemoMode.mockReturnValue(false)
    prepayWechat.mockResolvedValue({
      timeStamp: '1',
      nonceStr: 'nonce',
      packageValue: 'prepay_id=1',
      signType: 'RSA',
      paySign: 'sign',
    })
    requestWechatPayment.mockResolvedValue({ sheet: 'completed', status: completed })

    await expect(payAndSettle('RC-REAL-1', 5000, prompts)).resolves.toMatchObject(completed)

    expect(prepayWechat).toHaveBeenCalledWith('RC-REAL-1')
    expect(simulatePay).not.toHaveBeenCalled()
  })
})
