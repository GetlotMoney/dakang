import type { BusinessTime, EntityId, MoneyFen, VolumeMl } from './common'
import { cloneContractData, ContractError } from './common'
import { withRealSession } from './real-session'
import { post } from './request'
import { selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

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

export interface CardDetail extends CardSummary {
  packageName?: string
  scopeDescription: string
  members: CardMember[]
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
}

export interface DeliveryAddress {
  addressId: EntityId
  contactName: string
  maskedPhone: string
  region: string
  detail: string
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
  isDefault: boolean
  locationAuthorized: boolean
}

export interface CardApi {
  getPrimaryCard: () => Promise<CardSummary | null>
  getCardDetail: (cardId: EntityId) => Promise<CardDetail>
  /** 可用卡列表：本人持卡（OWNER）+ 当前有效成员授权卡（MEMBER）；不改 getPrimaryCard 语义。 */
  listUsableCards: () => Promise<UsableCard[]>
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
  saveMember: '/mini/card/member/save',
  revokeMember: '/mini/card/member/revoke',
  familyDetail: '/mini/family/detail',
  familySave: '/mini/family/save',
  familyDelete: '/mini/family/delete',
  familyRewards: '/mini/family/reward/list',
  addresses: '/mini/address/list',
  addressDetail: '/mini/address/detail',
  addressSave: '/mini/address/save',
  addressDelete: '/mini/address/delete',
} as const

function ownCard(cardId: EntityId) {
  const userId = scenarioStore.activeAccount().userId
  const card = scenarioStore.cards.find(
    item => item.cardId === cardId && item.userId === userId,
  )
  if (!card) {
    throw new ContractError('CARD_NOT_FOUND', '水卡不存在或无权访问')
  }
  return card
}

function maskPhone(phone: string) {
  if (!/^1\d{10}$/.test(phone)) {
    throw new ContractError('PHONE_INVALID', '手机号格式不合法')
  }
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

function ownAddress(addressId: EntityId) {
  const userId = scenarioStore.activeAccount().userId
  const address = scenarioStore.addresses.find(
    item => item.addressId === addressId && item.userId === userId,
  )
  if (!address) {
    throw new ContractError('ADDRESS_NOT_FOUND', '水配送地址不存在或无权访问')
  }
  return address
}

const mockCardApi: CardApi = {
  async getPrimaryCard() {
    const userId = scenarioStore.activeAccount().userId
    const card = scenarioStore.cards.find(item => item.userId === userId)
    return card ? cloneContractData(card) : null
  },
  async getCardDetail(cardId) {
    return cloneContractData(ownCard(cardId))
  },
  async listUsableCards() {
    const userId = scenarioStore.activeAccount().userId
    const own: UsableCard[] = scenarioStore.cards
      .filter(item => item.userId === userId)
      .map(item => ({
        ...cloneContractData(item),
        accessRole: 'OWNER' as const,
        canRecharge: true,
        canManageMembers: true,
      }))
    // 成员授权卡：他人卡上存在 enabled 且指向本账号的成员记录（Mock 无用量台账，剩余=限额全额）
    const granted: UsableCard[] = scenarioStore.cards
      .filter(item => item.userId !== userId
        && item.members.some(member => member.enabled && member.memberUserId === userId))
      .map((item) => {
        const grant = item.members.find(member => member.enabled && member.memberUserId === userId)
        return {
          ...cloneContractData(item),
          accessRole: 'MEMBER' as const,
          canRecharge: false,
          canManageMembers: false,
          remainingDailyLimitMl: grant?.dayLimitMl,
        }
      })
    return [...own, ...granted]
  },
  async saveCardMember(input) {
    const card = ownCard(input.cardId)
    if (!input.memberName.trim()) {
      throw new ContractError('MEMBER_NAME_REQUIRED', '成员姓名不能为空')
    }
    if (input.dayLimitMl !== undefined && input.dayLimitMl <= 0) {
      throw new ContractError('DAY_LIMIT_INVALID', '单日限额必须大于 0')
    }
    const existing = input.memberId
      ? card.members.find(item => item.memberId === input.memberId)
      : undefined
    if (input.memberId && !existing) {
      throw new ContractError('CARD_MEMBER_NOT_FOUND', '授权成员不存在')
    }
    const member: CardMember = existing ?? {
      memberId: `MM-${card.members.length + 1}`,
      memberUserId: `PROTOTYPE-${card.members.length + 1}`,
      memberName: '',
      maskedPhone: '',
      enabled: true,
    }
    member.memberName = input.memberName.trim()
    member.maskedPhone = maskPhone(input.phone)
    member.dayLimitMl = input.dayLimitMl
    member.effectiveTime = input.effectiveTime
    member.expireTime = input.expireTime
    member.enabled = true
    if (!existing) {
      card.members.push(member)
    }
    return cloneContractData(member)
  },
  async revokeCardMember(cardId, memberId) {
    const card = ownCard(cardId)
    const member = card.members.find(item => item.memberId === memberId)
    if (!member) {
      throw new ContractError('CARD_MEMBER_NOT_FOUND', '授权成员不存在')
    }
    member.enabled = false
    return cloneContractData(member)
  },
  async getFamilyProfile() {
    const userId = scenarioStore.activeAccount().userId
    const profile = scenarioStore.familyProfiles.find(item => item.userId === userId)
    return profile ? cloneContractData(profile) : null
  },
  async saveFamilyProfile(input) {
    if (!input.privacyAccepted) {
      throw new ContractError('PRIVACY_CONSENT_REQUIRED', '保存家庭资料前必须明确同意隐私说明')
    }
    if (input.memberCount !== undefined && input.memberCount < 0) {
      throw new ContractError('MEMBER_COUNT_INVALID', '家庭人数不能小于 0')
    }
    const userId = scenarioStore.activeAccount().userId
    let profile = scenarioStore.familyProfiles.find(item => item.userId === userId)
    if (!profile) {
      profile = {
        userId,
        profileId: `FAMILY-${userId}`,
        updatedTime: '20260716180000',
      }
      scenarioStore.familyProfiles.push(profile)
    }
    profile.privacyConsentTime ??= '20260716180000'
    profile.memberCount = input.memberCount
    profile.waterHabitNote = input.waterHabitNote?.trim() || undefined
    profile.updatedTime = '20260716180000'
    return cloneContractData(profile)
  },
  async deleteFamilyProfile() {
    const userId = scenarioStore.activeAccount().userId
    const index = scenarioStore.familyProfiles.findIndex(item => item.userId === userId)
    if (index >= 0) {
      scenarioStore.familyProfiles.splice(index, 1)
    }
  },
  async listFamilyRewards() {
    return cloneContractData(scenarioStore.familyRewardRecords)
  },
  async listDeliveryAddresses() {
    const userId = scenarioStore.activeAccount().userId
    return cloneContractData(
      scenarioStore.addresses.filter(item => item.userId === userId),
    )
  },
  async getDeliveryAddress(addressId) {
    return cloneContractData(ownAddress(addressId))
  },
  async saveDeliveryAddress(input) {
    if (!input.contactName.trim() || !input.region.trim() || !input.detail.trim()) {
      throw new ContractError('ADDRESS_REQUIRED', '联系人、区域和详细地址不能为空')
    }
    const userId = scenarioStore.activeAccount().userId
    const existing = input.addressId ? ownAddress(input.addressId) : undefined
    const address = existing ?? {
      userId,
      addressId: `ADDR-${scenarioStore.addresses.length + 1}`,
      contactName: '',
      maskedPhone: '',
      region: '',
      detail: '',
      isDefault: false,
      locationAuthorized: false,
    }
    if (input.isDefault) {
      scenarioStore.addresses
        .filter(item => item.userId === userId)
        .forEach(item => (item.isDefault = false))
    }
    address.contactName = input.contactName.trim()
    address.maskedPhone = maskPhone(input.phone)
    address.region = input.region.trim()
    address.detail = input.detail.trim()
    address.isDefault = input.isDefault
    address.locationAuthorized = input.locationAuthorized
    if (!existing) {
      scenarioStore.addresses.push(address)
    }
    return cloneContractData(address)
  },
  async deleteDeliveryAddress(addressId) {
    const address = ownAddress(addressId)
    const index = scenarioStore.addresses.indexOf(address)
    scenarioStore.addresses.splice(index, 1)
  },
}

/**
 * 后端主水卡原始返回：后端全局 Jackson 将 Long 序列化为字符串（防 JS 精度丢失），
 * 故 cardId/balanceFen/balanceMl 到达前端是数值字符串；cardType/cardStatus 为 Integer 仍是数字。
 * 字段名按 miniapp CardSummary 契约对齐（cardId/balanceFen/balanceMl）；兼容后端可能沿用的 id/balanceAmount。
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

/** 后端主水卡 → 前端 CardSummary（Long→字符串的余额/水量归一化为 number；防精度丢失）。 */
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

/** 后端 MiniCardDetailVo 原样结构。 */
interface CardDetailRaw extends CardSummaryRaw {
  packageName?: string | null
  scopeDescription?: string | null
  members?: CardMemberRaw[] | null
}

/** 后端 MiniUsableCardVo 原样结构（usable-list）。 */
interface UsableCardRaw extends CardSummaryRaw {
  accessRole?: string | null
  canRecharge?: boolean | null
  canManageMembers?: boolean | null
  remainingDailyLimitMl?: string | number | null
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
 * 后端可用卡 → 契约 UsableCard（CARD-MEMBER）。fail-closed 口径：
 * accessRole 只有显式 'OWNER' 才是 OWNER，未知/缺失一律按 MEMBER 降级；
 * MEMBER 卡能力位无条件钉死 false——即使后端（或被篡改的响应）声称可充值，
 * 前端也不得给成员卡开充值/管成员入口；剩余限额走 strictNumber，畸形值归一化为 undefined。
 */
export function normalizeUsableCard(raw: UsableCardRaw): UsableCard {
  const owner = raw.accessRole === 'OWNER'
  return {
    ...normalizeCardSummary(raw),
    accessRole: owner ? 'OWNER' : 'MEMBER',
    canRecharge: owner && raw.canRecharge === true,
    canManageMembers: owner && raw.canManageMembers === true,
    remainingDailyLimitMl: owner ? undefined : strictNonNegativeInt(raw.remainingDailyLimitMl),
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

/**
 * card 域真实适配器（L1f-MP 水卡余额真实展示，修资金误导）。
 *
 * getPrimaryCard/getCardDetail/usable-list/成员授权已接真（KH_USER 会话按登录人强制圈定，
 * CARD-MEMBER 成员接口使用 /mini/card/member/*，手机号只返回脱敏值。
 * 其余方法（家庭/地址）后端 /mini/family|address 未建，暂委托 mock，
 * 待后续切片逐个替换（不引入 realAdapterPending 以免打断已封板演示）。
 */
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
  getFamilyProfile: () => mockCardApi.getFamilyProfile(),
  saveFamilyProfile: input => mockCardApi.saveFamilyProfile(input),
  deleteFamilyProfile: () => mockCardApi.deleteFamilyProfile(),
  listFamilyRewards: () => mockCardApi.listFamilyRewards(),
  listDeliveryAddresses: () => mockCardApi.listDeliveryAddresses(),
  getDeliveryAddress: addressId => mockCardApi.getDeliveryAddress(addressId),
  saveDeliveryAddress: input => mockCardApi.saveDeliveryAddress(input),
  deleteDeliveryAddress: addressId => mockCardApi.deleteDeliveryAddress(addressId),
}

/**
 * U04 取水卡选择判定（CARD-MEMBER：仅一张可用卡默认选中；多张必须用户选；不再依赖
 * 「虚拟卡优先」当业务规则）。抽成纯函数的原因：这条判定决定了"钱从谁的卡里扣"，
 * 藏在页面里就没法证伪——多卡静默选第一张，成员就可能在不知情时扣了卡主的卡。
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

export const cardApi = selectAdapter(mockCardApi, realCardApi, 'card')
