import { randomUUID } from 'node:crypto'
import path from 'node:path'
import process from 'node:process'
import Uni from '@uni-helper/plugin-uni'
import UniLayouts from '@uni-helper/vite-plugin-uni-layouts'
import UniManifest from '@uni-helper/vite-plugin-uni-manifest'
import UniPages from '@uni-helper/vite-plugin-uni-pages'
import UniKuRoot from '@uni-ku/root'
import { defineConfig, loadEnv } from 'vite'

process.env.UNI_INPUT_DIR ??= path.resolve(process.cwd(), 'src')

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, path.resolve(process.cwd(), 'env'))
  const buildFingerprint = randomUUID()

  return {
    envDir: './env',
    define: {
      __DAKANG_BUILD_FINGERPRINT__: JSON.stringify(buildFingerprint),
    },
    plugins: [
      {
        /**
         * 移除 uni-app 注入的 preloadAssets 打点。
         *
         * 框架在 initCreateApp 里塞了一段：
         *   const protocol = "https";
         *   setTimeout(() => wx.preloadAssets({ data:[{ type:"image",
         *     src: protocol + "/<base64(项目名%%appid)>/img/shadow-grey.png" }] }), 3e3);
         *
         * protocol 少了 "://"，拼出来是相对路径，微信把它解析成
         * `/pages/<当前页>/https/<…>/img/shadow-grey.png` —— 每次启动 3 秒后 Console 必报一条
         * HTTP 500。预加载的还是 H5 页头阴影素材（uni-page-head-shadow-grey），
         * 小程序端根本不渲染它，纯属无用打点，且把项目名与 AppID 编进了上报 URL。
         *
         * 这里只移除打点本身，不动任何视觉资源。真正要守住的不变量是「产物里不含该坏路径」，
         * 由 e2e/mp-artifact.test.js 独立看守 —— 所以此处不命中也不报错：
         * 上游哪天自己修好了，插件匹配不到属正常，门禁仍会通过；
         * 而若上游换了写法导致坏路径重新出现，门禁会红，不会被这里的静默掩盖。
         */
        name: 'dakang-strip-uni-preload-beacon',
        generateBundle(_options, bundle) {
          // 用括号配平删掉整个 `if (isFunction(wx.preloadAssets)) { … }` 块，而不是正则打补丁：
          // 压缩后的辅助函数名带哈希后缀（isFunction$1）且会随版本变，按名字写死的正则一升级就失效。
          // 留下空的 if 外壳也不行——判据是「产物里不含该调用」，外壳会让判据永远不成立。
          const strip = (code: string) => {
            let out = code
            for (;;) {
              const hit = out.indexOf('wx.preloadAssets')
              if (hit === -1) {
                return out
              }
              const ifStart = out.lastIndexOf('if', hit)
              const bodyStart = out.indexOf('{', hit)
              if (ifStart === -1 || bodyStart === -1) {
                return out
              }
              let depth = 0
              let bodyEnd = -1
              for (let i = bodyStart; i < out.length; i++) {
                if (out[i] === '{') {
                  depth++
                }
                else if (out[i] === '}') {
                  depth--
                  if (depth === 0) {
                    bodyEnd = i
                    break
                  }
                }
              }
              if (bodyEnd === -1) {
                return out
              }
              // 替换文案里不能出现被检测的那个标识符，否则门禁会击中这句注释本身
              out = `${out.slice(0, ifStart)}/* uni 资源预载打点已移除（其 URL 缺 :// 必报 500） */${out.slice(bodyEnd + 1)}`
            }
          }
          for (const file of Object.values(bundle)) {
            if (file.type === 'chunk' && file.code.includes('wx.preloadAssets')) {
              file.code = strip(file.code)
            }
          }
        },
      },
      {
        name: 'dakang-build-fingerprint',
        generateBundle() {
          this.emitFile({
            type: 'asset',
            fileName: 'build-fingerprint.json',
            source: `${JSON.stringify({ fingerprint: buildFingerprint })}\n`,
          })
        },
      },
      UniLayouts(),
      UniManifest(),
      UniPages({
        exclude: ['**/components/**'],
        dts: 'src/types/uni-pages.d.ts',
      }),
      UniKuRoot({
        excludePages: ['**/components/**'],
      }),
      Uni(),
    ],
    resolve: {
      alias: {
        '@': path.resolve(process.cwd(), 'src'),
      },
    },
    esbuild: {
      drop: env.VITE_DELETE_CONSOLE === 'true' ? ['console', 'debugger'] : [],
    },
    build: {
      minify: mode === 'development' ? false : 'esbuild',
      sourcemap: false,
      target: 'es6',
    },
    server: {
      host: '0.0.0.0',
      port: Number.parseInt(env.VITE_APP_PORT, 10),
    },
  }
})
