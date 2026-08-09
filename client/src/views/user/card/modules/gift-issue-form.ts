/**
 * 赠卡发放表单的纯函数层（收卡用户 ID string 直传）。
 *
 * 收卡用户 ID 是身份类 Long：任何 Number() 化在 >2^53 时会静默舍入到相邻可表示值，
 * 礼包会发给错误用户。ID 全链 string 逐字直传（后端 Spring 转 Long），归一判定与
 * S4 授权范围链同一受检入口 normalizeScopeId——规范正整数十进制且 ≤ Long.MAX_VALUE，
 * 其余（前导零/负数/小数/科学计数/超界）一律拒绝，绝不静默转换。
 */
import { normalizeScopeId } from './card-scope-form'

/** 收卡用户 ID 归一：文本输入先去首尾空白（输入框粘贴常带空格），其余逐字判定。 */
export function normalizeGiftUserId(raw: unknown): string | null {
  return normalizeScopeId(typeof raw === 'string' ? raw.trim() : raw)
}
