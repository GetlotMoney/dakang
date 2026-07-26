import type { ApiDomain } from '@/api/runtime'

/**
 * 运行边界文案分流（E2E-03 验收 P1-4）——全仓唯一判定点。
 *
 * 背景：接真构建里页面若继续显示「不产生真实业务动作 / 刷新恢复原型 / 证据仅本地模拟」，
 * 与「真实扣款、真实上传」的实际行为相悖。分流判定收敛为纯函数（返回文案 key），
 * prototype-notice / evidence-picker 组件按 key 取文案，页面只声明所属业务域，
 * 不在每个页面各写一份 mock/real 判断，也不复制第二套文案。
 *
 * 登记范围：目前仅 delivery 域按域接真需要组件级分流；device/order/card 等已接真域的
 * 页面均在页面层传入精确 :text（先例见 user/home、user/order/detail），不依赖组件默认文案。
 * 新增按域分流时在两个 REAL_* 表登记即可，判定函数不改。
 * 组件默认口径覆盖不了的单页场景（D02 准入顶栏 / U01 配送视角 / D03 离站弹窗）以场景函数
 * 集中在本模块末尾：页面按 currentMode 取产出绑定，不内联第二份 mock/real 文案。
 */

export type PrototypeNoticeKey = 'mock-prototype' | `real-${ApiDomain}`
export type EvidenceNoticeKey = 'mock-local' | `real-${ApiDomain}`

/** mock 构建统一原型边界口径（与历史默认文案一字不差，Mock 行为不受本次整改影响）。 */
const MOCK_PROTOTYPE_TEXT = '原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。'

/** 接真域的顶部边界口径：写明真实后果（参照充值页先例「真实扣款/真实入账」的准确性要求）。 */
const REAL_NOTICE_TEXTS: Partial<Record<ApiDomain, string>> = {
  delivery: '配送链已接真实接口：本页操作会真实变更订单与任务状态，涉及的凭证照片将真实上传至受控媒体，提交后不因刷新恢复。',
}

/** 接真域的凭证选择口径：真实上传，不再是本地模拟（{max} 由组件按上限插值）。 */
const REAL_EVIDENCE_TEXTS: Partial<Record<ApiDomain, string>> = {
  delivery: '凭证可选 0~{max} 张，提交时将真实上传至受控媒体并随业务动作归档为证据。',
}

/**
 * 顶部边界条文案 key：仅当「声明了业务域 + 该域构建为 real + 该域登记了真实口径」三者齐备
 * 才切真实文案；未声明域 / mock 构建 / 未登记域一律回落原型口径（fail-safe，不会把
 * mock 页面误标成真实，也不会让未登记的接真域顶着原型文案——未登记即视为仍需页面级 :text）。
 */
export function resolvePrototypeNoticeKey(
  domain: ApiDomain | undefined,
  mode: 'mock' | 'real',
): PrototypeNoticeKey {
  if (domain && mode === 'real' && REAL_NOTICE_TEXTS[domain]) {
    return `real-${domain}`
  }
  return 'mock-prototype'
}

/** 顶部边界条文案取值（key → 文案；与 resolvePrototypeNoticeKey 同表，保证 key 必有文案）。 */
export function prototypeNoticeText(key: PrototypeNoticeKey): string {
  if (key !== 'mock-prototype') {
    const domain = key.slice('real-'.length) as ApiDomain
    const text = REAL_NOTICE_TEXTS[domain]
    if (text) {
      return text
    }
  }
  return MOCK_PROTOTYPE_TEXT
}

/** 凭证选择器文案 key：判定规则与顶部边界条一致（同一分界，不做第二套口径）。 */
export function resolveEvidenceNoticeKey(
  domain: ApiDomain | undefined,
  mode: 'mock' | 'real',
): EvidenceNoticeKey {
  if (domain && mode === 'real' && REAL_EVIDENCE_TEXTS[domain]) {
    return `real-${domain}`
  }
  return 'mock-local'
}

/** 凭证选择器文案取值：按上限插值张数；mock 保持既有「本地记录（mock-recorded）」口径。 */
export function evidenceNoticeText(key: EvidenceNoticeKey, max: number): string {
  if (key !== 'mock-local') {
    const domain = key.slice('real-'.length) as ApiDomain
    const text = REAL_EVIDENCE_TEXTS[domain]
    if (text) {
      return text.replace('{max}', String(max))
    }
  }
  return `凭证可选 0~${max} 张，仅本地记录（mock-recorded，未上传云端）。`
}

/*
 * ——页面场景级按模式文案——
 * 组件默认口径覆盖不了的单页场景：mock 口径与历史文案一字不差（Mock 行为不受影响），
 * real 口径只陈述真实实现的事实（服务端行为、未接入的能力如实写明，不作虚假声明）。
 */

/** D02 配送准入顶部边界条：real 下提交走真实准入接口、真实进入 PC 后台审核队列。 */
export function courierAdmissionNoticeText(mode: 'mock' | 'real'): string {
  if (mode === 'real') {
    return '配送准入为真实业务接口：提交后真实进入 PC 后台审核队列，审核通过前不具备配送工作能力。'
  }
  return MOCK_PROTOTYPE_TEXT
}

/** U01 首页配送视角边界条：delivery 已接真而 owner 域仍 mock，两种状态并存时分别如实陈述。 */
export function homeCourierNoticeText(mode: 'mock' | 'real'): string {
  if (mode === 'real') {
    return '配送接单、履约与申诉来自真实接口，操作会真实变更订单与任务状态；机主经营面仍为原型演示。'
  }
  return MOCK_PROTOTYPE_TEXT
}

/** D03 离站确认弹窗：real 下离站时间由服务端记录，且当前版本不采集定位与轨迹。 */
export function taskDepartConfirmMsg(mode: 'mock' | 'real'): string {
  if (mode === 'real') {
    return '确认已从水站取水出发？离站时间由服务端记录；当前版本不采集定位与轨迹。'
  }
  return '确认已从水站取水出发？离站时间按原型快照记录；定位在三照签收时以固定坐标原型快照记录，不绘制轨迹。'
}

/** U07 水站目录：catalog 跟随 delivery 域接真，定位能力仍按实际未接入状态说明。 */
export function stationCatalogNoticeText(mode: 'mock' | 'real'): string {
  if (mode === 'real') {
    return '水站档案来自真实接口；当前版本不采集实时定位，距离由服务端返回，未提供时显示“未定位”。'
  }
  return '原型水站列表：未授权定位时按固定列表展示，距离为快照数据，不发起真实定位。'
}

/** U03 服务说明：混合接真构建不得用“全原型”口径否认已发生的持久化与余额扣减。 */
export function appServiceNoticeText(allMock: boolean): string {
  if (allMock) {
    return '本应用当前为 Demo 原型：页面可操作但不产生真实扣款、退款、设备出水或消息发送；正式服务条款待商业一期发布。'
  }
  return '当前为 Demo 联调环境：已接真实接口的业务操作会持久化，并可能真实扣减水卡余额；Pay-Sim 与设备模拟器仅用于内部技术验证，不代表真实微信支付或物理设备，退款及消息发送等未接能力以页面提示为准。正式服务条款待商业一期发布。'
}
