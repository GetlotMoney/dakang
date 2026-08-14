import { describe, expect, it } from 'vitest'
import {
  AFTER_SALE_QUEUES,
  AFTER_SALE_TODO_STATUSES,
  businessNowStamp,
  financeQueueOf,
  parseReconcileBizKey,
  SPLIT_QUEUES,
  SplitStatus,
  type FinanceQueue
} from './finance-governance'
import { AfterSaleActionStatus } from './after-sale-entry'

/**
 * 四视图是既有状态字典值的归并而不是新枚举，两条判据都要能红：
 * ① 每个字典值有且只有一个归宿——漏一个状态，那批记录会从所有队列里消失；
 * ② 「业务事实」不带状态条件——它一带条件就不再是全部事实。
 *
 * 期望值必须从状态常量表整体取（Object.values），不能在用例里再抄一份字典值：
 * 抄一份的话，字典新增一个值又漏配队列时两边一起漏，用例照绿——正是本判据要防的那条。
 */
function assertPartition(queues: readonly FinanceQueue[], dict: Record<string, number>) {
  const dictValues = Object.values(dict)
  const fact = queues.find((queue) => queue.key === 'fact')
  expect(fact?.statuses).toEqual([])

  const grouped = queues.filter((queue) => queue.key !== 'fact').flatMap((queue) => queue.statuses)
  expect([...grouped].sort((a, b) => a - b)).toEqual([...dictValues].sort((a, b) => a - b))
  expect(new Set(grouped).size).toBe(grouped.length)
}

describe('财务治理四视图', () => {
  it('分账状态字典每个值各归一个视图', () => {
    assertPartition(SPLIT_QUEUES, SplitStatus)
  })

  it('售后执行状态字典每个值各归一个视图', () => {
    assertPartition(AFTER_SALE_QUEUES, AfterSaleActionStatus)
  })

  it('视图键非法时回落到业务事实而不是抛错', () => {
    expect(financeQueueOf(SPLIT_QUEUES, 'manual').key).toBe('manual')
    expect(financeQueueOf(SPLIT_QUEUES, 'nope' as never).key).toBe('fact')
  })

  /**
   * 订单中心顶部一格＝一个待办状态值。2执行中 曾落在所有格子之外（卡片自写一套切分），
   * 动作停在该态时顶部全是 0，队列看着已清空。
   */
  it('待办状态＝待处理与需人工两组的全部状态值，一个都不落在格子之外', () => {
    const expected = [...financeQueueOf(AFTER_SALE_QUEUES, 'pending').statuses]
      .concat(financeQueueOf(AFTER_SALE_QUEUES, 'manual').statuses)
      .sort((a, b) => a - b)
    expect([...AFTER_SALE_TODO_STATUSES]).toEqual(expected)
    expect(AFTER_SALE_TODO_STATUSES).toContain(AfterSaleActionStatus.PROCESSING)
    expect(AFTER_SALE_TODO_STATUSES).not.toContain(AfterSaleActionStatus.SUCCESS)
    expect(AFTER_SALE_TODO_STATUSES).not.toContain(AfterSaleActionStatus.TERMINATED)
  })
})

/**
 * 差异台账是资金证据面：跳错对象会让运营照着别人的账做结论。
 * 因此业务键与核对位置必须互相印证，任一不符一律 unknown（不给跳转），而不是「尽量猜一个」。
 *
 * 夹具一律照服务端实际写入的形态取（ReconcileServiceImpl 各维度）：
 * 订单号是 2 位字母前缀 + 30 位大写十六进制，支付事实维度的键还带一位支付来源前缀。
 */
const ORDER_NO = 'WD4F2A9B8C7D6E5F0A1B2C3D4E5F60'
const RECHARGE_ORDER_NO = 'RC0123456789ABCDEF0123456789AB'

