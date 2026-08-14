import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    include: ['src/**/*.test.ts'],
    // after-sale-permission.test.ts 是 node:test 写法的存量文件（无 vitest suite），
    // vitest 收集会报「No test suite found」；保持其原生态，本配置只跑 vitest 用例
    exclude: ['src/api/after-sale-permission.test.ts', 'node_modules/**']
  }
})
