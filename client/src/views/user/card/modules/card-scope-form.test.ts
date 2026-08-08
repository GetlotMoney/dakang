import { describe, expect, it } from 'vitest'
import {
  buildScopePayload,
  canSubmitScope,
  emptyScopeForm,
  normalizeDeviceOptions,
  normalizeOutletOptions,
  normalizeScopeId,
  normalizeStationOptions,
  parseScopeJson,
  retainLegalOutlets
} from './card-scope-form'

/**
 * S4 R3：身份类 Long ID 前端边界 string 守恒 + 解析异常 fail-closed。
 * 大于 Number.MAX_SAFE_INTEGER(9007199254740991) 的 ID 经 Number()/JSON 数字解析
 * 会静默舍入到相邻可表示值（选 A 卡实际改 B 卡）；损坏的范围原文绝不预选
 * 「全部范围」——两者都必须 fail-closed。测试 ID 全部落在有符号 Long 范围内。
 */
describe('card-scope-form 解析 fail-closed（R3-P1-2）', () => {
  it('非法 JSON：ok=false、未选态，绝不得到 scopeType=all', () => {
    const parsed = parseScopeJson('not-json')
    expect(parsed.ok).toBe(false)
    expect(parsed.reason).toContain('数据异常')
    expect(parsed.form.scopeType).toBe('')
    expect(parsed.form.scopeType).not.toBe('all')
  })

  it('scopeType 缺失/未知/结构异常：ok=false，绝不预选全部范围', () => {
    expect(parseScopeJson('{}').ok).toBe(false)
    expect(parseScopeJson('{"scopeType":"everything"}').ok).toBe(false)
    expect(parseScopeJson('[1,2]').ok).toBe(false)
    expect(parseScopeJson('{"scopeType":"specified","stationIds":"7"}').ok).toBe(false)
    for (const raw of ['{}', '{"scopeType":"everything"}', '[1,2]']) {
      expect(parseScopeJson(raw).form.scopeType).toBe('')
    }
  })

  it('非法范围状态下 canSubmit=false；显式重选合法范围后才可提交', () => {
    const broken = parseScopeJson('not-json')
    expect(canSubmitScope(broken.form)).toBe(false)
    // 运营显式重选：全场
    broken.form.scopeType = 'all'
    expect(canSubmitScope(broken.form)).toBe(true)
    // 指定范围必须三维至少一项
    const specified = emptyScopeForm()
    specified.scopeType = 'specified'
    expect(canSubmitScope(specified)).toBe(false)
    specified.stationIds = ['7']
    expect(canSubmitScope(specified)).toBe(true)
    // 未选态构造载荷被防御性拒绝
    expect(() => buildScopePayload('9', emptyScopeForm(), undefined)).toThrow()
  })

  it('空原文=未配置：合法但落未选态，仍需显式选择', () => {
    const parsed = parseScopeJson(undefined)
    expect(parsed.ok).toBe(true)
    expect(parsed.form.scopeType).toBe('')
    expect(canSubmitScope(parsed.form)).toBe(false)
  })

  it('历史超界 number 进入：整体拒绝，绝不静默改写成相邻错值', () => {
    // JSON 数字 9007199254740993 经 JSON.parse 已塌缩为 9007199254740992——
    // 转字符串只会固化错 ID，必须整体拒绝而非丢弃/改写
    const parsed = parseScopeJson('{"scopeType":"specified","stationIds":[9007199254740993]}')
    expect(parsed.ok).toBe(false)
    expect(parsed.reason).toContain('无法安全解析')
    expect(JSON.stringify(parsed.form)).not.toContain('9007199254740992')
  })

  it('同值以字符串进入：逐字保留', () => {
    const parsed = parseScopeJson('{"scopeType":"specified","stationIds":["9007199254740993"]}')
    expect(parsed.ok).toBe(true)
    expect(parsed.form.stationIds).toEqual(['9007199254740993'])
  })

  it('Long 边界：MAX 通过、MAX+1 拒绝、20 位拒绝', () => {
    expect(normalizeScopeId('9223372036854775807')).toBe('9223372036854775807')
    expect(normalizeScopeId('9223372036854775808')).toBeNull()
    expect(normalizeScopeId('18446744073709551557')).toBeNull()
  })

  it('负数/小数/科学计数/前导零/零/非法串全部 fail-closed', () => {
    expect(normalizeScopeId(-5)).toBeNull()
    expect(normalizeScopeId(3.5)).toBeNull()
    expect(normalizeScopeId('-5')).toBeNull()
    expect(normalizeScopeId('3.5')).toBeNull()
    expect(normalizeScopeId('1e3')).toBeNull()
    expect(normalizeScopeId('007')).toBeNull()
    expect(normalizeScopeId('0')).toBeNull()
    expect(normalizeScopeId(0)).toBeNull()
    expect(normalizeScopeId('')).toBeNull()
    expect(normalizeScopeId(null)).toBeNull()
    // 安全范围内正整数 number 是合法历史来源：无损十进制化
    expect(normalizeScopeId(7)).toBe('7')
  })

  it('历史合法数字转十进制；数组含不可信元素则整体拒绝（不静默丢弃）', () => {
    const legal = parseScopeJson('{"scopeType":"specified","stationIds":[7,"8"]}')
    expect(legal.ok).toBe(true)
    expect(legal.form.stationIds).toEqual(['7', '8'])
    expect(parseScopeJson('{"scopeType":"specified","stationIds":[7,null]}').ok).toBe(false)
    expect(parseScopeJson('{"scopeType":"specified","stationIds":[{}]}').ok).toBe(false)
  })
})

