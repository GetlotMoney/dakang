import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function source(relativePath: string): string {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('订单与配送状态同步', () => {
  it('用户订单和配送任务列表可见时轮询，隐藏与卸载时停止', () => {
    for (const page of [source('./index.vue'), source('../../courier/task/index.vue')]) {
      expect(page).toContain('createPagePoller')
      expect(page).toContain('4000')
      expect(page).toContain('onHide(')
      expect(page).toContain('onUnload(')
    }
  })

  it('取水进度和配送详情保持更短的终态轮询', () => {
    expect(source('../water/progress.vue')).toContain('1500')
    expect(source('./detail.vue')).toContain('3000')
  })

  it('商城列表与详情可见时轮询，分页加载后列表避免自动重排', () => {
    const list = source('../../mall/orders.vue')
    const detail = source('../../mall/order-detail.vue')
    expect(list).toContain('createPagePoller(() => reload(true), 5000)')
    expect(list).toContain('nextPage.value !== 2')
    expect(detail).toContain('createPagePoller(() => refresh(true), 5000)')
    expect(detail).toContain('onHide(orderPoller.stop)')
  })
})
