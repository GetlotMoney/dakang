import path from 'node:path'
import process from 'node:process'
import { defineConfig } from 'vitest/config'

export default defineConfig({
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
