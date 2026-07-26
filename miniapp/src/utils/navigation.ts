import type { RouteId } from '@/router/routes'
import { buildRouteUrl, findRouteById } from '@/router/routes'

/** 按路由合同跳转；参数校验失败会抛 ContractError，暴露页面间契约破坏。 */
export function goTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.navigateTo({ url: buildRouteUrl(routeId, params) })
}

export function redirectTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.redirectTo({ url: buildRouteUrl(routeId, params) })
}

export function switchToTab(routeId: RouteId) {
  uni.switchTab({ url: buildRouteUrl(routeId) })
}

/** 清空页面栈并直达目标（会话失效登出统一回 C01 用）。 */
export function reLaunchTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.reLaunch({ url: buildRouteUrl(routeId, params) })
}

/**
 * 直达兜底目标不能带必填参数（否则会被路由参数守卫拦下并卡在原页）：
 * 沿 defaultBackTo 链回溯到第一个无必填参数的路由（如 D03→D01、U11→U03）。
 */
function resolveParamFreeRoute(routeId: RouteId) {
  let route = findRouteById(routeId)
  const visited = new Set<RouteId>()
  while (route.params.some(param => param.required) && !visited.has(route.id)) {
    visited.add(route.id)
    route = findRouteById(route.defaultBackTo)
  }
  return route
}

/**
 * 详情页返回：优先返回上级页面栈；直达打开（栈深为 1）时按路由合同的
 * defaultBackTo 落回所属模块（蓝图 §5.1），必填参数路由自动上溯。
 */
export function backOr(fallback: RouteId) {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack({})
    return
  }
  const route = resolveParamFreeRoute(fallback)
  if (route.tab) {
    uni.switchTab({ url: route.path })
  }
  else {
    uni.redirectTo({ url: route.path })
  }
}
