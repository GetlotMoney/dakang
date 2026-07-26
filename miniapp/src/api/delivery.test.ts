import { beforeEach, describe, expect, it } from 'vitest'
import { accountApi } from './account'
import { addSecondsToBusinessTime, cloneContractData } from './common'
import { deliveryApi } from './delivery'
import { deviceApi } from './device'
import { orderApi } from './order'
import { scenarioStore } from '@/scenario/store'

/** 对抗夹具：克隆 DT-2008 造未支付订单任务与孤儿任务（2026-07-18 审计整改常驻场景）。 */
function addUnpaidOrderAndTask() {
  const baseTask = scenarioStore.deliveryTasks.find(task => task.taskNo === 'DT-2008')!
  const baseOrder = scenarioStore.orderDetails.find(item => item.order.orderNo === 'WO20260712091008')!
  scenarioStore.orderDetails.push({
    ...cloneContractData(baseOrder),
    order: { ...cloneContractData(baseOrder.order), orderId: 'UP-1', orderNo: 'UNPAID-WO-1', orderStatus: 1 },
    deliveryTaskNo: 'UNPAID-DT-1',
  })
  scenarioStore.deliveryTasks.push({
    ...cloneContractData(baseTask),
    taskId: 'UP-T1',
    taskNo: 'UNPAID-DT-1',
    orderId: 'UP-1',
    orderNo: 'UNPAID-WO-1',
  })
}

function addOrphanTask() {
  const baseTask = scenarioStore.deliveryTasks.find(task => task.taskNo === 'DT-2008')!
  scenarioStore.deliveryTasks.push({
    ...cloneContractData(baseTask),
    taskId: 'ORPHAN-T1',
    taskNo: 'ORPHAN-DT-1',
    orderId: 'ORPHAN-1',
    orderNo: 'ORPHAN-WO-1',
  })
}

let advSeq = 0

/** 第三轮对抗夹具：克隆 DT-2008 与其已支付订单为一对新共键，可对订单侧任意打补丁（终态/关联错位场景）。 */
function addDeliveryPair(patchRecord: (record: (typeof scenarioStore.orderDetails)[number]) => void = () => {}) {
  advSeq += 1
  const taskNo = `ADV-DT-${advSeq}`
  const orderId = `ADV-${advSeq}`
  const orderNo = `ADV-WO-${advSeq}`
  const baseTask = scenarioStore.deliveryTasks.find(task => task.taskNo === 'DT-2008')!
  const baseOrder = scenarioStore.orderDetails.find(item => item.order.orderNo === 'WO20260712091008')!
  const record = {
    ...cloneContractData(baseOrder),
    order: { ...cloneContractData(baseOrder.order), orderId, orderNo },
    deliveryTaskNo: taskNo,
  }
  patchRecord(record)
  scenarioStore.orderDetails.push(record)
  scenarioStore.deliveryTasks.push({
    ...cloneContractData(baseTask),
    taskId: `ADV-T-${advSeq}`,
    taskNo,
    orderId,
    orderNo,
  })
  return taskNo
}

/** 拒绝后必须保持不变的任务字段（第三轮审计要求）。 */
function taskInvariants(taskNo: string) {
  const task = scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!
  return {
    taskStatus: task.taskStatus,
    version: task.version,
    courierId: task.courierId,
    acceptTime: task.acceptTime,
  }
}

/** 异常上报零副作用断言用快照：任务字段 + 各写入集合长度。 */
function sideEffectSnapshot(taskNo: string, orderNo: string) {
  return {
    invariants: taskInvariants(taskNo),
    exceptions: scenarioStore.deliveryExceptions.length,
    traceLength: scenarioStore.orderDetails.find(item => item.order.orderNo === orderNo)?.trace.length ?? null,
    messages: scenarioStore.messages.length,
    audits: scenarioStore.auditEvents.length,
  }
}

/** 申诉创建零副作用断言用快照：任务字段 + 申诉/轨迹/消息/审计集合 + 场景时钟（第四轮审计整改）。 */
function appealSideEffectSnapshot(taskNo: string, orderNo: string) {
  const task = scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!
  const detail = scenarioStore.orderDetails.find(item => item.order.orderNo === orderNo)
  return {
    taskStatus: task.taskStatus,
    version: task.version,
    appeals: scenarioStore.deliveryAppeals.length,
    appealId: detail?.appealId ?? null,
    traceLength: detail?.trace.length ?? null,
    messages: scenarioStore.messages.length,
    audits: scenarioStore.auditEvents.length,
    clock: scenarioStore.now,
  }
}

