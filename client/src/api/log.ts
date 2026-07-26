/**
 * 日志模块 API
 *
 * @module api/log
 */

import request from '@/utils/http'

/** 获取登录日志列表 */
export function fetchGetLogLoginList(data: Api.Log.LogLoginSearchParams) {
  return request.post<Api.Log.LogLoginList>({
    url: '/api/logLogin/pageData',
    data
  })
}

/** 获取登录日志详情 */
export function fetchGetLogLoginDetail(id: string) {
  return request.post<Api.Log.LogLoginItem>({
    url: '/api/logLogin/getData',
    data: { id }
  })
}

/** 获取操作日志列表 */
export function fetchGetLogOperationList(data: Api.Log.LogOperationSearchParams) {
  return request.post<Api.Log.LogOperationList>({
    url: '/api/logOperation/pageData',
    data
  })
}

/** 获取操作日志详情 */
export function fetchGetLogOperationDetail(id: string) {
  return request.post<Api.Log.LogOperationItem>({
    url: '/api/logOperation/getData',
    data: { id }
  })
}
