/**
 * PC 财务治理面的纯策略层。
 *
 * 本文件不依赖 HTTP、Store 或组件：队列归组、对账业务键解析与生效时刻判定是
 * 「哪一行该被谁看、能追到谁、哪一版此刻算数」的判据，必须能被单测逐条钉住，也必须只有一份——
 * 分账页、对账页、售后台账各写一份分组，就是三份治理口径。
 * 全部为读侧判定，不含任何资金写入语义。
 */

import { AfterSaleActionStatus } from './after-sale-entry'

/**
 * 分账状态(1345) 代码常量。只用于代码分支与队列归组；下拉选项与中文标签一律取字典，
 * 页面不得据此另写一份状态文案。
 */
export const SplitStatus = {
  PENDING: 1,
  SETTLED: 2,
  FAILED: 3,
  REVERSED: 4
} as const

/** 对账任务状态(1380) 代码常量，同上只作分支判断。 */
export const ReconcileTaskStatus = {
  RUNNING: 1,
  BALANCED: 2,
  DIFF: 3
} as const

/**
 * 财务治理四视图。它不是新枚举：四个键只是既有状态字典值的视图归并，
 * 每次查询下发的仍是字典里的单个状态值，服务端筛选条件一个字都没变。
 */
export type FinanceQueueKey = 'fact' | 'pending' | 'manual' | 'done'

export interface FinanceQueue {
  key: FinanceQueueKey
  label: string
  /** 归入本视图的既有字典状态值；空数组＝不带状态条件（全部事实）。 */
  statuses: readonly number[]
  /** 视图说明，用于页面一行提示。 */
  hint: string
}

/** 分账明细的四视图（状态值取自 1345）。 */
export const SPLIT_QUEUES: readonly FinanceQueue[] = [
  { key: 'fact', label: '业务事实', statuses: [], hint: '全部分账记录' },
  { key: 'pending', label: '待处理', statuses: [SplitStatus.PENDING], hint: '等待入账的分账记录' },
  {
    key: 'manual',
    label: '需人工',
    statuses: [SplitStatus.FAILED],
    hint: '入账未成功，需人工核对处理'
  },
  {
    key: 'done',
    label: '已完成',
    statuses: [SplitStatus.SETTLED, SplitStatus.REVERSED],
    hint: '已入账或已被退款回退的记录'
  }
]

/**
 * 售后台账（退款/补偿/补送）的四视图（状态值取自 1372）。
 * 订单中心顶部待办计数按本组切分（见 AFTER_SALE_TODO_STATUSES），台账筛选下发的仍是组内单个状态值；
 * 这是唯一一份可用的归组定义，不得再另写一份。
 */
export const AFTER_SALE_QUEUES: readonly FinanceQueue[] = [
  { key: 'fact', label: '业务事实', statuses: [], hint: '全部售后处理记录' },
  {
    key: 'pending',
    label: '待处理',
    statuses: [AfterSaleActionStatus.PENDING, AfterSaleActionStatus.PROCESSING],
    hint: '等待处理或正在处理的记录'
  },
  {
    key: 'manual',
    label: '需人工',
    statuses: [AfterSaleActionStatus.RECONCILIATION_REQUIRED, AfterSaleActionStatus.RETRY_WAIT],
    hint: '需人工核对处理的记录'
  },
  {
    key: 'done',
    label: '已完成',
    statuses: [AfterSaleActionStatus.SUCCESS, AfterSaleActionStatus.TERMINATED],
    hint: '已到终态的记录'
  }
]

/** 按键取视图定义；键非法时回落到首个视图而不是抛错（视图键来自地址栏，可被随手改坏）。 */
export function financeQueueOf(
  queues: readonly FinanceQueue[],
  key: FinanceQueueKey
): FinanceQueue {
  return queues.find((queue) => queue.key === key) ?? queues[0]
}

/** 待办口径＝未到终态的两组：待处理 + 需人工。终态组与「业务事实」不属于待办。 */
const TODO_QUEUE_KEYS: readonly FinanceQueueKey[] = ['pending', 'manual']

/**
 * 售后待办涉及的全部执行状态值（升序）。订单中心顶部一格对应这里的一个状态值：
 * 计数与下钻用的是同一个值，且本组新增一个状态就自动多一格——不会再有状态落在所有格子之外
 * （2执行中 曾因卡片自写一套切分而无人可见，动作停在该态时顶部三格全是 0）。
 */
export const AFTER_SALE_TODO_STATUSES: readonly number[] = AFTER_SALE_QUEUES.filter((queue) =>
  TODO_QUEUE_KEYS.includes(queue.key)
)
  .flatMap((queue) => [...queue.statuses])
  .sort((a, b) => a - b)

