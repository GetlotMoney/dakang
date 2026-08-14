-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 用户卡券域（ws_user_card）
-- 表：ws_package / ws_card / ws_card_member / ws_identity_conflict
-- 字典段：1330 套餐状态、1331 卡类型、1332 卡状态、1333 成员授权状态
-- 需求映射：水卡余额与权益校验 / 购卡充值 / 一卡多人授权 /
--          套餐兑换汇率、价格快照与退款折算 / 实体水卡刷卡在线授权（一期预留字段）/
--          设备机组与水卡授权范围模型（SCOPE_JSON 预留）
-- 金额说明：金额一律存"分"（bigint）；水量一律存"毫升"（bigint），避免浮点误差
-- C 端用户主体：复用底座 ws_user（客户态 KH_USER），本域不建用户表
-- ============================================================

-- ----------------------------
-- 套餐表（购卡/充值的商品定义；卡与流水保留套餐快照）
-- ----------------------------
DROP TABLE IF EXISTS `ws_package`;
CREATE TABLE `ws_package` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `PACKAGE_NAME`     varchar(50)  NOT NULL COMMENT '套餐名称(max50)',
  `PAY_AMOUNT`       bigint       NOT NULL COMMENT '售价(分)',
  `WATER_ML`         bigint       NOT NULL COMMENT '兑换水量(毫升)，0=纯余额充值套餐',
  `BONUS_AMOUNT`     bigint       NOT NULL DEFAULT 0 COMMENT '赠送余额(分)',
  `UNIT_PRICE_SNAP`  varchar(20)  NOT NULL COMMENT '折算单价快照(分/升,字符串保留两位)，退款折算与对账依据',
  `SCOPE_JSON`       text         COMMENT '可用范围JSON（水站/机组/设备白名单；空=未配置并默认拒绝；全场需显式scopeType=all）',
  `EXPIRE_DAYS`      int          COMMENT '有效期(天)，空=永久',
  `PACKAGE_STATUS`   tinyint      NOT NULL COMMENT '状态(1330)：1在售 2下架',
  `PACKAGE_REMARK`   varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡套餐表';

-- ----------------------------
-- 水卡表（虚拟卡为主；实体卡通过 CARD_NO/ENTITY_FLAG 支持，在线授权一期预留）
-- ----------------------------
DROP TABLE IF EXISTS `ws_card`;
CREATE TABLE `ws_card` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CARD_NO`          varchar(32)  NOT NULL COMMENT '卡号(max32)，虚拟卡系统生成/实体卡取卡面编号',
  `CARD_TYPE`        tinyint      NOT NULL COMMENT '卡类型(1331)：1虚拟卡 2实体卡',
  `USER_ID`          bigint       NOT NULL COMMENT '持卡用户ID（ws_user.ID，主卡人）',
  `BALANCE_AMOUNT`   bigint       NOT NULL DEFAULT 0 COMMENT '余额(分)。扣减必须原子UPDATE并写ws_wallet_flow流水',
  `BALANCE_ML`       bigint       NOT NULL DEFAULT 0 COMMENT '剩余水量(毫升)。扣减规则同上',
  `PACKAGE_ID`       bigint       COMMENT '最近购买套餐ID',
  `PACKAGE_SNAP`     text         COMMENT '套餐快照JSON（名称/售价/水量/单价），退款折算与申诉对账依据',
  `SCOPE_JSON`       text         COMMENT '可用范围JSON（继承套餐，可后台改；空=未配置并默认拒绝）',
  `EXPIRE_TIME`      varchar(14)  COMMENT '到期时间，空=永久',
  `CARD_STATUS`      tinyint      NOT NULL COMMENT '卡状态(1332)：1正常 2冻结 3已过期 4已注销',
  `CARD_REMARK`      varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：卡号是取水扣费与实体卡授权的资金主键，
  -- 并发开卡重复会造成资金归属错乱，必须由数据库层兜底。
  UNIQUE INDEX `uk_card_no` (`CARD_NO`),
  INDEX `idx_card_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡表';

