-- ============================================================
-- 2026-07-20 收口轮运行库修复迁移 v5（原子事务；固定基线对象正向断言；NULL-safe）
-- 适用：旧种子初始化且已有真实业务数据的运行库；fresh 库执行应零影响（守卫全不命中、invariant 全过）。
-- 执行前必须备份：mysqldump -uroot -p dakang > backup-$(date +%F).sql
-- 用法：docker exec -i dakang-mysql mysql --default-character-set=utf8mb4 -uroot -p'<密码>' dakang < 本文件
-- 验证：./verify-closure-repair.sh（独立临时容器对抗矩阵，禁止在主库构造冲突）
--
-- v5 修正（2026-07-21 封板安全收口）：①固定对象 order#1/order#3/task#1/command#4 均以“恰好一条”正向断言，
-- 禁止缺行时空集通过；②所有可空字段使用 NULL-safe 比较或显式 IS NULL，禁止 SQL 三值逻辑放行；
-- ③task#1 只能从已知旧态（ORDER_ID=2/NULL/孤儿）修到 order#3，若已挂其他有效订单则中止；
-- ④command#4 后置覆盖身份、逻辑状态、执行状态、双向共键、payload/result；⑤command#16/#17 以事故前
-- 备份中的 CMD_NO/创建时间/原订单号锁定身份，禁止固定 ID 误伤其它记录；⑥归档事件按完整 canonical payload 去重并
-- 后置验唯一；⑦字典按业务键总数与内容双重验唯一；⑧一张有效配送订单最多关联一条有效配送任务。
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS closure_repair_20260720;

