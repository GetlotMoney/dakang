import type {
  BusinessTime,
  EntityId,
  MockMeta,
  MoneyFen,
} from './common'
import type { DeliveryAppeal, OrderDetail, OrderDetailRaw } from './order'
import { cardApi } from './card'
import { ContractError } from './common'
import {
  DELIVERY_MEDIA_KEY_PATTERN,
  DELIVERY_MODE_TO_VALUE,
  DELIVERY_REQUEST_ID_PATTERN,
  EXCEPTION_REASON_TO_VALUE,
  normalizeCourierAdmission,
  normalizeDeliveryAppeal,
  normalizeDeliveryException,
  normalizeDeliveryTask,
} from './delivery-normalize'
import type {
  CourierAdmissionRaw,
  DeliveryAppealRaw,
  DeliveryExceptionRaw,
  DeliveryTaskRaw,
} from './delivery-normalize'
import { normalizeOrderDetail } from './order'
import { withRealSession } from './real-session'
import { post } from './request'
import { currentMode, realAdapterPending } from './runtime'
import { formatFen, formatMl } from '@/utils/format'

export type DeliveryTaskStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7
export type DeliveryTaskView = 'available' | 'active' | 'history'
export type SignPhotoType = 1 | 2 | 3
export type CourierAdmissionStatus = 0 | 1 | 2 | 3 | 4

/**
 * 配送支付方式（D-214 二选一，1346 子集）：2 全余额（水费+配送费均扣余额）；
 * 3 混合结算（水费按容器水量抵扣 BALANCE_ML，配送费仍扣余额——配送费是服务费，不得用毫升支付）。
 */
export type DeliveryPayWay = 2 | 3

export interface DeliveryPriceSnapshot {
  /** D-214 口径：本单实际应扣水费（payWay=3 恒 0，抵扣升数由容器规格×数量换算展示）。 */
  waterAmountFen: MoneyFen
  deliveryFeeFen: MoneyFen
  totalAmountFen: MoneyFen
}

export interface SignPhoto {
  type: SignPhotoType
  label: '门牌' | '水品' | '摆放'
  /** 照片引用：Mock 为本地记录号；real 为受控媒体键（DM+30位十六进制，上传换取）。 */
  recordRef: string
  previewUrl?: string
  time: BusinessTime
  latitude?: number
  longitude?: number
  evidenceMode: 'prototype' | 'external-snapshot' | 'real'
}

export interface DeliveryTask {
  taskId: EntityId
  taskNo: string
  orderId: EntityId
  orderNo: string
  userId: EntityId
  courierId?: EntityId
  stationId: EntityId
  stationName: string
  waterTypeId: EntityId
  waterTypeName: string
  containerSpec: '3L袋' | '5L桶' | '10L桶' | '20L桶'
  plannedDeliveryCount: number
  actualDeliveryCount?: number
  plannedReturnCount: number
  actualReturnCount?: number
  receiveAddress: string
  maskedPhone: string
  priceSnapshot: DeliveryPriceSnapshot
  /**
   * 支付方式（D-214）。可选以兼容封板前的 Mock 夹具与后端旧单：缺省一律按 2（全余额）
   * 展示——历史配送单只有余额支付一种口径。
   */
  payWay?: DeliveryPayWay
  taskStatus: DeliveryTaskStatus
  version: number
  acceptTime?: BusinessTime
  departTime?: BusinessTime
  arriveTime?: BusinessTime
  signTime?: BusinessTime
  /** 申诉截止（签收+24h）；real 数据为签收事务落定的权威值，Mock 数据缺省由展示口径派生。 */
  appealDeadline?: BusinessTime
  signPhotos: SignPhoto[]
  /** 签收定位记录状态：原型快照=固定坐标已随三照记录；recorded=真实坐标已记录；unrecorded=未记录。 */
  locationStatus?: 'prototype-snapshot' | 'recorded' | 'unrecorded'
  /**
   * 补送任务标识（E2E-04 包C：申诉裁决 RESEND 生成的零金额子单任务）。
   *
   * **只认服务端下发的显式标识**：补送任务在快照上与普通任务只差"金额为 0、无回收桶"，
   * 用这些特征去猜必然误标（价格调整或免配送费活动都能命中）。缺省即不加标识。
   */
  isResend?: boolean
  mockMeta?: MockMeta
}

