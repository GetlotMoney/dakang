import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 复审 P1-4 页面绑定断言：仓库无组件挂载基建（无 @vue/test-utils / jsdom），
 * 以源码断言钉住「页面绑定 runtime-notice 产出、不内联第二份 mock/real 文案」。
 * 断言匹配标识符与绑定形态而非整行文本，页面重排/格式化不破坏断言。
 */
function pageSource(relative: string): string {
  return readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8')
}

describe('运行边界文案页面绑定 (P1-4 复审)', () => {
  it('准入页（D02）：AppPrototypeNotice 绑定 delivery 模式分流产出，不再是光板默认', () => {
    const source = pageSource('../pages/courier/admission/index.vue')
    expect(source).toMatch(/courierAdmissionNoticeText\(\s*currentMode\('delivery'\)\s*\)/)
    expect(source).toMatch(/<AppPrototypeNotice[^>]*:text="admissionNotice"/)
    expect(source).not.toMatch(/<AppPrototypeNotice\s*\/>/)
  })

  it('任务详情（D03）：离站弹窗取 runtime-notice 按模式函数，无「按原型快照记录」硬编码，拨号提示不标「原型」', () => {
    const source = pageSource('../pages/courier/task/detail.vue')
    expect(source).toMatch(/taskDepartConfirmMsg\(\s*currentMode\('delivery'\)\s*\)/)
    expect(source).not.toContain('按原型快照记录')
    expect(source).not.toContain('原型不拨打真实电话')
  })

  it('首页（U01）：配送视角提示按 delivery 模式经 runtime-notice 分流', () => {
    const source = pageSource('../pages/user/home/index.vue')
    expect(source).toMatch(/face\.value === 'courier'/)
    expect(source).toMatch(/homeCourierNoticeText\(\s*currentMode\('delivery'\)\s*\)/)
  })

  it('我的页（U03）：聚合口径按 card 与 delivery 两域模式分流，不恒标配送员申请为演示', () => {
    const source = pageSource('../pages/user/profile/index.vue')
    expect(source).toMatch(/currentMode\('card'\)/)
    expect(source).toMatch(/currentMode\('delivery'\)/)
    expect(source).toMatch(/appServiceNoticeText\(isMockBuild\)/)
    expect(source).not.toContain('页面可操作但不产生真实扣款')
  })

  it('附近水站（U07）：catalog 随 delivery 接真时按模式分流，不硬编码原型固定列表', () => {
    const source = pageSource('../pages/user/station/index.vue')
    expect(source).toMatch(/stationCatalogNoticeText\(currentMode\('delivery'\)\)/)
    expect(source).toMatch(/<AppPrototypeNotice[^>]*:text="stationNotice"/)
    expect(source).not.toContain('原型水站列表：未授权定位时按固定列表展示')
  })
})