describe('card-scope-form Long ID string 守恒', () => {
  const HUGE_A = '9007199254740993'
  const HUGE_B = '9007199254740995'
  const HUGE_CARD = '9223372036854775806'

  it('回显：超安全整数 ID 逐字保留，绝不数值舍入', () => {
    const parsed = parseScopeJson(
      `{"scopeType":"specified","stationIds":["${HUGE_A}"],"deviceIds":["${HUGE_B}"],"outletIds":[]}`
    )
    expect(parsed.ok).toBe(true)
    expect(parsed.form.stationIds).toEqual([HUGE_A])
    expect(parsed.form.deviceIds).toEqual([HUGE_B])
    // 反证：若经 Number() 舍入，两个相邻奇数 ID 会塌缩为同一偶数
    expect(String(Number(HUGE_A))).not.toBe(HUGE_A)
    expect(parsed.form.stationIds[0]).toBe(HUGE_A)
  })

  it('提交：载荷 ID 与表单选择逐字一致（含 Long.MAX−1 级卡 ID）', () => {
    const form = emptyScopeForm()
    form.scopeType = 'specified'
    form.stationIds = [HUGE_A]
    form.outletIds = [HUGE_B]
    form.reason = ' 运营调整 '
    const payload = buildScopePayload(HUGE_CARD, form, '{"scopeType":"all"}')
    expect(payload.cardId).toBe(HUGE_CARD)
    expect(payload.stationIds).toEqual([HUGE_A])
    expect(payload.outletIds).toEqual([HUGE_B])
    expect(payload.reason).toBe('运营调整')
    expect(JSON.parse(JSON.stringify(payload)).cardId).toBe(HUGE_CARD)
  })

  it('过滤：Set 键为 string，相邻大 ID 不因舍入被误保留/误剔除', () => {
    const kept = retainLegalOutlets([HUGE_A, HUGE_B], [HUGE_A])
    expect(kept).toEqual([HUGE_A])
  })
})

/**
 * S4 R2/R3 归一化适配器：站点/设备/出水口接口响应（unknown）→ string ID 选项。
 * ID 判定与 parseScopeJson 同一入口 normalizeScopeId：不可信行丢弃 fail-closed。
 */
describe('card-scope 归一化适配器（真实 API 形状）', () => {
  const HUGE_A = '9007199254740993'
  const HUGE_CARD = '9223372036854775806'

  it('水站：string ID 逐字保留、安全正整数转十进制、超界/负数行丢弃', () => {
    const options = normalizeStationOptions([
      { id: HUGE_A, stationName: '一号水站' },
      { id: 7, stationName: '二号水站' },
      // 后端若把超界 Long 序列化成 JSON number，JSON.parse 时精度已丢——
      // "9007199254740993" 解析即塌缩为 9007199254740992（=MAX_SAFE_INTEGER+2 的
      // 实际浮点值，字面量会触发 no-loss-of-precision 故用表达式），选它必错卡，整行丢弃
      { id: Number.MAX_SAFE_INTEGER + 2, stationName: '损坏行' },
      { id: -3, stationName: '负数行' },
      { id: null, stationName: '无 ID 行' }
    ])
    expect(options).toEqual([
      { id: HUGE_A, stationName: '一号水站' },
      { id: '7', stationName: '二号水站' }
    ])
    expect(options.some((o) => o.id === '9007199254740992')).toBe(false)
  })

  it('设备/出水口：同一契约；畸形输入（非数组/非对象行）恒回空', () => {
    const devices = normalizeDeviceOptions([
      { id: HUGE_A, deviceNo: 'DEV-001', deviceName: '大堂机' },
      { id: '8', deviceNo: 'DEV-002' },
      'junk',
      null
    ])
    expect(devices).toEqual([
      { id: HUGE_A, deviceNo: 'DEV-001', deviceName: '大堂机' },
      { id: '8', deviceNo: 'DEV-002', deviceName: undefined }
    ])
    const outlets = normalizeOutletOptions([{ id: HUGE_A, outletNo: 2 }, { id: {} }], 'DEV-001')
    expect(outlets).toEqual([{ id: HUGE_A, deviceNo: 'DEV-001', outletNo: 2 }])
    expect(normalizeStationOptions(undefined)).toEqual([])
    expect(normalizeDeviceOptions({ list: [] })).toEqual([])
    expect(normalizeOutletOptions('x', 'DEV')).toEqual([])
  })

  it('端到端：归一化→选择→提交体 JSON 序列化 ID 逐字一致', () => {
    const stations = normalizeStationOptions([{ id: HUGE_A, stationName: '一号水站' }])
    const outlets = normalizeOutletOptions([{ id: '9007199254740995', outletNo: 1 }], 'DEV-001')
    const form = emptyScopeForm()
    form.scopeType = 'specified'
    form.stationIds = [stations[0].id]
    form.outletIds = retainLegalOutlets(
      [outlets[0].id],
      outlets.map((o) => o.id)
    )
    form.reason = '运营调整'
    expect(canSubmitScope(form)).toBe(true)
    const body = JSON.stringify(buildScopePayload(HUGE_CARD, form, undefined))
    expect(body).toContain(`"cardId":"${HUGE_CARD}"`)
    expect(body).toContain(`"stationIds":["${HUGE_A}"]`)
    expect(body).toContain('"outletIds":["9007199254740995"]')
    // 提交体里绝不允许出现被舍入的相邻值
    expect(body).not.toContain('9007199254740992')
    expect(body).not.toContain('9007199254740994')
  })
})
