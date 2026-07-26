import type { CapabilityCode } from './account'
import type { BusinessTime, EntityId } from './common'
import { hasCapability } from './capability'
import { cloneContractData, ContractError } from './common'
import { realAdapterPending, selectAdapter } from './runtime'
import { scenarioStore } from '@/scenario/store'

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
  evidenceMode: 'prototype' | 'external-snapshot'
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

function ownMessage(messageId: EntityId) {
  const accountId = scenarioStore.activeAccountId
  const message = scenarioStore.messages.find(
    item => item.messageId === messageId && item.accountId === accountId,
  )
  if (!message) {
    throw new ContractError('MESSAGE_NOT_FOUND', '消息不存在或无权访问')
  }
  return message
}

const mockMessageApi: MessageApi = {
  async listMessages(domain) {
    const list = scenarioStore.messages
      .filter(
        item => item.accountId === scenarioStore.activeAccountId && (!domain || item.domain === domain),
      )
      // 冻结排序口径：按发送时间倒序，无发送时间（待发送）排在最后。
      .sort((left, right) => (right.sendTime ?? '').localeCompare(left.sendTime ?? ''))
    return cloneContractData(list)
  },
  async getMessageDetail(messageId) {
    const message = cloneContractData(ownMessage(messageId))
    const context = scenarioStore.activeAccount()
    if (
      message.requiredCapability
      && !hasCapability(context, message.requiredCapability)
    ) {
      message.content = '相关业务能力已撤销，当前仅保留消息摘要。'
      message.objectAccess = 'capability-revoked'
      return message
    }
    message.objectAccess = 'allowed'
    return message
  },
  async markRead(messageId) {
    ownMessage(messageId).unread = false
  },
}

const realMessageApi: MessageApi = {
  async listMessages() {
    return realAdapterPending('查询统一消息', messageEndpoints.list)
  },
  async getMessageDetail() {
    return realAdapterPending('查询消息详情', messageEndpoints.detail)
  },
  async markRead() {
    return realAdapterPending('标记消息已读', messageEndpoints.read)
  },
}

export const messageApi = selectAdapter(mockMessageApi, realMessageApi)
