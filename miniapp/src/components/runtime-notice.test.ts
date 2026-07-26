import { describe, expect, it } from 'vitest'
import {
  appServiceNoticeText,
  courierAdmissionNoticeText,
  evidenceNoticeText,
  homeCourierNoticeText,
  prototypeNoticeText,
  resolveEvidenceNoticeKey,
  resolvePrototypeNoticeKey,
  stationCatalogNoticeText,
  taskDepartConfirmMsg,
} from './runtime-notice'

/**
 * E2E-03 验收 P1-4：real/mock 文案分流判定纯函数化。
 * 钉住两件事：① real 构建的 delivery 页面必须切换为真实后果口径（真实变更/真实上传），
 * ② mock 构建与未声明域的页面必须保持历史原型口径一字不差（Mock 行为不受整改影响）。
 */
describe('运行边界文案分流 (P1-4)', () => {
  it('delivery 域 real 构建：顶部边界条切真实口径 key', () => {
    expect(resolvePrototypeNoticeKey('delivery', 'real')).toBe('real-delivery')
    const text = prototypeNoticeText('real-delivery')
    expect(text).toContain('真实变更订单与任务状态')
    expect(text).toContain('真实上传至受控媒体')
    expect(text).not.toContain('原型演示数据')
    expect(text).not.toContain('刷新后重置')
  })

  it('delivery 域 mock 构建：保持历史原型口径一字不差', () => {
    expect(resolvePrototypeNoticeKey('delivery', 'mock')).toBe('mock-prototype')
    expect(prototypeNoticeText('mock-prototype'))
      .toBe('原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。')
  })

  it('未声明域：无论构建模式恒为原型口径（全局 real 不得误标 mock 页面）', () => {
    expect(resolvePrototypeNoticeKey(undefined, 'mock')).toBe('mock-prototype')
    expect(resolvePrototypeNoticeKey(undefined, 'real')).toBe('mock-prototype')
  })

  it('未登记真实口径的接真域：回落原型口径（登记表是唯一开关）', () => {
    // order 域接真页面在页面层传精确 :text，组件层未登记 → 不切组件默认真实文案
    expect(resolvePrototypeNoticeKey('order', 'real')).toBe('mock-prototype')
  })

  it('凭证选择器 real：如实写明真实上传并按上限插值张数', () => {
    expect(resolveEvidenceNoticeKey('delivery', 'real')).toBe('real-delivery')
    const text = evidenceNoticeText('real-delivery', 3)
    expect(text).toContain('0~3 张')
    expect(text).toContain('真实上传至受控媒体')
    expect(text).not.toContain('mock-recorded')
  })

  it('凭证选择器 mock：保持「仅本地记录（mock-recorded）」口径', () => {
    expect(resolveEvidenceNoticeKey('delivery', 'mock')).toBe('mock-local')
    expect(resolveEvidenceNoticeKey(undefined, 'real')).toBe('mock-local')
    const text = evidenceNoticeText('mock-local', 5)
    expect(text).toBe('凭证可选 0~5 张，仅本地记录（mock-recorded，未上传云端）。')
  })
})

/**
 * 复审 P1-4：单页场景级条目双模式钉死——real 只陈述真实实现（审核队列/服务端记录/未接入能力如实），
 * mock 与历史文案一字不差。
 */
describe('页面场景级按模式文案 (P1-4 复审)', () => {
  it('准入顶栏（D02）real：如实写真实审核队列，不再出现原型口径', () => {
    const text = courierAdmissionNoticeText('real')
    expect(text).toContain('真实业务接口')
    expect(text).toContain('PC 后台审核队列')
    expect(text).toContain('审核通过前不具备配送工作能力')
    expect(text).not.toContain('原型演示数据')
    expect(text).not.toContain('刷新后重置')
  })

  it('准入顶栏（D02）mock：保持历史原型口径一字不差', () => {
    expect(courierAdmissionNoticeText('mock'))
      .toBe('原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。')
  })

  it('首页配送视角（U01）real：接单/履约/申诉如实标真，机主经营面如实保留原型陈述', () => {
    const text = homeCourierNoticeText('real')
    expect(text).toContain('真实接口')
    expect(text).toContain('真实变更订单与任务状态')
    expect(text).toContain('机主经营面仍为原型演示')
    expect(text).not.toContain('刷新后重置')
  })

  it('首页配送视角（U01）mock：保持历史原型口径一字不差', () => {
    expect(homeCourierNoticeText('mock'))
      .toBe('原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。')
  })

  it('离站确认（D03）real：离站时间由服务端记录、不采集定位与轨迹，不再写原型快照', () => {
    const text = taskDepartConfirmMsg('real')
    expect(text).toContain('确认已从水站取水出发')
    expect(text).toContain('服务端记录')
    expect(text).toContain('不采集定位与轨迹')
    expect(text).not.toContain('原型快照')
  })

  it('离站确认（D03）mock：保持历史弹窗文案一字不差', () => {
    expect(taskDepartConfirmMsg('mock'))
      .toBe('确认已从水站取水出发？离站时间按原型快照记录；定位在三照签收时以固定坐标原型快照记录，不绘制轨迹。')
  })

  it('附近水站（U07）real：档案接真但不虚构实时定位', () => {
    const text = stationCatalogNoticeText('real')
    expect(text).toContain('水站档案来自真实接口')
    expect(text).toContain('不采集实时定位')
    expect(text).not.toContain('原型水站列表')
  })

  it('附近水站（U07）mock：保持历史快照口径', () => {
    expect(stationCatalogNoticeText('mock'))
      .toBe('原型水站列表：未授权定位时按固定列表展示，距离为快照数据，不发起真实定位。')
  })

  it('服务说明（U03）混合接真：不再否认持久化与余额扣减', () => {
    const text = appServiceNoticeText(false)
    expect(text).toContain('业务操作会持久化')
    expect(text).toContain('可能真实扣减水卡余额')
    expect(text).toContain('不代表真实微信支付或物理设备')
    expect(text).not.toContain('不产生真实扣款')
  })

  it('服务说明（U03）全 Mock：保持原有 Demo 边界', () => {
    expect(appServiceNoticeText(true))
      .toBe('本应用当前为 Demo 原型：页面可操作但不产生真实扣款、退款、设备出水或消息发送；正式服务条款待商业一期发布。')
  })
})
