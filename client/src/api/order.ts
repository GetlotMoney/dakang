/**
 * 订单中心 API 契约。
 * H2-FE 已接真：fetchOrderPage / fetchOrderTrace 走管理端只读接口（/order/order/page、/order/order/trace），
 * 读真实 ws_order/ws_command/ws_wallet_flow；后端 Long 序列化为字符串，见下方归一化。
 * E2E-03 包C 已接真：fetchDeliveryTaskPage / fetchDeliveryTaskDetail（/order/delivery/**）、
 * fetchAppealPage / fetchAppealEvidence / fetchDecideAppeal（/order/appeal/**）走真实配送域接口；
 * 追溯的 delivery/appeals/auditEvents 三区块对配送单返回真实聚合（deliveryTrace，履约链/资金链
 * 分层 fail-closed），照片仅呈现受控媒体元数据（一期无媒体下载出口，不渲染图片字节）。
 * E2E-04 包E：售后退款与补偿的 API 全部在 @/api/after-sale.ts，本文件不再承载该域。
 * Mock 追溯数据源（fetchOrderTraceMock + MOCK_* 固定样例）已随 Mock 期结束整体退役（2026-08-02）：
 * 全部页面接真后无任何发起方，追溯一律走真实接口。
 *
 * 字段口径与真实表严格对齐：ws_order / ws_delivery_task / ws_delivery_appeal / ws_command（见 server/sql）。
 * 字典：订单类型1340 订单状态1341 支付方式1346 指令类型1320 指令状态1321 任务状态1351 申诉状态1352。
 * 金额一律"分"（bigint→number）；水量一律"毫升"；时间 varchar(14) yyyyMMddHHmmss。
 */

import request from '@/utils/http'
import {
  normalizeRechargePackageSnapshot,
  packageSnapshotStateOf,
  type PackageSnapshotState,
  type RechargePackageSnapshot
} from '@/utils/recharge-snapshot'

// ============ 类型定义 ============

/** 统一返回格式（与后端 R<T> 对齐：code=0 成功） */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

/** 分页格式（与后端 PageDataVo 对齐） */
export interface PageResult<T> {
  list: T[]
  total: number
}

export type PaymentEvidenceState = 'valid' | 'missing' | 'invalid'

/**
 * 订单列表项（ws_order）。
 * 数据库 Long 型 ID 一律保持 string（2026-07-20 收口轮 P1：Number 超过 2^53 丢精度会查错/查不到订单）；
 * 仅金额、水量、序号、状态等可计算字段转 number。
 */
export interface OrderItem {
  id: string
  orderNo: string
  /** 订单类型(1340)：1扫码取水 2购卡充值 3水配送 */
  orderType: number
  userId: string
  userName?: string
  userPhone?: string
  stationId?: string
  stationName?: string
  deviceId?: string
  deviceNo?: string
  outletId?: string
  outletNo?: number
  cardId?: string
  cardNo?: string
  /** 实际使用人脱敏手机号（服务端已脱敏；空串=号码异常整体屏蔽，不回落明文）。 */
  actorMaskedPhone?: string
  /** 持卡人用户ID（经 CARD_ID → ws_card.USER_ID；卡缺失/被删时为空）。 */
  cardOwnerUserId?: string
  /** 持卡人姓名（关联 ws_user 派生）。 */
  cardOwnerName?: string
  /** 持卡人脱敏手机号（服务端已脱敏）。 */
  cardOwnerMaskedPhone?: string
  /** 用卡角色：OWNER=使用人即持卡人 / MEMBER=成员用卡；服务端判定下发，前端不推导。 */
  accessRole?: 'OWNER' | 'MEMBER'
  /** 卡当前余额(分)，追溯只读快照；卡缺失时为空。 */
  cardBalanceFen?: number
  /** 卡当前剩余水量(毫升)，追溯只读快照；卡缺失时为空。 */
  cardBalanceMl?: number
  packageId?: string
  /** 套餐快照JSON（充值单） */
  packageSnap?: string
  /** API 归一化后的套餐快照展示字段。 */
  packageSnapshot?: RechargePackageSnapshot
  /** 原始快照缺失或结构异常时显式 fail-closed。 */
  packageSnapshotState?: PackageSnapshotState
  /** 计划出水量(毫升) */
  planMl?: number
  /** 实际出水量(毫升) */
  actualMl?: number
  /** 订单金额(分) */
  orderAmount: number
  /** 支付方式(1346)：1微信支付 2水卡余额 3水卡水量 */
  payWay: number
  /** 支付状态：1待支付 2支付成功 3支付失败 4已关闭；无支付记录时为空。 */
  payStatus?: number
  /** 支付来源：1微信支付 2Pay-Sim；无支付记录或来源异常时为空。 */
  paySource?: number
  /** 支付平台交易号保持 string，避免长数字或混合编号被数值化。 */
  transactionId?: string
  /** 不可变付款截止时间，yyyyMMddHHmmss。 */
  payExpireTime?: string
  /** 权威支付成功时间，yyyyMMddHHmmss。 */
  paySuccessTime?: string
  /** 支付记录逻辑删除状态；0正常，非0必须按异常证据展示。 */
  paymentDataStatus?: number
  /** 订单状态(1341)：1待支付 2已支付 3出水中 4已完成 5已取消 6异常待补偿 7已退款 8部分退款 */
  orderStatus: number
  cmdId?: string
  finishTime?: string
  cancelReason?: string
  createTime: string
}

/** 指令时间线节点（追溯区块二：订单→指令→ACK→结果） */
export interface CommandTraceNode {
  /** 节点：created下发 / ack回执 / result结果 / timeout超时 */
  node: string
  nodeLabel: string
  time: string
  /** 附加说明（如失败原因/实际水量） */
  detail?: string
  /** 时间线着色 */
  tone: 'success' | 'warning' | 'danger' | 'info' | 'primary'
}

/** 指令追溯（ws_command）；linkStatus=mismatch 时后端不提供类型/状态/报文/时间线 */
export interface CommandTrace {
  cmdId: string
  cmdNo: string
  /** 指令类型(1320)；mismatch 时无值 */
  cmdType?: number
  /** 指令状态(1321)；mismatch 时无值 */
  cmdStatus?: number
  deviceNo: string
  payload?: string
  timeline: CommandTraceNode[]
  /** 订单-指令共键校验：ok 一致；mismatch 关联异常（后端不提供载荷与时间线）；历史数据缺此字段视为 ok */
  linkStatus?: 'ok' | 'mismatch'
  /** linkStatus=mismatch 时的原因说明 */
  linkReason?: string
}

