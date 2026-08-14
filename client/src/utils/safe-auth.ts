import JSEncrypt from 'jsencrypt'
import { ElMessageBox } from 'element-plus'
import { fetchOpenSafe } from '@/api/auth'
import { HttpError } from '@/utils/http/error'
import { ApiStatus } from '@/utils/http/status'

/**
 * 二级认证（openSafe）的唯一前端入口。
 *
 * 高风险设备指令在服务端由 Sa-Token 的二级认证守着；未通过时后端回 1440
 * （ErrorMsg.SAFE_FAIL）。这里把「撞 1440 → 弹口令 → 换取安全期 → 重放原请求」
 * 收成一处，而不是每个调用点各写一份：写第二份的代价是各处对「什么算认证失败」
 * 判得不一样，最松的那一处就成了绕过口。
 *
 * 口令只在本函数内存在于内存，加密后即交给服务端，不落 store、不落日志。
 * 安全期由服务端记时（60 秒），前端不缓存也不预测——预测会在临界点上
 * 让用户以为还在安全期内，实际请求已经被拒。
 */

/** 判定一个异常是不是「需要二级认证」。 */
export function isSafeAuthRequired(error: unknown): boolean {
  return error instanceof HttpError && error.code === ApiStatus.safeAuthRequired
}

/**
 * 执行一个可能需要二级认证的动作。
 *
 * @param action 原动作；被 1440 拒绝后会在认证成功时**原样重放一次**
 * @param title 口令框标题，说清正在授权的是哪一个动作
 */
export async function withSafeAuth<T>(action: () => Promise<T>, title: string): Promise<T> {
  try {
    return await action()
  } catch (error) {
    if (!isSafeAuthRequired(error)) {
      throw error
    }
    // 只重试一次：认证成功后仍被拒说明不是安全期问题，再弹一次只会让人以为是自己输错了
    const input = await ElMessageBox.prompt('请输入当前账号的登录密码以继续', title, {
      inputType: 'password',
      inputPlaceholder: '登录密码',
      confirmButtonText: '验证并继续',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value || '').length > 0 || '密码不能为空'
    })
    const encryptor = new JSEncrypt()
    encryptor.setPublicKey(import.meta.env.VITE_ACCESS_LOGIN_KEY)
    await fetchOpenSafe(encryptor.encrypt(input.value) as string)
    return await action()
  }
}
