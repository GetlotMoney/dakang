/**
 * 水卡授权范围表单 ⇄ 提交载荷的纯函数层（S4 R3）。
 * 身份类 Long ID 边界恒 string（>2^53 经 Number 静默舍入，选 A 改 B），本层禁止任何数值转换。
 * 铁律：解析失败绝不预选「全部范围」，落未选态禁存（fail-closed）；
 * 任一 ID 不可信即整体拒绝，不静默丢弃、不静默改写。
 */

export interface ScopeFormState {
  /** ''=未选择（初始/解析失败后强制显式重选），提交前必须落到 all/specified */
  scopeType: 'all' | 'specified' | ''
  stationIds: string[]
  deviceIds: string[]
  outletIds: string[]
  reason: string
}

export interface ScopePayload {
  cardId: string
  scopeType: 'all' | 'specified'
  stationIds: string[]
  deviceIds: string[]
  outletIds: string[]
  reason: string
  expectedScopeJson?: string
}

/** 解析结果显式化（R3-P1-2）：ok=false 时 form 为未选态空白，reason 供页面展示。 */
export interface ScopeParseResult {
  ok: boolean
  form: ScopeFormState
  reason?: string
}

export function emptyScopeForm(): ScopeFormState {
  return { scopeType: '', stationIds: [], deviceIds: [], outletIds: [], reason: '' }
}

const LONG_MAX = '9223372036854775807'

/**
 * 身份 ID 归一唯一入口：string 只接受规范正整数十进制且 ≤ Long.MAX_VALUE；number 仅正安全整数
 * （超界 number 在 JSON.parse 已丢精度，String() 只会固化错值）；其余一律 null，调用方 fail-closed。
 */
export function normalizeScopeId(raw: unknown): string | null {
  if (typeof raw === 'string') {
    if (!/^[1-9][0-9]{0,18}$/.test(raw)) {
      return null
    }
    if (raw.length === 19 && raw > LONG_MAX) {
      return null
    }
    return raw
  }
  if (typeof raw === 'number' && Number.isSafeInteger(raw) && raw > 0) {
    return String(raw)
  }
  return null
}

/**
 * 解析既有 SCOPE_JSON 回显表单（fail-closed）：空原文=未配置，返回未选态；
 * JSON 损坏/类型未知/任一 ID 不可信时 ok=false 落未选态禁存，绝不预选成全部范围。
 */
export function parseScopeJson(scopeJson: string | undefined | null): ScopeParseResult {
  if (!scopeJson) {
    return { ok: true, form: emptyScopeForm() }
  }
  let scope: {
    scopeType?: unknown
    stationIds?: unknown
    deviceIds?: unknown
    outletIds?: unknown
  }
  try {
    scope = JSON.parse(scopeJson)
  } catch {
    return { ok: false, form: emptyScopeForm(), reason: '原授权范围数据异常：不是合法 JSON' }
  }
  if (scope === null || typeof scope !== 'object') {
    return { ok: false, form: emptyScopeForm(), reason: '原授权范围数据异常：结构不完整' }
  }
  if (scope.scopeType === 'all') {
    const form = emptyScopeForm()
    form.scopeType = 'all'
    return { ok: true, form }
  }
  if (scope.scopeType !== 'specified') {
    return { ok: false, form: emptyScopeForm(), reason: '原授权范围数据异常：范围类型未知' }
  }
  const stationIds = parseIdList(scope.stationIds)
  const deviceIds = parseIdList(scope.deviceIds)
  const outletIds = parseIdList(scope.outletIds)
  if (stationIds === null || deviceIds === null || outletIds === null) {
    return {
      ok: false,
      form: emptyScopeForm(),
      reason: '原授权范围数据异常：范围内存在无法安全解析的 ID'
    }
  }
  const form = emptyScopeForm()
  form.scopeType = 'specified'
  form.stationIds = stationIds
  form.deviceIds = deviceIds
  form.outletIds = outletIds
  return { ok: true, form }
}