/** 流水追溯（ws_wallet_flow 简化投影） */
export interface FlowTrace {
  flowId: string
  /** 流水类型：扣水量/扣余额/微信支付/退款 */
  flowType: string
  /** 变动值（分或毫升，见 unit） */
  amount: number
  unit: '分' | '毫升'
  /** 该笔流水后的卡余额快照(分)（AMOUNT_AFTER）；本单最后一条有效流水的该值=「本单结算后余额」；历史流水可能缺失。 */
  amountAfter?: number
  /** 该笔流水后的卡水量快照(毫升)（ML_AFTER）；本单最后一条有效流水的该值=「本单结算后水量」；历史流水可能缺失。 */
  mlAfter?: number
  time: string
  remark?: string
}

/**
 * 充值追溯的服务端核验结果。页面只消费服务端已经完成共键核验的结构化证据，
 * 不读取 PACKAGE_SNAP 原文，也不在浏览器内重算发卡或入账规则。
 */
export interface RechargeTraceDetail {
  snapshotValid: boolean
  purchaseMode: 'FIRST_CARD' | 'EXISTING_CARD'
  cardId?: string
  cardNo?: string
  cardType?: number
  issueOrderId?: string
  scopeDescription?: string
  packageName: string
  payAmountFen: number
  waterMl: number
  bonusAmountFen: number
  expireDays?: number
  payStatus: number
  paySource: number
  processingStatus: string
  flowAmountChange?: number
  flowMlChange?: number
  flowAmountAfter?: number
  flowMlAfter?: number
  cardBalanceFen?: number
  cardBalanceMl?: number
  cardExpireTime?: string
}

export interface RechargeTrace {
  linkStatus: 'ok' | 'mismatch'
  linkReason?: string
  detail?: RechargeTraceDetail
}

// ============ 配送域真实契约（E2E-03 包C，/order/delivery/** 与 /order/appeal/**） ============

/** 受控媒体引用元数据；非 ok 时后端不下发 mime/大小等正向元数据（fail-closed）。 */
export interface AdminMediaRef {
  mediaKey: string
  mimeType?: string
  sizeBytes?: number
  uploadTime?: string
  mediaStatus: 'ok' | 'missing' | 'invalid'
  mediaReason?: string
}

/** 签收三照元数据：type/时间/GPS 来自签收事务落库 JSON；PC 只展示元数据，不渲染图片。 */
export interface AdminSignPhotoMeta extends AdminMediaRef {
  type: number
  typeLabel?: string
  time?: string
  latitude?: number
  longitude?: number
}

/** 履约时间线节点；done 由服务端按落库时间判定，页面不自行推导。 */
export interface DeliveryTimelineNode {
  node: string
  nodeLabel: string
  time?: string
  detail?: string
  done: boolean
}

/** 配送异常记录（只插入不更新，PC 只读）。 */
export interface DeliveryExceptionAdmin {
  exceptionId: string
  reason?: number
  reasonLabel?: string
  description?: string
  evidenceRefs: AdminMediaRef[]
  createTime?: string
}

/** 管理端配送任务列表项（真实接口；手机号为服务端 PhoneMask 脱敏值）。 */
export interface DeliveryTaskAdminItem {
  taskId: string
  taskNo: string
  orderId?: string
  orderNo?: string
  orderStatus?: number
  userId?: string
  userName?: string
  userMaskedPhone?: string
  receiveMaskedPhone?: string
  courierId?: string
  courierName?: string
  courierMaskedPhone?: string
  stationId?: string
  stationName?: string
  waterTypeId?: string
  waterTypeName?: string
  containerSpec?: string
  deliveryCount?: number
  actualDeliveryCount?: number
  planReturnCount?: number
  actualReturnCount?: number
  waterAmountFen?: number
  deliveryFeeFen?: number
  /** 总额=水费+配送费（服务端求和；任一侧缺失为 undefined，不用 0 冒充）。 */
  totalAmountFen?: number
  /** 支付方式(1346)：2水卡余额 3水卡水量+余额付配送费（D-214，关联 ws_order 派生）。 */
  payWay?: number
  receiveAddress?: string
  taskStatus: number
  scheduledTime?: string
  acceptTime?: string
  departTime?: string
  arriveTime?: string
  signTime?: string
  appealDeadline?: string
  /** 签收定位记录状态(1353)：1已记录 2未记录，签收前为空。 */
  locationStatus?: number
  createTime?: string
}

/** 任务详情：linkStatus=mismatch 时后端只保留标识与原因，正向履约证据不下发。 */
export interface DeliveryTaskAdminDetail extends DeliveryTaskAdminItem {
  linkStatus: 'ok' | 'mismatch'
  linkReason?: string
  timeline?: DeliveryTimelineNode[]
  signPhotos?: AdminSignPhotoMeta[]
  exceptions?: DeliveryExceptionAdmin[]
}

/**
 * 管理端申诉列表项（真实接口）。
 *
 * 分页列表下发的是<b>案件聚合行</b>（一行 = 一个 taskId，D-215）：`appealId` 及原因、
 * 实收、状态、处理人等展示字段取代表申诉（活跃申诉优先，无活跃取最近一条），
 * 因此 `appealStatus` 就是案件当前状态；裁决/证据接口继续用 `appealId`。
 * 聚合列（appealCount / activeAppealId / first-lastAppealTime）只在分页下发，
 * 证据详情的 `appeal` 与 `appealHistory` 条目上为空。
 */
export interface AppealAdminItem {
  appealId: string
  taskId?: string
  taskNo?: string
  orderId?: string
  orderNo?: string
  userId?: string
  userName?: string
  userMaskedPhone?: string
  /** 申诉原因码：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER */
  appealReason?: string
  appealReasonLabel?: string
  appealDesc?: string
  receivedCount?: number
  /** 申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行 */
  appealStatus: number
  handleBy?: string
  handleByName?: string
  handleTime?: string
  handleResult?: string
  createTime?: string
  /** 该任务累计申诉次数；>1 表示反复申诉，列表必须显式提示运营。 */
  appealCount?: number
  /** 当前待裁决申诉ID；为空表示案件无待处理申诉（后端唯一键保证至多一条）。 */
  activeAppealId?: string
  /** 案件内最早申诉时间。 */
  firstAppealTime?: string
  /** 案件内最近申诉时间（列表排序与「申诉时间」列取此值）。 */
  lastAppealTime?: string
}

