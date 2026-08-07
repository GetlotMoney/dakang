import request from '@/utils/http'

/**
 * 站内消息记录（E2E-07 包D / REQ-087「后台记录」）。
 * 只读查询——消息只由各业务链的同事务写入点产生，管理端不提供任何写入口。
 */
export function fetchMessageRecordPage(data: Api.Message.MessageRecordSearchParams) {
  return request.post<Api.Message.MessageRecordList>({
    url: '/message/page',
    data
  })
}
