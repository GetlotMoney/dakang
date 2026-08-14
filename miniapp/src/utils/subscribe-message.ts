/**
 * 微信订阅消息授权（WX-ECO S2）。授权必须由用户手势触发（运营规范 15.1.3.1），故不提供"进页面就申请"的入口；
 * 拒绝授权不阻断业务，返回值只用于提示。模板 ID 只存于后端环境配置，由调用方按需向后端取。
 */

/** 三态而不是布尔：「用户拒了」与「压根没弹出来」要分开处理。 */
export type SubscribeOutcome = 'accepted' | 'rejected' | 'unavailable'

/**
 * 申请订阅授权。必须在用户手势的同步调用栈里调用：放进 await 之后或定时器里会被微信判定为非用户触发而失败。
 * @param templateIds 模板 ID 列表；为空直接返回 unavailable，不发起申请
 */
export async function requestSubscribe(templateIds: string[]): Promise<SubscribeOutcome> {
  const ids = (templateIds || []).filter(id => typeof id === 'string' && id.length > 0)
  if (ids.length === 0) {
    // 空数组调 requestSubscribeMessage 会得到与真实原因无关的报错，不申请
    return 'unavailable'
  }
  try {
    // 返回体是「模板ID → 'accept'|'reject'|'ban'|'filter'」的动态键映射，按 Record 读取
    const res = await new Promise<Record<string, unknown>>((resolve, reject) => {
      uni.requestSubscribeMessage({
        tmplIds: ids,
        success: r => resolve(r as unknown as Record<string, unknown>),
        fail: reject,
      })
    })
    // 任一模板被接受即算接受：用户可能只勾了其中一个，那也是有效授权
    const accepted = ids.some(id => res[id] === 'accept')
    return accepted ? 'accepted' : 'rejected'
  }
  catch {
    // 弹窗没出来（未开通、被平台限制、非手势触发）归 unavailable，此时不该提示用户去设置里打开
    return 'unavailable'
  }
}
