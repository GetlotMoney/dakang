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
| `up/{deviceNo}/ack` | `{msgId, cmdNo, ackTs}` 指令收到 | `ws_command` 2→3 |
| `up/{deviceNo}/result` | `{msgId, cmdNo, success, actualMl?, finishTs, reason?}` | `ws_command` 3→4/5/7；出水单回填 `ACTUAL_ML` 并触发补偿判断 |
| `up/{deviceNo}/replay` | 断网补传，载荷同上带原 `msgId` | `ws_device_msg` 按 `uk_msg_id` 去重，重复置 4 丢弃 |

### 下行（平台 → 设备）

| 主题 | 载荷要点 | 对应 |
|---|---|---|
| `down/{deviceNo}/cmd` | `{cmdNo, cmdType, payload, ts}`；出水：`{outletNo, waterType, planMl, orderNo}` | `ws_command` 1→2 |

## 状态机与超时

```
ws_command: 1待下发 → 2已下发 → 3已回执 → 4成功 / 5失败 / 7部分完成
                └─(30s无ack)──┴─(120s无result)→ 6超时（定时任务扫描，产生"指令超时"告警）
```

## 平台侧落地位置（开发时对号入座）

- 依赖：`spring-integration-mqtt`（pom 已留注释位）；配置 `mqtt.*`（`application-*.yml`，`mqtt.enabled` 开关，默认 false 不影响无 broker 启动）
- 计划包结构：`com.jbk.serve.mqtt`（`MqttConfig` / `DeviceUplinkHandler` / `CommandPublisher`）+ `serve/service/command`
- 设备模拟器：独立小工具（Node 或 Java main），按本文档主题收 cmd、回 ack/result，用于无物理样机时的协议联调
