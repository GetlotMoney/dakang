import type { ApiDomain } from '@/api/runtime'

/**
 * 页面顶部提示条的按域文案表。
 *
 * <p>这里只登记<b>用户不知道就会做错决定</b>的一句话。不写系统怎么实现、
 * 数据从哪来、哪个环节由谁记录——那些属于代码注释。</p>
 *
 * <p><b>2026-08-06 拆除 mock 分流</b>：本模块原本按构建期模式在 mock/real 两套文案间分流，
 * 但 mock 基建已于 2026-08-02 整体退役（适配器与场景库均已删除），且 {@code env/} 下三份
 * 环境档把每个业务域都置为 real，mock 分支在任何出货构建里都不可达。留着它的代价是实打实的：
 * 那套回落文案会对着一个根本跑不起来的构建说「这是演示数据」，比没有提示更糟。
 * 分流函数与场景函数一并删除，单页文案回到各自页面里。</p>
 */
const NOTICE_TEXTS: Partial<Record<ApiDomain, string>> = {
  // card 域这条不能少：成员授权保存后是真写库的，被授权人可以真刷卡扣余额。
  // 曾因缺登记而回落到「演示数据」口径，用户当演示随手授权过。
  card: '授权后对方可用你的卡取水，有单日限额。',
  delivery: '操作提交后无法撤销。',
  device: '报修提交后无法撤销。',
  message: '不会发微信通知，需在此查看。',
}

/**
 * 取该域的提示文案；未登记的域返回 undefined。
 *
 * <p>返回 undefined 而不是兜一句套话：没有值得说的事就什么都不显示。
 * 调用方（prototype-notice.vue）据此整条不渲染，不留空提示条。</p>
 */
export function noticeTextOf(domain: ApiDomain | undefined): string | undefined {
  return domain ? NOTICE_TEXTS[domain] : undefined
}
