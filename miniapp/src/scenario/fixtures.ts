import type { AccountContext, CapabilityCode } from '@/api/account'
import type {
  CardDetail,
  DeliveryAddress,
  FamilyProfile,
  FamilyRewardRecord,
} from '@/api/card'
import type { PackageSummary, StationSummary, WaterType } from '@/api/catalog'
import type {
  CourierAdmission,
  DeliveryExceptionRecord,
  DeliveryTask,
} from '@/api/delivery'
import type {
  DeviceDetail,
  OwnerServiceRequest,
  ScanSession,
  WaterEligibility,
} from '@/api/device'
import type { MessageItem } from '@/api/message'
import type { DeliveryAppeal, OrderDetail } from '@/api/order'
import { externalSnapshotMeta } from '@/api/common'

export interface ScenarioCard extends CardDetail {
  userId: string
}

export interface ScenarioAddress extends DeliveryAddress {
  userId: string
}

export interface ScenarioFamilyProfile extends FamilyProfile {
  userId: string
}

export interface PrototypeAuditEvent {
  auditId: string
  accountId: string
  actingCapability: CapabilityCode
  portal: 'miniapp'
  action: string
  objectType: string
  objectId: string
  time: string
  result: 'success' | 'rejected'
}

export interface ScenarioState {
  now: string
  activeAccountId: string
  orderSequence: number
  rechargeRequestSequence: number
  auditSequence: number
  scanSequence: number
  messageSequence: number
  accounts: AccountContext[]
  waterTypes: WaterType[]
  stations: StationSummary[]
  packages: PackageSummary[]
  devices: DeviceDetail[]
  ownerServiceRequests: OwnerServiceRequest[]
  cards: ScenarioCard[]
  addresses: ScenarioAddress[]
  familyProfiles: ScenarioFamilyProfile[]
  familyRewardRecords: FamilyRewardRecord[]
  orderDetails: OrderDetail[]
  deliveryTasks: DeliveryTask[]
  courierAdmissions: CourierAdmission[]
  deliveryExceptions: DeliveryExceptionRecord[]
  deliveryAppeals: DeliveryAppeal[]
  messages: MessageItem[]
  scanSession: ScanSession
  /** 演示期间由扫码适配器追加的短期会话；与 scanSession 一样按过期与消费判定失效。 */
  scanSessions: ScanSession[]
  /** 已被下单消费的扫码会话，二次使用一律按已失效拒绝。 */
  consumedScanSessionIds: string[]
  waterEligibility: WaterEligibility
  auditEvents: PrototypeAuditEvent[]
}

