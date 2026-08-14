import { defineConfig, loadEnv, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { fileURLToPath } from 'url'
import vueDevTools from 'vite-plugin-vue-devtools'
import viteCompression from 'vite-plugin-compression'
import Components from 'unplugin-vue-components/vite'
import AutoImport from 'unplugin-auto-import/vite'
import ElementPlus from 'unplugin-element-plus/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import tailwindcss from '@tailwindcss/vite'
// import { visualizer } from 'rollup-plugin-visualizer'

/**
 * PC 端使用 Hash 路由；业务页面路径又与后端代理前缀同名。
 * 浏览器误开 /device/index 这类无 # 地址时，先纠正为 /#/device/index，
 * 避免 HTML 导航被代理成后端接口。fetch/XHR（Accept 非 text/html）不受影响。
 */
function demoHashRouteRedirect(): Plugin {
  // mall 为二期商城混合前缀（页面 /mall/* + 接口 /mall/**），与 nginx.conf 混合组同步（R2-P1）
  const uiPathPattern =
    /^\/(auth|dashboard|station|device|product|user|order|mall|system|log)(?:\/|$)/

  return {
    name: 'dakang-demo-hash-route-redirect',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const requestUrl = req.url || '/'
        const pathname = requestUrl.split('?')[0]
        const acceptsHtml = String(req.headers.accept || '').includes('text/html')

        // 供人和自动化明确识别：13321 只用于源码热更新，不是 Demo 最终验收入口。
        res.setHeader('X-Dakang-Entry', 'vite-dev-only')
        res.setHeader('X-Dakang-Canonical-Entry', 'http://localhost:8081/')

        if (req.method === 'GET' && acceptsHtml && uiPathPattern.test(pathname)) {
          res.statusCode = 302
          res.setHeader('Location', `/#${requestUrl}`)
          res.end()
          return
        }

        next()
      })
    }
  }
}

export default ({ mode }: { mode: string }) => {
  const root = process.cwd()
  const env = loadEnv(mode, root)
  const { VITE_VERSION, VITE_PORT, VITE_BASE_URL, VITE_API_URL, VITE_API_PROXY_URL } = env

  console.log(`🚀 API_URL = ${VITE_API_URL}`)
  console.log(`🚀 VERSION = ${VITE_VERSION}`)

  return defineConfig({
    define: {
      __APP_VERSION__: JSON.stringify(VITE_VERSION)
    },
    base: VITE_BASE_URL,
    server: {
      port: Number(VITE_PORT),
      proxy: {
        // 系统底座（员工/RBAC/字典/日志/OSS/认证）
        '/api': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 一期业务前缀（后台 5+1）：水站 /station、设备中控 /device、水种套餐 /product、
        // 用户管理 /user、订单中心 /order；配送 /delivery 供订单中心配送 tab 与小程序端共用。
        // /finance 已随 E2E-08 启用（见下方条目）；商业一期启用运维时追加：/workorder，并同步 nginx.conf。
        '/station': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        '/device': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        '/product': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        '/user': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        '/order': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        '/delivery': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 小程序端接口前缀（L1a 起）：纯接口，不加入 uiPathPattern 的 HTML 导航纠正。
        '/mini': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 消息记录只读接口（E2E-07）：新增接口前缀必须与 nginx.conf 同步，漏配=主环境 405
        '/message': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 财务分账与对账（E2E-08）：同上双处同步铁律
        '/finance': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 运营总览聚合（2026-08-02 接真）：同上双处同步铁律
        '/ws': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        },
        // 二期商城（E2E-09）：同上双处同步铁律——漏配=主环境 405（R1-P0-1 实锤过）
        '/mall': {
          target: VITE_API_PROXY_URL,
          changeOrigin: true
        }
      },
      host: true
    },
    // 路径别名
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@views': resolvePath('src/views'),
        '@imgs': resolvePath('src/assets/images'),
        '@icons': resolvePath('src/assets/icons'),
        '@utils': resolvePath('src/utils'),
        '@stores': resolvePath('src/store'),
        '@styles': resolvePath('src/assets/styles')
      }
    },
    build: {
      target: 'es2015',
      outDir: 'dist',
      chunkSizeWarningLimit: 2000,
      minify: 'terser',
      terserOptions: {
        compress: {
          // 生产环境去除 console
          drop_console: true,
          // 生产环境去除 debugger
          drop_debugger: true
        }
      },
      dynamicImportVarsOptions: {
        warnOnError: true,
        exclude: [],
        include: ['src/views/**/*.vue']
      }
    },
    plugins: [
      demoHashRouteRedirect(),
      vue(),
      tailwindcss(),
      // 自动按需导入 API
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia', '@vueuse/core'],
        dts: 'src/types/import/auto-imports.d.ts',
        resolvers: [ElementPlusResolver()],
        eslintrc: {
          enabled: true,
          filepath: './.auto-import.json',
          globalsPropValue: true
        }
      }),
      // 自动按需导入组件
      Components({
        dts: 'src/types/import/components.d.ts',
        resolvers: [ElementPlusResolver()]
      }),
      // 按需定制主题配置
      ElementPlus({
        useSource: true
      }),
      // 压缩
      viteCompression({
        verbose: false, // 是否在控制台输出压缩结果
        disable: false, // 是否禁用
        algorithm: 'gzip', // 压缩算法
        ext: '.gz', // 压缩后的文件名后缀
        threshold: 10240, // 只有大小大于该值的资源会被处理 10240B = 10KB
        deleteOriginFile: false // 压缩后是否删除原文件
      }),
      vueDevTools()
      // 打包分析
      // visualizer({
      //   open: true,
      //   gzipSize: true,
      //   brotliSize: true,
      //   filename: 'dist/stats.html' // 分析图生成的文件名及路径
      // }),
    ],
    // 依赖预构建：避免运行时重复请求与转换，提升首次加载速度
    optimizeDeps: {
      include: [
        'echarts/core',
        'echarts/charts',
        'echarts/components',
        'echarts/renderers',
        'xlsx',
        'xgplayer',
        'crypto-js',
        'file-saver',
        'vue-img-cutter',
        'element-plus/es',
        'element-plus/es/components/*/style/css',
        'element-plus/es/components/*/style/index'
      ]
    },
    css: {
      preprocessorOptions: {
        // sass variable and mixin
        scss: {
          additionalData: `
            @use "@styles/core/el-light.scss" as *; 
            @use "@styles/core/mixin.scss" as *;
          `
        }
      },
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  })
}

function resolvePath(paths: string) {
  return path.resolve(__dirname, paths)
}
