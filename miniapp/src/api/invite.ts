import { post } from './request'

/**
 * 邀请归因（E2E-08 / 甲方③邀请码路径）。域复用 device，不新增 ApiDomain；
 * 微信分享链接/小程序码属 R-001 外部能力，本模块只做邀请码。
 */

export interface InviteApi {
  /** 本人邀请码（real：服务端惰性生成；mock：本地派生演示码） */
  myCode: () => Promise<string>
  /** 本人邀请小程序码的 scene（带签名与到期日）。不收入参：归属只从会话取。 */
  qrScene: () => Promise<string>
  /** 补绑推荐人（一次性；防自邀；已绑定明确拒绝） */
  bind: (inviteCode: string) => Promise<void>
}

export const inviteEndpoints = {
  myCode: '/mini/invite/my-code',
  qrScene: '/mini/invite/qr-scene',
  bind: '/mini/invite/bind',
} as const

const realInviteApi: InviteApi = {
  async qrScene() {
    return post<string>(inviteEndpoints.qrScene, {})
  },
  async myCode() {
    return post<string>(inviteEndpoints.myCode, {})
  },
  async bind(inviteCode) {
    await post<boolean>(inviteEndpoints.bind, { inviteCode })
  },
}

export const inviteApi = realInviteApi
