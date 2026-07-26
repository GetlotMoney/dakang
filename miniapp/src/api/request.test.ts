import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getToken, onSessionInvalid, post, SESSION_INVALID_CODES, setToken } from './request'

/** 用固定响应体桩住 uni.request 与 storage；聚焦 post 的会话失效链（复审 P1-2）。 */
function stubUni(responseBody: unknown) {
  vi.stubGlobal('uni', {
    getStorageSync: () => '',
    setStorageSync: () => {},
    removeStorageSync: () => {},
    request: (opt: any) => opt.success({ data: responseBody }),
  })
}

describe('post 会话失效链 (1401~1405)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    onSessionInvalid(() => {}) // 复位为无副作用处理器
  })

  it('1401~1405 全部触发全局失效处理器且抛统一 UNAUTHORIZED', async () => {
    for (const code of [...SESSION_INVALID_CODES]) {
      const handler = vi.fn()
      onSessionInvalid(handler)
      stubUni({ code, msg: '会话失效', data: null })

      await expect(post('/any'), `code=${code}`).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
      expect(handler, `code=${code} 应触发一次全局失效处理`).toHaveBeenCalledTimes(1)
    }
  })

  it('成功码 0 返回 data，不触发失效处理', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    stubUni({ code: 0, msg: 'ok', data: { hello: 'world' } })

    await expect(post('/any')).resolves.toEqual({ hello: 'world' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('普通业务拒绝码（非 140x）不触发失效处理', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    stubUni({ code: 5401, msg: '二维码无效', data: null })

    await expect(post('/any')).rejects.toMatchObject({ code: 'INVALID_QR_CODE' })
    expect(handler).not.toHaveBeenCalled()
  })
})

/**
 * E2E-03 验收 P1-5：新旧会话响应竞态。请求发起时冻结携带的 token 作为会话代际，
 * 1401 全局登出只在「失败响应对应的会话仍是当前会话」时执行——
 * 旧页面滞留请求的迟到 1401 不得清掉新登录建立的会话。
 */
describe('会话代际判定 (P1-5)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    onSessionInvalid(() => {})
  })

  /** 可写 storage + 手动放行响应的 uni 桩：模拟响应在任意时点迟到。 */
  function stubUniDeferred() {
    const store = new Map<string, string>()
    const sentHeaders: Array<Record<string, string>> = []
    let release: ((body: unknown) => void) | null = null
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => store.get(key) ?? '',
      setStorageSync: (key: string, value: string) => store.set(key, value),
      removeStorageSync: (key: string) => store.delete(key),
      request: (opt: any) => {
        sentHeaders.push(opt.header)
        release = body => opt.success({ data: body })
      },
    })
    return { sentHeaders, respond: (body: unknown) => release!(body) }
  }

  it('旧 token 请求延迟返回 1401：新登录已建立时不清新会话（丢弃登出动作）', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    const { sentHeaders, respond } = stubUniDeferred()

    setToken('token-old')
    const stale = post('/stale')
    expect(sentHeaders[0]['dakang-token']).toBe('token-old')

    // 旧请求未返回期间，新登录已建立（新 token 落库）
    setToken('token-new')
    respond({ code: 1401, msg: '登录状态异常', data: null })

    await expect(stale).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler, '迟到的旧会话 1401 不得触发全局登出').not.toHaveBeenCalled()
    expect(getToken(), '新 token 不得被清除').toBe('token-new')
  })

  it('当前会话请求返回 1401：仍正常触发一次全局登出（对照，既有语义不变）', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    const { respond } = stubUniDeferred()

    setToken('token-current')
    const pending = post('/current')
    respond({ code: 1401, msg: '登录状态异常', data: null })

    await expect(pending).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler).toHaveBeenCalledTimes(1)
  })
})
