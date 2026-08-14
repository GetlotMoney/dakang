import type {
  UserAuditItem,
  UserFlowItem,
  UserOrderItem,
  UserRelation,
  UserRelationItem
} from './user'
// 数值形态判据复用既有实现，不在本文件另起一套：同一条"什么算合法整数"的规则
// 分成两份后，两份迟早会漂移，而漂移点正好落在金额上。
import { strictNonNegInt, strictSignedInt } from './mall-normalize'

/**
 * 用户档案响应运行时归一化。
 *
 * 服务端对 Long/long 全局 ToStringSerializer：分页 total、金额（分）、水量（毫升）、
 * 计数在真实响应里都是十进制字符串（"2400"），Integer 字段（状态、类型、来源端）才是 number。
 * TS 类型标注挡不住运行时形态，本模块统一收敛为 number，畸形值 fail-closed 抛错——
 * 资金视图上"看起来是 0"比"打不开"危险得多，绝不把 NaN 或错值交给页面。
 *
 * 身份类 Long ID（用户ID/卡ID/订单ID/记录ID）刻意**不参与**数值归一：它们逐字保持字符串，
 * 一旦转成 number，超过 2^53 的编号会静默落到相邻值上——看的是 A、请求带走的是 B。
 */

type RawRecord = Record<string, unknown>

export function userCount(raw: unknown, field: string): number {
  const n = strictNonNegInt(raw)
  if (n === undefined) {
    throw new Error(`用户档案数据异常：${field} 形态非法（${JSON.stringify(raw)}）`)
  }
  return n
}

export function userOptCount(raw: unknown, field: string): number | undefined {
  return raw === null || raw === undefined ? undefined : userCount(raw, field)
}

/**
 * 带符号整数。变动列天然可正可负；变动后快照按余额不变式应当非负，
 * 这里同样按带符号解析——真出现负值时要让它显示出来被人看见，
 * 而不是整页抛错把异常藏起来。
 */
export function userDelta(raw: unknown, field: string): number {
  const n = strictSignedInt(raw)
  if (n === undefined) {
    throw new Error(`用户档案数据异常：${field} 形态非法（${JSON.stringify(raw)}）`)
  }
  return n
}

/**
 * 分页壳归一：total 是 Long 字符串；行归一交给 mapRow，行内非法=整页失败。
 *
 * list 侧与 total 一样 fail-closed：非数组回落成 []、非对象行静默丢弃，都会得到
 * 「共 37 条」配一张空表或缺行的表，而它在界面上和「这个人没有流水」一模一样——
 * 资金视图上"看起来是 0"比"打不开"危险得多，宁可整页失败也不交出半页。
 * 服务端 PageDataVo 恒把 list 置为数组（构造器里 null 会被替换成空集合），
 * 所以「不是数组」只可能是响应结构本身出了问题，没有合法的静默回落场景。
 */
export function userPage<T>(
  raw: unknown,
  mapRow: (row: RawRecord) => T
): { total: number; list: T[] } {
  if (raw === null || raw === undefined || typeof raw !== 'object') {
    throw new Error('用户档案数据异常：分页响应缺失')
  }
  const page = raw as RawRecord
  if (!Array.isArray(page.list)) {
    throw new Error(`用户档案数据异常：list 形态非法（${JSON.stringify(page.list)}）`)
  }
  const total = userCount(page.total, 'total')
  const list = page.list.map((row, index) => {
    if (typeof row !== 'object' || row === null || Array.isArray(row)) {
      throw new Error(`用户档案数据异常：第 ${index + 1} 行形态非法（${JSON.stringify(row)}）`)
    }
    return mapRow(row as RawRecord)
  })
  return { total, list }
}

export function userOrderRowOf(row: RawRecord): UserOrderItem {
  return {
    ...(row as unknown as UserOrderItem),
    orderAmount: userOptCount(row.orderAmount, 'orderAmount'),
    planMl: userOptCount(row.planMl, 'planMl'),
    actualMl: userOptCount(row.actualMl, 'actualMl')
  }
}

export function userFlowRowOf(row: RawRecord): UserFlowItem {
  return {
    ...(row as unknown as UserFlowItem),
    amountChange: userDelta(row.amountChange, 'amountChange'),
    mlChange: userDelta(row.mlChange, 'mlChange'),
    amountAfter: userDelta(row.amountAfter, 'amountAfter'),
    mlAfter: userDelta(row.mlAfter, 'mlAfter')
  }
}

export function userRelationItemOf(row: RawRecord): UserRelationItem {
  return row as unknown as UserRelationItem
}

export function userAuditRowOf(row: RawRecord): UserAuditItem {
  return row as unknown as UserAuditItem
}

export function userRelationOf(raw: unknown): UserRelation {
  const row = (raw ?? {}) as RawRecord
  return {
    ...(row as unknown as UserRelation),
    directInviteeCount: userOptCount(row.directInviteeCount, 'directInviteeCount')
  }
}
