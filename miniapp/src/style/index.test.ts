import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('wechat WXSS compatibility', () => {
  it('does not emit the unsupported universal child selector', () => {
    const styleSource = readFileSync(new URL('./index.scss', import.meta.url), 'utf8')

    expect(styleSource).not.toMatch(/>\s*\*/)
  })
})
