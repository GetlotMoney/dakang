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
10. DDL 使用 `DROP TABLE IF EXISTS` + `CREATE TABLE`；种子、字典、菜单统一 `INSERT IGNORE INTO`。

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

开发前先检查 `ApiEnum.DictType` 占用号。字典必须同时落：

```sql
INSERT IGNORE INTO `api_dict_type` (...);
INSERT IGNORE INTO `api_dict_data` (...);
```

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
- `server/sql/` 只保存领域表/字典源文件；最终菜单不在各领域文件重复。
- 变更现有库必须先备份并单独写迁移 SQL，禁止把不可重复 ALTER 混成历史补丁。
- 同一 SQL 在独立验证库执行成功后，才能用于当前环境。