/** 配送员申诉举证（COURIER_EVIDENCES JSON 投影）。 */
export interface CourierEvidenceAdmin {
  description?: string
  time?: string
  evidenceRefs: AdminMediaRef[]
}

/** 申诉证据详情：linkStatus=mismatch 时任务详情与举证媒体不下发（申诉自述字段保留）。 */
export interface AppealAdminEvidence {
  appeal: AppealAdminItem
  linkStatus: 'ok' | 'mismatch'
  linkReason?: string
  /**
   * 同任务申诉往来，按申诉时间正序（含当前查看的这条）。
   * 历史条目只有文本与裁决结论，媒体元数据仍只针对当前 appealId 那条申诉。
   * linkStatus=mismatch 时不下发。
   */
  appealHistory?: AppealAdminItem[]
  appealPhotos?: AdminMediaRef[]
  courierEvidences?: CourierEvidenceAdmin[]
  task?: DeliveryTaskAdminDetail
}

/** 补偿策略码（E2E-04 R0-2）；与后端 AfterSaleEnum.StrategyCode 一一对应。 */
export type AppealStrategyCode =
  | 'PRODUCT_ONLY'
  | 'SERVICE_FEE_ONLY'
  | 'PRODUCT_AND_SERVICE'
  | 'RESEND'
  | 'REJECT'

/**
 * 裁决提交：**只提交策略码 + 批准数量 + 说明**。
 *
 * 申诉终态（2成立待补偿 / 3不成立驳回 / 5补送待执行）由服务端从策略码唯一派生，
 * 前端不再传 outcome —— 两套码并存必然出现"驳回却带补偿策略"这种自相矛盾的入参。
 * 金额与水量同样由服务端按订单快照计算，本契约刻意不含任何金额字段：
 * 让前端决定退多少钱是资金事故的起点。
 */
export interface AppealDecideData {
  id: string
  strategyCode: AppealStrategyCode
  /** 受影响数量(桶)；仅资金类策略需要，RESEND/REJECT 传空。上界由服务端判定 */
  approvedCount?: number
  handleResult: string
}

/** 卡扣款流水核验（DELIVERY:订单号 幂等键）；mismatch 只有原因，无正向流水证据。 */
export interface DeliveryPaymentTrace {
  flowStatus: 'ok' | 'mismatch'
  flowReason?: string
  flowId?: string
  bizKey?: string
  amountChangeFen?: number
  amountAfterFen?: number
  /** 水量变动(毫升)，payWay=3 抵扣为负；payWay=2 恒 0（D-214）。 */
  mlChange?: number
  /** 扣减后卡水量快照(毫升)（流水写入时冻结）。 */
  mlAfter?: number
  time?: string
  remark?: string
}

/** 配送站内消息证据（一期只有站内渠道；4=站内落库即送达）。 */
export interface DeliveryNotificationReal {
  id: string
  title?: string
  content?: string
  sendStatus?: number
  sendTime?: string
  objectType?: string
  objectId?: string
}

/** 追溯·配送区块（真实聚合）：履约链 linkStatus 与资金链 payment.flowStatus 独立核验。 */
export interface RealDeliveryTrace {
  linkStatus: 'ok' | 'mismatch'
  linkReason?: string
  taskId?: string
  taskNo?: string
  taskStatus?: number
  userName?: string
  userMaskedPhone?: string
  courierName?: string
  courierMaskedPhone?: string
  stationName?: string
  waterTypeName?: string
  containerSpec?: string
  deliveryCount?: number
  actualDeliveryCount?: number
  planReturnCount?: number
  actualReturnCount?: number
  waterAmountFen?: number
  deliveryFeeFen?: number
  totalAmountFen?: number
  /** 支付方式(1346)：2水卡余额 3水卡水量+余额付配送费（D-214）。 */
  payWay?: number
  /** payWay=3 的水量抵扣(毫升)，来源创单冻结快照；余额支付为空。 */
  deductWaterMl?: number
  receiveAddress?: string
  receiveMaskedPhone?: string
  scheduledTime?: string
  appealDeadline?: string
  locationStatus?: number
  timeline?: DeliveryTimelineNode[]
  signPhotos?: AdminSignPhotoMeta[]
  payment?: DeliveryPaymentTrace
  notifications?: DeliveryNotificationReal[]
}

/** 追溯·审计事件行：Demo 共享审计与真实领域事件的公共展示形状。 */
export interface TraceAuditEvent {
  id: string | number
  eventTypeLabel: string
  eventKey: string
  actorLabel: string
  detail?: string
  time: string
  tone?: 'success' | 'warning' | 'danger' | 'info' | 'primary'
}

/**
 * 申诉摘要（挂在订单追溯里）；Long ID 一律 string。
 * 真实聚合行带 linkStatus（逐行共键核验）：mismatch 行只有 appealId 与 linkReason，
 * 正向内容（状态/理由/裁决）不下发；缺 linkStatus 的历史行视为 ok。
 */
export interface AppealBrief {
  appealId: string
  linkStatus?: 'ok' | 'mismatch'
  linkReason?: string
  /** 申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行 */
  appealStatus?: number
  appealReason?: string
  appealReasonLabel?: string
  appealDesc?: string
  receivedCount?: number
  handleTime?: string
  handleResult?: string
  createTime?: string
}

/** 订单全链路追溯（详情抽屉六区块数据源，REQ-050 MVP 验收核心） */
export interface OrderTraceVo {
  order: OrderItem
  /** 充值单的支付、发卡、流水与卡终值共键核验结果。 */
  recharge?: RechargeTrace
  /** 区块二：指令与回执时间线（取水单） */
  command?: CommandTrace
  /** 区块三：支付/扣减流水 */
  flows: FlowTrace[]
  /** 区块四（真实）：配送单真实聚合区块（履约链/资金链分层核验，E2E-03 包C）。 */
  deliveryTrace?: RealDeliveryTrace
  /** 区块五：申诉记录 */
  appeals: AppealBrief[]
  /** 区块六：审计事件（Mock=共享 Demo 审计；真实=配送单领域事件聚合）。 */
  auditEvents: TraceAuditEvent[]
}

