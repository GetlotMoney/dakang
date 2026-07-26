/**
 * 设备中控 API（对接 /device/device/* /device/outlet/* /device/command/*，统一 POST + { code:0, msg, data }）
 *
 * 状态字段（在线/运行/心跳/故障码/信号）由设备上行链路维护，档案编辑不承载；
 * 二维码/遥测/告警为详情页只读数据（完整管理为商业一期）。
 */
import request from '@/utils/http'
import { recordDemoAuditEvent } from '@/api/demo-audit'

/** 设备列表项 / 详情 */
export interface DeviceItem {
  id: number
  deviceNo: string
  deviceName: string
  deviceModel: string
  stationId: number
  stationName?: string
  ownerUserId?: number
  ownerUserName?: string
  firmwareVersion?: string
  simIccid?: string
  simCarrier?: string
  /** 在线状态(1300)：1在线 2离线 3未激活 */
  onlineStatus: number
  /** 运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机 */
  runStatus: number
  lastHeartbeat?: string
  lastFaultCode?: string
  signalStrength?: number
  outletCount?: number
  deviceRemark?: string
  createTime?: string
  updateTime?: string
}

/** 设备分页查询参数 */
export interface DeviceSearchParams {
  current: number
  size: number
  deviceNo?: string
  deviceName?: string
  stationId?: number
  onlineStatus?: number
  runStatus?: number
}

/** 设备新增/编辑表单（编号建档后不可改） */
export interface DeviceFormData {
  id?: number
  deviceNo: string
  deviceName: string
  deviceModel: string
  stationId: number
  ownerUserId?: number
  firmwareVersion?: string
  simIccid?: string
  simCarrier?: string
  deviceRemark?: string
}

/** 出水口 */
export interface OutletItem {
  id: number
  deviceId: number
  outletNo: number
  waterTypeId?: number
  waterType?: string
  /** 单价(分/升) */
  outletPrice: string
  /** 状态(10)：1正常 2禁用 */
  outletStatus: number
}

/** 出水口表单 */
export interface OutletFormData {
  id?: number
  deviceId: number
  outletNo: number
  waterTypeId: number
  outletPrice: string
  outletStatus: number
}

/** 指令记录 */
export interface CommandItem {
  id: number
  cmdNo: string
  deviceId: number
  deviceNo?: string
  deviceName?: string
  orderId?: number
  /** 指令类型(1320)：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启 */
  cmdType: number
  cmdPayload?: string
  /** 指令状态(1321)：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成 */
  cmdStatus: number
  sentTime?: string
  ackTime?: string
  finishTime?: string
  resultPayload?: string
  failReason?: string
  retryCount?: number
  createTime?: string
}

/** 指令分页查询参数 */
export interface CommandSearchParams {
  current: number
  size: number
  deviceId?: number
  cmdStatus?: number
  cmdNo?: string
}

/** 二维码（只读） */
export interface QrcodeItem {
  id: number
  qrcodeContent: string
  /** 类型(1303)：1新版设备码 2旧版设备码 3万能码 */
  qrcodeType: number
  deviceId?: number
  outletId?: number
  outletNo?: number
  qrcodeStatus: number
}

/** 遥测 */
export interface TelemetryItem {
  id: number
  deviceId: number
  tdsValue?: number
  rawTdsValue?: number
  waterTemp?: number
  /** JSON 数组：[{no, restDay, status}]，status 见字典 1383 */
  filterLifeJson?: string
  signalStrength?: number
  reportTime: string
}

/** 告警（只读提醒） */
export interface AlarmItem {
  id: number
  deviceId: number
  /** 告警类型(1360) */
  alarmType: number
  /** 等级(1304)：1提示 2一般 3严重 */
  alarmLevel: number
  alarmContent: string
  sourceRef?: string
  /** 状态(1361)：1待处理 2已转工单 3已忽略 4自动恢复 */
  alarmStatus: number
  recoverTime?: string
  createTime?: string
}

// ==================== 设备档案 ====================

export function fetchDevicePage(params: DeviceSearchParams) {
  return request.post<{ total: number; list: DeviceItem[] }>({
    url: '/device/device/page',
    data: params
  })
}

export function fetchDeviceDetail(id: number) {
  return request.post<DeviceItem>({
    url: '/device/device/detail',
    data: { id }
  })
}

export function fetchAddDevice(data: DeviceFormData) {
  return request.post<number>({
    url: '/device/device/add',
    data
  })
}

export function fetchUpdateDevice(data: DeviceFormData) {
  return request.post<boolean>({
    url: '/device/device/update',
    data
  })
}

export function fetchDeleteDevice(id: number) {
  return request.post<boolean>({
    url: '/device/device/delete',
    data: { id }
  })
}

export function fetchDeviceQrcodeList(id: number) {
  return request.post<QrcodeItem[]>({
    url: '/device/device/qrcodeList',
    data: { id }
  })
}

export function fetchDeviceLatestTelemetry(id: number) {
  return request.post<TelemetryItem | null>({
    url: '/device/device/latestTelemetry',
    data: { id }
  })
}

export function fetchDeviceAlarmPage(params: {
  current: number
  size: number
  deviceId: number
  alarmStatus?: number
}) {
  return request.post<{ total: number; list: AlarmItem[] }>({
    url: '/device/device/alarmPage',
    data: params
  })
}

// ==================== 出水口 ====================

export function fetchOutletListByDevice(deviceId: number) {
  return request.post<OutletItem[]>({
    url: '/device/outlet/listByDevice',
    data: { deviceId }
  })
}

