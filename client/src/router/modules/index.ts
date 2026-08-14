import { AppRouteRecord } from '@/types/router'
import { dashboardRoutes } from './dashboard'
import { systemRoutes } from './system'
import { logRoutes } from './log'
import { stationRoutes } from './station'
import { deviceRoutes } from './device'
import { productRoutes } from './product'
import { userRoutes } from './user'
import { orderRoutes } from './order'
import { mallRoutes } from './mall'

/**
 * 导出所有模块化路由
 * 技术路由根保持稳定；用户可见工作区由 businessNavigation.ts 按职责投影。
 * 财务结算与配送运营继续复用 /order 下既有授权路由，旧深链和 RBAC 不迁移。
 * 运行时为后端菜单模式，本处静态路由与 api_rbac_menu 保持镜像，两处修改必须同步。
 */
export const routeModules: AppRouteRecord[] = [
  dashboardRoutes,
  stationRoutes,
  deviceRoutes,
  productRoutes,
  userRoutes,
  orderRoutes,
  mallRoutes,
  systemRoutes,
  logRoutes
]
