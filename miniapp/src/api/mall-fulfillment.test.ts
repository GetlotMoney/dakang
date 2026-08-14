import { describe, expect, it } from 'vitest'
import { ContractError } from './common'
import { normalizeFulfill, normalizeShipment } from './mall-fulfillment'

/**
 * 商城履约与包裹契约层（E2E-09 L1）。
 *
 * 这里只测**判据本身**：渠道与包裹状态一旦读到白名单外的值，必须炸而不是猜。
 * 猜的代价很具体——把未知渠道渲染成「自营配送」，用户会一直等一个不存在的配送员上门。
 */

function fulfillRow(patch: Record<string, unknown> = {}) {
  return {
    orderNo: 'MO2026081100001',
    fulfillStatus: 4,
    fulfillStatusName: '待承运方揽收',
    fulfillMode: 2,
    fulfillModeName: '第三方物流',
    warehouseId: '9931',
    receiverName: '张三',
    receiverPhone: '138****0001',
    receiverRegion: '湖北省武汉市洪山区',
    receiverAddress: '光谷大道 1 号',
    timeline: [],
    ...patch,
  }
}

function shipmentRow(patch: Record<string, unknown> = {}) {
  return {
    shipmentId: '900001',
    orderNo: 'MO2026081100001',
    direction: 1,
    directionName: '正向发货',
    fulfillMode: 2,
    fulfillModeName: '第三方物流',
    providerCode: 'SIM',
    waybillNo: 'SIMWB0123456789ABCD',
    shipmentStatus: 4,
    shipmentStatusName: '运输中',
    lines: [],
    logisticsTraces: [],
    ...patch,
  }
}

describe('normalizeFulfill 渠道字段', () => {
  it('保留后端下发的渠道值与名称', () => {
    const result = normalizeFulfill(fulfillRow())
    expect(result.fulfillMode).toBe(2)
    expect(result.fulfillModeName).toBe('第三方物流')
  })

  it('渠道缺失按未定，不阻断整单展示', () => {
    // 老数据没有这一列，与「还没选渠道」在端上是同一件事
    expect(normalizeFulfill(fulfillRow({ fulfillMode: undefined })).fulfillMode).toBe(0)
    expect(normalizeFulfill(fulfillRow({ fulfillMode: null })).fulfillMode).toBe(0)
  })

  it('渠道越界必须抛错而不是回落成自营', () => {
    // 数字字符串按全局口径接受（后端 Long 恒 string，判据是值域而不是 JS 类型）；
    // 值域之外一律抛错——把未知渠道渲染成自营，用户会一直等一个不存在的配送员上门
    expect(normalizeFulfill(fulfillRow({ fulfillMode: '2' })).fulfillMode).toBe(2)
    expect(() => normalizeFulfill(fulfillRow({ fulfillMode: 3 }))).toThrow(ContractError)
    expect(() => normalizeFulfill(fulfillRow({ fulfillMode: '3' }))).toThrow(ContractError)
    expect(() => normalizeFulfill(fulfillRow({ fulfillMode: -1 }))).toThrow(ContractError)
  })
})

describe('normalizeShipment', () => {
  it('包裹与承运方轨迹按原样透出', () => {
    const result = normalizeShipment(
      shipmentRow({
        logisticsTraces: [
          { eventState: 'IN_TRANSIT', eventStateName: '运输中', eventTime: '20260811103000', eventDesc: '已到达武汉转运中心' },
        ],
      }),
    )
    expect(result.waybillNo).toBe('SIMWB0123456789ABCD')
    expect(result.logisticsTraces).toHaveLength(1)
    expect(result.logisticsTraces[0].eventStateName).toBe('运输中')
  })

  it('运单号未取得时为 undefined，由页面显示「待承运方受理」', () => {
    // 空串不得变成 ''：页面用 falsy 判断，但 undefined 才是「还没有」的诚实表示
    expect(normalizeShipment(shipmentRow({ waybillNo: '' })).waybillNo).toBeUndefined()
  })

  it('后端真实可达的八个包裹状态全部接受', () => {
    // 这条用例此前把 6 写成「必抛」，等于把一个错误白名单锁成了期望。
    // 8 异常待人工是承运方 EXCEPTION 事件的落点（L1⑩ 已证明可达），漏掉它时
    // normalizeShipment 抛错、订单详情连坐把「确认收货」一起吞掉，订单再也走不完。
    for (const status of [1, 2, 3, 4, 5, 6, 7, 8]) {
      expect(normalizeShipment(shipmentRow({ shipmentStatus: status })).shipmentStatus).toBe(status)
    }
  })

  it('包裹状态越界必须抛错', () => {
    expect(() => normalizeShipment(shipmentRow({ shipmentStatus: 0 }))).toThrow(ContractError)
    expect(() => normalizeShipment(shipmentRow({ shipmentStatus: 9 }))).toThrow(ContractError)
  })

  it('包裹渠道越界必须抛错', () => {
    expect(() => normalizeShipment(shipmentRow({ fulfillMode: 9 }))).toThrow(ContractError)
  })

  it('轨迹里的非对象条目被丢弃而不是崩溃', () => {
    const result = normalizeShipment(shipmentRow({ logisticsTraces: [null, 'x', { eventState: 'CREATED' }] }))
    expect(result.logisticsTraces).toHaveLength(1)
  })
})
