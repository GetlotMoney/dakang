import type {
  CourierAdmission,
  CourierAdmissionStatus,
  DeliveryExceptionRecord,
  DeliveryTask,
  DeliveryTaskStatus,
  SignPhoto,
  SignPhotoType,
} from './delivery'
import type { BusinessTime, EntityId } from './common'
import type { CourierAppealEvidence, DeliveryAppeal } from './order'
import { addSecondsToBusinessTime, ContractError } from './common'

/**
 * 配送域真实响应归一化与页面阻断判定的中立模块（E2E-03 包B）。
 *
 * - 归一化：后端 Long 序列化为字符串（number|十进制串双收，拒小数/前导零/越界，
 *   strictNumber 手法与 order.ts 同口径）；关键字段缺失/畸形 fail-closed 抛
 *   DELIVERY_CONTRACT_BROKEN，绝不拼残缺数据渲染。
 * - 阻断判定：页面对 接单/推进/签收/异常/申诉 的可用性判断全部走本模块纯函数，
 *   语义与包A 服务端拒因一致——页面不写第二份状态规则，服务端仍是最终裁决。
 * - 依赖关系：delivery.ts 与 order.ts 都只单向依赖本模块（延续 delivery-link.ts
 *   的中立模块手法），避免两域适配器互相 import 形成运行时循环。
 */

// ==================== strictNumber 手法 ====================

/** 严格安全整数：number 直收，字符串仅收规范十进制（拒小数/前导零/指数/越界）。 */
export function strictInt(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) ? value : undefined
  }
  if (typeof value === 'string' && /^(?:0|-?[1-9]\d*)$/.test(value)) {
    const n = Number(value)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

export function strictNonNegativeInt(value: unknown): number | undefined {
  const n = strictInt(value)
  return n !== undefined && n >= 0 ? n : undefined
}

export function strictPositiveInt(value: unknown): number | undefined {
  const n = strictInt(value)
  return n !== undefined && n > 0 ? n : undefined
}

/**
 * Long 主键 → EntityId(string)。后端全局把 Long 序列化为字符串防 2^53 精度丢失；
 * number 形态只在安全整数范围内接受（超范围说明精度已丢，String() 也救不回来，拒收）。
 */
export function strictEntityId(value: unknown): EntityId | undefined {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) {
    return value
  }
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) {
    return String(value)
  }
  return undefined
}

function optionalBizTime(value: unknown): BusinessTime | undefined {
  return typeof value === 'string' && /^\d{14}$/.test(value) ? value : undefined
}

function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

/**
 * field 只用于定位，<b>不进 message</b>——message 会被页面直接 toast 给用户，
 * 而「价格快照总额 缺失或不合法」对用户是一份后端字段体检报告，他既看不懂也做不了什么。
 * 排障线索在错误码 DELIVERY_CONTRACT_BROKEN 与抛出点代码里。
 */
function broken(field: string): ContractError {
  void field
  return new ContractError('DELIVERY_CONTRACT_BROKEN', '配送数据异常，请稍后重试')
}

// ==================== 契约码映射（唯一映射点） ====================

/** 异常原因契约码 ↔ 后端 1354 整数值（枚举名即契约码，包A DeliveryEnum 同源）。 */
export const EXCEPTION_REASON_TO_VALUE: Record<DeliveryExceptionRecord['reason'], number> = {
  UNREACHABLE: 1,
  ADDRESS: 2,
  QUANTITY: 3,
  DAMAGED: 4,
  OTHER: 5,
}

const EXCEPTION_REASON_CODES = new Set<string>(Object.keys(EXCEPTION_REASON_TO_VALUE))

const APPEAL_REASON_CODES = new Set<string>(['QUANTITY', 'QUALITY', 'DAMAGE', 'PLACEMENT', 'OTHER'])

/** 配送方式契约值（DeliveryCreateBo：1即时 2预约 3自动补货）。 */
export const DELIVERY_MODE_TO_VALUE = {
  'immediate': 1,
  'scheduled': 2,
  'auto-refill': 3,
} as const

