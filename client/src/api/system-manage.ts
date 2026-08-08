import request from '@/utils/http'
import { AppRouteRecord } from '@/types/router'

// 获取用户列表
export function fetchGetUserList(data: Api.SystemManage.UserSearchParams) {
  return request.post<Api.SystemManage.UserList>({
    url: '/api/employee/getPage',
    data
  })
}

// 保存用户（新增）：响应携带一次性初始密码，页面必须立即展示并提示转交员工
export function fetchSaveUser(data: Api.SystemManage.UserFormData) {
  return request.post<Api.SystemManage.EmployeeInitPwd>({
    url: '/api/employee/saveData',
    data
  })
}

/**
 * 修改当前登录员工本人的密码（服务端锚定会话身份，不接受指定他人）
 * @param data loginPwd/newLoginPwd 均为 RSA 加密后的密文（公钥 VITE_ACCESS_LOGIN_KEY）
 */
export function fetchUpdateEmployeePassword(data: { loginPwd: string; newLoginPwd: string }) {
  return request.post<boolean>({
    url: '/api/employee/updatePassword',
    data
  })
}

// 修改用户
export function fetchUpdateUser(data: Api.SystemManage.UserFormData) {
  return request.post<boolean>({
    url: '/api/employee/updateData',
    data
  })
}

// 删除用户
export function fetchDeleteUser(id: string) {
  return request.post<boolean>({
    url: '/api/employee/deleteData',
    data: { id }
  })
}

// 获取角色列表
export function fetchGetRoleList(data: Api.SystemManage.RoleSearchParams) {
  return request.post<Api.SystemManage.RoleListNoPage>({
    url: '/api/rbacRole/listData',
    data
  })
}

// 保存角色（新增）
export function fetchSaveRole(data: Api.SystemManage.RoleListItem) {
  return request.post<boolean>({
    url: '/api/rbacRole/saveData',
    data
  })
}

// 编辑角色
export function fetchUpdateRole(data: Api.SystemManage.RoleListItem) {
  return request.post<boolean>({
    url: '/api/rbacRole/updateData',
    data
  })
}

// 删除角色
export function fetchDeleteRole(id: string) {
  return request.post<boolean>({
    url: '/api/rbacRole/delData',
    data: { id }
  })
}

// 获取菜单列表（直接返回后端字段，前端不再做映射）
export function fetchGetMenuList() {
  return request.post<any[]>({
    url: '/api/rbacMenu/treeData',
    params: { pointsFlag: 2 },
    skipParamsMigration: true
  })
}

// 保存菜单（新增）
export function fetchSaveMenu(data: any) {
  return request.post<boolean>({
    url: '/api/rbacMenu/saveData',
    data
  })
}

// 编辑菜单
export function fetchUpdateMenu(data: any) {
  return request.post<boolean>({
    url: '/api/rbacMenu/updateData',
    data
  })
}

// 删除菜单
export function fetchDeleteMenu(id: number) {
  return request.post<boolean>({
    url: '/api/rbacMenu/deleteData',
    data: { id }
  })
}

// 获取角色拥有的菜单ID列表
export function fetchGetRoleMenuIds(roleId: string) {
  return request.post<string[]>({
    url: '/api/rbacRole/listMenuId',
    params: { roleId },
    skipParamsMigration: true
  })
}

// 保存角色菜单权限
export function fetchUpdateRoleMenu(data: { roleId: string; menuIdList: string[] }) {
  return request.post<boolean>({
    url: '/api/rbacRole/updateMenu',
    data
  })
}

// 获取职务列表
export function fetchGetPositionList(data: Api.SystemManage.PositionSearchParams) {
  return request.post<Api.SystemManage.PositionList>({
    url: '/api/position/pageData',
    data
  })
}

// 保存职务（新增/编辑，传入 id 则为编辑）
export function fetchSavePosition(data: Api.SystemManage.PositionFormData) {
  return request.post<boolean>({
    url: '/api/position/saveData',
    data
  })
}

// 修改职务
export function fetchUpdatePosition(data: Api.SystemManage.PositionFormData) {
  return request.post<boolean>({
    url: '/api/position/updateData',
    data
  })
}

// 删除职务
export function fetchDeletePosition(id: string) {
  return request.post<boolean>({
    url: '/api/position/deleteData',
    data: { id }
  })
}

// 分配角色
export function fetchAssignRole(data: { employeeId: string; roleIdList: string[] }) {
  return request.post<boolean>({
    url: '/api/rbacRole/addEmployeeToRoleList',
    data
  })
}

// 获取用户已有的角色列表
export function fetchGetEmployeeRoleList(employeeId: string) {
  return request.post<Api.SystemManage.RoleListItem[]>({
    url: '/api/rbacRole/listRoleByEmployee',
    data: { employeeId }
  })
}

/** 小程序入口配置（S6）：草稿/发布/撤回，小程序只读已发布 */
export interface MiniEntryItem {
  id: string
  entryKey: string
  entryType: number
  entryName: string
  sortNo: number
  enabledFlag: number
  jumpType?: number
  routeId?: string
  externalUrl?: string
  contentText?: string
  configStatus: number
  publishTime?: string
  version: number
}

export function fetchMiniEntryList() {
  return request.post<MiniEntryItem[]>({ url: '/api/miniEntry/list', data: {} })
}

export function fetchMiniEntrySave(data: Partial<MiniEntryItem>) {
  return request.post<string>({ url: '/api/miniEntry/save', data })
}

export function fetchMiniEntryPublish(id: string, version: number) {
  return request.post<boolean>({ url: '/api/miniEntry/publish', data: { id, version } })
}

export function fetchMiniEntryRetract(id: string, version: number) {
  return request.post<boolean>({ url: '/api/miniEntry/retract', data: { id, version } })
}
