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

// ==================== C 端用户（只读） ====================

/** C 端用户列表项 */
export interface WsUserItem {
  id: number
  userName: string
  /** 性别(20)：1男 2女 */
  userGender?: number
  userPhone: string
  userAvatar?: string
  // 微信 openid 为身份密钥，普通用户列表/详情不再下发（复审 P1-4），故此处不含 wechatXcxOpenid。
  channelUserId?: number
  referrerUserId?: number
  promoCode?: string
  createTime?: string
  updateTime?: string
}

/** C 端用户分页查询参数 */
export interface WsUserSearchParams {
  current: number
  size: number
  userName?: string
  userPhone?: string
}

/** C 端用户分页（机主选择器等场景共用） */
export function fetchWsUserPage(params: WsUserSearchParams) {
  return request.post<{ total: number; list: WsUserItem[] }>({
    url: '/user/user/page',
    data: params
  })
}

/** C 端用户详情 */
export function fetchWsUserDetail(id: number) {
  return request.post<WsUserItem>({
    url: '/user/user/detail',
    data: { id }
  })
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
  userId: number
  userName?: string
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
  userId?: number
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

/** 按用户查卡（用户详情抽屉聚合） */
export function fetchCardListByUser(userId: number) {
  return request.post<CardItem[]>({
    url: '/user/card/listByUser',
    data: { userId }
  })
}

/** 冻结/解冻（targetStatus：1解冻为正常 2冻结） */
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

export function fetchChangeCardStatus(id: string, targetStatus: number, changeReason?: string) {
  return request.post<boolean>({
    url: '/user/card/changeStatus',
    data: { id, targetStatus, changeReason }
  })
}

// ==================== 配送员 ====================

/** 配送员列表项 / 详情 */
export interface CourierItem {
  id: number
  userId: number
  userName?: string
  courierName: string
  courierPhone: string
  idCardNo?: string
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

/** 配送员新增表单 */
export interface CourierFormData {
  userId: number
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
export function fetchCourierDetail(id: number) {
  return request.post<CourierItem>({
    url: '/user/courier/detail',
    data: { id }
  })
}

/** 按用户查询配送员准入记录（无则返回 null） */
export function fetchCourierByUser(userId: number) {
  return request.post<CourierItem | null>({
    url: '/user/courier/getByUser',
    data: { userId }
  })
}

/** 人工创建配送员（创建即待审核） */
export function fetchAddCourier(data: CourierFormData) {
  return request.post<number>({
    url: '/user/courier/add',
    data
  })
}

/** 审核/启停（targetStatus：2启用/通过 3停用 4驳回；驳回/停用必填备注） */
export function fetchAuditCourier(id: number, targetStatus: number, auditRemark?: string) {
  return request.post<boolean>({
    url: '/user/courier/audit',
    data: { id, targetStatus, auditRemark }
  })
}

export interface GiftIssueParams {
  /** 规范 UUID：幂等锚，卡号由它确定性派生 */
  requestId: string
  /** 收卡用户 ID：身份类 Long 恒 string 直传（>2^53 经 Number 舍入会发错人），Spring 转 Long */
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
