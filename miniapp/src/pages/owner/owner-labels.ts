import type {
  DeviceOnlineStatus,
  DeviceRunStatus,
  OwnerServiceStatus,
  OwnerServiceType,
} from '@/api/device'
import type { TagTone } from '@/utils/format'

/**
 * 机主能力域（O01～O05）共用的状态文案与 tag 色调映射。
 * 共享层 utils/format.ts 未收录设备在线/运行态与机主服务请求口径，
 * 按“共享文件只用不改”约束在机主页面目录内维护，避免五个页面各自复制。
 */

export const ONLINE_STATUS_LABELS: Record<DeviceOnlineStatus, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
}

export const ONLINE_STATUS_TONES: Record<DeviceOnlineStatus, TagTone> = {
  ONLINE: 'success',
  OFFLINE: 'danger',
}

export const RUN_STATUS_LABELS: Record<DeviceRunStatus, string> = {
  IDLE: '空闲',
  DISPENSING: '出水中',
  FAULT: '故障',
  MAINTENANCE: '维护',
  LOCKED: '锁定',
}

export const RUN_STATUS_TONES: Record<DeviceRunStatus, TagTone> = {
  IDLE: 'success',
  DISPENSING: 'primary',
  FAULT: 'danger',
  MAINTENANCE: 'warning',
  LOCKED: 'default',
}

export const SERVICE_TYPE_LABELS: Record<OwnerServiceType, string> = {
  REPAIR: '报修',
  PART: '配件',
}

export const SERVICE_TYPE_TONES: Record<OwnerServiceType, TagTone> = {
  REPAIR: 'warning',
  PART: 'primary',
}

export const SERVICE_STATUS_LABELS: Record<OwnerServiceStatus, string> = {
  PENDING_ACCEPTANCE: '待受理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  REJECTED: '已驳回',
}

export const SERVICE_STATUS_TONES: Record<OwnerServiceStatus, TagTone> = {
  PENDING_ACCEPTANCE: 'warning',
  PROCESSING: 'primary',
  COMPLETED: 'success',
  REJECTED: 'danger',
}
