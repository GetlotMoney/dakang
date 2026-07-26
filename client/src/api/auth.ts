import request from '@/utils/http'

/**
 * 登录
 * @param params 登录参数
 * @returns 用户登录信息（包含 token + refreshToken + rbacMenuList）
 */
export function fetchLogin(data: Api.Auth.LoginParams) {
  return request.post<Api.Auth.ApiEmployeeLoginVo>({
    url: '/api/auth/loginEmployee',
    data
  })
}

/**
 * 获取用户信息
 * @returns 用户信息
 */
export function fetchGetUserInfo() {
  return request.get<Api.Auth.UserInfo>({
    url: '/api/user/info'
    // 自定义请求头
    // headers: {
    //   'X-Custom-Header': 'your-custom-value'
    // }
  })
}

/**
 * 退出登录
 */
export function fetchLoginOut() {
  return request.post({
    url: '/api/auth/loginOut'
  })
}

/**
 * 二级认证（高风险操作前置：锁机/解锁指令等）
 * @param loginPwd RSA 加密后的登录密码（公钥 VITE_ACCESS_LOGIN_KEY）
 */
export function fetchOpenSafe(loginPwd: string) {
  return request.post<boolean>({
    url: '/api/auth/openSafe',
    data: { loginPwd }
  })
}
