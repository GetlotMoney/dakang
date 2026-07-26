import request from '@/utils/http'

// 获取部门树形列表
export function fetchGetDeptTree() {
  return request.post<Api.SystemManage.DeptListItem[]>({
    url: '/api/dept/treeData'
  })
}

// 保存部门（新增/编辑）
export function fetchSaveDept(data: Api.SystemManage.DeptFormData) {
  return request.post<boolean>({
    url: '/api/dept/saveData',
    data
  })
}

// 修改部门
export function fetchUpdateDept(data: Api.SystemManage.DeptFormData) {
  return request.post<boolean>({
    url: '/api/dept/updateData',
    data
  })
}

// 删除部门
export function fetchDeleteDept(id: String) {
  return request.post<boolean>({
    url: '/api/dept/deleteData',
    data: { id }
  })
}
