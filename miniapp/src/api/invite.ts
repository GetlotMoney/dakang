import { post } from './request'

/**
 * 邀请归因（E2E-08 / 甲方③邀请码路径）。
 * 域开关复用 device（与机主/归因面同一 real/mock 粒度，避免新增第 9 段指纹——
 * 新增 ApiDomain 的连带成本见 runtime.ts 注释与两次历史事故）。
 * 微信分享链接/小程序码属 R-001 外部能力，本模块只做邀请码。
 */

export interface InviteApi {
  /** 本人邀请码（real：服务端惰性生成；mock：本地派生演示码） */
  myCode: () => Promise<string>
  /** 补绑推荐人（一次性；防自邀；已绑定明确拒绝） */
  bind: (inviteCode: string) => Promise<void>
}

export const inviteEndpoints = {
  myCode: '/mini/invite/my-code',
  bind: '/mini/invite/bind',
} as const

const realInviteApi: InviteApi = {
  async myCode() {
    return post<string>(inviteEndpoints.myCode, {})
  },
  async bind(inviteCode) {
    await post<boolean>(inviteEndpoints.bind, { inviteCode })
  },
}

export const inviteApi = realInviteApi
