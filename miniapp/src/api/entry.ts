import type { RouteId } from '@/router/routes'
import { withRealSession } from './real-session'
import { post } from './request'

/**
 * 小程序入口与运营配置（S6，只读已发布）：配置只影响入口显隐/排序/名称与公告文案，不构成授权；
 * 读取失败回放最后一次成功配置，无内置默认入口复活路径（核心页由固定 Tabbar 保证可用）；
 * 并发请求按代际取新（R2-P1-4），过期响应不写缓存也不上屏。
 */
export interface PublishedEntry {
  entryKey: string
  /** 1功能入口 2内容链接 3维护公告 */
  entryType: 1 | 2 | 3
  entryName: string
  sortNo: number
  routeId?: RouteId
  externalUrl?: string
  contentText?: string
}

export const entryEndpoints = {
  list: '/mini/entry/list',
} as const

let lastGoodEntries: PublishedEntry[] | null = null
/** 请求代际（R2-P1-4）：每次发起自增，只有最新一代允许写缓存/返回——旧响应可能带着撤回前的列表晚到。 */
let requestSeq = 0

/** 仅供测试复位缓存与请求代际（生产不调用）。 */
export function resetEntryCacheForTest() {
  lastGoodEntries = null
  requestSeq = 0
}

export async function listPublishedEntries(): Promise<PublishedEntry[]> {
  const generation = ++requestSeq
  try {
    const fetched = await withRealSession(async () => {
      const raw = await post<Array<Record<string, unknown>>>(entryEndpoints.list, {})
      const toInt = (v: unknown): number => typeof v === 'number' ? v : Number(v ?? 0)
      return (raw ?? []).flatMap((row) => {
        const type = toInt(row.entryType)
        if (type !== 1 && type !== 2 && type !== 3) {
          return []
        }
        const routeId = typeof row.routeId === 'string' && row.routeId ? row.routeId : undefined
        return [{
          entryKey: String(row.entryKey ?? ''),
          entryType: type as 1 | 2 | 3,
          entryName: typeof row.entryName === 'string' ? row.entryName : '',
          sortNo: toInt(row.sortNo),
          routeId: routeId as RouteId | undefined,
          externalUrl: typeof row.externalUrl === 'string' && row.externalUrl ? row.externalUrl : undefined,
          contentText: typeof row.contentText === 'string' && row.contentText ? row.contentText : undefined,
        }]
      })
    })
    if (generation !== requestSeq) {
      // 过期成功响应（R2-P1-4）：旧列表既不写缓存也不返回，一律回放当前最后一次成功值
      return lastGoodEntries ?? []
    }
    // 空数组是合法缓存值：「成功读取为空」=当前无已发布入口，与「从未成功」严格区分
    lastGoodEntries = fetched
    return fetched
  }
  catch {
    // fail-closed（R1-7）：异常只回放最后一次成功配置，绝不复活已撤回入口；从未成功过返回空
    return lastGoodEntries ?? []
  }
}

/**
 * 宫格图标按入口键固定映射；配置只管显隐/排序/名称，不注入任意图标。
 * 名称必须取自 wot-design-uni 内置图标字体（wd-icon-* 类名全集）：字体里没有的名字不报错，只渲染成空白。
 */
export const ENTRY_ICONS: Record<string, string> = {
  U07: 'location',
  U08: 'goods',
  U10: 'wallet',
  U13: 'usergroup',
  U14: 'location',
  U16: 'refresh',
  O01: 'chart-bar',
  O06: 'money-circle',
  D01: 'bags',
  D02: 'user-circle',
  C02: 'notification',
}

/**
 * 首页功能宫格的最终投影（R1-7 fail-closed）：只按传入的已发布配置渲染——
 * 未发布/已撤回/停用即隐藏，绝无硬编码默认入口复活路径。
 * routeId 缺失或图标未登记的行整条丢弃。
 */
export function projectFeatureEntries(published: PublishedEntry[]): PublishedEntry[] {
  return published
    .filter(e => e.entryType === 1)
    .filter(e => e.routeId && ENTRY_ICONS[e.entryKey])
    .sort((a, b) => a.sortNo - b.sortNo || a.entryKey.localeCompare(b.entryKey))
}

/** 维护公告（已发布才有）。 */
export function projectNotice(published: PublishedEntry[]): string | null {
  const notice = published.find(e => e.entryType === 3 && e.contentText)
  return notice?.contentText ?? null
}
