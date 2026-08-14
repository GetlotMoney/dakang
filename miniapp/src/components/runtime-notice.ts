import type { ApiDomain } from '@/api/runtime'

/**
 * 页面顶部提示条的按域文案表：只登记「用户不知道就会做错决定」的一句话。
 * mock 分流已拆除（mock 分支在任何出货构建里都不可达，回落文案会说假话）。
 */
const NOTICE_TEXTS: Partial<Record<ApiDomain, string>> = {
  // card 这条不能少：成员授权是真写库的，被授权人可以真刷卡扣余额
  card: '授权后对方可用你的卡取水。',
  device: '报修提交后无法撤销。',
  message: '不会发微信通知，需在此查看。',
}

/** 取该域的提示文案；未登记的域返回 undefined（调用方据此整条不渲染，不兜套话）。 */
export function noticeTextOf(domain: ApiDomain | undefined): string | undefined {
  return domain ? NOTICE_TEXTS[domain] : undefined
}
