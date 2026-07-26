import { onSessionInvalid } from '@/api/request'
import { useAccountStore } from '@/store/account'
import { reLaunchTo } from '@/utils/navigation'

/**
 * 全局会话失效处理器（复审 P1-2）：任一业务请求返回会话失效码即一次性登出并回登录入口 C01。
 * 码值与后端 ErrorMsg.java 一致：1401 登录状态异常 / 1402 登录超时 / 1403 被顶下线 / 1404 被踢下线 / 1405 账户冻结。
 *
 * 处理内容全部经由 accountStore.handleUnauthorized 完成：清 Token、tokenName、AccountContext 与
 * 进行中的会话恢复缓存；绝不回退 Mock 账号或继续使用旧能力上下文。之后 reLaunch 到 C01 清空页面栈。
 */
let handling = false

export function registerSessionInvalidHandler(): void {
  onSessionInvalid(() => {
    // 合并同一时刻多个并发请求触发的重复失效，避免多次 reLaunch。
    if (handling) {
      return
    }
    handling = true
    try {
      useAccountStore().handleUnauthorized()
      reLaunchTo('C01')
    }
    finally {
      // 下一个微任务再解锁：同一同步批次的失效合并为一次登出，后续新会话失效仍可再次处理。
      Promise.resolve().then(() => {
        handling = false
      })
    }
  })
}
