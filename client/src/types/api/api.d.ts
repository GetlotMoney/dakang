/**
 * API 接口类型定义模块
 *
 * 提供所有后端接口的类型定义
 *
 * ## 主要功能
 *
 * - 通用类型（分页参数、响应结构等）
 * - 认证类型（登录、用户信息等）
 * - 系统管理类型（用户、角色等）
 * - 全局命名空间声明
 *
 * ## 使用场景
 *
 * - API 请求参数类型约束
 * - API 响应数据类型定义
 * - 接口文档类型同步
 *
 * ## 注意事项
 *
 * - 在 .vue 文件使用需要在 eslint.config.mjs 中配置 globals: { Api: 'readonly' }
 * - 使用全局命名空间，无需导入即可使用
 *
 * ## 使用方式
 *
 * ```typescript
 * const params: Api.Auth.LoginParams = { userName: 'admin', password: '123456' }
 * const response: Api.Auth.UserInfo = await fetchUserInfo()
 * ```
 *
 * @module types/api/api
 * @author Art Design Pro Team
 */

declare namespace Api {
  /** 通用类型 */
  namespace Common {
    /** 分页参数 */
    interface PaginationParams {
      /** 当前页码 */
      current: number
      /** 每页条数 */
      size: number
      /** 总条数 */
      total: number
    }

    /** 通用搜索参数 */
    type CommonSearchParams = Pick<PaginationParams, 'current' | 'size'>

    /** 分页响应基础结构 */
    interface PaginatedResponse<T = any> {
      list: T[]
      total: string
      current: string
      size: string
    }

    /** 启用状态 */
    type EnableStatus = '1' | '2'
  }

  /** 认证类型 */
  namespace Auth {
    /** 登录参数 */
    interface LoginParams {
      loginName: string
      loginPwd: string
    }

    /** 路由菜单项 */
    interface RbacMenuItem {
      id?: number | string
      /** 前端路由镜像字段（后端动态菜单响应通常使用 menuPath） */
      path?: string
      name?: string
      component?: string
      redirect?: string
      meta?: {
        title: string
        icon?: string
        isHide?: boolean
        isHideTab?: boolean
        isIframe?: boolean
        link?: string
        keepAlive?: boolean
        fixedTab?: boolean
        activePath?: string
        isFullPage?: boolean
        roles?: string[]
        authList?: Array<{ title: string; authMark: string }>
        authMark?: string
        parentPath?: string
        [key: string]: unknown
      }
      children?: RbacMenuItem[]
      /** 后端返回的菜单权限字段 */
      menuPath?: string
      menuComponent?: string
      menuWebPerms?: string
      menuApiPerms?: string
      menuName?: string
      menuType?: number
      menuDisabledFlag?: number
      menuVisibleFlag?: number
      menuFrameFlag?: number
      menuFrameUrl?: string
      menuParentId?: string
      menuSort?: number
      createTime?: string
      updateTime?: string
    }

    /** 用户登录返回的用户信息 */
    interface ApiEmployeeLoginVo {
      id: string
      loginName: string
      employeeName: string
      employeeGender: number
      employeePhone: string
      positionId: string
      deptId: string
      rbacMenuList: RbacMenuItem[]
      createTime: string
      updateTime: string
      /** 会话 token（后端 loginEmployee 响应体下发，经 dakang-token 请求头回传） */
      tokenValue?: string
      token: string
      refreshToken: string
      /** 是否需先修改初始密码：true 时服务端拒绝除改密/退出外的接口，前端直接进入改密页 */
      pwdChangeRequired?: boolean
    }

    /** 用户信息 */
    interface UserInfo {
      id: string
      loginName: string
      employeeName: string
      employeeGender: number
      employeePhone: string
      positionId: string
      deptId: string
      rbacMenuList: RbacMenuItem[]
      createTime: string
      updateTime: string
      buttons?: string[]
      roles?: string[]
      avatar?: string
      userName?: string
      userId?: string
      email?: string
      /** 是否需先修改初始密码：true 时服务端拒绝除改密/退出外的接口，前端守卫钉在改密页 */
      pwdChangeRequired?: boolean
    }
  }

  /** 系统管理类型 */
  namespace SystemManage {
    /** 用户列表 */
    type UserList = Api.Common.PaginatedResponse<UserListItem>

    /** 用户列表项 */
    interface UserListItem {
      id: string
      loginName: string
      employeeName: string
      employeeGender: number
      employeePhone: string
      deptId: number
      positionId: number
      disabledFlag: number
      createTime: string
      updateTime: string
      tagIdList: { id: string; tagName: string }[]
      tagList: { id: string; tagName: string }[]
    }

    /** 新增员工/重置密码的一次性初始密码回执：明文仅本次响应可见，库内只存哈希 */
    interface EmployeeInitPwd {
      id: string
      initialPwd: string
    }

    /** 用户表单数据 */
    interface UserFormData {
      id?: string
      loginName: string
      loginPwd?: string
      newLoginPwd?: string
      employeeName: string
      employeeGender: number
      employeePhone: string
      deptId: string
      positionId: string
      disabledFlag: number
      tagIdList: string[]
    }

    /** 用户搜索参数 */
    type UserSearchParams = Partial<
      Pick<
        UserListItem,
        'id' | 'loginName' | 'employeeName' | 'employeeGender' | 'employeePhone' | 'disabledFlag'
      > &
        Api.Common.CommonSearchParams
    >

    /** 角色列表 */
    type RoleList = Api.Common.PaginatedResponse<RoleListItem>

    /** 角色列表（不分页） */
    type RoleListNoPage = RoleListItem[]