/** 受控媒体键形态（服务端派生 DM+30位大写十六进制）；举证/三照引用必须先上传换键。 */
export const DELIVERY_MEDIA_KEY_PATTERN = /^DM[0-9A-F]{30}$/

/** 幂等 requestId：规范小写带连字符 UUID（后端 DeliveryOrderNo 只接受此形态）。 */
export const DELIVERY_REQUEST_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const CONTAINER_SPECS = new Set<DeliveryTask['containerSpec']>(['3L袋', '5L桶', '10L桶', '20L桶'])

const SIGN_PHOTO_LABELS: Record<SignPhotoType, SignPhoto['label']> = {
  1: '门牌',
  2: '水品',
  3: '摆放',
}

// ==================== 后端原样结构 ====================

interface SignPhotoRaw {
  type?: number | null
  mediaKey?: string | null
  time?: string | null
  latitude?: number | null
  longitude?: number | null
}

export interface DeliveryTaskRaw {
  taskId?: string | number | null
  taskNo?: string | null
  orderId?: string | number | null
  orderNo?: string | null
  userId?: string | number | null
  courierId?: string | number | null
  stationId?: string | number | null
  stationName?: string | null
  waterTypeId?: string | number | null
  waterTypeName?: string | null
  containerSpec?: string | null
  plannedDeliveryCount?: string | number | null
  actualDeliveryCount?: string | number | null
  plannedReturnCount?: string | number | null
  actualReturnCount?: string | number | null
  receiveAddress?: string | null
  maskedPhone?: string | null
  priceSnapshot?: {
    waterAmountFen?: string | number | null
    deliveryFeeFen?: string | number | null
    totalAmountFen?: string | number | null
  } | null
  payWay?: string | number | null
  taskStatus?: number | null
  version?: string | number | null
  scheduledTime?: string | null
  acceptTime?: string | null
  departTime?: string | null
  arriveTime?: string | null
  signTime?: string | null
  appealDeadline?: string | null
  locationStatus?: number | null
  signPhotos?: SignPhotoRaw[] | null
  /** 补送任务标识（E2E-04）：由售后动作 RESULT_TASK_ID 派生；缺省即普通任务。 */
  isResend?: boolean | null
}

export interface CourierAdmissionRaw {
  accountId?: string | number | null
  userId?: string | number | null
  status?: number | null
  applicantName?: string | null
  maskedPhone?: string | null
  requestedStationIds?: Array<string | number> | null
  requestedRegion?: string | null
  submittedTime?: string | null
  rejectReason?: string | null
}

export interface DeliveryExceptionRaw {
  exceptionId?: string | number | null
  taskNo?: string | null
  courierId?: string | number | null
  reason?: string | null
  description?: string | null
  evidenceRefs?: unknown
  createTime?: string | null
}

interface CourierEvidenceRaw {
  description?: string | null
  evidenceRefs?: unknown
  time?: string | null
}

export interface DeliveryAppealRaw {
  appealId?: string | number | null
  orderNo?: string | null
  taskNo?: string | null
  userId?: string | number | null
  appealStatus?: number | null
  reason?: string | null
  description?: string | null
  receivedCount?: string | number | null
  evidenceRefs?: unknown
  createTime?: string | null
  decisionSummary?: string | null
  courierEvidences?: CourierEvidenceRaw[] | null
}

// ==================== 归一化 ====================

function normalizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((item): item is string => typeof item === 'string' && item.length > 0)
}

/**
 * 签收单照归一化：type/mediaKey/time 任一畸形即丢弃该条（与后端"宁可不展示不拼残缺证据"
 * 同口径）；真实数据 evidenceMode 固定 'real'，recordRef 即受控媒体键。
 */
