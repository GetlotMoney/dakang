export interface PagePoller {
  start: () => void
  stop: () => void
}

/**
 * 页面可见期间的顺序轮询器：每次任务结束后才开始计算下一轮间隔，杜绝慢请求重叠。
 * start/stop 可重复调用；隐藏或卸载页面后，未开始的下一轮会立即取消。
 */
export function createPagePoller(task: () => Promise<unknown>, intervalMs: number): PagePoller {
  let active = false
  let timer: ReturnType<typeof setTimeout> | null = null

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule() {
    clearTimer()
    if (!active) {
      return
    }
    timer = setTimeout(async () => {
      timer = null
      try {
        await task()
      }
      finally {
        schedule()
      }
    }, intervalMs)
  }

  return {
    start() {
      if (active) {
        return
      }
      active = true
      schedule()
    },
    stop() {
      active = false
      clearTimer()
    },
  }
}
