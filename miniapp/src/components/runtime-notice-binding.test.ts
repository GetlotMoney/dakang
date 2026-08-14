import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 页面提示文案的源码级断言（仓库无组件挂载基建）：钉结果而非机制——渲染文案不许出现实现术语。
 * 与 tools/check-ui-copy.py 分工：那道闸扫全仓进 CI，这里覆盖重点页面给开发即时反馈。
 */
function pageSource(relative: string): string {
  return readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8')
}

/** 曾经在这些页面上出现过、绝不允许回潮的词。 */
const BANNED = [
  'mock',
  'Mock',
  '原型',
  '快照',
  '契约',
  '口径',
  '受控媒体',
  '服务端',
  '后端',
  'Pay-Sim',
  'Refund-Sim',
  '会真的',
  '演示数据',
]

const PAGES = [
  ['准入页（D02）', '../pages/courier/admission/index.vue'],
  ['任务详情（D03）', '../pages/courier/task/detail.vue'],
  ['首页（U01）', '../pages/user/home/index.vue'],
  ['我的页（U03）', '../pages/user/profile/index.vue'],
  ['附近水站（U07）', '../pages/user/station/index.vue'],
] as const

/** 只保留会渲染出去的部分：注释里写这些词是对的，那正是它该待的地方。 */
function renderedOnly(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('页面提示文案', () => {
  for (const [name, path] of PAGES) {
    it(`${name}：渲染文案里没有实现术语`, () => {
      const source = renderedOnly(pageSource(path))
      for (const word of BANNED) {
        expect(source, `${name} 渲染文案含「${word}」`).not.toContain(word)
      }
    })
  }

  /**
   * 守住「暂不支持自助申请」这条已删死路径不许回来。「不许出现」类断言必须先 renderedOnly 剥注释，
   * 否则解释性注释里的同名字符串会让断言失真。
   */
  it('准入页（D02）：不留「不支持自助申请」这条死路径，也不挂顶部提示条', () => {
    const rendered = renderedOnly(pageSource('../pages/courier/admission/index.vue'))
    expect(rendered).not.toContain('<AppPrototypeNotice')
    expect(rendered).not.toContain('暂不支持自助申请')
    expect(rendered).not.toContain('selfSubmitSupported')
    // 已启用状态要给去处而不是一句「入口在首页」
    expect(rendered).toMatch(/goTo\('D01'\)/)
  })

  /** 钉「必须没有免责式声明、且必须有收益钱包入口」，防止两条已删提示回潮。 */
  it('首页（U01）：不挂常驻提示条，口径差异交给标签与入口', () => {
    const rendered = renderedOnly(pageSource('../pages/user/home/index.vue'))
    expect(rendered).not.toContain('<AppPrototypeNotice')
    expect(rendered).not.toContain('非可提现金额')
    expect(rendered).not.toContain('无法撤销')
    // 机主面必须能走到收益钱包，否则「成交额不等于到手」这件事就真的没人回答了
    const ownerFace = pageSource('./home-face-owner.vue')
    expect(ownerFace).toContain('收益钱包')
    expect(ownerFace).toMatch(/goTo\('O06'\)/)
    // 金额标签本身承担口径
    expect(ownerFace).toContain('订单成交额')
  })

  it('我的页（U03）：不挂顶部提示条，隐私走平台指引而不是自撰弹窗', () => {
    const source = pageSource('../pages/user/profile/index.vue')
    // 「不许出现」类断言一律先剥注释再判：注释里写这些词是对的
    const rendered = renderedOnly(source)
    expect(rendered).not.toContain('<AppPrototypeNotice')
    expect(rendered).not.toContain('扣减水卡余额')
    expect(rendered).not.toContain('服务说明')
    // 隐私入口保留，但指向平台《用户隐私保护指引》全文，不再弹自写的一段说明
    expect(source).toContain('隐私与授权')
    expect(source).toContain('openPrivacyContract')
    expect(rendered).not.toContain('当前不获取你的位置')
  })

  /** 距离缺省时整格不渲染，不再挂「暂不获取定位」解释性提示；守住不回潮。 */
  it('水站目录（U07）：没有距离就不摆空指标格，也不解释为什么', () => {
    const rendered = renderedOnly(pageSource('../pages/user/station/index.vue'))
    expect(rendered).not.toContain('<AppPrototypeNotice')
    expect(rendered).not.toContain('暂不获取定位')
    expect(rendered).not.toContain('未定位')
    // 距离只在接口真给了米数时才渲染
    expect(pageSource('../pages/user/station/index.vue')).toMatch(/distanceText\(station\.distanceMeters\)/)
  })
})
