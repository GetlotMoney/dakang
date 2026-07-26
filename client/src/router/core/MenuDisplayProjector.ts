/**
 * 菜单导航策略与展示投影。
 *
 * routeList 保留完整技术路由和权限层级；displayMenuList 只描述用户可见导航。
 * 两棵树必须分离，避免 Layout/RBAC 的父子关系强迫侧栏出现无意义层级。
 */
import type { AppRouteRecord } from '@/types/router'
import { PRIMARY_BUSINESS_MENU_CONTRACTS } from '@/config/businessNavigation'

const SYSTEM_ROOT_PATH = '/system'
const LOG_ROOT_PATH = '/system/log'

/** 递归克隆路由，避免展示投影与动态路由注册共享 meta/children 引用。 */
function cloneRoute(route: AppRouteRecord): AppRouteRecord {
  return {
    ...route,
    meta: { ...route.meta },
    children: route.children?.map(cloneRoute)
  }
}

function normalizePath(path?: string): string {
  if (!path) return ''
  return path.startsWith('/') ? path : `/${path}`
}

function findRouteByPath(root: AppRouteRecord, targetPath: string): AppRouteRecord | undefined {
  if (normalizePath(root.path) === targetPath) {
    return root
  }

  for (const child of root.children ?? []) {
    const matched = findRouteByPath(child, targetPath)
    if (matched) return matched
  }

  return undefined
}

function isNavigablePage(route: AppRouteRecord): boolean {
  return Boolean(
    !route.meta.isHide &&
      route.path &&
      (route.component || route.meta.link || route.meta.isIframe === true)
  )
}

function findFirstAuthorizedPage(root: AppRouteRecord): AppRouteRecord | undefined {
  for (const child of root.children ?? []) {
    if (child.children?.length) {
      const nested = findFirstAuthorizedPage(child)
      if (nested) return nested
    }

    if (isNavigablePage(child)) {
      return child
    }
  }

  return undefined
}

function resolveLanding(root: AppRouteRecord, preferredPath: string): AppRouteRecord | undefined {
  const preferred = findRouteByPath(root, preferredPath)
  if (preferred && isNavigablePage(preferred)) {
    return preferred
  }

  return findFirstAuthorizedPage(root)
}

function applyActivePath(route: AppRouteRecord, activePath: string): void {
  route.meta = {
    ...route.meta,
    activePath
  }
  route.children?.forEach((child) => applyActivePath(child, activePath))
}

/**
 * 为完整路由树补齐确定的默认落点和一级菜单激活路径。
 * 返回新树，不修改 MenuProcessor 的输入。
 */
export function applyMenuNavigationPolicy(routeList: AppRouteRecord[]): AppRouteRecord[] {
  const prepared = routeList.map(cloneRoute)

  for (const contract of PRIMARY_BUSINESS_MENU_CONTRACTS) {
    const root = prepared.find((route) => normalizePath(route.path) === contract.rootPath)
    if (!root) continue

    const landing = resolveLanding(root, contract.defaultPath)
    if (!landing) continue

    root.redirect = landing.path
    applyActivePath(root, landing.path)
  }

  return prepared
}

function projectDirectBusinessMenu(root: AppRouteRecord): AppRouteRecord | undefined {
  const contract = PRIMARY_BUSINESS_MENU_CONTRACTS.find(
    (item) => item.rootPath === normalizePath(root.path)
  )
  if (!contract) return undefined

  const landing = resolveLanding(root, contract.defaultPath)
  if (!landing) return undefined

  return {
    ...root,
    path: landing.path,
    component: landing.component,
    redirect: undefined,
    children: undefined,
    meta: {
      ...root.meta,
      title: contract.useLandingTitle ? landing.meta.title : root.meta.title,
      fixedTab: landing.meta.fixedTab,
      activePath: landing.path,
      isFirstLevel: true,
      isDirectMenu: true,
      menuRootPath: contract.rootPath
    }
  }
}

function mergeLogMenuIntoSystem(displayMenuList: AppRouteRecord[]): AppRouteRecord[] {
  const systemIndex = displayMenuList.findIndex(
    (route) => normalizePath(route.path) === SYSTEM_ROOT_PATH
  )
  const logIndex = displayMenuList.findIndex((route) => normalizePath(route.path) === LOG_ROOT_PATH)

  // 仅当两个授权根都存在时合并；只有日志权限的角色仍保留原日志入口。
  if (systemIndex < 0 || logIndex < 0) {
    return displayMenuList
  }

  const system = displayMenuList[systemIndex]
  const log = displayMenuList[logIndex]
  const mergedChildren = [...(system.children ?? []), ...(log.children ?? [])]
  const seenPaths = new Set<string>()

  system.children = mergedChildren.filter((child) => {
    const path = normalizePath(child.path)
    if (!path || seenPaths.has(path)) return false
    seenPaths.add(path)
    return true
  })

  return displayMenuList.filter((_, index) => index !== logIndex)
}

/** 从完整授权路由树生成侧栏/顶部菜单展示树。 */
export function projectDisplayMenu(routeList: AppRouteRecord[]): AppRouteRecord[] {
  const projected = routeList
    .map((route) => {
      const isDirectBusinessRoot = PRIMARY_BUSINESS_MENU_CONTRACTS.some(
        (contract) => contract.rootPath === normalizePath(route.path)
      )

      // 业务根没有任何授权落点时不应展示空入口；非业务根保持原树。
      return isDirectBusinessRoot ? projectDirectBusinessMenu(route) : cloneRoute(route)
    })
    .filter((route): route is AppRouteRecord => Boolean(route))

  return mergeLogMenuIntoSystem(projected)
}

function findDirectMenuForPath(
  currentPath: string,
  displayMenuList: AppRouteRecord[]
): AppRouteRecord | undefined {
  return displayMenuList.find((item) => {
    if (!item.meta.isDirectMenu || !item.meta.menuRootPath) return false
    const rootPath = normalizePath(String(item.meta.menuRootPath))
    return currentPath === rootPath || currentPath.startsWith(`${rootPath}/`)
  })
}

function containsMenuPath(item: AppRouteRecord, targetPath: string): boolean {
  if (normalizePath(item.path) === targetPath) return true
  return item.children?.some((child) => containsMenuPath(child, targetPath)) ?? false
}

/** 将真实页面路径映射到当前应该高亮的展示菜单路径。 */
export function resolveDisplayMenuActivePath(
  currentPath: string,
  displayMenuList: AppRouteRecord[]
): string {
  const normalized = normalizePath(currentPath)
  const directMenu = findDirectMenuForPath(normalized, displayMenuList)
  if (directMenu?.path) {
    return normalizePath(directMenu.path)
  }

  return normalized
}

/** 判断某个顶级展示菜单是否包含当前激活项，供 Top-left/Dual/Mixed 共用。 */
export function isDisplayMenuActive(item: AppRouteRecord, activePath: string): boolean {
  return containsMenuPath(item, normalizePath(activePath))
}
