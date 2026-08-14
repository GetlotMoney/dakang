/**
 * 菜单导航策略与展示投影。
 *
 * routeList 保留完整技术路由和权限层级；displayMenuList 只描述用户可见导航。
 * 两棵树必须分离，避免 Layout/RBAC 的父子关系强迫侧栏出现无意义层级。
 */
import type { AppRouteRecord } from '@/types/router'
import {
  PRIMARY_BUSINESS_MENU_CONTRACTS,
  type PrimaryBusinessMenuContract
} from '@/config/businessNavigation'

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

function resolveLanding(
  root: AppRouteRecord,
  contract: PrimaryBusinessMenuContract
): AppRouteRecord | undefined {
  const preferred = findRouteByPath(root, contract.defaultPath)
  const preferredBelongsToContract =
    !contract.ownedPaths?.length || contract.ownedPaths.includes(contract.defaultPath)
  if (preferredBelongsToContract && preferred && isNavigablePage(preferred)) {
    return preferred
  }

  // 分区投影只能降级到本工作区内的授权页面，不能串到同一技术根下的其他职责域。
  if (contract.ownedPaths?.length) {
    for (const path of contract.ownedPaths) {
      const candidate = findRouteByPath(root, path)
      if (candidate && isNavigablePage(candidate)) return candidate
    }
    return undefined
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

function applyOwnedActivePath(
  root: AppRouteRecord,
  contract: PrimaryBusinessMenuContract,
  activePath: string
): void {
  if (!contract.ownedPaths?.length) {
    applyActivePath(root, activePath)
    return
  }

  for (const path of contract.ownedPaths) {
    const ownedRoute = findRouteByPath(root, path)
    if (ownedRoute) applyActivePath(ownedRoute, activePath)
  }
}

/**
 * 为完整路由树补齐确定的默认落点和一级菜单激活路径。
 * 返回新树，不修改 MenuProcessor 的输入。
 */
export function applyMenuNavigationPolicy(routeList: AppRouteRecord[]): AppRouteRecord[] {
  const prepared = routeList.map(cloneRoute)

  const rootPaths = [...new Set(PRIMARY_BUSINESS_MENU_CONTRACTS.map((item) => item.rootPath))]
  for (const rootPath of rootPaths) {
    const root = prepared.find((route) => normalizePath(route.path) === rootPath)
    if (!root) continue

    const contracts = PRIMARY_BUSINESS_MENU_CONTRACTS.filter(
      (contract) => contract.rootPath === rootPath
    )
    const primaryLanding = contracts
      .map((contract) => resolveLanding(root, contract))
      .find((landing): landing is AppRouteRecord => Boolean(landing))
    if (!primaryLanding) continue

    root.redirect = primaryLanding.path
    root.meta = { ...root.meta, activePath: primaryLanding.path }

    for (const contract of contracts) {
      const landing = resolveLanding(root, contract)
      if (landing) applyOwnedActivePath(root, contract, landing.path)
    }
  }

  return prepared
}

function projectDirectBusinessMenu(
  root: AppRouteRecord,
  contract: PrimaryBusinessMenuContract
): AppRouteRecord | undefined {
  const landing = resolveLanding(root, contract)
  if (!landing) return undefined

  return {
    ...root,
    name: `${String(root.name || 'BusinessRoot')}-${contract.key}`,
    path: landing.path,
    component: landing.component,
    redirect: undefined,
    children: undefined,
    meta: {
      ...root.meta,
      title:
        contract.displayTitle ?? (contract.useLandingTitle ? landing.meta.title : root.meta.title),
      icon: contract.displayIcon ?? root.meta.icon,
      fixedTab: landing.meta.fixedTab,
      activePath: landing.path,
      isFirstLevel: true,
      isDirectMenu: true,
      menuRootPath: contract.rootPath,
      menuOwnedPaths: contract.ownedPaths ? [...contract.ownedPaths] : undefined
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
    .flatMap((route) => {
      const contracts = PRIMARY_BUSINESS_MENU_CONTRACTS.filter(
        (contract) => contract.rootPath === normalizePath(route.path)
      )
      if (!contracts.length) return [cloneRoute(route)]

      // 同一技术根可投影成多个职责工作区；无任何授权落点的工作区不展示。
      return contracts
        .map((contract) => projectDirectBusinessMenu(route, contract))
        .filter((item): item is AppRouteRecord => Boolean(item))
    })
    .filter((route): route is AppRouteRecord => Boolean(route))

  return mergeLogMenuIntoSystem(projected)
}

function findDirectMenuForPath(
  currentPath: string,
  displayMenuList: AppRouteRecord[]
): AppRouteRecord | undefined {
  return displayMenuList.find((item) => {
    if (!item.meta.isDirectMenu) return false
    const ownedPaths = Array.isArray(item.meta.menuOwnedPaths)
      ? item.meta.menuOwnedPaths.map((path) => normalizePath(String(path)))
      : []
    if (ownedPaths.length) return ownedPaths.includes(currentPath)
    if (!item.meta.menuRootPath) return false
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
