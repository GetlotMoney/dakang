import { ContractError } from './common'
import { getToken } from './request'

/**
 * 在正式登录建立 KH_USER 会话的前提下执行真实请求；不换取测试会话、不在 401 后自动重登。
 * 会话失效（1401~1405）的裁决点唯一在 request.ts 的代际判定；本模块对 UNAUTHORIZED 只向上抛、
 * 不清 token——叠加本地清理会绕过代际判定，让迟到的旧会话 1401 清掉新 token（复审 P1-5）。
 */
export async function withRealSession<T>(run: () => Promise<T>): Promise<T> {
  if (!getToken()) {
    throw new ContractError('UNAUTHORIZED', '请先登录后再使用')
  }
  return run()
}
