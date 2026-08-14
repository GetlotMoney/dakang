package com.jbk.serve.service.delivery.impl;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E2E-03 配送域 + E2E-04 售后域 DB 测试共享 schema：列/唯一键与 02-ws-business.sql 对齐，
 * 唯一键（ACTIVE_TASK_KEY、uk_order_no / uk_wallet_flow_biz_key / uk_dtask_*）正是被测防线。
 * public 供 service/aftersale/impl 复用，避免第二份 DDL 漂移。
 */
public final class DeliveryDbSchema {

    private DeliveryDbSchema() {
    }

    public static void createAll(JdbcTemplate jdbc) {
        // 通知 outbox 是部署契约的一部分，表缺失会让事务内登记的业务动作整体失败；
        // DDL 不复制，直接执行真实迁移文件防抄本漂移
        com.jbk.serve.service.mini.notify.WechatNotifyTestSchema.create(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_NO VARCHAR(32), CARD_TYPE TINYINT, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, CARD_REMARK VARCHAR(255) NULL,
                  ISSUE_ORDER_ID BIGINT NULL,
                  UNIQUE KEY uk_card_no (CARD_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_order (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_NO VARCHAR(64), ORDER_TYPE TINYINT, USER_ID BIGINT,
                  STATION_ID BIGINT, DEVICE_ID BIGINT, OUTLET_ID BIGINT, CARD_ID BIGINT,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL,
                  PLAN_ML BIGINT NULL, ACTUAL_ML BIGINT NULL, ORDER_AMOUNT BIGINT,
                  PAY_WAY TINYINT, ORDER_STATUS TINYINT, CMD_ID BIGINT NULL,
                  FINISH_TIME VARCHAR(20) NULL, CANCEL_REASON VARCHAR(500) NULL,
                  CHANNEL_USER_ID BIGINT NULL, REFERRER_USER_ID BIGINT NULL,
                  UNIQUE KEY uk_order_no (ORDER_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_wallet_flow (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_ID BIGINT, USER_ID BIGINT, FLOW_TYPE TINYINT,
                  AMOUNT_CHANGE BIGINT NOT NULL DEFAULT 0, ML_CHANGE BIGINT NOT NULL DEFAULT 0,
                  AMOUNT_AFTER BIGINT NOT NULL, ML_AFTER BIGINT NOT NULL,
                  ORDER_ID BIGINT, FLOW_REMARK VARCHAR(255), BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_wallet_flow_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_station (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  STATION_NAME VARCHAR(100), STATION_CODE VARCHAR(50), STATION_REGION VARCHAR(100),
                  STATION_ADDRESS VARCHAR(200), STATION_LNG VARCHAR(20), STATION_LAT VARCHAR(20),
                  STATION_STATUS TINYINT, OWNER_USER_ID BIGINT NULL, CHANNEL_USER_ID BIGINT NULL,
                  STATION_REMARK VARCHAR(255) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_water_type (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  WATER_NAME VARCHAR(20), WATER_SORT INT, DEFAULT_FLAG TINYINT,
                  WATER_STATUS TINYINT, WATER_DESC VARCHAR(500) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_courier (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  USER_ID BIGINT, COURIER_NAME VARCHAR(50), COURIER_PHONE VARCHAR(20),
                  ID_CARD_NO VARCHAR(30) NULL, STATION_IDS VARCHAR(200) NULL,
                  SERVICE_REGION VARCHAR(100) NULL, COURIER_STATUS TINYINT, AUDIT_REMARK VARCHAR(500) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_delivery_task (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  TASK_NO VARCHAR(32) NOT NULL, ORDER_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL,
                  STATION_ID BIGINT NOT NULL, COURIER_ID BIGINT NULL, WATER_TYPE_ID BIGINT NULL,
                  WATER_TYPE VARCHAR(20), CONTAINER_SPEC VARCHAR(20),
                  DELIVERY_COUNT INT NOT NULL, PLAN_RETURN_COUNT INT NOT NULL,
                  ACTUAL_DELIVERY_COUNT INT NULL, ACTUAL_RETURN_COUNT INT NULL,
                  WATER_AMOUNT BIGINT NOT NULL, DELIVERY_FEE BIGINT NOT NULL,
                  RECEIVE_ADDRESS VARCHAR(200), RECEIVE_PHONE VARCHAR(20),
                  TASK_STATUS TINYINT NOT NULL, VERSION INT NOT NULL,
                  SCHEDULED_TIME VARCHAR(20) NULL,
                  ACCEPT_TIME VARCHAR(20) NULL, DEPART_TIME VARCHAR(20) NULL,
                  ARRIVE_TIME VARCHAR(20) NULL, SIGN_TIME VARCHAR(20) NULL,
                  SIGN_PHOTOS TEXT NULL, LOCATION_STATUS TINYINT NULL,
                  APPEAL_DEADLINE VARCHAR(20) NULL, TASK_REMARK VARCHAR(500) NULL,
                  UNIQUE KEY uk_dtask_order (ORDER_ID),
                  UNIQUE KEY uk_dtask_task_no (TASK_NO),
                  KEY idx_dtask_station_status (STATION_ID, TASK_STATUS)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_delivery_appeal (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  TASK_ID BIGINT NOT NULL, ORDER_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL,
                  APPEAL_REASON VARCHAR(20) NOT NULL, APPEAL_DESC VARCHAR(500) NULL,
                  RECEIVED_COUNT INT NULL, APPEAL_PHOTOS TEXT NULL, COURIER_EVIDENCES TEXT NULL,
                  APPEAL_STATUS TINYINT NOT NULL,
                  HANDLE_BY BIGINT NULL, HANDLE_TIME VARCHAR(20) NULL, HANDLE_RESULT VARCHAR(500) NULL,
                  ACTIVE_TASK_KEY BIGINT GENERATED ALWAYS AS (IF(APPEAL_STATUS = 1, TASK_ID, NULL)) STORED,
                  UNIQUE KEY uk_appeal_active_task (ACTIVE_TASK_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // 管理端申诉列表的派生展示列来源：ws_user（申诉人）/ api_employee（裁决处理人）。
        // 只保留投影用到的列，NOT NULL 一律放宽——这里被测的是聚合口径，不是底座建表约束。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_user (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  USER_NAME VARCHAR(50), USER_GENDER TINYINT NULL, USER_PHONE VARCHAR(20),
                  USER_STATUS TINYINT NULL, DISABLED_FLAG TINYINT NULL,
                  USER_IDENTITY_CIPHER VARCHAR(200) NULL, USER_AVATAR VARCHAR(200) NULL,
                  POINTS BIGINT NULL, WECHAT_XCX_OPENID VARCHAR(64) NULL,
                  CHANNEL_USER_ID BIGINT NULL, REFERRER_USER_ID BIGINT NULL,
                  PROMO_CODE VARCHAR(20) NULL, OWN_INVITE_CODE VARCHAR(12) NULL,
                  UNIQUE KEY uk_user_invite_code (OWN_INVITE_CODE)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_employee (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  LOGIN_NAME VARCHAR(50) NULL, LOGIN_PWD VARCHAR(100) NULL,
                  EMPLOYEE_NAME VARCHAR(30) NULL, EMPLOYEE_GENDER TINYINT NULL,
                  EMPLOYEE_PHONE VARCHAR(11) NULL, DEPT_ID BIGINT NULL, POSITION_ID BIGINT NULL,
                  DISABLED_FLAG TINYINT NULL, PWD_CHANGE_FLAG TINYINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_delivery_exception (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  TASK_ID BIGINT NOT NULL, COURIER_ID BIGINT NOT NULL,
                  EXCEPTION_REASON TINYINT NOT NULL, EXCEPTION_DESC VARCHAR(500) NOT NULL,
                  EVIDENCE_REFS TEXT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_delivery_media (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  MEDIA_KEY VARCHAR(64) NOT NULL, OWNER_USER_ID BIGINT NOT NULL,
                  OWNER_PORTAL TINYINT NOT NULL DEFAULT 2,
                  MEDIA_PURPOSE TINYINT NOT NULL, CONTENT_SHA256 CHAR(64) NOT NULL,
                  SIZE_BYTES BIGINT NOT NULL, MIME_TYPE VARCHAR(50) NOT NULL,
                  BOUND_TASK_ID BIGINT NULL,
                  UNIQUE KEY uk_dmedia_key (MEDIA_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_delivery_auto_rule (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  RULE_KEY VARCHAR(64) NOT NULL, USER_ID BIGINT NOT NULL, CARD_ID BIGINT NOT NULL,
                  STATION_ID BIGINT NOT NULL, WATER_TYPE_ID BIGINT NOT NULL,
                  CONTAINER_SPEC VARCHAR(20) NOT NULL, DELIVERY_COUNT INT NOT NULL,
                  PLAN_RETURN_COUNT INT NOT NULL, RECEIVE_ADDRESS VARCHAR(200) NOT NULL,
                  RECEIVE_PHONE VARCHAR(20) NOT NULL, INTERVAL_DAYS INT NOT NULL,
                  ANCHOR_TIME VARCHAR(20) NOT NULL, RULE_STATUS TINYINT NOT NULL,
                  ACTIVE_SHAPE_KEY VARCHAR(64) GENERATED ALWAYS AS (
                    CASE WHEN RULE_STATUS IN (1, 2)
                         THEN SHA2(CONCAT_WS(':', USER_ID, WATER_TYPE_ID, CONTAINER_SPEC, RECEIVE_ADDRESS), 256)
                         ELSE NULL END) STORED,
                  UNIQUE KEY uk_dauto_rule_key (RULE_KEY),
                  UNIQUE KEY uk_dauto_active_shape (ACTIVE_SHAPE_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_message (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  USER_ID BIGINT NOT NULL, MSG_DOMAIN TINYINT NOT NULL,
                  MSG_TITLE VARCHAR(100) NOT NULL, MSG_CONTENT VARCHAR(500) NOT NULL,
                  MSG_CHANNEL TINYINT NOT NULL, SEND_STATUS TINYINT NOT NULL,
                  SEND_TIME VARCHAR(20) NULL, READ_FLAG TINYINT NOT NULL DEFAULT 0,
                  OBJECT_TYPE VARCHAR(20) NULL, OBJECT_ID VARCHAR(64) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // uk_domain_event_biz_key 与生产同名：可靠审计「同键最多一条 + 撞键读回核验」的物理前提
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_domain_event (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  EVENT_TYPE TINYINT, EVENT_KEY VARCHAR(64), EVENT_PAYLOAD TEXT,
                  ACTOR_ID BIGINT NULL, ACTOR_PORTAL TINYINT, ACTOR_ROLE VARCHAR(20),
                  WHITELIST_FLAG TINYINT, CONSUMED_FLAG TINYINT,
                  BIZ_IDEMPOTENCY_KEY VARCHAR(64) NULL,
                  UNIQUE KEY uk_domain_event_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // E2E-04 包A：裁决与待接单取消都会在同事务登记一条待执行售后动作。
        // 两把唯一键与生产同名同形（2026-07-29-aftersale-e2e04-a.sql）：
        // uk_after_sale_source 是「同来源只允许一笔售后」的物理防线，也是重放幂等的落点，
        // 且刻意不含 DATA_STATUS——删了再建绕过唯一约束就等于可以重复返还。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_after_sale_action (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  AFTER_SALE_NO VARCHAR(32) NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  SOURCE_ID BIGINT NOT NULL, ORDER_ID BIGINT NOT NULL,
                  USER_ID BIGINT NOT NULL, CARD_ID BIGINT NULL,
                  ACTION_TYPE TINYINT NOT NULL, STRATEGY_CODE VARCHAR(20) NULL,
                  APPROVED_COUNT INT NULL,
                  REFUND_PRODUCT_FEN BIGINT NOT NULL DEFAULT 0,
                  REFUND_SERVICE_FEN BIGINT NOT NULL DEFAULT 0,
                  REFUND_PRODUCT_ML BIGINT NOT NULL DEFAULT 0,
                  REFUND_AMOUNT BIGINT NOT NULL DEFAULT 0,
                  CALC_SNAPSHOT TEXT NULL, ACTION_STATUS TINYINT NOT NULL,
                  VERSION INT NOT NULL DEFAULT 1, RETRY_COUNT INT NOT NULL DEFAULT 0,
                  NEXT_RETRY_TIME VARCHAR(14) NULL,
                  REFUND_ID BIGINT NULL, RESULT_ORDER_ID BIGINT NULL, RESULT_TASK_ID BIGINT NULL,
                  APPROVE_BY BIGINT NULL, APPROVE_TIME VARCHAR(14) NULL,
                  FINISH_TIME VARCHAR(14) NULL, LAST_ERROR VARCHAR(500) NULL,
                  UNIQUE KEY uk_after_sale_no (AFTER_SALE_NO),
                  UNIQUE KEY uk_after_sale_source (SOURCE_TYPE, SOURCE_ID),
                  KEY idx_after_sale_order (ORDER_ID, ACTION_STATUS),
                  KEY idx_after_sale_status (ACTION_STATUS, NEXT_RETRY_TIME)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // E2E-04 包B：支付单 + 退款单 + 退款事实收件箱。
        // 列取自 2026-07-29-aftersale-e2e04-b.sql；两把唯一键与生产同名同形——
        // uk_refund_after_sale 是「一个售后动作至多一张退款单」的物理防线，
        // uk_refund_event_source_channel_key 是「同事实重复多少次也只有一行」的物理防线。
        // 本类与生产 DDL 的一致性由 SchemaParityTest 常态守住，不靠人工比对。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_payment (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  ORDER_ID BIGINT NOT NULL, ORDER_NO VARCHAR(32) NOT NULL,
                  TRANSACTION_ID VARCHAR(64) NULL, PAY_AMOUNT BIGINT NOT NULL,
                  PAY_STATUS TINYINT NOT NULL, PAY_SOURCE TINYINT NULL,
                  CURRENCY VARCHAR(16) NULL, PAY_EXPIRE_TIME VARCHAR(14) NULL,
                  PAY_SUCCESS_TIME VARCHAR(14) NULL, PREPAY_ID VARCHAR(64) NULL,
                  CALLBACK_TIME VARCHAR(14) NULL, CALLBACK_PAYLOAD TEXT NULL,
                  KEY idx_payment_order (ORDER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_refund (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  REFUND_NO VARCHAR(32) NOT NULL, ORDER_ID BIGINT NOT NULL,
                  ORDER_NO VARCHAR(32) NOT NULL DEFAULT '', PAYMENT_ID BIGINT NOT NULL,
                  AFTER_SALE_ID BIGINT NULL, REFUND_AMOUNT BIGINT NOT NULL,
                  REFUND_SOURCE TINYINT NOT NULL, CURRENCY VARCHAR(16) NOT NULL DEFAULT 'CNY',
                  REFUND_REASON VARCHAR(500) NOT NULL, REFUND_STATUS TINYINT NOT NULL,
                  PROVIDER_REFUND_ID VARCHAR(64) NULL, REQUEST_TIME VARCHAR(14) NULL,
                  SUCCESS_TIME VARCHAR(14) NULL, VERSION INT NOT NULL DEFAULT 1,
                  RETRY_COUNT INT NOT NULL DEFAULT 0, NEXT_RETRY_TIME VARCHAR(14) NULL,
                  LAST_ERROR VARCHAR(500) NULL, CALLBACK_TIME VARCHAR(14) NULL,
                  CALLBACK_PAYLOAD TEXT NULL,
                  UNIQUE KEY uk_refund_no (REFUND_NO),
                  UNIQUE KEY uk_refund_after_sale (AFTER_SALE_ID),
                  KEY idx_refund_order (ORDER_ID),
                  KEY idx_refund_claim (REFUND_STATUS, NEXT_RETRY_TIME),
                  KEY idx_refund_payment (PAYMENT_ID, REFUND_STATUS)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_refund_event (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  REFUND_SOURCE TINYINT NOT NULL, FACT_CHANNEL TINYINT NOT NULL,
                  PROVIDER_EVENT_KEY VARCHAR(100) NOT NULL,
                  REFUND_ID BIGINT NULL, AFTER_SALE_ID BIGINT NULL, ORDER_ID BIGINT NULL,
                  REFUND_NO VARCHAR(32) NOT NULL, ORDER_NO VARCHAR(32) NULL,
                  REFUND_STATE VARCHAR(32) NOT NULL, PROVIDER_REFUND_ID VARCHAR(64) NULL,
                  REFUND_AMOUNT BIGINT NULL, CURRENCY VARCHAR(16) NULL,
                  REFUND_SUCCESS_TIME VARCHAR(14) NULL, RAW_BODY MEDIUMTEXT NULL,
                  RAW_BODY_SHA256 CHAR(64) NOT NULL, VERIFY_METHOD TINYINT NOT NULL,
                  PROCESSING_STATUS TINYINT NOT NULL, RETRY_COUNT INT NOT NULL DEFAULT 0,
                  NEXT_RETRY_TIME VARCHAR(14) NULL, CLAIM_TIME VARCHAR(14) NULL,
                  LEASE_UNTIL VARCHAR(14) NULL, LAST_ERROR VARCHAR(500) NULL,
                  RECEIVED_TIME VARCHAR(14) NOT NULL, PROCESSED_TIME VARCHAR(14) NULL,
                  UNIQUE KEY uk_refund_event_source_channel_key (REFUND_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY),
                  KEY idx_refund_event_refund_no (REFUND_NO),
                  KEY idx_refund_event_claim (PROCESSING_STATUS, NEXT_RETRY_TIME)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // 包D-5：退款结算判定「卡上是否还有成员授权」要查这张表（CardClosureRule 的证据之一）。
        // 列取自 02-ws-business.sql；uk_card_member_user 与生产同名，成员授权的唯一性靠它
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_member (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CARD_ID BIGINT, MEMBER_USER_ID BIGINT, MEMBER_NAME VARCHAR(50),
                  DAY_LIMIT_ML BIGINT NULL, EFFECTIVE_TIME VARCHAR(14) NULL, EXPIRE_TIME VARCHAR(14) NULL,
                  MEMBER_STATUS TINYINT,
                  UNIQUE KEY uk_card_member_user (CARD_ID, MEMBER_USER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        createEntitlementTables(jdbc);
        // E2E-04 包A：取水异常核账要做「订单-指令共键」核验（CMD_ID → ORDER_ID/DEVICE_ID 回指）。
        // 列取自 02-ws-business.sql；NOT NULL 一律放宽——这里被测的是共键判定，不是建表约束。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_command (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CMD_NO VARCHAR(64) NOT NULL, DEVICE_ID BIGINT, ORDER_ID BIGINT,
                  CMD_TYPE TINYINT, CMD_PAYLOAD TEXT NULL, CMD_STATUS TINYINT,
                  SENT_TIME VARCHAR(14) NULL, ACK_TIME VARCHAR(14) NULL, FINISH_TIME VARCHAR(14) NULL,
                  RESULT_PAYLOAD TEXT NULL, FAIL_REASON VARCHAR(500) NULL,
                  RETRY_COUNT TINYINT NOT NULL DEFAULT 0,
                  BATCH_ID BIGINT NULL,
                  UNIQUE KEY uk_cmd_no (CMD_NO),
                  UNIQUE KEY uk_cmd_batch_device (BATCH_ID, DEVICE_ID),
                  KEY idx_cmd_order (ORDER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        createDeviceOpsTables(jdbc);
    }

    /**
     * E2E-05 设备运营域：告警活动键、六状态工单、批量聚合。唯一键与生产同名同形——
     * uk_alarm_active_dedupe / uk_wo_no / uk_wo_alarm / uk_wo_request / uk_batch_no
     * 都是被测的幂等物理防线。ws_device 只保留告警/工单/批量联查用到的列。
     */
    public static void createDeviceOpsTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_device (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  DEVICE_NO VARCHAR(50), DEVICE_NAME VARCHAR(100) NULL, DEVICE_MODEL VARCHAR(50) NULL,
                  STATION_ID BIGINT NULL, OWNER_USER_ID BIGINT NULL, CHANNEL_USER_ID BIGINT NULL,
                  FIRMWARE_VERSION VARCHAR(20) NULL,
                  SIM_ICCID VARCHAR(50) NULL, SIM_CARRIER VARCHAR(20) NULL,
                  SIM_STATUS TINYINT NULL, SIM_EXPIRE_TIME VARCHAR(14) NULL,
                  ONLINE_STATUS TINYINT NULL, RUN_STATUS TINYINT NULL,
                  LAST_HEARTBEAT VARCHAR(14) NULL, LAST_FAULT_CODE VARCHAR(20) NULL,
                  LAST_STATUS_DEVICE_TIME VARCHAR(14) NULL,
                  SIGNAL_STRENGTH INT NULL, DEVICE_REMARK VARCHAR(500) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_alarm (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  DEVICE_ID BIGINT NOT NULL, ALARM_TYPE TINYINT NOT NULL,
                  ALARM_LEVEL TINYINT NOT NULL, ALARM_CONTENT VARCHAR(500) NOT NULL,
                  SOURCE_REF VARCHAR(64) NULL, ALARM_STATUS TINYINT NOT NULL,
                  WORK_ORDER_ID BIGINT NULL, RECOVER_TIME VARCHAR(14) NULL,
                  ACTIVE_DEDUPE_KEY VARCHAR(100) NULL,
                  HANDLE_BY BIGINT NULL, HANDLE_TIME VARCHAR(14) NULL,
                  UNIQUE KEY uk_alarm_active_dedupe (ACTIVE_DEDUPE_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_work_order (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_NO VARCHAR(32) NOT NULL, WORK_TYPE TINYINT NOT NULL,
                  DEVICE_ID BIGINT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  ALARM_ID BIGINT NULL, APPLICANT_USER_ID BIGINT NULL, REQUEST_ID VARCHAR(64) NULL,
                  ORDER_TITLE VARCHAR(100) NOT NULL, ORDER_CONTENT VARCHAR(1000) NULL,
                  ORDER_PHOTOS TEXT NULL, ASSIGNEE_ID BIGINT NULL, ORDER_STATUS TINYINT NOT NULL,
                  ASSIGN_TIME VARCHAR(14) NULL, FINISH_TIME VARCHAR(14) NULL,
                  FINISH_RESULT VARCHAR(500) NULL, RESULT_PHOTOS TEXT NULL,
                  REVIEW_BY BIGINT NULL, REVIEW_TIME VARCHAR(14) NULL, REVIEW_REMARK VARCHAR(500) NULL,
                  REJECT_REASON VARCHAR(500) NULL, CLOSE_TIME VARCHAR(14) NULL,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_wo_no (ORDER_NO),
                  UNIQUE KEY uk_wo_alarm (ALARM_ID),
                  UNIQUE KEY uk_wo_request (REQUEST_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_command_batch (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  BATCH_NO VARCHAR(32) NOT NULL, SCOPE_TYPE TINYINT NOT NULL,
                  SCOPE_SNAPSHOT TEXT NULL, CMD_TYPE TINYINT NOT NULL, CMD_PAYLOAD TEXT NULL,
                  PARAM_DIGEST CHAR(64) NULL,
                  TOTAL_COUNT INT NOT NULL DEFAULT 0, SUCCESS_COUNT INT NOT NULL DEFAULT 0,
                  FAIL_COUNT INT NOT NULL DEFAULT 0, TIMEOUT_COUNT INT NOT NULL DEFAULT 0,
                  BATCH_STATUS TINYINT NOT NULL, FINISH_TIME VARCHAR(14) NULL,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_batch_no (BATCH_NO)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    /**
     * E2E-04 包D：权益批次与消费分摊。uk_batch_order=每笔充值恰一批次、
     * uk_alloc_biz_batch=同消费对同批次只分摊一次；一致性由 SchemaParityTest 守住。
     * public 供取水域真库测试复用，防第二份 DDL 漂移。
     */
    public static void createEntitlementTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card_entitlement_batch (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  CARD_ID BIGINT NOT NULL, USER_ID BIGINT NOT NULL, SOURCE_TYPE TINYINT NOT NULL,
                  ORDER_ID BIGINT NULL, ORDER_NO VARCHAR(32) NULL, PAYMENT_ID BIGINT NULL,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP TEXT NULL,
                  PAY_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_BONUS_FEN BIGINT NOT NULL DEFAULT 0,
                  GRANT_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  REMAIN_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  REMAIN_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  EXPIRE_TIME VARCHAR(14) NULL, SCOPE_JSON TEXT NULL,
                  BATCH_STATUS TINYINT NOT NULL, REFUND_LOCKED_BY BIGINT NULL,
                  REFUND_LOCK_TIME VARCHAR(14) NULL,
                  REFUNDED_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_batch_order (ORDER_ID),
                  KEY idx_batch_pick (CARD_ID, BATCH_STATUS, EXPIRE_TIME, CREATE_TIME),
                  KEY idx_batch_payment (PAYMENT_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_entitlement_allocation (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  BATCH_ID BIGINT NOT NULL, CARD_ID BIGINT NOT NULL,
                  FLOW_ID BIGINT NULL, ORDER_ID BIGINT NULL,
                  BIZ_KEY VARCHAR(64) NOT NULL, ALLOC_SEQ INT NOT NULL DEFAULT 1,
                  ALLOC_AMOUNT_FEN BIGINT NOT NULL DEFAULT 0,
                  ALLOC_WATER_ML BIGINT NOT NULL DEFAULT 0,
                  REVERSED_FLAG TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_alloc_biz_batch (BIZ_KEY, BATCH_ID),
                  KEY idx_alloc_batch (BATCH_ID, REVERSED_FLAG),
                  KEY idx_alloc_card (CARD_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        // E2E-08 包B：分账/收益域（列清单与 02-ws-business.sql + settlement-e2e08-a 迁移同源）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_record (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  ORDER_ID BIGINT NOT NULL, RECEIVER_TYPE TINYINT NOT NULL, RECEIVER_USER_ID BIGINT NULL,
                  SPLIT_AMOUNT BIGINT NOT NULL, SPLIT_RATE_SNAP VARCHAR(20) NOT NULL,
                  SPLIT_STATUS TINYINT NOT NULL, WX_SPLIT_NO VARCHAR(64) NULL,
                  SPLIT_TIME VARCHAR(14) NULL, SPLIT_REMARK VARCHAR(500) NULL, REFUND_ID BIGINT NULL,
                  REVERSED_AMOUNT BIGINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_split_order_receiver (ORDER_ID, RECEIVER_TYPE, RECEIVER_USER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_clawback (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  ACTION_ID BIGINT NOT NULL, ORDER_ID BIGINT NOT NULL, SPLIT_ID BIGINT NOT NULL,
                  CLAWBACK_AMOUNT BIGINT NOT NULL, CLAWBACK_STATUS TINYINT NOT NULL,
                  PROCESS_REMARK VARCHAR(500) NULL,
                  UNIQUE KEY uk_split_clawback_action_split (ACTION_ID, SPLIT_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_clawback_action (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT NOT NULL DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(14),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(14),
                  ACTION_ID BIGINT NOT NULL, ORDER_ID BIGINT NOT NULL, ACTION_TYPE TINYINT NOT NULL,
                  REFUND_PRODUCT_FEN BIGINT NOT NULL, OUTBOX_STATUS TINYINT NOT NULL,
                  PROCESS_REMARK VARCHAR(500) NULL,
                  UNIQUE KEY uk_split_clawback_action (ACTION_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_split_config (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  PRODUCT_LINE TINYINT NOT NULL, RECEIVER_TYPE TINYINT NOT NULL,
                  SPLIT_RATE INT NOT NULL, EFFECT_TIME VARCHAR(14) NOT NULL, CONFIG_REMARK VARCHAR(200) NULL,
                  UNIQUE KEY uk_split_config_version (PRODUCT_LINE, RECEIVER_TYPE, EFFECT_TIME)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_income_account (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  USER_ID BIGINT NOT NULL, BALANCE_FEN BIGINT NOT NULL DEFAULT 0,
                  FROZEN_FEN BIGINT NOT NULL DEFAULT 0, CLAWBACK_DEFICIT_FEN BIGINT NOT NULL DEFAULT 0,
                  VERSION INT NOT NULL DEFAULT 1,
                  UNIQUE KEY uk_income_account_user (USER_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_income_flow (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  USER_ID BIGINT NOT NULL, FLOW_TYPE TINYINT NOT NULL,
                  AMOUNT_FEN BIGINT NOT NULL, AFTER_FEN BIGINT NOT NULL,
                  SPLIT_ID BIGINT NULL, ORDER_NO VARCHAR(32) NULL,
                  BIZ_IDEMPOTENCY_KEY VARCHAR(64) NOT NULL, FLOW_REMARK VARCHAR(200) NULL,
                  UNIQUE KEY uk_income_flow_biz_key (BIZ_IDEMPOTENCY_KEY)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_reconcile_task (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  BIZ_DATE VARCHAR(8) NOT NULL, TASK_STATUS TINYINT NOT NULL,
                  CHECK_TOTAL INT NOT NULL DEFAULT 0, DIFF_TOTAL INT NOT NULL DEFAULT 0,
                  TASK_REMARK VARCHAR(500) NULL,
                  UNIQUE KEY uk_reconcile_task_date (BIZ_DATE)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_reconcile_diff (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  TASK_ID BIGINT NOT NULL, BIZ_DATE VARCHAR(8) NOT NULL, DIFF_TYPE TINYINT NOT NULL,
                  CHECK_DIMENSION VARCHAR(30) NOT NULL, BIZ_KEY VARCHAR(64) NOT NULL,
                  EXPECTED_VAL VARCHAR(200) NULL, ACTUAL_VAL VARCHAR(200) NULL, DIFF_REMARK VARCHAR(500) NULL,
                  KEY idx_reconcile_diff_task (TASK_ID)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
    }

    public static void truncateAll(JdbcTemplate jdbc) {
        // 包D 起追加两张批次表：uk_alloc_biz_batch 跨用例复用同一订单号会撞键，
        // 漏清的表现是「上一条用例的分摊把下一条的消费顶掉」，而错因看起来像业务 Bug
        for (String table : new String[]{
                "ws_wechat_notify_outbox",
                "ws_message", "ws_domain_event", "ws_after_sale_action", "ws_delivery_auto_rule",
                "ws_delivery_media", "ws_delivery_exception", "ws_delivery_appeal", "ws_delivery_task",
                "ws_entitlement_allocation", "ws_card_entitlement_batch", "ws_card_member",
                "ws_wallet_flow", "ws_command", "ws_order", "ws_card", "ws_courier", "ws_water_type",
                "ws_station", "ws_user", "api_employee",
                "ws_alarm", "ws_work_order", "ws_command_batch", "ws_device",
                "ws_split_record", "ws_split_clawback", "ws_split_clawback_action",
                "ws_split_config", "ws_income_account", "ws_income_flow",
                "ws_reconcile_task", "ws_reconcile_diff"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }
}