// ============ 共键解析（对账差异 → 可追溯对象） ============

/** 差异行指向的对象种类。unknown＝无法逐字解析，一律不给跳转入口。 */
export type ReconcileBizKeyKind = 'order' | 'card' | 'income' | 'unknown'

export interface ReconcileBizKeyRef {
  kind: ReconcileBizKeyKind
  /** 订单号（kind=order） */
  orderNo?: string
  /** 水卡ID（kind=card）；Long 恒 string */
  cardId?: string
  /** 收益人用户ID（kind=income）；Long 恒 string */
  userId?: string
  /** 同一账本键上的流水ID，仅账本连续性差异才有 */
  flowId?: string
}

const CARD_KEY = /^card:(\d+)(?::flow:(\d+))?$/
const INCOME_KEY = /^income:(\d+)(?::flow:(\d+))?$/
/** 订单号形态：两位字母前缀 + 定长大写哈希，逐字校验避免把截断串当成订单号。 */
const ORDER_NO_KEY = /^[A-Z]{2}[0-9A-Z]{16,32}$/
/**
 * 支付事实维度的键带支付来源前缀（服务端按来源隔离口径写入，形如 `1:WD…`），
 * 同维度反向半场（订单已收款无支付事实）写的是裸订单号，两种都要能解析成同一个订单。
 */
const PAY_SOURCE_PREFIXED_ORDER_NO = /^(\d+):([A-Z]{2}[0-9A-Z]{16,32})$/

/**
 * 差异行的键就是订单号的核对维度。三个维度都由服务端逐个跑批产生，
 * 漏登记一个＝该维度整类差异在台账上没有跳转入口、还被标成对象待核实。
 */
const ORDER_NO_DIMENSIONS: readonly string[] = ['payment-fact', 'order-flow', 'split-sum']

/**
 * 解析差异行的业务键。核对维度与键前缀必须互相印证，任一不符即判 unknown：
 * 差异台账是资金证据面，跳错对象比不跳更危险（运营会照着别人的账做结论）。
 */
export function parseReconcileBizKey(bizKey?: string, checkDimension?: string): ReconcileBizKeyRef {
  const key = (bizKey || '').trim()
  const dimension = (checkDimension || '').trim()
  if (!key) return { kind: 'unknown' }

  const card = CARD_KEY.exec(key)
  if (card) {
    if (dimension !== 'card-ledger') return { kind: 'unknown' }
    return { kind: 'card', cardId: card[1], flowId: card[2] }
  }

  const income = INCOME_KEY.exec(key)
  if (income) {
    if (dimension !== 'income-ledger') return { kind: 'unknown' }
    return { kind: 'income', userId: income[1], flowId: income[2] }
  }

  if (ORDER_NO_DIMENSIONS.includes(dimension)) {
    if (ORDER_NO_KEY.test(key)) return { kind: 'order', orderNo: key }
    // 来源前缀只在支付事实维度成立，其余维度带前缀即判 unknown（宁可不跳也不猜）
    const prefixed = dimension === 'payment-fact' ? PAY_SOURCE_PREFIXED_ORDER_NO.exec(key) : null
    if (prefixed) return { kind: 'order', orderNo: prefixed[2] }
  }
  return { kind: 'unknown' }
}

// ============ 业务时区时刻（生效判定） ============

/** 业务时间契约固定为 Asia/Shanghai：生效时间等 14 位时刻串都按这个墙钟写入。 */
const BUSINESS_TIME_ZONE = 'Asia/Shanghai'

let businessStampFormatter: Intl.DateTimeFormat | undefined

/**
 * 当下的业务时区 14 位时刻串（yyyyMMddHHmmss）。
 *
 * 生效与否是拿它与服务端写入的 EFFECT_TIME 逐位比大小，因此两边必须是同一个墙钟：
 * 直接取浏览器本地时钟，运营机器时区一变（或时钟有偏差）就会把已生效的新比例标成未来待生效，
 * 于是页面把上一版旧比例指认成现行比例——对账与答复商户全跟着错。
 */
export function businessNowStamp(now: Date = new Date()): string {
  if (!businessStampFormatter) {
    businessStampFormatter = new Intl.DateTimeFormat('en-US', {
      timeZone: BUSINESS_TIME_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23'
    })
  }
  const parts = businessStampFormatter.formatToParts(now)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}${part('month')}${part('day')}${part('hour')}${part('minute')}${part('second')}`
}
