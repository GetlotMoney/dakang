#!/usr/bin/env node
/**
 * 接口存在性门禁：三端前端调用的每一条接口路径，必须真的存在于后端控制器里。
 *
 * 为什么需要它：路径写错或后端改名时，vue-tsc、ESLint、构建、单测、check-proxy-prefixes
 * 全都是绿的——只有在真环境点到那个按钮才会 404/405。这个类别的缺陷本项目已经撞过两次
 * （/mall 前缀漏配、/mini/catalog/package/list 端点根本不存在），两次都是靠人点出来的。
 *
 * 判据两条：
 *   1) 路径存在性：被扫描文件里所有形如接口路径的字符串字面量，必须命中后端端点集合；
 *   2) 扫描面完整性：request.post/get 与 api 层的 post() 只允许出现在被扫描的文件里。
 *      第 2 条是第 1 条的前提——放任别处新开调用点，第 1 条就会漏扫而依然全绿。
 *
 * 刻意不启动应用、不读 openapi.json：门禁要能在任何一台干净机器上离线跑完。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import process from 'node:process'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '../..')
const SERVER_CONTROLLERS = join(REPO, 'server/src/main/java/com/jbk/serve/controller')

/** 被扫描的前端文件（接口路径只允许在这些地方声明）。 */
const SCAN_ROOTS = [
  { dir: join(REPO, 'client/src/api'), label: 'client/src/api' },
  { dir: join(REPO, 'miniapp/src/api'), label: 'miniapp/src/api' },
]
/** 例外文件：历史上把接口路径写在 api 层之外的地方，逐个登记而不是整目录放行。 */
const SCAN_EXTRA_FILES = [join(REPO, 'client/src/utils/dict.ts')]

/**
 * 允许出现 request.post/get 的文件（相对仓库根）。
 *
 * art-wang-editor 是 art-design-pro 模板自带的富文本组件，上传地址来自组件 props
 * （`props.uploadConfig?.server`），不是本项目的后端端点；它随模板一起进来，本项目未接入
 * 上传能力。登记在此而不是整目录放行，新增调用点仍会被门禁挡下。
 */
const REQUEST_CALL_ALLOWLIST = new Set([
  'client/src/api',
  'client/src/utils/dict.ts',
  'client/src/utils/http/index.ts',
  'client/src/components/core/forms/art-wang-editor/index.vue',
  'miniapp/src/api',
])

/**
 * 已知的非字面量或后端不由控制器注解暴露的路径。
 *
 * 每条都必须写清为什么它无法被静态匹配——空着的白名单条目等于把门禁关掉一格。
 */
const PATH_ALLOWLIST = new Map([
  ['/mini/profile/avatar/', '头像读取按前缀拼文件名（MiniProfileController 的 {fileName} 路径变量），不是 post 调用'],
])

function walk(dir, out = []) {
  let entries
  try {
    entries = readdirSync(dir)
  }
  catch {
    return out
  }
  for (const name of entries) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === 'dist' || name === '.git') {
        continue
      }
      walk(full, out)
    }
    else {
      out.push(full)
    }
  }
  return out
}

/** 去掉注释与字符串外的干扰；注释里的示例路径不该被当成真实调用（本项目踩过）。 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

// ---------------------------------------------------------------------------
// 后端端点集合
// ---------------------------------------------------------------------------

/** Spring 的拼接语义：段间恰一个斜杠，缺前导斜杠也照样拼（本仓有这种写法）。 */
function joinPath(base, sub) {
  const b = (base || '').replace(/\/+$/, '')
  const s = (sub || '').replace(/^\/+/, '')
  if (!s) {
    return b || '/'
  }
  return `${b}/${s}`
}

