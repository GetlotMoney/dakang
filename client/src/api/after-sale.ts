/**
 * 售后退款与补偿 API 契约（PC 售后域唯一 API 出处，接口面见 AdminAfterSaleController）。
 * 请求体不含任何金额、水量、批准数量或目标状态字段：额度在动作登记时冻结、终态由服务端重算，
 * 前端推算返还额度/补偿水量/订单终态即第二份资金真相，一律禁止；页面只做「分→元、毫升→升」展示换算。
 * 手机号只有服务端脱敏的 userMaskedPhone；openid / session_key / 原始退款报文不出接口面。
 * 字段集对齐 AdminAfterSaleActionItemVo / AdminWaterAbnormalPreviewVo；金额「分」，水量「毫升」，
 * 时间 varchar(14) yyyyMMddHHmmss；Long ID 一律保持 string（超 2^53 丢精度会查错行）。
 */

import request from '@/utils/http'
import type { PageResult } from '@/api/order'
export * from './after-sale-entry'

/**
 * 售后台账行全部来自服务端，前端不推导资金事实。四元额度分列下发：payWay=2 时只看合计
 * refundAmount 无法区分退的是水品还是服务费，页面必须分列展示。
 */
export interface AfterSaleActionItem {
  /** 售后动作ID（台账行主键，执行类接口的定位键） */
  id: string
  /** 售后号（确定性派生；与 id 共键复核，执行接口两者都要传） */
  afterSaleNo: string
  /** 售后来源(1370) */
  sourceType?: number
  /** 来源主体ID：配送取消/取水核账取订单ID，配送申诉取申诉ID */
  sourceId?: string
  orderId?: string
  orderNo?: string
  /** 订单类型(1340) */
  orderType?: number
  /** 订单状态(1341) */
  orderStatus?: number
  /** 支付方式(1346)：2水卡余额 3水卡水量 */
  payWay?: number
  /** 充值支付来源：1微信支付 2Pay-Sim */
  paySource?: number
  /** 订单金额(分) */
  orderAmount?: number
  userId?: string
  userName?: string
  /** 服务端脱敏后的手机号；空串表示号码异常整体屏蔽，不回落明文 */
  userMaskedPhone?: string
  /** 返还目标卡ID */
  cardId?: string
  cardNo?: string
  /** 动作类型(1371) */
  actionType?: number
  /** 补偿策略码（白名单见 AfterSaleStrategyCode） */
  strategyCode?: string
  /** 运营批准的受影响数量(桶)，裁决时提交、此处只读 */
  approvedCount?: number
  /** 水品权益返还金额(分)，payWay=2 专用 */
  refundProductFen?: number
  /** 配送费返还金额(分) */
  refundServiceFen?: number
  /** 水品权益返还水量(毫升)，payWay=3 专用 */
  refundProductMl?: number
  /** 返还金额合计(分)=水品+配送费；服务端下发，前端不得自行相加校对 */
  refundAmount?: number
  /** 执行状态(1372) */
  actionStatus?: number
  /** 乐观锁版本（只读展示，执行接口不回传） */
  version?: number
  retryCount?: number
  /** 下次可重试时间；认领时被清空，已完成行不会残留 */
  nextRetryTime?: string
  approveBy?: string
  approveByName?: string
  approveTime?: string
  /** 终态时间 */
  finishTime?: string
  /** 最近一次失败原因（可直接展示给运营） */
  lastError?: string
  createTime?: string
}

/** 台账查询入参。四个筛选维度与 WsAfterSaleActionMapper.xml 的 <if> 一一对应。 */
export interface AfterSaleActionPageParams {
  current: number
  size: number
  /** 执行状态(1372) */
  actionStatus?: number
  /** 售后来源(1370) */
  sourceType?: number
  /** 动作类型(1371) */
  actionType?: number
  /** 关键字：售后号 / 订单号 / 用户姓名 / 手机号模糊匹配 */
  keyword?: string
}

