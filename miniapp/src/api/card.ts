import type { BusinessTime, EntityId, MoneyFen, VolumeMl } from './common'
import { withRealSession } from './real-session'
import { post } from './request'

export type CardStatus = 1 | 2 | 3 | 4
/** 水卡类型（PC 字典 1331）：1=虚拟卡 2=实体卡。 */
export type CardType = 1 | 2

export interface CardSummary {
  cardId: EntityId
  cardNo: string
  cardType?: CardType
  cardStatus: CardStatus
  balanceFen: MoneyFen
  balanceMl: VolumeMl
  expireTime?: BusinessTime
}

export interface CardMember {
  memberId: EntityId
  memberUserId: EntityId
  memberName: string
  maskedPhone: string
  dayLimitMl?: VolumeMl
  effectiveTime?: BusinessTime
  expireTime?: BusinessTime
  enabled: boolean
}

/** 带到期时间的权益批次摘要（D-415：「合并后显示多久到期」的数据源）。 */
export interface CardBundle {
  remainFen: MoneyFen
  remainMl: VolumeMl
  expireTime: BusinessTime
}

export interface CardDetail extends CardSummary {
  packageName?: string
  scopeDescription: string
  members: CardMember[]
  /** D-415：本人赠卡且名下有正式水卡时 true（展示投影，服务端合并接口另行强制校验）。 */
  canMergeToPaidCard: boolean
  /** 尚有剩余且带到期时间的权益批次，按到期升序；空数组=全部权益长期有效。 */
  expiringBundles: CardBundle[]
}

/** 赠卡合并结果（/mini/card/merge）。 */
export interface CardMergeResult {
  mainCardId?: EntityId
  mainCardNo?: string
  movedFen: MoneyFen
  movedMl: VolumeMl
  bundleExpireTime?: BusinessTime
  mainBalanceFen?: MoneyFen
  mainBalanceMl?: VolumeMl
  /** true=赠卡已过期，权益作废仅完成清理注销。 */
  expiredCleared: boolean
}

/** 访问角色（CARD-MEMBER）：OWNER=本人持卡 MEMBER=成员授权卡。 */
export type CardAccessRole = 'OWNER' | 'MEMBER'

/**
 * 可用水卡（usable-list）：摘要 + 角色与能力位。能力位只是展示投影，
 * 服务端各接口仍强制校验；归一化时 MEMBER 卡的能力位一律钉死 false（fail-closed）。
 */
export interface UsableCard extends CardSummary {
  accessRole: CardAccessRole
  canRecharge: boolean
  canManageMembers: boolean
  /** 成员今日剩余限额；仅 MEMBER 且配置了限额时有值（undefined=不限或非成员）。 */
  remainingDailyLimitMl?: VolumeMl
  /** D-415：OWNER 的赠卡且名下有正式水卡时 true。 */
  canMergeToPaidCard: boolean
}

export interface DeliveryAddress {
  addressId: EntityId
  contactName: string
  maskedPhone: string
  region: string
  detail: string
  /** 收货区县行政区码（6 位）。缺省=尚未补选；商城选仓完全依赖它，区县文本（region）不能替代。 */
  districtCode?: string
  isDefault: boolean
  locationAuthorized: boolean
}

export interface SaveCardMemberInput {
  cardId: EntityId
  memberId?: EntityId
  memberName: string
  phone: string
  dayLimitMl?: VolumeMl
  effectiveTime?: BusinessTime
  expireTime?: BusinessTime
}

export interface FamilyProfile {
  profileId: EntityId
  privacyConsentTime?: BusinessTime
  memberCount?: number
  waterHabitNote?: string
  updatedTime: BusinessTime
}

export interface SaveFamilyProfileInput {
  privacyAccepted: boolean
  memberCount?: number
  waterHabitNote?: string
}

export interface FamilyRewardRecord {
  recordId: EntityId
  ruleName: string
  status: 'RULE_ONLY' | 'EXTERNAL_SNAPSHOT'
  recordTime?: BusinessTime
}

export interface SaveDeliveryAddressInput {
  addressId?: EntityId
  contactName: string
  phone: string
  region: string
  detail: string
  /** 6 位行政区码；留空表示暂不补选（服务端存 NULL），不是必填项。 */
  districtCode?: string
  isDefault: boolean
  locationAuthorized: boolean
}