function normalizeSignPhoto(raw: SignPhotoRaw): SignPhoto | undefined {
  const type = strictInt(raw.type)
  const time = optionalBizTime(raw.time)
  const mediaKey = optionalText(raw.mediaKey)
  if ((type !== 1 && type !== 2 && type !== 3) || !time || !mediaKey) {
    return undefined
  }
  const photoType = type as SignPhotoType
  return {
    type: photoType,
    label: SIGN_PHOTO_LABELS[photoType],
    recordRef: mediaKey,
    time,
    latitude: typeof raw.latitude === 'number' ? raw.latitude : undefined,
    longitude: typeof raw.longitude === 'number' ? raw.longitude : undefined,
    evidenceMode: 'real',
  }
}

/** 后端配送任务 → 契约 DeliveryTask；关键字段缺失/价格快照不自洽一律 fail-closed。 */
export function normalizeDeliveryTask(raw: DeliveryTaskRaw): DeliveryTask {
  const taskId = strictEntityId(raw.taskId)
  const orderId = strictEntityId(raw.orderId)
  const userId = strictEntityId(raw.userId)
  const stationId = strictEntityId(raw.stationId)
  const waterTypeId = strictEntityId(raw.waterTypeId)
  const taskNo = optionalText(raw.taskNo)
  const orderNo = optionalText(raw.orderNo)
  if (!taskId || !orderId || !userId || !stationId || !waterTypeId || !taskNo || !orderNo) {
    throw broken('任务标识')
  }
  const taskStatus = strictInt(raw.taskStatus)
  if (taskStatus === undefined || taskStatus < 1 || taskStatus > 7) {
    throw broken('任务状态')
  }
  const version = strictPositiveInt(raw.version)
  if (version === undefined) {
    throw broken('任务版本')
  }
  const containerSpec = raw.containerSpec as DeliveryTask['containerSpec']
  if (!CONTAINER_SPECS.has(containerSpec)) {
    throw broken('容器规格')
  }
  const plannedDeliveryCount = strictPositiveInt(raw.plannedDeliveryCount)
  const plannedReturnCount = strictNonNegativeInt(raw.plannedReturnCount)
  if (plannedDeliveryCount === undefined || plannedReturnCount === undefined) {
    throw broken('计划数量')
  }
  const waterAmountFen = strictNonNegativeInt(raw.priceSnapshot?.waterAmountFen)
  const deliveryFeeFen = strictNonNegativeInt(raw.priceSnapshot?.deliveryFeeFen)
  const totalAmountFen = strictNonNegativeInt(raw.priceSnapshot?.totalAmountFen)
  if (waterAmountFen === undefined || deliveryFeeFen === undefined || totalAmountFen === undefined) {
    throw broken('价格快照')
  }
  // 规则2 校验而非重算：总额必须恰等于水费+配送费，不一致即契约破坏（金额不许猜）。
  // D-214 口径下恒等式对 payWay=3 同样成立（水费=0、总额=配送费），无需按支付方式分叉
  if (totalAmountFen !== waterAmountFen + deliveryFeeFen) {
    throw broken('价格快照总额')
  }
  // 支付方式（D-214）：缺省按 2 兼容旧后端/旧单；白名单外 fail-closed——
  // 支付口径决定金额展示语义，未知值宁可拒绝也不猜着渲染
  const payWayRaw = raw.payWay == null ? 2 : strictInt(raw.payWay)
  if (payWayRaw !== 2 && payWayRaw !== 3) {
    throw broken('支付方式')
  }
  const photos = (raw.signPhotos ?? [])
    .map(normalizeSignPhoto)
    .filter((item): item is SignPhoto => item !== undefined)
  return {
    taskId,
    taskNo,
    orderId,
    orderNo,
    userId,
    courierId: strictEntityId(raw.courierId),
    stationId,
    stationName: optionalText(raw.stationName) ?? '',
    waterTypeId,
    waterTypeName: optionalText(raw.waterTypeName) ?? '',
    containerSpec,
    plannedDeliveryCount,
    actualDeliveryCount: strictNonNegativeInt(raw.actualDeliveryCount),
    plannedReturnCount,
    actualReturnCount: strictNonNegativeInt(raw.actualReturnCount),
    receiveAddress: optionalText(raw.receiveAddress) ?? '',
    maskedPhone: optionalText(raw.maskedPhone) ?? '',
    priceSnapshot: { waterAmountFen, deliveryFeeFen, totalAmountFen },
    payWay: payWayRaw,
    taskStatus: taskStatus as DeliveryTaskStatus,
    version,
    acceptTime: optionalBizTime(raw.acceptTime),
    departTime: optionalBizTime(raw.departTime),
    arriveTime: optionalBizTime(raw.arriveTime),
    signTime: optionalBizTime(raw.signTime),
    appealDeadline: optionalBizTime(raw.appealDeadline),
    signPhotos: photos,
    // 补送标识只认显式 true：任何"真值"或特征推断都可能把普通任务标成补送单（E2E-04 包E）
    isResend: raw.isResend === true ? true : undefined,
    // 1353：1已记录 2未记录；未知值一律按未记录呈现（声明不得超出证据）
    locationStatus: raw.locationStatus == null
      ? undefined
      : raw.locationStatus === 1
        ? 'recorded'
        : 'unrecorded',
  }
}

