const { execFileSync } = require('node:child_process')

/** Windows/Linux 通用的验收后端归属证明：容器必须运行，且只把约定容器端口映射到 13340。 */
function verifyAccBackendContainer(name, expectedHostPort) {
  if (!name) {
    throw new Error('验收后端容器名为空')
  }
  const running = execFileSync('docker', ['inspect', '-f', '{{.State.Running}}', name], {
    encoding: 'utf8',
  }).trim()
  if (running !== 'true') {
    throw new Error(`验收后端容器未运行：${name}`)
  }
  const mapping = execFileSync('docker', ['port', name, '13340/tcp'], { encoding: 'utf8' }).trim()
  if (!mapping.split(/\r?\n/).some(line => line.endsWith(`:${expectedHostPort}`))) {
    throw new Error(`验收后端端口映射不符：${mapping}`)
  }
  const id = execFileSync('docker', ['inspect', '-f', '{{.Id}}', name], { encoding: 'utf8' }).trim()
  const command = execFileSync('docker', ['inspect', '-f', '{{json .Config.Cmd}}', name], {
    encoding: 'utf8',
  }).trim()
  return {
    name,
    containerId: id.slice(0, 12),
    hostPort: expectedHostPort,
    monitorEnabled: command.includes('--dakang.device.monitor-enabled=true'),
    shortControlTicket: command.includes('--dakang.device.control-ticket-ttl-seconds=8'),
  }
}

module.exports = { verifyAccBackendContainer }