export interface CreateDeliveryOrderInput {
  /** 地址簿地址ID：传入时服务端按归属解引用取地址与号码写快照（号码不经前端，2026-08-02）。 */
  addressId?: EntityId
  stationId: EntityId
  waterTypeId: EntityId
  containerSpec: DeliveryTask['containerSpec']
  deliveryCount: number
  plannedReturnCount: number
  deliveryMode: 'immediate' | 'scheduled' | 'auto-refill'
  scheduledTime?: BusinessTime
  /** 自动补货为用户显式配置的固定周期（REQ-011：非 AI 预测），选择该方式时必填。 */
  autoRefillIntervalDays?: number
  /** 幂等键（real 必填）：规范小写 UUID，同一次提交的网络重试必须复用；Mock 忽略。 */
  requestId?: string
  /** 收水地址快照（直填模式必填，≤200 字）；与 addressId 二选一。 */
  receiveAddress?: string
  /** 收货电话（直填模式必填，11 位；服务端入库供配送联系、出网必脱敏）；与 addressId 二选一。 */
  receivePhone?: string
  /** 支付方式（D-214 二选一）：缺省按 2 全余额，兼容既有调用方；金额判定最终在服务端。 */
  payWay?: DeliveryPayWay
}

/**
 * 页面提交的三照草稿（第五轮审计整改）：不含权威时间；
 * 即使传入 time 也仅视为草稿展示值，契约在签收动作发生时统一覆盖为权威 signTime。
 */
export interface SignPhotoDraft extends Omit<SignPhoto, 'time'> {
  time?: BusinessTime
}

export interface SignDeliveryInput {
  taskNo: string
  expectedVersion: number
  actualDeliveryCount: number
  actualReturnCount: number
  photos: SignPhotoDraft[]
  /** 页面实际记录的定位状态；缺省按未记录处理，禁止文案宣称超出该值的证据。 */
  locationStatus?: 'prototype-snapshot' | 'recorded' | 'unrecorded'
}

export interface CourierAdmission {
  accountId: EntityId
  userId: EntityId
  status: CourierAdmissionStatus
  applicantName: string
  maskedPhone: string
  requestedStationIds: EntityId[]
  requestedRegion?: string
  submittedTime?: BusinessTime
  rejectReason?: string
}

export interface SubmitCourierAdmissionInput {
  applicantName: string
  phone: string
  requestedStationIds: EntityId[]
  requestedRegion?: string
  declarationAccepted: boolean
}

export interface DeliveryExceptionRecord {
  exceptionId: EntityId
  taskNo: string
  courierId: EntityId
  reason: 'UNREACHABLE' | 'ADDRESS' | 'QUANTITY' | 'DAMAGED' | 'OTHER'
  description: string
  evidenceRefs: string[]
  createTime: BusinessTime
}

export interface ReportDeliveryExceptionInput {
  taskNo: string
  expectedVersion: number
  reason: DeliveryExceptionRecord['reason']
  description: string
  evidenceRefs: string[]
}

/** 配送员追加申诉举证入参（S08：复用 D03，不另建脱离任务的页面）。 */
export interface AppendAppealEvidenceInput {
  taskNo: string
  appealId: EntityId
  description: string
  evidenceRefs: string[]
}

