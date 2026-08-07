/**
 * 安全与合规 API（B23 / REQ-024、REQ-066）。
 *
 * 审计导出申请已接真实后端（/api/auditExport/*）：申请事实、筛选快照、申请人与状态
 * 全部落 ws_audit_export_task，与操作日志、领域事件同源可查，刷新不再丢失。
 *
 * 【能力边界】文件生成依赖对象存储，尚未接入。任务只能停在「待生成」，
 * fileDigest/expireTime 恒为空，页面不提供下载入口——不做指向空文件的假按钮。
 */

import request from '@/utils/http'

/**
 * 状态值与后端 ComplianceEnum.AuditExportStatus 一致（字典 1382 是同一套值的后台配置副本，
 * 服务端并不在运行时查字典）。文案一律用服务端下发的 taskStatusName，前端不自行映射。
 */
export enum AuditExportStatus {
  Pending = 1,
  Running = 2,
  Failed = 3,
  Done = 4,
  Expired = 5
}

export interface AuditExportTaskItem {
  id: string
  taskNo: string
  exportScope: string
  filterSummary: string
  applyReason: string
  maskingRule: string
  taskStatus: AuditExportStatus
  /** 服务端按字典口径下发的状态名称 */
  taskStatusName: string
  applyByName: string
  createTime: string
  failureReason?: string
  /** 对象存储接入前恒为空 */
  fileDigest?: string
  /** 同 fileDigest，无真实文件即为空 */
  expireTime?: string
}

export interface AuditExportApplyForm {
  /** 必须取自 AUDIT_EXPORT_SCOPES；服务端按白名单校验，自由文本会被拒绝 */
  exportScope: string[]
  operatorKeyword?: string
  businessKeyword?: string
  /** yyyyMMddHHmmss */
  startTime: string
  /** yyyyMMddHHmmss */
  endTime: string
  applyReason: string
}

/** 可申请的导出范围，与服务端 WsAuditExportTaskServiceImpl.ALLOWED_SCOPES 同源 */
export const AUDIT_EXPORT_SCOPES = [
  '操作日志',
  '登录日志',
  '订单追溯',
  '设备事件',
  '指令回执',
  '领域事件'
] as const

export function fetchAuditExportTaskPage(params: { current: number; size: number }) {
  return request.post<{ list: AuditExportTaskItem[]; total: string }>({
    url: '/api/auditExport/pageData',
    data: params
  })
}

export function fetchCreateAuditExportTask(data: AuditExportApplyForm) {
  return request.post<AuditExportTaskItem>({
    url: '/api/auditExport/apply',
    data
  })
}

export function fetchRetryAuditExportTask(id: string) {
  return request.post<AuditExportTaskItem>({
    url: '/api/auditExport/retry',
    data: { id }
  })
}