/** 追溯 Mock 数据源使用的配送签收照片（演示样例专用；真实链路只出媒体元数据）。 */
export interface AppealEvidencePhoto {
  type: number
  label: string
  url: string
  time?: string
  lat?: number
  lng?: number
}

/** 订单分页查询参数 */
export interface OrderSearchParams {
  current: number
  size: number
  orderNo?: string
  orderType?: number
  orderStatus?: number
  userKeyword?: string
}

const parseSignPhotos = (raw?: string): AppealEvidencePhoto[] => {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []

    return parsed.flatMap((item): AppealEvidencePhoto[] => {
      if (!item || typeof item !== 'object') return []
      const record = item as Record<string, unknown>
      const url = typeof record.url === 'string' ? record.url : ''
      if (!url) return []

      const type = Number(record.type)
      return [
        {
          type,
          label: type === 1 ? '门牌照' : type === 2 ? '水品照' : '摆放照',
          url,
          time: typeof record.time === 'string' ? record.time : undefined,
          lat: typeof record.lat === 'number' ? record.lat : undefined,
          lng: typeof record.lng === 'number' ? record.lng : undefined
        }
      ]
    })
  } catch {
    return []
  }
}

// ============ 接真归一化（H2-FE：订单本体 + 追溯）============
// 后端 Long 型经全局 Jackson 序列化为字符串以防 JS 精度丢失（id/userId/orderAmount/planMl 等到前端是数值字符串）。
// 数据库 Long ID 一律保持 string（2026-07-20 收口轮 P1：Number 超过 2^53 丢精度会查错/查不到订单）；
// 仅金额、水量、序号、状态等参与算术/比较的可计算字段转回 number（字符串按字典序比较会误判）。
const toNum = (v: unknown): number | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

const toIdStr = (v: unknown): string | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  return String(v)
}

/** 支付证据的枚举/状态字段只接受安全整数或十进制整数字符串。 */
const toEvidenceNum = (v: unknown): number | undefined => {
  if (typeof v === 'number') return Number.isSafeInteger(v) ? v : undefined
  if (typeof v !== 'string' || !/^-?(?:0|[1-9]\d*)$/.test(v)) return undefined
  const parsed = Number(v)
  return Number.isSafeInteger(parsed) ? parsed : undefined
}

/** 交易号和业务时间只接受字符串/数字标量，拒绝对象被隐式转成 [object Object]。 */
const toEvidenceStr = (v: unknown): string | undefined => {
  if (typeof v !== 'string' && typeof v !== 'number') return undefined
  const normalized = String(v).trim()
  return normalized || undefined
}

/** 支付记录存在性与删除态的唯一判断口径，列表和追溯页共用。 */
export const paymentEvidenceStateOf = (order: OrderItem): PaymentEvidenceState => {
  if (order.paymentDataStatus != null && order.paymentDataStatus !== 0) return 'invalid'
  return order.paymentDataStatus === 0 ||
    order.payStatus != null ||
    order.paySource != null ||
    !!order.transactionId ||
    !!order.payExpireTime ||
    !!order.paySuccessTime
    ? 'valid'
    : 'missing'
}

function normalizeOrderItem(raw: Record<string, any>): OrderItem {
  const packageSnap = typeof raw.packageSnap === 'string' ? raw.packageSnap : undefined
  const packageSnapshot = normalizeRechargePackageSnapshot(packageSnap)
  return {
    ...(raw as OrderItem),
    id: toIdStr(raw.id) ?? '',
    orderType: toNum(raw.orderType) ?? 0,
    userId: toIdStr(raw.userId) ?? '',
    stationId: toIdStr(raw.stationId),
    deviceId: toIdStr(raw.deviceId),
    outletId: toIdStr(raw.outletId),
    outletNo: toNum(raw.outletNo),
    cardId: toIdStr(raw.cardId),
    // 使用人/持卡人身份聚合（UI-TRACE）：脱敏与 accessRole 均为服务端产物，
    // 前端只做结构校验，不用 userId/cardOwnerUserId 自行推导角色。
    actorMaskedPhone: typeof raw.actorMaskedPhone === 'string' ? raw.actorMaskedPhone : undefined,
    cardOwnerUserId: toIdStr(raw.cardOwnerUserId),
    cardOwnerName: typeof raw.cardOwnerName === 'string' ? raw.cardOwnerName : undefined,
    cardOwnerMaskedPhone:
      typeof raw.cardOwnerMaskedPhone === 'string' ? raw.cardOwnerMaskedPhone : undefined,
    accessRole:
      raw.accessRole === 'OWNER' || raw.accessRole === 'MEMBER' ? raw.accessRole : undefined,
    cardBalanceFen: toNum(raw.cardBalanceFen),
    cardBalanceMl: toNum(raw.cardBalanceMl),
    packageId: toIdStr(raw.packageId),
    packageSnap,
    packageSnapshot,
    packageSnapshotState: packageSnapshotStateOf(packageSnap, packageSnapshot),
    planMl: toNum(raw.planMl),
    actualMl: toNum(raw.actualMl),
    orderAmount: toNum(raw.orderAmount) ?? 0,
    payWay: toNum(raw.payWay) ?? 0,
    payStatus: toEvidenceNum(raw.payStatus),
    paySource: toEvidenceNum(raw.paySource),
    transactionId: toEvidenceStr(raw.transactionId),
    payExpireTime: toEvidenceStr(raw.payExpireTime),
    paySuccessTime: toEvidenceStr(raw.paySuccessTime),
    paymentDataStatus: toEvidenceNum(raw.paymentDataStatus),
    orderStatus: toNum(raw.orderStatus) ?? 0,
    cmdId: toIdStr(raw.cmdId)
  }
}

// ============ 配送域真实归一化（E2E-03 包C）：Long ID string、数值 number、状态白名单 ============

/** 媒体核验状态白名单：未知值一律按 invalid 处理（fail-closed，不把脏值当已核验）。 */
const toMediaStatus = (v: unknown): AdminMediaRef['mediaStatus'] =>
  v === 'ok' ? 'ok' : v === 'missing' ? 'missing' : 'invalid'

