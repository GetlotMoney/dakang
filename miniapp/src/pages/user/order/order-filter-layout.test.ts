import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function pageSource(): string {
  return readFileSync(fileURLToPath(new URL('./index.vue', import.meta.url)), 'utf8')
}

describe('订单页双维筛选布局', () => {
  const source = pageSource()

  it('订单类型保留顶部 Tab，订单状态使用左侧纵向栏', () => {
    expect(source).toMatch(/<wd-tabs v-model="activeTab"/)
    expect(source).toMatch(/class="order-workspace"/)
    expect(source).toMatch(/class="status-rail"/)
    expect(source).toMatch(/class="status-rail__item"/)
    expect(source).not.toMatch(/<scroll-view class="status-bar"/)
    expect(source).not.toMatch(/class="status-chip"/)
  })

  it('左栏固定宽度、右栏可收缩，避免状态和订单内容横向溢出', () => {
    expect(source).toMatch(/\.status-rail\s*\{[\s\S]*?flex:\s*0 0 84px/)
    expect(source).toMatch(/\.order-content\s*\{[\s\S]*?flex:\s*1;[\s\S]*?min-width:\s*0/)
    expect(source).toMatch(/&__item\s*\{[\s\S]*?min-height:\s*44px/)
  })

  it('状态选择仍复用原查询函数，不把筛选退化为纯前端显隐', () => {
    expect(source).toMatch(/@click="pickStatus\(column\.value\)"/)
    expect(source).toMatch(/orderStatus:\s*statusValue/)
  })
})
