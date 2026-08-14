const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

/**
 * 小程序产物门禁：构建物里不得出现会在运行时必然报错的资源路径。
 *
 * 起因（2026-08-11 实测）：uni-app 在 initCreateApp 里注入一段打点——
 *   const protocol = "https";
 *   setTimeout(() => wx.preloadAssets({ data: [{ type: "image",
 *     src: protocol + "/<base64(项目名%%appid)>/img/shadow-grey.png" }] }), 3e3);
 * protocol 少了 "://"，拼出来是相对路径，微信解析成
 * `/pages/<当前页>/https/<…>/img/shadow-grey.png`，每次启动 3 秒后 Console 必报 HTTP 500。
 * 预加载的还是 H5 页头阴影素材，小程序端根本不渲染。
 *
 * 为什么门禁判据是「产物里没有坏路径」而不是「插件替换成功」：
 * 前者是真正要守住的事实，后者只是当前的实现手段。上游哪天自己修好了、
 * 或换一种注入写法，判据仍然成立/失效得当——而按「替换是否命中」判会在上游修复后误报，
 * 按「插件有没有跑」判则在上游换写法后漏报。
 *
 * 产物不存在时跳过而不是失败：本文件在 `pnpm test:e2e-gate` 里随常规门禁跑，
 * 而门禁不应强制每次都先构建一次小程序（分钟级）。构建后的核验由 WX 流程显式执行。
 */

const DIST = path.resolve(__dirname, '../dist/dev/mp-weixin')

/**
 * 打点入口本身。
 *
 * 坏路径 `https/<base64>/img/shadow-grey.png` 是**运行时**由 `protocol + "/…"` 拼出来的，
 * 产物里并不存在这个字面量——按「扫描坏路径字符串」写的判据会永远绿，什么都没测。
 * 唯一稳定可静态观测的锚点是 `wx.preloadAssets` 这个调用：它是该坏路径的唯一来源。
 * 上游若改用别的图名继续打点，下面第一条会漏，这条能抓住；两条互补。
 */
const BEACON_MARKER = 'preloadAssets'

function collectJsFiles(dir) {
  const out = []
  if (!fs.existsSync(dir)) {
    return out
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...collectJsFiles(full))
    }
    else if (entry.name.endsWith('.js') || entry.name.endsWith('.wxss')) {
      out.push(full)
    }
  }
  return out
}

test('小程序产物不含 shadow-grey 打点（它必然 500）', () => {
  if (!fs.existsSync(DIST)) {
    console.log('  跳过：dist/dev/mp-weixin 不存在，构建后再核验')
    return
  }
  const hits = []
  for (const file of collectJsFiles(DIST)) {
    const text = fs.readFileSync(file, 'utf8')
    if (text.includes('shadow-grey')) {
      hits.push(path.relative(DIST, file))
    }
  }
  assert.deepEqual(hits, [], `产物中仍有 shadow-grey 引用：${hits.join(', ')}`)
})

test('小程序产物走正式微信登录，且不含任何测试登录路径', () => {
  if (!fs.existsSync(DIST)) {
    console.log('  跳过：dist/dev/mp-weixin 不存在，构建后再核验')
    return
  }
  // 判据锚定**行为**，不锚定配置常量。
  //
  // 上一版断言的是 `authMode: "real"`——那是个可以被改的开关，而它恰恰就是漏洞本身：
  // 2026-08-12 实测到该开关取 mock 时整条正式登录不执行且不报错。
  // 现在整套按域切换的解析层已删除（正式登录是唯一路径），
  // 判据也随之改成「产物里确实调了 wx.login，且确实没有测试登录端点」——
  // 这两件事无论配置怎么变都必须成立。
  const files = collectJsFiles(DIST)
  const all = files.map(f => fs.readFileSync(f, 'utf8')).join('\n')

  assert.ok(/\blogin\s*\(/.test(all) && all.includes('/mini/auth/login'), '产物里找不到正式微信登录调用：入口可能又被换成了别的登录方式')

  const testLoginHits = files.filter(f => fs.readFileSync(f, 'utf8').includes('/mini/test-login'))
  assert.deepEqual(testLoginHits.map(f => path.relative(DIST, f)), [], '产物里仍有测试登录端点路径，正式登录验收不得携带它')
})

test('小程序产物不含 preloadAssets 打点调用', () => {
  if (!fs.existsSync(DIST)) {
    console.log('  跳过：dist/dev/mp-weixin 不存在，构建后再核验')
    return
  }
  const hits = []
  for (const file of collectJsFiles(DIST)) {
    if (fs.readFileSync(file, 'utf8').includes(BEACON_MARKER)) {
      hits.push(path.relative(DIST, file))
    }
  }
  assert.deepEqual(hits, [], `产物仍含 ${BEACON_MARKER} 打点：${hits.join(', ')}`)
})