/** ID 数组解析：缺省=空数组；非数组或任一元素不可信=整体不可信（null），绝不静默丢弃。 */
function parseIdList(raw: unknown): string[] | null {
  if (raw === undefined || raw === null) {
    return []
  }
  if (!Array.isArray(raw)) {
    return null
  }
  const ids: string[] = []
  for (const item of raw) {
    const id = normalizeScopeId(item)
    if (id === null) {
      return null
    }
    ids.push(id)
  }
  return ids
}

/**
 * 提交闸（R3-P1-2）：未选态（含解析失败后）恒不可提交；指定范围必须三维至少一项
 * （与服务端 WaterCardScope 空范围拒绝同向，前端不复制其余业务规则）。
 */
export function canSubmitScope(form: ScopeFormState): boolean {
  if (form.scopeType === 'all') {
    return true
  }
  if (form.scopeType === 'specified') {
    return form.stationIds.length + form.deviceIds.length + form.outletIds.length > 0
  }
  return false
}

/** 构造提交载荷：ID 全链 string 逐字透传，由后端 Spring 转 Long。未选态防御性拒绝。 */
export function buildScopePayload(
  cardId: string,
  form: ScopeFormState,
  expectedScopeJson: string | undefined
): ScopePayload {
  if (form.scopeType !== 'all' && form.scopeType !== 'specified') {
    throw new Error('授权范围类型未选择，禁止构造提交载荷')
  }
  return {
    cardId,
    scopeType: form.scopeType,
    stationIds: [...form.stationIds],
    deviceIds: [...form.deviceIds],
    outletIds: [...form.outletIds],
    reason: form.reason.trim(),
    expectedScopeJson
  }
}

/** 出水口合法性过滤（设备变更后剔除失效选择）：Set 键为 string，无数值窗口。 */
export function retainLegalOutlets(selected: string[], legal: string[]): string[] {
  const legalSet = new Set(legal)
  return selected.filter((id) => legalSet.has(id))
}

// ==================== 范围维护选项归一化适配器（S4 R2/R3） ====================
// 站点/设备/出水口全局类型仍是 number id；本链路用专用适配器归一为 string ID 选项，
// ID 判定与 normalizeScopeId 同一入口，不可信 ID 整行丢弃 fail-closed。

/** 范围维护·水站选项（ID 恒 string）。 */
export interface ScopeStationOption {
  id: string
  stationName: string
}

/** 范围维护·设备选项（ID 恒 string）。 */
export interface ScopeDeviceOption {
  id: string
  deviceNo: string
  deviceName?: string
}

/** 范围维护·出水口选项（ID 恒 string；outletNo 是口序号小整数，非身份 ID）。 */
export interface ScopeOutletOption {
  id: string
  deviceNo: string
  outletNo: number
}

function rowsOf(raw: unknown): Record<string, unknown>[] {
  if (!Array.isArray(raw)) {
    return []
  }
  return raw.filter(
    (row): row is Record<string, unknown> => typeof row === 'object' && row !== null
  )
}

function textOf(raw: unknown): string {
  return typeof raw === 'string' ? raw : ''
}

/** 水站接口响应 → 选项：非法/超界 ID 行丢弃。 */
export function normalizeStationOptions(raw: unknown): ScopeStationOption[] {
  return rowsOf(raw).flatMap((row) => {
    const id = normalizeScopeId(row.id)
    return id === null ? [] : [{ id, stationName: textOf(row.stationName) }]
  })
}

/** 设备接口响应 → 选项：非法/超界 ID 行丢弃。 */
export function normalizeDeviceOptions(raw: unknown): ScopeDeviceOption[] {
  return rowsOf(raw).flatMap((row) => {
    const id = normalizeScopeId(row.id)
    return id === null
      ? []
      : [{ id, deviceNo: textOf(row.deviceNo), deviceName: textOf(row.deviceName) || undefined }]
  })
}

/** 出水口接口响应 → 选项：非法/超界 ID 行丢弃；outletNo 非 number 时置 0 仅影响展示。 */
export function normalizeOutletOptions(raw: unknown, deviceNo: string): ScopeOutletOption[] {
  return rowsOf(raw).flatMap((row) => {
    const id = normalizeScopeId(row.id)
    if (id === null) {
      return []
    }
    const outletNo = typeof row.outletNo === 'number' ? row.outletNo : 0
    return [{ id, deviceNo, outletNo }]
  })
}