export const initialScenario: ScenarioState = {
  now: '20260716180000',
  activeAccountId: 'ACCOUNT-USER-001',
  orderSequence: 9001,
  rechargeRequestSequence: 1,
  auditSequence: 1,
  scanSequence: 2,
  messageSequence: 1,
  accounts: [
    {
      // 纯配送员（PC 事实源：ws_courier#1.USER_ID=2，已启用）；设备归属与他无关。
      accountId: 'ACCOUNT-WORKER-002',
      userId: '2',
      userName: '李配送',
      userPhone: '13800001111',
      capabilities: ['USER_BASE', 'COURIER_APPLY', 'COURIER_WORK'],
      courierScope: {
        courierId: '1',
        status: 2,
        stationIds: ['1', '2'],
        serviceRegion: '武汉东湖高新区',
      },
    },
    {
      // 机主账号（PC 事实源：ws_device 两台设备 OWNER_USER_ID=4）；
      // 水站范围按名下设备所在站派生（站 1/站 2 的 OWNER_USER_ID 种子为空，仅作聚合口径）。
      accountId: 'ACCOUNT-OWNER-004',
      userId: '4',
      userName: '赵先生',
      userPhone: '13700003333',
      capabilities: ['USER_BASE', 'COURIER_APPLY', 'OWNER_VIEW', 'OWNER_SERVICE'],
      ownerScope: {
        stationIds: ['1', '2'],
        deviceNos: ['DK-DEV-0001', 'DK-DEV-0002'],
      },
    },
    {
      accountId: 'ACCOUNT-USER-001',
      userId: '1',
      userName: '张女士',
      userPhone: '13900001111',
      capabilities: ['USER_BASE', 'COURIER_APPLY'],
    },
    {
      // 权益阻断演示账号（PC 事实源：ws_user#5 无任何水卡）：用于 S04 无卡阻断故事。
      accountId: 'ACCOUNT-USER-003',
      userId: '5',
      userName: '钱女士',
      userPhone: '13600004444',
      capabilities: ['USER_BASE', 'COURIER_APPLY'],
    },
  ],
  waterTypes: [
    { id: '1', name: '纯净水（占位）', enabled: true, placeholder: true },
    { id: '2', name: '矿物质水（占位）', enabled: true, placeholder: true },
    { id: '3', name: '待确认水种3', enabled: true, placeholder: true },
    { id: '4', name: '待确认水种4', enabled: true, placeholder: true },
    { id: '5', name: '待确认水种5', enabled: true, placeholder: true },
    { id: '6', name: '待确认水种6', enabled: true, placeholder: true },
    { id: '7', name: '待确认水种7', enabled: true, placeholder: true },
    { id: '8', name: '待确认水种8', enabled: true, placeholder: true },
  ],
  stations: [
    {
      id: '1',
      stationName: '光谷软件园水站',
      address: '光谷软件园 A1 栋一楼大厅',
      distanceMeters: 680,
      availableOutletCount: 2,
      onlineDeviceCount: 1,
      status: 'OPEN',
    },
    {
      id: '2',
      stationName: '南湖社区水站',
      address: '南湖佰港城北门',
      distanceMeters: 3200,
      availableOutletCount: 0,
      onlineDeviceCount: 0,
      status: 'MAINTENANCE',
    },
  ],
  packages: [
    {
      // PC 事实源 ws_package#1。
      id: '1',
      packageName: '100元500升卡（原型）',
      payAmountFen: 10000,
      waterMl: 500000,
      bonusAmountFen: 0,
      expireDays: 365,
    },
    {
      // PC 事实源 ws_package#2：纯余额充值（waterMl=0），按出水口单价计费。
      id: '2',
      packageName: '50元充值(送5元)',
      payAmountFen: 5000,
      waterMl: 0,
      bonusAmountFen: 500,
      expireDays: null,
    },
  ],
  devices: [
    {
      deviceNo: 'DK-DEV-0001',
      deviceName: '光谷1号机',
      stationId: '1',
      stationName: '光谷软件园水站',
      onlineStatus: 'ONLINE',
      runStatus: 'IDLE',
      lastHeartbeat: '20260716155950',
      tds: 12,
      temperatureCelsius: 24.6,
      filterPercent: 68,
      signalDbm: -67,
      reportTime: '20260716155950',
      outlets: [
        {
          outletId: '1',
          outletNo: 1,
          waterTypeId: '1',
          waterTypeName: '纯净水（占位）',
          // 单价对齐 PC 种子 ws_device_outlet.OUTLET_PRICE='20'（20 分/升）。
          unitPriceFenPerLiter: 20,
          available: true,
        },
        {
          outletId: '2',
          outletNo: 2,
          waterTypeId: '2',
          waterTypeName: '矿物质水（占位）',
          unitPriceFenPerLiter: 30,
          available: true,
        },
      ],
    },
    {
      deviceNo: 'DK-DEV-0002',
      deviceName: '南湖1号机',
      stationId: '2',
      stationName: '南湖社区水站',
      onlineStatus: 'OFFLINE',
      runStatus: 'FAULT',
      lastHeartbeat: '20260716155000',
      lastFaultCode: 'E003',
      reportTime: '20260716155000',
      outlets: [
        {
          outletId: '3',
          outletNo: 1,
          waterTypeId: '1',
          waterTypeName: '纯净水（占位）',
          unitPriceFenPerLiter: 20,
          available: true,
        },
      ],
    },
  ],
  ownerServiceRequests: [],
  cards: [
    {
      // PC 事实源 ws_card#1：张女士主卡（虚拟卡，正常）。
      userId: '1',
      cardId: '1',
      cardNo: 'VC20260710000001',
      cardType: 1,
      cardStatus: 1,
      balanceFen: 5500,
      balanceMl: 500000,
      expireTime: '20270710120000',
      packageName: '100元500升卡（原型）',
      scopeDescription: '光谷软件园水站 / 光谷1号机 / 1、2号出水口',
      members: [
        {
          // PC 种子 ws_card_member 暂为空，本成员为 S05 演示数据（已上报补种子建议）。
          memberId: '1',
          memberUserId: '4',
          memberName: '赵先生',
          maskedPhone: '137****3333',
          dayLimitMl: 20000,
          enabled: true,
        },
      ],
    },
    {
      // PC 事实源 ws_card#2：张女士名下实体卡样例（冻结态，验证拦截）；排列在主卡之后，不参与取水预检。
      userId: '1',
      cardId: '2',
      cardNo: 'PC-8800001',
      cardType: 2,
      cardStatus: 2,
      balanceFen: 0,
      balanceMl: 100000,
      scopeDescription: '实体卡样例（冻结）',
      members: [],
    },
  ],
  addresses: [
    {
      userId: '1',
      addressId: 'ADDR-1',
      contactName: '张女士',
      maskedPhone: '139****1111',
      region: '武汉东湖高新区',
      detail: '光谷软件园 A1 栋 502',
      isDefault: true,
      locationAuthorized: false,
    },
    {
      // 钱女士地址：支撑"无卡用户下配送单被拦截"的 S04 姊妹场景（先过地址校验、卡校验拦截）。
      userId: '5',
      addressId: 'ADDR-2',
      contactName: '钱女士',
      maskedPhone: '136****4444',
      region: '武汉洪山区',
      detail: '南湖佰港城 3 栋 1101',
      isDefault: true,
      locationAuthorized: false,
    },
  ],
  familyProfiles: [],
  familyRewardRecords: [
    {
      recordId: 'FAMILY-RULE-1',
      ruleName: '首次完善家庭资料奖励（规则待确认）',
      status: 'RULE_ONLY',
    },
  ],
  orderDetails: [
    {
      order: {
        orderId: '1001',
        orderNo: 'WO20260712091001',
        userId: '1',
        orderType: 1,
        orderStatus: 4,
        orderAmountFen: 200,
        payWay: 3,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 10000,
        actualMl: 10000,
        createTime: '20260712091500',
        finishTime: '20260712091530',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1001',
      commandStatus: 4,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260712091505', tone: 'primary' },
        { node: 'ack', label: '设备已确认', time: '20260712091507', tone: 'info' },
        { node: 'result', label: '出水完成', time: '20260712091530', tone: 'success' },
      ],
      flowCount: 1,
    },
    {
      order: {
        orderId: '1006',
        orderNo: 'WO20260712091006',
        userId: '1',
        orderType: 3,
        orderStatus: 4,
        orderAmountFen: 4500,
        payWay: 1,
        stationId: '1',
        stationName: '光谷软件园水站',
        createTime: '20260711120000',
        finishTime: '20260711183000',
        mockMeta: { ...externalSnapshotMeta },
      },
      trace: [
        { node: 'delivery-created', label: '配送任务已生成', time: '20260711120000', tone: 'primary' },
        { node: 'accepted', label: '配送员已接单', time: '20260711123000', tone: 'info' },
        { node: 'departed', label: '水已离开水站', time: '20260711150000', tone: 'info' },
        { node: 'arrived', label: '已送达待确认', time: '20260711180000', tone: 'info' },
        { node: 'signed', label: '三照签收', time: '20260711183000', tone: 'success' },
      ],
      flowCount: 1,
      deliveryTaskNo: 'DT-2006',
      appealId: '3001',
    },
    {
      order: {
        orderId: '1008',
        orderNo: 'WO20260712091008',
        userId: '1',
        orderType: 3,
        orderStatus: 2,
        orderAmountFen: 1500,
        payWay: 1,
        stationId: '1',
        stationName: '光谷软件园水站',
        createTime: '20260712114500',
        mockMeta: { ...externalSnapshotMeta },
      },
      trace: [
        { node: 'delivery-created', label: '待配送员接单', time: '20260712114500', tone: 'primary' },
      ],
      flowCount: 1,
      deliveryTaskNo: 'DT-2008',
    },
    {
      // 蓝图 §11 部分出水场景：计划 10L 实际 6L，异常待补偿。
      order: {
        orderId: '1002',
        orderNo: 'WO20260712091002',
        userId: '1',
        orderType: 1,
        orderStatus: 6,
        orderAmountFen: 200,
        payWay: 3,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 10000,
        actualMl: 6000,
        createTime: '20260712101200',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1002',
      commandStatus: 7,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260712101204', tone: 'primary' },
        { node: 'ack', label: '设备已确认', time: '20260712101206', tone: 'info' },
        { node: 'result', label: '部分出水', time: '20260712101400', detail: '计划 10L，实际 6L', tone: 'warning' },
        { node: 'compensation', label: '异常待补偿', time: '20260712101430', detail: '不足部分由运营核实后处理，小程序只展示结果', tone: 'warning' },
      ],
      flowCount: 1,
    },
    {
      // 蓝图 §11 出水中场景：ACK 已到，等待 result。
      order: {
        orderId: '1003',
        orderNo: 'WO20260712091003',
        userId: '1',
        orderType: 1,
        orderStatus: 3,
        orderAmountFen: 100,
        payWay: 3,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 5000,
        createTime: '20260712113000',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1003',
      commandStatus: 3,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260712113002', tone: 'primary' },
        { node: 'ack', label: '设备已确认，等待出水结果', time: '20260712113004', tone: 'info' },
      ],
      flowCount: 1,
    },
    {
      // 蓝图 §11 失败场景：失败 ACK，设备拒绝执行，未扣费。
      order: {
        orderId: '1009',
        orderNo: 'WO20260712091009',
        userId: '1',
        orderType: 1,
        orderStatus: 5,
        orderAmountFen: 300,
        payWay: 2,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 10000,
        actualMl: 0,
        createTime: '20260712140500',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1009',
      commandStatus: 5,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260712140502', tone: 'primary' },
        { node: 'ack-failed', label: '失败 ACK：设备拒绝执行', time: '20260712140504', detail: 'E004 出水阀异常（2号口·矿物质水）', tone: 'danger' },
        { node: 'closed', label: '订单已取消，未发生扣费', time: '20260712140520', tone: 'info' },
      ],
      flowCount: 0,
    },
    {
      // 蓝图 §11 超时/离线场景：离线设备指令超时，异常待补偿。
      order: {
        orderId: '1010',
        orderNo: 'WO20260712091010',
        userId: '1',
        orderType: 1,
        orderStatus: 6,
        orderAmountFen: 100,
        payWay: 3,
        stationId: '2',
        stationName: '南湖社区水站',
        deviceNo: 'DK-DEV-0002',
        cardId: '1',
        planMl: 5000,
        createTime: '20260712075500',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1010',
      commandStatus: 6,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260712075502', tone: 'primary' },
        { node: 'ack-timeout', label: '30 秒未收到设备确认', time: '20260712075532', tone: 'danger' },
        { node: 'timeout', label: '指令超时，转异常待补偿', time: '20260712080002', detail: '设备离线（最后心跳超过 90 秒）', tone: 'danger' },
      ],
      flowCount: 1,
    },
    {
      // 蓝图 §11 重复 ACK 场景：幂等拦截，仅一笔流水。
      order: {
        orderId: '1011',
        orderNo: 'WO20260711091011',
        userId: '1',
        orderType: 1,
        orderStatus: 4,
        orderAmountFen: 160,
        payWay: 3,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 8000,
        actualMl: 8000,
        createTime: '20260711100500',
        finishTime: '20260711100520',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1011',
      commandStatus: 4,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260711100502', tone: 'primary' },
        { node: 'ack', label: '设备已确认', time: '20260711100504', tone: 'info' },
        { node: 'dup-ack', label: '重复 ACK 已拦截', time: '20260711100505', detail: '仅记录审计，不重复扣减，仅一笔流水', tone: 'info' },
        { node: 'result', label: '出水完成', time: '20260711100520', tone: 'success' },
      ],
      flowCount: 1,
    },
    {
      // 蓝图 §11 错设备 ACK 场景：错误回执被拒绝后，正确回执到达并完成（对齐 PC 终态口径）。
      order: {
        orderId: '1012',
        orderNo: 'WO20260711091012',
        userId: '1',
        orderType: 1,
        orderStatus: 4,
        orderAmountFen: 180,
        payWay: 3,
        stationId: '1',
        stationName: '光谷软件园水站',
        deviceNo: 'DK-DEV-0001',
        cardId: '1',
        planMl: 6000,
        actualMl: 6000,
        createTime: '20260711102000',
        finishTime: '20260711102025',
        mockMeta: { ...externalSnapshotMeta },
      },
      commandNo: 'CMD-1012',
      commandStatus: 4,
      trace: [
        { node: 'created', label: '指令已下发', time: '20260711102002', tone: 'primary' },
        { node: 'wrong-device', label: '收到非目标设备回执，已拒绝', time: '20260711102006', detail: '不推进指令状态，不产生二次扣减', tone: 'warning' },
        { node: 'ack', label: '目标设备已确认', time: '20260711102010', tone: 'info' },
        { node: 'result', label: '出水完成', time: '20260711102025', detail: '2号口·矿物质水 6L', tone: 'success' },
      ],
      flowCount: 1,
    },
    {
      // 蓝图 §11 配送中场景：已接单并离站，对应任务 DT-2007。
      order: {
        orderId: '1007',
        orderNo: 'WO20260712091007',
        userId: '1',
        orderType: 3,
        orderStatus: 2,
        orderAmountFen: 3000,
        payWay: 2,
        stationId: '1',
        stationName: '光谷软件园水站',
        createTime: '20260712103000',
        mockMeta: { ...externalSnapshotMeta },
      },
      trace: [
        { node: 'delivery-created', label: '配送任务已生成', time: '20260712103000', tone: 'primary' },
        { node: 'accepted', label: '配送员已接单', time: '20260712104500', tone: 'info' },
        { node: 'departed', label: '水已离开水站', time: '20260712110000', tone: 'info' },
      ],
      flowCount: 1,
      deliveryTaskNo: 'DT-2007',
    },
    {
      // PC 共键 091004：充值成功历史快照（固定外部快照，非原型动作产生）。
      order: {
        orderId: '1004',
        orderNo: 'WO20260712091004',
        userId: '1',
        orderType: 2,
        orderStatus: 4,
        orderAmountFen: 10000,
        payWay: 1,
        cardId: '1',
        packageSnapshot: '{"packageId":"1","packageName":"100元500升卡","payAmountFen":10000,"waterMl":500000,"bonusAmountFen":0,"expireDays":365}',
        createTime: '20260711203000',
        finishTime: '20260711203010',
        mockMeta: { ...externalSnapshotMeta },
      },
      trace: [
        { node: 'paid', label: '支付成功（固定外部快照）', time: '20260711203010', tone: 'success' },
        { node: 'credited', label: '充值到账 500L', time: '20260711203010', detail: '历史快照，非本次演示动作产生', tone: 'success' },
      ],
      flowCount: 1,
    },
    {
      // PC 共键 091005：已退款历史快照（订单状态 7 仅由历史快照承载，蓝图 §9.1）。
      order: {
        orderId: '1005',
        orderNo: 'WO20260712091005',
        userId: '1',
        orderType: 2,
        orderStatus: 7,
        orderAmountFen: 5000,
        payWay: 1,
        cardId: '1',
        packageSnapshot: '{"packageId":"2","packageName":"50元充值(送5元)","payAmountFen":5000,"waterMl":0,"bonusAmountFen":500,"expireDays":null}',
        createTime: '20260710150000',
        mockMeta: { ...externalSnapshotMeta },
      },
      trace: [
        { node: 'paid', label: '支付成功（固定外部快照）', time: '20260710150010', tone: 'info' },
        { node: 'refunded', label: '已退款（含赠送金额回收）', time: '20260711093000', detail: '历史快照，退款不由当前操作产生', tone: 'warning' },
      ],
      flowCount: 2,
    },
  ],
  deliveryTasks: [
    {
      taskId: '2006',
      taskNo: 'DT-2006',
      orderId: '1006',
      orderNo: 'WO20260712091006',
      userId: '1',
      courierId: '1',
      stationId: '1',
      stationName: '光谷软件园水站',
      waterTypeId: '1',
      waterTypeName: '纯净水（占位）',
      containerSpec: '20L桶',
      plannedDeliveryCount: 3,
      actualDeliveryCount: 2,
      plannedReturnCount: 2,
      actualReturnCount: 2,
      receiveAddress: '武汉东湖高新区光谷软件园 A1 栋 502',
      maskedPhone: '139****1111',
      priceSnapshot: {
        waterAmountFen: 3900,
        deliveryFeeFen: 600,
        totalAmountFen: 4500,
      },
      taskStatus: 7,
      version: 6,
      acceptTime: '20260711123000',
      departTime: '20260711150000',
      arriveTime: '20260711180000',
      signTime: '20260711183000',
      signPhotos: [
        { type: 1, label: '门牌', recordRef: 'SNAPSHOT-DT-2006-DOOR', time: '20260711182500', evidenceMode: 'external-snapshot' },
        { type: 2, label: '水品', recordRef: 'SNAPSHOT-DT-2006-WATER', time: '20260711182600', evidenceMode: 'external-snapshot' },
        { type: 3, label: '摆放', recordRef: 'SNAPSHOT-DT-2006-PLACEMENT', time: '20260711182700', evidenceMode: 'external-snapshot' },
      ],
      // PC 外部快照未含坐标，定位状态如实标记为未记录（证据文案不得越界）。
      locationStatus: 'unrecorded',
      mockMeta: { ...externalSnapshotMeta },
    },
    {
      taskId: '2008',
      taskNo: 'DT-2008',
      orderId: '1008',
      orderNo: 'WO20260712091008',
      userId: '1',
      stationId: '1',
      stationName: '光谷软件园水站',
      waterTypeId: '1',
      waterTypeName: '纯净水（占位）',
      containerSpec: '20L桶',
      plannedDeliveryCount: 1,
      plannedReturnCount: 1,
      receiveAddress: '武汉东湖高新区光谷软件园 A1 栋 502',
      maskedPhone: '139****1111',
      priceSnapshot: {
        waterAmountFen: 1300,
        deliveryFeeFen: 200,
        totalAmountFen: 1500,
      },
      taskStatus: 1,
      version: 1,
      signPhotos: [],
      mockMeta: { ...externalSnapshotMeta },
    },
    {
      // S07 配送中样例：已接单并离站，供任务中心"进行中"分组与状态机演示。
      taskId: '2007',
      taskNo: 'DT-2007',
      orderId: '1007',
      orderNo: 'WO20260712091007',
      userId: '1',
      courierId: '1',
      stationId: '1',
      stationName: '光谷软件园水站',
      waterTypeId: '2',
      waterTypeName: '矿物质水（占位）',
      containerSpec: '10L桶',
      plannedDeliveryCount: 2,
      plannedReturnCount: 1,
      receiveAddress: '武汉东湖高新区光谷软件园 A1 栋 502',
      maskedPhone: '139****1111',
      priceSnapshot: {
        waterAmountFen: 2400,
        deliveryFeeFen: 600,
        totalAmountFen: 3000,
      },
      taskStatus: 3,
      version: 3,
      acceptTime: '20260712104500',
      departTime: '20260712110000',
      signPhotos: [],
      mockMeta: { ...externalSnapshotMeta },
    },
  ],
  courierAdmissions: [
    {
      accountId: 'ACCOUNT-WORKER-002',
      userId: '2',
      status: 2,
      applicantName: '李配送',
      maskedPhone: '138****1111',
      requestedStationIds: ['1', '2'],
      requestedRegion: '武汉东湖高新区',
      submittedTime: '20260710100000',
    },
    {
      accountId: 'ACCOUNT-USER-001',
      userId: '1',
      status: 0,
      applicantName: '张女士',
      maskedPhone: '139****1111',
      requestedStationIds: [],
    },
    {
      accountId: 'ACCOUNT-USER-003',
      userId: '5',
      status: 0,
      applicantName: '钱女士',
      maskedPhone: '136****4444',
      requestedStationIds: [],
    },
    {
      accountId: 'ACCOUNT-OWNER-004',
      userId: '4',
      status: 0,
      applicantName: '赵先生',
      maskedPhone: '137****3333',
      requestedStationIds: [],
    },
  ],
  deliveryExceptions: [],
  deliveryAppeals: [
    {
      appealId: '3001',
      orderNo: 'WO20260712091006',
      taskNo: 'DT-2006',
      userId: '1',
      appealStatus: 1,
      reason: 'QUANTITY',
      description: '实际收到 2 桶，与订单计划数量不一致。',
      receivedCount: 2,
      evidenceRefs: ['APPEAL-3001-USER-1'],
      createTime: '20260712130000',
    },
  ],
  messages: [
    {
      messageId: 'MSG-USER-1',
      accountId: 'ACCOUNT-USER-001',
      domain: 'delivery',
      title: '配送任务已生成',
      summary: '订单 WO20260712091008 等待配送员接单',
      content: '您的配送订单已生成，当前等待配送员接单。',
      channel: 'in-app',
      sendStatus: 4,
      sendTime: '20260712114500',
      unread: true,
      objectType: 'order',
      objectId: 'WO20260712091008',
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    },
    {
      // 机主域消息随设备归属走 PC 事实源（OWNER_USER_ID=4）。
      messageId: 'MSG-WORKER-1',
      accountId: 'ACCOUNT-OWNER-004',
      domain: 'owner',
      title: '设备离线提醒',
      summary: 'DK-DEV-0002 已超过心跳阈值',
      content: '南湖1号机最后心跳已超过 90 秒，请关注设备状态。',
      channel: 'in-app',
      sendStatus: 4,
      sendTime: '20260716155200',
      unread: true,
      objectType: 'device',
      objectId: 'DK-DEV-0002',
      requiredCapability: 'OWNER_VIEW',
      evidenceMode: 'prototype',
    },
    {
      // S10 发送成功样例：固定外部快照，只读展示微信订阅消息结果，不由小程序动作产生。
      messageId: 'MSG-USER-2',
      accountId: 'ACCOUNT-USER-001',
      domain: 'delivery',
      title: '水已离开水站',
      summary: '订单 WO20260712091007 配送中，请留意收货',
      content: '您的配送订单 WO20260712091007 已由配送员取水离站，预计今日送达。',
      channel: 'wechat-subscribe',
      sendStatus: 2,
      sendTime: '20260712110000',
      unread: true,
      objectType: 'order',
      objectId: 'WO20260712091007',
      requiredCapability: 'USER_BASE',
      evidenceMode: 'external-snapshot',
    },
    {
      // S10 发送失败样例：订阅消息失败并降级说明。
      messageId: 'MSG-USER-3',
      accountId: 'ACCOUNT-USER-001',
      domain: 'delivery',
      title: '配送提醒发送失败',
      summary: '微信订阅消息发送失败，已降级为站内提醒',
      content: '订单 WO20260712091007 的配送提醒经微信订阅消息发送失败，本条站内提醒作为降级通道，不影响您的申诉权利。',
      channel: 'wechat-subscribe',
      sendStatus: 3,
      sendTime: '20260712110000',
      unread: false,
      objectType: 'order',
      objectId: 'WO20260712091007',
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    },
    {
      // S08 申诉登记回执：站内消息可回跳订单申诉区块。
      messageId: 'MSG-USER-4',
      accountId: 'ACCOUNT-USER-001',
      domain: 'delivery',
      title: '配送申诉已登记',
      summary: '申诉 3001 等待运营核验证据',
      content: '您对订单 WO20260712091006 的申诉已登记（编号 3001），运营将在核验三照与举证材料后给出裁决结果。',
      channel: 'in-app',
      sendStatus: 4,
      sendTime: '20260712130500',
      unread: false,
      objectType: 'appeal',
      objectId: '3001',
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    },
    {
      // water 域正向样例：取水完成通知（外部快照，只读）。
      messageId: 'MSG-USER-5',
      accountId: 'ACCOUNT-USER-001',
      domain: 'water',
      title: '取水完成',
      summary: '订单 WO20260712091001 出水完成 10L',
      content: '您在光谷软件园水站的取水订单 WO20260712091001 已完成，实际出水 10L。',
      channel: 'wechat-subscribe',
      sendStatus: 2,
      sendTime: '20260712091210',
      unread: false,
      objectType: 'order',
      objectId: 'WO20260712091001',
      requiredCapability: 'USER_BASE',
      evidenceMode: 'external-snapshot',
    },
    {
      // card 域样例：有效期提醒，站内消息，无跳转对象。
      messageId: 'MSG-USER-6',
      accountId: 'ACCOUNT-USER-001',
      domain: 'card',
      title: '水卡有效期提醒',
      summary: '水卡 VC20260710000001 将于 2027-07-10 到期',
      content: '您的水卡 VC20260710000001 有效期至 2027-07-10，到期前可在充值页续费延期（原型提示）。',
      channel: 'in-app',
      sendStatus: 4,
      sendTime: '20260712090000',
      unread: false,
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    },
    {
      // S10.4 能力撤销演示：绑定 COURIER_WORK 的任务消息；C01 停用配送后本条详情降级为安全摘要。
      messageId: 'MSG-WORKER-3',
      accountId: 'ACCOUNT-WORKER-002',
      domain: 'delivery',
      title: '服务范围内有新的可接任务',
      summary: '任务 DT-2008 等待接单（20L桶×3）',
      content: '光谷软件园水站有新的配送任务 DT-2008 等待接单，请在任务中心查看。',
      channel: 'in-app',
      sendStatus: 4,
      sendTime: '20260712091100',
      unread: true,
      objectType: 'task',
      objectId: 'DT-2008',
      requiredCapability: 'COURIER_WORK',
      evidenceMode: 'prototype',
    },
    {
      // S10 待发送样例：模板/资质未确认前订阅消息停留待发送。
      messageId: 'MSG-WORKER-2',
      accountId: 'ACCOUNT-WORKER-002',
      domain: 'system',
      title: '订阅消息待发送（示例）',
      summary: '微信订阅模板未确认，消息停留待发送',
      content: '该消息演示"待发送"状态：微信订阅消息模板与资质确认前，平台不会宣称已发送成功。',
      channel: 'wechat-subscribe',
      sendStatus: 1,
      unread: false,
      requiredCapability: 'USER_BASE',
      evidenceMode: 'prototype',
    },
  ],
  scanSession: {
    scanSessionId: 'SCAN-SESSION-0001',
    deviceNo: 'DK-DEV-0001',
    outletId: '1',
    expiresAt: '20260716190000',
  },
  scanSessions: [],
  consumedScanSessionIds: [],
  waterEligibility: {
    availability: 'AVAILABLE',
    maxAllowedMl: 20000,
  },
  auditEvents: [],
}
