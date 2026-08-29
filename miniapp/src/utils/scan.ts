import type { ScanSession } from '@/api/device'
import { deviceApi } from '@/api/device'
import { ContractError } from '@/api/common'
import { demoScanCode, isDemoMode } from '@/api/runtime'

/** 用户主动取消（而非调用失败）的标记：调用方据此结束本次扫码。 */
const USER_CANCELED = Symbol('scan-canceled')
type ScanOutcome = string | typeof USER_CANCELED

/** 平台回调 errMsg 含 cancel/取消 即用户主动放弃，其余一律按失败处理。 */
function isUserCancel(errMsg?: string): boolean {
  const msg = String(errMsg ?? '')
  return msg.includes('cancel') || msg.includes('取消')
}

/**
 * 真实扫码：调起微信扫一扫。onlyFromCamera=false 允许从相册选图（真机没有实体码时可扫码图）。
 * 取消与失败必须分开；调用扫码前先提示正在打开，避免开发者工具弹窗迟滞时像没点到。
 */
function scanRealCode(): Promise<ScanOutcome> {
  uni.showToast({ title: '正在打开扫码', icon: 'none', duration: 1200 })
  if (isDemoMode()) {
    const code = demoScanCode()
    if (!code) {
      return Promise.reject(new ContractError('DEMO_SCAN_CODE_MISSING', '测试版扫码配置缺失，请联系运营'))
    }
    // 保留短暂识别过程，按钮反馈与真机调起相机的节奏一致；随后仍走真实后端扫码解析。
    return new Promise(resolve => setTimeout(() => resolve(code), 450))
  }
  return new Promise((resolve, reject) => {
    uni.scanCode({
      onlyFromCamera: false,
      scanType: ['qrCode', 'barCode'],
      success: (result) => {
        const raw = String(result.result ?? '').trim()
        if (!raw) {
          reject(new ContractError('SCAN_FAILED', '未识别到二维码内容，请对准设备上的取水码重试'))
          return
        }
        resolve(raw)
      },
      fail: (error) => {
        if (isUserCancel(error?.errMsg)) {
          resolve(USER_CANCELED)
          return
        }
        reject(new ContractError('SCAN_FAILED', `无法调起扫码：${error?.errMsg ?? '请检查相机权限后重试'}`))
      },
    })
  })
}

/**
 * U01 原位扫码入口（扫码不占独立路由）：
 * 用户取消给出轻提示并返回 null，其余失败一律抛 ContractError 由调用方原位提示；
 * 成功返回短期扫码会话，页面凭 scanSessionId 进入 U04。
 */
export async function scanWaterCode(): Promise<ScanSession | null> {
  // 恒走真实扫码；微信开发者工具会弹自带的模拟扫码窗，可粘贴码值联调
  const rawCode = await scanRealCode()
  if (rawCode === USER_CANCELED) {
    uni.showToast({ title: '已取消扫码', icon: 'none' })
    return null
  }
  try {
    return await deviceApi.resolveScanCode(rawCode)
  }
  catch (error) {
    if (error instanceof ContractError) {
      throw error
    }
    throw new ContractError('SCAN_FAILED', '扫码解析失败，请重试')
  }
}
