export type EntityId = string
export type BusinessTime = string
export type MoneyFen = number
export type VolumeMl = number

export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
}

export interface BackendPageData<T> {
  list: T[]
  total: string
}

export interface PageQuery {
  current: number
  size: number
}

export interface PageResult<T> {
  list: T[]
  total: number
}

export type EvidenceMode = 'prototype' | 'external-snapshot' | 'real'

export interface MockMeta {
  evidenceMode: Exclude<EvidenceMode, 'real'>
  settlementEffect: 'none'
  deviceEffect: 'none'
  syncedToPc: false
}

export const prototypeMeta: MockMeta = {
  evidenceMode: 'prototype',
  settlementEffect: 'none',
  deviceEffect: 'none',
  syncedToPc: false,
}

export const externalSnapshotMeta: MockMeta = {
  evidenceMode: 'external-snapshot',
  settlementEffect: 'none',
  deviceEffect: 'none',
  syncedToPc: false,
}

export class ContractError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ContractError'
  }
}

/**
 * 在 yyyyMMddHHmmss 业务时间上做确定性偏移，用于原型剧本的固定时间轴；
 * 不读取真实时钟，保证演示可复现。
 */
export function addSecondsToBusinessTime(time: BusinessTime, seconds: number): BusinessTime {
  const match = time.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/)
  if (!match) {
    throw new ContractError('BUSINESS_TIME_INVALID', '业务时间格式不合法')
  }
  const timestamp = Date.UTC(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    Number(match[6]),
  ) + seconds * 1000
  const value = new Date(timestamp)
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${value.getUTCFullYear()}${pad(value.getUTCMonth() + 1)}${pad(value.getUTCDate())}`
    + `${pad(value.getUTCHours())}${pad(value.getUTCMinutes())}${pad(value.getUTCSeconds())}`
  )
}

/**
 * 统一确定性业务逻辑时钟（2026-07-18 第四轮审计整改）：
 * 取全部下限时间的最大值并前进 offsetSeconds，产出下一动作时间。
 * 业务动作应把场景当前时间与各自的前置节点一并作为下限，保证后发生的动作不会拿到更早的时间；
 * 全程无真实时钟（禁止 Date.now），Mock 结果固定可复现。
 */
export function nextBusinessTime(options: {
  floorTimes: Array<BusinessTime | undefined>
  offsetSeconds?: number
}): BusinessTime {
  const floors = options.floorTimes.filter((item): item is BusinessTime => Boolean(item))
  if (floors.length === 0) {
    throw new ContractError('BUSINESS_TIME_INVALID', '逻辑时钟缺少下限时间')
  }
  const base = floors.reduce((left, right) => (left >= right ? left : right))
  return addSecondsToBusinessTime(base, options.offsetSeconds ?? 1)
}

export function cloneContractData<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
