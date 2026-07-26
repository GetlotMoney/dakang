/**
 * 字典类型常量定义
 *
 * 所有枚举字段对应的字典 type 值必须在此定义。
 * 字典数据存储于 api_dict_type 和 api_dict_data 表。
 *
 * @module constants/dict
 * @usage
 *   import { DictTypeEnum } from '@/constants/dict'
 *   const statusOptions = await fetchDictByTypes([DictTypeEnum.状态])
 */

/** 通用字典类型（系统底座） */
export const DictTypeEnum = {
  /** 是否 */
  是否: '1',
  /** 禁用状态（1正常 2禁用） */
  禁用状态: '10',
  /** 性别 */
  性别: '20',
  /** 系统菜单类型 */
  菜单类型: '50',
  /** 登录类型 */
  登录类型: '120',

  // ===== 饮水业务字典（1300+，与后端 ApiEnum.DictType 一一对应）=====
  /** 设备在线状态：1在线 2离线 3未激活 */
  设备在线状态: '1300',
  /** 设备运行状态：1空闲 2出水中 3故障 4维护中 5锁机 */
  设备运行状态: '1301',
  /** 设备二维码类型 */
  二维码类型: '1303',
  /** 故障/告警等级 */
  故障等级: '1304',
  /** 设备上行消息类型 */
  设备消息类型: '1310',
  /** 设备消息处理状态 */
  设备消息状态: '1311',
  /** 设备指令类型 */
  指令类型: '1320',
  /** 设备指令状态：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成 */
  指令状态: '1321',
  /** 套餐状态：1在售 2下架 */
  套餐状态: '1330',
  /** 水卡类型：1虚拟卡 2实体卡 */
  水卡类型: '1331',
  /** 水卡状态：1正常 2冻结 3已过期 4已注销 */
  水卡状态: '1332',
  /** 水卡成员授权状态 */
  成员授权状态: '1333',
  /** 订单类型：1扫码取水 2购卡充值 3水配送 */
  订单类型: '1340',
  /** 订单状态：1待支付 2已支付 3出水中 4已完成 5已取消 6异常待补偿 7已退款 8部分退款 */
  订单状态: '1341',
  /** 支付状态 */
  支付状态: '1342',
  /** 退款状态 */
  退款状态: '1343',
  /** 钱包流水类型 */
  钱包流水类型: '1344',
  /** 分账状态 */
  分账状态: '1345',
  /** 支付方式：1微信支付 2水卡余额 3水卡水量 */
  支付方式: '1346',
  /** 配送员状态：1待审核 2启用 3停用 4审核驳回 */
  配送员状态: '1350',
  /** 配送任务状态：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中 */
  配送任务状态: '1351',
  /** 申诉状态 */
  申诉状态: '1352',
  /** 告警类型 */
  告警类型: '1360',
  /** 告警状态 */
  告警状态: '1361',
  /** 工单状态 */
  工单状态: '1362',
  /** 领域事件类型 */
  领域事件类型: '1363',
  /** 操作端口：1公司后台 2用户端 3机主端 4配送端 5渠道端 6系统 7设备 */
  操作端口: '1364',
  /** 滤芯状态 */
  滤芯状态: '1383'
} as const

export type DictTypeKey = keyof typeof DictTypeEnum
export type DictTypeValue = (typeof DictTypeEnum)[DictTypeKey]

/** 字典数据项 */
export interface DictData {
  id: number
  dictClass: string | null
  dictDefaultFlag: number
  dictType: string
  dictSort: number
  dictValue: number
  dictLabel: string
}

/** 字典类型（包含数据列表） */
export interface DictType {
  id: number
  dictName: string
  dictType: string
  dictRemark: string
  dictDataList: DictData[]
}
