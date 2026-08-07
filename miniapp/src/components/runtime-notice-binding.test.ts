import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 页面提示文案的源码级断言（仓库无组件挂载基建，无 @vue/test-utils / jsdom）。
 *
 * <p>本文件原本钉的是「页面必须绑定 runtime-notice 的 mock/real 分流产出」。
 * 分流机制已于 2026-08-06 拆除，那条不变式随之失效——现在钉结果而非机制：
 * 每页给出确定的一句话，且任何页面都不许再冒出实现术语。</p>
 *
 * <p>与 {@code tools/check-ui-copy.py} 的分工：那道闸扫全仓、进 CI；这里覆盖几页
 * 出过实际事故的重点页面，跑在 {@code pnpm test} 里给开发即时反馈。</p>
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

  it('准入页（D02）：说清「自助申请不可用」，且同屏只说一遍', () => {
    const source = pageSource('../pages/courier/admission/index.vue')
    expect(source).toContain('暂不支持自助申请')
    // 这句只归准入状态卡片。顶栏提示条是无条件渲染的：已启用的配送员进来看到的
    // 第一句话会是「暂不支持自助申请」，而他早就在接单了——对每一种非「未提交」状态
    // 都是噪音，且在「未提交」状态下与卡片里那句一字不差。整条移除。
    expect(source).not.toContain('<AppPrototypeNotice')
    expect(source.match(/暂不支持自助申请/g)).toHaveLength(1)
  })

  it('首页（U01）：机主面写明订单额不等于可提现金额', () => {
    const source = pageSource('../pages/user/home/index.vue')
    expect(source).toMatch(/face\.value === 'courier'/)
    expect(source).toContain('非可提现金额')
  })

  it('我的页（U03）：不挂顶部提示条，服务说明只讲会扣余额这一件事', () => {
    const source = pageSource('../pages/user/profile/index.vue')
    expect(source).not.toContain('<AppPrototypeNotice')
    expect(source).toContain('扣减水卡余额')
  })

  it('附近水站（U07）：说清距离看不到，而不是解释为什么', () => {
    const source = pageSource('../pages/user/station/index.vue')
    expect(source).toContain('暂不获取定位')
    expect(source).toMatch(/<AppPrototypeNotice[^>]*:text="stationNotice"/)
  })
})
