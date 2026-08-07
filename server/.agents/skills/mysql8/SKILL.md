---
name: mysql8
description: 六维达康 MySQL 8 数据设计规范。设计表、字段、索引、字典、菜单和初始化数据时使用。
---

# 六维达康 MySQL 8 规范

## 强制前置门

先读根目录 `AGENTS.md`、`docs/development-workflow.md`、对应 REQ 与安全铁律。Demo 未验收时，只允许沉淀已授权的字段/契约，不得借建表提前实现资金或设备真实控制。

## 基础规则

1. InnoDB、`utf8mb4`、`utf8mb4_general_ci`。
2. 达康业务表统一 `ws_<domain>`，禁止 `new_` 和其他项目域名。
3. 主键统一 `ID bigint AUTO_INCREMENT`。
4. 每个字段和表必须有中文 COMMENT。
5. 通用字段固定放在前部：`ID`、`DATA_STATUS`、`CREATE_BY`、`CREATE_TIME`、`UPDATE_BY`、`UPDATE_TIME`。
6. 时间按现有项目契约使用 `varchar(14)`，格式 `yyyyMMddHHmmss`。
7. 金额用 `bigint` 分；水量用 `bigint` 毫升；禁止浮点金额。
8. 枚举用 `tinyint` 并注明字典 type。
9. 关键字段 `NOT NULL`；是否允许默认值以现有领域状态机为准，不得用默认值掩盖缺失输入。
10. DDL 使用 `DROP TABLE IF EXISTS` + `CREATE TABLE`。种子、字典、菜单必须幂等，但写法取决于**这条 INSERT 有没有携带能撞上唯一约束的值**：带显式主键 ID 的（菜单、角色绑定、业务测试数据）用 `INSERT IGNORE INTO`；依赖自增主键且表上无业务唯一键的（`api_dict_type`/`api_dict_data`）必须用 `NOT EXISTS`，详见下方「字典与菜单」。

## 命名

| 对象 | 规则 | 示例 |
|---|---|---|
| 表 | 小写下划线 + ws_ 前缀 | `ws_device` |
| 列 | 大写下划线 | `DEVICE_NO` |
| 普通索引 | `idx_<table语义>_<field>` | `idx_device_station` |
| 唯一索引 | `uk_<table语义>_<field>` | `uk_command_cmd_no` |

唯一索引只用于真实业务唯一性与幂等，例如设备编号、命令号、支付业务单号和上行 `msgId`。不能依赖代码查重替代并发安全。

## 建表示例

```sql
DROP TABLE IF EXISTS `ws_device_policy`;
CREATE TABLE `ws_device_policy` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_ID`         bigint       NOT NULL COMMENT '设备ID',
  `POLICY_STATUS`     tinyint      NOT NULL COMMENT '策略状态(10)：1正常 2禁用',
  `HEARTBEAT_TIMEOUT` int          NOT NULL COMMENT '心跳超时秒数',
  `POLICY_REMARK`     varchar(500) COMMENT '策略说明(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_device_policy_device` (`DEVICE_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备运营策略表';
