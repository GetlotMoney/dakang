# 六维达康 · MQTT 主题规则（v0.1 草案）

> 状态：**待硬件厂协议确认后定稿**（需求 VER-10"硬件接口确认"）。本文档先约定平台侧口径，
> 设备模拟器与后端按此开发；硬件协议到位后在此逐字段对齐，差异处以联调纪要更新。

## 连接约定

| 项 | 约定 |
|---|---|
| Broker | EMQX 5.8（开发 `tcp://localhost:1883`，容器内 `tcp://emqx:1883`） |
| clientId | 设备端 = `DEVICE_NO`（如 `DK-DEV-0001`）；平台 = `dakang-server-{env}` |
| 认证 | 一期用户名/密码（设备侧烧录）；生产关闭匿名，二期升级一机一密 |
| QoS | 指令下行与回执上行 QoS1；心跳/遥测 QoS0 |
| 时间 | 报文内时间一律 `yyyyMMddHHmmss`（东八区），与库内 varchar(14) 一致 |

## 主题设计

### 上行（设备 → 平台，平台以共享订阅消费）

| 主题 | 载荷要点 | 对应表 |
|---|---|---|
| `up/{deviceNo}/heartbeat` | `{ts}` 周期 30s；超时 3 周期判离线 | Redis 设备影子 + `ws_device.LAST_HEARTBEAT` |
| `up/{deviceNo}/status` | `{msgId, runStatus, faultCode?, ts}` 运行状态/故障 | `ws_device_msg(type=1)` + `ws_device` 回写 + 严重故障产告警 |
| `up/{deviceNo}/telemetry` | `{tds, rawTds, waterTemp, filterLife[], signal, ts}` | `ws_device_telemetry` |
| `up/{deviceNo}/ack` | `{msgId, cmdNo, ackTs, ackCode?, reason?}`；`ackCode=rejected/busy` 表示设备拒绝执行，平台直接转 5失败 | `ws_command` 2→3（拒绝时 2→5） |
| `up/{deviceNo}/result` | `{msgId, cmdNo, success, partial?, actualMl?, finishTs, reason?}`；`success=true` 且 `partial=true` 判 7部分完成（绝不显示为成功） | `ws_command` 3→4/5/7；出水单回填 `ACTUAL_ML` 并触发补偿判断 |
| `up/{deviceNo}/replay` | 断网补传，载荷同上带原 `msgId` | `ws_device_msg` 按 `uk_msg_id` 去重，重复置 4 丢弃 |

### 下行（平台 → 设备）

| 主题 | 载荷要点 | 对应 |
|---|---|---|
| `down/{deviceNo}/cmd` | `{cmdNo, cmdType, payload, ts}`；出水：`{outletNo, waterType, planMl, orderNo}`；紧急停止（cmdType=2）：`{orderNo, reason}` 必锚定服务端确认的活动出水订单 | `ws_command` 1→2 |

指令类型（1320）：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启 **8价格同步**。
价格同步为平台内部命令类型（E2E-05）：`payload={priceVersion,...}`，设备模拟器支持并在 result 回带

参数类指令（6参数同步 / 8价格同步）的 payload 结构约束（REQ-213，平台侧恒生效，与厂家无关）：
扁平 JSON 对象、键名 `^[A-Za-z][A-Za-z0-9_]{0,31}$`、值只允许字符串/数字/布尔、
禁止嵌套与 null、键数 ≤32、字符串值 ≤64 字符。已在 `ws_device_param_def` 登记的键
额外按类型/单位/值域/枚举校验；未登记键放行但标记（单调收紧：登记只会更严，永不更松）。
result **成功**（非 partial、非失败）后平台把下发值写入 `ws_device_param` 快照并抬升版本，
ACK 不触发回写——受理不等于生效。业务参数键集待厂家答复 V-12.3，当前仅登记 priceVersion。
`priceVersion`；**厂商映射待外部协议确认**。批量控制（含价格同步/紧急停止）经
`ws_command_batch` 聚合，一台设备一条 `ws_command`（`uk_cmd_batch_device` 防重复展开）。

## 状态机与超时

```
ws_command: 1待下发 → 2已下发 → 3已回执 → 4成功 / 5失败 / 7部分完成
                └─(30s无ack)──┴─(120s无result)→ 6超时（定时任务扫描，产生"指令超时"告警）
```

## 平台侧落地位置（开发时对号入座）

- 依赖：`spring-integration-mqtt`（pom 已留注释位）；配置 `mqtt.*`（`application-*.yml`，`mqtt.enabled` 开关，默认 false 不影响无 broker 启动）
- 计划包结构：`com.jbk.serve.mqtt`（`MqttConfig` / `DeviceUplinkHandler` / `CommandPublisher`）+ `serve/service/command`
- 设备模拟器：独立小工具（Node 或 Java main），按本文档主题收 cmd、回 ack/result，用于无物理样机时的协议联调