/** 执行类入参：只承载定位键。服务端逐一比对 ID 与售后号，指向不同行即拒绝（防旧列表误操作）。 */
export interface AfterSaleExecuteParams {
  id: string
  afterSaleNo: string
}

/**
 * 取水异常核账依据（只读预览）。状态 6异常待补偿 有两条语义相反来源（A 已退差待复核 / B 未退差），
 * 混同会导致二次退款或无偿完结：判别结论由服务端下发并原样展示，页面不得按订单状态自行猜测。
 */
export interface WaterAbnormalPreview {
  orderId?: string
  orderNo?: string
  /** 订单状态(1341)：可核账时恒为 6异常待补偿 */
  orderStatus?: number
  /** 支付方式(1346)：2水卡余额 3水卡水量 */
  payWay?: number
  /** 订单金额(分)：payWay=2 的预扣金额，也是退差金额的上界 */
  orderAmount?: number
  /** 计划水量(毫升) */
  planMl?: number
  /** 实际水量(毫升)：为空即从未结算过 */
  actualMl?: number
  /** 已退差金额(分)：本单 FLOW_TYPE=4 补偿流水合计 */
  refundedFen?: number
  /** 已退差水量(毫升) */
  refundedMl?: number
  /** 来源判别：A=已退差待复核 B=未退差 UNKNOWN=证据不足或账本断裂 */
  sourceVerdict?: string
  /** 来源判别说明（可直接展示） */
  sourceVerdictDesc?: string
  /** 建议目标状态(1341)：仅供展示，确认接口不接受回传 */
  suggestTargetStatus?: number
  /** 是否可确认：只有服务端明确 true 才放行，其余一律按不可确认处理 */
  confirmable: boolean
  /** 阻断原因：confirmable=false 时必非空 */
  blockReason?: string
}

/** 核账确认入参：只有订单ID与运营说明，终态由服务端重算。 */
export interface WaterAbnormalConfirmParams {
  orderId: string
  /** 运营核账说明，落审计与台账计算快照；后端 @Size(max=200) */
  handleRemark: string
}

/** 核账说明长度上限，与后端 WaterAbnormalReconcileBo 的 @Size(max = 200) 同值。 */
export const WATER_REMARK_MAX_LENGTH = 200

/**
 * 充值/购卡退款依据（只读预览，REQ-061）。页面一列不推导。两个金额是不同维度，必须分开展示：
 * refundableFen=退回支付账户的钱（按实付折算，赠送与已用不退），reverseFen/reverseMl=从卡上收回的权益（批次剩余，含赠送）。
 */
export interface RechargeRefundPreview {
  orderId?: string
  orderNo?: string
  /** 订单状态(1341)：可退时恒为 4已完成 */
  orderStatus?: number
  cardId?: string
  cardNo?: string
  batchId?: string
  /** 批次来源(1375)：1首次购卡 2已有卡充值 3历史聚合（不可退） 4运营赠卡（恒不可退） */
  batchSourceType?: number
  /** 批次状态(1374) */
  batchStatus?: number
  /** 本批次实付金额(分) */
  payAmountFen?: number
  /** 发放余额权益(分)=本金+赠送 */
  grantAmountFen?: number
  /** 其中赠送部分(分) */
  grantBonusFen?: number
  /** 发放水量权益(毫升) */
  grantWaterMl?: number
  /** 剩余余额权益(分) */
  remainAmountFen?: number
  /** 剩余水量权益(毫升) */
  remainWaterMl?: number
  /** true=按水量套餐折算（已用水量占比），false=按纯金额套餐（已消费金额直接抵扣） */
  waterPackage?: boolean
  /** 已消费水量(毫升) */
  usedWaterMl?: number
  /** 预计可退金额(分)：只读，提交时不回传 */
  refundableFen?: number
  /** 预计冲减余额(分)=批次剩余 */
  reverseFen?: number
  /** 预计冲减水量(毫升)=批次剩余 */
  reverseMl?: number
  /** 退款完成后水卡是否会转注销 */
  cardWillClose?: boolean
  /** 是否可退：fail-closed，仅服务端明确 true 才放行 */
  refundable: boolean
  /** 阻断原因：refundable=false 时必非空 */
  blockReason?: string
}

