package com.jbk.serve.service.delivery.impl;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E2E-03 配送域 DB 测试共享 schema（列/唯一键与 02-ws-business.sql 权威结构对齐；
 * 生成列 ACTIVE_TASK_KEY、uk_order_no / uk_wallet_flow_biz_key / uk_dtask_* 必须
 * 与生产一致——这些唯一键正是被测的幂等与并发防线，缺了断言就退化成口头保证）。
 */
final class DeliveryDbSchema {

    private DeliveryDbSchema() {
    }

    static void createAll(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  CARD_NO VARCHAR(32), CARD_TYPE TINYINT, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  PACKAGE_ID BIGINT NULL, PACKAGE_SNAP MEDIUMTEXT NULL, SCOPE_JSON VARCHAR(2000) NULL,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, CARD_REMARK VARCHAR(255) NULL,
                  ISSUE_ORDER_ID BIGINT NULL
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
                  USER_STATUS TINYINT NULL, DISABLED_FLAG TINYINT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_employee (
                  ID BIGINT PRIMARY KEY AUTO_INCREMENT,
                  DATA_STATUS TINYINT DEFAULT 0, CREATE_BY BIGINT, CREATE_TIME VARCHAR(20),
                  UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
                  LOGIN_NAME VARCHAR(50) NULL, EMPLOYEE_NAME VARCHAR(30) NULL,
                  EMPLOYEE_PHONE VARCHAR(11) NULL, DEPT_ID BIGINT NULL, DISABLED_FLAG TINYINT NULL
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
                  UNIQUE KEY uk_dauto_rule_key (RULE_KEY)
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
    }

    static void truncateAll(JdbcTemplate jdbc) {
        for (String table : new String[]{
                "ws_message", "ws_domain_event", "ws_delivery_auto_rule", "ws_delivery_media",
                "ws_delivery_exception", "ws_delivery_appeal", "ws_delivery_task", "ws_wallet_flow",
                "ws_order", "ws_card", "ws_courier", "ws_water_type", "ws_station",
                "ws_user", "api_employee"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }
}
