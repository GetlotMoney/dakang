/**
 * 售后执行入口的纯策略层。
 *
 * 本文件不依赖 HTTP、Store 或组件，台账页与申诉页共用同一份动作、权限和可执行性分流，
 * 同时允许 Node 直接运行回归测试。
 */

/** 售后权限码。与后端 @SaCheckPermission 及 api_rbac_menu 逐字一致。 */
export const AfterSalePerms = {
  /** 台账查询、详情、取水核账预览 */
  query: 'order:aftersale:query',
  /** 执行卡内返还、生成补送、核账确认 */
  handle: 'order:aftersale:handle',
  /** 发起机构退款、充值/购卡退款预览与受理 */
  refund: 'order:aftersale:refund'
} as const

/** 售后来源(1370)：下拉选项一律走字典，本常量只用于代码分支判断。 */
export const AfterSaleSourceType = {
  DELIVERY_CANCEL: 1,
  DELIVERY_APPEAL: 2,
  WATER_ABNORMAL: 3,
  RECHARGE_REFUND: 4
} as const

/** 售后动作类型(1371)：决定该行走哪个执行入口，后端各入口自带类型闸。 */
export const AfterSaleActionType = {
  CARD_REFUND: 1,
  CARD_COMPENSATE: 2,
  GATEWAY_REFUND: 3,
  RESEND: 4
} as const

/** 售后执行状态(1372)。合法迁移边只认后端状态机，前端只做按钮前置提示。 */
export const AfterSaleActionStatus = {
  PENDING: 1,
  PROCESSING: 2,
  SUCCESS: 3,
  RETRY_WAIT: 4,
  RECONCILIATION_REQUIRED: 5,
  TERMINATED: 6
} as const

export type RechargeRefundEntryMode = 'entitlement' | 'unsettled'

/** 充值订单在订单中心的独立财务入口；其它类型与终态不显示退款操作。 */
export function rechargeRefundEntryMode(order: {
  orderType?: number
  orderStatus?: number
}): RechargeRefundEntryMode | null {
  if (order.orderType !== 2) return null
  if (order.orderStatus === 4) return 'entitlement'
  if (order.orderStatus === 6) return 'unsettled'
  return null
}

export type AfterSaleStrategyCode =
  | 'PRODUCT_ONLY'
  | 'SERVICE_FEE_ONLY'
  | 'PRODUCT_AND_SERVICE'
  | 'RESEND'
  | 'REJECT'

const AFTER_SALE_STRATEGY_LABELS: Record<AfterSaleStrategyCode, string> = {
  PRODUCT_ONLY: '仅退水品',
  SERVICE_FEE_ONLY: '仅退配送费',
  PRODUCT_AND_SERVICE: '水品与配送费同退',
  RESEND: '补送',
  REJECT: '驳回'
}

const ASSET_REFUND_STRATEGIES: ReadonlySet<string> = new Set([
  'PRODUCT_ONLY',
  'SERVICE_FEE_ONLY',
  'PRODUCT_AND_SERVICE'
])

/** 策略码中文名；未知码原样带出，便于运营核查数据而非看到错误映射。 */
export function afterSaleStrategyLabel(code?: string): string {
  if (!code) return '-'
  return AFTER_SALE_STRATEGY_LABELS[code as AfterSaleStrategyCode] || `未知策略（${code}）`
}

/** 策略码为空时由后端最终判定；前端只据此决定是否展示资金执行入口。 */
export function afterSaleRefundsAssets(strategyCode?: string): boolean {
  if (!strategyCode) return true
  return ASSET_REFUND_STRATEGIES.has(strategyCode)
}

export type AfterSaleExecuteMode = 'execute' | 'refund' | 'resend'

/** 台账行对应的执行入口描述。 */
export interface AfterSaleExecuteEntry {
  mode: AfterSaleExecuteMode
  label: string
  /** 本入口对应的后端权限码；页面不得按 mode 另写一份分流。 */
  requiredPermission: (typeof AfterSalePerms)[keyof typeof AfterSalePerms]
  /** 仅为前置提示，最终判定是后端状态 CAS。 */
  enabled: boolean
  disabledReason: string
}

/** 页面只提供权限查询函数，具体权限码由入口策略统一绑定。 */
export function canUseAfterSaleExecuteEntry(
  entry: AfterSaleExecuteEntry,
  hasPermission: (permission: string) => boolean
): boolean {
  return hasPermission(entry.requiredPermission)
}

/**
 * 根据服务端下发的动作类型、策略与状态生成唯一执行入口。
 * 本函数不判断金额或额度，资金准入仍由后端完成。
 */
export function afterSaleExecuteEntry(item: {
  actionType?: number
  strategyCode?: string
  actionStatus?: number
  sourceType?: number
}): AfterSaleExecuteEntry | null {
  // 充值退款在订单中心一次完成受理与发起；台账只展示事实，不提供重复发起入口。
  if (item.sourceType === AfterSaleSourceType.RECHARGE_REFUND) return null
  const claimable =
    item.actionStatus === AfterSaleActionStatus.PENDING ||
    item.actionStatus === AfterSaleActionStatus.RETRY_WAIT
  if (
    item.actionType === AfterSaleActionType.CARD_REFUND ||
    item.actionType === AfterSaleActionType.CARD_COMPENSATE
  ) {
    if (!afterSaleRefundsAssets(item.strategyCode)) return null
    return {
      mode: 'execute',
      label: '执行返还',
      requiredPermission: AfterSalePerms.handle,
      enabled: claimable,
      disabledReason: '仅待执行、可重试的动作可执行'
    }
  }
  if (item.actionType === AfterSaleActionType.GATEWAY_REFUND) {
    return {
      mode: 'refund',
      label: '发起退款',
      requiredPermission: AfterSalePerms.refund,
      enabled: claimable,
      disabledReason: '仅待执行、可重试的动作可退款'
    }
  }
  if (item.actionType === AfterSaleActionType.RESEND) {
    return {
      mode: 'resend',
      label: '生成补送',
      requiredPermission: AfterSalePerms.handle,
      enabled: item.actionStatus === AfterSaleActionStatus.PENDING,
      disabledReason: '仅待执行的动作可生成补送'
    }
  }
  return null
}
