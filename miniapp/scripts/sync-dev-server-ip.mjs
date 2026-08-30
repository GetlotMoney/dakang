// 真机联调基址自动校正：每次 dev:mp-weixin 前把 .env.development.local 里的
// VITE_SERVER_BASEURL 校正为本机当前局域网 IP。
//
// 背景：该地址是构建期烧死进产物的，Mac 换网络后 IP 漂移，真机上表现为
// 「服务响应格式异常」（请求打到失效地址）。此故障已复发多次且每次都要人肉排查，
// 故收敛为构建前置钩子——构建产物恒指向构建时刻的真实 IP。
// 只改 IPv4 形态的 host；写 localhost/域名视为有意为之，不动、只提示。
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'

const mode = process.argv[2] || 'development'
const ENV_FILE = path.resolve(process.cwd(), `env/.env.${mode}.local`)
const KEY = 'VITE_SERVER_BASEURL'

// 非局域网地址黑名单：198.18.0.0/15 是代理 TUN 虚拟网卡（本机 Clash 实测占用 198.18.0.1），
// 169.254.x 是链路本地失败回退——两者手机都打不进来，误选即整包白瞎。
const NOT_LAN = /^(?:198\.1[89]\.|169\.254\.)/
const VIRTUAL_NIC = /vethernet|vmware|virtualbox|hyper-v|wsl|default switch/i

function currentLanIp() {
  const nics = os.networkInterfaces()
  const candidates = []
  const names = Object.keys(nics)
  const preferred = process.platform === 'win32'
    ? ['Wi-Fi', 'WLAN', 'Ethernet', '以太网']
    : ['en0', 'en1']
  // 真机必须走物理局域网；Windows 的 VMware/WSL 地址虽然非 internal，手机仍无法访问。
  const orderedNames = [
    ...preferred.filter(name => names.includes(name)),
    ...names.filter(name => !preferred.includes(name) && !VIRTUAL_NIC.test(name)),
    ...names.filter(name => VIRTUAL_NIC.test(name)),
  ]
  for (const name of orderedNames) {
    for (const addr of nics[name] ?? []) {
      if (addr.family === 'IPv4' && !addr.internal && !NOT_LAN.test(addr.address)
        && !candidates.includes(addr.address)) {
        candidates.push(addr.address)
      }
    }
  }
  if (candidates.length > 1) {
    // 多网并存（如 Wi-Fi + 热点）时选不准是常态：把候选全打出来，人能一眼纠偏
    console.log(`[sync-dev-server-ip] 本机有多个局域网地址：${candidates.join(' / ')}，取 ${candidates[0]}；手机连不上时手工改成另一个`)
  }
  return candidates[0] ?? null
}

if (!fs.existsSync(ENV_FILE)) {
  console.log(`[sync-dev-server-ip] 无 .env.${mode}.local（非本机联调档），跳过`)
  process.exit(0)
}

const ip = currentLanIp()
const text = fs.readFileSync(ENV_FILE, 'utf8')
const line = text.split('\n').find(l => l.trim().startsWith(`${KEY}=`))
const host = line?.match(/https?:\/\/([^:/'"]+)/)?.[1]

if (!line || !host) {
  console.warn(`[sync-dev-server-ip] ⚠ 未找到 ${KEY}，跳过`)
}
else if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
  console.log(`[sync-dev-server-ip] 基址 host=${host} 非 IPv4（视为有意配置），不校正`)
}
else if (!ip) {
  console.warn(`[sync-dev-server-ip] ⚠ 本机无局域网 IP（离线？），保留 ${host}——真机将无法连接`)
}
else if (host === ip) {
  console.log(`[sync-dev-server-ip] 基址 IP 未漂移（${ip}）`)
}
else {
  fs.writeFileSync(ENV_FILE, text.replaceAll(host, ip))
  console.log(`[sync-dev-server-ip] ✔ IP 已漂移，基址已校正：${host} → ${ip}`)
}
