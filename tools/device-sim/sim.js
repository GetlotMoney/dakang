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
 * E2E-05 扩展模式：
 *   reject-ack        ACK 带 ackCode=rejected（平台按设备拒绝转失败）
 *   busy-ack          ACK 带 ackCode=busy（同上）
 *   partial-result    result success=true + partial=true（平台判 7 部分完成，不得显示成功）
 *   result-before-ack 先发 result 再补 ack（乱序：终态后迟到 ACK 只审计不回退）
 *   no-heartbeat      连接但不发心跳（配合离线扫描验证）
 *   hold-dispense     出水指令(1)只回 ACK 不回 result（订单停在出水中，供紧急停止锚定活动链）
 *   lock-model        锁机状态建模：锁(4)/解锁(5)成功后随 status 上报 runStatus 5/1，
 *                     价格同步(8)记录版本号并在 result 回带 priceVersion
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

  // 心跳：立即一次 + 每 30s（no-heartbeat 模式静默——离线扫描场景专用）
  if (!modes.has('no-heartbeat')) {
    up('heartbeat', { ts: ts() })
    setInterval(() => up('heartbeat', { ts: ts() }), 30_000)
  }

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

// 锁机状态建模（lock-model）：锁/解锁成功后设备自身运行状态随之翻转并主动上报
let modeledRunStatus = 1
// 价格同步建模：记录最近一次同步版本，result 回带（平台侧可核对参数落地）
let priceVersion = null

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
  const buildResult = () => {
    const failMode = modes.has('fail-result')
    const result = {
      msgId: nextMsgId(),
      cmdNo,
      success: !failMode,
      finishTs: ts()
    }
    if (failMode) result.reason = '阀门卡滞，执行失败（模拟）'
    if (modes.has('partial-result')) {
      result.success = true
      result.partial = true
      result.reason = '部分出水口执行成功（模拟）'
    }
    // 出水指令附带实际水量；当前模拟器的运维指令不使用该字段。
    if (cmdType === 1) result.actualMl = 4980
    if (cmdType === 3) result.status = { onlineStatus: 1, runStatus: modeledRunStatus }
    if (cmdType === 8 && priceVersion) result.priceVersion = priceVersion
    return result
  }
  const sendAck = () => {
    // ackCode 必填：平台只认非空 accepted 为已受理，缺省不再兼容为接受（fail-open 已移除）
    const ack = { msgId: nextMsgId(), cmdNo, ackTs: ts(), ackCode: 'accepted' }
    if (modes.has('reject-ack')) {
      ack.ackCode = 'rejected'
      ack.reason = '设备拒绝执行（模拟）'
    }
    else if (modes.has('busy-ack')) {
      ack.ackCode = 'busy'
      ack.reason = '设备忙（模拟）'
    }
    client.publish(ackTopic, JSON.stringify(ack), { qos: 1 })
    console.log(`[${ts()}] ↑ ${ackTopic}`, JSON.stringify(ack))
  }

  // 乱序模式：先 result 后 ack（平台终态后迟到 ACK 只审计不回退）
  if (modes.has('result-before-ack')) {
    up('result', buildResult())
    setTimeout(sendAck, 2_000)
    return
  }

  sendAck()
  if (modes.has('reject-ack') || modes.has('busy-ack')) {
    console.log('  （拒绝/忙 ACK：不再回结果，平台应已按失败终态）')
    return
  }

  if (modes.has('dup-ack')) {
    setTimeout(() => {
      const dup = { msgId: nextMsgId(), cmdNo, ackTs: ts(), ackCode: 'accepted' }
      client.publish(ackTopic, JSON.stringify(dup), { qos: 1 })
      console.log(`[${ts()}] ↑ ${ackTopic}（重复 ACK）`, JSON.stringify(dup))
    }, 2_000)
  }

  if (modes.has('no-result')) {
    console.log('  （no-result 模式：不回结果，等平台 120s 判超时）')
    return
  }
  if (modes.has('hold-dispense') && cmdType === 1) {
    console.log('  （hold-dispense 模式：出水指令扣住 result，订单保持出水中）')
    return
  }

  if (cmdType === 8) {
    // payload 在下行报文里已经是**对象**（服务端 downPayload.set("payload", JSONUtil.parse(...))），
    // 对对象调 JSON.parse 会先转成 "[object Object]" 再抛 SyntaxError，被 catch 吞掉后
    // priceVersion 恒为 null —— 于是「价格同步在 result 回带 priceVersion」这条声称验收过的
    // 能力从来没有真正执行过，且看不出任何失败迹象。兼容两种形态。
    try {
      const raw = cmd.payload
      const parsed = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
      priceVersion = parsed.priceVersion || null
    }
    catch { priceVersion = null }
  }

  // 2 秒后回执行结果（wrong-device 模式连 result 一起走错误设备主题——
  // 目标指令必须零推进，只发错 ack 而 result 走对主题等于没模拟错设备）
  setTimeout(() => {
    const result = buildResult()
    if (modes.has('wrong-device')) {
      const wrongTopic = `up/DK-DEV-0002/result`
      client.publish(wrongTopic, JSON.stringify(result), { qos: 1 })
      console.log(`[${ts()}] ↑ ${wrongTopic}（错设备 result）`, JSON.stringify(result))
      return
    }
    up('result', result)

    // 锁机建模：锁/解锁成功后运行状态翻转并主动 status 上报（PC/机主端读到一致投影）
    if (modes.has('lock-model') && result.success && (cmdType === 4 || cmdType === 5)) {
      modeledRunStatus = cmdType === 4 ? 5 : 1
      setTimeout(() => up('status', { msgId: nextMsgId(), runStatus: modeledRunStatus, faultCode: '', ts: ts() }), 500)
    }

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
