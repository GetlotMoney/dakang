/**
 * D4 行为门（链路一 S0-R1 / A-2）。
 *
 * 背景：`EXPECTED_BEHAVIOR_COUNT` 长期写死 23，而 run-d4.js 的业务断言实际有 26 项，
 * 导致 `behaviorPass = results.length === 23` 恒 false——即使 26 项全过也判 FAIL。
 *
 * 口径（明确排除异常路径）：
 * - `step()` 捕获异常时补记的失败项带 `viaStepError: true`，**不计入完备性计数**
 *   （它代表"某步整体抛错"，该步自身的业务断言根本没执行，用它凑数会掩盖缺项）；
 * - 完备性 = 业务断言条数恰好等于 EXPECTED_BEHAVIOR_COUNT；
 * - 通过 = 完备 且 全部条目（含异常项）ok 且 无 fatalError。
 *
 * 新增/删除业务断言时必须同步本常量，否则 behaviorPass 会失败并提示实际条数。
 */

/** run-d4.js 中处于正常路径、每轮必然执行的业务断言条数。 */
const EXPECTED_BEHAVIOR_COUNT = 26

/** 仅统计业务断言，异常路径补记项不计入。 */
function countBusinessAssertions(results) {
  return (results || []).filter(item => !item.viaStepError).length
}

function evaluateBehaviorPass(results, fatalError) {
  const list = results || []
  return countBusinessAssertions(list) === EXPECTED_BEHAVIOR_COUNT
    && list.every(item => item.ok)
    && !fatalError
}

module.exports = { EXPECTED_BEHAVIOR_COUNT, countBusinessAssertions, evaluateBehaviorPass }
