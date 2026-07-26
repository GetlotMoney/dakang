/**
 * PC Demo 共享审计事件契约（REQ-024 / REQ-050）。
 *
 * Demo 阶段仅记录前端 Mock 状态变化，页面刷新后重置；不写入真实
 * operation_log/ws_domain_event，也不触发支付、退款、余额、水卡或设备副作用。
 * 接入真实服务时保持字段契约不变，数据源替换为领域事件与操作日志聚合接口。
 */

export type DemoAuditTone = 'success' | 'warning' | 'danger' | 'info' | 'primary'

export interface DemoAuditEvent {
  id: number
  eventTypeLabel: string
  /** 页面优先展示的业务主键，如订单号、指令号或配置版本。 */
  eventKey: string
  /** 用于订单、指令、任务等对象反向检索同一事件。 */
  relatedKeys: string[]
  actorLabel: string
  oldStatus?: string
  newStatus?: string
  detail: string
  time: string
  tone: DemoAuditTone
  /** 可从总览继续进入的正式 PC 页面；不承载跨端模拟动作。 */
  targetPath?: string
}

export interface DemoAuditEventInput extends Omit<DemoAuditEvent, 'id' | 'time'> {
  time?: string
}

const events: DemoAuditEvent[] = [
  {
    id: 1,
    eventTypeLabel: '指令状态变化',
    eventKey: 'CMD20260712162849678024',
    relatedKeys: ['CMD20260712162849678024', 'DK-DEV-0001'],
    actorLabel: '设备',
    oldStatus: '已回执',
    newStatus: '执行失败',
    detail: '已回执 → 执行失败：阀门卡滞',
    time: '20260712162851',
    tone: 'danger',
    targetPath: '/device/command?cmdNo=CMD20260712162849678024'
  },
  {
    id: 2,
    eventTypeLabel: '订单状态变化',
    eventKey: 'WO20260712091002',
    relatedKeys: ['WO20260712091002', 'CMD20260712101200002', 'DK-DEV-0001'],
    actorLabel: '系统',
    oldStatus: '出水中',
    newStatus: '异常待补偿',
    detail: '出水中 → 异常待补偿：实际 6000ml / 计划 10000ml',
    time: '20260712101246',
    tone: 'warning',
    targetPath: '/order/index?orderNo=WO20260712091002'
  },
  {
    id: 3,
    eventTypeLabel: '配送节点变化',
    eventKey: 'DT-2006',
    relatedKeys: ['DT-2006', 'WO20260712091006'],
    actorLabel: '配送端·李师傅',
    oldStatus: '已送达',
    newStatus: '三照签收',
    detail: '已送达 → 三照签收（GPS 与收货地址一致）',
    time: '20260711183000',
    tone: 'success',
    targetPath: '/order/delivery?keyword=WO20260712091006'
  },
  {
    id: 4,
    eventTypeLabel: '指令状态变化',
    eventKey: 'CMD20260712075500010',
    relatedKeys: ['CMD20260712075500010', 'WO20260712091010', 'DK-DEV-0002'],
    actorLabel: '系统',
    oldStatus: '已下发',
    newStatus: '超时',
    detail: '已下发 → 超时：30 秒未收到 ACK（监控 worker 判定）',
    time: '20260712075601',
    tone: 'danger',
    targetPath: '/order/index?orderNo=WO20260712091010'
  },
  {
    id: 5,
    eventTypeLabel: '订单状态变化',
    eventKey: 'WO20260712091004',
    relatedKeys: ['WO20260712091004'],
    actorLabel: '用户端·钱女士',
    oldStatus: '待支付',
    newStatus: '已完成',
    detail: '待支付 → 已完成：微信支付回调（幂等校验通过）',
    time: '20260711203010',
    tone: 'success',
    targetPath: '/order/index?orderNo=WO20260712091004'
  },
  {
    id: 6,
    eventTypeLabel: '告警产生',
    eventKey: 'DK-DEV-0002',
    relatedKeys: ['DK-DEV-0002', 'WO20260712091010'],
    actorLabel: '系统',
    newStatus: '离线告警',
    detail: '设备离线超 3 个心跳周期，产生离线告警并阻断下单',
    time: '20260712080000',
    tone: 'danger',
    targetPath: '/device/index?deviceNo=DK-DEV-0002'
  },
  {
    id: 7,
    eventTypeLabel: '重复 ACK 拦截',
    eventKey: 'CMD20260711100500011',
    relatedKeys: ['CMD20260711100500011', 'WO20260711091011', 'DK-DEV-0001'],
    actorLabel: '系统',
    oldStatus: '已回执',
    newStatus: '已回执（状态不变）',
    detail: '同一 cmdNo 第二次 ACK 被幂等忽略；订单只结算一次、水卡只扣减一次',
    time: '20260711100504',
    tone: 'warning',
    targetPath: '/order/index?orderNo=WO20260711091011'
  },
  {
    id: 8,
    eventTypeLabel: '错设备 ACK 拦截',
    eventKey: 'CMD20260711102000012',
    relatedKeys: ['CMD20260711102000012', 'WO20260711091012', 'DK-DEV-0001', 'DK-DEV-0002'],
    actorLabel: '系统',
    oldStatus: '已下发',
    newStatus: '已下发（状态不变）',
    detail: '目标设备 DK-DEV-0001，实际上报 DK-DEV-0002；ACK 被拒绝，等待目标设备回执',
    time: '20260711102003',
    tone: 'danger',
    targetPath: '/order/index?orderNo=WO20260711091012'
  },
  {
    id: 9,
    eventTypeLabel: '指令状态变化',
    eventKey: 'CMD20260711102000012',
    relatedKeys: ['CMD20260711102000012', 'WO20260711091012', 'DK-DEV-0001'],
    actorLabel: '设备·DK-DEV-0001',
    oldStatus: '已下发',
    newStatus: '已回执',
    detail: '目标设备回执通过校验，指令继续进入执行结果等待',
    time: '20260711102006',
    tone: 'primary',
    targetPath: '/order/index?orderNo=WO20260711091012'
  },
  {
    id: 10,
    eventTypeLabel: '取水订单完成',
    eventKey: 'WO20260712091001',
    relatedKeys: ['WO20260712091001', 'CMD20260712091500001', 'DK-DEV-0001'],
    actorLabel: '系统',
    oldStatus: '出水中',
    newStatus: '已完成',
    detail: '目标设备 ACK/result 校验通过，按实际 10000ml 完成唯一一次水量扣减',
    time: '20260712091530',
    tone: 'success',
    targetPath: '/order/index?orderNo=WO20260712091001'
  },
  {
    id: 11,
    eventTypeLabel: '指令状态变化',
    eventKey: 'CMD20260712113000003',
    relatedKeys: ['CMD20260712113000003', 'WO20260712091003', 'DK-DEV-0001'],
    actorLabel: '设备·DK-DEV-0001',
    oldStatus: '已下发',
    newStatus: '已回执',
    detail: 'ACK 已通过目标设备校验，订单保持出水中，等待 result',
    time: '20260712113002',
    tone: 'primary',
    targetPath: '/order/index?orderNo=WO20260712091003'
  },
  {
    id: 12,
    eventTypeLabel: '指令状态变化',
    eventKey: 'CMD20260712140500009',
    relatedKeys: ['CMD20260712140500009', 'WO20260712091009', 'DK-DEV-0001'],
    actorLabel: '设备·DK-DEV-0001',
    oldStatus: '已回执',
    newStatus: '执行失败',
    detail: '故障码 E21 阀门卡滞，实际出水 0ml；订单取消并登记唯一一次余额退回',
    time: '20260712140510',
    tone: 'danger',
    targetPath: '/order/index?orderNo=WO20260712091009'
  }
]

