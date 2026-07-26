import path from 'node:path'
import process from 'node:process'
import { defineManifestConfig } from '@uni-helper/vite-plugin-uni-manifest'
import { loadEnv } from 'vite'

function resolveMode() {
  const args = process.argv.slice(2)
  const modeIndex = args.findIndex(arg => arg === '--mode')
  if (modeIndex >= 0 && args[modeIndex + 1]) {
    return args[modeIndex + 1]
  }
  return args[0] === 'build' ? 'production' : 'development'
}

const env = loadEnv(resolveMode(), path.resolve(process.cwd(), 'env'))

export default defineManifestConfig({
  'name': env.VITE_APP_TITLE,
  'appid': env.VITE_UNI_APPID,
  'description': '六维达康共享健康水站小程序',
  'versionName': '0.1.0',
  'versionCode': '100',
  'transformPx': false,
  'h5': {
    router: {
      base: '/',
    },
  },
  'mp-weixin': {
    appid: env.VITE_WX_APPID,
    usingComponents: true,
    mergeVirtualHostAttributes: true,
    setting: {
      es6: true,
      minified: true,
      urlCheck: false,
    },
  },
  'vueVersion': '3',
})
