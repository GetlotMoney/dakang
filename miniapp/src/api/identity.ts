import type { BusinessTime, EntityId, MoneyFen } from './common'
import type { OwnerWallet } from './device'
import { post } from './request'

export type IdentityType = 1 | 2 | 3 | 4
export type IdentityStatus = 0 | 1 | 2 | 3 | 4

export interface IdentityRole {
  capabilityType: IdentityType
  title: string
  status: IdentityStatus
  statusText: string
  capabilityCode: string
  entryRouteId: string
  applicantName?: string
  regionName?: string
  submittedTime?: BusinessTime
  reviewRemark?: string
}

export interface IdentityOverview {
  demoMode: boolean
  roles: IdentityRole[]
}

export interface IdentityApplyInput {
  capabilityType: 2 | 3 | 4
  subjectType: 1 | 2
  applicantName: string
  regionName: string
  agentLevel?: 1 | 2 | 3
  inviteCode?: string
  stationName?: string
  stationAddress?: string
  requestId: string
  declarationAccepted: true
}

export interface IdentityInvite {
  code: string
  targetCapabilityType: 2 | 3 | 4
  targetAgentLevel?: 1 | 2 | 3
  regionName?: string
  usedCount: number
}

export interface IdentityOwner {
  userId: EntityId
  userName: string
  stationCount: number
  bindTime?: BusinessTime
}

export interface PublicLead {
  leadId: EntityId
  ownerName: string
  regionName: string
  status: 1 | 2 | 3 | 4
  responseDeadline?: BusinessTime
}

export interface LineageNode {
  profileId: EntityId
  subjectName: string
  level?: 1 | 2 | 3
  regionName?: string
  current: boolean
}

export interface IdentityDashboard {
  capabilityType: 3 | 4
  subjectName: string
  regionName: string
  agentLevel?: 1 | 2 | 3
  directOwnerCount: number
  stationCount: number
  orderCount: number
  wallet: OwnerWallet
  inviteCodes: IdentityInvite[]
  owners: IdentityOwner[]
  publicLeads: PublicLead[]
  lineage: LineageNode[]
}

export interface DemoControl {
  demoMode: boolean
  fixedQrContent: string
  nextPayResult: 'SUCCESS' | 'CANCEL' | 'INSUFFICIENT' | 'TIMEOUT' | 'DUPLICATE'
  nextDeviceResult: 'NORMAL' | 'SHORT' | 'REJECT' | 'TIMEOUT' | 'DUPLICATE'
  deliveryAuto: 1 | 2
  channelInviteCode: string
  regionProvinceInviteCode: string
  regionCityInviteCode: string
  regionCountyInviteCode: string
}

export const identityEndpoints = {
  overview: '/mini/identity/overview',
  apply: '/mini/identity/apply',
  channelOverview: '/mini/identity/channel/overview',
  regionOverview: '/mini/identity/region/overview',
  leadConfirm: '/mini/identity/region/lead/confirm',
  demoControl: '/mini/identity/demo/control',
  demoControlUpdate: '/mini/identity/demo/control/update',
  withdrawSim: '/mini/identity/wallet/withdraw-sim',
} as const

export const identityApi = {
  overview: () => post<IdentityOverview>(identityEndpoints.overview, {}),
  apply: (input: IdentityApplyInput) => post<IdentityOverview>(identityEndpoints.apply, { ...input }),
  channelOverview: () => post<IdentityDashboard>(identityEndpoints.channelOverview, {}),
  regionOverview: () => post<IdentityDashboard>(identityEndpoints.regionOverview, {}),
  confirmLead: (leadId: EntityId) => post<IdentityDashboard>(identityEndpoints.leadConfirm, { leadId }),
  demoControl: () => post<DemoControl>(identityEndpoints.demoControl, {}),
  updateDemoControl: (input: Pick<DemoControl, 'nextPayResult' | 'nextDeviceResult' | 'deliveryAuto'>) =>
    post<DemoControl>(identityEndpoints.demoControlUpdate, input),
  simulateWithdraw: (amountFen: MoneyFen, requestId: string) =>
    post<string>(identityEndpoints.withdrawSim, { amountFen, requestId }),
}

/** 小程序运行时无 crypto.randomUUID；保持规范 UUID 供服务端唯一键幂等。 */
export function createIdentityRequestId(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const value = Math.floor(Math.random() * 16)
    return (char === 'x' ? value : (value & 0x3) | 0x8).toString(16)
  })
}
