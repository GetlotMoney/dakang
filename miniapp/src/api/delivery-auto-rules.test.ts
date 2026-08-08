import { describe, expect, it, vi } from 'vitest'
import * as request from './request'
import { deliveryApi } from './delivery'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * S2 自动补货规则归一化：后端 Long→字符串序列化收敛；状态白名单（1/2/3）外的
 * 结构性脏行整条丢弃，不进渲染层；可选字段缺省收敛 undefined。
 */
describe('deliveryApi.listAutoRules 归一化', () => {
  it('字符串数值转数值、状态透传、可选字段保留', async () => {
    post.mockResolvedValueOnce([{
      id: '5',
      waterTypeName: '纯净水',
      containerSpec: '10L桶',
      deliveryCount: '2',
      receiveAddress: '幸福小区 3 栋',
      intervalDays: '7',
      nextDueTime: '20260815120000',
      ruleStatus: '1',
      lastResult: '第2期已生成',
      lastResultTime: '20260808120000',
    }])
    const rules = await deliveryApi.listAutoRules()
    expect(rules).toHaveLength(1)
    expect(rules[0].ruleId).toBe('5')
    expect(rules[0].deliveryCount).toBe(2)
    expect(rules[0].intervalDays).toBe(7)
    expect(rules[0].ruleStatus).toBe(1)
    expect(rules[0].nextDueTime).toBe('20260815120000')
    expect(rules[0].lastResult).toBe('第2期已生成')
  })

  it('已取消/停用规则缺省下次时间与结果：收敛 undefined', async () => {
    post.mockResolvedValueOnce([{
      id: '6',
      waterTypeName: '矿泉水',
      containerSpec: '5L桶',
      deliveryCount: 1,
      receiveAddress: 'A 座',
      intervalDays: 14,
      ruleStatus: 3,
    }])
    const rules = await deliveryApi.listAutoRules()
    expect(rules[0].ruleStatus).toBe(3)
    expect(rules[0].nextDueTime).toBeUndefined()
    expect(rules[0].lastResult).toBeUndefined()
  })

  it('状态白名单外的脏行整条丢弃，不进渲染层', async () => {
    post.mockResolvedValueOnce([
      { id: '7', ruleStatus: 99, waterTypeName: 'x', containerSpec: 'x', deliveryCount: 1, receiveAddress: '', intervalDays: 7 },
      { id: '8', ruleStatus: 2, waterTypeName: '纯净水', containerSpec: '10L桶', deliveryCount: 1, receiveAddress: 'B 座', intervalDays: 7 },
    ])
    const rules = await deliveryApi.listAutoRules()
    expect(rules).toHaveLength(1)
    expect(rules[0].ruleId).toBe('8')
  })

  it('空返回收敛空列表', async () => {
    post.mockResolvedValueOnce(null)
    expect(await deliveryApi.listAutoRules()).toEqual([])
  })
})
