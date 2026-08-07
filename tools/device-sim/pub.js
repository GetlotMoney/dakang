/**
 * 一次性上行发布器（E2E-05 包F）：以设备身份向任意上行主题发一条消息后退出。
 * 供验收 runner 按需触发故障/恢复/遥测/补传，不复制 sim.js 的协议逻辑——
 * 本文件只是"传输"，报文内容由调用方给全（msgId/ts 缺省时补齐）。
 *
 * 用法：
 *   node pub.js <deviceNo> <kind> '<payloadJson>'
 *   node pub.js ACC-DEV-0001 status '{"runStatus":3,"faultCode":"E003"}'
 *   node pub.js ACC-DEV-0001 status '{"runStatus":1,"faultCode":""}'
 *   node pub.js ACC-DEV-0001 telemetry '{"tds":35,"rawTds":260,"waterTemp":21,"signal":-66}'
 *   node pub.js ACC-DEV-0001 replay '<与原 result 完全一致的 JSON（含原 msgId）>'
 *
 * 环境变量：BROKER=tcp://localhost:1883
 */
import mqtt from 'mqtt'

const [deviceNo, kind, payloadRaw] = process.argv.slice(2)
if (!deviceNo || !kind || !payloadRaw) {
  console.error('用法：node pub.js <deviceNo> <kind> <payloadJson>')
  process.exit(2)
}
// 验收专用工具：BROKER 必须显式指定。默认值曾指向主 EMQX 1883——
// 审计复核时漏带环境变量，上行打到主环境（被按未建档设备忽略并留痕 7 条审计事件）。
// 主库零接触的防线不能靠"记得带参数"，fail-closed 拒绝执行。
const broker = process.env.BROKER
if (!broker) {
  console.error('拒绝执行：必须显式设置 BROKER（验收环境为 tcp://127.0.0.1:1884），不提供默认值以防误打主环境')
  process.exit(2)
}

const ts = () => {
  const d = new Date(Date.now() + 8 * 3600_000)
  const p = (n, l = 2) => String(n).padStart(l, '0')
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}`
}

const payload = JSON.parse(payloadRaw)
if (payload.msgId === undefined && kind !== 'heartbeat' && kind !== 'telemetry') {
  payload.msgId = `PUB-${deviceNo}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`
}
if (payload.ts === undefined) {
  payload.ts = ts()
}

const client = mqtt.connect(broker, {
  clientId: `${deviceNo}-pub-${Date.now()}-${Math.floor(Math.random() * 1e6)}`,
  username: 'device',
  password: 'device',
})
client.on('connect', () => {
  const topic = `up/${deviceNo}/${kind}`
  client.publish(topic, JSON.stringify(payload), { qos: kind === 'heartbeat' || kind === 'telemetry' ? 0 : 1 }, (err) => {
    if (err) {
      console.error('发布失败：', err.message)
      process.exit(1)
    }
    console.log(`↑ ${topic}`, JSON.stringify(payload))
    client.end(false, {}, () => process.exit(0))
  })
})
client.on('error', (e) => {
  console.error('MQTT 错误：', e.message)
  process.exit(1)
})
