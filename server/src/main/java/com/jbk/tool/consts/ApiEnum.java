package com.jbk.tool.consts;

import com.jbk.tool.exception.JbkException;

/**
 * 公共枚举 - 跨模块通用
 */
public interface ApiEnum {

    enum DictType {
        API_FLAG("1"),// 是否
        API_DISABLED("10"),// 禁用状态
        API_GENDER("20"),// 性别
        API_MENU_TYPE("50"),// 系统菜单类型
        API_LOGIN_TYPE("120"),// 登录日志类型
        // ===== 饮水业务字典分段（1300+）=====
        // 【强制】编号与 server/sql/ws_*.sql 的 api_dict_type INSERT 一一对应（库内已灌入），
        // 新增编号先查本枚举避让，再同步 SQL 与本处注册。分段：130x 设备 / 131x 消息 /
        // 132x 指令 / 133x 卡券 / 134x 交易 / 135x 配送 / 136x 运维事件 / 1383 滤芯
        WS_DEVICE_ONLINE("1300"),// 设备在线状态（ws_device.sql）
        WS_DEVICE_RUN("1301"),// 设备运行状态（ws_device.sql）
        WS_QRCODE_TYPE("1303"),// 设备二维码类型（ws_device.sql）
        WS_FAULT_LEVEL("1304"),// 故障等级（ws_device.sql，告警等级复用）
        WS_DEVICE_MSG_TYPE("1310"),// 设备上行消息类型（ws_device.sql）
        WS_DEVICE_MSG_STATUS("1311"),// 设备消息处理状态（ws_device.sql）
        WS_MESSAGE_DOMAIN("1312"),// 站内消息领域（02-ws-business.sql 站内消息域）
        WS_MESSAGE_SEND_STATUS("1313"),// 站内消息发送状态（02-ws-business.sql 站内消息域）
        WS_COMMAND_TYPE("1320"),// 设备指令类型（ws_command.sql）
        WS_COMMAND_STATUS("1321"),// 设备指令状态（ws_command.sql）
        WS_PACKAGE_STATUS("1330"),// 套餐状态（ws_user_card.sql）
        WS_CARD_TYPE("1331"),// 水卡类型（ws_user_card.sql）
        WS_CARD_STATUS("1332"),// 水卡状态（ws_user_card.sql）
        WS_CARD_MEMBER_STATUS("1333"),// 成员授权状态（ws_user_card.sql）
        WS_ORDER_TYPE("1340"),// 订单类型（ws_trade.sql）
        WS_ORDER_STATUS("1341"),// 订单状态（ws_trade.sql）
        WS_PAY_STATUS("1342"),// 支付状态（ws_trade.sql）
        WS_REFUND_STATUS("1343"),// 退款状态（ws_trade.sql）
        WS_FLOW_TYPE("1344"),// 钱包流水类型（ws_trade.sql）
        WS_SPLIT_STATUS("1345"),// 分账状态（ws_trade.sql）
        WS_PAY_WAY("1346"),// 支付方式（ws_trade.sql）
        WS_COURIER_STATUS("1350"),// 配送员状态（ws_delivery.sql）
        WS_DELIVERY_STATUS("1351"),// 配送任务状态（ws_delivery.sql）
        WS_APPEAL_STATUS("1352"),// 申诉状态（ws_delivery.sql；E2E-03 增值5补送待执行）
        WS_DELIVERY_LOCATION_STATUS("1353"),// 签收定位记录状态（E2E-03 包A）
        WS_DELIVERY_EXCEPTION_REASON("1354"),// 配送异常原因（E2E-03 包A）
        WS_DELIVERY_AUTO_RULE_STATUS("1355"),// 自动补货规则状态（E2E-03 包A）
        WS_ALARM_TYPE("1360"),// 告警类型（ws_ops.sql）
        WS_ALARM_STATUS("1361"),// 告警状态（ws_ops.sql）
        WS_WORK_ORDER_STATUS("1362"),// 工单状态（六状态，E2E-05 重定义；02-ws-business.sql）
        WS_EVENT_TYPE("1363"),// 领域事件类型（ws_ops.sql）
        WS_ACTOR_PORTAL("1364"),// 操作端口/身份上下文来源（03-demo-baseline.sql）
        WS_WORK_ORDER_TYPE("1365"),// 工单类型（E2E-05 包A）
        WS_CMD_BATCH_SCOPE("1366"),// 批量指令范围（E2E-05 包A）
        WS_CMD_BATCH_STATUS("1367"),// 批量指令聚合状态（E2E-05 包A）
        WS_SIM_STATUS("1368"),// SIM状态（E2E-05 包A）
        // 1370-1375 由 E2E-04 各迁移写入库
        WS_AFTER_SALE_SOURCE("1370"),// 售后来源（E2E-04 包A）
        WS_AFTER_SALE_ACTION_TYPE("1371"),// 售后动作类型（E2E-04 包A）
        WS_AFTER_SALE_ACTION_STATUS("1372"),// 售后执行状态（E2E-04 包A）
        WS_REFUND_SOURCE("1373"),// 退款来源（E2E-04 包B）
        WS_ENTITLEMENT_BATCH_STATUS("1374"),// 权益批次状态（E2E-04 包D）
        WS_ENTITLEMENT_BATCH_SOURCE("1375"),// 权益批次来源（E2E-04 包D）
        // 1376~1381 为 E2E-08 分账/对账域字典，已由 02-ws-business.sql 与 settlement 迁移写入数据库（已占用，勿选）
        WS_AUDIT_EXPORT_STATUS("1382"),// 审计导出任务状态（ws_audit_export.sql，B23）
        WS_FILTER_STATUS("1383"),// 滤芯状态（ws_device.sql 遥测滤芯JSON）
        WS_MINI_ENTRY_TYPE("1384"),// 小程序入口类型（ws_mini_entry.sql，S6）
        WS_MINI_ENTRY_JUMP("1385"),// 小程序入口跳转类型（ws_mini_entry.sql，S6）
        WS_MINI_ENTRY_STATUS("1386"),// 小程序入口配置状态（ws_mini_entry.sql，S6）
        WS_SPLIT_CLAWBACK_STATUS("1387"),// 分润冲减事实状态（ws_trade.sql，D-420 R1）
        WS_MALL_PRODUCT_STATUS("1388"),// 商城商品状态（ws_mall.sql，E2E-09 S1）
        WS_MALL_SKU_STATUS("1389"),// 商城SKU状态（ws_mall.sql，E2E-09 S1）
        WS_MALL_WAREHOUSE_STATUS("1390"),// 前置仓状态（ws_mall.sql，E2E-09 S1）
        WS_MALL_STOCK_FLOW_TYPE("1391"),// 商城库存流水类型（ws_mall.sql，E2E-09 S1；5~8 预留 S2）
        WS_MALL_CATEGORY_STATUS("1392"),// 商城分类状态（ws_mall.sql，E2E-09 S1）
        WS_MALL_ORDER_STATUS("1393"),// 商城订单状态（ws_mall.sql，E2E-09 S2）
        WS_MALL_PAY_STATUS("1394"),// 商城支付单状态（ws_mall.sql，E2E-09 S2）
        WS_MALL_PAY_FACT_STATUS("1395"),// 商城支付事实处理状态（ws_mall.sql，E2E-09 S2）
        // 1396~1403 由 S3/S4 落库，L1 补登记（Skill 要求字典必须同步注册本枚举）
        WS_MALL_FULFILL_STATUS("1396"),// 商城履约任务状态（E2E-09 S3）
        WS_MALL_FULFILL_ACTOR("1397"),// 商城履约操作方（E2E-09 S3）
        WS_MALL_SIGN_METHOD("1398"),// 商城签收方式（E2E-09 S3）
        WS_MALL_AFTER_SALE_STATUS("1399"),// 商城售后状态（E2E-09 S4）
        WS_MALL_AFTER_SALE_TYPE("1400"),// 商城售后类型（E2E-09 S4）
        WS_MALL_INSPECT_RESULT("1401"),// 商城质检结论（E2E-09 S4）
        WS_MALL_REFUND_STATUS("1402"),// 商城退款状态（E2E-09 S4）
        WS_MALL_REFUND_FACT_STATUS("1403"),// 商城退款事实处理状态（E2E-09 S4）
        WS_MALL_FULFILL_MODE("1404"),// 商城履约渠道（E2E-09 L1）
        WS_MALL_SHIPMENT_DIRECTION("1405"),// 商城出库包裹方向（E2E-09 L1）
        WS_MALL_SHIPMENT_STATUS("1406"),// 商城出库包裹状态（E2E-09 L1）
        WS_MALL_LOGISTICS_EVENT_STATE("1407"),// 商城物流事件状态（E2E-09 L1）
        WS_MALL_LOGISTICS_PROCESSING("1408"),// 商城物流处理状态：事件收件箱与 outbox 共用值域（E2E-09 L1）
        WS_MALL_LOGISTICS_ACTION("1409"),// 商城物流动作类型（E2E-09 L1）
        WS_IDENTITY_CAPABILITY_TYPE("1410"),// 小程序经营身份能力类型
        WS_IDENTITY_APPLICATION_STATUS("1411"),// 小程序身份申请状态
        WS_IDENTITY_SUBJECT_TYPE("1412"),// 经营主体类型
        WS_REGION_AGENT_LEVEL("1413"),// 区域代理级别
        WS_PUBLIC_LEAD_STATUS("1414"),// 公域线索轮转状态
        WS_WITHDRAW_STATUS("1415"),// 收益提现终态
        ;
        private final String value;

