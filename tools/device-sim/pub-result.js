// 【测试工具 · 不进生产】手工发布 up/{deviceNo}/result，补 sim.js 固定 actualMl=4980 的缺口，
// 供 L1d 结算场景（零出水/不足/足量/fail/重复）精确控制 success/actualMl 验证；与 sim.js 同属 tools/device-sim 测试脚手架。
// 用法：node pub-result.js <deviceNo> <cmdNo> <success:true|false> <actualMl> [msgIdSuffix]
import mqtt from 'mqtt'

const deviceNo = process.argv[2]
const cmdNo = process.argv[3]
const success = process.argv[4] === 'true'
const actualMl = Number(process.argv[5])
const suffix = process.argv[6] || String(Date.now())
const broker = process.env.BROKER || 'tcp://localhost:1883'
const mqttUsername = process.env.DAKANG_MQTT_USERNAME || ''
const mqttPassword = process.env.DAKANG_MQTT_PASSWORD || ''

// 与 sim.js 同口径：finishTs 固定 +8 时区，避免开发机本地时区（如 UTC-7）造成时间倒挂。
const ts = () => {
  const d = new Date(Date.now() + 8 * 3600_000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}`
}

const client = mqtt.connect(broker, {
  clientId: `pub-${deviceNo}-${suffix}`,
  username: mqttUsername,
  password: mqttPassword
})
client.on('connect', () => {
  const payload = { msgId: `MANUAL-${deviceNo}-${suffix}`, cmdNo, success, finishTs: ts() }
  if (!Number.isNaN(actualMl)) payload.actualMl = actualMl
  if (!success) payload.reason = '手工失败结果（结算验证）'
  client.publish(`up/${deviceNo}/result`, JSON.stringify(payload), { qos: 1 }, () => {
    console.log(`published up/${deviceNo}/result`, JSON.stringify(payload))
    client.end()
  })
})
client.on('error', (e) => { console.error('MQTT 错误：', e.message); process.exit(1) })
