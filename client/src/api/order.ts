/**
 * 订单中心 API 契约。
 * H2-FE 已接真：fetchOrderPage / fetchOrderTrace 走管理端只读接口（/order/order/page、/order/order/trace），
 * 读真实 ws_order/ws_command/ws_wallet_flow；后端 Long 序列化为字符串，见下方归一化。
 * E2E-03 包C 已接真：fetchDeliveryTaskPage / fetchDeliveryTaskDetail（/order/delivery/**）、
 * fetchAppealPage / fetchAppealEvidence / fetchDecideAppeal（/order/appeal/**）走真实配送域接口；
 * 追溯的 delivery/appeals/auditEvents 三区块对配送单返回真实聚合（deliveryTrace，履约链/资金链
 * 分层 fail-closed），照片仅呈现受控媒体元数据（一期无媒体下载出口，不渲染图片字节）。
 * 仍为 Mock（异常处理为后续切片）：fetchResolveWaterException；fetchOrderTraceMock 保留
 * 五类 ACK 演示样例数据源，与真实接口严格分流。
 *
 * ============ 业务闭环故事线（Mock 数据保持引用一致） ============
 * 取水链（REQ-050 五类强制 ACK 验收场景齐全，另保留部分完成/出水中补充态）：
 *         订单 WO...1001（已完成）→ 指令 CMD-1001（下发→ACK→成功）→ 实际水量回填 → 水卡水量流水
 *         订单 WO...1002（异常待补偿）→ 指令部分完成(7)，出水不足 6L/10L
 *         订单 WO...1003（出水中）→ 指令已回执(3)
 *         订单 WO...1009（已取消）→ 指令执行失败(5)：阀门卡滞 → 余额原路退回
 *         订单 WO...1010（异常待补偿）→ 指令超时(6)：DK-DEV-0002 无 ACK → 触发离线告警（总览审计事件#6）
 *         订单 WO...1011（已完成）→ 重复 ACK 被幂等忽略 → 仅一笔扣减
 *         订单 WO...1012（已完成）→ 错设备 ACK 被拒绝 → 正确设备 ACK/result 后完成
 * 资金链：订单 WO...1004（充值已完成，套餐快照）；WO...1005（已退款）
 * 配送链：订单 WO...1006 → 配送任务 DT-2006（接单→离站→送达→三照签收）→ 申诉记录 #3001（待处理）
 *         订单 WO...1007 → DT-2007（配送中）；WO...1008 → DT-2008（待接单）
 * 设备口径（与总览/设备中控一致）：DK-DEV-0001 在线并承接当日出水；DK-DEV-0002 离线并导致 1010 指令超时
 *
 * 字段口径与真实表严格对齐：ws_order / ws_delivery_task / ws_delivery_appeal / ws_command（见 server/sql）。
 * 字典：订单类型1340 订单状态1341 支付方式1346 指令类型1320 指令状态1321 任务状态1351 申诉状态1352。
 * 金额一律"分"（bigint→number）；水量一律"毫升"；时间 varchar(14) yyyyMMddHHmmss。
 */

import request from '@/utils/http'
import { getDemoAuditEvents, recordDemoAuditEvent, type DemoAuditEvent } from '@/api/demo-audit'
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
  /** REQ-050 固定验收场景标签；只解释证据，不改变订单状态。 */
  scenarioLabel?: string
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
  /** 订单-指令共键校验：ok 一致；mismatch 关联异常（后端不提供载荷与时间线）；Mock 数据无此字段视为 ok */
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
  /** 该笔流水后的卡余额快照(分)（AMOUNT_AFTER）；本单最后一条有效流水的该值=「本单结算后余额」。Mock 无此字段。 */
  amountAfter?: number
  /** 该笔流水后的卡水量快照(毫升)（ML_AFTER）；本单最后一条有效流水的该值=「本单结算后水量」。Mock 无此字段。 */
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

/** 管理端申诉列表项（真实接口）。 */
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
  appealPhotos?: AdminMediaRef[]
  courierEvidences?: CourierEvidenceAdmin[]
  task?: DeliveryTaskAdminDetail
}

