import type { AccountContext } from './account'
import { normalizeAccountContext } from './auth'
import { ContractError } from './common'
import { post } from './request'

/**
 * 自助资料（昵称/头像）适配层。
 *
 * 头像动线是微信 2022 年底废除授权弹窗后的唯一合规路径（资料填写能力）：
 * `chooseAvatar` 只给临时文件路径，必须读成 base64 交给服务端持久化，
 * 否则临时文件随会话回收、头像会悄悄失效。上传沿用全仓 POST+JSON 惯例。
 */

const UPDATE_ENDPOINT = '/mini/profile/update'

/** chooseAvatar 临时路径的扩展名 → MIME；微信头像产物基本是 jpeg，未识别时按 jpeg 兜底。 */
export function avatarMimeFromPath(filePath: string): string {
  const lower = String(filePath ?? '').toLowerCase()
  if (lower.endsWith('.png')) {
    return 'image/png'
  }
  if (lower.endsWith('.webp')) {
    return 'image/webp'
  }
  return 'image/jpeg'
}

/** 读临时文件为 base64（仅小程序端有文件系统能力；失败抛可提示的 ContractError）。 */
export function readFileAsBase64(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    try {
      uni.getFileSystemManager().readFile({
        filePath,
        encoding: 'base64',
        success: res => resolve(String(res.data)),
        fail: () => reject(new ContractError('PROFILE_AVATAR_READ_FAILED', '头像读取失败，请重新选择')),
      })
    }
    catch {
      reject(new ContractError('PROFILE_AVATAR_READ_FAILED', '当前环境不支持读取头像文件'))
    }
  })
}

export interface ProfileUpdatePayload {
  userName?: string
  avatarBase64?: string
  avatarMimeType?: string
}

export const profileApi = {
  /** 更新昵称/头像（至少其一），返回刷新后的账号上下文；不换发会话。 */
  async update(payload: ProfileUpdatePayload): Promise<AccountContext> {
    const raw = await post<Parameters<typeof normalizeAccountContext>[0]>(UPDATE_ENDPOINT, { ...payload })
    return normalizeAccountContext(raw)
  },
}