describe('对账差异业务键解析', () => {
  it('支付事实维度的两种键都解析到同一个订单', () => {
    // 反向半场（订单已收款无支付事实）写裸单号
    expect(parseReconcileBizKey(ORDER_NO, 'payment-fact')).toEqual({
      kind: 'order',
      orderNo: ORDER_NO
    })
    // 正向四类差异写「支付来源:订单号」，前缀不能把整行判成对象待核实
    expect(parseReconcileBizKey(`1:${ORDER_NO}`, 'payment-fact')).toEqual({
      kind: 'order',
      orderNo: ORDER_NO
    })
    expect(parseReconcileBizKey(`2:${ORDER_NO}`, 'payment-fact').orderNo).toBe(ORDER_NO)
  })

  it('订单流水与分账合计维度按裸订单号解析', () => {
    expect(parseReconcileBizKey(RECHARGE_ORDER_NO, 'order-flow')).toEqual({
      kind: 'order',
      orderNo: RECHARGE_ORDER_NO
    })
    expect(parseReconcileBizKey(ORDER_NO, 'split-sum').kind).toBe('order')
  })

  it('水卡与收益账本键解析出对象ID，流水键额外带流水ID', () => {
    expect(parseReconcileBizKey('card:9007199254740993', 'card-ledger')).toEqual({
      kind: 'card',
      cardId: '9007199254740993',
      flowId: undefined
    })
    expect(parseReconcileBizKey('income:42:flow:77', 'income-ledger')).toEqual({
      kind: 'income',
      userId: '42',
      flowId: '77'
    })
  })

  it('超出安全整数范围的ID逐字保留，不被数值化', () => {
    const parsed = parseReconcileBizKey('card:9223372036854775807', 'card-ledger')
    expect(parsed.cardId).toBe('9223372036854775807')
  })

  it('键与核对位置对不上时判 unknown', () => {
    expect(parseReconcileBizKey('card:12', 'income-ledger').kind).toBe('unknown')
    expect(parseReconcileBizKey('income:12', 'card-ledger').kind).toBe('unknown')
    expect(parseReconcileBizKey(ORDER_NO, 'card-ledger').kind).toBe('unknown')
    expect(parseReconcileBizKey('card:12', 'payment-fact').kind).toBe('unknown')
    // 来源前缀只在支付事实维度成立
    expect(parseReconcileBizKey(`1:${ORDER_NO}`, 'order-flow').kind).toBe('unknown')
    expect(parseReconcileBizKey(`1:${ORDER_NO}`, 'split-sum').kind).toBe('unknown')
  })

  it('空键、截断键与未登记维度一律 unknown', () => {
    expect(parseReconcileBizKey(undefined, 'payment-fact').kind).toBe('unknown')
    expect(parseReconcileBizKey('   ', 'payment-fact').kind).toBe('unknown')
    expect(parseReconcileBizKey('WO0123...F0123', 'payment-fact').kind).toBe('unknown')
    expect(parseReconcileBizKey(`1:WO0123...F0123`, 'payment-fact').kind).toBe('unknown')
    expect(parseReconcileBizKey('card:12:flow:', 'card-ledger').kind).toBe('unknown')
    expect(parseReconcileBizKey(ORDER_NO, 'brand-new-dim').kind).toBe('unknown')
  })
})

/**
 * 生效判定拿 14 位串逐位比大小，两边必须是同一个墙钟：
 * 取浏览器本地时钟的话，运营机器时区一变就会把已生效的版本标成未来待生效。
 */
describe('业务时区时刻', () => {
  it('固定按业务时区(Asia/Shanghai)出 14 位串，与运行机器时区无关', () => {
    expect(businessNowStamp(new Date('2026-08-13T02:00:00Z'))).toBe('20260813100000')
    // 业务时区已跨日、UTC 仍是前一天
    expect(businessNowStamp(new Date('2026-08-12T16:00:05Z'))).toBe('20260813000005')
  })

  it('午夜出 00 时而不是 24 时，字符串序才等于时间序', () => {
    expect(businessNowStamp(new Date('2026-08-12T16:00:00Z')).slice(8, 10)).toBe('00')
  })
})
