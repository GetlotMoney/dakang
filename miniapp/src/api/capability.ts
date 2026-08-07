import type { AccountContext, CapabilityCode } from './account'
import { ContractError } from './common'

export interface CapabilityDefinition {
  code: CapabilityCode
  group: 'water' | 'courier' | 'owner'
  title: string
  entryPath: string
}

export const capabilityRegistry: CapabilityDefinition[] = [
  {
    code: 'USER_BASE',
    group: 'water',
    title: '用水服务',
    entryPath: '/pages/user/home/index',
  },
  {
    code: 'COURIER_APPLY',
    group: 'courier',
    title: '配送员准入',
    entryPath: '/pages/courier/admission/index',
  },
  {
    code: 'COURIER_WORK',
    group: 'courier',
    title: '配送工作',
    entryPath: '/pages/courier/task/index',
  },
  {
    code: 'OWNER_VIEW',
    group: 'owner',
    title: '经营管理',
    entryPath: '/pages/owner/overview/index',
  },
  {
    code: 'OWNER_SERVICE',
    group: 'owner',
    title: '设备服务',
    entryPath: '/pages/owner/service/index',
  },
]

export function hasCapability(context: AccountContext, capability: CapabilityCode) {
  return context.capabilities.includes(capability)
}

export function requireCapability(
  context: AccountContext,
  capability: CapabilityCode,
): void {
  if (!hasCapability(context, capability)) {
    const definition = capabilityRegistry.find(item => item.code === capability)
    // 面向用户的拒绝文案使用中文能力名，不泄露内部契约代号（2026-07-18 审计整改）。
    throw new ContractError('CAPABILITY_DENIED', `当前账号未开通「${definition?.title ?? '对应'}」能力`)
  }
}