-- ----------------------------
-- 水卡成员授权表（一卡多人：主卡人授权家庭/企业成员用卡）
-- ----------------------------
DROP TABLE IF EXISTS `ws_card_member`;
CREATE TABLE `ws_card_member` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CARD_ID`          bigint       NOT NULL COMMENT '水卡ID',
  `MEMBER_USER_ID`   bigint       NOT NULL COMMENT '被授权用户ID（ws_user.ID）',
  `MEMBER_NAME`      varchar(50)  COMMENT '成员备注名(max50)，如"爸爸/保洁阿姨"',
  `DAY_LIMIT_ML`     bigint       COMMENT '单日限额(毫升)，空=不限',
  `EFFECTIVE_TIME`   varchar(14)  COMMENT '授权生效时间，空=立即生效',
  `EXPIRE_TIME`      varchar(14)  COMMENT '授权失效时间，空=长期有效',
  `MEMBER_STATUS`    tinyint      NOT NULL COMMENT '授权状态(1333)：1生效 2已解除',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：同一张卡对同一用户至多一条授权记录（不含 DATA_STATUS，
  -- 逻辑删除记录仍占键），重新授权复用原记录；成员取水事务按该键行锁串行化日限额校验。
  UNIQUE INDEX `uk_card_member_user` (`CARD_ID`, `MEMBER_USER_ID`),
  INDEX `idx_member_card` (`CARD_ID`),
  INDEX `idx_member_user` (`MEMBER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡成员授权表';

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('套餐状态', '1330', '水卡套餐上下架');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1330', 1, 1, '在售');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1330', 2, 2, '下架');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('卡类型', '1331', '水卡类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1331', 1, 1, '虚拟卡');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1331', 2, 2, '实体卡');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('卡状态', '1332', '水卡生命周期状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 1, 1, '正常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 2, 2, '冻结');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 3, 3, '已过期');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 4, 4, '已注销');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('成员授权状态', '1333', '一卡多人授权状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1333', 1, 1, '生效');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1333', 2, 2, '已解除');

-- ----------------------------
-- 测试数据（两套餐两卡一授权；USER_ID 依赖 ws_user 测试用户，联调前按实际ID调整）
-- ----------------------------
INSERT IGNORE INTO `ws_package` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `PACKAGE_NAME`, `PAY_AMOUNT`, `WATER_ML`, `BONUS_AMOUNT`, `UNIT_PRICE_SNAP`, `SCOPE_JSON`, `EXPIRE_DAYS`, `PACKAGE_STATUS`, `PACKAGE_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', '100元500升卡', 10000, 500000, 0, '20.00', '{"scopeType":"specified","stationIds":["1"]}', 365, 1, '一期主推：1升=0.2元'),
(2, 0, 1, '20260710120000', 1, '20260710120000', '50元充值(送5元)', 5000, 0, 500, '0', NULL, NULL, 1, '纯余额充值，按出水口单价计费');

INSERT IGNORE INTO `ws_card` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CARD_NO`, `CARD_TYPE`, `USER_ID`, `BALANCE_AMOUNT`, `BALANCE_ML`, `PACKAGE_ID`, `PACKAGE_SNAP`, `SCOPE_JSON`, `EXPIRE_TIME`, `CARD_STATUS`, `CARD_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 'VC20260710000001', 1, 1, 5500, 495000, 1, '{"packageName":"100元500升卡","payAmount":10000,"waterMl":500000,"unitPriceSnap":"20.00"}', '{"scopeType":"specified","stationIds":[1],"stationNames":["光谷软件园水站"],"deviceIds":[1],"deviceNames":["光谷1号机"],"outletIds":[1,2],"outletLabels":["1号口·纯净水","2号口·矿物质水"]}', '20270710120000', 1, '光谷社区主力水卡'),
(2, 0, 1, '20260710120000', 1, '20260710120000', 'PC-8800001', 2, 1, 0, 100000, NULL, NULL, NULL, NULL, 2, '实体卡样例（冻结态，验证拦截）');

INSERT IGNORE INTO `ws_card_member` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CARD_ID`, `MEMBER_USER_ID`, `MEMBER_NAME`, `DAY_LIMIT_ML`, `MEMBER_STATUS`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 1, 4, '赵先生', 20000, 1);

