import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function pageSource(): string {
  return readFileSync(fileURLToPath(new URL('./index.vue', import.meta.url)), 'utf8')
}

describe('首页多能力视角切换', () => {
  const source = pageSource()

  it('菜单锚定在当前工作台控件下方，不使用系统顶部选择器', () => {
    expect(source).toContain('class="face-dropdown-panel"')
    expect(source).toContain('@click.stop="toggleFaceMenu"')
    expect(source).toContain('@click.stop="chooseFace(option.value)"')
    expect(source).toContain('top: calc(100% + 8px)')
    expect(source).not.toContain('<picker')
    expect(source).not.toContain('<wd-drop-menu')
    expect(source).not.toContain('<wd-segmented')
    expect(source).not.toContain('<wd-popup')
  })

  it('下拉标签覆盖五种工作台并提供用途说明', () => {
    for (const label of ['生活服务', '配送工作台', '机主经营', '渠道推广', '区域运营']) {
      expect(source).toContain(`label: '${label}'`)
    }
    expect(source).toContain('切换只改变首页视角')
    expect(source).toContain('区域血缘和公域线索')
  })

  it('切换仍复用原有本地记忆与刷新逻辑', () => {
    expect(source).toContain('function chooseFace')
    expect(source).toContain('selectFace(next)')
    expect(source).toContain('uni.setStorageSync(storageKey(), next)')
    expect(source).toContain('refreshTick.value += 1')
  })
})
