import type { AccountContext } from '@/api/account'

/**
 * 首页主态（2026-07-16 决策"一个账号，三张脸"）：
 * 视角只是 U01 的局部 UI 偏好，不是全局身份——Tabbar、我的、路由守卫、
 * 数据范围与审计均不随视角变化；安全边界始终在服务端数据范围。
 */
export type HomeFace = 'life' | 'courier' | 'owner' | 'channel' | 'region'

export const HOME_FACE_LABELS: Record<HomeFace, string> = {
  courier: '配送工作台',
  owner: '机主经营',
  channel: '渠道推广',
  region: '区域运营',
  life: '生活服务',
}

/** 账号可用的首页视角集合；生活态对所有账号可用。 */
export function availableHomeFaces(context: AccountContext | null): HomeFace[] {
  const faces: HomeFace[] = []
  if (context?.capabilities.includes('COURIER_WORK')) {
    faces.push('courier')
  }
  if (context?.capabilities.includes('OWNER_VIEW')) {
    faces.push('owner')
  }
  if (context?.capabilities.includes('CHANNEL_VIEW')) {
    faces.push('channel')
  }
  if (context?.capabilities.includes('REGION_VIEW')) {
    faces.push('region')
  }
  faces.push('life')
  return faces
}

export interface HomeFaceSignals {
  /** 本人进行中配送任务数（状态 2/3/4）。 */
  activeTaskCount: number
  /** 授权范围内离线/故障设备数。 */
  attentionDeviceCount: number
  /** 用户上次手动选择的视角（本地记忆），能力变化后可能失效。 */
  savedFace?: HomeFace | null
}

/**
 * 默认主态判定（决策口径）：
 * 记忆视角（仍可用时）＞ 进行中任务→配送 ＞ 待关注设备→经营 ＞ 单一工作能力 ＞ 生活。
 */
export function deriveDefaultHomeFace(
  context: AccountContext | null,
  signals: HomeFaceSignals,
): HomeFace {
  const faces = availableHomeFaces(context)
  if (signals.savedFace && faces.includes(signals.savedFace)) {
    return signals.savedFace
  }
  if (faces.includes('courier') && signals.activeTaskCount > 0) {
    return 'courier'
  }
  if (faces.includes('owner') && signals.attentionDeviceCount > 0) {
    return 'owner'
  }
  if (faces.includes('courier')) {
    return 'courier'
  }
  if (faces.includes('owner')) {
    return 'owner'
  }
  if (faces.includes('channel')) {
    return 'channel'
  }
  if (faces.includes('region')) {
    return 'region'
  }
  return 'life'
}