function collectBackendEndpoints() {
  const endpoints = new Set()
  for (const file of walk(SERVER_CONTROLLERS)) {
    if (!file.endsWith('.java')) {
      continue
    }
    const src = stripComments(readFileSync(file, 'utf8'))
    const classMapping = src.match(/@RequestMapping\(\s*"([^"]*)"\s*\)/)
    const base = classMapping ? classMapping[1] : ''
    const methodRe = /@(?:Post|Get|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)/g
    let m = methodRe.exec(src)
    let found = 0
    while (m) {
      endpoints.add(joinPath(base, m[1]))
      found += 1
      m = methodRe.exec(src)
    }
    // 无参映射（@PostMapping 直接落在类路径上）
    const bareRe = /@(?:Post|Get|Put|Delete|Patch)Mapping(?![\s\S]{0,4}\()/g
    if (base && bareRe.test(src)) {
      endpoints.add(base)
    }
    if (!found && !base) {
      continue
    }
  }
  return endpoints
}

/** 路径变量段归一成通配，便于与前端拼接出的具体路径比对。 */
function toMatcher(endpoint) {
  if (!endpoint.includes('{')) {
    return null
  }
  return new RegExp(`^${endpoint.replace(/\{[^}]*\}/g, '[^/]+')}$`)
}

// ---------------------------------------------------------------------------
// 前端调用面
// ---------------------------------------------------------------------------

function scanFrontendPaths(prefixes) {
  const files = []
  for (const root of SCAN_ROOTS) {
    for (const f of walk(root.dir)) {
      if (/\.(?:ts|js|vue)$/.test(f) && !/\.test\.ts$/.test(f)) {
        files.push(f)
      }
    }
  }
  files.push(...SCAN_EXTRA_FILES)

  const hits = []
  for (const file of files) {
    const src = stripComments(readFileSync(file, 'utf8'))
    const re = /['"`](\/[A-Za-z0-9_\-/{}.]*)['"`]/g
    let m = re.exec(src)
    while (m) {
      const path = m[1]
      const head = path.split('/')[1]
      if (prefixes.has(head)) {
        const line = src.slice(0, m.index).split('\n').length
        hits.push({ file: relative(REPO, file), line, path })
      }
      m = re.exec(src)
    }
  }
  return hits
}

function scanStrayRequestCalls() {
  const roots = [join(REPO, 'client/src'), join(REPO, 'miniapp/src')]
  const stray = []
  for (const root of roots) {
    for (const file of walk(root)) {
      if (!/\.(?:ts|js|vue)$/.test(file) || /\.test\.ts$/.test(file)) {
        continue
      }
      const rel = relative(REPO, file)
      const allowed = [...REQUEST_CALL_ALLOWLIST].some(
        entry => rel === entry || rel.startsWith(`${entry}/`),
      )
      if (allowed) {
        continue
      }
      const src = stripComments(readFileSync(file, 'utf8'))
      if (/\brequest\s*\.\s*(?:post|get|put|del|delete)\s*[<(]/.test(src)) {
        stray.push(rel)
      }
    }
  }
  return stray
}

// ---------------------------------------------------------------------------

const endpoints = collectBackendEndpoints()
if (endpoints.size < 100) {
  console.error(`接口存在性门禁自检失败：只解析到 ${endpoints.size} 个后端端点，扫描口径可能已失效`)
  process.exit(2)
}
const matchers = [...endpoints].map(toMatcher).filter(Boolean)
const prefixes = new Set([...endpoints].map(e => e.split('/')[1]).filter(Boolean))

const failures = []

for (const hit of scanFrontendPaths(prefixes)) {
  if (endpoints.has(hit.path) || matchers.some(re => re.test(hit.path))) {
    continue
  }
  const allowed = [...PATH_ALLOWLIST.keys()].some(p => hit.path.startsWith(p))
  if (allowed) {
    continue
  }
  failures.push(`${hit.file}:${hit.line} 调用了后端不存在的接口路径 ${hit.path}`)
}

const stray = scanStrayRequestCalls()
for (const file of stray) {
  failures.push(`${file} 在 api 层之外直接发请求；请移入 api 层，否则本门禁扫不到它的路径`)
}

if (failures.length) {
  console.error('接口存在性门禁未通过：')
  for (const f of failures) {
    console.error(`  - ${f}`)
  }
  console.error('\n若某条路径确实无法静态匹配（路径变量拼接等），在 PATH_ALLOWLIST 里登记并写明原因。')
  process.exit(1)
}

console.log(
  `接口存在性门禁通过：后端 ${endpoints.size} 个端点，`
  + `前缀 ${[...prefixes].sort().join(', ')}；三端调用路径全部命中。`,
)
