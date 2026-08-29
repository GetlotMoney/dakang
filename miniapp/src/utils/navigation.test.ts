import { beforeEach, describe, expect, it, vi } from 'vitest'
import { backOr, goTo, reLaunchTo, switchToTab } from './navigation'

interface NavigationOptions {
  url: string
  fail?: () => void
}

const navigateTo = vi.fn()
const redirectTo = vi.fn()
const switchTab = vi.fn()
const reLaunch = vi.fn()
const navigateBack = vi.fn()
const showToast = vi.fn()

beforeEach(() => {
  vi.resetAllMocks()
  ;(globalThis as Record<string, unknown>).uni = {
    navigateTo,
    redirectTo,
    switchTab,
    reLaunch,
    navigateBack,
    showToast,
  }
  ;(globalThis as Record<string, unknown>).getCurrentPages = () => []
})

describe('页面跳转反馈', () => {
  it('普通页面打开失败时提示用户重试', () => {
    navigateTo.mockImplementation((options: NavigationOptions) => options.fail?.())

    goTo('M01')

    expect(navigateTo).toHaveBeenCalledWith(expect.objectContaining({ url: '/pages/mall/index' }))
    expect(showToast).toHaveBeenCalledWith({ title: '页面打开失败，请重试', icon: 'none' })
  })

  it('tab 切换和重新启动失败都有明确提示', () => {
    switchTab.mockImplementation((options: NavigationOptions) => options.fail?.())
    reLaunch.mockImplementation((options: NavigationOptions) => options.fail?.())

    switchToTab('U02')
    reLaunchTo('C01')

    expect(showToast).toHaveBeenNthCalledWith(1, { title: '页面切换失败，请重试', icon: 'none' })
    expect(showToast).toHaveBeenNthCalledWith(2, { title: '页面打开失败，请重试', icon: 'none' })
  })

  it('返回栈异常时落回合同兜底页', () => {
    ;(globalThis as Record<string, unknown>).getCurrentPages = () => [{ route: 'detail' }, { route: 'detail-2' }]
    navigateBack.mockImplementation((options: { fail?: () => void }) => options.fail?.())

    backOr('U03')

    expect(switchTab).toHaveBeenCalledWith(expect.objectContaining({ url: '/pages/user/profile/index' }))
    expect(showToast).not.toHaveBeenCalled()
  })
})