/**
 * 裁决提交：结果只有包A 白名单三态（3不成立驳回 / 5补送待执行 / 2成立待补偿）。
 * 资金补偿只落待处理，真实退款属后续链路；本契约不存在"退款成功"入参。
 */
export interface AppealDecideData {
  id: string
  outcome: 2 | 3 | 5
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
  tone?: DemoAuditEvent['tone']
}

/** 配送轨迹节点 */
export interface DeliveryTraceNode {
  node: string
  time?: string
  detail?: string
  done: boolean
}

/** 配送通知节点（REQ-059/087 Demo 契约，不代表微信订阅消息已接真）；Long ID 一律 string */
export interface DeliveryNotification {
  id: string
  event: 'order_created' | 'departed' | 'signed' | 'appeal_submitted' | 'appeal_resolved'
  receiverRole: 'user' | 'courier'
  channel: '站内消息' | '微信订阅消息'
  content: string
  /** 1待发送 2发送成功 3发送失败 4已降级站内 */
  sendStatus: 1 | 2 | 3 | 4
  sendTime?: string
}

/**
 * 申诉摘要（挂在订单追溯里）；Long ID 一律 string。
 * 真实聚合行带 linkStatus（逐行共键核验）：mismatch 行只有 appealId 与 linkReason，
 * 正向内容（状态/理由/裁决）不下发；Mock 演示行无 linkStatus 视为 ok。
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
  /** 区块四：配送轨迹（Mock 演示数据源专用形状；真实配送区块走 deliveryTrace） */
  delivery?: {
    taskId: string
    taskNo: string
    courierName?: string
    courierPhone?: string
    taskStatus: number
    nodes: DeliveryTraceNode[]
    signPhotos: { type: number; label: string; url: string; time: string }[]
    notifications: DeliveryNotification[]
  }
  /** 区块四（真实）：配送单真实聚合区块（履约链/资金链分层核验，E2E-03 包C）。 */
  deliveryTrace?: RealDeliveryTrace
  /** 区块五：申诉记录 */
  appeals: AppealBrief[]
  /** 区块六：审计事件（Mock=共享 Demo 审计；真实=配送单领域事件聚合）。 */
  auditEvents: TraceAuditEvent[]
}

/** 配送任务列表项（ws_delivery_task）；Long ID 一律 string（收口轮 P1） */
export interface DeliveryTaskItem {
  id: string
  orderId: string
  orderNo: string
  userId: string
  userName?: string
  courierId?: string
  courierName?: string
  waterType: string
  containerSpec?: string
  /** 单桶水量（毫升），来自下单快照。 */
  unitWaterMl: number
  deliveryCount: number
  /** 实际签收桶数；未到签收节点时为空。 */
  actualDeliveryCount?: number
  /** 下单价格快照，展示与申诉核验使用，不参与前端计算扣款。 */
  priceSnapshot: {
    waterUnitPriceFen: number
    waterAmountFen: number
    deliveryFeeFen: number
    totalAmountFen: number
  }
  receiveAddress: string
  receivePhone: string
  /** 任务状态(1351)：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中 */
  taskStatus: number
  acceptTime?: string
  departTime?: string
  arriveTime?: string
  signTime?: string
  /** 签收三照JSON数组 [{type:1门牌/2水品/3摆放, url, lat, lng, time}] */
  signPhotos?: string
  appealDeadline?: string
  taskRemark?: string
  createTime: string
  /** 固定 Mock 通知记录；真实实现由后端事件模型产生。 */
  notifications: DeliveryNotification[]
}

