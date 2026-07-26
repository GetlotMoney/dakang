import request from '@/utils/http'
import { HttpError } from '@/utils/http/error'

// 标签列表
export function fetchTagList(params: { tagName?: string } = {}) {
  return request.post<Api.SystemManage.TagListItem[]>({
    url: '/api/tag/listData',
    data: params
  })
}

// 保存标签（新增）
export function fetchSaveTag(data: Api.SystemManage.TagFormData) {
  return request.post<boolean>({
    url: '/api/tag/saveData',
    data
  })
}

// 修改标签
export function fetchUpdateTag(data: Api.SystemManage.TagFormData) {
  return request.post<boolean>({
    url: '/api/tag/updateData',
    data
  })
}

// 删除标签
export function fetchDeleteTag(id: string) {
  return request.post<boolean>({
    url: '/api/tag/deleteData',
    data: { id }
  })
}

// 查看标签下所有用户信息（分页）
export function fetchTagEmployeePage(params: Api.SystemManage.TagEmployeeSearchParams) {
  return request.post<Api.SystemManage.TagEmployeeList>({
    url: '/api/tag/employeeTag/pageEmployee',
    data: params
  })
}

// 保存单个用户-标签关联（后端 employeeTag/saveData 每次仅接收一个 employeeId）
export function fetchSaveTagEmployee(data: { tagId: string; employeeId: string }) {
  return request.post<boolean>({
    url: '/api/tag/employeeTag/saveData',
    data,
    // 批量场景由 fetchAddEmployeesToTag 统一聚合并提示，这里关闭单条自动错误弹窗
    showErrorMessage: false
  })
}

/** 批量添加结果：区分新增成功、已关联跳过、真实失败三类。 */
export interface AddEmployeesToTagResult {
  success: string[]
  skipped: string[]
  failed: { employeeId: string; reason: string }[]
}

/**
 * 批量把多个用户加入同一标签。
 *
 * 后端只提供单条关联接口（ApiEmployeeTagBo 仅收单个 employeeId），这里逐个 await 调用并聚合：
 * - 成功计入 success；
 * - 后端“已经关联”幂等冲突视为良性跳过计入 skipped，不计失败；
 * - 其余错误收集到 failed（含原因），单个失败不中断整批。
 */
export async function fetchAddEmployeesToTag(
  tagId: string,
  employeeIds: string[]
): Promise<AddEmployeesToTagResult> {
  const result: AddEmployeesToTagResult = { success: [], skipped: [], failed: [] }
  for (const employeeId of employeeIds) {
    try {
      await fetchSaveTagEmployee({ tagId, employeeId })
      result.success.push(employeeId)
    } catch (error) {
      const reason = error instanceof HttpError ? error.message : '添加失败'
      if (reason.includes('已经关联') || reason.includes('已关联')) {
        result.skipped.push(employeeId)
      } else {
        result.failed.push({ employeeId, reason })
      }
    }
  }
  return result
}

// 删除用户和标签关联（根据标签id和用户id）
export function fetchDeleteTagEmployee(data: { tagId: string; employeeId: string }) {
  return request.post<boolean>({
    url: '/api/tag/employeeTag/deleteData',
    data
  })
}
