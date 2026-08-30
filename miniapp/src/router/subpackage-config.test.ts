import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { MINIAPP_SUBPACKAGE_ROOTS } from '../../subpackage.config'

describe('微信小程序分包契约', () => {
  it('低频工作台固定进入分包，三项 Tabbar 不得误入', () => {
    expect(MINIAPP_SUBPACKAGE_ROOTS).toEqual([
      'src/pages/mall',
      'src/pages/owner',
      'src/pages/courier',
      'src/pages/identity',
      'src/pages/channel',
      'src/pages/region',
      'src/pages/demo',
    ])
    const roots: readonly string[] = MINIAPP_SUBPACKAGE_ROOTS
    expect(roots.includes('src/pages/user')).toBe(false)
    expect(new Set(MINIAPP_SUBPACKAGE_ROOTS).size).toBe(MINIAPP_SUBPACKAGE_ROOTS.length)
  })

  it('构建前必须重写生成态 pages.json，防止旧主包页面与分包重复', () => {
    const source = readFileSync(new URL('../../scripts/create-base-files.mjs', import.meta.url), 'utf8')
    expect(source).toContain('fs.writeFileSync(pagesPath')
    expect(source).not.toMatch(/if\s*\(!fs\.existsSync\(pagesPath\)\)/)
  })
})