export interface CardApi {
  getPrimaryCard: () => Promise<CardSummary | null>
  getCardDetail: (cardId: EntityId) => Promise<CardDetail>
  /** 可用卡列表：本人持卡（OWNER）+ 当前有效成员授权卡（MEMBER）；不改 getPrimaryCard 语义。 */
  listUsableCards: () => Promise<UsableCard[]>
  /** D-415：赠卡合并入正式水卡（目标卡由服务端定位；有效期内转移权益，已过期作废清理）。 */
  mergeGiftCard: (cardId: EntityId) => Promise<CardMergeResult>
  saveCardMember: (input: SaveCardMemberInput) => Promise<CardMember>
  revokeCardMember: (cardId: EntityId, memberId: EntityId) => Promise<CardMember>
  getFamilyProfile: () => Promise<FamilyProfile | null>
  saveFamilyProfile: (input: SaveFamilyProfileInput) => Promise<FamilyProfile>
  deleteFamilyProfile: () => Promise<void>
  listFamilyRewards: () => Promise<FamilyRewardRecord[]>
  listDeliveryAddresses: () => Promise<DeliveryAddress[]>
  getDeliveryAddress: (addressId: EntityId) => Promise<DeliveryAddress>
  saveDeliveryAddress: (input: SaveDeliveryAddressInput) => Promise<DeliveryAddress>
  deleteDeliveryAddress: (addressId: EntityId) => Promise<void>
}

export const cardEndpoints = {
  primary: '/mini/card/primary',
  detail: '/mini/card/detail',
  usableList: '/mini/card/usable-list',
  merge: '/mini/card/merge',
  saveMember: '/mini/card/member/save',
  revokeMember: '/mini/card/member/revoke',
  familyGet: '/mini/family/profile/get',
  familySave: '/mini/family/profile/save',
  familyDelete: '/mini/family/profile/delete',
  addresses: '/mini/family/address/list',
  addressGet: '/mini/family/address/get',
  addressSave: '/mini/family/address/save',
  addressDelete: '/mini/family/address/delete',
} as const

/**
 * 后端主水卡原始返回：Long 序列化为字符串（防 JS 精度丢失），Integer 仍是数字；
 * 兼容后端可能沿用的 id/balanceAmount 字段名。
 */
interface CardSummaryRaw {
  cardId?: string | number | null
  id?: string | number | null
  cardNo: string
  cardType?: number | null
  cardStatus: number
  balanceFen?: string | number | null
  balanceAmount?: string | number | null
  balanceMl: string | number
  expireTime?: string | null
}

/** 后端 MiniCardMemberVo 原样结构（Long 已按字符串下发）。 */
interface CardMemberRaw {
  memberId?: string | number
  memberUserId?: string | number
  memberName?: string
  maskedPhone?: string
  dayLimitMl?: string | number | null
  effectiveTime?: string | null
  expireTime?: string | null
  enabled?: boolean
}

/** 后端 MiniCardBundleVo 原样结构（Long 已按字符串下发）。 */
interface CardBundleRaw {
  remainFen?: string | number | null
  remainMl?: string | number | null
  expireTime?: string | null
}

/** 后端 MiniCardDetailVo 原样结构。 */
interface CardDetailRaw extends CardSummaryRaw {
  packageName?: string | null
  scopeDescription?: string | null
  members?: CardMemberRaw[] | null
  canMergeToPaidCard?: boolean | null
  expiringBundles?: CardBundleRaw[] | null
}

/** 后端 MiniCardMergeVo 原样结构。 */
interface CardMergeResultRaw {
  mainCardId?: string | number | null
  mainCardNo?: string | null
  movedFen?: string | number | null
  movedMl?: string | number | null
  bundleExpireTime?: string | null
  mainBalanceFen?: string | number | null
  mainBalanceMl?: string | number | null
  expiredCleared?: boolean | null
}

/** 后端 MiniUsableCardVo 原样结构（usable-list）。 */
interface UsableCardRaw extends CardSummaryRaw {
  accessRole?: string | null
  canRecharge?: boolean | null
  canManageMembers?: boolean | null
  remainingDailyLimitMl?: string | number | null
  canMergeToPaidCard?: boolean | null
}

