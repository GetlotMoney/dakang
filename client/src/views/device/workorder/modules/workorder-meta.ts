/**
 * 工单展示元数据（E2E-05 包D）：来源/状态的展示口径单一出处，
 * 列表、详情抽屉、建单弹窗共用，防止各处复制后漂移。
 * 状态语义以后端 WorkOrderTransitions 为准，这里只管显示颜色。
 */

/** 来源：下标 = sourceType - 1 */
export const WORK_ORDER_SOURCE_LABELS = ['告警转入', '机主申报', '后台创建'] as const

/** 1待确认 2待分配 3处理中 4待复核 5已关闭 6已驳回 */
export function workOrderStatusTagType(status: number) {
  switch (status) {
    case 1:
      return 'warning'
    case 2:
      return 'primary'
    case 3:
      return 'primary'
    case 4:
      return 'warning'
    case 5:
      return 'success'
    case 6:
      return 'danger'
    default:
      return 'info'
  }
}

/** 操作端口(1364) → 轨迹显示名 */
export const ACTOR_PORTAL_LABELS: Record<number, string> = {
  1: '公司后台',
  2: '用户端',
  3: '机主端',
  4: '配送端',
  5: '渠道端',
  6: '系统',
  7: '设备'
}