/** 服务端核验位白名单：只有显式 ok 才算通过，缺失/脏值一律 mismatch。 */
const toLinkStatus = (v: unknown): 'ok' | 'mismatch' => (v === 'ok' ? 'ok' : 'mismatch')

const toText = (v: unknown): string | undefined => {
  if (typeof v !== 'string') return undefined
  const text = v.trim()
  return text || undefined
}

function normalizeMediaRef(raw: Record<string, any>): AdminMediaRef {
  return {
    mediaKey: String(raw.mediaKey ?? ''),
    mimeType: toText(raw.mimeType),
    sizeBytes: toNum(raw.sizeBytes),
    uploadTime: toText(raw.uploadTime),
    mediaStatus: toMediaStatus(raw.mediaStatus),
    mediaReason: toText(raw.mediaReason)
  }
}

function normalizeSignPhotoMeta(raw: Record<string, any>): AdminSignPhotoMeta {
  return {
    ...normalizeMediaRef(raw),
    type: toNum(raw.type) ?? 0,
    typeLabel: toText(raw.typeLabel),
    time: toText(raw.time),
    latitude: toNum(raw.latitude),
    longitude: toNum(raw.longitude)
  }
}

function normalizeTimelineNode(raw: Record<string, any>): DeliveryTimelineNode {
  return {
    node: String(raw.node ?? ''),
    nodeLabel: String(raw.nodeLabel ?? ''),
    time: toText(raw.time),
    detail: toText(raw.detail),
    done: raw.done === true
  }
}

function normalizeDeliveryExceptionAdmin(raw: Record<string, any>): DeliveryExceptionAdmin {
  const refs = Array.isArray(raw.evidenceRefs) ? (raw.evidenceRefs as Record<string, any>[]) : []
  return {
    exceptionId: toIdStr(raw.exceptionId) ?? '',
    reason: toNum(raw.reason),
    reasonLabel: toText(raw.reasonLabel),
    description: toText(raw.description),
    evidenceRefs: refs.map(normalizeMediaRef),
    createTime: toText(raw.createTime)
  }
}

function normalizeDeliveryTaskAdminItem(raw: Record<string, any>): DeliveryTaskAdminItem {
  return {
    taskId: toIdStr(raw.taskId) ?? '',
    taskNo: String(raw.taskNo ?? ''),
    orderId: toIdStr(raw.orderId),
    orderNo: toText(raw.orderNo),
    orderStatus: toNum(raw.orderStatus),
    userId: toIdStr(raw.userId),
    userName: toText(raw.userName),
    userMaskedPhone: toText(raw.userMaskedPhone),
    receiveMaskedPhone: toText(raw.receiveMaskedPhone),
    courierId: toIdStr(raw.courierId),
    courierName: toText(raw.courierName),
    courierMaskedPhone: toText(raw.courierMaskedPhone),
    stationId: toIdStr(raw.stationId),
    stationName: toText(raw.stationName),
    waterTypeId: toIdStr(raw.waterTypeId),
    waterTypeName: toText(raw.waterTypeName),
    containerSpec: toText(raw.containerSpec),
    deliveryCount: toNum(raw.deliveryCount),
    actualDeliveryCount: toNum(raw.actualDeliveryCount),
    planReturnCount: toNum(raw.planReturnCount),
    actualReturnCount: toNum(raw.actualReturnCount),
    waterAmountFen: toNum(raw.waterAmountFen),
    deliveryFeeFen: toNum(raw.deliveryFeeFen),
    totalAmountFen: toNum(raw.totalAmountFen),
    payWay: toNum(raw.payWay),
    receiveAddress: toText(raw.receiveAddress),
    taskStatus: toNum(raw.taskStatus) ?? 0,
    scheduledTime: toText(raw.scheduledTime),
    acceptTime: toText(raw.acceptTime),
    departTime: toText(raw.departTime),
    arriveTime: toText(raw.arriveTime),
    signTime: toText(raw.signTime),
    appealDeadline: toText(raw.appealDeadline),
    locationStatus: toNum(raw.locationStatus),
    createTime: toText(raw.createTime)
  }
}

function normalizeDeliveryTaskAdminDetail(raw: Record<string, any>): DeliveryTaskAdminDetail {
  const timeline = Array.isArray(raw.timeline) ? (raw.timeline as Record<string, any>[]) : undefined
  const signPhotos = Array.isArray(raw.signPhotos)
    ? (raw.signPhotos as Record<string, any>[])
    : undefined
  const exceptions = Array.isArray(raw.exceptions)
    ? (raw.exceptions as Record<string, any>[])
    : undefined
  return {
    ...normalizeDeliveryTaskAdminItem(raw),
    linkStatus: toLinkStatus(raw.linkStatus),
    linkReason: toText(raw.linkReason),
    timeline: timeline?.map(normalizeTimelineNode),
    signPhotos: signPhotos?.map(normalizeSignPhotoMeta),
    exceptions: exceptions?.map(normalizeDeliveryExceptionAdmin)
  }
}

function normalizeAppealAdminItem(raw: Record<string, any>): AppealAdminItem {
  return {
    appealId: toIdStr(raw.appealId) ?? '',
    taskId: toIdStr(raw.taskId),
    taskNo: toText(raw.taskNo),
    orderId: toIdStr(raw.orderId),
    orderNo: toText(raw.orderNo),
    userId: toIdStr(raw.userId),
    userName: toText(raw.userName),
    userMaskedPhone: toText(raw.userMaskedPhone),
    appealReason: toText(raw.appealReason),
    appealReasonLabel: toText(raw.appealReasonLabel),
    appealDesc: toText(raw.appealDesc),
    receivedCount: toNum(raw.receivedCount),
    appealStatus: toNum(raw.appealStatus) ?? 0,
    handleBy: toIdStr(raw.handleBy),
    handleByName: toText(raw.handleByName),
    handleTime: toText(raw.handleTime),
    handleResult: toText(raw.handleResult),
    createTime: toText(raw.createTime),
    // 聚合列只在案件分页下发；详情/往来条目上后端本就不给，归一化后保持 undefined
    appealCount: toNum(raw.appealCount),
    activeAppealId: toIdStr(raw.activeAppealId),
    firstAppealTime: toText(raw.firstAppealTime),
    lastAppealTime: toText(raw.lastAppealTime)
  }
}

