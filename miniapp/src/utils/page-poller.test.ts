import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPagePoller } from './page-poller'

afterEach(() => {
  vi.useRealTimers()
})

describe('页面顺序轮询器', () => {
  it('页面可见时按间隔执行，停止后不再请求', async () => {
    vi.useFakeTimers()
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createPagePoller(task, 4000)

    poller.start()
    await vi.advanceTimersByTimeAsync(3999)
    expect(task).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledOnce()

    poller.stop()
    await vi.advanceTimersByTimeAsync(8000)
    expect(task).toHaveBeenCalledOnce()
  })

  it('慢请求完成后才安排下一轮，不产生重叠请求', async () => {
    vi.useFakeTimers()
    let release!: () => void
    const task = vi.fn(() => new Promise<void>((resolve) => {
      release = resolve
    }))
    const poller = createPagePoller(task, 4000)

    poller.start()
    await vi.advanceTimersByTimeAsync(4000)
    expect(task).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(12000)
    expect(task).toHaveBeenCalledOnce()

    release()
    await vi.runAllTicks()
    await vi.advanceTimersByTimeAsync(3999)
    expect(task).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(2)
    poller.stop()
  })
})
