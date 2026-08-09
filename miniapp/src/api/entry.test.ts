import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as request from './request'
import {
  listPublishedEntries,
  projectFeatureEntries,
  projectNotice,
  resetEntryCacheForTest,
} from './entry'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

vi.mock('./real-session', () => ({
  withRealSession: (fn: () => unknown) => fn(),
}))

const post = vi.mocked(request.post)

/**
 * S6 R1-7 fail-closed：宫格只渲染已发布配置，无硬编码默认入口复活路径。
 * 「成功读取为空」是合法缓存值，与「从未成功」严格区分；网络异常只回放
 * 最后一次成功配置，绝不把空列表替换成默认入口。
 */
describe('入口配置投影（fail-closed）', () => {
  beforeEach(() => {
    resetEntryCacheForTest()
    post.mockReset()
  })

  it('已发布功能入口按排序稳定投影；名称取配置', async () => {
    post.mockResolvedValueOnce([
      { entryKey: 'U10', entryType: '1', entryName: '充值中心', sortNo: '5', routeId: 'U10' },
      { entryKey: 'U08', entryType: 1, entryName: '订水', sortNo: 20, routeId: 'U08' },
      { entryKey: 'notice', entryType: 3, entryName: '公告', sortNo: 0, contentText: '今晚维护' },
    ])
    const published = await listPublishedEntries()
    const entries = projectFeatureEntries(published)
    expect(entries.map(e => e.entryKey)).toEqual(['U10', 'U08'])
    expect(entries[0].entryName).toBe('充值中心')
    expect(projectNotice(published)).toBe('今晚维护')
  })

  it('从未成功读取+接口异常：宫格为空（不复活任何默认入口）', async () => {
    post.mockRejectedValueOnce(new Error('boom'))
    const published = await listPublishedEntries()
    expect(published).toEqual([])
    expect(projectFeatureEntries(published)).toEqual([])
  })

  it('r1-7 核心回归：先成功读取撤回后的空列表，再接口异常——U08/U10 仍不可见', async () => {
    // 第一次：运营已撤回全部入口，服务端成功返回空列表
    post.mockResolvedValueOnce([])
    expect(await listPublishedEntries()).toEqual([])
    // 第二次：网络异常——必须回放「空列表」这一合法缓存，绝不恢复硬编码默认
    post.mockRejectedValueOnce(new Error('network down'))
    const afterFailure = await listPublishedEntries()
    expect(afterFailure).toEqual([])
    expect(projectFeatureEntries(afterFailure).map(e => e.entryKey)).not.toContain('U08')
    expect(projectFeatureEntries(afterFailure).map(e => e.entryKey)).not.toContain('U10')
  })

  it('异常回放最后一次成功配置（非空场景）', async () => {
    post.mockResolvedValueOnce([
      { entryKey: 'U08', entryType: 1, entryName: '订水', sortNo: 1, routeId: 'U08' },
    ])
    await listPublishedEntries()
    post.mockRejectedValueOnce(new Error('flaky'))
    const replayed = await listPublishedEntries()
    expect(projectFeatureEntries(replayed).map(e => e.entryKey)).toEqual(['U08'])
  })

  it('r2-p1-4 并发乱序：旧非空响应晚到——两个调用都不得出现旧入口，缓存保持空', async () => {
    // A（旧代）：撤回前发出的请求，响应被扣住晚到；B（新代）：撤回后的空列表先返回
    let releaseA!: (rows: Array<Record<string, unknown>>) => void
    post.mockImplementationOnce(
      () => new Promise((resolve) => { releaseA = resolve }) as never,
    )
    const callA = listPublishedEntries()
    post.mockResolvedValueOnce([])
    const callB = listPublishedEntries()
    expect(await callB).toEqual([])
    // A 晚到：携带 U08/U10 的撤回前列表——过期代际不得写缓存，也不得把旧列表交给界面
    releaseA([
      { entryKey: 'U08', entryType: 1, entryName: '订水', sortNo: 1, routeId: 'U08' },
      { entryKey: 'U10', entryType: 1, entryName: '充值', sortNo: 2, routeId: 'U10' },
    ])
    const resultA = await callA
    expect(resultA).toEqual([])
    expect(projectFeatureEntries(resultA).map(e => e.entryKey)).not.toContain('U08')
    expect(projectFeatureEntries(resultA).map(e => e.entryKey)).not.toContain('U10')
    // 缓存仍是「撤回后的空列表」：随后的异常回放也不复活旧入口
    post.mockRejectedValueOnce(new Error('flaky'))
    expect(await listPublishedEntries()).toEqual([])
  })

  it('缺路由或图标未登记的行整条丢弃；脏类型行丢弃', async () => {
    post.mockResolvedValueOnce([
      { entryKey: 'U08', entryType: 1, entryName: '订水', sortNo: 1, routeId: 'U08' },
      { entryKey: 'X99', entryType: 1, entryName: '幽灵', sortNo: 2, routeId: 'X99' },
      { entryKey: 'U10', entryType: 1, entryName: '缺路由', sortNo: 3 },
      { entryKey: 'weird', entryType: 9, entryName: '脏类型', sortNo: 4 },
    ])
    const entries = projectFeatureEntries(await listPublishedEntries())
    expect(entries.map(e => e.entryKey)).toEqual(['U08'])
  })
})
