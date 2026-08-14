/**
 * 设备中控 API（对接 /device/device/* /device/outlet/* /device/command/*，统一 POST + { code:0, msg, data }）
 *
 * 状态字段（在线/运行/心跳/故障码/信号）由设备上行链路维护，档案编辑不承载；
 * 二维码/遥测/告警为详情页只读数据（完整管理为商业一期）。
 */
import request from '@/utils/http'

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
  /** SIM状态(1368)：1正常 2未激活 3欠费 4停用；真实运营商查询未接入 */
  simStatus?: number
  simExpireTime?: string
  /** 在线状态(1300)：1在线 2离线 3未激活 */
  onlineStatus: number
  /** 运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机 */
  runStatus: number
  lastHeartbeat?: string
  lastFaultCode?: string
  orderAvailabilityCode?: string
  orderAvailabilityReason?: string
  orderAvailable?: boolean
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
  simStatus?: number
  simExpireTime?: string
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

/** 告警（告警中心 + 设备详情只读提醒） */
export interface AlarmItem {
  id: number
  deviceId: number
  deviceNo?: string
  /** 告警类型(1360) */
  alarmType: number
  /** 等级(1304)：1提示 2一般 3严重 */
  alarmLevel: number
  alarmContent: string
  sourceRef?: string
  /** 状态(1361)：1待处理 2已转工单 3已忽略 4自动恢复 */
  alarmStatus: number
  recoverTime?: string
  /** 转出的工单（详情联查，回看关联工单） */
  workOrderId?: number
  workOrderNo?: string
  handleBy?: number
  handleTime?: string
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

// S4 R1：身份 ID 边界收敛为 string（>2^53 数值舍入防线）；调用方显式 String() 适配
export function fetchOutletListByDevice(deviceId: string) {
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

// ==================== 告警中心（E2E-05 包C/D，接真） ====================

export interface AlarmSearchParams {
  current: number
  size: number
  deviceId?: number
  alarmStatus?: number
  alarmType?: number
  alarmLevel?: number
}

export function fetchAlarmPage(params: AlarmSearchParams) {
  return request.post<{ total: number; list: AlarmItem[] }>({
    url: '/device/alarm/page',
    data: params
  })
}

export function fetchAlarmDetail(id: number) {
  return request.post<AlarmItem>({
    url: '/device/alarm/detail',
    data: { id }
  })
}

/** 忽略告警（仅待处理可忽略；键清空后同型告警可再次触发） */
export function fetchIgnoreAlarm(id: number) {
  return request.post<boolean>({
    url: '/device/alarm/ignore',
    data: { id }
  })
}

/** 告警转工单（一个告警最多一单，重复转单返回既有工单ID） */
export function fetchAlarmToWorkOrder(id: number) {
  return request.post<number>({
    url: '/device/alarm/toWorkOrder',
    data: { id }
  })
}

// ==================== 运维工单（E2E-05 包C/D，接真） ====================

export interface WorkOrderTraceItem {
  eventTime: string
  /** 操作端口(1364)：1公司后台 2用户端 3机主端 6系统 */
  actorPortal?: number
  actorId?: number
  payload?: string
}

export interface WorkOrderItem {
  id: number
  orderNo: string
  /** 工单类型(1365)：1维修 2配件 3巡检 */
  workType: number
  deviceId?: number
  deviceNo?: string
  stationName?: string
  /** 来源：1告警转入 2机主申报 3后台创建 */
  sourceType: number
  alarmId?: number
  alarmContent?: string
  applicantUserId?: number
  orderTitle: string
  orderContent?: string
  /** 申报证据媒体键 JSON 数组（受控 mediaKey） */
  orderPhotos?: string
  assigneeId?: number
  assigneeName?: string
  /** 工单状态(1362)：1待确认 2待分配 3处理中 4待复核 5已关闭 6已驳回 */
  orderStatus: number
  assignTime?: string
  finishTime?: string
  finishResult?: string
  resultPhotos?: string
  reviewBy?: number
  reviewTime?: string
  reviewRemark?: string
  rejectReason?: string
  closeTime?: string
  createTime?: string
  /** 完整状态轨迹（仅详情返回） */
  trace?: WorkOrderTraceItem[]
}

export interface WorkOrderSearchParams {
  current: number
  size: number
  orderNo?: string
  orderStatus?: number
  filterWorkType?: number
  sourceType?: number
  filterDeviceId?: number
  filterAssigneeId?: number
}

export function fetchWorkOrderPage(params: WorkOrderSearchParams) {
  return request.post<{ total: number; list: WorkOrderItem[] }>({
    url: '/device/workorder/page',
    data: params
  })
}

export function fetchWorkOrderDetail(id: number) {
  return request.post<WorkOrderItem>({
    url: '/device/workorder/detail',
    data: { id }
  })
}

/** 后台建单（维修/配件/巡检，直接进待分配） */
export function fetchCreateWorkOrder(data: {
  workType: number
  deviceId?: number
  orderTitle: string
  orderContent?: string
}) {
  return request.post<number>({
    url: '/device/workorder/create',
    data
  })
}

export function fetchConfirmWorkOrder(id: number) {
  return request.post<boolean>({
    url: '/device/workorder/confirm',
    data: { id }
  })
}

export function fetchRejectWorkOrder(id: number, rejectReason: string) {
  return request.post<boolean>({
    url: '/device/workorder/reject',
    data: { id, rejectReason }
  })
}

export function fetchAssignWorkOrder(id: number, assigneeId: number) {
  return request.post<boolean>({
    url: '/device/workorder/assign',
    data: { id, assigneeId }
  })
}

export function fetchSubmitWorkOrderResult(
  id: number,
  finishResult: string,
  resultPhotos?: string[]
) {
  return request.post<boolean>({
    url: '/device/workorder/submitResult',
    data: { id, finishResult, resultPhotos }
  })
}

export function fetchReviewPassWorkOrder(id: number, reviewRemark?: string) {
  return request.post<boolean>({
    url: '/device/workorder/reviewPass',
    data: { id, reviewRemark }
  })
}

export function fetchReviewReturnWorkOrder(id: number, reviewRemark: string) {
  return request.post<boolean>({
    url: '/device/workorder/reviewReturn',
    data: { id, reviewRemark }
  })
}

/** 员工登记工单处理证据（base64，仅 purpose=4；返回受控 mediaKey） */
export function fetchUploadWorkOrderMedia(data: { mimeType: string; contentBase64: string }) {
  return request.post<string>({
    url: '/device/workorder/media/upload',
    data: { purpose: 4, ...data }
  })
}

// ==================== 批量控制（E2E-05 包B/D，两步确认） ====================

export interface BatchPreviewResult {
  operationTicket: string
  ticketTtlSeconds: number
  cmdType: number
  cmdTypeDesc: string
  scopeType: number
  /** 目标设备数量（服务端解析结果，非前端提交值） */
  targetCount: number
  /** 目标设备编号预览（最多前 20 台） */
  deviceNos: string[]
  targetDigest: string
  paramDigest: string
  /** 紧急停止锚定的活动订单号（仅紧急停止返回） */
  activeOrderNo?: string
  /** 是否需二级认证（服务端按 D-423 判据算出）；前端只做提前告知，读到 false 也可能被拒。 */
  requireSafe?: boolean
}

export interface BatchItem {
  id: number
  batchNo: string
  /** 范围类型(1366)：1指定设备 2指定水站 3全部设备 */
  scopeType: number
  scopeSnapshot?: string
  cmdType: number
  cmdPayload?: string
  paramDigest?: string
  totalCount: number
  successCount: number
  failCount: number
  timeoutCount: number
  /** 聚合状态(1367)：1处理中 2全部成功 3部分成功 4全部失败 */
  batchStatus: number
  finishTime?: string
  createTime?: string
  /** 子指令明细（仅详情返回） */
  commands?: CommandItem[]
}

/** 第一步预览：服务端解析目标并冻结参数，返回一次性操作凭据 */
export function fetchBatchPreview(data: {
  scopeType: number
  deviceIds?: number[]
  stationId?: number
  cmdType: number
  cmdPayload?: string
}) {
  return request.post<BatchPreviewResult>({
    url: '/device/batch/preview',
    data
  })
}

/** 第二步确认：GETDEL 领取凭据（过期/重复/换人/参数变化全部拒绝），返回批次ID */
export function fetchBatchConfirm(data: {
  operationTicket: string
  targetDigest: string
  paramDigest: string
}) {
  return request.post<number>({
    url: '/device/batch/confirm',
    data
  })
}

export function fetchBatchPage(params: {
  current: number
  size: number
  batchNo?: string
  batchStatus?: number
  filterCmdType?: number
}) {
  return request.post<{ total: number; list: BatchItem[] }>({
    url: '/device/batch/page',
    data: params
  })
}

export function fetchBatchDetail(id: number) {
  return request.post<BatchItem>({
    url: '/device/batch/detail',
    data: { id }
  })
}