/** 申诉列表项（ws_delivery_appeal）；Long ID 一律 string（收口轮 P1） */
export interface AppealItem {
  id: string
  taskId: string
  taskNo?: string
  orderNo?: string
  userId: string
  userName?: string
  userPhone?: string
  appealReason: string
  appealPhotos?: string
  /** 申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 */
  appealStatus: number
  handleBy?: string
  handleByName?: string
  handleTime?: string
  handleResult?: string
  createTime: string
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

// ============ Mock 数据（与真实表口径一致，闭环咬合） ============

const MOCK_ORDERS: OrderItem[] = [
  {
    id: '1001',
    orderNo: 'WO20260712091001',
    orderType: 1,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '1',
    outletNo: 1,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 10000,
    actualMl: 10000,
    orderAmount: 200,
    payWay: 3,
    orderStatus: 4,
    scenarioLabel: '成功 ACK',
    cmdId: '9001',
    finishTime: '20260712091530',
    createTime: '20260712091500'
  },
  {
    id: '1002',
    orderNo: 'WO20260712091002',
    orderType: 1,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '2',
    outletNo: 2,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 10000,
    actualMl: 6000,
    orderAmount: 200,
    payWay: 3,
    orderStatus: 6,
    scenarioLabel: '部分完成 / 出水不足',
    cmdId: '9002',
    createTime: '20260712101200'
  },
  {
    id: '1003',
    orderNo: 'WO20260712091003',
    orderType: 1,
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '1',
    outletNo: 1,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 5000,
    orderAmount: 100,
    payWay: 3,
    orderStatus: 3,
    scenarioLabel: 'ACK 已到 / 等待 result',
    cmdId: '9003',
    createTime: '20260712113000'
  },
  {
    id: '1009',
    orderNo: 'WO20260712091009',
    orderType: 1,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '2',
    outletNo: 2,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 10000,
    actualMl: 0,
    orderAmount: 300,
    payWay: 2,
    orderStatus: 5,
    scenarioLabel: '失败 ACK / 未出水',
    cmdId: '9009',
    cancelReason: '设备执行失败（阀门卡滞），未出水自动取消，余额原路退回',
    createTime: '20260712140500'
  },
  {
    id: '1010',
    orderNo: 'WO20260712091010',
    orderType: 1,
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    stationId: '2',
    stationName: '南湖社区水站',
    deviceId: '2',
    deviceNo: 'DK-DEV-0002',
    outletId: '3',
    outletNo: 1,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 5000,
    orderAmount: 100,
    payWay: 3,
    orderStatus: 6,
    scenarioLabel: 'ACK 超时',
    cmdId: '9010',
    cancelReason: '指令超时无回执（设备离线先导信号），待人工核实补偿',
    createTime: '20260712075500'
  },
  {
    id: '1011',
    orderNo: 'WO20260711091011',
    orderType: 1,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '1',
    outletNo: 1,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 8000,
    actualMl: 8000,
    orderAmount: 160,
    payWay: 3,
    orderStatus: 4,
    scenarioLabel: '重复 ACK 幂等拦截',
    cmdId: '9011',
    finishTime: '20260711100520',
    createTime: '20260711100500'
  },
  {
    id: '1012',
    orderNo: 'WO20260711091012',
    orderType: 1,
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    stationId: '1',
    stationName: '光谷软件园水站',
    deviceId: '1',
    deviceNo: 'DK-DEV-0001',
    outletId: '2',
    outletNo: 2,
    cardId: '1',
    cardNo: 'VC20260710000001',
    planMl: 6000,
    actualMl: 6000,
    orderAmount: 180,
    payWay: 3,
    orderStatus: 4,
    scenarioLabel: '错设备 ACK 拦截',
    cmdId: '9012',
    finishTime: '20260711102025',
    createTime: '20260711102000'
  },
  {
    id: '1004',
    orderNo: 'WO20260712091004',
    orderType: 2,
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    packageId: '1',
    packageSnap:
      '{"packageName":"100元500升卡","payAmount":10000,"waterMl":500000,"unitPriceSnap":"20.00"}',
    orderAmount: 10000,
    payWay: 1,
    orderStatus: 4,
    finishTime: '20260711203010',
    createTime: '20260711203000'
  },
  {
    id: '1005',
    orderNo: 'WO20260712091005',
    orderType: 2,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    packageId: '2',
    packageSnap: '{"packageName":"50元充值(送5元)","payAmount":5000,"bonusAmount":500}',
    orderAmount: 5000,
    payWay: 1,
    orderStatus: 7,
    cancelReason: '用户申请退款：误购',
    createTime: '20260710150000'
  },
  {
    id: '1006',
    orderNo: 'WO20260712091006',
    orderType: 3,
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    stationId: '1',
    stationName: '光谷软件园水站',
    orderAmount: 4500,
    payWay: 1,
    orderStatus: 4,
    finishTime: '20260711183000',
    createTime: '20260711120000'
  },
  {
    id: '1007',
    orderNo: 'WO20260712091007',
    orderType: 3,
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    stationId: '2',
    stationName: '南湖社区水站',
    orderAmount: 3000,
    payWay: 2,
    orderStatus: 2,
    createTime: '20260712103000'
  },
  {
    id: '1008',
    orderNo: 'WO20260712091008',
    orderType: 3,
    userId: '4',
    userName: '赵先生',
    userPhone: '13700003333',
    stationId: '1',
    stationName: '光谷软件园水站',
    orderAmount: 1500,
    payWay: 1,
    orderStatus: 2,
    createTime: '20260712114500'
  }
]

/** 指令追溯 Mock（与订单 cmdId 咬合） */
const MOCK_COMMANDS: Record<string, CommandTrace> = {
  '9001': {
    cmdId: '9001',
    cmdNo: 'CMD20260712091500001',
    cmdType: 1,
    cmdStatus: 4,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":1,"waterType":"纯净水","planMl":10000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260712091501', tone: 'info' },
      { node: 'ack', nodeLabel: '设备回执 ACK', time: '20260712091502', tone: 'primary' },
      {
        node: 'result',
        nodeLabel: '执行成功',
        time: '20260712091528',
        detail: '实际出水 10000ml，与计划一致',
        tone: 'success'
      }
    ]
  },
  '9002': {
    cmdId: '9002',
    cmdNo: 'CMD20260712101200002',
    cmdType: 1,
    cmdStatus: 7,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":2,"waterType":"矿物质水","planMl":10000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260712101201', tone: 'info' },
      { node: 'ack', nodeLabel: '设备回执 ACK', time: '20260712101203', tone: 'primary' },
      {
        node: 'result',
        nodeLabel: '部分完成',
        time: '20260712101245',
        detail: '实际出水 6000ml / 计划 10000ml，水压异常中断 → 订单转异常待补偿',
        tone: 'warning'
      }
    ]
  },
  '9003': {
    cmdId: '9003',
    cmdNo: 'CMD20260712113000003',
    cmdType: 1,
    cmdStatus: 3,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":1,"waterType":"纯净水","planMl":5000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260712113001', tone: 'info' },
      {
        node: 'ack',
        nodeLabel: '设备回执 ACK，出水中',
        time: '20260712113002',
        tone: 'primary'
      }
    ]
  },
  '9009': {
    cmdId: '9009',
    cmdNo: 'CMD20260712140500009',
    cmdType: 1,
    cmdStatus: 5,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":2,"waterType":"矿物质水","planMl":10000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260712140501', tone: 'info' },
      { node: 'ack', nodeLabel: '设备回执 ACK', time: '20260712140502', tone: 'primary' },
      {
        node: 'result',
        nodeLabel: '执行失败',
        time: '20260712140510',
        detail: '故障码 E21 阀门卡滞，未出水 → 订单自动取消，余额原路退回',
        tone: 'danger'
      }
    ]
  },
  '9010': {
    cmdId: '9010',
    cmdNo: 'CMD20260712075500010',
    cmdType: 1,
    cmdStatus: 6,
    deviceNo: 'DK-DEV-0002',
    payload: '{"outletNo":1,"waterType":"纯净水","planMl":5000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260712075501', tone: 'info' },
      {
        node: 'timeout',
        nodeLabel: '超时',
        time: '20260712075601',
        detail: '30 秒未收到设备 ACK，监控 worker 判定超时；随后设备心跳超限产生离线告警',
        tone: 'danger'
      }
    ]
  },
  '9011': {
    cmdId: '9011',
    cmdNo: 'CMD20260711100500011',
    cmdType: 1,
    cmdStatus: 4,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":1,"waterType":"纯净水","planMl":8000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260711100501', tone: 'info' },
      {
        node: 'ack-first',
        nodeLabel: '首次 ACK 通过',
        time: '20260711100502',
        detail: '目标设备和 cmdNo 校验一致，状态推进为已回执',
        tone: 'primary'
      },
      {
        node: 'ack-duplicate',
        nodeLabel: '重复 ACK 已忽略',
        time: '20260711100504',
        detail: '同一 cmdNo 第二次 ACK 只写审计、不重复推进状态，也不重复扣减',
        tone: 'warning'
      },
      {
        node: 'result',
        nodeLabel: '执行成功',
        time: '20260711100520',
        detail: '实际出水 8000ml；订单和水卡流水各只生成一次',
        tone: 'success'
      }
    ]
  },
  '9012': {
    cmdId: '9012',
    cmdNo: 'CMD20260711102000012',
    cmdType: 1,
    cmdStatus: 4,
    deviceNo: 'DK-DEV-0001',
    payload: '{"outletNo":2,"waterType":"矿物质水","planMl":6000}',
    timeline: [
      { node: 'created', nodeLabel: '指令下发', time: '20260711102001', tone: 'info' },
      {
        node: 'ack-rejected',
        nodeLabel: '错设备 ACK 已拒绝',
        time: '20260711102003',
        detail: '目标 DK-DEV-0001，实际上报 DK-DEV-0002；指令状态保持已下发',
        tone: 'danger'
      },
      {
        node: 'ack',
        nodeLabel: '目标设备 ACK 通过',
        time: '20260711102006',
        detail: 'DK-DEV-0001 回执通过设备与 cmdNo 双重校验',
        tone: 'primary'
      },
      {
        node: 'result',
        nodeLabel: '执行成功',
        time: '20260711102025',
        detail: '实际出水 6000ml；被拒绝的错设备 ACK 未产生任何业务副作用',
        tone: 'success'
      }
    ]
  }
}