/** 第四轮夹具：走完整下单→接单→离站→送达→签收链，返回已签收任务共键（申诉/逻辑时钟测试基础）。 */
async function fulfillFreshDelivery() {
  scenarioStore.selectAccount('ACCOUNT-USER-001')
  const created = await deliveryApi.createDeliveryOrder({
    addressId: 'ADDR-1',
    stationId: '1',
    waterTypeId: '1',
    containerSpec: '20L桶',
    deliveryCount: 1,
    plannedReturnCount: 1,
    deliveryMode: 'immediate',
  })
  scenarioStore.selectAccount('ACCOUNT-WORKER-002')
  let task = await deliveryApi.acceptTask(created.task.taskNo, created.task.version)
  task = await deliveryApi.advanceTask(created.task.taskNo, 3, task.version)
  task = await deliveryApi.advanceTask(created.task.taskNo, 4, task.version)
  task = await deliveryApi.signTask({
    taskNo: created.task.taskNo,
    expectedVersion: task.version,
    actualDeliveryCount: 1,
    actualReturnCount: 1,
    locationStatus: 'prototype-snapshot',
    photos: ([1, 2, 3] as const).map(type => ({
      type,
      label: (type === 1 ? '门牌' : type === 2 ? '水品' : '摆放') as '门牌' | '水品' | '摆放',
      recordRef: `T-${type}`,
      time: '20260716170000',
      latitude: 30.4586,
      longitude: 114.4276,
      evidenceMode: 'prototype' as const,
    })),
  })
  scenarioStore.selectAccount('ACCOUNT-USER-001')
  return { orderNo: created.order.order.orderNo, taskNo: created.task.taskNo, task, created }
}

function appealInput(orderNo: string, taskNo: string) {
  return {
    orderNo,
    taskNo,
    reason: 'QUANTITY' as const,
    description: '实收数量与订单不一致',
    receivedCount: 0,
    evidenceRefs: [],
  }
}

function selectCourierAccount() {
  scenarioStore.selectAccount('ACCOUNT-WORKER-002')
}

function addSelfDeliveryTask() {
  const source = scenarioStore.deliveryTasks.find(task => task.taskNo === 'DT-2008')!
  scenarioStore.deliveryTasks.push({
    ...source,
    taskId: 'SELF-TASK-1',
    taskNo: 'SELF-DT-1',
    orderId: 'SELF-ORDER-1',
    orderNo: 'SELF-WO-1',
    userId: '2',
    signPhotos: [],
  })
}