export function fetchAddOutlet(data: OutletFormData) {
  return request.post<number>({
    url: '/device/outlet/add',
    data
  })
}

export function fetchUpdateOutlet(data: OutletFormData) {
  return request.post<boolean>({
    url: '/device/outlet/update',
    data
  })
}

export function fetchDeleteOutlet(id: number) {
  return request.post<boolean>({
    url: '/device/outlet/delete',
    data: { id }
  })
}

// ==================== 指令 ====================

export function fetchCommandPage(params: CommandSearchParams) {
  return request.post<{ total: number; list: CommandItem[] }>({
    url: '/device/command/page',
    data: params
  })
}

export function fetchCommandDetail(id: number) {
  return request.post<CommandItem>({
    url: '/device/command/detail',
    data: { id }
  })
}

/** 下发指令（仅 3查询状态 4锁机 5解锁 6参数同步 7重启；出水类由订单链路触发） */
export function fetchSendCommand(data: { deviceId: number; cmdType: number; cmdPayload?: string }) {
  return request.post<number>({
    url: '/device/command/send',
    data
  })
}

// ==================== 设备运营配置（Demo Mock，后续按同名契约接真） ====================

export interface DeviceDisplayPolicy {
  showOnlineStatus: boolean
  showTds: boolean
  showFilterLife: boolean
  heartbeatTimeoutSeconds: number
  unavailableAction: 'hide_order' | 'disable_order'
  updateReason: string
  updateTime: string
}

export interface DeviceAnnouncementItem {
  id: number
  title: string
  content: string
  scopeType: 'all' | 'station' | 'device'
  scopeText: string
  startTime: string
  endTime: string
  publishReason: string
  /** PC 配置状态，不代表小程序已读取。 */
  pcStatus: '待生效' | '生效中' | '已失效'
  version: string
  clientSyncStatus: '待跨端同步' | '已同步'
  lastSyncTime?: string
}

export interface DeviceAnnouncementForm {
  title: string
  content: string
  scopeType: DeviceAnnouncementItem['scopeType']
  scopeText: string
  startTime: string
  endTime: string
  publishReason: string
}

let displayPolicy: DeviceDisplayPolicy = {
  showOnlineStatus: true,
  showTds: true,
  showFilterLife: true,
  heartbeatTimeoutSeconds: 90,
  unavailableAction: 'disable_order',
  updateReason: '一期默认策略：不可用设备展示原因并禁用下单',
  updateTime: '20260714120000'
}

const announcements: DeviceAnnouncementItem[] = [
  {
    id: 1,
    title: '南湖水站设备检修提示',
    content: '南湖 1 号机故障检修中，请前往附近可用水站。',
    scopeType: 'station',
    scopeText: '南湖社区水站',
    startTime: '20260714120000',
    endTime: '20260715200000',
    publishReason: 'E003 故障阻断取水，向用户说明不可用原因',
    pcStatus: '生效中',
    version: 'ANN-20260714-001',
    clientSyncStatus: '待跨端同步'
  }
]

const mockDelay = <T>(value: T): Promise<T> =>
  new Promise((resolve) => setTimeout(() => resolve(value), 180))

export function fetchDeviceDisplayPolicy(): Promise<DeviceDisplayPolicy> {
  return mockDelay({ ...displayPolicy })
}

export function fetchSaveDeviceDisplayPolicy(
  data: Omit<DeviceDisplayPolicy, 'updateTime'>
): Promise<DeviceDisplayPolicy> {
  const before = { ...displayPolicy }
  displayPolicy = { ...data, updateTime: '20260714143000' }
  recordDemoAuditEvent({
    eventTypeLabel: '设备展示策略修改',
    eventKey: 'DEVICE-DISPLAY-POLICY',
    relatedKeys: [],
    actorLabel: '运营后台·超级管理员',
    oldStatus:
      before.unavailableAction === 'disable_order' ? '不可用时禁止下单' : '不可用时隐藏下单',
    newStatus: data.unavailableAction === 'disable_order' ? '不可用时禁止下单' : '不可用时隐藏下单',
    detail: `心跳超时 ${before.heartbeatTimeoutSeconds}→${data.heartbeatTimeoutSeconds} 秒；修改原因：${data.updateReason}`,
    tone: 'warning',
    targetPath: '/device/operations'
  })
  return mockDelay({ ...displayPolicy })
}

export function fetchDeviceAnnouncementList(): Promise<DeviceAnnouncementItem[]> {
  return mockDelay(announcements.map((item) => ({ ...item })))
}

export function fetchCreateDeviceAnnouncement(
  data: DeviceAnnouncementForm
): Promise<DeviceAnnouncementItem> {
  const item: DeviceAnnouncementItem = {
    id: Math.max(0, ...announcements.map((announcement) => announcement.id)) + 1,
    ...data,
    pcStatus: '待生效',
    version: `ANN-20260714-${String(announcements.length + 1).padStart(3, '0')}`,
    clientSyncStatus: '待跨端同步'
  }
  announcements.unshift(item)
  recordDemoAuditEvent({
    eventTypeLabel: '设备公告配置',
    eventKey: item.version,
    relatedKeys: [item.title, item.scopeText],
    actorLabel: '运营后台·超级管理员',
    newStatus: '待生效（待跨端同步）',
    detail: `新增公告「${item.title}」，范围：${item.scopeText}；发布原因：${item.publishReason}`,
    tone: 'primary',
    targetPath: '/device/operations'
  })
  return mockDelay({ ...item })
}
