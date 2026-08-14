/**
 * 功能点权限判定的统一入口。
 *
 * 判定本身只有一行，抽出来是因为内联副本会各自漂移：同一个判据在 30 个页面里
 * 重写 30 遍，只要有一份把全等写成 includes、或者少一道空串保护，最松的那一份
 * 就成了事实口径，而且拼错权限码不会被任何编译器或门禁发现。
 *
 * 判定只决定「摆不摆出这个入口」。真正的拦截在服务端（`@SaCheckPermission`），
 * 隐藏按钮不构成授权，也不能替代服务端校验；反过来，服务端拦得住不等于前端可以
 * 把点了必被拒的按钮摆出来——那会让人填完整张表单才知道自己没有权限。
 */
import { useUserStore } from '@/store/modules/user'

/**
 * 登录人是否持有该功能点权限。
 *
 * 必须在 pinia 就绪后调用（组件 setup、computed、render 或事件回调内），
 * 模块顶层求值时 store 尚未装配。
 *
 * @param perm 功能点权限码，与后端 `@SaCheckPermission` 一字不差，如 `mall:product:shelf`。
 *             空串恒为 false：菜单目录与页面行的权限字段本就为空，拿空串去匹配会把
 *             任意一条目录当成功能点命中，等于无声放行。
 */
export function hasPermission(perm: string): boolean {
  if (!perm) return false
  return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
}
