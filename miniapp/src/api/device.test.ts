import { describe, expect, it, vi } from 'vitest'
import * as request from './request'
import { deviceApi } from './device'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * D-421 R1 归一化回归：钱包两个新增字段。后端全局 Long→字符串序列化，
 * pendingSplitFen 到达前端是数值字符串；earliestUnfreezeTime 无在途时缺省。
 * 缺省/脏值必须收敛为 0/undefined，不许把 NaN 或半截时间放进渲染层。
 */
describe('deviceApi.getOwnerWallet 在途分润归一化', () => {
  it('字符串金额转数值、解冻时间原样保留', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '200',
      frozenFen: '0',
      pendingSplitFen: '700',
      earliestUnfreezeTime: '20260808120000',
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.pendingSplitFen).toBe(700)
    expect(wallet.earliestUnfreezeTime).toBe('20260808120000')
    expect(wallet.balanceFen).toBe(200)
  })

  it('旧后端无这两个字段：金额收敛 0、时间收敛 undefined（灰度兼容）', async () => {
    post.mockResolvedValueOnce({ balanceFen: '0', frozenFen: '0', flows: [] })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.pendingSplitFen).toBe(0)
    expect(wallet.earliestUnfreezeTime).toBeUndefined()
  })

  it('解冻时间为非字符串脏值：收敛 undefined，金额不受牵连', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '0',
      frozenFen: '0',
      pendingSplitFen: '300',
      earliestUnfreezeTime: 12345,
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.pendingSplitFen).toBe(300)
    expect(wallet.earliestUnfreezeTime).toBeUndefined()
  })

  // R1 复验 P2：金额只认安全整数（number 或规范十进制整数字符串），四类脏值全收敛 0
  it('小数字符串（"1.5"）不是合法金额分：收敛 0', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '1.5',
      frozenFen: 2.5,
      pendingSplitFen: '0.99',
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.balanceFen).toBe(0)
    expect(wallet.frozenFen).toBe(0)
    expect(wallet.pendingSplitFen).toBe(0)
  })

  it('科学计数法（"1e3"）不是规范十进制串：收敛 0', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '1e3',
      frozenFen: '0',
      pendingSplitFen: '2E5',
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.balanceFen).toBe(0)
    expect(wallet.pendingSplitFen).toBe(0)
  })

  it('非法字符串（"abc"）绝不产出 NaN：收敛 0', async () => {
    post.mockResolvedValueOnce({
      balanceFen: 'abc',
      frozenFen: '12x',
      pendingSplitFen: 'NaN',
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.balanceFen).toBe(0)
    expect(Number.isNaN(wallet.balanceFen)).toBe(false)
    expect(wallet.frozenFen).toBe(0)
    expect(wallet.pendingSplitFen).toBe(0)
  })

  it('超过安全整数范围：拒绝舍入值，收敛 0', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '99999999999999999999',
      frozenFen: Number.MAX_SAFE_INTEGER + 2,
      pendingSplitFen: String(Number.MAX_SAFE_INTEGER),
      flows: [],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.balanceFen).toBe(0)
    expect(wallet.frozenFen).toBe(0)
    expect(wallet.pendingSplitFen).toBe(Number.MAX_SAFE_INTEGER)
  })

  it('余额负值=脏数据收敛 0；流水金额合法为负（提现冻结）必须保留符号', async () => {
    post.mockResolvedValueOnce({
      balanceFen: '-100',
      frozenFen: '500',
      pendingSplitFen: '0',
      flows: [{ flowType: '3', amountFen: '-500', afterFen: '200', orderNo: undefined, createTime: '20260807120000' }],
    })
    const wallet = await deviceApi.getOwnerWallet()
    expect(wallet.balanceFen).toBe(0)
    expect(wallet.flows[0].amountFen).toBe(-500)
    expect(wallet.flows[0].afterFen).toBe(200)
  })
})