export interface DeliveryApi {
  createDeliveryOrder: (input: CreateDeliveryOrderInput) => Promise<{
    order: OrderDetail
    task: DeliveryTask
  }>
  listTasks: (view: DeliveryTaskView) => Promise<DeliveryTask[]>
  getTaskDetail: (taskNo: string) => Promise<DeliveryTask>
  acceptTask: (taskNo: string, expectedVersion: number) => Promise<DeliveryTask>
  advanceTask: (
    taskNo: string,
    targetStatus: 3 | 4,
    expectedVersion: number,
  ) => Promise<DeliveryTask>
  signTask: (input: SignDeliveryInput) => Promise<DeliveryTask>
  getCourierAdmission: () => Promise<CourierAdmission>
  submitCourierAdmission: (
    input: SubmitCourierAdmissionInput,
  ) => Promise<CourierAdmission>
  reportException: (
    input: ReportDeliveryExceptionInput,
  ) => Promise<DeliveryExceptionRecord>
  listTaskExceptions: (taskNo: string) => Promise<DeliveryExceptionRecord[]>
  /** 配送员查看本人任务关联的申诉与用户材料。 */
  getTaskAppeal: (taskNo: string) => Promise<DeliveryAppeal | null>
  /** 配送员在申诉中追加举证；只允许任务状态 7（申诉中）。 */
  appendAppealEvidence: (input: AppendAppealEvidenceInput) => Promise<DeliveryAppeal>
}

export const deliveryEndpoints = {
  create: '/mini/delivery/order/create',
  list: '/mini/delivery/task/page',
  detail: '/mini/delivery/task/detail',
  accept: '/mini/delivery/task/accept',
  advance: '/mini/delivery/task/advance',
  sign: '/mini/delivery/task/sign',
  admissionDetail: '/mini/delivery/admission/detail',
  admissionSubmit: '/mini/delivery/admission/submit',
  exceptionReport: '/mini/delivery/task/exception/report',
  exceptionList: '/mini/delivery/task/exception/list',
  appealDetail: '/mini/delivery/task/appeal/detail',
  appealEvidence: '/mini/delivery/task/appeal/evidence',
  mediaUpload: '/mini/delivery/media/upload',
} as const

/**
 * 容器规格 → 单桶水费（分）；配送费按桶固定 200 分。
 * 10L/20L 对齐 PC 共键快照口径（DT-2007=1200/桶、DT-2006/2008=1300/桶）；3L/5L 为原型占位。
 * 历史快照中的配送费差异（DT-2007 为 300/桶）按"下单时价格快照"语义保留；正式价目待商业一期确认。
 */
export const CONTAINER_WATER_PRICE_FEN: Record<DeliveryTask['containerSpec'], MoneyFen> = {
  '3L袋': 300,
  '5L桶': 500,
  '10L桶': 1200,
  '20L桶': 1300,
}
export const DELIVERY_FEE_PER_CONTAINER_FEN: MoneyFen = 200

/**
 * 容器规格 → 单桶水量（毫升），D-214 混合结算（payWay=3）的抵扣量换算表。
 * 与后端 DeliveryPricing.CONTAINER_WATER_ML 契约同源同值：规格是容器物理容量，
 * 服务端按同表折算并冻结进创单快照，前端只用于费用预览与快照展示的升数换算。
 */
export const CONTAINER_WATER_ML: Record<DeliveryTask['containerSpec'], number> = {
  '3L袋': 3000,
  '5L桶': 5000,
  '10L桶': 10000,
  '20L桶': 20000,
}

/** 本单抵扣水量（毫升）＝单桶水量×数量；白名单外规格/非法数量 fail-closed，与后端同口径。 */
export function deliveryWaterMl(containerSpec: DeliveryTask['containerSpec'], count: number): number {
  const unit = CONTAINER_WATER_ML[containerSpec]
  if (!unit) {
    throw new ContractError('DELIVERY_INPUT_INVALID', '容器规格不合法')
  }
  if (!Number.isSafeInteger(count) || count <= 0 || count > 99) {
    throw new ContractError('INVALID_DELIVERY_COUNT', '配送数量不合法')
  }
  return unit * count
}

/** 价格快照展示行（label/value 均为最终展示文案；total 行加粗）。 */
export interface DeliveryPriceLine {
  label: string
  value: string
  total?: boolean
}

/** 展示分流所需的最小任务形状（创单预览可用草稿对象构造同形入参）。 */
export interface DeliveryPriceView {
  payWay?: DeliveryPayWay
  containerSpec: DeliveryTask['containerSpec']
  plannedDeliveryCount: number
  priceSnapshot: DeliveryPriceSnapshot
}

