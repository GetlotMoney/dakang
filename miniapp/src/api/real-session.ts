import { ContractError } from './common'
import { getToken } from './request'

/**
 * 在已经由正式登录流程建立 KH_USER 会话的前提下执行真实请求。
 *
 * 当前 Demo 未接微信 code2session，因此日常交付构建固定全域 Mock。真实适配器联调必须由外部正式
 * 鉴权流程先写入 token；本模块不会按用户 ID 换取测试会话，也不会在 401 后自动冒充账号重登。
 *
 * 会话失效（1401~1405）的裁决点唯一在 request.ts：post() 以请求发起时冻结的 token 作会话代际判定，
 * 仅当失败响应仍属当前会话才触发全局登出（清 Token/tokenName/AccountContext/会话缓存并 reLaunch 回 C01）。
 * 本模块对 UNAUTHORIZED 只向上抛、不清 token——在此叠加本地清理会绕过代际判定，
 * 让迟到的旧会话 1401 清掉新登录写入的 token（复审 P1-5）。
 */
export async function withRealSession<T>(run: () => Promise<T>): Promise<T> {
  if (!getToken()) {
    throw new ContractError('UNAUTHORIZED', '请先登录后再使用')
  }
  return run()
}
