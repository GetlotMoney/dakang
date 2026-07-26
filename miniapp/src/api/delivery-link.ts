import type { OrderDetail } from './order'
import { ContractError } from './common'
import { scenarioStore } from '@/scenario/store'

/**
 * 任务—订单关联校验的中立模块（2026-07-18 第四轮审计整改）：
 * delivery.ts（履约动作）与 order.ts（用户创建申诉）共用同一套栅栏，
 * 双方都只在运行时依赖本模块，避免互相引用形成运行时循环。
 */

/** 任务侧参与共键校验的最小字段集（结构类型，不引入 delivery.ts 的运行时依赖）。 */
export interface DeliveryLinkKeys {
  taskNo: string
  orderNo: string
  orderId: string
  userId: string
}

export function orderDetailOfTask(task: DeliveryLinkKeys): OrderDetail | undefined {
  return scenarioStore.orderDetails.find(item => item.order.orderNo === task.orderNo)
}

/**
 * fail-closed 关联校验：订单必须存在，且 orderType===3、orderId/userId/deliveryTaskNo 与任务共键一致。
 * 订单状态语义：1 待支付、2 已支付待履约、3 出水中、4 已完成、5 已取消、6 异常待补偿、7 已退款、8 部分退款。
 */
export function requireLinkedDeliveryOrder(task: DeliveryLinkKeys): OrderDetail {
  const record = orderDetailOfTask(task)
  if (!record) {
    throw new ContractError('ORDER_MISSING', '配送任务缺少关联订单，已阻断该操作')
  }
  const order = record.order
  const linked
    = order.orderType === 3
      && order.orderId === task.orderId
      && order.userId === task.userId
      && record.deliveryTaskNo === task.taskNo
  if (!linked) {
    throw new ContractError('ORDER_LINK_MISMATCH', '配送任务与订单关联关系不一致，已阻断该操作')
  }
  return record
}

/**
 * fail-closed 履约校验：在关联校验之上额外要求 orderStatus 精确为 2（已支付待履约）；
 * 其余任何状态（1 待支付、3 出水中、4 已完成、5 已取消、6 异常待补偿、7 已退款、8 部分退款）
 * 一律不可入池、不可接单、不可推进，接真后由后端事务强制。
 */
export function requireFulfillableDeliveryOrder(task: DeliveryLinkKeys): OrderDetail {
  const record = requireLinkedDeliveryOrder(task)
  if (record.order.orderStatus !== 2) {
    throw new ContractError('ORDER_NOT_FULFILLABLE', '关联订单不在"已支付待履约"状态，不能接单或履约')
  }
  return record
}

/** 非抛错版本：供列表过滤使用，判定与 requireFulfillableDeliveryOrder 完全同源。 */
export function isFulfillableTask(task: DeliveryLinkKeys): boolean {
  try {
    requireFulfillableDeliveryOrder(task)
    return true
  }
  catch {
    return false
  }
}