/**
 * 价格快照展示行（D-214 展示分流唯一实现，U06/D01/D03/创单预览共用）：
 * payWay=3 时水费行呈现为「水量抵扣 X L」而不是 0 元水费——0 元会被读成免费，
 * 事实是水费以水量支付了；应扣合计仍如实等于快照 totalAmountFen（即配送费）。
 * payWay 缺省按 2（历史单只有余额口径）。
 */
export function deliveryPriceLines(view: DeliveryPriceView): DeliveryPriceLine[] {
  const snapshot = view.priceSnapshot
  if ((view.payWay ?? 2) !== 3) {
    return [
      { label: '水费', value: formatFen(snapshot.waterAmountFen) },
      { label: '配送费', value: formatFen(snapshot.deliveryFeeFen) },
      { label: '合计', value: formatFen(snapshot.totalAmountFen), total: true },
    ]
  }
  const ml = deliveryWaterMl(view.containerSpec, view.plannedDeliveryCount)
  return [
    { label: '水量抵扣', value: formatMl(ml) },
    { label: '配送费', value: formatFen(snapshot.deliveryFeeFen) },
    {
      label: '应扣合计',
      value: `${formatFen(snapshot.totalAmountFen)}（另抵扣 ${formatMl(ml)}）`,
      total: true,
    },
  ]
}

/** 列表紧凑合计：payWay=3 呈现「¥配送费+抵扣升数」，绝不把只剩配送费的金额当全部对价。 */
export function deliveryTotalText(view: DeliveryPriceView): string {
  if ((view.payWay ?? 2) !== 3) {
    return formatFen(view.priceSnapshot.totalAmountFen)
  }
  const ml = deliveryWaterMl(view.containerSpec, view.plannedDeliveryCount)
  return `${formatFen(view.priceSnapshot.totalAmountFen)}+${formatMl(ml)}`
}

/** 支付方式选项可用性（创单页实时展示）；disabled 时 reason 即禁用原因文案。 */
export interface DeliveryPayOption {
  payWay: DeliveryPayWay
  disabled: boolean
  reason?: string
}

/** 自动补货边界拒因（与后端/Mock 同文案）：规则表无支付方式列，周期单恒走余额。 */
const AUTO_REFILL_PAY_WAY_REASON = '自动补货暂仅支持水卡余额支付'

/**
 * 两种支付方式的实时可用性（与服务端拒因同文案，页面提示不另造第二套说法）：
 * 全余额需 余额≥水费+配送费；水量抵扣需 卡水量≥抵扣量 且 余额≥配送费，
 * 且自动补货方式下水量抵扣直接禁用（服务端同边界）。无卡时两项均禁用。
 * 最终裁决仍在服务端扣减事务（这里只是预检提示）。
 */
export function deliveryPayWayOptions(
  card: { balanceFen: number, balanceMl: number } | null,
  quote: { totalFen: number, deliveryFeeFen: number, waterMl: number },
  context: { autoRefill?: boolean } = {},
): DeliveryPayOption[] {
  if (!card) {
    return [
      { payWay: 2, disabled: true, reason: '当前账号暂无水卡，请先购卡后再下配送单' },
      { payWay: 3, disabled: true, reason: '当前账号暂无水卡，请先购卡后再下配送单' },
    ]
  }
  const balance: DeliveryPayOption = card.balanceFen >= quote.totalFen
    ? { payWay: 2, disabled: false }
    : { payWay: 2, disabled: true, reason: '水卡余额不足以支付本单水费与配送费' }
  let ml: DeliveryPayOption
  if (context.autoRefill) {
    ml = { payWay: 3, disabled: true, reason: AUTO_REFILL_PAY_WAY_REASON }
  }
  else if (card.balanceMl < quote.waterMl) {
    ml = { payWay: 3, disabled: true, reason: '水卡水量不足以抵扣本单水量' }
  }
  else if (card.balanceFen < quote.deliveryFeeFen) {
    ml = { payWay: 3, disabled: true, reason: '水卡余额不足以支付配送费' }
  }
  else {
    ml = { payWay: 3, disabled: false }
  }
  return [balance, ml]
}

// ==================== real 适配器（E2E-03 包B） ====================