/** 后端成员记录 → 契约 CardMember（save/revoke/detail 共用；脱敏号只透传绝不拼造）。 */
export function normalizeCardMember(raw: CardMemberRaw): CardMember {
  return {
    memberId: String(raw.memberId ?? ''),
    memberUserId: String(raw.memberUserId ?? ''),
    memberName: raw.memberName ?? '',
    // 后端只下发脱敏号；缺失时留空，绝不在前端拼造。
    maskedPhone: raw.maskedPhone ?? '',
    dayLimitMl: raw.dayLimitMl == null ? undefined : Number(raw.dayLimitMl),
    effectiveTime: raw.effectiveTime == null ? undefined : raw.effectiveTime,
    expireTime: raw.expireTime == null ? undefined : raw.expireTime,
    enabled: raw.enabled === true,
  }
}

/**
 * 后端卡详情 → 契约 CardDetail。
 * scopeDescription 缺失时按「未配置（默认拒绝）」呈现——绝不因字段缺失就显示为可用（fail-closed 口径与后端一致）。
 */
export function normalizeCardDetail(raw: CardDetailRaw): CardDetail {
  return {
    ...normalizeCardSummary(raw),
    packageName: raw.packageName == null ? undefined : raw.packageName,
    scopeDescription: raw.scopeDescription == null ? '未配置（默认拒绝）' : raw.scopeDescription,
    members: (raw.members ?? []).map(normalizeCardMember),
    // fail-closed：仅显式 true 才展示合并入口（服务端合并接口仍强制校验）
    canMergeToPaidCard: raw.canMergeToPaidCard === true,
    expiringBundles: (raw.expiringBundles ?? [])
      .filter(b => typeof b.expireTime === 'string' && b.expireTime.length > 0)
      .map(b => ({
        remainFen: Number(b.remainFen ?? 0),
        remainMl: Number(b.remainMl ?? 0),
        expireTime: b.expireTime as string,
      })),
  }
}

/** 后端合并结果 → 契约 CardMergeResult（Long→number 归一化；expiredCleared 仅显式 true）。 */
export function normalizeCardMergeResult(raw: CardMergeResultRaw): CardMergeResult {
  return {
    mainCardId: raw.mainCardId == null ? undefined : String(raw.mainCardId),
    mainCardNo: raw.mainCardNo == null ? undefined : raw.mainCardNo,
    movedFen: Number(raw.movedFen ?? 0),
    movedMl: Number(raw.movedMl ?? 0),
    bundleExpireTime: raw.bundleExpireTime == null ? undefined : raw.bundleExpireTime,
    mainBalanceFen: raw.mainBalanceFen == null ? undefined : Number(raw.mainBalanceFen),
    mainBalanceMl: raw.mainBalanceMl == null ? undefined : Number(raw.mainBalanceMl),
    expiredCleared: raw.expiredCleared === true,
  }
}

