/**
 * 微信自定义导航的顶部安全区适配：
 * env(safe-area-inset-top) 只覆盖状态栏，不覆盖右上角胶囊——自定义头部若在首行
 * 放置右侧操作（消息铃/视角胶囊），必须按胶囊矩形避让，否则真机与开发者工具会重叠。
 */
export interface WxSafeHeader {
  /** 页面顶部内边距：mp-weixin 下与胶囊顶部对齐，其余平台回退 env() 方案。 */
  pageTopPadding: string
  /** 头部右侧需要避让的宽度（胶囊宽度 + 间距）；无胶囊平台为 0。 */
  capsuleAvoidWidth: string
}

export function getWxSafeHeader(): WxSafeHeader {
  try {
    const rect = uni.getMenuButtonBoundingClientRect?.()
    if (rect && rect.top > 0 && rect.width > 0) {
      const windowWidth = uni.getWindowInfo().windowWidth
      return {
        pageTopPadding: `${rect.top}px`,
        capsuleAvoidWidth: `${Math.max(windowWidth - rect.left + 8, 0)}px`,
      }
    }
  }
  catch {
    // H5 等平台无胶囊 API，走 env 兜底。
  }
  return {
    pageTopPadding: 'calc(env(safe-area-inset-top) + 20px)',
    capsuleAvoidWidth: '0px',
  }
}