/** 后端 MiniDeliveryCreateVo 原样结构。 */
interface DeliveryCreateRaw {
  order?: OrderDetailRaw | null
  task?: DeliveryTaskRaw | null
}

/** 三照/举证引用必须是已上传换取的受控媒体键；本地路径/伪引用在发出前就拒绝。 */
function requireMediaKeys(refs: string[], scene: string): string[] {
  for (const ref of refs) {
    if (!DELIVERY_MEDIA_KEY_PATTERN.test(ref)) {
      throw new ContractError('DELIVERY_MEDIA_NOT_UPLOADED', `${scene}前请先完成照片上传`)
    }
  }
  return refs
}

/**
 * delivery 域真实适配器：全部经 withRealSession（无正式会话不发请求；401 走
 * request.ts 全局失效处理清正式会话，绝不回退 Mock）。业务判定（状态机/范围/
 * 幂等/窗口）全在服务端，这里只做入参形态收口与响应归一化。
 */
const realDeliveryApi: DeliveryApi = {
  async createDeliveryOrder(input) {
    // 幂等键由页面持有并在同一次提交的重试间复用；缺失/形态不符直接拒绝，防重试双扣款
    if (!input.requestId || !DELIVERY_REQUEST_ID_PATTERN.test(input.requestId)) {
      throw new ContractError('DELIVERY_REQUEST_ID_INVALID', '配送下单请求异常，请返回重试')
    }
    // real 无地址簿域（包A 快照式输入）：地址与电话必须随单提交，服务端再做最终校验
    const receiveAddress = input.receiveAddress?.trim() ?? ''
    const receivePhone = input.receivePhone?.trim() ?? ''
    // addressId 引用模式：地址与号码由服务端按归属解引用写快照（号码不经前端）；直填模式才做形态预检
    if (!input.addressId) {
      if (!receiveAddress || receiveAddress.length > 200) {
        throw new ContractError('DELIVERY_ADDRESS_REQUIRED', '请填写收水地址（200 字以内）')
      }
      if (!/^1\d{10}$/.test(receivePhone)) {
        throw new ContractError('PHONE_INVALID', '请填写 11 位收货手机号')
      }
    }
    return withRealSession(async () => {
      const raw = await post<DeliveryCreateRaw>(deliveryEndpoints.create, {
        requestId: input.requestId,
        cardId: await requireRealPrimaryCardId(),
        stationId: input.stationId,
        waterTypeId: input.waterTypeId,
        containerSpec: input.containerSpec,
        deliveryCount: input.deliveryCount,
        planReturnCount: input.plannedReturnCount,
        addressId: input.addressId,
        receiveAddress: input.addressId ? undefined : receiveAddress,
        receivePhone: input.addressId ? undefined : receivePhone,
        deliveryMode: DELIVERY_MODE_TO_VALUE[input.deliveryMode],
        scheduledTime: input.deliveryMode === 'scheduled' ? input.scheduledTime : undefined,
        autoRefillIntervalDays: input.deliveryMode === 'auto-refill' ? input.autoRefillIntervalDays : undefined,
        // D-214：缺省显式发 2，冻结进服务端幂等快照（同 requestId 改 payWay 会被服务端拒绝）
        payWay: input.payWay ?? 2,
      })
      if (!raw?.order || !raw.task) {
        throw new ContractError('DELIVERY_CONTRACT_BROKEN', '配送下单未成功，请稍后重试')
      }
      return { order: normalizeOrderDetail(raw.order), task: normalizeDeliveryTask(raw.task) }
    })
  },
  async listTasks(view) {
    return withRealSession(async () => {
      const raw = await post<DeliveryTaskRaw[] | null>(deliveryEndpoints.list, { view })
      return (raw ?? []).map(normalizeDeliveryTask)
    })
  },
  async getTaskDetail(taskNo) {
    return withRealSession(async () => {
      const raw = await post<DeliveryTaskRaw>(deliveryEndpoints.detail, { taskNo })
      return normalizeDeliveryTask(raw)
    })
  },
  async acceptTask(taskNo, expectedVersion) {
    return withRealSession(async () => {
      const raw = await post<DeliveryTaskRaw>(deliveryEndpoints.accept, { taskNo, expectedVersion })
      return normalizeDeliveryTask(raw)
    })
  },
  async advanceTask(taskNo, targetStatus, expectedVersion) {
    return withRealSession(async () => {
      const raw = await post<DeliveryTaskRaw>(deliveryEndpoints.advance, {
        taskNo,
        targetStatus,
        expectedVersion,
      })
      return normalizeDeliveryTask(raw)
    })
  },
  async signTask(input) {
    // 三照引用必须是受控媒体键（上传换取）；服务端在签收事务内原子占用并校验归属/用途
    requireMediaKeys(input.photos.map(photo => photo.recordRef), '三照签收')
    return withRealSession(async () => {
      const raw = await post<DeliveryTaskRaw>(deliveryEndpoints.sign, {
        taskNo: input.taskNo,
        expectedVersion: input.expectedVersion,
        actualDeliveryCount: input.actualDeliveryCount,
        actualReturnCount: input.actualReturnCount,
        // 定位声明不得超出证据：只有显式 recorded 才声明已记录（1353：1已记录 2未记录）
        locationStatus: input.locationStatus === 'recorded' ? 1 : 2,
        photos: input.photos.map(photo => ({
          type: photo.type,
          mediaKey: photo.recordRef,
          latitude: photo.latitude,
          longitude: photo.longitude,
        })),
      })
      return normalizeDeliveryTask(raw)
    })
  },
  async getCourierAdmission() {
    return withRealSession(async () => {
      const raw = await post<CourierAdmissionRaw>(deliveryEndpoints.admissionDetail, {})
      return normalizeCourierAdmission(raw)
    })
  },
  // 准入建档/审核是 PC 人工链路（B09 已接真）；小程序端提交是未实现写路径，
  // 显式 pending 而非委托 Mock——宁可明确阻断也不给假申请成功（order 域同款口径）。
  submitCourierAdmission: () => realAdapterPending('提交配送准入申请', deliveryEndpoints.admissionSubmit),
  async reportException(input) {
    requireMediaKeys(input.evidenceRefs, '异常举证')
    return withRealSession(async () => {
      const raw = await post<DeliveryExceptionRaw>(deliveryEndpoints.exceptionReport, {
        taskNo: input.taskNo,
        expectedVersion: input.expectedVersion,
        // 契约字符码 → 后端 1354 整数值（唯一映射点在 delivery-normalize）
        reason: EXCEPTION_REASON_TO_VALUE[input.reason],
        description: input.description,
        evidenceRefs: input.evidenceRefs,
      })
      return normalizeDeliveryException(raw)
    })
  },
  async listTaskExceptions(taskNo) {
    return withRealSession(async () => {
      const raw = await post<DeliveryExceptionRaw[] | null>(deliveryEndpoints.exceptionList, { taskNo })
      return (raw ?? []).map(normalizeDeliveryException)
    })
  },
  async getTaskAppeal(taskNo) {
    return withRealSession(async () => {
      // 无申诉后端返回 null（code 0 data null），透传 null 与 mock 口径一致
      const raw = await post<DeliveryAppealRaw | null>(deliveryEndpoints.appealDetail, { taskNo })
      return raw ? normalizeDeliveryAppeal(raw) : null
    })
  },
  async appendAppealEvidence(input) {
    requireMediaKeys(input.evidenceRefs, '申诉举证')
    return withRealSession(async () => {
      const raw = await post<DeliveryAppealRaw>(deliveryEndpoints.appealEvidence, {
        taskNo: input.taskNo,
        appealId: input.appealId,
        description: input.description,
        evidenceRefs: input.evidenceRefs,
      })
      return normalizeDeliveryAppeal(raw)
    })
  },
}