    /** 角色列表项 */
    interface RoleListItem {
      id: string
      roleName: string
      roleCode: string
      roleRemark: string
      roleSort: number
      createTime?: string
      updateTime?: string
    }

    /** 角色搜索参数 */
    type RoleSearchParams = Partial<
      Pick<RoleListItem, 'id' | 'roleName' | 'roleCode'> &
        Api.Common.CommonSearchParams & {
          startTime: string | null
          endTime: string | null
        }
    >

    /** 部门列表项 */
    interface DeptListItem {
      id: string
      deptName: string
      deptManagerId: string
      deptManagerIdToEmployee: {
        id: string
        loginName: string
        employeeName: string
        employeeGender: number
        employeePhone: string
        deptId: string
        positionId: string
        disabledFlag: number
      } | null
      deptParentId: string
      deptSort: number
      deptDesc: string
      children: DeptListItem[]
    }

    /** 部门表单数据 */
    interface DeptFormData {
      id?: string
      deptName: string
      deptManagerId: string
      deptParentId: string
      deptSort: number
      deptDesc: string
    }

    /** 部门搜索参数 */
    type DeptSearchParams = Partial<Pick<DeptListItem, 'deptName'> & Api.Common.CommonSearchParams>

    /** 职务列表 */
    type PositionList = Api.Common.PaginatedResponse<PositionListItem>

    /** 职务列表项 */
    interface PositionListItem {
      id: string
      positionName: string
      positionLevel: string
      positionSort: number
      positionRemark: string
      createTime: string
      updateTime: string
    }

    /** 职务表单数据 */
    interface PositionFormData {
      id?: string
      positionName: string
      positionLevel: string
      positionSort: number
      positionRemark: string
    }

    /** 职务搜索参数 */
    type PositionSearchParams = Partial<
      Pick<PositionListItem, 'positionName' | 'positionLevel'> & Api.Common.CommonSearchParams
    >

    /** 标签列表项 */
    interface TagListItem {
      id: string
      tagName: string
      createTime?: string
      updateTime?: string
    }

    /** 标签表单数据 */
    interface TagFormData {
      id?: string
      tagName: string
    }

    /** 标签下用户列表 */
    type TagEmployeeList = Api.Common.PaginatedResponse<TagEmployeeItem>

    /** 标签下用户项 */
    interface TagEmployeeItem {
      id: string
      employeeId: string
      employeeName: string
      employeePhone: string
      employeeGender: number
    }

    /** 标签下用户搜索参数 */
    type TagEmployeeSearchParams = {
      tagId: string
    } & Partial<Pick<TagEmployeeItem, 'employeeName'>> &
      Api.Common.CommonSearchParams

    /** 标签用户关联表单数据 */
    interface TagEmployeeFormData {
      tagId: string
      employeeIds: string[]
    }
  }

  /** 日志模块类型 */
  namespace Log {
    /** 登录日志列表 */
    type LogLoginList = Api.Common.PaginatedResponse<LogLoginItem>

    /** 登录日志列表项 */
    interface LogLoginItem {
      id: string
      logUserName: string
      logUserType: number
      logExecuteTime: string
      logType: number
      logIp: string
      logUserAgent: string
      uaBrowser?: string
      uaBrowserVersion?: string
      uaEngine?: string
      uaEngineVersion?: string
      uaIsMobile?: number
      uaOs?: string
      uaOsVersion?: string
      uaPlatform?: string
    }

    /** 登录日志搜索参数 */
    type LogLoginSearchParams = Partial<
      Pick<LogLoginItem, 'id' | 'logUserName' | 'logType' | 'logIp'> &
        Api.Common.CommonSearchParams & {
          logExecuteTimeBegin: string | null
          logExecuteTimeEnd: string | null
        }
    >

    /** 操作日志列表 */
    type LogOperationList = Api.Common.PaginatedResponse<LogOperationItem>

    /** 操作日志列表项 */
    interface LogOperationItem {
      id: string
      logUserName: string
      logUserType: number
      logModule: string
      logContent: string
      logUrl: string
      logMethod: string
      logRequestParam: string
      logResponseParam: string
      logIp: string
      logUserAgent: string
      logExecuteTime: string
      logConsumerTime: number
      logMonitorInfo: string
      logSuccessFlag: number
    }

    /** 操作日志搜索参数 */
    type LogOperationSearchParams = Partial<
      Pick<
        LogOperationItem,
        | 'id'
        | 'logUserName'
        | 'logUserType'
        | 'logModule'
        | 'logContent'
        | 'logUrl'
        | 'logSuccessFlag'
        | 'logIp'
      > &
        Api.Common.CommonSearchParams & {
          logExecuteTimeBegin: string | null
          logExecuteTimeEnd: string | null
        }
    >
  }

  /** 站内消息记录（E2E-07 REQ-087「后台记录」，只读） */
  namespace Message {
    /** 消息记录列表 */
    type MessageRecordList = Api.Common.PaginatedResponse<MessageRecordItem>

    /** 消息记录列表项（对齐服务端 WsMessage Po；金额类字段无，时间为 yyyyMMddHHmmss） */
    interface MessageRecordItem {
      id: string
      userId: string
      msgDomain: number
      msgTitle: string
      msgContent: string
      msgChannel: number
      sendStatus: number
      sendTime?: string
      readFlag: number
      objectType?: string
      objectId?: string
      createTime: string
    }

    /** 消息记录搜索参数 */
    type MessageRecordSearchParams = Partial<
      Pick<MessageRecordItem, 'userId' | 'msgDomain' | 'sendStatus'> & Api.Common.CommonSearchParams
    >
  }
}
