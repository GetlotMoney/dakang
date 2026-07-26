/**
 * 安全与合规 Demo API 契约。
 *
 * Demo 阶段仅实现 PC 责任范围内的导出申请与任务追踪，不生成文件或虚构备份结果。
 * 接入真实服务时保留入参与状态机契约，并替换为 /system/audit-export/* 接口。
 */

import { recordDemoAuditEvent } from '@/api/demo-audit'

export type AuditExportTaskStatus = '待生成' | '生成中' | '失败' | '已生成' | '已过期'

export interface AuditExportTaskItem {
  id: number
  taskNo: string
  exportScope: string
  filterSummary: string
  applyReason: string
  maskingRule: string
  taskStatus: AuditExportTaskStatus
  createByName: string
  createTime: string
  failureReason?: string
  fileDigest?: string
  expireTime?: string
}

export interface AuditExportApplyForm {
  exportScope: string[]
  operatorKeyword?: string
  businessKeyword?: string
  startTime: string
  endTime: string
  applyReason: string
}

const tasks: AuditExportTaskItem[] = [
  {
    id: 1,
    taskNo: 'AUD-EXP-20260714-001',
    exportScope: '操作日志、订单追溯',
    filterSummary: '2026-07-10 00:00 至 2026-07-14 12:00；订单 WO20260712',
    applyReason: '核对异常取水订单的操作与状态变化记录',
    maskingRule: '手机号中间四位脱敏；身份信息不导出',
    taskStatus: '失败',
    createByName: '超级管理员',
    createTime: '20260714121000',
    failureReason: 'Demo 未接入文件生成器与对象存储'
  },
  {
    id: 2,
    taskNo: 'AUD-EXP-20260714-002',
    exportScope: '设备事件、指令回执',
    filterSummary: '2026-07-14 00:00 至 2026-07-14 14:00；设备 DK-DEV-0002',
    applyReason: '复核离线设备的指令超时与故障告警证据',
    maskingRule: '手机号中间四位脱敏；身份信息不导出',
    taskStatus: '待生成',
    createByName: '超级管理员',
    createTime: '20260714140500'
  }
]

const delay = <T>(value: T): Promise<T> =>
  new Promise((resolve) => setTimeout(() => resolve(value), 180))

export function fetchAuditExportTaskList(): Promise<AuditExportTaskItem[]> {
  return delay(tasks.map((item) => ({ ...item })))
}

export function fetchCreateAuditExportTask(
  data: AuditExportApplyForm
): Promise<AuditExportTaskItem> {
  const item: AuditExportTaskItem = {
    id: Math.max(0, ...tasks.map((task) => task.id)) + 1,
    taskNo: `AUD-EXP-20260714-${String(tasks.length + 1).padStart(3, '0')}`,
    exportScope: data.exportScope.join('、'),
    filterSummary: `${data.startTime} 至 ${data.endTime}${data.operatorKeyword ? `；操作人 ${data.operatorKeyword}` : ''}${data.businessKeyword ? `；业务对象 ${data.businessKeyword}` : ''}`,
    applyReason: data.applyReason,
    maskingRule: '手机号中间四位脱敏；身份信息不导出',
    taskStatus: '待生成',
    createByName: '超级管理员',
    createTime: '20260714144000'
  }
  tasks.unshift(item)
  recordDemoAuditEvent({
    eventTypeLabel: '审计导出申请',
    eventKey: item.taskNo,
    relatedKeys: [data.businessKeyword, data.operatorKeyword].filter((key): key is string => !!key),
    actorLabel: '运营后台·超级管理员',
    newStatus: '待生成',
    detail: `申请范围：${item.exportScope}；申请原因：${item.applyReason}`,
    tone: 'primary',
    targetPath: '/system/compliance'
  })
  return delay({ ...item })
}

export function fetchRetryAuditExportTask(id: number): Promise<boolean> {
  const task = tasks.find((item) => item.id === id)
  if (!task || task.taskStatus !== '失败') return delay(false)
  task.taskStatus = '待生成'
  task.failureReason = undefined
  recordDemoAuditEvent({
    eventTypeLabel: '审计导出重试',
    eventKey: task.taskNo,
    relatedKeys: [],
    actorLabel: '运营后台·超级管理员',
    oldStatus: '失败',
    newStatus: '待生成',
    detail: '重试已提交；Demo 仍不生成真实文件，等待文件服务接真',
    tone: 'warning',
    targetPath: '/system/compliance'
  })
  return delay(true)
}
