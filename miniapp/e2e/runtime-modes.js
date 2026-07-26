/**
 * 入口页运行期 API 模式指纹的解析与「逐域断言」（链路一 S0-A）。
 *
 * 单一来源：入口页 `miniapp/src/pages/entry/index.vue` 以 `NAME=value` 用分号拼接，
 * 顺序与段名必须与本文件 DOMAIN_ORDER 一致。
 *
 * 为什么不再用整串全等比较：L2-AUTH 给入口页新增 `AUTH=` 段后，两个取证脚本里写死的 5 段常量
 * 与实际 6 段永不相等，安全闸恒判「不安全构建」而在第 0/1 步退出——脚本静默失配、无法取证。
 * 改为逐域断言后：期望域缺失/值不符会给出精确原因；出现「未登记域」也会显式失败并提示同步，
 * 既保持 fail-closed，又不会再出现看不出根因的整串失配。
 */

/** 与 entry/index.vue 的 e2eApiModes 拼接顺序严格一致。 */
const DOMAIN_ORDER = ['GLOBAL', 'DEVICE', 'ORDER', 'CARD', 'RECHARGE', 'AUTH', 'DELIVERY']

/** 由期望映射还原为规范模式串（供日志与对照展示）。 */
function formatRuntimeModes(expected) {
  return DOMAIN_ORDER.map(domain => `${domain}=${expected[domain]}`).join(';')
}

/** 把 `A=b;C=d` 解析为对象；忽略空段与畸形段。 */
function parseRuntimeModes(raw) {
  const parsed = {}
  String(raw || '')
    .split(';')
    .filter(Boolean)
    .forEach((segment) => {
      const idx = segment.indexOf('=')
      if (idx > 0) {
        parsed[segment.slice(0, idx)] = segment.slice(idx + 1)
      }
    })
  return parsed
}

/**
 * 逐域断言，返回不满足的原因数组（空数组＝通过）。
 * 期望域缺失或值不符 → 精确原因；出现未登记域 → 提示同步期望，避免新增域后静默放行。
 */
function checkRuntimeModes(raw, expected) {
  const parsed = parseRuntimeModes(raw)
  const reasons = []
  Object.keys(expected).forEach((domain) => {
    if (!(domain in parsed)) {
      reasons.push(`缺少模式段 ${domain}`)
    }
    else if (parsed[domain] !== expected[domain]) {
      reasons.push(`${domain} 期望 ${expected[domain]} 实际 ${parsed[domain]}`)
    }
  })
  Object.keys(parsed).forEach((domain) => {
    if (!(domain in expected)) {
      reasons.push(`出现未登记域 ${domain}（入口页新增业务域后请同步 e2e 期望映射）`)
    }
  })
  return reasons
}

module.exports = { DOMAIN_ORDER, formatRuntimeModes, parseRuntimeModes, checkRuntimeModes }