/** 配送任务 Mock（与订单 1006/1007/1008 咬合） */
const MOCK_TASKS: DeliveryTaskItem[] = [
  {
    id: '2006',
    orderId: '1006',
    orderNo: 'WO20260712091006',
    userId: '1',
    userName: '张女士',
    courierId: '1',
    courierName: '李师傅',
    waterType: '纯净水',
    containerSpec: '20L桶',
    unitWaterMl: 20000,
    deliveryCount: 3,
    actualDeliveryCount: 2,
    priceSnapshot: {
      waterUnitPriceFen: 1300,
      waterAmountFen: 3900,
      deliveryFeeFen: 600,
      totalAmountFen: 4500
    },
    receiveAddress: '武汉东湖高新区软件园中路 1 号 A 座 1201',
    receivePhone: '13900001111',
    taskStatus: 7,
    acceptTime: '20260711123000',
    departTime: '20260711150000',
    arriveTime: '20260711180000',
    signTime: '20260711183000',
    signPhotos: JSON.stringify([
      {
        type: 1,
        url: 'https://picsum.photos/seed/door/400/300',
        lat: 30.4586,
        lng: 114.4276,
        time: '20260711182800'
      },
      {
        type: 2,
        url: 'https://picsum.photos/seed/water/400/300',
        lat: 30.4586,
        lng: 114.4276,
        time: '20260711182900'
      },
      {
        type: 3,
        url: 'https://picsum.photos/seed/place/400/300',
        lat: 30.4586,
        lng: 114.4276,
        time: '20260711183000'
      }
    ]),
    appealDeadline: '20260712183000',
    createTime: '20260711120000',
    notifications: [
      {
        id: '7001',
        event: 'departed',
        receiverRole: 'user',
        channel: '微信订阅消息',
        content: '您的 3 桶纯净水已离开光谷软件园水站，配送员正在送达。',
        sendStatus: 2,
        sendTime: '20260711150002'
      },
      {
        id: '7002',
        event: 'signed',
        receiverRole: 'user',
        channel: '微信订阅消息',
        content: '订单已三照签收，可在 24 小时内发起配送申诉。',
        sendStatus: 2,
        sendTime: '20260711183002'
      },
      {
        id: '7003',
        event: 'appeal_submitted',
        receiverRole: 'courier',
        channel: '站内消息',
        content: '用户反馈实收数量不足，请准备配送举证。',
        sendStatus: 2,
        sendTime: '20260711210002'
      }
    ]
  },
  {
    id: '2007',
    orderId: '1007',
    orderNo: 'WO20260712091007',
    userId: '5',
    userName: '钱女士',
    courierId: '1',
    courierName: '李师傅',
    waterType: '矿物质水',
    containerSpec: '10L桶',
    unitWaterMl: 10000,
    deliveryCount: 2,
    priceSnapshot: {
      waterUnitPriceFen: 1200,
      waterAmountFen: 2400,
      deliveryFeeFen: 600,
      totalAmountFen: 3000
    },
    receiveAddress: '武汉洪山区南湖大道 88 号 3 栋 502',
    receivePhone: '13600004444',
    taskStatus: 3,
    acceptTime: '20260712104500',
    departTime: '20260712110000',
    createTime: '20260712103000',
    notifications: [
      {
        id: '7004',
        event: 'departed',
        receiverRole: 'user',
        channel: '微信订阅消息',
        content: '您的 2 桶矿物质水已离站，配送员正在送达。',
        sendStatus: 3,
        sendTime: '20260712110002'
      }
    ]
  },
  {
    id: '2008',
    orderId: '1008',
    orderNo: 'WO20260712091008',
    userId: '4',
    userName: '赵先生',
    waterType: '纯净水',
    containerSpec: '20L桶',
    unitWaterMl: 20000,
    deliveryCount: 1,
    priceSnapshot: {
      // 与 DT-2006 同规格（20L桶）单价对齐：1300 水费 + 200/桶配送费，总额 1500 不变。
      waterUnitPriceFen: 1300,
      waterAmountFen: 1300,
      deliveryFeeFen: 200,
      totalAmountFen: 1500
    },
    receiveAddress: '武汉东湖高新区光谷大道 77 号',
    receivePhone: '13700003333',
    taskStatus: 1,
    createTime: '20260712114500',
    notifications: []
  }
]

