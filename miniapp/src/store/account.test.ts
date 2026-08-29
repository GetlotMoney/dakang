import type { AccountContext } from '@/api/account'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { accountApi } from '@/api/account'
import { useAccountStore } from './account'

function context(capabilities: AccountContext['capabilities']): AccountContext {
  return {
    accountId: '6',
    userId: '6',
    userName: '测试用户',
    userPhone: '15300003971',
    phoneBound: true,
    capabilities,
  }
}

describe('账号上下文刷新', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('冷启动恢复复用缓存，身份变更后的强制刷新重新读取服务端能力', async () => {
    const store = useAccountStore()
    store.establishRealSession(context(['USER_BASE']))
    const request = vi.spyOn(accountApi, 'restoreSession')
      .mockResolvedValue(context(['USER_BASE', 'OWNER_VIEW', 'OWNER_SERVICE']))

    await store.restoreSession()
    expect(request).not.toHaveBeenCalled()

    await store.refreshSession()
    expect(request).toHaveBeenCalledOnce()
    expect(store.context?.capabilities).toContain('OWNER_VIEW')
  })
})