/**
 * real 创单的支付卡：取本人主卡 ID（服务端仍强制归属与锁卡校验，铁律1/6）。
 * 经 cardApi 间接读取避免复制卡域归一化；card 域未接真时给出明确阻断而不是静默失败。
 */
async function requireRealPrimaryCardId(): Promise<EntityId> {
  if (currentMode('card') !== 'real') {
    throw new ContractError('DELIVERY_CARD_DOMAIN_MOCK', '暂时无法下单，请稍后重试')
  }
  const card = await cardApi.getPrimaryCard()
  if (!card) {
    throw new ContractError('CARD_MISSING', '当前账号暂无水卡，请先购卡后再下配送单')
  }
  return card.cardId
}

/** 媒体用途（页面语义命名）→ 后端 1签收三照 2申诉举证 3异常举证 4工单证据（E2E-05 机主申报）。 */
export type DeliveryMediaPurpose = 'sign' | 'appeal' | 'exception' | 'workorder'

const MEDIA_PURPOSE_TO_VALUE: Record<DeliveryMediaPurpose, number> = {
  sign: 1,
  appeal: 2,
  exception: 3,
  workorder: 4,
}

/** 各用途归属的接真域：上传门控按业务域判定（工单证据随 device 域接真，与配送三照互不牵连）。 */
const MEDIA_PURPOSE_DOMAIN: Record<DeliveryMediaPurpose, 'delivery' | 'device'> = {
  sign: 'delivery',
  appeal: 'delivery',
  exception: 'delivery',
  workorder: 'device',
}

