import type { EntityId } from './common'
import { ContractError } from './common'
import { strictEntityId, strictNonNegativeInt } from './delivery-normalize'
import { withRealSession } from './real-session'
import { post } from './request'

export interface WaterType {
  id: EntityId
  name: string
  enabled: boolean
  placeholder: boolean
}

export interface StationSummary {
  id: EntityId
  stationName: string
  address: string
  distanceMeters?: number
  availableOutletCount: number
  onlineDeviceCount: number
  status: 'OPEN' | 'MAINTENANCE' | 'CLOSED'
}

export interface CatalogApi {
  listWaterTypes: () => Promise<WaterType[]>
  listStations: () => Promise<StationSummary[]>
}

export const catalogEndpoints = {
  waterTypes: '/mini/catalog/water-type/list',
  stations: '/mini/catalog/station/list',
} as const

/** 后端 MiniWaterTypeVo 原样结构（Long ID 已按字符串下发）。 */
interface WaterTypeRaw {
  id?: string | number | null
  name?: string | null
  enabled?: boolean | null
  placeholder?: boolean | null
}

/** 后端 MiniStationVo 原样结构。 */
interface StationRaw {
  id?: string | number | null
  stationName?: string | null
  address?: string | null
  distanceMeters?: string | number | null
  availableOutletCount?: string | number | null
  onlineDeviceCount?: string | number | null
  status?: string | null
}

function normalizeWaterType(raw: WaterTypeRaw): WaterType {
  const id = strictEntityId(raw.id)
  if (!id || typeof raw.name !== 'string' || !raw.name) {
    throw new ContractError('CATALOG_CONTRACT_BROKEN', '水种数据异常，请稍后重试')
  }
  return {
    id,
    name: raw.name,
    enabled: raw.enabled === true,
    // 8 种水最终定义待甲方确认：后端未显式声明非占位前一律按占位呈现
    placeholder: raw.placeholder !== false,
  }
}

function normalizeStation(raw: StationRaw): StationSummary {
  const id = strictEntityId(raw.id)
  if (!id || typeof raw.stationName !== 'string' || !raw.stationName) {
    throw new ContractError('CATALOG_CONTRACT_BROKEN', '水站数据异常，请稍后重试')
  }
  return {
    id,
    stationName: raw.stationName,
    address: typeof raw.address === 'string' ? raw.address : '',
    distanceMeters: strictNonNegativeInt(raw.distanceMeters),
    availableOutletCount: strictNonNegativeInt(raw.availableOutletCount) ?? 0,
    onlineDeviceCount: strictNonNegativeInt(raw.onlineDeviceCount) ?? 0,
    // 未知状态一律按 CLOSED 呈现（fail-closed：不把停用站当营业站供下单）
    status: raw.status === 'OPEN' || raw.status === 'MAINTENANCE' ? raw.status : 'CLOSED',
  }
}

/** catalog 真实适配器（E2E-03 包B）：水种/水站接真；套餐归 recharge 域，不在此重复来源。 */
const realCatalogApi: CatalogApi = {
  async listWaterTypes() {
    return withRealSession(async () => {
      const raw = await post<WaterTypeRaw[] | null>(catalogEndpoints.waterTypes, {})
      return (raw ?? []).map(normalizeWaterType)
    })
  },
  async listStations() {
    return withRealSession(async () => {
      const raw = await post<StationRaw[] | null>(catalogEndpoints.stations, {})
      return (raw ?? []).map(normalizeStation)
    })
  },
}

// catalog 的消费方全部在配送链，跟随 delivery 域接真，不单设 catalog 域
export const catalogApi = realCatalogApi
