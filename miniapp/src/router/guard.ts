import type { AccountContext } from '@/api/account'
import type { Plugin } from 'vue'
import { hasCapability } from '@/api/capability'
import store from '@/store'
import { useAccountStore } from '@/store/account'
import { findRouteByPath, parseRouteUrl, validateRouteParams } from './routes'

export type RouteAccessDecision
  = | { allowed: true }
    | { allowed: false, code: 'ROUTE_NOT_REGISTERED' | 'ROUTE_PARAMS_INVALID' | 'SESSION_REQUIRED' | 'CAPABILITY_DENIED', message: string }

export function evaluateRouteAccess(
  url: string,
  context: AccountContext | null,
): RouteAccessDecision {
  const location = parseRouteUrl(url)
  const route = findRouteByPath(location.path)
  if (!route) {
    return { allowed: false, code: 'ROUTE_NOT_REGISTERED', message: '页面不存在' }
  }

  const validation = validateRouteParams(route, location.params)
  if (!validation.valid) {
    return {
      allowed: false,
      code: 'ROUTE_PARAMS_INVALID',
      message: validation.message ?? '页面参数不合法',
    }
  }

  if (route.id === 'C01') {
    return { allowed: true }
  }
  if (!context) {
    return { allowed: false, code: 'SESSION_REQUIRED', message: '请先登录' }
  }
  if (route.requiredCapability && !hasCapability(context, route.requiredCapability)) {
    return {
      allowed: false,
      code: 'CAPABILITY_DENIED',
      message: '当前账号没有该功能权限',
    }
  }
  return { allowed: true }
}

function notifyDenied(message: string) {
  uni.showModal({
    title: '无法进入',
    content: message,
    showCancel: false,
  })
}

let installed = false

export const routeGuard: Plugin = {
  install() {
    if (installed) {
      return
    }
    installed = true

    const accountStore = useAccountStore(store)
    const navigationMethods = ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab'] as const
    for (const method of navigationMethods) {
      uni.addInterceptor(method, {
        invoke(args: { url?: string }) {
          if (!args.url) {
            return false
          }
          const decision = evaluateRouteAccess(args.url, accountStore.context)
          if (decision.allowed) {
            return true
          }
          notifyDenied(decision.message)
          return false
        },
      })
    }
  },
}
