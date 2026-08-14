/**
 * 用户管理 API（用户 /user/user/*、水卡 /user/card/*、配送员 /user/courier/*）
 *
 * 一期口径：用户只读（REQ-018）；水卡只读+冻结/解冻（REQ-075，开卡充值迁移属商业一期）；
 * 配送员人工创建+审核/启停准入状态机（REQ-079）。
 */
import request from '@/utils/http'
import {
  normalizeRechargePackageSnapshot,
  packageSnapshotStateOf,
  type PackageSnapshotState,
  type RechargePackageSnapshot
} from '@/utils/recharge-snapshot'
import {
  userAuditRowOf,
  userFlowRowOf,
  userOrderRowOf,
  userPage,
  userRelationItemOf,
  userRelationOf
} from './user-normalize'

// ==================== C 端用户（只读） ====================

/** 能力标签取值，与服务端能力投影同源；机主由名下水站/设备判定，配送由启用态准入记录判定 */
export const USER_CAPABILITY = {
  owner: 'OWNER_VIEW',
  courier: 'COURIER_WORK'
} as const

/**
 * C 端用户列表项。
 *
 * 身份类 Long ID 恒为 string：超过 2^53 的值经 Number 会静默舍入到相邻可表示值，
 * 界面上选中的是 A、请求里带走的却是 B，且全程不报错。
 */
export interface WsUserItem {
  id: string
  userName: string
  /** 性别(20)：1男 2女 */
  userGender?: number
  /** 已脱敏，前 3 后 4；长度异常时服务端整体屏蔽为空串 */
  userPhone: string
  userAvatar?: string
  // 微信 openid 为身份密钥，普通用户列表/详情不再下发（复审 P1-4），故此处不含 wechatXcxOpenid。
  /** 是否禁用(10)：1正常 2禁用 */
  disabledFlag?: number
  /** 用户状态：1正常 2注销 */
  userStatus?: number
  /** 能力标签：OWNER_VIEW 机主 / COURIER_WORK 配送员 */
  capabilities?: string[]
  channelUserId?: string
  referrerUserId?: string
  promoCode?: string
  createTime?: string
  updateTime?: string
}

/** C 端用户分页查询参数 */
export interface WsUserSearchParams {
  current: number
  size: number
  /** 用户ID精确匹配（string 直传，不做数值化） */
  id?: string
  userName?: string
  userPhone?: string
  /** 是否禁用(10)：1正常 2禁用 */
  disabledFlag?: number
  /** 能力标签，取值见 USER_CAPABILITY */
  capability?: string
  /** 注册时间起，yyyyMMddHHmmss 14 位；服务端按同格式做字符串比较，带分隔符会被拒绝 */
  createTimeBegin?: string
  /** 注册时间止，yyyyMMddHHmmss 14 位 */
  createTimeEnd?: string
}

/** C 端用户分页（机主选择器等场景共用） */
export function fetchWsUserPage(params: WsUserSearchParams) {
  return request.post<{ total: number; list: WsUserItem[] }>({
    url: '/user/user/page',
    data: params
  })
}

/** C 端用户详情 */
export function fetchWsUserDetail(id: string) {
  return request.post<WsUserItem>({
    url: '/user/user/detail',
    data: { id }
  })
}

// ==================== 用户档案（一人一档，只读；每区独立分页） ====================

/** 档案分区分页参数 */
export interface UserProfilePageParams {
  current: number
  size: number
  userId: string
}

/** 档案分区：订单行 */
export interface UserOrderItem {
  id: string
  orderNo: string
  /** 订单类型(1340) */
  orderType: number
  /** 订单状态(1341) */
  orderStatus: number
  /** 订单金额(分) */
  orderAmount?: number
  /** 计划水量(毫升) */
  planMl?: number
  /** 实际水量(毫升) */
  actualMl?: number
  createTime?: string
  finishTime?: string
}

/** 档案分区：资金流水行（AFTER 两列是写入时冻结的权威快照） */
export interface UserFlowItem {
  id: string
  cardId: string
  cardNo?: string
  /** 流水类型(1344) */
  flowType: number
  /** 余额变动(分)，正入负出 */
  amountChange: number
  /** 水量变动(毫升)，正入负出 */
  mlChange: number
  /** 变动后余额(分) */
  amountAfter: number
  /** 变动后水量(毫升) */
  mlAfter: number
  orderId?: string
  flowRemark?: string
  createTime?: string
}