/** 申诉 Mock（记录 3001 与任务 2006 / 订单 1006 使用同一业务链键） */
const MOCK_APPEALS: AppealItem[] = [
  {
    id: '3001',
    taskId: '2006',
    taskNo: 'DT-2006',
    orderNo: 'WO20260712091006',
    userId: '1',
    userName: '张女士',
    userPhone: '13900001111',
    appealReason: '下单 3 桶实收 2 桶，签收照片里只有 2 桶水，要求补送或退差价',
    appealPhotos: JSON.stringify(['https://picsum.photos/seed/appeal1/400/300']),
    appealStatus: 1,
    createTime: '20260711210000'
  },
  {
    id: '3002',
    taskId: '1999',
    taskNo: 'DT-2026070902',
    orderNo: 'WO20260709090021',
    userId: '5',
    userName: '钱女士',
    userPhone: '13600004444',
    appealReason: '桶身破损漏水，摆放位置也不对',
    appealPhotos: JSON.stringify(['https://picsum.photos/seed/appeal2/400/300']),
    appealStatus: 2,
    handleBy: '1',
    handleByName: '客服专员 王敏',
    handleTime: '20260710093000',
    handleResult: '核实属实，已登记补送一桶并致歉，责任配送员进入服务复核',
    createTime: '20260709200000'
  },
  {
    id: '3003',
    taskId: '1998',
    taskNo: 'DT-2026070801',
    orderNo: 'WO20260708090011',
    userId: '4',
    userName: '赵先生',
    userPhone: '13700003333',
    appealReason: '声称未收到水',
    appealStatus: 3,
    handleBy: '1',
    handleByName: '客服专员 王敏',
    handleTime: '20260709100000',
    handleResult: '三照齐全且 GPS 与收货地址一致，签收时间在场证据充分，申诉不成立',
    createTime: '20260708210000'
  }
]

