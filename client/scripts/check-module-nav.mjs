/**
 * 页内导航条一致性闸：businessNavigation.ts 登记的每个页面都必须挂 BusinessModuleNav。
 *
 * 背景：PC 端业务模块页之间没有浏览器级返回按钮，模块内互跳全靠这条页内导航条。
 * 页面漏挂 = 用户进去后无路可退（只能改地址栏或走侧栏重进）。E2E-07 的消息记录页与
 * E2E-08 的分账明细/日对账/分账比例配置四页曾连续漏挂，直到用户实际点击才发现——
 * 类型检查与构建都不会报错，只能靠这道静态闸。
 *
 * 用法：node scripts/check-module-nav.mjs（非零退出即有页面漏挂）
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const clientDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const navConfigPath = path.join(clientDir, 'src/config/businessNavigation.ts')
const viewsDir = path.join(clientDir, 'src/views')

const source = fs.readFileSync(navConfigPath, 'utf8')
const navigationStart = source.indexOf('export const BUSINESS_MODULE_NAVIGATION')
const navigationSource = navigationStart >= 0 ? source.slice(navigationStart) : ''
const moduleHeaders = [...navigationSource.matchAll(/^  ([A-Za-z][A-Za-z0-9]*): \{$/gm)]
const routeOwners = new Map()
const duplicateOwners = []

for (let index = 0; index < moduleHeaders.length; index += 1) {
  const header = moduleHeaders[index]
  const moduleKey = header[1]
  const blockStart = header.index ?? 0
  const blockEnd = moduleHeaders[index + 1]?.index ?? navigationSource.length
  const block = navigationSource.slice(blockStart, blockEnd)
  for (const match of block.matchAll(/path:\s*'([^']+)'/g)) {
    const routePath = match[1]
    if (routeOwners.has(routePath)) {
      duplicateOwners.push(`${routePath}: ${routeOwners.get(routePath)} / ${moduleKey}`)
    } else {
      routeOwners.set(routePath, moduleKey)
    }
  }
}

const routePaths = [...routeOwners.keys()]

if (routePaths.length === 0) {
  console.error('未从 businessNavigation.ts 解析到任何 path，检查脚本与配置结构已脱节')
  process.exit(2)
}

const missing = []
const unresolved = []
const wrongModule = []

for (const routePath of routePaths) {
  const relative = routePath.replace(/^\//, '')
  const candidates = [
    path.join(viewsDir, relative, 'index.vue'),
    path.join(viewsDir, `${relative}.vue`)
  ]
  const file = candidates.find((candidate) => fs.existsSync(candidate))
  if (!file) {
    unresolved.push(routePath)
    continue
  }
  const pageSource = fs.readFileSync(file, 'utf8')
  if (!pageSource.includes('BusinessModuleNav')) {
    missing.push(`${routePath}  →  ${path.relative(clientDir, file)}`)
    continue
  }
  const expectedModule = routeOwners.get(routePath)
  if (!pageSource.includes(`module-key="${expectedModule}"`)) {
    wrongModule.push(`${routePath} 应属于 ${expectedModule}  →  ${path.relative(clientDir, file)}`)
  }
}

if (unresolved.length) {
  console.error(`以下登记路径找不到视图文件（路由与视图脱节）：\n  ${unresolved.join('\n  ')}`)
}
if (missing.length) {
  console.error(`以下页面未挂 BusinessModuleNav，用户进入后无返回路径：\n  ${missing.join('\n  ')}`)
}
if (wrongModule.length) {
  console.error(`以下页面挂到了错误的职责工作区：\n  ${wrongModule.join('\n  ')}`)
}
if (duplicateOwners.length) {
  console.error(`以下页面被多个职责工作区重复认领：\n  ${duplicateOwners.join('\n  ')}`)
}
if (unresolved.length || missing.length || wrongModule.length || duplicateOwners.length) {
  process.exit(1)
}

console.log(
  `页内导航条一致性通过：${routePaths.length} 个登记页面均挂载且归属唯一的 BusinessModuleNav`
)
