import type { DeliveryAppealRaw, DeliveryExceptionRaw, DeliveryTaskRaw } from './delivery-normalize'
import { describe, expect, it } from 'vitest'
import { ContractError } from './common'
import {
  appealDeadlineOf,
  appealWindowState,
  canAcceptDeliveryTask,
  canAdvanceDeliveryTask,
  canAppendAppealEvidence,
  canCreateDeliveryAppeal,
  canReportDeliveryException,
  canSignDeliveryTask,
  normalizeCourierAdmission,
  normalizeDeliveryAppeal,
  normalizeDeliveryException,
  normalizeDeliveryTask,
  strictEntityId,
  strictInt,
} from './delivery-normalize'

/**
 * E2E-03 包B：delivery 域 real 归一化与页面阻断纯函数测试。
 *
 * 归一化钉死三件事：Long 序列化为字符串后 number|十进制串双收；小数/前导零/越界/
 * 非法枚举一律拒绝（fail-closed 抛 DELIVERY_CONTRACT_BROKEN，不拼残缺数据）；
 * 价格快照总额必须恰等于水费+配送费（金额不许猜）。
 * 阻断纯函数钉死与包A 拒因同源的状态矩阵，防止页面各写一份规则漂移。
 */

function taskRaw(overrides: Partial<DeliveryTaskRaw> = {}): DeliveryTaskRaw {
  return {
    taskId: '77',
    taskNo: 'DTABC',
    orderId: 11,
    orderNo: 'WDABC',
    userId: '6',
    stationId: '1',
    stationName: '光谷软件园水站',
    waterTypeId: 2,
    waterTypeName: '纯净水（占位）',
    containerSpec: '10L桶',
    plannedDeliveryCount: 3,
    plannedReturnCount: '1',
    receiveAddress: '光谷软件园 A1 栋',
    maskedPhone: '139****1111',
    priceSnapshot: { waterAmountFen: '3600', deliveryFeeFen: 600, totalAmountFen: '4200' },
    taskStatus: 1,
    version: 1,
    ...overrides,
  }
}

