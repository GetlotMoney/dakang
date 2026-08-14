import request from '@/utils/http'

/** 登录：返回 token + refreshToken + rbacMenuList */
export function fetchLogin(data: Api.Auth.LoginParams) {
  return request.post<Api.Auth.ApiEmployeeLoginVo>({
    url: '/api/auth/loginEmployee',
    data
  })
}

export function fetchLoginOut() {
  return request.post({
    url: '/api/auth/loginOut'
  })
}

/** 二级认证（高风险操作前置）；loginPwd 为 RSA 加密密文（公钥 VITE_ACCESS_LOGIN_KEY） */
export function fetchOpenSafe(loginPwd: string) {
  return request.post<boolean>({
    url: '/api/auth/openSafe',
    data: { loginPwd }
  })
}