/** 流水 Mock（按订单咬合） */
const MOCK_FLOWS: Record<string, FlowTrace[]> = {
  1001: [
    {
      flowId: '5001',
      flowType: '水卡水量扣减',
      amount: -10000,
      unit: '毫升',
      time: '20260712091530',
      remark: '卡 VC20260710000001，原子扣减'
    }
  ],
  1002: [
    {
      flowId: '5002',
      flowType: '水卡水量扣减',
      amount: -6000,
      unit: '毫升',
      time: '20260712101246',
      remark: '按实际出水量 6000ml 扣减，差额待补偿'
    }
  ],
  1004: [
    {
      flowId: '5004',
      flowType: '微信支付',
      amount: 10000,
      unit: '分',
      time: '20260711203008',
      remark: '微信单号 4200002026071120300001'
    },
    {
      flowId: '5005',
      flowType: '水卡水量充入',
      amount: 500000,
      unit: '毫升',
      time: '20260711203010',
      remark: '套餐兑换 500L 入卡'
    }
  ],
  1005: [
    {
      flowId: '5006',
      flowType: '微信支付',
      amount: 5000,
      unit: '分',
      time: '20260710150005',
      remark: '微信单号 4200002026071015000002'
    },
    {
      flowId: '5007',
      flowType: '微信退款',
      amount: -5000,
      unit: '分',
      time: '20260710160000',
      remark: '全额退款，赠送余额同步回收'
    }
  ],
  1006: [
    {
      flowId: '5008',
      flowType: '微信支付',
      amount: 4500,
      unit: '分',
      time: '20260711120005',
      remark: '水费 39 元 + 配送费 6 元（分离计价）'
    }
  ],
  1007: [
    {
      flowId: '5009',
      flowType: '水卡余额扣减',
      amount: -3000,
      unit: '分',
      time: '20260712103002',
      remark: '卡 VC20260710000001 余额支付'
    }
  ],
  1009: [
    {
      flowId: '5010',
      flowType: '水卡余额扣减',
      amount: -300,
      unit: '分',
      time: '20260712140503',
      remark: '卡 VC20260710000001 余额支付（矿物质水 10L）'
    },
    {
      flowId: '5011',
      flowType: '失败退回',
      amount: 300,
      unit: '分',
      time: '20260712140512',
      remark: '指令执行失败未出水，余额原路退回（同事务原子操作）'
    }
  ],
  1011: [
    {
      flowId: '5012',
      flowType: '水卡水量扣减',
      amount: -8000,
      unit: '毫升',
      time: '20260711100520',
      remark: '重复 ACK 已幂等忽略；本订单仅生成这一笔 8000ml 扣减'
    }
  ],
  1012: [
    {
      flowId: '5013',
      flowType: '水卡水量扣减',
      amount: -6000,
      unit: '毫升',
      time: '20260711102025',
      remark: '错设备 ACK 未推进业务；目标设备 result 后仅生成这一笔 6000ml 扣减'
    }
  ]
  // 1010 超时单无流水：水量按实际出水量结算，未出水即未扣（追溯抽屉展示空态）
}

