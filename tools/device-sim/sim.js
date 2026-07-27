/**
 * 六维达康设备模拟器（协议 v0.1，docs/mqtt-topics.md）
 *
 * 用法：
 *   node sim.js [deviceNo] [模式...]
 *   node sim.js DK-DEV-0001                  # 正常设备：心跳30s + 遥测60s + 正常回执
 *   node sim.js DK-DEV-0001 no-result        # 只回 ack 不回 result（验证 120s 结果超时）
 *   node sim.js DK-DEV-0001 silent           # 收指令不回任何消息（验证 30s 回执超时）
 *   node sim.js DK-DEV-0001 dup-ack          # 对同一指令回两次 ack（验证重复 ACK 只审计）
 *   node sim.js DK-DEV-0001 wrong-device     # 以 DK-DEV-0002 的身份主题回执（验证错设备 ACK）
 *   node sim.js DK-DEV-0001 replay           # result 额外经 replay 主题重发一次（验证补传去重）
 *   node sim.js DK-DEV-0001 fault            # 附带上报严重故障码 E003（验证故障告警）
 *   node sim.js DK-DEV-0001 fail-result      # 回 ack 后回执行失败（验证 REQ-033 失败结果）
 *
 * 环境变量：BROKER=tcp://localhost:1883
 */
import mqtt from 'mqtt'

const deviceNo = process.argv[2] || 'DK-DEV-0001'
const modes = new Set(process.argv.slice(3))
const broker = process.env.BROKER || 'tcp://localhost:1883'

// 设备上报时间固定用 +8 时区（与服务器 Asia/Shanghai 一致）：
// 开发机为 UTC-7 时本地时区会导致 ackTs/finishTs 与服务器时间差 15h，PC timeline 出现"回执早于下发"倒挂。
const ts = () => {
  const d = new Date(Date.now() + 8 * 3600_000)
  const p = (n, l = 2) => String(n).padStart(l, '0')
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}`
}
let msgSeq = 0
const nextMsgId = () => `SIM-${deviceNo}-${Date.now()}-${++msgSeq}`

const client = mqtt.connect(broker, {
  clientId: deviceNo,
  username: 'device',
  password: 'device',
  keepalive: 30
})

const up = (kind, payload) => {
  const topic = `up/${deviceNo}/${kind}`
  client.publish(topic, JSON.stringify(payload), { qos: kind === 'heartbeat' || kind === 'telemetry' ? 0 : 1 })
  console.log(`[${ts()}] ↑ ${topic}`, JSON.stringify(payload))
}

client.on('connect', () => {
  console.log(`[${ts()}] 已连接 ${broker}，设备=${deviceNo}，模式=${[...modes].join(',') || '正常'}`)
  client.subscribe(`down/${deviceNo}/cmd`, { qos: 1 })

  // 心跳：立即一次 + 每 30s
  up('heartbeat', { ts: ts() })
  setInterval(() => up('heartbeat', { ts: ts() }), 30_000)

  // 遥测：立即一次 + 每 60s
  const telemetry = () =>
    up('telemetry', {
      tds: 30 + Math.floor(Math.random() * 20),
      rawTds: 200 + Math.floor(Math.random() * 100),
      waterTemp: 18 + Math.floor(Math.random() * 8),
      filterLife: [
        { no: 1, restDay: 120, status: 1 },
        { no: 2, restDay: 15, status: 2 },
        { no: 3, restDay: modes.has('fault') ? -3 : 60, status: modes.has('fault') ? 3 : 1 }
      ],
      signal: -60 - Math.floor(Math.random() * 20),
      ts: ts()
    })
  telemetry()
  setInterval(telemetry, 60_000)

  // 故障模式：上报严重故障码（E003 在 ws_fault_dict 种子里为严重+阻断下单）
  if (modes.has('fault')) {
    setTimeout(() => up('status', { msgId: nextMsgId(), runStatus: 3, faultCode: 'E003', ts: ts() }), 3_000)
  }
})

client.on('message', (topic, buf) => {
  const cmd = JSON.parse(buf.toString())
  console.log(`[${ts()}] ↓ ${topic}`, buf.toString())
  const { cmdNo, cmdType } = cmd

  if (modes.has('silent')) {
    console.log('  （silent 模式：不回执，等平台 30s 判超时）')
    return
  }

  // 非目标设备模式：使用其他设备主题发送回执；平台仅记录审计事件，不推进指令状态。
  const ackDevice = modes.has('wrong-device') ? 'DK-DEV-0002' : deviceNo
  const ackTopic = `up/${ackDevice}/ack`
  const ack = { msgId: nextMsgId(), cmdNo, ackTs: ts() }
  client.publish(ackTopic, JSON.stringify(ack), { qos: 1 })
  console.log(`[${ts()}] ↑ ${ackTopic}`, JSON.stringify(ack))

  if (modes.has('dup-ack')) {
    setTimeout(() => {
      const dup = { msgId: nextMsgId(), cmdNo, ackTs: ts() }
      client.publish(ackTopic, JSON.stringify(dup), { qos: 1 })
      console.log(`[${ts()}] ↑ ${ackTopic}（重复 ACK）`, JSON.stringify(dup))
    }, 2_000)
  }

  if (modes.has('no-result')) {
    console.log('  （no-result 模式：不回结果，等平台 120s 判超时）')
    return
  }

  // 2 秒后回执行结果
  setTimeout(() => {
    const failMode = modes.has('fail-result')
    const result = {
      msgId: nextMsgId(),
      cmdNo,
      success: !failMode,
      finishTs: ts()
    }
    if (failMode) result.reason = '阀门卡滞，执行失败（模拟）'
    // 出水指令附带实际水量；当前模拟器的运维指令不使用该字段。
    if (cmdType === 1) result.actualMl = 4980
    if (cmdType === 3) result.status = { onlineStatus: 1, runStatus: 1 }
    up('result', result)

    // 补传模式：同 msgId 经 replay 主题重发（平台应按 uk_msg_id 去重丢弃）
    if (modes.has('replay')) {
      setTimeout(() => {
        const topic2 = `up/${deviceNo}/replay`
        client.publish(topic2, JSON.stringify(result), { qos: 1 })
        console.log(`[${ts()}] ↑ ${topic2}（补传重发，应被去重）`, JSON.stringify(result))
      }, 2_000)
    }
  }, 2_000)
})

client.on('error', (e) => console.error('MQTT 错误：', e.message))