```

这只是格式示例；只有需求和 Demo 契约通过后才允许真的新增该表。

## 字段选择

- 名称/编码：`varchar(20/50/100)`，COMMENT 标明 max。
- 说明：`varchar(500)`；大文本或 JSON：`text`。
- 经纬度沿用当前水站合同的字符串字段，接真地图前再评审精度类型。
- 状态/类型：`tinyint` + 字典编号与值。
- 敏感身份信息：密文列建议 `varchar(512)`，允许为空；禁止把真实证件号放进测试数据。
- JSON 字段必须在 COMMENT 中写结构、owner 和演进边界。

## 索引

- 为高频过滤、关联和排序字段建索引。
- 联合索引按最左匹配和真实查询路径设计。
- 不给低区分度字段单独滥建索引。
- 删除、状态机更新和幂等处理必须能命中主键/唯一键。
- 新索引必须说明对应页面或接口查询。

## 字典与菜单

开发前先检查 `ApiEnum.DictType` 占用号。字典必须同时落 `api_dict_type` 与 `api_dict_data`。

**字典幂等只能用 `NOT EXISTS`，不能用 `INSERT IGNORE`。** 这两张表除 `PRIMARY KEY(ID)` 外没有任何唯一索引，而字典 INSERT 一律不写 ID（走自增），因此 `IGNORE` 无键可撞、等同普通 `INSERT`：`init` 灌一套、迁移再灌一套就翻倍，而字典查询是 `selectJoinOne`，遇重复行直接 TooManyResults，`/api/dict/listByType` 对该编号整个返回 500。本项目已因此踩过两次（1376~1381、1382）。

```sql
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '状态名' AS DICT_NAME, '13xx' AS DICT_TYPE, '说明' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '13xx' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '标签1' AS DICT_LABEL
  UNION ALL SELECT '13xx', 2, 2, '标签2'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);
```

菜单（`api_rbac_menu`）与角色绑定（`api_rbac_role_menu`）的 INSERT 都显式写主键 ID，撞主键即被忽略，**继续用 `INSERT IGNORE INTO`**。

这条规则由 `SchemaParityTest.dictionaryInsertsNeverUseInsertIgnore` 常态看守：扫描
`deploy/mysql/migrations/` 与 `server/sql/`（即所有能对非空库重复执行的 SQL），发现字典表用
`INSERT IGNORE` 即红。`deploy/mysql/init/` 不在扫描范围——它只在空数据卷首启执行一次，跑一次不会翻倍。
测试内的 `GRANDFATHERED_DICT_IGNORE` 是早于该守卫的存量欠账清单，**只减不增**。

菜单类型唯一口径：`1=目录、2=菜单、3=功能点`。PC Demo 最终菜单统一在 `deploy/mysql/init/03-demo-baseline.sql`，禁止在每个领域 SQL 再写一套互相迁移的菜单。

## 测试数据

- 每张新业务表提供 5～10 条贴近达康业务的数据，覆盖主要状态和边界。
- 使用假手机号/不可逆占位密文，不使用真实身份、支付或设备密钥。
- 数据之间要能形成订单→指令→ACK/result→审计等追溯关系。
- 不用“点击按钮推进内存状态”代替外部端事实。

## 资金与设备

- 余额/水量扣减：条件 UPDATE 校验余额 + 流水，同一事务。
- 支付/退款/分账：业务单号唯一索引，回调幂等。
- 设备消息：`MSG_ID` 唯一索引。
- 指令：`CMD_NO` 唯一，必须有 ACK/result/超时终态和审计。
- 机主/渠道归属字段必须支持 Service 层数据范围过滤。

## 初始化与迁移

- 全新环境权威顺序：`deploy/mysql/init/01-base.sql` → `02-ws-business.sql` → `03-demo-baseline.sql`。
- **`server/sql/` 只保存领域表/字典源文件，且【仅限空库】**：其中 9 份 `ws_*.sql` 全部以
  `DROP TABLE IF EXISTS` 开头，对既有库执行会静默删光本域数据、无提示、不可恢复。
  这些文件顶部必带「【仅限空库】」横幅，由 `SchemaParityTest.destructiveDomainSqlDeclaresEmptyDbOnly` 看守。
  最终菜单不在各领域文件重复。
- **变更现有库一律走 `deploy/mysql/migrations/`**，只用 `CREATE TABLE IF NOT EXISTS` /
  `ALTER TABLE ADD` / 幂等 INSERT；禁止 `DROP`、`TRUNCATE`、无 `WHERE` 的 `DELETE`/`UPDATE`
  （`SchemaParityTest.migrationsCarryNoDestructiveDdl` 看守）。执行前先备份，禁止把不可重复 ALTER 混成历史补丁。
- 同一 SQL 在独立验证库执行成功后，才能用于当前环境。