const delay = <T>(data: T, ms = 200): Promise<T> =>
  new Promise((resolve) => setTimeout(() => resolve(data), ms))

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

// ============ Mock 接口（前端可直接调用运行） ============

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
    createTime: toText(raw.createTime)
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
    eventTypeLabel: String(raw.eventTypeLabel ?? '领域事件'),
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
    return { linkStatus: 'mismatch', linkReason: reason || '充值关联证据未通过服务端核验' }
  }
  if (raw.linkStatus !== 'ok' || !raw.detail || typeof raw.detail !== 'object') {
    return { linkStatus: 'mismatch', linkReason: '充值追溯响应缺少有效核验结果' }
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
    return { linkStatus: 'mismatch', linkReason: '充值追溯响应的结构化证据不完整' }
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

/**
 * 异常取水 Demo 终态处理：仅更新前端内存并生成“模拟补偿凭证”。
 * 不调用退款、余额、水卡或设备接口；只追加共享 Demo 审计事件，刷新后全部重置。
 */
export function fetchResolveWaterException(
  orderId: string,
  handleRemark: string
): Promise<boolean> {
  const order = MOCK_ORDERS.find((item) => item.id === orderId)
  if (!order || order.orderStatus !== 6) return delay(false)

  const actualMl = order.actualMl || 0
  const missingMl = Math.max((order.planMl || 0) - actualMl, 0)
  let resolvedStatusLabel: string
  if (missingMl > 0 && actualMl > 0) {
    const flows = (MOCK_FLOWS[orderId] ||= [])
    flows.push({
      flowId: String(
        Math.max(
          0,
          ...Object.values(MOCK_FLOWS)
            .flat()
            .map((item) => Number(item.flowId))
        ) + 1
      ),
      flowType: '补偿水量登记',
      amount: missingMl,
      unit: '毫升',
      time: '20260714102000',
      remark: `补偿 ${missingMl}ml；处理说明：${handleRemark}`
    })
    order.orderStatus = 4
    order.finishTime = '20260714102000'
    order.cancelReason = `异常已关闭：按实扣减 ${actualMl}ml，补偿登记 ${missingMl}ml；${handleRemark}`
    resolvedStatusLabel = '已完成（补偿已登记）'
  } else {
    order.orderStatus = 5
    order.finishTime = '20260714102100'
    order.cancelReason = `异常已关闭：设备超时且实际出水 0ml，确认未扣费；${handleRemark}`
    resolvedStatusLabel = '已取消（确认未扣费）'
  }

  const command = order.cmdId ? MOCK_COMMANDS[order.cmdId] : undefined
  recordDemoAuditEvent({
    eventTypeLabel: '异常订单处理',
    eventKey: order.orderNo,
    relatedKeys: [command?.cmdNo, order.deviceNo].filter((key): key is string => !!key),
    actorLabel: '运营后台·超级管理员',
    oldStatus: '异常待补偿',
    newStatus: resolvedStatusLabel,
    detail: `异常待补偿 → ${resolvedStatusLabel}：${order.cancelReason}`,
    tone: missingMl > 0 && actualMl > 0 ? 'success' : 'info',
    targetPath: `/order/index?orderNo=${order.orderNo}`
  })
  return delay(true)
}

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
 * Mock 域订单追溯（2026-07-20 收口轮复审 P0-2）：配送/申诉等 Mock 模块的"查看订单追溯"
 * 明确进入本数据源（六区块由 MOCK_* 组装），与真实接口 fetchOrderTrace 严格分流，
 * 不再出现"Mock ID 打到真实订单库查不到"的断链；真实域绝不回退到本函数。
 */
export function fetchOrderTraceMock(orderId: string): Promise<OrderTraceVo | null> {
  const order = MOCK_ORDERS.find((o) => o.id === orderId)
  if (!order) return delay(null)
  const task = MOCK_TASKS.find((t) => t.orderId === orderId)
  const command = order.cmdId ? MOCK_COMMANDS[order.cmdId] : undefined
  const relatedKeys = [order.orderNo, command?.cmdNo, task ? `DT-${task.id}` : undefined].filter(
    (key): key is string => !!key
  )
  const trace: OrderTraceVo = {
    order: normalizeOrderItem(order as unknown as Record<string, any>),
    command,
    flows: MOCK_FLOWS[orderId] || [],
    appeals: MOCK_APPEALS.filter((a) => a.orderNo === order.orderNo).map((a) => ({
      appealId: a.id,
      appealStatus: a.appealStatus,
      appealReason: a.appealReason,
      handleResult: a.handleResult,
      createTime: a.createTime
    })),
    auditEvents: getDemoAuditEvents(relatedKeys),
    delivery: task
      ? {
          taskId: task.id,
          taskNo: `DT-${task.id}`,
          courierName: task.courierName,
          courierPhone: '13800001111',
          taskStatus: task.taskStatus,
          nodes: [
            { node: '下单', time: task.createTime, done: true },
            {
              node: '接单',
              time: task.acceptTime,
              detail: task.courierName,
              done: !!task.acceptTime
            },
            { node: '已离站', time: task.departTime, done: !!task.departTime },
            { node: '已送达', time: task.arriveTime, done: !!task.arriveTime },
            {
              node: '三照签收',
              time: task.signTime,
              detail: task.signTime ? '门牌/水品/摆放三照齐全' : undefined,
              done: !!task.signTime
            }
          ],
          signPhotos: task.signPhotos
            ? parseSignPhotos(task.signPhotos).map((photo) => ({
                type: photo.type,
                label: photo.label,
                url: photo.url,
                time: photo.time || ''
              }))
            : [],
          notifications: [...task.notifications]
        }
      : undefined
  }
  return delay(trace)
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

/** 申诉分页（真实接口 /order/appeal/page，待处理排前）。 */
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
  return {
    appeal: normalizeAppealAdminItem((res?.appeal as Record<string, any>) ?? {}),
    linkStatus: toLinkStatus(res?.linkStatus),
    linkReason: toText(res?.linkReason),
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
    data: { appealId: data.id, outcome: data.outcome, handleResult: data.handleResult }
  })
}