/** 后端准入状态 → 契约 CourierAdmission；status 越界 fail-closed。 */
export function normalizeCourierAdmission(raw: CourierAdmissionRaw): CourierAdmission {
  const accountId = strictEntityId(raw.accountId)
  const userId = strictEntityId(raw.userId)
  if (!accountId || !userId) {
    throw broken('准入归属')
  }
  const status = strictInt(raw.status)
  if (status === undefined || status < 0 || status > 4) {
    throw broken('准入状态')
  }
  const stationIds = (raw.requestedStationIds ?? [])
    .map(strictEntityId)
    .filter((item): item is EntityId => item !== undefined)
  return {
    accountId,
    userId,
    status: status as CourierAdmissionStatus,
    applicantName: optionalText(raw.applicantName) ?? '',
    maskedPhone: optionalText(raw.maskedPhone) ?? '',
    requestedStationIds: stationIds,
    requestedRegion: optionalText(raw.requestedRegion),
    submittedTime: optionalBizTime(raw.submittedTime),
    rejectReason: optionalText(raw.rejectReason),
  }
}

/** 后端异常记录 → 契约 DeliveryExceptionRecord；原因码白名单外 fail-closed。 */
export function normalizeDeliveryException(raw: DeliveryExceptionRaw): DeliveryExceptionRecord {
  const exceptionId = strictEntityId(raw.exceptionId)
  const courierId = strictEntityId(raw.courierId)
  const taskNo = optionalText(raw.taskNo)
  const createTime = optionalBizTime(raw.createTime)
  const reason = optionalText(raw.reason)
  if (!exceptionId || !courierId || !taskNo || !createTime) {
    throw broken('异常记录')
  }
  if (!reason || !EXCEPTION_REASON_CODES.has(reason)) {
    throw broken('异常原因')
  }
  return {
    exceptionId,
    taskNo,
    courierId,
    reason: reason as DeliveryExceptionRecord['reason'],
    description: optionalText(raw.description) ?? '',
    evidenceRefs: normalizeStringArray(raw.evidenceRefs),
    createTime,
  }
}

function normalizeCourierEvidence(raw: CourierEvidenceRaw): CourierAppealEvidence | undefined {
  const time = optionalBizTime(raw.time)
  const description = optionalText(raw.description)
  if (!time || !description) {
    return undefined
  }
  return { description, evidenceRefs: normalizeStringArray(raw.evidenceRefs), time }
}

