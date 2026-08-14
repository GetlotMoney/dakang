/**
 * 日志模块 API
 *
 * @module api/log
 */

import request from '@/utils/http'

/** 登录与操作日志共用的时间区间入参。 */
type LogTimeRangeParams = {
  logExecuteTimeBegin?: string | null
  logExecuteTimeEnd?: string | null
}

/**
 * 把日期选择器给的 `YYYY-MM-DD` 补齐成日志时间的定长格式。
 *
 * 日志时间落库是 14 位 `yyyyMMddHHmmss` 定长字符串，查询按字符串大小比较。
 * `'2026-08-13'` 第 5 位是 `'-'`（0x2D），排在 `'0'`（0x30）之前，于是
 * 「小于等于结束日期」会把当天乃至当年的记录整段排除——选任意含当年的区间都返回空表，
 * 且不报任何错。区间端点在这里补成当日 `000000` / `235959`，是全站唯一的归一化点：
 * 放在各页面里写第二份，只会让某一份先漂回去。
 */
function toLogTimeBoundary(value: string | null | undefined, edge: 'begin' | 'end') {
  if (!value) return value
  const digits = value.replace(/\D/g, '')
  if (digits.length >= 14) return digits.slice(0, 14)
  // 认不出的格式原样透传，交给服务端判定，不在前端猜
  if (digits.length !== 8) return value
  return edge === 'begin' ? `${digits}000000` : `${digits}235959`
}

function normalizeLogTimeRange<T extends LogTimeRangeParams>(data: T): T {
  return {
    ...data,
    logExecuteTimeBegin: toLogTimeBoundary(data.logExecuteTimeBegin, 'begin'),
    logExecuteTimeEnd: toLogTimeBoundary(data.logExecuteTimeEnd, 'end')
  } as T
}

/** 获取登录日志列表 */
export function fetchGetLogLoginList(data: Api.Log.LogLoginSearchParams) {
  return request.post<Api.Log.LogLoginList>({
    url: '/api/logLogin/pageData',
    data: normalizeLogTimeRange(data)
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
    data: normalizeLogTimeRange(data)
  })
}

/** 获取操作日志详情 */
export function fetchGetLogOperationDetail(id: string) {
  return request.post<Api.Log.LogOperationItem>({
    url: '/api/logOperation/getData',
    data: { id }
  })
}
