import type { CapabilityCode } from './account'
import type { BusinessTime, EntityId, EvidenceMode } from './common'
import { ContractError } from './common'
import { post } from './request'
import { useAccountStore } from '@/store/account'

export type MessageDomain = 'water' | 'card' | 'delivery' | 'owner' | 'system'
export type MessageChannel = 'in-app' | 'wechat-subscribe'
export type MessageSendStatus = 1 | 2 | 3 | 4

export interface MessageItem {
  messageId: EntityId
  accountId: EntityId
  domain: MessageDomain
  title: string
  summary: string
  content: string
  channel: MessageChannel
  sendStatus: MessageSendStatus
  sendTime?: BusinessTime
  unread: boolean
  objectType?: 'order' | 'task' | 'device' | 'appeal' | 'service'
  objectId?: string
  requiredCapability?: CapabilityCode
  /** real=真实库消息；prototype/external-snapshot 为 mock 演示态（能力撤销降级只存在于 mock）。 */
  evidenceMode: EvidenceMode
  objectAccess?: 'allowed' | 'capability-revoked'
}

export interface MessageApi {
  listMessages: (domain?: MessageDomain) => Promise<MessageItem[]>
  getMessageDetail: (messageId: EntityId) => Promise<MessageItem>
  markRead: (messageId: EntityId) => Promise<void>
}

export const messageEndpoints = {
  list: '/mini/message/page',
  detail: '/mini/message/detail',
  read: '/mini/message/read',
} as const

// ---------------------------------------------------------------------------
// real 适配器（E2E-07 包C）：三端点对齐服务端 MiniMessageController。
// 服务端按会话强制过滤（铁律6），字段为字典 tinyint——映射归一只做在这一层，页面签名不变。
// ---------------------------------------------------------------------------

/** 字典 1312 ↔ 前端域枚举（与 deploy/mysql/init/02-ws-business.sql 字典同源，契约测试锁定）。 */
export const DOMAIN_BY_VALUE: Record<number, MessageDomain> = {
  1: 'water',
  2: 'card',
  3: 'delivery',
  4: 'owner',
  5: 'system',
}
export const VALUE_BY_DOMAIN: Record<MessageDomain, number> = {
  water: 1,
  card: 2,
  delivery: 3,
  owner: 4,
  system: 5,
}

const OBJECT_TYPES = new Set(['order', 'task', 'device', 'appeal', 'service'])

interface RealMessageRaw {
  messageId: unknown
  msgDomain: unknown
  msgTitle: unknown
  summary: unknown
  msgContent: unknown
  msgChannel: unknown
  sendStatus: unknown
  sendTime: unknown
  readFlag: unknown
  objectType: unknown
  objectId: unknown
}

/** 全局 Long→字符串序列化下数字字段可能到达为字符串：双形态收敛为有限数，畸形返回 undefined。 */
function strictNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value !== '' && Number.isFinite(Number(value))) {
    return Number(value)
  }
  return undefined
}

/** 纯函数归一化（accountId 由适配器注入，保持本函数可脱离 pinia 单测）。 */
export function normalizeRealMessage(raw: RealMessageRaw, accountId: EntityId): MessageItem {
  const id = typeof raw.messageId === 'string' ? raw.messageId : String(strictNumber(raw.messageId) ?? '')
  const domain = DOMAIN_BY_VALUE[strictNumber(raw.msgDomain) ?? 0]
  const sendStatus = strictNumber(raw.sendStatus)
  if (!id || !domain || ![1, 2, 3, 4].includes(sendStatus ?? 0) || typeof raw.msgTitle !== 'string') {
    throw new ContractError('MESSAGE_CONTRACT_MISMATCH', '消息数据格式异常')
  }
  const objectType = typeof raw.objectType === 'string' && OBJECT_TYPES.has(raw.objectType)
    ? raw.objectType as MessageItem['objectType']
    : undefined
  return {
    messageId: id,
    // 服务端按会话过滤即身份事实，不回传收件人；本字段仅为 mock 域过滤所需，real 取当前会话
    accountId,
    domain,
    title: raw.msgTitle,
    summary: typeof raw.summary === 'string' ? raw.summary : '',
    content: typeof raw.msgContent === 'string' ? raw.msgContent : '',
    channel: strictNumber(raw.msgChannel) === 2 ? 'wechat-subscribe' : 'in-app',
    sendStatus: sendStatus as MessageSendStatus,
    sendTime: typeof raw.sendTime === 'string' && raw.sendTime !== '' ? raw.sendTime as BusinessTime : undefined,
    unread: strictNumber(raw.readFlag) === 0,
    objectType,
    objectId: typeof raw.objectId === 'string' && raw.objectId !== '' ? raw.objectId : undefined,
    // real 详情不做能力降级伪装（口径10）：跳转拦截交给路由守卫；降级演示保留在 mock
    evidenceMode: 'real',
    objectAccess: 'allowed',
  }
}

/** 会话账号 ID（store 未就绪/未登录时空串兜底——页面不消费该字段，仅契约形态需要）。 */
function sessionAccountId(): EntityId {
  try {
    return useAccountStore().context?.accountId ?? ''
  }
  catch {
    return ''
  }
}

const realMessageApi: MessageApi = {
  async listMessages(domain) {
    // 契约面是数组不是分页：demo 消息量级单页足够，服务端上限 100 条/页
    const page = await post<{ list: RealMessageRaw[], total: unknown }>(messageEndpoints.list, {
      msgDomain: domain ? VALUE_BY_DOMAIN[domain] : undefined,
      current: 1,
      size: 100,
    })
    const accountId = sessionAccountId()
    return (page.list ?? []).map(raw => normalizeRealMessage(raw, accountId))
  },
  async getMessageDetail(messageId) {
    return normalizeRealMessage(
      await post<RealMessageRaw>(messageEndpoints.detail, { messageId }),
      sessionAccountId(),
    )
  },
  async markRead(messageId) {
    await post<boolean>(messageEndpoints.read, { messageId })
  },
}

export const messageApi = realMessageApi