describe('normalizeDeliveryTask', () => {
  it('接受 number 与十进制字符串双形态的 Long，产出契约形状', () => {
    const task = normalizeDeliveryTask(taskRaw())
    expect(task.taskId).toBe('77')
    expect(task.orderId).toBe('11')
    expect(task.waterTypeId).toBe('2')
    expect(task.plannedDeliveryCount).toBe(3)
    expect(task.plannedReturnCount).toBe(1)
    expect(task.priceSnapshot).toEqual({ waterAmountFen: 3600, deliveryFeeFen: 600, totalAmountFen: 4200 })
    expect(task.taskStatus).toBe(1)
    expect(task.signPhotos).toEqual([])
    expect(task.courierId).toBeUndefined()
  })

  it('缺关键标识（taskNo/orderNo/userId）一律 fail-closed', () => {
    expect(() => normalizeDeliveryTask(taskRaw({ taskNo: null }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ orderNo: '' }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ userId: null }))).toThrow(ContractError)
  })

  it('状态/版本越界拒绝：0、8、小数、缺失', () => {
    expect(() => normalizeDeliveryTask(taskRaw({ taskStatus: 0 }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ taskStatus: 8 }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ taskStatus: null }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ version: '1.5' }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ version: 0 }))).toThrow(ContractError)
  })

  it('容器规格白名单外拒绝（价目契约之外的规格不允许渲染）', () => {
    expect(() => normalizeDeliveryTask(taskRaw({ containerSpec: '50L桶' }))).toThrow(ContractError)
  })

  it('支付方式（D-214）：缺省按 2 兼容旧单，2/3 双收（number|串），白名单外 fail-closed', () => {
    expect(normalizeDeliveryTask(taskRaw()).payWay).toBe(2)
    expect(normalizeDeliveryTask(taskRaw({ payWay: 3 })).payWay).toBe(3)
    expect(normalizeDeliveryTask(taskRaw({ payWay: '3' })).payWay).toBe(3)
    expect(normalizeDeliveryTask(taskRaw({ payWay: '2' })).payWay).toBe(2)
    // 1（微信）不是配送任务快照的合法支付口径；未知值决定金额展示语义，宁可拒绝不猜
    expect(() => normalizeDeliveryTask(taskRaw({ payWay: 1 }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ payWay: '03' }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ payWay: 'ml' }))).toThrow(ContractError)
  })

  it('价格快照总额 ≠ 水费+配送费 即契约破坏（金额不许猜）', () => {
    expect(() => normalizeDeliveryTask(taskRaw({
      priceSnapshot: { waterAmountFen: 3600, deliveryFeeFen: 600, totalAmountFen: 4300 },
    }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({
      priceSnapshot: { waterAmountFen: '36.5', deliveryFeeFen: 600, totalAmountFen: 4200 },
    }))).toThrow(ContractError)
    expect(() => normalizeDeliveryTask(taskRaw({ priceSnapshot: null }))).toThrow(ContractError)
  })

  it('签收三照：合法项归一化为 real 证据，畸形项丢弃不拼残缺证据', () => {
    const task = normalizeDeliveryTask(taskRaw({
      taskStatus: 5,
      version: 5,
      signTime: '20260724120000',
      appealDeadline: '20260725120000',
      locationStatus: 2,
      signPhotos: [
        { type: 1, mediaKey: 'DMAAA', time: '20260724120000' },
        { type: 2, mediaKey: 'DMBBB', time: '20260724120000', latitude: 30.4, longitude: 114.4 },
        // 畸形：缺 mediaKey / 非法 type / 非法时间
        { type: 3, mediaKey: '', time: '20260724120000' },
        { type: 9, mediaKey: 'DMX', time: '20260724120000' },
        { type: 3, mediaKey: 'DMCCC', time: 'not-a-time' },
      ],
    }))
    expect(task.signPhotos).toHaveLength(2)
    expect(task.signPhotos[0]).toMatchObject({
      type: 1,
      label: '门牌',
      recordRef: 'DMAAA',
      evidenceMode: 'real',
    })
    expect(task.signPhotos[1]).toMatchObject({ latitude: 30.4, longitude: 114.4 })
    expect(task.appealDeadline).toBe('20260725120000')
    expect(task.locationStatus).toBe('unrecorded')
  })

  it('locationStatus 映射：1→recorded 2→unrecorded 空→undefined 未知值→unrecorded（声明不得超出证据）', () => {
    expect(normalizeDeliveryTask(taskRaw({ locationStatus: 1 })).locationStatus).toBe('recorded')
    expect(normalizeDeliveryTask(taskRaw({ locationStatus: 2 })).locationStatus).toBe('unrecorded')
    expect(normalizeDeliveryTask(taskRaw({ locationStatus: null })).locationStatus).toBeUndefined()
    expect(normalizeDeliveryTask(taskRaw({ locationStatus: 9 })).locationStatus).toBe('unrecorded')
  })
})

describe('strict number/id helpers', () => {
  it('strictInt 拒绝小数/前导零/指数/越界', () => {
    expect(strictInt('1.5')).toBeUndefined()
    expect(strictInt('05')).toBeUndefined()
    expect(strictInt('1e3')).toBeUndefined()
    expect(strictInt('9007199254740993')).toBeUndefined()
    expect(strictInt(1.5)).toBeUndefined()
    expect(strictInt('42')).toBe(42)
    expect(strictInt(-7)).toBe(-7)
  })

  it('strictEntityId 拒绝 0/负数/前导零/超安全整数 number', () => {
    expect(strictEntityId('0')).toBeUndefined()
    expect(strictEntityId('-3')).toBeUndefined()
    expect(strictEntityId('007')).toBeUndefined()
    // 2^53+1 以 Number 形态传入时精度已丢，必须拒收（写成表达式避免字面量精度告警）
    expect(strictEntityId(2 ** 53 + 1)).toBeUndefined()
    expect(strictEntityId('123')).toBe('123')
    expect(strictEntityId(123)).toBe('123')
    // 字符串形态不受 2^53 限制：后端就是为此才下发字符串
    expect(strictEntityId('9007199254740993')).toBe('9007199254740993')
  })
})