-- ----------------------------

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

-- ----------------------------
-- 账号身份冲突台账（WX-ECO S1）
--
-- 四类身份冲突（openid 已绑他号 / 手机号已绑他微信 / 手机号归属他人 / 并发抢绑落败）
-- 一律 fail-closed 抛异常且零副作用——这部分早就做到了。本表补的是任务书要求的后半句：
-- 「进入可审计的人工处理状态」。此前冲突只给用户一句「请联系客服处理」，系统零留痕，
-- 客服接到电话时手上没有任何信息。
--
-- 不是 outbox：无 Worker、无租约、无重试、无退避。冲突不需要被投递，需要被人逐条裁决。
-- 不存 openid：冲突用「哪两个账号」就能完整表达，复制身份密钥只扩大泄漏面。
-- ACTOR_USER_ID 用 0 哨兵而非 NULL：票据路径冲突时发起方还没有账号，而 MySQL 唯一键
-- 忽略 NULL——用 NULL 会让同一冲突每次重试都插新行，幂等键形同虚设。
-- ----------------------------
DROP TABLE IF EXISTS `ws_identity_conflict`;
CREATE TABLE `ws_identity_conflict` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID（系统留痕恒为0）',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `CONFLICT_KEY`     varchar(120) NOT NULL COMMENT '幂等键 IDC:<类型>:<持有方>:<发起方|0>:<脱敏号>；同一冲突恒一行',
  `CONFLICT_TYPE`    varchar(40)  NOT NULL COMMENT '冲突类型，见 MiniIdentityConflictEnum.Type',
  `OCCUR_SCENE`      varchar(24)  NOT NULL COMMENT '发生场景：LOGIN_BIND登录绑定链 / SELF_BIND已登录自助补绑',
  `HOLDER_USER_ID`   bigint       NOT NULL COMMENT '当前持有该身份的账号(ws_user.ID)——冲突的另一方',
  `ACTOR_USER_ID`    bigint       NOT NULL COMMENT '发起动作的账号(ws_user.ID)；票据路径建号前用0哨兵（NULL不参与唯一约束）',
  `MASKED_PHONE`     varchar(20)  DEFAULT NULL COMMENT '脱敏手机号，仅供人工核对；绝不存明文，更不存 openid',
  `OCCUR_COUNT`      int          NOT NULL DEFAULT 1 COMMENT '同一冲突累计发生次数；用户反复重试只累加不新增行',
  `FIRST_OCCUR_TIME` varchar(14)  NOT NULL COMMENT '首次发生时间',
  `LAST_OCCUR_TIME`  varchar(14)  NOT NULL COMMENT '最近发生时间',
  `HANDLE_STATUS`    tinyint      NOT NULL DEFAULT 1 COMMENT '处理状态：1待处理 2已处理 3已忽略',
  `HANDLE_BY`        bigint       DEFAULT NULL COMMENT '处理人(api_employee.ID)',
  `HANDLE_TIME`      varchar(14)  DEFAULT NULL COMMENT '处理时间',
  `HANDLE_REMARK`    varchar(500) DEFAULT NULL COMMENT '处理说明：怎么裁决的、通知了谁',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_identity_conflict_key` (`CONFLICT_KEY`),
  KEY `idx_identity_conflict_pending` (`HANDLE_STATUS`, `LAST_OCCUR_TIME`),
  KEY `idx_identity_conflict_holder` (`HOLDER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='账号身份冲突台账：fail-closed 后的人工处理面，不是队列也不是 outbox';

-- 不插任何种子：冲突是运行时事实，种子只会让台账一上来就带着假待办。
