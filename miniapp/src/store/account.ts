import type { AccountContext, CapabilityCode } from '@/api/account'
import { defineStore } from 'pinia'
import { accountApi } from '@/api/account'
import { authApi } from '@/api/auth'
import { clearToken } from '@/api/request'

interface AccountState {
  context: AccountContext | null
  loading: boolean
  restored: boolean
  errorMessage: string | null
}

let restorePromise: Promise<AccountContext> | null = null

export const useAccountStore = defineStore('account', {
  state: (): AccountState => ({
    context: null,
    loading: false,
    restored: false,
    errorMessage: null,
  }),
  getters: {
    hasCapability: state => (capability: CapabilityCode) =>
      state.context?.capabilities.includes(capability) ?? false,
  },
  actions: {
    async restoreSession() {
      if (this.context) {
        return this.context
      }
      if (restorePromise) {
        return restorePromise
      }

      this.loading = true
      restorePromise = accountApi.restoreSession()
      try {
        this.context = await restorePromise
        this.errorMessage = null
        return this.context
      }
      catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '账号会话恢复失败'
        throw error
      }
      finally {
        this.loading = false
        this.restored = true
        restorePromise = null
      }
    },
    /**
     * 正式微信会话落地：登录/绑手机成功后由入口页调用，写入服务端返回的 AccountContext。
     * 会话 token 已由 authApi 写入 storage，这里只记录账号上下文快照。
     */
    establishRealSession(context: AccountContext) {
      this.context = context
      this.restored = true
      this.errorMessage = null
    },
    /**
     * 会话失效（401/UNAUTHORIZED）：清除真实 token 与失效上下文，回到未登录态。
     * 绝不回退 Mock 账号；由入口页据 restored/context 为空重新拉起正式登录（回 C01 正式入口）。
     */
    handleUnauthorized() {
      authApi.clearSession()
      // 一并清除进行中的会话恢复缓存，杜绝失效后旧 Promise 复用。
      restorePromise = null
      this.context = null
      this.restored = false
      this.errorMessage = null
    },
    /**
     * 退出登录（契约占位）：清除本端会话快照后回统一入口。
     * 真实版本清除本地会话与缓存；微信为静默登录，下次进入会自动重新建立会话。
     */
    logout() {
      // 退出同时清理真实会话 token。
      clearToken()
      this.context = null
      this.restored = false
      this.errorMessage = null
    },
  },
})
