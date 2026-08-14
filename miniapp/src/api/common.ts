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
 * 在 yyyyMMddHHmmss 业务时间上做确定性偏移。
 *
 * <p>唯一在用的地方是申诉截止时间（签收时间 + 24h）：它必须由服务端落定的签收时间派生，
 * 而不是拿设备时钟去加——设备时间可被用户随意改，那样算出来的截止时间是可以被伪造的。</p>
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

export function cloneContractData<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