function normalizeCourierEvidenceAdmin(raw: Record<string, any>): CourierEvidenceAdmin {
  const refs = Array.isArray(raw.evidenceRefs) ? (raw.evidenceRefs as Record<string, any>[]) : []
  return {
    description: toText(raw.description),
    time: toText(raw.time),
    evidenceRefs: refs.map(normalizeMediaRef)
  }
}

/** 追溯·配送区块真实归一化：mismatch 时后端不下发正向字段，归一化不伪造默认值。 */
function normalizeRealDeliveryTrace(
  raw: Record<string, any> | null | undefined
): RealDeliveryTrace | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const timeline = Array.isArray(raw.timeline) ? (raw.timeline as Record<string, any>[]) : undefined
  const signPhotos = Array.isArray(raw.signPhotos)
    ? (raw.signPhotos as Record<string, any>[])
    : undefined
  const notifications = Array.isArray(raw.notifications)
    ? (raw.notifications as Record<string, any>[])
    : undefined
  const paymentRaw = raw.payment as Record<string, any> | null | undefined
  return {
    linkStatus: toLinkStatus(raw.linkStatus),
    linkReason: toText(raw.linkReason),
    taskId: toIdStr(raw.taskId),
    taskNo: toText(raw.taskNo),
    taskStatus: toNum(raw.taskStatus),
    userName: toText(raw.userName),
    userMaskedPhone: toText(raw.userMaskedPhone),
    courierName: toText(raw.courierName),
    courierMaskedPhone: toText(raw.courierMaskedPhone),
    stationName: toText(raw.stationName),
    waterTypeName: toText(raw.waterTypeName),
    containerSpec: toText(raw.containerSpec),
    deliveryCount: toNum(raw.deliveryCount),
    actualDeliveryCount: toNum(raw.actualDeliveryCount),
    planReturnCount: toNum(raw.planReturnCount),
    actualReturnCount: toNum(raw.actualReturnCount),
    waterAmountFen: toNum(raw.waterAmountFen),
    deliveryFeeFen: toNum(raw.deliveryFeeFen),
    totalAmountFen: toNum(raw.totalAmountFen),
    payWay: toNum(raw.payWay),
    deductWaterMl: toNum(raw.deductWaterMl),
    receiveAddress: toText(raw.receiveAddress),
    receiveMaskedPhone: toText(raw.receiveMaskedPhone),
    scheduledTime: toText(raw.scheduledTime),
    appealDeadline: toText(raw.appealDeadline),
    locationStatus: toNum(raw.locationStatus),
    timeline: timeline?.map(normalizeTimelineNode),
    signPhotos: signPhotos?.map(normalizeSignPhotoMeta),
    payment: paymentRaw
      ? {
          flowStatus: toLinkStatus(paymentRaw.flowStatus) === 'ok' ? 'ok' : 'mismatch',
          flowReason: toText(paymentRaw.flowReason),
          flowId: toIdStr(paymentRaw.flowId),
          bizKey: toText(paymentRaw.bizKey),
          amountChangeFen: toNum(paymentRaw.amountChangeFen),
          amountAfterFen: toNum(paymentRaw.amountAfterFen),
          mlChange: toNum(paymentRaw.mlChange),
          mlAfter: toNum(paymentRaw.mlAfter),
          time: toText(paymentRaw.time),
          remark: toText(paymentRaw.remark)
        }
      : undefined,
    notifications: notifications?.map((n) => ({
      id: toIdStr(n.id) ?? '',
      title: toText(n.title),
      content: toText(n.content),
      sendStatus: toNum(n.sendStatus),
      sendTime: toText(n.sendTime),
      objectType: toText(n.objectType),
      objectId: toText(n.objectId)
    }))
  }
}

/** 追溯·申诉行真实归一化：mismatch 行只保留ID与原因（后端已隐藏正向内容，前端不补造）。 */
function normalizeTraceAppeal(raw: Record<string, any>): AppealBrief {
  return {
    appealId: toIdStr(raw.appealId) ?? '',
    linkStatus: toLinkStatus(raw.linkStatus),
    linkReason: toText(raw.linkReason),
    appealStatus: toNum(raw.appealStatus),
    appealReason: toText(raw.appealReason),
    appealReasonLabel: toText(raw.appealReasonLabel),
    appealDesc: toText(raw.appealDesc),
    receivedCount: toNum(raw.receivedCount),
    handleTime: toText(raw.handleTime),
    handleResult: toText(raw.handleResult),
    createTime: toText(raw.createTime)
  }
}

/** 追溯·审计事件真实归一化（领域事件投影；tone 统一 info，由事实文字自述）。 */
function normalizeTraceAuditEvent(raw: Record<string, any>): TraceAuditEvent {
  return {
    id: toIdStr(raw.id) ?? '',
    eventTypeLabel: String(raw.eventTypeLabel ?? '事件'),
    eventKey: String(raw.eventKey ?? ''),
    actorLabel: String(raw.actorLabel ?? ''),
    detail: toText(raw.detail),
    time: String(raw.time ?? ''),
    tone: 'info'
  }
}

const toNonNegativeEvidenceNum = (value: unknown): number | undefined => {
  const parsed = toEvidenceNum(value)
  return parsed != null && parsed >= 0 ? parsed : undefined
}

const toPositiveEvidenceNum = (value: unknown): number | undefined => {
  const parsed = toEvidenceNum(value)
  return parsed != null && parsed > 0 ? parsed : undefined
}

const normalizedText = (value: unknown): string | undefined => {
  if (typeof value !== 'string') return undefined
  const text = value.trim()
  return text || undefined
}

/** Long ID 只接受正整数字符串或安全整数；对象、浮点数和已丢精度数字一律拒绝。 */
const toEvidenceId = (value: unknown): string | undefined => {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) return value
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value)
  return undefined
}

const toBusinessTime = (value: unknown): string | undefined => {
  const normalized = normalizedText(value)
  return normalized && /^\d{14}$/.test(normalized) ? normalized : undefined
}

const RECHARGE_PROCESSING_STATUSES = new Set([
  'WAITING_PAYMENT',
  'PENDING',
  'PROCESSING',
  'PROCESSED',
  'RETRY_WAIT',
  'RECONCILIATION_REQUIRED'
])

