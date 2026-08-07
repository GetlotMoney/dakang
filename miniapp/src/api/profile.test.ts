import { describe, expect, it, vi } from 'vitest'
import { avatarMimeFromPath, profileApi } from './profile'
import * as request from './request'

vi.mock('./request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./request')>()
  return { ...actual, post: vi.fn() }
})

const post = vi.mocked(request.post)

describe('avatarMimeFromPath', () => {
  it('按扩展名映射，未识别回落 jpeg（chooseAvatar 产物基本是 jpeg）', () => {
    expect(avatarMimeFromPath('wxfile://tmp/a.png')).toBe('image/png')
    expect(avatarMimeFromPath('wxfile://tmp/a.WEBP')).toBe('image/webp')
    expect(avatarMimeFromPath('wxfile://tmp/a.jpeg')).toBe('image/jpeg')
    expect(avatarMimeFromPath('wxfile://tmp/noext')).toBe('image/jpeg')
  })
})

describe('resolveServerPath', () => {
  it('相对路径拼基址；完整地址原样；空值回空串（回落占位图标）', () => {
    const full = request.resolveServerPath('/mini/profile/avatar/AVX.jpg')
    expect(full.endsWith('/mini/profile/avatar/AVX.jpg')).toBe(true)
    expect(full.startsWith('http')).toBe(true)
    expect(request.resolveServerPath('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png')
    expect(request.resolveServerPath(undefined)).toBe('')
  })
})

describe('profileApi.update', () => {
  it('返回的裸上下文经统一规范化（含 userAvatar 与 phoneBound 判定）', async () => {
    post.mockResolvedValueOnce({
      accountId: '6',
      userId: '6',
      userName: '微信用户6',
      userAvatar: '/mini/profile/avatar/AVABC.jpg',
      capabilities: ['USER_BASE'],
    })

    const ctx = await profileApi.update({ userName: '微信用户6' })

    expect(post).toHaveBeenCalledWith('/mini/profile/update', { userName: '微信用户6' })
    expect(ctx.userAvatar).toBe('/mini/profile/avatar/AVABC.jpg')
    // 仅微信身份建号的账号改昵称后仍未绑号：phoneBound 必须保持 false，补绑入口不能因此消失
    expect(ctx.phoneBound).toBe(false)
  })

  it('上下文非法（缺 userId）→ 契约破坏，不静默放行', async () => {
    post.mockResolvedValueOnce({ accountId: '6', userName: 'x' })
    await expect(profileApi.update({ userName: 'x' })).rejects.toThrow()
  })
})
