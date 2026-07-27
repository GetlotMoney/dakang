import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAccountStore } from '@/store/account'
import { authApi, parseTestLoginAccounts, readPhoneAuthorization } from './auth'
import * as request from './request'
import { resolveApiMode } from './runtime'

// 请求层整体打桩：拦截 post 与会话写入，聚焦 auth 编排与会话管理，不触真实 HTTP/storage。
vi.mock('./request', () => ({
  post: vi.fn(),
  setToken: vi.fn(),
  setTokenName: vi.fn(),
  clearToken: vi.fn(),
}))

const post = vi.mocked(request.post)
const setToken = vi.mocked(request.setToken)
const setTokenName = vi.mocked(request.setTokenName)
const clearToken = vi.mocked(request.clearToken)

/** uni.login 成功桩：固定返回一次性 code。 */
function stubWechatLoginSuccess(code = 'WX-CODE-1') {
  vi.stubGlobal('uni', { login: (opt: any) => opt.success({ code }) })
}

const BOUND_RESPONSE = {
  result: 'BOUND',
  tokenName: 'dakang-token',
  tokenValue: 'TOKEN-KH-XYZ',
  accountContext: {
    accountId: '10000000000000001',
    userId: '10000000000000001',
    userName: '张三',
    userPhone: '138****5678',
    capabilities: ['USER_BASE'],
  },
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('authApi.login', () => {
  it('已绑定用户直登建会话并返回账号上下文（可直接进首页）', async () => {
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce(BOUND_RESPONSE)

    const result = await authApi.login()

    expect(post).toHaveBeenCalledWith('/mini/auth/login', { code: 'WX-CODE-1' })
    expect(result.stage).toBe('BOUND')
    expect(result.stage === 'BOUND' && result.context.userName).toBe('张三')
  })

  it('会话落地：严格按响应 tokenName/tokenValue 写入（Token 与上下文可保存）', async () => {
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce(BOUND_RESPONSE)

    await authApi.login()

    expect(setTokenName).toHaveBeenCalledWith('dakang-token')
    expect(setToken).toHaveBeenCalledWith('TOKEN-KH-XYZ')
  })

  it('未绑定用户返回一次性绑定票据，不建会话', async () => {
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce({ result: 'UNBOUND', bindTicket: 'TICKET-abc', expiresInSeconds: 300 })

    const result = await authApi.login()

    expect(result.stage).toBe('UNBOUND')
    expect(result.stage === 'UNBOUND' && result.bindTicket).toBe('TICKET-abc')
    // UNBOUND 分支绝不写会话。
    expect(setToken).not.toHaveBeenCalled()
    expect(setTokenName).not.toHaveBeenCalled()
  })

  it('超过 2^53 的十进制字符串 ID 原样保留（不丢精度）', async () => {
    const big = '9007199254740993' // 2^53 + 1，number 无法精确表示
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce({
      ...BOUND_RESPONSE,
      accountContext: { ...BOUND_RESPONSE.accountContext, accountId: big, userId: big },
    })

    const result = await authApi.login()

    expect(result.stage).toBe('BOUND')
    if (result.stage === 'BOUND') {
      expect(result.context.accountId).toBe(big)
      expect(result.context.userId).toBe(big)
    }
  })

  it('数字 ID 响应被拒为契约破坏，且不落 token（精度已在解析时丢失，不得转换放行）', async () => {
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce({
      ...BOUND_RESPONSE,
      accountContext: { ...BOUND_RESPONSE.accountContext, accountId: 123, userId: 456 },
    })

    await expect(authApi.login()).rejects.toMatchObject({ code: 'AUTH_CONTRACT_BROKEN' })
    expect(setToken).not.toHaveBeenCalled()
    expect(setTokenName).not.toHaveBeenCalled()
  })

  it('空串/负数/小数/指数/非数字 ID 一律拒绝', async () => {
    for (const bad of ['', '-1', '1.5', '1e5', 'abc', ' 12', '01']) {
      stubWechatLoginSuccess()
      post.mockResolvedValueOnce({
        ...BOUND_RESPONSE,
        accountContext: { ...BOUND_RESPONSE.accountContext, accountId: bad },
      })
      await expect(authApi.login(), `ID=${JSON.stringify(bad)} 应被拒`).rejects.toMatchObject({
        code: 'AUTH_CONTRACT_BROKEN',
      })
    }
  })

  it('响应不含 openid/session_key（规范化只保留白名单字段）', async () => {
    stubWechatLoginSuccess()
    post.mockResolvedValueOnce({
      ...BOUND_RESPONSE,
      accountContext: { ...BOUND_RESPONSE.accountContext, openid: 'oLEAK', sessionKey: 'sk-LEAK' },
    })

    const result = await authApi.login()

    expect(result.stage).toBe('BOUND')
    if (result.stage === 'BOUND') {
      const serialized = JSON.stringify(result.context).toLowerCase()
      expect(serialized).not.toContain('openid')
      expect(serialized).not.toContain('session')
    }
  })
})

describe('authApi.bindPhone', () => {
  it('绑定手机号成功：以票据+phoneCode 建会话', async () => {
    post.mockResolvedValueOnce(BOUND_RESPONSE)

    const ctx = await authApi.bindPhone('TICKET-abc', 'PHONE-CODE-1')

    expect(post).toHaveBeenCalledWith('/mini/auth/bind-phone', {
      bindTicket: 'TICKET-abc',
      phoneCode: 'PHONE-CODE-1',
    })
    expect(ctx.userName).toBe('张三')
    expect(setToken).toHaveBeenCalledWith('TOKEN-KH-XYZ')
  })
})

describe('手机号授权（getPhoneNumber）', () => {
  it('拒绝/取消授权（无 code）：判为未授权，可恢复重试且不消费票据', () => {
    expect(readPhoneAuthorization({ errMsg: 'getPhoneNumber:fail user deny' }).authorized).toBe(false)
    expect(readPhoneAuthorization({}).authorized).toBe(false)
    expect(readPhoneAuthorization(undefined).authorized).toBe(false)
  })

  it('已授权（有 code）：返回 phoneCode 供绑定', () => {
    const authorization = readPhoneAuthorization({ code: 'PHONE-CODE-1' })
    expect(authorization.authorized).toBe(true)
    expect(authorization.authorized && authorization.phoneCode).toBe('PHONE-CODE-1')
  })
})

describe('会话失效与退出（handleUnauthorized）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('401：清真实 token 与失效上下文，回未登录态', () => {
    const store = useAccountStore()
    store.establishRealSession({
      accountId: '1',
      userId: '1',
      userName: '张三',
      userPhone: '138****5678',
      capabilities: ['USER_BASE'],
    })
    expect(store.context).not.toBeNull()

    store.handleUnauthorized()

    expect(clearToken).toHaveBeenCalled()
    expect(store.context).toBeNull()
    expect(store.restored).toBe(false)
  })

  it('401 绝不回退 Mock：清理后上下文保持为空，不注入任何原型账号', () => {
    const store = useAccountStore()
    store.handleUnauthorized()
    // 未调用 selectMockAccount，上下文仍为空——不存在“掉线自动登原型账号”的旁路。
    expect(store.context).toBeNull()
    expect(store.errorMessage).toBeNull()
  })
})

