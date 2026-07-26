import type { AccountContext } from '@/api/account'
import { describe, expect, it } from 'vitest'
import { availableHomeFaces, deriveDefaultHomeFace } from './home-face'

function contextWith(capabilities: AccountContext['capabilities']): AccountContext {
  return {
    accountId: 'A',
    userId: '1',
    userName: '测试',
    userPhone: '13800000000',
    capabilities,
  }
}

describe('home face derivation', () => {
  it('falls back to life for base users and missing context', () => {
    expect(availableHomeFaces(null)).toEqual(['life'])
    expect(
      deriveDefaultHomeFace(contextWith(['USER_BASE']), { activeTaskCount: 0, attentionDeviceCount: 0 }),
    ).toBe('life')
  })

  it('leads with the single work capability', () => {
    expect(
      deriveDefaultHomeFace(contextWith(['USER_BASE', 'COURIER_WORK']), { activeTaskCount: 0, attentionDeviceCount: 0 }),
    ).toBe('courier')
    expect(
      deriveDefaultHomeFace(contextWith(['USER_BASE', 'OWNER_VIEW']), { activeTaskCount: 0, attentionDeviceCount: 0 }),
    ).toBe('owner')
  })

  it('prioritises active tasks, then attention devices, for multi-capability accounts', () => {
    const both = contextWith(['USER_BASE', 'COURIER_WORK', 'OWNER_VIEW'])
    expect(deriveDefaultHomeFace(both, { activeTaskCount: 2, attentionDeviceCount: 1 })).toBe('courier')
    expect(deriveDefaultHomeFace(both, { activeTaskCount: 0, attentionDeviceCount: 1 })).toBe('owner')
    expect(deriveDefaultHomeFace(both, { activeTaskCount: 0, attentionDeviceCount: 0 })).toBe('courier')
  })

  it('honours a saved preference only while it is still available', () => {
    const both = contextWith(['USER_BASE', 'COURIER_WORK', 'OWNER_VIEW'])
    expect(
      deriveDefaultHomeFace(both, { activeTaskCount: 3, attentionDeviceCount: 0, savedFace: 'life' }),
    ).toBe('life')
    // 配送能力被停用后，记忆的配送视角失效，按剩余能力回退。
    const ownerOnly = contextWith(['USER_BASE', 'OWNER_VIEW'])
    expect(
      deriveDefaultHomeFace(ownerOnly, { activeTaskCount: 0, attentionDeviceCount: 0, savedFace: 'courier' }),
    ).toBe('owner')
  })
})