let nextId = Math.max(...events.map((event) => event.id)) + 1
let mockTimeSequence = 0

function nextMockTime(): string {
  const date = new Date(Date.UTC(2026, 6, 14, 14, 30, mockTimeSequence++))
  const part = (value: number) => String(value).padStart(2, '0')
  return `${date.getUTCFullYear()}${part(date.getUTCMonth() + 1)}${part(date.getUTCDate())}${part(date.getUTCHours())}${part(date.getUTCMinutes())}${part(date.getUTCSeconds())}`
}

const cloneEvent = (event: DemoAuditEvent): DemoAuditEvent => ({
  ...event,
  relatedKeys: [...event.relatedKeys]
})

/** 记录一条前端 Mock 审计事件；只影响当前页面会话，刷新后恢复。 */
export function recordDemoAuditEvent(input: DemoAuditEventInput): DemoAuditEvent {
  const event: DemoAuditEvent = {
    ...input,
    id: nextId++,
    time: input.time || nextMockTime(),
    relatedKeys: [...new Set([input.eventKey, ...input.relatedKeys])]
  }
  events.unshift(event)
  return cloneEvent(event)
}

/** 同步读取，供订单追溯等现有 Mock 聚合函数使用。 */
export function getDemoAuditEvents(relatedKeys?: string[], limit = 30): DemoAuditEvent[] {
  const keys = new Set((relatedKeys || []).filter(Boolean))
  const matched = keys.size
    ? events.filter(
        (event) => keys.has(event.eventKey) || event.relatedKeys.some((key) => keys.has(key))
      )
    : events

  return matched
    .slice()
    .sort((left, right) => right.time.localeCompare(left.time) || right.id - left.id)
    .slice(0, limit)
    .map(cloneEvent)
}

/** 总览使用的异步契约；接真时替换为领域事件聚合接口。 */
export function fetchDemoAuditEvents(limit = 30): Promise<DemoAuditEvent[]> {
  return new Promise((resolve) =>
    setTimeout(() => resolve(getDemoAuditEvents(undefined, limit)), 120)
  )
}
