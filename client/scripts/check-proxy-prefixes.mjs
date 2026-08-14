/**
 * 代理前缀一致性闸：client/vite.config.ts 的 server.proxy 键集合，必须与
 * deploy/nginx/nginx.conf 中反代到后端的正则 location 前缀集合完全一致。
 *
 * 背景：新接口前缀要在两处同步登记。E2E-07 的 /message 与 E2E-09 的 /mall 都曾
 * 只登记一处或两处全漏，直到主环境实测才暴露为 405 text/html——类型检查、构建、
 * 单测全都不会报错，只能靠这道静态闸把"双处同步铁律"变成常驻约束。
 *
 * 用法：node scripts/check-proxy-prefixes.mjs（非零退出即两处集合不一致）
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const clientDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const viteConfigPath = path.join(clientDir, 'vite.config.ts')
const nginxConfPath = path.resolve(clientDir, '../deploy/nginx/nginx.conf')

const viteSource = fs.readFileSync(viteConfigPath, 'utf8')
const nginxSource = fs.readFileSync(nginxConfPath, 'utf8')

// —— Vite 侧：只取 server.proxy 块内的键（截到 host: true 之前，避免误吞 alias 等）——
const proxyStart = viteSource.indexOf('proxy: {')
const proxyEnd = viteSource.indexOf('host: true', proxyStart)
if (proxyStart < 0 || proxyEnd < 0) {
  console.error('未在 vite.config.ts 中定位到 server.proxy 块，脚本与配置结构已脱节')
  process.exit(2)
}
const proxyBlock = viteSource.slice(proxyStart, proxyEnd)
const viteKeys = new Set([...proxyBlock.matchAll(/'\/([A-Za-z]+)':\s*\{/g)].map((m) => m[1]))

// —— Nginx 侧：解析所有 `location ~ ^/(a|b|c)(/|$)` 块并按行为分组 ——
// 混合组=既做 HTML 导航纠正（$is_html_navigation → 302 Hash URL）又反代后端；
// 纯接口组=只反代不纠正。兼有页面路由与接口的前缀（station/device/.../mall）必须在
// 混合组：R2-P1 实锤过 mall 错放纯接口组——前缀"存在"但直开 /mall/product 被整页
// 代理给后端。auth|dashboard 等纯页面块不反代后端，不参与集合；/dakangApi 是
// Knife4j 文档专用前缀路径 location，同样不参与。
const nginxKeys = new Set()
const nginxMixedKeys = new Set()
const nginxPureApiKeys = new Set()
const locationPattern = /location ~ \^\/\(([A-Za-z|]+)\)\(\/\|\$\) \{([\s\S]*?)\n    \}/g
for (const match of nginxSource.matchAll(locationPattern)) {
  const body = match[2]
  if (!body.includes('proxy_pass')) {
    continue
  }
  const target = body.includes('$is_html_navigation') ? nginxMixedKeys : nginxPureApiKeys
  for (const prefix of match[1].split('|')) {
    nginxKeys.add(prefix)
    target.add(prefix)
  }
}

// —— Vite dev 侧镜像：uiPathPattern 承担与 nginx 混合组同职（HTML 导航纠正）——
const uiPatternMatch = viteSource.match(/uiPathPattern\s*=\s*\n?\s*\/\^\\\/\(([A-Za-z|]+)\)/)
const uiPathKeys = new Set(uiPatternMatch ? uiPatternMatch[1].split('|') : [])

if (viteKeys.size === 0 || nginxKeys.size === 0) {
  console.error(
    `前缀解析结果为空（vite=${viteKeys.size}，nginx=${nginxKeys.size}），脚本与配置结构已脱节`
  )
  process.exit(2)
}

const missingInNginx = [...viteKeys].filter((key) => !nginxKeys.has(key))
const missingInVite = [...nginxKeys].filter((key) => !viteKeys.has(key))

let failed = false

if (missingInNginx.length > 0) {
  console.error(`以下前缀已在 vite.config.ts 登记，但 nginx.conf 未反代（主环境将 405）：`)
  for (const key of missingInNginx) console.error(`  - /${key}`)
  failed = true
}

if (missingInVite.length > 0) {
  console.error(`以下前缀已在 nginx.conf 反代，但 vite.config.ts 未代理（开发环境将 404）：`)
  for (const key of missingInVite) console.error(`  - /${key}`)
  failed = true
}

// E2E-09 R1-P0-1 的最低钉子：/mall 必须双处同时存在，防止解析口径漂移时静默放行。
for (const [name, keys] of [
  ['vite.config.ts', viteKeys],
  ['nginx.conf', nginxKeys]
]) {
  if (!keys.has('mall')) {
    console.error(`${name} 缺少商城前缀 /mall（E2E-09 R1-P0-1 固定整改项）`)
    failed = true
  }
}

// E2E-09 R2-P1：/mall 是页面/API 混合前缀，必须位于混合组、绝不允许出现在纯接口组
// （否则直开 http://…/mall/product 被整页代理给后端，而不是纠正为 /#/mall/product）。
if (!nginxMixedKeys.has('mall')) {
  console.error('nginx.conf：/mall 不在页面/API 混合前缀组（需 HTML 导航纠正 + 反代并存）')
  failed = true
}
if (nginxPureApiKeys.has('mall')) {
  console.error('nginx.conf：/mall 出现在纯接口组——混合前缀放错组（R2-P1 固定整改项）')
  failed = true
}
if (uiPathKeys.size === 0) {
  console.error('未解析到 vite uiPathPattern，脚本与配置结构已脱节')
  failed = true
} else if (!uiPathKeys.has('mall')) {
  console.error('vite.config.ts：uiPathPattern 缺少 mall（dev 直开 /mall/* 将被代理给后端）')
  failed = true
}

if (failed) {
  process.exit(1)
}

console.log(
  `代理前缀一致性通过：双处共 ${viteKeys.size} 个前缀（${[...viteKeys].sort().join(', ')}）`
)