/** 档案分区：关系归属概览（只有直接上一级与直接下级，不含任何层级推导） */
export interface UserRelation {
  ownInviteCode?: string
  promoCode?: string
  referrerUserId?: string
  referrerUserName?: string
  /** 已脱敏 */
  referrerUserPhone?: string
  /** 绑定列指向的账号已不可用 */
  referrerMissing?: boolean
  directInviteeCount?: number
}

/** 档案分区：直接下级行 */
export interface UserRelationItem {
  id: string
  userName?: string
  /** 已脱敏 */
  userPhone?: string
  createTime?: string
}

/** 档案分区：审计记录行（不含事件报文） */
export interface UserAuditItem {
  id: string
  eventType?: number
  eventTypeName?: string
  eventKey?: string
  actorPortal?: number
  actorPortalName?: string
  createTime?: string
}

/** 档案·身份资料 */
export function fetchUserProfileIdentity(userId: string) {
  return request.post<WsUserItem>({ url: '/user/profile/identity', data: { userId } })
}

/** 档案·订单分页 */
export async function fetchUserProfileOrderPage(params: UserProfilePageParams) {
  const raw = await request.post<unknown>({ url: '/user/profile/orderPage', data: params })
  return userPage<UserOrderItem>(raw, userOrderRowOf)
}

/** 档案·资金流水分页（只读事实，无任何调整入口） */
export async function fetchUserProfileFlowPage(params: UserProfilePageParams) {
  const raw = await request.post<unknown>({ url: '/user/profile/flowPage', data: params })
  return userPage<UserFlowItem>(raw, userFlowRowOf)
}

/** 档案·关系归属概览 */
export async function fetchUserProfileRelation(userId: string) {
  const raw = await request.post<unknown>({ url: '/user/profile/relation', data: { userId } })
  return userRelationOf(raw)
}

/** 档案·直接下级分页（仅一级） */
export async function fetchUserProfileInviteePage(params: UserProfilePageParams) {
  const raw = await request.post<unknown>({ url: '/user/profile/inviteePage', data: params })
  return userPage<UserRelationItem>(raw, userRelationItemOf)
}

/** 档案·审计记录分页 */
export async function fetchUserProfileAuditPage(params: UserProfilePageParams) {
  const raw = await request.post<unknown>({ url: '/user/profile/auditPage', data: params })
  return userPage<UserAuditItem>(raw, userAuditRowOf)
}

// ==================== 水卡 ====================

/**
 * 水卡授权成员。身份类 Long ID（id/cardId/memberUserId）恒为 string（S4 R2）：
 * 超过 2^53 的 Long 经 Number 会静默舍入到相邻可表示值——选 A 实际动 B。
 */
export interface CardMemberItem {
  id: string
  cardId: string
  memberUserId: string
  memberUserName?: string
  memberUserPhone?: string
  memberName?: string
  /** 单日限额(毫升)，空=不限 */
  dayLimitMl?: number
  /** 授权状态(1333)：1生效 2已解除 */
  memberStatus: number
  createTime?: string
}

/** 水卡列表项 / 详情。卡主键为身份类 Long ID，边界恒 string（S4 R2，禁数值转换）。 */
export interface CardItem {
  id: string
  cardNo: string
  /** 卡类型(1331)：1虚拟卡 2实体卡 */
  cardType: number
  userId: string
  userName?: string
  /** 已脱敏 */
  userPhone?: string
  /** 余额(分) */
  balanceAmount: number
  /** 剩余水量(毫升) */
  balanceMl: number
  packageId?: number
  packageSnap?: string
  packageSnapshot?: RechargePackageSnapshot
  packageSnapshotState?: PackageSnapshotState
  scopeJson?: string
  /** 到期时间，空=永久 */
  expireTime?: string
  /** 卡状态(1332)：1正常 2冻结 3已过期 4已注销 */
  cardStatus: number
  memberCount?: number
  memberList?: CardMemberItem[]
  cardRemark?: string
  createTime?: string
  updateTime?: string
}

/** 水卡分页查询参数 */
export interface CardSearchParams {
  current: number
  size: number
  cardNo?: string
  cardType?: number
  cardStatus?: number
  userId?: string
}

/** 水卡分页 */
export function fetchCardPage(params: CardSearchParams) {
  return request.post<{ total: number; list: CardItem[] }>({
    url: '/user/card/page',
    data: params
  })
}

/** 水卡详情（含授权成员列表）。id 为 string 身份 ID，JSON 原文直传由 Spring 转 Long。 */
export async function fetchCardDetail(id: string): Promise<CardItem> {
  const card = await request.post<CardItem>({
    url: '/user/card/detail',
    data: { id }
  })
  const packageSnapshot = normalizeRechargePackageSnapshot(card.packageSnap)
  return {
    ...card,
    packageSnapshot,
    packageSnapshotState: packageSnapshotStateOf(card.packageSnap, packageSnapshot)
  }
}

