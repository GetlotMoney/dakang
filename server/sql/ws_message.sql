-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 站内消息域（ws_message，E2E-03 包A / REQ-059）
-- 字典段：1312 站内消息领域、1313 站内消息发送状态
-- 说明：一期只做站内消息（in-app），不接微信订阅消息；配送履约节点
--      （任务生成/接单/离站/送达/签收/申诉）与业务动作同事务落一条，
--      SEND_TIME 与动作时间同源（E2E-03 规则13 同源要求的消息侧）。
-- ============================================================
DROP TABLE IF EXISTS `ws_message`;
CREATE TABLE `ws_message` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `USER_ID`          bigint       NOT NULL COMMENT '收件用户ID（ws_user.ID）；查询必须按会话用户强制过滤（铁律6）',
  `MSG_DOMAIN`       tinyint      NOT NULL COMMENT '消息领域(1312)：1取水 2卡券 3配送 4机主 5系统',
  `MSG_TITLE`        varchar(100) NOT NULL COMMENT '标题(max100)',
  `MSG_CONTENT`      varchar(500) NOT NULL COMMENT '正文(max500)，不得包含明文手机号等敏感信息',
  `MSG_CHANNEL`      tinyint      NOT NULL COMMENT '渠道：1站内（一期固定；微信订阅消息不在本期范围）',
  `SEND_STATUS`      tinyint      NOT NULL COMMENT '发送状态(1313)：1待发送 2发送中 3发送失败 4已送达（站内消息落库即送达=4）',
  `SEND_TIME`        varchar(14)  COMMENT '发送时间（与触发它的业务动作时间同源）',
  `READ_FLAG`        tinyint      NOT NULL DEFAULT 0 COMMENT '已读：0未读 1已读',
  `OBJECT_TYPE`      varchar(20)  COMMENT '关联对象类型(max20)：order/task/appeal/device/service',
  `OBJECT_ID`        varchar(64)  COMMENT '关联对象业务键(max64)：订单号/任务号/申诉ID',
  PRIMARY KEY (`ID`),
  INDEX `idx_message_user_time` (`USER_ID`, `SEND_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内消息表';

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('站内消息领域', '1312', '站内消息业务领域');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 1, 1, '取水');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 2, 2, '卡券');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 3, 3, '配送');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 4, 4, '机主');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 5, 5, '系统');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('站内消息发送状态', '1313', '站内消息发送状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 1, 1, '待发送');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 2, 2, '发送中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 3, 3, '发送失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 4, 4, '已送达');



-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。
