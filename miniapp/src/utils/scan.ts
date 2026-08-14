import type { ScanSession } from '@/api/device'
import { deviceApi } from '@/api/device'
import { ContractError } from '@/api/common'

/** 用户主动取消（而非调用失败）的标记：调用方据此静默返回，不弹错误提示。 */
const USER_CANCELED = Symbol('scan-canceled')
type ScanOutcome = string | typeof USER_CANCELED

/** 平台回调 errMsg 含 cancel/取消 即用户主动放弃，其余一律按失败处理。 */
function isUserCancel(errMsg?: string): boolean {
  const msg = String(errMsg ?? '')
  return msg.includes('cancel') || msg.includes('取消')
}

/**
 * 真实扫码：调起微信扫一扫。onlyFromCamera=false 允许从相册选图（真机没有实体码时可扫码图）。
 * 取消与失败必须分开：取消是正常动线静默返回，失败要让用户看见，否则按钮表现为「点了没反应」。
 */
function scanRealCode(): Promise<ScanOutcome> {
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
 * 用户取消返回 null，其余失败一律抛 ContractError 由调用方原位提示；
 * 成功返回短期扫码会话，页面凭 scanSessionId 进入 U04。
 */
export async function scanWaterCode(): Promise<ScanSession | null> {
  // 恒走真实扫码；微信开发者工具会弹自带的模拟扫码窗，可粘贴码值联调
  const rawCode = await scanRealCode()
  if (rawCode === USER_CANCELED) {
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
