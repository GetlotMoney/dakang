import assert from 'node:assert/strict'
import test from 'node:test'
import * as afterSale from './after-sale-entry'

const pendingEntry = (actionType: number) => {
  const entry = afterSale.afterSaleExecuteEntry({
    actionType,
    actionStatus: afterSale.AfterSaleActionStatus.PENDING
  })
  assert.ok(entry)
  return entry
}

test('binds gateway refunds to the dedicated refund permission', () => {
  const entry = pendingEntry(afterSale.AfterSaleActionType.GATEWAY_REFUND)

  assert.equal(entry.mode, 'refund')
  assert.equal(entry.requiredPermission, afterSale.AfterSalePerms.refund)
  assert.equal(
    afterSale.canUseAfterSaleExecuteEntry(
      entry,
      (permission) => permission === afterSale.AfterSalePerms.handle
    ),
    false
  )
  assert.equal(
    afterSale.canUseAfterSaleExecuteEntry(
      entry,
      (permission) => permission === afterSale.AfterSalePerms.refund
    ),
    true
  )
})

test('keeps card returns and resend actions on the handle permission', () => {
  for (const actionType of [
    afterSale.AfterSaleActionType.CARD_REFUND,
    afterSale.AfterSaleActionType.RESEND
  ]) {
    const entry = pendingEntry(actionType)

    assert.equal(entry.requiredPermission, afterSale.AfterSalePerms.handle)
    assert.equal(
      afterSale.canUseAfterSaleExecuteEntry(
        entry,
        (permission) => permission === afterSale.AfterSalePerms.handle
      ),
      true
    )
    assert.equal(
      afterSale.canUseAfterSaleExecuteEntry(
        entry,
        (permission) => permission === afterSale.AfterSalePerms.refund
      ),
      false
    )
  }
})

test('exposes distinct recharge refund modes only for completed and unsettled recharge orders', () => {
  assert.equal(afterSale.rechargeRefundEntryMode({ orderType: 2, orderStatus: 4 }), 'entitlement')
  assert.equal(afterSale.rechargeRefundEntryMode({ orderType: 2, orderStatus: 6 }), 'unsettled')
  assert.equal(afterSale.rechargeRefundEntryMode({ orderType: 2, orderStatus: 7 }), null)
  assert.equal(afterSale.rechargeRefundEntryMode({ orderType: 1, orderStatus: 6 }), null)
})