/** 由本地文件扩展名推断 MIME（微信 chooseImage 临时文件带扩展名；未知按 JPEG）。 */
function mimeTypeOfFile(filePath: string): string {
  const lower = filePath.toLowerCase()
  if (lower.endsWith('.png')) {
    return 'image/png'
  }
  if (lower.endsWith('.webp')) {
    return 'image/webp'
  }
  return 'image/jpeg'
}

function readFileAsBase64(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    try {
      uni.getFileSystemManager().readFile({
        filePath,
        encoding: 'base64',
        success: res => resolve(res.data as string),
        fail: () => reject(new ContractError('DELIVERY_MEDIA_READ_FAILED', '读取照片失败，请重新选择')),
      })
    }
    catch {
      // H5 等无文件系统环境：读取即失败，绝不伪造上传成功
      reject(new ContractError('DELIVERY_MEDIA_READ_FAILED', '当前环境不支持读取本地照片'))
    }
  })
}

/**
 * 上传配送受控媒体（仅 delivery 域接真时可用）：读本地照片 → base64 →
 * POST /mini/delivery/media/upload → 受控媒体键。签收/举证只提交该键。
 */
export async function uploadDeliveryMedia(filePath: string, purpose: DeliveryMediaPurpose): Promise<string> {
  if (currentMode(MEDIA_PURPOSE_DOMAIN[purpose]) !== 'real') {
    throw new ContractError('MOCK_ONLY', '当前无法上传照片，请稍后重试')
  }
  const contentBase64 = await readFileAsBase64(filePath)
  return withRealSession(async () => {
    const raw = await post<{ mediaKey?: string | null }>(deliveryEndpoints.mediaUpload, {
      purpose: MEDIA_PURPOSE_TO_VALUE[purpose],
      mimeType: mimeTypeOfFile(filePath),
      contentBase64,
    })
    const mediaKey = raw?.mediaKey ?? ''
    if (!DELIVERY_MEDIA_KEY_PATTERN.test(mediaKey)) {
      throw new ContractError('DELIVERY_CONTRACT_BROKEN', '照片上传失败，请重试')
    }
    return mediaKey
  })
}

/**
 * 配送下单幂等键：规范小写 UUID v4。页面在一次提交意图内持有并于重试间复用；
 * 不用 crypto.randomUUID()——小程序运行时没有该 API（recharge 域同款实现依据）。
 */
export function newDeliveryRequestId(): string {
  const bytes = new Uint8Array(16)
  for (let i = 0; i < 16; i++) {
    bytes[i] = Math.floor(Math.random() * 256)
  }
  bytes[6] = (bytes[6] & 0x0F) | 0x40
  bytes[8] = (bytes[8] & 0x3F) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// delivery 域按域解锁（recharge 先例）：显式 VITE_API_MODE_DELIVERY='real' 才接真，漏配回落 Mock。
export const deliveryApi = realDeliveryApi
