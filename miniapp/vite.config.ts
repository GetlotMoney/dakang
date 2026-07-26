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