/** 后端申诉 → 契约 DeliveryAppeal；状态/原因白名单外 fail-closed。 */
export function normalizeDeliveryAppeal(raw: DeliveryAppealRaw): DeliveryAppeal {
  const appealId = strictEntityId(raw.appealId)
  const userId = strictEntityId(raw.userId)
  const orderNo = optionalText(raw.orderNo)
  const taskNo = optionalText(raw.taskNo)
  const createTime = optionalBizTime(raw.createTime)
  if (!appealId || !userId || !orderNo || !taskNo || !createTime) {
    throw broken('申诉标识')
  }
  const appealStatus = strictInt(raw.appealStatus)
  if (appealStatus === undefined || appealStatus < 1 || appealStatus > 5) {
    throw broken('申诉状态')
  }
  const reason = optionalText(raw.reason)
  if (!reason || !APPEAL_REASON_CODES.has(reason)) {
    throw broken('申诉原因')
  }
  const receivedCount = strictNonNegativeInt(raw.receivedCount)
  if (receivedCount === undefined) {
    throw broken('实收数量')
  }
  return {
    appealId,
    orderNo,
    taskNo,
    userId,
    appealStatus: appealStatus as DeliveryAppeal['appealStatus'],
    reason: reason as DeliveryAppeal['reason'],
    description: optionalText(raw.description) ?? '',
    receivedCount,
    evidenceRefs: normalizeStringArray(raw.evidenceRefs),
    createTime,
    decisionSummary: optionalText(raw.decisionSummary),
    courierEvidences: (raw.courierEvidences ?? [])
      .map(normalizeCourierEvidence)
      .filter((item): item is CourierAppealEvidence => item !== undefined),
  }
}

// ==================== 页面阻断判定纯函数（语义对齐包A 拒因） ====================

/** 任务阻断视图的最小字段集（结构类型；Mock/Real 任务都满足）。 */
export interface DeliveryTaskGateView {
  taskStatus: DeliveryTaskStatus
  courierId?: EntityId
  signTime?: BusinessTime
  appealDeadline?: BusinessTime
}

/** 可接单：待接单且无人认领（包A「任务已被领取或状态不可接单」拒因的页面侧口径）。 */
export function canAcceptDeliveryTask(task: DeliveryTaskGateView): boolean {
  return task.taskStatus === 1 && !task.courierId
}

/** 可推进：3离站要求当前2已接单；4送达要求当前3配送中（DeliveryTransitions 同源）。 */
export function canAdvanceDeliveryTask(task: DeliveryTaskGateView, targetStatus: 3 | 4): boolean {
  return task.taskStatus === (targetStatus === 3 ? 2 : 3)
}

/** 可签收：仅 4已送达待确认（包A「当前任务不能签收」拒因）。 */
export function canSignDeliveryTask(task: DeliveryTaskGateView): boolean {
  return task.taskStatus === 4
}

/** 可上报异常：仅履约中 2/3/4（包A「当前任务不能上报配送异常」拒因）。 */
export function canReportDeliveryException(task: DeliveryTaskGateView): boolean {
  return task.taskStatus === 2 || task.taskStatus === 3 || task.taskStatus === 4
}

/** 可发起申诉：仅 5已签收且有签收时间（包A「只有已签收订单可以发起申诉」拒因）。 */
export function canCreateDeliveryAppeal(task: DeliveryTaskGateView): boolean {
  return task.taskStatus === 5 && Boolean(task.signTime)
}

/** 可追加举证：仅 7申诉中（包A「任务当前不在申诉中，无法追加举证」拒因）。 */
export function canAppendAppealEvidence(task: DeliveryTaskGateView): boolean {
  return task.taskStatus === 7
}

/**
 * 申诉截止时间的展示口径：优先服务端签收事务落定的权威值（appealDeadline），
 * 仅在缺失时按 签收+24h 派生兜底（历史 Mock 数据无该列）。
 */
export function appealDeadlineOf(task: DeliveryTaskGateView): BusinessTime | undefined {
  if (task.appealDeadline) {
    return task.appealDeadline
  }
  return task.signTime ? addSecondsToBusinessTime(task.signTime, 24 * 60 * 60) : undefined
}

/**
 * 申诉窗口展示态：窗口内/已超期/未知。仅供页面提示；是否放行以服务端
 * 提交时校验为准（规则15 的双侧窗口只有一份实现，在包A 事务内）。
 */
export function appealWindowState(
  task: DeliveryTaskGateView,
  now: BusinessTime | undefined,
): 'open' | 'expired' | 'unknown' {
  const deadline = appealDeadlineOf(task)
  if (!deadline || !now || !/^\d{14}$/.test(now)) {
    return 'unknown'
  }
  return now > deadline ? 'expired' : 'open'
}