/** 数值字段严格归一化（strictNumber 手法，与 order.ts 同口径）：仅接受非负安全整数或其十进制字符串。 */
function strictNonNegativeInt(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 ? value : undefined
  }
  if (typeof value === 'string' && /^(?:0|[1-9]\d*)$/.test(value)) {
    const n = Number(value)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

/**
 * 后端可用卡 → 契约 UsableCard（CARD-MEMBER）。fail-closed：accessRole 只有显式 'OWNER' 才是 OWNER，
 * 未知/缺失按 MEMBER 降级；MEMBER 卡能力位无条件钉死 false；剩余限额畸形值归一化为 undefined。
 */
export function normalizeUsableCard(raw: UsableCardRaw): UsableCard {
  const owner = raw.accessRole === 'OWNER'
  return {
    ...normalizeCardSummary(raw),
    accessRole: owner ? 'OWNER' : 'MEMBER',
    canRecharge: owner && raw.canRecharge === true,
    canManageMembers: owner && raw.canManageMembers === true,
    remainingDailyLimitMl: owner ? undefined : strictNonNegativeInt(raw.remainingDailyLimitMl),
    canMergeToPaidCard: owner && raw.canMergeToPaidCard === true,
  }
}

function normalizeCardSummary(raw: CardSummaryRaw): CardSummary {
  return {
    cardId: String(raw.cardId ?? raw.id),
    cardNo: raw.cardNo,
    cardType: raw.cardType == null ? undefined : (raw.cardType as CardType),
    cardStatus: raw.cardStatus as CardStatus,
    balanceFen: Number(raw.balanceFen ?? raw.balanceAmount ?? 0),
    balanceMl: Number(raw.balanceMl ?? 0),
    expireTime: raw.expireTime == null ? undefined : raw.expireTime,
  }
}

/** card 域真实适配器：KH_USER 会话按登录人强制圈定，手机号只返回脱敏值。 */

// ---- 家庭/地址真实契约归一化（Long ID 恒 string；电话只收服务端脱敏值） ----

interface FamilyProfileRaw {
  profileId?: unknown
  privacyConsentTime?: unknown
  memberCount?: unknown
  waterHabitNote?: unknown
  updatedTime?: unknown
}

interface DeliveryAddressRaw {
  addressId?: unknown
  contactName?: unknown
  maskedPhone?: unknown
  region?: unknown
  detail?: unknown
  districtCode?: unknown
  isDefault?: unknown
  locationAuthorized?: unknown
}

/**
 * 区县码归一：只认 6 位十进制数字，其余一律 undefined——半截值会让商城选仓在服务端悄悄命中不到任何仓。
 */
export function normalizeDistrictCode(raw: unknown): string | undefined {
  return typeof raw === 'string' && /^\d{6}$/.test(raw) ? raw : undefined
}

function normalizeFamilyProfile(raw: FamilyProfileRaw): FamilyProfile {
  return {
    profileId: String(raw.profileId ?? '') as EntityId,
    privacyConsentTime: raw.privacyConsentTime ? String(raw.privacyConsentTime) : undefined,
    memberCount: typeof raw.memberCount === 'number' ? raw.memberCount : undefined,
    waterHabitNote: raw.waterHabitNote ? String(raw.waterHabitNote) : undefined,
    updatedTime: String(raw.updatedTime ?? ''),
  }
}

export function normalizeDeliveryAddress(raw: DeliveryAddressRaw): DeliveryAddress {
  return {
    addressId: String(raw.addressId ?? '') as EntityId,
    contactName: String(raw.contactName ?? ''),
    maskedPhone: String(raw.maskedPhone ?? ''),
    region: String(raw.region ?? ''),
    detail: String(raw.detail ?? ''),
    districtCode: normalizeDistrictCode(raw.districtCode),
    isDefault: raw.isDefault === true,
    locationAuthorized: raw.locationAuthorized === true,
  }
}

const realCardApi: CardApi = {
  async getPrimaryCard() {
    return withRealSession(async () => {
      // 无卡时后端返回 null（code 0 data null），透传 null 与 mock 口径一致。
      const raw = await post<CardSummaryRaw | null>(cardEndpoints.primary, {})
      return raw ? normalizeCardSummary(raw) : null
    })
  },
  // L2-READ 接真：按 cardId 取本人卡详情。归属校验在服务端 WHERE 内完成，
  // 传他人 cardId 一律返回业务异常（不降级为主卡、也不返回 null）。
  async getCardDetail(cardId) {
    return withRealSession(async () => {
      const raw = await post<CardDetailRaw>(cardEndpoints.detail, { cardId })
      return normalizeCardDetail(raw)
    })
  },
  // CARD-MEMBER：可用卡列表=本人持卡+有效成员授权卡；角色与能力位经 fail-closed 归一化。
  async listUsableCards() {
    return withRealSession(async () => {
      const raw = await post<UsableCardRaw[] | null>(cardEndpoints.usableList, {})
      return (raw ?? []).map(normalizeUsableCard)
    })
  },
  // D-415：赠卡合并入正式水卡。仅上送赠卡 cardId，目标卡由服务端锁内定位。
  async mergeGiftCard(cardId) {
    return withRealSession(async () => {
      const raw = await post<CardMergeResultRaw>(cardEndpoints.merge, { cardId })
      return normalizeCardMergeResult(raw)
    })
  },
  // CARD-MEMBER：成员授权保存/撤销接真。卡主身份由服务端会话强制，手机号仅上送匹配、响应只回脱敏。
  async saveCardMember(input) {
    return withRealSession(async () => {
      const raw = await post<CardMemberRaw>(cardEndpoints.saveMember, {
        cardId: input.cardId,
        memberId: input.memberId,
        memberName: input.memberName,
        phone: input.phone,
        dayLimitMl: input.dayLimitMl,
        effectiveTime: input.effectiveTime,
        expireTime: input.expireTime,
      })
      return normalizeCardMember(raw)
    })
  },
  async revokeCardMember(cardId, memberId) {
    return withRealSession(async () => {
      const raw = await post<CardMemberRaw>(cardEndpoints.revokeMember, { cardId, memberId })
      return normalizeCardMember(raw)
    })
  },
  // 家庭资料与地址簿（2026-08-02 链路落地 /mini/family/**）：电话恒服务端脱敏，原号不回流
  async getFamilyProfile() {
    return withRealSession(async () => {
      const raw = await post<FamilyProfileRaw | null>(cardEndpoints.familyGet, {})
      return raw ? normalizeFamilyProfile(raw) : null
    })
  },
  async saveFamilyProfile(input) {
    return withRealSession(async () => {
      const raw = await post<FamilyProfileRaw>(cardEndpoints.familySave, {
        privacyAccepted: input.privacyAccepted,
        memberCount: input.memberCount,
        waterHabitNote: input.waterHabitNote,
      })
      return normalizeFamilyProfile(raw)
    })
  },
  async deleteFamilyProfile() {
    return withRealSession(async () => {
      await post<null>(cardEndpoints.familyDelete, {})
    })
  },
  /** 家庭奖励规则记录：无真实规则引擎，恒空列表（页面空态），不造演示行。 */
  async listFamilyRewards() {
    return []
  },
  async listDeliveryAddresses() {
    return withRealSession(async () => {
      const raw = await post<DeliveryAddressRaw[]>(cardEndpoints.addresses, {})
      return (raw ?? []).map(normalizeDeliveryAddress)
    })
  },
  async getDeliveryAddress(addressId) {
    return withRealSession(async () => {
      const raw = await post<DeliveryAddressRaw>(cardEndpoints.addressGet, { addressId })
      return normalizeDeliveryAddress(raw)
    })
  },
  async saveDeliveryAddress(input) {
    return withRealSession(async () => {
      const raw = await post<DeliveryAddressRaw>(cardEndpoints.addressSave, {
        addressId: input.addressId,
        contactName: input.contactName,
        phone: input.phone,
        region: input.region,
        detail: input.detail,
        // 上送前同样只放行 6 位码：把半截值送上去等于让服务端存一个永远选不到仓的地址
        districtCode: normalizeDistrictCode(input.districtCode) ?? '',
        isDefault: input.isDefault,
        locationAuthorized: input.locationAuthorized,
      })
      return normalizeDeliveryAddress(raw)
    })
  },
  async deleteDeliveryAddress(addressId) {
    return withRealSession(async () => {
      await post<null>(cardEndpoints.addressDelete, { addressId })
    })
  },
}

/**
 * U04 取水卡选择判定（CARD-MEMBER）：仅一张可用卡默认选中，多张必须用户选——
 * 多卡静默选第一张会在成员不知情时扣了卡主的卡。
 */
export type CardSelection
  = | { mode: 'none' }
    | { mode: 'auto', selected: UsableCard }
    | { mode: 'choose', candidates: UsableCard[] }

export function resolveCardSelection(cards: UsableCard[]): CardSelection {
  if (cards.length === 0) {
    return { mode: 'none' }
  }
  if (cards.length === 1) {
    return { mode: 'auto', selected: cards[0] }
  }
  return { mode: 'choose', candidates: cards }
}

/** 充值入口显隐唯一判据：MEMBER 卡 canRecharge 恒为 false（成员不得为卡主的卡充值）。 */
export function canShowRechargeEntry(card: Pick<UsableCard, 'accessRole' | 'canRecharge'>): boolean {
  return card.accessRole === 'OWNER' && card.canRecharge
}

export const cardApi = realCardApi