describe('normalizeCourierAdmission', () => {
  it('正常投影 + 站点 id 畸形项过滤', () => {
    const admission = normalizeCourierAdmission({
      accountId: '6',
      userId: 6,
      status: 2,
      applicantName: '李四',
      maskedPhone: '138****2222',
      requestedStationIds: ['1', 2, '00', 'abc'],
      requestedRegion: '光谷片区',
      submittedTime: '20260720000000',
    })
    expect(admission.status).toBe(2)
    expect(admission.requestedStationIds).toEqual(['1', '2'])
    expect(admission.maskedPhone).toBe('138****2222')
  })

  it('status 越界/归属缺失 fail-closed', () => {
    expect(() => normalizeCourierAdmission({ accountId: '6', userId: '6', status: 9 })).toThrow(ContractError)
    expect(() => normalizeCourierAdmission({ accountId: null, userId: '6', status: 1 })).toThrow(ContractError)
  })
})

describe('normalizeDeliveryException', () => {
  const base: DeliveryExceptionRaw = {
    exceptionId: '9',
    taskNo: 'DTABC',
    courierId: '3',
    reason: 'UNREACHABLE',
    description: '联系不上',
    evidenceRefs: ['DMAAA', 7, ''],
    createTime: '20260724130000',
  }

  it('正常归一化：非字符串/空串举证引用被过滤', () => {
    const record = normalizeDeliveryException(base)
    expect(record.reason).toBe('UNREACHABLE')
    expect(record.evidenceRefs).toEqual(['DMAAA'])
  })

  it('原因码白名单外/时间畸形 fail-closed', () => {
    expect(() => normalizeDeliveryException({ ...base, reason: 'WEATHER' })).toThrow(ContractError)
    expect(() => normalizeDeliveryException({ ...base, createTime: '2026-07-24' })).toThrow(ContractError)
  })
})

describe('normalizeDeliveryAppeal', () => {
  const base: DeliveryAppealRaw = {
    appealId: 5,
    orderNo: 'WDABC',
    taskNo: 'DTABC',
    userId: '6',
    appealStatus: 1,
    reason: 'QUANTITY',
    description: '少送一桶',
    receivedCount: '2',
    evidenceRefs: ['DMAAA'],
    createTime: '20260724140000',
  }

  it('正常归一化；裁决新态 5补送待执行 可收下', () => {
    const appeal = normalizeDeliveryAppeal(base)
    expect(appeal.appealId).toBe('5')
    expect(appeal.receivedCount).toBe(2)
    expect(normalizeDeliveryAppeal({ ...base, appealStatus: 5, decisionSummary: '安排补送' }).appealStatus).toBe(5)
  })

  it('状态越界/原因白名单外/实收数量畸形 fail-closed', () => {
    expect(() => normalizeDeliveryAppeal({ ...base, appealStatus: 6 })).toThrow(ContractError)
    expect(() => normalizeDeliveryAppeal({ ...base, reason: 'PRICE' })).toThrow(ContractError)
    expect(() => normalizeDeliveryAppeal({ ...base, receivedCount: '-1' })).toThrow(ContractError)
    expect(() => normalizeDeliveryAppeal({ ...base, receivedCount: '2.5' })).toThrow(ContractError)
  })

  it('配送员举证：畸形项丢弃、evidenceRefs 过滤非字符串', () => {
    const appeal = normalizeDeliveryAppeal({
      ...base,
      courierEvidences: [
        { description: '已按单配送', evidenceRefs: ['DMBBB', 5], time: '20260724150000' },
        { description: '', time: '20260724150000' },
        { description: '缺时间' },
      ],
    })
    expect(appeal.courierEvidences).toHaveLength(1)
    expect(appeal.courierEvidences?.[0].evidenceRefs).toEqual(['DMBBB'])
  })
})

