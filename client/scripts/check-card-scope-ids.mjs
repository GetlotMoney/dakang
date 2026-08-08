/**
 * S4 身份 ID string 契约闸（R2-P1-3）：水卡授权范围链路的身份类 Long ID 必须全链 string。
 *
 * 背景：后端 Long 超过 2^53 时，前端任何一次 Number()/parseInt() 都会把 ID 静默舍入到
 * 相邻可表示值——选 A 卡实际改 B 卡，类型检查与构建全绿。该缺陷在 S4 首轮与 R1 两次
 * 复审中反复出现（接口层 number 声明残留），故固化为源码级门禁：
 *   1. user.ts 卡域接口禁止 number 身份声明（CardItem.id / CardMemberItem 三键 /
 *      fetchCardDetail、fetchChangeCardStatus 入参）；
 *   2. 抽屉组件禁止 cardId?: number 回潮；
 *   3. 链路文件（纯函数层/抽屉/卡页对 ID）禁止 Number()/parseInt() 数值转换。
 *
 * 用法：node scripts/check-card-scope-ids.mjs（非零退出即有回归）
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const clientDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const read = (rel) => fs.readFileSync(path.join(clientDir, rel), 'utf8')

/** 注释不构成契约破坏（文档里必然要提 Number() 的危害）：扫描前统一剥离。 */
const stripComments = (source) =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:'"])\/\/.*$/gm, '$1')

const violations = []
const check = (file, source, pattern, message) => {
  const match = source.match(pattern)
  if (match) {
    const line = source.slice(0, match.index).split('\n').length
    violations.push(`${file}:${line} ${message}（命中：${match[0].trim()}）`)
  }
}

// 1) 接口层：卡域身份声明必须 string
const userApi = stripComments(read('src/api/user.ts'))
const cardBlocks = userApi.match(
  /export interface CardMemberItem[\s\S]*?\n}|export interface CardItem[\s\S]*?\n}/g
)
if (!cardBlocks || cardBlocks.length < 2) {
  violations.push('src/api/user.ts 未解析到 CardItem/CardMemberItem 接口块，门禁与源码结构脱节')
} else {
  for (const block of cardBlocks) {
    check(
      'src/api/user.ts',
      block,
      /\b(id|cardId|memberUserId):\s*number/,
      '卡域身份 ID 声明回归为 number'
    )
  }
}
check(
  'src/api/user.ts',
  userApi,
  /fetchCardDetail\s*\(\s*id:\s*number/,
  'fetchCardDetail 入参回归为 number'
)
check(
  'src/api/user.ts',
  userApi,
  /fetchChangeCardStatus\s*\(\s*id:\s*number/,
  'fetchChangeCardStatus 入参回归为 number'
)
// 赠卡链（用户指派切片）：收卡用户 ID 同为身份类 Long，必须 string 直传
const giftBlock = userApi.match(/export interface GiftIssueParams[\s\S]*?\n}/)
if (!giftBlock) {
  violations.push('src/api/user.ts 未解析到 GiftIssueParams 接口块，门禁与源码结构脱节')
} else {
  check('src/api/user.ts', giftBlock[0], /userId:\s*number/, '赠卡收卡用户 ID 回归为 number')
}

// 2) 组件入参：cardId 必须 string
const drawer = stripComments(read('src/views/user/card/modules/card-detail-drawer.vue'))
check(
  'src/views/user/card/modules/card-detail-drawer.vue',
  drawer,
  /cardId\??:\s*number/,
  'cardId 组件入参回归为 number'
)

// 3) 链路文件禁数值转换：纯函数层/抽屉全文件禁 Number()/parseInt()
//    （Number.isSafeInteger 属性访问不是调用 Number()，不在禁区），卡页仅对身份 ID 表达式
for (const rel of [
  'src/views/user/card/modules/card-scope-form.ts',
  'src/views/user/card/modules/card-detail-drawer.vue',
  'src/views/user/card/modules/gift-issue-form.ts'
]) {
  check(rel, stripComments(read(rel)), /\b(Number|parseInt)\s*\(/, '范围/赠卡链出现数值转换（ID 契约破坏点）')
}
check(
  'src/views/user/card/index.vue',
  stripComments(read('src/views/user/card/index.vue')),
  /\b(Number|parseInt)\s*\(\s*(row|currentRow)\.id\b/,
  '卡页对身份 ID 做数值转换'
)
check(
  'src/views/user/card/index.vue',
  stripComments(read('src/views/user/card/index.vue')),
  /\b(Number|parseInt)\s*\(\s*(userId|form\.userId)\b/,
  '赠卡链对收卡用户 ID 做数值转换'
)

// 4) String(number) 守卫（R3-P1-2）：超界 number 在 JSON.parse 已丢精度，
//    未经 Number.isSafeInteger 检查的 String(value) 会把错 ID 固化成字符串。
//    纯函数层只允许 normalizeScopeId 这一处受检 String()，其余出现即回归。
const scopeFormSource = stripComments(read('src/views/user/card/modules/card-scope-form.ts'))
const helperMatch = scopeFormSource.match(
  /export function normalizeScopeId[\s\S]*?\n}/
)
if (!helperMatch) {
  violations.push(
    'src/views/user/card/modules/card-scope-form.ts 缺少 normalizeScopeId 归一唯一入口，门禁与源码结构脱节'
  )
} else {
  const helper = helperMatch[0]
  if (!/Number\.isSafeInteger\s*\(/.test(helper) || !/>\s*0/.test(helper)) {
    violations.push(
      'src/views/user/card/modules/card-scope-form.ts normalizeScopeId 缺失安全整数/正数守卫'
    )
  }
  const outsideHelper = scopeFormSource.replace(helper, '')
  check(
    'src/views/user/card/modules/card-scope-form.ts',
    outsideHelper,
    /\bString\s*\(/,
    'normalizeScopeId 之外出现 String()——ID 字符串化必须走受检唯一入口'
  )
}
// 抽屉：范围链主战场，禁止对一切 id 形参做 String()（字典标签等非身份值不受限）
check(
  'src/views/user/card/modules/card-detail-drawer.vue',
  stripComments(read('src/views/user/card/modules/card-detail-drawer.vue')),
  /\bString\s*\(\s*[\w.[\]]*(\.id\b|Id\b|Ids\b|Ids\[)/,
  '对身份 ID 做未受检 String() 转换'
)
// 卡页：只盯卡身份表达式（row/currentRow 的 id 与 cardId）。赠卡弹窗的
// String(form.userId ?? '') 是文本输入域规整，属礼包发放业务、不在授权范围链，
// 其 userId 精度问题另立整改切片处理——R3 边界禁止顺手扩改
check(
  'src/views/user/card/index.vue',
  stripComments(read('src/views/user/card/index.vue')),
  /\bString\s*\(\s*(row|currentRow)\.(id|cardId)\b|\bString\s*\(\s*[\w.[\]]*\bcardId\b/,
  '卡页对卡身份 ID 做未受检 String() 转换'
)

if (violations.length) {
  console.error('S4 身份 ID string 契约被破坏：')
  for (const v of violations) {
    console.error(`  - ${v}`)
  }
  process.exit(1)
}
console.log('check-card-scope-ids: 卡授权范围链路身份 ID string 契约完好')