DELIMITER //
CREATE PROCEDURE closure_repair_20260720()
BEGIN
  DECLARE bad INT DEFAULT 0;

  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  -- ---------- 0) 前置冲突校验 ----------
  -- 0a) ws_order.ID=3 若已存在，必须与 init 种子整行一致，否则中止（禁止覆盖任何既有数据）。
  SELECT COUNT(*) INTO bad
    FROM ws_order
   WHERE ID = 3
     AND NOT (DATA_STATUS <=> 0
              AND CREATE_BY <=> 1
              AND CREATE_TIME <=> '20260710145500'
              AND UPDATE_BY <=> 1
              AND UPDATE_TIME <=> '20260710150000'
              AND ORDER_NO <=> 'WO20260710145500003'
              AND ORDER_TYPE <=> 3
              AND USER_ID <=> 1
              AND STATION_ID <=> 1
              AND DEVICE_ID IS NULL
              AND OUTLET_ID IS NULL
              AND CARD_ID IS NULL
              AND PACKAGE_ID IS NULL
              AND PACKAGE_SNAP IS NULL
              AND PLAN_ML IS NULL
              AND ACTUAL_ML IS NULL
              AND ORDER_AMOUNT <=> 3000
              AND PAY_WAY <=> 1
              AND ORDER_STATUS <=> 2
              AND CMD_ID IS NULL
              AND FINISH_TIME IS NULL
              AND CANCEL_REASON IS NULL);
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：ws_order.ID=3 已存在但与固定种子整行不符，禁止覆盖，请人工核查';
  END IF;

  -- 0b) task#1 只允许已知旧态：ORDER_ID=2、NULL、孤儿，或已正确指向 3。
  -- 已挂其他仍存在的订单属于未知业务关系，必须中止，不能静默覆盖。
  SELECT COUNT(*) INTO bad
    FROM ws_delivery_task t
   WHERE t.ID = 1
     AND (NOT (t.DATA_STATUS <=> 0)
          OR NOT (t.USER_ID <=> 1)
          OR (t.ORDER_ID IS NOT NULL
              AND t.ORDER_ID NOT IN (2, 3)
              AND EXISTS (SELECT 1 FROM ws_order linked WHERE linked.ID = t.ORDER_ID)));
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：ws_delivery_task#1 用户/数据状态异常或已关联其他有效订单，禁止覆盖，请人工核查';
  END IF;

  -- 0c) order#1 是 command#4 的权威反向共键，不属于本迁移可重建对象；缺失或身份错位必须中止。
  SELECT COUNT(*) INTO bad
    FROM ws_order o
   WHERE o.ID = 1
     AND o.DATA_STATUS <=> 0
     AND o.ORDER_NO <=> 'WO20260710130000001'
     AND o.ORDER_TYPE <=> 1
     AND o.USER_ID <=> 1
     AND o.STATION_ID <=> 1
     AND o.DEVICE_ID <=> 1
     AND o.OUTLET_ID <=> 1
     AND o.CARD_ID <=> 1
     AND o.PLAN_ML <=> 5000
     AND o.ACTUAL_ML <=> 5000
     AND o.PAY_WAY <=> 3
     AND o.ORDER_STATUS <=> 4
     AND o.CMD_ID <=> 4;
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：权威订单 ws_order.ID=1 缺失或共键不符，禁止补造指令关联';
  END IF;

  -- 0d) command#4 必须存在且身份键不可错位；仅执行状态、payload、时间和 ORDER_ID 属可修字段。
  SELECT COUNT(*) INTO bad
    FROM ws_command c
   WHERE c.ID = 4
     AND c.DATA_STATUS <=> 0
     AND c.CMD_NO <=> 'CMD-20260710-0004'
     AND c.DEVICE_ID <=> 1
     AND c.CMD_TYPE <=> 1
     AND (c.ORDER_ID IS NULL OR c.ORDER_ID <=> 1);
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：ws_command.ID=4 缺失、身份错位或已关联其他订单，禁止覆盖';
  END IF;

  -- 0e) command#16/#17 是事故前备份可证明的两条孤儿指令；固定 ID 存在时必须先证明不可变身份。
  -- ORDER_ID/CMD_STATUS/UPDATE_TIME/FAIL_REASON 属各迁移版本允许变化的修复字段，不纳入身份判断。
  SELECT COUNT(*) INTO bad
    FROM ws_command c
   WHERE (c.ID = 16
          AND NOT (c.DATA_STATUS <=> 0
                   AND c.CMD_NO <=> 'CMD20260720002636177626'
                   AND c.DEVICE_ID <=> 1
                   AND c.CMD_TYPE <=> 1
                   AND c.CREATE_BY <=> 1
                   AND c.CREATE_TIME <=> '20260720002636'
                   AND c.SENT_TIME <=> '20260720002636'
                   AND c.ACK_TIME IS NULL
                   AND JSON_VALID(c.CMD_PAYLOAD) = 1
                   AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(c.CMD_PAYLOAD), c.CMD_PAYLOAD, '{}'), '$.orderNo')) <=> 'WO202607200CD3C9DAE79C'))
      OR (c.ID = 17
          AND NOT (c.DATA_STATUS <=> 0
                   AND c.CMD_NO <=> 'CMD20260720003002984516'
                   AND c.DEVICE_ID <=> 1
                   AND c.CMD_TYPE <=> 1
                   AND c.CREATE_BY <=> 1
                   AND c.CREATE_TIME <=> '20260720003002'
                   AND c.SENT_TIME <=> '20260720003002'
                   AND c.ACK_TIME IS NULL
                   AND JSON_VALID(c.CMD_PAYLOAD) = 1
                   AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(c.CMD_PAYLOAD), c.CMD_PAYLOAD, '{}'), '$.orderNo')) <=> 'WO202607204FF8C0A91209'));
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：ws_command.ID=16/17 与事故前备份身份不符，禁止按固定 ID 归档';
  END IF;

  -- 0f) order#3 是固定配送订单；若已被 task#1 之外的有效任务占用，不能再把 task#1 绑入形成一单多任务。
  SELECT COUNT(*) INTO bad
    FROM ws_delivery_task t
   WHERE t.DATA_STATUS <=> 0
     AND t.ORDER_ID <=> 3
     AND t.ID <> 1;
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000'
       SET MESSAGE_TEXT = '迁移中止：固定配送订单 order#3 已被其他有效任务关联，禁止形成一单多任务';
  END IF;

  -- ---------- 1) 安全补入订单 3（与 init 种子行完全一致；已存在则跳过） ----------
  INSERT INTO ws_order
    (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ORDER_NO`,
     `ORDER_TYPE`, `USER_ID`, `STATION_ID`, `DEVICE_ID`, `OUTLET_ID`, `CARD_ID`, `PACKAGE_ID`,
     `PACKAGE_SNAP`, `PLAN_ML`, `ACTUAL_ML`, `ORDER_AMOUNT`, `PAY_WAY`, `ORDER_STATUS`,
     `CMD_ID`, `FINISH_TIME`, `CANCEL_REASON`)
  SELECT 3, 0, 1, '20260710145500', 1, '20260710150000', 'WO20260710145500003',
         3, 1, 1, NULL, NULL, NULL, NULL,
         NULL, NULL, NULL, 3000, 1, 2,
         NULL, NULL, NULL
    FROM DUAL
   WHERE NOT EXISTS (SELECT 1 FROM ws_order WHERE ID = 3);

  -- ---------- 2) command#4 完整对齐（身份键 + 订单 1 反向多字段） ----------
  -- 一次 UPDATE 覆盖两形态：①旧种子（ORDER_ID IS NULL）；②半修库（ORDER_ID=1 但时间/操作者/payload 不合规）。
  -- 全列 set 为 fresh 种子终态：SET 不读旧 payload，因此任意畸形/NULL/数值型/嵌套 orderNo 都被整体重写为
  -- 权威值（不依赖 LIKE 或对旧 payload 的 JSON 解析，规避畸形 JSON 报错）；同值重写天然幂等。
  -- WHERE 只用身份键 + 订单 1 反向共键（含 DEVICE_ID/ORDER_TYPE/DATA_STATUS/反向 CMD_ID），不含旧 payload 的 JSON 判断。
  UPDATE ws_command
     SET ORDER_ID = 1,
         CMD_STATUS = 4,
         CMD_PAYLOAD = '{"outletNo":1,"waterType":"纯净水","planMl":5000,"orderNo":"WO20260710130000001"}',
         SENT_TIME = '20260710130000',
         ACK_TIME = '20260710130030',
         FINISH_TIME = '20260710130500',
         RESULT_PAYLOAD = '{"actualMl":5000}',
         FAIL_REASON = NULL,
         CREATE_BY = 1,
         CREATE_TIME = '20260710130000',
         UPDATE_BY = 1,
         UPDATE_TIME = '20260710130500'
   WHERE ID = 4
     AND DATA_STATUS = 0
     AND CMD_NO = 'CMD-20260710-0004'
     AND DEVICE_ID = 1
     AND CMD_TYPE = 1
     AND (ORDER_ID IS NULL OR ORDER_ID = 1)
     AND EXISTS (SELECT 1 FROM ws_order o
                  WHERE o.ID = 1
                    AND o.DATA_STATUS = 0
                    AND o.ORDER_NO = 'WO20260710130000001'
                    AND o.ORDER_TYPE = 1
                    AND o.DEVICE_ID = 1
                    AND o.CMD_ID = 4);

  -- ---------- 3) task#1 关联订单 3（任务侧与订单侧共键都齐备才动） ----------
  UPDATE ws_delivery_task t
     SET t.ORDER_ID = 3,
         t.UPDATE_TIME = '20260710153000'
   WHERE t.ID = 1
     AND t.USER_ID = 1
     AND (t.ORDER_ID = 2
          OR t.ORDER_ID IS NULL
          OR NOT EXISTS (SELECT 1 FROM ws_order o WHERE o.ID = t.ORDER_ID))
     AND EXISTS (SELECT 1 FROM ws_order o
                  WHERE o.ID = 3
                    AND o.DATA_STATUS = 0
                    AND o.ORDER_NO = 'WO20260710145500003'
                    AND o.ORDER_TYPE = 3
                    AND o.USER_ID = 1
                    AND o.ORDER_STATUS = 2);

  -- 3b) 运行库补齐领域事件类型 8。fresh init 已存在时 INSERT IGNORE 零影响。
  INSERT IGNORE INTO api_dict_type(DICT_NAME, DICT_TYPE, DICT_REMARK)
  SELECT '事件类型', '1363', '领域事件类型（n8n白名单订阅源）'
    FROM DUAL
   WHERE NOT EXISTS (SELECT 1 FROM api_dict_type WHERE DICT_TYPE = '1363');
  INSERT IGNORE INTO api_dict_data(DICT_CLASS, DICT_DEFAULT_FLAG, DICT_TYPE, DICT_SORT, DICT_VALUE, DICT_LABEL)
  SELECT NULL, 1, '1363', 8, 8, '指令状态变化'
    FROM DUAL
   WHERE NOT EXISTS (SELECT 1 FROM api_dict_data WHERE DICT_TYPE = '1363' AND DICT_VALUE = 8);

  -- ---------- 4) command#16/#17 归档（弃"超时"语义；状态机内取"执行失败(5)"+原因写明人工归档） ----------
  -- 4a) 首次归档：仍为"已下发(2)"且关联订单不存在的历史真实指令。
  UPDATE ws_command c
     SET c.CMD_STATUS = 5,
         c.FINISH_TIME = '20260720230000',
         c.FAIL_REASON = '人工数据归档(2026-07-20 收口迁移)：关联订单已被历史清库操作删除，原始执行结果不可考；本终态为归档动作，非设备真实执行失败',
         c.ORDER_ID = NULL,
         c.UPDATE_BY = 1,
         c.UPDATE_TIME = '20260720230000'
   WHERE c.ID IN (16, 17)
     AND c.DATA_STATUS = 0
     AND ((c.ID = 16
           AND c.CMD_NO = 'CMD20260720002636177626'
           AND c.CREATE_TIME = '20260720002636'
           AND c.SENT_TIME = '20260720002636')
          OR (c.ID = 17
              AND c.CMD_NO = 'CMD20260720003002984516'
              AND c.CREATE_TIME = '20260720003002'
              AND c.SENT_TIME = '20260720003002'))
     AND c.DEVICE_ID = 1
     AND c.CMD_TYPE = 1
     AND c.CMD_STATUS = 2
     AND (c.ORDER_ID IS NULL
          OR NOT EXISTS (SELECT 1 FROM ws_order o WHERE o.ID = c.ORDER_ID));

  -- 4b) 纠正 v2 遗留：曾被本迁移旧版按"超时(6)"归档的记录改为失败(5)口径（仅限本迁移写入的归档记录）。
  UPDATE ws_command c
     SET c.CMD_STATUS = 5,
         c.FAIL_REASON = '人工数据归档(2026-07-20 收口迁移)：关联订单已被历史清库操作删除，原始执行结果不可考；本终态为归档动作，非设备真实执行失败',
         c.UPDATE_BY = 1,
         c.UPDATE_TIME = '20260720230000'
   WHERE c.ID IN (16, 17)
     AND c.DATA_STATUS = 0
     AND ((c.ID = 16
           AND c.CMD_NO = 'CMD20260720002636177626'
           AND c.CREATE_TIME = '20260720002636'
           AND c.SENT_TIME = '20260720002636')
          OR (c.ID = 17
              AND c.CMD_NO = 'CMD20260720003002984516'
              AND c.CREATE_TIME = '20260720003002'
              AND c.SENT_TIME = '20260720003002'))
     AND c.DEVICE_ID = 1
     AND c.CMD_TYPE = 1
     AND c.CMD_STATUS = 6
     AND c.FAIL_REASON LIKE '%收口迁移归档%';

  -- 4c) 归档审计证据：ws_domain_event（EVENT_TYPE=8 指令状态变化）每条指令一次，防重插入。
  INSERT INTO ws_domain_event
    (`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`,
     `EVENT_TYPE`, `EVENT_KEY`, `EVENT_PAYLOAD`, `ACTOR_ID`, `ACTOR_PORTAL`)
  SELECT 0, 1, '20260720230000', 1, '20260720230000',
         8, c.CMD_NO,
         CONCAT('{"action":"manual-archive","migration":"2026-07-20-closure-repair","cmdId":', c.ID,
                ',"from":"SENT","to":"FAILED","reason":"关联订单已被历史清库删除，归档非设备失败"}'),
         1, 1
    FROM ws_command c
   WHERE c.ID IN (16, 17)
     AND c.CMD_STATUS = 5
     AND c.FAIL_REASON LIKE '%人工数据归档%'
     AND NOT EXISTS (SELECT 1 FROM ws_domain_event e
                      WHERE e.DATA_STATUS <=> 0
                        AND e.EVENT_TYPE <=> 8
                        AND e.EVENT_KEY <=> c.CMD_NO
                        AND e.EVENT_PAYLOAD <=> CONCAT(
                          '{"action":"manual-archive","migration":"2026-07-20-closure-repair","cmdId":', c.ID,
                          ',"from":"SENT","to":"FAILED","reason":"关联订单已被历史清库删除，归档非设备失败"}')
                        AND e.CREATE_BY <=> 1
                        AND e.CREATE_TIME <=> '20260720230000'
                        AND e.UPDATE_BY <=> 1
                        AND e.UPDATE_TIME <=> '20260720230000'
                        AND e.WHITELIST_FLAG <=> 1
                        AND e.CONSUMED_FLAG <=> 1);

  -- ---------- 5) 后置 invariant（任一不成立 → SIGNAL，EXIT HANDLER 整体回滚） ----------
  -- i1) order#3 必须恰好存在一条且整行与固定种子一致。正向计数避免缺行空集通过。
  SELECT COUNT(*) INTO bad
    FROM ws_order o
   WHERE o.ID = 3
     AND o.DATA_STATUS <=> 0
     AND o.CREATE_BY <=> 1
     AND o.CREATE_TIME <=> '20260710145500'
     AND o.UPDATE_BY <=> 1
     AND o.UPDATE_TIME <=> '20260710150000'
     AND o.ORDER_NO <=> 'WO20260710145500003'
     AND o.ORDER_TYPE <=> 3
     AND o.USER_ID <=> 1
     AND o.STATION_ID <=> 1
     AND o.DEVICE_ID IS NULL
     AND o.OUTLET_ID IS NULL
     AND o.CARD_ID IS NULL
     AND o.PACKAGE_ID IS NULL
     AND o.PACKAGE_SNAP IS NULL
     AND o.PLAN_ML IS NULL
     AND o.ACTUAL_ML IS NULL
     AND o.ORDER_AMOUNT <=> 3000
     AND o.PAY_WAY <=> 1
     AND o.ORDER_STATUS <=> 2
     AND o.CMD_ID IS NULL
     AND o.FINISH_TIME IS NULL
     AND o.CANCEL_REASON IS NULL;
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：固定配送订单 order#3 缺失或整行不一致，已回滚';
  END IF;

  -- i2) task#1 必须恰好一条并精确挂 order#3；全表同时不得有订单孤儿。
  SELECT COUNT(*) INTO bad
    FROM ws_delivery_task t
    JOIN ws_order o ON o.ID = t.ORDER_ID
   WHERE t.ID = 1
     AND t.DATA_STATUS <=> 0
     AND t.USER_ID <=> 1
     AND t.ORDER_ID <=> 3
     AND o.ID <=> 3
     AND o.DATA_STATUS <=> 0
     AND o.ORDER_TYPE <=> 3
     AND o.USER_ID <=> t.USER_ID
     AND o.ORDER_STATUS <=> 2;
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：task#1 未精确关联 order#3 或共键不成立，已回滚';
  END IF;
  SELECT COUNT(*) INTO bad
    FROM ws_delivery_task t
   WHERE t.ORDER_ID IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM ws_order o WHERE o.ID = t.ORDER_ID);
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：存在配送任务订单孤儿，已回滚';
  END IF;
  SELECT COUNT(*) INTO bad
    FROM (SELECT t.ORDER_ID
            FROM ws_delivery_task t
           WHERE t.DATA_STATUS <=> 0
             AND t.ORDER_ID IS NOT NULL
           GROUP BY t.ORDER_ID
          HAVING COUNT(*) > 1) duplicated_task_orders;
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：存在一张订单关联多条有效配送任务，已回滚';
  END IF;

  -- i3) order#1 与 command#4 必须正向恰好一条，且身份、状态、双向共键、payload/result 全部精确。
  SELECT COUNT(*) INTO bad
    FROM ws_order o
    JOIN ws_command c ON c.ID = o.CMD_ID AND c.ORDER_ID = o.ID
   WHERE o.ID = 1
     AND o.DATA_STATUS <=> 0
     AND o.ORDER_NO <=> 'WO20260710130000001'
     AND o.ORDER_TYPE <=> 1
     AND o.DEVICE_ID <=> 1
     AND o.OUTLET_ID <=> 1
     AND o.PLAN_ML <=> 5000
     AND o.ACTUAL_ML <=> 5000
     AND o.ORDER_STATUS <=> 4
     AND o.CMD_ID <=> 4
     AND c.ID <=> 4
     AND c.DATA_STATUS <=> 0
     AND c.CMD_NO <=> 'CMD-20260710-0004'
     AND c.DEVICE_ID <=> o.DEVICE_ID
     AND c.CMD_TYPE <=> 1
     AND c.CMD_STATUS <=> 4
     AND c.ORDER_ID <=> o.ID
     AND c.CREATE_BY <=> 1
     AND c.CREATE_TIME <=> '20260710130000'
     AND c.UPDATE_BY <=> 1
     AND c.UPDATE_TIME <=> '20260710130500'
     AND c.SENT_TIME <=> '20260710130000'
     AND c.ACK_TIME <=> '20260710130030'
     AND c.FINISH_TIME <=> '20260710130500'
     AND c.FAIL_REASON IS NULL
     AND JSON_VALID(c.CMD_PAYLOAD) = 1
     AND JSON_TYPE(JSON_EXTRACT(c.CMD_PAYLOAD, '$.orderNo')) = 'STRING'
     AND JSON_UNQUOTE(JSON_EXTRACT(c.CMD_PAYLOAD, '$.orderNo')) <=> o.ORDER_NO
     AND JSON_TYPE(JSON_EXTRACT(c.CMD_PAYLOAD, '$.planMl')) = 'INTEGER'
     AND JSON_EXTRACT(c.CMD_PAYLOAD, '$.planMl') = o.PLAN_ML
     AND JSON_TYPE(JSON_EXTRACT(c.CMD_PAYLOAD, '$.outletNo')) = 'INTEGER'
     AND JSON_EXTRACT(c.CMD_PAYLOAD, '$.outletNo') = o.OUTLET_ID
     AND JSON_VALID(c.RESULT_PAYLOAD) = 1
     AND JSON_TYPE(JSON_EXTRACT(c.RESULT_PAYLOAD, '$.actualMl')) = 'INTEGER'
     AND JSON_EXTRACT(c.RESULT_PAYLOAD, '$.actualMl') = o.ACTUAL_ML;
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：order#1 与 command#4 完整证据共键不成立，已回滚';
  END IF;

  -- i4) 全表出水指令关联必须指向正常取水订单且设备双向一致。
  SELECT COUNT(*) INTO bad
    FROM ws_command c
   WHERE c.CMD_TYPE = 1
     AND c.ORDER_ID IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM ws_order o
                      WHERE o.ID = c.ORDER_ID
                        AND o.DATA_STATUS <=> 0
                        AND o.ORDER_TYPE <=> 1
                        AND o.DEVICE_ID <=> c.DEVICE_ID
                        AND o.CMD_ID <=> c.ID);
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：存在出水指令与订单类型/设备/反向 CMD_ID 不一致，已回滚';
  END IF;

  -- i5) 事件类型 1363 与值 8 的业务键总数及正确内容都必须恰好一条。
  SELECT COUNT(*) INTO bad FROM api_dict_type t WHERE t.DICT_TYPE <=> '1363';
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：事件类型 1363 业务键缺失或重复，已回滚';
  END IF;
  SELECT COUNT(*) INTO bad
    FROM api_dict_type t
   WHERE t.DICT_TYPE <=> '1363'
     AND t.DICT_NAME <=> '事件类型'
     AND t.DICT_REMARK <=> '领域事件类型（n8n白名单订阅源）';
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：事件类型 1363 字典类型缺失、错位或重复，已回滚';
  END IF;
  SELECT COUNT(*) INTO bad
    FROM api_dict_data d
   WHERE d.DICT_TYPE <=> '1363'
     AND d.DICT_VALUE <=> 8;
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：事件类型 8 业务键缺失或重复，已回滚';
  END IF;
  SELECT COUNT(*) INTO bad
    FROM api_dict_data d
   WHERE d.DICT_TYPE <=> '1363'
     AND d.DICT_VALUE <=> 8
     AND d.DICT_LABEL <=> '指令状态变化';
  IF bad <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：事件类型 8 字典缺失或重复，已回滚';
  END IF;

  -- i6) 指令 16/17（存在时）必须仍是已证明身份的记录、处于归档终态 5 且无失效关联。
  SELECT COUNT(*) INTO bad
    FROM ws_command c
   WHERE c.ID IN (16, 17)
     AND (NOT (c.DATA_STATUS <=> 0)
          OR NOT (c.DEVICE_ID <=> 1)
          OR NOT (c.CMD_TYPE <=> 1)
          OR NOT (c.CMD_STATUS <=> 5)
          OR c.ORDER_ID IS NOT NULL
          OR (c.ID = 16 AND NOT (c.CMD_NO <=> 'CMD20260720002636177626'
                                  AND c.CREATE_TIME <=> '20260720002636'
                                  AND c.SENT_TIME <=> '20260720002636'))
          OR (c.ID = 17 AND NOT (c.CMD_NO <=> 'CMD20260720003002984516'
                                  AND c.CREATE_TIME <=> '20260720003002'
                                  AND c.SENT_TIME <=> '20260720003002')));
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：指令 16/17 未处于归档终态或仍带失效关联，已回滚';
  END IF;

  -- i7) 每条实际存在的归档指令必须恰有一条本迁移生成的 canonical 领域事件；伪事件不得替代。
  SELECT COUNT(*) INTO bad
    FROM ws_command c
   WHERE c.ID IN (16, 17)
     AND (SELECT COUNT(*)
            FROM ws_domain_event e
           WHERE e.DATA_STATUS <=> 0
             AND e.EVENT_TYPE <=> 8
             AND e.EVENT_KEY <=> c.CMD_NO
             AND e.EVENT_PAYLOAD <=> CONCAT(
               '{"action":"manual-archive","migration":"2026-07-20-closure-repair","cmdId":', c.ID,
               ',"from":"SENT","to":"FAILED","reason":"关联订单已被历史清库删除，归档非设备失败"}')
             AND e.CREATE_BY <=> 1
             AND e.CREATE_TIME <=> '20260720230000'
             AND e.UPDATE_BY <=> 1
             AND e.UPDATE_TIME <=> '20260720230000'
             AND e.WHITELIST_FLAG <=> 1
             AND e.CONSUMED_FLAG <=> 1) <> 1;
  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invariant 失败：指令 16/17 canonical 归档事件缺失或重复，已回滚';
  END IF;

  COMMIT;
END//
DELIMITER ;

CALL closure_repair_20260720();
DROP PROCEDURE closure_repair_20260720;

-- 说明（不迁移项，留痕）：
-- 运行库钱包流水在 flow#2（ML_AFTER 495000）与 flow#67（起点 480000）之间存在 +5000ml 账实差额
-- （旧种子卡余额 500000 未实际扣减订单 1 的 5000；fresh 种子已改卡=495000 闭合）。
-- 历史流水不重写、不补造；flow#67 起真实业务段 ML_CHANGE/ML_AFTER 与 ws_card.BALANCE_ML 连续一致。