describe('页面阻断判定纯函数（与包A 拒因同源）', () => {
  const at = (taskStatus: 1 | 2 | 3 | 4 | 5 | 6 | 7, extra: Record<string, unknown> = {}) =>
    ({ taskStatus, ...extra }) as Parameters<typeof canSignDeliveryTask>[0]

  it('接单：仅 1待接单且无人认领', () => {
    expect(canAcceptDeliveryTask(at(1))).toBe(true)
    expect(canAcceptDeliveryTask(at(1, { courierId: '3' }))).toBe(false)
    for (const status of [2, 3, 4, 5, 6, 7] as const) {
      expect(canAcceptDeliveryTask(at(status))).toBe(false)
    }
  })

  it('推进：3离站要求当前2；4送达要求当前3；其余一律拒', () => {
    expect(canAdvanceDeliveryTask(at(2), 3)).toBe(true)
    expect(canAdvanceDeliveryTask(at(3), 4)).toBe(true)
    expect(canAdvanceDeliveryTask(at(2), 4)).toBe(false)
    expect(canAdvanceDeliveryTask(at(3), 3)).toBe(false)
    expect(canAdvanceDeliveryTask(at(4), 3)).toBe(false)
  })

  it('签收：仅 4已送达待确认', () => {
    for (const status of [1, 2, 3, 5, 6, 7] as const) {
      expect(canSignDeliveryTask(at(status))).toBe(false)
    }
    expect(canSignDeliveryTask(at(4))).toBe(true)
  })

  it('异常上报：仅履约中 2/3/4', () => {
    expect(canReportDeliveryException(at(2))).toBe(true)
    expect(canReportDeliveryException(at(3))).toBe(true)
    expect(canReportDeliveryException(at(4))).toBe(true)
    for (const status of [1, 5, 6, 7] as const) {
      expect(canReportDeliveryException(at(status))).toBe(false)
    }
  })

  it('创建申诉：仅 5已签收且有签收时间；追加举证：仅 7申诉中', () => {
    expect(canCreateDeliveryAppeal(at(5, { signTime: '20260724120000' }))).toBe(true)
    expect(canCreateDeliveryAppeal(at(5))).toBe(false)
    expect(canCreateDeliveryAppeal(at(7, { signTime: '20260724120000' }))).toBe(false)
    expect(canAppendAppealEvidence(at(7))).toBe(true)
    expect(canAppendAppealEvidence(at(5))).toBe(false)
  })

  it('申诉截止：优先服务端权威值，缺失才按签收+24h 派生', () => {
    expect(appealDeadlineOf(at(5, {
      signTime: '20260724120000',
      appealDeadline: '20260725110000',
    }))).toBe('20260725110000')
    expect(appealDeadlineOf(at(5, { signTime: '20260724120000' }))).toBe('20260725120000')
    expect(appealDeadlineOf(at(5))).toBeUndefined()
  })

  it('申诉窗口展示态：窗口内/已超期/未知（now 或截止缺失一律 unknown）', () => {
    const signed = at(5, { signTime: '20260724120000', appealDeadline: '20260725120000' })
    expect(appealWindowState(signed, '20260725115959')).toBe('open')
    expect(appealWindowState(signed, '20260725120000')).toBe('open')
    expect(appealWindowState(signed, '20260725120001')).toBe('expired')
    expect(appealWindowState(signed, undefined)).toBe('unknown')
    expect(appealWindowState(signed, 'garbage')).toBe('unknown')
    expect(appealWindowState(at(5), '20260725120001')).toBe('unknown')
  })
})