/** 充值退款受理入参：只有订单ID与运营说明，金额一律服务端算定。 */
export interface RechargeRefundParams {
  orderId: string
  /** 运营受理说明，落计算快照与审计；后端 @Size(max=200) 且必填 */
  handleRemark: string
}

// ============ 归一化 ============

/** ID 一律保持字符串，避免大整数被数值化后丢精度。 */
const toIdStr = (v: unknown): string | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  return String(v)
}

const toNum = (v: unknown): number | undefined => {
  if (v === null || v === undefined || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

/** 财务预览只接受非负安全整数；小数、负数和越过 JS 精确范围的值都不能进入确认界面。 */
const toNonNegativeSafeInt = (v: unknown): number | undefined => {
  const n = toNum(v)
  return n !== undefined && Number.isSafeInteger(n) && n >= 0 ? n : undefined
}

/** 空串在本域有语义（脱敏屏蔽结果），故只剔除非字符串，不 trim 成 undefined。 */
const toText = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)

function normalizeActionItem(raw: Record<string, any>): AfterSaleActionItem {
  return {
    id: toIdStr(raw.id) ?? '',
    afterSaleNo: toText(raw.afterSaleNo) ?? '',
    sourceType: toNum(raw.sourceType),
    sourceId: toIdStr(raw.sourceId),
    orderId: toIdStr(raw.orderId),
    orderNo: toText(raw.orderNo),
    orderType: toNum(raw.orderType),
    orderStatus: toNum(raw.orderStatus),
    payWay: toNum(raw.payWay),
    paySource: toNum(raw.paySource),
    orderAmount: toNum(raw.orderAmount),
    userId: toIdStr(raw.userId),
    userName: toText(raw.userName),
    userMaskedPhone: toText(raw.userMaskedPhone),
    cardId: toIdStr(raw.cardId),
    cardNo: toText(raw.cardNo),
    actionType: toNum(raw.actionType),
    strategyCode: toText(raw.strategyCode),
    approvedCount: toNum(raw.approvedCount),
    refundProductFen: toNum(raw.refundProductFen),
    refundServiceFen: toNum(raw.refundServiceFen),
    refundProductMl: toNum(raw.refundProductMl),
    refundAmount: toNum(raw.refundAmount),
    actionStatus: toNum(raw.actionStatus),
    version: toNum(raw.version),
    retryCount: toNum(raw.retryCount),
    nextRetryTime: toText(raw.nextRetryTime),
    approveBy: toIdStr(raw.approveBy),
    approveByName: toText(raw.approveByName),
    approveTime: toText(raw.approveTime),
    finishTime: toText(raw.finishTime),
    lastError: toText(raw.lastError),
    createTime: toText(raw.createTime)
  }
}

/** 布尔一律 fail-closed 归一：只有严格 true 才是 true，字段缺失/脏值都按 false。 */
const toStrictBool = (v: unknown): boolean => v === true

function normalizeRechargeRefundPreview(raw: Record<string, any>): RechargeRefundPreview {
  const payAmountFen = toNonNegativeSafeInt(raw.payAmountFen)
  const grantAmountFen = toNonNegativeSafeInt(raw.grantAmountFen)
  const grantBonusFen = toNonNegativeSafeInt(raw.grantBonusFen)
  const grantWaterMl = toNonNegativeSafeInt(raw.grantWaterMl)
  const remainAmountFen = toNonNegativeSafeInt(raw.remainAmountFen)
  const remainWaterMl = toNonNegativeSafeInt(raw.remainWaterMl)
  const usedWaterMl = toNonNegativeSafeInt(raw.usedWaterMl)
  const refundableFen = toNonNegativeSafeInt(raw.refundableFen)
  const reverseFen = toNonNegativeSafeInt(raw.reverseFen)
  const reverseMl = toNonNegativeSafeInt(raw.reverseMl)
  const evidenceValid = [
    payAmountFen,
    grantAmountFen,
    grantBonusFen,
    grantWaterMl,
    remainAmountFen,
    remainWaterMl,
    usedWaterMl,
    refundableFen,
    reverseFen,
    reverseMl
  ].every((value) => value !== undefined)
  return {
    orderId: toIdStr(raw.orderId),
    orderNo: toText(raw.orderNo),
    orderStatus: toNum(raw.orderStatus),
    cardId: toIdStr(raw.cardId),
    cardNo: toText(raw.cardNo),
    batchId: toIdStr(raw.batchId),
    batchSourceType: toNum(raw.batchSourceType),
    batchStatus: toNum(raw.batchStatus),
    payAmountFen,
    grantAmountFen,
    grantBonusFen,
    grantWaterMl,
    remainAmountFen,
    remainWaterMl,
    waterPackage: toStrictBool(raw.waterPackage),
    usedWaterMl,
    refundableFen,
    reverseFen,
    reverseMl,
    cardWillClose: toStrictBool(raw.cardWillClose),
    // 服务端明确放行且财务证据均为非负安全整数时才允许提交。
    refundable: toStrictBool(raw.refundable) && evidenceValid,
    blockReason:
      toText(raw.blockReason) ||
      (toStrictBool(raw.refundable) && !evidenceValid
        ? '退款依据数据异常，请刷新后联系管理员核对'
        : undefined)
  }
}

function normalizeWaterPreview(raw: Record<string, any>): WaterAbnormalPreview {
  return {
    orderId: toIdStr(raw.orderId),
    orderNo: toText(raw.orderNo),
    orderStatus: toNum(raw.orderStatus),
    payWay: toNum(raw.payWay),
    orderAmount: toNum(raw.orderAmount),
    planMl: toNum(raw.planMl),
    actualMl: toNum(raw.actualMl),
    refundedFen: toNum(raw.refundedFen),
    refundedMl: toNum(raw.refundedMl),
    sourceVerdict: toText(raw.sourceVerdict),
    sourceVerdictDesc: toText(raw.sourceVerdictDesc),
    suggestTargetStatus: toNum(raw.suggestTargetStatus),
    // fail-closed：只有服务端明确给出 true 才允许确认；字段缺失/脏值一律禁用确认按钮
    confirmable: raw.confirmable === true,
    blockReason: toText(raw.blockReason)
  }
}

// ============ 接口 ============

/** 售后台账分页（/order/after-sale/page，权限 order:aftersale:query）。 */
export async function fetchAfterSaleActionPage(
  params: AfterSaleActionPageParams
): Promise<PageResult<AfterSaleActionItem>> {
  const res = await request.post<{ list?: Record<string, any>[]; total?: unknown }>({
    url: '/order/after-sale/page',
    data: params
  })
  const rawList = Array.isArray(res?.list) ? res.list : []
  return {
    list: rawList.map(normalizeActionItem),
    total: toNum(res?.total) ?? rawList.length
  }
}

/** 售后动作详情（/order/after-sale/detail）。行不存在时后端抛业务异常，由 http 层统一提示。 */
export async function fetchAfterSaleActionDetail(id: string): Promise<AfterSaleActionItem> {
  const res = await request.post<Record<string, any>>({
    url: '/order/after-sale/detail',
    data: { id }
  })
  return normalizeActionItem(res ?? {})
}

/**
 * 执行资金返还（/order/after-sale/execute，权限 order:aftersale:handle）。
 * 认领→资金写入→失败落痕三步由服务端独立事务编排；本调用只提交定位键，返还额度不可指定。
 */
export function executeAfterSaleAction(params: AfterSaleExecuteParams): Promise<boolean> {
  return request.post<boolean>({
    url: '/order/after-sale/execute',
    data: { id: params.id, afterSaleNo: params.afterSaleNo }
  })
}

/**
 * 发起外部退款（/order/after-sale/refund/request）。只受理不判定成功：
 * 返回本地退款单ID，退款事实由服务端异步收口后回落到售后动作状态，页面不得据此宣告退款成功。
 */
export async function requestAfterSaleRefund(params: AfterSaleExecuteParams): Promise<string> {
  const res = await request.post<unknown>({
    url: '/order/after-sale/refund/request',
    data: { id: params.id, afterSaleNo: params.afterSaleNo }
  })
  return toIdStr(res) ?? ''
}

/**
 * 生成补送子订单与任务（/order/after-sale/resend/generate）。返回补送子订单ID；
 * 生成后售后动作仍为待执行，直到补送任务被签收才转已完成。
 */
export async function generateAfterSaleResend(params: AfterSaleExecuteParams): Promise<string> {
  const res = await request.post<unknown>({
    url: '/order/after-sale/resend/generate',
    data: { id: params.id, afterSaleNo: params.afterSaleNo }
  })
  return toIdStr(res) ?? ''
}

/**
 * 取水异常核账预览（/order/after-sale/water/preview，只读）。
 * 对不适用的订单同样返回对象并以 blockReason 说明原因，页面据此禁用确认按钮。
 */
export async function fetchWaterAbnormalPreview(orderId: string): Promise<WaterAbnormalPreview> {
  const res = await request.post<Record<string, any>>({
    url: '/order/after-sale/water/preview',
    data: { orderId }
  })
  return normalizeWaterPreview(res ?? {})
}

/**
 * 确认取水异常核账（/order/after-sale/water/confirm，权限 order:aftersale:handle）。
 * 零资金写入，订单终态由服务端在事务内按账本重算，预览的建议终态不参与提交。
 */
export function confirmWaterAbnormal(params: WaterAbnormalConfirmParams): Promise<boolean> {
  return request.post<boolean>({
    url: '/order/after-sale/water/confirm',
    data: { orderId: params.orderId, handleRemark: params.handleRemark }
  })
}

/**
 * 充值退款依据预览（/order/after-sale/refund/recharge/preview，权限 order:aftersale:refund）。
 * 只读、不加锁、不写库；对不可退的订单同样返回对象并以 blockReason 说明原因。
 */
export async function fetchRechargeRefundPreview(orderId: string): Promise<RechargeRefundPreview> {
  const res = await request.post<Record<string, any>>({
    url: '/order/after-sale/refund/recharge/preview',
    data: { orderId }
  })
  return normalizeRechargeRefundPreview(res ?? {})
}

/**
 * 受理并发起充值退款（/order/after-sale/refund/recharge，权限 order:aftersale:refund）。
 * 返回本地退款单ID，不代表退款已成功：权益冲正、卡处置与订单终态在服务端收口时才发生。
 */
export async function requestRechargeRefund(params: RechargeRefundParams): Promise<string> {
  const res = await request.post<unknown>({
    url: '/order/after-sale/refund/recharge',
    data: { orderId: params.orderId, handleRemark: params.handleRemark }
  })
  return toIdStr(res) ?? ''
}

/**
 * 已付款未入账异常充值单全额退款。请求体不含金额；服务端锁订单与支付单后按原支付额算定。
 */
export async function requestUnsettledRechargeRefund(
  params: RechargeRefundParams
): Promise<string> {
  const res = await request.post<unknown>({
    url: '/order/after-sale/refund/recharge/unsettled',
    data: { orderId: params.orderId, handleRemark: params.handleRemark }
  })
  return toIdStr(res) ?? ''
}