/** 按用户查卡（不分页；用户档案抽屉改走 /user/card/page 分页口径，本函数保留给一次性小批量场景） */
export function fetchCardListByUser(userId: string) {
  return request.post<CardItem[]>({
    url: '/user/card/listByUser',
    data: { userId }
  })
}

/**
 * 修改水卡授权范围（S4 R1）：服务端构造并校验 JSON；expectedScopeJson 为并发防覆盖锚。
 * 身份类 Long ID 恒为 string（>2^53 时 Number 会静默舍入到相邻值，选 A 改 B），
 * JSON 字符串 ID 由 Spring 转 Long。
 */
export function fetchUpdateCardScope(data: {
  cardId: string
  scopeType: 'all' | 'specified'
  stationIds?: string[]
  deviceIds?: string[]
  outletIds?: string[]
  reason: string
  expectedScopeJson?: string
}) {
  return request.post<boolean>({ url: '/user/card/updateScope', data })
}

/** 冻结/解冻（targetStatus：1解冻为正常 2冻结） */
export function fetchChangeCardStatus(id: string, targetStatus: number, changeReason?: string) {
  return request.post<boolean>({
    url: '/user/card/changeStatus',
    data: { id, targetStatus, changeReason }
  })
}

// ==================== 配送员 ====================

/**
 * 配送员列表项 / 详情。身份类 Long ID 恒为 string（>2^53 经 Number 会静默改人）。
 * 读侧不含身份证号：完整证件号一旦下发就已经离开服务端，展示层再打星也追不回来。
 */
export interface CourierItem {
  id: string
  userId: string
  userName?: string
  courierName: string
  /** 已脱敏 */
  courierPhone: string
  /** 服务水站ID集(逗号分隔)，空=未配置且默认不可接单 */
  stationIds?: string
  stationNames?: string
  serviceRegion?: string
  /** 状态(1350)：1待审核 2启用 3停用 4审核驳回 */
  courierStatus: number
  auditRemark?: string
  createTime?: string
  updateTime?: string
}

/** 配送员分页查询参数 */
export interface CourierSearchParams {
  current: number
  size: number
  courierName?: string
  courierPhone?: string
  courierStatus?: number
}

/** 配送员新增表单（写侧仍收集身份证号用于实名留档，仅上行、不回显） */
export interface CourierFormData {
  userId: string
  courierName: string
  courierPhone: string
  idCardNo?: string
  stationIds?: string
  serviceRegion?: string
}

/** 配送员分页（待审核排前） */
export function fetchCourierPage(params: CourierSearchParams) {
  return request.post<{ total: number; list: CourierItem[] }>({
    url: '/user/courier/page',
    data: params
  })
}

/** 配送员详情 */
export function fetchCourierDetail(id: string) {
  return request.post<CourierItem>({
    url: '/user/courier/detail',
    data: { id }
  })
}

/** 按用户查询配送员准入记录（无则返回 null） */
export function fetchCourierByUser(userId: string) {
  return request.post<CourierItem | null>({
    url: '/user/courier/getByUser',
    data: { userId }
  })
}

/** 人工创建配送员（创建即待审核） */
export function fetchAddCourier(data: CourierFormData) {
  return request.post<string>({
    url: '/user/courier/add',
    data
  })
}

/** 审核/启停（targetStatus：2启用/通过 3停用 4驳回；驳回/停用必填备注） */
export function fetchAuditCourier(id: string, targetStatus: number, auditRemark?: string) {
  return request.post<boolean>({
    url: '/user/courier/audit',
    data: { id, targetStatus, auditRemark }
  })
}

export interface GiftIssueParams {
  /** 规范 UUID：幂等锚，卡号由它确定性派生 */
  requestId: string
  /** 收卡用户 ID：Long 恒 string 直传（数值化舍入会发错人） */
  userId: string
  /** 赠送余额(分)，与水量至少其一为正 */
  grantFen?: number
  /** 赠送水量(毫升) */
  grantMl?: number
  /** 1~3650 天（D-213 赠卡必带有效期） */
  expireDays: number
  remark?: string
}

/** 运营赠卡发放（高风险三件套端点；请求号幂等，重放返回同一张卡） */
export function fetchGiftIssue(data: GiftIssueParams) {
  return request.post<string>({ url: '/user/card/issueGift', data })
}
