import path from 'node:path'
import process from 'node:process'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  // 与 vite.config.ts 同源读 env/：否则测试里 import.meta.env.VITE_* 全是 undefined，
  // 依赖基址的纯函数（如 resolveServerPath）在测试与构建产物中行为不一致。
  envDir: './env',
  resolve: {
    alias: {
      '@': path.resolve(process.cwd(), 'src'),
    },
  },
  test: {
    clearMocks: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