/**
 * 充值追溯只做结构校验与单位归一化。业务共键、金额和权益规则均由后端核验；
 * 后端声明 ok 但缺少完成态关键证据时，前端主动降级为 mismatch，避免展示半份成功证据。
 */
function normalizeRechargeTrace(
  raw: Record<string, any> | null | undefined,
  order: OrderItem
): RechargeTrace | undefined {
  if (!raw) return undefined

  const reason = normalizedText(raw.linkReason)
  if (raw.linkStatus === 'mismatch') {
    return { linkStatus: 'mismatch', linkReason: reason || '充值关联证据核验未通过' }
  }
  if (raw.linkStatus !== 'ok' || !raw.detail || typeof raw.detail !== 'object') {
    return { linkStatus: 'mismatch', linkReason: '充值追溯证据缺失' }
  }

  const detailRaw = raw.detail as Record<string, unknown>
  // 服务端充值详情沿用既有契约：FIRST_CARD 显式标识，已有卡充值为 null。
  // PC 只在归一化后的展示模型中把 null 命名为 EXISTING_CARD，不改写业务事实。
  const purchaseMode =
    detailRaw.purchaseMode === 'FIRST_CARD'
      ? 'FIRST_CARD'
      : detailRaw.purchaseMode == null || detailRaw.purchaseMode === 'EXISTING_CARD'
        ? 'EXISTING_CARD'
        : undefined
  const packageName = normalizedText(detailRaw.packageName)
  const payAmountFen = toNonNegativeEvidenceNum(detailRaw.payAmountFen)
  const waterMl = toNonNegativeEvidenceNum(detailRaw.waterMl)
  const bonusAmountFen = toNonNegativeEvidenceNum(detailRaw.bonusAmountFen)
  const payStatus = toNonNegativeEvidenceNum(detailRaw.payStatus)
  const paySource = toNonNegativeEvidenceNum(detailRaw.paySource)
  const processingStatus = normalizedText(detailRaw.processingStatus)
  const expireDays =
    detailRaw.expireDays == null ? undefined : toPositiveEvidenceNum(detailRaw.expireDays)

  if (
    detailRaw.snapshotValid !== true ||
    !purchaseMode ||
    !packageName ||
    payAmountFen == null ||
    waterMl == null ||
    bonusAmountFen == null ||
    payStatus == null ||
    paySource == null ||
    !processingStatus ||
    !RECHARGE_PROCESSING_STATUSES.has(processingStatus) ||
    (detailRaw.expireDays != null && expireDays == null)
  ) {
    return { linkStatus: 'mismatch', linkReason: '充值追溯证据不完整' }
  }

  const detail: RechargeTraceDetail = {
    snapshotValid: true,
    purchaseMode,
    cardId: toEvidenceId(detailRaw.cardId),
    cardNo: normalizedText(detailRaw.cardNo),
    cardType: toNonNegativeEvidenceNum(detailRaw.cardType),
    issueOrderId: toEvidenceId(detailRaw.issueOrderId),
    scopeDescription: normalizedText(detailRaw.scopeDescription),
    packageName,
    payAmountFen,
    waterMl,
    bonusAmountFen,
    expireDays,
    payStatus,
    paySource,
    processingStatus,
    flowAmountChange: toEvidenceNum(detailRaw.flowAmountChange),
    flowMlChange: toEvidenceNum(detailRaw.flowMlChange),
    flowAmountAfter: toNonNegativeEvidenceNum(detailRaw.flowAmountAfter),
    flowMlAfter: toNonNegativeEvidenceNum(detailRaw.flowMlAfter),
    cardBalanceFen: toNonNegativeEvidenceNum(detailRaw.cardBalanceFen),
    cardBalanceMl: toNonNegativeEvidenceNum(detailRaw.cardBalanceMl),
    cardExpireTime: toBusinessTime(detailRaw.cardExpireTime)
  }

  if (
    order.orderStatus === 4 &&
    (!detail.cardId ||
      !detail.cardNo ||
      detail.cardType == null ||
      detail.flowAmountChange == null ||
      detail.flowMlChange == null ||
      detail.flowAmountAfter == null ||
      detail.flowMlAfter == null ||
      detail.cardBalanceFen == null ||
      detail.cardBalanceMl == null ||
      (detail.expireDays != null && !detail.cardExpireTime) ||
      (purchaseMode === 'FIRST_CARD' &&
        (!detail.issueOrderId || !detail.scopeDescription || detail.cardType !== 1)))
  ) {
    return { linkStatus: 'mismatch', linkReason: '已完成充值缺少发卡、流水或卡终值证据' }
  }

  return { linkStatus: 'ok', detail }
}

function normalizeOrderTrace(raw: Record<string, any>): OrderTraceVo {
  const order = normalizeOrderItem(raw.order)
  const rawCommand = raw?.command as Record<string, any> | null | undefined
  const rawFlows = Array.isArray(raw?.flows) ? (raw.flows as Record<string, any>[]) : []
  const rawAppeals = Array.isArray(raw?.appeals) ? (raw.appeals as Record<string, any>[]) : []
  return {
    order,
    recharge: normalizeRechargeTrace(
      raw?.recharge as Record<string, any> | null | undefined,
      order
    ),
    command: rawCommand
      ? {
          ...(rawCommand as CommandTrace),
          cmdId: toIdStr(rawCommand.cmdId) ?? '',
          // mismatch 时后端不下发 cmdType/cmdStatus：保持 undefined，不伪造 0。
          cmdType: toNum(rawCommand.cmdType),
          cmdStatus: toNum(rawCommand.cmdStatus),
          timeline: Array.isArray(rawCommand.timeline) ? rawCommand.timeline : []
        }
      : undefined,
    flows: rawFlows.map((f) => ({
      ...(f as FlowTrace),
      flowId: toIdStr(f.flowId) ?? '',
      amount: toNum(f.amount) ?? 0,
      // P1-B：AFTER 是写入流水时冻结的卡面快照，缺失时保持 undefined，不伪造 0
      amountAfter: toNum(f.amountAfter),
      mlAfter: toNum(f.mlAfter)
    })),
    // E2E-03 包C：配送单三区块真实聚合（deliveryTrace）；非配送单后端返 null/空数组。
    // Mock 演示形状的 delivery 只由 fetchOrderTraceMock 构造，两条路径严格分流。
    deliveryTrace: normalizeRealDeliveryTrace(
      raw?.delivery as Record<string, any> | null | undefined
    ),
    appeals: rawAppeals.map(normalizeTraceAppeal),
    auditEvents: Array.isArray(raw?.auditEvents)
      ? (raw.auditEvents as Record<string, any>[]).map(normalizeTraceAuditEvent)
      : []
  }
}

