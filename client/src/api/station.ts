/**
 * 水站管理 API（对接 /station/station/*，统一 POST + { code:0, msg, data }）
 */
import request from '@/utils/http'

/** 水站列表项 / 详情 */
export interface StationItem {
  id: number
  stationName: string
  stationCode: string
  stationRegion: string
  stationAddress: string
  stationLng?: string
  stationLat?: string
  /** 状态(10)：1正常 2禁用 */
  stationStatus: number
  ownerUserId?: number
  ownerUserName?: string
  ownerUserPhone?: string
  deviceCount?: number
  stationRemark?: string
  createTime?: string
  updateTime?: string
}

/** 分页查询参数 */
export interface StationSearchParams {
  current: number
  size: number
  stationName?: string
  stationCode?: string
  stationRegion?: string
  stationStatus?: number
}

/** 新增/编辑表单 */
export interface StationFormData {
  id?: number
  stationName: string
  stationCode: string
  stationRegion: string
  stationAddress: string
  stationLng?: string
  stationLat?: string
  stationStatus: number
  ownerUserId?: number
  stationRemark?: string
}

/** 分页 */
export function fetchStationPage(params: StationSearchParams) {
  return request.post<{ total: number; list: StationItem[] }>({
    url: '/station/station/page',
    data: params
  })
}

/** 详情 */
export function fetchStationDetail(id: number) {
  return request.post<StationItem>({
    url: '/station/station/detail',
    data: { id }
  })
}

/** 全部正常水站（下拉） */
export function fetchStationList() {
  return request.post<StationItem[]>({
    url: '/station/station/list',
    data: {}
  })
}

/** 新增 */
export function fetchAddStation(data: StationFormData) {
  return request.post<number>({
    url: '/station/station/add',
    data
  })
}

/** 修改 */
export function fetchUpdateStation(data: StationFormData) {
  return request.post<boolean>({
    url: '/station/station/update',
    data
  })
}

/** 删除 */
export function fetchDeleteStation(id: number) {
  return request.post<boolean>({
    url: '/station/station/delete',
    data: { id }
  })
}
