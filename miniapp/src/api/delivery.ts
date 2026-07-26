import type { AccountContext } from './account'
import type {
  BusinessTime,
  EntityId,
  MockMeta,
  MoneyFen,
} from './common'
import type { DeliveryAppeal, OrderDetail, OrderDetailRaw } from './order'
import { cardApi } from './card'
import { requireCapability } from './capability'
import { cloneContractData, ContractError, prototypeMeta } from './common'
import { isFulfillableTask, requireFulfillableDeliveryOrder, requireLinkedDeliveryOrder } from './delivery-link'
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
import { currentMode, realAdapterPending, selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

export type DeliveryTaskStatus = 1 | 2 | 3 | 4 | 5 | 6 | 7
export type DeliveryTaskView = 'available' | 'active' | 'history'
export type SignPhotoType = 1 | 2 | 3
export type CourierAdmissionStatus = 0 | 1 | 2 | 3 | 4

export interface DeliveryPriceSnapshot {
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
  mockMeta?: MockMeta
}

export interface CreateDeliveryOrderInput {
  /** Mock 地址簿引用；real 模式无地址簿域（包A 冻结为快照式输入），可传空串。 */
  addressId: EntityId
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
  /** 收水地址快照（real 必填，≤200 字）；Mock 由 addressId 解析。 */
  receiveAddress?: string
  /** 收货电话（real 必填，11 位手机号；服务端入库供配送联系、出网必脱敏）；Mock 由 addressId 解析。 */
  receivePhone?: string
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

function accountIdOfUser(userId: EntityId): EntityId | undefined {
  return scenarioStore.accounts.find(item => item.userId === userId)?.accountId
}

/**
 * 统一逻辑时钟（第四/五轮审计整改）：动作时间统一取自 scenarioStore.takeBusinessTime，
 * 保证 创建≤接单≤离站≤送达≤签收===完成，且异常/举证不早于各自前置节点；
 * 必须在全部校验通过之后调用——拒绝路径连时钟都不允许推进。
 */
function takeActionTime(floorTimes: Array<BusinessTime | undefined>, offsetSeconds: number): BusinessTime {
  return scenarioStore.takeBusinessTime(floorTimes, offsetSeconds)
}

/** 履约节点回写订单轨迹：只接受 requireFulfillableDeliveryOrder 返回的订单记录，时间与任务动作同源。 */
function appendOrderTrace(
  record: OrderDetail,
  node: string,
  label: string,
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info',
  time: BusinessTime,
  detail?: string,
) {
  record.trace.push({ node, label, time, tone, detail })
}

/** 配送节点向下单用户发站内消息（REQ-059：至少覆盖离站与签收节点）。 */
function notifyOrderUser(task: DeliveryTask, title: string, content: string, sendTime: BusinessTime = scenarioStore.now) {
  const accountId = accountIdOfUser(task.userId)
  if (!accountId) {
    return
  }
  scenarioStore.pushMessage({
    accountId,
    domain: 'delivery',
    title,
    summary: `订单 ${task.orderNo}：${title}`,
    content,
    channel: 'in-app',
    sendStatus: 4,
    sendTime,
    unread: true,
    objectType: 'order',
    objectId: task.orderNo,
    requiredCapability: 'USER_BASE',
    evidenceMode: 'prototype',
  })
}

function maskPhone(phone: string) {
  if (!/^1\d{10}$/.test(phone)) {
    throw new ContractError('PHONE_INVALID', '手机号格式不合法')
  }
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

function courierContext(): AccountContext {
  const context = scenarioStore.activeAccount()
  requireCapability(context, 'COURIER_WORK')
  const scope = context.courierScope
  if (!scope || scope.status !== 2 || scope.stationIds.length === 0) {
    throw new ContractError('COURIER_SCOPE_DENIED', '配送范围未配置，默认不可接单')
  }
  return context
}

function taskByNo(taskNo: string) {
  const task = scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)
  if (!task) {
    throw new ContractError('TASK_NOT_FOUND', '配送任务不存在')
  }
  return task
}

function assertTaskVersion(task: DeliveryTask, expectedVersion: number) {
  if (task.version !== expectedVersion) {
    throw new ContractError('TASK_STALE', '任务状态已变化，请刷新后重试')
  }
}

function assertCourierCanAccept(context: AccountContext, task: DeliveryTask) {
  const scope = context.courierScope!
  if (!scope.stationIds.includes(task.stationId)) {
    throw new ContractError('TASK_OUT_OF_SCOPE', '任务不在当前配送范围')
  }
  if (task.userId === context.userId) {
    throw new ContractError('SELF_DELIVERY_FORBIDDEN', '同一账号不能配送自己的订单')
  }
  if (task.taskStatus !== 1 || task.courierId) {
    throw new ContractError('TASK_ALREADY_ACCEPTED', '任务已被领取或状态不可接单')
  }
}

/**
 * 只读证据访问收口（2026-07-18 第五轮审计整改）：
 * 申诉与异常证据只允许任务实际归属配送员查看——未分配可接任务、其他配送员（即使服务范围
 * 覆盖该水站）一律拒绝；任务-订单共键必须一致，孤儿/错位任务 fail-closed。
 * 纯校验：拒绝路径不写审计、不发消息、不推进时钟。
 */
function requireAssignedCourierEvidenceAccess(context: AccountContext, task: DeliveryTask): OrderDetail {
  if (!task.courierId || task.courierId !== context.courierScope!.courierId) {
    throw new ContractError('TASK_ACCESS_DENIED', '只有任务归属配送员可以查看申诉与异常证据')
  }
  return requireLinkedDeliveryOrder(task)
}

function assertCourierCanView(context: AccountContext, task: DeliveryTask) {
  const scope = context.courierScope!
  const canViewAvailable
    = task.taskStatus === 1
      && !task.courierId
      && task.userId !== context.userId
      && scope.stationIds.includes(task.stationId)
  const isMine = task.courierId === scope.courierId
  if (!canViewAvailable && !isMine) {
    throw new ContractError('TASK_ACCESS_DENIED', '无权查看该配送任务')
  }
}

const mockDeliveryApi: DeliveryApi = {
  async createDeliveryOrder(input) {
    if (input.deliveryCount <= 0 || input.plannedReturnCount < 0) {
      throw new ContractError('INVALID_DELIVERY_COUNT', '配送和回收数量不合法')
    }
    if (input.deliveryMode === 'scheduled') {
      if (!input.scheduledTime) {
        throw new ContractError('SCHEDULE_TIME_REQUIRED', '预约配送必须选择预约时间')
      }
      if (input.scheduledTime <= scenarioStore.now) {
        throw new ContractError('SCHEDULE_TIME_INVALID', '预约时间必须晚于当前时间')
      }
    }
    if (input.deliveryMode === 'auto-refill') {
      const interval = input.autoRefillIntervalDays
      if (!interval || interval < 3 || interval > 90) {
        throw new ContractError('AUTO_REFILL_RULE_REQUIRED', '自动补货需配置 3~90 天的固定周期')
      }
    }
    const account = scenarioStore.activeAccount()
    const address = scenarioStore.addresses.find(
      item => item.addressId === input.addressId && item.userId === account.userId,
    )
    const station = scenarioStore.stations.find(item => item.id === input.stationId)
    const waterType = scenarioStore.waterTypes.find(item => item.id === input.waterTypeId)
    if (!address || !station || !waterType) {
      throw new ContractError('DELIVERY_INPUT_INVALID', '地址、水站或水种无效')
    }
    if (station.status !== 'OPEN') {
      throw new ContractError('STATION_NOT_OPEN', '该水站检修或暂停营业中，暂不支持配送下单')
    }

    const waterAmountFen = input.deliveryCount * CONTAINER_WATER_PRICE_FEN[input.containerSpec]
    const deliveryFeeFen = input.deliveryCount * DELIVERY_FEE_PER_CONTAINER_FEN
    const totalAmountFen = waterAmountFen + deliveryFeeFen
    // 2026-07-18 决策：配送单默认水卡余额原型支付——校验余额后以"已支付"入池，
    // 消除"待支付订单可接单履约"矛盾；不改变卡面余额，不伪造微信支付。
    const card = scenarioStore.cards.find(item => item.userId === account.userId)
    if (!card) {
      throw new ContractError('CARD_MISSING', '当前账号暂无水卡，请先购卡后再下配送单')
    }
    if (card.cardStatus !== 1) {
      throw new ContractError('CARD_NOT_USABLE', '水卡状态不可用（冻结/过期/注销），无法支付配送单')
    }
    if (card.balanceFen < totalAmountFen) {
      throw new ContractError('INSUFFICIENT_BALANCE', '水卡余额不足以支付本单水费与配送费')
    }

    const sequence = scenarioStore.nextOrderSequence()
    const orderNo = `MD20260716${sequence}`
    const taskNo = `MDT-${sequence}`
    const createTime = takeActionTime([], 1)
    const order: OrderDetail = {
      order: {
        orderId: `MO-${sequence}`,
        orderNo,
        userId: account.userId,
        orderType: 3,
        orderStatus: 2,
        orderAmountFen: totalAmountFen,
        payWay: 2,
        cardId: card.cardId,
        stationId: station.id,
        stationName: station.stationName,
        createTime,
        mockMeta: { ...prototypeMeta },
      },
      trace: [
        {
          node: 'paid',
          label: '水卡余额原型支付',
          time: createTime,
          detail: '原型扣减：不改变卡面余额，不发生真实结算',
          tone: 'success',
        },
        {
          node: 'delivery-created',
          label: '配送任务已生成，等待接单',
          time: createTime,
          tone: 'primary',
        },
      ],
      flowCount: 0,
      deliveryTaskNo: taskNo,
    }
    const task: DeliveryTask = {
      taskId: `MT-${sequence}`,
      taskNo,
      orderId: order.order.orderId,
      orderNo,
      userId: account.userId,
      stationId: station.id,
      stationName: station.stationName,
      waterTypeId: waterType.id,
      waterTypeName: waterType.name,
      containerSpec: input.containerSpec,
      plannedDeliveryCount: input.deliveryCount,
      plannedReturnCount: input.plannedReturnCount,
      receiveAddress: `${address.region}${address.detail}`,
      maskedPhone: address.maskedPhone,
      priceSnapshot: {
        waterAmountFen,
        deliveryFeeFen,
        totalAmountFen,
      },
      taskStatus: 1,
      version: 1,
      signPhotos: [],
      mockMeta: { ...prototypeMeta },
    }
    scenarioStore.orderDetails.unshift(order)
    scenarioStore.deliveryTasks.unshift(task)
    notifyOrderUser(task, '配送任务已生成', `您的配送订单 ${orderNo} 已生成，等待配送员接单。`, createTime)
    scenarioStore.recordAudit('USER_BASE', 'delivery.order.create', 'task', taskNo, 'success', createTime)
    return cloneContractData({ order, task })
  },
  async listTasks(view) {
    const context = courierContext()
    const scope = context.courierScope!
    const list = scenarioStore.deliveryTasks.filter((task) => {
      if (view === 'available') {
        // fail-closed：与履约动作同源的精确判定——订单存在、共键一致且 orderStatus===2；
        // 孤儿/未支付/终态/关联错位任务一律不进入可接列表（2026-07-18 第三轮审计整改）。
        return (
          task.taskStatus === 1
          && !task.courierId
          && scope.stationIds.includes(task.stationId)
          && task.userId !== context.userId
          && isFulfillableTask(task)
        )
      }
      if (view === 'active') {
        return task.courierId === scope.courierId && [2, 3, 4, 7].includes(task.taskStatus)
      }
      return task.courierId === scope.courierId && [5, 6].includes(task.taskStatus)
    })
    return cloneContractData(list)
  },
  async getTaskDetail(taskNo) {
    const context = courierContext()
    const task = taskByNo(taskNo)
    assertCourierCanView(context, task)
    // 深链防护：未分配任务只有在关联订单可履约时才允许查看，
    // 阻断孤儿/未支付/取消/退款/关联错位任务经 URL 直达泄露收货地址（2026-07-18 第三轮审计整改）。
    if (task.courierId !== context.courierScope!.courierId) {
      requireFulfillableDeliveryOrder(task)
    }
    return cloneContractData(task)
  },
  async acceptTask(taskNo, expectedVersion) {
    const context = courierContext()
    const task = taskByNo(taskNo)
    assertTaskVersion(task, expectedVersion)
    assertCourierCanAccept(context, task)
    const orderRecord = requireFulfillableDeliveryOrder(task)
    const acceptTime = takeActionTime([orderRecord.order.createTime], 600)
    task.courierId = context.courierScope!.courierId
    task.taskStatus = 2
    task.version += 1
    task.acceptTime = acceptTime
    appendOrderTrace(orderRecord, 'accepted', '配送员已接单', 'info', acceptTime)
    notifyOrderUser(task, '配送员已接单', `订单 ${task.orderNo} 已由配送员接单，备货后将从水站出发。`, acceptTime)
    scenarioStore.recordAudit('COURIER_WORK', 'delivery.task.accept', 'task', taskNo, 'success', acceptTime)
    return cloneContractData(task)
  },
  async advanceTask(taskNo, targetStatus, expectedVersion) {
    const context = courierContext()
    const task = taskByNo(taskNo)
    assertTaskVersion(task, expectedVersion)
    if (task.courierId !== context.courierScope!.courierId) {
      throw new ContractError('TASK_ACCESS_DENIED', '只能推进本人已接任务')
    }
    const expectedCurrent = targetStatus === 3 ? 2 : 3
    if (task.taskStatus !== expectedCurrent) {
      throw new ContractError('INVALID_TASK_TRANSITION', '配送任务状态不允许该操作')
    }
    const orderRecord = requireFulfillableDeliveryOrder(task)
    const actionTime = targetStatus === 3
      ? takeActionTime([task.acceptTime], 600)
      : takeActionTime([task.departTime], 1800)
    task.taskStatus = targetStatus
    task.version += 1
    if (targetStatus === 3) {
      task.departTime = actionTime
      appendOrderTrace(orderRecord, 'departed', '水已离开水站', 'info', actionTime)
      notifyOrderUser(task, '水已离开水站', `订单 ${task.orderNo} 已由配送员取水离站，正在配送途中。`, actionTime)
    }
    else {
      task.arriveTime = actionTime
      appendOrderTrace(orderRecord, 'arrived', '已送达待确认', 'info', actionTime)
      notifyOrderUser(task, '已送达，等待签收确认', `订单 ${task.orderNo} 已送达收货地址，等待三照签收。`, actionTime)
    }
    scenarioStore.recordAudit('COURIER_WORK', 'delivery.task.advance', 'task', taskNo, 'success', actionTime)
    return cloneContractData(task)
  },
  async signTask(input) {
    const context = courierContext()
    const task = taskByNo(input.taskNo)
    assertTaskVersion(task, input.expectedVersion)
    if (task.courierId !== context.courierScope!.courierId || task.taskStatus !== 4) {
      throw new ContractError('SIGN_NOT_ALLOWED', '当前任务不能签收')
    }
    const photoTypes = new Set(input.photos.map(item => item.type))
    if (
      input.photos.length !== 3
      || photoTypes.size !== 3
      || ![1, 2, 3].every(type => photoTypes.has(type as SignPhotoType))
    ) {
      throw new ContractError('SIGN_PHOTO_INCOMPLETE', '门牌、水品、摆放三照缺一不可')
    }
    if (input.actualDeliveryCount <= 0 || input.actualReturnCount < 0) {
      throw new ContractError('INVALID_DELIVERY_COUNT', '实际配送或回收数量不合法')
    }
    // 定位证据一致性（2026-07-18 审计整改）：声明"原型快照"时，三照必须携带同一合法固定坐标，
    // 契约不信任调用方的单方声明，防止"文案宣称超出实际记录"。
    if ((input.locationStatus ?? 'unrecorded') === 'prototype-snapshot') {
      const first = input.photos[0]
      const consistent = input.photos.every(photo =>
        typeof photo.latitude === 'number'
        && typeof photo.longitude === 'number'
        && photo.latitude >= -90 && photo.latitude <= 90
        && photo.longitude >= -180 && photo.longitude <= 180
        && photo.latitude === first.latitude
        && photo.longitude === first.longitude,
      )
      if (!consistent) {
        throw new ContractError('LOCATION_EVIDENCE_INVALID', '定位快照声明与三照坐标不符：三照须携带同一合法固定坐标')
      }
    }
    const orderRecord = requireFulfillableDeliveryOrder(task)
    const signTime = takeActionTime([task.arriveTime], 600)
    task.actualDeliveryCount = input.actualDeliveryCount
    task.actualReturnCount = input.actualReturnCount
    // 权威照片时间由签收动作统一写入，不采信页面传入的草稿时间（第五轮审计整改）。
    task.signPhotos = cloneContractData(input.photos).map(photo => ({
      ...photo,
      time: signTime,
    }))
    task.signTime = signTime
    task.taskStatus = 5
    task.version += 1
    task.locationStatus = input.locationStatus ?? 'unrecorded'
    // 订单终态闭合：签收即订单完成，完成时间与任务签收时间同源。
    appendOrderTrace(orderRecord, 'signed', '三照签收，订单完成', 'success', signTime)
    orderRecord.order.orderStatus = 4
    orderRecord.order.finishTime = signTime
    notifyOrderUser(
      task,
      '订单已签收',
      `订单 ${task.orderNo} 已完成三照签收；如有异议，可在签收后 24 小时内发起申诉。`,
      signTime,
    )
    scenarioStore.recordAudit('COURIER_WORK', 'delivery.task.sign', 'task', input.taskNo, 'success', signTime)
    return cloneContractData(task)
  },
  async getCourierAdmission() {
    const context = scenarioStore.activeAccount()
    requireCapability(context, 'COURIER_APPLY')
    const admission = scenarioStore.courierAdmissions.find(
      item => item.accountId === context.accountId,
    )
    if (!admission) {
      throw new ContractError('COURIER_ADMISSION_NOT_FOUND', '配送准入记录不存在')
    }
    return cloneContractData(admission)
  },
  async submitCourierAdmission(input) {
    const context = scenarioStore.activeAccount()
    requireCapability(context, 'COURIER_APPLY')
    const admission = scenarioStore.courierAdmissions.find(
      item => item.accountId === context.accountId,
    )
    if (!admission || ![0, 4].includes(admission.status)) {
      throw new ContractError('COURIER_ADMISSION_NOT_ALLOWED', '当前准入状态不能重复提交')
    }
    if (!input.applicantName.trim() || !input.declarationAccepted) {
      throw new ContractError('COURIER_ADMISSION_INVALID', '申请人和必要声明不能为空')
    }
    if (!input.requestedRegion?.trim() && input.requestedStationIds.length === 0) {
      throw new ContractError('COURIER_SCOPE_REQUIRED', '申请服务区域或水站至少填写一项')
    }
    admission.applicantName = input.applicantName.trim()
    admission.maskedPhone = maskPhone(input.phone)
    admission.requestedStationIds = [...input.requestedStationIds]
    admission.requestedRegion = input.requestedRegion?.trim() || undefined
    const submitTime = takeActionTime([], 1)
    admission.submittedTime = submitTime
    admission.status = 1
    admission.rejectReason = undefined
    scenarioStore.recordAudit(
      'COURIER_APPLY',
      'courier.admission.submit',
      'courier-admission',
      context.accountId,
      'success',
      submitTime,
    )
    return cloneContractData(admission)
  },
  async reportException(input) {
    const context = courierContext()
    const task = taskByNo(input.taskNo)
    assertTaskVersion(task, input.expectedVersion)
    if (
      task.courierId !== context.courierScope!.courierId
      || ![2, 3, 4].includes(task.taskStatus)
    ) {
      throw new ContractError('EXCEPTION_NOT_ALLOWED', '当前任务不能上报配送异常')
    }
    if (!input.description.trim()) {
      throw new ContractError('EXCEPTION_DESCRIPTION_REQUIRED', '异常说明不能为空')
    }
    // 全部校验（含订单关联与可履约状态）通过之后才允许产生任何写入；
    // 校验失败时异常记录、订单轨迹、消息、审计与任务字段一律零副作用（2026-07-18 第三轮审计整改）。
    const orderRecord = requireFulfillableDeliveryOrder(task)
    // 异常时间不得早于任务当前最后已发生节点（第四轮统一逻辑时钟）。
    const exceptionTime = takeActionTime(
      [orderRecord.order.createTime, task.acceptTime, task.departTime, task.arriveTime],
      60,
    )
    const record: DeliveryExceptionRecord = {
      exceptionId: `DEX-${scenarioStore.deliveryExceptions.length + 1}`,
      taskNo: input.taskNo,
      courierId: context.courierScope!.courierId,
      reason: input.reason,
      description: input.description.trim(),
      evidenceRefs: [...input.evidenceRefs],
      createTime: exceptionTime,
    }
    scenarioStore.deliveryExceptions.push(record)
    appendOrderTrace(
      orderRecord,
      'exception',
      '配送异常已登记',
      'warning',
      exceptionTime,
      `${record.reason}：${record.description}`,
    )
    scenarioStore.recordAudit(
      'COURIER_WORK',
      'delivery.task.exception.report',
      'task',
      input.taskNo,
      'success',
      exceptionTime,
    )
    return cloneContractData(record)
  },
  async listTaskExceptions(taskNo) {
    const context = courierContext()
    const task = taskByNo(taskNo)
    requireAssignedCourierEvidenceAccess(context, task)
    return cloneContractData(
      scenarioStore.deliveryExceptions.filter(
        item => item.taskNo === taskNo && item.courierId === task.courierId,
      ),
    )
  },
  async getTaskAppeal(taskNo) {
    const context = courierContext()
    const task = taskByNo(taskNo)
    const record = requireAssignedCourierEvidenceAccess(context, task)
    const appeal = scenarioStore.deliveryAppeals.find(
      item => item.taskNo === taskNo && item.orderNo === record.order.orderNo,
    )
    return appeal ? cloneContractData(appeal) : null
  },
  async appendAppealEvidence(input) {
    const context = courierContext()
    const task = taskByNo(input.taskNo)
    if (task.courierId !== context.courierScope!.courierId) {
      throw new ContractError('TASK_ACCESS_DENIED', '只能就本人任务的申诉举证')
    }
    if (task.taskStatus !== 7) {
      throw new ContractError('APPEAL_NOT_ACTIVE', '任务当前不在申诉中，无法追加举证')
    }
    // 申诉发生在已完成订单上，走关联校验而非履约支付栅栏：
    // 要求任务 7 / 订单 4 / 申诉 1 且任务-订单-申诉三方共键一致（2026-07-18 第三轮审计整改）。
    const orderRecord = requireLinkedDeliveryOrder(task)
    if (orderRecord.order.orderStatus !== 4) {
      throw new ContractError('APPEAL_ORDER_STATE_INVALID', '申诉举证要求关联订单处于已完成状态')
    }
    const appeal = scenarioStore.deliveryAppeals.find(
      item => item.appealId === input.appealId
        && item.taskNo === input.taskNo
        && item.orderNo === orderRecord.order.orderNo,
    )
    if (!appeal || appeal.appealStatus !== 1) {
      throw new ContractError('APPEAL_NOT_FOUND', '申诉不存在、共键不一致或已裁决')
    }
    if (!input.description.trim()) {
      throw new ContractError('EVIDENCE_DESCRIPTION_REQUIRED', '举证说明不能为空')
    }
    appeal.courierEvidences = appeal.courierEvidences ?? []
    // 举证时间不得早于签收、申诉创建与上一份举证（第四轮统一逻辑时钟）。
    const previousEvidenceTime = appeal.courierEvidences[appeal.courierEvidences.length - 1]?.time
    const evidenceTime = takeActionTime(
      [task.signTime, appeal.createTime, previousEvidenceTime],
      60,
    )
    appeal.courierEvidences.push({
      description: input.description.trim(),
      evidenceRefs: [...input.evidenceRefs],
      time: evidenceTime,
    })
    scenarioStore.recordAudit(
      'COURIER_WORK',
      'delivery.appeal.evidence',
      'appeal',
      input.appealId,
      'success',
      evidenceTime,
    )
    return cloneContractData(appeal)
  },
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
      throw new ContractError('DELIVERY_MEDIA_NOT_UPLOADED', `${scene}必须先完成照片上传（受控媒体键缺失）`)
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
      throw new ContractError('DELIVERY_REQUEST_ID_INVALID', '配送下单缺少合法幂等标识，请返回重试')
    }
    // real 无地址簿域（包A 快照式输入）：地址与电话必须随单提交，服务端再做最终校验
    const receiveAddress = input.receiveAddress?.trim() ?? ''
    const receivePhone = input.receivePhone?.trim() ?? ''
    if (!receiveAddress || receiveAddress.length > 200) {
      throw new ContractError('DELIVERY_ADDRESS_REQUIRED', '请填写收水地址（200 字以内）')
    }
    if (!/^1\d{10}$/.test(receivePhone)) {
      throw new ContractError('PHONE_INVALID', '请填写 11 位收货手机号')
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
        receiveAddress,
        receivePhone,
        deliveryMode: DELIVERY_MODE_TO_VALUE[input.deliveryMode],
        scheduledTime: input.deliveryMode === 'scheduled' ? input.scheduledTime : undefined,
        autoRefillIntervalDays: input.deliveryMode === 'auto-refill' ? input.autoRefillIntervalDays : undefined,
      })
      if (!raw?.order || !raw.task) {
        throw new ContractError('DELIVERY_CONTRACT_BROKEN', '配送下单响应缺少订单或任务')
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
    throw new ContractError('DELIVERY_CARD_DOMAIN_MOCK', 'card 域未接真：配送真实下单要求真实水卡数据源')
  }
  const card = await cardApi.getPrimaryCard()
  if (!card) {
    throw new ContractError('CARD_MISSING', '当前账号暂无水卡，请先购卡后再下配送单')
  }
  return card.cardId
}

/** 媒体用途（页面语义命名）→ 后端 1签收三照 2申诉举证 3异常举证。 */
export type DeliveryMediaPurpose = 'sign' | 'appeal' | 'exception'

const MEDIA_PURPOSE_TO_VALUE: Record<DeliveryMediaPurpose, number> = {
  sign: 1,
  appeal: 2,
  exception: 3,
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
  if (currentMode('delivery') !== 'real') {
    throw new ContractError('MOCK_ONLY', 'Mock 构建不上传照片（本地记录即完整边界）')
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
      throw new ContractError('DELIVERY_CONTRACT_BROKEN', '媒体登记响应缺少合法受控媒体键')
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
export const deliveryApi = selectAdapter(mockDeliveryApi, realDeliveryApi, 'delivery')
