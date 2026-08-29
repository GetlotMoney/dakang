import type { RouteId } from '@/router/routes'
import { buildRouteUrl, findRouteById } from '@/router/routes'

/**
 * 页面没有切换时必须给用户明确反馈。这里用 uni 原生提示，确保路由守卫、
 * 自定义 Tabbar 等页面组件之外也能显示，不依赖某个页面是否挂载 wd-toast。
 */
function showNavigationFailure(message = '页面打开失败，请重试') {
  uni.showToast({ title: message, icon: 'none' })
}

/** 按路由合同跳转；参数校验失败会抛 ContractError，暴露页面间契约破坏。 */
export function goTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.navigateTo({
    url: buildRouteUrl(routeId, params),
    fail: () => showNavigationFailure(),
  })
}

export function redirectTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.redirectTo({
    url: buildRouteUrl(routeId, params),
    fail: () => showNavigationFailure(),
  })
}

export function switchToTab(routeId: RouteId) {
  uni.switchTab({
    url: buildRouteUrl(routeId),
    fail: () => showNavigationFailure('页面切换失败，请重试'),
  })
}

/** 清空页面栈并直达目标（会话失效登出统一回 C01 用）。 */
export function reLaunchTo(routeId: RouteId, params: Record<string, string | number | undefined> = {}) {
  uni.reLaunch({
    url: buildRouteUrl(routeId, params),
    fail: () => showNavigationFailure(),
  })
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
 * defaultBackTo 落回所属模块，必填参数路由自动上溯。
 */
export function backOr(fallback: RouteId) {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack({
      // 页面栈异常时仍尝试合同兜底页，只有兜底也失败才提示用户。
      fail: () => openFallback(fallback),
    })
    return
  }
  openFallback(fallback)
}

function openFallback(fallback: RouteId) {
  const route = resolveParamFreeRoute(fallback)
  if (route.tab) {
    uni.switchTab({
      url: route.path,
      fail: () => showNavigationFailure('返回失败，请重试'),
    })
  }
  else {
    uni.redirectTo({
      url: route.path,
      fail: () => showNavigationFailure('返回失败，请重试'),
    })
  }
}
