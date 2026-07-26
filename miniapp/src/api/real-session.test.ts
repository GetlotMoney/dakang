import { beforeEach, describe, expect, it, vi } from 'vitest'
import { withRealSession } from './real-session'
import { getToken, onSessionInvalid, post, setToken } from './request'

/**
 * 复审 P1-5：withRealSession 不得破坏 request.ts 的会话代际保护。
 * 走完整调用栈（withRealSession → post → uni 桩），不 mock request 层：
 * 迟到的旧会话 1401 只向调用方抛 UNAUTHORIZED，不清新 token、不触发全局登出。
 */
describe('withRealSession 会话代际保护 (P1-5 复审)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    onSessionInvalid(() => {})
  })

  /** 可写 storage + 手动放行响应的 uni 桩（与 request.test.ts 同手法）：模拟响应在任意时点迟到。 */
  function stubUniDeferred() {
    const store = new Map<string, string>()
    let release: ((body: unknown) => void) | null = null
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => store.get(key) ?? '',
      setStorageSync: (key: string, value: string) => store.set(key, value),
      removeStorageSync: (key: string) => store.delete(key),
      request: (opt: any) => {
        release = body => opt.success({ data: body })
      },
    })
    return { respond: (body: unknown) => release!(body) }
  }

  it('迟到旧 1401（T1）：新登录已建立时调用方收到 UNAUTHORIZED，新 token 不被清、全局登出不触发', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    const { respond } = stubUniDeferred()

    setToken('token-old')
    const stale = withRealSession(() => post('/stale'))
    // 旧请求未返回期间，新登录已建立（新 token 落库）
    setToken('token-new')
    respond({ code: 1401, msg: '登录状态异常', data: null })

    await expect(stale).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler, '迟到的旧会话 1401 不得触发全局登出').not.toHaveBeenCalled()
    expect(getToken(), '新 token 不得被 withRealSession 本地兜底清掉').toBe('token-new')
  })

  it('对照（T2）：token 未变时 1401 全局失效处理器恰触发一次，调用方收到 UNAUTHORIZED', async () => {
    const handler = vi.fn()
    onSessionInvalid(handler)
    const { respond } = stubUniDeferred()

    setToken('token-current')
    const pending = withRealSession(() => post('/current'))
    respond({ code: 1401, msg: '登录状态异常', data: null })

    await expect(pending).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler, '当前会话失效仍走全局一次性登出，清理语义不回退').toHaveBeenCalledTimes(1)
  })
})