        DictType(String value) {
            this.value = value;
        }

        public String value() {
            return this.value;
        }

        public static DictType getType(String type) {
            for (DictType cardType : DictType.values()) {
                if (cardType.value().equals(type)) {
                    return cardType;
                }
            }
            return null;
        }
    }

    // 是否 (dictType=1)
    enum Flag {
        NO(1, "否"),
        YES(2, "是");

        private final int value;
        private final String desc;

        Flag(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int value() {
            return this.value;
        }

        public String desc() {
            return this.desc;
        }
    }

    // 禁用状态 (dictType=10)
    enum DisabledFlag {
        NORMAL(1, "正常"),
        DISABLED(2, "禁用");

        private final int value;
        private final String desc;

        DisabledFlag(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static DisabledFlag getType(int type) {
            for (DisabledFlag item : DisabledFlag.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 性别 (dictType=20)
    enum GenderEnum {
        MALE(1, "男"),
        FEMALE(2, "女");

        private final int value;
        private final String desc;

        GenderEnum(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static GenderEnum getType(int type) {
            for (GenderEnum item : GenderEnum.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 菜单类型 (dictType=50)
    enum MenuTypeEnum {
        CATALOG(1, "目录"),
        MENU(2, "菜单"),
        POINTS(3, "功能点");

        private final int value;
        private final String desc;

        MenuTypeEnum(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static MenuTypeEnum getType(int type) {
            for (MenuTypeEnum infoType : MenuTypeEnum.values()) {
                if (infoType.getValue() == type) {
                    return infoType;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 登录类型 (dictType=120)
    enum LoginType {
        SUCCESS(1, "登录成功"),
        LOG_OUT(2, "退出登录"),
        PWD_FAIL(3, "密码错误");

        private final int value;
        private final String desc;

        LoginType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int value() {
            return this.value;
        }

        public String desc() {
            return this.desc;
        }

        public static LoginType getType(int type) {
            for (LoginType item : LoginType.values()) {
                if (item.value() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }
}