describe('delivery mock data scope', () => {
  beforeEach(() => {
    scenarioStore.reset()
    selectCourierAccount()
  })

  it('excludes orders created by the current user from available tasks', async () => {
    addSelfDeliveryTask()

    const tasks = await deliveryApi.listTasks('available')

    expect(tasks.some(task => task.taskNo === 'DT-2008')).toBe(true)
    expect(tasks.some(task => task.taskNo === 'SELF-DT-1')).toBe(false)
  })

  it('rejects accepting an own order even when called directly', async () => {
    addSelfDeliveryTask()

    await expect(deliveryApi.acceptTask('SELF-DT-1', 1)).rejects.toMatchObject({
      code: 'SELF_DELIVERY_FORBIDDEN',
    })
  })

  it('denies task access when the courier scope is empty', async () => {
    const account = scenarioStore.activeAccount()
    account.courierScope!.stationIds = []

    await expect(deliveryApi.listTasks('available')).rejects.toMatchObject({
      code: 'COURIER_SCOPE_DENIED',
    })
  })

  it('creates a card-paid prototype order whose task is immediately acceptable (2026-07-18 caliber)', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')

    const result = await deliveryApi.createDeliveryOrder({
      addressId: 'ADDR-1',
      stationId: '1',
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 2,
      plannedReturnCount: 1,
      deliveryMode: 'immediate',
    })

    expect(result.order.order.orderStatus).toBe(2)
    expect(result.order.trace.map(node => node.node)).toContain('paid')
    expect(result.order.order.mockMeta).toMatchObject({
      settlementEffect: 'none',
      syncedToPc: false,
    })
    expect(result.task.orderNo).toBe(result.order.order.orderNo)
  })

  it('rejects delivery orders when the account has no card or insufficient balance', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-003')
    await expect(
      deliveryApi.createDeliveryOrder({
        addressId: 'ADDR-2',
        stationId: '1',
        waterTypeId: '1',
        containerSpec: '20L桶',
        deliveryCount: 1,
        plannedReturnCount: 0,
        deliveryMode: 'immediate',
      }),
    ).rejects.toMatchObject({ code: 'CARD_MISSING' })

    scenarioStore.selectAccount('ACCOUNT-USER-001')
    await expect(
      deliveryApi.createDeliveryOrder({
        addressId: 'ADDR-1',
        stationId: '1',
        waterTypeId: '1',
        containerSpec: '20L桶',
        deliveryCount: 4,
        plannedReturnCount: 0,
        deliveryMode: 'immediate',
      }),
    ).rejects.toMatchObject({ code: 'INSUFFICIENT_BALANCE' })
  })

  it('closes the order loop on sign: order 2→4 with finish time and synced trace', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const created = await deliveryApi.createDeliveryOrder({
      addressId: 'ADDR-1',
      stationId: '1',
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 1,
      plannedReturnCount: 1,
      deliveryMode: 'immediate',
    })

    scenarioStore.selectAccount('ACCOUNT-WORKER-002')
    let task = await deliveryApi.acceptTask(created.task.taskNo, created.task.version)
    task = await deliveryApi.advanceTask(created.task.taskNo, 3, task.version)
    task = await deliveryApi.advanceTask(created.task.taskNo, 4, task.version)
    task = await deliveryApi.signTask({
      taskNo: created.task.taskNo,
      expectedVersion: task.version,
      actualDeliveryCount: 1,
      actualReturnCount: 1,
      locationStatus: 'prototype-snapshot',
      photos: ([1, 2, 3] as const).map(type => ({
        type,
        label: (type === 1 ? '门牌' : type === 2 ? '水品' : '摆放') as '门牌' | '水品' | '摆放',
        recordRef: `T-${type}`,
        time: '20260716170000',
        latitude: 30.4586,
        longitude: 114.4276,
        evidenceMode: 'prototype' as const,
      })),
    })
    expect(task.taskStatus).toBe(5)
    expect(task.locationStatus).toBe('prototype-snapshot')

    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const detail = await orderApi.getOrderDetail(created.order.order.orderNo)
    expect(detail.order.orderStatus).toBe(4)
    expect(detail.order.finishTime).toBeTruthy()
    expect(detail.trace.map(node => node.node)).toEqual(
      expect.arrayContaining(['paid', 'accepted', 'departed', 'arrived', 'signed']),
    )
  })

  it('rejects delivery orders against a non-open station', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')

    await expect(
      deliveryApi.createDeliveryOrder({
        addressId: 'ADDR-1',
        stationId: '2',
        waterTypeId: '1',
        containerSpec: '20L桶',
        deliveryCount: 1,
        plannedReturnCount: 0,
        deliveryMode: 'immediate',
      }),
    ).rejects.toMatchObject({ code: 'STATION_NOT_OPEN' })
  })

  it('submits admission for review without granting work capability', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')

    const admission = await deliveryApi.submitCourierAdmission({
      applicantName: '张女士',
      phone: '13900001111',
      requestedStationIds: ['1'],
      declarationAccepted: true,
    })

    expect(admission.status).toBe(1)
    expect(scenarioStore.activeAccount().capabilities).not.toContain('COURIER_WORK')
  })

  it('lets the assigned courier append appeal evidence while the task is under appeal', async () => {
    const appeal = await deliveryApi.getTaskAppeal('DT-2006')
    expect(appeal).toMatchObject({ appealId: '3001', appealStatus: 1 })

    const updated = await deliveryApi.appendAppealEvidence({
      taskNo: 'DT-2006',
      appealId: '3001',
      description: '送达时已按门牌拍照，实际配送 2 桶为用户当场确认。',
      evidenceRefs: ['COURIER-EVIDENCE-1'],
    })

    expect(updated.courierEvidences).toHaveLength(1)
  })

  it('rejects appeal evidence on tasks that are not under appeal', async () => {
    await expect(
      deliveryApi.appendAppealEvidence({
        taskNo: 'DT-2007',
        appealId: '3001',
        description: '与任务无关的举证',
        evidenceRefs: [],
      }),
    ).rejects.toMatchObject({ code: 'APPEAL_NOT_ACTIVE' })
  })

  it('rejects appeal evidence when the linked order is not in completed state', async () => {
    const record = scenarioStore.orderDetails.find(item => item.deliveryTaskNo === 'DT-2006')!
    record.order.orderStatus = 2

    await expect(
      deliveryApi.appendAppealEvidence({
        taskNo: 'DT-2006',
        appealId: '3001',
        description: '补充举证材料',
        evidenceRefs: [],
      }),
    ).rejects.toMatchObject({ code: 'APPEAL_ORDER_STATE_INVALID' })
  })

  it('adversarial: unpaid-order tasks are excluded from the pool and rejected at accept', async () => {
    addUnpaidOrderAndTask()

    const tasks = await deliveryApi.listTasks('available')
    expect(tasks.some(task => task.taskNo === 'UNPAID-DT-1')).toBe(false)

    await expect(deliveryApi.acceptTask('UNPAID-DT-1', 1)).rejects.toMatchObject({
      code: 'ORDER_NOT_FULFILLABLE',
    })
  })

  it('adversarial: only orderStatus===2 is fulfillable — 1/3/4/5/6/7/8 excluded from pool and rejected at accept', async () => {
    const cases = ([1, 3, 4, 5, 6, 7, 8] as const).map(status => ({
      status,
      taskNo: addDeliveryPair((record) => {
        record.order.orderStatus = status
      }),
    }))

    const pool = await deliveryApi.listTasks('available')
    for (const item of cases) {
      expect(pool.some(task => task.taskNo === item.taskNo)).toBe(false)
      const before = taskInvariants(item.taskNo)
      await expect(deliveryApi.acceptTask(item.taskNo, 1)).rejects.toMatchObject({
        code: 'ORDER_NOT_FULFILLABLE',
      })
      expect(taskInvariants(item.taskNo)).toEqual(before)
    }

    // 对照组：唯一可履约状态 2 正常入池且可接。
    const okTaskNo = addDeliveryPair()
    const refreshed = await deliveryApi.listTasks('available')
    expect(refreshed.some(task => task.taskNo === okTaskNo)).toBe(true)
    await expect(deliveryApi.acceptTask(okTaskNo, 1)).resolves.toMatchObject({ taskStatus: 2 })
  })

  it('adversarial: any link-key mismatch (orderId/userId/orderType/deliveryTaskNo) excluded and rejected', async () => {
    const mutations: Array<(record: (typeof scenarioStore.orderDetails)[number]) => void> = [
      (record) => {
        record.order.orderId = 'WRONG-ID'
      },
      (record) => {
        record.order.userId = '999'
      },
      (record) => {
        record.order.orderType = 1
      },
      (record) => {
        record.deliveryTaskNo = 'OTHER-DT'
      },
    ]
    const taskNos = mutations.map(mutate => addDeliveryPair(mutate))

    const pool = await deliveryApi.listTasks('available')
    for (const taskNo of taskNos) {
      expect(pool.some(task => task.taskNo === taskNo)).toBe(false)
      const before = taskInvariants(taskNo)
      await expect(deliveryApi.acceptTask(taskNo, 1)).rejects.toMatchObject({
        code: 'ORDER_LINK_MISMATCH',
      })
      expect(taskInvariants(taskNo)).toEqual(before)
    }
  })

  it('adversarial: deep-link detail of unassigned tasks is blocked unless the order is fulfillable', async () => {
    const terminalTaskNo = addDeliveryPair((record) => {
      record.order.orderStatus = 3
    })
    await expect(deliveryApi.getTaskDetail(terminalTaskNo)).rejects.toMatchObject({
      code: 'ORDER_NOT_FULFILLABLE',
    })

    addOrphanTask()
    await expect(deliveryApi.getTaskDetail('ORPHAN-DT-1')).rejects.toMatchObject({
      code: 'ORDER_MISSING',
    })

    const mismatchTaskNo = addDeliveryPair((record) => {
      record.deliveryTaskNo = 'OTHER-DT'
    })
    await expect(deliveryApi.getTaskDetail(mismatchTaskNo)).rejects.toMatchObject({
      code: 'ORDER_LINK_MISMATCH',
    })
  })

  it('adversarial: reportException with a missing order fails with zero side effects', async () => {
    const task = await deliveryApi.acceptTask('DT-2008', 1)
    const index = scenarioStore.orderDetails.findIndex(
      item => item.order.orderNo === 'WO20260712091008',
    )
    scenarioStore.orderDetails.splice(index, 1)

    const before = sideEffectSnapshot('DT-2008', 'WO20260712091008')
    await expect(
      deliveryApi.reportException({
        taskNo: 'DT-2008',
        expectedVersion: task.version,
        reason: 'DAMAGED',
        description: '桶体破损',
        evidenceRefs: [],
      }),
    ).rejects.toMatchObject({ code: 'ORDER_MISSING' })
    expect(sideEffectSnapshot('DT-2008', 'WO20260712091008')).toEqual(before)
  })

  it('adversarial: reportException on unpaid/cancelled/completed/refunded orders has zero side effects', async () => {
    const task = await deliveryApi.acceptTask('DT-2008', 1)
    const record = scenarioStore.orderDetails.find(
      item => item.order.orderNo === 'WO20260712091008',
    )!

    for (const status of [1, 3, 4, 6] as const) {
      record.order.orderStatus = status
      const before = sideEffectSnapshot('DT-2008', 'WO20260712091008')
      await expect(
        deliveryApi.reportException({
          taskNo: 'DT-2008',
          expectedVersion: task.version,
          reason: 'UNREACHABLE',
          description: '联系不上用户',
          evidenceRefs: [],
        }),
      ).rejects.toMatchObject({ code: 'ORDER_NOT_FULFILLABLE' })
      expect(sideEffectSnapshot('DT-2008', 'WO20260712091008')).toEqual(before)
    }
  })

  it('keeps exception record time and its order trace node time from one source', async () => {
    const task = await deliveryApi.acceptTask('DT-2008', 1)
    const result = await deliveryApi.reportException({
      taskNo: 'DT-2008',
      expectedVersion: task.version,
      reason: 'QUANTITY',
      description: '水品数量不符',
      evidenceRefs: [],
    })

    const record = scenarioStore.orderDetails.find(
      item => item.order.orderNo === 'WO20260712091008',
    )!
    const node = record.trace[record.trace.length - 1]
    expect(node.node).toBe('exception')
    expect(node.time).toBe(result.createTime)
  })

  it('adversarial: orphan tasks (no linked order) are excluded and rejected at accept', async () => {
    addOrphanTask()

    const tasks = await deliveryApi.listTasks('available')
    expect(tasks.some(task => task.taskNo === 'ORPHAN-DT-1')).toBe(false)

    await expect(deliveryApi.acceptTask('ORPHAN-DT-1', 1)).rejects.toMatchObject({
      code: 'ORDER_MISSING',
    })
  })

  it('adversarial: fulfillment is fail-closed when the linked order disappears mid-flow', async () => {
    let task = await deliveryApi.acceptTask('DT-2008', 1)
    const index = scenarioStore.orderDetails.findIndex(
      item => item.order.orderNo === 'WO20260712091008',
    )
    scenarioStore.orderDetails.splice(index, 1)

    await expect(deliveryApi.advanceTask('DT-2008', 3, task.version)).rejects.toMatchObject({
      code: 'ORDER_MISSING',
    })
    // 任务状态未被推进（fail-closed：不允许订单与任务脱钩）。
    task = await deliveryApi.getTaskDetail('DT-2008')
    expect(task.taskStatus).toBe(2)
  })

  it('adversarial: prototype-snapshot claim without consistent coordinates is rejected', async () => {
    let task = await deliveryApi.acceptTask('DT-2008', 1)
    task = await deliveryApi.advanceTask('DT-2008', 3, task.version)
    task = await deliveryApi.advanceTask('DT-2008', 4, task.version)

    await expect(
      deliveryApi.signTask({
        taskNo: 'DT-2008',
        expectedVersion: task.version,
        actualDeliveryCount: 1,
        actualReturnCount: 1,
        locationStatus: 'prototype-snapshot',
        photos: ([1, 2, 3] as const).map(type => ({
          type,
          label: (type === 1 ? '门牌' : type === 2 ? '水品' : '摆放') as '门牌' | '水品' | '摆放',
          recordRef: `T-${type}`,
          time: '20260716170000',
          evidenceMode: 'prototype' as const,
        })),
      }),
    ).rejects.toMatchObject({ code: 'LOCATION_EVIDENCE_INVALID' })
    // 签收失败不推进任务，也不触碰订单。
    expect((await deliveryApi.getTaskDetail('DT-2008')).taskStatus).toBe(4)
  })

  it('keeps one single fulfillment clock: order finish/trace times equal task action times', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const created = await deliveryApi.createDeliveryOrder({
      addressId: 'ADDR-1',
      stationId: '1',
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 1,
      plannedReturnCount: 1,
      deliveryMode: 'immediate',
    })
    scenarioStore.selectAccount('ACCOUNT-WORKER-002')
    let task = await deliveryApi.acceptTask(created.task.taskNo, created.task.version)
    task = await deliveryApi.advanceTask(created.task.taskNo, 3, task.version)
    task = await deliveryApi.advanceTask(created.task.taskNo, 4, task.version)
    task = await deliveryApi.signTask({
      taskNo: created.task.taskNo,
      expectedVersion: task.version,
      actualDeliveryCount: 1,
      actualReturnCount: 1,
      locationStatus: 'prototype-snapshot',
      photos: ([1, 2, 3] as const).map(type => ({
        type,
        label: (type === 1 ? '门牌' : type === 2 ? '水品' : '摆放') as '门牌' | '水品' | '摆放',
        recordRef: `T-${type}`,
        time: '20260716170000',
        latitude: 30.4586,
        longitude: 114.4276,
        evidenceMode: 'prototype' as const,
      })),
    })

    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const detail = await orderApi.getOrderDetail(created.order.order.orderNo)
    expect(detail.order.finishTime).toBe(task.signTime)
    const nodeTime = (node: string) => detail.trace.find(item => item.node === node)?.time
    expect(nodeTime('accepted')).toBe(task.acceptTime)
    expect(nodeTime('departed')).toBe(task.departTime)
    expect(nodeTime('arrived')).toBe(task.arriveTime)
    expect(nodeTime('signed')).toBe(task.signTime)

    // 单调时间轴：创建 <= 接单 <= 离站 <= 送达 <= 签收 === 完成（第三轮审计整改）。
    const chain = [
      detail.order.createTime,
      task.acceptTime,
      task.departTime,
      task.arriveTime,
      task.signTime,
    ]
    expect([...chain].sort()).toEqual(chain)
    expect(task.signTime).toBe(detail.order.finishTime)

    // 离站与签收站内消息与任务动作时间同源。
    const messageTime = (title: string) =>
      scenarioStore.messages.find(
        item => item.objectId === created.order.order.orderNo && item.title === title,
      )?.sendTime
    expect(messageTime('水已离开水站')).toBe(task.departTime)
    expect(messageTime('订单已签收')).toBe(task.signTime)
  })

  it('appeal gate: order statuses 2/3/5/6/7/8 are rejected with zero side effects', async () => {
    const { orderNo, taskNo } = await fulfillFreshDelivery()
    const record = scenarioStore.orderDetails.find(item => item.order.orderNo === orderNo)!

    for (const status of [2, 3, 5, 6, 7, 8] as const) {
      record.order.orderStatus = status
      const before = appealSideEffectSnapshot(taskNo, orderNo)
      await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
        code: 'APPEAL_ORDER_STATE_INVALID',
      })
      expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)
    }
  })

  it('appeal gate: any link-key mismatch is rejected with zero side effects', async () => {
    const { orderNo, taskNo } = await fulfillFreshDelivery()
    const record = scenarioStore.orderDetails.find(item => item.order.orderNo === orderNo)!

    const originalOrderId = record.order.orderId
    const cases: Array<{ code: string, mutate: () => void, restore: () => void }> = [
      {
        code: 'ORDER_LINK_MISMATCH',
        mutate: () => {
          record.order.orderId = 'WRONG-ID'
        },
        restore: () => {
          record.order.orderId = originalOrderId
        },
      },
      {
        // 属主校验先于共键校验：订单 userId 错位时按"订单不存在或无权访问"拒绝。
        code: 'ORDER_NOT_FOUND',
        mutate: () => {
          record.order.userId = '999'
        },
        restore: () => {
          record.order.userId = '1'
        },
      },
      {
        code: 'ORDER_LINK_MISMATCH',
        mutate: () => {
          record.order.orderType = 1
        },
        restore: () => {
          record.order.orderType = 3
        },
      },
      {
        code: 'ORDER_LINK_MISMATCH',
        mutate: () => {
          record.deliveryTaskNo = 'OTHER-DT'
        },
        restore: () => {
          record.deliveryTaskNo = taskNo
        },
      },
    ]

    for (const item of cases) {
      item.mutate()
      const before = appealSideEffectSnapshot(taskNo, orderNo)
      await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
        code: item.code,
      })
      expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)
      item.restore()
    }
  })

  it('appeal gate: a completed order 4 + signed task 5 creates the appeal with one action time everywhere', async () => {
    const { orderNo, taskNo, task } = await fulfillFreshDelivery()

    const appeal = await orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))
    expect(appeal.appealStatus).toBe(1)

    // 窗口：signTime <= createTime <= signTime + 24h。
    expect(appeal.createTime >= task.signTime!).toBe(true)
    expect(appeal.createTime <= addSecondsToBusinessTime(task.signTime!, 24 * 60 * 60)).toBe(true)

    // 四源同一动作时间：申诉记录 / 订单轨迹 / 站内消息 / 审计事件。
    const detail = await orderApi.getOrderDetail(orderNo)
    expect(detail.trace.find(item => item.node === 'appeal-created')?.time).toBe(appeal.createTime)
    const message = scenarioStore.messages.find(item => item.objectId === appeal.appealId)
    expect(message?.sendTime).toBe(appeal.createTime)
    const audit = scenarioStore.auditEvents.filter(item => item.action === 'delivery.appeal.create').pop()
    expect(audit?.time).toBe(appeal.createTime)

    expect(scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!.taskStatus).toBe(7)
  })

  it('appeal gate: duplicate appeals are rejected with zero side effects', async () => {
    const { orderNo, taskNo } = await fulfillFreshDelivery()
    await orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))

    // 任务已转申诉中（7）：按状态拒绝。
    let before = appealSideEffectSnapshot(taskNo, orderNo)
    await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
      code: 'APPEAL_NOT_ALLOWED',
    })
    expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)

    // 防御分支：任务被外力改回 5 而活动申诉仍在，按重复申诉拒绝。
    scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!.taskStatus = 5
    before = appealSideEffectSnapshot(taskNo, orderNo)
    await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
      code: 'APPEAL_ALREADY_EXISTS',
    })
    expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)
  })

  it('appeal gate: appeal time earlier than sign or beyond the 24h window is rejected', async () => {
    const { orderNo, taskNo } = await fulfillFreshDelivery()
    const task = scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!
    const originalSignTime = task.signTime!

    // 早于签收：签收时间被置于未来时，申诉动作时间落在签收前，必须拒绝。
    task.signTime = addSecondsToBusinessTime(scenarioStore.now, 60 * 60)
    let before = appealSideEffectSnapshot(taskNo, orderNo)
    await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
      code: 'APPEAL_WINDOW_EXPIRED',
    })
    expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)

    // 超过 24 小时：签收时间在 25 小时前，窗口已关。
    task.signTime = addSecondsToBusinessTime(scenarioStore.now, -25 * 60 * 60)
    before = appealSideEffectSnapshot(taskNo, orderNo)
    await expect(orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))).rejects.toMatchObject({
      code: 'APPEAL_WINDOW_EXPIRED',
    })
    expect(appealSideEffectSnapshot(taskNo, orderNo)).toEqual(before)

    task.signTime = originalSignTime
  })

  it('logical clock: exception times floor at the latest fulfilled node with one time across record/trace/audit', async () => {
    scenarioStore.selectAccount('ACCOUNT-USER-001')
    const created = await deliveryApi.createDeliveryOrder({
      addressId: 'ADDR-1',
      stationId: '1',
      waterTypeId: '1',
      containerSpec: '20L桶',
      deliveryCount: 1,
      plannedReturnCount: 1,
      deliveryMode: 'immediate',
    })
    scenarioStore.selectAccount('ACCOUNT-WORKER-002')
    const taskNo = created.task.taskNo
    const record = scenarioStore.orderDetails.find(
      item => item.order.orderNo === created.order.order.orderNo,
    )!
    const assertExceptionSources = (createTime: string) => {
      const node = record.trace[record.trace.length - 1]
      expect(node.node).toBe('exception')
      expect(node.time).toBe(createTime)
      const audit = scenarioStore.auditEvents
        .filter(item => item.action === 'delivery.task.exception.report')
        .pop()
      expect(audit?.time).toBe(createTime)
    }

    let task = await deliveryApi.acceptTask(taskNo, created.task.version)
    const exception1 = await deliveryApi.reportException({
      taskNo,
      expectedVersion: task.version,
      reason: 'UNREACHABLE',
      description: '接单后联系不上用户',
      evidenceRefs: [],
    })
    expect(created.order.order.createTime <= task.acceptTime!).toBe(true)
    expect(task.acceptTime! <= exception1.createTime).toBe(true)
    assertExceptionSources(exception1.createTime)

    task = await deliveryApi.advanceTask(taskNo, 3, task.version)
    const exception2 = await deliveryApi.reportException({
      taskNo,
      expectedVersion: task.version,
      reason: 'ADDRESS',
      description: '离站后地址异常',
      evidenceRefs: [],
    })
    expect(task.departTime! <= exception2.createTime).toBe(true)
    assertExceptionSources(exception2.createTime)

    task = await deliveryApi.advanceTask(taskNo, 4, task.version)
    const exception3 = await deliveryApi.reportException({
      taskNo,
      expectedVersion: task.version,
      reason: 'DAMAGED',
      description: '送达后发现桶体破损',
      evidenceRefs: [],
    })
    expect(task.arriveTime! <= exception3.createTime).toBe(true)
    assertExceptionSources(exception3.createTime)
  })

  it('logical clock: courier evidence times are non-decreasing and floored at sign/appeal times', async () => {
    const { orderNo, taskNo } = await fulfillFreshDelivery()
    const appeal = await orderApi.createDeliveryAppeal(appealInput(orderNo, taskNo))

    scenarioStore.selectAccount('ACCOUNT-WORKER-002')
    await deliveryApi.appendAppealEvidence({
      taskNo,
      appealId: appeal.appealId,
      description: '第一份补充举证',
      evidenceRefs: [],
    })
    const updated = await deliveryApi.appendAppealEvidence({
      taskNo,
      appealId: appeal.appealId,
      description: '第二份补充举证',
      evidenceRefs: [],
    })

    const evidences = updated.courierEvidences!
    expect(evidences.length).toBe(2)
    const signTime = scenarioStore.deliveryTasks.find(item => item.taskNo === taskNo)!.signTime!
    expect(signTime <= appeal.createTime).toBe(true)
    expect(appeal.createTime <= evidences[0].time).toBe(true)
    expect(evidences[0].time <= evidences[1].time).toBe(true)

    const audit = scenarioStore.auditEvents.filter(item => item.action === 'delivery.appeal.evidence').pop()
    expect(audit?.time).toBe(evidences[1].time)
  })

  it('sign evidence: any draft photo time is overwritten by the authoritative signTime', async () => {
    // fulfillFreshDelivery 的照片草稿仍带旧时间 20260716170000，契约必须整体覆盖。
    const { task } = await fulfillFreshDelivery()
    expect(task.signPhotos).toHaveLength(3)
    for (const photo of task.signPhotos) {
      expect(photo.time).toBe(task.signTime)
      expect(photo.time).not.toBe('20260716170000')
    }
  })

  it('sign evidence: photos/task/order/trace/message/audit share one sign action time', async () => {
    const { orderNo, task } = await fulfillFreshDelivery()
    const signTime = task.signTime!

    for (const photo of task.signPhotos) {
      expect(photo.time).toBe(signTime)
    }
    const detail = await orderApi.getOrderDetail(orderNo)
    expect(detail.order.finishTime).toBe(signTime)
    expect(detail.trace.find(item => item.node === 'signed')?.time).toBe(signTime)
    const message = scenarioStore.messages.find(
      item => item.objectId === orderNo && item.title === '订单已签收',
    )
    expect(message?.sendTime).toBe(signTime)
    const audit = scenarioStore.auditEvents.filter(item => item.action === 'delivery.task.sign').pop()
    expect(audit?.time).toBe(signTime)
  })

  it('sign evidence: a rejected sign leaves photos and the logical clock untouched', async () => {
    let task = await deliveryApi.acceptTask('DT-2008', 1)
    task = await deliveryApi.advanceTask('DT-2008', 3, task.version)
    task = await deliveryApi.advanceTask('DT-2008', 4, task.version)

    const before = {
      photos: cloneContractData(scenarioStore.deliveryTasks.find(item => item.taskNo === 'DT-2008')!.signPhotos),
      clock: scenarioStore.now,
      audits: scenarioStore.auditEvents.length,
      messages: scenarioStore.messages.length,
    }
    await expect(
      deliveryApi.signTask({
        taskNo: 'DT-2008',
        expectedVersion: task.version,
        actualDeliveryCount: 1,
        actualReturnCount: 1,
        locationStatus: 'prototype-snapshot',
        photos: ([1, 2, 3] as const).map(type => ({
          type,
          label: (type === 1 ? '门牌' : type === 2 ? '水品' : '摆放') as '门牌' | '水品' | '摆放',
          recordRef: `T-${type}`,
          evidenceMode: 'prototype' as const,
        })),
      }),
    ).rejects.toMatchObject({ code: 'LOCATION_EVIDENCE_INVALID' })

    const after = scenarioStore.deliveryTasks.find(item => item.taskNo === 'DT-2008')!
    expect(after.signPhotos).toEqual(before.photos)
    expect(scenarioStore.now).toBe(before.clock)
    expect(scenarioStore.auditEvents.length).toBe(before.audits)
    expect(scenarioStore.messages.length).toBe(before.messages)
  })

  it('evidence access: unassigned orphan tasks cannot read appeal or exception evidence', async () => {
    addOrphanTask()
    const before = {
      clock: scenarioStore.now,
      audits: scenarioStore.auditEvents.length,
      messages: scenarioStore.messages.length,
    }

    await expect(deliveryApi.getTaskAppeal('ORPHAN-DT-1')).rejects.toMatchObject({
      code: 'TASK_ACCESS_DENIED',
    })
    await expect(deliveryApi.listTaskExceptions('ORPHAN-DT-1')).rejects.toMatchObject({
      code: 'TASK_ACCESS_DENIED',
    })

    expect(scenarioStore.now).toBe(before.clock)
    expect(scenarioStore.auditEvents.length).toBe(before.audits)
    expect(scenarioStore.messages.length).toBe(before.messages)
  })

  it('evidence access: tasks assigned to another courier are rejected', async () => {
    const base = scenarioStore.deliveryTasks.find(item => item.taskNo === 'DT-2006')!
    scenarioStore.deliveryTasks.push({
      ...cloneContractData(base),
      taskId: 'FOREIGN-T1',
      taskNo: 'FOREIGN-DT-1',
      courierId: '99',
    })

    await expect(deliveryApi.getTaskAppeal('FOREIGN-DT-1')).rejects.toMatchObject({
      code: 'TASK_ACCESS_DENIED',
    })
    await expect(deliveryApi.listTaskExceptions('FOREIGN-DT-1')).rejects.toMatchObject({
      code: 'TASK_ACCESS_DENIED',
    })
  })

  it('evidence access: the assigned courier still reads own appeal and exception evidence', async () => {
    // 状态 7 申诉任务：共键收紧后仍能读到申诉。
    const appeal = await deliveryApi.getTaskAppeal('DT-2006')
    expect(appeal?.appealId).toBe('3001')

    // 本人已接任务上报异常后可读回，且只含本人记录。
    const task = await deliveryApi.acceptTask('DT-2008', 1)
    await deliveryApi.reportException({
      taskNo: 'DT-2008',
      expectedVersion: task.version,
      reason: 'UNREACHABLE',
      description: '联系不上用户',
      evidenceRefs: [],
    })
    const exceptions = await deliveryApi.listTaskExceptions('DT-2008')
    expect(exceptions).toHaveLength(1)
    expect(exceptions[0].courierId).toBe('1')
  })

  it('audit clock: later business actions never audit earlier than the logical clock', async () => {
    await fulfillFreshDelivery()
    const clockAfterDelivery = scenarioStore.now

    // 充值订单（张女士）：记录/审计/时钟同源。
    const recharge = await orderApi.createRechargeOrder({ cardId: '1', amountFen: 3000 })
    expect(recharge.order.createTime >= clockAfterDelivery).toBe(true)
    const rechargeAudit = scenarioStore.auditEvents.filter(item => item.action === 'order.recharge.create').pop()
    expect(rechargeAudit?.time).toBe(recharge.order.createTime)
    expect(scenarioStore.now >= rechargeAudit!.time).toBe(true)

    // 机主服务申请（赵先生）：同断言。
    scenarioStore.selectAccount('ACCOUNT-OWNER-004')
    const request = await deviceApi.createOwnerServiceRequest({
      deviceNo: 'DK-DEV-0001',
      serviceType: 'REPAIR',
      description: '出水口漏水需要维修',
      evidenceRefs: [],
      contactPhone: '13800001111',
    })
    expect(request.createTime >= rechargeAudit!.time).toBe(true)
    const serviceAudit = scenarioStore.auditEvents.filter(item => item.action === 'owner.service.create').pop()
    expect(serviceAudit?.time).toBe(request.createTime)
    expect(scenarioStore.now >= serviceAudit!.time).toBe(true)

    // 默认分支（无业务时间的普通审计）也不得早于当前时钟。
    const clockBefore = scenarioStore.now
    scenarioStore.recordAudit('USER_BASE', 'demo.plain.audit', 'demo', 'X-1', 'success')
    const plainAudit = scenarioStore.auditEvents[scenarioStore.auditEvents.length - 1]
    expect(plainAudit.time > clockBefore).toBe(true)
    expect(scenarioStore.now).toBe(plainAudit.time)
  })

  it('audit clock: resetMockScenario restores the clock and replays identically', async () => {
    const runOnce = async () => {
      const { task } = await fulfillFreshDelivery()
      const recharge = await orderApi.createRechargeOrder({ cardId: '1', amountFen: 3000 })
      return [task.signTime!, recharge.order.createTime, scenarioStore.now]
    }

    const first = await runOnce()
    await accountApi.resetMockScenario()
    expect(scenarioStore.now).toBe('20260716180000')
    selectCourierAccount()
    const second = await runOnce()
    expect(second).toEqual(first)
  })

  it('notifies the ordering user on depart and sign nodes', async () => {
    await deliveryApi.acceptTask('DT-2008', 1)
    await deliveryApi.advanceTask('DT-2008', 3, 2)

    const departMessage = scenarioStore.messages.find(
      item =>
        item.accountId === 'ACCOUNT-USER-001'
        && item.objectId === 'WO20260712091008'
        && item.title === '水已离开水站',
    )
    expect(departMessage).toBeTruthy()
    expect(departMessage?.channel).toBe('in-app')
    expect(departMessage?.sendStatus).toBe(4)
  })
})