/** 订单分页（管理端只读接真 /order/order/page；三类订单共用，orderType 区分 tab） */
export async function fetchOrderPage(params: OrderSearchParams): Promise<PageResult<OrderItem>> {
  const res = await request.post<{ list?: Record<string, any>[]; total?: unknown }>({
    url: '/order/order/page',
    data: params
  })
  const rawList = Array.isArray(res?.list) ? res.list : []
  return {
    list: rawList.map(normalizeOrderItem),
    total: toNum(res?.total) ?? rawList.length
  }
}

/*
 * 取水异常的终态处理已迁出本文件（E2E-04 包E）。
 *
 * 原 fetchResolveWaterException 是一套跑在浏览器里的资金算法：它自行算缺水量、自行决定
 * 订单落 4已完成 还是 5已取消、自行往 Mock 流水里追加一条“补偿水量登记”。这是资金口径的
 * 第二份真相，且与服务端账本毫无关系，按任务书明令删除。
 *
 * 取水核账现在走真实接口：/order/after-sale/water/preview 只读预览来源判别与建议终态，
 * /order/after-sale/water/confirm 在事务内按账本重算终态。两者都在 @/api/after-sale.ts，
 * 页面只提交订单ID与核账说明，不提交也不推算任何金额、水量或目标状态。
 */

/**
 * 订单全链路追溯（管理端只读接真 /order/order/trace；REQ-050 订单→指令→ACK→流水）。
 * 配送/申诉/审计三区块属配送链与领域事件聚合，为后续切片：后端本期返 null/空数组，抽屉对应区块走空态。
 */
export async function fetchOrderTrace(orderId: string): Promise<OrderTraceVo> {
  const res = await request.post<Record<string, any>>({
    url: '/order/order/trace',
    data: { id: orderId }
  })
  return normalizeOrderTrace(res)
}

/**
 * 配送任务分页（真实接口 /order/delivery/page，DRIVER_MANAGE 只读）。
 * PC 只监控与追溯：本域没有任何接单/推进/签收写接口，页面不得伪造履约动作。
 */
export async function fetchDeliveryTaskPage(params: {
  current: number
  size: number
  taskStatus?: number
  keyword?: string
}): Promise<PageResult<DeliveryTaskAdminItem>> {
  const res = await request.post<{ list?: Record<string, any>[]; total?: unknown }>({
    url: '/order/delivery/page',
    data: params
  })
  const rawList = Array.isArray(res?.list) ? res.list : []
  return {
    list: rawList.map(normalizeDeliveryTaskAdminItem),
    total: toNum(res?.total) ?? rawList.length
  }
}

/** 任务详情（真实接口 /order/delivery/detail：时间线/三照媒体元数据/异常，共键 fail-closed）。 */
export async function fetchDeliveryTaskDetail(taskId: string): Promise<DeliveryTaskAdminDetail> {
  const res = await request.post<Record<string, any>>({
    url: '/order/delivery/detail',
    data: { id: taskId }
  })
  return normalizeDeliveryTaskAdminDetail(res)
}

/**
 * 申诉分页（真实接口 /order/appeal/page）：返回的是案件聚合行（一行一个 taskId，D-215），
 * 待处理案件排前、其余按最近申诉时间倒序；total 是案件数不是申诉条数。
 * appealStatus 筛的是案件当前状态。
 */
export async function fetchAppealPage(params: {
  current: number
  size: number
  appealStatus?: number
  orderNo?: string
}): Promise<PageResult<AppealAdminItem>> {
  const res = await request.post<{ list?: Record<string, any>[]; total?: unknown }>({
    url: '/order/appeal/page',
    data: params
  })
  const rawList = Array.isArray(res?.list) ? res.list : []
  return {
    list: rawList.map(normalizeAppealAdminItem),
    total: toNum(res?.total) ?? rawList.length
  }
}

/** 申诉证据详情（真实接口 /order/appeal/evidence：用户举证 + 配送员举证 + 关联履约任务）。 */
export async function fetchAppealEvidence(appealId: string): Promise<AppealAdminEvidence> {
  const res = await request.post<Record<string, any>>({
    url: '/order/appeal/evidence',
    data: { id: appealId }
  })
  const taskRaw = res?.task as Record<string, any> | null | undefined
  const photos = Array.isArray(res?.appealPhotos)
    ? (res.appealPhotos as Record<string, any>[])
    : undefined
  const evidences = Array.isArray(res?.courierEvidences)
    ? (res.courierEvidences as Record<string, any>[])
    : undefined
  // 断链（linkStatus=mismatch）时后端不下发往来，归一化不补空数组冒充「查过且无历史」
  const history = Array.isArray(res?.appealHistory)
    ? (res.appealHistory as Record<string, any>[])
    : undefined
  return {
    appeal: normalizeAppealAdminItem((res?.appeal as Record<string, any>) ?? {}),
    linkStatus: toLinkStatus(res?.linkStatus),
    linkReason: toText(res?.linkReason),
    appealHistory: history?.map(normalizeAppealAdminItem),
    appealPhotos: photos?.map(normalizeMediaRef),
    courierEvidences: evidences?.map(normalizeCourierEvidenceAdmin),
    task: taskRaw ? normalizeDeliveryTaskAdminDetail(taskRaw) : undefined
  }
}

/**
 * 申诉裁决（真实接口 /order/appeal/decide，权限 order:appeal:handle + 后端操作审计/防重）。
 * 结果只有包A 白名单三态；资金补偿仅登记待处理，本接口不产生退款成功结果。
 */
export function fetchDecideAppeal(data: AppealDecideData): Promise<boolean> {
  return request.post<boolean>({
    url: '/order/appeal/decide',
    data: {
      appealId: data.id,
      strategyCode: data.strategyCode,
      approvedCount: data.approvedCount,
      handleResult: data.handleResult
    }
  })
}
