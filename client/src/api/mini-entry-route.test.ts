import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

describe('mini entry admin route contract', () => {
  it('uses the API proxy prefix instead of the PC system page prefix', () => {
    const source = readFileSync(
      fileURLToPath(new URL('./system-manage.ts', import.meta.url)),
      'utf8'
    )

    expect(source).not.toContain("'/system/miniEntry/")
    expect(source.match(/'\/api\/miniEntry\/(list|save|publish|retract)'/g)).toHaveLength(4)
  })
})