describe('构建门禁：auth 域模式解析', () => {
  it('默认 Demo 构建：auth 域回退全局 mock（走 C01 原型入口）', () => {
    expect(resolveApiMode('auth', { globalMode: 'mock' })).toBe('mock')
    expect(resolveApiMode('auth', {})).toBe('mock')
  })

  it('auth 与 recharge 互不牵连：各自只认自己那一维的显式覆盖', () => {
    // 硬锁解除后仍要保证两域解耦——L2-T 真机档正是「auth=mock 但 recharge=real」这种组合，
    // 一旦谁牵连谁，要么真机登不进去，要么原型账号被放进真实充值链。
    expect(resolveApiMode('auth', { globalMode: 'mock', authMode: 'real', rechargeMode: 'mock' })).toBe('real')
    expect(resolveApiMode('recharge', { globalMode: 'mock', authMode: 'real', rechargeMode: 'mock' })).toBe('mock')
    expect(resolveApiMode('auth', { globalMode: 'mock', authMode: 'mock', rechargeMode: 'real' })).toBe('mock')
    expect(resolveApiMode('recharge', { globalMode: 'mock', authMode: 'mock', rechargeMode: 'real' })).toBe('real')
  })
})

describe('测试账号白名单解析（parseTestLoginAccounts）', () => {
  it('解析「手机号:标签」清单，标签缺省回退手机号', () => {
    const list = parseTestLoginAccounts('13900001111:演示用户,13999990002', undefined)
    expect(list).toEqual([
      { phone: '13900001111', label: '演示用户' },
      { phone: '13999990002', label: '13999990002' },
    ])
  })

  it('非法手机号一律丢弃：白名单不成为账号枚举面', () => {
    const list = parseTestLoginAccounts('abc:坏号,1390000111:短号,239000011112:非1开头,13900001111:好号', undefined)
    expect(list).toEqual([{ phone: '13900001111', label: '好号' }])
  })

  it('未配置清单时回退单号（旧构建行为不变）', () => {
    expect(parseTestLoginAccounts(undefined, '13900001111'))
      .toEqual([{ phone: '13900001111', label: '13900001111' }])
    expect(parseTestLoginAccounts('', '13900001111'))
      .toEqual([{ phone: '13900001111', label: '13900001111' }])
  })

  it('清单与单号都缺席时为空：不渲染任何测试入口', () => {
    expect(parseTestLoginAccounts(undefined, undefined)).toEqual([])
  })

  it('容忍空白与空项', () => {
    const list = parseTestLoginAccounts(' 13900001111 : 张女士 , ,13999990001 ', undefined)
    expect(list).toEqual([
      { phone: '13900001111', label: '张女士' },
      { phone: '13999990001', label: '13999990001' },
    ])
  })
})
