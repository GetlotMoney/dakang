-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 水站域（ws_station）
-- 依赖：共用底座表 api_dict_type / api_dict_data / api_rbac_menu（库内已存在）
-- 执行：mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < ws_station.sql
-- 口令不入库：先 `set -a; . .env; set +a` 导出仓库根 .env 的 DAKANG_DB_PASSWORD（模板见 .env.example）
-- 需求映射：需求池「水站管理」（P0/一期必需/MVP:是）
-- ============================================================

-- ----------------------------
-- 水站表
-- ----------------------------
DROP TABLE IF EXISTS `ws_station`;
CREATE TABLE `ws_station` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `STATION_NAME`     varchar(50)  NOT NULL COMMENT '水站名称(max50)',
  `STATION_CODE`     varchar(50)  NOT NULL COMMENT '水站编码(max50)，业务唯一，代码层查重',
  `STATION_REGION`   varchar(50)  NOT NULL COMMENT '所属区域(max50)',
  `STATION_ADDRESS`  varchar(200) NOT NULL COMMENT '详细地址(max200)',
  `STATION_LNG`      varchar(20)  COMMENT '经度(max20)',
  `STATION_LAT`      varchar(20)  COMMENT '纬度(max20)',
  `STATION_STATUS`   tinyint      NOT NULL COMMENT '状态(10)：1正常 2禁用',
  `OWNER_USER_ID`    bigint       COMMENT '机主用户ID（ws_user.ID，机主端数据范围过滤依据）',
  `CHANNEL_USER_ID`  bigint       COMMENT '渠道用户ID（一期仅归属预留，不做渠道端）',
  `STATION_REMARK`   varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_station_region` (`STATION_REGION`),
  INDEX `idx_station_owner` (`OWNER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水站表';

-- ----------------------------
-- 测试数据（贴近联调场景：两个水站，一个绑机主一个未绑）
-- ----------------------------
INSERT IGNORE INTO `ws_station` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `STATION_NAME`, `STATION_CODE`, `STATION_REGION`, `STATION_ADDRESS`, `STATION_LNG`, `STATION_LAT`, `STATION_STATUS`, `OWNER_USER_ID`, `CHANNEL_USER_ID`, `STATION_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', '光谷软件园水站', 'WS-WH-001', '武汉东湖高新区', '光谷软件园 A1 栋一楼大厅', '114.4276', '30.4586', 1, NULL, NULL, '光谷片区主力水站'),
(2, 0, 1, '20260710120000', 1, '20260710120000', '南湖社区水站', 'WS-WH-002', '武汉洪山区', '南湖佰港城北门', '114.3355', '30.4899', 1, NULL, NULL, '第二水站，验证跨站授权范围拦截');

-- ----------------------------

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。
