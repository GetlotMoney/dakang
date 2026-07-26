import { reactive } from 'vue'

/**
 * U07（selectMode=delivery）与 U14（returnTo=delivery）选择结果回填 U08 的页面间草稿。
 * 仅存在于导航过程，不属于业务数据，也不进入 Scenario Store；U08 消费后即清空。
 */
const draft = reactive<{ stationId?: string, addressId?: string }>({})

export function setDraftStation(stationId: string) {
  draft.stationId = stationId
}

export function setDraftAddress(addressId: string) {
  draft.addressId = addressId
}

/** U08 onShow 消费草稿；返回后立即清空，避免旧选择污染下次下单。 */
export function consumeDeliveryDraft(): { stationId?: string, addressId?: string } {
  const value = { stationId: draft.stationId, addressId: draft.addressId }
  draft.stationId = undefined
  draft.addressId = undefined
  return value
}
