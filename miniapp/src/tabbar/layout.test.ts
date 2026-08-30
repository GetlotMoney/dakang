import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function source(name: string): string {
  return readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8')
}

describe('底部导航视觉结构', () => {
  const page = source('./index.vue')
  const config = source('./config.ts')

  it('微信原生栏保留三个稳定入口，H5仍复用Wot栏', () => {
    expect(config).toContain('custom: false')
    expect(page).toContain(':title="item.text"')
    expect(page).toContain(':icon="item.icon"')
  })

  it('订单和我的使用语义清晰的列表与用户圆形图标', () => {
    expect(config).toMatch(/name:\s*'order'[\s\S]*?icon:\s*'list'/)
    expect(config).toMatch(/name:\s*'profile'[\s\S]*?icon:\s*'user-circle'/)
  })

  it('h5栏保留 fixed、placeholder 与安全区，避免内容被底栏遮挡', () => {
    expect(page).toMatch(/<wd-tabbar[\s\S]*?fixed[\s\S]*?placeholder[\s\S]*?safe-area-inset-bottom/)
  })

  it('每个原生入口都有普通与选中两套PNG图标', () => {
    for (const name of ['home', 'order', 'profile']) {
      expect(config).toContain(`iconPath: 'static/tabbar/${name}.png'`)
      expect(config).toContain(`selectedIconPath: 'static/tabbar/${name}-active.png'`)
    }
  })
})
