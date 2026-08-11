import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('app cold launch', () => {
  it('handles the expected missing-session rejection inside the launch callback', () => {
    const appSource = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')

    expect(appSource).toMatch(/onLaunch\(async\s*\(\)\s*=>/)
    expect(appSource).toContain('await accountStore.restoreSession()')
    expect(appSource).toMatch(/catch\s*\{/)
  })
})
