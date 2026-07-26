import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { registerSessionInvalidHandler } from '@/api/session-guard'
import { useAccountStore } from '@/store/account'

// 捕获注册到 request 层的失效处理器；并桩住 request 会话原语与 reLaunch 导航（vi.hoisted 供已提升的 mock 工厂引用）。
const { capturedRef, reLaunchTo } = vi.hoisted(() => ({
  capturedRef: { current: null as null | (() => void) },
  reLaunchTo: vi.fn(),
}))

vi.mock('@/api/request', () => ({
  onSessionInvalid: (fn: () => void) => {
    capturedRef.current = fn
  },
  clearToken: vi.fn(),
  post: vi.fn(),
  setToken: vi.fn(),
  setTokenName: vi.fn(),
}))

vi.mock('@/utils/navigation', () => ({ reLaunchTo }))

describe('全局会话失效处理器 (P1-2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    capturedRef.current = null
    reLaunchTo.mockClear()
  })

  it('触发后：清 AccountContext + reLaunch 回 C01，且不落 Mock 账号', () => {
    registerSessionInvalidHandler()
    expect(capturedRef.current).toBeTypeOf('function')

    const store = useAccountStore()
    store.establishRealSession({
      accountId: '1',
      userId: '1',
      userName: '张三',
      userPhone: '138****5678',
      capabilities: ['USER_BASE'],
    })
    expect(store.context).not.toBeNull()

    capturedRef.current!()

    expect(store.context).toBeNull()
    expect(store.restored).toBe(false)
    expect(reLaunchTo).toHaveBeenCalledWith('C01')
  })

  it('并发多次触发只 reLaunch 一次（合并同一轮失效）', () => {
    registerSessionInvalidHandler()
    const store = useAccountStore()
    store.establishRealSession({
      accountId: '1',
      userId: '1',
      userName: '张三',
      userPhone: '138****5678',
      capabilities: ['USER_BASE'],
    })

    capturedRef.current!()
    capturedRef.current!()
    capturedRef.current!()

    expect(reLaunchTo).toHaveBeenCalledTimes(1)
    expect(store.context).toBeNull()
  })
})
