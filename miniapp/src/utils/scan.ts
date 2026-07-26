import type { ScanSession } from '@/api/device'
import { deviceApi } from '@/api/device'
import { apiMode } from '@/api/runtime'
import { ContractError } from '@/api/common'

/**
 * Mock 扫码适配器的固定样例码（蓝图 S03/S04）：
 * 覆盖可用、第二水种、离线故障、过期码与无效码五类入口，保证阻断分支可复现。
 * 原始二维码只在适配器内部消费，不进入路由、日志或可复制文本。
 */
const MOCK_SCAN_OPTIONS: { label: string, code: string }[] = [
  { label: '光谷1号机 · 1号口（纯净水，在线）', code: 'DK-QR-DEV0001-O1' },
  { label: '光谷1号机 · 2号口（矿物质水，在线）', code: 'DK-QR-DEV0001-O2' },
  { label: '南湖1号机（离线故障样例）', code: 'DK-QR-DEV0002-O1' },
  { label: '万能码（选择流程待定型）', code: 'DK-QR-UNIVERSAL-001' },
  { label: '已过期二维码（阻断样例）', code: 'DK-QR-EXPIRED' },
  { label: '无效二维码（阻断样例）', code: 'INVALID-CODE' },
]

function pickMockCode(): Promise<string | null> {
  return new Promise((resolve) => {
    uni.showActionSheet({
      alertText: '模拟扫码（开发适配器）',
      itemList: MOCK_SCAN_OPTIONS.map(item => item.label),
      success: result => resolve(MOCK_SCAN_OPTIONS[result.tapIndex]?.code ?? null),
      fail: () => resolve(null),
    })
  })
}

function scanRealCode(): Promise<string | null> {
  return new Promise((resolve) => {
    uni.scanCode({
      onlyFromCamera: false,
      success: result => resolve(result.result ?? null),
      fail: () => resolve(null),
    })
  })
}

/**
 * U01 原位扫码入口（扫码不占独立路由）：
 * 取消/未选择返回 null，解析失败抛 ContractError 由调用方原位提示；
 * 成功返回短期扫码会话，页面凭 scanSessionId 进入 U04。
 */
export async function scanWaterCode(): Promise<ScanSession | null> {
  // TODO(方案A真机)：真扫码接入时改按 device 域判定（不用全局 apiMode，否则全局翻 real 会牵连其它域）；
  // 当前全局 mock 下走样例选择器取码 + device real 适配器解析，是 L1a 方案 B 的 dev 桥接。
  const rawCode = apiMode === 'real' ? await scanRealCode() : await pickMockCode()
  if (rawCode === null) {
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
