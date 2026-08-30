import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { scanWaterCode } from './scan'

const resolveScanCode = vi.hoisted(() => vi.fn())
vi.mock('@/api/device', () => ({ deviceApi: { resolveScanCode } }))

interface ScanOptions {
  success: (result: { result?: string }) => void
  fail: (error: { errMsg?: string }) => void
}

const scanCode = vi.fn()
const showToast = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as Record<string, unknown>).uni = { scanCode, showToast }
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllEnvs()
})

describe('扫码按钮反馈', () => {
  it('调起前先提示，成功后把码值交给服务端解析', async () => {
    scanCode.mockImplementation((options: ScanOptions) => options.success({ result: 'DEVICE-QR-001' }))
    resolveScanCode.mockResolvedValueOnce({
      scanSessionId: 'SCAN-1',
      deviceNo: 'DK-001',
      outletId: '1',
      expiresAt: '20260826120000',
    })

    await expect(scanWaterCode()).resolves.toMatchObject({ scanSessionId: 'SCAN-1' })
    expect(showToast).toHaveBeenCalledWith({ title: '正在打开扫码', icon: 'none', duration: 1200 })
    expect(resolveScanCode).toHaveBeenCalledWith('DEVICE-QR-001')
  })

  it('用户取消时明确提示而不是静默无反应', async () => {
    scanCode.mockImplementation((options: ScanOptions) => options.fail({ errMsg: 'scanCode:fail cancel' }))

    await expect(scanWaterCode()).resolves.toBeNull()
    expect(showToast).toHaveBeenNthCalledWith(1, { title: '正在打开扫码', icon: 'none', duration: 1200 })
    expect(showToast).toHaveBeenNthCalledWith(2, { title: '已取消扫码', icon: 'none' })
  })

  it('平台扫码调用失败时返回能直接展示的原因', async () => {
    scanCode.mockImplementation((options: ScanOptions) => options.fail({ errMsg: 'scanCode:fail auth deny' }))

    await expect(scanWaterCode()).rejects.toThrow('无法调起扫码')
  })

  it('老板测试包跳过相机但仍把固定码交给真实扫码解析接口', async () => {
    vi.stubEnv('VITE_DEMO_MODE', 'true')
    vi.stubEnv('VITE_DEMO_SCAN_CODE', 'DK-QR-DEV0001-O1')
    vi.useFakeTimers()
    resolveScanCode.mockResolvedValueOnce({
      scanSessionId: 'SCAN-DEMO',
      deviceNo: 'DK-DEV-0001',
      outletId: '1',
      expiresAt: '20260826120000',
    })

    const pending = scanWaterCode()
    await vi.advanceTimersByTimeAsync(450)

    await expect(pending).resolves.toMatchObject({ scanSessionId: 'SCAN-DEMO' })
    expect(scanCode).not.toHaveBeenCalled()
    expect(resolveScanCode).toHaveBeenCalledWith('DK-QR-DEV0001-O1')
  })
})
