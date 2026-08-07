/**
 * PC 后台 Demo 的业务导航契约。
 *
 * 本配置定义用户可见的业务入口，不表示 Vue Router 或 RBAC 的父子结构。
 * 完整路由、权限菜单仍可保持嵌套；侧栏按本契约投影为 5+1 一级直达。
 */

export type BusinessModuleKey = 'device' | 'product' | 'user' | 'order'

export interface BusinessModuleNavItem {
  label: string
  path: string
  icon: string
}

export interface BusinessModuleNavigation {
  title: string
  items: readonly BusinessModuleNavItem[]
}

export interface PrimaryBusinessMenuContract {
  key: 'dashboard' | 'station' | BusinessModuleKey
  rootPath: string
  defaultPath: string
  /** 仪表盘父级名称是“仪表盘”，展示时应采用子页“运营总览”的名称。 */
  useLandingTitle?: boolean
}

/**
 * 持久化登录态所对应的后台导航契约版本。
 *
 * 后端菜单与前端组件是一个整体契约；删除/重命名页面后必须升级此值，
 * 使各 origin（尤其 13321 与 8081）中的旧菜单不能继续注册已经不存在的组件。
 */
export const DEMO_SESSION_SCHEMA_VERSION = 'pc-demo-navigation-20260714-v4'

/** 六个核心业务入口的固定落点，不依赖菜单排序推导。 */
export const PRIMARY_BUSINESS_MENU_CONTRACTS: readonly PrimaryBusinessMenuContract[] = [
  {
    key: 'dashboard',
    rootPath: '/dashboard',
    defaultPath: '/dashboard/console',
    useLandingTitle: true
  },
  { key: 'station', rootPath: '/station', defaultPath: '/station/index' },
  { key: 'device', rootPath: '/device', defaultPath: '/device/index' },
  { key: 'product', rootPath: '/product', defaultPath: '/product/water' },
  { key: 'user', rootPath: '/user', defaultPath: '/user/index' },
  { key: 'order', rootPath: '/order', defaultPath: '/order/index' }
]

/** 多页面业务域在页面内部显示的导航。 */
export const BUSINESS_MODULE_NAVIGATION: Record<BusinessModuleKey, BusinessModuleNavigation> = {
  device: {
    title: '设备中控',
    items: [
      { label: '设备档案', path: '/device/index', icon: 'ri:cpu-line' },
      { label: '告警中心', path: '/device/alarm', icon: 'ri:alarm-warning-line' },
      { label: '运维工单', path: '/device/workorder', icon: 'ri:file-list-3-line' },
      { label: '批量控制', path: '/device/batch', icon: 'ri:stack-line' },
      { label: '指令记录', path: '/device/command', icon: 'ri:terminal-box-line' },
      { label: '运营配置', path: '/device/operations', icon: 'ri:settings-3-line' }
    ]
  },
  product: {
    title: '水种套餐',
    items: [
      { label: '水种管理', path: '/product/water', icon: 'ri:drop-line' },
      { label: '套餐管理', path: '/product/package', icon: 'ri:coupon-3-line' }
    ]
  },
  user: {
    title: '用户管理',
    items: [
      { label: 'C端用户', path: '/user/index', icon: 'ri:user-3-line' },
      { label: '水卡与授权', path: '/user/card', icon: 'ri:bank-card-line' },
      { label: '配送员准入', path: '/user/courier', icon: 'ri:user-follow-line' },
      { label: '消息记录', path: '/user/message', icon: 'ri:notification-3-line' }
    ]
  },
  order: {
    title: '订单中心',
    items: [
      { label: '订单查询', path: '/order/index', icon: 'ri:file-search-line' },
      { label: '配送任务', path: '/order/delivery', icon: 'ri:truck-line' },
      { label: '申诉处理', path: '/order/appeal', icon: 'ri:customer-service-2-line' },
      { label: '分账明细', path: '/order/split', icon: 'ri:pie-chart-2-line' },
      { label: '日对账', path: '/order/reconcile', icon: 'ri:scales-3-line' },
      { label: '分账比例配置', path: '/order/splitconfig', icon: 'ri:percent-line' }
    ]
  }
}
